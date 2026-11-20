# TDD-支付中心运营页面

状态：P0、P1 已实现并提交（bd35f1058）；P2 已实现（2026-11-20，未提交）；P1/P2 只对 mock 验证，真接口待联调；P3 待 near-pay 补接口
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
- **库表**：无（菜单点位由网关种子维护）。
- **权限码**：用菜单 TDD §2.2 的查看码；各页维护码随页面移植登记（经网关 G2）。
- **i18n**：每页的标题、列名、操作名、状态名、提示三语（简 / 繁 / 英）。
- **配置项**：`NEXT_PUBLIC_PAY_OPS_API_BASE`（默认同源 `/oms-api/pay-ops/v2`，网关就绪后改为网关代理前缀）；
  `NEXT_PUBLIC_PAY_NATIVE_PAGES`（P1 新增，默认关：打开后既有支付菜单项由跳 `/oms/` 改进 `/payment-center`）。
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
| **P3 后端待补 22 项** | 资金结算 7、对账 2、费率 2、风控 4、合规争议 2、会员资产 2、异步任务、交易流水（订单页签）、测试渠道 | 22 | near-pay 先补接口（另立 near-pay 任务，逐域出接口契约）；测试渠道另需 mockpay 与 shop 统一登录；P0 |

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

8. **页签名字不回 nav.ts 取**：支付中心菜单点位由网关落库（菜单 TDD G1/G4），nav.ts 里还没有叶子。页签先自带三语名（`center-tabs.ts`），并用 `PENDING_MENU` 显式列出等网关登记的页签，`nav.test.ts` 守着清单只许变短。
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

