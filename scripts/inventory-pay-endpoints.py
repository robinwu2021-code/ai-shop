"""盘点 ai-shop 网关模块里与支付相关的入口，输出 Markdown（TDD-支付三端经网关透传与权限归属-附录-入口盘点.md 的正文）。

用法：python3 scripts/inventory-pay-endpoints.py <仓库根目录>

两部分：
1. 机器盘点：每个 controller 的接口数、判权码、依赖；以及 v2 实际形态（shop.pay.deployment=standalone）下它的支付客户端
   走「远程」（payclient/impl/Remote*.java，经 near-pay 内部接口）还是「进程内」（只有同进程实现，业务在 ai-shop 进程里），
   远程的列出调到的 near-pay 内部地址（从 Remote* 调用的方法 → *InternalApi 上的 @XxxExchange → 地址常量解析）。
2. 阶段 0 结论：C1 逐个的结论（迁 / 等 near-pay 补公开接口 / 改判 C2），写在下面的 CONCLUSIONS 里，人工维护；
   near-pay 公开接口按 near-pay develop/s1 核对。
"""
import os
import re
import sys

root = sys.argv[1]
portal = os.path.join(root, 'backend/shop-gateway/src/main/java/ai/neargo/shop/portal')
impl_dir = os.path.join(root, 'backend/shop-gateway/src/main/java/ai/neargo/shop/payclient/impl')
client_dir = os.path.join(root, 'backend/pay/pay-domain/src/main/java/ai/neargo/shop/pay/client')

# ---------- near-pay 内部接口：方法名 → 地址 ----------

def read(path):
    with open(path, encoding='utf-8') as fh:
        return fh.read()


def constants(path):
    out = {}
    if not os.path.exists(path):
        return out
    for name, expr in re.findall(r'String\s+([A-Z_]+)\s*=\s*([^;]+);', read(path)):
        out[name] = expr.strip()
    return out


PATHS = {
    'PaymentInternalPaths': constants(os.path.join(client_dir, 'PaymentInternalPaths.java')),
    'PayInternalPaths': constants(os.path.join(client_dir, 'PayInternalPaths.java')),
}


def resolve(expr, local):
    parts = []
    for tok in [t.strip() for t in expr.split('+')]:
        if tok.startswith('"'):
            parts.append(tok.strip('"'))
        elif '.' in tok:
            holder, name = tok.split('.', 1)
            parts.append(resolve(PATHS.get(holder, {}).get(name, '?'), PATHS.get(holder, {})))
        else:
            parts.append(resolve(local.get(tok, '?'), local) if tok in local else '?')
    return ''.join(parts)


API_METHODS = {}
for api in ('PayInternalApi', 'PayBizInternalApi', 'PayOpsInternalApi'):
    p = os.path.join(client_dir, api + '.java')
    if not os.path.exists(p):
        continue
    src = read(p)
    local = constants(p)
    methods = {}
    for verb, expr, name in re.findall(
            r'@(Get|Post|Put|Delete|Patch)Exchange\(([^)]*)\)\s*(?:@\w+(?:\([^)]*\))?\s*)*[\w<>?,.\[\]\s]+?\s(\w+)\(', src):
        methods[name] = verb.upper() + ' ' + resolve(expr, local)
    API_METHODS[api] = methods


def remote_paths(service):
    """standalone 下的实现：Remote<service>.java 存在即远程，返回调到的内部地址；否则 None（进程内）"""
    p = os.path.join(impl_dir, 'Remote' + service + '.java')
    if not os.path.exists(p):
        return None
    src = read(p)
    apis = [a for a in API_METHODS if re.search(r'\b' + a + r'\b', src)]
    called = set(re.findall(r'\.(\w+)\(', src)) | set(re.findall(r'::(\w+)', src))
    out = set()
    for a in apis:
        for m, path in API_METHODS[a].items():
            if m in called:
                out.add(path)
    return out


def group(paths):
    """内部地址去掉路径变量后按前六段归并：/internal/pay/v1/biz/stores/{storeNo}/qr-codes/{qrCode}/disabled → …/stores/qr-codes"""
    seen = {}
    for p in paths:
        _, path = p.split(' ', 1)
        segs = [s for s in path.split('/') if s and not s.startswith('{')]
        key = '/' + '/'.join(segs[:6])
        seen[key] = seen.get(key, 0) + 1
    return ', '.join(f'`{k}`' + (f'×{v}' if v > 1 else '') for k, v in sorted(seen.items()))


