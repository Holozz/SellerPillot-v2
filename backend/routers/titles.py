from fastapi import Path, HTTPException, Depends
from sqlalchemy.orm import Session
from database import get_db
from models import User
from services import StoreNotFoundError
from llm import ModelError
from title_drafts import ProductNotFoundError, TitleDraftError, TitleDraftSaveError, TitleDraftConflictError, TitleDraftNotFoundError, create_product_title_draft, get_product_title_draft, confirm_product_title_draft
from fastapi import APIRouter
from dependencies import get_current_user
from schemas import TitleDraftConfirmation

router = APIRouter(tags=["titles"])

@router.post("/stores/{store_id}/products/{product_id}/title-drafts", status_code=201)
def generate_product_title_draft(
    store_id: int = Path(ge=1),
    product_id: int = Path(ge=1),
    session: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        return create_product_title_draft(
            session,
            user_id=current_user.id,
            store_id=store_id,
            product_id=product_id,
        )
    except (StoreNotFoundError, ProductNotFoundError) as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except (ModelError, TitleDraftError) as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc
    except TitleDraftSaveError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc


@router.get("/stores/{store_id}/title-drafts/{draft_id}")
def read_product_title_draft(
    store_id: int = Path(ge=1),
    draft_id: int = Path(ge=1),
    session: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        return get_product_title_draft(
            session, user_id=current_user.id, store_id=store_id, draft_id=draft_id,
        )
    except (StoreNotFoundError, TitleDraftNotFoundError) as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc


@router.post("/stores/{store_id}/title-drafts/{draft_id}/confirm")
def apply_product_title_draft(
    body: TitleDraftConfirmation,
    store_id: int = Path(ge=1),
    draft_id: int = Path(ge=1),
    session: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        return confirm_product_title_draft(
            session, user_id=current_user.id, store_id=store_id,
            draft_id=draft_id, confirmed=body.confirmed,
        )
    except (StoreNotFoundError, TitleDraftNotFoundError, ProductNotFoundError) as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except TitleDraftConflictError as exc:
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except TitleDraftSaveError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc

