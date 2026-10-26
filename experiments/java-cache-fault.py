"""Real two-process rebuild and Java-only Redis outage experiment.

Requires cache-enabled APIs on 8081/8082; touches only the generated bench store.
"""
import argparse,concurrent.futures,json,subprocess,threading,time
from pathlib import Path
import httpx,pymysql
from dotenv import dotenv_values

ROOT=Path(__file__).resolve().parents[1]
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--outage',action='store_true');args=parser.parse_args()
    credentials=json.loads((ROOT/'.data/java-bench/credentials.json').read_text())
    query=json.loads((ROOT/'.data/java-bench/query.json').read_text());env=dotenv_values(ROOT/'.env.java')
    bases=['http://127.0.0.1:8081','http://127.0.0.1:8082'];report={'apis':bases,'scope':'synthetic fixture, two actual JVM processes, contention not sustained throughput'}
    with httpx.Client(timeout=15,trust_env=False) as api:
        login=api.post(bases[0]+'/auth/login',json={k:credentials[k] for k in ('username','password')});login.raise_for_status()
        headers={'Authorization':'Bearer '+login.json()['access_token']}
        def counter(base):
            response=api.get(base+'/actuator/metrics/sellerpilot.aggregate.sql',headers=headers)
            return response.json()['measurements'][0]['value'] if response.status_code==200 else 0
        path=f"/api/v2/stores/{credentials['store_id']}/metrics/query"
        sample=api.post(bases[0]+path,json=query,headers=headers);sample.raise_for_status();expected=sample.json()['rows']
        conn=pymysql.connect(host='127.0.0.1',port=3310,user=env['JAVA_DB_USER'],password=env['JAVA_DB_PASSWORD'],database='sellerpilot_java',autocommit=True)
        with conn.cursor() as cursor:cursor.execute('UPDATE stores SET data_version=data_version+1 WHERE id=%s',(credentials['store_id'],))
        conn.close()
        before=[counter(base) for base in bases]
        stopped=False
        try:
            if args.outage:
                subprocess.run(['docker','compose','--env-file','.env.java','-f','compose.java.yaml','stop','redis'],cwd=ROOT,check=True,capture_output=True);stopped=True
            barrier=threading.Barrier(40)
            def request(i):
                barrier.wait();start=time.perf_counter()
                response=api.post(bases[i%2]+path,json=query,headers=headers)
                correct=response.status_code==200 and response.json()['rows']==expected
                return {'status':response.status_code,'correct':correct,'seconds':time.perf_counter()-start}
            with concurrent.futures.ThreadPoolExecutor(max_workers=40) as pool:results=list(pool.map(request,range(40)))
            after=[counter(base) for base in bases]
            report.update(redis_outage=args.outage,requests=results,aggregate_sql_delta=sum(after)-sum(before),counter_before=before,counter_after=after)
            if args.outage:
                assert all(r['correct'] or r['status']==503 for r in results),report
            else:
                assert all(r['correct'] for r in results),report
                assert report['aggregate_sql_delta']==1,report
        finally:
            if stopped:subprocess.run(['docker','compose','--env-file','.env.java','-f','compose.java.yaml','start','redis'],cwd=ROOT,check=True,capture_output=True)
            out=ROOT/'docs/verification/java'/('redis-outage.json' if args.outage else 'two-jvm-rebuild.json')
            out.write_text(json.dumps(report,indent=2))
        if args.outage:
            time.sleep(2)
            recovery=api.post(bases[0]+path,json=query,headers=headers)
            report['recovery_status']=recovery.status_code
            report['recovery_correct']=recovery.status_code==200 and recovery.json()['rows']==expected
            out.write_text(json.dumps(report,indent=2));assert report['recovery_correct']
        print(json.dumps({k:v for k,v in report.items() if k!='requests'}))
if __name__=='__main__':main()
