"""应用入口：只负责注册路由和基础连通性接口。"""
from fastapi import FastAPI
from sqlalchemy import text
from database import engine
from routers import auth, stores, products, imports, analytics, agent, titles

app = FastAPI(swagger_ui_parameters={"persistAuthorization": True})
from v2 import models as v2_models
from v2.api import router as v2_router
app.include_router(v2_router)

from fastapi.responses import JSONResponse, Response
from sqlalchemy.exc import IntegrityError
from prometheus_client import Counter, Histogram, Gauge, generate_latest, CONTENT_TYPE_LATEST
from uuid import uuid4
import time
import logging

REQUESTS = Counter('sellerpilot_requests_total', 'HTTP requests', ['method', 'route', 'status'])
LATENCY = Histogram('sellerpilot_request_seconds', 'HTTP latency', ['method', 'route'])
JOB_COUNTS = Gauge('sellerpilot_jobs', 'Persisted jobs by state', ['status'])
DATABASE_UP = Gauge('sellerpilot_database_up','Whether the last metrics DB probe succeeded')
from v2.observability import configure,log_event
configure(app,engine)

@app.exception_handler(IntegrityError)
async def integrity_error(request, error):
    return JSONResponse(status_code=409, content={'detail':'请求违反唯一性或关联约束'})

@app.middleware('http')
async def trace_request(request, call_next):
    request_id=uuid4().hex; started=time.monotonic()
    response=await call_next(request)
    route=getattr(request.scope.get('route'),'path','unmatched')
    REQUESTS.labels(request.method,route,str(response.status_code)).inc()
    LATENCY.labels(request.method,route).observe(time.monotonic()-started)
    response.headers['X-Request-ID']=request_id
    log_event('http_request',request_id=request_id,method=request.method,route=route,
              status=response.status_code,seconds=round(time.monotonic()-started,4))
    return response

@app.get('/health')
def health():
    from v2.settings import value
    return {'status':'ok','version':'2.0','embedding_backend':value('EMBEDDING_BACKEND','openai')}

@app.get('/metrics', include_in_schema=False)
def metrics():
    from sqlalchemy.orm import Session
    from sqlalchemy import select,func
    from v2.models import Job
    try:
        with Session(engine) as db:
            counts=dict(db.execute(select(Job.status,func.count()).group_by(Job.status)).all())
        for status in ('queued','running','retry_wait','succeeded','failed','cancelled'):
            JOB_COUNTS.labels(status).set(counts.get(status,0))
        DATABASE_UP.set(1)
    except Exception:
        DATABASE_UP.set(0)
    return Response(generate_latest(),media_type=CONTENT_TYPE_LATEST)
for module in (auth, stores, products, imports, analytics, agent, titles):
    app.include_router(module.router)

@app.get("/database-check")
def database_check():
    with engine.connect() as connection:
        result = connection.execute(text("SELECT 1"))
        value = result.scalar_one()
    return {"database": "mysql", "result": value}


@app.get("/hello")
def hello():
    return {"message": "Hello, SellerPilot"}


@app.get("/greet")
def greet(name: str):
    return {"message": f"Hello, {name}"}

