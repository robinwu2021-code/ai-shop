# TDD-发票模块（多行业 · 首期台湾电子发票与证明联打印）

状态：已实现（后端、证明联打印、B 端页面、自动开立、混合课税、Turnkey 通道；运营端与回执回写见 §7 后续）
档位：2 · 依据：[PRD-发票开立与打印](../requirements/PRD-发票开立与打印.md) §4 ·
决策：[ADR-063](./ADR/ADR-063-发票独立成模块与税制SPI.md)
创建：2026-11-21 · 最后更新：2026-11-21

---

## L1 定位

`invoice` 是独立业务域：**持有发票号码资源（字轨号段），把一张已付清的来源单开成一张合法发票，
负责作废、折让、上传与证明联打印**。行业（零售 / 餐饮 / 美业）只提供「来源单的冻结事实」，
税制（台湾 `TW_EGUI` …）与开票通道（加值中心 / Turnkey）都是可替换的实现。

## L2 结构

![发票模块结构](./diagrams/invoice-module.svg)

| 部分 | 位置 | 职责 |
|---|---|---|
| 跨域契约 | `shop-base` `ai.neargo.shop.spi.invoice` | `InvoicePort`（行业开票入口）、`InvoiceSourcePort`（行业供数） |
| 发票域 | `backend/invoice/invoice-domain` `ai.neargo.shop.invoice` | 号段、开立、作废、折让、上传任务、B 端 API、打印来源 |
| 税制 SPI | `…invoice.regime` / `…invoice.regime.tw` | 买受人校验、号码格式、证明联内容（一维码 / QR）、上传报文 |
| 通道 SPI | `…invoice.gateway`；实现在 `invoice-channel` | 首期 `STUB`，正式加值中心另加实现 |
| 来源实现 | `shop-app` `ai.neargo.shop.invoicebridge.port` | `SubOrderInvoiceSourcePortImpl`：已付核心子单 → 发票来源（零售、餐饮、美业共用） |
| 打印接入 | 发票域 `InvoicePrintSourceFactsPortImpl` + 打印中心场景 `INVOICE_PROOF` | 证明联走共享打印中心；打印中心新增 `barcode` / `qrPair` 版面块 |
| 迁移 | `invoice-domain/src/main/resources/db/invoice`，历史表 `ivc_flyway_history` | 独立序列 V1 起，不与主序列抢号 |

**为什么来源实现只写一个**：餐饮结账（`DiningCheckoutServiceImpl`）、美业服务单与零售下单最终都落成
核心 `ord_sub_order` + `ord_item`，行业码记在 `ord_industry_order_source.source_system`。
所以「已付子单」一个来源就覆盖三行业；行业以后有子单之外的来源（如美业储值卡销售）时，再各加一个
`InvoiceSourcePort` 实现，开票主流程不动。

**表前缀用 `ivc_` 而不是 `inv_`**：`inv_` 已被进销存占用（`inv_stock_balance` 等）。

## §0 对账一 · 需求 → 设计

| AC | 需求（一句话） | 落点 |
|---|---|---|
| AC1 | 号段依序取号、并发不重号 | `ivc_number_range` + `InvoiceServiceImpl#allocate`（`SELECT … FOR UPDATE` 后 `next_no+1`，与发票同事务） |
| AC2 | 号段用尽拒开 | `allocate` 无可用号段 → `INVOICE_NUMBER_EXHAUSTED(26003)` |
| AC3 | 同来源单幂等 | `ivc_invoice.active_source_key` 唯一键；`InvoiceServiceImpl#issue` 先查有效发票 |
| AC4 | 买受人校验 | `TwBuyerRules`（统编检查码、手机条码、自然人凭证、爱心码、互斥）→ `INVOICE_BUYER_INVALID(26004)` |
| AC5 | 金额一致 | `InvoiceSourcePort` 给出明细与总计，`InvoiceServiceImpl` 校验明细合计 = 总计；`TwEguiRegime#amounts` 按 B2C/B2B 拆销售额/税额 |
| AC6 | 证明联内容 | `TwProofCodes`（期别、一维码 19 码、左右 QR、AES 验证码 `TwQrCipher`）→ `ivc_invoice.proof_snapshot`；`InvoicePrintSourceFactsPortImpl` 供打印中心 |
| AC7 | 载具/捐赠不印证明联 | `TwEguiRegime#printMark` → `print_mark=N`，打印来源返回 `BLOCKED` |
| AC8 | 补印标「补印」并留痕 | 打印中心既有 `REPRINT`（`biz:print:reprint`，系统标记含原因与操作人）；发票域不另做 |
| AC9 | 当期作废，可重开 | `InvoiceServiceImpl#voidInvoice`：`status=VOIDED`、清 `active_source_key`、建 `VOID` 上传任务 |
| AC10 | 跨期不能作废 | `TwEguiRegime#voidable` 比较期别 → `INVOICE_VOID_PERIOD_CLOSED(26006)` |
| AC11 | 折让不超剩余 | `InvoiceServiceImpl#allowance` 条件更新 `allowed_minor + ? <= total_minor` → `INVOICE_ALLOWANCE_EXCEEDED(26007)` |
| AC12 | 上传与重试 | `ivc_transmission` + `InvoiceTransmissionServiceImpl#transmitDue`；`InvoiceGateway` SPI；失败退避重试 |
| AC13 | 多行业同一入口 | `InvoiceSourcePort`（按 sourceType 注册）+ `InvoicePort`；发票域不依赖 shop-core 与行业插件（`InvoiceBoundaryTest`） |
| AC14 | 税制可替换 | `InvoiceRegime` SPI + `ivc_regime_profile`；未开通 → `INVOICE_REGIME_NOT_ENABLED(26001)` |
| AC15 | B 端查询 | `GET /biz/invoices`、`GET /biz/invoices/{invoiceNo}` |
| AC16 | 付款后自动开立 | `ivc_regime_profile.auto_issue` + `ivc_buyer_preference` + `PUT /biz/invoices/buyer-preferences`；`InvoicePort#rememberBuyer / issueIfAutomatic`；`shop-app/…/invoicebridge/InvoiceAutoIssueConsumer`（`ORDER_PAID`） |
| AC17 | 混合课税别 | `InvoiceSourcePort.Line.taxType`（取自报价行冻结税类）、`ivc_invoice.zero_tax_sales_minor / free_tax_sales_minor`、`TwEguiRegime#amounts` |
| AC18 | 积分折抵 / 秤重调整 | `SubOrderInvoiceSourcePortImpl` 各成一行 |
| AC19 | Turnkey 通道 | `invoice-channel/…/turnkey/TurnkeyInvoiceGateway`（`shop.invoice.turnkey.root`） |

**孤立项**：无。号段登记、税制开通（`/biz/invoice-setup/*`）挂 AC1、AC14 的前置。

## §1 现状与影响面

- **可复用**：打印中心（`PrintSourceFactsPort`、`BusinessPrintFactReady`、场景 JSON 资源）；
  `BizKey`；`OutboxEventBus`；`SecretVault`（QR 加密密钥）；`ErrorCode` + 三语 properties。
- **会被改到的已在跑功能**（逐个列）：
  - 打印中心编译器 `DefaultPrintDocumentCompiler`：白名单加 `barcode`、`qrPair` 两种块；只对引用它们的模板生效。
  - 版面引擎 `DefaultPrintLayoutEngine`：行里带 `symbol` 时画 `ImageRun`；纯文本行逻辑不变。
  - PDF 渲染器 `PdfReceiptRenderer`：原来遇到 `ImageRun` 抛 `print.imageDrawingUnsupported`，改为画图。
  - `PrintSourceDataScopes`：`INVOICE` → `SOURCE_INVOICE_READ`；`PrintActorFactory` 按 `biz:invoice:read` 授予。
  - `BizPerms.ROLE_PERMS`：店长加 `biz:invoice:read/issue/void/setup`，店员加 `read/issue`。
- **明确不受影响**：`ord_invoice_request`（V94，线上事后申请）、结算发票（V111 / pay）、飞鹅通道
  （仍只收文本，遇图片按既有逻辑拒绝 `print.vendorDrawingUnsupported`，见 §4）。

## §2 方案

### 2.1 契约变更

