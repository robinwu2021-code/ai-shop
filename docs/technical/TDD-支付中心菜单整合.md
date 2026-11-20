# TDD-支付中心菜单整合

状态：**菜单与点位已在分支 `feat/ops-pay-center-menu` 落地（2026-11-20，用户要求不再等网关；见 §6）**；已确认（菜单目标与映射，用户 2026-11-20 确认；同日两次修订：①页面合并 + 待建隐藏；②按岗位与权限级别重拆，单页不聚合过多功能）；**实施交统一网关执行**，本文不单独落代码与迁移
档位：1 · 依据：用户 2026-11-20 口头需求（下列 AC 已在会话中逐项确认）
前序：[平台运营五目录收敛与验收](TDD-平台运营五目录收敛与验收.md)
执行方：[网关层与统一权限-五层架构对齐](TDD-网关层与统一权限-五层架构对齐.md)（ADR-060 / ADR-061；分支 `feat/gateway-layer-20261118`）
创建：2026-11-20 · 最后更新：2026-11-20

## 一句话

把独立支付（near-pay）运营端的 49 个页面，按 shop 运营端现有五目录归位：「商户管理」改名「支付中心」承载全部支付业务，支付侧的系统配置并进「平台设置」。**菜单按岗位的工作拆分**：一个菜单项是一个岗位的一类工作，页签只用于同一对象、同一岗位、同一权限级别的不同视图；查看、维护、审批分属不同人时拆开，审批统一进「审核待办」。每个岗位只看到自己那一小块（客服 8 项、风控 6 项、财务 15 项），**还没移植的页面不进菜单**。本文只定菜单目标；功能点入库、判权、到 near-pay 的调用由统一网关按它已定稿的方式执行。

## §0 对账一 · 需求 → 设计

需求原话：「统一整理支付的页面到 shop 运营端，先基于 shop 的菜单整合支付的菜单」；「先形成文档，具体的优化方案交给统一网关执行」；「这样支付中心的菜单是否太多？」→「按这个思路更新文档」；「可以适当再拆出一些到菜单，一个界面聚合的功能太多也不要，要充分考虑使用者的角色和权限」。

| AC | 需求（会话已确认） | 落点 |
|---|---|---|
| AC1 | Given shop 主导航五目录，When 登录，Then 「商户管理」改名「支付中心」，承载全部支付业务入口，一级仍为五个 | §2.2；网关运营端菜单（`platformNav` 投影） |
| AC2 | 「订单中心」只留经营订单；支付订单、异常交易移入「支付中心 · 交易」 | §2.2；既有 point_code 不变，只换展示分组 |
| AC3 | 支付渠道移入「支付中心 · 渠道接入」；数据范围、支付配置、支付运维进「平台设置」 | §2.2 |
| AC4 | 菜单按 §2.1 拆分规则组织：单页页签不超过 2 个；查看/维护/审批不同人的、不同岗位的功能不在同一页 | §2.1 规则 + §2.2 映射表 |
| AC5 | 每个菜单项有独立的查看权限码，按岗位授权；任一岗位登录只看到 §2.3 角色矩阵里属于自己的菜单项 | §2.2 权限码列 + §2.3 |
| AC6 | Given 一个页面还没有任何可用页签，When 取菜单，Then 它不出现在菜单里（不灰显）；它的功能点照常在权限库、在角色配置页可见 | §2.5 显示规则 |
| AC7 | near-pay 线上版已上线的 10 页都能从 shop 进入 | §2.4 |
| AC8 | 两本账不混：支付侧资金结算与 shop「经营财务·分账结算」、内嵌 `/pay-ops/*`（商城 pay-domain）互不归并 | 支付中心不收 `/pay-ops/*` 与 `/finance*` |
| AC9 | 不扩权：既有角色只因「移动」看到位置变化；本批只给新增可用项按同码既有持有者继承，建议岗位的默认授权在页面上线时单独评审 | §2.6 |

**孤立项**：无。near-pay 的「角色权限(SY03)」「审计日志(SY09)」不新增入口，并入 shop 同名页（App/运营权限由 ai-shop 决定）。

## §1 现状与影响面

