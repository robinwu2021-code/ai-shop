# TDD-支付服务-接入守卫模块 pay-guard · 代码结构、代码清单与数据库设计

状态：**草稿**（随 [TDD-支付服务-接入守卫模块pay-guard](./TDD-支付服务-接入守卫模块pay-guard.md) v4.1 一起确认；[ADR-045](./ADR/ADR-045-支付接入守卫与网关集成在支付服务.md)）
迁移号：按[开发计划](./开发计划-支付接入守卫与独立部署.md) §0.2 —— `db/pay` 号须从 1 连续、先合先占：请求记录来源已占 **V13**；终端、开放 API 加解密、W6 对账合入时取下一个空号（下文的 V15 / V17 是计划序）
基线：`codex/ai-shop-codex`（2026-10-27，已含 W5 密钥模块、I5 接入层、K1-DB `ed7d5aed6`；`db/pay` 已到 V12）
创建：2026-10-27 · 最后更新：2026-10-27

> **v4 调整（守卫 TDD §2.7）**：`pay-svc` 的入口改为外部 API、POS、渠道回调、收银台、内部五个；新增 `guard.callback.CallbackGuard`（渠道验签前移到过滤器）；
> `guard.ops.OpsGuard`、`guard.biz.BizGuard` 只在过渡期内嵌形态存在，目标形态下商家 / 运营的支付功能由 `shop-app` 认人后经 `/internal/pay/v1/biz|ops/**` 调支付；
> 业务侧新增 `InternalCallFilter`（`shop-app`）与 `CallSourceClientInterceptor`（`shop-base`）。下面的目录树与清单以 v2 为底，按这三点增删。

---

## §0 它在哪：是支付服务的一个 Maven 子模块，不是新服务

```
backend/pay/                      ← 支付服务（聚合 pom：pay-modules）
├── pay-channel                   渠道协议
├── pay-domain                    支付业务
├── pay-guard          🆕         接入守卫（本篇）
├── pay-portal                    对外 controller
└── pay-svc                       独立形态的内部端点进程
```

| 问题 | 回答 |
|---|---|
| 是不是一个新进程 / 新服务？ | **不是**。`pay-guard` 是 `backend/pay/` 下的一个 jar，和 `pay-domain`、`pay-portal` 平级，归支付服务所有 |
| 目标形态（v3，用户 2026-10-27 定）怎么运行？ | **打进 `pay-svc`**：支付服务进程装 `pay-guard` + `pay-portal`，六个入口都在这里；`shop-app` 与其他业务只经 `/internal/pay/v1` 调支付，自己不再装 `pay-portal` / `pay-guard`（守卫 TDD §2.7） |
| 将来网关独立呢？ | 同一个 jar 换到新进程 `pay-gateway`（`pay-guard` + `pay-portal`），由它经 `/internal/pay/v1` 调 `pay-svc`；`pay-svc` 只剩内部守卫 + 支付核心 |
| 切换之前（今天） | 支付整体内嵌在 `shop-app`（`shop.pay.deployment` 默认内嵌）；`pay-svc` 在线上空跑 |
| 谁能依赖它？ | 只有 `pay-portal`、`pay-svc`（以及过渡期的 `shop-app`）。**业务域（商城、餐饮…）不依赖它**，它们只依赖 `shop-base` 的 `spi.pay.CallSource` |
| 它依赖谁？ | `pay-domain`（`PayCaller`、接入方 / 终端数据、请求记录）· `secret-api`（`KeyVault` / `KeyRegistry`）· `shop-base-auth`（平台运营 / 商家会话）· `shop-base`（`spi.pay`） |

```
          shop-base（spi.pay.CallSource / PaymentPort）◀──── 商城 / 餐饮 / 零售 / 美业 …
                ▲
shop-base-auth  │   secret-api
      ▲         │      ▲
      └──── pay-guard ─┘
               │  ▲  ▲
               ▼  │  └──────── pay-svc（独立形态：只装 INTERNAL）
          pay-domain          pay-portal（对外 controller）◀── shop-app（内嵌形态：全部装）
               │
          pay-channel
```

---

## §1 代码结构

### 1.1 `backend/pay/pay-guard` 目录树

