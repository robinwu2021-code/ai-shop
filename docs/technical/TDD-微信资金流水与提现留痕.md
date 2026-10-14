# TDD-微信资金流水与提现留痕

状态：草稿（待确认）
关联需求：[PRD-商家资金到账与对账](../requirements/PRD-商家资金到账与对账.md) §3.3「对账：四条轴」·
本文 §0.1 临时补 AC（PRD 未覆盖「平台自身账户的资金流水」这条轴）
创建：2026-09-29 · 最后更新：2026-09-29
档位：1（动了库表、端点、权限码、配置项；不是新域，也不是不可逆选型）

---

## §0.0 这份设计的起点：一次查不到源头的提现

2026-09-29 04:47，微信推送了一笔 0.20 元的提现通知（商户号 1117261658，
深圳市虹选科技有限公司，收款账户浦发银行 \*\*\*\*2577），状态「发起提现异常（资金未流出基本账户）」，
失败原因「账户余额不足」。

**排查结论：不是本系统触发的。** 六个方向全部排空：

| 查的地方 | 结果 |
|---|---|
| `ai_shop.stl_withdraw` | 0 行 |
| `pb_core.stl_withdrawal` | 0 行 |
| `stl_channel_message`（全部 25 条） | 全是 `/v3/pay/transactions/jsapi` 下单与 `/pay/callback/WECHAT` 回调 |
| 代码中的微信提现/转账调用 | 无 `merchant-transfer` / `transfer/batches` / `fund/withdraw` |
| 40 个 `@Scheduled` 任务 | 无一涉及提现；凌晨档 cron 是 04:00/04:10/04:15/04:30/04:40，**无 04:47** |
| `sys_audit_log`（9-28 起） | 4 条，均非提现 |
| 应用日志（含 9-15 起的历史 gz） | `withdraw`/「提现」零命中 |

截图三处直接证据指向**微信支付商户平台自身的「自动提现」**：
提现方式「自动提现」、备注 `system`、银行附言 `0929_1117261658`（日期+商户号）。
即商户号配置了自动提现，微信支付侧在 T+1 日切后自动发起，与本系统无关。

**这次查不到源头，本身就是缺口**：平台商户号的资金进出，我方一行记录都没有。
这份 TDD 要补的就是这一条。

---

## §0.1 临时补 AC

PRD-商家资金到账与对账 §3.3 列的四条对账轴（收款 / 分账 / 出款 / 积分池）里，
「出款」指的是**自营应付**（财务网银打款 vs 银行流水），
**不包含「平台商户号自己的余额提现」** —— 这是第五条轴，PRD 没有写。
按规范附录补 AC，不新建 PRD：

- **AC-1** Given 商户号在某日有资金变动，When 拉取任务在次日 10:00 后运行，
  Then 该日 BASIC 账户资金账单的每一行明细都落进 `stl_fund_flow`。
- **AC-2** Given 同一天的账单被重复拉取，When 任务再次运行，
  Then 不产生重复行（按「资金流水单号」幂等）。
- **AC-3** Given 账单行已入库，When 运营在「财务 → 资金流水」查看，
  Then 能看到记账时间、业务名称、业务类型、收支类型、金额、账户结余、
  **资金变更提交申请人**、备注、业务凭证号。
- **AC-4** Given 一笔提现流水已入库，When 运营按「业务名称=提现」筛选，
  Then 列表显示该笔的发起人，并把 `system` 显示为「自动提现」、
  `员工账号@商户号` 显示为该员工、`商户号API` 显示为「接口发起」。
- **AC-5** Given 运营打开资金流水页，Then 页面上必须写明覆盖范围：
  **失败的提现（资金未流出）不在资金账单中，本页查不到**（见 §2.4）。
- **AC-6** Given 商家在 B 端提交提现申请，When 申请成功，
  Then `sys_audit_log` 多一条 `WITHDRAW_APPLY`，含操作人、IP、时间、金额，
  可在运营端 `/iam?tab=audit` 查到。