**端点**（B 端，`@Profile("api")`，门店取 `X-Store-No`，主体取 `BizContext`，入参不接受主体号）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/biz/invoices` | `biz:invoice:issue` | `{sourceType, sourceNo, buyer{taxId,name,carrierType,carrierId,donateCode}}` → `InvoiceVO`；幂等（同来源返回原发票） |
| GET | `/biz/invoices` | `biz:invoice:read` | `?periodCode&status&page&size` 本店分页 |
| GET | `/biz/invoices/{invoiceNo}` | `biz:invoice:read` | 详情含明细、上传状态、折让 |
| POST | `/biz/invoices/{invoiceNo}/void` | `biz:invoice:void` | `{reason}` |
| POST | `/biz/invoices/{invoiceNo}/allowances` | `biz:invoice:void` | `{amountMinor, reason}` |
| GET | `/biz/invoice-setup/profile` | `biz:invoice:setup` | 本主体税制配置 |
| PUT | `/biz/invoice-setup/profile` | `biz:invoice:setup` | `{regimeCode, sellerTaxId, sellerName, gatewayCode(STUB/TURNKEY), qrAesKeyHex?, memberCarrierType?, autoIssue?}` |
| PUT | `/biz/invoices/buyer-preferences` | `biz:invoice:issue` | `{sourceType, sourceNo, buyer}`：付款前预存买受人，自动开立时使用 |
| GET | `/biz/invoice-setup/ranges` | `biz:invoice:setup` | `?periodCode` |
| POST | `/biz/invoice-setup/ranges` | `biz:invoice:setup` | `{periodCode, track, startNo, endNo, storeNo?}` |

证明联打印、补印沿用打印中心 `POST /biz/print/requests`（`sourceRef.type=INVOICE`，`scene=INVOICE_PROOF`）。

**库表**（`db/invoice/V1__invoice_core.sql`，`V2__invoice_role_permissions.sql`）

| 表 | 关键列 | 约束 |
|---|---|---|
| `ivc_regime_profile` | tenant_no, entity_no, regime_code, seller_tax_id, seller_name, gateway_code, qr_key_ref, member_carrier_type, status | uk(tenant, entity, regime) |
| `ivc_number_range` | range_no, tenant/entity, regime_code, store_no(`*`=主体共用), period_code, track, start_no, end_no, next_no, status ACTIVE/EXHAUSTED | uk(tenant, entity, regime, period, track, start_no)；CHECK start≤end |
| `ivc_invoice` | invoice_no, tenant/entity/store, regime_code, industry_code, source_type, source_no, **active_source_key**, fiscal_number, period_code, random_code, issued_at, buyer_type, buyer_tax_id, buyer_name, carrier_type, carrier_id, donate_code, print_mark, currency, sales/zero_tax_sales/free_tax_sales/tax/total_minor, allowed_minor, tax_type, status, void_*, proof_snapshot JSON, proof_snapshot_hash | uk(tenant, fiscal_number, regime)；uk(tenant, entity, active_source_key)；CHECK allowed≤total |
| `ivc_invoice_line` | invoice_no, line_no, source_line_ref, name, quantity, unit, unit_price_minor, amount_minor, tax_type | uk(invoice_no, line_no) |
| `ivc_buyer_preference` | tenant/entity, source_type, source_no, buyer_tax_id, buyer_name, carrier_type, carrier_id, donate_code | uk(tenant, entity, source_type, source_no) |
| `ivc_allowance` | allowance_no, invoice_no, tenant/entity/store, amount_minor, tax_minor, reason | uk(allowance_no) |
| `ivc_transmission` | transmission_no, tenant/entity, regime/gateway_code, message_type ISSUE/VOID/ALLOWANCE, ref_no, payload JSON, status PENDING/SENT/FAILED, attempts, next_attempt_at, last_error, gateway_ref | uk(tenant, message_type, ref_no)；idx(status, next_attempt_at) |

**权限码**：`biz:invoice:read` / `issue` / `void` / `setup`（`BizPerms` + V2 角色种子）。

**错误码**（26xxx · 发票，交易段）：

| 码 | 枚举 | 场景 |
|---|---|---|
| 26001 | INVOICE_REGIME_NOT_ENABLED | 主体未开通发票 |
| 26002 | INVOICE_SOURCE_NOT_READY | 来源单未付清 / 不存在 / 金额组成不支持 |
| 26003 | INVOICE_NUMBER_EXHAUSTED | 本期号码已用完 |
| 26004 | INVOICE_BUYER_INVALID | 买受人信息不合规（统编、载具、爱心码、互斥） |
| 26005 | INVOICE_NOT_FOUND | 发票不存在 |
| 26006 | INVOICE_VOID_PERIOD_CLOSED | 跨期不能作废 |
| 26007 | INVOICE_ALLOWANCE_EXCEEDED | 折让超过剩余可折让 |
| 26008 | INVOICE_STATE_INVALID | 已作废发票再作废 / 折让 |
| 26009 | INVOICE_RANGE_INVALID | 号段格式错误或与已有号段重叠 |

**i18n**：上述 9 条 × 三语（`messages*.properties`）。**配置项**：`shop.invoice.transmission.enabled`（默认 false）、`shop.invoice.turnkey.root` / `shop.invoice.turnkey.path-pattern`（配了才装 Turnkey 通道）、
`shop.invoice.flyway.enabled`（默认 true，与平台迁移同开同关）。

### 2.2 关键接口

```java
// shop-base · ai.neargo.shop.spi.invoice
public interface InvoicePort {                       // 行业结账后开票
    InvoiceReceipt issue(Scope scope, String sourceType, String sourceNo, Buyer buyer);
    Optional<InvoiceReceipt> activeOf(Scope scope, String sourceType, String sourceNo);
}
public interface InvoiceSourcePort {                 // 行业供数，按 sourceType 注册，重复启动失败
    Set<String> sourceTypes();
    InvoiceSource load(Scope scope, String sourceType, String sourceNo);  // 未付清抛 INVOICE_SOURCE_NOT_READY
}
record InvoiceSource(String industryCode, String currency, Instant paidAt, long totalMinor, List<Line> lines)
record Line(String sourceLineRef, String name, BigDecimal quantity, String unit, long unitPriceMinor, long amountMinor)

// invoice-domain · 税制 SPI
public interface InvoiceRegime {
    String code();                                         // TW_EGUI
    String periodOf(Instant at);                           // 11512
    void validateBuyer(Buyer buyer, IvcRegimeProfile p);   // 不合规抛 INVOICE_BUYER_INVALID
    Amounts amounts(long totalMinor, Buyer buyer);         // B2C: 销售额=总计、税额=0；B2B: 拆 5%
    boolean printMark(Buyer buyer);                        // 有载具/捐赠 → false
    String randomCode();
    Map<String,Object> proof(IvcInvoice inv, List<IvcInvoiceLine> lines, IvcRegimeProfile p, byte[] qrKey);
    boolean voidable(IvcInvoice inv, Instant now);
    Map<String,Object> message(String messageType, IvcInvoice inv, List<IvcInvoiceLine> lines, IvcAllowance a);
}
// 通道 SPI
public interface InvoiceGateway { String code(); GatewayReceipt send(String regimeCode, String messageType, String payloadJson); }
```

### 2.3 台湾证明联内容（`TwProofCodes`）

- 期别 `yyyMM`：民国年 3 位 + 双月（11、12 月 → `12`）；显示「115年11-12月」。时区固定 `Asia/Taipei`。
- 一维码（Code39）：期别 5 + 号码 10 + 随机码 4 = 19 码。
- 左 QR：号码 10 + 民国日期 7 + 随机码 4 + 销售额 8 位十六进制 + 总计 8 位十六进制 + 买方统编 8（无则 `00000000`）
  + 卖方统编 8 + 加密验证 24 + `:**********:` + QR 内品目笔数 + `:` + 总品目笔数 + `:1:` + `品名:数量:单价…`。
- 加密验证：`AES/CBC/PKCS5Padding`，密钥 = 主体在财政部取得的 32 位十六进制 AES Key，IV 为财政部公开固定值，
  明文 = 号码 + 随机码，Base64 后 24 字符。
- 右 QR：`**` + 左码放不下的品目；单码预算 200 字节（UTF-8），超出部分不入码（总品目笔数仍如实写）。

### 2.4 模块设计（代码清单）

| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `backend/invoice/pom.xml` | 聚合 `invoice-domain`、`invoice-channel` |
| 新增 | `backend/invoice/invoice-domain/pom.xml` | 依赖 shop-base、shop-base-auth、shop-store-mybatis、print-api、secret-api |
| 新增 | `invoice-domain/…/invoice/entity/Ivc{RegimeProfile,NumberRange,Invoice,InvoiceLine,Allowance,Transmission}.java` | 表实体 |
| 新增 | `invoice-domain/…/invoice/mapper/InvoiceMappers.java` | 六个 Mapper + 号段加锁查询 |
| 新增 | `invoice-domain/…/invoice/dto/InvoiceDtos.java` | Req / VO / Query |
| 新增 | `invoice-domain/…/invoice/regime/{InvoiceRegime,InvoiceRegimes,RegimeModels}.java` | 税制 SPI、注册表、Amounts/Buyer 模型 |
| 新增 | `invoice-domain/…/invoice/regime/tw/{TwEguiRegime,TwPeriods,TwBuyerRules,TwProofCodes,TwQrCipher}.java` | 台湾电子发票 |
| 新增 | `invoice-domain/…/invoice/gateway/{InvoiceGateway,GatewayReceipt}.java` | 通道 SPI |
| 新增 | `invoice-domain/…/invoice/service/{InvoiceService,InvoiceSetupService,InvoiceTransmissionService}.java` + `impl/` | 开立/作废/折让/查询、配置与号段、上传任务 |
| 新增 | `invoice-domain/…/invoice/port/{InvoicePortImpl,InvoicePrintSourceFactsPortImpl}.java` | 对行业、对打印中心 |
| 新增 | `invoice-domain/…/invoice/api/biz/{BizInvoiceController,BizInvoiceSetupController,BizInvoiceScope}.java` | B 端；`BizInvoiceScope` 统一取租户/主体/门店/操作人 |
| 新增 | `invoice-domain/…/invoice/job/InvoiceTransmissionJob.java` | 定时上传（开关控制） |
| 新增 | `invoice-domain/src/main/resources/db/invoice/V1__invoice_core.sql`、`V2__invoice_role_permissions.sql` | 独立迁移序列 |
| 新增 | `backend/invoice/invoice-channel/…/invoice/channel/stub/StubInvoiceGateway.java` | 本地桩通道 |
| 新增 | `backend/invoice/invoice-channel/…/invoice/channel/turnkey/TurnkeyInvoiceGateway.java` | 财政部 Turnkey：MIG XML 原子落目录 |
| 新增 | `invoice-domain/…/invoice/entity/IvcBuyerPreference.java` | 预存买受人 |
| 新增 | `shop-app/…/invoicebridge/InvoiceAutoIssueConsumer.java` | `ORDER_PAID` → 自动开立 |
| 修改 | `shop-base-auth/…/auth/BizPerms.java` | `LABELS` 补发票四码；`assignableCodes` 排除 `biz:invoice:setup` |
| 修改 | `b-app/src/i18n/locale/{zh-CN,en,ar}.ts`、`packages/shared/tests/biz-role-seed.test.ts`、`docs/technical/README.md` | 权限文案、角色种子守卫读模块序列、索引 |
| 新增 | `shop-base/…/spi/invoice/{InvoicePort,InvoiceSourcePort}.java` | 跨域契约 |
| 新增 | `shop-app/…/invoicebridge/port/SubOrderInvoiceSourcePortImpl.java` | 已付子单来源（三行业共用） |
| 新增 | `shop-app/…/config/InvoiceSchemaConfig.java` | `db/invoice` 迁移，历史表 `ivc_flyway_history` |
| 修改 | `backend/pom.xml`、`shop-app/pom.xml`、`shop-core/pom.xml` | 模块登记；zxing 版本在父 POM 管理 |
| 修改 | `shop-base/…/common/{ErrorCode,BizKey}.java`、`shop-base-auth/…/auth/BizPerms.java` | 26001–26009、`IVC/IVA/IVR/IVT`、四个权限码 |
| 修改 | `shop-app/…/i18n/messages{,_en,_ar}.properties`、`docs/api/响应格式规范.md` | 错误码文案与分段表 |
| 修改 | `print-api/…/PrintSourceDataScopes.java`、`shop-app/…/printbridge/PrintActorFactory.java` | INVOICE 数据权限 |
| 新增 | `shop-core/…/printing/center/layout/PrintSymbolRenderer.java` | zxing 生成 Code39 / QR 位图 |
| 修改 | `shop-core/…/template/DefaultPrintDocumentCompiler.java`、`layout/DefaultPrintLayoutEngine.java` | `barcode`、`qrPair` 块 |
| 修改 | `shop-core/src/main/resources/print/print-{scene-catalog,template-definitions,scene-examples}.json` | `INVOICE_PROOF` 场景与 58mm 模板 |
| 修改 | `print-channel/…/pdf/PdfReceiptRenderer.java` | 画 `ImageRun` |
| 新增 | 测试：`TwRulesTest`、`TwProofCodesTest`、`InvoiceServiceMysqlTest`、`InvoiceBoundaryTest`（invoice-domain），`TurnkeyInvoiceGatewayTest`（invoice-channel），`InvoiceProofLayoutTest`（print-channel），`SubOrderInvoiceSourcePortImplTest`、`InvoiceAutoIssueConsumerTest`（shop-app） | 见 §5 |
| 修改 | `shop-app/src/test/…/arch/{ArchitectureTest,BizEndpointPermTest,BizRoleSeedTest}.java`、`infra/SchemaDriftTest.java` | 登记 `invoice` 域与 `invoicebridge`、6 个 /biz 端点的权限、模块迁移序列的角色追加、`db/invoice` 重放 |
| 修改 | `backend/scripts/gen-test-schema.py`、`shop-app/src/test/resources/schema-test.sql` | H2 夹具接上 `db/invoice`（只并入发票增量，见 §6） |
| 修改 | `print-channel/src/test/…/ReceiptSoftwareAcceptanceTest.java` | 按场景声明的纸宽渲染（证明联只有 58） |
| 新增 | `docs/api/invoice.openapi.json` | 10 个 /biz 端点的公开契约（首次提交时另有 `backend-ui-delivery-invoice.json` 登记「页面待接」，B 端页面接通后已删除） |
| 新增 | `b-app/src/pages/{sales-invoices,sales-invoice,sales-invoice-issue,invoice-setup}/index.vue` | 列表（期别/状态筛选）、详情（作废 / 折让 / 打印证明联走打印中心 J03）、开票（含「仅预存，付款后自动开立」）、设置（税制、通道、自动开立、号段） |
| 新增 | `b-app/src/api/{sales-invoice,sales-invoice-http}.ts`、`api/mocks/sales-invoice.ts`、`shared/sales-invoice.ts` | 端点 `mSi*`；mock 抛「不可用」不伪造成功 |
| 修改 | `b-app/src/{pages.json,shared/nav.ts,shared/store-scope.ts,api/contract.ts,api/endpoints.ts,api/http.ts,api/mocks/index.ts,pages/home/index.vue,pages/order/index.vue}`、三语 locale、`docs/technical/design/ui-catalog.json` | 路由登记、首页「销售发票」入口、订单详情「开发票」入口（子单号预填）、门店作用域、界面清单 |

### 2.5 开立时序

1. B 端 / 行业调用 `issue(scope, sourceType, sourceNo, buyer)`。
2. 已有有效发票（`active_source_key` 命中）→ 原样返回（AC3）。
3. 读主体税制配置；未开通 → 26001。
4. `InvoiceSourcePort#load` 取冻结明细；校验明细合计 = 总计（AC5）。
5. 税制校验买受人（AC4），算销售额/税额，定 `printMark`。
6. **同一事务**：号段 `FOR UPDATE` 取号（AC1/2）→ 插发票、明细 → 生成证明联快照 → 插 `ISSUE` 上传任务；
   唯一键冲突（并发同来源）回读原发票返回。
