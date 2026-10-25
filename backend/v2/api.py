import asyncio
import json
from decimal import Decimal
from typing import Literal
from fastapi import APIRouter, Depends, File, Header, HTTPException, Query, UploadFile
from fastapi.encoders import jsonable_encoder
from fastapi.responses import Response, StreamingResponse
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import func, or_, select
from sqlalchemy.orm import Session
from database import get_db, engine
from dependencies import get_current_user
from models import Product, Store, User
from v2.auth import authorize
from v2.models import (Membership, KnowledgeBase, Document, DocumentVersion, IndexBuild, Chunk,
                        Job, JobAttempt, RunEvent, Proposal, Audit, now, uid)
from v2.jobs import enqueue, TERMINAL
from v2.metrics import QuerySpec, query_metrics
from v2.knowledge import upload, retrieve
from v2.actions import create_proposal, confirm
from v2.storage import ObjectStore

router = APIRouter(prefix='/api/v2/stores/{sid}', tags=['SellerPilot v2'])


def record(obj):
    return jsonable_encoder({column.key:getattr(obj,column.key) for column in obj.__table__.columns})


class StrictModel(BaseModel):
    model_config = ConfigDict(extra='forbid', str_strip_whitespace=True)


class Name(StrictModel):
    name: str = Field(min_length=1, max_length=160)


class MemberInput(StrictModel):
    username: str = Field(min_length=3, max_length=50)
    role: Literal['operator','viewer']
    active: bool = True


class SearchInput(StrictModel):
    query: str = Field(min_length=1, max_length=2000)
    strategy: Literal['bm25','vector','hybrid','rerank'] = 'hybrid'
    limit: int = Field(default=5, ge=1, le=20)


class RunInput(StrictModel):
    message: str = Field(min_length=1, max_length=2000)
    kb_id: str | None = Field(default=None, max_length=32)
    mode: Literal['model','offline'] = 'model'
    idempotency_key: str = Field(min_length=1, max_length=100)


class ProductInput(StrictModel):
    sku: str = Field(min_length=3,max_length=30,pattern=r'^[A-Za-z0-9-]+$')
    title: str = Field(min_length=1,max_length=200)
    price: Decimal = Field(ge=0,max_digits=18,decimal_places=2)
    currency: str = Field(default='USD',pattern=r'^[A-Z]{3}$')
    stock: int = Field(default=0,ge=0)


class ProductEdit(StrictModel):
    title: str = Field(min_length=1,max_length=200)
    price: Decimal = Field(ge=0,max_digits=18,decimal_places=2)
    currency: str = Field(pattern=r'^[A-Z]{3}$')
    stock: int = Field(ge=0)
    version: int = Field(ge=1)


class ProposalInput(StrictModel):
    product_id: int = Field(ge=1)
    title: str = Field(min_length=1,max_length=200)
    product_version: int = Field(ge=1)


class ConfirmInput(StrictModel):
    payload_hash: str = Field(pattern=r'^[a-f0-9]{64}$')
    idempotency_key: str = Field(min_length=1,max_length=100)
    confirmed: Literal[True]


@router.get('/members')
def members(sid:int, user=Depends(get_current_user), db:Session=Depends(get_db)):
    authorize(db,user.id,sid,'members')
    return [record(m) | {'username':name} for m,name in db.execute(
        select(Membership,User.username).join(User,User.id==Membership.user_id).where(Membership.store_id==sid))]


@router.put('/members')
def put_member(sid:int, body:MemberInput, user=Depends(get_current_user), db:Session=Depends(get_db)):
    db.scalar(select(Store).where(Store.id==sid).with_for_update())
    store=authorize(db,user.id,sid,'members')
    target=db.scalar(select(User).where(User.username==body.username))
    if not target: raise HTTPException(404,'用户尚未注册')
    if target.id==store.owner_id: raise HTTPException(409,'不能修改店铺所有者权限')
    member=db.scalar(select(Membership).where(Membership.store_id==sid,Membership.user_id==target.id))
    if not member:
        member=Membership(store_id=sid,user_id=target.id,role=body.role,active=body.active); db.add(member)
    else: member.role=body.role; member.active=body.active; member.version+=1
    db.add(Audit(store_id=sid,user_id=user.id,action='membership',target=str(target.id),detail=body.model_dump()))
    db.commit(); return record(member)