- **AC-7** Given 某日商户号无资金变动（微信返回 `NO_STATEMENT_EXIST`），
  When 任务运行，Then 记为「当日无账单」正常结束，不告警、不无限重试。

### 不做（Out of Scope）

- **不做提现发起 API。** 普通商户（直连）的 APIv3 目录里**没有任何提现接口**（§2.1 已核）。
- **不做失败提现的抓取。** 技术不可得，见 §2.4。
- **不给 `stl_withdraw` 补 `PAID`/`FAILED` 写入口。** 见 §1.3。
- **不做关闭自动提现的自动化。** 那是微信商户平台的设置，只能人工点，见 §6。

---

## §1 现状与影响面

### 1.1 相关现有模块

| 路径 | 职责 | 本次关系 |
|---|---|---|
| `backend/pay/pay-channel/.../WechatChannelClient.java` | 微信 APIv3 签名与调用 | **复用**（新增一个账单接口调用） |
| `backend/pay/pay-channel/.../WechatApis.java` | API 路径常量 | 修改（加 `FUNDFLOW_BILL`、`BILL_DOWNLOAD`） |
| `backend/shop-app/.../paybridge/ChannelMessageRetentionJob.java` | 渠道报文清理任务 | **范式来源**（`@ConditionalOnProperty` + `@SchedulerLock` + `JobDeclaration`） |
| `backend/shop-core/.../platform/impl/OpsServiceImpl.java#audit` | 审计写入，已自动带 staff/ip/client_type/at | **直接复用**，不改 |
| `ops-web/app/finance/channel-message-tab.tsx` | 渠道报文查看 tab | **范式来源**（新 tab 照它做） |

### 1.2 可直接复用（已在 `docs/technical/reference/` 搜过同名概念）

- **审计基础设施是现成的**：`OpsServiceImpl.audit()` 已经填 `staff_no` / `staff_name` /
  `ip` / `client_type` / `at`，运营端「操作审计日志」页（`/iam?tab=audit`，权限 `iam:audit:read`）
  已在跑。AC-6 只需要在申请处加一行 `auditLogPort.record(...)`，**不需要新建任何表或页面**。
- **微信 APIv3 客户端是现成的**：`WechatApiV3Signer` + `WechatChannelClient` 已在生产跑了
  25 次调用，证书与 APIv3 密钥都已配置。

### 1.3 会被改到的已在跑功能

- **`BizSettleAppServiceImpl#applyWithdraw`** —— 加一行审计。行为不变，只多一条日志。
- **无其他**。资金流水是纯新增的旁路：新表、新任务、新端点、新 tab，
  不碰订单、结算、分账、退款的任何一条现有路径。

### 1.4 明确不受影响的

`stl_withdraw` 的状态机、`stl_settle*` 的结算链路、支付回调、分账、退款 —— 一行都不动。

### 1.5 ⚠️ 必须先解决的冲突：PRD 说不做的事，代码已经做了

**PRD-商家资金到账与对账 §4 第一条白纸黑字写着：**

> **不给 `stl_withdraw` 补商家申请入口。** 理由见 §2。

理由是 ADR-002 的合规判断：「商家申请提现 → 平台审批 → 平台打款」属于**二次清算**，
无支付牌照不可做。

**而代码里这个入口已经建好并且接通了**（commit `fe1e30669`）：

| 层 | 位置 | 状态 |
|---|---|---|
| 后端端点 | `BizSettleController:197` `POST /biz/settle/withdraw` | 已接通，`@PreAuthorize` 判 `biz:finance` |
| 业务实现 | `WithdrawServiceImpl#apply` | 完整实现：金额下限、可提余额、唯一在途单三道校验 |
| 端上页面 | `b-app/src/pages/withdraw/index.vue`（`pages.json:517`） | 已存在 |
| 端点登记 | `b-app/src/api/endpoints.ts:214,222` | 已登记 |

