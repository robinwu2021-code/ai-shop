from pathlib import Path
import json, html
root=Path('docs/api/pos-fnb')
# Tuples: group, operation, method, fixed route, business input fields, output data fields.
rows=[
('基础','终端登录','POST','/auth/login','account:string,password:string,terminalNo:string','accessToken:string,expiresAt:string,staff:Staff,stores:Store[]'),
('基础','读取门店营业信息','GET','/store/info','','store:Store,businessDate:string,payModes:string[],capabilities:string[]'),
('基础','当前操作员权限','GET','/auth/profile','','staff:Staff,permissions:string[]'),
('桌台','桌台列表','GET','/table/list','areaNo?:string,status?:string,page?:int,size?:int','records:Table[],total:int,page:int,size:int'),
('桌台','开台','POST','/table/open','tableNo:string,guestCount:int','check:Check,table:Table'),
('桌台','账单详情','GET','/check/detail','checkNo:string','check:Check,lines:OrderLine[],amounts:Amounts'),
('桌台','转台','POST','/table/transfer','checkNo:string,targetTableNo:string,version:long','check:Check'),
('桌台','拼桌','POST','/table/combine','checkNo:string,targetTableNos:string[],version:long','check:Check'),
('桌台','分单或AA预览','POST','/check/split/preview','checkNo:string,mode:string,allocations:Allocation[],version:long','plan:SplitPlan'),
('桌台','确认分单方案','POST','/check/split/confirm','checkNo:string,planNo:string,version:long','plan:SplitPlan'),
('菜品','可售餐牌列表','GET','/menu/list','channel?:string,at?:string','menus:Menu[]'),
('菜品','餐牌菜品与分类','GET','/menu/detail','menuNo:string','menu:Menu,categories:Category[],dishes:Dish[]'),
('菜品','菜品规格配料和套餐','GET','/dish/detail','menuNo:string,offerNo:string','dish:Dish,groups:SelectionGroup[]'),
('购物车','创建购物车','POST','/cart/create','checkNo?:string,fulfillment:string,payMode:string','cart:Cart'),
('购物车','查询购物车','GET','/cart/detail','cartNo:string','cart:Cart,lines:CartLine[]'),
('购物车','新增或修改购物车行','POST','/cart/line/save','cartNo:string,lineRef:string,offerNo:string,skuNo:string,quantity:decimal,selections:Selection[],version:long','cart:Cart,lines:CartLine[]'),
('购物车','移除购物车行','POST','/cart/line/remove','cartNo:string,lineRef:string,version:long','cart:Cart,lines:CartLine[]'),
('结账','金额试算','POST','/pricing/quote','cartNo:string,version:long,benefits?:BenefitInput,manualAdjustment?:ManualAdjustment','quote:Quote'),
('订单','提交订单','POST','/order/submit','cartNo:string,version:long,quoteRef:string,quoteHash:string','batchNo:string,orderNo:string,status:string,amounts:Amounts'),
('订单','订单列表','GET','/order/list','businessDate?:string,status?:string,page?:int,size?:int','records:Order[],total:int,page:int,size:int'),
('订单','订单详情','GET','/order/detail','orderNo:string','order:Order,lines:OrderLine[],amounts:Amounts'),
('订单','取消订单','POST','/order/cancel','orderNo:string,version:long,reasonCode:string,reason:string','orderNo:string,status:string,pending:boolean'),
('结账','结账试算与确认依据','POST','/checkout/preview','checkNo:string,version:long,benefits?:BenefitInput,manualAdjustment?:ManualAdjustment','checkoutRef:string,quote:Quote'),
('结账','提交结账','POST','/checkout/confirm','checkNo:string,checkoutRef:string,quoteHash:string,version:long,payment:PaymentInput','operationNo:string,status:string,pending:boolean,amounts:Amounts'),
('结账','查询结账结果','GET','/checkout/result','operationNo:string','operationNo:string,status:string,pending:boolean,amounts:Amounts'),
('厨房','工位任务列表','GET','/kitchen/task/list','stationNo?:string,status?:string,page?:int,size?:int','records:KitchenTask[],total:int,page:int,size:int'),
('厨房','任务操作','POST','/kitchen/task/action','taskNo:string,action:string,quantity?:decimal,version:long,reason?:string','task:KitchenTask,productionPending:boolean'),
('售后','提交退餐','POST','/return/create','orderNo:string,items:ReturnItem[],reasonCode:string,reason:string','returnNo:string,status:string,financialStatus:string,kitchenStatus:string'),
('售后','退餐详情','GET','/return/detail','returnNo:string','returnNo:string,status:string,financialStatus:string,kitchenStatus:string,items:ReturnItem[]'),
('售后','处理退餐库存','POST','/return/stock/apply','returnNo:string,returnItemNo:string,productionEventNo:string,quantity:decimal,disposition:string,reasonCode:string,reason:string,evidenceRef:string','disposition:StockDisposition'),
('售后','重试退餐库存','POST','/return/stock/retry','returnNo:string,operationNo:string','disposition:StockDisposition'),
('打印','申请打印任务','POST','/print/create','sourceType:string,sourceNo:string,printerNo?:string,templateNo?:string','jobNo:string,status:string'),
('打印','打印任务列表','GET','/print/list','sourceNo?:string,status?:string,page?:int,size?:int','records:PrintJob[],total:int,page:int,size:int'),
('打印','重试打印任务','POST','/print/retry','jobNo:string,reason:string','jobNo:string,status:string'),
('库存','库存余额查询','GET','/stock/balance/list','itemNo?:string,page?:int,size?:int','records:StockBalance[],total:int,page:int,size:int'),
('库存','出品待处理列表','GET','/stock/production/pending','afterId?:long,limit?:int','items:PendingProduction[],nextAfterId?:long'),
('库存','原出品查回或重试','POST','/stock/production/retry','eventNo:string','eventNo:string,status:string,pending:boolean'),
('库存','镜像同步异常查询','GET','/stock/sync/list','status?:string,itemNo?:string,page?:int,size?:int','records:SyncDelivery[],total:int,page:int,size:int'),
('库存','重试镜像同步','POST','/stock/sync/retry','syncNo:string','syncNo:string,status:string,pending:boolean'),
('营业','日结预览','POST','/close/preview','businessDate:string','closeNo:string,status:string,amounts:Amounts,blockingItems:BlockingItem[]'),
('营业','日结签署','POST','/close/sign','closeNo:string,version:long','closeNo:string,status:string,signedAt:string'),
('营业','日结结果查询','GET','/close/detail','closeNo:string','closeNo:string,status:string,amounts:Amounts')
]
def fields(s):
 out=[]
 for field in filter(None,s.split(',')):
  k,t=field.split(':');out.append(dict(name=k.rstrip('?'),type=t,required=not k.endswith('?')))
 return out
apis=[]
for group,name,method,path,inp,out in rows:
 common=[] if path=='/auth/login' else fields('storeNo:string')
 if method=='POST' and path!='/auth/login':common+=fields('idempotencyKey:string')
 apis.append(dict(group=group,name=name,method=method,path=path,status='待适配，尚非线上可调用接口',input=common+fields(inp),output=fields(out)))
models={
'Staff':'staffNo:string,name:string','Store':'storeNo:string,name:string,timeZone:string,currency:string',
'Table':'tableNo:string,name:string,areaNo:string,status:string,checkNo?:string,version:long',
'Check':'checkNo:string,status:string,tableNos:string[],guestCount:int,payMode:string,version:long',
'Amounts':'currency:string,subtotalMinor:long,discountMinor:long,taxMinor:long,payableMinor:long,paidMinor:long,refundedMinor:long',
'Menu':'menuNo:string,name:string,revision:string,channel:string',
'Category':'categoryNo:string,name:string,sort:int',
'Dish':'offerNo:string,goodsNo:string,skuNo:string,name:string,imageUrl?:string,priceMinor:long,currency:string,saleStatus:string',
'SelectionGroup':'groupNo:string,name:string,minSelect:int,maxSelect:int,options:Option[]',
'Option':'optionNo:string,name:string,priceDeltaMinor:long,available:boolean',
'Selection':'groupNo:string,optionNo:string,quantity:decimal',
'Cart':'cartNo:string,checkNo?:string,status:string,version:long',
'CartLine':'lineRef:string,offerNo:string,skuNo:string,quantity:decimal,selections:Selection[]',
'OrderLine':'lineNo:string,name:string,quantity:decimal,unit:string,amountMinor:long',
'Quote':'quoteRef:string,quoteHash:string,expiresAt:string,amounts:Amounts,warnings:string[]',
'Order':'orderNo:string,batchNo:string,checkNo?:string,status:string,version:long',
'KitchenTask':'taskNo:string,stationNo:string,status:string,quantity:decimal,version:long',
'ReturnItem':'lineNo:string,quantity:decimal,reason:string',
'StockDisposition':'operationNo:string,status:string,stockCommandNo?:string,lastErrorCode?:string',
'PrintJob':'jobNo:string,sourceNo:string,status:string,lastErrorCode?:string',
'StockBalance':'itemNo:string,baseUnit:string,onHand:decimal,reservedQty:decimal,availableQty:decimal,version:long',
'PendingProduction':'sourceId:long,eventNo:string,taskNo:string,action:string,status:string',
'SyncDelivery':'syncNo:string,itemNo:string,status:string,attempts:int,lastErrorCode?:string',
'BlockingItem':'type:string,referenceNo:string,reason:string',
'Allocation':'lineNo:string,quantity:decimal,seatNo?:string',
'SplitPlan':'planNo:string,status:string,version:long,allocations:Allocation[]',
'BenefitInput':'couponNos:string[],pointsToUse?:long,storedValueToUseMinor?:long',
'ManualAdjustment':'kind:string,value:decimal,reasonCode:string,reason:string',
'PaymentInput':'method:string,paymentRef?:string,tenderedMinor?:long'
}
contract=dict(title='餐饮 POS 固定 URL 对接合同（草案）',version='0.1',status='接口规划，未实现固定URL适配；不得作为线上已开放接口',primaryBase='https://www.hxmall.top/pos/fnb',alternateBase='https://api.hxmall.top/fnb',alternateVerified=False,apis=apis,models={k:fields(v) for k,v in models.items()})
(root/'pos-fnb-contract.json').write_text(json.dumps(contract,ensure_ascii=False,indent=2)+'\n')
intro="""# 餐饮 POS 对接接口

版本0.1，2026-10-30。**固定URL合同草案：目前后端未实现这些固定路由，不能直接向线上发起调用。** 已部署接口仍为/biz/x/food等原路径。本册用于外部评审及后续适配，不宣称已上线。

## 地址

- 主地址：https://www.hxmall.top/pos/fnb
- 餐饮API入口：https://api.hxmall.top/fnb；路由、证书及等价反代尚未核验。
- 地址来自本次用户要求，不是对现网路由已生效的确认。api.hxmall.top入口直接使用/fnb前缀，不额外拼接/pos/fnb。

## 调用约定

只使用GET和POST，URL路径固定，不包含订单号、门店号等动态段。GET参数放在?后；POST参数放JSON，不把业务参数拼入路径。身份使用Authorization: Bearer <token>，POST为Content-Type: application/json。业务参数storeNo由服务端核对权限，操作者和主体从登录态取得。

除登录外，所有POST带body.idempotencyKey，适配层须映射到原服务幂等机制；重试沿用原键和原内容。version从服务端最近查询结果获取，不能自行递增。?表示字段可选；其余为合同必填。decimal使用十进制字符串传输，金额Minor为币种最小单位整数；不跨币种相加。

统一响应：{"code":0,"msg":"success","data":{...}}。下表输出是data结构。业务失败返回非0 code，data为空；具体错误码沿平台错误码体系及适配后的验收结果，不新增虚假成功码。超时或UNKNOWN先按原操作号查询，不能重新创建结账或重复扣减。GET返回的是实时数据，不承诺冻结快照。

字段、枚举和这些简化输出是拟定的POS合同，需要适配层与既有领域DTO逐项映射后验收；不等于原接口DTO。涉及真实收款、积分、储值及退款执行，仍需专项接入，本文不宣称已接通。退餐库存处置成功不能代表退款成功。打印排队成功不能代表纸面打印成功。当前仅做文档，不改变生产服务。

## URL总表

"""
md=intro+'| 模块 | 功能 | 方法 | 固定路径（拼接主地址） |\n|---|---|---|---|\n'
for a in apis:md+=f"|{a['group']}|{a['name']}|{a['method']}|{a['path']}|\n"
md+='\n## 输入与输出\n\n'
def table(fs):
 return '|参数|类型|必填|\n|---|---|---|\n'+''.join(f"|{f['name']}|{f['type']}|{'是' if f['required'] else '否'}|\n" for f in fs)