7. 提交后：`printMark=Y` 时发 `BusinessPrintFactReady`，打印中心按路由自动出证明联。

## §3 选型

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| 号段计数器放 Redis | 快 | 与发票落库不在同一事务，宕机会跳号/重号 | ❌ |
| 号段行 `SELECT … FOR UPDATE` + 同事务插发票 | 不重号、不跳号可证 | 单主体高并发时串行 | ✅ 门店开票 QPS 极低，串行可接受 |
| 证明联自己出 ESC/POS | 不依赖打印中心 | 重复造设备/重试/补印留痕 | ❌ |
| 证明联走打印中心，条码以位图 `ImageRun` 画 | 复用设备、补印、对账；PDF/桌面 agent 天然支持 | 飞鹅等文本通道暂不能出条码 | ✅ |
| 发票迁移进主序列 | 一处管理 | 主序列多会话并行抢号，本分支与 main 已差 26 号 | ❌ 用独立序列 `db/invoice` |

## §4 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| QR 加密规格与财政部验证不一致 | 证明联扫不出 | `TwQrCipher` 用财政部公开 IV 与样例单测；正式启用前用财政部「电子发票 QR 码验证」实测一张 |
| 飞鹅通道不支持图片 | 用飞鹅的门店打不出证明联 | 打印中心如实返回 `print.vendorDrawingUnsupported`；后续给飞鹅适配 `<QR>`/`<BC128>` 原生标签（Code39 需确认机型） |
| 子单金额组成与发票总计口径 | 开错金额 | 明细 + 运费 − 优惠 − 积分折抵 ± 秤重 = 实付，对不上 → 26002；混合税别下整单优惠/折抵分不到税别 → 26002，不伪造分摊 |
| Turnkey 只确认「文件已交出」 | 大平台退件看不到 | 报文与文件名可追溯；回执目录（ERRORS/ProcessResult）回写是下一步；48 小时期限在 B 端可见上传状态 |
| 报价税类缺失或 OUT_OF_SCOPE | 不能开 | 回 26002，不默认按应税开 |

## §5 对账三 · 实现 → 需求（测试）

原生库测试连本机 MySQL 9.7（127.0.0.1:3308，每次建随机库、跑完删除），环境变量沿用 `COMMERCE_MYSQL_URL/USER/PASSWORD`。

| AC | 测试方法 | 结果 |
|---|---|---|
| AC1 | `InvoiceServiceMysqlTest#ac1NumbersAreSequentialAndConcurrentIssuesNeverDuplicate`（顺序 3 张 + 12 线程并发，15 号 15 张无缺号） | ✅ |
| AC2 | `#ac2ExhaustedRangeRejectsWithoutCreatingAnInvoice` | ✅ |
| AC3 | `#ac3SameSourceReturnsTheOriginalInvoiceEvenUnderConcurrency`（6 线程同来源 → 1 张、只取 1 号） | ✅ |
| AC4 | `TwRulesTest#taxIdChecksumIncludingTheSeventhDigitSevenRule`、`#buyerCombinationsAndCarrierFormats`；`#ac4InvalidBuyerRejectedBeforeANumberIsTaken` | ✅ |
| AC5 | `TwRulesTest#b2cKeepsTaxInsideSalesAndB2bSplitsFivePercent`；`#ac5AmountsAgreeWithTheSourceAndB2bSplitsTax` | ✅ |
| AC6 | `TwProofCodesTest`（19 码一维码、左 QR 77 字头、溢出到右 QR 与笔数）；`TwRulesTest#qrVerificationIsAesCbcOfNumberAndRandomWithTheOfficialIv`；`#ac6ProofSnapshotCarriesCodesAndPrintCenterIsWoken`；`InvoiceProofLayoutTest#proofDrawsBarcodeAndTwoQrCodesAsImagesThatSurviveIntoPdf` | ✅ |
| AC7 | `#ac7CarrierOrDonationMeansNoProof` | ✅ |
| AC8 | 补印复用打印中心 REPRINT（既有测试覆盖）；本期无新增代码 | — |
| AC9 | `#ac9VoidInPeriodFreesTheSourceForANewNumber`（作废后打印守卫为 CANCEL） | ✅ |
| AC10 | `#ac10PreviousPeriodCannotBeVoided` | ✅ |
| AC11 | `#ac11AllowancesNeverExceedTheInvoice` | ✅ |
| AC12 | `#ac12TransmissionSendsAndRetriesTheSamePayload` | ✅ |
| AC13 | `InvoiceBoundaryTest#invoiceDomainImportsNoIndustryOrTradeCode`；`ArchitectureTest` 登记 `invoice` 域（无发票违规） | ✅ |
| AC14 | `#ac14NoRegimeMeansNoInvoice` | ✅ |
| AC15 | `#ac15ListByPeriodAndStatusInTheCurrentStore`；B 端页面 `vue-tsc --noEmit` 无错、b-app vitest 400/400 | ✅ |
| 设置 | `#setupRejectsOverlappingRangesAndRoleSeedGotInvoicePermissions`（V2 角色追加在真库生效） | ✅ |
| AC16 | `#autoIssueUsesTheRememberedBuyerOnlyWhenTheEntityTurnedItOn`；`InvoiceAutoIssueConsumerTest`（2） | ✅ |
| AC17 | `TwRulesTest#mixedTaxTypesSplitIntoTaxableZeroAndFreeAndOnlyTaxablePaysTax`、`#allowanceTaxFollowsTheTaxableShareOfTheInvoice`；`#ac5MixedTaxTypesArePersistedPerLineAndInTheUploadMessage`；`SubOrderInvoiceSourcePortImplTest#foodOrderTakesIndustryAndTaxClassFromTheFrozenQuote`、`#mixedTaxWithWholeOrderDiscountOrUnknownTaxClassIsNotInvoiced` | ✅ |
| AC18 | `SubOrderInvoiceSourcePortImplTest#retailOrderBecomesItemsFreightDiscountPointsAndWeighLinesThatSumToThePayment`、`#unpaidOrPartlyRefundedOrdersAreNotInvoiced` | ✅ |
| AC20 | `InvoiceServiceMysqlTest#ac20BuyerKeepsCarriersAndTheFirstOrChosenOneIsDefault` | ✅ |
| AC21 | `#ac21MemberCarrierNumberIsTheLoggedInBuyerAndIssuesWithoutPaper` | ✅ |
| AC22 | `#ac22AutoIssueDefaultsToMemberCarrierWhenThereIsABuyerAndNoChoice` | ✅ |
| AC23 | `#ac21…`（买家只看到自己的）；`MpEndpointAuthTest#loginRequiredEndpointsRejectAnonymous`（6 个端点匿名被拒） | ✅ |
| AC24 | `#ac23ChoosingOnAPaidOrderIssuesNowAndEachShopAnswersForItself` | ✅ |
| AC19 | `TurnkeyInvoiceGatewayTest`（2：命名空间 XML、转义、原子写、重发覆盖；拒绝非台湾与未知报文） | ✅ |

