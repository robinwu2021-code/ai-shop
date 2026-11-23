# TDD · ai-shop ⇄ OpsKit 审批对接（对接方案 + 开发计划）

> 状态：**已确认，阶段 1 实施中** · 档位：2（新接入外部服务、新端点、新表、新权限与菜单）· 日期：2026-11-23
> 上位方案：[TDD-审批统一与简化.md](./TDD-审批统一与简化.md)（为什么改、业务清单、分期 S0–S4）· 交界：[交界文档](../handoff/opskit-approval-2026-11-23/交界文档.md)
> 契约真源（OpsKit 仓库 `/Users/neargo2026/work/ai/opskit`，`main`）：`docs/integration/对接契约-NEARGO.md`（下称「契约」）· `docs/openapi/{platform,process,ticket}.json` · `docs/error-keys.json` · 联调交接 `docs/handoff/neargo-joint-test-2026-11-23/README.md`
> 本文覆盖 ai-shop 侧（S0 开通 + S2 运营侧接入 + 页面）。near-pay 侧另由 near-pay 仓库按同一契约落地。

---

## 0. 依据与验收标准

| # | AC（Given / When / Then） | 来源 |
|---|---|---|
| AC1 | Given 运营持 `otk_` 会话，When OpsKit 以该令牌调内省，Then 返回 `active/sub/name/tenant=PLATFORM/roles/perms/scopes/exp`；失效令牌返回 `{"active":false}`；Basic 凭据错返回 401 | 契约 §1 |
| AC2 | Given 运营登录 ai-shop 后台，When 页面经 `/oms-api/opskit/v1/**` 调 OpsKit，Then 请求带原令牌、原样转发，OpsKit 的状态码、错误体、`traceId`、`Idempotent-Replayed` 原样回到页面；未登录在网关被拦 | 契约 §1、§2 |
| AC3 | Given 试点业务启用审批，When 运营「提交」，Then 业务状态进入待审、发件箱投递后 OpsKit 生成审批单，`approval_link` 记下单号；OpsKit 不可用时业务提交不失败，投递退避重试 | 上位 TDD §4 |
| AC4 | Given 允许本人审批，When 提交人在审批中心点「通过」，Then 执行节点触发业务执行，业务生效，审批详情显示「已生效」——两步完成 | 交界 §9-1 |
| AC5 | Given 执行时对象版本已变，Then 执行回复失败（`STALE`），实例进「生效失败」，业务不生效；「重试 / 放弃」可用 | 交界 §9-7、9-8 |
| AC6 | Given 同一事件重投 / 乱序 / near-pay 的事件，Then 业务只变一次 / 丢弃旧的 / 直接 200 | 契约 §4.1 |
| AC7 | Given 运营在流程配置里把试点流程改为 2 步并关闭本人审批，When 重启 ai-shop，Then 模板不被覆盖；新单按新版走、在途单按旧版走完 | 交界 §9-2、9-3 |
| AC8 | 审批中心「待我审批」角标与列表同口径；near-pay 与 ai-shop 的单在同一列表 | 交界 §9-5 |
| AC9 | 有新待办时，候选审批人收到站内消息 | 上位 TDD §5 |

## 1. 对接方案（契约逐项 → ai-shop 落点）

### 1.1 总览

```
ops-web (/ops-web/approvals, /ops-web/approval-flows)
   │ Bearer otk_…            ─▶ nginx /oms-api/opskit/v1/ ─▶ shop-gateway OpsKitPassThrough ─▶ OpsKit :8090 /api/opskit/v1/**
   │                                                                                                  │
   │                         ◀── 内省 POST /internal/opskit/introspect (Basic) ◀───────────────────────┤
业务模块（试点：门店开通）                                                                               │
   └─ ApprovalGateway.submit ─▶ sys_outbox ─▶ OpsKitSubmitConsumer ─▶ ApprovalClient.submit ──────────▶│
                                         ◀── 回调 POST /internal/opskit/webhook ◀──────────────────────┘
                                              └─ ApprovalClient.handleWebhook → 执行动作 / onFinished / task.created 推站内信
```

### 1.2 后端落点

