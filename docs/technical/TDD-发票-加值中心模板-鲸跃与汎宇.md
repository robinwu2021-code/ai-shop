# TDD-发票-加值中心模板-鲸跃与汎宇

状态：**P1、P2 已实现 · 注销与 P3 真机联调待做** · 档位：1（供应商层内部重构 + 新增能力接口；新增能力的领域落点与页面在 P2 另列契约）
关联：[TDD-发票模块](./TDD-发票模块.md) §19（供应商接口层）· [TDD-发票服务-供应商适配抽象](./TDD-发票服务-供应商适配抽象.md)（目标形态）·
[TDD-发票-汎宇接入](./TDD-发票-汎宇接入.md)（汎宇首批）
创建：2026-11-22 · 最后更新：2026-11-22

---

## L1 定位

> **系统同时支持鲸跃（Cetustek，SOAP）与汎宇（Universal EC，POS JSON）两家台湾加值中心。**
> 所有动作走同一个模板：**基础发票类 `AbstractInvoiceGateway` 定义固定流程（final），形态层只管「怎么送」，厂商类只管「送什么、回来怎么读、码怎么判」。**
> 加一家厂商 = 选一个形态、写一个子类、填一张码表；开票主流程、上传任务、号段、对账一行不动。

本文把 §19 已有的「上传模板」扩展到**所有动作**（核验、领号、查单、注销、字轨、空白字轨），并把两家的接口**全部**对上。

---

## L2 结构

```
invoice-domain · gateway（接口，领域定义）
  InvoiceGateway ─ 上传：ISSUE / VOID / ALLOWANCE / ALLOWANCE_VOID（+ 新增 REVOKE）
  能力接口：AccountCapability · RangeCapability · QueryCapability          （已有）
            TrackCapability · BlankRangeCapability · RevokeStatusCapability  （新增）

invoice-channel · channel.base（模板与形态）
  AbstractInvoiceGateway                 ← 基础发票类：标准流程模板（final）
    ├─ AbstractSoapInvoiceGateway        ← 形态：SOAP 信封、认证参数在前、取 <return>
    │     └─ CetustekInvoiceGateway      ← 鲸跃
    └─ AbstractHttpApiInvoiceGateway     ← 形态公共：HTTPS 请求 / 响应
          ├─ AbstractEncryptedApi…       （绿界、蓝新，已有）
          ├─ AbstractSignedApi…          （光贸、Peppol，已有）
          └─ AbstractPlainJsonInvoiceGateway   ← 新形态：明文 JSON、凭据写在报文体、单一地址按功能码分发
                └─ UniversalEcInvoiceGateway   ← 汎宇

invoice-channel · channel.tw（台湾公共规则，两家共用，组合不继承）
  TwMappingRules   B2B 判定、开法（纸本 / 载具 / 捐赠）、零税率判定、日期格式、可选字段
```

**为什么台湾公共规则不进继承树**：两家形态不同（SOAP / JSON），Java 单继承下「台湾」和「形态」只能有一个做父类。
形态决定传输代码，放继承；台湾规则是纯函数，放组合 —— 与 §19「编号方式不进继承树」同一个理由。

---

## §1 标准流程模板（基础发票类）

> 2026-11-22 与用户确认：**扩展现有基类，不另起一套**。上传沿用 `send()` + `upload` 钩子（五种形态不动），
> 能力动作另加 `call` 模板；命名保留 `Gateway`，ADR-064 落地时再统一为 `Provider`。

### 1.1 统一动作 `ProviderOperation`（`invoice-domain · gateway`）

```java
public enum ProviderOperation {
    ISSUE, VOID, ALLOWANCE, ALLOWANCE_VOID,   // 上传类：名字 = 上传任务 messageType，走 send()
    VERIFY, RANGE, QUERY;                      // 能力类：走 call()；P2 按需追加 REVOKE / TRACK_* / BLANK_REPORT
}
```

