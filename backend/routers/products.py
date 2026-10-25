from fastapi import Query, Path, HTTPException, Depends
from sqlalchemy import select
from sqlalchemy.orm import Session
from sqlalchemy.exc import IntegrityError
from database import get_db
from models import Product, Store, User
from services import StoreNotFoundError, list_low_stock_products
from fastapi import APIRouter
from dependencies import get_current_user, owned_products_query
from schemas import ProductCreate, ProductUpdate, ProductRead

router = APIRouter(tags=["products"])

@router.post("/products", status_code=201, response_model=ProductRead)
def create_database_product(product: ProductCreate,
                            session: Session = Depends(get_db),
                            current_user: User = Depends(get_current_user),
                            ):
    store = session.scalar(
        select(Store).where(
            Store.id == product.store_id,
            Store.owner_id == current_user.id,
        ).with_for_update()
    )
    if store is None: 
            raise HTTPException(status_code=404, detail="店铺不存在")
    existing_product = session.scalar(
        select(Product).where(Product.sku == product.sku, Product.store_id == product.store_id)
    )
    if existing_product is not None:
        raise HTTPException(status_code=409, detail="数据库中已存在该 SKU")
        
    database_product = Product(
        sku=product.sku,
        title=product.title,
        price=product.price,
        currency=product.currency,
        stock=product.stock,
        store_id = product.store_id
    )

    session.add(database_product)
    store.data_version += 1
    try: session.commit()
    except IntegrityError:
        session.rollback()
        raise HTTPException(status_code=409, detail="写入违反数据库约束")
    session.refresh(database_product)

    return database_product


@router.get("/products", response_model=list[ProductRead])
def list_database_products(limit:int=Query(default=10, ge=1, le=100),
                           store_id:int|None=Query(default=None, ge=1),
                           session: Session = Depends(get_db),
                           current_user: User = Depends(get_current_user),
                           ):
    statement = owned_products_query(current_user.id)
    if store_id is not None:
        statement = statement.where(Product.store_id == store_id)
    statement = statement.order_by(Product.id).limit(limit)
    return session.scalars(statement).all()


@router.get("/products/{sku}", response_model=ProductRead)
def get_database_product(sku: str = Path(
                            min_length = 3,
                            max_length=30,
                            pattern = r"^[A-Za-z0-9-]+$" 
                        ),
                        store_id: int | None = Query(default=None, ge=1),
                        session: Session = Depends(get_db),
                        current_user: User = Depends(get_current_user),):
    statement = owned_products_query(current_user.id).where(Product.sku == sku)
    if store_id is not None: statement = statement.where(Product.store_id == store_id)
    matches = session.scalars(statement.limit(2)).all()
    if len(matches) > 1: raise HTTPException(409, detail='请指定 store_id')
    product = matches[0] if matches else None
    if product is None:
        raise HTTPException(status_code=404, detail="商品不存在")
    return product


@router.delete("/products/{sku}", status_code=204)
def delete_database_product(sku: str = Path(
        min_length=3, max_length=30, pattern=r"^[A-Za-z0-9-]+$"),
        store_id: int | None = Query(default=None, ge=1),
        session: Session = Depends(get_db),
        current_user: User = Depends(get_current_user),
    ):
    statement = owned_products_query(current_user.id).where(Product.sku == sku)
    if store_id is not None: statement = statement.where(Product.store_id == store_id)
    matches = session.scalars(statement.limit(2)).all()
    if len(matches) > 1: raise HTTPException(409, detail='请指定 store_id')
    product = matches[0] if matches else None

    if product is None:
        raise HTTPException(status_code=404, detail="商品不存在")
    try:
        # 订单明细和标题草稿引用商品时，保留业务历史，拒绝物理删除。
        session.scalar(select(Store).where(Store.id == product.store_id).with_for_update()).data_version += 1
        session.delete(product)
        session.commit()
    except IntegrityError:
        session.rollback()
        raise HTTPException(status_code=409, detail="商品存在关联订单明细或标题草稿，无法删除；可以先调整库存或标题") from None


@router.put("/products/{sku}", response_model=ProductRead)
def updata_product(product_data: ProductUpdate,
                   sku: str = Path(
                       min_length=3, 
                       max_length=30,
                       pattern=r"^[A-Za-z0-9-]+$",
                   ),
                   store_id: int | None = Query(default=None, ge=1),
                   session: Session = Depends(get_db),
                   current_user: User = Depends(get_current_user),
                   ):
    statement = owned_products_query(current_user.id).where(Product.sku == sku)
    if store_id is not None: statement = statement.where(Product.store_id == store_id)
    matches = session.scalars(statement.limit(2)).all()
    if len(matches) > 1: raise HTTPException(409, detail='请指定 store_id')
    product = matches[0] if matches else None
    if product is None:
        raise HTTPException(status_code=404, detail="商品不存在")

    store = session.scalar(select(Store).where(Store.id == product.store_id).with_for_update())
    session.refresh(product, with_for_update=True)
    product.title = product_data.title
    product.version += 1
    store.data_version += 1
    product.price = product_data.price
    product.currency = product_data.currency
    product.stock = product_data.stock

    session.commit()
    session.refresh(product)

    return product


@router.get(
    "/stores/{store_id}/products/low-stock",
    response_model=list[ProductRead],
)
def get_low_stock_products(
    store_id: int = Path(ge=1),
    threshold: int = Query(default=10, ge=0),
    limit: int = Query(default=20, ge=1, le=100),
    session: Session = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    try:
        return list_low_stock_products(
            session,
            user_id=current_user.id,
            store_id=store_id,
            threshold=threshold,
            limit=limit,
        )
    except StoreNotFoundError as exc:
        raise HTTPException(
            status_code=404,
            detail=str(exc),
        ) from exc
    except ValueError as exc:
        raise HTTPException(
            status_code=422,
            detail=str(exc),
        ) from exc

