# TDD · 审批统一：near-pay 与 ai-shop 接入 OpsKit

> 状态：**草稿·方向已定** · 档位：2（跨三仓、换审批服务、新接入契约）· 日期：2026-11-23
> 用户决定：见 §1（2026-11-23 两轮）。
> 审批服务：**OpsKit**（独立仓库 `../opskit`，设计准绳 `opskit/docs/design/21-设计方案-重梳/`，接入指引 `opskit/docs/integration/host-integration.md`）。
> 与 OpsKit 的边界、接口和对方要补的能力见 **[交界文档](../handoff/opskit-approval-2026-11-23/交界文档.md)**。本文只讲两边业务系统怎么改。
> 推翻的旧决定：near-pay BM25 方案确认单（自建审批中心、Q9 手动执行、敏感类型 ≥2 节点）；near-pay `TDD-单人审批开关与确认审核.md`（全局开关）。

---

## 1. 用户决定

| # | 决定（2026-11-23） | 落点 |
|---|---|---|
| U1 | near-pay 和 ai-shop 用同一套审批方案 | 两边都接 OpsKit，删掉各自的审批引擎 |
| U2 | 支持本人提交、本人审批 | 业务登记的默认流程显式写 `selfApproval=true`（OpsKit 产品默认禁止） |
| U3 | 审批统一进审批表 | 审批单、步骤、决定、执行结果都存在 OpsKit；业务表只存 `approval_no` 和业务状态 |
| U4 | 默认一人提交、一人审批 | 每个业务的默认流程只有 1 个审批节点 |
| U5 | 按业务指定审批流 | 一个业务一条流程，在 OpsKit 控制台的「流程配置」里改：审批节点、执行节点、审批人、条件、是否允许本人审批 |
| U6 | 审批用新的审批服务（OpsKit） | 本文 |
| U7 | OpsKit 提供工单和审批的基础能力：业务流程定制、审批管理、业务系统提交审批、**审批节点触发业务执行**；**业务系统封装审批调用，OpsKit 提供 client 封装** | §3 分工、§4 执行节点、§5 业务侧封装 |

## 2. 现状要拆掉的东西

| 系统 | 现有审批实现 | 处置 |
|---|---|---|
| near-pay | BM25 审批中心：`ApprovalFlowService` / `ApprovalTaskService` / `ApprovalPolicyService` / `ApprovalMaintenance` / `ApprovalStore`、`pay_approval*` 三张表、SY01/SY02 页、`InternalApprovalController`（无调用方）、`/merchants/{no}/confirm` 一键特例 | 16 个 `ApprovalSubjectPort` 保留其中的 `snapshot` 和 `execute`，改由 OpsKit 回写驱动；其余全部删除 |
| near-pay | 旧 `ApprovalServiceImpl`（退款，三人互斥）、开放应用申请硬禁自批、提现直审 | 改接 OpsKit |
| ai-shop | 旧 `ApprovalServiceImpl` 副本（退款）及 V8 CHECK | 删除 |
| ai-shop | 13 类业务各自存审批状态（入驻、门店开通、店招、报名、商城退款、积分池、积分调账、结算放行、出款账户、储值活动、品牌变更、手工改价、盘点、餐饮退货） | 改接 OpsKit；业务表的审批状态列先停写，S4 再删 |
| ai-shop ops-web | 支付中心 `approvals-tab.tsx`（浏览器直连 near-pay） | 换成 OpsKit 审批组件 |

**不接入 OpsKit 审批的：**
- 售后：买卖双方之间的协商，按 OpsKit 附录 A 走工单类型，不在本期范围。
- 内容审核队列（商品 / 素材 / 评价…）：以后可用「审批模板 + 队列组」接入，本期不做。
- `stl_withdraw`：已经没有入口，直接删除。

## 3. 分工（2026-11-23 用户定）

| 层 | 负责 | 不负责 |
|---|---|---|
| **OpsKit**（审批与工单的基础能力） | 业务流程定制（每个业务一条流程：审批节点 + 执行节点 + 条件）· 审批管理（审批单、待办、决定、规则、历史、控制台）· **节点触发业务执行**（流程走到执行节点时通知业务去执行，等业务回复结果后再往下走）· **提供 client 封装**（送审、接收节点触发、回复执行结果、对账） | 业务对象、业务数据、业务动作本身 |
| **业务系统**（near-pay、ai-shop） | 用 OpsKit client **封装一层自己的审批调用**（`ApprovalGateway`）：业务代码只调「提交审批」和「注册执行动作」，不直接碰 OpsKit 的 HTTP、签名、令牌 | 审批规则、审批人、流程结构 |

## 4. 一次审批怎么走

**流程形状（每个业务在 OpsKit 里定制，默认如下）：**

```
草稿 ─submit─▶ [审批节点：1 步，PERM:<权限码>，允许本人审批]
                 ├─ 驳回 ─▶ 已驳回（终态）
                 └─ 通过 ─▶ [执行节点：action = <bizType>.apply] ─成功─▶ 已生效
                                                  └─失败─▶ 生效失败 ─重试─▶ 执行节点
```