```
backend/pay/pay-guard/
├── pom.xml
└── src/
    ├── main/java/ai/neargo/shop/pay/api/guard/
    │   ├── PayEntry.java                         入口枚举：INTERNAL / OPEN / TERMINAL / OPS / BIZ / CHECKOUT + 前缀
    │   ├── GuardEntryCondition.java              @ConditionalOnGuardEntry 的实现：按 shop.pay.guard.entries 装配
    │   ├── ConditionalOnGuardEntry.java          注解
    │   │
    │   ├── core/                                 管线：所有入口共用
    │   │   ├── GuardFilter.java                  ①–⑫
    │   │   ├── GuardFilters.java                 按入口给出过滤器；一个入口恰好一个 EntryGuard
    │   │   ├── EntryGuard.java                   入口差异接口
    │   │   ├── GuardResult.java                  认出 / 拒绝 + 原因
    │   │   ├── GuardedRequest.java               缓存体 + 头 + 可信 IP 的只读视图
    │   │   ├── GuardedCaller.java                PayCaller + 记录用的主体信息
    │   │   ├── CachedBodyRequest.java            请求体缓存（上限 1MB）
    │   │   ├── GuardMasking.java                 请求 / 响应脱敏
    │   │   ├── GuardErrors.java                  失败原因 → 对外错误码
    │   │   ├── PortalActions.java                路径 → PayAction（记录用）
    │   │   └── RequestLogWriter.java             组装并写 pay_api_request_log（GuardFilter 与 InternalCallRecorder 共用）
    │   │
    │   ├── sign/                                 签名：外部 API 与 POS 共用
    │   │   ├── SignatureCanonicalizer.java       请求待签串（v1 冻结）+ 响应待签串
    │   │   ├── SignedHeaders.java                读签名头（App / Terminal 两套）
    │   │   ├── HmacVerifier.java                 KeyVault.mac + 常量时间比较，返回命中版本
    │   │   ├── ResponseSigner.java               响应 / 推送签名（P2）
    │   │   ├── NonceStore.java
    │   │   ├── DbNonceStore.java                 pay_api_nonce 唯一键
    │   │   └── RedisNonceStore.java              SET NX EX
    │   │
    │   ├── crypto/                               字段与推送加解密（外部 API）
    │   │   ├── SensitiveFields.java              x-sensitive 字段清单的唯一来源
    │   │   ├── FieldDecryptor.java               P3：Base64(IV‖密文‖Tag) / RSA-OAEP
    │   │   └── ResourceCipher.java               P4 推送 resource、P5 对账单文件
    │   │
    │   ├── internal/                             A · 内部系统：不校验，只留痕
    │   │   ├── InternalGuard.java                /internal/pay/v1：识别 X-Internal-Caller + X-Call-Source，不拒绝
    │   │   ├── CallSourceCodec.java              CallSource ↔ X-Call-Source（Base64URL JSON）
    │   │   ├── InternalCallRecorder.java         内嵌形态：包住 PaymentPort，每次调用写一行记录
    │   │   ├── RecordingPaymentPort.java         PaymentPort 的装饰器（@Primary），内部转调 PaymentPortImpl
    │   │   ├── InternalCallers.java              内嵌形态的 PayCaller 工厂
    │   │   ├── InternalCallerRegistry.java       仅 verify-credential=true 时使用
    │   │   └── ConfigInternalCallerRegistry.java
    │   │
    │   ├── open/                                 B · 外部 API
    │   │   ├── OpenApiGuard.java
    │   │   ├── AppSignatureVerifier.java         HMAC / RSA 验签，不把材料交给调用方
    │   │   ├── SecretAppSignatureVerifier.java   密钥来自 sec_key（key-source=secret）
    │   │   ├── ConfigAppSignatureVerifier.java   ⏸ 过渡：密钥来自部署配置（key-source=config）
    │   │   ├── AppScopes.java                    scope × pay_app_grant → PayCaller
    │   │   ├── IpAllowlist.java                  单 IP + IPv4 CIDR
    │   │   ├── PartnerKeyService.java            AppSecret / EncryptKey 发放
    │   │   └── impl/PartnerKeyServiceImpl.java
    │   │
    │   ├── terminal/                             C · POS 终端
    │   │   ├── TerminalGuard.java                激活接口豁免验签、其余验设备密钥签名
    │   │   ├── TerminalActivationService.java
    │   │   ├── TerminalKeyService.java           设备密钥生成 / 挂失吊销
    │   │   └── impl/
    │   │       ├── TerminalActivationServiceImpl.java
    │   │       └── TerminalKeyServiceImpl.java
    │   │
    │   ├── ops/                                  D · 运营端：平台账号体系
    │   │   └── OpsGuard.java                     确认平台运营会话 → PayCaller(OPS)
    │   │
    │   ├── biz/
    │   │   └── BizGuard.java                     平台商家会话 + 门店范围（规则不变）
    │   │
    │   ├── checkout/
    │   │   ├── CheckoutGuard.java                收银令牌 / 码号（规则不变）
    │   │   └── CheckoutCallers.java              收银台 PayCaller 工厂（取代 CheckoutServiceImpl 里的两处 new）
    │   │
    │   └── web/
    │       ├── CallerArgumentResolver.java       controller 参数 PayCaller 自动注入
    │       └── GuardWebConfig.java               注册上面的解析器
    │
    └── test/java/ai/neargo/shop/pay/api/guard/
        ├── core/GuardPipelineTest.java
        ├── core/EntryIsolationTest.java
        ├── sign/SignatureCanonicalizerTest.java      ♻️ 自 pay-domain
        ├── sign/HmacVerifierTest.java
        ├── crypto/FieldDecryptorTest.java
        ├── internal/InternalGuardTest.java
        ├── internal/InternalCallRecorderTest.java
        ├── internal/CallSourceCodecTest.java
        ├── open/OpenApiGuardTest.java
        ├── open/PartnerKeyServiceTest.java           ♻️ 自 pay-domain
        ├── open/SecretAppSignatureVerifierTest.java  ♻️ 自 pay-domain SecretAppKeyResolverTest
        ├── terminal/TerminalGuardTest.java
        ├── ops/OpsGuardTest.java
        └── arch/GuardArchitectureTest.java           PayCaller 构造点、依赖方向、ops 不引用 Perms
```

