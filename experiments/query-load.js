import http from 'k6/http';
import {check,sleep} from 'k6';
export const options={scenarios:{query:{executor:'constant-vus',vus:Number(__ENV.VUS||5),duration:__ENV.DURATION||'60s'}},
  thresholds:{http_req_failed:['rate<0.01']}};
const base=__ENV.API_BASE||'http://127.0.0.1:8002';
export default function(){
  const end=new Date(__ENV.AS_OF||'2026-10-23T00:00:00Z');
  const start=new Date(end.getTime()-(7+(__ITER%4)*7)*86400000);
  const payload={metric:'sales',group_by:'product',start:start.toISOString(),end:end.toISOString(),limit:20};
  const response=http.post(`${base}/api/v2/stores/${__ENV.STORE_ID}/metrics/query`,JSON.stringify(payload),{
    headers:{Authorization:`Bearer ${__ENV.TOKEN}`,'Content-Type':'application/json'},tags:{operation:'product-sales'}});
  check(response,{'query succeeds':r=>r.status===200,'fact card returned':r=>r.status===200&&r.json('kind')==='fact_card'});
  sleep(0.1);
}
