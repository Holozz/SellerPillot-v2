from sqlalchemy import select
from sqlalchemy.orm import Session

from models import Product, Store


class StoreNotFoundError(Exception):
    """店铺不存在，或当前用户无权访问。"""

# 查询店铺：由于storeid是用户可改的，必须在这里检查是否是用户拥有的店铺
def require_owned_store(
    session: Session,
    *,
    user_id: int,
    store_id: int,
) -> Store:
    statement = select(Store).where(
        Store.id == store_id,
        Store.owner_id == user_id,
    )

    store = session.scalar(statement)

    if store is None:
        raise StoreNotFoundError("店铺不存在")

    return store

def list_low_stock_products(
    session: Session,
    *,
    user_id: int,
    store_id: int,
    threshold: int = 10,
    limit: int = 20,
) -> list[Product]:
    # 普通函数也校验参数，供未来 Agent 直接调用。
    if type(threshold) is not int or threshold < 0:
        raise ValueError("threshold 必须是大于或等于 0 的整数")

    if type(limit) is not int or not 1 <= limit <= 100:
        raise ValueError("limit 必须是 1～100 的整数")

    # 查询商品前，先确认当前用户拥有这个店铺。
    require_owned_store(
        session,
        user_id=user_id,
        store_id=store_id,
    )

    statement = (
        select(Product)
        .where(
            Product.store_id == store_id,
            Product.stock < threshold,
        )
        .order_by(Product.stock.asc(), Product.id.asc())
        .limit(limit)
    )

    return list(session.scalars(statement).all())