### 1.2 `pom.xml`

```xml
<artifactId>pay-guard</artifactId>
<dependencies>
    <dependency><groupId>ai.neargo</groupId><artifactId>pay-domain</artifactId></dependency>
    <dependency><groupId>ai.neargo</groupId><artifactId>secret-api</artifactId></dependency>
    <dependency><groupId>ai.neargo</groupId><artifactId>shop-base-auth</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
    <!-- 测试：secret-core（DatabaseKeyVault 跑真向量）、h2、archunit -->
</dependencies>
```

**不依赖** `pay-portal`、`pay-svc`、`secret-core`（实现由装配方提供，`pay-svc` 不装密钥模块也能启动 —— 外部 API 与 POS 守卫在那个进程里不装配）。

### 1.3 装配开关

| 配置 | 默认 | 作用 |
|---|---|---|
| `shop.pay.guard.entries` | 目标 `pay-svc`：`INTERNAL,OPEN,TERMINAL,OPS,BIZ,CHECKOUT`；将来 `pay-gateway`：去掉 `INTERNAL`、`pay-svc` 只留 `INTERNAL`；过渡期内嵌 `shop-app`：`OPEN,TERMINAL,OPS,BIZ,CHECKOUT` | `@ConditionalOnGuardEntry` 按它装配各入口的 `EntryGuard` 与其依赖（`pay-svc` 扫描整个 `ai.neargo.shop.pay`，不加条件会把外部 API 守卫与它要的 `KeyVault` 一起拖进来） |
| `shop.pay.guard.clock-skew-seconds` | 300 | 时间窗 |
| `shop.pay.guard.nonce-ttl-seconds` | 300 | nonce 有效期 |
| `shop.pay.guard.internal.verify-credential` | `false` | 内部调用暂不校验（用户 2026-10-27 定） |
| `shop.pay.guard.internal.record-port-calls` | `true` | 内嵌调用写记录 |
| `shop.pay.guard.internal.job-sample-rate` | `0.1` | 任务来源的成功查单按此比例写记录（M2.7） |
| `shop.pay.guard.internal.allowed-ips` | `127.0.0.1/32,::1/128` | 内部入口来源 IP 白名单，只认 TCP 对端；配空 = 不限；拆机部署配内网网段（M2.8，用户 2026-10-28 加） |
| `shop.pay.open.key-source` | `config` | 沿用：`secret` 时验签密钥取 `sec_key` |

---

## §2 代码清单

状态：🆕 新增 · ♻️ 迁入（原路径 → 新路径，逻辑不改或只按注明改）· ✏️ 修改 · 🗑 删除

### 2.1 `pay-guard`