- **主导航是投影**：`ops-web/lib/platform-nav.ts#platformNav()` 输出五目录；`lib/nav.ts#NAV`（13 域）是兼容目录与冻结授权清单。叶子可见性以服务端 `/ops/menu` 下发的 href 为准 —— **菜单项与页签都要有 `sys_function_point` 行**；今天未实现的点会被下发成灰显，本文要求支付中心改为不显示（§2.5）。
- **shop 运营岗位**（`ops-web/lib/auth.ts`）：SUPER_ADMIN 超管、MERCHANT_BD 商家运营、AUDITOR 审核员、CS 客服、FINANCE 财务/结算、RISK 风控、TECH_OPS 技术运维，另有商品、活动、社区、分析等与支付无关的岗位。
- **near-pay 的权限已是细码**：查看 / 维护 / 审批分开（如 `pay:merchant:view|manage|approve`、`pay:approval:view|review`），审批走统一的审核待办（SY01），提交人与审批人不能是同一人。
- **页签可见性已有机制**：页面内页签按 `platformContextTabKeys` / `visibleTabKeys` 过滤，无权或未实现的页签不出现。
- **支付工作区桥**：`lib/auth/ops-bridge.ts#PAYMENT_WORKSPACES`（今天 5 个）+ `components/payment-ops-entry.tsx`，同源跳 `/oms/<path>/`，共享令牌 `neargo-ops-auth`，目的端自行复核会话与数据范围。
- **near-pay 目录**：`near-pay/ops-web/lib/catalog.ts` 49 页；线上版 `LIVE_PAGE_CODES` 10 页（ME01 ME02 ME03 TR01 TR08 SY01 SY04 OP03 OP04 OP09），其余为 Mock 原型。
- **网关已定的相关能力**（未合入 main）：功能点按模块登记与 `sys_function_point.module_code`（F / F2，V681）；第一期数据库方式「库 → 本地缓存 → 逐请求判权」（§2.18）；运营端提前判权（M4）；`MANUAL` 资源行与透传代理（D2，`sys_api_resource`）；支付作为被代理对象（§2.22 `shop.gateway.integrations.pay`）；功能点 → 下游动作映射（AC16/AC19）。
- **明确不受影响**：既有 point_code / href / 授权；`/pay-ops/*` 8 页（留在兼容目录「经营财务·支付运营」）；near-pay 后端与守卫；166 上 nginx `/oms-api/pay-ops/v2` 直连（网关定稿：现在不动）；平台商城、客户管理。

## §2 方案

### 2.1 拆分规则

1. **一个菜单项 = 一个岗位的一类工作**：一个待处理队列，或一个对象的维护。
2. **页签只用于同一对象、同一岗位、同一权限级别的不同视图**（如资金账户 ↔ 该账户的流水），每页最多 2 个页签。
3. **权限级别不同就拆**：查看、维护、审批分属不同的人；审批动作统一进「审核待办」，不放在业务页里当页签或按钮。业务页只能「提交审批」。
4. **岗位不同就拆**：同一流程的前后两段由不同岗位办理时各占一项（如提现申请由财务审批、出款记录由出纳跟踪）。
5. **每个菜单项一个独立的查看码**，不再共用分组主码，授权粒度与菜单粒度一致。
6. **分组按岗位视角排**：同一岗位的工作放在同一或相邻分组；常办的队列放在组内前面，配置类放后面。
7. **默认页签是工作队列**，不是配置。

### 2.2 菜单映射

● 可用（第一步即同源打开 `/oms/` 对应页）；○ 待建（页面移植后可用）。建议岗位是页面上线时默认授权的对象，见 §2.6。查看码中标 ★ 的是 near-pay 已有码；其余为本文提议，**实施时以 near-pay 该页守卫实际使用的码为准**，对不上时替换并在 §6 记偏差。

