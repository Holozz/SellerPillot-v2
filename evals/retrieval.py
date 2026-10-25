"""Evaluate frozen, human-reviewed JSONL: question, relevant_chunk_ids, answerable, split."""
import argparse
import json
from pathlib import Path
import statistics
import sys
import time
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'backend'))
from sqlalchemy.orm import Session
from database import engine
from v2.knowledge import retrieve
from v2.settings import value


def evaluate(path,user_id,store_id,kb_id,split):
    cases=[json.loads(line) for line in Path(path).read_text(encoding='utf-8').splitlines() if line.strip()]
    cases=[c for c in cases if c['split']==split]
    if not cases: raise ValueError('所选分组没有题目')
    rows=[]
    for strategy in ('bm25','vector','hybrid','rerank'):
        for case in cases:
            with Session(engine) as db:
                started=time.perf_counter();result=retrieve(db,user_id,store_id,kb_id,case['question'],strategy,5)
                seconds=time.perf_counter()-started
            ranked=[e['chunk_id'] for e in result['evidence']];relevant=set(case['relevant_chunk_ids'])
            recall=len(set(ranked)&relevant)/len(relevant) if relevant else None
            rr=next((1/(i+1) for i,cid in enumerate(ranked) if cid in relevant),0) if relevant else None
            rows.append({'case_id':case['id'],'strategy':strategy,'recall_at_5':recall,'reciprocal_rank':rr,
                'answered':bool(ranked),'answerable':case['answerable'],'seconds':seconds,
                'degraded':result['degraded'],'rerank_applied':result.get('rerank_applied',False),
                'build_id':result.get('build_id'),'chunk_ids':ranked})
    summary={}
    for strategy in ('bm25','vector','hybrid','rerank'):
        group=[r for r in rows if r['strategy']==strategy]
        answerable=[r for r in group if r['recall_at_5'] is not None]
        summary[strategy]={'questions':len(group),'recall_at_5':statistics.mean(r['recall_at_5'] for r in answerable) if answerable else None,
            'mrr':statistics.mean(r['reciprocal_rank'] for r in answerable) if answerable else None,
            'mean_seconds':statistics.mean(r['seconds'] for r in group),
            'degraded_cases':sum(r['degraded'] for r in group),'rerank_applied_cases':sum(r['rerank_applied'] for r in group),
            'no_answer_false_positive':sum(r['answered'] and not r['answerable'] for r in group)}
    return {'split':split,'embedding_backend':value('EMBEDDING_BACKEND','openai'),
        'quality_claim_allowed':value('EMBEDDING_BACKEND','openai')!='hash','summary':summary,'rows':rows}


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--dataset',required=True);parser.add_argument('--user-id',type=int,required=True)
    parser.add_argument('--store-id',type=int,required=True);parser.add_argument('--kb-id',required=True)
    parser.add_argument('--split',choices=['dev','test'],default='dev');parser.add_argument('--output',required=True)
    args=parser.parse_args();result=evaluate(args.dataset,args.user_id,args.store_id,args.kb_id,args.split)
    Path(args.output).write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
