"""使用独立 sellerpilot_test 库验收业务闭环；模型响应用 mock，不访问模型服务。"""
import json
import sys
from pathlib import Path
from unittest.mock import patch
from uuid import uuid4

import pytest
from dotenv import load_dotenv
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, delete, func, inspect, select
from sqlalchemy.orm import Session

# 同时支持在协作目录验证和复制到实际项目的 backend 后执行。
BACKEND = Path(__file__).resolve().parents[1]
load_dotenv(BACKEND.parent / ".env")
sys.path.insert(0, str(BACKEND))
from database import database_url, get_db
from main import app
from models import AgentConversation, ImportJob, Order, OrderItem, Product, ProductTitleDraft, Store, User


@pytest.fixture
def acceptance_client():
    # 强制切到专用测试库，不复用演示库；仅删除本次随机账号关联的记录。
    engine = create_engine(database_url.set(database="sellerpilot_test"))
    assert engine.url.database == "sellerpilot_test"
    required = {model.__tablename__ for model in (ProductTitleDraft, AgentConversation, ImportJob, OrderItem, Order, Product, Store, User)}
    assert required <= set(inspect(engine).get_table_names()), "请先将 sellerpilot_test 迁移到 head"
    prefix = "accept_" + uuid4().hex[:12]

    def test_db():
        with Session(engine) as session:
            yield session

    previous = app.dependency_overrides.get(get_db)
    app.dependency_overrides[get_db] = test_db
    try:
        with TestClient(app) as client:
            yield client, engine, prefix
    finally:
        try:
            with Session(engine) as session:
                user_ids = list(session.scalars(select(User.id).where(
                    User.username.in_([prefix + "a", prefix + "b"]))))
                store_ids = list(session.scalars(select(Store.id).where(Store.owner_id.in_(user_ids))))
                # 先删引用店铺、商品、订单的子记录，避免外键阻止清理。
                for model in (ProductTitleDraft, AgentConversation, ImportJob, OrderItem, Order, Product):
                    session.execute(delete(model).where(model.store_id.in_(store_ids)))
                session.execute(delete(Store).where(Store.id.in_(store_ids)))
                session.execute(delete(User).where(User.id.in_(user_ids)))
                session.commit()
                assert session.scalar(select(func.count()).select_from(User).where(
                    User.username.in_([prefix + "a", prefix + "b"]))) == 0
        finally:
            if previous is None:
                app.dependency_overrides.pop(get_db, None)
            else:
                app.dependency_overrides[get_db] = previous
            engine.dispose()


