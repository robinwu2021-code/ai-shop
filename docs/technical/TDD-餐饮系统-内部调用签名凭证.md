# TDD-餐饮系统-内部调用签名凭证

状态：已实现
关联需求：`docs/technical/design/餐饮系统-功能清单与代码对齐.md` §4 C8（内部调用信任边界）；`TDD-餐饮系统-三端API与独立服务对齐.md` §2.4（独立部署合同）
创建：2026-10-26 · 最后更新：2026-10-26

档位：1 · 契约变更：新配置项 `shop.food.scope-key` / `shop.food.scope-key-previous`、内部协议新请求头 `X-Food-Scope`、svc-client 新增客户端拦截器入口。

## 背景

独立部署（`shop.food.deployment=standalone`）时，宿主 shop-app（带 food-web）经 `/internal/food/**` 调 food-server。
food-server 现在只校验共享令牌 `X-Internal-Token`，entity / store / actor 全取自请求体：

- 共享令牌是**全平台一把**（shop-app、pay-svc、food-server 都配同一个值），任何一个进程或配置泄露，持有者就能以任意店员身份写任意门店的餐饮数据；
- 宿主侧 B 端已有 `FoodBizStoreScope` / `BizContext` 判门店，但这道判断在宿主业务代码里，food-server 无从得知「这个 actor/store 组合是谁担保的」。

方案（用户于 2026-10-26 选定）：**宿主对每个请求签发短期签名凭证，food-server 本地验签**；不新增核心端点、不加每请求远程调用。

## 验收标准

- **AC1** Given 独立部署的 food-server，When `/internal/food/**` 请求令牌正确但没有 `X-Food-Scope`、或签名不对，Then 401，且不进入 Controller。
- **AC2** Given 凭证已过期，或过期时间超出允许的最长有效期，When 请求，Then 401。
- **AC3** Given 合法凭证，When 请求体或路径与签名时不同（摘要对不上），Then 401。
- **AC4** Given STAFF 凭证，When 请求体里的 entity 不等于凭证 entity、或 store 不在凭证 stores 内、或 actor 不等于凭证 actor，Then 403，且不进入 Controller。
- **AC5** Given 宿主 B 端请求，When 发往 food-server 的请求体目标门店不在当前 `BizContext.storeNos`、主体不是当前商家、或 actor 不是当前登录人，Then 宿主拒绝签发、请求不发出（403）。
- **AC6** Given 正在轮换密钥（新密钥签发、旧密钥配为 previous），When food-server 收到旧密钥签的未过期凭证，Then 接受；签发一律用新密钥。
- **AC7** Given food-server 没配 `shop.food.scope-key`（或少于 32 字节），Then 所有 `/internal/food/**` 拒绝；宿主独立部署没配时，调 food-server 直接报 NOT_CONFIGURED（失败即关闭）。
- **AC8** Given CONSUMER 凭证，When 请求体里的 buyer / actor 不等于凭证 actor，Then 403。
- **AC9** 既有餐饮 web↔server 传输测试与宿主 food-embedded 测试保持全绿；嵌入部署（不走 HTTP）行为不变。

不做：
- 防重放的一次性 nonce（有效期 60 秒内的同摘要重放仍可能；写命令本身全部带幂等键，重放得到同一结果）。
- 平台其他内部服务（pay-svc 等）的同类加固 —— 本次只收紧 food-server。
- 核对「店员仍在职」：凭证只表达签发那一刻宿主已验过的上下文。

## §0 对账一 · 需求 → 设计

| AC | 需求原文（一句话） | 落点 |
|---|---|---|
| AC1 | 无凭证 / 签名错 → 401 | food-server `FoodScopeVerificationFilter`（挂在 `FoodInternalSecurityConfiguration` 令牌校验之后） |
| AC2 | 过期 / 有效期过长 → 401 | food-api `FoodScopeCredentials.verify`（`exp ≤ now + 120s` 且 `exp > now − 30s` 宽限） |
| AC3 | 摘要不符 → 401 | `FoodScopeCredentials.digest(method, rawPath+query, body)`；filter 用缓存的请求体重算 |
| AC4 | STAFF 声明与请求体不符 → 403 | food-api `FoodScopeBodyScanner` + filter |
| AC5 | 宿主拒签越权请求 | food-web `FoodScopeSigningInterceptor`（STAFF 分支对照 `BizContext`） |
| AC6 | 旧密钥仍可验 | `FoodScopeKeys`（current + previous），签发只用 current |
| AC7 | 没配密钥失败即关闭 | filter 与 interceptor 构造时校验密钥；拦截器抛 `ServiceCallException(NOT_CONFIGURED)` |
| AC8 | CONSUMER 声明与请求体不符 → 403 | 同 AC4，CONSUMER 分支 |
| AC9 | 既有链路不变 | 所有 FOOD 远程客户端统一经 `FoodClientConfiguration` 加拦截器；嵌入形态不装拦截器与过滤器 |

