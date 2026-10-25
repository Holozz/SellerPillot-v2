from decimal import Decimal
from datetime import datetime, timezone

from sqlalchemy import (
    JSON, Numeric, String, ForeignKey,
    CheckConstraint,
    DateTime,
    ForeignKeyConstraint,
    Index,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column

from database import Base


class Product(Base):
    __tablename__ = "products"

    __table_args__ = (
        UniqueConstraint('store_id', 'sku', name='uq_products_store_sku'),
        UniqueConstraint(
            "store_id",
            "id",
            name = "uq_products_store_id_id",
        ),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    sku: Mapped[str] = mapped_column(String(30))
    version: Mapped[int] = mapped_column(default=1, server_default='1')
    title: Mapped[str] = mapped_column(String(200))
    price: Mapped[Decimal] = mapped_column(Numeric(18, 2))
    currency: Mapped[str] = mapped_column(String(3), default="USD")
    stock: Mapped[int] = mapped_column(default=0, server_default="0")
    store_id: Mapped[int|None] = mapped_column(
        ForeignKey("store.id", name="fk_products_store_id"),
        index=True,
    )

class Store(Base):
    __tablename__ = "store"

    id: Mapped[int] = mapped_column(primary_key=True)
    name: Mapped[str] = mapped_column(String(100))
    timezone: Mapped[str] = mapped_column(String(60), default='UTC', server_default='UTC')
    platform: Mapped[str] = mapped_column(String(30))
    owner_id: Mapped[int|None] = mapped_column(
        ForeignKey("users.id", name="fk_store_owner_id"),
        index=True,
    )
    data_version: Mapped[int] = mapped_column(
        default=0,
        server_default="0",
    )

class User(Base):
    __tablename__ = "users"
    id: Mapped[int] = mapped_column(primary_key=True)
    username: Mapped[str] = mapped_column(String(50), unique=True)
    password_hash: Mapped[str] = mapped_column(String(255))

class Order(Base):
    __tablename__ = "orders"

    id: Mapped[int] = mapped_column(primary_key=True)

    store_id: Mapped[int] = mapped_column(
        ForeignKey("store.id", name="fk_orders_store_id"),
    )

    external_order_id: Mapped[str] = mapped_column(String(100))
    ordered_at: Mapped[datetime] = mapped_column(DateTime())
    status: Mapped[str] = mapped_column(String(20))
    currency: Mapped[str] = mapped_column(String(3))

    __table_args__ = (
        UniqueConstraint(
            "store_id",
            "external_order_id",
            name="uq_orders_store_external_id",
        ),
        UniqueConstraint(
            "store_id",
            "id",
            name="uq_orders_store_id_id",
        ),
        CheckConstraint(
            "status IN ('pending', 'paid', 'shipped', 'cancelled')",
            name="ck_orders_status",
        ),
        Index(
            "ix_orders_store_ordered_at",
            "store_id",
            "ordered_at",
        ),
    )

class OrderItem(Base):
    __tablename__ = "order_items"

    id: Mapped[int] = mapped_column(primary_key=True)

    store_id: Mapped[int] = mapped_column()
    order_id: Mapped[int] = mapped_column()
    product_id: Mapped[int] = mapped_column()

    line_no: Mapped[int] = mapped_column()
    quantity: Mapped[int] = mapped_column()
    unit_price: Mapped[Decimal] = mapped_column(Numeric(18, 2))

    refund_amount: Mapped[Decimal] = mapped_column(
        Numeric(18, 2),
        default=Decimal("0.00"),
        server_default="0.00",
    )

    __table_args__ = (
        UniqueConstraint(
            "order_id",
            "line_no",
            name="uq_order_items_order_line",
        ),
        ForeignKeyConstraint(
            ["store_id", "order_id"],
            ["orders.store_id", "orders.id"],
            name="fk_order_items_store_order",
        ),
        ForeignKeyConstraint(
            ["store_id", "product_id"],
            ["products.store_id", "products.id"],
            name="fk_order_items_store_product",
        ),
        CheckConstraint(
            "line_no > 0",
            name="ck_order_items_line_no",
        ),
        CheckConstraint(
            "quantity > 0",
            name="ck_order_items_quantity",
        ),
        CheckConstraint(
            "unit_price >= 0",
            name="ck_order_items_unit_price",
        ),
        CheckConstraint(
            "refund_amount >= 0 "
            "AND refund_amount <= quantity * unit_price",
            name="ck_order_items_refund",
        ),
    )

class ImportJob(Base):
    __tablename__ = "import_jobs"

    id: Mapped[int] = mapped_column(primary_key=True)

    store_id: Mapped[int] = mapped_column(
        ForeignKey("store.id", name="fk_import_jobs_store_id"),
    )

    kind: Mapped[str] = mapped_column(String(20))
    file_hash: Mapped[str] = mapped_column(String(64))
    row_count: Mapped[int] = mapped_column()
    data_version: Mapped[int] = mapped_column()

    created_by: Mapped[int] = mapped_column(
        ForeignKey("users.id", name="fk_import_jobs_created_by"),
    )

    __table_args__ = (
        UniqueConstraint(
            "store_id",
            "kind",
            "file_hash",
            name="uq_import_jobs_store_kind_hash",
        ),
    )


class AgentConversation(Base):
    __tablename__ = "agent_conversations"

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(
        ForeignKey("users.id", name="fk_agent_conversations_user_id"),
    )
    store_id: Mapped[int] = mapped_column(
        ForeignKey("store.id", name="fk_agent_conversations_store_id"),
    )
    history: Mapped[list[dict]] = mapped_column(JSON, default=list)
    version: Mapped[int] = mapped_column(default=0, server_default="0")


class ProductTitleDraft(Base):
    __tablename__ = "product_title_drafts"

    id: Mapped[int] = mapped_column(primary_key=True)
    store_id: Mapped[int] = mapped_column()
    product_id: Mapped[int] = mapped_column()
    created_by: Mapped[int] = mapped_column(
        ForeignKey("users.id", name="fk_title_drafts_created_by"),
    )
    original_title: Mapped[str] = mapped_column(String(200))
    product_version: Mapped[int] = mapped_column(default=1, server_default='1')
    proposed_title: Mapped[str] = mapped_column(String(200))
    status: Mapped[str] = mapped_column(
        String(20), default="pending", server_default="pending",
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(),
        default=lambda: datetime.now(timezone.utc).replace(tzinfo=None),
    )
    applied_at: Mapped[datetime | None] = mapped_column(DateTime())

    __table_args__ = (
        ForeignKeyConstraint(
            ["store_id", "product_id"],
            ["products.store_id", "products.id"],
            name="fk_title_drafts_store_product",
        ),
        CheckConstraint(
            "status IN ('pending', 'applied')",
            name="ck_title_drafts_status",
        ),
    )