def test_business_acceptance(acceptance_client):
    client, engine, prefix = acceptance_client

    def send(method, path, *, status=200, **kwargs):
        response = client.request(method, path, **kwargs)
        assert response.status_code == status, (path, response.status_code, response.text)
        return response.json()

    auth = []
    stores = []
    for suffix in ("a", "b"):
        credentials = {"username": prefix + suffix, "password": "Acceptance123!"}
        send("POST", "/auth/register", status=201, json=credentials)
        token = send("POST", "/auth/login", json=credentials)["access_token"]
        header = {"Authorization": "Bearer " + token}
        auth.append(header)
        stores.append(send("POST", "/stores", status=201, headers=header,
                           json={"name": prefix + suffix, "platform": "amazon"})["id"])
    a, b = auth
    sid = stores[0]
    products = []
    for suffix, stock, price in (("P1", 0, "10.00"), ("P2", 10, "15.00")):
        products.append(send("POST", "/products", status=201, headers=a, json={
            "sku": prefix.replace("_", "-") + suffix, "title": "Storage Jar " + suffix,
            "price": price, "currency": "USD", "stock": stock, "store_id": sid}))
    low_path = f"/stores/{sid}/products/low-stock"
    assert len(send("GET", low_path, headers=a, params={"threshold": 10})) == 1
    assert send("GET", low_path, headers=a, params={"threshold": 0}) == []
    assert len(send("GET", low_path, headers=a, params={"threshold": 11})) == 2

    order_csv = "external_order_id,ordered_at,status,currency\n" + "".join(
        f"{prefix}-{i},2026-10-18T10:00:00+08:00,{state},USD\n"
        for i, state in enumerate(("paid", "shipped", "pending", "cancelled"), 1))
    item_header = "external_order_id,line_no,sku,quantity,unit_price,refund_amount\n"
    item_csv = item_header + "".join(
        f"{prefix}-{i},1,{products[p]['sku']},{qty},{price},{refund}\n"
        for i, p, qty, price, refund in ((1, 0, 2, 10, 0), (2, 1, 3, 15, 5), (3, 0, 1, 10, 0), (4, 1, 1, 15, 0)))
    for kind, content in (("orders", order_csv), ("order-items", item_csv)):
        path = f"/stores/{sid}/imports/{kind}"
        first = send("POST", path, headers=a, files={"file": ("data.csv", content.encode(), "text/csv")})
        again = send("POST", path, headers=a, files={"file": ("renamed.csv", content.encode(), "text/csv")})
        assert first["row_count"] == 4 and first["duplicate"] is False
        assert again == {**first, "duplicate": True}
        send("POST", path, headers=b, status=404, files={"file": ("data.csv", content.encode(), "text/csv")})
    params = {"start_at": "2026-10-18T00:00:00+08:00", "end_at": "2026-10-19T00:00:00+08:00"}
    summary_path = f"/stores/{sid}/sales/summary"
    baseline = send("GET", summary_path, headers=a, params=params)
    group = baseline["by_currency"][0]
    assert (group["order_count"], group["units_sold"], group["gross_sales"], group["refund_amount"], group["net_sales"]) == (2, 5, "65.00", "5.00", "60.00")
    assert len(send("GET", f"/stores/{sid}/orders", headers=a, params=params)["items"]) == 4
    page = send("GET", f"/stores/{sid}/orders", headers=a, params={**params, "limit": 2})
    assert len(page["items"]) == 2 and page["has_more"]
    assert len(send("GET", f"/stores/{sid}/orders", headers=a, params={**params, "status": "cancelled"})["items"]) == 1
    assert send("GET", summary_path, headers=a, params={**params, "end_at": "2026-10-18T10:00:00+08:00"})["by_currency"] == []
    for path in (summary_path, f"/stores/{sid}/orders", f"/stores/{sid}/sales/products", low_path):
        send("GET", path, headers=b, status=404, params=params)

    # 第一行合法、第二行引用不存在的SKU；整个文件必须失败，不留下第90行。
    bad = item_header + f"{prefix}-1,90,{products[0]['sku']},1,10,0\n{prefix}-1,91,MISSING-SKU,1,10,0\n"
    send("POST", f"/stores/{sid}/imports/order-items", status=422, headers=a,
         files={"file": ("invalid.csv", bad.encode(), "text/csv")})
    with Session(engine) as session:
        assert session.scalar(select(func.count()).select_from(OrderItem).where(
            OrderItem.store_id == sid)) == 4
    assert send("GET", summary_path, headers=a, params=params) == baseline
    sales = send("GET", f"/stores/{sid}/sales/products", headers=a, params=params)
    assert {item["sku"]: item["units_sold"] for item in sales["items"]} == {products[0]["sku"]: 2, products[1]["sku"]: 3}
    send("DELETE", f"/products/{products[0]['sku']}", headers=a, status=409)
    assert send("GET", f"/products/{products[0]['sku']}", headers=a)["stock"] == 0

    # 只模拟模型响应，真实Agent循环、工具校验、SQL查询及对话持久化仍执行。
    tool_reply = {"role": "assistant", "content": None, "tool_calls": [{"id": "accept-tool", "type": "function", "function": {
        "name": "list_low_stock_products", "arguments": json.dumps({"threshold": 10, "limit": 20})}}]}
    final_reply = {"role": "assistant", "content": "已查询库存。"}
    with patch("agent.chat", side_effect=[tool_reply, final_reply, final_reply]):
        chat = send("POST", f"/store/{sid}/agent/query", headers=a, json={"message": "哪些商品库存低于10？"})
        assert chat["tool_calls"][0]["result"]["returned_count"] == 1
        follow = send("POST", f"/store/{sid}/agent/query", headers=a, json={"message": "继续", "conversation_id": chat["conversation_id"]})
        assert follow["conversation_id"] == chat["conversation_id"] and follow["conversation_version"] == 2
        send("POST", f"/store/{stores[1]}/agent/query", headers=b, status=404,
             json={"message": "读取会话", "conversation_id": chat["conversation_id"]})

    title_path = f"/stores/{sid}/products/{products[0]['id']}/title-drafts"
    with patch("title_drafts.chat", return_value={"content": '{"proposed_title":"Kitchen Storage Jar P1"}'}):
        draft = send("POST", title_path, headers=a, status=201)
        other_draft = send("POST", title_path, headers=a, status=201)
    confirm = f"/stores/{sid}/title-drafts/{draft['draft_id']}/confirm"
    assert send("GET", f"/products/{products[0]['sku']}", headers=a)["title"] == products[0]["title"]
    send("POST", confirm, headers=b, status=404, json={"confirmed": True})
    applied = send("POST", confirm, headers=a, json={"confirmed": True})
    assert applied["status"] == "applied"
    assert send("POST", confirm, headers=a, json={"confirmed": True}) == applied
    send("POST", f"/stores/{sid}/title-drafts/{other_draft['draft_id']}/confirm", headers=a, status=409, json={"confirmed": True})
    assert send("GET", f"/products/{products[0]['sku']}", headers=a)["title"] == "Kitchen Storage Jar P1"
    assert send("GET", summary_path, headers=a, params=params) == baseline
    # 完整覆盖编辑价格、币种、库存以及未关联商品的204删除响应。
    disposable = send("POST", "/products", status=201, headers=a, json={
        "sku": prefix.replace("_", "-") + "FREE", "title": "Disposable", "price": "1.00", "currency": "USD", "stock": 1, "store_id": sid})
    edited = send("PUT", f"/products/{disposable['sku']}", headers=a, json={"title": "Edited", "price": "12.34", "currency": "EUR", "stock": 7})
    assert (edited["price"], edited["currency"], edited["stock"]) == ("12.34", "EUR", 7)
    response = client.delete(f"/products/{disposable['sku']}", headers=a)
    assert response.status_code == 204 and response.content == b""
    send("GET", f"/products/{disposable['sku']}", headers=a, status=404)
