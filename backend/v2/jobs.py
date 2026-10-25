"""MySQL owns task state; Redis is an at-least-once wake-up transport."""
import json
from datetime import timedelta
from fastapi import HTTPException
from sqlalchemy import and_, func, or_, select, update
from sqlalchemy.orm import Session
from database import engine
from v2.auth import authorize
from v2.models import Job, JobAttempt, Outbox, RunEvent, now, uid
from v2.settings import redis_client

TERMINAL = {'succeeded', 'failed', 'cancelled'}
LEASE_SECONDS = 45


def enqueue(session, user_id, store_id, kind, payload, key):
    authorize(session, user_id, store_id, 'knowledge' if kind == 'build' else 'read')
    from models import Store
    session.scalar(select(Store).where(Store.id == store_id).with_for_update())
    existing = session.scalar(select(Job).where(Job.store_id == store_id, Job.kind == kind,
                                               Job.idempotency_key == key))
    if existing:
        if existing.payload != payload or existing.user_id != user_id:
            raise HTTPException(409, '幂等键与原请求不一致')
        return existing
    pending = len(session.scalars(select(Job.id).where(
        Job.store_id == store_id, Job.status.not_in(TERMINAL)).with_for_update()).all())
    if pending >= 20:
        raise HTTPException(429, '店铺待执行任务过多，请稍后重试')
    from opentelemetry.propagate import inject
    trace_context={}; inject(trace_context)
    job = Job(store_id=store_id, user_id=user_id, kind=kind, payload=payload, idempotency_key=key,trace_context=trace_context)
    session.add(job); session.flush(); session.add(Outbox(job_id=job.id)); session.flush()
    return job


def claim(session, job_id):
    job = session.scalar(select(Job).where(Job.id == job_id).with_for_update())
    if not job or job.status in TERMINAL or job.available_at > now():
        return None
    if job.status == 'running' and job.lease_until and job.lease_until > now():
        return None
    if job.cancel_requested:
        job.status = 'cancelled'; return None
    if job.attempt >= 3:
        job.status = 'failed'; job.error = '已达到最大尝试次数'; return None
    if job.token:
        previous = session.scalar(select(JobAttempt).where(JobAttempt.job_id == job.id,
                                                           JobAttempt.token == job.token))
        if previous and previous.status == 'running':
            previous.status = 'interrupted'; previous.finished_at = now()
        # Never append another stochastic generation to a partially emitted answer.
        partial = session.scalar(select(RunEvent.id).where(RunEvent.job_id == job.id,
                    RunEvent.kind.in_(['answer_delta', 'answer'])).limit(1))
        if job.kind == 'agent' and partial:
            job.status = 'failed'; job.error = '输出已中断，请创建新的运行重试'; return None
    job.status = 'running'; job.attempt += 1; job.token += 1
    job.lease_until = now() + timedelta(seconds=LEASE_SECONDS); job.updated_at = now()
    session.add(JobAttempt(job_id=job.id, token=job.token))
    session.flush()
    return {'id': job.id, 'store_id': job.store_id, 'user_id': job.user_id,
            'kind': job.kind, 'payload': job.payload, 'token': job.token,'trace_context':job.trace_context}


def locked_job(session, job_id, token):
    job = session.scalar(select(Job).where(Job.id == job_id).with_for_update())
    if not job or job.status != 'running' or job.token != token or job.lease_until <= now():
        raise RuntimeError('任务租约已失效')
    if job.cancel_requested:
        raise InterruptedError('任务已取消')
    authorize(session, job.user_id, job.store_id, 'knowledge' if job.kind == 'build' else 'read')
    return job


def heartbeat(job_id, token):
    with Session(engine) as session, session.begin():
        changed = session.execute(update(Job).where(Job.id == job_id, Job.token == token,
            Job.status == 'running', Job.lease_until > now(), Job.cancel_requested.is_(False))
            .values(lease_until=now() + timedelta(seconds=LEASE_SECONDS), updated_at=now())).rowcount
        return changed == 1


def emit(job_id, token, kind, data):
    with Session(engine) as session, session.begin():
        locked_job(session, job_id, token)
        seq = (session.scalar(select(func.max(RunEvent.seq)).where(RunEvent.job_id == job_id)) or 0) + 1
        session.add(RunEvent(job_id=job_id, token=token, seq=seq, kind=kind, data=data))


def finish(job_id, token, result=None, error=None, retryable=False):
    with Session(engine) as session, session.begin():
        job = session.scalar(select(Job).where(Job.id == job_id).with_for_update())
        if not job or job.status != 'running' or job.token != token or job.lease_until <= now():
            return False
        if job.cancel_requested:
            job.status = 'cancelled'
        elif error:
            partial = session.scalar(select(RunEvent.id).where(RunEvent.job_id == job.id,
                RunEvent.kind.in_(['answer_delta', 'answer'])).limit(1))
            retryable = retryable and not partial and job.attempt < 3
            job.status = 'retry_wait' if retryable else 'failed'; job.error = str(error)[:2000]
            job.available_at = now() + timedelta(seconds=2 ** job.attempt)
        else:
            job.status = 'succeeded'; job.result = result; job.error = None
        job.lease_until = None; job.updated_at = now()
        attempt = session.scalar(select(JobAttempt).where(JobAttempt.job_id == job.id, JobAttempt.token == token))
        attempt.status = job.status; attempt.finished_at = now()
        seq = (session.scalar(select(func.max(RunEvent.seq)).where(RunEvent.job_id == job_id)) or 0) + 1
        session.add(RunEvent(job_id=job_id, token=token, seq=seq, kind='status',
                             data={'status': job.status, 'error': job.error}))
        return True


def dispatch_once():
    client = redis_client(queue=True)
    with Session(engine) as session, session.begin():
        row = session.scalar(select(Outbox).where(Outbox.sent_at.is_(None),
            or_(Outbox.lease_until.is_(None), Outbox.lease_until < now()))
            .order_by(Outbox.created_at).with_for_update(skip_locked=True).limit(1))
        if not row: return False
        claim_id = uid(); row.claim = claim_id; row.lease_until = now() + timedelta(seconds=15)
        event_id, job_id = row.id, row.job_id
        kind = session.get(Job, job_id).kind
    client.xadd(f'sellerpilot:{kind}', {'job_id': job_id})
    with Session(engine) as session, session.begin():
        session.execute(update(Outbox).where(Outbox.id == event_id, Outbox.claim == claim_id)
                        .values(sent_at=now(), lease_until=None))
    return True


def reconcile():
    with Session(engine) as session, session.begin():
        jobs = session.scalars(select(Job).where(Job.status.not_in(TERMINAL),
            Job.available_at <= now(), or_(Job.status != 'running', Job.lease_until < now()),
            Job.updated_at < now() - timedelta(seconds=15)).with_for_update(skip_locked=True).limit(100)).all()
        for job in jobs:
            unsent = session.scalar(select(Outbox.id).where(Outbox.job_id == job.id, Outbox.sent_at.is_(None)).limit(1))
            if not unsent:
                session.add(Outbox(job_id=job.id)); job.updated_at = now()
