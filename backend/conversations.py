from sqlalchemy import select, update
from sqlalchemy.engine import CursorResult
from sqlalchemy.exc import DBAPIError
from sqlalchemy.orm import Session
from typing import cast

from agent import run_store_agent
from models import AgentConversation
from services import require_owned_store

MAX_HISTORY_MESSAGES = 12  # 最近6轮，每轮一条用户消息和一条助手回答。


class ConversationNotFoundError(Exception):
    """会话不存在，或不属于当前用户与店铺。"""


class ConversationError(Exception):
    """会话暂时无法保存。"""


class ConversationConflictError(ConversationError):
    """另一条请求已更新了同一会话。"""


def run_conversation_agent(
    session: Session, *, user_id: int, store_id: int, question: str,
    conversation_id: int | None = None,
) -> dict:
    """读取私有历史、调用 Agent，再原子保存本轮问答；拥有本次请求的事务。"""
    try:
        require_owned_store(session, user_id=user_id, store_id=store_id)
        if conversation_id is not None and (
            type(conversation_id) is not int or conversation_id < 1
        ):
            raise ValueError("conversation_id 必须是正整数")

        conversation = None
        history = []
        version = 0
        if conversation_id is not None:
            conversation = session.scalar(
                select(AgentConversation).where(
                    AgentConversation.id == conversation_id,
                    AgentConversation.user_id == user_id,
                    AgentConversation.store_id == store_id,
                ).execution_options(populate_existing=True)
            )
            if conversation is None:
                raise ConversationNotFoundError("会话不存在")
            history = list(conversation.history[-MAX_HISTORY_MESSAGES:])
            version = conversation.version

        result = run_store_agent(
            session, user_id=user_id, store_id=store_id,
            question=question, history=history,
        )
        next_history = (
            history + [
                {"role": "user", "content": question.strip()},
                {"role": "assistant", "content": result["answer"]},
            ]
        )[-MAX_HISTORY_MESSAGES:]
        next_version = version + 1

        if conversation is None:
            # 取得成功回复后才创建记录，失败的模型请求不会留下空会话。
            conversation = AgentConversation(
                user_id=user_id, store_id=store_id,
                history=next_history, version=next_version,
            )
            session.add(conversation)
            session.flush()
            conversation_id = conversation.id
        else:
            # 版本仍与读取时一致才允许保存；避免并发请求覆盖历史。
            saved = cast(CursorResult, session.execute(
                update(AgentConversation).where(
                    AgentConversation.id == conversation_id,
                    AgentConversation.user_id == user_id,
                    AgentConversation.store_id == store_id,
                    AgentConversation.version == version,
                ).values(history=next_history, version=next_version),
                execution_options={"synchronize_session": False},
            ))
            if saved.rowcount != 1:
                raise ConversationConflictError("会话已更新，请重新提交问题")

        session.commit()
        return {
            **result, "conversation_id": conversation_id,
            "conversation_version": next_version,
        }
    except DBAPIError:
        session.rollback()
        raise ConversationError("会话暂时无法保存，请稍后重试") from None
    except Exception:
        session.rollback()
        raise
