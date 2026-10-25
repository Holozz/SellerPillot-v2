from fastapi import HTTPException
from sqlalchemy import select
from models import Store
from v2.models import Membership

CAPABILITIES = {
    'viewer': {'read'},
    'operator': {'read', 'write', 'knowledge', 'confirm'},
    'owner': {'read', 'write', 'knowledge', 'confirm', 'members'},
}


def authorize(session, user_id, store_id, capability='read'):
    # Preserve a Store freshly loaded by a locking read; a plain refresh under
    # REPEATABLE READ could otherwise overwrite it with the older auth snapshot.
    store = session.get(Store, store_id)
    if not store:
        raise HTTPException(404, '店铺不存在')
    role = 'owner' if store.owner_id == user_id else None
    if not role:
        statement = select(Membership).where(
            Membership.store_id == store_id, Membership.user_id == user_id,
            Membership.active.is_(True)).execution_options(populate_existing=True)
        if capability != 'read': statement = statement.with_for_update()
        membership = session.scalar(statement)
        role = membership.role if membership else None
    if not role:
        raise HTTPException(404, '店铺不存在')
    if capability not in CAPABILITIES[role]:
        raise HTTPException(403, '当前角色无此操作权限')
    return store