| 类 | 状态 | 来自 | 职责 / 关键签名 |
|---|---|---|---|
| `guard.PayEntry` | ♻️ | `pay-portal` `api/PortalFace.java` | `INTERNAL("internal","/internal/pay/v1")` · `OPEN("/open/pay/v1")` · `TERMINAL("/terminal/pay/v1")` · `OPS("/pay-ops/v1")` · `BIZ("/biz/pay")` · `CHECKOUT("/checkout/v1")`；`owns(path)`、`prefix()`、`packageName()`（`PortalRouteRules` 用） |
| `guard.ConditionalOnGuardEntry` · `GuardEntryCondition` | 🆕 | — | `@ConditionalOnGuardEntry(PayEntry.OPEN)` |
| `guard.core.GuardFilter` | ♻️ ✏️ | `api/auth/PortalAuthFilter.java` | `OncePerRequestFilter`；构造 `(PayEntry, EntryGuard, RequestLogWriter)`；拆出 ①–⑫；`entryOf` 加 `INTERNAL → INTERNAL_HTTP` |
| `guard.core.GuardFilters` | ♻️ | `api/auth/PortalAuthFilters.java` | `GuardFilter forEntry(PayEntry)`；同一入口两个 `EntryGuard` → 启动失败 |
| `guard.core.EntryGuard` | ♻️ ✏️ | `api/auth/PortalAuthenticator.java` | `PayEntry entry()` · `GuardResult authenticate(GuardedRequest)` · `default decryptSensitiveFields(..)` · `default signResponse(..)` |
| `guard.core.GuardResult` · `GuardedRequest` · `GuardedCaller` | 🆕 | — | `GuardResult.ok(GuardedCaller)` / `reject(int http, String code, String reason)` |
| `guard.core.CachedBodyRequest` | ♻️ | `api/common/CachedBodyRequest.java` | |
| `guard.core.GuardMasking` | ♻️ ✏️ | `api/auth/PortalMasking.java` | 加 `X-Signature`、`encryptedCardData`、`x-sensitive` 字段、`\bsk_(live|test)_[A-Za-z0-9]{32}\b` |
| `guard.core.GuardErrors` | 🆕 | — | `AuthFailure` 枚举 → `ErrorCode`；生产统一 / 沙箱细分 |
| `guard.core.PortalActions` | ♻️ | `api/auth/PortalActions.java` | |
| `guard.core.RequestLogWriter` | 🆕 | 自 `PortalAuthFilter#row/reject` 拆出 | `write(LogRow)`；内部记录也走它，保证列口径一致；底层调 `pay-domain` `ApiRequestRecorder` |
| `guard.sign.SignatureCanonicalizer` | ♻️ ✏️ | `pay-domain` `openapp/SignatureCanonicalizer.java` | 加 `responseStringToSign(ts, nonce, bodySha256Hex)` |
| `guard.sign.SignedHeaders` | 🆕 | — | `ofApp(req)` / `ofTerminal(req)` → `(identity, timestamp, nonce, keySerial, signature)` |
| `guard.sign.HmacVerifier` | 🆕 | 自 `AppAuthServiceImpl#matches` | `Optional<Integer> verify(long keyId, String keySerial, String toSign, String signatureB64)` |
| `guard.sign.ResponseSigner` | 🆕 | — | `sign(KeyVersionRef, byte[] body) → (timestamp, nonce, signature)` |
| `guard.sign.NonceStore` · `DbNonceStore` | ♻️ | `pay-domain` `openapp/NonceStore.java` · `DbNonceStore.java` | `PayApiNonceMapper` 留在 `pay-domain` |
| `guard.sign.RedisNonceStore` | 🆕 | — | `@ConditionalOnBean(StringRedisTemplate)`，否则用 `DbNonceStore` |
| `guard.crypto.SensitiveFields` · `FieldDecryptor` · `ResourceCipher` | 🆕 | — | 加解密 TDD P3–P5 |
| `guard.internal.InternalGuard` | ♻️ ✏️ | `pay-svc` `svc/InternalCallerFilter.java` | 改为 `EntryGuard`：读 `X-Internal-Caller`、`X-Call-Source`；**总是放行**；`verify-credential=true` 时走原注册表校验 |
| `guard.internal.CallSourceCodec` | 🆕 | — | `String encode(CallSource)` · `Optional<CallSource> decode(String)`；单字段超长截断，整体 ≤ 4KB |
| `guard.internal.InternalCallRecorder` | 🆕 | — | `<T> T record(String api, PayAction action, CallSource src, InternalCallMeta meta, Supplier<T> call)`：计时、成败都写 |
| `guard.internal.RecordingPaymentPort` | 🆕 | — | `@Primary PaymentPort`；构造注入 `PaymentPortImpl`（按类型，非接口）与 `InternalCallRecorder` |
| `guard.internal.InternalCallers` | ♻️ | `pay-domain` `acquiring/port/InternalCallers.java` | 内嵌 `PayCaller` 工厂 |
| `guard.internal.InternalCallerRegistry` · `ConfigInternalCallerRegistry` | ♻️ | `pay-svc` `svc/InternalCallerRegistry.java` · `ConfigInternalCallerRegistry.java` | 仅开关打开时 |
| `guard.open.OpenApiGuard` | ♻️ ✏️ | `api/auth/OpenSignatureAuthenticator.java` + `AppAuthServiceImpl#authenticate` | 校验顺序见守卫 TDD §2.3 B |
| `guard.open.AppSignatureVerifier` · `SecretAppSignatureVerifier` · `ConfigAppSignatureVerifier` | 🆕 / ♻️ | 取代 `openapp/AppKeyResolver.java`、`ConfigAppKeyResolver.java`、`secret/SecretAppKeyResolver.java` | `Optional<Verified> verify(PayApp, String keySerial, String toSign, String sig)` |
| `guard.open.AppScopes` | ♻️ | `AppAuthServiceImpl#requireScope` · `#callerFor` | |
| `guard.open.IpAllowlist` | ♻️ | `AppAuthServiceImpl#ipAllowed` | |
| `guard.open.PartnerKeyService` · `impl.PartnerKeyServiceImpl` | ♻️ | `pay-domain` `openapp/service/PartnerKeyService.java` · `impl/PartnerKeyServiceImpl.java` | K1-DB 原样搬 |
| `guard.terminal.TerminalGuard` | 🆕 | — | 激活路径豁免验签；其余：终端 ACTIVE → 时间窗 → 验签 → nonce → `storeScope={store_id}` |
| `guard.terminal.TerminalActivationService` · impl | 🆕 | — | `issueCode(terminalId, operator)` → 8 位码只存 SHA-256；`activate(sn, code, fingerprint)` → 一次性设备密钥 |
| `guard.terminal.TerminalKeyService` · impl | 🆕 | — | `issue(terminal)`：`KeyRegistry.generate(BASE64URL_32B)` 并直接启用（设备密钥无人工复核，两人控制由激活码签发承担）；`revoke(terminal, reason)` |
| `guard.ops.OpsGuard` | ♻️ ✏️ | `api/auth/OpsSessionAuthenticator.java` | 只确认 `realm=OPERATOR`，员工号 + 已解析权限码 → `PayCaller(OPS)`；**不引用 `Perms`** |
| `guard.biz.BizGuard` | ♻️ | `api/auth/BizSessionAuthenticator.java` | |
| `guard.checkout.CheckoutGuard` | ♻️ | `api/auth/CheckoutVisitorAuthenticator.java` | |
| `guard.checkout.CheckoutCallers` | 🆕 | 自 `CheckoutServiceImpl` 两处 `new PayCaller` | |
| `guard.web.CallerArgumentResolver` · `GuardWebConfig` | 🆕 | — | |

