# TDD-发票-汎宇接入

状态：已实现（待真机联调）· 档位：1（在已有发票模块里加一家供应商：新增供应商编码、配置项、端上枚举与文案，不动库表与开立主流程）
关联：[TDD-发票模块](./TDD-发票模块.md) §11/§19/§20（供应商 SPI 与鲸跃接入）· [TDD-发票服务-供应商适配抽象](./TDD-发票服务-供应商适配抽象.md) · [ADR-064](./ADR/ADR-064-发票独立为服务与多地区多供应商.md)
供应商资料：汎宇电商《電子發票POS機傳輸規格書 JSON MIG4.1》（2025-01-01）+《POS API 測試注意事項.xlsx》，存于本机 `~/work/env/ume/invoice/`，不入库
创建：2026-11-22 · 最后更新：2026-11-22

> 鲸跃（Cetustek）与汎宇（einvoice.com.tw）是两家加值中心。本文只加汎宇，**照鲸跃的接法**：`invoice-channel` 下一个厂商包，
> 继承现有形态基类，实现已有能力接口；开立、上传任务、号段、对账流程一行不改。

---

## 验收标准

- **AC1** 汎宇作为供应商 `UNIVERSALEC` 装配后，自动出现在 TW_EGUI 的可选供应商里（B 端发票设置页可选，显示「汎宇」）
- **AC2** 上传任务送 `ISSUE / VOID / ALLOWANCE / ALLOWANCE_VOID` 时，分别以 C0401 / C0501 / D0401N / D0501 报文 POST 到配置地址；
  账号三元 = `SELLERID / POSID / POSSN`，POSSN 只在报文里出现，不进日志与回执
- **AC3** 汎宇回 `ERROR_CODE=0000` 视为送达；重发时回「已存在」（开立 `E02`、作废 `E07`）也视为送达
- **AC4** 账号类错误（`S020` 公司停用、`S026` POS 停用或不存在、`S022/S024` 取不到卖方或门市资料）→ `ACCOUNT_PROBLEM`，上传任务暂停该主体；
  汎宇系统忙（`S010/S012/S030/S032/S034`，或 `REPLY=0` 而没给码）→ `RETRYABLE`；其余及未登记的码 → `FINAL`
- **AC5** 报文按规格书与测试注意事项映射：日期 `yyyy-MM-dd`；B2C 买方 `0000000000` / 名称沿用随机码；混合课税别 C4=9 且每行 B13 带本行课税别；
  纸本 / 载具 / 捐赠三选一；零税率（规格书要求零税率原因与通关方式，税制报文没有）在映射阶段拒绝，不送半对的票
- **AC6** 账号核验（`AccountCapability`）走 Y01：`REPLY=1` 回营业人统编；否则抛 `ProviderException`
- **AC7** 领号段（`RangeCapability`）：所请期别 = 当期走 A01、= 下一期走 C01，回 `INVOICEHEADER / INVOICESTART / INVOICEEND / TYPE`；
  回包期别与所请不符、或 `REPLY≠1`（如「無發票配號可用」）抛 `ProviderException`；其它期别不调用直接抛
- **AC8** 运营端电子发票页把汎宇当作服务商类通道：核验、领号两个动作对 `UNIVERSALEC` 也显示；查单只对鲸跃（汎宇没有查单接口）

### 不做（本期）
- 字轨管理 Z31 查询已下载字轨 / Z32 上传下期字轨 / Z33 查询配号方式：现有能力接口里没有「上传字轨」这一项，需在 `invoice-domain` 加 `TrackCapability`
  并在 B 端 / 运营端加页面，**另起一期**（见 §4 R4）
- C0701 注销、Z11、B0701；B2B 存证与交换（A0401 / A0101 系列）；E0401 / E0402
- 查单：汎宇没有发票状态查询接口，`QueryCapability` 不实现，对账靠回执

---

## §0 对账一 · 需求 → 设计

