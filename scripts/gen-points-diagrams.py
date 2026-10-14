#!/usr/bin/env python3
"""Generate v3 design diagrams and atlas from the points diagram contract.
Run: python3 scripts/gen-points-diagrams.py [--check]
No product code reflection: this is the explicitly labelled target design.
"""
from pathlib import Path
from html import escape
import argparse,json,math,re,sys
ROOT=Path(__file__).resolve().parents[1]
SOURCE=ROOT/'docs/technical/design/积分模块-图契约.json'
OUT=ROOT/'docs/technical/diagrams'
ATLAS=ROOT/'docs/technical/design/积分模块-流转与类图.md'
FONT='PingFang SC,Microsoft YaHei,Arial,sans-serif'
COLORS={'point':'#2563eb','money':'#c2410c','guard':'#7c3aed','risk':'#be123c','ink':'#172554','muted':'#526277'}
def wrap(s,limit):
 lines=[];line='';weight=0
 for ch in s:
  step=1 if ord(ch)>255 else .56
  if weight+step>limit and line:lines.append(line);line='';weight=0
  line+=ch;weight+=step
 if line:lines.append(line)
 return lines

def text(x,y,s,size=17,color='#172554',weight='400',anchor='start'):
 return f'<text x="{x}" y="{y}" font-family="{FONT}" font-size="{size}" fill="{color}" font-weight="{weight}" text-anchor="{anchor}">{escape(s)}</text>'

def begin(title,w,h,desc):
 return [f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}"><title>{escape(title)}</title><desc>{escape(desc)}</desc>',f'<defs><marker id="point" markerWidth="9" markerHeight="8" refX="8" refY="4" orient="auto"><path d="M0 0L8 4L0 8" fill="#2563eb"/></marker><marker id="money" markerWidth="9" markerHeight="8" refX="8" refY="4" orient="auto"><path d="M0 0L8 4L0 8" fill="#c2410c"/></marker><marker id="dep" markerWidth="10" markerHeight="9" refX="9" refY="4.5" orient="auto"><path d="M0 0L9 4.5L0 9" fill="none" stroke="#64748b"/></marker><marker id="real" markerWidth="13" markerHeight="12" refX="12" refY="6" orient="auto"><path d="M0 0L12 6L0 12Z" fill="white" stroke="#475569"/></marker><marker id="diamond" markerWidth="15" markerHeight="10" refX="14" refY="5" orient="auto-start-reverse"><path d="M0 5L7 0L14 5L7 10Z" fill="#475569"/></marker></defs>',f'<rect width="{w}" height="{h}" fill="#f8fafc"/>',text(35,48,title,29,weight='650'),text(35,80,'v3 目标设计 · pay-domain内独立业务模块 · payTxManager · 尚未按此设计实现',15,color=COLORS['muted'])]