孤立项：无。

## §1 现状与影响面

- 调用方向：只有宿主 food-web 的 `foodclient/Remote*`（`FoodClientConfiguration` 里约 50 个 `http.client(ServiceName.FOOD, …)`）调 food-server；后台任务、outbox 消费者也走这些客户端（无请求上下文）。core 不直接调 food-server。
- 传输层：`svc-client` 的 `ServiceClients.create` 用 `RestClient` 加两个拦截器（定地址、加令牌）；`ServiceClientSpec` 没有扩展入口。
- 服务端：`food-server` `FoodInternalSecurityConfiguration` 只比对令牌；46 个 `Internal*Controller` 直接拿请求体里的范围。
- 请求体范围字段**命名不统一**：`entityNo/storeNo/actorNo`（多数 DTO）、`entity/store/actor`（如 `DiningCheckoutInternalApi.StaffGet`）、C 端 `CartAccess.buyerNo`；可嵌套。
- 会被改到的：`svc-client`（新增可选拦截器列表，默认空，其他服务不受影响）、`shop-base` `InternalHttp`（新增重载）、food-web 客户端装配、food-server 安全配置、餐饮传输测试的夹具。
- 明确不受影响：嵌入部署（`shop.food.deployment=embedded`，不经 HTTP）；平台其他内部端点；生产当前形态（`api,ops`、food 未启用）。

## §2 方案

### 契约变更

- **配置项**
  - `shop.food.scope-key`：签名密钥（HMAC-SHA256），≥ 32 字节；宿主（standalone）与 food-server **两端都要配同一个值**，且**不得复用** `shop.services.internal-token`。
  - `shop.food.scope-key-previous`：可选，只用于验签（轮换窗口）。
- **内部协议**：请求头 `X-Food-Scope: v1.<payload>.<sig>`
  - `payload` = base64url(JSON)：`{"r":realm,"e":entity,"s":[stores],"a":actor,"x":expEpochSeconds,"d":digestHex}`
  - `sig` = base64url(HMAC-SHA256(key, "v1." + payload))
  - `digest` = SHA-256( `METHOD` + "\n" + rawPath[`?`rawQuery] + "\n" + body 字节 )，十六进制
  - 有效期：签发 `exp = now + 60s`；验签接受 `now − 30s < exp ≤ now + 120s`
- **realm 与约束**

| realm | 何时签发 | e / s / a | food-server 对请求体的约束 |
|---|---|---|---|
| STAFF | 宿主请求线程里 `BizContext` 有商家号（`/biz/**`） | 当前商家 / 请求体目标门店（且都在 `BizContext.storeNos` 内）/ 当前登录人 | 请求体所有 entity 值 = e；所有 store 值 ∈ s；所有 actor 值 = a |
| CONSUMER | 有 Web 请求、无商家上下文（`/mp/**`） | 空 / 空 / 登录买家号或空 | 请求体所有 buyer、actor 值 = a（a 为空时请求体不得带非空 buyer/actor） |
| OPERATOR | 登录人 realm 为平台运营（`/ops/**`） | 空 / 空 / 运营人 | 无（平台数据域由宿主判） |
| SYSTEM | 无 Web 请求（任务、outbox 消费者） | 空 / 空 / 空 | 无 |

- 请求体扫描（`FoodScopeBodyScanner`）：递归遍历 JSON，取以下键的**字符串值**：entity ← `entityNo`、`entity`；store ← `storeNo`、`store`；actor ← `actorNo`、`actor`；buyer ← `buyerNo`。空值忽略。
- 错误：签名 / 过期 / 摘要 / 缺头 / 未配密钥 → 401；声明与请求体不符 → 403。响应体不回显原因细节，日志记 realm 与不符的键名。

