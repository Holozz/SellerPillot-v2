from fastapi import HTTPException, Depends
from sqlalchemy import select
from sqlalchemy.orm import Session
from sqlalchemy.exc import IntegrityError
from database import get_db
from models import User
from secuity import hash_password, verify_password, create_access_token
from fastapi import APIRouter
from dependencies import get_current_user
from schemas import UserCreate, UserRead, UserLogin, TokenRead

router = APIRouter(tags=["auth"])

@router.post("/auth/register", status_code=201, response_model=UserRead)
def register_user(
    user_data: UserCreate,
    session: Session=Depends(get_db)
):
    existing_user = session.scalar(
        select(User).where(User.username==user_data.username)
    )
    if existing_user is not None:
        raise HTTPException(status_code=409, detail="用户名已经存在")

    user = User(
        username = user_data.username,
        password_hash = hash_password(user_data.password),
    )
    session.add(user)

    try:
        session.commit()
    except IntegrityError:
        session.rollback()
        raise HTTPException(status_code=409, detail="用户名已存在")

    session.refresh(user)
    return user


@router.post("/auth/login", response_model=TokenRead)
def login_user(
    login_data: UserLogin,
    session: Session = Depends(get_db),
):
    user = session.scalar(
        select(User).where(User.username == login_data.username)
    )

    if user is None:
        raise HTTPException(
            status_code=401,
            detail="用户名或密码错误",
        )

    if not verify_password(login_data.password, user.password_hash):
        raise HTTPException(
            status_code=401,
            detail="用户名或密码错误",
        )

    return {
        "access_token": create_access_token(user.id),
        "token_type": "bearer",
    }


@router.get("/auth/me", response_model=UserRead)
def read_current_user(current_user: User = Depends(get_current_user)):
    return current_user

