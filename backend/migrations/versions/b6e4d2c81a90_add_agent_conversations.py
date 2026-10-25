"""add bounded agent conversations

Revision ID: b6e4d2c81a90
Revises: 1ae0fe8ad4e4
"""
from alembic import op
import sqlalchemy as sa

revision = "b6e4d2c81a90"
down_revision = "1ae0fe8ad4e4"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "agent_conversations",
        sa.Column("id", sa.Integer(), nullable=False),
        sa.Column("user_id", sa.Integer(), nullable=False),
        sa.Column("store_id", sa.Integer(), nullable=False),
        sa.Column("history", sa.JSON(), nullable=False),
        sa.Column("version", sa.Integer(), server_default="0", nullable=False),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"], name="fk_agent_conversations_user_id"),
        sa.ForeignKeyConstraint(["store_id"], ["store.id"], name="fk_agent_conversations_store_id"),
        sa.PrimaryKeyConstraint("id"),
    )


def downgrade() -> None:
    op.drop_table("agent_conversations")
