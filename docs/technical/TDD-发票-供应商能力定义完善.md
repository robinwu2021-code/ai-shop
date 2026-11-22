# TDD-发票-供应商能力定义完善

状态：**已实现**（基础改造与两家厂商分两个提交）· 档位：1（供应商层与领域接口完善；B 端可选供应商、运营端卖家与上传任务的返回结构加字段）
关联：[TDD-发票-加值中心模板-鲸跃与汎宇](./TDD-发票-加值中心模板-鲸跃与汎宇.md)（基础类模板）·
[TDD-发票服务-供应商适配抽象](./TDD-发票服务-供应商适配抽象.md)（ADR-064 目标形态：CredentialField、能力接口）·
[TDD-发票-注销与同号重开](./TDD-发票-注销与同号重开.md)
创建：2026-11-22

---

## L1 定位

> 接完鲸跃（SOAP、已上生产）和汎宇（JSON）两家后，回头看基础能力定义：**能力靠 `instanceof` 猜、业务限制到上传才暴露、凭据和失败各说各话**。
> 本文把两家实际暴露出的差异收进基础定义 —— 供应商**声明**自己支持什么，领域**先问再做**，失败**按类处置**，页面**按声明渲染**。
> 目标是第三家（绿界等）接入时，领域与页面一行不改。

---

## §1 现状缺口（每条都有两家的实据）

| # | 缺口 | 实据 | 后果 |
|---|---|---|---|
| G1 | **能力发现靠 `instanceof` + 每个能力一个布尔** | `ProviderOptionVO` 已有 `verifiable / rangeProvided / queryable / trackManageable` 四个布尔；运营端页面另写死 `CETUSTEK`、`["CETUSTEK","UNIVERSALEC"]` | 每加一种能力要改 VO、b-app 类型、页面、运营端常量四处；运营端已经和后端不同步（汎宇的字轨、注销它都不知道） |
| G2 | **上传报文的支持范围没有声明** | 鲸跃、Turnkey 只收开立 / 作废 / 折让 / 折让作废，收到注销直接判终态；领域只能用「有没有 `RevokeStatusCapability`」间接推断能否注销 | 某家支持注销却不提供进度查询、或反过来，就会判错 |
| G3 | **业务限制到上传时才发现** | 两家都不支持零税率（缺零税率原因与通关方式），但开立照常取号落库，上传时才 `FINAL` | 号码已占用、48 小时上传期限开始计时，只能人工作废重开 |
| G4 | **凭据三元语义各家不同，页面只有固定标签** | 鲸跃 officeid / posno / posid；汎宇 SELLERID / POSID / POSSN，且 **SELLERID 就是卖方统编**；B 端固定显示「商家代号 / 账号 / 密码」 | 汎宇商家要把统编再填一遍、可能填错；标签对不上厂商文档，开通时容易填错位 |
| G5 | **失败没有分类** | `ProviderException.code` 有 `invalid`、`nodata`、`exist`、`period_unsupported`、`unexpected:9`、汎宇中文说明 …… | 领域只能原样透传；自动领号、巡检分不清「账号坏了 / 没有数据 / 不支持 / 被拒 / 不可达」，账号坏了也不会暂停该主体 |
| G6 | **QR 金钥来源各家不同** | 鲸跃在核验（`QueryOfficeInfo.QRCode`）回；汎宇在领号（A01 / C01 的 `QRCodeASKey`）回 | 只有核验路径会存，汎宇的金钥拿到也丢了 |
| G7 | **领号期别范围没声明** | 鲸跃任意期别；汎宇只有当期 / 下期，其余抛 `period_unsupported` | 巡检与页面不知道，只能调了再吃异常 |

---

## §2 设计

### D1 能力目录（解决 G1、G2）