| AC | 落点 |
|---|---|
| AC1 | `UniversalEcInvoiceGateway`（`@Component`，`code()=UNIVERSALEC`，`profile()` 声明 TW_EGUI / PROVIDER_RANGE / ASYNC_UPLOAD / 要账号）→ `InvoiceGateways#forRegime` 自动收录；b-app `gatewayName.UNIVERSALEC` 三语 |
| AC2 | `UniversalEcInvoiceGateway#upload` + `UniversalEcMessages`（MIG 树 → 汎宇报文）+ `seal`（账号字段写进报文） |
| AC3 / AC4 | `UniversalEcInvoiceGateway.CODES`（`ResultCodeTable`，照规格书 ERROR_CODE 表逐行转写）+ `open`（REPLY 无码时补 `REPLY0` / `REPLY-1`） |
| AC5 | `UniversalEcMessages#issue / voidInvoice / allowance / voidAllowance` |
| AC6 | `UniversalEcInvoiceGateway#verify`（Y01） |
| AC7 | `UniversalEcInvoiceGateway#allocate`（A01 / C01，期别用 `TwPeriods#periodOf` + 注入的 `Clock`） |
| AC8 | `ops-web/app/e-invoices/page.tsx` 的通道判断 |

孤立项：无。

---

## §1 现状与影响面

- 复用（不改）：`AbstractInvoiceGateway`（`send` final：账号核对 → 解析 → `upload` → 异常归类）、`AbstractHttpApiInvoiceGateway`（`seal → transport → open`）、
  `HttpTransport`、`ResultCodeTable`、`ProviderProfile`、`AccountCapability`、`RangeCapability`、`InvoiceGateways`、`InvoiceGatewayPolicy`
- 汎宇形态：**明文 JSON + 账号写在报文体里**，无签名、无整体加密，单一地址按功能码区分。现有形态层（SOAP / 加密 / 签名 / 网络 / 文件）都不对，
  所以直接继承 `AbstractHttpApiInvoiceGateway`，不新增形态基类（只有一家，不为它抽象）
- 会被改到：b-app `SiGatewayCode` 联合类型与 `gatewayName` 三语；`packages/shared` 枚举登记；ops-web 电子发票页的通道判断
- 明确不受影响：库表（`gateway_code` 是 VARCHAR(32)，新码直接可存）、开立 / 上传 / 号段 / 对账服务、鲸跃与 Turnkey 适配器、C 端

---

## §2 方案

### 契约变更
- 端点：无
- 库表：无
- 权限码：无
- i18n：b-app `salesInvoice.gatewayName.UNIVERSALEC`（zh-CN 汎宇 / en Universal EC / ar Universal EC）
- 配置项：`shop.invoice.universalec.url`（默认测试机 `https://epostw.einvoice.com.tw/GetInvoice.ashx`；正式机 `https://eposw.einvoice.com.tw/GetInvoice.ashx`）、
  `shop.invoice.universalec.timeout-seconds`（默认 20）
- 对外枚举：供应商编码新增 `UNIVERSALEC`

### 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `backend/invoice/invoice-channel/src/main/java/ai/neargo/shop/invoice/channel/universalec/UniversalEcInvoiceGateway.java` | 继承 `AbstractHttpApiInvoiceGateway`，实现 `AccountCapability`、`RangeCapability` |
| 新增 | `…/channel/universalec/UniversalEcMessages.java` | MIG 树 → C0401 / C0501 / D0401N / D0501 报文（纯函数，单测逐字段） |
| 新增 | `backend/invoice/invoice-channel/src/test/java/ai/neargo/shop/invoice/channel/universalec/UniversalEcInvoiceGatewayTest.java` | 本机假汎宇（JDK `HttpServer`），照规格书示例回包 |
| 修改 | `b-app/src/api/sales-invoice.ts` | `SiGatewayCode` 加 `UNIVERSALEC` |
| 修改 | `b-app/src/i18n/locale/{zh-CN,en,ar}.ts` | `gatewayName.UNIVERSALEC` |
| 修改 | `packages/shared/src/contract/enum-registry.ts` | `b-app:SiGatewayCode` 词表加 `UNIVERSALEC` |
| 修改 | `ops-web/app/e-invoices/page.tsx` | 通道列表加 `UNIVERSALEC`；核验 / 领号对服务商配号类通道显示，查单仍只对鲸跃 |
| 修改 | `docs/technical/README.md` | 登记本文 |