| 契约项 | ai-shop 落点（新增 ★ / 修改 ✎） | 要点 |
|---|---|---|
| §1 内省端点 | ★ `shop-gateway/.../portal/internal/OpsKitIntrospectEndpoint`（`/internal/**` 只允许在此包，`CommonPackageIsolationTest`） | `POST /internal/opskit/introspect`，`consumes=x-www-form-urlencoded`，参数 `token`；控制器内校验 Basic（常量时间比较），配置 `shop.opskit.introspect.client-id/secret`（值 = OpsKit 的 `OPSKIT_NEARGO_INTROSPECT_CLIENT_ID/_SECRET`）。按前缀分流：`otk_` → 复用 `TokenStore.get` + `LiveIdentityResolver` + `OperatorIdentityLoader` 取 `staffNo/realName/roles/perms/StaffScopes`；`btk_` → S3 再做，先返回 `active:false`。`exp` 需另读 `ops_session.expires_at`（`SessionData` 不带过期）。`scopes`：不限定 → `["*"]`，限定 → `merchant:/community:/pickup:`。`perms` 全量不过滤，通配原样返回 |
| §1 透传 | ★ `shop-gateway/.../gateway/ops/OpsKitPassThrough`（照 `PayOpsPassThrough`）· ✎ `OpsGatewayEnd.operatorChain` 加 `ObjectProvider<OpsKitPassThrough>` + `addFilterAfter` · ✎ `ServiceName` 加 `OPSKIT` | `/ops/opskit/v1/**` → `OPSKIT` `/api/opskit/v1/**`；开关 `shop.gateway.ops.opskit-proxy.enabled`（默认关）；目标 `shop.services.targets.OPSKIT=http://192.168.3.80:8090`，Nacos 映射 `shop.nacos.discovery.services.OPSKIT=opskit` |
| §2 响应头 | ✎ `PassThroughInvoker` | `RESPONSE_HEADERS` 加 `traceId`、`Idempotent-Replayed`、`ETag`、`X-Definition-Version`；`onError` 文案「支付服务…」改为按路由传入的服务名 |
| nginx | ✎ `deploy/ume/nearshop/app166.conf`（及 .80 实际配置） | `^~ /oms-api/opskit/v1/` → `127.0.0.1:18081/ops/opskit/v1/`，`Cookie ""`，放在 `/oms-api/` 404 兜底之前 |
| §3.4 client | ✎ `backend/pom.xml` `dependencyManagement` 加 `ai.neargo.opskit:opskit-approval-client:${opskit.version}`；★ `shop-core/.../approval/opskit/OpsKitClientConfiguration` | Spring Boot 4.0.7 / Java 21 / Jackson 3 与 client 一致；依赖收敛按 enforcer 钉版本。`ApprovalClient` 单例：`baseUrl`（同透传目标）、`workspace=ext:PLATFORM`、`credential/signingSecret`（`OPSKIT_APP_AISHOP_CREDENTIAL/_SIGNING_SECRET` 经部署环境变量注入，**不进仓库**）、`subjectPrefixes("shop.")`、`ledger`、各 `action`、`onFinished`。开关 `shop.opskit.enabled` |
| §3.4 ledger | ★ `shop-core/.../approval/opskit/DbEventLedger` | 实现 `EventLedger`：`firstTime` = `sys_event_consumed` 插入（`event_no = eventType + ' ' + idemKey`，`handler = 'opskit'`，唯一键冲突返回 false）；与业务执行同事务，`forget` 为空实现（事务回滚即撤销） |
| B1 送审 | ★ `ApprovalGateway`（业务唯一入口）+ ★ `OpsKitSubmitConsumer`（`OutboxConsumer`，`externalWorkflow=true`，照 `NearPayStoreSyncConsumer`） | 业务事务内：改业务状态 + `approval_link(PENDING_SUBMIT)` + `OutboxEventBus.publish(ApprovalSubmitRequested)`；消费者调 `client.submit`，幂等键 `{subjectType}:{ref}:{revision}`，成功回填 `approval_no/instance_no`。`scopes` 必带 `merchant:{no}`（I2） |
| B2 撤回 | `ApprovalGateway.withdraw` | 对象在审批中被编辑 → 代提交人撤回（执行节点之后返回 403，按已执行处理） |
| B3 作废 | —— | 批 2（C10）才有；之前提交人离职的单由运营驳回 |
| B4 执行回复 | client 自动 | 执行动作返回即 CONFIRM；`ApprovalActionException(code,msg)` 即 REJECT。**按 `ctx.idempotencyKey()` 幂等** |
| B5 对账 | ★ `OpsKitReconcileJob`（每日，Asia/Taipei 03:00） | 扫 `approval_link` 中 PENDING 超 24h 的，`client.reconcile` |
| B6 登记默认流程 | ★ `OpsKitProcessRegistrar`（`ApplicationReadyEvent`） | 对每个已接入业务 `client.ensureProcess(processCode, 默认模板)`：1 个审批步骤 `PERM:<原审批权限码>` + 1 个 `ACTION <subjectType>.apply`，`rules.selfApproval=true`。已存在不覆盖 |
| B7 目录同步 | 不做 | 审批人全用 `PERM`，靠令牌即时开通 |
| §4 回调 | ★ `shop-gateway/.../portal/internal/OpsKitWebhookEndpoint`（`POST /internal/opskit/webhook`） | 读**原始请求体字节**交给 `client.handleWebhook(X-OpsKit-Signature, body)`，按返回的状态码应答。订阅 `instance.*`、`approval.completed`、`task.created` |
| §4 task.created | ★ `OpsKitTodoNotifier` | 给候选审批人 `MessageService.pushTo(RECEIVER_OPS, staffNo, SYSTEM, …, "/approvals?no=…", "OPSKIT:"+taskNo+":"+staffNo)`；候选人按审批步骤的 `PERM` 用 `opsStaffPort.staffNosWithPerm` 求（事件里缺审批人信息，见 §3 K3） |
| §4 onFinished | `ApprovalGateway` 内 | 更新 `approval_link.status`；REJECTED / WITHDRAWN / CANCELLED → 业务回到可编辑；驳回原因从 `approval.completed` 取（`Finished.reason` 为空） |