def card(x,y,w,h,title,lines,color='point'):
 co=COLORS.get(color,color)
 out=[f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="12" fill="white" stroke="{co}" stroke-width="1.8"/>',f'<rect x="{x}" y="{y}" width="6" height="{h}" rx="3" fill="{co}"/>']
 for k,t in enumerate(wrap(title,(w-30)/20)):out.append(text(x+16,y+29+k*25,t,20,co,'650'))
 start=y+55+max(0,len(wrap(title,(w-30)/20))-1)*25
 for ln in lines:
  for t in wrap(ln,(w-30)/16):out.append(text(x+16,start,t,16,color=COLORS['muted']));start+=23
 return out

def arrow(points,label='',color='point',labelpos=None,dashed=False):
 co=COLORS.get(color,color)
 path='M'+' L'.join(f'{x},{y}' for x,y in points)
 out=[f'<path d="{path}" fill="none" stroke="{co}" stroke-width="2.2" stroke-linejoin="round" marker-end="url(#{color if color in ["point","money"] else "point"})"'+(' stroke-dasharray="6 5"' if dashed else '')+'/>']
 if label:
  x,y=labelpos or ((points[0][0]+points[-1][0])/2,(points[0][1]+points[-1][1])/2-8)
  lines=wrap(label,26);width=max(sum(1 if ord(ch)>255 else .56 for ch in line) for line in lines)*15+16
  out.append(f'<rect x="{x-5}" y="{y-17}" width="{width}" height="{len(lines)*20+6}" rx="4" fill="#f8fafc"/>')
  for i,line in enumerate(lines):out.append(text(x,y+i*20,line,15,co))
 return out

def module_architecture(module):
 o=begin('积分业务模块架构 · 集成pay',1700,1120,'积分是pay-domain中的业务模块，与结算共享pay事务；订单经SPI和可靠事件跨域，外部资金调用在已提交的付款意图之后执行。')
 o += [f'<rect x="400" y="145" width="970" height="775" rx="18" fill="#eff6ff" stroke="#2563eb" stroke-width="2"/>',text(425,185,module['artifact']+' · 现有构件与部署',24,weight='650')]
 o += card(30,235,320,180,'订单 / 售后',['源事实、退款计划','platformTx + sys_outbox','shop-base.spi.points'],'guard')
 o += card(430,235,420,180,'pay结算业务',['结算单、分账、付款结果','短事务准备及结果确认','不直接写pts表'])
 o += card(905,235,430,180,'积分业务包',['种类、协议、返分、核销','退款、账期、生命周期','pay.points · pts_*账本'])
 o += arrow([(850,325),(905,325)])
 o += [text(795,445,module['settlementHook']+' · 域内同步协作',17)]
 o += arrow([(350,365),(380,365),(380,480),(1120,480),(1120,415)],'SPI / Outbox→Inbox',labelpos=(450,475))
 o += card(430,520,420,170,'统一事务与Mapper工厂',[module['txManager']+' · REQUIRED',module['sqlSessionFactory'],'pay.mapper.points'],'guard')
 o += card(905,520,430,170,'统一积分过账与资金适配',['PointsPostingService写账','PointsFundsGateway / Adapter','只依赖pay资金与通道接口'])
 o += arrow([(1120,415),(1120,520)])
 o += arrow([(905,610),(850,610)],'共用pay事务',labelpos=(800,730))
 o += card(430,770,905,115,'本地事务提交边界',['结算状态与积分账务必要变更一起提交 / 回滚；跨域结果通过pay Outbox发布。'],'guard')
 o += card(1410,520,260,170,'通道 / 资金机构',['意图提交后执行','幂等submit / query','证据确认后本地过账'],'money')
 o += arrow([(1335,605),(1410,605)],color='money')
 o += card(30,770,320,150,'宿主入口与任务',['shop-app Controller / 门面','job-worker只负责触发','沿用既有pay部署装配'])
 o += [text(35,975,'同pay短事务：准备付款意图 → 提交；事务外调用资金通道；同pay短事务：确认结算与积分结果。',19,weight='650'),text(35,1015,'订单事务与pay事务仍隔离：取消、退款与事实补偿保留跨域幂等协议；不新增积分JAR、连接池或事务管理器。',18),text(35,1060,'模块独立体现在业务职责、对象、接口和统一写账入口。图中类与路径均为目标设计。',17,COLORS['muted'])]
 return '\n'.join(o+['</svg>'])

def flow_overview():
 title='完整积分流转总览';o=begin(title,1660,1140,'五种持有方、用户与门店分格、接收户两格，展示返分、两种兑付、退款、责任解除及回流；细分资金动作见图册。')
 o += [text(35,118,'蓝色实线＝积分移动；橙色＝伴随资金；H的订单、退款、到期来源在头寸区分。',17)]
 nodes=[(35,'MINT 发行登记',['累计发行记负数','新增必须有真实备款']), (355,'ISSUE 平台发放户',['可发积分 ↔ U 未分配','U不足整张子单停发']), (675,'USER.P 待生效',['支付后获得，可见不可花','返分与本次核销是两侧']), (995,'USER.A 可用',['结算终局只转剩余P','可用于后续订单']), (1315,'USER.H 订单占用',['下单成功占用','同时预留B退款额度'])]
 for x,t,lines in nodes:o+=card(x,195,285,130,t,lines)
 for x,t,lines in [(35,'RECEIVE 可回流',['责任解除 / 已完成到期','只有本格参与TOPUP回流']), (675,'RECEIVE 退款预留',['兑付后分仍保留在这里','付款不移动积分']), (995,'STORE.A 待结算',['终局后可进入账期单','确认账单才形成兑付']), (1315,'STORE.P 待确认',['仅定期结算留余额','随单门店只留受理流水'])]:o+=card(x,600,285,145,t,lines)
 # Normal acquisition/usage and two settlement paths.
 for a,b,l in [(320,355,'备款支持发行'),(640,675,'GRANT：U→C'),(960,995,'ACTIVATE'),(1280,1315,'下一单 HOLD')]:o+=arrow([(a,260),(b,260)],l,labelpos=(a-100,365))
 o+=arrow([(1457,325),(1457,600)],'定期：H→STORE.P；C不变',labelpos=(1175,475))
 o+=arrow([(1315,670),(1280,670)],'结算终局',labelpos=(1150,800))
 o+=arrow([(995,670),(960,670)],'账单确认：C→D',labelpos=(750,800))
 o+=arrow([(1457,325),(1457,415),(817,415),(817,600)],'随单：H→接收户预留；C→D',labelpos=(820,455))
 o+=arrow([(750,600),(750,525),(177,525),(177,600)],'退款责任解除：预留→自由，释放B额度',labelpos=(275,555))
 o+=arrow([(177,600),(177,420),(497,420),(497,325)],'补U：自由分优先回流，不足发行',labelpos=(230,455))
 # Refund and revocation routes stay in separate corridors.
 o+=arrow([(817,745),(817,890),(1635,890),(1635,500),(1137,500),(1137,325)],'核销退款：接收户预留→USER.A',labelpos=(840,865))
 o+=arrow([(1137,195),(1137,155),(497,155),(497,195)],'返分收回：P/A经退款准备H→ISSUE；C→U',labelpos=(600,150))
 o+=card(355,600,285,145,'B 与 R 不持有积分',['B＝实际退款备付金','R＝待追回款，不算现金'],'risk')
 o+=card(35,965,760,130,'资金与守恒',['U=m(ISSUE)；C=m(USER+STORE)；实际专项资金=U+C+D+B','B ≥ 已预留额度；所有积分户合计（含MINT）=0'],'money')
 o+=card(825,965,775,130,'例外与恢复入口',['付款前退款D→C；付款后未追回B→C并登记R；追回回B','到期、调账、提取、现金补收、UNKNOWN见分图'],'guard')
 return '\n'.join(o+['</svg>'])

def pipeline_page(title,rows,desc):
 width=1640;row_height=285;height=150+len(rows)*row_height+65;o=begin(title,width,height,desc)
 for i,row in enumerate(rows):
  label,steps,note=row[:3];linked=len(row)<4 or row[3]!="parallel"
  y=150+i*row_height;o.append(text(35,y-12,label,22,weight='650'))
  w=(width-90-42*(len(steps)-1))/len(steps)
  for j,step in enumerate(steps):
   t,lines,color=step;x=35+j*(w+42);o+=card(x,y,w,170,t,lines,color)
   if j and linked:o+=arrow([(x-42,y+85),(x,y+85)])
  for k,line in enumerate(wrap(note,105)):o.append(text(35,y+205+k*24,line,17,color=COLORS['muted']))
 o.append(text(35,height-24,'资金证据不明确时保留原意图/占用；不假记到账、不改同一幂等请求金额。',16,COLORS['risk']))
 return '\n'.join(o+['</svg>'])

def refund_page():
 o=begin('核销退款与备付资金流转',1640,1210,'退款准备及资金结果门禁，按门店/未付D/已付已追回/已付未追回四分支退回用户，迟到追回回B。')
 for x,t,ls,col in [(35,'准备退款',['锁核销/发放头寸','返分收回冻结到专用H'],'guard'),(600,'现金退款结果明确',['成功或合法零现金完成','失败重试；未知不撤销'],'guard'),(1165,'按兑付位置处理',['PAYING先查清结果','不能凭超时假定未付款'],'guard')]:o+=card(x,150,440,145,t,ls,col)
 o+=arrow([(475,220),(600,220)]);o+=arrow([(1040,220),(1165,220)])
 branches=[('门店户，未兑付',['STORE.P/A → USER.A','C不变，释放该部分B额度'],'point'),('已兑付，D未付',['RECEIVE预留 → USER.A','D→C，释放该部分B额度'],'point'),('已付款，回款已到',['RECEIVE预留 → USER.A','真实回款进C，释放B额度'],'money'),('已付款，回款未到',['RECEIVE预留 → USER.A','B→C，消耗预留并登记R'],'risk')]
 for j,(t,ls,col) in enumerate(branches):
  x=35+j*405;o+=card(x,460,375,155,t,ls,col)
  o+=arrow([(1385,295),(1385,360),(x+187,360),(x+187,460)],color='point')
  o+=arrow([(x+187,615),(x+187,720),(805,720),(805,810)])
 o+=card(600,810,410,145,'USER.A 返回可用积分',['确认后才可使用','返分实款已在C，不用R兑付'])
 o+=card(1165,810,440,145,'后续真实追回',['R减少，实际回款补B','不再增加C，不重复退分'],'money')
 o+=arrow([(1437,615),(1437,810)],'迟到回款',color='money',labelpos=(1450,760))
 o+=card(35,1030,1570,130,'累计比例与责任边界',['抵扣退分按累计商品退款；仅退运费不退积分；全商品退补齐尾差。返分收回按原发放行及迟到发放基线单独计算。','门店账单DRAFT先重算；已确认未付款先撤销确认；付款中查询后再走对应分支。'],'guard')
 return '\n'.join(o+['</svg>'])

def class_page(group,model):
 by={c['name']:c for c in model['classes']};names=group['classes'];compact=group['id']=='overview';w=1660;boxw=450;gap=100;x0=40
 positions={};y=155;rows=[]
 for row in range(math.ceil(len(names)/3)):
  row_names=names[row*3:row*3+3];heights=[];wrapped={}
  for n in row_names:
   c=by[n];fields=[] if compact else c['fields'];methods=c['methods'][:2] if compact else c['methods']
   fl=[l for f in fields for l in wrap(f,44)];ml=[l for m in methods for l in wrap(m,44)]
   h=100+max(1,len(fl))*22+max(1,len(ml))*22+22;heights.append(h);wrapped[n]=(fl,ml)
  for col,n in enumerate(row_names):positions[n]=(x0+col*(boxw+gap),y,boxw,max(heights),wrapped[n])
  y+=max(heights)+100
 edges=[e for e in model['relationships'] if e['source'] in names and e['target'] in names]
 footer_lines=max(1,len(edges));h=y+70+footer_lines*24
 o=begin('积分模块类图 · '+group['title'],w,h,'由积分模块图契约JSON生成，UML组合/关联/依赖/接口实现，目标设计非Java源码反射。')
 o.append(text(40,115,'实线＝关联  ◆＝组合  虚线开放箭头＝依赖  空心三角＝接口实现；字段与方法为目标设计。',16,COLORS['muted']))
 # Draw connectors behind opaque class boxes, routing through row gaps.
 for i,e in enumerate(edges):
  a=positions[e['source']];b=positions[e['target']];x,sy,bw,bh,_=a;tx,ty,tw,th,_=b
  kind=e['kind'];side=x+bw;corridor=side+24+(i%4)*10
  if sy==ty:
   if tx>x:pts=[(x+bw,sy+bh/2),(tx,ty+th/2)]
   else:pts=[(x,sy+bh/2),(tx+tw,ty+th/2)]
  elif ty>sy:pts=[(side,sy+bh/2),(corridor,sy+bh/2),(corridor,ty-35),(tx+tw/2,ty-35),(tx+tw/2,ty)]
  else:pts=[(side,sy+bh/2),(corridor,sy+bh/2),(corridor,ty+th+35),(tx+tw/2,ty+th+35),(tx+tw/2,ty+th)]
  path='M'+' L'.join(f'{px},{py}' for px,py in pts)
  dash=' stroke-dasharray="6 4"' if kind in ['dependency','realization'] else ''
  marker='real' if kind=='realization' else 'dep'
  start=' marker-start="url(#diamond)"' if kind=='composition' else ''
  o.append(f'<path d="{path}" fill="none" stroke="#94a3b8" stroke-width="1.6"{dash}{start} marker-end="url(#{marker})"/>')
 for n,(x,cy,bw,bh,(fl,ml)) in positions.items():
  c=by[n];co='#7c3aed' if c['kind']=='interface' else '#2563eb' if c['kind'] in ['entity','value'] else '#0f766e'
  o.append(f'<rect x="{x}" y="{cy}" width="{bw}" height="{bh}" rx="8" fill="white" stroke="{co}" stroke-width="1.5"/>')
  o.append(text(x+16,cy+24,'«'+c['kind']+'»  '+c['package'],13,co));o.append(text(x+16,cy+53,n,20,co,'650'))
  o.append(f'<path d="M{x} {cy+70}H{x+bw}" stroke="{co}" stroke-width="1"/>')
  pos=cy+95
  for line in fl or ['—']:o.append(text(x+16,pos,line,15));pos+=22
  o.append(f'<path d="M{x} {pos-6}H{x+bw}" stroke="#cbd5e1"/>');pos+=18
  for line in ml or ['—']:o.append(text(x+16,pos,'+ '+line if line!='—' else line,15));pos+=22
 o.append(text(40,y+12,'本图内关系（跨图关系及完整属性见图册与JSON契约）',18,weight='650'))
 for i,e in enumerate(edges):
  symbol={'composition':'◆──','association':'──','dependency':'- - >','realization':'- -▷'}[e['kind']]
  o.append(text(40,y+45+i*24,f'{e["source"]} {symbol} {e["target"]}   {e["label"]} {e["multiplicity"]}',15,COLORS['muted']))
 return '\n'.join(o+['</svg>'])

def main():
 parser=argparse.ArgumentParser();parser.add_argument('--check',action='store_true');args=parser.parse_args();m=json.loads(SOURCE.read_text());names={c['name'] for c in m['classes']}
 module=m['module']
 assert module['artifact']=='pay-domain' and module['physicalModule'] is False
 assert module['txManager']=='payTxManager' and module['sqlSessionFactory']=='paySqlSessionFactory'
 for c in m['classes']:
  if c['package'].startswith('pay.'):
   assert c['javaPackage'].startswith(module['businessPackage']+'.') or c['javaPackage']==module['mapperPackage'],c['name']
 assert len(names)==len(m['classes'])
 for e in m['relationships']:assert e['source'] in names and e['target'] in names
 covered=set()
 for g in m['groups']:
  assert len(g['classes']) <= (15 if g['id']=='overview' else 12)
  assert set(g['classes']) <= names;covered.update(g['classes'])
 assert covered==names, names-covered
 # Confirm V1-V4 for each 1:1 example. Reserve/R are independently labelled, not cash.
 for f in m['flows']:
  assert sum(f['points'].values())==0,f['id']
  assert f['funds'].get('U',0)==f['points'].get('ISSUE',0),f['id']
  user_store=sum(v for k,v in f['points'].items() if k.startswith('USER_') or k.startswith('STORE_'))
  assert f['funds'].get('C',0)==user_store,f['id']
  assert sum(f['funds'].values())==int(f['external']),f['id']
 products={}
 def add(name,content):products[OUT/name]=content+'\n'
 add('pts3-module-architecture.svg',module_architecture(module))
 add('pts3-flow-overview.svg',flow_overview())
 add('pts3-flow-funding.svg',pipeline_page('备款发行回流与预算提取',[
  ('补U与发行',[('真实备款核验',['两人审批＋唯一证据'],'guard'),('优先自由接收分',['退款预留格不得用'],'point'),('不足部分登记发行',['MINT减少，等额分到ISSUE'],'point'),('ISSUE与U一起增加',['ΔU=m(ΔISSUE)'],'money')],'自由分与新发行分合计等于本次备款对应积分，不能各发行一遍。'),
  ('补B：独立备款路径',[('真实资金核验',['双人复核、唯一证据'],'guard'),('B增加',['不发行积分','hold只增加预留数'],'money')],'补B与下面U转B是两种独立操作，不串行执行。'),
  ('U转B：内部预算调拨',[('内部调拨批准',['不产生外部现金'],'guard'),('同凭证调分与钱',['U−/B+','ISSUE→RECEIVE自由'],'money')],'发放预算降低、退款备付增加；不能只调整资金而漏掉发放户积分。'),
  ('提取与审批',[('提取U',['ISSUE→接收户自由','U按实际划出减少'],'money'),('提取B',['只能提freeB','不能动已锁退款额度'],'money'),('双人复核与回执',['未知资金保持原意图','确认后完成专项变动'],'guard')],'提取U与提取B是独立分支，均须双人审批与明确资金证据，不按三卡顺序串行执行。','parallel')],'补U/补B、发行与优先回流、内部U转B以及提取预算，各自伴随的积分/资金动作。'))
 add('pts3-flow-settlement.svg',pipeline_page('下单核销与两种商家兑付',[
  ('随单：默认',[('HELD',['USER.A→订单H','协议快照＋B额度'],'point'),('PAYABLE',['H→接收户预留','C→D，门店只留流水'],'point'),('PAYING',['固定ticket与应付','未知只查询不重付'],'guard'),('PAID',['证据成功扣D','积分仍在接收户预留'],'money')],'未支付取消：H→A并释放B。直连补差；归集专项回补，商家货款不再额外付第二次。'),
  ('定期：归集商家本期可用',[('IN_STORE',['H→STORE.P','C不变'],'point'),('SETTLEABLE',['终局STORE.P→A','可进入固定账期'],'point'),('IN_STATEMENT',['固定候选ID/金额','同账期修订重算'],'guard'),('PAYABLE',['确认STORE→接收户','C→D'],'point'),('PAYING',['gross=offset+cash','R锁定未核减'],'guard'),('PAID',['D−gross/B+offset','R−offset，现金出'],'money')],'直连无定期转账能力拒签；抵销不是现金到账。确认前退款修订，确认未付先反转；PAYING先查结果。')],'完整核销头寸状态及用户/门店/接收户积分移动，随单与定期付款来源互斥。'))
 add('pts3-flow-refund.svg',refund_page())
 add('pts3-flow-clawback.svg',pipeline_page('购物返分收回与现金不足处理',[
  ('实际可收回积分',[('逐行累计计算',['按发放行及迟到基线','仅商品退款，不含运费'],'guard'),('prepare锁P/A',['移到退款H记原来源','固定结果与hash'],'point'),('现金退款结果明确',['未知保持准备重试','不提前给可用退分'],'guard'),('实际H→ISSUE',['C→U','用户不出现负余额'],'point')],'迟到发放例：原100、先退50、再发50，后退25只收25；最后商品全退补齐尾差。'),
  ('已花掉不足部分',[('现金补收上限',['不超过本次可退现金','不支持回平台则为0'],'guard'),('真实回平台确认',['未到不补U','直连留商家不算回款'],'money'),('自由回流或备款发行',['自由分优先，不足MINT','ISSUE/U等额补足'],'point')],'现金退款与补收可分阶段。每腿有独立证据和幂等结果，晚到补收只处理一次。'),
  ('剩余差额',[('无法实际收回',['余额不足/补收不支持'],'guard'),('平台承担 waived',['不增U，不发行','不假记已收回'],'risk')],'积分退款形式本期下线；运营调账也不能绕过发放预算或凭空增加可用积分。')],'退款两侧分开：抵扣退回见退款图，本图是购物返分收回、补收及承担差额。'))
 add('pts3-flow-lifecycle.svg',pipeline_page('退款责任解除与到期',[
  ('关闭后才能回流',[('RECEIVE退款预留',['仍须保留的积分'],'point'),('权威责任解除',['窗口结束/无活动售后','所有资金结果明确'],'guard'),('RECEIVE可回流',['剩余预留→自由','释放对应B额度'],'point')],'结算终局不等于退款责任解除。关闭与prepare锁同头寸；额外补偿需重新审批备款。'),
  ('到期与收入划拨',[('USER.A 到期',['区间并集顺延后判断','P和其他H不动'],'point'),('到期准备H',['C保持实款备付','revision防重复周期'],'point'),('专项收入划拨',['未知/失败保持准备','原意图查询或重试'],'money'),('明确成功后清零',['H→RECEIVE自由','C减少并转平台收入'],'money')],'GRANT/HOLD续期，REVOKE/EXPIRE不续。冻结、暂停不阻断已有退款/付款收尾。'),
  ],'退款责任解除、到期准备/划拨确认及区间顺延；积分未上线，直接采用最新逻辑，不含旧业务数据搬迁。'))
 add('pts3-flow-governance.svg',pipeline_page('运营调账停发及可靠恢复',[
  ('运营调账',[('双人审批与审计',['审批人与提交人不同'],'guard'),('正调账',['ISSUE→USER.A','U→C'],'point'),('负调账',['USER.A→ISSUE','C→U，不能扣负'],'point')],'正负调账是独立分支，分别双人审批；正向需已有U，不是无资金赠分。冻结不取消已有责任。','parallel'),
  ('门禁与失败',[('种类/协议/能力',['发放不要求协议','核销必须签协议'],'guard'),('不足或暂停',['返分记SKIPPED','hold原子返回无抵扣'],'risk'),('展示实际应付',['金额按实际hold结果','不得静默提高支付额'],'guard')],'线下不发/不抵；不足只影响新的承诺，退款、释放、追回等资金收尾继续运行。'),
  ('事件与资金恢复',[('源事务Outbox',['订单/支付各自落事件'],'guard'),('积分Inbox＋过账',['同payTx提交','重复hash冲突拒绝'],'guard'),('UNKNOWN 查询',['付款/退款结果不明','不重付不提前撤销'],'guard'),('事实巡检补偿',['漏发/已付仍H/无单占用','按原命令重放'],'guard')],'订单与pay跨域不共享事务，结算与积分在pay内经Hook同步。对账核源事实与外部证据。')],'运营调整、门禁无动作分支、事件重复/乱序/崩溃以及未知资金恢复。'))
 for group in m['groups']:add('pts3-classes-'+group['id']+'.svg',class_page(group,m))
 flow_sheets=[('overview','完整积分流转总览'),('funding','备款发行回流与预算提取'),('settlement','下单核销与两种商家兑付'),('refund','核销退款与备付'),('clawback','购物返分收回与不足'),('lifecycle','责任解除与到期'),('governance','运营调账与可靠恢复')]
 doc='''# 积分模块完整流转图与类图

状态：v3设计图册，待评审 · 更新：2026-10-14

积分流转图展示五种持有方及分格、两种兑付、退款、备付、回流、到期、调账和异常恢复；类图按模块协作、核心对象、应用服务、订单SPI/结算Hook与持久化拆分。蓝线为积分移动，资金动作另用橙色；R是应收，不充当现金。

由 `python3 scripts/gen-points-diagrams.py` 从[图契约](./积分模块-图契约.json)生成，勿手改。源方案：[积分TDD](../TDD-积分域重整.md) · [资金方案](./积分资金方案-平台预存池.md) · [需求PRD](../../requirements/PRD-积分模块.md)。类图是71个核心设计类，不是当前Java源码反射；积分集成pay-domain，复用payTxManager/paySqlSessionFactory；接口嵌套DTO与基础框架不逐一展开。

## 模块定位

![pay内积分业务模块架构](../diagrams/pts3-module-architecture.svg)

积分与结算是pay内部的业务模块，必要结算结果与积分账务共用短pay事务；外部付款在意图提交后执行。订单与售后仍经SPI、Outbox/Inbox和事实补偿跨域协作。

## 一 完整积分流转

'''
 notes={
 'overview':'正常返分和核销构成主链；随单门店不留余额，定期门店先留分。退分从退款预留恢复，回流只用自由格，付款本身不移动积分。',
 'funding':'补U支持新增发行；补B不发行。U转B必须同时移动发放户积分。提取与内部调拨是不同动作，不能看见箭头就按同一笔顺序执行。',
 'settlement':'上下两行是互斥结算路径；只有明确成功后扣D。账单抵销回补B并核减具体R，不是外部现金收入。',
 'refund':'四个框是按积分位置和资金结果选择的分支，不是依次执行。现金确认后才返可用积分；未追回以B垫付，迟到追回只回B。',
 'clawback':'这张图处理购物返分的收回，与上一张抵扣积分退回分开。不足部分现金补收必须实际回平台；平台承担的差额不造分、不补U。',
 'lifecycle':'解除退款责任必须有权威关闭事实，不能借结算终局提前回流。到期准备仍保持C，收入划拨确认才清零；首次上线按真实备款初始化最新账本，旧方案不参与运行。',
 'governance':'正负调账、停发、取消及未知资金分别处理。订单Outbox与pay消费跨域；结算Hook与积分同pay事务，资金未知仍需查询恢复，不直接改余额。'}
 for id,title in flow_sheets:doc+=f'### {title}\n\n![{title}](../diagrams/pts3-flow-{id}.svg)\n\n{notes[id]}\n\n'
 doc+='## 二 全量流转与资金对照\n\n以下用1积分=1最小货币单位、单次100举例；实际系统按既约比例计算。预留增减仅改变可用额度，不重复扣B；R增减单独记应收。\n\n| 动作 | 积分变化 | U/C/D/B变化 | 实际资金 | B预留 / R | 条件 |\n|---|---|---|---|---|---|\n'
 def changes(d):return '；'.join(k+('+' if v>0 else '−')+str(abs(v)) for k,v in d.items()) or '不动'
 for f in m['flows']:doc+=f'| {f["id"]} {f["name"]} | {changes(f["points"])} | {changes(f["funds"])} | {f["external"]} | 预留{f["reserve"]} / R{f["receivable"]} | {f["condition"]} |\n'
 doc+='\nUSER_SOURCE代表退款准备冻结前P/A来源，STORE_SOURCE代表门店P/A来源，不是新增积分户。各HELD仍是USER.H，只在头寸按订单/退款/到期来源隔离。TOPUP和现金补收的发行/回流为分量选择，不能把两个分量各按全额记一次。\n\n## 三 积分模块类图\n\n'
 for g in m['groups']:
  doc+=f'### {g["title"]}\n\n![{g["title"]}](../diagrams/pts3-classes-{g["id"]}.svg)\n\n'
  doc+='本图表达目标类的字段、方法及本组关系；跨图依赖见下表。接口实现使用空心三角，组合关系使用实心菱形。服务可读快照，写账必须经PointsPostingService。资金适配器留pay.points，依赖pay资金接口；订单事实适配由宿主提供，不反向依赖订单实现。\n\n'
 doc+='积分尚未上线：图中仅最新业务逻辑，不含新旧引擎路由、旧读兼容、迁移批次或开账服务；旧实现按TDD §1.1/§2.12/任务T13移除。\n\n'
 doc+='## 四 完整跨图关系\n\n| 来源类 | 关系 | 目标类 | 语义 / 多重性 |\n|---|---|---|---|\n'
 for e in m['relationships']:doc+=f'| {e["source"]} | {e["kind"]} | {e["target"]} | {e["label"]} {e["multiplicity"]} |\n'
 doc+='\n## 五 图的边界与校验\n\n- 核心类覆盖图契约中全部71个类，细分图每张最多12个节点；总览最多15。基础框架、接口嵌套Command/Outcome与非账务元数据Store不冒充已实现类。\n- 模块边界是pay内业务包，不新增JAR/数据源/事务；方法为设计契约的具名化补充，实施时与TDD/Java签名共同对齐；不会从旧PointsService自动推断新行为。\n- 生成器检查类唯一、关系目标、分组覆盖和节点上限，并对32条流转样例核积分守恒、U/发放户、C/用户门店与真实资金总额变化。\n- `python3 scripts/gen-points-diagrams.py --check`验证产物与图契约一致；SVG可直接放大导出，HTML由项目Markdown渲染器生成，不单独维护。\n'
 doc=doc.replace('71个',str(len(m['classes']))+'个').replace('32条',str(len(m['flows']))+'条')
 products[ATLAS]=doc
 bad=[]
 for path,content in products.items():
  if args.check:
   if not path.exists() or path.read_text()!=content:bad.append(str(path.relative_to(ROOT)))
  else:path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content)
 if bad:print('STALE: '+', '.join(bad));sys.exit(1)
 print(f'PASS: {len(m["classes"])} core classes, {len(m["relationships"])} relationships, {len(m["flows"])} balanced flows; {len(products)-1} SVGs '+('checked' if args.check else 'generated')+'.')
if __name__=='__main__':main()
