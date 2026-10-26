import http from 'k6/http';
import {check} from 'k6';
import {Rate,Counter} from 'k6/metrics';
const invalid=new Rate('business_invalid');
const measured=new Counter('measured_requests');
const credentials=JSON.parse(open(__ENV.CREDENTIALS_FILE));
const query=JSON.parse(open(__ENV.QUERY_FILE));
const base=__ENV.API_BASE||'http://127.0.0.1:8081';
export const options={scenarios:{query:{executor:'constant-arrival-rate',rate:Number(__ENV.RATE||20),timeUnit:'1s',duration:__ENV.DURATION||'30s',preAllocatedVUs:Number(__ENV.PREALLOCATED_VUS||50),maxVUs:Number(__ENV.MAX_VUS||300)}},
  thresholds:{measured_requests:['count>0'],business_invalid:['rate<0.001'],dropped_iterations:['count==0'],'http_req_duration{phase:measured}':['p(95)<200'],'http_req_failed{phase:measured}':['rate<0.001']},summaryTrendStats:['avg','min','med','max','p(95)','p(99)']};
function signature(rows){return JSON.stringify(rows.map(r=>[r.product_id,r.currency,String(r.value),String(r.gross_sales),String(r.refund_amount),String(r.net_sales)]));}
export function setup(){
  const login=http.post(base+'/auth/login',JSON.stringify({username:credentials.username,password:credentials.password}),{headers:{'Content-Type':'application/json'}});
  if(login.status!==200)throw new Error('Benchmark login failed');const token=login.json('access_token');
  const parameters={headers:{Authorization:`Bearer ${token}`,'Content-Type':'application/json'},tags:{phase:'warmup'}};
  const response=http.post(`${base}/api/v2/stores/${credentials.store_id}/metrics/query`,JSON.stringify(query),parameters);
  if(response.status!==200)throw new Error('Benchmark baseline query failed');
  return {token,expected:signature(response.json('rows'))};
}
export default function(data){
  measured.add(1);
  const response=http.post(`${base}/api/v2/stores/${credentials.store_id}/metrics/query`,JSON.stringify(query),{
    headers:{Authorization:`Bearer ${data.token}`,'Content-Type':'application/json'},tags:{phase:'measured'}});
  let valid=false;try{valid=response.status===200&&response.json('kind')==='fact_card'&&signature(response.json('rows'))===data.expected;}catch(_){}
  invalid.add(!valid);check(response,{'valid business result':()=>valid});
}
