import { useEffect, useRef, useState } from 'react'
import { request, TOKEN_KEY, ApiError } from '../../shared/api/client'
import type { Store } from '../../shared/types'
import './operations.css'

interface KB { id: string; name: string; active_build_id: string | null; revision: number }
interface Doc { id: string; name: string; active: boolean; current_version_id: string }
interface Evidence { chunk_id: string; document_id: string; version_id: string; location: string; text: string }
interface Job { id: string; kind: string; status: string; error: string | null; attempt: number; created_at: string; result: {answer?:string; facts?:unknown[]; evidence?:Evidence[]} | null }
interface Proposal { id:string; product_id:number; original_title:string; proposed_title:string; payload_hash:string; status:string; product_version:number }
interface Product { id:number; title:string; sku:string; version:number }
interface Member { id:number; username:string; role:string; active:boolean }
interface SearchResult { evidence:Evidence[]; degraded:boolean; rerank_applied:boolean; reason:string|null; embedding_backend?:string }
interface EventRow { seq:number; kind:string; data:Record<string,unknown> }
type Section = 'assistant'|'knowledge'|'tasks'|'approvals'|'team'
const labels:Record<string,string>={queued:'排队中',running:'处理中',retry_wait:'等待重试',succeeded:'已完成',failed:'失败',cancelled:'已取消',pending:'待审核',applied:'已应用',rejected:'已拒绝'}