### 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 修改 | `backend/svc-client/.../ServiceClientSpec.java` | 新增 `List<ClientHttpRequestInterceptor> interceptors`（默认空）与 `withInterceptors(...)` |
| 修改 | `backend/svc-client/.../ServiceClients.java` | 在「定地址、加令牌」之后依次挂上额外拦截器 |
| 修改 | `backend/shop-base/.../svc/InternalHttp.java` | 新增 `client(service, api, readTimeout, ClientHttpRequestInterceptor...)`；按服务名挂上 `InternalServiceInterceptor` bean |
| 新增 | `backend/shop-base/.../svc/InternalServiceInterceptor.java` | 「给某个服务的所有内部调用加一道拦截器」的 bean 声明 |
| 新增 | `food-api/.../food/scope/FoodScopeCredentials.java` | 编解码、签名、验签、摘要；纯 JDK + Jackson |
| 新增 | `food-api/.../food/scope/FoodScopeBodyScanner.java` | 请求体范围键扫描 |
| 新增 | `food-web/.../foodclient/FoodScopeSigningInterceptor.java` | 判定 realm、对照上下文、签发；STAFF 越权时拒签 |
| 新增 | `food-web/.../foodclient/FoodScopeSigningConfiguration.java` | standalone 时声明 FOOD 的 `InternalServiceInterceptor`（见 §6 偏差） |
| 修改 | `food-web/.../config/FoodWebAutoConfiguration.java` | 引入上面这个配置 |
| 新增 | `food-server/.../config/FoodScopeVerificationFilter.java` | 缓存请求体、验签、对照请求体 |
| 修改 | `food-server/.../config/FoodInternalSecurityConfiguration.java` | 令牌校验通过后挂过滤器 |
| 新增/修改 | 三处测试与 food-server 测试夹具 | 见 §5 |
| 修改 | `deploy/` 环境说明 | 新增 `SHOP_FOOD_SCOPE_KEY`（仅独立部署） |

### 关键接口

```java
// food-api
public final class FoodScopeCredentials {
    public enum Realm { STAFF, CONSUMER, OPERATOR, SYSTEM }
    public record Claims(Realm realm, String entity, List<String> stores, String actor, long exp, String digest) {}
    public static String digest(String method, String pathAndQuery, byte[] body);
    public static String sign(Claims claims, byte[] key);
    /** 签名、过期、摘要任一不符抛 Rejected(401)；返回解出的声明 */
    public static Claims verify(String header, List<byte[]> keys, String expectedDigest, long nowEpochSeconds);
}
public final class FoodScopeBodyScanner {
    public record Scope(Set<String> entities, Set<String> stores, Set<String> actors, Set<String> buyers) {}
    public static Scope scan(byte[] json);
}
```

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试方法 | 跑过 | 消融验证 |
|---|---|---|---|
| AC1 | `FoodScopeVerificationApiTest#ac1TokenAloneIsNotEnoughAndForeignKeyIsRejected`；`FoodScopeCredentialsTest#ac1MissingMalformedOrForeignSignatureIs401` | ✅ | 去掉 `addFilterAfter(FoodScopeVerificationFilter)` → `expected:<401> but was:<200>` ✅ |
| AC2 | `FoodScopeCredentialsTest#ac2ExpiredOrTooLongLivedIs401` | ✅ | （编解码单测，按过期 / 超前边界逐一断言） |
| AC3 | `FoodScopeVerificationApiTest#ac3SignedRequestWhoseBodyChangesAfterSigningIsRejected`；`FoodScopeCredentialsTest#ac3TamperedBodyOrPathIs401` | ✅ | 去掉过滤器 → `expected:<401> but was:<200>` ✅ |
| AC4 | `FoodScopeVerificationApiTest#ac4StaffClaimsMustCoverEveryEntityAndStoreInTheBody`；`FoodScopeCredentialsTest#ac4StaffBodyMustMatchEntityStoresAndActor` | ✅ | 去掉过滤器 → `expected:<403> but was:<200>` ✅ |
| AC5 | `FoodScopeSigningInterceptorTest#ac5HostRefusesToSignAStoreEntityOrActorOutsideTheContext` | ✅ | 去掉拦截器里的门店检查与 `requireBodyMatches` → 测试失败 ✅ |
| AC6 | `FoodScopeVerificationApiTest#ac6PreviousKeyIsAcceptedDuringRotation`；`FoodScopeCredentialsTest#ac6PreviousKeyStillVerifies` | ✅ | 单测里只给新钥 → 旧钥签的凭证 401 ✅ |
| AC7 | `FoodScopeVerificationApiTest#ac7NoKeyOrShortKeyRejectsEverything`；`FoodScopeSigningInterceptorTest#ac7MissingKeyFailsClosedOnTheHost`；`FoodScopeCredentialsTest#ac7MissingOrShortKeyFailsClosed` | ✅ | 去掉过滤器 → `expected:<401> but was:<200>` ✅ |
| AC8 | `FoodScopeVerificationApiTest#ac8ConsumerMustBeTheBuyerInTheBody`；`FoodScopeSigningInterceptorTest#consumerBuyerMustBeTheLoggedInConsumer` | ✅ | 去掉过滤器 → 进入 Controller（400，不再是 403）；去掉宿主检查 → 测试失败 ✅ |
| AC9 | 餐饮全量 `-Pfood-embedded,food-verify`（插件五模块 + 宿主 food-embedded） | ✅ 970 跑 / 0 失败（9+8 / 232 / 622 / 9 / 32 / 58） | — |

