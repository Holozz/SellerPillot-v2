"""create products table

Revision ID: 0f94d7289cde
Revises: 
Create Date: 2026-10-10 23:36:39.216281

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '0f94d7289cde'
down_revision: Union[str, Sequence[str], None] = None
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "products",
        sa.Column("id", sa.Integer(), nullable=False),
        sa.Column("sku", sa.String(length=30), nullable=False),
        sa.Column("title", sa.String(length=200), nullable=False),
        sa.Column("price", sa.Numeric(precision=18, scale=2), nullable=False),
        sa.Column("currency", sa.String(length=3), nullable=False),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("sku"),
    )


def downgrade() -> None:
    op.drop_table("products")