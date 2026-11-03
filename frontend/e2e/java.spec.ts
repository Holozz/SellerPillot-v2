import {test,expect} from '@playwright/test'
import path from 'node:path'

test('毛利报表界面：跨店选择、缺汇率提示和历史复算',async({page})=>{
  const result={complete:false,event_count:2,priced_event_count:1,sales_net_cny:'126.00',refunds_cny:'0.00',procurement_cost_cny:'55.20',returned_cost_cny:'0.00',known_margin_cny:'70.80',gross_margin_cny:null,products:[{internal_sku:'MUG-01',known_margin_cny:'70.80'}],details:[{store_id:2,source_row_id:9,external_line_id:'B-1',event:'sales',original_amount:'20.00',currency:'EUR',original_purchase_cost:'12.50',cost_currency:'EUR',revenue_rate_date:'2026-11-02',revenue_rate:null,cost_rate_date:'MULTIPLE',cost_rate:null,complete:false,cost_allocations:[{source_batch_id:'LOT-A',procurement_batch_id:1,cost_batch_revision:1,quantity:1,cost_currency:'EUR',unit_purchase_cost:'5.00',rate_date:'2026-10-30',rate:'7.80',cost_cny_unrounded:'39.00'},{source_batch_id:'LOT-B',procurement_batch_id:2,cost_batch_revision:2,quantity:1,cost_currency:'EUR',unit_purchase_cost:'7.50',rate_date:'2026-10-31',rate:null}]}]}
  const fx={id:1,source:'provider',source_revision:'v1'}
  const saved={id:'report-1',result,input_manifest:{start:'2026-11-01',end:'2026-11-03',store_ids:[1,2],fx_rate_set:fx}}
  let created=false,recomputed=0,fixedSubmitted=false,revaluations=0
  await page.addInitScript(()=>sessionStorage.setItem('sellerpilot_token','ui-fixture'))
  await page.route('**/*',async route=>{
    const url=new URL(route.request().url()),p=url.pathname
    if(!p.startsWith('/api/')){await route.fulfill({path:path.resolve('dist',p==='/'?'index.html':p.slice(1))});return}
    let body:unknown={}
    if(p==='/api/auth/me')body={id:1,username:'owner'}
    else if(p==='/api/stores')body=[{id:1,name:'Store A',owner_id:1,platform:'market-a',source_store_key:'a'},{id:2,name:'Store B',owner_id:1,platform:'market-b',source_store_key:'b'},{id:3,name:'Other seller',owner_id:2,platform:'market-a',source_store_key:'c'}]
    else if(p.endsWith('/fx-rate-sets'))body=[fx]
    else if(p.endsWith('/fx-rate-sets/1'))body={rates:[{base_currency:'USD',effective_date:'2026-11-01',rate:'7.00'}]}
    else if(p==='/api/v2/margin-report-jobs'&&route.request().method()==='POST'){
      const query=route.request().postDataJSON();expect(query.store_ids).toEqual([1,2]);if(query.fixed_rate_date){expect(query.fixed_rate_date).toBe('2026-11-02');fixedSubmitted=true}created=true;body={id:"job-1",status:"QUEUED"}
    }else if(p==='/api/v2/margin-report-jobs/job-1')body={id:'job-1',status:'SUCCEEDED',report_id:'report-1'}
    else if(p==='/api/v2/margin-reports')body=created?(revaluations?['report-2','report-1']:['report-1']).map(id=>({id,start_date:'2026-11-01',end_date:'2026-11-03',complete:false,known_margin_cny:'70.80',created_at:'2026-11-02T12:00Z'})):[]
    else if(p.endsWith('/compare')){await route.fulfill({status:422,json:{detail:'报表存在未定价事件，不能分解完整毛利差额'}});return}
    else if(p.endsWith('/recompute')){recomputed++;body=result}
    else if(p.endsWith('/revalue-costs')){revaluations++;body={id:'job-2',status:'QUEUED',source_report_id:'report-1'}}
    else if(p.endsWith('/job-2'))body={id:'job-2',status:'SUCCEEDED',report_id:'report-2',source_report_id:'report-1'}
    else if(p.endsWith('/details'))body={rows:result.details}
    else if(p.endsWith('/latest')){expect(route.request().postDataJSON().store_ids).toEqual([1,2]);body=saved}
    else if(p.endsWith('/report-2/summary'))body={...saved,id:'report-2',input_manifest:{...saved.input_manifest,revalued_from:'report-1'}}
    else if(p.endsWith('/report-1/summary'))body=saved
    await route.fulfill({json:body})
  })
  await page.goto('http://sellerpilot.test/')
  await page.getByRole('button',{name:'人民币销售毛利',exact:true}).click()
  await page.getByRole('checkbox',{name:'Store B · market-b'}).check()
  await expect(page.getByRole('checkbox',{name:/Other seller/})).toHaveCount(0)
  await page.getByLabel('开始日期（UTC）',{exact:true}).fill('2026-11-01')
  await page.getByLabel('结束日期（不含，UTC）',{exact:true}).fill('2026-11-03')
  await page.getByLabel('历史汇率版本',{exact:true}).selectOption('1')
  await expect(page.getByRole('button',{name:'生成并保存毛利报表'})).toBeEnabled()
  await page.getByRole('button',{name:'查看最新报表'}).click()
  await expect(page.getByRole('heading',{name:'报表 report-1',exact:true})).toBeVisible()
  await page.getByRole('button',{name:'生成并保存毛利报表'}).click()
  await expect(page.getByText('报表任务 排队中',{exact:true})).toBeVisible()
  await page.getByRole('button',{name:'刷新任务状态'}).click()
  await expect(page.getByText('汇率不完整，已定价毛利小计：70.80 元；完整销售毛利暂不可用。',{exact:true})).toBeVisible()
  await expect(page.getByRole('cell',{name:'未定价',exact:true})).toHaveCount(0)
  await page.getByRole('button',{name:'加载折算明细'}).click()
  await expect(page.getByRole('cell',{name:'未定价',exact:true})).toBeVisible()
  await page.getByText('B-1 · 来源行 #9 · 采购批次分配（2 笔）',{exact:true}).click()
  await expect(page.getByRole('cell',{name:'LOT-B / 2',exact:true})).toBeVisible()
  await expect(page.getByRole('cell',{name:'2026-10-31 / 缺失',exact:true})).toBeVisible()
  await page.getByRole('button',{name:'用原输入复算'}).click()
  await expect(page.getByText('已使用原报表输入重新计算，保存的报表未被覆盖。',{exact:true})).toBeVisible()
  expect(recomputed).toBe(1)
  await page.getByRole('button',{name:'打开报表',exact:true}).click()
  await expect(page.getByRole('heading',{name:'报表 report-1',exact:true})).toBeVisible()
  await page.getByRole('checkbox',{name:'使用固定日期汇率折算销售、退款与采购成本'}).check()
  await page.getByLabel('固定汇率日期',{exact:true}).fill('2026-11-02')
  await page.getByRole('button',{name:'生成并保存毛利报表'}).click()
  await expect(page.getByText('报表任务 排队中',{exact:true})).toBeVisible()
  await page.getByRole('button',{name:'刷新任务状态'}).click()
  await expect(page.getByRole('heading',{name:'报表 report-1',exact:true})).toBeVisible();expect(fixedSubmitted).toBe(true)
  await page.getByLabel('基准报表',{exact:true}).selectOption('report-1');await page.getByLabel('目标报表',{exact:true}).selectOption('report-1')
  await page.getByRole('button',{name:'计算报表差额'}).click()
  await expect(page.getByRole('alert')).toHaveText('报表存在未定价事件，不能分解完整毛利差额')
  page.once('dialog',dialog=>dialog.dismiss())
  await page.getByRole('button',{name:'按最新采购成本生成新报表'}).click();expect(revaluations).toBe(0)
  page.once('dialog',dialog=>dialog.accept())
  await page.getByRole('button',{name:'按最新采购成本生成新报表'}).click()
  await expect(page.getByText('报表任务 排队中',{exact:true})).toBeVisible()
  await expect(page.getByRole('heading',{name:'报表 report-1',exact:true})).toBeVisible()
  await page.getByRole('button',{name:'刷新任务状态'}).click()
  await expect(page.getByRole('heading',{name:'报表 report-2',exact:true})).toBeVisible()
  await expect(page.getByLabel('基准报表',{exact:true})).toHaveValue('report-1');await expect(page.getByLabel('目标报表',{exact:true})).toHaveValue('report-2')
  expect(revaluations).toBe(1)
})