### 1.3 公共件与表

| 件 | 位置 | 内容 |
|---|---|---|
| SPI `ApprovalSubject` | `shop-base/.../approval/ApprovalSubject`（端口，业务模块实现） | `subjectType()` · `processCode()` · `display(ref)` · `fields(ref)` · `scopes(ref)` · `revision(ref)` · `apply(ref, revision, ActionContext)` · `onClosed(ref, outcome, reason)` |
| `ApprovalGateway` | `shop-core/.../approval/` | `submit(subjectType, ref, operator)` · `withdraw` · `statusOf(subjectType, ref)`；按 `subjectType` 找 `ApprovalSubject` Bean |
| 表 `approval_link` | `V688__approval_link.sql`（提交前查其它分支有无占用） | `id, subject_type, subject_ref, revision, process_code, approval_no, instance_no, status(PENDING_SUBMIT/PENDING/APPLIED/REJECTED/WITHDRAWN/CANCELLED), submitted_by, reason, created_at, updated_at`；唯一键 `(subject_type, subject_ref, revision)` |
| 命名 | —— | 流程代号用下划线 `shop_store_opening`；业务对象类型 / 执行动作用点 `shop.store_opening` / `shop.store_opening.apply`（与 OpsKit 示例 `np_refund` / `np.refund` 一致） |

### 1.4 试点业务：门店经营开通（第一阶段）

| 项 | 现状 | 接入后 |
|---|---|---|
| 代码 | `shop-merchant/.../StoreOperationalReviewServiceImpl`：`submit` → `PENDING_OPEN/PENDING`；`decide(no, approved, reason)` 条件更新，同结果重放安全 | `submit` 照旧改状态，另调 `ApprovalGateway.submit("shop.store_opening", storeNo)`；★ `StoreOpeningApprovalSubject` 的 `apply` = 原 `decide(no, true, …)`；`onClosed(REJECTED)` = `decide(no, false, reason)` |
| 端点 | `OpsStoreController` `/ops/stores/{no}/operational-review/decide` | 启用审批后该端点返回「请到审批中心处理」（开关期间保留，S4 删除） |
| 版本 | 无显式版本 | `revision` = `mch_store.updated_at` 毫秒值；`apply` 前比对，不一致抛 `ApprovalActionException("STALE")` |
| 竞争 | 买套餐自动开通 `activatePaid` | 自动开通时若有在途审批 → `withdraw`（执行节点前）；执行时状态已是 ACTIVE → `apply` 视为成功（幂等） |
| 默认流程 | —— | `PERM:merchant:apply:audit`，`selfApproval=true`，执行节点超时 10 分钟 |

选它的理由：已有显式「提交」步骤、执行是单条带条件的更新、不涉及资金。第二个接入建议「平台活动报名」（执行时占预算，超预算即执行失败，正好验证「生效失败」）。

### 1.5 页面落点（ops-web）

