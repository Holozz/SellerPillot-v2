import argparse
import logging
import threading
import time
import httpx
from uuid import uuid4
from sqlalchemy.orm import Session
from sqlalchemy import update
from database import engine
from v2.jobs import claim, dispatch_once, reconcile, heartbeat, finish
from v2.knowledge import build_knowledge
from v2.runner import run_agent
from v2.settings import redis_client
from v2.observability import configure,span,log_event

logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(message)s')
logger = logging.getLogger('sellerpilot.worker')


def execute(job_id):
    with Session(engine) as session, session.begin(): task = claim(session, job_id)
    if not task: return
    stopped = threading.Event()
    def renew():
        while not stopped.wait(10):
            if not heartbeat(job_id, task['token']): break
    thread = threading.Thread(target=renew, daemon=True); thread.start()
    from opentelemetry import context
    from opentelemetry.propagate import extract
    context_token=context.attach(extract(task.get('trace_context') or {}))
    try:
        with span('job.execute',job_id=job_id,attempt_token=task['token'],task_kind=task['kind']):
            result = build_knowledge(task) if task['kind'] == 'build' else run_agent(task)
        finish(job_id, task['token'], result=result)
        log_event('job_completed',job_id=job_id,token=task['token'])
    except Exception as error:
        logger.exception('job_failed job_id=%s token=%s', job_id, task['token'])
        if task['kind']=='build':
            from v2.models import IndexBuild
            with Session(engine) as session,session.begin():
                session.execute(update(IndexBuild).where(IndexBuild.job_id==job_id,
                    IndexBuild.token==task['token'],IndexBuild.status=='building').values(status='failed'))
        retryable=isinstance(error,(ConnectionError,TimeoutError,httpx.RequestError))
        if isinstance(error,httpx.HTTPStatusError): retryable=error.response.status_code in (429,502,503,504)
        finish(job_id, task['token'], error=error, retryable=retryable)
    finally:
        context.detach(context_token)
        stopped.set(); thread.join(timeout=2)


def main():
    parser = argparse.ArgumentParser(); parser.add_argument('kind', choices=['agent','build','control'])
    parser.add_argument('--once', action='store_true'); args = parser.parse_args()
    configure(None,engine)
    client = redis_client(queue=True); consumer = uuid4().hex
    if args.kind != 'control':
        stream = f'sellerpilot:{args.kind}'
        try: client.xgroup_create(stream, 'workers', id='0', mkstream=True)
        except Exception as error:
            if 'BUSYGROUP' not in str(error): raise
    while True:
        try:
            if args.kind == 'control':
                reconcile()
                for _ in range(100):
                    if not dispatch_once(): break
            else:
                stale = client.xautoclaim(stream, 'workers', consumer, min_idle_time=60000, start_id='0-0', count=10)
                entries = stale[1] or []
                if not entries:
                    batches = client.xreadgroup('workers', consumer, {stream:'>'}, count=1, block=1000)
                    entries = batches[0][1] if batches else []
                for message_id, fields in entries:
                    execute(fields['job_id']); client.xack(stream,'workers',message_id)
        except Exception:
            logger.exception('worker_cycle_failed kind=%s', args.kind)
        if args.once: break
        if args.kind == 'control': time.sleep(1)


if __name__ == '__main__': main()
