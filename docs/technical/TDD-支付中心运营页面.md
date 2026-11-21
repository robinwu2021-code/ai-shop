# TDD-支付中心运营页面

状态：P0–P3a 已提交并已上线 v2（39bea6a65，near-pay P3a 已上线）；P4（商户与进件原生化、支付中心独立成模块）进行中（§2.9–§2.10）；P3b / P3c 待 near-pay 补接口
档位：1 · 依据：[TDD-支付中心菜单整合](TDD-支付中心菜单整合.md)（菜单、权限码、岗位）+ 用户 2026-11-20 页面要求
创建：2026-11-20 · 最后更新：2026-11-20

## 一句话

把支付中心与平台设置里的 40 个支付菜单项，逐个做成 shop 运营端（ops-web）的原生页面，替代今天跳 `/oms/` 的过渡入口。所有页面一个骨架：**表格 + 行操作**；操作只走**抽屉**（单层、单一任务）或**弹框**（确认、短输入）；抽屉里的编辑**在前端存草稿**，离开页面不丢；共用部分先抽成组件，再按「已上线 → 后端已有 → 后端待补」三批移植。

## §0 对账一 · 需求 → 设计

需求原话：「按照文档，制定开发计划，完善运营端的页面，所有的页面都应该是基于 table+action，操作通过抽屉+弹框，抽屉不要有多层，抽屉中的内容尽量单一，如果复杂可以拆多个操作，抽屉中的编辑要在前端缓存，防止离开页面丢失，同时要抽取组件」。

| AC | 需求 | 落点 |
|---|---|---|
| AC1 | 每个页面 = 筛选 + 表格 + 行操作 + 页面级操作（新建、导出），不另做卡片墙或自由布局 | `ListPage` 组合件（§2.3） |
| AC2 | 操作只有两种载体：抽屉（查看、编辑、多字段提交）与弹框（确认、填理由、短输入） | `ActionSpec.kind` 只允许 `drawer` / `dialog` / `confirm`（§2.2） |
| AC3 | 同一时刻最多一个抽屉；抽屉里不再打开抽屉。抽屉上可以叠一个弹框 | `ActionHost` 统一持有打开状态；`Drawer` 嵌套在开发态报错（§2.3） |
| AC4 | 一个抽屉只做一件事；复杂编辑拆成多个独立操作，各自保存 | §2.2 规则 3、4；§2.6 各页操作拆分 |
| AC5 | 抽屉里未提交的编辑存在浏览器本地；关抽屉、切页面、刷新、关标签后回来可恢复；提交成功或主动放弃后清掉 | `useDraft` + `DraftNotice`（离开时立即落盘，见 §6 偏差 2）（§2.4） |
| AC6 | 敏感字段（银行账号、密钥、证件号、凭据）不进草稿 | `FieldDef.sensitive`；`useDraft` 落盘前剔除（§2.4） |
| AC7 | 共用部分抽成组件，页面只写配置与业务映射 | §2.3 组件清单；单页代码量门槛（§5） |
| AC8 | 页面、页签、行操作按菜单 TDD §2.2 的查看码与各自维护码判权；审批动作只在「审核待办」 | `ActionSpec.perm`；§2.2 规则 5 |
| AC9 | 页面移植完成后，对应菜单项由「待建隐藏」变为可见，替代 `/oms/` 跳转 | 每页完成定义（§2.7） |

**孤立项**：无。

## §1 现状与影响面

- **ops-web 已有、直接复用**（`components/ui/`，说明见 `components/README.md`）：`DataTable` / `PagedTable`（分页表格）、`Toolbar` + `FilterSelect` / `TextFilter` / `FilterChip`（筛选与回显）、`RowActions`（行操作「更多」）、`Drawer` / `DrawerSection` / `FieldGrid` / `Field` / `DrawerFooter`（抽屉与详情行，`DrawerFooter` 已支持子块占用外层底栏）、`FormDrawer`（配置化表单，`inDrawer` 已支持在现有抽屉内原地展开不叠层）、`ConfirmDialog` / `useConfirm`（含 `requireText` 强确认）、`StatusBadge`、`Timeline`、`EmptyState` / `ErrorState`。
- **ops-web 缺的**：草稿缓存（全站没有）、离开页面拦截、统一的「打开哪个抽屉 / 弹框」状态管理、列表页骨架（各页手拼 Toolbar + PagedTable + RowActions + 抽屉状态，115 处 `Drawer` 写法不一）、填理由的弹框、敏感字段掩码显示、支付端接口客户端。
- **near-pay 后端接口**（`pay-portal/.../api/ops/v2`，前缀 `/pay-ops/v2`）已覆盖：商户、门店、进件（含服务端草稿与文件）、支付订单与异常、渠道、产品、终端、收款接入、接入应用与凭据、审批与审批流程、数据范围与权限映射、请求日志与渠道调用、告警、经营设置、测试商户。**没有**：资金、清分结算、提现出款、对账、渠道成本、商户费率、风控、合规、争议、积分与储值资产、异步任务。退款列表在 v1（`/pay-ops/v1/refunds`）。
- **near-pay 前端**（`near-pay/ops-web`）：`lib/client/resources.ts` 已有上述接口的类型与调用，可作为移植时的接口参考；其页面多为 Mock 原型，**不照搬布局**，按本文骨架重做。
- **会被改到**：`ops-web/components/ui/`（新组合件、`Drawer` 加嵌套检测、`FieldDef` 加 `sensitive`）、`ops-web/components/`（支付业务件）、`ops-web/lib/api/`（支付客户端与 mock）、`ops-web/app/payment-center/**`（新页面）、`ops-web/lib/i18n`（三语词条）、`ui-catalog.json`。
- **明确不受影响**：现有 shop 页面（新组件不强制回迁，见 §2.8）；near-pay 后端；网关的菜单与权限落地（由菜单 TDD §2.7 交网关执行）。

## §2 方案

### 2.1 契约变更

- **端点**：ops-web 新增对 near-pay `/pay-ops/v2/*` 的调用。**第一步**沿用今天 near-pay 运营端已在用的同源路径与共享会话（不新增对外暴露）；**网关代理（菜单 TDD G6）就绪后**只改客户端的基础路径配置切过去，页面不动。
- **库表**：无新表。菜单点位：V684/V685 新增。V671 的 5 个既有支付入口改址的 SQL（原 V686）**暂不执行**，放在 `docs/technical/pending-sql/ops_payment_center_own_module.sql`：v1 与 v2 共用正式库菜单表，v1 前端没有支付中心页；先由 v2 前端把旧地址映射到支付中心（§2.9），v1 下线后再按空闲迁移号执行。
- **权限码**：用菜单 TDD §2.2 的查看码；各页维护码随页面移植登记（经网关 G2）。
- **i18n**：每页的标题、列名、操作名、状态名、提示三语（简 / 繁 / 英）。
- **配置项**：`NEXT_PUBLIC_PAY_OPS_API_BASE`（默认同源 `/oms-api/pay-ops/v2`，网关就绪后改为网关代理前缀）。
  ~~`NEXT_PUBLIC_PAY_NATIVE_PAGES`~~：P1 加的过渡开关，2026-11-20 随 §2.9 删除（支付入口只有原生页一条路）。
- **退款**走 shop 后端已有的 `/ops/payments/refunds`（near-pay 退款只有 v1，生产 nginx 对公网只转 `/oms-api/pay-ops/v2/`）。
- **浏览器存储**：新增草稿键空间 `neargo-ops-draft:v1:*`（§2.4）。

### 2.2 页面与操作规则

1. **一页一张主表**。页签（最多 2 个，见菜单 TDD）各是一张表，不在同一页签里放两张表。
2. **行操作 ≤ 3 个直接按钮，其余进「更多」**。第一个固定是「查看」。
3. **抽屉 = 一件事**：查看详情、或一组在业务上一次提交的字段。字段超过约 12 个、或分属不同审批 / 不同岗位时，**拆成多个操作**（如「编辑主体信息」「编辑结算账户」「上传资质文件」），各自保存。
4. **详情抽屉只读**；里面的操作按钮进底栏，点了以后**在同一个抽屉里替换内容**（带「返回详情」），或弹出弹框；绝不再开第二个抽屉。
5. **审批不在业务页**：需要审批的修改，业务页只「提交审批」；同意 / 驳回只在「审核待办」。
6. **弹框只放短内容**：确认、填理由（驳回、停用、忽略）、单个字段（指派给谁）。超过 3 个输入就改用抽屉。
7. **危险操作**（关闭商户、撤销密钥、人工结算）用强确认：弹框里输入对象编号才可提交。
8. **列表状态进 URL**：筛选、分页、页签进查询串；打开的抽屉也进查询串（`?action=<key>&id=<no>`），刷新后还原、链接可分享。
9. **空态、出错、无权三种分开**：沿用 `EmptyState` / `ErrorState` / `ReadOnlyNotice`。

### 2.3 组件清单

**新增组合件**（`components/ui/`，无业务语义）

| 组件 | 职责 | 关键约定 |
|---|---|---|
| `ListPage` | 页面骨架：标题 + 说明 + `Toolbar`（筛选、页面级操作）+ `PagedTable` + 行操作列 + `ActionHost` | 入参是配置：`columns`、`filters`、`query`、`rowActions`、`pageActions`；页面文件只写配置与映射 |
| `ActionSpec` / `ActionHost` | 操作的声明与承载：`{ key, label, kind: 'drawer' \| 'dialog' \| 'confirm', perm?, visible?(row), danger?, render }`；`ActionHost` 持有「当前抽屉 + 当前弹框」两个槽位，与 URL `?action=&id=` 同步 | 抽屉槽位只有一个：打开新抽屉 = 替换；弹框可叠在抽屉上 |
| `DetailDrawer` | 只读详情：`DrawerSection` + `FieldGrid` + 底栏操作；底栏操作可「原地替换」为一个编辑面板，带返回 | 基于现有 `Drawer` / `DrawerFooter` / `FormDrawer inDrawer` |
| `ActionDrawer` | 单一任务的编辑抽屉：`FormDrawer` + 草稿（`useDraft`）+ 关闭拦截 + 提交态 | 关闭时若有未提交改动：弹框「保留草稿 / 放弃 / 继续编辑」 |
| `ReasonDialog` | 填理由的弹框（必填、字数上限、可选常用理由） | 驳回、停用、忽略、撤回共用 |
| `MaskedValue` | 敏感值掩码显示，按权限可「查看完整」并记审计 | 掩码规则：保留后 4 位 |
| `useDraft` / `DraftNotice` / `useLeaveGuard` | 草稿与离开拦截，见 §2.4 | |

**改造现有组件**

| 组件 | 改动 |
|---|---|
| `Drawer` | 加嵌套检测：在已打开的抽屉内再渲染 `Drawer` 时，开发态 `console.error` 并在测试里断言为错；生产不拦 |
| `FieldDef`（`lib/form-validate`） | 加 `sensitive?: boolean`，草稿与日志都不收这类字段 |
| `RowActions` | 接收 `ActionSpec[]`，按 `perm` / `visible` 过滤，超过 3 个自动收进「更多」 |

**新增支付业务件**（`components/payment/`）

