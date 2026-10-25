from datetime import datetime, timezone
from decimal import Decimal

from sqlalchemy import and_, case, func, select
from sqlalchemy.orm import Session

from models import Order, OrderItem, Product
from services import require_owned_store

SALES_STATUSES = ("paid", "shipped")
ORDER_STATUSES = ("pending", "paid", "shipped", "cancelled")


def utc_range(start_at: datetime, end_at: datetime):
    values = []
    for value in (start_at, end_at):
        if not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("开始和结束时间必须包含时区偏移")
        try:
            values.append(value.astimezone(timezone.utc).replace(tzinfo=None))
        except (OverflowError, ValueError):
            raise ValueError("时间超出可转换范围") from None
    start, end = values
    if start >= end:
        raise ValueError("开始时间必须早于结束时间")
    return start, end


def check_page(limit: int, offset: int):
    if type(limit) is not int or not 1 <= limit <= 100:
        raise ValueError("limit 必须是 1～100 的整数")
    if type(offset) is not int or not 0 <= offset <= 2147483647:
        raise ValueError("offset 必须是 0～2147483647 的整数")


def utc_text(value: datetime):
    return value.replace(tzinfo=timezone.utc).isoformat().replace("+00:00", "Z")


def money(value):
    return format(Decimal(value), ".2f")


def context(store, start, end):
    return {"store_id": store.id, "start_at": utc_text(start), "end_at": utc_text(end),
            "interval": "[start_at, end_at)", "data_version": store.data_version}


def list_store_orders(session: Session, *, user_id: int, store_id: int,
                      start_at: datetime, end_at: datetime,
                      status: str | None = None, limit: int = 20, offset: int = 0):
    store = require_owned_store(session, user_id=user_id, store_id=store_id)
    start, end = utc_range(start_at, end_at)
    check_page(limit, offset)
    if status is not None and status not in ORDER_STATUSES:
        raise ValueError("订单状态无效")
    statement = select(Order).where(Order.store_id == store_id,
                                    Order.ordered_at >= start, Order.ordered_at < end)
    if status is not None:
        statement = statement.where(Order.status == status)
    orders = session.scalars(statement.order_by(Order.ordered_at.desc(), Order.id.desc())
                             .offset(offset).limit(limit + 1)).all()
    return {**context(store, start, end), "status_filter": status, "limit": limit, "offset": offset,
            "has_more": len(orders) > limit,
            "items": [{"id": order.id, "external_order_id": order.external_order_id,
                       "ordered_at": utc_text(order.ordered_at), "status": order.status,
                       "currency": order.currency} for order in orders[:limit]]}


def summarize_sales(session: Session, *, user_id: int, store_id: int,
                     start_at: datetime, end_at: datetime):
    store = require_owned_store(session, user_id=user_id, store_id=store_id)
    start, end = utc_range(start_at, end_at)
    statement = (
        select(
            Order.currency.label("currency"),
            # An order with multiple lines must still count as one order.
            func.count(func.distinct(Order.id)).label("order_count"),
            func.coalesce(func.sum(OrderItem.quantity), 0).label("units_sold"),
            func.coalesce(func.sum(OrderItem.quantity * OrderItem.unit_price), Decimal("0.00")).label("gross_sales"),
            func.coalesce(func.sum(OrderItem.refund_amount), Decimal("0.00")).label("refund_amount"),
            func.count(func.distinct(case((OrderItem.id.is_(None), Order.id), else_=None))).label("orders_without_items"),
        )
        .select_from(Order)
        .outerjoin(OrderItem, and_(OrderItem.order_id == Order.id, OrderItem.store_id == Order.store_id))
        .where(Order.store_id == store_id, Order.ordered_at >= start,
               Order.ordered_at < end, Order.status.in_(SALES_STATUSES))
        .group_by(Order.currency)
        .order_by(Order.currency)
    )
    groups = []
    for row in session.execute(statement).mappings().all():
        groups.append({"currency": row["currency"], "order_count": int(row["order_count"]),
                       "units_sold": int(row["units_sold"]), "gross_sales": money(row["gross_sales"]),
                       "refund_amount": money(row["refund_amount"]),
                       "net_sales": money(row["gross_sales"] - row["refund_amount"]),
                       "orders_without_items": int(row["orders_without_items"])})
    return {**context(store, start, end), "included_statuses": list(SALES_STATUSES),
            "has_orders_without_items": any(group["orders_without_items"] > 0 for group in groups),
            "by_currency": groups}


def list_product_sales(session: Session, *, user_id: int, store_id: int,
                        start_at: datetime, end_at: datetime,
                        limit: int = 20, offset: int = 0):
    store = require_owned_store(session, user_id=user_id, store_id=store_id)
    start, end = utc_range(start_at, end_at)
    check_page(limit, offset)
    units = func.sum(OrderItem.quantity).label("units_sold")
    statement = (
        select(Product.id.label("product_id"), Product.sku, Product.title, Order.currency,
               units, func.count(func.distinct(Order.id)).label("order_count"),
               func.sum(OrderItem.quantity * OrderItem.unit_price).label("gross_sales"),
               func.sum(OrderItem.refund_amount).label("refund_amount"))
        .select_from(OrderItem)
        .join(Order, and_(Order.id == OrderItem.order_id, Order.store_id == OrderItem.store_id))
        .join(Product, and_(Product.id == OrderItem.product_id, Product.store_id == OrderItem.store_id))
        .where(OrderItem.store_id == store_id, Order.ordered_at >= start,
               Order.ordered_at < end, Order.status.in_(SALES_STATUSES))
        .group_by(Product.id, Product.sku, Product.title, Order.currency)
        .order_by(units.desc(), Product.id, Order.currency)
        .offset(offset).limit(limit + 1)
    )
    rows = session.execute(statement).mappings().all()
    items = [{"product_id": row["product_id"], "sku": row["sku"], "title": row["title"],
              "currency": row["currency"], "units_sold": int(row["units_sold"]),
              "order_count": int(row["order_count"]), "gross_sales": money(row["gross_sales"]),
              "refund_amount": money(row["refund_amount"]),
              "net_sales": money(row["gross_sales"] - row["refund_amount"])} for row in rows[:limit]]
    return {**context(store, start, end), "included_statuses": list(SALES_STATUSES),
            "limit": limit, "offset": offset, "has_more": len(rows) > limit, "items": items}
