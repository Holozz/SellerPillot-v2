from datetime import datetime, timezone
from uuid import uuid4
from sqlalchemy import JSON, DateTime, ForeignKey, Index, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column
from database import Base


def now():
    return datetime.now(timezone.utc).replace(tzinfo=None)


def uid():
    return uuid4().hex


class Membership(Base):
    __tablename__ = 'store_memberships'
    __table_args__ = (UniqueConstraint('store_id', 'user_id'),)
    id: Mapped[int] = mapped_column(primary_key=True)
    store_id: Mapped[int] = mapped_column(ForeignKey('store.id'))
    user_id: Mapped[int] = mapped_column(ForeignKey('users.id'))
    role: Mapped[str] = mapped_column(String(20))
    active: Mapped[bool] = mapped_column(default=True)
    version: Mapped[int] = mapped_column(default=1)


class Job(Base):
    __tablename__ = 'jobs'
    __table_args__ = (UniqueConstraint('store_id', 'kind', 'idempotency_key'), Index('ix_jobs_recovery', 'status', 'lease_until'))
    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=uid)
    store_id: Mapped[int] = mapped_column(ForeignKey('store.id'), index=True)
    user_id: Mapped[int] = mapped_column(ForeignKey('users.id'))
    kind: Mapped[str] = mapped_column(String(24))
    idempotency_key: Mapped[str] = mapped_column(String(100))
    payload: Mapped[dict] = mapped_column(JSON)
    trace_context: Mapped[dict] = mapped_column(JSON, default=dict)
    status: Mapped[str] = mapped_column(String(20), default='queued', index=True)
    token: Mapped[int] = mapped_column(default=0)
    attempt: Mapped[int] = mapped_column(default=0)
    lease_until: Mapped[datetime | None] = mapped_column(DateTime)
    available_at: Mapped[datetime] = mapped_column(DateTime, default=now)
    cancel_requested: Mapped[bool] = mapped_column(default=False)
    result: Mapped[dict | None] = mapped_column(JSON)
    error: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=now)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=now)


class JobAttempt(Base):
    __tablename__ = 'job_attempts'
    __table_args__ = (UniqueConstraint('job_id', 'token'),)
    id: Mapped[int] = mapped_column(primary_key=True)
    job_id: Mapped[str] = mapped_column(ForeignKey('jobs.id'))
    token: Mapped[int] = mapped_column()
    started_at: Mapped[datetime] = mapped_column(DateTime, default=now)
    finished_at: Mapped[datetime | None] = mapped_column(DateTime)
    status: Mapped[str] = mapped_column(String(24), default='running')


class Outbox(Base):
    __tablename__ = 'outbox_events'
    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=uid)
    job_id: Mapped[str] = mapped_column(ForeignKey('jobs.id'), index=True)
    sent_at: Mapped[datetime | None] = mapped_column(DateTime)
    lease_until: Mapped[datetime | None] = mapped_column(DateTime)
    claim: Mapped[str | None] = mapped_column(String(32))
    created_at: Mapped[datetime] = mapped_column(DateTime, default=now)


class RunEvent(Base):
    __tablename__ = 'run_events'
    __table_args__ = (UniqueConstraint('job_id', 'seq'),)
    id: Mapped[int] = mapped_column(primary_key=True)
    job_id: Mapped[str] = mapped_column(ForeignKey('jobs.id'), index=True)
    token: Mapped[int] = mapped_column()
    seq: Mapped[int] = mapped_column()
    kind: Mapped[str] = mapped_column(String(30))
    data: Mapped[dict] = mapped_column(JSON)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=now)


class KnowledgeBase(Base):
    __tablename__ = 'knowledge_bases'
    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=uid)
    store_id: Mapped[int] = mapped_column(ForeignKey('store.id'), index=True)
    name: Mapped[str] = mapped_column(String(160))
    revision: Mapped[int] = mapped_column(default=0)
    visibility_version: Mapped[int] = mapped_column(default=0)
    active_build_id: Mapped[str | None] = mapped_column(String(32))