码表按它分类（`ResultCodeTable.classify(op.name(), code)`），留痕按它记。

### 1.2 两个 final 入口（`AbstractInvoiceGateway`）

| 入口 | 步骤 |
|---|---|
| `send(GatewayRequest)`（上传任务调） | 账号核对 → 解析 MIG → 厂商 `upload`（组报文、经形态层送出、`receipt` 按码表出回执）→ 异常归类 → **留痕一行** |
| `call(op, account, exchange, read)`（能力方法调） | 账号核对（无账号抛 `account_missing`）→ 厂商 `exchange`（组请求、经形态层送出、解读成 `ProviderReply`）→ 码表判定 → **留痕一行** → 成功才 `read`；失败抛 `ProviderException(failureCode)`；形态层的不可达原样上抛 |

```java
protected final <T> T call(ProviderOperation op, ProviderAccount account,
                           Supplier<ProviderReply> exchange, Function<ProviderReply, T> read);
protected String failureCode(ProviderOperation op, ProviderReply reply);   // 默认结果码小写；厂商可按动作改写
protected Set<String> secretValues(ProviderAccount account);               // 默认 keyId + secret
protected final void traceWire(String operation, ProviderAccount account, String request, String response);
protected final String mask(String text, ProviderAccount account);         // 原值与 XML 转义写法都打码

public record ProviderReply(String code, String providerRef, Map<String, String> data, String raw) {}
```

厂商的能力方法因此只剩一句，例（汎宇核验）：

```java
return call(VERIFY, account, () -> exchange(account, "Y01", index("Y01", account, now())),
            r -> new ProviderOffice(r.data().getOrDefault("SELLERID", account.accountCode()), null, null));
```

### 1.3 留痕

- 每次调用 INFO 一行：供应商、动作、账号代号、判定、结果码、耗时（上传另带单号与回执号）。
- 线上原文只在 DEBUG 记，由形态层送出后调 `traceWire`（SOAP 的 `soap()`、HTTP 的 `callApi()` 已接入），密钥值打码。
- 调用日志表（`ivc_provider_call_log`）留到 ADR-064 阶段。

### 1.4 三层分工

| 层 | 负责 | 不负责 |
|---|---|---|
| 基础发票类 | 两个入口的步骤顺序、账号前置、判码、留痕打码、异常归类 | 任何协议与字段 |
| 形态类 | 封装、传输、去外层、送出后 `traceWire`；HTTP 异常 → `ProviderException` | 码的含义、字段映射 |
| 厂商类 | 报文映射、回包解读成 `ProviderReply`、码表、`failureCode`、实现哪些能力 | 传输、留痕、异常 |

### 1.5 新形态 `AbstractPlainJsonInvoiceGateway`

明文 JSON、凭据在报文体、单一地址按功能码分发。本层管：POST JSON、取回包根、空值归一（`null` / `"null"` / 空串）、
结果码缺省按回复标志归一（成功 → `successCode()`，否则 `REPLY<标志>`）、回包所有标量字段进 `data`；
厂商只声明 `replyRoot / codeField / replyField / successCode / referenceField`，用 `exchange(account, 功能码, 报文)` 送出。

## §2 两家完整接口对照

