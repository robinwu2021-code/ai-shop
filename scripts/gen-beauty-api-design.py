#!/usr/bin/env python3
"""Generate and validate the beauty API design; no application routes are registered."""
import argparse
import json
import re
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'docs/technical/design/schema/beauty-api-design.json'
TARGET = ROOT / 'docs/technical/design/美业系统-API清单.md'


def render():
    data = json.loads(SOURCE.read_text())
    raw = (ROOT / data['featureSource']).read_text()
    source_features = set(re.findall(r'^\| (BTY-[A-T]\d{2}) \|', raw, re.M))
    assert set(data['features']) == source_features, '功能源与API追溯未同步'
    apis = [a for m in data['modules'] for a in m['apis']]
    assert len(data['modules']) == 20
    assert len({a['id'] for a in apis}) == len(apis), 'API编号重复'
    assert len({(a['method'], a['path']) for a in apis}) == len(apis), '方法路径重复'
    assert {f for a in apis for f in a['functions']} == source_features, '功能覆盖缺失或错误'
    for a in apis:
        assert a['phase'] == max(data['features'][f]['phase'] for f in a['functions'])
        assert all(a[k] for k in ('purpose', 'request', 'response', 'status'))
        if a['method'] == 'CONTRACT':
            assert not a['path'].startswith('/'), '共用合同不能伪装已实现路由'
        else:
            assert a['method'] in ('GET', 'POST') and a['path'].startswith(('/biz/', '/mp/', '/ops/'))
    methods = Counter(a['method'] for a in apis)
    nhttp = len(apis) - methods['CONTRACT']
    lines = ['# 美业系统 API 清单（按业务模块）', '',
             '> 状态：目标接口契约与实施对照 · 2026-10-21 · 部分接口已编码，模块交付以主TDD部署关口为准。',
             '> 由 `python3 scripts/gen-beauty-api-design.py` 生成，勿手改；真源：[API设计JSON](./schema/beauty-api-design.json)。',
             '> 上游：[118项功能矩阵](../../requirements/美业系统-多端功能矩阵.md) · [领域对象](./美业系统-领域对象与聚合边界.md) · [数据库结构](./美业系统-数据库结构设计.md)。',
             '> 关键详情：[预约工作台与日历契约](./美业系统-预约工作台与日历API设计.md)。', '',
             '## 一、范围与阅读方法', '',
             f'20个业务模块，覆盖全部{len(source_features)}项功能，共{len(apis)}个接口/接入项：{nhttp}个候选HTTP接口、{methods["CONTRACT"]}个共用合同接入点。功能可以对应多个接口，一个共用合同也可能包含多条既有路由；本表不能作为已实现API数量。', '',
             '优先采用行业编排＋共用商业核心：商品、报价、订单、支付、账户余额、权益余次、库存和财务分别由共用所有者维护。美业拥有预约、服务任务、实际参与、护理与疗程服务计划；疗程权益规则、发行、余次和使用账共用Pay能力。疗程计划不等于权益账户。', '',
             '| 标记 | 含义 |', '|---|---|',
             '| 美业候选新接口 | `/biz/x/beauty`、`/mp/x/beauty`、`/ops/x/beauty`为设计路径；需实施前核对行业路由ADR与包边界 |',
             '| 共用候选新接口 | `/biz/personnel`、`/biz/resources`、`/biz/schedules`等是多行业候选增量；不放进美业私有Controller |',
             '| CONTRACT | 业务接入合同名称，没有新增HTTP承诺；复用真实共用路由或内部Service/SPI，不能照合同名称生成路由 |',
             '| P1/P2/P3/P4 | 最低接线阶段；仍须门店能力、权限、来源和合同全部验收，不按日期自动打开 |', '',
             '前=前台/POS；技=技师；管=商家Web；B=老板/店长App；平=平台。前/技/管/B复用商家认证及同一组业务API，服务端按门店、本人任务和字段权限裁剪。C端只使用`/mp`和已有共用消费者合同，不调用商家工作台。', '',
             '## 二、统一请求、权限和结果约定', '',
             '- 身份：tenantNo/entityNo/operator由可信登录上下文产生；storeNo是需验证的业务范围，不接受请求体自行提升主体、操作人或买家。C端customerNo由本人身份解析；原正式资金模式下临客付款仍需真实买家及正式接管。现金线下普通服务临客按Task63 M2显式GUEST与线下购买证据接入，不冒充C。平台诊断需目标范围授权与审计。',
             '- 权限：表中端范围仅是入口。每个BAPI编号实施时绑定独立读/写权限与对象范围，例如`beauty.appointment.read/change`（候选权限名）；技师默认仅本人已分派任务及必要字段，管理操作按功能矩阵申/审/管分离。人员业务岗位与账号权限角色独立。',
             '- 写请求：业务命令使用`Idempotency-Key`；同键同载荷返回原处理结果，同键不同载荷拒绝。预览、查询类POST不产生业务写入；长期异步命令返回操作号，超时/未知必须查原号。客户端自报成功不作为账或履约事实。',
             '- 并发：修改携带`expectedRevision`；任务涉及来源另带`bindingRevision`。预览返回previewNo、expiresAt、目标修订及资源水位；提交由服务端重算并原子仲裁，预览不是资源锁。',
             '- 时间：RFC3339带偏移，服务端按UTC保存、以门店IANA时区解释营业日；区间统一`[from,to)`。金额使用共用Money/币种合同，数量及精度用来源定义，不使用浮点金额。',
             '- 返回：沿用项目统一响应封装，不另发明外层；普通列表数据为`PageData<T>{records,total,page,size}`。快照日历是`CalendarSnapshotVO`，不能套普通分页后将未加载区当空闲。Req/VO字段使用camelCase，业务编号为字符串，数据库id不对外。',
             '- 状态：输出`revision,allowedActions,blockers,asOf`等适用字段；allowedActions仅辅助展示，每个命令仍重新鉴权和校验。异步以`operationNo,status=PENDING/UNKNOWN/SUCCEEDED/FAILED`区分，具体领域状态不混用。',
             '- 错误：下文错误码均为提案，需接入统一ErrorCode/多语言；含`code,fieldErrors,conflicts,retryable,traceNo`等标准可容纳信息，禁止异常堆栈、护理正文、完整身份及余额出现在通用日志。',
             '- 核心副作用：创建/取消预约不等于建单、扣次、退款或履约；ServiceSubmission跨域编排必须留原号与状态，资金/来源未知时阻断受影响开工、来源替换或关闭，不以一次重试生成新来源。', '',
             '## 三、模块总表', '', '| 模块 | 入口端 | 接入归属 | 条目 | 功能数 |', '|---|---|---|---:|---:|']
    for m in data['modules']:
        fs = {f for a in m['apis'] for f in a['functions']}
        lines.append(f'| {m["code"]} {m["name"]} | {m["ends"]} | {m["owner"]} | {len(m["apis"])} | {len(fs)} |')
    lines += ['', '## 四、逐模块接口', '', '入参列列出业务字段；所有分页列表均加page/size，所有变更均适用身份、幂等和版本规则。`?`表示可选，`/`表示备选而非拼接路径；复杂领域校验见上游对象与数据库方案。共用合同的操作必须回到所有者执行，不能在美业重写。']
    for m in data['modules']:
        lines += ['', f'### {m["code"]} {m["name"]}', '', f'入口端：{m["ends"]}；接入归属：{m["owner"]}。写操作还须矩阵对应的操/申/审/管权限。', '', '| 编号 / 功能 / 阶段 | 方法与路径或合同 | 操作与关键入参 | 返回 / 状态 |', '|---|---|---|---|']
        for a in m['apis']:
            refs='、'.join(a['functions'])
            lines.append(f'| {a["id"]}<br>{refs}<br>{a["phase"]} | `{a["method"]} {a["path"]}` | {a["purpose"]}<br>{a["request"]} | {a["response"]}<br>{a["status"]} |')
    lines += ['', '## 五、既有路由证据与兼容边界', '',
              '| 已核查代码 | 已有路由举例 | 本方案如何使用 |', '|---|---|---|',
              '| [BizStaffController](../../../backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizStaffController.java) | `GET/POST /biz/staff`；`POST /biz/staff/{mchAccountNo}/status`；`POST /biz/staff/{mchAccountNo}/store`；`GET /biz/roles` | 是账号与角色接口，不能当无登录业务人员、技能或排班接口；C模块人员候选接口另建，原账号协议保留 |',
              '| [BizStoredValueController](../../../backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizStoredValueController.java) | `POST /biz/stored-value/recharges`；`POST /biz/stored-value/recharges/{rechargeNo}/submit`、`/receive`、`/cancel`；`GET /biz/stored-value/recharges/{rechargeNo}`；`GET /biz/stored-value/accounts`；`POST /biz/stored-value/consumptions/preview`、`/biz/stored-value/consumptions` | J模块候选接入，仍需验收门店隔离、真实来源、消费者授权和未知恢复。代码存在不等于所有门店已开通，不额外开放混付、线上退款或跨店 |',
              '| [数据库方案中的现状核查](./美业系统-数据库结构设计.md#11-当前代码依据与旧稿修订) | 旧预约时段只含门店容量；`AppointmentSlotPort`不具备完整人员/资源匹配 | D/F共享资源仲裁需要新合同，不能仅包装旧tryBook就声明支持跨店人员防重约 |', '',
              '其余CONTRACT项本轮按能力声明，不虚构已存在路径。实施拆分Controller应遵守[Java命名与包边界](../../../.claude/skills/ai-shop-dev-standards/references/java.md)，例如BizAppointmentController、BizServiceTaskController、BizPersonnelController；不要一个巨型BeautyApi承载20模块。', '',
              '## 六、功能追溯与阶段闸门', '', '| 功能 | 功能说明 | 阶段 | API/合同编号 |', '|---|---|---|---|']
    for f in sorted(source_features):
        feat=data['features'][f]
        lines.append(f'| {f} | {feat["title"]} | {feat["phase"]} | '+ '、'.join(a['id'] for a in apis if f in a['functions'])+' |')
    lines += ['', 'P1完成人员/资源统一、单次先付服务、无交易预约、接待工单、服务事实、逐来源账单与异常读模型。P2只接已验收储值/已持有次数权益；P3再开放护理、协作、营销、耗材和经营财务等；P4的付费售卡、团体/并行/上门、混付/后付/定金及资产逆向必须独立评审。', '',
              '## 七、待决与验收边界', '',
              '行业路由前缀、权限码、共用CONTRACT的真实入口与来源类型、异步响应适配，以及资源桶精度需在实施评审定稿。当前文档不注册路由，不更新后端已实现API清单，也不创建前端孤立页面。', '',
              '预约工作台首期选“有水位的全量窗口快照＋短轮询”；可视化按人员/工位切换，并支持未分派任务与异常栏。暂不要求WebSocket或新增通用工作流引擎。具体快照一致性、分页、拖动预览/提交和验收场景见工作台专篇。', '']
    supplemental = data.get('implementationEndpoints', [])
    if supplemental:
        assert len({(x['method'], x['path']) for x in supplemental}) == len(supplemental)
        assert not ({(x['method'], x['path']) for x in supplemental} & {(x['method'], x['path']) for x in apis})
        lines += ['## 八、实施补充入口（独立于原160项设计编号）', '',
                  '以下补充P1周规则、BM03显式手动恢复及双向机器协议，不将P3例外审批冒充P1，也不改变原160项设计编号。Task52协议已实现；BM03实际部署验收待Task53。', '',
                  '| 方法 / 路径 | 所有者 / 用途 | 请求 | 返回 |', '|---|---|---|---|']
        for x in supplemental:
            lines.append(f'| `{x["method"]} {x["path"]}` | {x["owner"]}<br>{x["purpose"]} | {x["request"]} | {x["response"]} |')
        lines.append('')
    bm04 = data.get('bm04Protocol')
    if bm04:
        assert bm04['targetCount'] == len(apis) == 160
        assert len(bm04['publicMethods']) == bm04['publicCount'] == 18
        lines += ['## 九、Task59 BM04 实际公开入口与权限', '', bm04['status'], '',
                  '原160目标编号不变；18条本次接线包括原052/160/恢复入口；新增SERVER19、反向HOST4。实际部署后置，未记为PASS。', '',
                  '权限：' + '；'.join(k + '=' + v for k, v in bm04['permissionLegend'].items()), '',
                  '| 编号 / 路径 | 实际Service方法 | 当前权限 / 幂等 |', '|---|---|---|']
        for row in bm04['publicMethods']:
            lines.append(f"| {row['apiId']} / `{row['method']} {row['path']}` | `{row['service']}.{row['name']}` | {row['permission']} / {row['idempotency']} |")
        lines += [''] + [rule + '\n' for rule in bm04['contracts']]
    foundation = data.get('serviceOperatorFoundation')
    if foundation:
        lines += ['', '## Task62 BM05 shared current-personnel contract', '', foundation['status'], '',
                  '| Private route / owner | Request | Response |', '|---|---|---|',
                  f"| `{foundation['method']} {foundation['path']}`<br>{foundation['owner']} | `{foundation['request']}` | `{foundation['response']}` |", '']
        lines += [rule + '\n' for rule in foundation['contracts']]
    manual = data.get('manualServiceFoundation')
    if manual:
        lines += ['', '## 现金线下核心：共用人工订单准入（Task64 M1）', '', manual['status'], '', manual['authority'], '',
                  '| Method / route | Operation / input | Result |', '|---|---|---|']
        for row in manual['routes']:
            lines.append(f"| `{row['method']} {row['path']}` | `{row['name']}`<br>{row['request']} | {row['response']} |")
        lines += ['', '私有四方法：POST /internal/trade/manual-service-admission/{prepare,confirm,query,source}；绑定MERCHANT真实身份/范围/原operation/完整digest。', '']
        lines += [rule + '\n' for rule in manual['contracts']]
    return '\n'.join(lines)


def main():
    parser=argparse.ArgumentParser(); parser.add_argument('--check',action='store_true'); args=parser.parse_args()
    result=render()
    if args.check:
        assert TARGET.exists() and TARGET.read_text()==result, 'API文档过期，请重新生成'
        print('PASS: 20模块、118功能覆盖、编号/路径唯一、阶段与生成物一致')
    else:
        TARGET.write_text(result); print(TARGET.relative_to(ROOT))


if __name__ == '__main__':
    main()
