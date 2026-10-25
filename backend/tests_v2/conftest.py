import os
import sys
from pathlib import Path
import pytest
from sqlalchemy import create_engine, event
from sqlalchemy.orm import Session
from fastapi.testclient import TestClient

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
os.environ.setdefault('JWT_SECRET_KEY','test-only-secret-with-at-least-thirty-two-bytes')
from database import Base, get_db
from main import app
from models import User, Store, Product
from secuity import create_access_token


@pytest.fixture
def world(tmp_path,monkeypatch):
    integration_url=os.getenv('SP_TEST_DATABASE_URL')
    engine=create_engine(integration_url or 'sqlite:///'+str(tmp_path/'test.db'),
        **({} if integration_url else {'connect_args':{'check_same_thread':False}}))
    if integration_url:
        assert engine.url.database=='sellerpilot_v2_test', '仅允许专用 v2 测试库'
        Base.metadata.drop_all(engine)
    else:
        @event.listens_for(engine,'connect')
        def foreign_keys(conn,record): conn.execute('PRAGMA foreign_keys=ON')
    Base.metadata.create_all(engine)
    import v2.jobs, v2.knowledge, v2.runner, v2.worker, v2.api
    for module in (v2.jobs,v2.knowledge,v2.runner,v2.worker,v2.api): monkeypatch.setattr(module,'engine',engine)
    monkeypatch.setenv('OBJECT_STORE','local');monkeypatch.setenv('LOCAL_OBJECT_PATH',str(tmp_path/'objects'))
    monkeypatch.setenv('EMBEDDING_BACKEND','hash');monkeypatch.setenv('EMBEDDING_MODEL','')
    def sessions():
        with Session(engine) as session: yield session
    app.dependency_overrides[get_db]=sessions
    with Session(engine) as db:
        a=User(username='owner',password_hash='unused');b=User(username='outsider',password_hash='unused')
        db.add_all([a,b]);db.flush()
        store=Store(owner_id=a.id,name='Test',platform='amazon');db.add(store);db.flush()
        p=Product(store_id=store.id,sku='ABC-001',title='Storage Jar',price=10,currency='USD',stock=8)
        db.add(p);db.commit();ids=(a.id,b.id,store.id,p.id)
    owner,outsider,sid,pid=ids
    with TestClient(app) as client:
        yield {'engine':engine,'client':client,'owner':owner,'outsider':outsider,'sid':sid,'pid':pid,
               'headers':{'Authorization':'Bearer '+create_access_token(owner)},
               'other_headers':{'Authorization':'Bearer '+create_access_token(outsider)},
               'base':f'/api/v2/stores/{sid}'}
    app.dependency_overrides.clear();engine.dispose()