### 2.2 其他模块

| 模块 · 文件 | 状态 | 改什么 |
|---|---|---|
| `backend/pay/pom.xml` | ✏️ | `<module>pay-guard</module>`（放在 `pay-domain` 之后、`pay-svc` 之前） |
| `backend/pom.xml` | ✏️ | `dependencyManagement` 加 `pay-guard` |
| **shop-base** `spi/pay/CallSource.java` · `CallSources.java` | 🆕 | 守卫 TDD §2.3 A |
| **shop-base** `spi/pay/PaymentPort.java` | ✏️ | `InternalCreatePaymentCmd` / `InternalRefundCmd` 末尾加 `CallSource source`，保留旧参数个数的构造器（自动 `CallSources.current()`）；`queryPayment` / `closePayment` 加带 `CallSource` 的重载，旧方法改 `default` |
| **pay-portal** `api/auth/*`（10 个）· `api/common/CachedBodyRequest.java` · `api/PortalFace.java` | 🗑 | 迁到 `pay-guard` |
| **pay-portal** `api/PortalRouteRules.java` · `test/…/PortalRouteRulesTest.java` | ✏️ | 改用 `PayEntry` |
| **pay-portal** `api/common/PortalRequests.java` | ✏️ | `PRINCIPAL` 属性改为 `GuardedCaller` |
| **pay-portal** `api/open/*` · `api/ops/*` · `api/biz/*` · `api/checkout/*` controller | ✏️ | 参数改为 `PayCaller caller`（`CallerArgumentResolver` 注入），不再从请求属性手取 |
| **pay-portal** `api/terminal/*` | 🆕 | `TerminalActivationController` · `TerminalPaymentController` · `TerminalRefundController` · `TerminalBatchController`（代码清单 §4.1） |
| **pay-portal** `pom.xml` | ✏️ | 依赖加 `pay-guard` |
| **pay-svc** `svc/InternalCallerFilter.java` · `InternalCallerRegistry.java` · `ConfigInternalCallerRegistry.java` | 🗑 | 迁到 `pay-guard.internal` |
| **pay-svc** 各 `Internal*Endpoint` | ✏️ | 读调用方码改读 `GuardedCaller`（属性名变了） |
| **pay-svc** `pom.xml` · `application.yml` | ✏️ | 依赖加 `pay-guard`；`shop.pay.guard.entries=INTERNAL` |
| **pay-svc** `test/…/PayApplicationBootTest.java` | ✏️ | 断言只装了 `InternalGuard`；未带凭据的内部请求不再 401 |
| **pay-domain** `openapp/SignatureCanonicalizer.java` · `NonceStore.java` · `DbNonceStore.java` · `AppKeyResolver.java` · `ConfigAppKeyResolver.java` · `secret/SecretAppKeyResolver.java` · `openapp/service/PartnerKeyService.java` · `impl/PartnerKeyServiceImpl.java` · `acquiring/port/InternalCallers.java` | 🗑 | 迁到 `pay-guard` |
| **pay-domain** `openapp/service/AppAuthService.java` · `impl/AppAuthServiceImpl.java` | ✏️ | 验证部分迁走；剩 `requireActive`、`checkDailyRefund` → 改名 `AppAccountService` / `AppRefundLimitService` |
| **pay-domain** `acquiring/port/PaymentPortImpl.java` | ✏️ | 去掉 `@Primary`（若有）；由 `RecordingPaymentPort` 装饰 |
| **pay-domain** `checkout/service/impl/CheckoutServiceImpl.java` | ✏️ | 两处 `new PayCaller` → `CheckoutCallers` |
| **pay-domain** `access/entity/PayApiRequestLog.java` | ✏️ | §3.1 的 8 个新字段 |
| **pay-domain** `terminal/entity/PayTerminal.java` · `PayManagerCode.java` · `PayTerminalBatch.java` · `mapper/terminal/TerminalMappers.java` | 🆕 | §3.2 |
| **pay-domain** `db/pay/V13__pay_request_log_call_source.sql` · `V14__pay_terminal.sql` | 🆕 | §3 |
| **shop-app** `pom.xml` | ✏️ | 依赖加 `pay-guard` |
| **shop-app** `config/SecurityConfig.java` | ✏️ | `PortalAuthFilters` → `GuardFilters`；新增 `terminalPayChain`（`/terminal/pay/v1/**`，无会话、无 CORS） |
| **shop-app** 独立形态 `RemotePaymentPort` | ✏️ | 每次调用写 `X-Call-Source` 头 |
| **shop-app** `test/resources/schema-test.sql` | ✏️ | `python3 backend/scripts/gen-test-schema.py` 重新生成 |
| **shop-app** `test/…/arch/ArchitectureTest.java` | ✏️ | `PayHasNoControllerTest` 白名单不变；新增 `pay-guard` 不得含 `@RestController` |