| 契约 | 页面 / 文件 | 要点 |
|---|---|---|
| 客户端 | ★ `ops-web/app/approvals/_api/opskit-client.ts`（照 `payment-center/_api/client.ts` 的 `createPayOpsClient`，不进 `lib/api/https`，不受商城 OpenAPI 闸门约束） | 基址 `NEXT_PUBLIC_OPSKIT_API_BASE`，默认 `/oms-api/opskit/v1/workspaces/ext:PLATFORM`；`Bearer` 取 `useAuth`；`Accept-Language` 随界面语言；错误体 `{error, args, errors[], traceId}` → `OpsKitError` |
| 类型 | ★ `ops-web/app/approvals/_api/types.ts` | 按 `docs/openapi/process.json` 手写用到的类型（仓库无 OpenAPI→TS 先例）；★ 测试把类型字段与仓库内一份 `opskit-openapi.process.json` 快照比对，OpsKit 改契约时同步快照 |
| 错误文案 | ★ `ops-web/app/approvals/_api/error-keys.json`（从 OpsKit 同步）+ `explain(key, args, locale)` | 错误提示与 `mine.whyNot` 共用；`zh-TW` 由 `zh` 经现有 OpenCC 转换 |
| P1 角标 | ✎ 左侧导航「审批」 | `todos/count` 的 `process` |
| P2–P4 审批中心 | ★ `ops-web/app/approvals/page.tsx`（`?tab=`） | 页签：待我审批（P2，`origin: "PROCESS"`）· 我提交的（**以实例为单位**，`instances/query`，见 §3 K1）· 生效失败（`instances/query status: failed_*`）· 全部（P3，需 `approval:view:all`）。复用 `components/ui/list-page`、`paged-table`、`status-badge` |
| P5–P9 详情 | ★ `ops-web/app/approvals/detail/page.tsx`（`?no=`） | 对象卡 + 步骤 + 轮次 + 流转记录（P9）；按钮只看 `mine.canDecide / canWithdraw`，不可用时显示 `whyNot` 文案；通过理由选填、驳回必填；409 `state_changed` 提示刷新；「打开原单」按 `subject.type` 映射到 ai-shop 路由；重试 / 放弃按 `pipeline[].actions`（批 2，之前用 `instances/{no}` 的可走迁移） |
| P10–P15 流程配置 | ★ `ops-web/app/approval-flows/page.tsx` + `detail/page.tsx` | 列表（在途数、版本、启停）· 编辑（审批步骤：名称、审批人 5 种、`mode`、`when`、时限；执行步骤：动作代号只读、超时；规则：本人审批、跨步互斥）· 版本历史 · 停用 / 启用；保存后提示「N 张在途单按旧版本走完」；422 的 `errors[].path` 挂到表单字段。需 `process:manage` |
| P16 当前用户 | `GET /me` | 决定「全部」「流程配置」是否显示 |
| 菜单 | ✎ `lib/nav.ts`、`lib/platform-nav.ts`（`pc('approvals')`、`pc('approval-flows')` 改指新页面）、Flyway 菜单点 | 按 CLAUDE.md 重跑 `python3 scripts/gen-ui-catalog.py` 并提交 `ui-catalog.json` |
| 下线 | ✎ `app/payment-center/approvals-tab.tsx`、「退款复核」重复菜单 | 第二阶段验收通过后删除（near-pay S1 切换后） |

## 2. 开发计划

工作量单位：人日（ai-shop 一人）。前置为空 = 现在就能做。

### 阶段 1 · 后端链路（应用凭证 + 代员工审批，现在可测）

| # | 任务 | 工作量 | 前置 |
|---|---|---|---|
| 1.1 | 引入 client：从 OpsKit 源码 `mvn install` 到本机；`backend/pom.xml` 钉版本、过 enforcer 收敛 | 0.5 | OpsKit 发布到 Nexus（K5）前，CI 无法构建 → 阶段 1 只在本机 / 联调分支 |
| 1.2 | `OpsKitClientConfiguration` + 配置项 + 凭证注入（`.80` 环境变量） | 0.5 | 凭证经安全渠道交付 |
| 1.3 | `approval_link` 表（V688）+ `ApprovalSubject` SPI + `ApprovalGateway` + `DbEventLedger` | 1.5 | —— |
| 1.4 | `OpsKitSubmitConsumer`（发件箱投递）+ `OpsKitProcessRegistrar` | 1 | 1.2 |
| 1.5 | `OpsKitWebhookEndpoint` + onFinished 回写 | 1 | 1.3 |
| 1.6 | 试点：`StoreOpeningApprovalSubject`（apply / STALE / 与自动开通的竞争）+ 开关 | 1.5 | 1.3 |
| 1.7 | 联调脚本：用应用凭证代员工 `decide`（`onBehalfOf{externalRef, perms}`）推进审批；覆盖通过→生效、驳回、撤回、执行失败→重试 / 放弃、超时 | 1 | OpsKit 登记 ai-shop 回调地址 |
| 1.8 | 跑 `WebhookTck` + 对账任务 `OpsKitReconcileJob` | 0.5 | 1.5 |
| | **小计** | **7.5** | |