实际输出（2026-11-21）：

```
invoice-domain  TwRulesTest 8 · TwProofCodesTest 3 · InvoiceServiceMysqlTest 20 · InvoiceBoundaryTest 1  → Tests run: 32, Failures: 0, Errors: 0
invoice-channel TurnkeyInvoiceGatewayTest 2                                                           → Tests run: 2, Failures: 0, Errors: 0
shop-app        SubOrderInvoiceSourcePortImplTest 4 · InvoiceAutoIssueConsumerTest 2                   → Tests run: 6, Failures: 0, Errors: 0
print-channel   ReceiptSoftwareAcceptanceTest 27 · InvoiceProofLayoutTest 2                            → Tests run: 29, Failures: 0, Errors: 0
shop-app        BizRoleSeedTest 4 · BackendI18nParityTest 6 · ErrorCodeUniqueTest 3 · ConditionalBeanWiringTest 1
                ControllerProfileTest 2 · FunctionPointPermAlignmentTest 3 · JsonColumnSeedTest 1           → 全绿
packages/shared spec-completeness「错误码分段表与 ErrorCode.java 一致」对 26xxx 无差异
scripts/check-sql-portability.mjs（扫 backend/，含 db/invoice）                                         → exit 0
```

**本分支 HEAD 上已有、与本次无关的红**（逐条核过失败信息不含 invoice；其中 `SchemaDriftTest` 与 `BusinessPrintSourceFlowTest`
另在干净 HEAD 工作树复跑同样失败）：`ArchitectureTest` 5 条、`BizKeyConventionTest`、`OrphanModuleTest`、`ScheduledJobConventionTest`、
`MpEndpointAuthTest`、`PayAuditTrailTest`、`PayReverseDependencyBudgetTest`、`InventoryDisabledContextTest`、`SchemaParityTest`、
`SchemaDriftTest`（正则 StackOverflow）、`MapperSmokeTest`（prd_execution_retention 等表缺 H2 投影）、`BizEndpointPermTest`（其他 5 个端点）、
`MerchantRoleFlowTest#assignablePermsComeFromBackend`、`BusinessPrintSourceFlowTest`；vitest `message-placeholder` 与 `LOGIN_TICKET_INVALID`、字段说明缺失。

## §6 对账二 · 设计 → 实现

文件清单见提交 `git show --stat`（§2.4 已按实际补齐）。与 §2.4 对比无遗漏、无多出文件。

### 偏差说明

| 偏差 | 原因 |
|---|---|
| `ivc_invoice.proof_snapshot` 用 `LONGTEXT` + `CHECK (JSON_VALID(...))`，不用 `JSON` 类型 | 真库测试发现 MySQL 会把 JSON 列重排键、加空白（实测 `{"source":1,"a":2}` → `{"a": 2, "source": 1}`），读回文本与开立时算的哈希对不上，打印中心 `FrozenJson` 直接拒绝。**同一隐患疑似存在于打印中心既有的 `biz_print_fact.snapshot`（V589，JSON 列 + 哈希）**，未在本次处理，另行核实 |
| 取号后的同来源复查改为锁定读 `InvoiceMapper#lockActive`（`FOR SHARE`） | 可重复读隔离下普通 SELECT 读的是事务首读快照，看不见排在前面刚提交的发票，会撞唯一键而不是返回原发票；AC3 并发用例覆盖 |
| zxing 依赖加在 `shop-core`（版本在父 POM） | 版面引擎住在 `shop-core/printing/center`，条码位图在那里生成 |
| 证明联模板只有 `STANDARD_58`；`ReceiptSoftwareAcceptanceTest` 改为按场景声明的纸宽渲染 | 证明联法定 5.7 cm，不提供 80 mm 版本 |
| 上传任务增加 `SENDING` 中间态（超过 10 分钟视为执行者中断） | 多实例下用条件更新认领，避免同一报文并发发送 |
| V1 在首次合入前补改（课税别两列、`auto_issue`、`ivc_buyer_preference`），未另开 V3 | V1 只存在于本分支未推送的提交里，从未在任何持久库执行过（测试库每次建删）；补改比叠一条 `DROP CHECK` 迁移干净，且 H2 重放器不认 `DROP CHECK` |
| `biz:invoice:setup` 不进自定义角色可分配列表 | 它写统编、QR 加密密钥与号段，与已排除的跨店税配置同类 |
| `schema-test.sql` 只并入发票增量 | 本分支已存在与生成器不一致的漂移（他人在途改动），整份重生成会把无关改动一并带入 |

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-11-21 | 用户确认：依据 CKO-24 / CKO07 / 打印中心 / V94；通用方案先实现台湾；直接在 codex/food-next01 开发 |
| 2026-11-21 | 已实现首期；闸门：invoice-domain / print-channel 相关测试全绿（MySQL 9 原生），shop-app 守卫对发票部分全绿，扫描范围见 §5 |

### 后续（不在本期）

1. 运营端「上传失败 / 即将超过 48 小时」清单（PRD §2 运营场景；需 ops 五处登记）。
2. Turnkey 回执目录（处理结果、退件）回写 `ivc_transmission`；商业加值中心通道按签约方各加一个 `InvoiceGateway`。
3. 手机条码真伪验证：接财政部验证接口（需营业人或加值中心 AppID）；目前只校验格式。
4. 飞鹅等文本通道：其原生标签只有 Code128 / QR，没有证明联规定的 Code39，故证明联只走 PDF / 桌面 agent；如需飞鹅出证明联，须先确认机型支持 Code39。

## §8 载具优先（C 端载具、会员载具、我的发票）

| AC | 落点 |
|---|---|
| AC20 | `ivc_user_carrier`（V3）+ `InvoiceBuyerServiceImpl#saveCarrier / carriers / deleteCarrier`；`GET/POST /mp/invoice-carrier`、`POST /mp/invoice-carrier/{carrierNo}/delete` |
| AC21 | `InvoiceServiceImpl#memberCarrier`：类别号取主体 `member_carrier_type`，号码 = 登录买家用户号，服务端填 |
| AC22 | `InvoiceServiceImpl#issueIfAutomatic`：无预存选择 + 来源有买家 + 主体有会员载具 → 会员载具；否则一般 |
| AC23 | `ivc_invoice.buyer_user_no`（V3，开立时取自 `InvoiceSource.buyerUserNo`）；`GET /mp/sales-invoice`、`/{invoiceNo}` |
| AC24 | `InvoiceSourcePort#sourcesOfBuyerOrder`（子单实现按买家过滤）+ `InvoiceBuyerServiceImpl#choose`；`PUT /mp/sales-invoice/choice` |

**为什么会员载具号码不让顾客填**：会员载具的号码就是他的账号；让客户端报号码，等于允许把发票存进别人的载具。
**为什么按订单而不是按子单选**：C 端只认识主订单；一张单拆给几家商家时，每家各自一个事务、各自回结果，一家没开通不影响另一家。
**为什么迁移是 V3 而不再补改 V1**：V1 已在本分支提交；此后改表一律加新迁移。

| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `invoice-domain/…/db/invoice/V3__invoice_buyer_carriers.sql` | `ivc_invoice.buyer_user_no` + 索引；`ivc_user_carrier` |
| 新增 | `invoice-domain/…/entity/IvcUserCarrier.java`、`service/InvoiceBuyerService.java`、`service/impl/InvoiceBuyerServiceImpl.java`、`api/mp/MpSalesInvoiceController.java` | C 端载具与开法 |
| 修改 | `shop-base/…/spi/invoice/InvoiceSourcePort.java` | `InvoiceSource.buyerUserNo`、`sourcesOfBuyerOrder`、`BuyerSource` |
| 修改 | `invoice-domain/…/service/impl/{InvoiceServiceImpl,InvoiceSetupServiceImpl}.java`、`dto/InvoiceDtos.java`、`mapper/InvoiceMappers.java` | 买受人在读到来源后决定、`listForBuyer / getForBuyer`、VO 带营业人；清空会员载具类别号显式写 null |
| 修改 | `shop-app/…/invoicebridge/port/SubOrderInvoiceSourcePortImpl.java` | 带出买家、按买家列订单子单 |
| 修改 | `shop-app/src/test/…/arch/MpEndpointAuthTest.java` | 6 个 /mp 端点登记为要登录，补探测请求体 |
| 新增 | `c-app` 我的发票 / 载具管理 / 订单开法页面 | 见 §8 测试 |

偏差：`InvoiceSetupServiceImpl#saveProfile` 原先用 `updateById`，清空会员载具类别号时被静默跳过（商家关不掉会员载具）；
AC22 用例在 MySQL 上复现后改为显式写 null。

## §9 发票打印（后端）

| AC | 落点 |
|---|---|
| AC25 | 场景 `INVOICE_DETAIL`（58 / 80）；`TwEguiRegime#detail`、`InvoiceServiceImpl#detailLines`；`ivc_invoice.detail_snapshot`（V4）；开立后唤醒 |
| AC26 | 场景与来源类型 `INVOICE_ALLOWANCE`（58 / 80）；`TwEguiRegime#allowanceProof`；`ivc_allowance.print_snapshot`（V4）；折让后唤醒 |
| AC27 | 正本唯一：打印中心按事实的触发键去重（既有）；加印：场景目录 `extraCopiesAllowed: false` → `PrintReprintServiceImpl` 拒绝 `print.extraCopiesNotAllowed`、`PrintViews` 不出「加印」动作；补印标记：场景目录 `reprintNotice`「補印」随文档冻结，`DefaultPrintLayoutEngine` 在 REPRINT 时以 16pt 画在正文开头 |