### 2.3 迁移时的类数

| 动作 | 个数 |
|---|---|
| 🆕 新增（`pay-guard` 内，含接口与 impl） | 约 26 |
| ♻️ 迁入 `pay-guard` | 24：`pay-portal` 12（`api/auth/*` 10 个 + `CachedBodyRequest` + `PortalFace`；其中 `PortalPrincipal` 并入新的 `GuardedCaller`）、`pay-domain` 9（另有 `AppAuthServiceImpl` 拆出验证部分）、`pay-svc` 3 |
| 🗑 原位置删除 | 同上 24 |
| ✏️ 其他模块修改 | 约 20 个文件（§2.2） |

---

## §3 数据库设计

迁移目录 `backend/pay/pay-domain/src/main/resources/db/pay/`，接在现有 **V12** 之后。密钥不建表（全在 `sec_*`，`db/secret` V2 已加存储分档）。

| 迁移 | 内容 | 随哪片 |
|---|---|---|
| **V13** `pay_request_log_call_source` | `pay_api_request_log` 加 8 列 + 1 个索引：内部调用来源 | G3 |
| **V15** `pay_terminal` | 新表 `pay_terminal` · `pay_manager_code` · `pay_terminal_batch` | G5 |
| （另） | 开放 API 加解密的 `pay_app.sign_type`、`pay_biz_notify` 冻结密钥版本、`pay_api_request_log.sign_key_version / encrypt_serial`；开发者控制台三张表 —— 见[开放API加解密-数据库设计](./TDD-支付服务-开放API加解密-数据库设计.md) §4，**迁移号从那份的 V12 顺延为 V17**（V12 已被 `pay_channel_market_explicit` 占用，V13 / V14 归 W6 对账） | G4 |

### 3.1 V13 · 请求记录加来源

