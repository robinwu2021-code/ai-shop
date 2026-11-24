# TDD-发票-注销与同号重开

状态：**已实现（按 §0 假设）· §0 六条待汎宇 / 财税书面确认** · 档位：2（发票生命周期加新状态、改唯一键、跨 B 端 / 后端 / 供应商层）
关联：[TDD-发票模块](./TDD-发票模块.md)（开立、作废、折让、上传任务）·
[TDD-发票-加值中心模板-鲸跃与汎宇](./TDD-发票-加值中心模板-鲸跃与汎宇.md)（供应商模板与能力接口）·
汎宇《電子發票POS機傳輸規格書 JSON MIG4.1》C0701 / Z11 / ERROR_CODE 表
创建：2026-11-22

---

## L1 定位

> **注销 = 把一张已上传、开错了的发票撤回，再用同一个发票号码重新开一张对的。**
> 作废（F0501）之后号码就废了；注销（F0701）之后号码可以再用。
> 本设计在现有「开立 → 作废 / 折让」之外加一条「注销 → 同号重开」：发票多两个状态，唯一键从「号码唯一」改成「**有效号码唯一**」，
> 重开复用开立主流程，只是不取新号。

只有支持注销的供应商（汎宇）开放此功能；鲸跃、Turnkey、模拟通道没有注销接口，入口不出现。

---

## §0 待确认（决定本设计能否按现在的样子落地）

搜索财政部法规库（电子发票实施作业要点、存证时限表）**没有找到**「注销后以原号码重开」的条文，规格书也只写了接口。下面几条要汎宇或财税顾问书面确认。
**2026-11-22 用户决定按下表「本设计的假设」先实现**；答案不同时只改前置校验与期末处理（见各行「若答案不同」）：

| # | 问题 | 本设计的假设 | 若答案不同 |
|---|---|---|---|
| Q1 | 注销后是否**必须**用原号码重开？还是可以不重开？ | 可重开也可不重开；不重开的号码期末算空白号码（E0402） | 必须重开：期末前未重开要升级为告警，不进空白上报 |
| Q2 | 注销的适用条件：仅 B2C？已列印证明联的能否注销？ | B2C、B2B 都可以；已列印的也可以（由商家线下收回证明联） | 加前置校验：B2B 拒绝 / `print_mark=Y` 拒绝 |
| Q3 | 期限：是否只能在同一期别内注销并重开？ | 同期别内（与作废同一条规则 `voidable`） | 换成税制的另一条规则 |
| Q4 | Z11 注销流程多久结束？「注销尚未完成」能持续多久？ | 分钟到小时级；每 10 分钟查一次，超过 24 小时未结束告警 | 调轮询间隔与告警阈值 |
| Q5 | 重开时能改哪些内容：只改买方 / 载具，还是金额明细也能改？ | 金额明细取来源单当下的事实（与开立相同），买方信息由商家重填 | 只允许改买方：重开时校验金额与原票一致 |
| Q6 | C0701 回 `REPLY=-1「發票尚未上傳至財政部」` 时是否应稍后重试 | 视为可重试（汎宇转财政部是异步的） | 改为终态，人工处理 |

---

## §1 验收标准（以 §0 假设成立为前提）

- **AC1** Given 一张已开立（ISSUED）、无折让、开立上传已送达、仍在开立期别内、供应商支持注销的发票 When 商家提交注销并填原因 Then 发票变「注销中」（REVOKING），入队一条 REVOKE 上传任务（MIG F0701 → 汎宇 C0701）
- **AC2** Given 不满足 AC1 任一前置 When 提交注销 Then 拒绝：状态不对 / 有折让 / 开立未送达 → `INVOICE_STATE_INVALID`；跨期 → `INVOICE_VOID_PERIOD_CLOSED`；供应商不支持 → `INVOICE_STATE_INVALID`
- **AC3** Given 注销任务已送达 When 巡检查注销进度（汎宇 Z11）Then 完成 → 发票变「已注销」（REVOKED），号码与来源单释放；失败 → 发票回到 ISSUED 并记失败原因；进行中 → 不变，下轮再查
- **AC4** Given 一张已注销的发票、仍在同一期别 When 商家重开（可重填买方 / 载具 / 捐赠）Then 生成新发票：**发票号码相同**、随机码新生成、金额明细取来源单当下事实；新票正常入队开立上传；原票记「重开为 → 新票」
- **AC5** Given 注销中的发票 When 作废、折让、再次注销、打印证明联 Then 全部拒绝；已注销的发票不出证明联与明细
- **AC6** 注销与重开的上报期限沿用存证时限（B2C 2 日 / B2B 7 日），进入现有期限巡检
- **AC7** 注销中超过 24 小时未结束、或已注销但期末前 N 天仍未重开，各告警一次

