import os
from pathlib import Path
from dotenv import load_dotenv

ROOT = Path(__file__).resolve().parents[2]
load_dotenv(ROOT / '.env')


def value(name, default=''):
    return os.getenv(name, default)


def redis_client(queue=False):
    from redis import Redis
    return Redis.from_url(value('QUEUE_REDIS_URL' if queue else 'CACHE_REDIS_URL',
                                'redis://localhost:6381/0' if queue else 'redis://localhost:6382/0'),
                          decode_responses=True, socket_connect_timeout=2, socket_timeout=3)