# ---------- 阶段 0 结论（人工维护，2026-11-22 核对） ----------
# 结论：迁（near-pay 已有同功能公开接口，迁后由 near-pay 判）/ 等补（near-pay 只有内部接口）/ 改判 C2（standalone 下在 ai-shop 进程里执行）
CONCLUSIONS = {
    # B 端
    'BizPaymentController': ('部分迁', '`/biz/pay/v2`：`collections`（收款）、`qr-codes`（收款码，停用为 POST `/disable`）、`apps` + `stores/{storeNo}/app-grants`（第三方授权，按 appNo 而非 id）',
                             '可迁 7 个：代客收款 1、收款码 3、第三方授权 3（形状不同，b-app 要改调）。等补 16 个：退款 2、对账单 1、收款单 6（列表还带沙箱/正式环境路由 `PaymentReadRouter`）、付款链接 3、收款状态 1、门店付款方式 4（near-pay 没有对应动作）'),
    'BizEntitlementIssueController': ('等补', '无', '权益发放，near-pay 只有内部接口'),
    'BizEntitlementTemplateController': ('等补', '无', '权益模板，near-pay 只有内部接口'),
    'BizStoredValueGovernanceCapabilityController': ('等补', '无', '储值治理，near-pay 只有内部接口；判权按管理范围（规则已在网关库里配）'),
    'BizStoredValueReportController': ('等补', '无', '同上'),
    'BizStoredValueSettlementController': ('等补', '无', '同上'),
    'BizReceivableController': ('改判 C2', '—', 'standalone 下进程内：应收汇总合 ai-shop 的 App 外收款记录'),
    'BizStoreFeatureController': ('改判 C2', '—', '门店功能开关，合 ai-shop 门店与方案数据；支付只读一项收款状态（经同一个支付客户端）；commerce 插件内'),
    # C 端
    'MpBuyerPointsController': ('改判 C2', '—', 'standalone 下进程内：积分在 ai-shop 进程'),
    # 运营
    'OpsPaymentController': ('迁 10 / 等补 11', '`/pay-ops/v1`：`orders`、`refunds`（含复核）、`exceptions`、`notifies`、`qr-codes/{qrCode}/disabled`，同受 `AiShopOpsSessionFilter`，判权用同一组 `Perms.PAY_*`',
                             '可迁 10 个（订单 2、退款 2、异常 3、通知 2、停用收款码 1）；等补 11 个（应用 4、请求日志 2、收款码列表 1、平台密钥 4）。支付中心「退款」页签现在借道 `/ops/payments/refunds`，迁后改走透传'),
    'OpsFeeRuleController': ('等补', '无', '费率规则，near-pay 只有内部接口'),
    'OpsEntitlementController': ('等补', '无', '权益异常，near-pay 只有内部接口'),
    'OpsEntitlementLifecycleController': ('等补', '无', '同上'),
    'OpsEntitlementReconciliationController': ('等补', '无', '同上'),
    'OpsBankFlowController': ('改判 C2', '—', 'standalone 下进程内：银行流水导入，ai-shop 结算表'),
    'OpsOrderPointsIntegrationController': ('改判 C2', '—', 'standalone 下进程内：订单积分，合 ai-shop 售后'),
    'OpsPayChannelController': ('改判 C2', '—', '进程内直连通道主数据（两种形态都装配，见 `OpsPayChannelAppServiceImpl` 注释）'),
    'OpsPayServiceController': ('改判 C2', '—', '进程内：开通申请合 ai-shop 门店快照，经来源关联接口调 near-pay'),
    'OpsPayServiceOnboardingController': ('改判 C2', '—', '进程内：合 ai-shop 商户治理数据'),
    'OpsPayableController': ('改判 C2', '—', 'standalone 下进程内：结算应付'),
    'OpsPointsController': ('改判 C2', '—', 'standalone 下进程内：积分'),
    'OpsPointsInspectionController': ('改判 C2', '—', '同上'),
    'OpsPointsPolicyController': ('改判 C2', '—', '同上'),
    'OpsPointsTreasuryController': ('改判 C2', '—', '同上'),
    'OpsPointsWorkbenchController': ('改判 C2', '—', '同上'),
    'OpsSettleStatsController': ('改判 C2', '—', 'standalone 下进程内：结算统计'),
    'OpsWithdrawController': ('改判 C2', '—', 'standalone 下进程内：提现'),
}

# 一个 controller 里结论不一的，按接口拆数（和须等于接口数，脚本会校验）
MIXED = {
    'BizPaymentController': {'迁': 7, '等补': 16},
    'OpsPaymentController': {'迁': 10, '等补': 11},
}

# ---------- 扫描 controller ----------