### 不做
- 批次注销（B0701）
- 运营端代商家注销（运营端只读展示状态；处置入口以后按需加）
- 鲸跃的注销（鲸跃文档没有注销接口）

---

## §2 状态与数据

### 2.1 发票状态

| 从 | 动作 | 到 | 条件 |
|---|---|---|---|
| ISSUED | 注销 | **REVOKING** | AC1 前置全部满足 |
| REVOKING | Z11 = 1 完成 | **REVOKED** | 释放 `active_source_key`、`active_fiscal_key` |
| REVOKING | Z11 = 3 失败 | ISSUED | 记 `revoke_error`，可再次注销 |
| REVOKED | 重开 | （原票不变） | 新票 ISSUED，号码相同；原票记 `revised_to_invoice_no` |
| ISSUED | 作废 / 折让 | VOIDED / ISSUED | 不变 |

REVOKING 期间这张票**仍是有效发票**（汎宇未完成前财政部那边仍是开立状态），只是冻结一切动作。

### 2.2 迁移 `V13__invoice_revoke.sql`

```sql
ALTER TABLE ivc_invoice
  ADD COLUMN active_fiscal_key VARCHAR(32) NULL COMMENT '有效号码键：开立 / 注销中 / 作废 = fiscal_number；已注销置空，号码可重开',
  ADD COLUMN revoke_reason VARCHAR(128) NULL,
  ADD COLUMN revoke_requested_at DATETIME(3) NULL,
  ADD COLUMN revoked_at DATETIME(3) NULL,
  ADD COLUMN revoke_error VARCHAR(255) NULL COMMENT '最近一次注销失败原因（汎宇 Z11=3 或上传被拒）',
  ADD COLUMN revised_from_invoice_no VARCHAR(32) NULL COMMENT '本票是哪张注销票的重开',
  ADD COLUMN revised_to_invoice_no VARCHAR(32) NULL COMMENT '本票注销后重开成了哪张';
UPDATE ivc_invoice SET active_fiscal_key = fiscal_number;
ALTER TABLE ivc_invoice
  DROP INDEX uk_ivc_invoice_fiscal,
  ADD UNIQUE KEY uk_ivc_invoice_active_fiscal (tenant_no, regime_code, active_fiscal_key),
  ADD KEY idx_ivc_invoice_fiscal (tenant_no, regime_code, fiscal_number);
```

**为什么用「有效号码键」而不是复用原行**：沿用现有 `active_source_key` 的做法（作废置空、唯一键只约束有效的那一张）。
原票原样保留（证明联快照、上传记录、注销原因都在），重开是新的一行，审计链完整；复用原行会把开错的内容覆盖掉。

**作废的票保留 `active_fiscal_key`**：作废后号码不能再用，唯一键继续挡住。

`ivc_transmission.message_type` 允许 `REVOKE`（列是 VARCHAR，无 CHECK，只改注释）；`ref_no` = 原票 `invoice_no`，唯一键 `(message_type, ref_no)` 天然保证一张票只入队一次注销。

---

## §3 流程

### 3.1 注销（`InvoiceService#revoke`，同一事务）

1. 锁定发票：`status=ISSUED`、`allowed_minor=0`
2. 税制 `voidable(inv, now)`（同期别，Q3）
3. 开立上传 `ISSUE` 任务已 `SENT`（汎宇要求「已上传至财政部」，我们能确认的是已送达加值中心）
4. 开立时冻结的供应商（`ivc_transmission.gateway_code`）有 `RevokeStatusCapability`
5. 条件更新 `ISSUED → REVOKING`，写 `revoke_reason / revoke_requested_at`，清 `revoke_error`
6. 入队 `REVOKE`，报文 = 税制 `message("REVOKE", …)`（MIG F0701：`CancelInvoiceNumber / InvoiceDate / BuyerId / SellerId / CancelDate / CancelTime / CancelReason`，与 F0501 同形）

