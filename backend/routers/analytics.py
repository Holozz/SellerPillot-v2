from fastapi import Query, Path, HTTPException, Depends
from typing import Literal
from pydantic import AwareDatetime
from sqlalchemy.orm import Session
from database import get_db
from models import User
from services import StoreNotFoundError
from analytics import list_product_sales, list_store_orders, summarize_sales
from fastapi import APIRouter
from dependencies import get_current_user

router = APIRouter(tags=["analytics"])

@router.get("/stores/{store_id}/orders")
def get_store_orders(
    store_id: int = Path(ge=1),
    start_at: AwareDatetime = Query(...),
    end_at: AwareDatetime = Query(...),
    status: Literal["pending", "paid", "shipped", "cancelled"] | None = Query(default=None),
    limit: int = Query(default=20, ge=1, le=100),
    offset: int = Query(default=0, ge=0, le=2147483647),
    session: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        return list_store_orders(session, user_id=current_user.id, store_id=store_id,
            start_at=start_at, end_at=end_at, status=status, limit=limit, offset=offset)
    except StoreNotFoundError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc


@router.get("/stores/{store_id}/sales/summary")
def get_sales_summary(
    store_id: int = Path(ge=1),
    start_at: AwareDatetime = Query(...),
    end_at: AwareDatetime = Query(...),
    session: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        return summarize_sales(session, user_id=current_user.id, store_id=store_id,
                               start_at=start_at, end_at=end_at)
    except StoreNotFoundError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc


@router.get("/stores/{store_id}/sales/products")
def get_product_sales(
    store_id: int = Path(ge=1),
    start_at: AwareDatetime = Query(...),
    end_at: AwareDatetime = Query(...),
    limit: int = Query(default=20, ge=1, le=100),
    offset: int = Query(default=0, ge=0, le=2147483647),
    session: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        return list_product_sales(session, user_id=current_user.id, store_id=store_id,
            start_at=start_at, end_at=end_at, limit=limit, offset=offset)
    except StoreNotFoundError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc

