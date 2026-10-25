from sqlalchemy.orm import Session
from qdrant_client import QdrantClient
from v2.models import KnowledgeBase, Job, Document, IndexBuild
from v2.jobs import enqueue, claim, finish
from v2.knowledge import upload, build_knowledge, retrieve
from v2.runner import run_agent


def test_knowledge_publish_retrieve_disable_and_replace(world,monkeypatch):
    w=world;client=QdrantClient(':memory:');monkeypatch.setattr('v2.knowledge.qdrant',lambda:client)
    with Session(w['engine']) as db:
        kb=KnowledgeBase(store_id=w['sid'],name='Title rules');db.add(kb);db.commit();kid=kb.id
        d=upload(db,w['owner'],w['sid'],kid,'rules.md','标题规则：ABC-001 标题不得包含 best seller 等排名承诺。\n\n退货规则：破损商品七天内可退。'.encode());did=d.id
        jid=enqueue(db,w['owner'],w['sid'],'build',{'kb_id':kid},'build-1').id
        db.commit()
    with Session(w['engine']) as db,db.begin(): task=claim(db,jid)
    result=build_knowledge(task);finish(jid,task['token'],result=result)
    with Session(w['engine']) as db:
        found=retrieve(db,w['owner'],w['sid'],kid,'ABC-001 标题规则','hybrid')
        assert found['evidence'];assert not found['degraded'];assert found['embedding_backend']=='hash'
        db.get(Document,did).active=False;db.commit()
        assert not retrieve(db,w['owner'],w['sid'],kid,'ABC-001','bm25')['evidence']


def test_agentscope_tool_execution_and_bounded_answer(world,monkeypatch):
    w=world
    with Session(w['engine']) as db,db.begin():
        jid=enqueue(db,w['owner'],w['sid'],'agent',{'message':'read product'},'agent-1').id
    with Session(w['engine']) as db,db.begin(): task=claim(db,jid)
    calls=iter([
        ({'role':'assistant','content':None,'tool_calls':[{'id':'call1','type':'function','function':{'name':'get_product','arguments':'{"product_id":'+str(w['pid'])+'}'}}]}, {'total_tokens':50}),
        ({'role':'assistant','content':'已读取商品资料。'}, {'total_tokens':20})])
    monkeypatch.setattr('v2.runner.chat',lambda *args:next(calls))
    result=run_agent(task)
    assert result['answer']=='已读取商品资料。';assert result['tool_calls']==1
    assert result['usage']==[{'total_tokens':50},{'total_tokens':20}]
    assert finish(jid,task['token'],result=result)


def test_offline_mode_is_explicit_and_sse_replays(world,monkeypatch):
    w=world
    # Do not spend 2 seconds connecting to an intentionally unavailable cache in a unit test.
    class NoCache:
        def get(self,key):return None
        def setex(self,*args):pass
    monkeypatch.setattr('v2.metrics.redis_client',lambda:NoCache())
    body={'message':'check','mode':'offline','idempotency_key':'offline-1'}
    response=w['client'].post(w['base']+'/runs',headers=w['headers'],json=body);jid=response.json()['id']
    with Session(w['engine']) as db,db.begin():task=claim(db,jid)
    result=run_agent(task);finish(jid,task['token'],result=result)
    assert result['mode']=='offline';assert result['usage'] is None
    stream=w['client'].get(w['base']+f'/runs/{jid}/events?after=1',headers=w['headers'])
    assert stream.status_code==200
    assert 'event: done' in stream.text and 'event: fact' in stream.text
    assert 'id: 1\n' not in stream.text
