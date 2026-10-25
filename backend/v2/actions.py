import hashlib
import json
from datetime import timedelta
from fastapi import HTTPException
from sqlalchemy import select
from models import Product
from v2.auth import authorize
from v2.models import Proposal, Execution, Audit, now


def proposal_hash(product_id, version, title):
    return hashlib.sha256(json.dumps([product_id, version, title], ensure_ascii=False).encode()).hexdigest()


def create_proposal(session, user_id, store_id, product_id, title, evidence=None, expected_version=None):
    authorize(session, user_id, store_id, 'write')
    product = session.scalar(select(Product).where(Product.id == product_id, Product.store_id == store_id))
    if not product:
        raise HTTPException(404, '商品不存在')
    if expected_version is not None and product.version != expected_version:
        raise HTTPException(409, '生成建议期间商品已变化')
    if not title.strip() or len(title) > 200 or title == product.title:
        raise HTTPException(422, '标题应有变化且在 1–200 字符内')
    proposal = Proposal(store_id=store_id, product_id=product_id, user_id=user_id,
        product_version=product.version, original_title=product.title, proposed_title=title.strip(),
        payload_hash=proposal_hash(product_id, product.version, title.strip()),
        evidence=evidence or {}, expires_at=now() + timedelta(days=1))
    session.add(proposal); session.flush()
    return proposal


def confirm(session, user_id, store_id, proposal_id, payload_hash, key):
    # All product writers lock Store before Product. Membership revocation also locks Store.
    from models import Store
    session.scalar(select(Store).where(Store.id == store_id).with_for_update())
    store = authorize(session, user_id, store_id, 'confirm')
    proposal = session.scalar(select(Proposal).where(Proposal.id == proposal_id,
        Proposal.store_id == store_id).with_for_update().execution_options(populate_existing=True))
    if not proposal:
        raise HTTPException(404, '草稿不存在')
    if payload_hash != proposal.payload_hash:
        raise HTTPException(409, '确认内容与草稿不一致')
    existing_key = session.scalar(select(Execution).where(Execution.store_id == store_id,
                                                        Execution.idempotency_key == key))
    if existing_key and existing_key.proposal_id != proposal.id:
        raise HTTPException(409, '幂等键已用于其他动作')
    if proposal.status == 'applied':
        return session.scalar(select(Execution).where(Execution.proposal_id == proposal.id)).receipt
    if proposal.status != 'pending' or proposal.expires_at <= now():
        raise HTTPException(409, '草稿状态无效或已过期')
    product = session.scalar(select(Product).where(Product.id == proposal.product_id,
        Product.store_id == store_id).with_for_update().execution_options(populate_existing=True))
    if not product or product.version != proposal.product_version:
        raise HTTPException(409, '商品版本冲突，请重新生成草稿')
    product.title = proposal.proposed_title; product.version += 1; store.data_version += 1
    proposal.status = 'applied'
    receipt = {'proposal_id': proposal.id, 'product_id': product.id, 'title': product.title,
               'product_version': product.version, 'applied_by': user_id, 'applied_at': now().isoformat() + 'Z'}
    session.add(Execution(proposal_id=proposal.id, store_id=store_id, idempotency_key=key, receipt=receipt))
    session.add(Audit(store_id=store_id, user_id=user_id, action='update_product_title',
                      target=str(product.id), detail=receipt))
    session.flush()
    return receipt
