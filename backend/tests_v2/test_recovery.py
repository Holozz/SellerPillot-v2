from datetime import timedelta
import pytest
from sqlalchemy import func, select
from sqlalchemy.orm import Session
from v2.jobs import enqueue, claim, finish, locked_job, emit, dispatch_once
from v2.models import Job, Outbox, RunEvent, now


def make_job(w):
    with Session(w['engine']) as db,db.begin():
        return enqueue(db,w['owner'],w['sid'],'agent',{'message':'hello','mode':'offline'},'job-key').id


def test_lease_fences_old_worker(world):
    w=world;jid=make_job(w)
    with Session(w['engine']) as db,db.begin(): first=claim(db,jid)
    with Session(w['engine']) as db,db.begin(): assert claim(db,jid) is None
    with Session(w['engine']) as db,db.begin(): db.get(Job,jid).lease_until=now()-timedelta(seconds=1)
    with Session(w['engine']) as db,db.begin(): second=claim(db,jid)
    assert second['token']>first['token']
    assert finish(jid,first['token'],result={'wrong':True}) is False
    assert finish(jid,second['token'],result={'correct':True}) is True
    with Session(w['engine']) as db: assert db.get(Job,jid).result=={'correct':True}


def test_cancel_prevents_commit(world):
    w=world;jid=make_job(w)
    with Session(w['engine']) as db,db.begin(): task=claim(db,jid)
    with Session(w['engine']) as db,db.begin(): db.get(Job,jid).cancel_requested=True
    with Session(w['engine']) as db,db.begin():
        with pytest.raises(InterruptedError): locked_job(db,jid,task['token'])
    finish(jid,task['token'],result={'should_not_be_published':True})
    with Session(w['engine']) as db: assert db.get(Job,jid).status=='cancelled'


def test_persisted_events_and_partial_output_not_replayed(world):
    w=world;jid=make_job(w)
    with Session(w['engine']) as db,db.begin(): task=claim(db,jid)
    emit(jid,task['token'],'answer_delta',{'text':'partial'})
    with Session(w['engine']) as db,db.begin(): db.get(Job,jid).lease_until=now()-timedelta(seconds=1)
    with Session(w['engine']) as db,db.begin(): assert claim(db,jid) is None
    with Session(w['engine']) as db:
        assert db.get(Job,jid).status=='failed'
        assert db.scalar(select(func.count()).select_from(RunEvent))==1


def test_committed_outbox_repeated_delivery(world,monkeypatch):
    w=world;jid=make_job(w);messages=[]
    class Transport:
        def xadd(self,stream,payload): messages.append((stream,payload));return '1-0'
    monkeypatch.setattr('v2.jobs.redis_client',lambda queue=False:Transport())
    assert dispatch_once()
    # Simulate XADD succeeded, but sender's DB acknowledgement was lost.
    with Session(w['engine']) as db,db.begin():
        row=db.scalar(select(Outbox));row.sent_at=None;row.lease_until=None
    assert dispatch_once();assert len(messages)==2
    with Session(w['engine']) as db,db.begin(): task=claim(db,jid)
    finish(jid,task['token'],result={'one':True})
    with Session(w['engine']) as db,db.begin(): assert claim(db,jid) is None
