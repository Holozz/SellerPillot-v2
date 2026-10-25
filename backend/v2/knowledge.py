import hashlib
import io
import json
import re
from functools import lru_cache
from pathlib import Path
from uuid import UUID
from fastapi import HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session
from qdrant_client import QdrantClient, models as qm
from rank_bm25 import BM25Okapi
from database import engine
from v2.auth import authorize
from v2.models import KnowledgeBase, Document, DocumentVersion, IndexBuild, Chunk, uid
from v2.jobs import locked_job, emit
from v2.provider import embed, rerank
from v2.settings import value
from v2.storage import ObjectStore


def tokens(text):
    # CJK unigram/bigram + whole ASCII SKU tokens, reproducible without dictionary state.
    words = re.findall(r'[a-z0-9]+(?:-[a-z0-9]+)*|[\u4e00-\u9fff]+', text.lower())
    result = []
    for word in words:
        if re.match('[\u4e00-\u9fff]', word):
            result += list(word) + [word[i:i+2] for i in range(len(word)-1)]
        else: result.append(word)
    return result


def parse(filename, data):
    suffix = Path(filename).suffix.lower()
    if suffix in ('.txt', '.md'):
        return [('正文', data.decode('utf-8-sig'))]
    if suffix == '.pdf':
        from pypdf import PdfReader
        return [(f'第 {i+1} 页', page.extract_text() or '') for i, page in enumerate(PdfReader(io.BytesIO(data)).pages)]
    if suffix == '.docx':
        from docx import Document as Docx
        doc = Docx(io.BytesIO(data))
        return [('正文', '\n'.join(p.text for p in doc.paragraphs))]
    raise ValueError('仅支持 UTF-8 TXT/Markdown、文本 PDF 和 DOCX')


def split_sections(sections, size=600, overlap=80):
    result = []
    for location, text in sections:
        text = text.replace('\x00', '').strip()
        for paragraph in re.split(r'\n\s*\n|(?=^#{1,4} )', text, flags=re.M):
            paragraph = paragraph.strip()
            for start in range(0, len(paragraph), size-overlap):
                fragment = paragraph[start:start+size].strip()
                if fragment: result.append((f'{location} · 字符 {start}–{start+len(fragment)}', fragment))
    if not result: raise ValueError('未提取到文本；扫描文档需要 OCR，当前不支持')
    return result


@lru_cache(maxsize=1)
def qdrant():
    if value('QDRANT_LOCAL_PATH'):
        return QdrantClient(path=value('QDRANT_LOCAL_PATH'), force_disable_check_same_thread=True)
    return QdrantClient(url=value('QDRANT_URL', 'http://localhost:6335'), timeout=20)


def collection_name():
    model = value('EMBEDDING_MODEL') if value('EMBEDDING_BACKEND') != 'hash' else 'offline-hash-256'
    return 'sp_' + hashlib.sha256(model.encode()).hexdigest()[:12]


def upload(session, user_id, store_id, kb_id, filename, data, document_id=None):
    """Owns its read transaction; file IO is outside DB locks, final metadata awaits caller commit."""
    authorize(session, user_id, store_id, 'knowledge')
    kb = session.scalar(select(KnowledgeBase).where(KnowledgeBase.id == kb_id,
        KnowledgeBase.store_id == store_id).with_for_update())
    if not kb: raise HTTPException(404, '知识库不存在')
    if not data or len(data) > 10*1024*1024: raise HTTPException(422, '文件须在 1 字节至 10MB 内')
    if Path(filename).suffix.lower() not in ('.md', '.txt', '.pdf', '.docx'):
        raise HTTPException(422, '不支持的文件格式')
    if document_id:
        document = session.scalar(select(Document).where(Document.id == document_id, Document.kb_id == kb_id))
        if not document: raise HTTPException(404, '资料不存在')
        did=document.id; previous_version=document.current_version_id
    else:
        did=uid(); previous_version=None
    version_id = uid(); key = f'{store_id}/{kb_id}/{did}/{version_id}/original'
    session.rollback()
    ObjectStore().put(key, data)
    authorize(session,user_id,store_id,'knowledge')
    kb=session.scalar(select(KnowledgeBase).where(KnowledgeBase.id==kb_id,KnowledgeBase.store_id==store_id).with_for_update())
    if not kb: raise HTTPException(404,'知识库已删除')
    if document_id:
        document=session.scalar(select(Document).where(Document.id==did,Document.kb_id==kb_id).with_for_update())
        if not document or document.current_version_id!=previous_version:
            raise HTTPException(409,'上传期间资料已变化，请重新确认替换版本')
    else:
        document=Document(id=did,kb_id=kb_id,store_id=store_id,name=Path(filename).name[:200])
        session.add(document);session.flush()
    version = DocumentVersion(id=version_id, document_id=document.id, object_key=key,
        filename=Path(filename).name[:200], digest=hashlib.sha256(data).hexdigest())
    session.add(version); document.current_version_id = version_id; document.active = True
    kb.revision += 1; kb.visibility_version += 1; session.flush()
    return document