| 一级 · 分组 | 菜单项 | 页签 | near-pay | 查看码 | 建议岗位 | 状态 |
|---|---|---|---|---|---|---|
| 支付中心 · 审核 | 审核待办 | — | SY01 | pay:approval:view ★ | AUDITOR；持 `pay:approval:review` 的各域主管 | ● |
| 支付中心 · 支付商户 | 商户档案 | — | ME01 | pay:merchant:view ★ | MERCHANT_BD、CS（只读）、AUDITOR（只读） | ● |
| | 支付门店 | — | ME02 | pay:store:view ★ | MERCHANT_BD、CS（只读） | ● |
| | 渠道进件 | — | ME03 | pay:onboarding:view ★ | MERCHANT_BD | ● |
| | 商户变更 | — | ME04 | pay:merchant-change:view | MERCHANT_BD | ○ |
| 支付中心 · 交易 | 支付订单 | 支付订单 ● / 交易流水 ○ | TR01 / TR02 | pay:order:view ★ | CS、FINANCE | ● |
| | 退款订单 | — | TR03 | pay:refund:view | CS、FINANCE | ○ |
| | 异常交易 | — | TR08 | pay:order:view ★（处置另需 `pay:order:compensate` ★） | CS、TECH_OPS | ● |
| 支付中心 · 渠道接入 | 支付渠道 | — | OP03 | pay:channel:view ★ | TECH_OPS | ● |
| | 支付产品 | — | OP04 | pay:product:view ★ | TECH_OPS | ● |
| | 收款接入 | — | OP09 | pay:collection-entry:view ★（2026-11-20 核对 near-pay 守卫） | TECH_OPS | ● |
| | 支付终端 | — | OP08 | pay:terminal:view ★ | TECH_OPS | ○ |
| | 接入应用 | — | SY05 | pay:app:view | TECH_OPS | ○ |
| 支付中心 · 费率 | 渠道成本 | — | OP05 | pay:channel-cost:view | FINANCE | ○ |
| | 商户费率 | — | OP06 | pay:fee-plan:view | FINANCE、MERCHANT_BD（只读） | ○ |
| 支付中心 · 资金结算 | 资金概览 | — | FI01 | pay:fund:view | FINANCE | ○ |
| | 资金账户 | 账户 / 资金流水 | FI02 / FI03 | pay:fund-account:view | FINANCE | ○ |
| | 清分明细 | — | FI04 | pay:clearing:view | FINANCE | ○ |
| | 结算记录 | — | FI06 | pay:settlement:view | FINANCE | ○ |
| | 结算规则 | — | FI05 | pay:settlement-rule:view | FINANCE（变更走审核待办） | ○ |
| | 提现申请 | — | FI07 | pay:withdrawal:view | FINANCE（审批走审核待办） | ○ |
| | 出款记录 | — | FI08 | pay:payout:view | FINANCE | ○ |
| 支付中心 · 对账 | 对账任务 | 对账任务 / 渠道账单 | FI10 / FI09 | pay:recon:view | FINANCE | ○ |
| | 对账差异 | — | FI11 | pay:recon-diff:view | FINANCE | ○ |
| 支付中心 · 风控 | 风险事件 | — | RC03 | pay:risk-event:view | RISK | ○ |
| | 风险限制 | — | RC04 | pay:risk-restriction:view | RISK | ○ |
| | 风控规则 | 规则 / 风控等级 | RC01 / OP07 | pay:risk-rule:view | RISK（变更走审核待办） | ○ |
| | 黑白名单 | — | RC02 | pay:risk-list:view | RISK | ○ |
| 支付中心 · 合规争议 | 争议案件 | — | RC07 | pay:dispute:view | CS、RISK | ○ |
| | 合规审查 | 审查 / 合规事件 | RC05 / RC06 | pay:compliance:view | RISK（另设合规岗时迁给该岗） | ○ |
| 支付中心 · 会员资产 | 积分 | 积分账户 / 积分流水 | TR04 / TR05 | 沿用 shop 积分页的码（`finance:settle:read`） | CS、FINANCE | ● 链到 shop `/points/accounts`、`/points/transactions`（2026-11-20 用户定；near-pay S4 新模型出来再议） |
| | 储值 | 储值账户 / 储值交易 | TR06 / TR07 | pay:stored-value:view | CS、FINANCE | ○ |
| 平台设置 · 权限管理 | 数据范围 | — | SY04 | pay:data-scope:view ★ | SUPER_ADMIN | ● |
| 平台设置 · 支付配置 | 经营设置 | — | OP01 | pay:region:view | SUPER_ADMIN | ○ |
| | 审批流程 | — | SY02 | pay:approval-flow:view ★ | SUPER_ADMIN | ○ |
| 平台设置 · 支付运维 | 监控告警 | — | SY10 | pay:alert:view | TECH_OPS | ○ |
| | 渠道调用 | — | SY07 | pay:trace:view ★ | TECH_OPS | ○ |
| | 异步任务 | — | SY08 | pay:job:view | TECH_OPS | ○ |
| | 请求日志 | — | SY06 | pay:log:view（shop 已有码） | TECH_OPS | ○ |
| | 测试渠道 | — | MP01 | pay:mockpay:view | TECH_OPS（仅测试实例开放） | ● 打开支付端工作区 `/oms/ops/mockpay`，保留 mockpay 自己的登录（2026-11-20 用户定） |
| — 不新增 | — | 角色权限 / 审计日志 | SY03 / SY09 | — | — | 并入 shop 同名页 |