| 标准动作 | 鲸跃（SOAP 操作） | 汎宇（功能码） | 本期 |
|---|---|---|---|
| 开立 ISSUE | `UploadInvoice` | `C0401`（买方有统编也用它，不用 A0401） | 两家 ✅ |
| 作废 VOID | `UploadCancelInvoice` | `C0501` | 两家 ✅ |
| 折让 ALLOWANCE | `UploadAllowance` | `D0401N`（D0401 要原明细序号，税制没有） | 两家 ✅ |
| 折让作废 ALLOWANCE_VOID | `UploadCancelAllowance` | `D0501` | 两家 ✅ |
| 注销 REVOKE | 无 | `C0701` | 汎宇（P2） |
| 注销进度 REVOKE_STATUS | 无 | `Z11` | 汎宇（P2） |
| 账号核验 VERIFY | `QueryOfficeInfo`（回统编、名称、QRCode） | `Y01` | 两家 ✅ |
| 领号段 RANGE | `ApplyInvoiceNumber` → `TakeInvoiceNumber` | 当期 `A01` / 下期 `C01` | 两家 ✅ |
| 查单 QUERY | `QueryInvoiceStatus` | 无 | 鲸跃 ✅ |
| 已下载字轨 TRACK_QUERY | 无 | `Z31` | 汎宇（P2） |
| 上传下期字轨 TRACK_UPLOAD | 无 | `Z32` | 汎宇（P2） |
| 配号方式 TRACK_MODE | 无 | `Z33` | 汎宇（P2） |
| 空白未使用字轨 BLANK_REPORT | 待鲸跃确认 | `E0402` | 汎宇（P2） |

**明确不接**（汎宇）：`Z21` / `Z22`（逐号展开，与 A01 / C01 重复，只多随机码与 AESKEY —— 随机码由税制生成）、`B0701` 批次注销、
`E0401` 分支机构配号（无总分支需求）、B2B 存证 `A0401 / A0501 / B0401 / B0501` 与交换 `A0101 / A0201 / B0101`（平台存证 C0401 已覆盖带统编的买方）、
`C0401N`（与 C0401 等价，规格书示例有误）。

### 2.1 新增能力接口（`invoice-domain · gateway`）

```java
/** 字轨管理（汎宇 Z31 / Z32 / Z33）。 */
public interface TrackCapability {
    List<TrackRange> downloadedTracks(ProviderAccount a, String periodCode);                 // Z31
    void uploadNextTracks(ProviderAccount a, String periodCode, String invoiceType,
                          String track, List<TrackRange> ranges);                           // Z32，区间 ≤ 9999 本
    AllocationMode allocationMode(ProviderAccount a, String periodCode);                     // Z33：NONE / AUTO / MANUAL
    record TrackRange(String track, String startNo, String endNo, String invoiceType) {}
    enum AllocationMode { NONE, AUTO, MANUAL }
}

/** 空白未使用字轨上报（汎宇 E0402）。期末由领域按 ivc_number_range 算出未用区间后调用。 */
public interface BlankRangeCapability {
    void reportBlank(ProviderAccount a, String periodCode, String invoiceType, String track,
                     List<TrackCapability.TrackRange> blanks);
}

/** 注销进度（汎宇 Z11）；注销本身是上传类报文 REVOKE（F0701 → C0701）。 */
public interface RevokeStatusCapability {
    RevokeState revokeStatus(ProviderAccount a, String fiscalNumber, LocalDate invoiceDate);  // 1 完成 / 2 进行中 / 3 失败
    enum RevokeState { DONE, PENDING, FAILED }
}
```

---

## §3 厂商类要写的东西

### 3.1 鲸跃 `CetustekInvoiceGateway`（迁移，行为不变）

| 钩子 | 内容 |
|---|---|
| `map` | ISSUE / VOID / ALLOWANCE / ALLOWANCE_VOID → `invoiceXml` / `cancelXml` / `allowanceXml` / `cancelAllowanceXml`（现有代码搬入）；VERIFY → `QueryOfficeInfo`；RANGE → `ApplyInvoiceNumber`（取得 applyid 后第二步 `TakeInvoiceNumber`，两步都走 `call`）；QUERY → `QueryInvoiceStatus` |
| `parse` | `<return>` 原文即结果码；VERIFY 解 `<ID>/<Name>/<QRCode>`；RANGE 解 `prefix;start;end;type`；QUERY 映射 0/1/2/3/nodata |
| 码表 | 不变：S0/S2、C0/C5、A0/A6、C0/C4 送达；invalid 账号；error 可重试 |
| 能力 | Account、Range、Query |