for i,a in enumerate(apis,1):
 md+=f"### {i}. {a['name']}\n\n`{a['method']} {contract['primaryBase']}{a['path']}`\n\n"
 if a['method']=='GET':
  query='&'.join(f"{f['name']}=示例值" for f in a['input'] if f['required'])
  md+='请求示例：`'+contract['primaryBase']+a['path']+('?' + query if query else '')+'`\n\n'
 md+=('Query参数：\n\n' if a['method']=='GET' else 'JSON参数：\n\n')+table(a['input'])+'\ndata输出：\n\n'+table(a['output'])+'\n'
md+='## 复用对象字段\n\n'
for n,fs in contract['models'].items():md+=f"### {n}\n\n"+table(fs)+'\n'
md+='''## 后续交付条件

固定POS适配层实现并核对全部字段、错误码、枚举及事务状态；认证门店隔离、幂等、超时查回测试通过；两个域名路由和证书核验；外部评审后锁定合同版本，才标记为可调用。原始线上接口另见[已部署接口清单](../food/接口清单.md)。

[HTML外发版](POS对接文档.html) · [JSON合同](pos-fnb-contract.json)
'''
md=md.replace('## 后续交付条件','## 导出\n\n运行 `python3 scripts/gen-pos-fnb-doc.py` 可重新生成Markdown、JSON与离线HTML；HTML不依赖外部脚本或网络资源，可直接作为评审附件发送。接口仍处草案阶段，外发时须保留状态说明。\n\n## 后续交付条件')
(root/'POS接口清单.md').write_text(md)
# Offline HTML with escaped content, semantic tables and navigation. No external dependency.
def ht(fs):
 return '<table><thead><tr><th>参数</th><th>类型</th><th>必填</th></tr></thead><tbody>'+''.join('<tr><td>'+html.escape(f['name'])+'</td><td>'+html.escape(f['type'])+'</td><td>'+('是' if f['required'] else '否')+'</td></tr>' for f in fs)+'</tbody></table>'
