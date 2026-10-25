from decimal import Decimal
from typing import Literal
from pydantic import BaseModel, Field, ConfigDict

class ProductCreate(BaseModel):
    sku: str = Field(min_length=3, max_length=30, pattern=r"^[A-Za-z0-9-]+$")
    title: str = Field(min_length=1, max_length=200)
    price: Decimal = Field(ge=0, max_digits=18, decimal_places=2)
    currency: str = Field(default="USD", pattern=r"^[A-Z]{3}$")
    stock: int = Field(default=0, ge=0)
    store_id: int = Field(ge=1)


class ProductUpdate(BaseModel):
    title: str = Field(min_length=1, max_length=200)
    price: Decimal = Field(ge=0, max_digits=18, decimal_places=2)
    currency: str = Field(pattern=r"^[A-Z]{3}$")
    stock: int = Field(ge=0)


class ProductRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: int
    sku: str
    title: str
    price: Decimal
    currency: str
    stock: int
    store_id: int|None


class StoreCreate(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    platform: str = Field(pattern=r"^(amazon|shopify)$")


class StoreRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: int
    name: str
    platform: str
    owner_id: int|None


class UserCreate(BaseModel):
    username: str = Field(
        min_length=3,
        max_length=50,
        pattern=r"^[A-Za-z0-9_-]+$",
    )
    password: str = Field(min_length=8, max_length=128)


class UserRead(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: int
    username: str    


class UserLogin(BaseModel):
    username: str = Field(min_length=3, max_length=50)
    password: str = Field(min_length=1, max_length=128)


class TokenRead(BaseModel):
    access_token: str
    token_type: str


class AgentQuestion(BaseModel):
    model_config = ConfigDict(str_strip_whitespace=True, extra="forbid")
    message: str=Field(min_length=1, max_length=2000)
    conversation_id:int|None=Field(default=None, ge=1, strict=True)


class TitleDraftConfirmation(BaseModel):
    model_config = ConfigDict(extra="forbid")
    confirmed: bool = Field(strict=True)