| 组件 | 职责 |
|---|---|
| `payOps` 客户端（`lib/api/pay-ops/`） | `/pay-ops/v2` 的类型化调用、分页约定、错误码到提示的映射；基础路径读配置；同时提供 mock 实现（开发与测试） |
| `PayStatusBadge` 系列 | 商户、门店、进件、订单、异常、渠道、产品、审批等状态的映射表（喂 `StatusBadge` 与 `FilterSelect`） |
| `PayRefCell` | 编号单元格：复制、跳到对应页并打开详情（如订单 → 商户档案） |
| `ApprovalSubmitNotice` | 「此修改需审批」提示与提交后跳审核待办的链接 |
| `PayAuditTimeline` | 对象的操作与审批记录（基于 `Timeline`） |

### 2.4 草稿缓存

| 项 | 规定 |
|---|---|
| 存哪 | `localStorage`，所有读写包 try/catch；存储不可用时退化为仅内存，并在抽屉顶部提示「草稿无法本地保存」 |
| 键 | `neargo-ops-draft:v1:<操作人编号>:<页面>:<操作>:<对象编号或 new>`；带操作人，换号登录看不到别人的草稿 |
| 值 | `{ schemaVersion, savedAt, baseVersion, values }`；`values` 剔除 `sensitive` 字段 |
| 何时写 | 字段变更后 500ms 防抖写入；关抽屉、`beforeunload`、路由离开前再写一次 |
| 何时恢复 | 打开同一操作时若有草稿，表单用草稿填充，顶部 `DraftNotice`：「已恢复 11-20 14:32 的草稿 · 放弃草稿」 |
| 冲突 | `baseVersion` 与服务端当前版本不一致时，`DraftNotice` 改为警告：「记录已被他人修改，草稿基于旧版本」，提供「查看差异 / 放弃草稿」，不自动覆盖 |
| 何时清 | 提交成功、点「放弃草稿」、超过 7 天、退出登录（清本人全部草稿） |
| 离开拦截 | 有未保存改动时：关抽屉弹框询问；切菜单 / 浏览器后退 / 关标签走 `useLeaveGuard`（路由拦截 + `beforeunload`）。因为草稿已落盘，拦截文案是「草稿已保存，可稍后继续」，只提醒不阻断 |
| 服务端草稿 | 进件等已有服务端草稿接口的（`/onboardings/{no}/draft`），以服务端为准：本地草稿只缓存「尚未保存到服务端」的那部分，保存成功即清本地 |
| 页面级入口 | `ListPage` 工具栏显示「我的草稿 (n)」，点开是弹框列表，选一条直接打开对应抽屉 |

### 2.5 分期

| 期 | 内容 | 菜单项 | 依赖 |
|---|---|---|---|
| **P0 组件与基座** | §2.3 全部新组件与改造；`payOps` 客户端与 mock；`/dev/ui` 示例页；`/payment-center` 路由壳 | — | 无 |
| **P1 已上线 10 项原生化** | 审核待办、商户档案、支付门店、渠道进件、支付订单、异常交易、支付渠道、支付产品、收款接入、数据范围；做完一项就把菜单从 `/oms/` 跳转切到原生页 | 10 | P0 |
| **P2 后端已有、未上线 9 项** | 商户变更、退款订单、支付终端、接入应用、请求日志、渠道调用（调用 / 回调两页签）、监控告警（事件 / 规则两页签）、审批流程、经营设置 | 9（11 个页签） | P0；菜单点位改已实现（网关 G1） |
| **P3 后端待补 22 项**（分批见 §2.6「P3 盘点与分批」） | 资金结算 7、对账 2、费率 2、风控 4、合规争议 2、会员资产 2、异步任务、交易流水（订单页签）、测试渠道 | 22 | near-pay 先补接口（另立 near-pay 任务，逐域出接口契约）；测试渠道另需 mockpay 与 shop 统一登录；P0 |

> 2026-11-20 P2 开工前核对 near-pay 源码后调整：交易流水没有跨订单列表接口（只在订单详情里），测试渠道是独立部署、登录也独立的 mockpay 应用，两项改入 P3。

P1 内顺序按使用频率：支付订单 → 异常交易 → 商户档案 → 支付门店 → 渠道进件 → 审核待办 → 支付渠道 → 支付产品 → 收款接入 → 数据范围。P2、P3 每一域可独立并行，互不依赖。

### 2.6 P1 各页的表与操作

列与接口在移植时以 near-pay 现有接口为准逐项核对；下表定的是**操作怎么拆、走什么载体**。

| 页面 | 主表 | 行操作（载体） | 页面级操作 |
|---|---|---|---|
| 支付订单 | 订单号、商户 / 门店、金额、方式、渠道、状态、时间 | 查看（详情抽屉，含渠道日志段）· 补查（确认）· 人工结算（强确认 + 理由）· 关闭（理由弹框） | 导出 |
| 异常交易 | 异常号、关联订单、类型、金额、状态、处理人 | 查看（详情抽屉）· 退款处理（抽屉：金额、原因）· 忽略（理由弹框） | — |
| 商户档案 | 商户号、名称、主体、状态、来源、开通渠道数 | 查看（详情抽屉，含资料版本与来源事件段）· 校验来源关联（确认）· 暂停 / 启用（理由弹框）· 关闭（强确认）· 资料修订记录（详情抽屉内替换为列表） | 新建商户（抽屉，草稿） |
| 支付门店 | 门店号、名称、所属商户、收款状态、绑定渠道 | 查看（详情抽屉，含同步事件段）· 编辑收款策略（抽屉，草稿）· 编辑支付方式偏好（抽屉，草稿）· 变更归属（抽屉，提交审批）· 取消 / 执行变更（确认） | — |
| 渠道进件 | 申请号、商户、渠道、阶段、状态、更新时间 | 查看（详情抽屉，含送件进度）· 编辑主体资料 / 编辑结算账户 / 上传资质文件（三个独立抽屉，服务端草稿 + 本地缓存）· 提交审核（确认） | 新建申请（抽屉：选商户与渠道） |
| 审核待办 | 审批号、类型、对象、提交人、当前节点、处理人、时间 | 查看并审批（详情抽屉：变更内容对比；底栏「同意」确认、「驳回」理由弹框）· 认领 / 释放（确认）· 指派（弹框选人）· 撤回（提交人可见，理由弹框） | 页签式筛选：待我处理 / 我提交的 / 全部 |
| 支付渠道 | 渠道编码、名称、状态、可用产品数、最近诊断 | 查看 · 编辑（抽屉，草稿）· 启用 / 停用（理由弹框）· 诊断（确认，结果在弹框展示） | — |
| 支付产品 | 产品编码、名称、渠道、状态、版本 | 查看 · 编辑（抽屉，草稿）· 发布（确认）· 停用（理由弹框） | 新建（抽屉，草稿） |
| 收款接入 | 接入编号、商户 / 门店、接入方式、状态 | 查看 · 编辑（抽屉，草稿）· 启用 / 停用（理由弹框） | 新建（抽屉，草稿） |
| 数据范围 | 委托编号、被授权人、范围、状态、生效期 | 查看 · 编辑（抽屉，提交审批）· 启用（确认）· 撤销（强确认 + 理由） | 新建委托（抽屉，提交审批） |

P3 各页的同类表在该期开工前补进本节，经确认后实施。

#### P3 盘点与分批（2026-11-20，读 near-pay 源码与设计文档）

P3 的 22 项在 near-pay 都**没有运营接口**，但缺口差别很大。near-pay 的 `docs/api/handbook/page-api-design.json`（DESIGN_ONLY）已为除测试渠道外的每一页设计了 `/pay-ops/v2` 路径与模型，
其通用客户端 `lib/client/resources.ts` 也已按设计路径调用 —— **接口契约照这份设计落，不另起**；但设计里的权限码是 `ops:<页面>:<动作>` 占位，落地时统一换成 `pay:*`（App/运营权限由 ai-shop 决定）。

| 批 | near-pay 现状 | 页面 | near-pay 要做的 | shop 侧 |
|---|---|---|---|---|
| **P3a 只缺接口** | 表与服务都在 | 交易流水（TR02）、对账差异（FI11：列表 / 详情 / 裁决）、资金流水（FI03）、结算记录（FI06）、提现申请（FI07）、出款记录（FI08）、储值账户 / 储值交易（TR06 / TR07）、渠道成本（OP05）、佣金规则（OP06 的佣金部分） | 在现有服务上加 `/pay-ops/v2` 控制器与查询方法（台账加查询、提现 / 出款状态迁移改成 v2 命令、储值加运营全量查询）；守卫码 `pay:*` | 按 P1 骨架做页面，与 near-pay 同批联调 |
| **P3b 表在、服务要补** | 统计与结算服务可复用 | 资金概览（FI01）、清分明细（FI04） | 汇总与清分查询；FI04 依赖 DEC20 拆分（`stl_bill` 归 ai-shop，经 `/internal/pay-source/v2` 读） | 同上 |
| **P3c 从零** | 只有设计，没有表 | 资金账户（FI02）、结算规则（FI05）、渠道账单（FI09）、对账任务（FI10）、商户费率方案（OP06）、风控 5 页（OP07、RC01–RC04）、合规争议 3 页（RC05–RC07） | 建表（design-v2 文档 05–11）+ 服务 + 接口；风控有单独 TDD（near-pay `TDD-风控管理与分析方案.md`） | 接口出来一域做一域 |
| **另议** | 归属或登录未定 | 积分账户 / 流水（TR04 / TR05）、异步任务（SY08）、测试渠道（MP01） | 积分：台账在 ai-shop 库，near-pay 设计要在 S4 换成平台出资的新模型（DEC17）；异步任务：没有统一任务表，可先做现有各队列的只读聚合；测试渠道：后端已在（mockpay 自有 `/pay-ops/v1/mockpay/*`），缺的是与 shop 统一登录或经网关代理 | 定了再排 |

**执行方式**：near-pay 的接口在 near-pay 仓库实现（各批单独会话、单独分支与 PR，契约以上述设计文档为准）；每批接口合入后，shop 侧在本工作树按 P1/P2 的骨架补页面并登记页签与权限码。
建议顺序：P3a → P3b → P3c（资金 → 对账 → 费率 → 风控 → 合规），「另议」三项先定方案。

**已定（用户 2026-11-20）**：
1. **near-pay 接口在 near-pay 仓库单独会话实现**，契约照 `page-api-design.json`，权限码用菜单 TDD §2.2 的 `pay:*`；shop 页面在本工作树随各域接口落地补。
2. **测试渠道保留 mockpay 自己的登录**：不做原生页，菜单项直接打开支付端工作区的测试渠道控制台（`/oms/ops/mockpay`）。
3. **积分账户 / 流水链到 shop 现有积分页**（`/points/accounts`、`/points/transactions`），等 near-pay S4 新模型出来再议。

**仍待定**：异步任务先做「现有队列的只读聚合」还是等统一任务表 —— 排在 P3c 之后，届时再定。

#### P2 各页（2026-11-20 补；契约核对见 §6 P2）