### 3.2 上传与进度

- 上传任务照常发送；汎宇 C0701 收下 = **受理**，不是完成
- 新增巡检步骤 `InvoiceTransmissionService#checkRevocations`（并入 `InvoiceTransmissionJob`，每 10 分钟）：
  `status=REVOKING` 且 REVOKE 任务已 SENT 的发票 → `RevokeStatusCapability.revokeStatus(account, fiscalNumber, invoiceDate)`（汎宇 Z11）
  - `DONE` → `REVOKED`：`active_source_key=NULL`、`active_fiscal_key=NULL`、`revoked_at=now`
  - `FAILED` → `ISSUED`：`revoke_error` 记原文
  - `PENDING` → 不动；超过 24 小时告警一次（AC7）
- REVOKE 上传被拒（终态）→ 发票回 `ISSUED`，`revoke_error` 记回执，上传任务转待人工

### 3.3 同号重开（`InvoiceService#reissue`）

复用开立主流程 `issue(...)`，加一个「指定号码」模式：

| 步骤 | 普通开立 | 重开 |
|---|---|---|
| 来源单 | 按来源取事实 | 同原票的来源（`active_source_key` 已释放） |
| 买方 | 请求或预存偏好 | 请求里重填；不填沿用原票 |
| 号码 | 锁号段 `lockUsable` → `advance` | **不取号**：用原票 `fiscal_number / range_no / period_code`，靠 `uk_ivc_invoice_active_fiscal` 防并发重开 |
| 期别 | 当期 | 必须仍等于原票期别，否则 `INVOICE_VOID_PERIOD_CLOSED` |
| 随机码 | 新生成 | 新生成 |
| 关联 | — | 新票 `revised_from_invoice_no`；原票 `revised_to_invoice_no` |

### 3.4 期末

- 已注销且到期末仍未重开的号码：按 Q1 假设视为空白号码，**由空白字轨上报一并报出**。号段里它是已用号码（`next_no` 已越过），所以空白上报要额外带上「已注销未重开」的单号（`reportBlankRanges` 加一个来源）
- 期末前 `prefetch-days` 内仍有未重开的已注销票，告警一次（AC7）

---

## §4 契约

| 项 | 内容 |
|---|---|
| 端点 | `POST /biz/invoices/{invoiceNo}/revocation`，体 `{reason}` → `InvoiceVO`；权限 `biz:invoice:void` |
| | `POST /biz/invoices/{invoiceNo}/reissue`，体 `{buyer?}` → `InvoiceVO`（新票）；权限 `biz:invoice:issue` |
| | `InvoiceVO` 加 `revokeReason / revokeRequestedAt / revokedAt / revokeError / revisedFromInvoiceNo / revisedToInvoiceNo / revocable` |
| 状态值 | `ivc_invoice.status` 加 `REVOKING / REVOKED`；b-app `SiStatus`、枚举登记、三语文案 |
| 上传报文 | `ivc_transmission.message_type` 加 `REVOKE` |
| 迁移 | `V13__invoice_revoke.sql`（§2.2） |
| 错误码 | 沿用 `INVOICE_STATE_INVALID`、`INVOICE_VOID_PERIOD_CLOSED`、`INVOICE_PROVIDER_REJECTED` |
| 权限码 | 沿用 `biz:invoice:void`（注销与作废同一类处置）、`biz:invoice:issue`（重开即开立） |
| 配置 | `shop.invoice.revoke.pending-alert-hours`（默认 24） |
| 能力接口 | `RevokeStatusCapability#revokeStatus`（§5）；`ProviderOperation` 加 `REVOKE / REVOKE_STATUS` |

---

## §5 供应商层

```java
/** 注销进度（汎宇 Z11）。注销本身是上传类报文 REVOKE，走 send()。 */
public interface RevokeStatusCapability {
    RevokeState revokeStatus(ProviderAccount account, String fiscalNumber, LocalDate invoiceDate);
    enum RevokeState { DONE, PENDING, FAILED }   // Z11 STATUSCODE 1 / 2 / 3
}
```