test('统一导入界面：映射确认与冲突修订需用户确认',async({page})=>{
  await page.addInitScript(()=>sessionStorage.setItem('sellerpilot_token','ui-fixture'))
  let products:object[]=[],mappings:object[]=[],uploads=0,confirmed=0
  await page.route('**/*',async route=>{
    const url=new URL(route.request().url()),pathname=url.pathname,method=route.request().method()
    if(!pathname.startsWith('/api/')){await route.fulfill({path:path.resolve('dist',pathname==='/'?'index.html':pathname.slice(1))});return}
    const pathName=pathname
    let body:unknown={}
    if(pathName==='/api/auth/me')body={id:1,username:'owner'}
    else if(pathName==='/api/stores')body=[{id:1,owner_id:1,name:'Store A',platform:'market-a',source_store_key:'store-a'}]
    else if(pathName.endsWith('/catalog-products')){
      if(method==='POST')products=[{id:1,...route.request().postDataJSON()}]
      body=method==='POST'?products[0]:products
    }else if(pathName.endsWith('/product-mappings/MUG-US')){
      mappings=[{source_sku:'MUG-US',internal_sku:'MUG-01',title:'Mug',mapping_version:1}];body=mappings[0]
    }else if(pathName.endsWith('/product-mappings'))body=mappings
    else if(pathName.includes('/source-imports/')){
      uploads++
      if(url.searchParams.get('confirm_conflicts')!=='true'){await route.fulfill({status:409,json:{detail:'内容不同，需confirm_conflicts=true'}});return}
      confirmed++;body={import_id:10,row_count:1,status:'PUBLISHED',duplicate:false}
    }
    await route.fulfill({status:method==='POST'&&pathName.endsWith('/catalog-products')?201:200,json:body})
  })
  await page.goto('http://sellerpilot.test/')
  await page.getByRole('button',{name:'统一商品映射',exact:true}).click()
  await page.getByLabel('统一 SKU',{exact:true}).fill('MUG-01')
  await page.getByLabel('商品名称',{exact:true}).fill('Mug')
  await page.getByRole('button',{name:'创建统一商品',exact:true}).click()
  await expect(page.getByText('统一商品已创建。',{exact:true})).toBeVisible()
  await page.getByLabel('本店铺来源 SKU',{exact:true}).fill('MUG-US')
  page.once('dialog',dialog=>dialog.accept())
  await page.getByRole('button',{name:'确认映射',exact:true}).click()
  await expect(page.getByRole('cell',{name:'MUG-US',exact:true})).toBeVisible()
  await page.getByRole('button',{name:'平台文件导入',exact:true}).click()
  await page.getByLabel('平台 CSV 文件').setInputFiles({name:'batches.csv',mimeType:'text/csv',buffer:Buffer.from('header\nrow\n')})
  await page.getByRole('button',{name:'导入平台文件',exact:true}).click()
  await expect(page.getByRole('button',{name:'核对后确认追加修订'})).toBeVisible()
  expect(uploads).toBe(1);expect(confirmed).toBe(0)
  page.once('dialog',dialog=>dialog.accept())
  await page.getByRole('button',{name:'核对后确认追加修订'}).click()
  await expect(page.getByRole('heading',{name:'平台文件导入成功'})).toBeVisible()
  expect(uploads).toBe(2);expect(confirmed).toBe(1)
})

