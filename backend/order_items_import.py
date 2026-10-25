import csv
import hashlib
import io
from decimal import Decimal

from pydantic import BaseModel, ConfigDict, Field, ValidationError, ValidationInfo, field_validator
from sqlalchemy import select
from sqlalchemy.exc import DBAPIError
from sqlalchemy.orm import Session

from imports import MAX_FILE_BYTES, MAX_ROWS, CsvImportError, ImportConflictError
from models import ImportJob, Order, OrderItem, Product, Store
from services import StoreNotFoundError, require_owned_store

ITEM_COLUMNS = ["external_order_id", "line_no", "sku", "quantity", "unit_price", "refund_amount"]


class OrderItemCsvRow(BaseModel):
    model_config = ConfigDict(str_strip_whitespace=True, extra="forbid")

    external_order_id: str = Field(min_length=1, max_length=100)
    line_no: int = Field(ge=1, le=2147483647)
    sku: str = Field(min_length=3, max_length=30, pattern=r"^[A-Za-z0-9-]+$")
    quantity: int = Field(ge=1, le=2147483647)
    unit_price: Decimal = Field(ge=0, max_digits=18, decimal_places=2)
    refund_amount: Decimal = Field(ge=0, max_digits=18, decimal_places=2)

    @field_validator("refund_amount")
    @classmethod
    def check_refund(cls, value: Decimal, info: ValidationInfo):
        quantity = info.data.get("quantity")
        price = info.data.get("unit_price")
        if quantity is not None and price is not None and value > quantity * price:
            raise ValueError("退款不能超过 quantity × unit_price")
        return value


def parse_order_items_csv(content: bytes) -> list[tuple[int, OrderItemCsvRow]]:
    if len(content) > MAX_FILE_BYTES:
        raise CsvImportError([{"row": None, "field": "file", "reason": "文件超过 5 MiB"}])
    try:
        decoded = content.decode("utf-8-sig")
    except UnicodeDecodeError:
        raise CsvImportError([{"row": None, "field": "file", "reason": "文件必须使用 UTF-8 编码"}]) from None
    reader = csv.reader(io.StringIO(decoded, newline=""), strict=True)
    rows, errors, seen_lines = [], [], set()
    try:
        if next(reader, None) != ITEM_COLUMNS:
            raise CsvImportError([{"row": 1, "field": "header", "reason": "表头及顺序必须为 " + ",".join(ITEM_COLUMNS)}])
        for record_no, values in enumerate(reader, start=2):
            if record_no - 1 > MAX_ROWS:
                raise CsvImportError([{"row": record_no, "field": "file", "reason": "最多允许 10000 条数据记录"}])
            row_no = reader.line_num
            if len(values) != len(ITEM_COLUMNS):
                errors.append({"row": row_no, "field": "row", "reason": "每条记录必须恰好有 6 列"})
                continue
            try:
                row = OrderItemCsvRow.model_validate(dict(zip(ITEM_COLUMNS, values)))
            except ValidationError as exc:
                for error in exc.errors(include_input=False, include_context=False, include_url=False):
                    errors.append({"row": row_no, "field": ".".join(map(str, error["loc"])), "reason": error["msg"]})
                continue
            key = (row.external_order_id, row.line_no)
            if key in seen_lines:
                errors.append({"row": row_no, "field": "line_no", "reason": "同一文件内，同一订单的行号不能重复"})
            seen_lines.add(key)
            rows.append((row_no, row))
    except csv.Error:
        raise CsvImportError([{"row": reader.line_num, "field": "file", "reason": "CSV 引号或字段格式错误"}]) from None
    if errors:
        raise CsvImportError(errors)
    if not rows:
        raise CsvImportError([{"row": None, "field": "file", "reason": "文件没有数据记录"}])
    return rows


def import_order_items_csv(session: Session, *, user_id: int, store_id: int, content: bytes):
    try:
        require_owned_store(session, user_id=user_id, store_id=store_id)
        rows = parse_order_items_csv(content)
        digest = hashlib.sha256(content).hexdigest()
        store = session.scalar(select(Store).where(Store.id == store_id, Store.owner_id == user_id)
                               .with_for_update().execution_options(populate_existing=True))
        if store is None:
            raise StoreNotFoundError("店铺不存在")
        previous = session.scalar(select(ImportJob).where(
            ImportJob.store_id == store_id, ImportJob.kind == "order_items", ImportJob.file_hash == digest,
        ).with_for_update())
        if previous is not None:
            result = {"import_job_id": previous.id, "row_count": previous.row_count,
                      "data_version": previous.data_version, "duplicate": True}
            session.commit()
            return result

        external_ids = list({row.external_order_id for _, row in rows})
        skus = list({row.sku for _, row in rows})
        orders = {order.external_order_id: order for order in session.scalars(
            select(Order).where(Order.store_id == store_id, Order.external_order_id.in_(external_ids))
            .with_for_update()).all()}
        products = {product.sku: product for product in session.scalars(
            select(Product).where(Product.store_id == store_id, Product.sku.in_(skus))
            .with_for_update()).all()}
        existing = {(item.order_id, item.line_no): item for item in session.scalars(
            select(OrderItem).where(OrderItem.store_id == store_id,
                                    OrderItem.order_id.in_([order.id for order in orders.values()]))
            .with_for_update()).all()}

        errors, resolved = [], []
        for row_no, row in rows:
            order, product = orders.get(row.external_order_id), products.get(row.sku)
            if order is None:
                errors.append({"row": row_no, "field": "external_order_id", "reason": "目标店铺中找不到该订单，请先导入订单"})
            if product is None:
                errors.append({"row": row_no, "field": "sku", "reason": "目标店铺中找不到该商品"})
            if order is None or product is None:
                continue
            if order.currency != product.currency:
                errors.append({"row": row_no, "field": "sku", "reason": "商品币种必须与订单币种一致"})
            item = existing.get((order.id, row.line_no))
            if item is not None and item.product_id != product.id:
                errors.append({"row": row_no, "field": "sku", "reason": "已有明细行不能更换商品，请使用新行号"})
            resolved.append((row, order, product, item))
        if errors:
            raise CsvImportError(errors)

        for row, order, product, item in resolved:
            if item is None:
                item = OrderItem(store_id=store_id, order_id=order.id,
                                 product_id=product.id, line_no=row.line_no)
                session.add(item)
            item.quantity = row.quantity
            item.unit_price = row.unit_price
            item.refund_amount = row.refund_amount
        store.data_version += 1
        job = ImportJob(store_id=store_id, kind="order_items", file_hash=digest,
                        row_count=len(rows), data_version=store.data_version, created_by=user_id)
        session.add(job)
        session.flush()
        result = {"import_job_id": job.id, "row_count": job.row_count,
                  "data_version": job.data_version, "duplicate": False}
        session.commit()
        return result
    except DBAPIError:
        session.rollback()
        raise ImportConflictError("数据库拒绝本次导入，已回滚；请检查数据，或稍后重试") from None
    except Exception:
        session.rollback()
        raise