汎宇适配器：
- `upload` 加 `REVOKE` → `C0701`（字段同 C0501，`INVOICE_CODE=C0701`）
- 码表：`REVOKE` 的 `0000` 送达；`S038`（已注销成功）、`S042`（已有注销资料）视为送达（重发幂等）；
  `S040`（已注销失败）、`S044`（日期与原发票不符）、`S049`（作废中）终态；`REPLY-1「尚未上傳至財政部」` 按 Q6 定
- `revokeStatus` 走 `call(REVOKE_STATUS, …)`，Z11 报文 `{"INDEX":{FUNCTIONCODE:"Z11", SELLERID, POSID, POSSN, INVOICE_NUMBER, INVOICE_DATE, SYSTIME}}`

鲸跃不实现：上传 `REVOKE` 时基类按「不支持的报文」直接判终态；领域在 §3.1 第 4 步就挡住了，不会走到这里。

---

## §6 影响面

| 现有功能 | 变化 |
|---|---|
| 作废、折让、折让作废 | 只对 ISSUED；REVOKING / REVOKED 拒绝（现有条件已是 `status=ISSUED`，无需改） |
| 打印事实（证明联 / 明细） | REVOKED 不出；REVOKING 不出（防止注销中又打一张） |
| 列表与详情 | 状态筛选加两值；详情显示注销原因、进度、重开关联 |
| 查单对账（鲸跃） | 不受影响（鲸跃无注销） |
| 号码唯一 | 唯一键改为有效号码；同号两行（原票 REVOKED + 新票 ISSUED）是正常数据 |
| 运营端电子发票页 | 只读展示新状态 |
| 期限巡检 | REVOKE 任务进入现有期限计算 |
| 空白字轨上报 | 加「已注销未重开」号码（§3.4） |

---

## §7 测试计划

| AC | 测试 |
|---|---|
| AC1 / AC2 | `InvoiceServiceMysqlTest#revoke_*`：前置逐条（折让、未送达、跨期、不支持的供应商、重复注销） |
| AC3 | `#revocation_progress_done_failed_pending`（假供应商按次序回 PENDING → DONE / FAILED） |
| AC4 | `#reissue_uses_the_same_number_and_links_both_invoices`；并发两次重开只成一张（唯一键） |
| AC5 | `#revoking_and_revoked_invoices_freeze_void_allowance_and_print` |
| AC6 / AC7 | `#revoke_deadline_and_alerts` |
| 迁移 | 存量数据 `active_fiscal_key` 回填、旧唯一键替换后同号作废票仍挡重开 |
| 供应商 | `UniversalEcInvoiceGatewayTest#revoke_posts_c0701` · `#revoke_status_maps_z11` · `#revoke_duplicate_codes_are_accepted` |

---

## §8 风险

| 风险 | 缓解 |
|---|---|
| 法规语义与假设不符（§0） | 确认前不写代码；状态机与唯一键的改法对 Q1–Q5 的不同答案都只需改前置校验与期末处理 |
| 改唯一键是存量表变更 | 迁移先回填再换键；回填后 `active_fiscal_key` 与原 `fiscal_number` 一一对应，新键对存量等价 |
| 注销中长期卡住 | 24 小时告警；运营可看到进度；必要时向汎宇人工确认 |
| 商家注销后忘记重开 | 期末前告警；按 Q1 期末作空白号码上报 |

---

## §9 对账（实现后）

| AC | 测试 | 跑过 |
|---|---|---|
| AC1 / AC2 / AC5 | `InvoiceServiceMysqlTest#revokeChecksPreconditionsFreezesTheInvoiceAndQueuesF0701` · `#revokeIsRejectedWhenTheFrozenGatewayCannotRevoke` | ✅ |
| AC3 / AC4 | `#revokedInvoiceIsReissuedUnderTheSameNumber` · `#failedRevocationReturnsToIssuedAndCanBeRevokedAgain` | ✅ |
| AC4 跨期 / AC7 / 期末空白 | `#revocationAlertsCrossPeriodReissueAndBlankReport` | ✅ |
| 迁移 | `InvoiceServiceMysqlTest` 启动时在 MySQL 9.7 跑 V1–V13（断言 13 个迁移），全部 45 条在新唯一键上通过 | ✅ |
| 供应商 | `UniversalEcInvoiceGatewayTest#revoke_posts_c0701` · `#revoke_duplicate_codes_are_accepted_and_not_uploaded_is_retryable` · `#revoke_status_maps_z11` | ✅ |
| 页面 | b-app / c-app `vue-tsc --noEmit`、ops-web `tsc --noEmit` | ✅ 0 错 |

