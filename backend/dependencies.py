from fastapi import Depends, HTTPException
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from jwt.exceptions import InvalidTokenError
from sqlalchemy import select
from sqlalchemy.orm import Session
from database import get_db
from models import User, Product, Store
from secuity import decode_access_token

bearer_scheme = HTTPBearer(auto_error=False)

def get_current_user(
    credentials: HTTPAuthorizationCredentials | None = Depends(
        bearer_scheme
    ),
    session: Session = Depends(get_db),
):
    auth_error = HTTPException(
        status_code=401,
        detail="登录凭证无效或已过期",
        headers={"WWW-Authenticate": "Bearer"},
    )
    if credentials is None:
        raise auth_error

    try:
        payload = decode_access_token(credentials.credentials)
        user_id = int(payload["sub"])
        if user_id < 1:
            raise ValueError("无效用户 ID")

    except (InvalidTokenError, ValueError, KeyError, TypeError):
        raise auth_error

    user = session.get(User, user_id)
    if user is None:
        raise auth_error

    return user


def owned_products_query(user_id:int):
    return (select(Product)
            .join(Store, Product.store_id == Store.id)
            .where(Store.owner_id == user_id)
    )