@router.get('/products')
def products(sid:int, after:int=0, limit:int=Query(50,ge=1,le=100), user=Depends(get_current_user), db:Session=Depends(get_db)):
    authorize(db,user.id,sid)
    rows=db.scalars(select(Product).where(Product.store_id==sid,Product.id>after).order_by(Product.id).limit(limit+1)).all()
    return {'items':[record(p) for p in rows[:limit]],'next_cursor':rows[limit-1].id if len(rows)>limit else None}


@router.post('/products',status_code=201)
def product_create(sid:int, body:ProductInput, user=Depends(get_current_user), db:Session=Depends(get_db)):
    db.scalar(select(Store).where(Store.id==sid).with_for_update())
    store=authorize(db,user.id,sid,'write')
    p=Product(store_id=sid,**body.model_dump()); db.add(p); store.data_version+=1
    db.commit(); return record(p)


@router.put('/products/{pid}')
def product_edit(sid:int,pid:int,body:ProductEdit,user=Depends(get_current_user),db:Session=Depends(get_db)):
    db.scalar(select(Store).where(Store.id==sid).with_for_update())
    store=authorize(db,user.id,sid,'write')
    p=db.scalar(select(Product).where(Product.id==pid,Product.store_id==sid).with_for_update())
    if not p: raise HTTPException(404,'商品不存在')
    if p.version!=body.version: raise HTTPException(409,'商品版本已变化')
    for key,v in body.model_dump(exclude={'version'}).items(): setattr(p,key,v)
    p.version+=1; store.data_version+=1; db.commit(); return record(p)


@router.post('/metrics/query')
def metrics(sid:int,body:QuerySpec,user=Depends(get_current_user),db:Session=Depends(get_db)):
    return query_metrics(db,user.id,sid,body)


@router.get('/knowledge-bases')
def knowledge_bases(sid:int,user=Depends(get_current_user),db:Session=Depends(get_db)):
    authorize(db,user.id,sid)
    return [record(kb) for kb in db.scalars(select(KnowledgeBase).where(KnowledgeBase.store_id==sid))]


@router.post('/knowledge-bases',status_code=201)
def kb_create(sid:int,body:Name,user=Depends(get_current_user),db:Session=Depends(get_db)):
    authorize(db,user.id,sid,'knowledge'); kb=KnowledgeBase(store_id=sid,name=body.name)
    db.add(kb); db.commit(); return record(kb)


@router.get('/knowledge-bases/{kid}/documents')
def documents(sid:int,kid:str,user=Depends(get_current_user),db:Session=Depends(get_db)):
    authorize(db,user.id,sid)
    return [record(d) for d in db.scalars(select(Document).where(Document.kb_id==kid,Document.store_id==sid))]


@router.post('/knowledge-bases/{kid}/documents',status_code=202)
def document_upload(sid:int,kid:str,file:UploadFile=File(...),document_id:str|None=None,
                    user=Depends(get_current_user),db:Session=Depends(get_db)):
    data=file.file.read(10*1024*1024+1)
    document=upload(db,user.id,sid,kid,file.filename or 'file.txt',data,document_id)
    job=enqueue(db,user.id,sid,'build',{'kb_id':kid},'upload:'+document.current_version_id)
    result={'document':record(document),'job_id':job.id}; db.commit(); return result


@router.post('/knowledge-bases/{kid}/builds',status_code=202)
def kb_rebuild(sid:int,kid:str,user=Depends(get_current_user),db:Session=Depends(get_db)):
    kb=db.get(KnowledgeBase,kid)
    if not kb or kb.store_id!=sid: raise HTTPException(404,'知识库不存在')
    job=enqueue(db,user.id,sid,'build',{'kb_id':kid},uid()); db.commit(); return record(job)


