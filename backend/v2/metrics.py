import hashlib
import json
from datetime import datetime, timezone
from decimal import Decimal
from typing import Literal
from pydantic import BaseModel, ConfigDict, Field, model_validator
from sqlalchemy import func, select
from models import Order, OrderItem, Product
from v2.auth import authorize
from v2.settings import redis_client


class QuerySpec(BaseModel):
    model_config = ConfigDict(extra='forbid')
    metric: Literal['sales', 'quantity', 'orders', 'stock'] = 'sales'
    group_by: Literal['currency', 'product'] = 'product'
    start: datetime | None = None
    end: datetime | None = None
    statuses: list[Literal['paid', 'shipped', 'pending', 'cancelled']] = Field(default=['paid', 'shipped'], min_length=1, max_length=4)
    limit: int = Field(default=20, ge=1, le=100)
    threshold: int | None = Field(default=None, ge=0)

    @model_validator(mode='after')
    def check_range(self):
        if self.metric != 'stock':
            if not self.start or not self.end or not self.start.tzinfo or not self.end.tzinfo:
                raise ValueError('经营查询必须提供带时区的起止时间')
            if not 0 < (self.end - self.start).total_seconds() <= 366 * 86400:
                raise ValueError('时间区间必须为 0–366 天')
        return self


def query_metrics(session, user_id, store_id, spec, use_cache=True):
    store = authorize(session, user_id, store_id)
    version = store.data_version
    canonical = json.dumps(spec.model_dump(mode='json'), sort_keys=True)
    key = f'metrics:{store_id}:{version}:v1:' + hashlib.sha256(canonical.encode()).hexdigest()
    cache = redis_client()
    if use_cache:
        try:
            hit = cache.get(key)
            if hit:
                result = json.loads(hit); result['cache_hit'] = True; return result
        except Exception:
            pass  # A cache is optional, not an authority.
    if spec.metric == 'stock':
        statement = select(Product.id.label('product_id'), Product.sku, Product.title, Product.stock,
                           Product.currency).where(Product.store_id == store_id)
        if spec.threshold is not None: statement = statement.where(Product.stock < spec.threshold)
        rows = session.execute(statement.order_by(Product.stock, Product.id).limit(spec.limit)).mappings().all()
    else:
        start = spec.start.astimezone(timezone.utc).replace(tzinfo=None)
        end = spec.end.astimezone(timezone.utc).replace(tzinfo=None)
        filters = [Order.store_id == store_id, Order.ordered_at >= start, Order.ordered_at < end,
                   Order.status.in_(spec.statuses)]
        # COUNT(DISTINCT) prevents JOIN multiplicity from inflating order counts.
        metric = {'sales': func.sum(OrderItem.quantity * OrderItem.unit_price - OrderItem.refund_amount),
                  'quantity': func.sum(OrderItem.quantity), 'orders': func.count(func.distinct(Order.id))}[spec.metric]
        columns = [Order.currency]
        groups = [Order.currency]
        if spec.group_by == 'product':
            columns += [Product.id.label('product_id'), Product.sku, Product.title]
            groups += [Product.id, Product.sku, Product.title]
        if spec.metric == 'orders' and spec.group_by == 'currency':
            statement = select(*columns, metric.label('value')).select_from(Order).outerjoin(OrderItem,
                (OrderItem.order_id == Order.id) & (OrderItem.store_id == store_id))
        else:
            statement = select(*columns, metric.label('value')).select_from(Order).join(OrderItem,
                (OrderItem.order_id == Order.id) & (OrderItem.store_id == store_id)).join(Product,
                (Product.id == OrderItem.product_id) & (Product.store_id == store_id))
        statement = statement.where(*filters).group_by(*groups).order_by(metric.desc(), *groups).limit(spec.limit)
        rows = session.execute(statement).mappings().all()
    result = {'kind': 'fact_card', 'store_id': store_id, 'data_version': version, 'schema_version': 1,
              'as_of': datetime.now(timezone.utc).isoformat(), 'query_spec': spec.model_dump(mode='json'),
              'rows': [{k: str(v) if isinstance(v, Decimal) else v for k, v in row.items()} for row in rows],
              'cache_hit': False, 'interval': '[start,end)', 'sales_definition': 'quantity*unit_price-refund_amount'}
    if use_cache:
        try: cache.setex(key, 120, json.dumps(result, ensure_ascii=False))
        except Exception: pass
    return result