**为什么交易明细每张都唤醒、证明联只在应印时唤醒**：载具 / 捐赠发票不出纸，顾客手上唯一的东西就是明细；
是否真的自动出纸由门店的场景策略决定（打印中心既有），不在发票域写死。
**为什么折让证明单的法定全称不放在系统栏**：系统栏的公告行不换行，58 mm 放不下「營業人銷貨退回、進貨退出或折讓證明單」，
所以系统栏写「折讓證明單」，全称作为正文第一行（会换行）。
**补印标记只对非紧凑版面生效**：发票三种模板都不用 COMPACT_V2；以后紧凑模板要用 `reprintNotice` 需在 `CompactPrintLayout` 补同样的一段。

| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `invoice-domain/…/db/invoice/V4__invoice_print_documents.sql` | 明细与折让证明单快照（LONGTEXT + JSON_VALID） |
| 修改 | `invoice-domain/…/port/InvoicePrintSourceFactsPortImpl.java` | 两种来源类型、三种场景；作废发票的证明联与明细都取消 |
| 修改 | `invoice-domain/…/regime/{InvoiceRegime,tw/TwEguiRegime}.java`、`service/impl/InvoiceServiceImpl.java`、`entity/{IvcInvoice,IvcAllowance}.java` | 内容生成、冻结与唤醒 |
| 修改 | `shop-core/…/printing/center/{template/PrintSceneRegistry,template/DefaultPrintDocumentCompiler,layout/DefaultPrintLayoutEngine,service/impl/PrintReprintServiceImpl,service/impl/PrintQueryServiceImpl,support/PrintViews}.java` | `extraCopiesAllowed`、`reprintNotice` |
| 修改 | `print-api/…/PrintSourceDataScopes.java`、`shop-core/src/main/resources/print/print-*.json` | 数据权限与场景、模板、示例 |

| AC | 测试 | 结果 |
|---|---|---|
| AC25 | `InvoiceServiceMysqlTest#ac25EveryInvoiceHasAFrozenDetailSlipAndCarrierOnesSayWhereTheInvoiceWent`、`#ac7CarrierOrDonationMeansNoProof`；`ReceiptSoftwareAcceptanceTest`（58 / 80 出 PDF） | ✅ |
| AC26 | `#ac26AllowanceProofIsFrozenPrintableAndVoidStopsTheInvoiceDocuments`；`ReceiptSoftwareAcceptanceTest` | ✅ |
| AC27 | `InvoiceProofLayoutTest#reprintOfTheProofCarriesAVisibleBuYinMarkButTheOriginalDoesNot`、`#onlyTheProofRefusesExtraCopies` | ✅ |

`PrintDatabaseAcceptanceTest#sampleSimulationAndPdfExportNeverCreateProductionJobs` 在干净父提交上同样失败（模拟运行 0 成功，与 `BusinessPrintSourceFlowTest` 同一症状），非本次引入。

### §9.1 打印前端（B 端）

| 入口 | 接法 |
|---|---|
| 发票详情「打印证明联」 | `POST /biz/print/requests`（J03），`sourceRef {INVOICE, invoiceNo, scene INVOICE_PROOF}`；ISSUED + printMark + `biz:print:submit`。重复按不会出第二张正本（打印中心按触发键去重） |
| 「打印交易明细」 | 同上，scene `INVOICE_DETAIL`；ISSUED 即可，不看 printMark |
| 折让行「打印折让证明单」 | 同上，`sourceRef {INVOICE_ALLOWANCE, allowanceNo, scene INVOICE_ALLOWANCE}` |
| 「补印证明联」 | 按场景分页查作业（J06），取 `documentKey = 发票号:INVOICE_PROOF` 的最新一张；选原因（卡纸 / 缺纸 / 顾客遗失 / 其他）后 `POST /biz/print/jobs/{jobNo}/reprints`（J13）。没出过正本时提示先打正本；`biz:print:reprint` + `biz:print:read` |
| 打印中心 | 动作只认后端下发的 `allowedActions`（证明联不出「加印」）；23 个场景三语名称；`printCenter.failure.*` 失败文案 |
| 「打印中心」链接 | 按来源子单号过滤 —— 发票打印件的事实记了来源子单（`InvoicePrintSourceFactsPortImpl#fact`），与该单的支付小票列在一起 |

**共享 `ApiError` 带出失败 `data`**（`packages/shared/src/net/http-client.ts`）：打印中心把机器可读原因放在 `data.key`，
此前 HTTP 失败路径把它丢了，用户只看到「打印操作未完成」。改为可选第三参数，旧调用不受影响（`http-error-data.test.ts`）。

| 测试 | 结果 |
|---|---|
| `b-app/tests/sales-invoice-print.test.ts`（7）、`b-app/tests/print-center.test.ts`；b-app vitest 410/410；b-app、c-app `vue-tsc` 无错 | ✅ |
| `packages/shared/tests/http-error-data.test.ts`（2） | ✅ |
| `InvoiceServiceMysqlTest#ac25…`（打印事实挂来源子单） | ✅ |

## §10 服务商接入整合方案（鲸跃、绿界）

状态：草稿 · 2026-11-21。依据远端 `codex/food-next01` 的三份对接文档（`TDD-台湾电子发票-鲸跃与绿界.md`、`design/台灣電子發票多服務商接入方案.md`、`交接-台湾电子发票-鲸跃与绿界.md`，提交 `b41142678`、`b494219df`）与 UME Git 备份 `trendlab-pay/service-rpc@master` 的源码核对，把两家厂商落到本模块（`ivc_*`）上。

### 10.1 已核实的事实

| 项 | 鲸跃（Cetustek） | 绿界（ECPay） |
|---|---|---|
| 存量 | UME `invoice.t_user` 274 个账号、`t_invoice` 18,653 张，全部 `cetustek`；餐饮四分片当日仍在开立 | 无存量，新接入 |
| 协议 | SOAP 1.1，`text/xml`，命名空间 `http://webservice.cetustek.com/`，结果取 `<return>` | HTTPS JSON；外层 `MerchantID`、`RqHeader.Timestamp`、`Data`，`Data` 为 URL Encode 后 AES-128-CBC/PKCS7 再 Base64；先判 `TransCode` 再判 `RtnCode` |
| 地址 | UME 生产 `http://ve.ei.com.tw/InvoiceEntitySync/InvoiceAPI`；dev/uat `http://invoice.cetustek.com.tw/InvoiceEntitySync/InvoiceAPI`；两者改 HTTPS 读 WSDL 均 403。另有公开新服务 `https://www.ei.com.tw/InvoiceMultiWeb/InvoiceAPI`（`CreateInvoiceV3`、`QueryInvoiceByOrderid`、`CancelInvoice`、`CreateAllowance` 等，方法集不同） | 测试 `https://einvoice-stage.ecpay.com.tw`，正式 `https://einvoice.ecpay.com.tw` |
| 凭证 | 只有商家级：`officeid`（店家代号）、`posno`（账号）、`posid`（密码），UME 来源 `t_user.OFFICE_ID/ID/PW`；**无平台密钥** | 商家级：`MerchantID`、发票专用 `HashKey`、`HashIV`；无平台密钥 |
| 发票号 | **厂商配号段，本地依序取号**：`ApplyInvoiceNumber(民国年, 双月)` → `applyid` → `TakeInvoiceNumber` 回 `字轨;起号;讫号;种类`；`CheckInvoiceNumber` 回目前号；开立时本地生成随机码后 `UploadInvoice` | **在线由绿界分配**：`/B2CInvoice/Issue` 回 `InvoiceNo`、日期、随机码；本地以 `RelateNumber`（≤50，大写字母数字）作幂等身份；离线另有取号区间 + `OfflineIssue` |
| 动作 | `QueryOfficeInfo`、`QueryLoveCode`、`UploadInvoice`(S0)、`QueryInvoiceStatus`、`UploadCancelInvoice`(C0，作废)、`UploadVoidInvoice`(V0)、`UploadAllowance`(A0)、`UploadCancelAllowance`(C0)；失败 `error/invalid/exist/nodata/notake` | `Issue`、`GetIssue`、`Invalid`、`Allowance`/线上折让、`GetAllowanceList`、`AllowanceInvalid`、`CheckBarcode`、`InvoicePrint`（回一小时有效的列印网址） |
| 证明联 QR | `QueryOfficeInfo` 返回 `Office.QRCode`，UME 打印端用它生成 QR（32 位十六进制，与财政部 QR AES Key 同格式，待厂商确认） | 可本地生成（需 AES Key）或用 `InvoicePrint` 网址；以本地打印中心出纸为主 |

### 10.2 与本模块的差距

| 对接方案要求 | 本模块现状 | 需要的改动 |
|---|---|---|
| 厂商账户：营业人 × 厂商 × 环境 × 账号，凭证只存引用 | `ivc_regime_profile` 只有 `gateway_code`，没有账号、环境、凭证 | 新增 `ivc_provider_account`；凭证进 secret 模块（PLATFORM 命名空间，ownerType 区分厂商） |
| 门店路由：按门店选唯一生效账户，切换只影响新开立，历史沿原账户 | 主体级一条配置；上传任务记 `gateway_code` 但不记账户 | 新增 `ivc_provider_route`（主体或门店级，生效时间，范围内唯一启用）；`ivc_invoice`、`ivc_transmission` 记 `account_id`，重试、作废、折让一律用原账户 |
| 鲸跃号段来自厂商 | 号段由商家在 B 端手工登记 | 「领取本期号段」动作：调 `ApplyInvoiceNumber`/`TakeInvoiceNumber`，写入现有 `ivc_number_range`（加 `account_id`、`apply_id`、`allocation_owner=PROVIDER`）；手工登记保留给 Turnkey |
| 绿界在线配号 | 开立在本地事务里取号，上传是事后异步 | 新增「厂商配号」开立模式：先落开立命令（`issue_status=PENDING`，`provider_order_no`=RelateNumber）→ 事务外调用 → 成功写回号码与随机码 → 超时置 `UNKNOWN`，由恢复任务按 `RelateNumber` 查单；`UNKNOWN` 不得换厂商重开 |
| 开票、上报、出纸三种状态分开 | 发票 `ISSUED/VOIDED` + 上传任务状态 + 打印中心 | 补 `issue_status`（仅厂商配号模式有 PENDING/UNKNOWN/REJECTED）；上报状态沿用上传任务；出纸沿用打印中心，已满足 |
| 证明联 QR 参数 | 商家在 B 端手填 AES Key | 鲸跃开通时调 `QueryOfficeInfo` 取 `QRCode` 自动入密钥模块（厂商确认语义后启用）；绿界仍手填或走厂商打印数据 |
| 日志不落凭证与完整报文 | 上传任务保存完整报文（供补传） | 保留报文（补传必需）；应用日志只记请求号、厂商码、业务状态、耗时，不打凭证与签名原串 |
| 旧 UME POS 兼容（SHA-256 签名网关） | 无 | 不做：ai-shop 原生端用现有门店身份 |