def build_knowledge(task):
    kb_id = task['payload']['kb_id']; bid = uid(); store_id = task['store_id']; obj = ObjectStore()
    with Session(engine) as session, session.begin():
        locked_job(session, task['id'], task['token'])
        kb = session.get(KnowledgeBase, kb_id)
        if not kb or kb.store_id != store_id: raise ValueError('知识库不存在')
        revision = kb.revision
        docs = session.execute(select(Document, DocumentVersion).join(DocumentVersion,
            DocumentVersion.id == Document.current_version_id).where(Document.kb_id == kb.id, Document.active.is_(True))).all()
        sources = [{'document_id': d.id, 'version_id': v.id, 'key': v.object_key,
                    'filename': v.filename, 'digest': v.digest} for d,v in docs]
        manifest = {'sources': sources, 'embedding_backend': value('EMBEDDING_BACKEND', 'openai'),
                    'embedding_model': value('EMBEDDING_MODEL'), 'collection': collection_name(),
                    'parser_version': 1, 'chunker': {'size': 600, 'overlap': 80}, 'build_id': bid}
        session.add(IndexBuild(id=bid, kb_id=kb.id, job_id=task['id'], token=task['token'],
                               revision=revision, manifest=manifest))
    chunks = []
    for source in sources:
        for position, (location, text) in enumerate(split_sections(parse(source['filename'], obj.get(source['key'])))):
            chunks.append({'id': uid(), 'build_id': bid, 'document_id': source['document_id'],
                'version_id': source['version_id'], 'position': position, 'location': location, 'text': text})
    client = qdrant(); collection = manifest['collection']
    for start in range(0, len(chunks), 32):
        batch = chunks[start:start+32]; vectors = embed([c['text'] for c in batch])
        if not client.collection_exists(collection):
            client.create_collection(collection, vectors_config=qm.VectorParams(size=len(vectors[0]), distance=qm.Distance.COSINE))
        client.upsert(collection, points=[qm.PointStruct(id=str(UUID(c['id'])), vector=vector,
            payload={'store_id': store_id, 'build_id': bid, 'chunk_id': c['id']}) for c,vector in zip(batch,vectors)], wait=True)
        emit(task['id'], task['token'], 'progress', {'phase': 'embedding', 'completed': min(start+32,len(chunks)), 'total': len(chunks)})
    obj.put(f'{store_id}/builds/{bid}/manifest.json', json.dumps(manifest).encode())
    obj.put(f'{store_id}/builds/{bid}/bm25.json', json.dumps(chunks, ensure_ascii=False).encode())
    with Session(engine) as session, session.begin():
        locked_job(session, task['id'], task['token'])
        kb = session.scalar(select(KnowledgeBase).where(KnowledgeBase.id == kb_id).with_for_update())
        build = session.get(IndexBuild, bid)
        superseded=kb.revision != revision
        if superseded:
            build.status = 'superseded'
        else:
            session.add_all([Chunk(**c) for c in chunks]); build.status = 'published'
            kb.active_build_id = bid; kb.revision += 1
    if superseded: raise ValueError('构建期间资料已变化，请重新构建')
    return {'build_id': bid, 'chunk_count': len(chunks), 'embedding_backend': manifest['embedding_backend']}