另：`FoodScopeSigningInterceptorTest#staffRequestInsideTheContextIsSignedAndVerifies` 证明宿主签的凭证按 food-server 的方式（同钥、同摘要算法）验得过；
`ServiceClientsTest#extraInterceptorRunsAfterTokenWithFinalRequest` 证明额外拦截器在加令牌之后执行、看得到最终路径与请求体。

## §6 对账二 · 设计 → 实现

`git show --stat 669bd00d2 a9cadd5f2`（平台扩展点 + 餐饮实现）：

```
backend/shop-base/.../svc/InternalHttp.java                     | 33 ++-
backend/shop-base/.../svc/InternalServiceInterceptor.java       | 15 +
backend/svc-client/.../ServiceClientSpec.java                   | 29 ++-
backend/svc-client/.../ServiceClients.java                      |  8 +-
backend/svc-client/.../ServiceClientsTest.java                  | 22 +
food-api/.../scope/FoodScopeBodyScanner.java                    | 60 +
food-api/.../scope/FoodScopeCredentials.java                    | 198 +
food-api/.../scope/FoodScopeCredentialsTest.java                | 123 +
food-server/.../config/FoodInternalSecurityConfiguration.java   | 16 +-
food-server/.../config/FoodScopeVerificationFilter.java         | 104 +
food-server/src/main/resources/application.yml                  |  4 +
food-server/src/test/...（19 个既有测试改为签 SYSTEM 凭证）       |
food-server/.../scenario/FoodScopeVerificationApiTest.java      | 127 +
food-server/.../scope/FoodScopeTestSigning.java                 | 41 +
food-web/.../config/FoodWebAutoConfiguration.java               |  2 +-
food-web/.../foodclient/FoodScopeSigningConfiguration.java      | 29 +
food-web/.../foodclient/FoodScopeSigningInterceptor.java        | 89 +
food-web/.../scenario/FoodScopeSigningInterceptorTest.java      | 127 +
```

| 差异 | 说明 |
|---|---|
| TDD 里没有、实际改了：`InternalServiceInterceptor`、`FoodScopeSigningConfiguration`、`FoodWebAutoConfiguration` | 见偏差说明 |
| TDD 列了、实际没动：`FoodClientConfiguration` | 同上 |
| 19 个 food-server 既有测试 | 它们直接打内部端点，现在要带凭证；统一用 `FoodScopeTestSigning.system()`，不改断言 |
| `deploy/` 环境说明 | 仓库里 food-server 只在 `application.yml` 里声明环境变量，已加 `SHOP_FOOD_SCOPE_KEY(_PREVIOUS)`；宿主无 food 配置文件，部署时设 `SHOP_FOOD_SCOPE_KEY`（Spring 环境变量映射到 `shop.food.scope-key`） |

### 偏差说明

原设计是在 `FoodClientConfiguration` 里把所有 FOOD 客户端改成经一个工厂方法创建。实际 FOOD 客户端分散在 8 个配置类
（`FoodClientConfiguration`、`FoodBusinessOperationsClientConfiguration`、`DiningDeliveryWebAutoConfiguration`、
`DiningIdentityWebAutoConfiguration`、`FoodKdsWebAutoConfiguration`、`FoodSupportWebAutoConfiguration`、`FoodReportFactsAutoConfiguration` 等），
逐个改调用点会漏，后加的客户端也会漏。改为平台侧提供 `InternalServiceInterceptor` bean：`InternalHttp` 每造一个指向该服务的客户端都自动挂上。
签名配置单独成类，只看 `shop.food.enabled` 与 `deployment=standalone`，不受各客户端配置类自己的条件影响。

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-10-26 | 方案选定（宿主签发短期签名凭证、food-server 本地验签），起草本文 |
| 2026-10-26 | 已实现（`669bd00d2`、`a9cadd5f2`）；餐饮全量 970 跑 0 失败，svc-client 11 跑 0 失败；四组消融均变红。全量闸门见推送记录 |