### 报文映射（MIG 树 → 汎宇）

外层：开立 / 作废 / 折让 / 折让作废用 `{"Invoice":{…}}`，取号 / 核验用 `{"INDEX":{…}}`；回包一律 `{"INDEX":{…}}`。
公共字段：`SYSTIME`（台北时间 `yyyy-MM-dd HH:mm:ss`）、`ACCOUNT` / `APPID` 填 16 个 0。

| 报文 | 关键映射 |
|---|---|
| C0401（ISSUE） | A2←InvoiceNumber；A3←InvoiceDate 转 `yyyy-MM-dd`；A4←InvoiceTime；A5/A6←Buyer；A22←InvoiceType；A24←DonateMark；A25–A27←Carrier；A28←PrintMark；A29←NPOBAN；A30←RandomNumber；B[]←ProductItem（B1/B7 序号、B2 品名、B3 数量、B4 单位、B5 单价、B6 金额、B13 课税别）；C1–C7←Amount；D1←Seller.Identifier（= 账号 SELLERID）、D2←POSSN、D3←POSID、D4←SYSTIME |
| C0501（VOID） | INVOICE_NUMBER / INVOICE_DATE / BUYERID / SELLERID / CANCEL_DATE / CANCEL_TIME / CANCEL_REASON |
| D0401N（ALLOWANCE） | ALLOWANCENUMBER / ALLOWANCEDATE / IDENTIFIER / NAME / ALLOWANCETYPE=2 / TAXAMOUNT / TOTALAMOUNT / PRODUCTITEM[]（原发票日期、号码、品名、数量、单价、金额、税额、序号、课税别）。用 D0401N 而不是 D0401：D0401 的「原明细排列序号」必填，税制的折让报文没有这一项，不编造 |
| D0501（ALLOWANCE_VOID） | 同 C0501 字段，INVOICE_NUMBER 填折让单号，ALLOWANCETYPE=2 |

**B2B 明细用未税（2026-11-22 补）**：打统编时应税明细的单价 / 金额送未税 —— 依据汎宇《POS API 測試注意事項》
（有统编：75 → 71，批注「F0401才用未稅」）。逐行 ÷1.05 四舍五入，尾差放在最后一条应税明细，使合计等于主档应税销售额；
免税、零税率明细与 B2C 不变。改在税制 `TwEguiRegime#message`，鲸跃、Turnkey 的 B2B 报文同样变为未税明细。
测试：`InvoiceServiceMysqlTest#b2bTaxableLinesAreSentUntaxedAndAddUpToTheSalesAmount`。

### 结果码表（`ERROR_CODE`）

| 分类 | 码 |
|---|---|
| 送达 | 通用 `0000`；开立 `E02`（已存在）；作废 `E07`（已存在） |
| 账号问题 | `S020` `S022` `S024` `S026` |
| 可重试 | `S010` `S012` `S030` `S032` `S034`、`REPLY0`（REPLY=0 未给码） |
| 终态 | 其余全部（`E002` `E003` `S027`–`S049` `U010`–`U018`、`REPLY-1` …） |

---

## §4 风险与待厂商确认

| 风险 | 影响 | 缓解 |
|---|---|---|
| R1 尚无测试账号，只按规格书 + 假服务验证 | 真机字段差异上线前才暴露 | 拿到账号后对测试机各走一遍（开立、作废、折让、折让作废、核验、领号），结果补进 §5 |
| R2 A01 / C01 每调一次是否新配一段 | 自动领号可能多占号 | 领号只在水位不足时触发（现有逻辑）；号段入库按「同一段只入一次」去重；向汎宇确认 |
| R3 汎宇回 `QRCodeASKey`（A01 / C01 / Z21），疑为证明联 QR 的 AES 金钥 | 证明联 QR 需要它 | 本期不落；确认语义后经 `AccountCapability#verify` 的 `qrKey` 回传入密钥模块（同鲸跃 §11 做法） |
| R4 字轨上传 Z32 / 查询 Z31 / Z33 本期不做 | 自动配号的商家不受影响；手动配号的商家需在汎宇后台上传下期字轨 | 下一期加 `TrackCapability` 与页面 |
| R5 汎宇是否只放行台湾出口 | 本机直连失败 | 先按可直连配置；若受限，照鲸跃走 UME166 隧道 |