export default function OperationsConsole({store,onSessionExpired}:{store:Store;onSessionExpired:()=>void}) {
  const base=`/v2/stores/${store.id}`
  const [section,setSection]=useState<Section>('assistant')
  const [kbs,setKbs]=useState<KB[]>([]); const [kid,setKid]=useState('')
  const [docs,setDocs]=useState<Doc[]>([]); const [jobs,setJobs]=useState<Job[]>([])
  const [proposals,setProposals]=useState<Proposal[]>([]); const [products,setProducts]=useState<Product[]>([])
  const [members,setMembers]=useState<Member[]>([]); const [canManage,setCanManage]=useState(false)
  const [busy,setBusy]=useState(false); const [error,setError]=useState(''); const [notice,setNotice]=useState('')
  const [kbName,setKbName]=useState('店铺规则与商品资料'); const [file,setFile]=useState<File|null>(null)
  const [replaceId,setReplaceId]=useState(''); const [question,setQuestion]=useState('')
  const [offline,setOffline]=useState(false); const [activeRun,setActiveRun]=useState('')
  const [timeline,setTimeline]=useState<EventRow[]>([]); const [answer,setAnswer]=useState('')
  const [facts,setFacts]=useState<unknown[]>([]); const [sources,setSources]=useState<Evidence[]>([])
  const [search,setSearch]=useState(''); const [strategy,setStrategy]=useState('hybrid')
  const [searchResult,setSearchResult]=useState<SearchResult|null>(null)
  const [selectedProduct,setSelectedProduct]=useState(0); const [newTitle,setNewTitle]=useState('')
  const [username,setUsername]=useState(''); const [role,setRole]=useState('viewer')
  const stream=useRef<AbortController|null>(null)

  function fail(cause:unknown) {
    if(cause instanceof DOMException && cause.name==='AbortError') return
    if(cause instanceof ApiError && cause.status===401) onSessionExpired()
    else setError(cause instanceof Error?cause.message:'操作失败，请重试')
  }
  async function refresh() {
    const [ks,js,ps,items]=await Promise.all([
      request<KB[]>(`${base}/knowledge-bases`),request<Job[]>(`${base}/jobs`),
      request<Proposal[]>(`${base}/proposals`),request<{items:Product[]}>(`${base}/products`)])
    setKbs(ks); setKid(current=>ks.some(k=>k.id===current)?current:ks[0]?.id??'')
    setJobs(js);setProposals(ps);setProducts(items.items)
  }
  useEffect(()=>{ refresh().catch(fail); return()=>stream.current?.abort() },[store.id])
  useEffect(()=>{ if(kid) request<Doc[]>(`${base}/knowledge-bases/${kid}/documents`).then(setDocs).catch(fail); else setDocs([]) },[kid,jobs])
  useEffect(()=>{
    if(!jobs.some(j=>!['succeeded','failed','cancelled'].includes(j.status))) return
    const timer=setInterval(()=>request<Job[]>(`${base}/jobs`).then(setJobs).catch(fail),3000)
    return()=>clearInterval(timer)
  },[jobs])
  useEffect(()=>{
    if(section==='team') request<Member[]>(`${base}/members`).then(rows=>{setMembers(rows);setCanManage(true)}).catch(cause=>{
      if(cause instanceof ApiError&&cause.status===403) setCanManage(false); else fail(cause)
    })
  },[section])
  async function act(work:()=>Promise<void>) {
    setBusy(true);setError('');setNotice('')
    try {await work();await refresh()} catch(cause){fail(cause)} finally{setBusy(false)}
  }
  async function followRun(id:string) {
    stream.current?.abort(); const controller=new AbortController();stream.current=controller
    let cursor=0
    for(let retry=0;retry<4&&!controller.signal.aborted;retry++) {
      try {
        const response=await fetch(`/api${base}/runs/${id}/events?after=${cursor}`,{
          headers:{Authorization:`Bearer ${sessionStorage.getItem(TOKEN_KEY)??''}`},signal:controller.signal})
        if(!response.ok) throw new ApiError(response.status,'无法读取任务过程')
        const reader=response.body!.getReader();const decoder=new TextDecoder();let buffer=''
        while(true){
          const {value,done}=await reader.read();if(done)break
          buffer+=decoder.decode(value,{stream:true});let index:number
          while((index=buffer.indexOf('\n\n'))>=0){
            const frame=buffer.slice(0,index);buffer=buffer.slice(index+2)
            if(frame.includes('event: done')){await refresh();return}
            if(frame.includes('event: revoked'))throw new ApiError(403,'店铺访问权限已撤销')
            const data=frame.split('\n').find(line=>line.startsWith('data: '))
            if(!data)continue
            const event=JSON.parse(data.slice(6)) as EventRow
            if(event.seq<=cursor)continue
            cursor=event.seq;setTimeline(current=>[...current,event].slice(-200))
            if(event.kind==='answer_delta')setAnswer(current=>current+String(event.data.text??''))
            if(event.kind==='answer')setAnswer(String(event.data.text??''))
            if(event.kind==='fact')setFacts(current=>[...current,event.data])
            if(event.kind==='tool_end'){
              const result=event.data.result as {evidence?:Evidence[]}|undefined
              if(result?.evidence)setSources(current=>[...new Map([...current,...result.evidence!].map(e=>[e.chunk_id,e])).values()])
            }
          }
        }
      }catch(cause){if(controller.signal.aborted)return;if(cause instanceof ApiError){fail(cause);return}if(retry===3){fail(cause);return}}
      await new Promise(resolve=>setTimeout(resolve,1000))
    }
  }
  function evidenceList(rows:Evidence[]) {
    return rows.map(e=><details className="sp-source" key={e.chunk_id}><summary>{e.location} · 查看依据</summary>
      <p>{e.text}</p><a href="#" onClick={async event=>{event.preventDefault();try{
        const response=await fetch(`/api${base}/documents/${e.document_id}/versions/${e.version_id}/original`,{headers:{Authorization:`Bearer ${sessionStorage.getItem(TOKEN_KEY)??''}`}})
        if(!response.ok)throw new Error('无法下载原文');const url=URL.createObjectURL(await response.blob());const a=document.createElement('a');a.href=url;a.download='original';a.click();URL.revokeObjectURL(url)
      }catch(cause){fail(cause)}}}>下载对应版本原文</a></details>)
  }
  return <section className="sp-console">
    <div className="sp-console-heading"><div><h2>经营决策与执行</h2><p>先核对事实与规则，再确认修改。</p></div><button disabled={busy} onClick={()=>act(async()=>{})}>刷新</button></div>
    <nav className="sp-tabs" aria-label="智能运营模块">{([
      ['assistant','运营助手'],['knowledge','知识中心'],['tasks','任务中心'],['approvals','操作审批'],['team','团队权限']
    ] as [Section,string][]).map(([id,label])=><button key={id} aria-pressed={section===id} onClick={()=>setSection(id)}>{label}</button>)}</nav>
    {error&&<p className="error-message" role="alert">{error}</p>}{notice&&<p className="sp-notice" role="status">{notice}</p>}
    {section==='assistant'&&<div className="sp-columns"><div>
      <form onSubmit={event=>{event.preventDefault();act(async()=>{
        const job=await request<Job>(`${base}/runs`,{method:'POST',body:JSON.stringify({message:question,kb_id:kid||null,mode:offline?'offline':'model',idempotency_key:crypto.randomUUID()})})
        setActiveRun(job.id);setAnswer('');setFacts([]);setSources([]);setTimeline([]);void followRun(job.id)
      })}}>
        <label>使用的知识库<select value={kid} onChange={e=>setKid(e.target.value)}><option value="">暂不使用知识库</option>{kbs.map(k=><option key={k.id} value={k.id}>{k.name}{k.active_build_id?'':'（尚未发布）'}</option>)}</select></label>
        <label>本次任务<textarea required maxLength={2000} rows={4} placeholder="查询近七天商品销量，或根据规则检查商品标题。" value={question} onChange={e=>setQuestion(e.target.value)}/></label>
        <label className="sp-checkbox"><input type="checkbox" checked={offline} onChange={e=>setOffline(e.target.checked)}/>仅检查近七天数据查询（不调用模型）</label>
        <button disabled={busy}>开始分析</button>
      </form>
      {activeRun&&<p className="hint">本次任务：{activeRun.slice(0,8)}。离开页面后任务继续，可在任务中心查看结果。</p>}
      {facts.map((fact,i)=><details key={i} open className="sp-fact"><summary>经营事实 {i+1}</summary><pre>{JSON.stringify(fact,null,2)}</pre></details>)}
      {answer&&<article className="sp-answer"><h3>分析结果</h3><p>{answer}</p></article>}
      {sources.length>0&&<div><h3>引用资料</h3>{evidenceList(sources)}</div>}
    </div><aside className="sp-timeline"><h3>处理过程</h3>{!timeline.length&&<p className="hint">提交任务后，这里会展示处理进度与结果。</p>}{timeline.map(row=><div key={row.seq}><span>{row.seq}</span><div><strong>{({started:'开始处理',model_start:'分析问题',tool_start:'读取资料',tool_end:'读取完成',fact:'返回经营事实',answer_delta:'生成结果',status:'状态更新'} as Record<string,string>)[row.kind]??row.kind}</strong><small>{String(row.data.name??row.data.status??'')}</small></div></div>)}</aside></div>}
    {section==='knowledge'&&<div className="sp-columns"><div>
      <h3>资料与版本</h3><form onSubmit={e=>{e.preventDefault();act(async()=>{const kb=await request<KB>(`${base}/knowledge-bases`,{method:'POST',body:JSON.stringify({name:kbName})});setKid(kb.id)})}} className="sp-inline"><input required value={kbName} onChange={e=>setKbName(e.target.value)}/><button disabled={busy}>创建知识库</button></form>
      <label>知识库<select value={kid} onChange={e=>setKid(e.target.value)}><option value="">请选择</option>{kbs.map(k=><option key={k.id} value={k.id}>{k.name}</option>)}</select></label>
      <form onSubmit={e=>{e.preventDefault();if(!file||!kid)return;act(async()=>{const form=new FormData();form.append('file',file);await request(`${base}/knowledge-bases/${kid}/documents${replaceId?`?document_id=${replaceId}`:''}`,{method:'POST',body:form});setNotice('资料已接收，处理完成后自动发布。')})}}>
        <label>上传或替换资料<input required type="file" accept=".txt,.md,.pdf,.docx" onChange={e=>setFile(e.target.files?.[0]??null)}/></label>
        <select value={replaceId} onChange={e=>setReplaceId(e.target.value)}><option value="">作为新资料上传</option>{docs.map(d=><option key={d.id} value={d.id}>替换：{d.name}</option>)}</select>
        <button disabled={busy||!kid}>上传并处理</button><p className="hint">支持文本 PDF、DOCX、TXT 和 Markdown，最大 10MB。扫描文件暂不支持。</p>
      </form>
      {docs.map(d=><div className="sp-row" key={d.id}><div><strong>{d.name}</strong><small>{d.active?'参与后续检索':'已禁用'} · 版本 {d.current_version_id.slice(0,8)}</small></div><button disabled={busy} onClick={()=>act(async()=>{await request(`${base}/documents/${d.id}/visibility?active=${!d.active}`,{method:'POST'})})}>{d.active?'禁用':'启用'}</button></div>)}
      {kid&&<button disabled={busy} onClick={()=>act(async()=>{await request(`${base}/knowledge-bases/${kid}/builds`,{method:'POST'});setNotice('已提交重新构建任务')})}>重新构建并发布</button>}
    </div><aside><h3>检查检索结果</h3><form onSubmit={e=>{e.preventDefault();act(async()=>setSearchResult(await request<SearchResult>(`${base}/knowledge-bases/${kid}/retrieval-tests`,{method:'POST',body:JSON.stringify({query:search,strategy})})))}}>
      <label>问题<input required value={search} onChange={e=>setSearch(e.target.value)}/></label><select value={strategy} onChange={e=>setStrategy(e.target.value)}><option value="hybrid">混合检索</option><option value="bm25">关键词检索</option><option value="vector">向量检索</option><option value="rerank">混合检索与重排</option></select><button disabled={busy||!kid}>测试检索</button></form>
      {searchResult&&<><p className="hint">{searchResult.reason??`找到 ${searchResult.evidence.length} 条依据`}{searchResult.degraded?' · 部分服务不可用，已降级':''}{searchResult.embedding_backend==='hash'?' · 当前为离线词特征向量，不能用于语义效果评测':''}</p>{evidenceList(searchResult.evidence)}</>}
    </aside></div>}
    {section==='tasks'&&<><h3>处理任务</h3>{!jobs.length&&<p>上传资料或提交分析后，可在这里跟踪进度。</p>}{jobs.map(j=><details key={j.id} className="sp-task"><summary><span>{j.kind==='build'?'资料构建':'经营分析'}</span><span>{labels[j.status]??j.status}</span><span>{j.created_at.replace('T',' ').slice(0,19)}</span></summary><p>尝试次数：{j.attempt} · 编号：{j.id}</p>{j.error&&<p className="error-message">{j.error}</p>}{j.result&&<pre>{JSON.stringify(j.result,null,2)}</pre>}{['failed','cancelled'].includes(j.status)?<button disabled={busy} onClick={()=>act(async()=>{await request(`${base}/jobs/${j.id}/retry`,{method:'POST'})})}>重新执行</button>:!['succeeded'].includes(j.status)&&<button disabled={busy} onClick={()=>act(async()=>{await request(`${base}/jobs/${j.id}/cancel`,{method:'POST'})})}>取消任务</button>}</details>)}</>}
    {section==='approvals'&&<><h3>商品标题调整</h3><form className="sp-inline" onSubmit={e=>{e.preventDefault();const p=products.find(p=>p.id===selectedProduct);if(!p)return;act(async()=>{await request(`${base}/proposals`,{method:'POST',body:JSON.stringify({product_id:p.id,product_version:p.version,title:newTitle})});setNewTitle('')})}}>
      <select required value={selectedProduct||''} onChange={e=>setSelectedProduct(Number(e.target.value))}><option value="">选择商品</option>{products.map(p=><option key={p.id} value={p.id}>{p.sku} · {p.title}</option>)}</select><input required maxLength={200} placeholder="建议的新标题" value={newTitle} onChange={e=>setNewTitle(e.target.value)}/><button disabled={busy}>保存待审核草稿</button></form>
      {proposals.map(p=><article className="sp-proposal" key={p.id}><div className="sp-console-heading"><h4>商品 #{p.product_id}</h4><span>{labels[p.status]??p.status}</span></div><p><del>{p.original_title}</del></p><p><strong>{p.proposed_title}</strong></p><small>基于商品版本 {p.product_version}</small>{p.status==='pending'&&<div className="sp-actions"><button disabled={busy} onClick={()=>{if(window.confirm(`确认将标题修改为“${p.proposed_title}”？`))act(async()=>{await request(`${base}/proposals/${p.id}/confirm`,{method:'POST',body:JSON.stringify({confirmed:true,payload_hash:p.payload_hash,idempotency_key:`confirm:${p.id}`})});setNotice('标题已修改，执行回执和审计已保存。')})}}>确认应用</button><button disabled={busy} className="quiet-button" onClick={()=>act(async()=>{await request(`${base}/proposals/${p.id}/reject`,{method:'POST'})})}>拒绝</button></div>}</article>)}
    </>}
    {section==='team'&&<><h3>团队访问权限</h3>{canManage?<><p className="hint">成员需要先注册账号。运营成员可维护资料和确认操作，只读成员仅可查看。</p><form className="sp-inline" onSubmit={e=>{e.preventDefault();act(async()=>{await request(`${base}/members`,{method:'PUT',body:JSON.stringify({username,role})});setMembers(await request<Member[]>(`${base}/members`))})}}><input required placeholder="成员用户名" value={username} onChange={e=>setUsername(e.target.value)}/><select value={role} onChange={e=>setRole(e.target.value)}><option value="viewer">只读成员</option><option value="operator">运营成员</option></select><button disabled={busy}>保存成员</button></form>{members.map(m=><div className="sp-row" key={m.id}><span>{m.username} · {m.role==='viewer'?'只读':'运营'} · {m.active?'有效':'已撤销'}</span><button disabled={busy} onClick={()=>act(async()=>{await request(`${base}/members`,{method:'PUT',body:JSON.stringify({username:m.username,role:m.role,active:!m.active})});setMembers(await request<Member[]>(`${base}/members`))})}>{m.active?'撤销访问':'恢复访问'}</button></div>)}</>:<p>仅店铺所有者可以维护成员权限。</p>}</>}
  </section>
}