test('Java业务链：注册、商品、订单与明细、经营查询、审批回执',async({page})=>{
  const errors:string[]=[];page.on('pageerror',error=>errors.push(error.message))
  const name='java_e2e_'+Date.now(),password='BrowserFixture123!'
  await page.goto('/')
  await page.getByRole('button',{name:'还没有账号？注册'}).click()
  await page.getByLabel('用户名').fill(name);await page.getByLabel('密码',{exact:true}).fill(password)
  await page.getByRole('button',{name:'注册账号',exact:true}).click()
  await expect(page.getByText('账号已创建，请输入密码登录。')).toBeVisible()
  await page.getByLabel('密码',{exact:true}).fill(password)
  await page.getByRole('button',{name:'登录工作台',exact:true}).click()
  await page.getByRole('button',{name:'创建店铺',exact:true}).click()
  await page.getByLabel('店铺名称').fill('浏览器验收店铺')
  await page.getByRole('button',{name:'保存店铺',exact:true}).click()
  await expect(page.getByRole('heading',{name:'核对经营数据'})).toBeVisible()
  await page.getByRole('button',{name:'商品库存',exact:true}).click()
  await page.getByRole('button',{name:'新增商品',exact:true}).click()
  await page.getByLabel('SKU',{exact:true}).fill('JAR-001')
  await page.getByLabel('商品名称',{exact:true}).fill('Storage Jar 500ml')
  await page.getByLabel('单价',{exact:true}).fill('10.00')
  await page.getByLabel('库存',{exact:true}).fill('8')
  await page.getByRole('button',{name:'保存商品',exact:true}).click()
  await expect(page.getByRole('cell',{name:'JAR-001',exact:true})).toBeVisible()
  await page.getByRole('button',{name:'订单导入',exact:true}).click()
  await page.getByLabel('CSV文件').setInputFiles({name:'orders.csv',mimeType:'text/csv',buffer:Buffer.from('external_order_id,ordered_at,status,currency\nO1,2026-10-02T00:00:00Z,paid,USD\n')})
  await page.getByRole('button',{name:'上传并导入',exact:true}).click()
  await expect(page.getByRole('heading',{name:'导入成功',exact:true})).toBeVisible()
  await page.getByLabel('导入内容').selectOption('order-items')
  await page.getByLabel('CSV文件').setInputFiles({name:'items.csv',mimeType:'text/csv',buffer:Buffer.from('external_order_id,line_no,sku,quantity,unit_price,refund_amount\nO1,1,JAR-001,2,10.00,1.00\nO1,2,JAR-001,2,10.00,1.00\n')})
  await page.getByRole('button',{name:'上传并导入',exact:true}).click()
  await expect(page.getByRole('heading',{name:'导入成功',exact:true})).toBeVisible()
  await page.getByRole('button',{name:'经营分析',exact:true}).click()
  await page.getByLabel('开始日期（UTC）').fill('2026-10-01')
  await page.getByLabel('结束日期（不含，UTC）').fill('2026-10-03')
  await page.getByRole('button',{name:'查询经营数据',exact:true}).click()
  await expect(page.getByRole('cell',{name:'38.00',exact:true}).first()).toBeVisible()
  await page.screenshot({path:'../docs/verification/java/metrics-desktop.png',fullPage:true})
  await page.getByRole('button',{name:'操作审批',exact:true}).click()
  await page.getByLabel('商品',{exact:true}).selectOption({index:1})
  await page.getByLabel('建议的新标题').fill('Clear Glass Storage Jar 500ml')
  await page.getByRole('button',{name:'保存待确认草稿',exact:true}).click()
  const proposal=page.locator('.java-proposal').filter({hasText:'Clear Glass Storage Jar 500ml'})
  page.once('dialog',dialog=>dialog.accept())
  await proposal.getByRole('button',{name:'确认应用',exact:true}).click()
  await expect(proposal).toContainText('已应用')
  await page.getByText('查看最近操作记录').click()
  await expect(page.getByText(/标题修改已确认/)).toBeVisible()
  await page.screenshot({path:'../docs/verification/java/approval-desktop.png',fullPage:true})
  await page.setViewportSize({width:390,height:844})
  await page.screenshot({path:'../docs/verification/java/approval-mobile.png',fullPage:true})
  expect(errors).toEqual([])
})