@router.post('/documents/{did}/visibility')
def document_visibility(sid:int,did:str,active:bool,user=Depends(get_current_user),db:Session=Depends(get_db)):
    authorize(db,user.id,sid,'knowledge')
    d=db.get(Document,did)
    if not d or d.store_id!=sid: raise HTTPException(404,'资料不存在')
    kb=db.scalar(select(KnowledgeBase).where(KnowledgeBase.id==d.kb_id).with_for_update())
    d.active=active; kb.visibility_version+=1; kb.revision+=1; db.commit(); return record(d)


@router.get('/documents/{did}/versions')
def document_versions(sid:int,did:str,user=Depends(get_current_user),db:Session=Depends(get_db)):
    authorize(db,user.id,sid)
    d=db.get(Document,did)
    if not d or d.store_id!=sid: raise HTTPException(404,'资料不存在')
    return [record(v) for v in db.scalars(select(DocumentVersion).where(DocumentVersion.document_id==did))]


@router.get('/documents/{did}/versions/{vid}/original')
def original(sid:int,did:str,vid:str,user=Depends(get_current_user),db:Session=Depends(get_db)):
    authorize(db,user.id,sid)
    d=db.get(Document,did); v=db.get(DocumentVersion,vid)
    if not d or d.store_id!=sid or not v or v.document_id!=did: raise HTTPException(404,'资料不存在')
    key=v.object_key; db.rollback()
    return Response(ObjectStore().get(key),media_type='application/octet-stream',headers={'Content-Disposition':'attachment'})


@router.get('/knowledge-bases/{kid}/chunks')
def chunks(sid:int,kid:str,user=Depends(get_current_user),db:Session=Depends(get_db)):
    authorize(db,user.id,sid)
    kb=db.get(KnowledgeBase,kid)
    if not kb or kb.store_id!=sid: raise HTTPException(404,'知识库不存在')
    return [record(c) for c in db.scalars(select(Chunk).join(Document,Document.id==Chunk.document_id)
        .where(Chunk.build_id==kb.active_build_id,Document.active.is_(True),Chunk.version_id==Document.current_version_id))]


@router.post('/knowledge-bases/{kid}/retrieval-tests')
def search(sid:int,kid:str,body:SearchInput,user=Depends(get_current_user),db:Session=Depends(get_db)):
    return retrieve(db,user.id,sid,kid,body.query,body.strategy,body.limit)


@router.post('/runs',status_code=202)
def run_create(sid:int,body:RunInput,user=Depends(get_current_user),db:Session=Depends(get_db)):
    if body.kb_id:
        kb=db.get(KnowledgeBase,body.kb_id)
        if not kb or kb.store_id!=sid: raise HTTPException(404,'知识库不存在')
    job=enqueue(db,user.id,sid,'agent',body.model_dump(exclude={'idempotency_key'}),body.idempotency_key)
    db.commit(); return record(job)


@router.get('/jobs')
def jobs(sid:int,user=Depends(get_current_user),db:Session=Depends(get_db)):
    authorize(db,user.id,sid)
    return [record(j) for j in db.scalars(select(Job).where(Job.store_id==sid).order_by(Job.created_at.desc()).limit(100))]


@router.get('/jobs/{jid}')
@router.get('/runs/{jid}')
def job_get(sid:int,jid:str,user=Depends(get_current_user),db:Session=Depends(get_db)):
    authorize(db,user.id,sid); job=db.get(Job,jid)
    if not job or job.store_id!=sid: raise HTTPException(404,'任务不存在')
    return record(job) | {'attempts':[record(a) for a in db.scalars(select(JobAttempt).where(JobAttempt.job_id==jid))]}


@router.post('/jobs/{jid}/cancel')
def job_cancel(sid:int,jid:str,user=Depends(get_current_user),db:Session=Depends(get_db)):
    authorize(db,user.id,sid,'write')
    job=db.scalar(select(Job).where(Job.id==jid,Job.store_id==sid).with_for_update())
    if not job: raise HTTPException(404,'任务不存在')
    if job.status not in TERMINAL:
        job.cancel_requested=True
        if job.status!='running': job.status='cancelled'
    db.commit(); return record(job)


