"""Real service experiments; skipped without explicitly selected isolated test services."""
import json
import os
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from pathlib import Path
import time
from uuid import uuid4
import pytest
from redis import Redis
from sqlalchemy import func,select
from sqlalchemy.orm import Session
from v2.models import Execution,Audit,Job,now
from v2.jobs import enqueue,claim,finish
from models import Product,Store

pytestmark=[pytest.mark.integration,pytest.mark.skipif(not os.getenv('SP_TEST_DATABASE_URL'),reason='需要专用 MySQL 测试库')]


def test_mysql_50_parallel_confirmations(world):
    w=world;c=w['client'];base=w['base'];h=w['headers']
    p=c.post(base+'/proposals',headers=h,json={'product_id':w['pid'],'product_version':1,'title':'Approved title'}).json()
    def send(_):
        response=c.post(base+f'/proposals/{p["id"]}/confirm',headers=h,json={
            'confirmed':True,'payload_hash':p['payload_hash'],'idempotency_key':'parallel-confirm'})
        return {'status':response.status_code,'receipt':response.json()}
    started=time.perf_counter()
    with ThreadPoolExecutor(max_workers=50) as pool:responses=list(pool.map(send,range(50)))
    with Session(w['engine']) as db:
        executions=db.scalar(select(func.count()).select_from(Execution))
        audit=db.scalar(select(func.count()).select_from(Audit).where(Audit.action=='update_product_title'))
        product=db.get(Product,w['pid']);version=product.version
    report={'experiment':'mysql-parallel-approval','requests':50,'responses':responses,
        'effective_executions':executions,'audit_rows':audit,'product_version':version,
        'elapsed_seconds':time.perf_counter()-started,'dialect':w['engine'].dialect.name}
    target=Path('docs/verification/mysql-parallel-approval.json');target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    assert all(r['status']==200 for r in responses),report
    assert executions==audit==1 and version==2
    assert len({json.dumps(r['receipt'],sort_keys=True) for r in responses})==1


def test_mysql_only_one_worker_claims(world):
    w=world
    with Session(w['engine']) as db,db.begin():
        jid=enqueue(db,w['owner'],w['sid'],'agent',{'mode':'offline','message':'race'},'race').id
    def acquire(_):
        with Session(w['engine']) as db,db.begin():return claim(db,jid)
    with ThreadPoolExecutor(max_workers=8) as pool:claimed=list(pool.map(acquire,range(8)))
    assert sum(task is not None for task in claimed)==1


def test_redis_pending_message_can_be_reclaimed(world):
    url=os.getenv('SP_TEST_REDIS_URL')
    if not url:pytest.skip('需要独立 Redis DB 9')
    from urllib.parse import urlsplit
    assert urlsplit(url).path=='/9', '不能向业务 Redis DB 写入故障测试'
    client=Redis.from_url(url,decode_responses=True)
    stream='interview-recovery:'+uuid4().hex;group='workers'
    try:
        client.xgroup_create(stream,group,mkstream=True)
        message_id=client.xadd(stream,{'job_id':'controlled-fault'})
        assert client.xreadgroup(group,'crashed-consumer',{stream:'>'},count=1)
        time.sleep(0.05)
        reclaimed=client.xautoclaim(stream,group,'recovery-consumer',min_idle_time=1,start_id='0-0')
        assert reclaimed[1][0][0]==message_id
        client.xack(stream,group,message_id)
        assert client.xpending(stream,group)['pending']==0
    finally:
        client.delete(stream)