Controller 的注释还写着「**这条与下面的申请一起，是提现功能的第一次落地**」——
与 PRD §4 的结论正相反。

**这不是文档过时，是代码与一条合规决定冲突。** 按规范「先改文档，再改代码」，
但这里要改的不是文档措辞 —— 需要先定性，三条路选一条：

| 选项 | 含义 | 代价 |
|---|---|---|
| **A. 撤掉入口**（PRD 原意） | 下线 `POST /biz/settle/withdraw` 与 b-app 提现页 | 已写的代码作废；但**生产 0 行，无存量数据要迁** |
| **B. 保留并更新 PRD** | 承认它是过渡账本的正式入口，PRD §4 第一条改写，并记录合规口径 | 需要法务/商务给一句书面结论 |
| **C. 维持现状** | 文档与代码继续对不上 | 下一个人照 PRD 做会撞车；且这条冲突会在每次资金评审时重新吵一遍 |

**本 TDD 的 AC-6（补审计埋点）在 A / B 下都成立**：
入口只要还在，它就该留痕；即使走 A 要下线，在下线之前也该有痕迹。
所以 AC-6 可以先做，不必等这个决定。**但 §1.5 这个决定本身必须有人拍板**，
它不属于技术选型（见 §6）。

---

## §2 方案

### 2.1 前置事实：普通商户能拿到什么，拿不到什么

核对了微信支付商户文档中心（普通商户）2026-09-23 版的完整 API 目录，结论如下。

**能用（普通商户）：**

| 接口 | 路径 | 用途 |
|---|---|---|
| 申请资金账单 | `GET /v3/bill/fundflowbill?bill_date=&account_type=BASIC` | 拿下载地址 |
| 下载账单 | `GET {download_url}` | 取账单文件（CSV），5 分钟内有效 |

账单说明（官方原文要点）：当日账单次日 09:00 开始生成，**建议次日 10:00 后获取**；
仅支持三个月内；金额单位是**元**；每项数据前有反引号 `` ` `` 防 Excel 科学计数法；
商户号维度限频 3 QPS。

**不能用（服务商/收付通专用，我方是直连普通商户）：**

| 接口 | 支持商户 | 结论 |
|---|---|---|
| 按日下载提现异常文件 `GET /v3/merchant/fund/withdraw/bill-type/NO_SUCC` | **【平台商户】** | ❌ 调用会返回 403 `NO_AUTH` |
| 平台/二级商户预约提现 `POST /v3/merchant/fund/withdraw` | 【平台商户】 | ❌ |
| 查询预约提现状态 | 【平台商户】 | ❌ |

**普通商户的 APIv3 目录里没有任何提现类接口** —— 有下载账单、分账、商家转账、
退款、支付分、代金券，唯独没有余额提现、提现记录查询、提现结果通知。
对我方而言，提现只能在商户平台操作或由自动提现执行，**API 层面唯一的观测窗口就是资金账单**。

### 2.2 核心：资金账单里那一列就是答案

资金账单明细共 11 个字段：

```
记账时间 · 微信支付业务单号 · 资金流水单号 · 业务名称 · 业务类型 ·
收支类型 · 收支金额(元) · 账户结余(元) · 资金变更提交申请人 · 备注 · 业务凭证号
```

其中 **「资金变更提交申请人」= 资金变更操作的发起人**，取值形如：

| 取值 | 含义 |
|---|---|
| `system` | 微信支付系统发起 —— **自动提现就是这一类**（与截图备注 `system` 吻合） |
| `员工账号@商户号` | 商户平台某个员工手动发起 —— **这就是「是谁触发的」** |
| `商户号API` | 通过 API 发起 |

把这一列存下来，「谁触发的提现」这个问题以后就有地方查了。
「业务名称」中有 `充值/提现` 分类，用于筛出提现类流水。

### 2.3 契约变更

**端点**（1 个新增，运营端）
```
GET /ops/finance/fund-flows   权限 finance:recon:read
  query: billDate(yyyy-MM-dd) | from,to | bizName | applicant | pageNo,pageSize
  resp : PageData<FundFlowVO>