```java
/** 供应商能力的标准名：领域、接口、页面只认它。 */
public enum ProviderCapability {
    VERIFY_ACCOUNT,   // AccountCapability
    ALLOCATE_RANGE,   // RangeCapability
    QUERY_STATUS,     // QueryCapability
    MANAGE_TRACKS,    // TrackCapability
    REPORT_BLANK,     // BlankRangeCapability
    REVOKE            // 上传支持 REVOKE 且有 RevokeStatusCapability
}

public record ProviderProfile(Set<String> regimes, NumberingMode numbering, ReportMode reporting,
        boolean requiresAccount,
        Set<ProviderOperation> uploads,            // 新：收哪些上传报文
        Set<InvoiceFeature> features,              // 新：D2
        List<CredentialField> credentials,         // 新：D3
        QrKeySource qrKeySource,                   // 新：D5
        Set<RangeWindow> rangeWindows) { … }       // 新：D6

// InvoiceGateways 统一推导，不让厂商手写（手写会和实现不一致）
public Set<ProviderCapability> capabilities(String code);   // instanceof + uploads 推导
public boolean has(String code, ProviderCapability c);
```

| 供应商 | uploads | capabilities |
|---|---|---|
| 鲸跃 | ISSUE / VOID / ALLOWANCE / ALLOWANCE_VOID | VERIFY_ACCOUNT、ALLOCATE_RANGE、QUERY_STATUS |
| 汎宇 | 同上 + REVOKE | VERIFY_ACCOUNT、ALLOCATE_RANGE、MANAGE_TRACKS、REPORT_BLANK、REVOKE |
| Turnkey | 同鲸跃 | （无） |
| 模拟通道 | 全部 | （无） |

- 领域：`revokeSupported` 改为 `gateways.has(code, REVOKE)`；其余 `capability(..., XxxCapability.class)` 不变（取实现仍按接口）
- 契约：`ProviderOptionVO` 加 `capabilities: string[]`；四个布尔**保留一版**（由能力集推导），前端迁完再删
- 运营端：卖家列表回 `capabilities`，页面按它显示核验 / 领号 / 查单按钮，删掉写死的厂商码

### D2 开立前预检（解决 G3）

两家的报文映射本来就是纯函数（鲸跃 `invoiceXml`、汎宇 `UniversalEcMessages.issue`），把基础类的上传拆成「准备」与「送出」两步，预检就是只跑准备：

```java
public interface InvoiceGateway {
    GatewayReceipt send(GatewayRequest request);
    /** 不出网：按本供应商的映射规则检查这份报文能否送出；能送出返回空，否则返回原因。 */
    Optional<String> preflight(GatewayRequest request);
}

public abstract class AbstractInvoiceGateway {
    public final Optional<String> preflight(GatewayRequest r) {   // 解析 → prepare → 捕获 UnsupportedMessageException
    protected abstract PreparedMessage prepare(GatewayRequest r, JsonNode message);   // 厂商：纯映射
    protected abstract GatewayReceipt deliver(GatewayRequest r, PreparedMessage m);    // 形态 + 厂商：送出、判码
    // send = prepare + deliver；原 upload 拆成这两步（Turnkey、Stub、样例形态同步拆）
}
```

- 领域 `InvoiceServiceImpl#issue / allowance / revoke`：报文生成后、**同一事务提交前**调 `preflight`；不通过 → 新错误码 `INVOICE_PROVIDER_UNSUPPORTED {0}`，事务回滚，**号码不占用**
- `features` 声明（`ZERO_RATED、MIXED_TAX、EXEMPT、B2B、CARRIER、DONATION`）只用于页面提示与选择供应商时的说明；**判据以 preflight 为准**（同一套映射，不会两处不一致）

| 特性 | 鲸跃 | 汎宇 | Turnkey |
|---|---|---|---|
| 零税率 | ❌ | ❌（缺零税率原因 / 通关方式） | ✅ |
| 免税 / 混合课税 | ✅（DType=TN） | ✅（C4=9 + 行课税别） | ✅ |
| B2B（打统编） | ✅ | ✅ | ✅ |
| 载具 / 捐赠 | ✅ | ✅ | ✅ |

### D3 凭据字段声明（解决 G4）

```java
/** 一个凭据字段：放在三元里的哪一位、页面用哪个词条、是否密文、校验、能否由主体资料自动带出。 */
public record CredentialField(Slot slot, String labelKey, boolean secret, String pattern, Derived derivedFrom) {
    public enum Slot { ACCOUNT_CODE, KEY_ID, SECRET }
    public enum Derived { NONE, SELLER_TAX_ID }
}
```

