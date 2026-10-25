"""Record baseline and indexed query plans without silently changing schema/indexes."""
import argparse
import json
from pathlib import Path
import sys
import time
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'backend'))
from sqlalchemy import text
from database import engine

parser=argparse.ArgumentParser();parser.add_argument('--store-id',type=int,required=True)
parser.add_argument('--start',required=True);parser.add_argument('--end',required=True)
parser.add_argument('--output',required=True);args=parser.parse_args()
if engine.dialect.name!='mysql': raise SystemExit('执行计划实验必须在 MySQL 上执行，SQLite 不能代替')
query='''SELECT o.currency, SUM(i.quantity*i.unit_price-i.refund_amount) AS sales
FROM orders o JOIN order_items i ON i.order_id=o.id AND i.store_id=o.store_id
WHERE o.store_id=:sid AND o.status IN ('paid','shipped') AND o.ordered_at>=:start AND o.ordered_at<:end
GROUP BY o.currency'''
parameters={'sid':args.store_id,'start':args.start,'end':args.end}
with engine.connect() as db:
    started=time.perf_counter();result=[dict(row) for row in db.execute(text(query),parameters).mappings()]
    elapsed=time.perf_counter()-started
    plan=[list(row) for row in db.execute(text('EXPLAIN ANALYZE '+query),parameters)]
    indexes=[list(row) for row in db.execute(text('SHOW INDEX FROM orders'))]
report={'scope':'单次 SQL 实验，不能当作 p95 或 API 负载测试','parameters':parameters,'elapsed_seconds':elapsed,
        'result':result,'plan':plan,'indexes':indexes,'mysql_version':db.scalar(text('SELECT VERSION()'))}
Path(args.output).write_text(json.dumps(report,default=str,ensure_ascii=False,indent=2),encoding='utf-8')