```sql
-- 支付服务 v4 · 迁移 13（接入守卫 G3；db/pay 号须连续，先合先占）：内部调用不校验、只留痕（用户 2026-10-27 定）——
-- 来源信息由调用参数 CallSource 带进来，落在请求记录里。外部入口这些列为空。

ALTER TABLE pay_api_request_log ADD COLUMN source_service VARCHAR(64) NULL COMMENT '调用方服务名（spring.application.name）；内部调用取不到记 UNKNOWN';
ALTER TABLE pay_api_request_log ADD COLUMN source_module VARCHAR(24) NULL COMMENT '调用方业务线：MALL / FOOD / RETAIL / BEAUTY / STORED_VALUE …';
ALTER TABLE pay_api_request_log ADD COLUMN source_instance VARCHAR(128) NULL COMMENT '调用方实例：主机名或 pod 名';
ALTER TABLE pay_api_request_log ADD COLUMN source_ip VARCHAR(64) NULL COMMENT '调用方自报的服务器 IP；client_ip 仍是支付实际看到的对端 IP（内嵌形态为空）';
ALTER TABLE pay_api_request_log ADD COLUMN origin_api VARCHAR(128) NULL COMMENT '触发这次调用的上游接口或任务名，如 POST /mp/orders/{orderNo}/pay';
ALTER TABLE pay_api_request_log ADD COLUMN origin_client VARCHAR(24) NULL COMMENT '上游请求来源端：MINI_PROGRAM / H5 / BIZ_APP / OPS_WEB / POS / JOB';
ALTER TABLE pay_api_request_log ADD COLUMN origin_ip VARCHAR(64) NULL COMMENT '上游请求的终端用户 IP（经可信代理头）';
ALTER TABLE pay_api_request_log ADD COLUMN origin_ua_digest CHAR(64) NULL COMMENT '上游请求 User-Agent 的 SHA-256';

CREATE INDEX idx_pay_api_request_log_6 ON pay_api_request_log (source_service, received_at);
```

**一行内部调用记录长什么样**（内嵌形态，商城小程序下单后发起支付）：

| 列 | 值 |
|---|---|
| `trace_id` | `CallSource.traceId`（= 小程序那次请求的 `X-Request-Id`） |
| `entry` · `source_type` · `app_no` | `INTERNAL_PORT` · `INTERNAL` · `MALL` |
| `api` · `pay_action` | `PaymentPort#createPayment` · `CREATE_ONLINE` |
| `source_service` · `source_module` · `source_instance` · `source_ip` | `shop-app` · `MALL` · `api-01` · `10.0.1.12` |
| `origin_api` · `origin_client` · `origin_ip` · `origin_ua_digest` | `POST /mp/orders/{orderNo}/pay` · `MINI_PROGRAM` · `223.104.x.x` · `…` |
| `client_ip` · `http_status` | 空（进程内调用） |
| `operator_no` · `biz_type` · `biz_no` · `store_id` | 顾客账号 · `MALL_ORDER` · 订单号 · 门店 id |
| `request_amount_minor` · `request_currency` | `12000` · `CNY` |
| `pay_order_id` · `result_code` · `outcome` · `elapsed_ms` | 新支付单 id · `OK` · `ACCEPTED` · `38` |
| `request_masked` | 命令对象的脱敏 JSON |

保留期与查询沿用 `pay_api_request_log` 现有规则；新索引用于「某个服务这一小时调了多少次、失败几次」。

### 3.2 V15 · POS 终端

列定义取自唯一真源 `design/schema/pay-service-database.json`（表结构说明 v4），**逐字不改**；激活码就在 `pay_terminal` 上（`activation_code_sha256` + `activation_expire_at`），不另建表。