def retrieve(session, user_id, store_id, kb_id, query, strategy='hybrid', limit=5):
    authorize(session, user_id, store_id)
    kb = session.get(KnowledgeBase, kb_id, populate_existing=True)
    if not kb or kb.store_id != store_id: raise HTTPException(404, '知识库不存在')
    if not kb.active_build_id: return {'evidence': [], 'reason': '知识库尚未发布', 'degraded': False}
    bid = kb.active_build_id; build = session.get(IndexBuild, bid)
    docs = session.execute(select(Chunk, Document).join(Document, Document.id == Chunk.document_id)
        .where(Chunk.build_id == bid, Document.active.is_(True))).all()
    # A replaced document's old version is not current evidence even before the next publication.
    chunks = [c for c,d in docs if c.version_id == d.current_version_id]
    if not chunks: return {'evidence': [], 'reason': '无可见资料', 'degraded': False}
    # Detach the fixed snapshot and return the DB connection before external HTTP requests.
    session.expunge_all(); session.rollback()
    corpus = [tokens(c.text) for c in chunks]; query_tokens = tokens(query)
    bm25 = BM25Okapi(corpus); raw_scores = bm25.get_scores(query_tokens)
    lexical = [chunks[i].id for i in sorted(range(len(chunks)), key=lambda i: raw_scores[i], reverse=True)
               if set(corpus[i]) & set(query_tokens)][:20]
    vector = []; degraded = False
    if strategy != 'bm25':
        try:
            if build.manifest['embedding_backend'] != value('EMBEDDING_BACKEND', 'openai') or build.manifest['embedding_model'] != value('EMBEDDING_MODEL'):
                raise ValueError('Embedding configuration differs from published build')
            found = qdrant().query_points(build.manifest['collection'], query=embed([query])[0],
                query_filter=qm.Filter(must=[qm.FieldCondition(key='store_id', match=qm.MatchValue(value=store_id)),
                    qm.FieldCondition(key='build_id', match=qm.MatchValue(value=bid))]), limit=20, score_threshold=0.3).points
            allowed = {c.id for c in chunks}
            vector = [point.payload['chunk_id'] for point in found if point.payload['chunk_id'] in allowed]
        except Exception:
            degraded = True
    rankings = [lexical] if strategy == 'bm25' or degraded else [vector] if strategy == 'vector' else [lexical, vector]
    scores = {}
    for ranking in rankings:
        for rank, cid in enumerate(ranking, 1): scores[cid] = scores.get(cid, 0) + 1/(60+rank)
    ordered = sorted(scores, key=scores.get, reverse=True)[:20]; by_id = {c.id:c for c in chunks}
    rerank_applied = False
    if strategy == 'rerank' and ordered:
        try:
            results = rerank(query, [by_id[cid].text for cid in ordered])
            if results is not None:
                ordered = [ordered[r['index']] for r in sorted(results, key=lambda r:r['relevance_score'], reverse=True)]
                rerank_applied = True
        except Exception: degraded = True
    evidence = [{'chunk_id': c.id, 'document_id': c.document_id, 'version_id': c.version_id,
        'build_id': bid, 'location': c.location, 'text': c.text} for c in [by_id[cid] for cid in ordered[:limit]]]
    authorize(session, user_id, store_id)
    visible = {d.id:d.current_version_id for d in session.scalars(select(Document).where(
        Document.store_id == store_id, Document.kb_id == kb_id, Document.active.is_(True)))}
    evidence = [source for source in evidence if visible.get(source['document_id']) == source['version_id']]
    return {'evidence': evidence, 'build_id': bid, 'strategy': strategy, 'degraded': degraded,
            'rerank_applied': rerank_applied, 'embedding_backend': build.manifest['embedding_backend'],
            'reason': None if evidence else '无有效证据，请补充资料或明确问题'}