### 3.2 汎宇 `UniversalEcInvoiceGateway`（改继承 `AbstractPlainJsonInvoiceGateway`，补全接口）

| 钩子 | 内容 |
|---|---|
| `map` | 上传类 → `{"Invoice":{…}}`（C0401 / C0501 / D0401N / D0501 / C0701，字段见 [TDD-发票-汎宇接入](./TDD-发票-汎宇接入.md) §2）；VERIFY / RANGE → `{"INDEX":{FUNCTIONCODE: Y01 / A01 / C01 …}}`；Z11 / Z31 / Z33 → `{"INDEX"…}` 或 `{"Invoice"…}`（按规格书示例）；Z32 → `INVOICE_ITEM[]`；E0402 → `Main{HeadBan, BranchBan, InvoiceType, YearMonth, InvoiceTrack}` + `Details.BranchTrackBlankItem[]` |
| `parse` | `INDEX.ERROR_CODE`（缺省按 REPLY）；数据键：`SELLERID`、`TAXMONTH`、`INVOICEHEADER/START/END`、`TYPE`、`STATUSCODE`（Z11）、`INVSTATUS`（Z33）、`INVOICE_DATA[]`（Z31） |
| 码表 | `0000` 通用送达；开立 `E02`、作废 `E07` 送达；`S020/S022/S024/S026` 账号；`S010/S012/S030/S032/S034/REPLY0` 可重试；其余 FINAL |
| 能力 | Account、Range、Track、BlankRange、RevokeStatus（P2 起） |
| 前置校验 | 零税率拒送（缺零税率原因与通关方式）；Z32 区间 `(end − start + 1) / 50 ≤ 9999`、只接受下一期 |

---

## §4 领域落点（P2）

P2 本期做**字轨管理**与**空白字轨上报**；**注销（C0701 / Z11）暂缓**：它改变发票生命周期（注销后同号重开），
需要先定领域状态与运营流程，另起一期。

### 4.1 验收标准

- **P2-AC1** Given 主体的生效供应商有字轨能力（汎宇）When 商家在 B 端发票设置查看某期字轨 Then 显示汎宇侧配号方式（Z33：无 / 自动 / 手动）与已下载字轨区间（Z31）；汎宇回「查無」时显示为空而不是报错
- **P2-AC2** Given 同上 When 商家上传下期字轨（字轨、发票类别、若干起迄号）Then 调 Z32；只接受下一期、字轨两位大写、号码 8 位且起 ≤ 迄、每段为 50 的整倍数、合计不超过 9999 本，不合规不打汎宇
- **P2-AC3** Given 期别已结束、号段有未用号码、生效供应商有空白字轨能力 When 号段巡检运行 Then 按（期别、字轨、发票类别）合并未用区间调 E0402，成功后号段记 `blank_reported_at`，同一段不重复上报；失败记原因、下轮重试，不影响其它主体
- **P2-AC4** Given 供应商没有字轨 / 空白字轨能力（鲸跃、Turnkey、STUB）When 调字轨接口 Then 回 `INVOICE_STATE_INVALID`；巡检跳过该主体
- **P2-AC5** B 端可选供应商列表带 `trackManageable`，页面只对有字轨能力的供应商显示「字轨」区块

### 4.2 契约