test('真实毛利报表：页面提交任务并读取最新已发布版本',async({page,request})=>{
  const api=process.env.PLAYWRIGHT_API_BASE||'http://127.0.0.1:8081',suffix=Date.now(),username=`margin_e2e_${suffix}`,password='BrowserFixture123!'
  const call=async(method:'get'|'post'|'put',path:string,token?:string,data?:unknown,form?:Record<string,{name:string,mimeType:string,buffer:Buffer}>)=>{
    const response=await request[method](`${api}${path}`,{headers:token?{Authorization:`Bearer ${token}`}:{},data, multipart:form})
    const body=await response.text()
    expect(response.ok(),`${method.toUpperCase()} ${path}: ${response.status()} ${body}`).toBeTruthy()
    return body?JSON.parse(body):{}
  }
  await call('post','/auth/register',undefined,{username,password})
  const session=await call('post','/auth/login',undefined,{username,password})
  const token=session.access_token as string
  const store=await call('post','/stores',token,{name:`E2E ${suffix}`,platform:'market-a',source_store_key:`e2e-${suffix}`})
  const storeId=store.id as number,base=`/api/v2/stores/${storeId}`
  await call('post',`${base}/catalog-products`,token,{internal_sku:'E2E-MUG',title:'E2E Mug',base_unit:'piece'})
  await call('put',`${base}/product-mappings/E2E-SKU`,token,{internal_sku:'E2E-MUG'})
  const upload=async(kind:string,csv:string)=>call('post',`${base}/source-imports/${kind}`,token,undefined,{file:{name:`${kind}.csv`,mimeType:'text/csv',buffer:Buffer.from(csv)}})
  const key=`e2e-${suffix}`
  await upload('orders',`platform_code,store_key,external_order_id,ordered_at,paid_at,status,currency\nmarket-a,${key},E2E-ORDER,2026-11-01T09:00:00Z,2026-11-01T09:00:00Z,paid,USD\n`)
  await upload('procurement-batches',`platform_code,store_key,source_batch_id,external_sku,received_at,quantity,unit_purchase_cost,currency\nmarket-a,${key},E2E-LOT,E2E-SKU,2026-10-31T09:00:00Z,2,4.00,USD\n`)
  await upload('order-items',`platform_code,store_key,external_line_id,external_order_id,external_sku,quantity,unit_price,discount_amount,currency,source_batch_id\nmarket-a,${key},E2E-LINE,E2E-ORDER,E2E-SKU,1,10.00,0.00,USD,E2E-LOT\n`)
  const fx=await call('post',`${base}/fx-rate-sets`,token,{source:'playwright-e2e',source_revision:String(suffix),rates:[{base_currency:'USD',effective_date:'2026-10-31',rate:'7.00'},{base_currency:'USD',effective_date:'2026-11-01',rate:'7.00'}]})
  await page.addInitScript(value=>sessionStorage.setItem('sellerpilot_token',value),token)
  await page.goto('/')
  await page.getByRole('button',{name:'人民币销售毛利',exact:true}).click()
  await page.getByLabel('开始日期（UTC）',{exact:true}).fill('2026-11-01')
  await page.getByLabel('结束日期（不含，UTC）',{exact:true}).fill('2026-11-02')
  await page.getByLabel('历史汇率版本',{exact:true}).selectOption(String(fx.id))
  await expect(page.getByRole('button',{name:'生成并保存毛利报表'})).toBeEnabled()
  await page.getByRole('button',{name:'生成并保存毛利报表'}).click()
  await expect(page.getByText('报表任务 排队中',{exact:true})).toBeVisible()
  for(let attempt=0;attempt<30;attempt++){
    await page.getByRole('button',{name:'刷新任务状态',exact:true}).click()
    if(await page.getByText('销售毛利：42.00 元',{exact:true}).count())break
    await page.waitForTimeout(200)
  }
  await expect(page.getByRole('heading',{name:/报表 [a-f0-9-]+/})).toBeVisible({timeout:30000})
  await expect(page.getByText('销售毛利：42.00 元',{exact:true})).toBeVisible()
  await page.getByRole('button',{name:'查看最新报表',exact:true}).click()
  await expect(page.getByText('销售毛利：42.00 元',{exact:true})).toBeVisible()
  const reportHeading=await page.getByRole('heading',{name:/报表 [a-f0-9-]+/}).innerText()
  await page.reload()
  await page.getByRole('button',{name:'人民币销售毛利',exact:true}).click()
  await page.getByLabel('开始日期（UTC）',{exact:true}).fill('2026-11-01')
  await page.getByLabel('结束日期（不含，UTC）',{exact:true}).fill('2026-11-02')
  await page.getByLabel('历史汇率版本',{exact:true}).selectOption(String(fx.id))
  await page.getByRole('button',{name:'查看最新报表',exact:true}).click()
  await expect(page.getByRole('heading',{name:reportHeading,exact:true})).toBeVisible()
  await expect(page.getByText('销售毛利：42.00 元',{exact:true})).toBeVisible()
})