---

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试方法 | 跑过 |
|---|---|---|
| AC1 | `UniversalEcInvoiceGatewayTest#declares_tw_provider_range_with_account` | ✅ 2026-11-22 |
| AC2 | `UniversalEcInvoiceGatewayTest#issue_posts_c0401_with_account_fields` · `#void_posts_c0501` · `#allowance_posts_d0401n` · `#allowance_void_posts_d0501` · `#receipt_never_carries_possn` | ✅ 2026-11-22 |
| AC3 | `UniversalEcInvoiceGatewayTest#duplicate_issue_e02_is_accepted` · `#duplicate_void_e07_is_accepted` | ✅ 2026-11-22 |
| AC4 | `UniversalEcInvoiceGatewayTest#account_codes_pause_the_entity` · `#busy_codes_are_retryable` · `#unknown_code_is_final` | ✅ 2026-11-22 |
| AC5 | `UniversalEcInvoiceGatewayTest#mixed_tax_keeps_line_tax_types` · `#carrier_and_donation_fields` · `#zero_rated_is_rejected_before_sending` | ✅ 2026-11-22 |
| AC6 | `UniversalEcInvoiceGatewayTest#verify_uses_y01` · `#verify_failure_throws` | ✅ 2026-11-22 |
| AC7 | `UniversalEcInvoiceGatewayTest#allocate_current_period_uses_a01` · `#allocate_next_period_uses_c01` · `#allocate_other_period_or_mismatch_throws` | ✅ 2026-11-22 |
| AC8 | ops-web `tsc --noEmit`（页面只改通道判断）；b-app `vue-tsc --noEmit` | ✅ 两端 0 错 |

运行输出（2026-11-22，`mvn -o -pl invoice/invoice-channel -am test`，扫描面 = invoice-channel 全部测试）：

```
UniversalEcInvoiceGatewayTest  tests=19 failures=0 errors=0
CetustekInvoiceGatewayTest     tests=7  failures=0 errors=0
TurnkeyInvoiceGatewayTest      tests=3  failures=0 errors=0
base.*Test（5 个类）            tests=10 failures=0 errors=0
```

闸门：`check-ops-contract` ✓、`check-app-contract` ✓、`check-i18n-orphan` ✓（用了没有 0 / 有了没人用 0）。
`check-enum-fields` 失败 17 列，均为他域既有表（`inv_paired_transfer`、`mbr_guest_registration`、`mch_booking_hold` …），与本次无关，未改动。

## §6 对账二 · 设计 → 实现

按提交的文件清单对照 §2 模块设计：

```
新增 backend/invoice/invoice-channel/src/main/java/ai/neargo/shop/invoice/channel/universalec/UniversalEcInvoiceGateway.java
新增 backend/invoice/invoice-channel/src/main/java/ai/neargo/shop/invoice/channel/universalec/UniversalEcMessages.java
新增 backend/invoice/invoice-channel/src/test/java/ai/neargo/shop/invoice/channel/universalec/UniversalEcInvoiceGatewayTest.java
修改 b-app/src/api/sales-invoice.ts
修改 b-app/src/i18n/locale/{zh-CN,en,ar}.ts
修改 packages/shared/src/contract/enum-registry.ts
修改 ops-web/app/e-invoices/page.tsx
新增 docs/technical/TDD-发票-汎宇接入.md
修改 docs/technical/README.md
```

| 差异 | 说明 |
|---|---|
| TDD 里没有、实际改了的文件 | 无 |
| TDD 列了、实际没动的文件 | 无 |

### 偏差说明
- 配置项 `shop.invoice.universalec.url / timeout-seconds` 只以 `@Value` 默认值存在（默认测试机），未写进 `application.yml`，与鲸跃一致；上正式前在部署配置里设正式机地址。

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-11-22 | 用户指定：照鲸跃方案放 invoice 模块，在 `codex/food-next01` 同分支设计与开发 |
| 2026-11-22 | 适配器、端上枚举与文案实现；invoice-channel 全部测试、契约与 i18n 闸门通过；待测试账号联调 |
