# TDD-发票模块（多行业 · 首期台湾电子发票与证明联打印）

状态：已实现（首期后端 + 证明联打印；B 端页面、正式通道见 §7 后续）
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
| PUT | `/biz/invoice-setup/profile` | `biz:invoice:setup` | `{regimeCode, sellerTaxId, sellerName, gatewayCode, qrAesKeyHex?, memberCarrierType?}` |
| GET | `/biz/invoice-setup/ranges` | `biz:invoice:setup` | `?periodCode` |
| POST | `/biz/invoice-setup/ranges` | `biz:invoice:setup` | `{periodCode, track, startNo, endNo, storeNo?}` |

证明联打印、补印沿用打印中心 `POST /biz/print/requests`（`sourceRef.type=INVOICE`，`scene=INVOICE_PROOF`）。

**库表**（`db/invoice/V1__invoice_core.sql`，`V2__invoice_role_permissions.sql`）

| 表 | 关键列 | 约束 |
|---|---|---|
| `ivc_regime_profile` | tenant_no, entity_no, regime_code, seller_tax_id, seller_name, gateway_code, qr_key_ref, member_carrier_type, status | uk(tenant, entity, regime) |
| `ivc_number_range` | range_no, tenant/entity, regime_code, store_no(`*`=主体共用), period_code, track, start_no, end_no, next_no, status ACTIVE/EXHAUSTED | uk(tenant, entity, regime, period, track, start_no)；CHECK start≤end |
| `ivc_invoice` | invoice_no, tenant/entity/store, regime_code, industry_code, source_type, source_no, **active_source_key**, fiscal_number, period_code, random_code, issued_at, buyer_type, buyer_tax_id, buyer_name, carrier_type, carrier_id, donate_code, print_mark, currency, sales/tax/total_minor, allowed_minor, tax_type, status, void_*, proof_snapshot JSON, proof_snapshot_hash | uk(tenant, fiscal_number, regime)；uk(tenant, entity, active_source_key)；CHECK allowed≤total |
| `ivc_invoice_line` | invoice_no, line_no, source_line_ref, name, quantity, unit, unit_price_minor, amount_minor, tax_type | uk(invoice_no, line_no) |
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

**i18n**：上述 9 条 × 三语（`messages*.properties`）。**配置项**：`shop.invoice.transmission.enabled`（默认 false）、
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
| 新增 | 测试：`TwRulesTest`、`TwProofCodesTest`、`InvoiceServiceMysqlTest`、`InvoiceBoundaryTest`（invoice-domain），`InvoiceProofLayoutTest`（print-channel） | 见 §5 |
| 修改 | `shop-app/src/test/…/arch/{ArchitectureTest,BizEndpointPermTest,BizRoleSeedTest}.java`、`infra/SchemaDriftTest.java` | 登记 `invoice` 域与 `invoicebridge`、6 个 /biz 端点的权限、模块迁移序列的角色追加、`db/invoice` 重放 |
| 修改 | `backend/scripts/gen-test-schema.py`、`shop-app/src/test/resources/schema-test.sql` | H2 夹具接上 `db/invoice`（只并入发票增量，见 §6） |
| 修改 | `print-channel/src/test/…/ReceiptSoftwareAcceptanceTest.java` | 按场景声明的纸宽渲染（证明联只有 58） |
| 新增 | `docs/api/invoice.openapi.json`、`docs/api/backend-ui-delivery-invoice.json` | 9 个 /biz 端点的公开契约；登记为「后端已交付、B 端页面下一批」，`check-app-backend-orphan.py` 按此计入分阶段交付而不是欠账 |

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
| 子单金额组成（积分、储值抵扣）与发票总计口径 | 开错金额 | 首期只接「明细 + 运费 − 优惠 = 实付」的子单，不满足 → 26002，不伪造金额；储值/积分口径随 CKO07 冻结税据接入 |
| 只有桩通道 | 发票未真正上传 | `ivc_transmission` 留全量报文；正式通道上线后对 PENDING/FAILED 补传；48 小时期限在 B 端可见 |
| 混合税别（零税率、免税） | 不能开 | 首期所有行按应税 5%；`tax_type` 列已留，混合税别随报价税类接入 |

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
| AC15 | `#ac15ListByPeriodAndStatusInTheCurrentStore` | ✅ |
| 设置 | `#setupRejectsOverlappingRangesAndRoleSeedGotInvoicePermissions`（V2 角色追加在真库生效） | ✅ |

实际输出（2026-11-21）：

```
invoice-domain  TwRulesTest 6 · TwProofCodesTest 3 · InvoiceServiceMysqlTest 14 · InvoiceBoundaryTest 1  → Tests run: 24, Failures: 0, Errors: 0
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
| `schema-test.sql` 只并入发票增量 | 本分支已存在与生成器不一致的漂移（他人在途改动），整份重生成会把无关改动一并带入 |

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-11-21 | 用户确认：依据 CKO-24 / CKO07 / 打印中心 / V94；通用方案先实现台湾；直接在 codex/food-next01 开发 |
| 2026-11-21 | 已实现首期；闸门：invoice-domain / print-channel 相关测试全绿（MySQL 9 原生），shop-app 守卫对发票部分全绿，扫描范围见 §5 |

### 后续（不在本期）

1. B 端页面：开票（结账后选载具/统编/捐赠）、发票列表与详情、作废/折让、发票设置（税制、号段），并登记界面清单。
2. 正式加值中心通道（`InvoiceGateway` 新实现）与开启 `shop.invoice.transmission.enabled`；首张证明联用财政部 QR 验证工具实测。
3. 餐饮结账 / 美业结账在付款成功后直接调 `InvoicePort.issue`（目前经 B 端 `/biz/invoices` 按子单开立）。
4. 积分抵扣、称重补差、混合税别的发票口径，随 CKO07 冻结税据接入 `SubOrderInvoiceSourcePortImpl`。
5. 飞鹅等文本通道的原生条码标签适配；自定义角色可分配权限目录登记发票四个码。
