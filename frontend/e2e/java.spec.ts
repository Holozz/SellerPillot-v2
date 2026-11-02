import {test,expect} from '@playwright/test'

test('统一导入界面：映射确认与冲突修订需用户确认',async({page})=>{
  await page.addInitScript(()=>sessionStorage.setItem('sellerpilot_token','ui-fixture'))
  let products:object[]=[],mappings:object[]=[],uploads=0,confirmed=0
  await page.route('**/api/**',async route=>{
    const url=new URL(route.request().url()),path=url.pathname,method=route.request().method()
    let body:unknown={}
    if(path==='/api/auth/me')body={id:1,username:'owner'}
    else if(path==='/api/stores')body=[{id:1,owner_id:1,name:'Store A',platform:'market-a',source_store_key:'store-a'}]
    else if(path.endsWith('/catalog-products')){
      if(method==='POST')products=[{id:1,...route.request().postDataJSON()}]
      body=method==='POST'?products[0]:products
    }else if(path.endsWith('/product-mappings/MUG-US')){
      mappings=[{source_sku:'MUG-US',internal_sku:'MUG-01',title:'Mug',mapping_version:1}];body=mappings[0]
    }else if(path.endsWith('/product-mappings'))body=mappings
    else if(path.includes('/source-imports/')){
      uploads++
      if(url.searchParams.get('confirm_conflicts')!=='true'){await route.fulfill({status:409,json:{detail:'内容不同，需confirm_conflicts=true'}});return}
      confirmed++;body={import_id:10,row_count:1,status:'PUBLISHED',duplicate:false}
    }
    await route.fulfill({status:method==='POST'&&path.endsWith('/catalog-products')?201:200,json:body})
  })
  await page.goto('/')
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
