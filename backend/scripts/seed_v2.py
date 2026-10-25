"""Reproducible business fixtures. No model calls, no fabricated real-user volume."""
import argparse
from datetime import datetime, timedelta, timezone
from decimal import Decimal
import json
import os
from pathlib import Path
import random
import sys
import time
from uuid import uuid4
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from sqlalchemy import insert, select, update
from sqlalchemy.orm import Session
from database import engine, Base
from models import User, Store, Product, Order, OrderItem
from secuity import hash_password
import v2.models


def seed(orders,seed_value,username,password,as_of,batch_size=2000):
    if engine.dialect.name!='sqlite' and engine.url.database not in ('sellerpilot_v2','sellerpilot_v2_test'):
        raise RuntimeError('造数仅允许写入独立 sellerpilot_v2 / sellerpilot_v2_test 库')
    rng=random.Random(seed_value);tag=uuid4().hex[:10];started=time.perf_counter()
    with Session(engine) as db:
        user=db.scalar(select(User).where(User.username==username))
        if not user:
            user=User(username=username,password_hash=hash_password(password));db.add(user);db.flush()
        shops=[];catalog={}
        for i in range(3):
            shop=Store(name=f'模拟店铺 {tag}-{i+1}',platform='amazon',owner_id=user.id)
            db.add(shop);db.flush();shops.append(shop.id)
            catalog[shop.id]=[]
            for p in range(100):
                product=Product(store_id=shop.id,sku=f'ITEM-{p:04}',title=f'演示商品 {p:04}',
                    price=Decimal(rng.randint(500,10000))/100,currency='USD',stock=rng.randrange(0,100))
                db.add(product);db.flush();catalog[shop.id].append((product.id,product.price))
        db.commit()
    for start in range(0,orders,batch_size):
        rows=[];line_data={}
        for i in range(start,min(start+batch_size,orders)):
            sid=rng.choices(shops,weights=[80,15,5])[0];external=f'synthetic-{tag}-{i}'
            timestamp=as_of-timedelta(seconds=rng.randrange(180*86400))
            rows.append({'store_id':sid,'external_order_id':external,'ordered_at':timestamp,
                'status':rng.choices(['paid','shipped','pending','cancelled'],[60,25,10,5])[0],'currency':'USD'})
            # Popular products are deliberately skewed; rare SKUs and mixed line counts remain.
            products=rng.choices(catalog[sid],weights=[20 if p<10 else 1 for p in range(100)],k=rng.randint(1,4))
            line_data[external]=(sid,products)
        with Session(engine) as db,db.begin():
            db.execute(insert(Order),rows)
            ids=dict(db.execute(select(Order.external_order_id,Order.id).where(
                Order.external_order_id.in_(list(line_data)),Order.store_id.in_(shops))).all())
            items=[]
            for external,(sid,products) in line_data.items():
                for line,(pid,price) in enumerate(products,1):
                    qty=rng.randint(1,3)
                    items.append({'store_id':sid,'order_id':ids[external],'product_id':pid,'line_no':line,
                        'quantity':qty,'unit_price':price,'refund_amount':price if rng.random()<0.03 else Decimal(0)})
            db.execute(insert(OrderItem),items)
            db.execute(update(Store).where(Store.id.in_(shops)).values(data_version=Store.data_version+1))
        if (start//batch_size)%25==0: print(f'orders={min(start+batch_size,orders)}/{orders}',flush=True)
    return {'kind':'synthetic-business-data','seed':seed_value,'tag':tag,'as_of':as_of.isoformat(),
        'orders':orders,'stores':shops,'products_per_store':100,'username':username,
        'elapsed_seconds':round(time.perf_counter()-started,3),'database_dialect':engine.dialect.name,
        'scope':'仅为合成数据，不代表真实交易量；不构成性能测试结果'}


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--orders',type=int,default=10000)
    parser.add_argument('--seed',type=int,default=42);parser.add_argument('--username',default='demo')
    parser.add_argument('--as-of',default=datetime.now(timezone.utc).isoformat())
    parser.add_argument('--output',default='docs/verification/seed-manifest.json')
    args=parser.parse_args()
    if not 0<=args.orders<=2_000_000: parser.error('orders 必须在 0–2000000 之间')
    password=os.getenv('DEMO_PASSWORD')
    if not password or len(password)<8: parser.error('请通过 DEMO_PASSWORD 环境变量指定演示账号密码（至少8字符）')
    as_of=datetime.fromisoformat(args.as_of.replace('Z','+00:00')).astimezone(timezone.utc).replace(tzinfo=None)
    result=seed(args.orders,args.seed,args.username,password,as_of)
    target=Path(args.output);target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(result,ensure_ascii=False))