| 页面（页签） | 主表 | 行操作（载体） | 页面级操作 | 接口要点 |
|---|---|---|---|---|
| 商户变更 | 资料待审核的商户：商户、最新资料版本、用途、提交人、提交时间 | 查看（详情抽屉：资料字段与材料）· 通过（理由弹框）· 驳回（理由弹框，危险）· 要求补充（理由弹框） | — | near-pay 没有跨商户的变更列表：列表 = `GET /merchants?profileStatus=SUBMITTED`，审的是 `latestProfile`；命令 `profile-revisions/{rev}/approve\|reject\|request-more`，码 `pay:merchant:approve` |
| 退款订单 | 退款号、支付单、金额、状态、申请人、时间 | 查看 · 同意（理由弹框）· 驳回（理由弹框，危险）；待复核状态才出现 | — | 只有 v1 `/pay-ops/v1/refunds`：不分页（limit ≤ 200，前端分页）、信封 `{code,msg,data}`、复核 `{approve, reason}` 无幂等号；需两个不同的人、申请人不能复核自己 |
| 支付终端 | 终端号、名称、门店、渠道、配置状态、核验、设备状态 | 查看（基本 + 就绪检查）· 编辑（抽屉）· 核验（抽屉：凭证编号）· 启用 / 停用（理由弹框）· 生成激活码（抽屉，一次性明文只显示一次）· 挂失（强确认）· 退役（强确认 + 理由） | 新增（抽屉） | 码 `pay:terminal:view\|manage\|verify`；启用前须已核验且核验人 ≠ 配置人（near-pay 强制） |
| 接入应用 | 应用号、名称、归属、环境、回调状态、密钥状态、状态 | 查看（基本 + 门店授权表）· 编辑（抽屉）· 授权范围（抽屉）· 回调地址（抽屉）· 核验回调（抽屉，审批码）· 启用 / 恢复（理由弹框，审批码）· 暂停（理由弹框）· 吊销（强确认）· 签发密钥（抽屉，明文只显示一次） | 新增（抽屉） | 码 `pay:app:view\|manage\|approve`、签发 `pay:credential:manage`；密钥从不回读，只有指纹 |
| 请求日志 | 请求号、入口、应用、方法 + 路径、HTTP、鉴权结果、耗时、时间 | 查看（详情：脱敏的请求 / 响应、关联的渠道调用与回调） | — | 只读，码 `pay:trace:view`；详情不是标准 Detail |
| 渠道调用 · 调用 | 调用号、渠道、动作、业务号、结果、耗时、状态、时间 | 查看（每次尝试的脱敏报文、HTTP、熔断状态） | — | 只读，`pay:trace:view` |
| 渠道调用 · 回调 | 回调号、渠道、通知类型、验签、结果、业务号、时间 | 查看（脱敏的头与体、处理结果、应答） | — | 只读，`pay:trace:view` |
| 监控告警 · 事件（默认） | 事件号、规则、指标、级别、观测值 / 阈值、处理人、状态、最近触发 | 查看 · 认领（确认）· 指派（抽屉）· 处置完成（抽屉：结论 + 关联单号）· 关闭（理由弹框） | — | 码 `pay:alert-event:view\|handle`；改派或处置别人认领的需 `pay:alert-event:escalate`（服务端判） |
| 监控告警 · 规则 | 规则号、名称、指标、范围、条件、级别、状态 | 查看（含历史版本）· 编辑（抽屉）· 发布（理由弹框，审批码；发布人 ≠ 录入人）· 停用（理由弹框） | 新增（抽屉） | 码 `pay:alert-rule:view\|manage\|approve`；阈值是十进制字符串；新建也要 `expectedVersion=0` |
| 审批流程 | 流程号、名称、业务、节点数、状态、版本 | 查看（节点与历史版本） | — | 只读（near-pay S1 写入未开放），`pay:approval-flow:view`；详情不是标准 Detail |
| 经营设置 | 单行：地区、币种、时区、默认渠道、渠道顺序、更新 | 查看 · 编辑（抽屉：默认渠道、渠道顺序、原因） | — | `GET/PUT /settings`，码 `pay:channel:view\|manage`；地区 / 币种 / 时区为启动配置只读 |

### 2.7 每页完成定义

1. 页面只由 `ListPage` + `ActionSpec` 配置 + 业务映射组成，不手拼表格与抽屉状态。
2. 操作载体符合 §2.2；没有抽屉嵌套（测试断言）。
3. 所有编辑抽屉接入草稿；敏感字段标 `sensitive`。
4. 查看码、维护码判权与菜单 TDD §2.2 一致；无权时操作不显示。
5. 三语词条齐；mock 与真实接口两套都能跑。
6. 单元测试（配置、判权、草稿）+ 浏览器实机走一遍关键路径（本机 ops-web + mock；能连 near-pay 测试库的再连真实接口）。
7. 菜单项改为可见、`/oms/` 跳转下线（经网关 G1 / G4 更新点位状态）；`gen-ui-catalog.py` 重生成并提交。

### 2.9 模块边界与 P4（2026-11-20 用户：「商户列表，渠道进件页面还在支付那边，要统一思想放在 shop 这边一起实现，支付中心放在独立的模块」）

用户选定：**全量对齐**（near-pay `/oms` 商户与进件工作区的功能全部原生化，不再跳 `/oms`）+ **菜单、代码、网关模块三层都独立**。

**模块边界**

| 层 | 规则 | 守卫 |
|---|---|---|
| 菜单 | 支付相关的菜单全部进 `/payment-center?tab=`：V684/V685 新增的直接是新地址；V671 的 5 个（商户列表、渠道进件、支付渠道、支付订单、支付异常）库里仍是旧地址，前端读菜单时由 `normalizeLegacyPaymentMenu`（`LEGACY_MENU_HREFS`）换成支付中心页签，改址 SQL 待 v1 下线后执行。商户、订单模块不再挂支付页签；旧书签由 `useLegacyPayTabRedirect` 转到新页签 | `platform-nav.test`、`use-page-tab.test`（页面再列旧页签键会因「没有对应叶子」报错） |
| 代码 | 页面、接口客户端（`_api/`，原 `lib/pay-ops`）、组件（`_kit/`，原 `components/payment`）全在 `app/payment-center/`；模块外只许 import `app/payment-center/module.ts`（页签登记、旧书签转发）；模块内不 import 其它业务页 | `lib/payment-center-module.test.ts` 三条：对外只经 module、对内不引业务页、不再跳 `/oms` |
| 网关 | 支付中心全部菜单点位的权限码都是 `pay:*`，`ModuleOwnership` 按前缀归 `pay` 模块；pay 没装配时菜单接口不下发 | 网关既有的 `FunctionModuleSync` |

删除：`components/payment-ops-entry.tsx`（跳 `/oms` 的过渡页）、`LEGACY_MENU_HREF`、`NEXT_PUBLIC_PAY_NATIVE_PAGES`、near-pay v1 客户端（退款改走 shop）。

**P4 商户与进件原生化**：把 near-pay `/oms` 的商户工作区（新建商户、资料代填、主体资料、能力进度、渠道、收款策略、门店设置、批量绑定、测试商户）与进件工作区（申请草稿、补件、文件上传、证照识别、蓝新资料与变更）做进支付中心的「商户档案」「渠道进件」两个页签。接口契约与分步见 §2.10。做完后 `merchants-tab` / `onboardings-tab` 里的 `WorkspaceHint` 删除，守卫第三条转绿。

### 2.10 P4 契约与分步（依据：near-pay 线上那条线 `release/nearpay-p3a-live-20261120` 的 ops-web 与控制器）

**口径**：调用 near-pay 已有的 `/pay-ops/v2` 端点，**near-pay 后端不改**。幂等号沿用本模块客户端的规则（路径 + 请求体算出 `requestNo`：
超时后原样重发是同一个号，near-pay 按号去重），所以不照搬旧页的「恢复上次操作 / 核查原请求」按钮。需要审批的动作只「提交审批」（`POST /approvals`），批与驳在「审核待办」。

| 页签 | 操作（抽屉 = 一件事；弹框 = 确认 / 理由） | 端点 | 码 |
|---|---|---|---|
| 商户档案 | 页面：新建支付商户（经营主体号 + 名称；`/merchant-creations/context` 可用才出现） | `GET /merchant-creations/context`、`POST /merchant-creations` | `pay:merchant:manage` |
| | 页面：新建测试商户（批次号；`/test-merchants/context` 可用才出现，标「仅测试」） | `GET/POST /test-merchants` | 同上 |
| | 查看：基本、收款能力进度、已开通 / 申请中渠道、收款策略、来源关联、资料版本、来源事件 | `GET /merchants/{no}`、`/capability-progress`、`/channels`、`/collection-policy`、`/source-events` | `pay:merchant:view` |
| | 代填资料（6 个字段，留空 = 不改，敏感三项不进草稿；只对无来源关联的商户） | `GET/PUT /merchants/{no}/assisted-profile/draft` | manage |
| | 提交资料审核（确认） | `POST …/assisted-profile/submit` | manage |
| | 收款策略（模式 + 产品 + 理由） | `PUT /merchants/{no}/collection-policy` | `pay:collection-policy:manage` |
| | 核验来源关联（选关联 + 通过 / 不一致 + 理由） | `POST …/source-links/{link}/verify` | manage |
| | 一键确认审核（单人模式实例；确认） | `POST /merchants/{no}/confirm` | approve + manage |
| | 提交恢复审批（弹框） | `POST /approvals` `PLATFORM_MERCHANT_RESUME` | `pay:approval:view` |
| | 主体资料核对（选版本 → 字段、证件预览、证照识别比对）/ 退回指定资料补件 | `GET …/entity-materials`、`…/files/{no}`、`POST …/request-more`；识别 `POST /ops/onboarding-documents/recognize`（shop） | `pay:merchant:approve` |
| 渠道进件 | 页面：代商户创建进件（商户号 → 按商户市场列出可用的法币渠道 → 选一个） | `GET /merchants/{no}`、`GET /channels`、`POST /onboardings` | `pay:onboarding:manage` |
| | 查看：基本、开户交接进度（蓝新）、账户候选、签约链接、各材料版本（脱敏显示）、渠道告警（AMR10014 / 回执缺失） | `GET /onboardings/{no}`、`/draft/readiness` | view |
| | 平台资料（法律形式、联系人、电话*、结算账户类型、结算账号*） | `PUT /draft`；* 先 `POST /newebpay-materials` 换 `cipherRef` | manage |
| | 蓝新资料 · 主体与代表人 / 联系与管理者 / 商店 / 结算 / 运营字段（5 个抽屉，字段按法律形式与代表人类型显隐） | 同上 | manage |
| | 附件（`LICENSE_1..10`，PNG / JPEG / PDF ≤5 MiB，预览走 blob） | `GET/POST /files`、`GET /files/{no}` | manage / view |
| | 证照识别填资料（上传证件 → 识别结果映射到字段 → 勾选写入草稿） | shop `/ops/onboarding-documents/recognize` + `PUT /draft` | manage |
| | 代商户确认资料、提交完整版本审核（确认） | `POST /draft/confirm`、`/draft/submit` | manage |
| | 提交平台审核批准（弹框）· 退回补件 / 驳回（抽屉） · 退回指定字段补件（抽屉，勾字段） | `POST /approvals` `ONBOARDING_REVIEW`、`/review`、`/draft/request-more` | manage / approve |
| | 送件（人工模式填回执号）· 刷新渠道进度 · 登记渠道结果（人工模式）· 重试本地交接 · 取消 | `/dispatch`、`/refresh`、`/manual-result`、`/draft/handoff/retry`、`/cancel` | manage / approve |
| | 蓝新商户资料修改：列表与提交审批 / 发送 / 取消；新建修改（字段由服务端给） | `…/newebpay-modifications*` | view / manage |

