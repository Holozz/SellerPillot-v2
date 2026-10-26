import {useEffect,useState} from 'react'
import type {Store} from '../../shared/types'
import {request,ApiError} from '../../shared/api/client'
import ProductManager from '../products/ProductManager'
import CsvImports from '../imports/CsvImports'
import './java.css'

interface Product{id:number;sku:string;title:string;version:number}
interface Proposal{id:string;product_id:number;product_version:number;original_title:string;proposed_title:string;payload_hash:string;status:string}
interface Fact{data_version:number;as_of:string;cache_hit:boolean;query_spec:{metric:string};rows:Record<string,string|number>[]}
interface Member{username:string;role:string;active:boolean}
interface Audit{id:number;action:string;target:string;created_at:string;user_id:number}
type Panel='metrics'|'products'|'imports'|'actions'|'members'
const states:Record<string,string>={pending:'待确认',applied:'已应用',rejected:'已拒绝',expired:'已过期'}

export default function JavaWorkspace({store,onSessionExpired}:{store:Store;onSessionExpired:()=>void}){
  const base=`/v2/stores/${store.id}`
  const [panel,setPanel]=useState<Panel>('metrics'),[busy,setBusy]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState('')
  const today=new Date().toISOString().slice(0,10)
  const [start,setStart]=useState(new Date(Date.now()-7*86400000).toISOString().slice(0,10)),[end,setEnd]=useState(today)
  const [metric,setMetric]=useState('sales'),[group,setGroup]=useState('product'),[fact,setFact]=useState<Fact|null>(null)
  const [products,setProducts]=useState<Product[]>([]),[proposals,setProposals]=useState<Proposal[]>([]),[audits,setAudits]=useState<Audit[]>([])
  const [selected,setSelected]=useState(''),[title,setTitle]=useState('')
  const [members,setMembers]=useState<Member[]>([]),[username,setUsername]=useState(''),[role,setRole]=useState('viewer')
  function fail(cause:unknown){if(cause instanceof ApiError&&cause.status===401)onSessionExpired();else setError(cause instanceof Error?cause.message:'操作失败')}
  async function refreshActions(){
    const [ps,items,log]=await Promise.all([request<Proposal[]>(`${base}/proposals`),request<{items:Product[]}>(`${base}/products`),request<Audit[]>(`${base}/audit`)])
    setProposals(ps);setProducts(items.items);setAudits(log)
  }
  async function act(work:()=>Promise<void>){setBusy(true);setError('');setNotice('');try{await work()}catch(cause){fail(cause)}finally{setBusy(false)}}
  useEffect(()=>{setError('');if(panel==='actions')refreshActions().catch(fail);if(panel==='members')request<Member[]>(`${base}/members`).then(setMembers).catch(fail)},[panel,store.id])
  function renderFact(card:Fact){
    const fields=new Set(card.rows.flatMap(row=>Object.keys(row)))
    const cols=['sku','title','currency',...(card.query_spec.metric==='stock'?['stock']:card.query_spec.metric==='sales'?['gross_sales','refund_amount','net_sales']:['value','gross_sales','refund_amount','net_sales'])].filter(key=>fields.has(key))
    const labels:Record<string,string>={currency:'币种',product_id:'商品编号',sku:'SKU',title:'商品',stock:'当前库存',value:({sales:'销售额（扣退款）',quantity:'销量',orders:'订单数'} as Record<string,string>)[card.query_spec.metric]??'结果',gross_sales:'成交金额',refund_amount:'退款',net_sales:'扣退款金额'}
    return <section className="java-facts"><h3>经营查询结果</h3><p className="hint">区间按 UTC 计算，结束日期不计入；不同币种分别展示。</p>{card.rows.length?<div className="java-table-scroll"><table><thead><tr>{cols.map(key=><th key={key}>{labels[key]??key}</th>)}</tr></thead><tbody>{card.rows.map((row,i)=><tr key={i}>{cols.map(key=><td key={key}>{row[key]??'—'}</td>)}</tr>)}</tbody></table></div>:<p className="empty-state">该区间没有符合条件的数据，请检查日期或导入订单明细。</p>}
      <details className="java-query-note"><summary>查询口径与时间</summary><p>数据版本：{card.data_version} · 查询时间：{card.as_of} · {card.cache_hit?'复用当前版本结果':'本次计算结果'}</p></details></section>
  }
  return <div className="store-workspace"><nav className="workspace-navigation" aria-label="工作区导航">{([
    ['metrics','经营分析'],['products','商品库存'],['imports','订单导入'],['actions','操作审批'],['members','团队权限']
  ] as [Panel,string][]).map(([id,label])=><button key={id} aria-pressed={panel===id} onClick={()=>setPanel(id)}>{label}</button>)}</nav><section className="workspace-page java-panel">
    {error&&<p className="error-message" role="alert">{error}</p>}{notice&&<p className="success-message" role="status">{notice}</p>}
    {panel==='metrics'&&<><div className="section-heading"><div><h2>核对经营数据</h2><p>按统一口径查看销售额、订单数、销量与库存。</p></div></div>
      <form className="java-query-form" onSubmit={e=>{e.preventDefault();act(async()=>{
        const result=await request<Fact>(`${base}/metrics/query`,{method:'POST',body:JSON.stringify({metric,group_by:group,start:start+'T00:00:00Z',end:end+'T00:00:00Z',limit:50})})
        setFact(result)
      })}}>
        <label>开始日期（UTC）<input required type="date" value={start} onChange={e=>setStart(e.target.value)}/></label>
        <label>结束日期（不含，UTC）<input required type="date" value={end} onChange={e=>setEnd(e.target.value)}/></label>
        <label>指标<select value={metric} onChange={e=>setMetric(e.target.value)}><option value="sales">销售额（扣退款）</option><option value="quantity">销量</option><option value="orders">订单数</option><option value="stock">当前库存</option></select></label>
        <label>分组<select value={group} onChange={e=>setGroup(e.target.value)}><option value="product">按商品与币种</option><option value="currency">按币种</option></select></label>
        <button className="primary-button compact" disabled={busy}>查询经营数据</button>
      </form>{fact&&renderFact(fact)}</>}
    {panel==='products'&&<ProductManager store={store} onSessionExpired={onSessionExpired}/>}
    {panel==='imports'&&<CsvImports store={store} onSessionExpired={onSessionExpired}/>}
    {panel==='actions'&&<><div className="section-heading"><div><h2>商品变更审批</h2><p>核对原值与建议后确认，过期版本不会覆盖当前商品。</p></div><button className="quiet-button" disabled={busy} onClick={()=>act(refreshActions)}>刷新</button></div>
      <form className="java-query-form" onSubmit={e=>{e.preventDefault();const product=products.find(p=>p.id===Number(selected));if(!product)return;act(async()=>{
        await request(`${base}/proposals`,{method:'POST',body:JSON.stringify({product_id:product.id,product_version:product.version,title})});setTitle('');await refreshActions()
      })}}><label>商品<select aria-label="商品" required value={selected} onChange={e=>setSelected(e.target.value)}><option value="">请选择商品</option>{products.map(p=><option key={p.id} value={p.id}>{p.sku} · {p.title}</option>)}</select></label>
        <label className="java-grow">建议的新标题<input required maxLength={200} value={title} onChange={e=>setTitle(e.target.value)}/></label><button className="primary-button compact" disabled={busy}>保存待确认草稿</button></form>
      {!proposals.length&&<p className="empty-state">尚无修改草稿。选择商品并填写新标题后，可以先预览再确认。</p>}
      {proposals.map(p=><article className="java-proposal" key={p.id}><div className="section-heading"><strong>商品 #{p.product_id}</strong><span>{states[p.status]??p.status}</span></div><p><del>{p.original_title}</del></p><p><strong>{p.proposed_title}</strong></p><small>基于商品版本 {p.product_version}</small>{p.status==='pending'&&<div className="java-actions"><button className="primary-button compact" disabled={busy} onClick={()=>{
        if(window.confirm(`确认将标题修改为“${p.proposed_title}”？`))act(async()=>{await request(`${base}/proposals/${p.id}/confirm`,{method:'POST',body:JSON.stringify({confirmed:true,payload_hash:p.payload_hash,idempotency_key:`confirm:${p.id}`})});setNotice('修改已完成，执行回执和审计已保存。');await refreshActions()})
      }}>确认应用</button><button className="quiet-button" disabled={busy} onClick={()=>act(async()=>{await request(`${base}/proposals/${p.id}/reject`,{method:'POST'});await refreshActions()})}>拒绝</button></div>}</article>)}
      <details><summary>查看最近操作记录</summary>{audits.map(a=><p key={a.id}>{a.created_at} · 商品 {a.target} · {a.action==='update_product_title'?'标题修改已确认':'草稿已拒绝'} · 操作人 #{a.user_id}</p>)}</details>
    </>}
    {panel==='members'&&<><h2>团队权限</h2><p className="hint">仅店铺所有者可维护成员。成员需要先注册账号。</p><form className="java-query-form" onSubmit={e=>{e.preventDefault();act(async()=>{await request(`${base}/members`,{method:'PUT',body:JSON.stringify({username,role,active:true})});setMembers(await request<Member[]>(`${base}/members`))})}}>
      <label>成员用户名<input required value={username} onChange={e=>setUsername(e.target.value)}/></label><label>权限<select value={role} onChange={e=>setRole(e.target.value)}><option value="viewer">只读成员</option><option value="operator">运营成员</option></select></label><button className="primary-button compact" disabled={busy}>保存成员</button></form>
      {members.map(m=><div className="java-member" key={m.username}><span>{m.username} · {m.role==='viewer'?'只读':'运营'} · {m.active?'有效':'已撤销'}</span><button className="quiet-button" disabled={busy} onClick={()=>act(async()=>{await request(`${base}/members`,{method:'PUT',body:JSON.stringify({username:m.username,role:m.role,active:!m.active})});setMembers(await request<Member[]>(`${base}/members`))})}>{m.active?'撤销访问':'恢复访问'}</button></div>)}</>}
  </section></div>
}