### 阶段 2 · 内省与页面

| # | 任务 | 工作量 | 前置 |
|---|---|---|---|
| 2.1 | `OpsKitIntrospectEndpoint`（`otk_`；`exp`、`scopes`、`roles`）+ 测试 | 1.5 | 内省 Basic 凭据交付 |
| 2.2 | `OpsKitPassThrough` + `ServiceName.OPSKIT` + `PassThroughInvoker` 响应头 / 文案 + 测试（照 `PayOpsPassThroughTest`、`…OffTest`） | 1 | —— |
| 2.3 | nginx 路由（仓库配置 + .80 / 166 实机） | 0.5 | 运维 |
| 2.4 | ops-web 客户端、类型快照、错误文案 | 1.5 | —— |
| 2.5 | 审批中心（4 个页签 + 角标） | 2.5 | 2.4；K1 |
| 2.6 | 审批详情与通过 / 驳回 / 撤回 / 重试 / 放弃 | 2.5 | 2.4；重试 / 放弃按钮 C9（批 2）前用临时方案 |
| 2.7 | 流程配置（列表 / 编辑 / 版本 / 启停） | 3 | 2.4 |
| 2.8 | 菜单、菜单点迁移、`ui-catalog.json`、i18n 三语 | 1 | 2.5–2.7 |
| 2.9 | `task.created` 站内消息 | 0.5 | K3 |
| | **小计** | **14** | |

### 阶段 3 · 验收（交界文档 §9）

| # | 任务 | 工作量 | 前置 |
|---|---|---|---|
| 3.1 | 逐条跑 §9 的 9 条（第 9 条「商户空间隔离」属 S3，本期跳过）并在本文 §5 贴真实输出 | 1.5 | 阶段 1、2 完成；near-pay 至少一个业务接入（第 5 条「同一列表」） |
| 3.2 | 第二个业务接入：平台活动报名（验证「生效失败」与重试） | 1.5 | 阶段 1 |
| 3.3 | 下线 `approvals-tab` 与重复菜单 | 0.5 | near-pay S1 切换 |
| | **小计** | **3.5** | |

**合计约 25 人日。** 关键路径：凭证与回调地址（OpsKit 侧）→ 阶段 1 → 内省 → 页面。阶段 1 与 2.2、2.4、2.7 可并行。其余 7 类运营侧业务（入驻、店招、报名、商城退款、积分池、积分调账、结算放行、出款账户）按 1.6 的模式各 1–1.5 人日，排在验收之后。

## 3. 需要 OpsKit 配合或答疑

| # | 问题 | 为什么 | 建议 |
|---|---|---|---|
| K1 | `POST /instances/query` 没有「发起人」筛选 | 契约建议「我提交的」以实例为单位展示，但 `InstanceQuery` 只有 `processCode/status/statusCategory/subjectType/subjectRef/open/createdFrom/To`，查不出「我发起的」 | 加 `createdBy: "me" \| memberId`（加法） |
| K2 | 「生效失败」的状态码是按步骤生成的 `failed_{步骤}`，筛选要预先知道步骤代号 | 页面要列出所有生效失败的单 | `instances/query` 支持按状态前缀或新增 `failed: true`；或保证 `statusCategory=WAITING` 只出现在生效失败上 |
| K3 | `task.created` 只有 `taskNo, kind, instanceNo, assignee`，没有候选审批人信息 | 审批人用 `PERM` 时没有 `assignee`，ai-shop 不知道该通知谁 | 事件加 `candidates[]`（主体引用，如 `perm:merchant:apply:audit`）和 `subject` |
| K4 | 执行动作的上下文里拿不到提交时的 `revision` | STALE 判断要比对送审时的版本 | 确认 `ActionContext.fields()` 是否含送审 `fields`；是则 ai-shop 送审时把 `revision` 放进 `fields` |
| K5 | client 未发布到 Maven 私仓 | ai-shop CI 构建不了，阶段 1 只能在本机 | 发布 `opskit-connector`、`opskit-approval-client` 到 `nexus.neargo.ai`，给出版本号 |
| K6 | 是否提前做批 2 | C9 `pipeline`（重试 / 放弃按钮）是页面上线前提；C8 facts 是「看出改了什么」的前提；C10 作废影响离职场景 | **C9 必须在阶段 2 上线前**；C8 建议同批；C10 可以晚 |
| K7 | 内省地址与回调地址 | ai-shop 在 .80 是单容器 `ai-shop-v2` 监听 **18180**（交接文档猜对了） | 内省：`http://127.0.0.1:18180/internal/opskit/introspect`；回调：`http://127.0.0.1:18180/internal/opskit/webhook`（同机）。上线前 ai-shop 以实测为准再确认一次 |
| K8 | 应用凭证代员工 `decide` 的 `onBehalfOf.perms` | 阶段 1 联调脚本要知道带哪些字段才能满足 `PERM` 审批人 | 给一个请求示例写进交接文档 §五 |