nav=''.join(f'<a href="#api{i}">{i}. {html.escape(a["name"])}</a>' for i,a in enumerate(apis,1))
body='<h1>餐饮 POS 对接文档</h1><p class="notice">合同草案 v0.1 · 固定路由尚未实现，不能直接线上调用。2026-10-30</p><p>主地址：<code>https://www.hxmall.top/pos/fnb</code><br>备用：<code>https://api.hxmall.top/fnb</code>（路由及证书未核验）</p><p>GET使用?查询参数；POST使用JSON。Bearer token放Authorization。除登录外POST body需idempotencyKey，重试保持原键与原内容。storeNo须经服务端授权。输出为统一响应data中的结构：<code>{"code":0,"msg":"success","data":{}}</code>。金额Minor为最小单位整数；decimal为十进制字符串。UNKNOWN先查回。真实资金功能尚需专项接入。</p>'
for i,a in enumerate(apis,1):
 body+=f'<section id="api{i}"><h2>{i}. {html.escape(a["name"])}</h2><p><strong>{a["method"]}</strong> <code>{html.escape(contract["primaryBase"]+a["path"])}</code></p><h3>{"Query参数" if a["method"]=="GET" else "JSON请求体"}</h3>'+ht(a['input'])+'<h3>data输出</h3>'+ht(a['output'])+'</section>'
body+='<h2>复用对象</h2>'
for n,fs in contract['models'].items():body+='<h3>'+html.escape(n)+'</h3>'+ht(fs)
doc='<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>餐饮POS对接文档（草案）</title><style>body{font:15px/1.65 system-ui,sans-serif;color:#18212b;margin:0;background:#fff}nav{position:fixed;inset:0 auto 0 0;width:230px;overflow:auto;padding:20px;background:#f6f7f8}nav a{display:block;color:#344151;text-decoration:none;font-size:13px;padding:3px}main{margin-left:270px;max-width:1000px;padding:30px}section{border-top:1px solid #ddd;padding-top:18px;scroll-margin:20px}table{border-collapse:collapse;width:100%;margin-bottom:22px}td,th{border:1px solid #ddd;padding:7px 12px;text-align:left}th{background:#f6f7f8}code{overflow-wrap:anywhere}.notice{padding:12px;border-left:4px solid #d19a22;background:#fff9e8}@media(max-width:760px){nav{position:static;width:auto;max-height:180px}main{margin:0;padding:18px}}@media print{nav{display:none}main{margin:0}section{break-inside:avoid}}</style><nav><b>POS接口目录</b>'+nav+'</nav><main>'+body+'</main></html>'
(root/'POS对接文档.html').write_text(doc)
print(f'{len(apis)} POS operations; {len(models)} reusable models')