- **蓝新字段定义**：near-pay 由 `backend/pay/pay-channel/src/main/resources/newebpay/onboarding-ndna-1.1.1.json` 经 `scripts/generate-newebpay-onboarding.py` 生成 `ops-web/lib/onboarding/newebpay-fields.ts`，没有运行时接口；本模块原样拷一份到 `_api/newebpay-fields.ts`（文件头注明来源与版本 `NEWEBPAY_NDNA_1_1_1`），JSON 改了要重拷。
- **非蓝新渠道**仍用原有的「提交材料」表单（`POST /submit`），蓝新开了草稿后 near-pay 拒绝直接提交（409），按渠道分流。
- **不做**（near-pay 线上不可达或无后端）：门店设置与批量绑定（旧原型适配器，线上路由不到）、`/merchant-changes` 复核（near-pay 无此控制器；资料版本的批驳在「商户变更」页签已有）。
- **已知限制**：蓝新资料修改只在 near-pay 网关为 sandbox / mock 时存在（生产 `gateway.newebpay=disabled`），接口 404 时该操作不出现；其查看码在 near-pay 写成 `pay:onboarding:read`（疑似笔误），由 near-pay 修。

**分步**：① 客户端补上传（multipart）与文件下载（blob）、接口与 mock；② 商户档案；③ 渠道进件；④ 删 `WorkspaceHint` 与 `paymentWorkspaceHref`，商户详情「支付服务」块的跳转改进支付中心；⑤ 测试、守卫、浏览器、提交。

### 2.11 P5 补齐 near-pay 线上页面的剩余操作（2026-11-20 用户：「继续完成剩余的任务，web页面对接支付运营接口」）

**依据**：near-pay `develop/s1`（`fa708146c`）ops-web 线上版 `LIVE_PAGE_CODES` 的 10 个页面实际调用的 `/pay-ops/v2` 端点，与本模块 `_api/api.ts` 逐条比对（脚本比对，宽匹配路径参数）。
near-pay 有 322 个运营端点；本模块已调 133 个；未调的里 99 个连 near-pay 自己的页面也不调（纯接口），不算缺口。线上页面里还缺的是下面这些。
P4 记的「门店设置与批量绑定线上路由不到」指的是 `merchant-detail.tsx` 的旧适配器，**不是**商户「渠道」页签的渠道账户与门店「渠道绑定」页签 —— 那两处在线上版可达，P4 漏了。

| 位置 | 操作 | 端点 | 码 |
|---|---|---|---|
| 商户 · 行操作「渠道账户」（抽屉，列表 ↔ 账户详情在抽屉内切换，不嵌套抽屉） | 列表（渠道、MID、核验、状态、绑定门店数）、手工登记（待核验） | `GET/POST /channel-accounts` | `pay:channel-account:view` / `manage` |
| | 账户详情：资料、开通产品、渠道凭证、关联门店、结算配置（只读）、操作记录 | `GET /channel-accounts/{no}`、`/bindings`；产品目录 `GET /products?channelCode=` | view |
| | 提交核验审批 · 驳回核验 · 启用 · 仅退款 · 停用 · 提交恢复审批 | `POST /approvals`（`CHANNEL_ACCOUNT_VERIFY` / `_RESUME`）、`…/verify`、`/activate`、`/refund-only`、`/suspend` | manage；驳回 `approve` |
| | 开通产品（证明引用 + 理由 + 勾选已核对）· 关闭产品 | `POST …/products/{code}/open`、`/close` | manage |
| | 绑定凭据（keyId）· 录入 / 轮换密钥（蓝新 HashKey+HashIV；LINE Pay / 街口 / MockPay secret；PayBy 私钥+公钥；不回显） | `PUT …/credential`、`POST …/migration-credential` | manage |
| | 商户号认领：列表、证明预览、关联既有账户 · 拒绝 · 带入登记表（实例未开放时 404 → 一行说明） | `GET /channel-account-claims`、`/{no}`、`/files/{file}`、`POST /{no}/resolve` | view / manage |
| 门店 · 行操作「渠道绑定」（抽屉） | 当前与历史绑定；绑定本商户已核验账户（同门店同渠道一条生效）；解绑 | `GET/POST /stores/{no}/bindings`、`…/{b}/unbind` | `pay:store-binding:view` / `manage` |
| | 换绑：选目标 → 影响预览 → 登记 → 提交换绑审批 / 撤回登记 | `GET …/rebind-impact`、`POST …/rebind-request`、`/withdraw`、`POST /approvals`（`STORE_BINDING_REBIND`） | manage |
| 异常交易 | 「转人工」视图（门店筛选、跟进记录） | `GET /payment-escalations` | `pay:order:view` |
| 支付渠道 | 标为待开通（理由弹框）；新建 / 编辑时适配器改为下拉 | `POST /channels/{code}/pending`、`GET /adapters` | `pay:channel:manage` / view |
| 支付产品 | 新建 / 编辑时支付方式改为下拉 | `GET /payment-methods` | `pay:product:view` |

- **契约变更**：只新增权限码（`Perms.java`、`ops-web/lib/perm-map.ts` 各 6 个：`pay:channel-account:view|manage|approve`、`pay:store-binding:view|manage|approve`），无端点、库表、迁移。码与 near-pay 控制器 `@payPerm.can` 同名。
- **不移植**：near-pay 的「恢复上次操作 / 查询原回执」（`/commands/{key}`、`/merchant-creations/by-request`、`…/operations/{requestNo}`）—— 本模块幂等号由路径 + 请求体决定，原样重发即同号，同 §2.10 口径；直接调换绑执行 `…/rebind` 不做（执行在审批通过后由审核待办触发）。
- **分步**：① 类型、接口、mock 与接口测试；② 渠道账户抽屉（含认领、产品、凭证）；③ 门店绑定抽屉；④ 转人工、待开通、两个下拉；⑤ 权限码、生成物、守卫、提交。

### 2.12 P6 商户页补缺（2026-11-21 用户：「补 1–4，再看门店页」）

**依据**：线上逐页核对（渠道 → 商户 → 门店）时，near-pay `feat/ops-identity-unified-20261120` 的 `/pay-ops/v2` 端点与本模块 `_api/api.ts` 重新比对，并在线上用测试商户 `PM0E0F6CC8F7454D75` 看 `allowedActions` 与实际返回。

| # | 位置 | 操作 | 端点 | 码 |
|---|---|---|---|---|
| 1 | 渠道账户详情 | 恢复：显示本账户最近一张 `CHANNEL_ACCOUNT_RESUME` 审批的状态；已批准未执行时给「执行恢复」（审批执行即调 `ChannelAccountService.resume`）。关闭：near-pay 服务端未开放（「S1 未开放」），只在详情里注明，不放按钮 | `GET /approvals?bizType=&subjectRef=`、`POST /approvals/{no}/execute` | 执行 `pay:channel-account:approve` |
| 2 | 渠道账户详情 · 新标签「转移」+ 商户页级入口不做 | 本账户的转移记录；发起转移（目标商户号、证明、理由）；审核通过 / 驳回（审批单版本）；执行（申请版本） | `GET/POST /channel-account-transfers`、`GET /{no}`、`POST /{no}/approve|reject|execute` | `pay:channel-account:view` / `:transfer` / `:transfer:approve` |
| 3 | 渠道账户详情 · 新标签「资质」 | 资质版本列表（状态、用途、有效期、制单人、审批号）；查看内容；导入新版本（证据号、版本、渠道、用途、内容 JSON、有效期、理由）；草稿提交激活审批（`CHANNEL_QUALIFICATION`，`subjectRevision` = 资质版本号）；吊销 | `GET /channel-accounts/{no}/qualifications`、`GET /channel-qualifications/{ref}`、`POST /channel-qualifications`、`POST /{ref}/revoke`、`POST /approvals` | view / `manage`（激活审核、执行 `approve`） |
| 4 | 商户详情 · 来源事件 | 摘要改用 near-pay 实际字段：来源系统 · 能力 · 结果 · 操作人 | 无新增 | — |

- **契约变更**：`ops-web/lib/perm-map.ts` 加 `pay:channel-account:transfer`、`pay:channel-account:transfer:approve` 两个映射（后端常量 §9 已加），无端点、库表、迁移。
- **不做**：测试渠道账户的试运行准备（`POST /channel-accounts/{no}/qualifications`，只适用蓝新测试渠道）、测试渠道开户与试单、代建进度与认领附件 —— 属测试工具或只读回执，放第 6 项。

### 2.13 P7 门店页补缺（2026-11-21 用户：「三块都补」）

| # | 位置 | 操作 | 端点 | 码 |
|---|---|---|---|---|
| 1 | 门店详情 · 收款码 | 列表（码、类型、桌号、币种、单笔 / 单日限额、状态、扫码链接与门店码链接）；新建（类型 STORE / TABLE、桌号、限额、理由；near-pay 建码非幂等，先查后建，页面在提交前刷新列表）；停用（理由，`expectedVersion` 取视图里的版本） | `GET/POST /stores/{no}/qr-codes`、`POST …/{qrCode}/disable` | `pay:collection-entry:view` / `manage` |
| 2 | 门店详情 · 支付方式 | 当前模式（跟随商户 / 单独设置）、每种方式的配置开关与生效状态、能否改与被哪一层挡住；保存（开启集合 + 模式，带 `eligibilityRevision` 与版本；near-pay 换算成门店收款策略） | `GET/PUT /stores/{no}/method-preferences` | `pay:collection-policy:view` / `manage` |
| 3 | 门店详情 · 同步事件 | 来源系统、事件号、渠道、版本、结果、问题、时间 | `GET /stores/{no}/sync-events` | `pay:store:view` |

- **契约变更**：near-pay `OpsQrCodeController.CodeView` 加 `version`（只加字段；停码必须带它，原视图没给）。ai-shop 无新码、端点、库表。
- **线上**：near-pay 换 `pay-portal` 内 `OpsQrCodeController` 与 `$CodeView` 两个类。

### 2.14 near-pay 侧修复（2026-11-21 用户：「直接改」，Chrome 复核与线上排查发现）

| # | 问题 | 原因 | 修复（near-pay `feat/ops-identity-unified-20261120`） |
|---|---|---|---|
| 1 | 测试渠道账户的资质页 403「资格材料归属冲突」 | 8 条历史资格版本内容写 SANDBOX、却存于 PRODUCTION 分区（ADR-046 并入正式接口时留下）；按账户列版本时一条不符就整页失败 | `cfa29d4a8`：列表跳过非当前环境的版本；单条读取仍严格校验 |
| 2 | 渠道账户允许操作里有 `close`，点了必报「S1 未开放」 | `allowedActions` 与 `close()` 不一致 | `c586f7938`：未开放前不列 `close`（运营端的「关闭未开放」说明随之不再出现） |
| 3 | 日志每 3 秒刷 `Payment display rebuild deferred for projection 830/834` | 门店 830/834 在 ai-shop 已换主体 `M261113Y3QW5XKEFW`，near-pay 仍挂商户 15，解析器判不可收款；`defer()` 只改时间，同一行无限重选 | `36f05f23f`：不可收款的门店发布空支付方式，不再重试；意外异常仍 defer |
| 4 | 商户 / 门店 / 渠道账户列表 6–20 秒 | 全部行先转 DTO（每行数次查库）再内存分页 | `a0032ba7c`：先按原始字段筛选分页，只给当前页转 DTO（商户列表带资料状态 / 来源系统筛选时保持原路径） |

不是代码问题、需要业务决定的：门店 830（收款码无扫码链接同因）、834（两者名称都标「舊·待下架」）该下架还是挂到主体 `M261113Y3QW5XKEFW` 的新商户（near-pay 里还没有）；测试店 835（主体 `M261119TS0PN0XBK9` 在 near-pay 无商户，`UNLINKED_ENTITY`）。