**规模**

| | 满配 | 上线初期 |
|---|---|---|
| 支付中心菜单项 | 32（10 组） | 9（审核待办、商户档案、支付门店、渠道进件、支付订单、异常交易、支付渠道、支付产品、收款接入） |
| 平台设置新增菜单项 | 8（3 组） | 1（数据范围） |
| 带页签的菜单项 | 7 项，每项 2 个页签 | 0（支付订单的「交易流水」页签待建） |

一级顺序：平台设置 / 平台商城 / 客户管理 / 订单中心 / 支付中心。分组顺序即表中顺序。三语一级名：支付中心 / 支付中心 / Payments。分组没有可显示的菜单项时，分组标题一并不显示。

**既有点位的处理**：商户档案 `/merchants?tab=payment`、渠道进件 `/merchants?tab=payment-onboarding`、支付渠道 `/merchants?tab=payment-channels`、支付订单 `/orders?tab=payments`、异常交易 `/orders?tab=payment-exceptions` 五个 point_code、href、授权都不动，直接作为对应菜单项。新菜单项的路径由网关 G4 定；如统一换成支付中心自己的路径，旧 href 登记为 `legacyHrefs` 保持深链可达。

### 2.3 角色矩阵（满配时各岗位在支付中心 + 平台设置看到的菜单项）

| 岗位 | 菜单项数 | 涉及分组 |
|---|---|---|
| CS 客服 | 8 | 支付商户（只读 2）、交易 3、合规争议（争议案件）、会员资产 2 |
| MERCHANT_BD 商家运营 | 5 | 支付商户 4、费率（商户费率只读） |
| AUDITOR 审核员 | 2 | 审核待办、商户档案（只读） |
| FINANCE 财务/结算 | 15 | 交易（支付订单、退款订单）、费率 2、资金结算 7、对账 2、会员资产 2 |
| RISK 风控 | 6 | 风控 4、合规争议 2 |
| TECH_OPS 技术运维 | 11 | 交易（异常交易）、渠道接入 5、支付运维 5 |
| SUPER_ADMIN 超管 | 40 | 全部 |

财务的 15 项分在 5 个组里，单组最多 7 项。若财务内部再分「资金结算」与「对账」两个岗，按分组拆授权即可，菜单不用改。超管看到全部 40 项，靠分组折叠来控制长度，不为超管单独做菜单。

### 2.4 可用页面的两步

| 步 | 形态 | 说明 |
|---|---|---|
| 第一步（菜单落地即可用） | 同源跳 `/oms/` 工作区 | `PAYMENT_WORKSPACES` 由 5 个扩到 10 个：新增 `/merchants/stores`、`/ops/products`、`/ops/access-configs`、`/system/approvals`、`/system/data-scope`。不改 near-pay，不碰 nginx 直连 |
| 目标形态（网关执行） | 页面移植进 shop ops-web（页面规范与分期见 [TDD-支付中心运营页面](TDD-支付中心运营页面.md)），经网关代理调 near-pay | 走 §2.22 `integrations.pay`（standalone）；每个页面的接口在 `sys_api_resource` 登记为 `MANUAL` 行，功能点映射到 near-pay 下游动作；网关先判权（无权 403，支付收不到请求）。逐个移植，○ 变 ● 后自动进菜单 |

### 2.5 显示规则（AC6）

- **页签**：未实现或无权 → 不显示（沿用现有页签过滤）；只剩一个页签时不显示页签栏。
- **菜单项**：至少有一个「已实现且有权」的页签才显示；否则不显示，**不灰显**。只对 `module_code='pay'` 的点适用，shop 其他目录保持现有「待建灰显」做法不变。
- **分组标题**：组内没有可显示的菜单项则不显示。
- **页内操作**：维护、处置类按钮按各自的维护码（`*:manage`、`pay:order:compensate` 等）单独判权，无权则不显示；审批类动作不在业务页出现。
- **角色配置页**：所有功能点（含待建）照常列出、可勾选，供提前授权。
- 由网关运营端菜单出口实现（G4），前端投影只做展示，不另判状态。

### 2.6 授权规则（AC9）

