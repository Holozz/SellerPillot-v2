from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from decimal import Decimal
import pytest
from sqlalchemy import func, select
from sqlalchemy.orm import Session
from models import Product, Order, OrderItem, Store
from v2.models import Execution, Audit
from v2.metrics import QuerySpec, query_metrics


def test_queryspec_rejects_identity_sql_and_naive_dates():
    for args in ({'sql':'select 1'},{'metric':'profit'},{'metric':'sales','start':'2026-01-01','end':'2026-01-02'}):
        with pytest.raises(ValueError): QuerySpec.model_validate(args)


def test_fact_card_no_join_double_count_and_currency_separation(world):
    w=world
    with Session(w['engine']) as db:
        order=Order(store_id=w['sid'],external_order_id='O1',ordered_at=datetime(2026,1,2),status='paid',currency='USD')
        db.add(order);db.flush()
        db.add_all([OrderItem(store_id=w['sid'],order_id=order.id,product_id=w['pid'],line_no=i,
            quantity=2,unit_price=Decimal('10'),refund_amount=Decimal('1')) for i in (1,2)])
        db.commit()
    spec={'start':'2026-01-01T00:00:00Z','end':'2026-01-03T00:00:00Z','group_by':'currency'}
    with Session(w['engine']) as db:
        sales=query_metrics(db,w['owner'],w['sid'],QuerySpec(**spec),False)
        assert Decimal(sales['rows'][0]['value'])==38
        count=query_metrics(db,w['owner'],w['sid'],QuerySpec(**spec,metric='orders'),False)
        assert count['rows'][0]['value']==1


def test_cross_store_and_rbac(world):
    w=world;c=w['client'];base=w['base'];h=w['headers'];other=w['other_headers']
    assert c.get(base+'/products',headers=other).status_code==404
    assert c.put(base+'/members',headers=h,json={'username':'outsider','role':'viewer'}).status_code==200
    assert c.get(base+'/products',headers=other).status_code==200
    assert c.post(base+'/knowledge-bases',headers=other,json={'name':'x'}).status_code==403
    assert c.put(base+'/members',headers=h,json={'username':'outsider','role':'viewer','active':False}).status_code==200
    assert c.get(base+'/products',headers=other).status_code==404


def test_store_scoped_sku(world):
    w=world
    with Session(w['engine']) as db:
        store=Store(owner_id=w['owner'],name='Second',platform='amazon');db.add(store);db.flush()
        db.add(Product(store_id=store.id,sku='ABC-001',title='Different',price=1,currency='USD'));db.commit()


def test_proposal_idempotence_payload_and_aba(world):
    w=world;c=w['client'];h=w['headers'];base=w['base']
    def draft(title):
        with Session(w['engine']) as db: version=db.get(Product,w['pid']).version
        r=c.post(base+'/proposals',headers=h,json={'product_id':w['pid'],'product_version':version,'title':title})
        assert r.status_code==201,r.text
        return r.json()
    p=draft('New Storage Jar')
    body={'confirmed':True,'payload_hash':p['payload_hash'],'idempotency_key':'confirm-1'}
    one=c.post(base+f'/proposals/{p["id"]}/confirm',headers=h,json=body)
    assert one.status_code==200,one.text
    two=c.post(base+f'/proposals/{p["id"]}/confirm',headers=h,json=body)
    assert one.json()==two.json()
    with Session(w['engine']) as db:
        assert db.scalar(select(func.count()).select_from(Execution))==1
        assert db.scalar(select(func.count()).select_from(Audit).where(Audit.action=='update_product_title'))==1
    stale=draft('Another Title')
    with Session(w['engine']) as db:
        product=db.get(Product,w['pid']);product.title='Intermediate';product.version+=1;db.commit()
        product.title='New Storage Jar';product.version+=1;db.commit()
    response=c.post(base+f'/proposals/{stale["id"]}/confirm',headers=h,json={
        'confirmed':True,'payload_hash':stale['payload_hash'],'idempotency_key':'confirm-2'})
    assert response.status_code==409


def test_idempotency_key_cannot_change_run_payload(world):
    w=world;c=w['client'];h=w['headers'];url=w['base']+'/runs'
    body={'message':'first','mode':'offline','idempotency_key':'same'}
    first=c.post(url,headers=h,json=body);second=c.post(url,headers=h,json=body)
    assert first.status_code==second.status_code==202
    assert first.json()['id']==second.json()['id']
    assert c.post(url,headers=h,json={**body,'message':'different'}).status_code==409