```
复用既有的 `finance:recon:read`（对账读），不新增权限码 ——
资金流水就是对账的一条轴，没有「只看流水不看对账」的岗位（与
`finance:withdraw:approve` 刻意不拆读写同一条理由）。

**库表**（新增 1 张，迁移号 `V358`，落库前需再确认未被并行会话占用）
```sql
-- V358__stl_fund_flow.sql
CREATE TABLE IF NOT EXISTS stl_fund_flow
(
    id               BIGINT(20)   NOT NULL AUTO_INCREMENT,
    flow_no          VARCHAR(64)  NOT NULL COMMENT '资金流水单号 —— 幂等键，微信侧唯一',
    bill_date        DATE         NOT NULL COMMENT '账单日期',
    account_type     VARCHAR(16)  NOT NULL DEFAULT 'BASIC' COMMENT 'BASIC/OPERATION/FEES',
    booked_at        DATETIME     NOT NULL COMMENT '记账时间（账单原文）',
    wx_trade_no      VARCHAR(64)  DEFAULT NULL COMMENT '微信支付业务单号',
    voucher_no       VARCHAR(64)  DEFAULT NULL COMMENT '业务凭证号',
    biz_name         VARCHAR(64)  DEFAULT NULL COMMENT '业务名称，如 充值/提现、交易',
    biz_type         VARCHAR(64)  DEFAULT NULL COMMENT '业务类型，如 交易/退款/扣除交易手续费',
    direction        VARCHAR(8)   NOT NULL COMMENT '收支类型：收入/支出',
    amount_minor     BIGINT(20)   NOT NULL COMMENT '收支金额（分）。账单给的是元，入库统一转分',
    balance_minor    BIGINT(20)   NOT NULL COMMENT '账户结余（分）',
    applicant        VARCHAR(128) DEFAULT NULL COMMENT '资金变更提交申请人：system / 员工账号@商户号 / 商户号API。**「是谁触发的」就看这一列**',
    remark           VARCHAR(255) DEFAULT NULL COMMENT '备注（账单原文）',
    tenant_no        VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at       DATETIME     NOT NULL,
    created_by       VARCHAR(64)  DEFAULT NULL,
    updated_at       DATETIME     NOT NULL,
    updated_by       VARCHAR(64)  DEFAULT NULL,
    version          BIGINT(20)   NOT NULL DEFAULT 0,
    deleted          TINYINT(4)   NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_stl_fund_flow (flow_no),
    KEY idx_stl_fund_flow_date (bill_date, booked_at),
    KEY idx_stl_fund_flow_biz (biz_name, bill_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='微信商户号资金流水（账单镜像，只读不改）';
```

金额一律**转分存 BIGINT**，不存元、不用浮点（全站契约）。
`uk_stl_fund_flow(flow_no)` 是 AC-2 幂等的落点：重复拉取走 `INSERT IGNORE`。

**配置项**（3 项，均给缺省值）
```
shop.job.fund-flow-pull.cron = 0 30 10 * * *   # 官方建议次日 10:00 后，取 10:30
shop.pay.fund-flow.account-types = BASIC       # 逗号分隔，将来要 OPERATION/FEES 时加
shop.pay.fund-flow.backfill-days = 3           # 每次回看几天，补微信侧的补录（见 §2.6）
```
⚠️ 配置键用 **kebab-case**，注入一律用 `@ConfigurationProperties` 或
`@Value("${shop.pay.fund-flow.backfill-days}")`；`@Value` 不认驼峰与 kebab 混写，
配了等于没配（见 memory `spring-config-silent-misfires`）。

**i18n 词条**：新增 tab 标题与字段名，三语（`ops-web` 侧走 `copy.ts`，不进后端 i18n 闸门）。
若新增 ErrorCode，必须同时补三语文案（`BackendI18nParityTest` 会拦）。

**权限码**：无新增（复用 `finance:recon:read`）。

### 2.4 ⚠️ 覆盖范围（AC-5，必须写在页面上）

这是本方案最重要的一条边界，**必须在页面上显式写明，不能只写在文档里**：

> 资金账单反映的是**商户微信账户的资金变动**。
> **一笔「发起提现异常、资金未流出基本账户」的失败提现，没有资金变动，因此不会出现在资金账单里。**

也就是说：**2026-09-29 那笔 0.20 元失败提现，做完这套之后依然查不到。**
本方案能覆盖的是**成功的**提现（以及所有其他真实发生的资金进出），
覆盖不了失败的。覆盖失败提现需要「提现异常文件」接口，而那是平台商户专用（§2.1）。

不把这句话写在页面上，「本页没有提现记录」就会被读成「没有人提现」——
而真相可能是「提现失败了，所以不在这里」。PRD §3.3 AC-6 要求的
「每一类对账都要有覆盖范围说明」，指的就是这种情况。

### 2.5 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `backend/shop-app/src/main/resources/db/migration/V358__stl_fund_flow.sql` | 建表 |
| 新增 | `backend/pay/pay-domain/.../pay/entity/StlFundFlow.java` | 实体。**加列必须同步补字段**，否则永远读出 null（`migration-needs-entity-field` 守卫） |
| 修改 | `backend/pay/pay-domain/.../pay/mapper/SettleMappers.java` | 加 `FundFlowMapper` |
| 新增 | `backend/pay/pay-domain/.../pay/service/FundFlowService.java` + `impl/FundFlowServiceImpl.java` | 入库（`INSERT IGNORE` 幂等）与分页查询 |
| 新增 | `backend/pay/pay-channel/.../WechatFundBillClient.java` | 申请账单 → 拿 download_url → 下载 → 解析 CSV |
| 修改 | `backend/pay/pay-channel/.../WechatApis.java` | 加 `FUNDFLOW_BILL = "/v3/bill/fundflowbill"` |
| 新增 | `backend/shop-app/.../paybridge/FundFlowPullJob.java` | 定时拉取。照 `ChannelMessageRetentionJob` 的范式 |
| 新增 | `backend/shop-app/.../portal/ops/pay/OpsFundFlowController.java` | `GET /ops/finance/fund-flows` |
| 新增 | `backend/shop-app/.../payclient/OpsFundFlowAppService.java` + `impl/` | 应用层（Controller 不碰 Mapper，`ArchitectureTest` 会拦） |
| **修改** | `backend/shop-app/.../payclient/impl/BizSettleAppServiceImpl.java` | **AC-6：加审计埋点**（详见 §2.7） |
| 新增 | `ops-web/app/finance/fund-flow-tab.tsx` | 照 `channel-message-tab.tsx` |
| 修改 | `ops-web/app/finance/page.tsx` · `copy.ts` · `ops-web/lib/nav.ts` | 加 tab 与菜单项 |
| 修改 | `ops-web/lib/api.ts`（或 `gen:api` 产物） | 加 `listFundFlows` |

### 2.6 拉取任务的几个判断

- **幂等靠唯一键，不靠「今天跑过没有」**。App 进程活得比一次加载久，
  「拉过没有」这种进程内标记在长跑实例上整段会话都是错的
  （memory `app-process-outlives-one-shot-loads`）。走 `INSERT IGNORE` + `uk(flow_no)`。
- **每次回看 N 天，不只拉昨天**。官方明说「历史数据如果有某一日遗漏，
  系统会补入当天的账单中」，只拉昨天会永久丢掉补录的行。缺省回看 3 天，
  幂等键保证重复拉不会重复入库。
- **`NO_STATEMENT_EXIST` 是正常结果不是错误**（AC-7）：
  当日商户号无资金变动就没有账单。记 INFO，不告警、不重试。
  `STATEMENT_CREATING` 才是「还没生成好」，下次任务再拉。
- **`@SchedulerLock`**：多实例下只跑一次，照 `ChannelMessageRetentionJob`。
- **解析要去掉行首反引号**，且**金额是元要转分**——
  用 `BigDecimal.movePointRight(2)`，不要用 double。
- **不重试成死循环**：单次任务内每个日期最多请求一次，失败留给下一次定时。

### 2.7 AC-6 的实现（B 端申请提现审计）

现状：`BizSettleAppServiceImpl:115` 的注释写着
「具体是店里哪个员工点的按钮由审计日志另行记录」，**但实际一行都没记**。
这是典型的「注释承诺了、代码没做」，且没有任何闸门能发现它。

```java
// BizSettleAppServiceImpl#applyWithdraw，在 withdrawService.apply(...) 成功返回之后
WithdrawVO vo = withdrawService.apply(me, amountMinor, me);
auditLogPort.record("WITHDRAW_APPLY", vo.withdrawNo(),
        "商家 " + me + " 申请提现 " + amountMinor + " 分", true);
return vo;
```

- `critical = true`：这是资金动作。
- **staff / IP / 时间不用自己填** —— `OpsServiceImpl.audit()` 已经从
  `SecurityUtils.currentUser()` 和 web 上下文里取好了（`ip`、`client_type`、`at`）。
- 写在 `apply` **成功之后**：失败的申请不该留下「他申请过」的痕迹，
  那会让审计日志里出现一堆并不存在的提现单号。
- 记 `withdrawNo` 而不是商家号作为 `target`：审计页按 target 能直接跳到那张单。

---

## §3 选型

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| **A. 拉资金账单入库** | 普通商户唯一可用通道；含「申请人」列，直接回答「谁触发的」；同时覆盖所有资金进出，不只提现 | T+1 才有；失败提现拿不到 | ✅ **采用** |
| B. 提现异常文件接口 | 正好覆盖失败提现 | **平台商户专用，我方调用返回 403** | ❌ 不可用 |
| C. 提现结果回调 | 实时 | 普通商户 APIv3 无此回调 | ❌ 不存在 |
| D. 爬商户平台页面 | 能拿到失败记录 | 需要商户平台登录态、无稳定契约、随时会坏；且等于把运营账号交给程序 | ❌ 不采用 |
| E. 什么都不做，出事再人工查商户平台 | 零成本 | 就是今天的状态：一笔提现查了六个方向才排除掉是自己触发的 | ❌ |

A 与 B 不是互斥的，B 只是**对我方不可用**。若将来升级为服务商/收付通模式，B 可以补上，
届时 AC-5 的覆盖范围说明要同步改 —— **这句说明本身也是产物，不能改了能力不改它**。

---

## §4 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| **失败提现依然不可见** | 本次这类事件做完后仍查不到 | §2.4 写在页面上；不靠口头传达 |
| 账单接口限频 3 QPS | 回看多天时被限 | 每个日期串行请求，之间留间隔；单次任务最多 `backfill-days` 个请求 |
| `download_url` 5 分钟过期 | 拿到地址后处理慢会下载失败 | 拿到即下，不入队；失败留给下次定时 |
| 微信调整业务类型取值 | 筛选条件失效 | 官方明说「业务类型会随业务发展调整」。**入库存原文，不做枚举映射**；筛选用 `LIKE`，页面允许自由输入 |
| 迁移号 V358 被并行会话占用 | 本地不报、上生产起不来 | 落库前再查一次 `ls db/migration`；改号后必须 `clean package`（memory `migration-number-collision`） |
| 新表未注册数据域 | 配了「只看某商家」的运营看到全平台 | `DataScopeRegistration` 注册；**平台自身流水无商家锚点**，按 `ANCHOR_WAIVED` 登记并写明「配了数据域的运营会看到空白」 |
| H2 与 MariaDB/MySQL 方言差异 | 测试绿、生产迁移炸 | 迁移要在真库副本上跑一次，不只跑 H2（memory `migration-test-on-scratch-db`） |

---

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试方法 | 消融验证（撤掉实现必须变红） |
|---|---|---|
| AC-1 | `FundFlowPullJobTest#pulls_and_persists_each_detail_row` | 注掉 `insert` → 红 |
| AC-2 | `FundFlowServiceTest#same_flow_no_inserted_twice_yields_one_row` | 去掉 `uk_stl_fund_flow` 或改成 `INSERT` → 红 |
| AC-3 | `OpsFundFlowControllerTest#lists_with_applicant_column` | 从 VO 去掉 `applicant` → 红 |
| AC-4 | `FundFlowServiceTest#filters_withdraw_rows_by_biz_name` | 去掉筛选条件 → 红 |
| AC-5 | `ops-web` vitest：`fund-flow-tab` 渲染出覆盖范围说明文本 | 删掉那段文案 → 红 |
| AC-6 | `BizSettleAppServiceTest#apply_withdraw_writes_audit_log` | 注掉 `auditLogPort.record` → 红 |
| AC-7 | `FundFlowPullJobTest#no_statement_exist_is_not_an_error` | 把 `NO_STATEMENT_EXIST` 改成抛异常 → 红 |

**每一条的消融都要真跑**，不是写在表里就算。
还要注意两件本仓库反复出事的：
1. **`ops-web` 的 vitest 只收 `lib/` 下的用例**，`app/` 下的 `.test.ts` 一条都不跑
   （memory `ops-web-vitest-only-lib`）。AC-5 的测试要放对位置，
   并确认**用例总数涨了**。
2. **测试里的替身别太干净**：解析器的测试要喂**真实账单样例**
   （含行首反引号、含中文业务名、含空的申请人列），
   替身把这些洗掉会盖住真缺陷（memory `verify-on-real-path`）。

### 收尾要跑的闸门（扫描面写清楚）

- `mvn -pl shop-app -am test -Dtest=ArchitectureTest`（扫 `shop-app/src/main/java`）
- `mvn -pl shop-app -am test -Dtest=BackendI18nParityTest`（若新增 ErrorCode）
- `cd packages/shared && npx vitest run` —— **新增 `/ops` 端点的五处登记**
  （`ops-endpoint-exists.test.ts` 的 `KNOWN_GAPS`、`perm-endpoint-map.mjs` 规则表与
  `NEAREST_CODE`、`DataScopeRegistration`、生成物），只跑 ops-web 与后端查不出
  （memory `new-ops-endpoint-checklist`）
- 生成物：`node ops-web/scripts/gen-perm-seed.mjs --doc`（**要带 `--doc`**）、
  `npm --prefix ops-web run gen:api`、`python3 backend/scripts/gen-test-schema.py`、
  `python3 scripts/gen-ui-catalog.py`（运营端加了菜单项）
- `bash .githooks/pre-push </dev/null` 整套跑一遍，不要挑着跑

---

## §6 需要拍板的两件事（不是技术选型）

1. **§1.5 的合规冲突**：`POST /biz/settle/withdraw` 与 PRD §4「不给 stl_withdraw 补商家申请入口」
   直接冲突。选 A（撤入口）/ B（保留并改 PRD）/ C（维持）。
   生产 0 行，**现在改代价最小**。
2. **自动提现关不关**：截图那笔是商户平台的自动提现。
   要改成手动提现，得在微信支付商户平台「账户中心 → 提现设置」里操作 ——
   这是微信侧的人工动作，本系统做不到，也不该做。
   同一处的「操作日志」能看到当初是谁开的自动提现。

---

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-09-29 | 方案草稿；微信 API 能力已按官方文档（2026-09-23 版目录）核过，§2.1 与 §2.4 的边界为实测结论 |
| | 待确认：§6 两条 |