- SUPER_ADMIN：所有新增功能点全部授予。
- 本批新增的 ● 项按「同码既有点位的持有者」继承：支付门店 ← `OPS_MERCHANT__TAB_PAYMENT`；支付产品、收款接入 ← `OPS_IAM__TAB_PAYMENT_CHANNELS`；审核待办 ← `OPS_MERCHANT__TAB_PAYMENT_ONBOARDING`；数据范围不继承，只给超管。
- ○ 项本批只授 SUPER_ADMIN。**每个页面移植上线时**，按 §2.2「建议岗位」列做一次授权评审，再随该页的种子迁移授予；不在菜单落地时预授，避免页面上线即自动扩权。
- 既有 5 个点位的授权不动。

### 2.7 交统一网关执行的事项

按网关定稿的方式落地，**不在 main 上另起 ai-shop 自己的菜单迁移**，避免与网关的 V679–V681 及其后续迁移撞号或重复建模。

| # | 事项 | 网关里的落点 | 依赖期次 |
|---|---|---|---|
| G1 | 40 个菜单项 + 7 个附加页签共 47 个功能点入运营端权限库（含既有 5 个），`module_code='pay'`，○ 为 `NOT_IMPLEMENTED` | 第一期数据库方式（§2.18）的种子；F2 的模块归属 | F2 |
| G2 | 登记 §2.2 查看码（★ 为 near-pay 已有，其余对齐 near-pay 守卫后定稿），归属 `pay` 命名空间；页内维护码随页面移植登记。**P1 已在 shop `Perms.java` / `perm-map.ts` 登记 21 个码**（运营页面 TDD §6 偏差 10），网关只需落功能点与授权 | 功能点登记表（F）；`point-namespaces: [pay:]` | F |
| G3 | §2.6 的授权种子；后续每页上线时的岗位授权随该页种子 | 同 G1 的种子迁移，号由网关分支统一取 | F2 之后 |
| G4 | 运营端菜单出口按 §2.2 / §2.5 输出：待建菜单项不下发、旧 href 收编为 legacyHrefs；前端投影、`PAYMENT_WORKSPACES`、三语文案随之调整 | 网关运营端出口（`shop-gateway` ops 包） | 可与 G1 同批 |
| G5 | 支付中心入口受运营端提前判权覆盖 | M4 提前判权，开关先 observe 再 enforce | M4 |
| G6 | 页面移植时，接口登记为 `MANUAL` 资源并映射下游动作，经代理调 near-pay；审批类动作只映射给审核待办 | D2 代理 + §2.22 `integrations.pay` + AC16/AC19 | D2、S |
| G7 | 运营端权限库独立后，以上数据随库迁移 | I1 | I1 |

需另行请用户确认、本文不预设的：near-pay 直连改经网关；支付内部入口打开验签拒绝档；是否新设合规岗。

## §5 对账三 · 实现 → 需求（测试）

由网关实施时补全「跑过 / 消融」两列。

| AC | 测试 | 跑过 | 消融 |
|---|---|---|---|
| AC1 | `platform-nav.test.ts`：五目录顺序；支付中心分组顺序 | | 恢复「商户管理」→ 红 |
| AC2 | 同上：订单中心不含 `/orders?tab=payments*`；支付中心含且 point_code 不变 | | |
| AC3 | 同上：平台设置不含支付渠道、含数据范围 | | |
| AC4 | 每个菜单项页签 ≤ 2；同一菜单项内各页签的查看码相同；业务页不出现 `*:approve` / `*:review` 码 | | 把风控规则与风险事件合成一页 → 红 |
| AC5 | 按 §2.3 角色矩阵逐岗位登录（mock 授权满配）：可见菜单项集合与矩阵一致 | | 两项共用一个查看码 → 红 |
| AC6 | 超管登录（当前实现状态）：支付中心恰好 9 项；无 `soon` 灰项；角色配置页能勾到全部待建项 | | 恢复灰显 → 红 |
| AC7 | `payment-workspace.test.ts`：10 个工作区与 near-pay `LIVE_PAGE_CODES` 一一对应 | | 删一个工作区 → 红 |
| AC8 | 支付中心不含 `/pay-ops/*`、`/finance*` | | |
| AC9 | 可见性基线：非超管岗位的可用菜单项 ⊆ 旧集合 ∪ §2.6 继承项 | | |
| 实机 | 超管、客服、财务各登一次：菜单截图与 §2.3 对得上；点进各项打开对应支付页 | | |

## §6 对账二 · 设计 → 实现（实现完再填）

## §6 对账二 · 设计 → 实现

### 菜单与点位落地（2026-11-20，用户要求在本分支改，不再等网关的 G1/G4）