| 供应商 | ACCOUNT_CODE | KEY_ID | SECRET |
|---|---|---|---|
| 鲸跃 | 商家代号（officeid） | posno（密文） | posid（密文） |
| 汎宇 | **卖方统编，自动带出不必填** | POS 机编号 POSID | 通道金钥 POSSN（密文） |

- B 端设置页按 `credentials` 渲染：标签取 `salesInvoice.credential.<CODE>.<slot>`，`derivedFrom=SELLER_TAX_ID` 的字段隐藏、保存时由后端填
- 后端 `saveProfile` 校验 `pattern`；汎宇保存时若填了与卖方统编不同的代号直接拒绝
- 与 ADR-064 的 `CredentialField` 同名同义，日后迁到「营业人 × 账户」表结构时直接沿用

### D4 失败分类（解决 G5）

```java
public class ProviderException extends RuntimeException {
    public enum Kind { ACCOUNT, NOT_FOUND, UNSUPPORTED, REJECTED, UNAVAILABLE, UNEXPECTED }
    public Kind kind();      // 新
    public String code();    // 保留：厂商原文码
    public String detail();  // 新：厂商原文说明（汎宇中文 MESSAGE），可空
}
```

- 基础类 `call` 统一归类：码表 `ACCOUNT_PROBLEM → ACCOUNT`、`RETRYABLE → UNAVAILABLE`、`FINAL → REJECTED`；形态层不可达 → `UNAVAILABLE`；`read` 里解析失败 → `UNEXPECTED`
- 厂商只在特例里指定：鲸跃 `nodata`、汎宇「查無」→ `NOT_FOUND`；汎宇 `period_unsupported` → `UNSUPPORTED`
- 领域：
  - `ACCOUNT` → 与上传同样**暂停该主体**并告警（现在只有上传会暂停）
  - `NOT_FOUND` → 查询类当空结果；领号当「无配号可用」提醒
  - `UNAVAILABLE` → 巡检下轮重试，不告警到人
  - 用户看到的错误 = 分类词条 + 厂商原文（`INVOICE_PROVIDER_REJECTED` 的参数改为 `kind` + `detail`）

### D5 QR 金钥来源（解决 G6）

`QrKeySource { MERCHANT, VERIFY, RANGE }`：鲸跃 `VERIFY`；汎宇在 `QRCodeASKey` 语义确认前为 `MERCHANT`（商家手填），确认后改 `RANGE`，领号时把回包里的金钥存进 secret 模块（`AllocatedRange` 加 `qrKey`）。

### D6 领号期别窗口（解决 G7）

`RangeWindow { CURRENT, NEXT, ANY }`：鲸跃 `ANY`，汎宇 `{CURRENT, NEXT}`。领域领号前先判，不在窗口内直接回 `INVOICE_PROVIDER_UNSUPPORTED`，不打服务商；页面的期别输入按窗口限制。

---

## §3 分期

| 批 | 内容 | 契约 | 验收 |
|---|---|---|---|
| **第一批**（供应商层 + 领域，页面不动） | D1 能力目录与 `uploads`（领域改用 `has`）· D2 预检（拆 prepare / deliver，开立 / 折让 / 注销预检）· D4 失败分类 · D6 期别窗口 | 新错误码 `INVOICE_PROVIDER_UNSUPPORTED`（后端三语）；`ProviderOptionVO` 加 `capabilities`（只加不删） | 鲸跃 7 条、汎宇 26 条测试断言不改；新增：零税率开立在事务内被拒且号段 `next_no` 不动；账号类失败暂停主体；期别窗口外不出网 |
| **第二批**（页面按声明渲染） | D3 凭据字段（B 端设置页、`saveProfile` 校验）· D1 前端改用 `capabilities`、运营端删写死厂商码 · D5 QR 金钥来源 | `ProviderOptionVO` 加 `credentials`、`qrKeySource`；b-app 词条；之后删四个布尔 | b-app / ops-web 类型检查；MySQL 测试覆盖汎宇代号自动带出与校验 |