**不需要改的**：开立主流程的幂等与金额校验、税制 SPI（`TW_EGUI`）、证明联 / 交易明细 / 折让证明单打印、补印与禁止加印、载具与会员载具、自动开立、B 端与 C 端页面。厂商差异全部收在 `InvoiceGateway` 实现与账户路由里。

### 10.3 通道 SPI 扩展

现有 `InvoiceGateway#send(regime, messageType, payload)` 只覆盖「本地配号 + 事后上传」。扩展为按能力声明：

| 能力 | 鲸跃 `CETUSTEK` | 绿界 `ECPAY` | Turnkey / Stub |
|---|---|---|---|
| `numbering` | `PROVIDER_RANGE`（领号段，本地取号） | `PROVIDER_ONLINE`（开立即配号） | `LOCAL_RANGE`（手工登记） |
| `fetchRange(account, period)` | Apply + Take | — | — |
| `issue(account, invoice)` | `UploadInvoice`（异步上传任务调用） | `Issue`（同步，开立时调用） | 写文件 / 桩 |
| `query(account, ref)` | `QueryInvoiceStatus(号码, 日期)` | `GetIssue(RelateNumber)` | — |
| `void` / `allowance` / `cancelAllowance` | `UploadCancelInvoice` / `UploadAllowance` / `UploadCancelAllowance` | `Invalid` / `Allowance` / `AllowanceInvalid` | 写文件 |
| `officeInfo(account)` | `QueryOfficeInfo`（核对营业人、取 QRCode） | — | — |

### 10.4 数据库变更（完整方案，首期按 §11 简化落地）

| 表 | 字段与约束 |
|---|---|
| `ivc_provider_account`（新） | `account_no, tenant_no, entity_no, seller_tax_id, provider_code, environment(SANDBOX/PRODUCTION), provider_account_code, office_id, secret_ref, status`；唯一 `tenant + entity + provider + environment + provider_account_code` |
| `ivc_provider_route`（新） | `route_no, tenant_no, entity_no, store_no('*'=主体), account_no, effective_from, status`；同一路由范围仅一条 ACTIVE |
| `ivc_invoice`（加列） | `account_no`、`provider_order_no`（绿界 RelateNumber，账户内唯一）、`issue_status`（PENDING/UNKNOWN/ISSUED/REJECTED，本地配号直接 ISSUED） |
| `ivc_number_range`（加列） | `account_no`、`apply_id`、`allocation_owner`（PROVIDER/MANUAL） |
| `ivc_transmission`（加列） | `account_no`（重试与补传沿原账户） |

迁移只加列和新表，不改已存在的 V1–V4。

### 10.5 配置与凭证

| 项 | 位置 |
|---|---|
| 厂商地址 | 配置：`shop.invoice.provider.cetustek.{sandbox,production}-url`、`shop.invoice.provider.ecpay.{sandbox,production}-url`（绿界已知；鲸跃待厂商给 HTTPS POST 地址） |
| 鲸跃凭证 | 账户表存 `office_id`；`posno`、`posid` 存 secret 模块，账户表只存 `secret_ref` |
| 绿界凭证 | 账户表存 `MerchantID`（provider_account_code）；`HashKey`、`HashIV` 存 secret 模块 |
| 运行环境 | 每个账户自带 `environment`；进程按部署环境只加载对应环境的账户，交叉加载拒绝 |
| 录入入口 | 运营端（平台人员核对营业人、环境、字轨后录入并启用路由）；B 端只读账户状态。存量 UME 鲸跃商户（274 个账号）按营业人与门店逐个迁入，不复制明文到文档或工单 |

### 10.6 实施顺序

| 步 | 内容 | 依赖 |
|---|---|---|
| 1 | V5 表与账户 / 路由服务、运营端录入；`InvoiceGateway` 能力扩展；开票流程按账户路由，记 `account_no` | 无 |
| 2 | 鲸跃适配器：SOAP 封装、`QueryOfficeInfo`、领号段、上传 / 作废 / 折让 / 查询、结果码映射；用 UME 报文结构做单测 | 鲸跃测试账套与 HTTPS 地址 |
| 3 | 绿界适配器：加解密（官方测试向量字节级核对）、`Issue/GetIssue/Invalid/Allowance/AllowanceInvalid`；厂商配号开立模式与 `UNKNOWN` 恢复任务 | 绿界测试商户（公开测试介接资料可先用） |
| 4 | 联调：两家各走纸本 / 载具 / 捐赠 / 统编、作废、部分折让、超时恢复、门店切换后新旧单分流；58mm 实机出纸与扫码 | 测试账号、实机 |
| 5 | 存量鲸跃商户迁入与单店启用；对账（交易—发票—厂商回执—上报—出纸） | 厂商书面确认生产地址、账套、字轨 |

### 10.7 待决与待厂商确认

- **实施基线**：远端方案以 `ai-neargo` trade T3 为基线（`ng_trade.tax_tw_*`）；本节按 ai-shop 已实现的发票模块落地。两者只能选一个作为实施代码库，需产品与架构确认。
- 鲸跃：新系统应接旧 `InvoiceEntitySync` 还是公开的 `InvoiceMultiWeb`（`CreateInvoiceV3`）；各自 HTTPS 测试 / 正式 POST 地址、白名单；`QRCode` 是否即 QR AES Key；`UploadCancelInvoice` 与 `UploadVoidInvoice` 的确切语义；`S1/S2` 含义；字段长度与回复时限。
- 绿界：首批是否需要离线开立（取号区间 + `OfflineIssue`）；证明联走本地打印中心还是 `InvoicePrint` 网址（建议本地，需 AES Key）。

## §11 鲸跃接入（首期实现）

状态：已实现 · 2026-11-21 · 档位 1（新增端点、库表列、错误码、i18n）· 需求 PRD AC28。协议与字段照 UME `trendlab-pay/service-rpc` 的 `CetustekInvoiceServiceHelper` / `CetustekInvoiceServiceImpl` / `Soap*` 模型。

### 11.1 范围与相对 §10 的简化

| §10 方案 | 首期实现 | 为什么 |
|---|---|---|
| `ivc_provider_account` + `ivc_provider_route`（门店路由、多账户） | 账号挂在主体配置上：`ivc_regime_profile.provider_account_code`（officeid）+ `provider_secret_ref`（posno/posid 封存于 secret，ownerType `INVOICE_PROVIDER`） | UME 存量是一主体一账号；路由表等到同一主体真要分店用不同账号时再加，迁移只加列，不挡后续 |
| 账户表 `environment` | 不区分；地址取配置 `shop.invoice.cetustek.url`（默认 UME 用的正式地址） | 一个部署只连一个环境 |
| 绿界 `ECPAY`（在线配号） | 不做 | 本期只接鲸跃；绿界需要「厂商配号」开立模式，另起 |
| 运营端录入 | B 端「发票设置」录入（只写不读） | 现有设置页即主体级入口，权限 `biz:invoice:setup` |

### 11.2 契约变更

| 类 | 内容 |
|---|---|
| 库表 | `db/invoice/V5`：`ivc_regime_profile` + `provider_account_code`、`provider_secret_ref`；`ivc_number_range` + `allocation_owner`（MANUAL/PROVIDER）、`apply_id`、`invoice_type`；`ivc_allowance` + `fiscal_allowance_number`（发票号码 + 两位序号，MIG 上限 16 位，内部单号放不下） |
| 端点 | `POST /biz/invoice-setup/provider-verification`（QueryOfficeInfo，统编须一致，QRCode 存为 QR 金钥）；`POST /biz/invoice-setup/provider-ranges`（ApplyInvoiceNumber → TakeInvoiceNumber，同段重复取回只入库一次）；`PUT /biz/invoice-setup/profile` 加 `providerAccountCode / providerUser / providerPassword`（后两者只写） |
| 错误码 | `26011 INVOICE_PROVIDER_REJECTED {0}`（服务商原文代码：invalid / exist / nodata / unreachable / tax_id_mismatch / account_not_configured） |
| 配置 | `shop.invoice.cetustek.url`、`shop.invoice.cetustek.timeout-seconds`（默认 20） |
| 前端枚举 | `SiGatewayCode` 加 `CETUSTEK` |

### 11.3 模块设计

| 文件 | 职责 |
|---|---|
| `invoice-domain/gateway/InvoiceGateway` | `send(GatewayRequest)`：请求带 `refNo` 与可空的 `ProviderAccount` |
| `invoice-domain/gateway/InvoiceProviderGateway` | 加值中心类通道：`verify`、`allocate`、`ProviderException(code)` |
| `invoice-domain/service/impl/InvoiceProviderAccounts` | 账号封存与现解（secret PLATFORM） |
| `InvoiceTransmissionServiceImpl` | 加值中心通道发送前按任务主体 + 税制现取账号；没账号记一次失败，不抛 |
| `InvoiceSetupServiceImpl` | 保存账号、核验、取号段；远程调用不在事务里 |
| `InvoiceServiceImpl#allowance` / `TwEguiRegime#message` | 对外折让单号；G0401 带 `ext.OriginalInvoiceTime`（Turnkey 不输出 `ext`） |
| `invoice-channel/cetustek/CetustekSoapClient` | SOAP 信封（与 UME 逐字同形）、`<return>` 提取、转义 |
| `invoice-channel/cetustek/CetustekInvoiceGateway` | MIG 树 → 鲸跃 XML；结果码：开立 S0/S2、作废 C0/C5、折让 A0/A6 视为送达；零税率拒送 |
| b-app `invoice-setup` | 通道选「鲸跃」时出现商家代号 / 账号 / 密码；已保存为鲸跃后出现「核验账号」「向加值中心取号段」 |

