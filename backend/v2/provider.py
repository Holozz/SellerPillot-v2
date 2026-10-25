"""Provider requests with finite timeouts, retries, circuit and shared concurrency."""
import hashlib
import math
import re
import time
from contextlib import contextmanager
import httpx
from v2.settings import value, redis_client
from v2.models import uid


class ProviderError(RuntimeError):
    pass


@contextmanager
def model_slot():
    client = redis_client(); ticket = uid(); key = 'provider:chat:slots'
    script = """redis.call('ZREMRANGEBYSCORE',KEYS[1],'-inf',ARGV[1]);
    if redis.call('ZCARD',KEYS[1]) >= tonumber(ARGV[3]) then return 0 end;
    redis.call('ZADD',KEYS[1],ARGV[2],ARGV[4]); redis.call('EXPIRE',KEYS[1],100); return 1"""
    try:
        if client.exists('provider:chat:open'):
            raise ProviderError('模型服务熔断中，请稍后重试')
        if not client.eval(script, 1, key, time.time(), time.time() + 90, int(value('MODEL_CONCURRENCY', '2')), ticket):
            raise ProviderError('模型调用并发已满')
    except ProviderError: raise
    except Exception as error: raise ProviderError('模型配额服务不可用') from error
    try:
        yield
        client.delete('provider:chat:failures')
    except (httpx.HTTPError, ProviderError):
        if client.incr('provider:chat:failures') >= 5:
            client.setex('provider:chat:open', 30, '1')
        client.expire('provider:chat:failures', 60)
        raise
    finally:
        try: client.zrem(key, ticket)
        except Exception: pass


def chat(messages, tools=None):
    if not value('LLM_API_KEY') or not value('LLM_MODEL'):
        raise ProviderError('请配置 LLM_API_KEY 和 LLM_MODEL，或使用明确的离线模式')
    with model_slot():
        for attempt in range(2):
            try:
                response = httpx.post(value('LLM_BASE_URL').rstrip('/') + '/chat/completions',
                    headers={'Authorization': 'Bearer ' + value('LLM_API_KEY')},
                    json={'model': value('LLM_MODEL'), 'messages': messages, 'max_tokens': 1200,
                          'stream': False, **({'tools': tools, 'tool_choice': 'auto'} if tools else {})}, timeout=45)
                response.raise_for_status()
                body = response.json()
                if body['choices'][0].get('finish_reason') not in ('stop', 'tool_calls'):
                    raise ProviderError('模型输出未正常完成')
                return body['choices'][0]['message'], body.get('usage')
            except httpx.HTTPStatusError as error:
                if error.response.status_code in (429, 502, 503, 504) and attempt == 0:
                    time.sleep(1); continue
                raise ProviderError(f'模型服务 HTTP {error.response.status_code}') from error
            except httpx.RequestError as error:
                if attempt == 0: continue
                raise ProviderError('模型请求失败或超时') from error


def embed(texts):
    backend = value('EMBEDDING_BACKEND', 'openai')
    if backend == 'hash':
        # Explicit offline fixture: deterministic lexical features, NOT semantic embeddings.
        vectors = []
        for text in texts:
            vector = [0.0] * 256
            for token in re.findall(r'[a-z0-9-]+|[\u4e00-\u9fff]', text.lower()):
                digest = hashlib.sha256(token.encode()).digest()
                vector[int.from_bytes(digest[:2], 'big') % 256] += 1 if digest[2] % 2 else -1
            norm = math.sqrt(sum(x*x for x in vector)) or 1
            vectors.append([x/norm for x in vector])
        return vectors
    if backend != 'openai' or not value('EMBEDDING_MODEL'):
        raise ProviderError('请配置 EMBEDDING_MODEL；hash 仅用于离线流程验证')
    response = httpx.post(value('EMBEDDING_BASE_URL', value('LLM_BASE_URL')).rstrip('/') + '/embeddings',
        headers={'Authorization': 'Bearer ' + value('EMBEDDING_API_KEY', value('LLM_API_KEY'))},
        json={'model': value('EMBEDDING_MODEL'), 'input': texts}, timeout=45)
    response.raise_for_status()
    data = sorted(response.json()['data'], key=lambda item: item['index'])
    if len(data) != len(texts): raise ProviderError('Embedding 返回数量不一致')
    return [item['embedding'] for item in data]


def rerank(query, texts):
    if not value('RERANK_URL'):
        return None
    response = httpx.post(value('RERANK_URL'), headers={'Authorization': 'Bearer ' + value('RERANK_API_KEY')},
        json={'model': value('RERANK_MODEL'), 'query': query, 'documents': texts}, timeout=30)
    response.raise_for_status()
    return response.json()['results']