| 项 | 内容 |
|---|---|
| 端点 | `GET /biz/invoice-setup/provider-tracks?periodCode=`（缺省当期）→ `ProviderTracksVO{periodCode, allocationMode, tracks[{invoiceType, track, startNo, endNo}]}` |
| | `POST /biz/invoice-setup/provider-tracks`，体 `ProviderTrackUploadReq{periodCode, invoiceType, track, ranges[{startNo, endNo}]}` → `ProviderTrackUploadVO{periodCode, track, books}` |
| | `GET /biz/invoice-setup/providers` 的每项加 `trackManageable` |
| 权限码 | 沿用 `biz:invoice:setup` |
| 错误码 | 沿用：`INVOICE_STATE_INVALID`（供应商无此能力）、`INVOICE_RANGE_INVALID`（区间不合规）、`INVOICE_PROVIDER_REJECTED {0}`（汎宇拒绝，`{0}` 为汎宇说明） |
| 迁移 | `invoice-domain/src/main/resources/db/invoice/V12__invoice_blank_report.sql`：`ivc_number_range` 加 `blank_reported_at DATETIME NULL`、`blank_report_error VARCHAR(255) NULL` |
| 定时任务 | 并入 `InvoiceRangeWatermarkJob`（同一开关 `shop.invoice.range-watch.enabled`），结果多一项 `blank=n` |
| i18n | b-app `salesInvoice.track*` 词条三语 |
| 能力接口 | `TrackCapability`（`allocationMode` Z33、`downloadedTracks` Z31、`uploadNextTracks` Z32）、`BlankRangeCapability`（`reportBlank` E0402）；`ProviderOperation` 加 `TRACK_MODE / TRACK_QUERY / TRACK_UPLOAD / BLANK_REPORT` |

### 4.3 模块

| 动作 | 路径 |
|---|---|
| 新增 | `invoice-domain/.../gateway/TrackCapability.java`、`BlankRangeCapability.java` |
| 修改 | `invoice-domain/.../gateway/ProviderOperation.java` |
| 修改 | `invoice-domain/.../service/InvoiceSetupService.java` + `impl/InvoiceSetupServiceImpl.java`：`providerTracks`、`uploadProviderTracks`、`reportBlankRanges` |
| 修改 | `invoice-domain/.../api/biz/BizInvoiceSetupController.java`、`dto/InvoiceDtos.java`、`entity/IvcNumberRange.java`、`job/InvoiceRangeWatermarkJob.java` |
| 新增 | `invoice-domain/src/main/resources/db/invoice/V12__invoice_blank_report.sql` |
| 修改 | `invoice-channel/.../universalec/UniversalEcInvoiceGateway.java`、`UniversalEcMessages.java`；`base/AbstractPlainJsonInvoiceGateway.java`（数组字段以 JSON 文本进 `data`） |
| 修改 | b-app `api/sales-invoice.ts`、`sales-invoice-http.ts`、`endpoints.ts`、`mocks/sales-invoice.ts`、`pages/invoice-setup/index.vue`、`i18n/locale/*.ts` |
| 测试 | `UniversalEcInvoiceGatewayTest`（Z31 / Z32 / Z33 / E0402）、`InvoiceServiceMysqlTest`（字轨与空白字轨） |

### 4.4 待汎宇确认（不挡开发）
- E0402 在无总分支机构时 `HeadBan = BranchBan = 卖方统编`
- Z32 是否要求每段 50 的整倍数（规格书只写「不可超过 9999 本」，示例为 50 的倍数；本期按整倍数校验）

## §5 分期

| 期 | 内容 | 验收 |
|---|---|---|
| **P1 模板化**（已实现） | 基础发票类加 `call` 能力模板、留痕打码；SOAP / HTTP 形态接入 `traceWire`；新增 `AbstractPlainJsonInvoiceGateway`、`channel.tw.TwMappingRules`；鲸跃、汎宇的能力方法迁到 `call` | **测试断言一行不改**：鲸跃 7/7、汎宇 19/19、Turnkey、base 全绿；新增 `AbstractInvoiceGatewayTemplateTest`（步骤顺序、打码、异常归类） |
| **P2 汎宇补全**（已实现） | 字轨（Z31/Z32/Z33）与空白字轨（E0402）能力、领域服务、B 端页面（§4）；注销 C0701/Z11 暂缓 | 每个功能码一条假服务测试；领域服务测试；b-app / ops-web 类型检查 |
| P3 真机联调 | 汎宇测试机、鲸跃（经 UME166 隧道）各走一遍：开立、作废、折让、折让作废、核验、领号；汎宇再走字轨与空白字轨 | 结果贴回本节 |