### 3.1 OpsKit 回复（2026-11-23，对照 OpsKit `ddffcba`）

| # | 结论 | ai-shop 怎么用 |
|---|---|---|
| K1 | 会做（约 0.5 天，待 OpsKit 侧用户点头）：`InstanceQuery.createdBy = "me" \| memberId` | 「我提交的」页签用它 |
| K2 | 会做：`InstanceQuery.statusPrefix`；状态约定 `exec_*` 执行中、`failed_*` 生效失败、`cancelled` 已放弃、`applied` / `approved` 已生效 / 已通过。**不能**用 `statusCategory=WAITING` 判生效失败（执行中也是 WAITING） | 「生效失败」页签用 `statusPrefix: "failed_"` |
| K3 | 会做：`task.created` 加 `subject`、`candidates[]`（如 `perm:pay:refund:audit` 及其通配展开、`member:`、`group:`、`role:`） | `OpsKitTodoNotifier` 取 `perm:` 候选键，按权限码反查员工推站内信 |
| K4 | 已有：`ActionContext.fields()` 含送审时 `fields` 全部键 | 送审 `fields.revision`，执行前比对判 STALE |
| K5 | 需用户提供 Nexus 发布账号与仓库；届时发正式版 `0.1.0`。之前 CI 可先从 Codeup 拉源码 `mvn -pl approval-client -am install -DskipTests` | 阶段 1 本机 install；CI 方案待定 |
| K6 | 已提给 OpsKit 侧用户：C9 约 1 天、C8 约 1.5 天同批，C10 靠后 | —— |
| K7 | 内省地址已是 `http://127.0.0.1:18180/internal/opskit/introspect`；两端点实测后再加回调 `…/internal/opskit/webhook` | 端点上线后通知 OpsKit |
| K8 | 已实测：① 代员工 `todos/query`（`origin:"PROCESS"`，带 `onBehalfOf{externalRef, displayName, perms}`）取 `taskNo/approvalNo` ② `approvals/{no}/decide`（`{taskNo, decision, reason?, onBehalfOf}`）③ `perms` 要覆盖该步 PERM，可用通配。本人审批按送审与审批的 `externalRef` 是否相同判 | **只用于阶段 1 联调脚本**；业务系统声明的 perms OpsKit 照单全收，阶段 2 起页面审批一律用成员令牌 |

## 4. 用户已定（2026-11-23）

| # | 事项 | 决定 |
|---|---|---|
| U1 | 排期 | **阶段 1 立即开始**（本会话）；阶段 2 的透传、客户端、流程配置并行 |
| U2 | 试点业务 | **门店经营开通**；第二个平台活动报名 |
| U3 | OpsKit 批 2 | **请 OpsKit 把 C9（pipeline）和 C8（facts）都提前** |

## 5. AC → 测试（实现时填真实输出）

| AC | 测试 |
|---|---|
| AC1 | `OpsKitIntrospectEndpointTest`（有效 / 失效 / Basic 错 / 限定范围员工 / 通配权限） |
| AC2 | `OpsKitPassThroughTest`、`OpsKitPassThroughOffTest`、`PassThroughInvokerTest`（新增响应头） |
| AC3 | `ApprovalGatewayTest`（提交写发件箱；OpsKit 不可用时重试） |
| AC4 / AC5 | `StoreOpeningApprovalSubjectTest` + 阶段 1 联调脚本输出 |
| AC6 | `OpsKitWebhookEndpointTest` + `WebhookTck` 输出 |
| AC7 | `OpsKitProcessRegistrarTest` + 联调实测 |
| AC8 / AC9 | 阶段 3 联调实测截图 / 日志 |

## 6. 实现记录

### 6.1 阶段 1（2026-11-23，提交 58cb92cc6）