- **节点可以是审批，也可以是执行。** 执行节点不限于流程末尾：例如「第一步通过后先冻结额度 → 第二步审批 → 正式出款」，就是审批、执行、审批、执行四个节点。
- 执行节点由 OpsKit 的**外部确认门**实现：进入节点 → 发事件 → 业务执行 → 业务回复成功或失败 → 流程继续。

**时序：**

```
业务页「提交」
  └─ ApprovalGateway.submit(bizType, ref)
       业务事务：业务状态 PENDING_APPROVAL + 发件箱「送审」
       └─ 投递 → OpsKit client.start(流程代号, subject, facts, fields, 提交人)
审批人在「审批」收件箱点「通过」（允许本人审批时可以是提交人自己）
  └─ OpsKit 流程进入执行节点 → 事件 instance.awaiting_confirmation{action, token}
       └─ client 收到 → 分发给业务注册的 ApprovalAction(action)
            ├─ 校验对象版本 → 执行 → client.reply(token, CONFIRM)  → 流程到「已生效」
            └─ 执行失败 → client.reply(token, REJECT, 原因)      → 流程到「生效失败」，审批详情和业务页都能看到，可点「重试」
每日对账：client.reconcile 补上丢失的事件
```

| 规则 | 说明 |
|---|---|
| 决定只在一处做 | 通过 / 驳回只在 OpsKit 的审批界面里做。业务页只显示进度和「去审批」。删除各业务自己的 approve / reject / audit / decide 端点 |
| 节点触发执行 | 审批通过就由流程推进到执行节点，不再有手动「执行」。执行结果记在 OpsKit 流程里，审批详情显示「已生效 / 生效失败」 |
| 对象被改了 | 执行动作先比对版本；版本不一致 → 回复失败（原因 `STALE`），提示「按最新内容重新提交」。对象在审批中被编辑时，Gateway 以提交人身份撤回该审批单 |
| 驳回 | 终态。业务回到可编辑状态，修改后重新提交（生成新的审批单） |
| 理由 | 通过时选填，驳回时必填 |

## 5. 业务侧封装（两仓各一份，结构相同；底层用 OpsKit client）

| 件 | 内容 |
|---|---|
| `ApprovalGateway`（业务调用的唯一入口） | `submit(bizType, ref)` · `withdraw(bizType, ref)` · `status(bizType, ref)`。内部：组装 subject / facts / fields / scopes / 提交人 → 写发件箱 → 调 OpsKit client |
| `ApprovalSubject`（每个业务一个，替代 near-pay 的 `ApprovalSubjectPort`） | `bizType` · `processCode` · `subject(ref)`（标题和摘要）· `facts(ref)`（详情显示字段，变更类附带改前值）· `fields(ref)`（流程条件用的字段，如 `amount`）· `scopes(ref)` · `revision(ref)` · `deepLink(ref)` |
| `ApprovalAction`（每个执行节点一个） | `action()`（如 `np.credential_enable.apply`）· `execute(ref, revision)`（幂等），返回成功或失败原因。通常一个业务一个 `apply`；多节点流程可以注册多个 |
| `onRejected / onWithdrawn` | 审批结束但未通过时，业务回到可编辑状态 |
| 接收端点 | `/internal/opskit/webhook`，直接交给 OpsKit client 处理（验签、去重、分发到 `ApprovalAction`、回复结果） |
| `approval_link` 表 | `biz_type, biz_ref, revision, approval_no, status` —— 只用于列表徽标和按业务对象查单。执行状态以 OpsKit 为准，不再在业务侧存 |
| 默认流程登记 | 启动时为每个 `bizType` 登记默认流程（审批节点 1 步 · 审批人 `PERM:<原审批权限码>` · **显式写 `selfApproval=true`**，因为 OpsKit 产品默认禁止 · 执行节点 `<bizType>.apply`），**只在不存在时创建**，不覆盖控制台改过的版本 |
| 事件过滤 | near-pay 与 ai-shop 同在平台空间，会收到对方的事件：`subject.type` 不是本系统前缀的直接回 200（由 client 按注册的 action 自动判断） |

## 6. 业务清单与默认流程

所有流程默认都是：审批节点 1 步、`mode=ANY`、`selfApproval=true`，加一个执行节点 `<代号>.apply`。上线后在 OpsKit 控制台按业务调整。