---

## §6 风险

| 风险 | 缓解 |
|---|---|
| 另一会话正在改鲸跃适配器（`9f486c49b` Host 头） | P1 只搬代码不改行为，单独一个提交；动手前 `git status` 确认鲸跃文件无他人未提交改动 |
| 模板改动波及 Turnkey / Stub / 加密 / 签名形态 | 这些形态的 `upload` 先由基类默认实现桥接到新钩子，测试全跑；不在本期改它们的内部 |
| 汎宇 A01 / C01 每调一次是否新配号段、`QRCodeASKey` 是否 QR 金钥、是否限台湾出口 | P3 向汎宇确认；领号仍只在水位不足时触发，号段入库去重 |
| E0402 的总 / 分支统编：无总分支时 HeadBan = BranchBan = 卖方统编（待汎宇确认） | P2 实现前确认 |

---

## §7 对账

| AC | 测试 | 跑过 |
|---|---|---|
| P1-1 模板步骤顺序、失败不读数据、无账号先拦、不可达原样上抛、打码 | `AbstractInvoiceGatewayTemplateTest`（6 条） | ✅ |
| P1-2 鲸跃行为不变 | `CetustekInvoiceGatewayTest`（7 条，文件未改） | ✅ |
| P1-3 汎宇行为不变 | `UniversalEcInvoiceGatewayTest`（19 条，文件未改） | ✅ |
| P1-4 其它形态不受影响 | `AbstractInvoiceGatewayTest`、`EncryptedApi…`、`Network…`、`ResultCodeTable…`、`AesCbcCipher…`、`TurnkeyInvoiceGatewayTest` | ✅ |
| P1-5 领域侧注册与边界 | `InvoiceBoundaryTest`、`InvoiceGatewaysTest`、`InvoiceGatewayPolicyTest`、`TwRulesTest`、`TwProofCodesTest`、`OpsEntityScopeTest` | ✅ |

运行（2026-11-22，`mvn -o -pl invoice/invoice-channel -am test`）：invoice-channel 9 个测试类 45 条全过；invoice-domain 上列 6 类 18 条全过。

### 设计 → 实现

```
新增 invoice-domain/.../gateway/ProviderOperation.java
修改 invoice-channel/.../base/AbstractInvoiceGateway.java          call 模板、留痕、打码、ProviderReply
修改 invoice-channel/.../base/AbstractSoapInvoiceGateway.java      soap() 送出后 traceWire
修改 invoice-channel/.../base/AbstractHttpApiInvoiceGateway.java   callApi() 送出后 traceWire
新增 invoice-channel/.../base/AbstractPlainJsonInvoiceGateway.java
新增 invoice-channel/.../tw/TwMappingRules.java
修改 invoice-channel/.../cetustek/CetustekInvoiceGateway.java      verify / queryStatus / allocate 走 call；映射用 TwMappingRules
新增 invoice-channel/.../universalec/*（改继承 PlainJson，能力走 call）
新增 invoice-channel/src/test/.../base/AbstractInvoiceGatewayTemplateTest.java
```

### P2 对账