| 落点 | 文件 | 与 §1 的偏差 |
|---|---|---|
| SPI | `shop-base/.../spi/approval/{ApprovalPort, ApprovalSubject, ApprovalApplyException, ApprovalWebhookPort, ApprovalSubmitRequested}` | 契约放 `spi.approval`（ArchitectureTest：Port 只能在 spi）；多了 `ApprovalWebhookPort`，让网关端点不直接依赖 OpsKit |
| 适配器 | **新模块** `backend/shop-opskit`（`ai.neargo.shop.approval.{opskit,port}`）：`OpsKitProperties` · `OpsKitApprovalConfiguration` · `ApprovalSubjects` · `ApprovalLinkStore` · `DbEventLedger` · `ApprovalOutcomes` · `OpsKitSubmitConsumer` · `OpsKitProcessRegistrar` · `OpsKitReconcileJob` · `port/OpsKitApprovalPort` · `port/OpsKitWebhookPort` | **偏差**：原计划放 `shop-core`。改成独立模块、**只在 Maven profile `opskit` 构建**（根 `pom.xml` 与 `shop-app/pom.xml` 各加一个 profile）——client 未发布到 Nexus，放进常规模块会让 CI 与 pre-push 后端编译闸门拉不到依赖。发布后可并回常规模块 |
| 依赖 | 根 `pom.xml`：`opskit.version` 属性 + `dependencyManagement` 钉 4 个 OpsKit 构件 | 不加 profile 时不解析，默认构建不受影响（已验证） |
| 表 | `shop-app/.../db/migration/V690__approval_link.sql` | **偏差**：V688、V689 已被 `origin/main` 占用，改用 V690 |
| 回调端点 | `shop-gateway/.../portal/internal/OpsKitWebhookEndpoint`（`POST /internal/opskit/webhook`，读原始字节；未启用回 404） | —— |
| 试点 | `shop-merchant/.../merchant/approval/StoreOpeningApprovalSubject`；`StoreOperationalReviewServiceImpl.submit` 首次进入待审时送审（修订 = `mch_store.version`） | 生效 / 回退在回调线程上**绕开数据域**执行（`DataScopeContext.executeWithoutScope`，同套餐履约）；人工 `decide` 端点阶段 1 保留不拦 |
| 域登记 | `ArchitectureTest.DOMAINS` 加 `approval` | —— |
| 联调脚本 | `scripts/opskit/stage1-decide.sh`（应用凭证代员工审批，K8） | —— |
| 未做 | 1.2 凭证注入与 .80 配置、1.7 对真环境联调 —— 等凭证与部署 | —— |

**配置（启用时）**：`mvn -P opskit` 打包，并设置
`shop.opskit.enabled=true` · `shop.opskit.base-url=http://127.0.0.1:8090/api/opskit/v1` · `shop.opskit.credential=${OPSKIT_APP_AISHOP_CREDENTIAL}` ·
`shop.opskit.signing-secret=${OPSKIT_APP_AISHOP_SIGNING_SECRET}` · `shop.opskit.enabled-subjects=shop.store_opening`。对账任务只在 `worker` profile 调度。

### 6.2 测试（真实输出）

```
OpsKitApprovalFlowTest   Tests run: 9, Failures: 0, Errors: 0, Skipped: 0   （mvn -P opskit -pl shop-opskit）
  执行节点生效并回复 CONFIRM · 同一事件重投只执行一次 · 对象版本变了回复 REJECT/STALE · near-pay 的事件直接 200
  驳回关闭关联并回退业务 · 重复结束不重复回退 · 对账补回调且只生效一次 · 默认模板（本人审批 + 执行节点）· 台账键 64 位
StoreOperationalReviewTest Tests run: 7, Failures: 0, Errors: 0, Skipped: 0   （本机 MySQL 9.7 :3308，原 5 条 + 新 2 条）
  送审带当前修订 · STALE 不生效 · 无数据域的回调线程上生效且幂等 · 驳回回到可重新提交、旧修订的驳回被忽略
ArchitectureTest          Tests run: 27, Failures: 0, Errors: 0, Skipped: 1   （-P opskit，含 approval 域）
默认构建（不带 profile）  mvn -pl shop-app -am compile：通过
```

### 6.3 阶段 2（2026-11-23，未提交）