### 2.15 P8–P10 对接补齐（2026-11-21 用户：「给出对接计划，先完成页面，再对接，再测试」「确认，然后自动执行所有任务」）

依据《支付中心对接完整性测试方案》：near-pay `/pay-ops/v2` 321 个接口，已接 212、只封装 2、未接 107（`scripts/pay-center-wiring.py`）。

**范围（用户确认）**
- **A 核心运营闭环（约 64 个，本节做）**：分 P8–P10 三批，每批按「页面（mock）→ 对接（HTTP、权限码、菜单）→ 测试（vitest、Chrome）」走完再做下一批。
- **B 渠道专属（37 个，暂不做）**：易特付虚拟账户 15、PayBy POS 及验收 13、SoftPOS 3、测试渠道试用 / 注册 / 会话 6。等业务确认上线渠道后再排。
- **C 不在前端提供（8 个）**：near-pay 权限映射与资源 7 个（权限由 ai-shop 决定，ADR-061 / 支付运营权限在服务内判）；`/me/access` 已由顶栏入口使用。
- **不接**：`POST /channel-accounts/{}/close`。near-pay 不在允许的操作里开放，前端保留「未开放」说明。

**批次**

| 批 | 业务域 | 落点 | 接口 |
|---|---|---|---|
| P8 | 商户 | 商户详情：资料版本列表、工作台、关闭商户；代办建档结果查询；**新页签「商户成员」** | 11 |
| P8 | 审核 | 「审批流程」页签加新建 / 编辑 / 复制 / 发布 / 停用；审核待办加候选人、任务审核 | 7 |
| P8 | 门店 | 渠道绑定加「重新绑定」；**新页签「来源关联修复」**（申请、审批、执行、驳回） | 7 |
| P8 | 进件 | 草稿命令状态查询 | 1 |
| P9 | 渠道 | 渠道账户编辑、恢复；资质新建与详情；认领附件与命令状态；终端凭证引用、退役信息；应用撤销门店授权 | 11 |
| P9 | 订单 / 告警 | 告警事件「冲正回查」；告警规则详情；告警指标 | 3 |
| P9 | 资金对账 | 对账差异匹配、调整申请；储值交易详情；**新页签「渠道账单」** | 9 |
| P10 | 平台设置 | **新页签「凭证管理」**（类型、策略、凭证、版本生成 / 启用 / 撤销 / 校验、访问日志、绑定）；**新页签「审计事件」**；佣金规则详情 | 16 |

**每批的契约动作**
- 新页签：`module.ts` 登记（先进 `PENDING_MENU`），`platform-nav.ts` 加叶子，迁移加菜单功能点（V687 起，授权 SUPER_ADMIN），重跑 `gen-ui-catalog.py`。
- 新权限码：`perm-map`、shop `Perms` 与功能点迁移；`FunctionPointPermAlignmentTest`、near-pay `OpsV2PermissionCoverageTest` 要通过。
- 文案：`-copy.ts` 简体、繁体、英文；新枚举进枚举登记。
- 网关：`/ops/pay-ops/v2` 整体透传，不改。
- 每批结束重跑 `scripts/pay-center-wiring.py`，更新测试方案的对照表。

**P8 实现（2026-11-21，分支 `feat/pay-center-p8-20261121`）**

契约逐个从 near-pay `da3ced2a4` 源码核对。调整如下：

- **near-pay 未开放，不做**（固定回 409 `CAPABILITY_NOT_READY`）：关闭商户（随 BM09/BM18）、审批流新建 / 编辑 / 复制 / 发布 / 停用（随 S3）、审核任务候选人（无员工目录）。
- **按等价能力处理**：`POST /approval-tasks/{}/review` 与现有 `/approvals/{}/review` 重复，不另做。
- **门店换绑执行**：`POST /stores/{}/bindings/{}/rebind` 是换绑审批通过后的执行。需要 `pay:store-binding:approve`，登记人不能自己执行，返回新绑定号。做成渠道绑定里「执行换绑」按钮。
- **超时恢复（接口层，页面无感）**：建商户、代填资料保存与提交、进件草稿保存 / 确认 / 提交遇到网络中断或 5xx 时，按同一个幂等号查回执（`/merchant-creations/by-request/{}`、`/assisted-profile/operations/{}`、`/draft/commands/{}`）。查到就当成功，否则原样报错。
- **资料版本**：商户详情原有「资料版本」改用 `GET /merchants/{}/profile-revisions`，多出审核人、审核意见、审批号；新增「工作台」段（`GET /workspace`）。
- **新页签**「商户成员」「关联修复」：菜单 V687（排序 550 / 551，授权 SUPER_ADMIN 并从商户列表继承查看）。成员的状态命令先取详情拿版本（列表不带版本）。修复单审批用审批版本，执行用修复单版本。
- **权限码**：后端早已登记（V686），前端 `perm-map` 补 `pay:member:view|manage`、`pay:merchant:repair`、`pay:merchant:repair:approve`。

**结果**：`scripts/pay-center-wiring.py` 统计本期范围 267 个接口，已接 230（86%）；未开放 7、不提供 10、暂缓 37 单列。测试：`p8.test.ts` 9 条，ops-web 全量通过；后端 `FunctionPointPermAlignmentTest`、`OpsPermConfigFlowTest`、`FunctionModuleSyncTest`、`PayOpsPermissionCodesTest` 通过。

**P9 / P10 实现（2026-11-21，同一分支）**

契约同样从 near-pay `da3ced2a4` 核对，线上开关从 166 只读核对。

- **P9 渠道**：
  - 渠道账户「修改坐标」（`PUT /channel-accounts/{}`，只在 UNVERIFIED，改完核验退回 PENDING；渠道商户号要重填、不存草稿）。
  - 蓝新测试渠道「准备沙箱试运行」资质（`POST /channel-accounts/{}/qualifications`，建 DRAFT 经审批生效）；资质展开改取 `GET /channel-qualifications/{}`。
  - 终端详情「凭证与退役」（凭证引用需 `pay:credential:view`，没有就不发请求）。
  - 应用门店授权「撤销」（版本是授权版本）。
- **P9 告警与资产**：
  - 告警规则的指标说明来自 `GET /alert-metrics`。
  - 告警事件「冲正回查」（无请求体、不带幂等号，结果 VERIFIED / PENDING / DEFERRED）。
  - 储值交易查看改取详情。
- **超时恢复**：认领处理也走回执（`/channel-account-claims/{}/commands/{}`）。回执明确「被拒」时把原因交给页面，不再当作查不到。
- **P10**：
  - 新页签「凭证管理」：列表、详情（版本 / 策略 / 操作记录 / 访问记录）、登记、录入版本、生成（明文只显示一次）、核验 / 启用 / 撤销、轮换策略、使用情况。录入与生成不带自动幂等号，避免密钥材料进请求体指纹。
  - 新页签「审计事件」（只读）。
  - 佣金规则查看改取详情。
  - 两个页签都只授 SUPER_ADMIN（V687 排序 552 / 553）。
- **不做（已核对）**：
  - 对账差异匹配 / 调整申请：near-pay 固定 409。
  - 渠道账单 6 个：线上未开 `shop.pay.channel-bill.enabled`，控制器不加载；回执下载也是空文件。
  - 账户恢复：已由审批执行完成，`/resume` 等价。
  - 认领附件：随认领详情返回。
- **权限码**：前端补 `pay:credential:view|approve`、`pay:audit:view`（后端 V686 早已登记）。

**结果**：`scripts/pay-center-wiring.py` 统计本期范围 257 个接口已接 257（100%）；near-pay 未开放 15、不在运营端提供 12、渠道专属暂缓 37 单列。测试：P8 9 条 + P9 7 条 + P10 6 条，ops-web 全量 158 个文件 1431 条通过；后端菜单 / 权限测试通过。

### 2.8 不做

- 不回迁 shop 现有页面到新骨架；新组件稳定后另议。
- 不照搬 near-pay 运营端的页面布局与 Mock 数据。
- 不在本文补 near-pay 后端接口（P3 依赖另立 near-pay 任务）。

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试 | 跑过 | 消融 |
|---|---|---|---|
| AC1 | `lib/action-host.test.ts` › ListPage 渲染 ✅；`lib/payment-center-page.test.ts` › 10 个页签各自渲染页头（TabHeader）、表格行与「查看」✅ | ✅ | |
| AC2 | `lib/action-host.test.ts` › validateActions：kind 只能三种、drawer 必须有 render、弹框必须有 confirm+run、key 不重复 ✅；「超过 3 个输入的弹框」不再需要 —— dialog 只有理由一个输入（见 §6 偏差 1） | ✅ | |
| AC3 | `lib/action-host.test.ts` › 只有一个抽屉（替换 + 返回）、弹框叠在抽屉上、地址栏同步与还原、无权不还原、Drawer 嵌套报错 ✅ | ✅ | 去掉嵌套检测 → 「在已打开的抽屉里再渲染抽屉时报错」红 ✅ |
| AC4 | 每页配置测试：单个抽屉字段数 ≤ 12（超出须拆操作并在 §2.6 写明） | | |
| AC5 | `lib/draft.test.ts`（键、读写、7 天过期、操作人隔离、列表、存储不可用、未知版本）+ `lib/use-draft.test.ts`（防抖落盘、恢复、冲突、提交后清除、放弃、卸载与 pagehide 立即落盘、写入失败提示、不启用时不碰存储）✅ | ✅ | |
| AC6 | `lib/draft.test.ts` › 敏感字段不落盘；`lib/use-draft.test.ts` › 编辑后防抖落盘，敏感字段不落盘 ✅ | ✅ | 去掉 stripSensitive → 两条红 ✅ |
| AC7 | 单页文件行数门槛（如 ≤ 300 行，超出须拆业务件） | | |
| AC8 | `lib/payment-center-page.test.ts` › 页签按权限码过滤、全无权给空态 ✅；行操作按 `ActionSpec.perm` 过滤由 `lib/action-host.test.ts` › 按权限码与 visible 过滤 ✅；审批动作只在审核待办（代码评审） | ✅ | 去掉页签权限过滤 → 两条红 ✅ |
| AC9 | `lib/nav.test.ts` › 支付中心页签要么有菜单叶子、要么在 PENDING_MENU（清单只许变短）✅；菜单项切换到原生页由开关 `NEXT_PUBLIC_PAY_NATIVE_PAGES` 控制，网关登记点位后再收口 | 部分 ✅ | |
| 实机 | P0：本机 ops-web mock（`/dev/pay-kit`，超管）走查 ✅：详情 → 底栏「编辑备注」在同一抽屉替换（始终 1 个抽屉、地址栏 `?action=&id=`）；输入后 `localStorage` 只有一条草稿；取消提示「草稿已保存」；重开恢复；保存后草稿清除；结算账户的账号不进草稿；详情上「关闭订单」弹框叠在抽屉上；「我的草稿 (1)」点开直达对应抽屉；整页刷新后抽屉与草稿还原并提示版本冲突。各岗位走查随 P1 | P0 ✅ | |

## §6 对账二 · 设计 → 实现

### P0（2026-11-20，工作树 `ai-shop-paycenter`，分支 `feat/ops-pay-center-menu`，未提交）

