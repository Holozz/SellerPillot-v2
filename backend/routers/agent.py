from fastapi import Path, HTTPException, Depends
from sqlalchemy.orm import Session
from database import get_db
from models import User
from services import StoreNotFoundError
from llm import ModelError
from agent import AgentError
from conversations import ConversationConflictError, ConversationError, ConversationNotFoundError, run_conversation_agent
from fastapi import APIRouter
from dependencies import get_current_user
from schemas import AgentQuestion

router = APIRouter(tags=["agent"])

@router.post("/store/{store_id}/agent/query")
def query_store_agent(
    body: AgentQuestion,
    store_id: int=Path(ge=1),
    session: Session=Depends(get_db),
    current_usr: User=Depends(get_current_user),
):
    try:
        return run_conversation_agent(
            session,
            user_id=current_usr.id,
            store_id=store_id,
            question=body.message,
            conversation_id=body.conversation_id,
        )
    except (StoreNotFoundError, ConversationNotFoundError) as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except ConversationConflictError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    except ConversationError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except (ModelError, AgentError) as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc

