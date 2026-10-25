"""Expose the shared demo policy corpus within an authorized store request."""
from sqlalchemy.orm import Session

from policy_search import retrieve_policy_chunks
from services import require_owned_store


def search_store_policy(
    session: Session, *, user_id: int, store_id: int, question: str, top_k: int = 3,
) -> dict:
    """Check store access, then return candidate passages from the demo corpus."""
    require_owned_store(session, user_id=user_id, store_id=store_id)
    chunks = retrieve_policy_chunks(question, top_k=top_k)
    return {
        "store_id": store_id,
        "corpus_scope": "shared_demo",
        "document_version": "demo-v1",
        "notice": "所有店铺共用的项目自编演示政策，不代表真实平台规则或店铺实际承诺。",
        "question": question,
        "returned_count": len(chunks),
        "chunks": chunks,
    }