运行（2026-11-22）：invoice-channel 9 类 52 条、invoice-domain 7 类 63 条全过；`shop-app` 编译通过。
闸门：`gen-ui-catalog`、`check-app-contract`、`check-app-orphan`、`check-i18n-orphan`、`check-rtl-physical`、`check-ops-contract`、`check-enums` 通过；
`check-controller-cohesion`、`check-sql-portability`、`check-app-backend-orphan`、`check-enum-fields` 仍是干净 HEAD 上就有的失败，失败项不涉及发票。

### 改动文件

```
新增 invoice-domain/.../gateway/RevokeStatusCapability.java
修改 invoice-domain/.../gateway/ProviderOperation.java（REVOKE / REVOKE_STATUS）
新增 invoice-domain/src/main/resources/db/invoice/V13__invoice_revoke.sql
修改 invoice-domain/.../entity/IvcInvoice.java、dto/InvoiceDtos.java（RevocationVO、RevokeReq、ReissueReq、InvoiceVO 加 revocable / revocation、状态筛选加两值）
修改 invoice-domain/.../regime/InvoiceRegime.java、regime/tw/TwEguiRegime.java（F0701）
修改 invoice-domain/.../service/InvoiceService.java、impl/InvoiceServiceImpl.java（revoke、reissue、开立「指定号码」模式、有效号码键）
修改 invoice-domain/.../service/InvoiceTransmissionService.java、impl/InvoiceTransmissionServiceImpl.java（注销被拒回退、checkRevocations）
修改 invoice-domain/.../service/impl/InvoiceSetupServiceImpl.java（空白上报加已注销未重开的号码）
修改 invoice-domain/.../job/InvoiceTransmissionJob.java、api/biz/BizInvoiceController.java
修改 invoice-domain/src/test/.../InvoiceServiceMysqlTest.java（迁移数 13、FakeProvider 注销进度、5 条测试）
修改 shop-app/src/test/resources/schema-test.sql
修改 invoice-channel/.../universalec/UniversalEcInvoiceGateway.java、UniversalEcMessages.java、UniversalEcInvoiceGatewayTest.java（+3 条）
修改 b-app/src/api/{sales-invoice.ts,sales-invoice-http.ts,endpoints.ts,mocks/sales-invoice.ts}、pages/sales-invoice/index.vue、pages/sales-invoices/index.vue、i18n/locale/*.ts
修改 c-app/src/api/sales-invoice-types.ts、shared/sales-invoice.ts、i18n/locale/*.ts
修改 ops-web/app/e-invoices/page.tsx（状态筛选）
修改 packages/shared/src/contract/enum-registry.ts
```

### 偏差说明
- 多了两列：`revoke_alerted`（注销超时 / 期末未重开各只提醒一次）、`ivc_invoice.blank_reported_at`（已注销未重开号码的空白上报只报一次）。§2.2 原稿没列，实现时为「同一事实只报一次」补上。
- 进度查询间隔用发票 `updated_at` 判断（每次查询后刷新），没有另加「上次查询时间」列；配置 `shop.invoice.revoke.check-interval-minutes`（默认 10）、`pending-alert-hours`（24）、`reissue-alert-days`（5）。
- 再次注销复用同一条 REVOKE 上传任务（`(message_type, ref_no)` 唯一），重置为待发并换新报文与期限。
- C 端「我的发票」与运营端发票筛选同步加了两个状态（§6 原稿只写了运营端只读展示）。
- 已注销号码的字轨按发票号前两位取（台湾号码格式），这段逻辑只在有空白字轨能力（台湾加值中心）时运行。