| 动作 | 路径 | 说明 |
|---|---|---|
| 修改 | `ops-web/lib/platform-nav.ts` | 一级顺序改为 平台设置 / 平台商城 / 客户管理 / 订单中心 / 支付中心；「商户管理」改名「支付中心」（key 沿用 `paymentMerchant`，保住按 key 存的菜单顺序与个人偏好），分组 审批 / 支付商户 / 交易 / 渠道接入；订单中心只留经营订单；平台设置移走支付渠道、加数据范围（权限管理）与 支付配置、支付运维 两组 |
| 修改 | `ops-web/lib/platform-menu-aliases.json` | +16 个别名（`/payment-center?tab=<页签>`，function `OPS_FINANCE`，point_code `OPS_FINANCE__PAYCENTER_<页签>`） |
| 新增 | `backend/shop-app/src/main/resources/db/migration/V684__ops_payment_center_menu.sql` | 16 个 MENU 功能点 + 授权：SUPER_ADMIN 全部；按运营页面 TDD §2.6 / 同码继承（支付门店、商户变更 ← `OPS_MERCHANT__TAB_PAYMENT`；审核待办 ← `OPS_MERCHANT__TAB_PAYMENT_ONBOARDING`；支付产品、收款接入、经营设置 ← `OPS_IAM__TAB_PAYMENT_CHANNELS`；退款订单 ← `OPS_FINANCE__PAY_REFUNDS`）；其余只给超管。只新增，不改已有行 |
| 修改 | `backend/shop-app/src/test/resources/schema-test.sql` | `gen-test-schema.py` 重生成，只多了 V684 的 39 行 |
| 修改 | `ops-web/lib/i18n/nav-labels.ts`、`nav-zh-TW.ts` | 新菜单名与分组名的英文 / 繁体 |
| 修改 | `ops-web/lib/pay-ops/center-tabs.ts` | `PENDING_MENU` 清空；`LEGACY_MENU_HREF` 记下 5 个既有入口的原 href |
| 修改 | `ops-web/lib/{platform-nav,nav,nav-platform-settings,use-page-tab}.test.ts` | 期望改成新结构：根顺序、支付中心叶子与分组、平台设置、各岗位可见根（财务多出支付中心：支付订单、支付异常、退款订单）、页签按根分区；种子授权测试同时读 V671 与 V684，并收紧「授给谁」的匹配 |

| 修改 | `backend/shop-app/src/test/java/ai/neargo/shop/arch/FunctionPointPermAlignmentTest.java` | 「登记的功能点须有本地端点或已接线的跨系统工作区」加一类认定：支付中心原生页（端点在 near-pay）。三条同时成立才算：点位 href = `/payment-center?tab=<key>`、`center-tabs.ts` 登记该页签且权限码一致、`page.tsx` 的 `TAB_COMPONENT` 真的渲染它 |

**验证**：ops-web `tsc` 无输出、`vitest` 146 个文件 1346 条全过；后端 `FunctionPointPermAlignmentTest` 7/7、`SchemaDriftTest` 6/6；共享包 `nav-function-point.test.ts` 2/2；`check-ops-orphan` 退出 0。
消融：从 `page.tsx` 去掉终端页签 → 对齐测试报出 `pay:terminal:view`。
实机（本机 mock，超管）：一级目录依次为 平台设置 / 平台商城 / 客户管理 / 订单中心 / 支付中心；支付中心面板 4 组 13 项、当前页高亮正确；平台设置无支付渠道、权限管理下有数据范围、末尾有支付配置与支付运维两组；订单中心只剩经营订单 3 项。

**偏差说明**
1. **点位落在 shop 主迁移目录（V684），不经网关的种子**：网关分支尚未开始 G1/G4。迁移号 V679–V683 已被其它未合并分支占用，**合并前须再核迁移号与顺序**；网关接手后以这批点位为基础，不重复建。
2. **渠道调用 / 回调、告警事件 / 规则在菜单上各是两项**：§2.2 设计为「一个菜单项两个页签」，现阶段每个页签就是一个菜单叶子。
3. **积分账户 / 流水、测试渠道还没进菜单**：已定链到 shop 积分页与 `/oms/ops/mockpay`，但跨根链接（积分页属另一业务域、测试渠道是外部工作区）的高亮与返回需单独处理，下一步做。
4. **界面清单（`ui-catalog.json`）看不到支付中心**：`gen-ui-catalog.py` 只读 `nav.ts`，不读菜单投影与别名表；要让清单收录支付中心需改生成器。