```sql
-- 支付服务 v4 · 迁移 15（接入守卫 G5；号与复核 §3.8 一致）：刷卡 POS 终端、店长授权码、终端日结。
-- 设备密钥不在这里：sec_key(PAY, TERMINAL, pay_terminal.id, INBOUND, HMAC)，pay_terminal.sec_key_id 只存引用。

CREATE TABLE pay_terminal (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  terminal_sn VARCHAR(64) NOT NULL COMMENT '终端序列号（设备上可见，对外）',
  store_id BIGINT NOT NULL COMMENT '门店 mch_store.id（核心系统；关联核心系统一律用门店 id）',
  pay_merchant_id BIGINT NOT NULL COMMENT '绑定的收款商户 pay_merchant.id',
  model VARCHAR(64) NOT NULL COMMENT '机型',
  reader_kit VARCHAR(64) NULL COMMENT '读卡套件型号 / 版本（按渠道认证清单校验）',
  app_version VARCHAR(32) NULL COMMENT '终端 App 版本',
  status VARCHAR(24) NOT NULL COMMENT 'REGISTERED / ACTIVE / LOST / RETIRED',
  activation_code_sha256 CHAR(64) NULL COMMENT '一次性激活码哈希',
  activation_expire_at DATETIME(3) NULL COMMENT '激活码过期（10 分钟）',
  device_fingerprint VARCHAR(128) NULL COMMENT '激活时设备指纹',
  sec_key_id BIGINT NULL COMMENT '设备密钥 sec_key.id（owner_type=TERMINAL），激活时生成',
  daily_refund_limit_minor BIGINT NOT NULL DEFAULT 0 COMMENT '单终端日退款上限',
  last_seen_at DATETIME(3) NULL COMMENT '最近心跳',
  activated_at DATETIME(3) NULL COMMENT '激活时间',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pay_terminal_1 (terminal_sn),
  KEY idx_pay_terminal_1 (store_id, status),
  KEY idx_pay_terminal_2 (pay_merchant_id)
) COMMENT='刷卡机终端（自研 App）：绑定收款商户与门店';

CREATE TABLE pay_manager_code (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  store_id BIGINT NOT NULL COMMENT '门店 mch_store.id（核心系统；关联核心系统一律用门店 id）',
  manager_account_id BIGINT NOT NULL COMMENT '店长账号 mch_account.id（核心系统）',
  code_hash VARCHAR(128) NOT NULL COMMENT '授权码哈希（加盐慢哈希）',
  status VARCHAR(24) NOT NULL COMMENT 'ACTIVE / REVOKED',
  failed_attempts INT NOT NULL DEFAULT 0 COMMENT '连续失败次数（超限锁定）',
  locked_until DATETIME(3) NULL COMMENT '锁定至',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pay_manager_code_1 (store_id, manager_account_id)
) COMMENT='店长授权码：终端撤销 / 退款的授权，由商家端设置，只存哈希';

CREATE TABLE pay_terminal_batch (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  pay_terminal_id BIGINT NOT NULL COMMENT '终端 pay_terminal.id',
  biz_date DATE NOT NULL COMMENT '营业日',
  currency CHAR(3) NOT NULL COMMENT '币种',
  reported_count INT NOT NULL COMMENT '终端上报笔数',
  reported_minor BIGINT NOT NULL COMMENT '终端上报金额',
  reported_void_count INT NOT NULL DEFAULT 0 COMMENT '撤销笔数',
  reported_void_minor BIGINT NOT NULL DEFAULT 0 COMMENT '撤销金额',
  server_count INT NULL COMMENT '服务端汇总笔数',
  server_minor BIGINT NULL COMMENT '服务端汇总金额',
  status VARCHAR(24) NOT NULL COMMENT 'REPORTED / MATCHED / MISMATCHED',
  operator_no VARCHAR(64) NOT NULL COMMENT '日结收银员（审计字段）',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pay_terminal_batch_1 (pay_terminal_id, biz_date)
) COMMENT='终端日结：终端上报与服务端汇总比对';
```

### 3.3 各入口用到的表

| 入口 | 读 | 写 | 密钥（`sec_*`） |
|---|---|---|---|
| 内部系统 | `pay_app`（按调用方码取 id，取不到照样放行） | `pay_api_request_log`（V13 新列） | 无 |
| 外部 API | `pay_app` · `pay_app_grant` | `pay_api_nonce` · `pay_api_request_log` | `PARTNER_APP` 的 AppSecret / EncryptKey；`PLATFORM_OPEN` 的平台密钥（增强方案） |
| POS 终端 | `pay_terminal` | `pay_terminal`（激活、心跳）· `pay_api_nonce` · `pay_api_request_log` | `TERMINAL` 的设备密钥 |
| 运营端 | 平台账号 / 角色表（不在支付库） | `pay_api_request_log` | 无 |
| 商家端 · 收银台 | 平台会话 · `pay_checkout_session` / `pay_qr_code` | `pay_api_request_log` | 无 |

### 3.4 同步真源

`pay-service-database.json` 是表结构说明的唯一真源，但生成器不在仓库（开放API加解密-类清单 Q-C3）。V13 的 8 列（已写回 JSON，2026-10-28）与终端三张表（已在 JSON 里）落地后，把 V13 的列补进 JSON；在生成器补回之前不手改《数据库表结构说明》。

---

## 待确认

| # | 问题 | 倾向 |
|---|---|---|
| Q-D1 | `pay_api_request_log` 写入量：内嵌调用每笔下单至少 2 行（建单 + 发起），查单补偿任务也会写 —— 补偿任务的查单是否也记 | 记，但 `origin_client=JOB` 的查单按 1/10 抽样（`record-port-calls` 加 `job-sample-rate`）；建单 / 退款 / 关单全记 |
| Q-D2 | V13（请求记录来源）与开放 API 加解密的迁移是否合并成一个 | 不合并：G3 先上，G4 晚，分开迁移各自可回滚 |