字段映射（鲸跃 `Invoice`）：日期 `yyyy/MM/dd`；`OrderId` = 发票单号；B2C 不带 `BuyerIdentifier`；`DonateMark` 纸本 2 / 载具 0 / 捐赠 1；`PayWay` 5；明细 `ProductionCode` = 行序号，`DType` 应税空、免税 `TN`；金额栏取 MIG `Amount` 原值。

### 11.4 测试（对账三）

| AC | 测试 | 结果 |
|---|---|---|
| AC28 | `CetustekInvoiceGatewayTest`（本机假 SOAP）：`#ac28IssueSendsUmeShapedSoapWithEscapedInvoiceXml`、`#ac28ResultCodesDecideDelivery`、`#ac28VoidAndAllowanceMapToCetustekFields`、`#ac28VerifyAndAllocateParseProviderReplies`、`#ac28UnreachableProviderIsAProviderException` | 5/5 ✅ |
| AC28 | `InvoiceServiceMysqlTest#ac28ProviderAccountIsSealedVerifiedAndAllocatesRanges`、`#ac28ProviderRejectionsSurfaceTheProviderCode`；`#ac11…` 加断言对外折让单号（MySQL 9，全类 24/24） | ✅ |
| AC28 实连 | 经 UME166 隧道（台湾出口）用本实现调正式 `invoice.cetustek.com.tw`：占位账号下 `QueryOfficeInfo`、`ApplyInvoiceNumber` 均回 `InValid`，被映射为 `ProviderException(invalid)` —— 信封、地址、回包解析在真服务上成立；未用真实账号，未产生任何发票 | ✅ |

### 11.5 待办

- 拿到鲸跃测试 / 正式账号后：核验 QRCode 是否即 QR AES Key（目前按 32 位十六进制才存）、一单 S0 实开、作废与折让实走一遍。
- 鲸跃只放行台湾出口：生产实例在 UME166 机房可直连；本机开发无法直连，需经隧道。
- `UploadCancelAllowance`（折让作废）、`QueryInvoiceStatus` 未接，本模块尚无折让作废动作。

## §12 上传失败分级止损（鲸跃 M1-2）

状态：已实现 · 2026-11-22 · 档位 1（`ivc_transmission.status` 加值、`ivc_regime_profile` 加两列、新端点、三语文案）· 需求 PRD AC35 · 计划见 [TDD-发票服务-总体设计 §14](./TDD-发票服务-总体设计.md) M1-2。

### 12.1 问题

改之前所有上传失败都按 1、2、4 … 分钟退避重发。账号填错（鲸跃回 `invalid`）时，**该主体的每一张票都会每小时打一次鲸跃**，一直到有人发现；
报文格式错误同样无限重发，而重发多少次结果都一样。

### 12.2 方案

通道回执带失败种类（`GatewayReceipt.Outcome`），上传任务按种类处置：

| 种类 | 鲸跃码（`CetustekResultCodes`，数据表） | 任务状态 | 处置 |
|---|---|---|---|
| `ACCEPTED` | `S0 S2` · `C0 C5` · `A0 A6` | `SENT` | — |
| `RETRYABLE` | `error`；网络不通 / HTTP 异常（通道抛异常） | `FAILED` | 退避重发（不变） |
| `ACCOUNT_PROBLEM` | `invalid`；没配账号 | `HELD` | 主体配置写 `provider_hold_reason / provider_held_at`；**之后该主体的任务在发送前直接转 `HELD`，不再打到服务商**；ERROR 日志 `INVOICE_PROVIDER_HOLD`（每次暂停只打一条） |
| `FINAL` | 其余全部（`S1`、`M1`、`D0_`、`C3/C4`、`A3–A8`、未登记的码） | `NEEDS_REVIEW` | 不自动重发；ERROR 日志 `INVOICE_TRANSMISSION_NEEDS_REVIEW` |

- **恢复**：`POST /biz/invoice-setup/provider-verification` 核验通过即调 `InvoiceTransmissionService.releaseHeld`：清暂停标记，`HELD` 任务全部放回 `PENDING` 下一轮补发。
- **重传**：`POST /biz/invoices/{invoiceNo}/retransmissions`（`biz:invoice:issue`）把这张票及其折让的 `NEEDS_REVIEW` 任务放回待发，报文不变；没有可重传的回 26008。
- **B 端**：发票列表与详情把 `HELD` / `NEEDS_REVIEW` 标红，详情给出说明与「重新上传」按钮；发票设置页在暂停时显示原因与「核验账号即恢复」。
- `S1 / C1 / A1`（「上传失败」）语义待鲸跃确认（§14 M0-3），确认可重试后改码表一行即可。
- 告警目前是固定关键字的 ERROR 日志，供日志告警订阅；接运营站内消息（`OpsAlertPort`）需在 shop-core 加一类通知，列为后续。

### 12.3 改动清单

| 文件 | 改动 |
|---|---|
| `gateway/InvoiceGateway` | `GatewayReceipt(outcome, gatewayRef, error)` + 四个工厂方法 |
| `channel/cetustek/CetustekResultCodes`（新） | 结果码表 |
| `channel/cetustek/CetustekInvoiceGateway` · `turnkey/TurnkeyInvoiceGateway` · `stub/StubInvoiceGateway` | 改用新回执 |
| `service/InvoiceTransmissionService(Impl)` | 分级处置、`releaseHeld`、`retry` |
| `service/impl/InvoiceSetupServiceImpl` | 核验通过后解除暂停；视图带暂停原因 |
| `api/biz/BizInvoiceController` | 重传端点 |
| `db/invoice/V6__invoice_transmission_hold.sql` | 两列 + 索引 |
| b-app `api/*` · `pages/sales-invoice(s)` · `pages/invoice-setup` · `i18n` · `enum-registry` | 状态、按钮、提示；`SiRangeOwner` 改为具名类型 |
| `docs/api/invoice.openapi.json` | 新端点与字段 |

### 12.4 测试

| AC | 测试 | 结果 |
|---|---|---|
| AC35 | `InvoiceServiceMysqlTest#ac35AccountProblemHoldsTheEntityUntilVerified`：两张票第一张 `invalid` 后第二张不再发送（服务商调用 1 次）；暂停期间新票直接 `HELD`；核验通过后 3 张一次补发 | ✅ |
| AC35 | `#ac35RejectedUploadWaitsForManualRetryAndSystemErrorsBackOff`：`M1` → `NEEDS_REVIEW` 且不重发；重传后送达；无可重传回 26008；`error` → `FAILED` 退避且不暂停主体 | ✅ |
| AC35 | `CetustekInvoiceGatewayTest#ac28ResultCodesDecideDelivery`：`InValid` → 账号问题、`error` → 可重试、`S1` / `M:CarrierId1` → 待人工、没配账号 → 账号问题 | ✅ |

MySQL 9 全类 26/26、鲸跃 5/5、Turnkey 2/2、b-app `vue-tsc` 无错。

## §13 折让作废（鲸跃 M1-4）

状态：已实现 · 2026-11-22 · 档位 1（新端点、`ivc_allowance` 加列、上传报文类型 `ALLOWANCE_VOID`、三语文案）· 需求 PRD AC36 · 计划见 [TDD-发票服务-总体设计 §14](./TDD-发票服务-总体设计.md) M1-4。

### 13.1 规则

| 规则 | 实现 |
|---|---|
| 只能作废本期折让（跨期已申报） | `InvoiceRegime#allowanceVoidable`：台湾按折让开立时间的期别与当期比较；与发票作废同一口径，错误码 26006 |
| 一张折让只作废一次 | 条件更新 `status ACTIVE → VOIDED`，并发也只有一次成功；第二次 26008 |
| 累计折让回退 | `InvoiceMapper#releaseAllowance`：`allowed_minor -= 金额`（不会减成负数）；与状态更新同事务。全部折让作废后 `allowed_minor=0`，发票本身可作废 |
| 折让证明单不再出纸 | `InvoicePrintSourceFactsPortImpl#executionGuard` 遇已作废折让返回 `CANCEL_UNSUBMITTED_EXECUTION / ALLOWANCE_VOIDED` |
| 上传 | 上传任务 `message_type=ALLOWANCE_VOID`、报文 MIG `G0501`（`CancelAllowanceNumber` 用对外折让单号）；鲸跃 `UploadCancelAllowance`（`C0` 成功、`C4` 已上传过算送达、`C3` 等待人工）；Turnkey 写 `G0501/CancelAllowance` 文件。失败按 §12 分级处置，可用重传端点补发 |
| 折让单号不复用 | 对外折让单号的序号按该发票全部折让（含已作废）计数，作废后再开的折让取下一个序号 |

### 13.2 接口

`POST /biz/invoices/{invoiceNo}/allowances/{allowanceNo}/void`，权限 `biz:invoice:void`，请求 `{reason}`，返回发票详情。
`AllowanceVO` 加 `fiscalAllowanceNumber`、`status`、`voidReason`、`voidedAt`。

### 13.3 改动清单

| 文件 | 改动 |
|---|---|
| `db/invoice/V7__invoice_allowance_void.sql` | `ivc_allowance` 加 `status`（默认 ACTIVE）、`void_reason`、`voided_at`、`voided_by` |
| `entity/IvcAllowance` · `mapper/InvoiceMappers` | 新字段；`releaseAllowance` |
| `regime/InvoiceRegime` · `regime/tw/TwEguiRegime` | `allowanceVoidable`；`message("ALLOWANCE_VOID")` 产出 G0501 |
| `service/InvoiceService(Impl)` · `api/biz/BizInvoiceController` | `voidAllowance` 与端点 |
| `port/InvoicePrintSourceFactsPortImpl` | 已作废折让不出纸 |
| `channel/cetustek/*` · `channel/turnkey/TurnkeyInvoiceGateway` | `UploadCancelAllowance` 报文与结果码；G0501 文件 |
| b-app `pages/sales-invoice` · `api/*` · `i18n` · `enum-registry`（`SiAllowanceStatus`） | 折让状态标签、「作废折让」按钮（确认 + 原因），已作废隐藏打印 |
| c-app `api/sales-invoice-types.ts` | 折让类型补字段（页面未展示折让，不改页面） |

### 13.4 测试