| 动作 | 路径 | 对应 §2.3 |
|---|---|---|
| 新增 | `ops-web/components/ui/list-page.tsx` | `ListPage`（含「我的草稿」弹框） |
| 新增 | `ops-web/components/ui/action-host.tsx` | `ActionSpec` / `useActionHost` / `RowActionButtons` / `validateActions` |
| 新增 | `ops-web/components/ui/detail-drawer.tsx` | `DetailBody` / `DetailActions` |
| 新增 | `ops-web/components/ui/action-form.tsx` | `ActionDrawer` → 实名 `ActionForm`（抽屉内容而非抽屉本身，见偏差 3） |
| 新增 | `ops-web/components/ui/draft-notice.tsx`、`masked-value.tsx` | `DraftNotice`、`MaskedValue` |
| 新增 | `ops-web/lib/draft.ts`、`lib/use-draft.ts` | 草稿存储与钩子 |
| 新增 | `ops-web/lib/pay-ops/client.ts` | `payOps` 客户端 |
| 新增 | `ops-web/components/payment/pay-kit.tsx` | `PayListPage` / `usePayOps` / `useDraftOperator` / `usePayOrderStatusMap` / `PayRefCell` / `ApprovalSubmitNotice` |
| 新增 | `ops-web/app/dev/pay-kit/page.tsx`、`lib/pay-ops/demo-orders.ts` | `/dev` 示例页与内存数据 |
| 修改 | `ops-web/components/ui/drawer.tsx` | 嵌套检测 + `data-surface="drawer"` |
| 修改 | `ops-web/components/ui/form-drawer.tsx` | `FieldDef.sensitive` |
| 修改 | `ops-web/lib/i18n/messages/{zh,zh-TW,en}.ts` | `actionKit.*`、`payKit.*` 三语 |
| 修改 | `ops-web/components/README.md`、`docs/technical/design/规范-运营端.md`（生成） | 组件清单 |
| 测试 | `lib/draft.test.ts`、`lib/use-draft.test.ts`、`lib/action-host.test.ts`、`lib/pay-ops/client.test.ts` | 29 + 6 条 |

验证：`npx tsc --noEmit` 无输出；`npx vitest run` 144 个文件 1308 条全部通过；`gen-ui-catalog.py --check` 通过；`check-ops-orphan.mjs --check` 退出 0。

**偏差说明**

1. **`ReasonDialog` 不另做**：现有 `useConfirm` 已支持 `requireReason`（理由必填、交给 action）与 `requireText`（强确认），`ActionSpec` 的 dialog 直接用它。因此 dialog 只有「理由」一个输入，「超过 3 个输入的弹框报错」这条守卫不再需要。
2. **`useLeaveGuard` 并入 `useDraft`**：草稿在卸载、`pagehide`、`visibilitychange`、`beforeunload` 时立即落盘，离开页面不会丢，所以不再弹「确定离开？」拦截；关抽屉时只提示「草稿已保存」。
3. **`ActionDrawer` 实名 `ActionForm`**：它是抽屉的**内容**，抽屉本身由 `ActionHost` 统一渲染 —— 这样才能保证只有一个抽屉槽位。
4. **`ActionContext.available()`**：§2.3 未列。详情底栏要按同一套判权过滤按钮，放进上下文后 `DetailActions` 只需 `ctx`。
5. **`/payment-center` 路由壳推迟到 P1**：空壳路由不在菜单里，会撞运营端孤儿页闸门；P0 的示例放在 `/dev/pay-kit`（dev-only，不进菜单）。
6. **支付端令牌**：客户端按 `Authorization: Bearer <shop 令牌>` 发送，与 near-pay 运营端共享会话；near-pay 是否认这个头还没连真接口验证，P1 第一页接真接口时核对。
7. **弹框理由提示文案**：`useConfirm` 的默认提示是「会记入审计，商家能看到」，面向商家；支付页面在 P1 用 `reasonHint` 覆盖成支付侧口径。

### P1（2026-11-20，同一工作树与分支，未提交）

**契约核对**：near-pay `/pay-ops/v2` 十个资源的端点、查询参数、命令体、DTO、枚举与守卫码逐项读后端源码核对（`pay-portal/.../api/ops/v2/*Controller`、`Ops*Dtos`、`OpsOrderConsoleService`）。要点：
成功信封 `{code:0,data}`；列表 `{records,total,page,size}`、`page/size` 从 1 起、上限 100；详情 `{record,version,allowedActions,timeline}`（**审批详情例外**，直接返回 ApprovalDetailDto）；
状态命令要 `requestNo + expectedVersion + reason`，**订单与支付异常的命令没有幂等号与版本**；鉴权读 `Authorization: Bearer <shop 运营令牌>`，每次回调 shop `/ops/auth/me` 校验 —— 偏差 6 的疑问已解除。

| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `ops-web/app/payment-center/page.tsx`、`copy.ts`、`shared.tsx` | 页签壳（`usePageTab` 读页签、切页签换干净地址）、三语文案、列表状态 / 状态映射 / 详情加载 / 命令执行 |
| 新增 | `ops-web/app/payment-center/{orders,exceptions,merchants,stores,onboardings,approvals,channels,products,access,data-scopes}-tab.tsx` | 10 个页签，操作拆分见 §2.6 |
| 新增 | `ops-web/lib/pay-ops/{types,api,mock,mock-store,center-tabs}.ts` | DTO、HTTP / mock 双实现（按 `NEXT_PUBLIC_USE_MOCK` 切）、页签登记与「等网关登记」清单、原生页开关 |
| 修改 | `ops-web/lib/pay-ops/client.ts` | 幂等号改为按「路径 + 请求体」计算；可关闭（订单类命令） |
| 修改 | `ops-web/components/payment/pay-kit.tsx` | `usePayCenterApi` |
| 修改 | `ops-web/components/payment-ops-entry.tsx` | 开关打开时既有入口改跳 `/payment-center` |
| 修改 | `ops-web/components/ui/list-page.tsx`、`detail-drawer.tsx`、`action-form.tsx` | 页头由页面传入 `TabHeader`；详情底栏危险动作在前；`ActionForm` 可关闭自带提示 |
| 修改 | `backend/shop-base-auth/.../Perms.java`、`ops-web/lib/perm-map.ts` | 登记 21 个 `pay:*` 码（与 near-pay 守卫同名，恒等映射）；只登记，不授予 |
| 修改 | `ops-web/lib/nav.test.ts`、`nav-platform-settings.test.ts`、`design-tokens.test.ts` | 支付中心页签登记守卫；新码生成的 21 个动作点（归 `OPS_FINANCE`）进期望；明细小表登记进错误态豁免（理由同既有条目） |
| 测试 | `lib/payment-center-page.test.ts`（12）、`lib/pay-ops/api.test.ts`（7） | 页签冒烟、权限过滤、HTTP 映射、mock 状态机 |

验证：`npx tsc --noEmit` 无输出；`npx vitest run` 146 个文件 1332 条全部通过；`Perms.java` 单独 `javac` 通过；`gen-ui-catalog.py --check`、`check-ops-orphan.mjs --check` 通过。
消融：去掉页签权限过滤 → 两条红；订单结案恢复带幂等号 → 映射测试红。
实机（本机 ops-web mock，超管）：支付订单详情（四段 + 明细表 + 空态说明）→ 底栏「人工确认到账」同抽屉替换、草稿落盘、保存后回到详情且状态变「已支付」、草稿清除、只一条提示；
审核待办「认领」→ 切「我已认领」出现「同意 / 释放」→「同意」（弹框显示支付端审计口径的理由提示）→ 状态「已通过」并出现「执行」；
收款接入新建选 POS 时域名 / 会话 / 接入应用 / 包名禁用、终端号可填，保存后入列表；控制台无应用错误。**未连 near-pay 真接口**。

**偏差说明（P1）**

8. **页签名字不回 nav.ts 取**：支付中心菜单点位原定由网关落库（菜单 TDD G1/G4），nav.ts 里没有叶子。页签自带三语名（`center-tabs.ts`），`PENDING_MENU` 列出未登记的页签，`nav.test.ts` 守着清单只许变短。**2026-11-20 菜单已在本分支落地（V684 + platform-nav，见菜单 TDD §6），清单已清空。**
9. **收款接入查看码改为 `pay:collection-entry:view`**：菜单 TDD 原写「对齐前沿用 pay:channel:view」，核对 near-pay 后改用真实码（菜单 TDD 已同步）。
10. **21 个 `pay:*` 码先在 shop 登记**：`can()` 对未登记码一律拒绝、`perm-map` 守卫要求后端码存在，不登记页面就用不了。只是常量与映射，角色授予仍由网关种子做（菜单 TDD G2/G3）。
11. **进件「平台审核」放在进件页**：它是 near-pay 进件域自己的复核（`pay:onboarding:approve`），不是审核待办里的 BM25 审批对象，放进审核待办反而找不到。拆分规则 5 针对的是 BM25 审批。
12. **暂未原生化、仍走支付端工作区的**：新建商户与资料代填（near-pay 结果结构未核对）、进件渠道专属字段与附件上传、门店支付方式偏好（逐项开关需要单独编辑件）、产品的多接入方式 / 多币种限额 / 账户模式调整、来源关联核验（结果取值未核对）。页面上有说明与入口，P2 补齐。
13. **原生页开关默认关**：未连真接口前不改线上行为；打开后既有 5 个菜单项改进原生页，新增的 5 个页签要等网关登记菜单点位才进得去（之前只能直达 `/payment-center?tab=`）。

### P2（2026-11-20，同一工作树与分支，未提交）

**契约核对**（读 near-pay 源码）：退款只有 v1（`/pay-ops/v1/refunds`，不分页、limit ≤ 200、信封 `{code,msg,data}`、复核 `{approve,reason}` 无幂等号）；商户变更没有跨商户列表；交易流水、异步任务没有接口；
测试渠道是独立部署、独立登录的 mockpay 应用；终端激活码与应用密钥是一次性明文；请求日志 / 调用 / 回调、告警规则、审批流程的详情都不是标准 Detail；新建告警规则也要 `expectedVersion=0`。

| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `ops-web/app/payment-center/{merchant-changes,refunds,terminals,apps,traces,alerts,config}-tab(s).tsx` | 11 个页签（渠道调用、监控告警各两个） |
| 新增 | `ops-web/app/payment-center/secret-once.tsx` | 一次性明文：生成后只在抽屉里显示一次，不进草稿与缓存 |
| 修改 | `ops-web/lib/pay-ops/{types,api,mock,client,center-tabs}.ts` | P2 DTO、HTTP / mock 实现、v1 基础路径（由 v2 推出）、11 个页签登记（均在 PENDING_MENU） |
| 修改 | `ops-web/components/payment/pay-kit.tsx` | `usePayCenterApi` 同时带 v1 客户端 |
| 修改 | `ops-web/app/payment-center/{page,copy}.ts(x)` | 注册页签；中英文案 +316 条 |
| 修改 | `backend/shop-base-auth/.../Perms.java`、`ops-web/lib/perm-map.ts` | 再登记 13 个 `pay:*` 码（终端、应用审批、密钥签发、追踪、告警、审批流程）；只登记不授予 |
| 修改 | `ops-web/lib/nav-platform-settings.test.ts` | 新码生成的 13 个动作点进期望（归 `OPS_FINANCE`） |
| 测试 | `lib/pay-ops/api.test.ts`（+3）、`lib/payment-center-page.test.ts`（自动覆盖新页签，+11） | v1 路径与信封、无幂等号命令、v1 基础路径推导、每个页签渲染与「查看」 |

验证：`npx tsc --noEmit` 无输出；`npx vitest run` 146 个文件 1346 条全部通过；`Perms.java` 单独 `javac` 通过；`gen-ui-catalog.py --check`、`check-ops-orphan.mjs --check` 通过。消融：签发密钥恢复带幂等号 → 映射测试红。
实机（本机 ops-web mock，超管）：终端「核验」后状态变「已核验」；「激活码」抽屉生成后明文只显示一次、按钮消失、本地存储里没有；退款复核自己申请的被拒（提示 near-pay 口径、弹框留在原地），复核别人的进入「待二次复核」；
告警认领 → 处置完成（结论 + 关联单号）→ 状态「已处置」并出现「关闭」；经营设置改默认渠道与顺序后表格即时更新；请求日志详情五段（含脱敏报文）；商户变更「通过」后该商户从待审列表消失。**未连 near-pay 真接口**。

**偏差说明（P2）**

14. **交易流水、测试渠道改入 P3**：前者 near-pay 没有跨订单列表接口；后者是独立部署、独立登录的 mockpay，要先解决与 shop 的统一登录。P3 因此为 22 项。
15. **商户变更的列表口径**：near-pay 没有跨商户变更列表，改为「资料待审核（profileStatus=SUBMITTED）的商户 + 最新一版」；同一商户同时有多版待审时只看得到最新一版。near-pay 补列表接口后再换。
16. **退款列表前端分页**：v1 一次最多 200 笔且无总数，更早的只能按状态筛出来；页面说明里写明。near-pay 补 v2 分页列表后再换。
17. **请求日志 / 渠道调用 / 回调的搜索框是精确查**：分别对应 near-pay 的 `requestId` / `bizNo` 参数，不是模糊搜索。
18. **又登记 13 个 `pay:*` 码**（同偏差 10 的理由），累计 34 个。

### P3a（2026-11-20，同一工作树与分支，未提交）

**near-pay 侧**：接口由单独会话在 near-pay 仓库实现，提交 `81d222c0c`（分支 `claude/ops-v2-p3a-426611`，未推送、未开 PR），新增 5 个控制器与 29 条测试，契约 `docs/api/handbook/08-运营端P3a接口落地.md`。
设计里有、本批未实现的（结算刷新 / 回执、出款查询 / 退回复核、提现发起、对账复核、费率发布 / 停用、费率方案、储值冻结与调账）页面不出入口。

| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `ops-web/app/payment-center/{attempts-recon,funds,assets-rates}-tabs.tsx` | 10 个页签：交易流水（查渠道结果）、对账差异（覆盖范围提示 + 认领 / 关闭）、资金流水（凭证分录）、结算记录（含本批出款）、提现申请（通过 / 驳回 / 撤销）、出款记录（登记回执）、储值账户 / 交易（只读）、渠道成本（新建 / 调价＝追加版本，含历史版本）、佣金规则（新建版本） |
| 修改 | `ops-web/lib/pay-ops/{types,api,mock,center-tabs}.ts`、`app/payment-center/{page,copy,shared}.ts(x)` | P3a DTO、HTTP / mock 实现、10 个页签登记、中英文案 +205 条；`useInstanceCurrency` / `amt`：DTO 不带币种的金额用实例币种格式化，取不到时不写币种符号 |
| 修改 | `ops-web/components/ui/{action-host,list-page}.tsx` | **修 P0 缺陷**：页面级操作（新建）曾出现在每一行的操作里；`RowActionButtons` 只出行操作 |
| 修改 | `ops-web/lib/platform-nav.ts`、`platform-menu-aliases.json`、`i18n/nav-*.ts` | 支付中心加 交易流水（交易）与 费率 / 资金结算 / 对账 / 会员资产 四组 |
| 新增 | `backend/shop-app/src/main/resources/db/migration/V685__ops_payment_center_p3a_menu.sql` | 10 个菜单点位；交易流水与支付订单同码，从 `OPS_ORDER__TAB_PAYMENTS` 继承，其余只给超管 |
| 修改 | `backend/shop-base-auth/.../Perms.java`、`ops-web/lib/perm-map.ts`、`schema-test.sql` | 登记 14 个 P3a 码（累计 48 个）；测试库重生成（只多 V685 的 21 行） |
| 测试 | `lib/action-host.test.ts`（+1）、`lib/pay-ops/api.test.ts`（+3）、`lib/payment-center-page.test.ts`（自动 +10）、菜单与动作点期望 | |

验证：`tsc` 无输出；`vitest` 146 个文件 1360 条全过；后端 `FunctionPointPermAlignmentTest` 7/7、`SchemaDriftTest` 6/6；`Perms.java` 单独 `javac` 通过；`check-ops-orphan` 退出 0。
新回归用例先红后绿（页面级操作不进表格行）。
实机（本机 mock，超管）：菜单出现 10 项；对账差异显示「渠道账单尚未接入」提示，认领 → 关闭后离开待处理列表；提现「通过」后离开待审批列表；出款登记回执后变「已付款」；渠道成本详情可见已被取代的旧版本，调价后出现「待生效」新版本；修复后表格行内不再出现「新建」、最低手续费按实例币种显示（NT$）。**未连 near-pay 真接口**。

**偏差说明（P3a）**

19. **交易流水、资金流水各自成为菜单项**：菜单 TDD §2.2 原设计为「支付订单」「资金账户」的页签；资金账户（FI02）还要等 P3c 建表，交易流水需要独立筛选，所以先各占一项。
20. **又登记 14 个 `pay:*` 码**，累计 48 个；只登记不授予，同偏差 10。
21. **near-pay 分支未合并**：本页面按其分支上的契约实现，near-pay 合并、部署后才能联调。


### P4 · 模块独立与商户 / 进件原生化（2026-11-20，同一工作树与分支，未提交）

**模块边界（§2.9）**
- 代码：`lib/pay-ops/*` → `app/payment-center/_api/`，`components/payment/pay-kit.tsx` → `app/payment-center/_kit/`，`center-tabs.ts` → `app/payment-center/module.ts`（模块对外唯一入口：页签登记、`payCenterHref`、`useLegacyPayTabRedirect`）。vitest 加 `app/payment-center/**/*.test.ts`。
- 守卫 `lib/payment-center-module.test.ts`：模块外只 import `module.ts`（`app/dev/` 画廊除外）、模块内不 import 别的业务页、模块内不再出现 `paymentWorkspaceHref` / `shareOpsToken`。三条全绿；第三条在删 `WorkspaceHint` 前是红的。
- 菜单：`V686` 只改 5 个 V671 点位的 href（`/merchants?tab=payment|payment-onboarding|payment-channels`、`/orders?tab=payments|payment-exceptions` → `/payment-center?tab=merchants|onboardings|channels|orders|exceptions`），点位码、权限码、授权不动；`platform-menu-aliases.json`、`platform-nav.ts` 同步；商户页、订单页删支付页签与 `PaymentOpsEntry`，旧书签由 `useLegacyPayTabRedirect` 转发。
- 删除：`components/payment-ops-entry.tsx`、`LEGACY_MENU_HREF`、`NEXT_PUBLIC_PAY_NATIVE_PAGES`、`resolvePayOpsV1Base`、`ops-bridge.ts` 的 `PAYMENT_WORKSPACES` / `paymentWorkspaceHref`（及其测试）；`FunctionPointPermAlignmentTest` 去掉「跨系统工作区」分支，支付码只认支付中心原生页签（读 `module.ts`）。
- 退款改走 shop `/ops/payments/refunds`（`RefundPort`），near-pay v1 客户端删除。线上 v2 实测该接口 200。
- 商户详情「支付服务」块：两个跳 `/oms` 的链接改成进支付中心（商户详情抽屉 / 渠道进件页签），文案同步。

**商户档案（merchant-workspace.tsx）**：新建支付商户 / 测试商户（context 可用才出现）、代填资料（6 字段，敏感 3 项不进草稿）、提交资料审核、收款策略、核验来源关联、一键确认（单人模式）、提交恢复审批、主体资料核对（证件 blob 预览 + 识别比对）、退回指定资料补件；详情加收款能力、渠道、默认策略、来源事件四段。

**渠道进件（onboarding-workspace.tsx + _api/onboarding-draft.ts）**：代商户创建（查商户 → 按市场列可用法币渠道）、平台资料、蓝新 5 段资料（按法律形式 / 代表人类型显隐；敏感值先 `newebpay-materials` 封存再写草稿）、附件（10 槽，上传 / 预览）、证照识别填资料、代商户确认、提交完整版本、提交平台审核批准、退回 / 驳回、退回指定字段、送件（人工填回执号）、登记渠道结果、重试交接、蓝新资料修改（接口 404 时提示不可用）；详情加资料状态与缺项、开户交接进度、账户候选、签约链接、渠道提示（AMR10014 / 回执缺失）。非蓝新渠道保留一次性「提交材料」。原「审核」抽屉的直接通过去掉（通过走审批）。

**验证**
- 前端：tsc 0；vitest 148 文件 1374 条（新增 client 上传 / 取文件 2、P4 映射 4、识别 4、草稿规则 5、模块边界 3、支付服务块链接 1；删除旧工作区链接测试 3）；eslint 无错；界面清单、运营端孤儿、生成文档（另重跑 perm-seed doc/matrix、doc-index、perm-domain-matrix、glossary、ops-ui-spec）全过。
- 后端：`FunctionPointPermAlignmentTest`（6）、`SchemaDriftTest`、`SchemaGeneratorTest`、`OpsPermConfigFlowTest` 通过；`schema-test.sql` 重新生成（含 V686）。
- 浏览器（本机 mock）：菜单 5 项都指向 `/payment-center`；新建商户 → 代填资料（草稿里没有统一编号）→ 提交审核；商户详情四段；主体资料识别比对（单层抽屉）；进件平台资料保存（电话 / 账号不进本地存储、经封存）；蓝新主体段按代表人类型出字段；附件上传。确认 / 提交在缺项时被拒并提示。
- **没验证**：真 near-pay 上的写操作（新建商户、草稿、附件、识别都只在 mock 上跑过）；证照识别接口在本分支的后端不存在（在网关分支 / v2 上）。

**偏差**
22. 「恢复上次操作 / 核查原请求」按钮不做：本模块幂等号由请求内容决定，原样重发即同号去重。
23. 页面顶部「系统切换」里跳 near-pay 运营端的入口保留：near-pay 还有渠道账户、密钥等工作区没进支付中心。

**2026-11-20 发布前调整（用户：「先不执行 V686，v2 前端兼容旧地址」）**：发现 V686 会让共用正式库的 v1 运营端这 5 项失效。V686 移出 Flyway 目录改为 `docs/technical/pending-sql/`；
`app/payment-center/module.ts` 加 `LEGACY_MENU_HREFS` 与 `normalizeLegacyPaymentMenu`，`lib/stores/server-menu.ts` 读菜单时调用（`module.test.ts` 2 条，先红后绿）；
`FunctionPointPermAlignmentTest` 从 `module.ts` 解析同一张映射，库里旧地址的点位也算原生支付页签。前端 1376 条、后端四个测试类 49 条通过。

### P5 · 补齐 near-pay 线上页面的剩余操作（2026-11-20，分支 `feat/ops-pay-center-menu`）

