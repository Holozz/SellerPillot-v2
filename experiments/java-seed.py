"""Generate MySQL-only Java benchmark fixtures; keep credentials out of reports."""
import argparse
from datetime import datetime,timedelta,timezone
from decimal import Decimal
import json,random,secrets,time
from pathlib import Path
import httpx,pymysql
from dotenv import dotenv_values

ROOT=Path(__file__).resolve().parents[1]

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--orders',type=int,default=100000)
    parser.add_argument('--seed',type=int,default=42);parser.add_argument('--api',default='http://127.0.0.1:8081')
    parser.add_argument('--as-of',default='2026-10-25T00:00:00Z');args=parser.parse_args()
    if not 1<=args.orders<=1_000_000:parser.error('orders must be 1–1000000')
    env=dotenv_values(ROOT/'.env.java');rng=random.Random(args.seed);run=secrets.token_hex(4)
    username='bench_'+run;password=secrets.token_urlsafe(18)
    with httpx.Client(timeout=30,trust_env=False) as api:
        registration=api.post(args.api+'/auth/register',json={'username':username,'password':password});registration.raise_for_status()
        user=registration.json()['id']
    conn=pymysql.connect(host='127.0.0.1',port=3310,user=env['JAVA_DB_USER'],password=env['JAVA_DB_PASSWORD'],
        database='sellerpilot_java',charset='utf8mb4',autocommit=False)
    asof=datetime.fromisoformat(args.as_of.replace('Z','+00:00')).astimezone(timezone.utc).replace(tzinfo=None)
    stores=[];catalog={};started=time.perf_counter();item_count=0
    with conn.cursor() as cur:
        for shop in range(3):
            cur.execute('INSERT INTO stores(owner_id,name,platform) VALUES(%s,%s,%s)',(user,f'Benchmark-{run}-{shop}','amazon'));sid=cur.lastrowid;stores.append(sid);catalog[sid]={c:[] for c in ('USD','EUR','GBP')}
            for p in range(100):
                currency='USD' if p%10<7 else 'EUR' if p%10<9 else 'GBP';price=Decimal(rng.randint(500,10000))/100
                cur.execute('INSERT INTO products(store_id,sku,title,price,currency,stock) VALUES(%s,%s,%s,%s,%s,%s)',(sid,f'ITEM-{p:04}',f'Fixture product {p:04}',price,currency,rng.randrange(100)))
                catalog[sid][currency].append((cur.lastrowid,price))
        conn.commit()
        for begin in range(0,args.orders,2000):
            orders=[];items_by_id={}
            for i in range(begin,min(begin+2000,args.orders)):
                sid=rng.choices(stores,[80,15,5])[0];currency=rng.choices(['USD','EUR','GBP'],[70,20,10])[0]
                external=f'bench-{run}-{i}';at=asof-timedelta(seconds=rng.randrange(180*86400))
                orders.append((sid,external,at,rng.choices(['paid','shipped','pending','cancelled'],[60,25,10,5])[0],currency))
                candidates=catalog[sid][currency]
                products=rng.choices(candidates,weights=[10 if n<3 else 1 for n in range(len(candidates))],k=rng.randint(1,4))
                lines=[]
                for line,(pid,price) in enumerate(products,1):lines.append((pid,line,rng.randint(1,3),price,price if rng.random()<.03 else Decimal(0)))
                items_by_id[external]=(sid,lines)
            cur.executemany('INSERT INTO orders(store_id,external_order_id,ordered_at,status,currency) VALUES(%s,%s,%s,%s,%s)',orders)
            placeholders=','.join(['%s']*len(orders))
            cur.execute(f'SELECT external_order_id,id FROM orders WHERE external_order_id IN ({placeholders}) AND store_id IN (%s,%s,%s)',[row[1] for row in orders]+stores)
            ids=dict(cur.fetchall());items=[]
            for external,(sid,lines) in items_by_id.items():
                for pid,line,qty,price,refund in lines:items.append((sid,ids[external],pid,line,qty,price,refund))
            cur.executemany('INSERT INTO order_items(store_id,order_id,product_id,line_no,quantity,unit_price,refund_amount) VALUES(%s,%s,%s,%s,%s,%s,%s)',items);item_count+=len(items)
            cur.execute('UPDATE stores SET data_version=data_version+1 WHERE id IN (%s,%s,%s)',stores);conn.commit()
            if begin%100000==0:print(f'orders={min(begin+2000,args.orders)}/{args.orders}',flush=True)
        cur.execute('ANALYZE TABLE orders,order_items');cur.fetchall()
        cur.execute('SELECT store_id,COUNT(*) FROM orders WHERE store_id IN (%s,%s,%s) GROUP BY store_id',stores);distribution=dict(cur.fetchall())
    conn.close()
    private=ROOT/'.data/java-bench';private.mkdir(parents=True,exist_ok=True)
    (private/'credentials.json').write_text(json.dumps({'username':username,'password':password,'store_id':stores[0]}),encoding='utf-8')
    query={'metric':'sales','group_by':'product','start':(asof-timedelta(days=7)).isoformat()+'Z','end':asof.isoformat()+'Z','statuses':['paid','shipped'],'limit':20}
    (private/'query.json').write_text(json.dumps(query),encoding='utf-8')
    report={'scope':'synthetic MySQL fixtures, not production orders or a performance result','seed':args.seed,'as_of':args.as_of,
        'orders':args.orders,'order_items':item_count,'stores':stores,'store_distribution':distribution,'query_store_id':stores[0],
        'username':username,'elapsed_seconds':time.perf_counter()-started,'query':query}
    out=ROOT/'docs/verification/java/seed-manifest.json';out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(report,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k!='query'}),flush=True)

if __name__=='__main__':main()