rows = []
for dp, _, fs in os.walk(portal):
    for f in fs:
        if not f.endswith('Controller.java'):
            continue
        p = os.path.join(dp, f)
        s = read(p)
        imps = re.findall(r'import ai\.neargo\.shop\.([\w.]+);', s)
        payc = [i for i in imps if i.startswith('payclient') or i.startswith('pay.client')]
        local = [i for i in imps if i.startswith('pay.') and not i.startswith('pay.client')
                 and re.search(r'(Service|Port|Mapper|Repository)$', i.split('.')[-1])]
        dom = [i for i in imps if re.match(r'(merchant|trade|platform|order|spi)\.', i)
               and re.search(r'(Service|Port|Mapper)$', i.split('.')[-1])]
        if not (payc or local or 'Forwarder' in s):
            continue
        cls = re.search(r'@PreAuthorize\((.*)\n(?:@\w+.*\n)*public class', s)
        m = re.search(r'@RequestMapping\("([^"]+)"', s)
        pre = m.group(1) if m else ''
        end = '运营' if '/ops/' in p else 'B端' if '/biz/' in p else 'C端' if '/mp/' in p else '?'
        fwd = 'Forwarder' in s and '/biz/pay/v2' in s
        eps = []
        for mm in re.finditer(r'@(Get|Post|Put|Delete|Patch)Mapping\((?:value\s*=\s*)?"?([^",)]*)"?[^)]*\)\s*(?:\n\s*@\w+(?:\([^)]*\))?)*', s):
            blk = s[mm.start():mm.start() + 600]
            pa = re.search(r'@PreAuthorize\((.*)', blk) or cls
            perm = ''
            if pa:
                line = pa.group(1)
                codes = re.findall(r"'([a-z][\w:.-]+)'", line) + re.findall(r'Perms\.(\w+)', line)
                perm = ', '.join(codes) if codes else re.sub(r'[@"()]', '', line.split(')')[0])[:40]
            eps.append((mm.group(1).upper(), pre + mm.group(2), perm))
        kind = 'B 转发' if fwd else 'C2 初判' if (local or dom) else 'C1 初判'
        services = [i.split('.')[-1] for i in payc if i.startswith('payclient.') and i.split('.')[-1].endswith(('AppService', 'Service'))]
        remote, paths = False, set()
        for svc in services:
            r = remote_paths(svc)
            if r is not None:
                remote = True
                paths |= r
        if fwd:
            mode = '转发 near-pay `/biz/pay/v2`'
        elif remote:
            mode = '远程'
        elif services:
            mode = '进程内'
        else:
            mode = '—'
        rows.append((end, kind, f[:-5], len(eps), eps, payc[:2], local[:2] + dom[:1], mode, paths))
rows.sort(key=lambda r: (r[0], r[1], r[2]))

print('## 1. 机器盘点')
print()
print('「standalone 下」一栏：v2 的形态（`shop.pay.deployment=standalone`）下支付客户端实际走哪条路。'
      '**远程** = 经 near-pay 内部接口（下一栏是这个 controller 所用支付客户端**整个**调到的地址，去掉路径变量后归并，×N 为条数）；**进程内** = 只有同进程实现，业务在 ai-shop 进程里跑。')
print()
print('| 端 | 形态 | Controller | 接口数 | 判权码（ai-shop 现状） | standalone 下 | 所用支付客户端调到的 near-pay 内部地址 | 依赖 |')
print('|---|---|---|---|---|---|---|---|')
tot = {}
for end, kind, c, n, eps, pc, lo, mode, paths in rows:
    perms = sorted({e[2] for e in eps if e[2]}) or ['（无注解）']
    dep = ', '.join(x.split('.')[-1] for x in pc + lo)
    print(f"| {end} | {kind} | `{c}` | {n} | {'; '.join(perms)[:120]} | {mode} | {group(paths) if paths else '—'} | {dep} |")
    tot[(end, kind)] = tot.get((end, kind), 0) + n
print()
print('| 端 | 形态 | 接口数 |')
print('|---|---|---|')
for k, v in sorted(tot.items()):
    print(f'| {k[0]} | {k[1]} | {v} |')

print()
print('## 2. 阶段 0 结论（C1 逐个）')
print()
print('结论：**迁** = near-pay 已有同功能的公开接口，前端改调透传前缀、ai-shop 适配入口下线；'
      '**等补** = near-pay 只有内部接口，维持 ai-shop 判，等 near-pay 补公开接口再排；'
      '**改判 C2** = standalone 下在 ai-shop 进程里执行（或合了 ai-shop 的数据），按原则由 ai-shop 判，原样保留。')
print()
print('| 端 | Controller | 接口数 | 结论 | near-pay 公开对应 | 说明 |')
print('|---|---|---|---|---|---|')
summary = {}
missing = []
for end, kind, c, n, eps, pc, lo, mode, paths in rows:
    if kind != 'C1 初判':
        continue
    if c not in CONCLUSIONS:
        missing.append(c)
        print(f'| {end} | `{c}` | {n} | **未定** | — | 新出现的 C1，要人工确认 |')
        continue
    verdict, public, note = CONCLUSIONS[c]
    print(f'| {end} | `{c}` | {n} | {verdict} | {public} | {note} |')
    split = MIXED.get(c) or {verdict: n}
    assert sum(split.values()) == n, f'{c} 的拆分数与接口数对不上'
    for k, v in split.items():
        summary[k] = summary.get(k, 0) + v
print()
print('| 结论 | 接口数 |')
print('|---|---|')
for k, v in sorted(summary.items()):
    print(f'| {k} | {v} |')
if missing:
    print()
    print('未定：' + '、'.join(f'`{m}`' for m in missing))