class Document(Base):
    __tablename__ = 'documents'
    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=uid)
    kb_id: Mapped[str] = mapped_column(ForeignKey('knowledge_bases.id'), index=True)
    store_id: Mapped[int] = mapped_column(ForeignKey('store.id'))
    name: Mapped[str] = mapped_column(String(200))
    active: Mapped[bool] = mapped_column(default=True)
    current_version_id: Mapped[str | None] = mapped_column(String(32))
    created_at: Mapped[datetime] = mapped_column(DateTime, default=now)


class DocumentVersion(Base):
    __tablename__ = 'document_versions'
    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=uid)
    document_id: Mapped[str] = mapped_column(ForeignKey('documents.id'))
    object_key: Mapped[str] = mapped_column(String(300))
    digest: Mapped[str] = mapped_column(String(64))
    filename: Mapped[str] = mapped_column(String(200))
    created_at: Mapped[datetime] = mapped_column(DateTime, default=now)


class IndexBuild(Base):
    __tablename__ = 'index_builds'
    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=uid)
    kb_id: Mapped[str] = mapped_column(ForeignKey('knowledge_bases.id'), index=True)
    job_id: Mapped[str] = mapped_column(ForeignKey('jobs.id'))
    token: Mapped[int] = mapped_column()
    revision: Mapped[int] = mapped_column()
    status: Mapped[str] = mapped_column(String(20), default='building')
    manifest: Mapped[dict] = mapped_column(JSON)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=now)


class Chunk(Base):
    __tablename__ = 'knowledge_chunks'
    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=uid)
    build_id: Mapped[str] = mapped_column(ForeignKey('index_builds.id'), index=True)
    document_id: Mapped[str] = mapped_column(ForeignKey('documents.id'), index=True)
    version_id: Mapped[str] = mapped_column(ForeignKey('document_versions.id'))
    position: Mapped[int] = mapped_column()
    location: Mapped[str] = mapped_column(String(160))
    text: Mapped[str] = mapped_column(Text)


class Proposal(Base):
    __tablename__ = 'action_proposals'
    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=uid)
    store_id: Mapped[int] = mapped_column(ForeignKey('store.id'), index=True)
    product_id: Mapped[int] = mapped_column(ForeignKey('products.id'))
    user_id: Mapped[int] = mapped_column(ForeignKey('users.id'))
    product_version: Mapped[int] = mapped_column()
    original_title: Mapped[str] = mapped_column(String(200))
    proposed_title: Mapped[str] = mapped_column(String(200))
    payload_hash: Mapped[str] = mapped_column(String(64))
    evidence: Mapped[dict] = mapped_column(JSON)
    status: Mapped[str] = mapped_column(String(20), default='pending')
    expires_at: Mapped[datetime] = mapped_column(DateTime)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=now)


class Execution(Base):
    __tablename__ = 'action_executions'
    __table_args__ = (UniqueConstraint('store_id', 'idempotency_key'),)
    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=uid)
    proposal_id: Mapped[str] = mapped_column(ForeignKey('action_proposals.id'), unique=True)
    store_id: Mapped[int] = mapped_column(ForeignKey('store.id'))
    idempotency_key: Mapped[str] = mapped_column(String(100))
    receipt: Mapped[dict] = mapped_column(JSON)


class Audit(Base):
    __tablename__ = 'audit_logs'
    id: Mapped[str] = mapped_column(String(32), primary_key=True, default=uid)
    store_id: Mapped[int] = mapped_column(ForeignKey('store.id'), index=True)
    user_id: Mapped[int] = mapped_column(ForeignKey('users.id'))
    action: Mapped[str] = mapped_column(String(40))
    target: Mapped[str] = mapped_column(String(100))
    detail: Mapped[dict] = mapped_column(JSON)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=now)