- **比对口径**：near-pay `develop/s1` `fa708146c`，322 个运营端点；本模块原调 133 个；余下 189 个里 99 个连 near-pay 自己的页面也不调；线上版 10 个页面实际调、本模块未调的 35 个落在 §2.11 的表里，全部补上（「恢复上次操作」类 3 个按 §2.11 不移植）。
- **新文件**：`channel-accounts.tsx`（渠道账户抽屉：列表 / 登记 / 认领 / 账户详情 6 页签 / 产品开通 / 密钥录入；门店绑定抽屉）、`channel-accounts-copy.ts`、`channel-accounts.test.ts`。
- **改动**：`_api/types.ts`、`_api/api.ts`（`channelAccounts`、`storeBindings`、`claims` 三个端口；`exceptions.escalations`、`channels.pending/adapters`、`products.paymentMethods`）、`_api/mock.ts`（PM1001 蓝新可收款 + LINE Pay 待核验、PM1003 蓝新仅退款、两条门店绑定、一张认领单）；商户「渠道账户」、门店「渠道绑定」行操作；异常交易页面操作「转人工」；渠道「标为待开通」与适配器下拉；产品支付方式下拉。
- **偏差**：
  1. 渠道启用 / 停用的可见条件改为「不是目标状态」（near-pay 允许从待开通直接启用或停用），原来只认 DISABLED / ENABLED。
  2. 动作码（`ACT__PAY_CHANNEL_ACCOUNT_*`、`ACT__PAY_STORE_BINDING_*`）与 P1–P3 的 `pay:*` 动作码一样**不出迁移**：只进生成器产出（`nav-platform-settings.test.ts` 的支付中心清单），超管不受影响；按岗位授权时再一并落库。
  3. 异常交易页不同步地址栏，「转人工」没有深链接（与该页其它操作一致）。
- **验证**：ops-web 1384 条、packages/shared 731 条（另有 `entity-alignment`、`i18n-backend-enum-keys` 两个套件在本工作树加载即报错，HEAD 原样，非本次）、`tsc` 通过；后端 `FunctionPointPermAlignmentTest`、`OpsEndpointPermTest`、`BizEndpointPermTest`、`BizRoleSeedTest` 通过；mock 浏览器走查：LINE Pay 待核验账户录入密钥一次到可收款（凭据 v1、按钮切到仅退款 / 停用）、门店绑定蓝新账户后出现换绑与解绑、转人工列表、适配器下拉与待开通操作。**未在正式 near-pay 上验证**（写操作要等测试商户与用户确认）。

### P6 · 商户页补缺（2026-11-21，分支 `feat/pay-ops-gateway-passthrough` `1862bac39`）

- 新增 `channel-account-extras.tsx`（`ResumeApprovalPanel`、`CloseUnavailableNote`、`TransfersPanel`、`QualificationsPanel`）与其文案文件；`_api` 加 `transfers`、`qualifications` 两个端口（HTTP + mock）；`SourceEvent` 按 near-pay 实际字段补齐，`sourceEventTitle` / `sourceEventSummary` 出摘要；`perm-map.ts` 加两个转移码。
- 测试：`channel-account-extras.test.ts` 6 例（HTTP 映射、mock 状态机、来源事件摘要）；ops-web 全量 154 文件 1398 例通过；`tsc`、eslint、生成物检查通过。
- 上线：166 `current → releases/gateway-1862bac39`（回退 `gateway-bfb2af8b7`）。线上核对：测试商户藍新账户详情出现「关闭未开放」说明与「转移」「资质」页签；`PM5731C712FF9145B8` 的转移页签列出已执行的 `CTCF1DFB4710594F04`；来源事件显示「AI_SHOP · FIAT｜已生效 · 操作人 ST-NEARA」。
- 未在线上验证：恢复审批面板（线上暂无暂停 / 仅退款的渠道账户）；转移与资质的写操作（未在正式数据上发起）。

### P7 · 门店页补缺（2026-11-21，分支 `feat/pay-ops-gateway-passthrough` `c0650a3e6`、`219fd4b7c`）

- 新增 `store-extras.tsx`（`StoreQrCodes`、`StoreMethods`、`StoreSyncEvents`，`buildMethodSave` / `initialOn` 与 near-pay 同一口径）与文案文件；`_api.stores` 加 6 个方法（建码 `idempotent:false`）；门店详情加三个区块（收款码、支付方式按码显隐）。被挡原因按 near-pay `Item#excludedBy` 真实取值翻译（`MERCHANT`、`BINDING`、`CATALOG`、`STORE_STATE`、`ENTRY`、`STORE`）。
- near-pay `2c5d97cbd`：`OpsQrCodeController.CodeView` 加 `version`，单测断言。
- 测试：`store-extras.test.ts` 5 例；ops-web 全量 155 文件 1403 例通过。
- 上线（UTC）：near-pay `pay-svc.jar` sha256 前缀 `4eb7826e11bfe6a0` = 上一版 `8aa470ca…` 只换 `pay-portal` 内 `OpsQrCodeController` 与 `$CodeView`（逐方法比对仅 `view()` 变化），中断 03:50:21–03:51:12，回退 `pay-svc.jar.before-qrver-20261121`；前端 `current → releases/gateway-219fd4b7c`（回退 `gateway-1862bac39`）。
- 线上核对（Oscar 测试店）：收款码 1 个（NT$5,000 / NT$50,000、启用中、两条链接、出现「停用」= 版本已返回）；支付方式「跟随商户」，LINE Pay 可开关，信用卡 / Apple Pay 因「收款入口未包含该产品」不可改；同步事件 3 条，旧的一条带 `UNLINKED_ENTITY`。
- 未在线上验证：建码、停码、保存支付方式（未在正式门店上写）。

### P6/P7 · Chrome 复核（2026-11-21，用户：「测试验证要通过 chrome 打开」，用户本人的 Chrome 会话，只读）

| 页面 | 结果 |
|---|---|
| 支付渠道 | 17 个渠道；藍新金流一行数据与操作正确 ✅ |
| 商户详情（`PM0E0F6CC8F7454D75`） | 来源事件「AI_SHOP · FIAT｜已生效 · 操作人 ST-NEARA」✅ |
| 渠道账户（`PM2026111707510001` 名下 8 个） | 关闭未开放说明、转移 / 资质页签都在；转移页列出已执行的 `CTCF1DFB4710594F04` ✅；正式账户 `PM-UME-NEWEBPAY-TRL00001898` 的资质 `UME-CARD-TRL00001898:1`（FULL_UAT、生效中、到 2027/11/4、审批号 `APF795FC4C0C8C4EC3`）显示正确 ✅ |
| 测试渠道账户资质 | `PM5731C712FF9145B8`（NEWEBPAY_TEST）、`PM7C90169391874975`（LINEPAY_TEST）near-pay 回 403「资格材料归属冲突」，超管也一样；页面显示错误与重试。**near-pay 侧问题**，待 RobinWu |
| 门店详情（ume 早餐店） | 收款码 1 个（限额、两条链接、停用按钮）；支付方式 5 项均可开关、测试项带标签；同步事件多条 ✅ |
| 角色页「支付中心数据范围」 | `NP_IT_REVIEW`：AE · 1 家商户 · 资金不可见 · 到期 2026/12/9 ✅（UME 两个角色的范围 12/9 到期，需续期） |

### near-pay 修复上线与 Chrome 验证（2026-11-21 12:38 CST）

- near-pay `pay-svc.jar` sha256 前缀 `af785883f4243d50` = 上一版 `4eb7826e…` 换 `pay-domain` 内 `QualificationService`、`ChannelAccountService`、`StorePaymentDisplayService` 与 `pay-portal` 内 `OpsV2`、`OpsMerchantController`、`OpsStoreController`（新增 `$1Hit`）、`OpsChannelAccountController`；中断 04:37:59–04:38:49 UTC；回退 `pay-svc.jar.before-nearpayfix-20261121`。
- 完整测试：pay-channel 610、pay-domain 3706（665 跳过）、pay-guard 128、pay-portal 286 全过；`ChannelAccountDomainIntegrationTest` 过。
- Chrome（用户会话）：商户列表 0.7 s / 0.6 s（原 12.7–20 s）、门店 2.6 s（原 13–17 s）、渠道账户 0.3 s（原 6–17 s），总数不变；按资料状态筛选仍 12.6 s（设计如此）。测试渠道账户资质 200（NEWEBPAY_TEST 5 条、LINEPAY_TEST 4 条，界面正常）；允许操作不再含 `close`。日志：重启后 `rebuild deferred` 0 次，830/834 各记两行「展示为空」后停止。
- 注意：`NEWEBPAY_TEST` 唯一生效的资格 `LYD_MPG_RENEW_20261120:1` 有效期到 2026-11-21 12:44（浏览器时区），到期后该测试账户试运行收款会被拒。

### 前端被覆盖后从 main 重新发布（2026-11-21 14:3x CST）

- **发现**：13:03 另一会话的 retail 发布把 `current` 切到 `retail-tier-b7fe7ee36`，后端换成 `shop-app-4bc6c4cf2.jar`。两个提交都没推送。后端核对：shop-app 自身类、V686 以及 11 个嵌套库与 `58c006c82` 一致，只差 `StoreFeatureMatrix`（retail），支付与网关代码都在。前端核对：有支付中心，但缺 P7（`method-preferences`、`sync-events`）和 IAM 支付范围说明。Chrome 实测门店详情只有「基本信息 / 归属记录」。
- **根因**：支付中心只在功能分支，从别的分支发布就会冲掉。
- **处理**：
  - 最新 main 合入分支（1726ef126）。冲突 4 处：交接文档取 main，3 份生成物重新生成；`check-generated-docs` 27 个生成器全部最新；ops-web `tsc` 通过、vitest 1405 条全过。
  - PR #22 压缩合入 main（`d055d9696`，与 1726ef126 内容一致）。
  - 推送用了 `--no-verify`，用户同意。被跳过的只有 3 条 commerce 测试：`CommerceMigrationIsolationTest.hostHistoryAndLaterHostVersionStayIndependentAcrossRestarts`、`MallFulfillmentMysqlTest.migratedLegacyTask*` ×2。它们在纯 origin/main（fb4ef9a82）上同样失败，来自 d0a4316d8 的 `V14__industry_catalog_tiers.sql`（1062 主键重复，迁移数断言 13≠14），待 RobinWu 修。
- **构建参数**：在原有参数基础上必须加 `NEXT_PUBLIC_NEARPAY_ACCESS_URL=/ops-shop-v2/ops/pay-ops/v2/me/access`，否则顶栏「支付运营」会探测旧的 `/oms-api/...`。retail 版就用了这个参数。和 retail 版比过页面清单与文案，没有 retail 独有的前端源码。
- **发布**：`releases/main-1726ef126`（tgz sha256 前缀 `e78c4ff466c044ea`），只原子切换 `current` 软链，不重启。回退：`ln -sfn …/releases/retail-tier-b7fe7ee36`。
- **Chrome 验证**（用户会话）：

| 页面 | 结果 |
|---|---|
| 门店详情（Oscar Retail QA 1121） | 收款码（暂无，可新建）、支付方式 5 项及不可改原因、同步事件 1 条「已生效」✅ |
| 渠道账户（`PM0E0F6CC8F7454D75` · ALIPAY） | 转移、资质页签都在，空状态和表单正常 ✅ |
| 顶栏权限探测 | `/ops-shop-v2/ops/pay-ops/v2/me/access` 返回 200 ✅ |
| 审核待办 / 支付订单 | 0 条 / 151 条，无报错；支付中心菜单 24 项 ✅ |

- **约定**：支付中心改动先合 main，发布一律从 main 打包；其他会话发布前先合最新 main。
