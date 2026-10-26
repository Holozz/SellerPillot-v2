"""Collect sanitized evidence after load tests; never serialize environment secrets."""
import hashlib,json,os,platform,shutil,subprocess
from datetime import datetime,timezone
from pathlib import Path
import httpx,pymysql
from dotenv import dotenv_values

ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'docs/verification/java'
def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def main():
    env=dotenv_values(ROOT/'.env.java');credentials=json.loads((ROOT/'.data/java-bench/credentials.json').read_text())
    context={'collected_at':datetime.now(timezone.utc).isoformat(),'scope':'local synthetic-data test; shared Windows/Docker host, not production SLA',
             'os':platform.platform(),'logical_cpus':os.cpu_count(),'java_pool':{'hikari_max':12,'aggregation_permits_per_process':2,'tomcat_threads':64,'accept_count':128},'cache_ttl_seconds':120,
             'jvm_options':'JDK defaults; no GC/heap tuning','k6_image':'grafana/k6:1.3.0','stages':'J0/J1 cache disabled; J2J3 cache and rebuild protection enabled together, no separate rebuild ablation'}
    info=json.loads(subprocess.check_output(['docker','info','--format','{{json .}}'],text=True))
    context['docker']={k:info[k] for k in ('ServerVersion','NCPU','MemTotal','OperatingSystem')}
    context['k6_digest']=json.loads(subprocess.check_output(['docker','image','inspect','grafana/k6:1.3.0','--format','{{json .RepoDigests}}'],text=True))
    conn=pymysql.connect(host='127.0.0.1',port=3310,user=env['JAVA_DB_USER'],password=env['JAVA_DB_PASSWORD'],database='sellerpilot_java')
    with conn.cursor() as cursor:
        cursor.execute('SELECT VERSION(),@@innodb_buffer_pool_size,@@transaction_isolation,@@max_connections')
        context['mysql']=dict(zip(('version','innodb_buffer_pool_bytes','isolation','max_connections'),cursor.fetchone()))
        cursor.execute("SELECT TABLE_NAME,DATA_LENGTH,INDEX_LENGTH FROM information_schema.tables WHERE TABLE_SCHEMA='sellerpilot_java' AND TABLE_NAME IN ('orders','order_items')")
        context['table_storage_estimates']=[dict(zip(('table','data_bytes','index_bytes'),row)) for row in cursor.fetchall()]
    conn.close()
    with httpx.Client(timeout=10,trust_env=False) as client:
        response=client.post('http://127.0.0.1:8081/auth/login',json={k:credentials[k] for k in ('username','password')});response.raise_for_status()
        headers={'Authorization':'Bearer '+response.json()['access_token']};metrics={}
        for name in ('sellerpilot.aggregate.sql','sellerpilot.aggregate.rejected','sellerpilot.cache.hit','sellerpilot.cache.miss','sellerpilot.cache.coalesced','sellerpilot.cache.degraded','hikaricp.connections.active','hikaricp.connections.max'):
            response=client.get('http://127.0.0.1:8081/actuator/metrics/'+name,headers=headers)
            if response.status_code==200:metrics[name]=response.json()['measurements']
        context['instance_8081_metrics_at_collection']=metrics
    baseline=json.loads((OUT/'python-baseline.json').read_text())
    baseline_files=baseline['files']
    present={p:h for p,h in baseline_files.items() if (ROOT/p).is_file()}
    retired=sorted(set(baseline_files)-set(present))
    changed=[p for p,h in present.items() if digest(ROOT/p)!=h]
    context['pre_migration_python_baseline']={'baseline_commit':baseline['baseline_commit'],'baseline_files':len(baseline_files),'files_still_present':len(present),'retired_files':len(retired),'changed_files_still_present':changed,'meaning':'historical baseline only; retired and changed files are expected after Java consolidation'}
    reports=OUT/'junit';reports.mkdir(exist_ok=True)
    for source in (ROOT/'backend-java/target/surefire-reports').glob('*'):
        if source.suffix not in ('.txt','.xml'):continue
        text=source.read_text()
        for secret in (env['JAVA_DB_PASSWORD'],env['JAVA_JWT_SECRET'],env['JAVA_MYSQL_ROOT_PASSWORD'],credentials['password']):
            assert secret not in text,'secret present in test output'
        shutil.copyfile(source,reports/source.name)
    (OUT/'environment.json').write_text(json.dumps(context,indent=2))
    manifest={}
    for base in ('backend-java/src','backend-java/scripts','frontend/src','frontend/e2e','deploy/java'):
        for path in (ROOT/base).rglob('*'):
            if path.is_file():manifest[path.relative_to(ROOT).as_posix()]=digest(path)
    for relative in ('backend-java/pom.xml','backend-java/Dockerfile','compose.java.yaml','frontend/Dockerfile','frontend/src/app/App.tsx','frontend/vite.config.ts','frontend/playwright.config.ts','frontend/nginx.conf','.github/workflows/verify.yml'):
        manifest[relative]=digest(ROOT/relative)
    for path in (ROOT/'experiments').glob('java-*'):
        if path.is_file():manifest[path.relative_to(ROOT).as_posix()]=digest(path)
    (OUT/'source-manifest.json').write_text(json.dumps({'base_commit':baseline['baseline_commit'],'state':'uncommitted workspace, file hashes identify tested code','files':manifest},indent=2))
    print(json.dumps({'pre_migration_baseline_files':len(baseline_files),'source_files':len(manifest),'mysql':context['mysql']}))
if __name__=='__main__':main()
