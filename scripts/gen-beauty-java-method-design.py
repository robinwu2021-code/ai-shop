#!/usr/bin/env python3
"""Generate method-level design documents, not Java business code."""
import argparse
import json
import re
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DESIGN = ROOT / 'docs/technical/design'
SOURCE = DESIGN / 'schema/beauty-java-method-design.json'
CLASSES = DESIGN / '美业系统-Java类与方法清单.md'
METHODS = DESIGN / '美业系统-Controller-Service-Mapper方法清单.md'


def signature(result, name, params, annotations=False):
    return result+' '+name+'('+', '.join(((p.get('annotation', '')+' ') if annotations and p.get('annotation') else '')+p['type']+' '+p['name'] for p in params)+');'


def generate():
    d=json.loads(SOURCE.read_text())
    a=json.loads((SOURCE.parent/d['sourceApis']).read_text())
    structure=json.loads((SOURCE.parent/d['sourceStructure']).read_text())
    schema=json.loads((SOURCE.parent/'beauty-database-design.json').read_text())
    apis={e['id']:e for m in a['modules'] for e in m['apis']}
    mods={m['code']:m['name'] for m in a['modules']}
    bindings={b['apiId']:b for b in structure['apiBindings']}
    eps=d['endpoints']; shared=d['sharedEntries']; mappers=d['mappers']
    assert len({e['apiId'] for e in eps})==len(eps)
    assert {e['apiId'] for e in eps}=={k for k,b in bindings.items() if b['owner']=='beauty-web'}
    assert {e['apiId'] for e in shared}==set(apis)-{e['apiId'] for e in eps}
    assert {m['table'] for m in mappers}=={t['name'] for t in schema['tables'] if t['name'].startswith('bty_')}
    tables={t['name']:t for t in schema['tables']}; mapper_by_table={m['table']:m for m in mappers}
    common={f[0] for f in schema['common_fields']}
    for m in mappers:
        assert m['class']=='ai.neargo.shop.beauty.mapper.BeautyMappers.'+m['class'].split('.')[-1]
        for method in m['customMethods']:
            assert set(method['columns'])<=common|{f['name'] for f in tables[m['table']]['fields']},(m['table'],method['name'],method['columns'])
            assert len({p['name'] for p in method['parameters']})==len(method['parameters'])
            if method['access']=='CAS':assert {'expectedVersion','actorNo','updatedAt'}<={p['name'] for p in method['parameters']}
    for f in d['flows'].values():
        if f.get('pageTable'):assert f['pageTable'] in f['readTables']
        assert set(f['readTables']+f['writeTables'])<=set(mapper_by_table),'跨域表进入美业Mapper'
        for call in f['customCalls']:
            table,name=call.split('.')
            assert table in f['readTables']+f['writeTables'],call
            assert any(m['name']==name for m in mapper_by_table[table]['customMethods']),call
    dtos={x['class'].split('.')[-1]:x for x in d['dtos']}
    assert len(dtos)==len(d['dtos'])
    allowed_types=set(dtos)|{t['entity'].split('.')[-1] for t in structure['tables']}|{'String','long','int','boolean','List','Map','Set','PageData','BigDecimal','LocalDateTime','void','JobResult','JobInvocation'}
    def check_types(text):
        assert set(re.findall(r'\b[A-Z][A-Za-z0-9]*\b',text))<=allowed_types,text
    controllers=defaultdict(list); services=defaultdict(list)
    for e in eps:
        assert e['controller']==bindings[e['apiId']]['controller']
        assert e['service'].split('.')[-1]==bindings[e['apiId']]['service']
        assert e['impl']==e['service'].replace('.service.','.service.impl.')+'Impl'
        assert e['method'].isidentifier() and not re.search(r'(bapi|api\d)',e['method'],re.I)
        assert e['flow'] in d['flows']
        assert e['phase']==apis[e['apiId']]['phase']
        assert {p['name'] for p in e['parameters'] if p['source']=='PATH'}==set(re.findall(r'\{(\w+)\}',apis[e['apiId']]['path']))
        assert not any(p['name'] in {'entityNo','actorNo','userNo','tenantNo','authorizedStoreNos'} for p in e['parameters'])
        assert len({p['name'] for p in e['serviceParameters']})==len(e['serviceParameters'])
        assert (not e['readOnly'])==any(p['name']=='idempotencyKey' for p in e['parameters'])
        assert not e['readOnly'] or not d['flows'][e['flow']]['writeTables']
        check_types(signature(e['result'],e['method'],e['serviceParameters']))
        row={'name':e['method'],'returnType':e['result'],'parameters':e['serviceParameters'],'apiId':e['apiId'],'flow':e['flow']}
        controllers[e['controller']].append(e);services[e['service']].append(row)
    for x in d['supplementalServiceMethods']:
        assert x['service'] in services
        check_types(signature(x['returnType'],x['name'],x['parameters']))
        services[x['service']].append(x)
    for methods in services.values():assert len({signature(x['returnType'],x['name'],x['parameters']) for x in methods})==len(methods),'Service签名重复'
    for methods in controllers.values():assert len({e['method'] for e in methods})==len(methods),'Controller方法重复'
    for h in d['supportClasses']:
        for method in h['methods']:check_types(method)
    for row in d['verifiedSpiEvidence']:
        src=(ROOT/row['path']).read_text()
        assert re.sub(r'\s+','',row['signature']) in re.sub(r'\s+','',src),row['owner']
    assert {x['apiId'] for x in d['sharedHttpMethods']}=={k for k,b in bindings.items() if b['owner']=='共用人员/资源所有者'}
    prefix=['> 状态：方法级目标与实施对照 · 2026-10-21 · 已实现范围和部署结果以主TDD为准，不能把整张清单当已完成。',
            '> 由 `python3 scripts/gen-beauty-java-method-design.py` 生成，勿手改；真源：[Java方法设计JSON](./schema/beauty-java-method-design.json)。',
            '> 依据：[统一Java规范§七](../../../.claude/skills/ai-shop-dev-standards/references/java.md) · [独立项目结构](../TDD-美业系统-独立项目与包依赖方案.md) · [API清单](./美业系统-API清单.md) · [数据库结构](./美业系统-数据库结构设计.md)。']
    method_count=sum(len(xs) for xs in services.values())
    rules=[
        'Controller仅绑定路径/Body/Query/协议头、鉴权与调用Service，返回业务VO/PageData；沿平台ApiResponseWrapper，不再包ApiResult。声明省略import、类级条件、Mapping和权限注解，HTTP方法/路径在标题；实施必须补齐其对应注解及“为什么”的公开API Javadoc。',
        'Service接口归beauty-api/service，Impl归beauty-core/service/impl且同名后缀Impl；Service方法无HTTP注解，Impl直接注入本域Mapper。禁止新增Repository、等义Application/DomainService或一个巨型BeautyManager。',
        '经营主体用entityNo；BizContext.requireMerchantNo()是现有兼容来源，在Web接入处映射为该语义。currentStoreNo/actorNo来自可信上下文，actorNo是账号/用户/运营操作身份，实际执行人员由共用账号关联及任职核验取得，不能把无登录人员伪装成操作者。',
        'Service签名显式接收可信范围；租户沿平台当前MAIN及数据域规则，不另启租户体系。详情按对象解析归属后与获准范围相交，SQL仍限entity/门店/受控父链。技师本人范围由可信数据域/共用任职授权解析，不采信query.personnelNo；非受权跨店请求拒绝。',
        '消费者本人方法接userNo，由SecurityUtils.requireUser()解析；publicStore/publicService/publicAvailability只由真实门店/目录确定主体，允许公开查询，不要求伪造商家身份。本人方法每次验证资源属主，不接收客户端自报userNo/entityNo/actorNo。',
        'ops的entityNo与authorizedStoreNos是平台授权目标，不是客户端任意主体；actorNo是当前运营身份，不代商家。技术恢复仍执行同一业务校验，不代顾客同意或代技师完工。跨店报表只读authorizedStoreNos与查询条件交集。',
        'Req/Query按统一Validation校验必填、长度、枚举、正数/精度与时间窗口；普通page>=1、size默认20且最大50，日历资源页/窗口沿工作台合同。错误沿统一ErrorCode/BizException及三语词条，不另造BeautyError体系。Req/Query只包含业务字段，VO不含物理id。body/query字段沿API输入合同，必须剔除路径字段及仅属身份的可信参数；预览编号绑定原操作者/主体/门店/窗口/版本，不能换人拿号提交。',
        '版本请求expectedRevision映射到聚合BaseEntity.version；scopeRevision/bindingRevision/appointmentRevision/planRevision分别是范围、来源、安排、计划版本，不能混用。步骤/参与子行CAS版本取锁内真实行，根版本仍按用户期望检查。',
        '业务写命令带Idempotency-Key，同键同载荷取原号、异参拒绝；预览与查询POST无业务副作用，不强制业务幂等头。长流程操作号/请求指纹由共用幂等编排与本域ServiceSubmission持久记录，不因超时换键。',
        'Mapper继承BaseMapper，常用CRUD不重写空壳；只对稳定行锁、CAS、数量上限等新增自定义方法。BaseMapper虽暴露删除/按id等方法，本域事实业务不使用delete/archive覆盖历史，也不把业务号传selectById。',
        '普通分页以用例的真实业务根/组合投影分页，返回IPage后用MybatisPages.of转换PageData；数据量大的列表不用PageData.ofAll。CalendarSnapshotVO/CalendarPageVO是快照协议，不套普通分页，不将未加载/延迟当空闲。批量获取任务/来源/资源，禁止每卡片循环跨域N+1。',
        'API用带偏移时间并按门店IANA时区显示；本域Entity/自定义SQL采用与BaseEntity一致的LocalDateTime，边界明确转换成UTC本地值。区间[from,to)，数量BigDecimal，金额整数最小单位+币种；不引入浮点金额或全局时区重构。',
        'LOCAL_ATOMIC仅指本域短事务；ORCHESTRATED先保存意图再外部调用、后短事务确认/补偿。调用清单是候选分支，不是一次必须全执行的线性脚本；HTTP不在长锁事务中等待，UNKNOWN按原号恢复。',
        '多行业同名短类通过受控扫描/必要Bean命名策略避免冲突；新增类正常import、构造器注入，不复制零售存量全限定注解/通配import等历史欠账。规范以统一Java文档为准，餐饮只参考模块隔离/薄适配和方法追溯。'
    ]
    classes=['# 美业系统 Java 类与方法清单', '']+prefix+['', '## 一、范围与规范', '',
             f'目标包含{len(controllers)}个美业用户Controller、{len(services)}个Service及同名Impl、25个内嵌Mapper、25个Entity、{len(dtos)}个Req/Query/VO和{len(d["supportClasses"])}个必要辅助类。{len(eps)}条美业HTTP方法与{len(d["supplementalServiceMethods"])}条内部业务方法合计{method_count}条Service方法。按需提供同接口远程实现、协议客户端和内部入口，不能把目标清单当已生成源码。', '',
             '11个共用HTTP目标另列其所属域的类/方法，其中5个人员、2个资源和1个班表查询已编码，另补1个P1周规则写入口，54个CONTRACT是能力接入点而非54条已定Java方法。共享资源、账户、打印、报表等不复制到美业；需要补齐的正式契约在原所有者评审。', '',
             '![模块依赖](../diagrams/beauty-java-module-dependencies.svg)', '',
             '图由项目结构真源生成。签名清单沿api/core/web/channel/server交付，业务归属不因同库或同名改变。逐API调用与Mapper约束见[方法清单](./美业系统-Controller-Service-Mapper方法清单.md)。', '']
    classes += [f'{i}. {rule}' for i,rule in enumerate(rules,1)]
    classes += ['', '## 二、Controller类与直接方法', '', '全部位于beauty-web。以下为方法声明，注解中未使用全限定名；实现时用正常import。']
    for cls,es in sorted(controllers.items()):
        classes += ['', '### '+cls, '', '```java']
        classes += [signature(e['result'],e['method'],e['parameters'],True) for e in es]
        classes += ['```', '', '追溯：'+ '、'.join(e['apiId'] for e in es)+'。']
    classes += ['', '## 三、Service接口与同名Impl方法', '', '每组接口位于beauty-api，同名Impl位于beauty-core；Impl覆写同一签名，不再人为复制一组不同的Command/DTO。']
    for cls,ms in sorted(services.items()):
        impl=cls.replace('.service.','.service.impl.')+'Impl'
        classes += ['', '### '+cls, '', f'实现：`{impl}`。', '', '```java']
        classes += [signature(m['returnType'],m['name'],m['parameters']) for m in ms]
        classes += ['```']
        for m in ms:
            if m.get('note'):
                f=d['flows'][m['flow']]
                classes += ['', f'`{m["name"]}`：{m["note"]}。', '', '内部流程：**'+f['transaction']+'**；读取：'+'、'.join(f['readTables'])+'；写入：'+('、'.join(f['writeTables']) or '无')+'。', '', '守卫：'+f['note']+'。']
    classes += ['', '## 四、远程Service、客户端和内部Controller', '',
                'standalone时需要以下按资源切片的目标类；embedded不装Remote实现。RemoteXxxService覆写上节同一Service的全部方法，仅做协议/错误映射；内部调用采用签名身份与动作/对象/范围委托，不能将可信Service参数直接作为可伪造Body。', '',
                '协议client方法与内部Controller使用同一业务方法名和返回值；用户入口方法沿原Path/Body/Query/幂等头绑定，可信范围由内部安全层验证委托后恢复再调用Service。非HTTP补充动作使用Typed Body绑定其业务参数，entity/store/actor依然取可信委托。每个操作使用固定、显式内部路由白名单，不注册按任意方法名反射调用的万能RPC；内部路由最终发布前单列协议并做契约测试。', '',
                '| Service名 | beauty-web远程实现完整名 | beauty-channel协议客户端完整名 | beauty-core内部Controller完整名 |', '|---|---|---|---|']
    for cls,ms in sorted(services.items()):
        name=cls.split('.')[-1];resource=name.removesuffix('QueryService').removesuffix('Service')
        classes.append(f'| {name} | `ai.neargo.shop.beautyclient.impl.Remote{name}` | `ai.neargo.shop.beauty.client.{resource}InternalClient` | `ai.neargo.shop.beauty.api.internal.Internal{resource}Controller` |')
    classes += ['', '该表是结构定位，未虚构已经发布的内部HTTP路径、现有委托DTO或已运行的RPC能力。具体Controller→Service→Impl业务方法在本清单已直接给出，内部薄传输不复制业务算法。', '',
                '## 五、Mapper与Entity类', '', '| 表 | Entity完整名（beauty-core） | 内嵌Mapper完整名（beauty-core） | 自定义方法数 |', '|---|---|---|---:|']
    for m in mappers:classes.append(f'| {m["table"]} | `{m["entity"]}` | `{m["class"]}` | {len(m["customMethods"])} |')
    classes += ['', '外部文件为`ai/neargo/shop/beauty/mapper/BeautyMappers.java`，每个嵌套接口继承其BaseMapper<Entity>。Entity继承`ai.neargo.shop.common.BaseEntity`，使用零售相同Getter/Setter、审计/乐观锁/逻辑删除基类；entityNo等自身字段按表补充。Entity不添加业务跨域调用，字段、唯一键和类型以数据库字段字典为准。所有Mapper继承方法与自定义完整签名见方法清单§四。', '',
                '## 六、Req / Query / VO类型', '',
                '全部位于beauty-api的`ai.neargo.shop.beauty.dto`；以下合同摘要同时包含路径/查询/body说明，定义record字段时按绑定来源拆分，不把actor/entity或路径键再塞一遍body。签名明确指向具体类型，不用Map<String,Object>代替公开入参。相同payload且授权由方法区分时复用同一Req，例如顾客/商家取消复用CancelAppointmentReq。VO集合和状态细节沿API/日历契约，此表不另造第二套JSON字段定义。', '',
                '| 完整类名 | 类型 | 合同摘要 | 追溯 |', '|---|---|---|---|']
    for dto in sorted(d['dtos'],key=lambda x:x['class']):
        classes.append(f'| `{dto["class"]}` | {dto["kind"]} | '+ '；'.join(dict.fromkeys(dto['fieldsContract']))+' | '+('、'.join(dto['apiIds']) or '内部产出分配')+' |')
    classes += ['', '## 七、必要辅助类与方法', '']
    for h in d['supportClasses']:
        classes += ['### '+h['class'], '', '模块：'+h['module']+'；'+h['note']+'。', '', '```java']+h['methods']+['```','']
    classes += ['## 八、装配类边界', '',
                '| 完整类名 | 模块 | 方法/职责 |', '|---|---|---|',
                '| ai.neargo.shop.beauty.config.BeautyAutoConfiguration | beauty-core | 条件装配本域Service、Mapper、Job/事件；只按真实Bean签名创建@Bean工厂，无业务API方法 |',
                '| ai.neargo.shop.beauty.config.BeautyWebAutoConfiguration | beauty-web | 独立Web条件；不得依赖core内配置类/条件注解 |',
                '| ai.neargo.shop.beautyclient.BeautyClientConfiguration | beauty-web | standalone选择RemoteXxxService，embedded选本地同接口；不得双提供方 |',
                '| ai.neargo.shop.beauty.server.BeautyApplication | beauty-server | `public static void main(String[] args)`；仅受控扫描启动包与显式导入业务/内部安全 |',
                '| ai.neargo.shop.beauty.server.config.BeautyInternalSecurityConfiguration | beauty-server | 验证内部身份、重放防护、委托动作/范围；具体工厂沿平台安全栈，不复制新用户登录体系 |', '',
                'DTO类型引用已逐项核对；字段完整定义沿API与数据库源，内部RPC、配置工厂尚无已发布协议。配置Properties、PersistenceConfiguration等按项目结构和首次接线实际签名增补；不为“类清单齐全”虚构大量@Bean方法。完整DTO字段定稿、内部RPC协议及Java编译/装配/并发验证是实施阶段工作，本轮只交付可审阅的类/方法设计。', '']
    methods=['# 美业系统 Controller、Service 与 Mapper 方法清单', '']+prefix+['',
             '## 一、方法及持久化规则', '',
             f'本清单逐项覆盖{len(eps)}条美业候选HTTP、{len(d["sharedHttpMethods"])}条共用候选HTTP，并保留54项共用CONTRACT对照。Mapper共{len(mappers)}个，新增{sum(len(m["customMethods"]) for m in mappers)}个必要自定义锁/CAS/查询方法；其余使用继承CRUD。类总表、完整Service与Impl签名、DTO和辅助类见[Java类与方法清单](./美业系统-Java类与方法清单.md)。', '']
    methods += [f'- {rule}' for rule in rules]
    methods += ['', '固定锁顺序：先workOrder范围根（适用时），再按稳定业务号排序的task/appointment根，再子行。资源桶锁由共用资源所有者自己仲裁，不能在美业长事务中调远程持锁。首版/空行靠业务唯一键与持久化意图竞争，不能用空SELECT FOR UPDATE或MAX+1冒充互斥。', '',
                '## 二、按模块直接输出Controller与Service方法', '']
    bymod=defaultdict(list)
    for e in eps:bymod[bindings[e['apiId']]['module']].append(e)
    for code,title in mods.items():
        methods += ['### '+code+' '+title, '']
        if not bymod[code]:methods += ['本模块为共用能力接入，不生成美业同义Controller/Service/Mapper；共用入口见§三及§五。',''];continue
        for e in bymod[code]:
            a=apis[e['apiId']];f=d['flows'][e['flow']]
            methods += [f'#### {e["apiId"]} · {a["method"]} {a["path"]}', '', f'目的：{a["purpose"]}；阶段：{e["phase"]}；模式：**{f["transaction"]}**。', '', f'Controller（beauty-web）：`{e["controller"]}`', '', '```java',signature(e['result'],e['method'],e['parameters'],True),'```','',f'Service（beauty-api）：`{e["service"]}`；Impl（beauty-core）：`{e["impl"]}`','', '```java',signature(e['result'],e['method'],e['serviceParameters']),'```','']
            reads=[mapper_by_table[t]['class'].split('mapper.')[1]+'.'+('selectPage' if e['result'].startswith('PageData<') and f.get('pageTable')==t else 'selectList') for t in f['readTables']]
            writes=[]
            for t in f['writeTables']:
                immutable=t in {'bty_store_policy_version','bty_service_definition_version','bty_service_requirement','bty_appointment_assignment','bty_trade_binding','bty_care_note','bty_action_log','bty_performance_share'}
                ops='insert' if immutable else 'insert / 受控update分支'
                writes.append(mapper_by_table[t]['class'].split('mapper.')[1]+'.'+ops)
            methods += ['本域读取：'+('；'.join('`'+s+'`' for s in reads) if reads else '无直接本域表读取（使用共享提供方/快照缓存）')+'。', '', '本域写入候选：'+('；'.join('`'+s+'`' for s in writes) if writes else '无')+'。']
            if f['customCalls']:
                methods += ['', '关键自定义调用：'+'；'.join('`'+mapper_by_table[ref.split('.')[0]]['class'].split('mapper.')[1]+'.'+ref.split('.')[1]+'`' for ref in f['customCalls'])+'。']
            methods += ['', '跨域/基础设施：'+('；'.join(f['externalCapabilities']) if f['externalCapabilities'] else '无新增跨域副作用')+'。', '', '守卫：'+(f['note'] or '按可信范围读取/校验，依能力和对象版本判断可用动作；写入仍须幂等和合法前态。')+'。','']
    methods += ['## 三、11条共用HTTP目标与实施对照', '',
                '以下为人员/资源所有者的目标及部分已实施签名；逐项状态见API清单与主TDD。人员、物理资源、周班表读写已编码，P3例外审批等仍待实施；这些类不在beauty-web/api/core中生成。归shop-merchant自身既有包边界，Service接口/Impl按原规范分包；Controller不得转而依赖人员Mapper。具体共享实现要同步共享契约评审与独立架构扫描。', '']
    for e in d['sharedHttpMethods'] + d.get('sharedSupplementalHttpMethods', []):
        a=apis.get(e['apiId'], e)
        methods += [f'### {e["apiId"]} · {a["method"]} {a["path"]}', '', f'状态：{e.get("status", "CANDIDATE_SHARED_OWNER_NOT_EXISTING_API")}。', '',f'Controller：`{e["controller"]}`；输入类型：`{e["inputType"]}`。返回VO沿同域merchant.dto；非美业DTO。','', '```java',signature(e['returnType'],e['name'],e['parameters'],True),'```','',f'Service：`{e["service"]}`；Impl：`{e["impl"]}`。','', '```java',signature(e['returnType'],e['name'],e['serviceParameters']),'```','']
    if d.get('sharedDtoContracts'):
        methods += ['### 本批已冻结的共用维护DTO', '', '| 类（shop-merchant） | 字段合同 |', '|---|---|']
        for contract in d['sharedDtoContracts']:
            methods.append(f'| `{contract["class"]}` | {contract["contract"]} |')
        methods += ['', '实际字段校验和修订语义以同域源码及主TDD§2.12（人员）、§2.13（资源）、§2.14（周班表）为准；不加入美业私有DTO/表统计。', '']
    methods += ['共用MerchantMappers与mch表的具体增量不在美业BeautyMappers中生成；人员同主体稳定号、账号可选关联、跨店统一STAFF资源、任职资格有效期及完整资源原子仲裁，沿数据库/工作台方案验证。', '',
                '## 四、25个美业Mapper直接方法', '',
                '完整外层：`ai.neargo.shop.beauty.mapper.BeautyMappers`，源码归beauty-core。下列CRUD是继承方法的常用调用形状，**不需要重新声明/实现**；新增自定义方法才写SQL。Wrapper由Service构造，强制主体/门店或bty受控父链、合法状态/版本。返回Entity只在core内使用，不返Controller。', '',
                '自定义SQL必须显式tenant/entity/业务键（子行经受权父链）范围；当前tenant沿MAIN，不新增客户端租户字段。CAS携带expectedVersion，成功version+1，并明确updatedBy/updatedAt；采用标量入参时不能假设MetaObjectHandler自动填审计。自定义int返回0必须转为明确冲突/越权/状态错误，不能静默成功。', '']
    for m in mappers:
        entity=m['entity'].split('.')[-1]
        methods += [f'### {m["class"]}', '',f'表：`{m["table"]}`；Entity：`{m["entity"]}`。', '', '继承调用形状：', '', '```java',f'int insert({entity} row);',f'{entity} selectOne(Wrapper<{entity}> queryWrapper);',f'List<{entity}> selectList(Wrapper<{entity}> queryWrapper);',f'<P extends IPage<{entity}>> P selectPage(P page, Wrapper<{entity}> queryWrapper);',f'int update({entity} row, Wrapper<{entity}> updateWrapper);','```','']
        if not m['customMethods']:methods += ['无新增自定义方法。不可变事实只调用insert及受控读；有状态子行的更新须由根锁/CAS协议保护。',''];continue
        for cm in m['customMethods']:
            methods += ['```java',signature(cm['returnType'],cm['name'],cm['parameters']),'```','',f'{cm["access"]}守卫：{cm["guard"]}','']
    methods += ['## 五、54个共用CONTRACT及既有方法证据', '',
                'CONTRACT是一个能力接入点，可能覆盖多个既有接口，不能从名称机械生成一个Java Service方法。下表全部有归属说明；其真实签名以原源码为准，资源仲裁/高级收银等缺少完整合同的能力先在原域完成评审，不能临时写BeautyPayService/BeautyResourceMapper来凑数。', '',
                '| API编号 | 共用合同 | 接入要求 |', '|---|---|---|']
    for entry in d['sharedEntries']:
        a=apis[entry['apiId']]
        if a['method']!='CONTRACT':continue
        methods.append(f'| {a["id"]} | `{a["path"]}` | {a["purpose"]}；{a["phase"]}；{a["request"]} → {a["response"]}；按原所有者正式合同，不新增美业同义Java类 |')
    methods += ['', '以下方法已逐条与当前源码比对，仅作为复用证据，不表示所有接入合同都已满足：', '']
    for proof in d['verifiedSpiEvidence']:
        methods += [f'### {proof["owner"]}', '', '[源码](../../../'+proof['path']+')；'+proof['note']+'。','', '```java',proof['signature'],'```','']
    methods += ['## 六、事务与验收重点', '',
                '- 首次业务号/请求键、来源guard、业绩/提成首版：依唯一键创建，重复只复用同键同载荷；后续版本锁稳定首版或本域根。并发不以进程内synchronized/分布式缓存锁替代数据库约束。',
                '- 开工许可：权威来源必须与并发撤销/退款建立许可/围栏；纯“查询已付后写任务开始”存在竞态，缺少正式合同就阻断能力。资源开工/交接也必须有权威结果及原号恢复。',
                '- 关闭范围：freezeScope后复查任务/绑定版本并锁定对应完成事实；不能只靠工单缓存计数。所有范围变更和任务动作须遵守同一锁序与冻结规则。',
                '- SourceAllocationGuard唯一键不含sourceRevision，当前门店经来源归属验证；CAS累计分配不超过sourceQty，未知来源不可替换，已消费数量不任意回退。',
                '- 方法清单检查只证明接口/归属/表列引用一致，不证明SQL锁、HTTP鉴权、审计填充或并发正确。实施需架构扫描覆盖整个美业reactor、Bean/路由测试、隔离库事务并发和跨域故障恢复验证；不得把文档生成通过称为Java编译通过。', '']
    increment = [
        '## BM03-a1增量实施清单（2026-10-21）', '',
        '原目标JSON/49任务保持基线；本节是私有V2的内部存储增量，未注册来源provider、Service或预约Controller。完整状态见[主TDD§2.15/§26](../TDD-美业系统-后端开发计划.md)。', '',
        '| 类 | 实际职责 |', '|---|---|',
        '| beauty.entity.BtyAppointmentResourceIntent | 原始计划LONGTEXT、命令/来源修订/摘要、决定与对账阶段；继承BaseEntity |',
        '| beauty.entity.BtyAppointmentResourceFence | 预约永久根、单一活动意图指针及版本；继承BaseEntity |',
        '| BeautyMappers.AppointmentResourceIntentMapper | scoped command锁读、authorize/reconcile CAS；调用参数不能证明权限或终态真实性 |',
        '| BeautyMappers.AppointmentResourceFenceMapper | scoped root锁读、acquire/releaseReconciled CAS；不按许可TTL释放 |',
        '| BeautyMappers.AppointmentMapper | changeSchedule/changeStatus/markArrived统一检查活动fence，含确认/取消 |', '',
        '以下方法均在原beauty-core包；每个写事务先锁预约根→永久fence→intent，Mapper仅持久化。', '',
        '```java',
        'BtyAppointmentResourceFence selectScopedForUpdate(String entityNo, String storeNo, String appointmentNo);',
        'int acquire(String entityNo, String storeNo, String appointmentNo, String intentNo,',
        '        long expectedFenceVersion, String actorNo, LocalDateTime updatedAt);',
        'int releaseReconciled(String entityNo, String storeNo, String appointmentNo, String intentNo,',
        '        long expectedFenceVersion, String actorNo, LocalDateTime updatedAt);',
        'BtyAppointmentResourceIntent selectByCommandForUpdate(String entityNo, String storeNo, String commandNo);',
        'int authorize(String entityNo, String storeNo, String intentNo, long expectedVersion, String decisionNo,',
        '        LocalDateTime issuedAt, LocalDateTime validUntil, String actorNo, LocalDateTime updatedAt);',
        'int reconcile(String entityNo, String storeNo, String intentNo, long expectedVersion,',
        '        int terminalCode, String terminalHash, String actorNo, LocalDateTime updatedAt);',
        '```', '',
        'a2仍须实现正式权限、持久预约生成计划、同Binding幂等及可信资源终态核实；本批不增加可调用预约HTTP。', '']
    increment += [
        '## BM03-a2-1共用核验增量（2026-10-21）', '',
        '本节为已编码的所有者只读前置，不改变原49任务或160编号；不是完整美业来源授权、预约或终态解冻。', '',
        '| 模块 / 完整类 | 职责与实际方法 |', '|---|---|',
        '| shop-base / ai.neargo.shop.spi.user.MerchantOperatorAccessPort | boolean allowed(Query query)；显式租户/主体/店/操作者realm+subjectKind/权限 |',
        '| shop-base / ai.neargo.shop.spi.resource.ResourceCommandFactsPort | Lookup lookup(ResourceSourceAuthorityPort.Request request)；完整Binding，原命令状态/结果/摘要；不以当前持有头替代历史结果 |',
        '| shop-base / ai.neargo.shop.spi.resource.ResourceRecoveryInternalApi | Boolean operatorAccess(Query query)；Lookup command(Request request)；PLATFORM内部协议 |',
        '| shop-merchant / ai.neargo.shop.merchant.port.MerchantOperatorAccessPortImpl | 实际有效成员、店授权、角色权限精确或裸*匹配；MCH/USR隔离，歧义拒绝，无静态角色兜底 |',
        '| shop-merchant / ai.neargo.shop.merchant.port.ResourceCommandFactsPortImpl | 实际原command/owner父链，完整Binding比对，REJECTED/SUCCEEDED摘要及ACCEPTED非终态 |',
        '| shop-merchant / ai.neargo.shop.merchant.mapper.MerchantMappers.OperatorAccessMapper | List<OperatorAccessRow> selectAccess(Query query)；最多2行用于拒绝歧义 |',
        '| shop-merchant / ai.neargo.shop.merchant.mapper.MerchantMappers.ResourceCommandMapper | ResourceCommandFactRow selectOriginalFact(ResourceCommandScopeRow scope, String commandNo)；只读投影 |',
        '| shop-app / ai.neargo.shop.portal.internal.InternalResourceRecoveryController | ResponseEntity<?> operatorAccess(Query query,String given) / command(Request request,String given)；先机器令牌、保留错误HTTP状态 |',
        '| beauty-channel / ai.neargo.shop.beauty.port.RemoteMerchantOperatorAccessPort | boolean allowed(Query query)；真实PLATFORM链路，不把失败当false |',
        '| beauty-channel / ai.neargo.shop.beauty.port.RemoteResourceCommandFactsPort | Lookup lookup(Request request)；完整返回Binding比对，不把失败当缺失 |',
        '| beauty-channel / ai.neargo.shop.beauty.config.BeautyMerchantClientConfiguration | 条件缺失时接远程两个Port，embedded仍使用本地owner |', '',
        '内部Lookup必须显式command字段，允许显式null；缺字段/200错误信封拒绝，未知owner运行异常503；只读OperatorAccessMapper显式@Mapper由宿主实际注解扫描注册。', '',
        '接口、owner及远程适配均在事务外读取；事实只说明读取时状态，不是来源许可。未新增可授权provider/预约Controller，待a2-2基于真实持久业务计划与来源根锁继续实现。', '']
    booking_increment = ['', '## Task52 BM03端点与独立双向机器协议增量（2026-10-21）', '',
        '以下仅为已编码/协议测试的增量；原160项设计编号保留。Task53负责实际JAR/进程部署验收，BM04/06未打开。', '',
        '| API / 方法 | Controller / 实际Service | 状态 |', '|---|---|---|']
    for e in eps:
        if e.get('implementationStatus', '').startswith('IMPLEMENTED_TASK52'):
            booking_increment.append(f'| {e["apiId"]} / `{e["method"]}` | {e["controller"]}<br>{e["service"]} | {e["implementationStatus"]} |')
    for e in d.get('implementationHttpMethods', []):
        booking_increment.append(f'| `{e["method"]} {e["path"]}`<br>`{e["signature"]}` | {e["controller"]}<br>{e["service"]} | {e["status"]} |')
    booking_increment += ['', '| 内部方法 / 路径 | 所有者 | 请求 | 返回 |', '|---|---|---|---|']
    for e in d.get('implementationProtocols', []):
        booking_increment.append(f'| `{e["method"]} {e["path"]}` | {e["owner"]} | {e["request"]} | {e["response"]} |')
    booking_increment += ['', '| 部署形态 | 唯一来源provider | owner / 装配 |', '|---|---|---|']
    for e in d.get('implementationTopology', []):
        booking_increment.append(f'| {e["deployment"]} | {e["sourceProvider"]} | {e["owner"]}<br>{e["rule"]} |')
    booking_increment += ['', '机器身份为shop-base的DelegatedIdentity与shop-base-auth的DelegatedIdentityContext；只在令牌校验后以consumer/merchantByUser/merchant恢复精确命名空间，finally恢复先前上下文。无存储用户名伪造身份。',
        'BOOKING VIEW或MANAGE可读、MANAGE写；C私有入口显式CONSUMER/USR；公开availability匿名。search incomplete独立ErrorCode80104（三语），内部422保留语义，availability返回complete=false。',
        '所有远程请求在事务外；Lookup显式found，失败不作为absence；source完整Binding和owner完整返回身份校验，capacity透传独立factAt及完整Replacement。CustomerIdentity只读真实成员所有者，不增加金融许可。', '']
    increment += booking_increment
    foundation = d['receptionFoundation']
    increment += ['', '## Task55A身份/认领/到店码增量', '', foundation['status'], '', '```java']
    increment += foundation['methods']
    increment += ['```', ''] + foundation['contracts'] + ['']
    source = d.get('sourceFoundation')
    if source:
        increment += ['', '## Task58 actual local source submission, binding and bill', '', source['status'], '',
                      '| Module / owner | Actual signature |', '|---|---|']
        for row in source['methods']:
            actual = (ROOT / row['path']).read_text()
            assert re.sub(r'\s+', '', row['signature']) in re.sub(r'\s+', '', actual), row['owner']
            increment.append(f"| {row['owner']} | `{row['signature']}` |")
        increment += ['', '| DTO record | Actual fields |', '|---|---|']
        for name, fields in source['dtoRecords'].items():
            increment.append(f'| `{name}` | `{fields}` |')
        increment += ['', '| Concrete helper / Bean | Contract |', '|---|---|']
        for row in source['helpers']:
            increment.append(f"| {row['owner']} | {row['role']} |")
        increment += [''] + [rule + '\n' for rule in source['contracts']]
    classes += increment
    methods += increment
    stats={'controllers':len(controllers),'services':len(services),'serviceMethods':method_count,'dtos':len(dtos),'mappers':len(mappers),'customMapperMethods':sum(len(m['customMethods']) for m in mappers),'ownedHttp':len(eps),'sharedHttp':len(d['sharedHttpMethods'])}
    return {CLASSES:'\n'.join(classes).replace('。。','。'),METHODS:'\n'.join(methods).replace('。。','。')},stats


def main():
    p=argparse.ArgumentParser();p.add_argument('--check',action='store_true');args=p.parse_args();outputs,stats=generate()
    for path,text in outputs.items():
        if args.check:assert path.exists() and path.read_text()==text,f'过期生成物：{path}'
        else:path.write_text(text)
    print('PASS beauty class/method design:',json.dumps(stats,ensure_ascii=False))


if __name__=='__main__':main()