| 落点 | 文件 | 与 §1 的偏差 |
|---|---|---|
| 2.1 内省 | `shop-gateway/.../portal/internal/OpsKitIntrospectEndpoint`（`POST /internal/opskit/introspect`，Basic：`shop.opskit.introspect.client-id/secret`） | **偏差**：`exp` 给「现在 + 5 分钟」而不是会话的 30 天滑动过期 —— 只作 OpsKit 缓存上限（OpsKit 另有 60 秒上限），不用为它多读一次会话表。`btk_` 先回 `active:false`（S3 / C12） |
| 2.2 透传 | `gateway/ops/OpsKitPassThrough`（开关 `shop.gateway.ops.opskit-proxy.enabled`，目标 `shop.services.targets.OPSKIT`）· `OpsGatewayEnd` 挂载 · `ServiceName.OPSKIT` · `PassThroughInvoker` 透传响应头加 `traceId / Idempotent-Replayed / ETag / X-Definition-Version`，下游不可用的文案按服务区分（审批服务 / 支付服务） | —— |
| 2.3 nginx | **不用改**：运营端接口本就经 `/ops-shop/ops/` → 网关 `/ops/`，`/ops/opskit/v1/**` 自然包含在内（`app166.conf` 的 `/oms-api` 是 near-pay 运营端的入口） | **偏差**：原计划在 `/oms-api` 加一段 |
| 2.4 客户端 | `ops-web/app/approvals/_api/{client.ts, types.ts, errors.ts, error-keys.json}`；基址默认 `${NEXT_PUBLIC_API_BASE}/ops/opskit/v1`，可用 `NEXT_PUBLIC_OPSKIT_API_BASE` 覆盖 | 类型按 OpenAPI 手写，未加快照比对测试（错误码表是 OpsKit 原文件拷贝） |
| 2.5 审批中心 | `ops-web/app/approvals/page.tsx`：待我审批（P2，带数量）· 我提交的（`createdBy:"me"`）· 生效失败（`statusPrefix:"failed_"`）· 全部（有 `approval:view:all` 才显示） | —— |
| 2.6 详情 | `ops-web/app/approvals/detail/page.tsx`：申请内容（改前 → 改后）· 流程进度（pipeline）· 审批步骤 · 审批记录 · 流转记录；按钮按 `mine`，不能审批时显示 `whyNot` 文案；重试 / 放弃按 `pipeline[].actions` 走实例迁移；通过不填意见、驳回必填 | —— |
| 2.7 流程配置 | `ops-web/app/approval-flows/{page.tsx, detail/page.tsx}`：列表（版本、在途、启停）· 编辑审批步骤（5 种审批人、ANY/ALL、条件、时限）与执行节点（已登记的动作只读、超时）· 规则 · 版本说明 · 版本历史；422 字段错误标到对应步骤 | —— |
| 2.8 菜单 | **未做，待用户定**：现有「支付中心 → 审批 / 审批流程」显示的是 near-pay BM25 旧审批，还在用；现在替换会让运营看不到在途旧单。新页面先按地址访问（`/approvals`、`/approval-flows`），菜单在 near-pay S1 切换时与下线 `approvals-tab` 一起换（含菜单点迁移与界面清单） | **偏差** |
| 2.9 站内消息 | `spi/notify/OpsAlertPort.approvalTodo`（默认空实现）+ `OpsAlertAdapter` 实现 + `shop-opskit/OpsKitTodoNotifier`；按 `task.created.candidates` 里不含通配的 `perm:` 反查运营员工；三语词条 `approval.todo.title/body` | —— |
| 测试配置 | `ops-web/vitest.config.mts` 的 include 加 `app/approvals/**/*.test.ts` | —— |

**配置（启用时，追加）**：`shop.gateway.ops.opskit-proxy.enabled=true` · `shop.services.targets.OPSKIT=http://127.0.0.1:8090`（或 Nacos `shop.nacos.discovery.services.OPSKIT=opskit`）·
`shop.opskit.introspect.client-id=${OPSKIT_NEARGO_INTROSPECT_CLIENT_ID}` · `shop.opskit.introspect.client-secret=${OPSKIT_NEARGO_INTROSPECT_CLIENT_SECRET}`。

### 6.4 阶段 2 测试（真实输出）

```
OpsKitApprovalFlowTest      Tests run: 10, Failures: 0  （+ 待办只通知持具体权限码的员工）
OpsKitIntrospectEndpointTest Tests run: 3, Failures: 0  （Basic 错 401 · 无效 / 商户 / 不存在员工 active=false · 运营 sub/roles/perms 与 /ops/auth/me 一致）
OpsKitPassThroughTest       Tests run: 3, Failures: 0  （未登录 401 · 令牌与请求体原样、traceId / Idempotent-Replayed 带回 · 403 错误体原样）
PayOpsPassThroughTest 3 · PayOpsPassThroughOffTest 1 · PassThroughInvokerTest 5 · OperatorIdentityEndpointTest 5：全部通过（未受影响）
ops-web client.test.ts      Tests 5 passed            （基址 · 寻址与头 · 错误体 · 实例→审批单 · 错误码表）
ops-web tsc --noEmit         新增文件无错误（全量仅剩既有的 opencc-js 缺包，主工作区同样存在）
gen-ui-catalog.py --check    界面清单是最新的（347 个界面）
```