| AC | 测试 | 结果 |
|---|---|---|
| AC36 | `InvoiceServiceMysqlTest#ac36AllowanceVoidReleasesTheAmountStopsPrintingAndQueuesG0501`：有折让时不能作废发票 → 作废折让后累计折让为 0、状态与原因正确、G0501 报文单号为「号码+01」、打印守卫拦下、重复作废 26008、发票本身可作废 | ✅ |
| AC36 | `#ac36PreviousPeriodAllowanceCannotBeVoided`：跨期 26006；不存在的折让 26005 | ✅ |
| AC36 | `CetustekInvoiceGatewayTest`：`UploadCancelAllowance` 信封与字段；`C4` 算送达、`C3` 待人工 | ✅ |
| AC36 | `TurnkeyInvoiceGatewayTest#allowanceVoidBecomesG0501CancelAllowance` | ✅ |

MySQL 9 全类 28/28、鲸跃 5/5、Turnkey 3/3、b-app / c-app `vue-tsc` 无错；契约、孤儿页、i18n 孤儿词条检查通过。

## §14 上报期限告警（鲸跃 M1-5）

状态：已实现 · 2026-11-22 · 档位 1（`ivc_transmission` 加列、新端点、新定时任务、三语文案）· 需求 PRD AC37 · 计划见 [TDD-发票服务-总体设计 §14](./TDD-发票服务-总体设计.md) M1-5。

### 14.1 期限

| 项 | 规则 | 实现 |
|---|---|---|
| 窗口 | 台湾 B2C 开立 / 作废 / 折让 / 作废折让后 48 小时内上传；B2B（存证）7 日 | `InvoiceRegime#reportWindow`（`TwEguiRegime` 按买受人类型） |
| 起算 | 事件发生即入队的那一刻 | `InvoiceServiceImpl#enqueue` 写 `report_deadline_at`、`report_warn_at`（窗口过半） |
| 存量 | V8 回填：从任务创建时间按票的买受人类型算；已送达的直接标为不参与 | `V8__invoice_report_deadline.sql` |

### 14.2 告警

`InvoiceReportDeadlineJob`（每 10 分钟，分布式锁 `invoice-report-deadline`，与上传任务同一开关 `shop.invoice.transmission.enabled`）调 `InvoiceTransmissionService#checkDeadlines`：

| 级别 | 条件 | 动作 |
|---|---|---|
| 1 预警 | 未送达且过了 `report_warn_at` | `deadline_level → 1`；按主体汇总一条 WARN `INVOICE_REPORT_DEADLINE_WARN` |
| 2 逾期 | 未送达且过了 `report_deadline_at` | `deadline_level → 2`；按主体汇总一条 ERROR `INVOICE_REPORT_OVERDUE` |

- 每条每级只报一次：条件更新 `deadline_level < 目标级别`，多实例同时巡检也只有一个实例报；先判逾期再判预警，直接跨两级的只报逾期。
- 送达即退出：上传成功时 `deadline_level = 2`。`HELD`、`NEEDS_REVIEW` 仍算未送达，照样到期告警 —— 这正是需要人处理的那批。
- 日志按主体汇总，附前 5 条业务号，供日志告警订阅；接运营站内消息仍列为后续（同 §12）。

### 14.3 B 端可见

- `GET /biz/invoices/upload-backlog`（`biz:invoice:read`）：本门店 `pending / held / needsReview / dueSoon / overdue / oldestDeadlineAt`。任务加了 `store_no`（V8 回填）以按门店统计。
- 发票列表顶部：有逾期或暂停时红色、只有即将到期或待人工时黄色提示条。
- 发票详情：未送达时显示「上传期限」，逾期标红。`InvoiceVO.uploadDeadlineAt`。

### 14.4 改动清单

| 文件 | 改动 |
|---|---|
| `db/invoice/V8__invoice_report_deadline.sql` | `store_no`、`report_warn_at`、`report_deadline_at`、`deadline_level`，回填，两个索引 |
| `regime/InvoiceRegime` · `regime/tw/TwEguiRegime` | `reportWindow` |
| `service/impl/InvoiceServiceImpl` | 入队时写门店与期限；详情带期限 |
| `service/InvoiceTransmissionService(Impl)` | `checkDeadlines`、`backlog`；送达即退出巡检 |
| `job/InvoiceReportDeadlineJob`（新） | 巡检任务与任务声明 |
| `api/biz/BizInvoiceController` · `dto/InvoiceDtos` | 积压端点、`UploadBacklogVO`、`uploadDeadlineAt` |
| b-app `pages/sales-invoices` · `pages/sales-invoice` · `api/*` · `i18n` | 提示条、期限展示 |
| 测试夹具 `InvoiceServiceMysqlTest` | `@BeforeEach` 清空桩通道的 `payloads`（原先未清，用例之间会串数） |

### 14.5 测试

| AC | 测试 | 结果 |
|---|---|---|
| AC37 | `InvoiceServiceMysqlTest#ac37DeadlinesWarnOnceEscalateOnceAndBacklogIsPerStore`：B2C 窗口 48 小时；到预警点报 1 次、再巡检不重复；到期限报 1 次；积压按门店统计（他店为 0）；送达后退出积压 | ✅ |
| AC37 | `#ac37BusinessBuyersGetSevenDaysAndJumpingStraightToOverdueReportsOnlyOnce`：B2B 窗口 168 小时；直接跨两级只报逾期 | ✅ |

MySQL 9 全类 30/30、鲸跃 5/5、Turnkey 3/3、b-app `vue-tsc` 无错；契约、孤儿页、i18n、controller 内聚检查对发票无新增。

## §15 号段水位与预领下期（鲸跃 M1-6）

状态：已实现 · 2026-11-22 · 档位 1（`ivc_regime_profile` 加列、设置请求 / 视图加字段、新定时任务、三语文案）· 需求 PRD AC38 · 计划见 [TDD-发票服务-总体设计 §14](./TDD-发票服务-总体设计.md) M1-6。

### 15.1 为什么

号码用尽时开票直接失败（26003 `INVOICE_NUMBER_EXHAUSTED`），门店当场开不出发票；双月换期的第一张票如果下期还没号段，同样失败。两者都要在发生之前发现。

### 15.2 规则

`InvoiceSetupService#watchRanges(now, prefetchBefore)`，由 `InvoiceRangeWatermarkJob` 每 30 分钟调用（分布式锁 `invoice-range-watermark`；开关 `shop.invoice.range-watch.enabled` 默认关，`prefetch-days` 默认 5）：

| 检查 | 条件 | 动作 |
|---|---|---|
| 本期不足 | 本期可用号码（主体内全部 ACTIVE 号段之和）< `range_low_water`（默认 50） | 第一次跌到该级时：开了自动领号就先向鲸跃领号，领到后余量够了不提醒；否则 WARN `INVOICE_RANGE_LOW` |
| 本期用尽 | 可用号码 = 0 | 同上；仍为 0 时 ERROR `INVOICE_RANGE_EMPTY` |
| 下期未备 | 距本期结束 ≤ `prefetch-days` 且下期可用号码 = 0 | 开了自动领号就领下期；仍没有则 WARN `INVOICE_RANGE_NEXT_PERIOD_MISSING` |

- **只报一次**：`range_alert_key` 记「期别:级别」（如 `11512:LOW`），`next_range_alert_key` 记已提醒的下期；同期同级不再报、也不再重复领号。补足后清空，下次跌破重新计；换期自然换键。
- **自动领号的边界**：只对服务商配号的通道（`InvoiceProviderGateway`，鲸跃）；账号暂停中（§12）不领；领号失败（如鲸跃 `exist`）记 WARN `INVOICE_RANGE_AUTO_FETCH_FAILED` 并转为提醒，不中断巡检。下期能否提前申请待鲸跃确认（§14 M0-3），确认前建议只对本期开自动领号或保持关闭。
- **口径**：余量按主体汇总，含门店专用号段；门店专用号段多的主体，个别门店可能先用尽而总量仍够 —— 首期接受，M2 改为按账户 / 门店统计。
- 期别边界放进税制：`InvoiceRegime#periodEnd`（台湾 `TwPeriods.periodEnd`：`11512` → 2027-01-01 台北时间），下期 = `periodOf(periodEnd(本期))`。

### 15.3 B 端

- 发票设置：「剩余号码提醒值」（数字）、「号码不足时自动向鲸跃领号」（仅鲸跃通道显示）。
- 号段区顶部提示：本期期别与剩余张数、下期已备张数；用尽标红、低于提醒值标黄。
- 视图 `RegimeProfileVO` 加 `rangeLowWater / autoFetchRanges / currentPeriod / currentRemaining / nextPeriod / nextRemaining`；请求加 `rangeLowWater`（0–100000）、`autoFetchRanges`，不传 = 不改。

### 15.4 改动清单

| 文件 | 改动 |
|---|---|
| `db/invoice/V9__invoice_range_watermark.sql` | `range_low_water`（默认 50）、`auto_fetch_ranges`、`range_alert_key`、`next_range_alert_key` |
| `regime/InvoiceRegime` · `regime/tw/TwEguiRegime` · `TwPeriods` | `periodEnd` |
| `mapper/InvoiceMappers.NumberRangeMapper` | `remaining`（某期可用号码数） |
| `service/InvoiceSetupService(Impl)` | `watchRanges`、自动领号、视图余量、保存提醒值与开关 |
| `job/InvoiceRangeWatermarkJob`（新） | 巡检任务与任务声明 |
| b-app `pages/invoice-setup` · `api/sales-invoice.ts` · `i18n` | 设置项与余量提示 |

### 15.5 测试

| AC | 测试 | 结果 |
|---|---|---|
| AC38 | `InvoiceServiceMysqlTest#ac38ManualRangesWarnOnceWhenLowOnceWhenEmptyAndOnceForAMissingNextPeriod`：50 < 60 提醒一次、不重复；用尽再提醒一次；补足后清键；离期末 6 天不查下期、1 小时内下期缺号提醒一次 | ✅ |
| AC38 | `#ac38AutoFetchAsksTheProviderOnceAndNeverWhileTheAccountIsHeld`：余量不足自动领号后不提醒（余量 50→100）；期末前自动领下期并入库；账号暂停中只提醒、不调服务商；领号失败转提醒，同期同级不再重复领号 | ✅ |

MySQL 9 全类 32/32、鲸跃 5/5、Turnkey 3/3、b-app `vue-tsc` 无错；契约、孤儿页、i18n 检查通过；定时任务 / 架构 / 权限守卫失败数与改动前一致，均不涉及发票。

