import csv
import hashlib
import io
from datetime import datetime, timezone
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator
from sqlalchemy import select
from sqlalchemy.exc import DBAPIError
from sqlalchemy.orm import Session

from services import StoreNotFoundError, require_owned_store

MAX_FILE_BYTES = 5 * 1024 * 1024
MAX_ROWS = 10_000
ORDER_COLUMNS = ["external_order_id", "ordered_at", "status", "currency"]


class CsvImportError(Exception):
    def __init__(self, errors):
        super().__init__("CSV 校验失败，未写入数据")
        self.errors = errors


class ImportConflictError(Exception):
    pass


class OrderCsvRow(BaseModel):
    model_config = ConfigDict(str_strip_whitespace=True, extra="forbid")

    external_order_id: str = Field(min_length=1, max_length=100)
    ordered_at: datetime
    status: Literal["pending", "paid", "shipped", "cancelled"]
    currency: str = Field(pattern=r"^[A-Z]{3}$")

    @field_validator("ordered_at", mode="before")
    @classmethod
    def parse_timestamp(cls, value):
        if not isinstance(value, str) or "T" not in value:
            raise ValueError("使用包含 T 和时区偏移的 ISO 8601 时间")
        try:
            parsed = datetime.fromisoformat(value.strip())
        except ValueError:
            raise ValueError("时间格式错误，例如 2026-10-12T10:00:00+08:00") from None
        if parsed.tzinfo is None or parsed.utcoffset() is None:
            raise ValueError("时间必须包含时区偏移，例如 +08:00 或 Z")
        # MySQL DATETIME stores a naive datetime; its meaning here is UTC.
        return parsed.astimezone(timezone.utc).replace(tzinfo=None)


def parse_orders_csv(content: bytes) -> list[tuple[int, OrderCsvRow]]:
    if len(content) > MAX_FILE_BYTES:
        raise CsvImportError([{"row": None, "field": "file", "reason": "文件超过 5 MiB"}])
    try:
        decoded = content.decode("utf-8-sig")
    except UnicodeDecodeError:
        raise CsvImportError([{"row": None, "field": "file", "reason": "文件必须使用 UTF-8 编码"}]) from None

    reader = csv.reader(io.StringIO(decoded, newline=""), strict=True)
    rows, errors, seen_ids = [], [], set()
    try:
        header = next(reader, None)
        if header != ORDER_COLUMNS:
            raise CsvImportError([{"row": 1, "field": "header", "reason": "表头及顺序必须为 " + ",".join(ORDER_COLUMNS)}])
        for record_no, values in enumerate(reader, start=2):
            if record_no - 1 > MAX_ROWS:
                raise CsvImportError([{"row": record_no, "field": "file", "reason": "最多允许 10000 条数据记录"}])
            # Report the ending physical line for records containing quoted newlines.
            row_no = reader.line_num
            if len(values) != len(ORDER_COLUMNS):
                errors.append({"row": row_no, "field": "row", "reason": "每条记录必须恰好有 4 列"})
                continue
            try:
                row = OrderCsvRow.model_validate(dict(zip(ORDER_COLUMNS, values)))
            except ValidationError as exc:
                for error in exc.errors(include_input=False, include_context=False, include_url=False):
                    errors.append({"row": row_no, "field": ".".join(map(str, error["loc"])), "reason": error["msg"]})
                continue
            if row.external_order_id in seen_ids:
                errors.append({"row": row_no, "field": "external_order_id", "reason": "同一个文件内订单号不能重复"})
            seen_ids.add(row.external_order_id)
            rows.append((row_no, row))
    except csv.Error:
        raise CsvImportError([{"row": reader.line_num, "field": "file", "reason": "CSV 引号或字段格式错误"}]) from None
    if errors:
        raise CsvImportError(errors)
    if not rows:
        raise CsvImportError([{"row": None, "field": "file", "reason": "文件没有数据记录"}])
    return rows


def import_orders_csv(session: Session, *, user_id: int, store_id: int, content: bytes):
    # Models are loaded here so the parser can be exercised before migration.
    from models import ImportJob, Order, Store

    try:
        require_owned_store(session, user_id=user_id, store_id=store_id)
        rows = parse_orders_csv(content)
        digest = hashlib.sha256(content).hexdigest()

        # Serialize imports within a store. Authentication has already begun a
        # transaction on this session, so avoid nesting a new session.begin().
        store = session.scalar(
            select(Store)
            .where(Store.id == store_id, Store.owner_id == user_id)
            .with_for_update()
            .execution_options(populate_existing=True)
        )
        if store is None:
            raise StoreNotFoundError("店铺不存在")

        previous = session.scalar(select(ImportJob).where(
            ImportJob.store_id == store_id,
            ImportJob.kind == "orders",
            ImportJob.file_hash == digest,
        ).with_for_update())
        if previous is not None:
            result = {"import_job_id": previous.id, "row_count": previous.row_count,
                      "data_version": previous.data_version, "duplicate": True}
            session.commit()
            return result

        external_ids = [row.external_order_id for _, row in rows]
        existing = {order.external_order_id: order for order in session.scalars(
            select(Order)
            .where(Order.store_id == store_id, Order.external_order_id.in_(external_ids))
            .with_for_update()
        ).all()}

        errors = []
        for row_no, row in rows:
            order = existing.get(row.external_order_id)
            if order is not None and order.currency != row.currency:
                errors.append({"row": row_no, "field": "currency", "reason": "已有订单的币种不能修改"})
        if errors:
            raise CsvImportError(errors)

        for _, row in rows:
            order = existing.get(row.external_order_id)
            if order is None:
                order = Order(store_id=store_id, **row.model_dump())
                session.add(order)
            else:
                order.ordered_at = row.ordered_at
                order.status = row.status

        store.data_version += 1
        job = ImportJob(store_id=store_id, kind="orders", file_hash=digest,
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