@router.post('/jobs/{jid}/retry',status_code=202)
def job_retry(sid:int,jid:str,user=Depends(get_current_user),db:Session=Depends(get_db)):
    authorize(db,user.id,sid,'write'); job=db.get(Job,jid)
    if not job or job.store_id!=sid: raise HTTPException(404,'任务不存在')
    if job.status not in ('failed','cancelled'): raise HTTPException(409,'仅可重试失败或取消任务')
    result=enqueue(db,user.id,sid,job.kind,job.payload,'retry:'+jid+':'+uid())
    db.commit(); return record(result)


@router.get('/runs/{jid}/events')
async def events(sid:int,jid:str,after:int=Query(0,ge=0),last_event_id:str|None=Header(None),
                 user=Depends(get_current_user),db:Session=Depends(get_db)):
    authorize(db,user.id,sid); job=db.get(Job,jid)
    if not job or job.store_id!=sid: raise HTTPException(404,'任务不存在')
    user_id=user.id; db.close()
    try: cursor=max(after,int(last_event_id or 0))
    except ValueError: raise HTTPException(422,'无效事件游标')
    async def stream():
        nonlocal cursor
        for _ in range(600):
            with Session(engine) as session:
                try: authorize(session,user_id,sid)
                except HTTPException:
                    yield 'event: revoked\ndata: {}\n\n'; return
                job=session.get(Job,jid)
                rows=session.scalars(select(RunEvent).where(RunEvent.job_id==jid,RunEvent.seq>cursor).order_by(RunEvent.seq).limit(100)).all()
                batch=[record(e) for e in rows]; status=job.status
            for row in batch:
                cursor=row['seq']
                yield f'id: {cursor}\nevent: {row["kind"]}\ndata: {json.dumps(row,ensure_ascii=False)}\n\n'
            if status in TERMINAL and len(batch)<100:
                yield f'event: done\ndata: {json.dumps({"status":status})}\n\n'; return
            if not batch: yield ': heartbeat\n\n'
            await asyncio.sleep(0.5)
    return StreamingResponse(stream(),media_type='text/event-stream',headers={'Cache-Control':'no-cache','X-Accel-Buffering':'no'})


@router.get('/proposals')
def proposals(sid:int,user=Depends(get_current_user),db:Session=Depends(get_db)):
    authorize(db,user.id,sid)
    return [record(p) for p in db.scalars(select(Proposal).where(Proposal.store_id==sid).order_by(Proposal.created_at.desc()).limit(100))]


@router.post('/proposals',status_code=201)
def proposal_create(sid:int,body:ProposalInput,user=Depends(get_current_user),db:Session=Depends(get_db)):
    p=create_proposal(db,user.id,sid,body.product_id,body.title,expected_version=body.product_version)
    db.commit(); return record(p)


@router.post('/proposals/{pid}/confirm')
def proposal_confirm(sid:int,pid:str,body:ConfirmInput,user=Depends(get_current_user),db:Session=Depends(get_db)):
    result=confirm(db,user.id,sid,pid,body.payload_hash,body.idempotency_key); db.commit(); return result


@router.post('/proposals/{pid}/reject')
def proposal_reject(sid:int,pid:str,user=Depends(get_current_user),db:Session=Depends(get_db)):
    authorize(db,user.id,sid,'confirm')
    p=db.scalar(select(Proposal).where(Proposal.id==pid,Proposal.store_id==sid).with_for_update())
    if not p: raise HTTPException(404,'草稿不存在')
    if p.status!='pending': raise HTTPException(409,'草稿已处理')
    p.status='rejected'; db.commit(); return record(p)


@router.get('/audit')
def audit(sid:int,user=Depends(get_current_user),db:Session=Depends(get_db)):
    authorize(db,user.id,sid)
    return [record(a) for a in db.scalars(select(Audit).where(Audit.store_id==sid).order_by(Audit.created_at.desc()).limit(100))]