两批都不动库表。

---

## §4 风险

| 风险 | 缓解 |
|---|---|
| 拆 `upload` 为 `prepare / deliver` 牵动全部形态与鲸跃（已在生产） | 只搬代码；鲸跃与 Turnkey 现有测试断言一行不改作为验收；单独一个提交 |
| 预检与真实上传判据不一致 | 两者调用同一个 `prepare`，没有第二套规则 |
| `INVOICE_PROVIDER_REJECTED` 参数变化影响已有端上提示 | 第一批保留原参数位置（第 0 位仍是厂商码），只追加 |
| 账号类失败自动暂停可能误伤 | 只对码表明确登记为账号问题的码（鲸跃 `invalid`、汎宇 `S020/S022/S024/S026`）生效；核验通过自动解除，与上传暂停同一机制 |

---

## §5 对账（实现后）

| 设计 | 落点 | 测试 |
|---|---|---|
| D1 能力目录 | `ProviderCapability`、`InvoiceGateways#capabilities / has`；`ProviderOptionVO / OpsSellerVO / OpsTransmissionVO.capabilities`；b-app 设置页、运营端电子发票页按能力渲染 | `InvoiceGatewaysTest#capabilitiesAreDerivedFromInterfacesAndDeclaredUploads` · `InvoiceServiceMysqlTest#capProviderOptionsExposeCapabilitiesAndDeclarations` |
| D2 预检 | `InvoiceGateway#preflight`；基础类 `prepare / deliver`；`InvoiceServiceImpl#preflight`（入队前） | `AbstractInvoiceGatewayTemplateTest#preflight_runs_prepare_only · #send_rejects_undeclared_messages_and_mapping_rejections` · `InvoiceServiceMysqlTest#capPreflightRejectionRollsBackWithoutUsingANumber` · 两家 `#capPreflight…` |
| D3 凭据字段 | `CredentialField`；`InvoiceSetupServiceImpl#accountCode`；b-app 按声明渲染 | `InvoiceServiceMysqlTest#capCredentialDeclarationDerivesAndValidatesTheAccountCode` · 两家 `#capDeclares…` |
| D4 失败分类 | `ProviderException.Kind`；基础类 `failureKind / failureDetail`；`InvoiceSetupServiceImpl#call / hold` | `AbstractInvoiceGatewayTemplateTest#call_failures_carry_a_kind` · `InvoiceServiceMysqlTest#capAccountFailuresHoldTheEntityOthersDoNot` · 两家 `#capFailures…` |
| D5 QR 金钥来源 | `QrKeySource`；`AllocatedRange.qrKey`；领号仅 RANGE 时存 | `InvoiceServiceMysqlTest#capRangeWindowAndRangeQrKeyFollowTheDeclaration` · 汎宇 `#capAllocatedRangeCarriesTheReturnedQrKey` |
| D6 期别窗口 | `RangeWindow`；`InvoiceSetupServiceImpl#fetchProviderRange` 先判窗口 | 同上 |

运行（2026-11-22）：invoice-channel + invoice-domain 131 条全过（`InvoiceServiceMysqlTest` 连本机 MySQL 9.7）；**鲸跃 7 条、汎宇 26 条原有测试断言一行未改**；
`BackendI18nParityTest` 6/6；b-app `vue-tsc`、ops-web `tsc` 0 错；契约、i18n 孤儿、RTL、枚举闸门通过。
`ArchitectureTest` 5 条失败均为既有（merchantbridge、inventory、trade、food、portal），与发票无关。

### 偏差说明
- 基础提交里先给汎宇声明「收 REVOKE」：基础类开始按声明拒收未登记的报文，不先声明汎宇的注销测试会红；两家其余声明在厂商提交里。
- `INVOICE_PROVIDER_REJECTED` 有原文说明时追加第 1 位参数（`detail`），第 0 位仍是厂商码，已有提示不受影响。
- 核验回传的 QR 金钥仍一律存（与改造前一致）；只有领号回传的按声明 `RANGE` 才存。