| 系统 | 业务 | 流程代号 | 默认审批人（PERM） | 空间 |
|---|---|---|---|---|
| near-pay | 凭证启用 | `np.credential_enable` | `pay:credential:approve` | 平台 |
| near-pay | 渠道资质 | `np.channel_qualification` | 原 reviewPermission | 平台 |
| near-pay | 门店关联变更 | `np.store_association` | 同上 | 平台 |
| near-pay | 蓝新商户改件 | `np.newebpay_modify` | 同上 | 平台 |
| near-pay | 商户资料审批 | `np.merchant_profile` | 同上 | 平台 |
| near-pay | 平台商户恢复 | `np.merchant_resume` | 同上 | 平台 |
| near-pay | 进件审核 | `np.onboarding_review` | 同上 | 平台 |
| near-pay | 渠道账户核验 / 恢复 / 换绑 / 转移 | `np.channel_account_{verify,resume,rebind,transfer}` | 同上 | 平台 |
| near-pay | 开放应用启用 / 开放应用申请 | `np.open_app_{enable,application}` | 同上 | 平台 |
| near-pay | 权限映射 / 数据范围 / 告警规则 | `np.{permission_mapping,data_scope,alert_rule}` | 同上 | 平台 |
| near-pay | 关联修复 | `np.source_repair` | `pay:merchant:repair:approve` | 平台 |
| near-pay | 退款复核 | `np.refund_review` | `pay:refund:review` | 平台 |
| near-pay | 提现申请 | `np.withdrawal` | 原提现审批权限 | 平台 |
| ai-shop | 入驻审核 / 门店开通 / 店招 | `shop.{merchant_entry,store_opening,store_sign}` | `merchant:apply:audit` / 同 / `store:page:audit` | 平台 |
| ai-shop | 平台活动报名 | `shop.promotion_enrollment` | `marketing:campaign:update` | 平台 |
| ai-shop | 商城退款 | `shop.mall_refund` | `commerce:refund:review` | 平台 |
| ai-shop | 积分池申请 / 积分调账 | `shop.points_{pool_request,adjustment}` | `finance:payout:execute` | 平台 |
| ai-shop | 结算放行 / 出款账户 | `shop.{settle_release,payout_account}` | `finance:settle:execute` / `finance:payout:execute` | 平台 |
| ai-shop | 储值活动 | `shop.stored_value_activity` | `sv.brand.activity.approve` | 商户 |
| ai-shop | 品牌变更 | `shop.brand_change` | `brand.change.decide` | 商户 |
| ai-shop | 手工改价 / 盘点 / 餐饮退货 | `shop.{manual_price,stock_count,food_return}` | `biz:finance` / 店长 / `biz:aftersale` | 商户 |

**删除的规则：** 敏感类型 ≥2 节点、退款三人互斥、开放应用硬禁自批、全局 `single-operator` 开关。需要更严格的业务，在控制台把 `selfApproval` 关掉或加一步即可。

## 7. 界面

| 端 | 改动 |
|---|---|
| ai-shop ops-web | **ai-shop 自己做全部审批页面**（用户 2026-11-23 定；OpsKit 只提供后端，契约 P1–P16）：一级导航「审批」（角标 = `todos/count` 的 `process`）、审批中心（待我审批 / 我提交的 / 全部）、详情（按 `mine` 显示按钮，`pipeline` 显示链路与重试 / 放弃）。页面经 `shop-gateway` 透传 `/ops/opskit/v1/**` 调 OpsKit。near-pay 和 ai-shop 的审批同在平台空间，**天然是一个列表**。删除支付中心 `approvals-tab`、「退款复核」重复菜单 |
| near-pay ops-web | 删除 SY01 / SY02，只留业务页上的「去审批」跳转到 ai-shop 运营台（Q2 已定）。near-pay 送审时提交人用 ai-shop 运营员工号（Q1 已定） |
| 业务页 | 对象旁显示审批状态徽标（来自 `approval_link`）、「去审批」、`STALE` 时「重新提交」、执行失败时显示原因（重试在审批详情里点，由 OpsKit 重新触发执行节点） |
| b-app | 「我的 → 审批」，用成员令牌调 OpsKit 的 `approvals/query` 和 `decide`（uni-app 不嵌 React 组件，自己画列表和详情） |
| 流程配置 | ai-shop 自己做，放在「平台设置 → 审批流程」：流程列表、编辑审批节点与执行节点、版本历史、停用 / 启用（契约 P10–P15，需 `process:manage`） |

界面落地的期次要按 CLAUDE.md 更新 `ui-catalog.json`。

## 8. 分期

| 期 | 内容 | 依赖 |
|---|---|---|
| **S0 开通** | 部署 OpsKit（只部署一套、支持多账户，Q5 已定）并引导 NEARGO 账户、引导账户与平台空间、声明 `nearpay` 和 `aishop` 两个应用和 webhook 订阅；ai-shop 提供令牌内省端点；目录同步（运营员工 → 成员） | OpsKit 多账户（A0）· A6（或内省端点展开通配） |
| **S1 near-pay** | 公共件落地；16 + 3 个业务接入；SY01/SY02 下线；BM25 在途单**排空**（停新建，等旧单走完或 72h 过期），不迁移（Q4 已定） | S0 |
| **S2 ai-shop 运营侧** | 平台空间 8 类接入；ops-web「审批」入口和组件；删除 `approvals-tab` | S0 |
| **S3 ai-shop 商户侧** | 商户空间开通（按商户 `ext:`），5 类接入，b-app「审批」 | S2；OpsKit 应用权限 `workspaces:create`（A5） |
| **S4 清理** | 两仓删除旧引擎、旧表（先改名备份）、业务表审批状态列、死权限码、`stl_withdraw` | S1–S3 稳定两周 |

每期开工前，在本文补「模块设计 + 文件清单」和「AC → 测试」。
