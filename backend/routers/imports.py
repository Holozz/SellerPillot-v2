from fastapi import Path, HTTPException, Depends, File, UploadFile
from imports import MAX_FILE_BYTES, CsvImportError, ImportConflictError, import_orders_csv
from sqlalchemy.orm import Session
from database import get_db
from models import User
from services import StoreNotFoundError
from order_items_import import import_order_items_csv
from fastapi import APIRouter
from dependencies import get_current_user

router = APIRouter(tags=["imports"])

@router.post("/stores/{store_id}/imports/orders")
def upload_orders_csv(
    store_id: int = Path(ge=1),
    file: UploadFile = File(...),
    session: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        if not file.filename or not file.filename.lower().endswith(".csv"):
            raise HTTPException(
                status_code=422,
                detail="请上传 .csv 文件",
            )

        content = file.file.read(MAX_FILE_BYTES + 1)

        return import_orders_csv(
            session,
            user_id=current_user.id,
            store_id=store_id,
            content=content,
        )

    except StoreNotFoundError as exc:
        raise HTTPException(
            status_code=404,
            detail=str(exc),
        ) from exc

    except CsvImportError as exc:
        raise HTTPException(
            status_code=422,
            detail={
                "message": str(exc),
                "errors": exc.errors,
            },
        ) from exc

    except ImportConflictError as exc:
        raise HTTPException(
            status_code=409,
            detail=str(exc),
        ) from exc


@router.post("/stores/{store_id}/imports/order-items")
def upload_order_items_csv(
    store_id: int = Path(ge=1),
    file: UploadFile = File(...),
    session: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        if not file.filename or not file.filename.lower().endswith(".csv"):
            raise HTTPException(
                status_code=422,
                detail="请上传 .csv 文件",
            )

        content = file.file.read(MAX_FILE_BYTES + 1)

        return import_order_items_csv(
            session,
            user_id=current_user.id,
            store_id=store_id,
            content=content,
        )

    except StoreNotFoundError as exc:
        raise HTTPException(
            status_code=404,
            detail=str(exc),
        ) from exc

    except CsvImportError as exc:
        raise HTTPException(
            status_code=422,
            detail={
                "message": str(exc),
                "errors": exc.errors,
            },
        ) from exc

    except ImportConflictError as exc:
        raise HTTPException(
            status_code=409,
            detail=str(exc),
        ) from exc

