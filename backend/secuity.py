import os
from datetime import datetime, timedelta, timezone
import jwt
from dotenv import load_dotenv
from pwdlib import PasswordHash

password_hasher = PasswordHash.recommended()

load_dotenv()

JWT_SECRET_KEY = os.getenv("JWT_SECRET_KEY") or ""

if not JWT_SECRET_KEY or len(JWT_SECRET_KEY.encode("utf-8")) < 32:
    raise RuntimeError("请在 .env 中配置至少 32 字节的 JWT_SECRET_KEY")


def create_access_token(user_id: int) -> str:
    payload = {
        "sub": str(user_id),
        "exp": datetime.now(timezone.utc) + timedelta(minutes=30),
    }

    return jwt.encode(
        payload,
        JWT_SECRET_KEY,
        algorithm="HS256",
    )

def hash_password(password: str) -> str:
    return password_hasher.hash(password)

def verify_password(password: str, hashed_password: str) -> bool:
    return password_hasher.verify(password, hashed_password)

def decode_access_token(token: str) -> dict:
    return jwt.decode(
        token,
        JWT_SECRET_KEY,
        algorithms=["HS256"],
        options={"require": ["sub", "exp"]},
    )

