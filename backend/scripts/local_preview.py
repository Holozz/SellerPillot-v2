"""Explicit single-process preview when containers are unavailable. NOT a queue/load benchmark."""
import os
from pathlib import Path
import sys
import threading
import time
root=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(root/'backend'))
data=root/'.data'/'preview';data.mkdir(parents=True,exist_ok=True)
os.environ['DATABASE_URL']='sqlite:///'+str(data/'preview.db')
os.environ['OBJECT_STORE']='local'
os.environ['LOCAL_OBJECT_PATH']=str(data/'objects')
os.environ['QDRANT_LOCAL_PATH']=str(data/'qdrant')
os.environ['EMBEDDING_BACKEND']='hash'
os.environ['EMBEDDING_MODEL']=''
os.environ.setdefault('JWT_SECRET_KEY','local-preview-only-not-for-shared-deployment-2026')
from sqlalchemy import select
from sqlalchemy.orm import Session
from database import Base,engine
from models import User,Store,Product
import v2.models
from v2.models import Job,now
from v2.worker import execute
from secuity import hash_password


def main():
    import uvicorn
    password=os.getenv('DEMO_PASSWORD')
    if not password or len(password)<8: raise SystemExit('请设置 DEMO_PASSWORD（至少8字符），用户名为 demo')
    Base.metadata.create_all(engine)
    with Session(engine) as db:
        user=db.scalar(select(User).where(User.username=='demo'))
        if not user:
            user=User(username='demo',password_hash=hash_password(password));db.add(user);db.flush()
            store=Store(owner_id=user.id,name='预览店铺',platform='amazon');db.add(store);db.flush()
            db.add(Product(store_id=store.id,sku='JAR-001',title='Glass Storage Jar 500ml',price=19.90,currency='USD',stock=8))
            db.commit()
    def worker():
        while True:
            try:
                with Session(engine) as db:
                    jid=db.scalar(select(Job.id).where(Job.status.in_(['queued','retry_wait']),Job.available_at<=now()).order_by(Job.created_at).limit(1))
                if jid:execute(jid)
            except Exception:
                import logging
                logging.exception('preview_worker_error')
            time.sleep(0.5)
    threading.Thread(target=worker,daemon=True).start()
    print('PREVIEW: SQLite + local files + lexical hash vectors + in-process worker. Not MySQL/Redis or semantic evaluation.',flush=True)
    uvicorn.run('main:app',host='127.0.0.1',port=8002)


if __name__=='__main__':main()