| AC | 测试 | 跑过 |
|---|---|---|
| P2-AC1 字轨查询，「查無」为空 | `UniversalEcInvoiceGatewayTest#track_mode_uses_z33` · `#downloaded_tracks_use_z31_and_drop_placeholders` · `InvoiceServiceMysqlTest#p2ProviderTracksQueryAndUploadValidateBeforeCallingTheProvider` | ✅ |
| P2-AC2 上传下期字轨与区间校验 | `UniversalEcInvoiceGatewayTest#upload_next_tracks_uses_z32` · `InvoiceServiceMysqlTest#p2ProviderTracksQueryAndUploadValidateBeforeCallingTheProvider` | ✅ |
| P2-AC3 空白字轨上报一次、失败重试 | `UniversalEcInvoiceGatewayTest#report_blank_uses_e0402_with_eight_digit_numbers` · `InvoiceServiceMysqlTest#p2BlankRangesAreReportedOncePerRangeAndRetriedOnFailure` | ✅ |
| P2-AC4 / AC5 无能力拒绝与跳过、`trackManageable` | `InvoiceServiceMysqlTest#p2ProvidersWithoutTrackCapabilityAreRejectedAndSkipped` | ✅ |
| 页面 | b-app `vue-tsc --noEmit`、ops-web `tsc --noEmit` | ✅ 0 错 |

运行（2026-11-22）：invoice-channel 9 类 49 条全过；invoice-domain 7 类 58 条全过，其中 `InvoiceServiceMysqlTest` 40 条连本机 MySQL 9.7（3308，随机库）。

闸门：`gen-ui-catalog`、`check-app-contract`、`check-app-orphan`、`check-i18n-orphan`、`check-rtl-physical`、`check-ops-contract`、`check-enums` 通过。
`check-app-backend-orphan`、`check-controller-cohesion`、`check-sql-portability`、`check-generated-docs`、`check-shared-guards`、`check-enum-fields` 失败，
在干净的 HEAD 上同样失败，失败项均不涉及发票（c-app 端点、`BizPayOnboardingController` / `BizStockReplenishmentController`、`SchemaDriftTest` 等）。
**`check-generated-docs` 因 `mRpState`（库存补货）挡在 B 端 OpenAPI 生成前，本次新增的两个 B 端端点未能进生成文档**，那条修好后重跑生成器即可。

P2 改动文件：

```
新增 invoice-domain/.../gateway/TrackCapability.java、BlankRangeCapability.java
修改 invoice-domain/.../gateway/ProviderOperation.java
修改 invoice-domain/.../service/InvoiceSetupService.java、impl/InvoiceSetupServiceImpl.java
修改 invoice-domain/.../api/biz/BizInvoiceSetupController.java、dto/InvoiceDtos.java、entity/IvcNumberRange.java、
     mapper/InvoiceMappers.java、job/InvoiceRangeWatermarkJob.java
新增 invoice-domain/src/main/resources/db/invoice/V12__invoice_blank_report.sql
修改 invoice-domain/src/test/.../InvoiceServiceMysqlTest.java（迁移数 11 → 12、FakeProvider 加两项能力、3 条 P2 测试）
修改 shop-app/src/test/resources/schema-test.sql（ivc_number_range 两列）
修改 invoice-channel/.../base/AbstractPlainJsonInvoiceGateway.java（数组字段进 data）
修改 invoice-channel/.../universalec/UniversalEcInvoiceGateway.java、UniversalEcMessages.java、UniversalEcInvoiceGatewayTest.java（+4 条）
修改 b-app/src/api/sales-invoice.ts、sales-invoice-http.ts、endpoints.ts、mocks/sales-invoice.ts、pages/invoice-setup/index.vue、i18n/locale/{zh-CN,en,ar}.ts
修改 packages/shared/src/contract/enum-registry.ts（SiTrackMode）
```

P2 偏差：
- 运营端未加字轨入口（§4 原稿提到），本期只做 B 端设置页；运营端有需要再加。
- 「上一期」由税制按天回退求得（不假设两个月），空白上报只看上一期 —— 更早的期别已过法定上报期限，不自动补报。

### 偏差说明
- 原稿 §1 的 `map / exchange / parse` 三钩子会牵动五种形态，按用户确认改为「上传不动、能力加 `call`」。
- 原稿的 `TwMigView`（类型化视图）未做：两家只需要几条判断，`TwMappingRules` 静态函数足够；第三家台湾厂商接入时再评估。
