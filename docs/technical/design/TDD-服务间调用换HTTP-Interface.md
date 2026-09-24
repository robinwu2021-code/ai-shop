# TDD-服务间调用换 HTTP Interface

状态：草稿
关联：[ADR-025 服务间调用用 HTTP Interface](../ADR/ADR-025-服务间调用用HTTP-Interface.md) ·
[ADR-023 服务发现先不装中间件](../ADR/ADR-023-服务发现先不装中间件.md) ·
[ADR-021 支付域独立为服务](../ADR/ADR-021-支付域独立为服务与独立库.md)
创建：2026-09-24 · 最后更新：2026-09-24

> **一句话**：把两条服务间调用链路 —— `shop-app → pay-svc`、`job-worker → shop-app` ——
> 从手写 JDK HttpClient（拼路径 + 手工反序列化）换成 `@HttpExchange` 接口。**契约不变**：端点、配置项、JSON 结构、失败语义一个都不改。
> 档位：契约不动，本应是 0 档；但这是「今后服务间调用一律怎么写」的选型，所以配一份 ADR。

## §0 对账一 · 需求 → 设计

没有 PRD：这是技术债偿还，验收标准是「行为与今天逐条一致」，外加一条新能力。

| AC | 验收标准 | 落点 |
|---|---|---|
| AC1 | 5 个 pay 内部端点都经 `@HttpExchange` 接口调用，业务代码里不再有路径字符串与 `TypeReference` | `PayInternalApi` + 两个 `Remote*AppService` |
| AC1b | job 的 2 个内部端点经 `@HttpExchange` 调用；任务结果的映射照旧：409 → 跳过、404 → 没有这个 handler、2xx 但解析不了 → 失败、没配地址 → 不可达；**超时仍按任务各自的 `timeoutSec`** | `JobBusinessApi` + `HttpBusinessClient` |
| AC2 | 五种失败分类照旧：`NOT_CONFIGURED` / `UNREACHABLE` / `TIMEOUT` / `REMOTE_ERROR` / `OK`，「没配地址」仍在**调用时**报，不是启动失败 | `ServiceLocatorInterceptor` + `InternalCalls` |
| AC3 | 调不通时 `rules()` / `effectiveRates()` 抛错，**不返回空集合**（空集合下游语义是「没配过」「零佣金」） | `RemoteOpsFeeRuleAppService` |
| AC4 | 写操作不重试；`add()` 在有幂等键之前照旧拒绝 | `RemoteOpsFeeRuleAppService#add` |
| AC5 | 守住四条旧规矩：强制 HTTP/1.1、带 `X-Internal-Token`、密钥没配一律拒绝、不记请求/响应 body | `InternalHttp#restClient` |
| AC6 | `/internal/**` 不被全局响应信封包裹（被包的话解码结果是**字段全 null 而不报错**） | 回归用例 |
| AC7 | 客户端接口与服务端 mapping 的路径对得上，漂了**编译不过** | 两边注解引用同一份路径常量：`PayInternalPaths`（pay-domain）、`JobHttpPaths`（job-api，纯字符串，不加依赖） |
| AC8 | 地址每次调用现查 `ServiceLocator`，将来换服务发现只换 `ServiceLocator` 实现 | `ServiceLocatorInterceptor` |

**孤立项**：无。

## §1 现状与影响面

**服务间调用只有两条链路**（2026-09-24 按 `git grep InternalClient|ServiceName.|"/internal` 全量查过）：

| 链路 | 客户端 | 端点 | 本次 |
|---|---|---|---|
| `shop-app → pay-svc` | `InternalClient`（shop-base，JDK HttpClient） | `/internal/pay/fee-rules`、`…/effective`、`/internal/pay/settle-invoices`、`…/{no}/issue`、`…/{no}/reject` | **迁移** |
| `job-worker → shop-app` | `HttpBusinessClient`（自带 JDK HttpClient） | `/internal/job/declarations`、`/internal/job/{handler}/run` | **迁移**（2026-09-24 用户定：全部迁移） |

- 会被改到的：`RemoteOpsFeeRuleAppService`、`RemoteOpsSettleInvoiceAppService`（仅 `shop.pay.deployment=standalone` 时装配）；
  `job-worker` 的 `HttpBusinessClient`（**生产在用**：调度器每一次触发都走它）
- 服务端只把 mapping 里的路径字面量换成常量，路径值不变
- 可直接复用：`ServiceLocator` / `ConfigServiceLocator`（寻址）、`ServiceName`（服务名常量）、`JobWorkerProperties`（job 的地址与令牌）
- **明确不受影响**：
  - `shop.pay.deployment=embedded`（今天的生产形态）走本地实现，一行不经过 pay 这条代码
  - 端点、响应格式、两边的配置键（`shop.services.*`、job 的 `targets` / `token`）、请求头名（`X-Internal-Token`、`X-Job-Token`）
  - 第三方网关（微信、个推、FCM、APNs、阿里短信、视觉识别）：它们调的是外部 API，不是服务间调用，不在本次范围

### 放哪儿：为什么要新开一个模块

| 约束 | 出处 |
|---|---|
| `job-worker` 不能依赖 `shop-base` | shop-base 带 mybatis 等编译依赖，worker 碰到就进 classpath（`job-api/pom.xml` 注释） |
| `job-api` 一个依赖都不能加 | 同上 |
| `shop-base` 与 `job-worker` 都要用同一套传输零件 | 否则 HTTP/1.1、不记 body、失败分类会在两处各写一遍，必然漂移 |

所以传输零件放在**新模块 `backend/svc-client`**（只依赖 `spring-web`，**不带 Tomcat**），包名 `ai.neargo.svc.client` ——
刻意不用 `ai.neargo.shop` 前缀：job 是独立交付的，不该 import 一个 shop 包。
它**不认识任何配置键和请求头名**，这些由两边各自传入：

| | shop-app → pay | job-worker → shop-app |
|---|---|---|
| 寻址 | `ServiceLocator` | `JobWorkerProperties#getTargets` |
| 令牌头 | `X-Internal-Token`，没配 → 拒绝 | `X-Job-Token`，行为照旧 |
| 读超时 | 按服务，pay 5s | **按任务**，每个 `timeoutSec` 一个代理实例（缓存） |

## §2 方案

### 契约变更

无。端点、库表、权限码、i18n、配置项（仍是 `shop.services.targets.*` 与 `shop.services.internal-token`）、JSON 结构都不变。

### 分层

```
Remote*AppService（业务端口的远程实现，瘦适配：InternalCallException → BizException）
   └─ PayInternalApi            @HttpExchange 接口，路径与类型在编译期定下
        └─ RestClient（InternalHttp 统一造）
             ├─ ServiceLocatorInterceptor   逻辑地址 http://PAY → 每次现查 ServiceLocator
             ├─ InternalTokenInterceptor    X-Internal-Token；没配 → NOT_CONFIGURED
             ├─ JdkClientHttpRequestFactory HTTP/1.1 · 连接 3s · 读超时按服务
             └─ 状态处理器                  非 2xx → REMOTE_ERROR（不读 body 进日志）
```

**逻辑地址 + 拦截器改写**，而不是启动时把 baseUrl 写死：
① 保住「没配地址在调用时报」（AC2，`RemoteFeeRuleFailureTest` 第 4 条钉着它）；
② 寻址每次现查，将来 `ServiceLocator` 换成服务发现实现时这里不用改（AC8）——
这正是 Spring Cloud LoadBalancer 拦截 `http://服务名` 的同一种做法，届时可以直接替换。

### 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `backend/svc-client/pom.xml` + 父 pom `<module>` | 只依赖 `spring-web`；模块扫描是自动发现的（`scripts/lib/backend-modules.mjs`），不用登记 |
| 新增 | `svc-client/…/ServiceClients.java` | 造 `RestClient` 与代理：HTTP/1.1、连接 / 读超时、逻辑地址改写、令牌头、非 2xx → `REMOTE_ERROR` |
| 新增 | `svc-client/…/ServiceClientSpec.java` | 一次造代理需要的全部参数（寻址函数、令牌头名、令牌、是否必须有令牌、超时） |
| 新增 | `svc-client/…/CallOutcome.java`、`ServiceCallException.java`、`ServiceCalls.java` | 五种失败分类；执行一次调用并把传输异常映射到分类 |
| 新增 | `job/job-api/…/JobHttpPaths.java` | 两个路径常量，**纯字符串，不加依赖** |
| 修改 | `shop-app/…/portal/internal/JobHandlerEndpoint.java` | mapping 改用 `JobHttpPaths` 常量，路径值不变 |
| 修改 | `job/job-worker/pom.xml` | 加 `svc-client` |
| 新增 | `job/job-worker/…/JobBusinessApi.java` | `@HttpExchange`：`declarations()`、`run(handler, body)`，返回 `JsonNode` —— 解析沿用现有的宽松规则（缺字段取默认值） |
| 修改 | `job/job-worker/…/HttpBusinessClient.java` | 改调 `JobBusinessApi`；409 / 404 / 解析失败 / 没配地址的映射一条不改 |
| 修改 | `backend/shop-base/pom.xml` | 加 `svc-client` |
| 新增 | `shop-base/…/svc/InternalHttp.java` | shop 侧的薄封装：`ServiceLocator` + `shop.services.internal-token` + `X-Internal-Token` |
| 新增 | `pay/pay-domain/…/pay/client/PayInternalPaths.java`、`PayInternalApi.java` | 路径常量 + `@HttpExchange` 接口（放 pay-domain：shop-app 与 pay-svc 都看得见） |
| 修改 | `pay/pay-svc/…/InternalPayEndpoint.java` | mapping 改用 `PayInternalPaths` 常量，路径值不变 |
| 新增 | `shop-app/…/payclient/PayClientConfig.java` | `@ConditionalOnProperty(shop.pay.deployment=standalone)` 下造 `PayInternalApi` |
| 修改 | `shop-app/…/payclient/impl/RemoteOps{FeeRule,SettleInvoice}AppService.java` | 改调 `PayInternalApi`；失败语义不变 |
| 修改 | `shop-app/src/test/…/payclient/RemoteFeeRuleFailureTest.java` | 4 条用例走新栈，**断言不改** |
| 新增 | `svc-client/src/test/…/ServiceClientsTest.java` | 本地起 JDK `HttpServer`（真实 socket，不用替身）：五种分类、HTTP/1.1、令牌头、每次现查地址 |
| 新增 | `job/job-worker/src/test/…/HttpBusinessClientTest.java` | 真实 socket：409 / 404 / 500 / 2xx 坏 JSON / 超时 / 没配地址 各一条 |
| 删除 | `shop-base/…/svc/InternalClient.java` | 最后一步，调用方清零后删 |

### 关键接口

```java
@HttpExchange("/internal/pay")
public interface PayInternalApi {
    @GetExchange("/fee-rules")                         List<FeeRuleVO> feeRules();
    @GetExchange("/fee-rules/effective")               Map<String, Integer> effectiveRates(@RequestParam("at") long at);
    @GetExchange("/settle-invoices")                   List<SettleInvoiceVO> settleInvoices(/* 与现有查询参数逐个对齐 */);
    @PostExchange("/settle-invoices/{no}/issue")       SettleInvoiceVO issue(@PathVariable("no") String no, @RequestBody IssueReq req);
    @PostExchange("/settle-invoices/{no}/reject")      SettleInvoiceVO reject(@PathVariable("no") String no, @RequestBody RejectReq req);
}

// svc-client（不认识任何配置键与头名）
public final class ServiceClients {
    public static <T> T create(Class<T> api, ServiceClientSpec spec);
}
public final class ServiceCalls {
    /** 执行一次调用；失败统一抛 ServiceCallException(outcome, service, status)，消息里不带 body */
    public static <R> R call(String service, Supplier<R> invocation);
}

// shop-base
public class InternalHttp {
    /** service 用 ServiceName 常量；readTimeout 按服务给（今天 pay 是 5s） */
    public <T> T client(String service, Class<T> api, Duration readTimeout);
}
```

### 执行步骤（每步单独提交，每步都能停）

| 步 | 做什么 | 完成判据 |
|---|---|---|
| 1 | 新模块 `svc-client` + 测试，**不接任何调用方** | `ServiceClientsTest` 五种分类全绿；逐条消融变红 |
| 2 | job：`JobHttpPaths` 常量 → 服务端改用常量 → `JobBusinessApi` → `HttpBusinessClient` 改调 | `HttpBusinessClientTest` 全绿；`JobApplicationSmokeTest` 证明 worker **仍是非 web 应用**（没起端口） |
| 3 | pay：`PayInternalPaths` + `PayInternalApi` → 服务端改用常量 → `InternalHttp` + `PayClientConfig` → 两个 `Remote*` 改调 | `RemoteFeeRuleFailureTest` 4 条**断言不动**全绿；`PayApplicationBootTest` 绿 |
| 4 | 删 `InternalClient`；`git grep InternalClient` 只剩文档 | 全量测试绿 |
| 5 | 冒烟：本机起 `job-worker` 对 `shop-app` 手动触发一个任务；起 `pay-svc` + `shop-app(standalone)` 点费率页与开票页 | 真实往返成功；停掉对方后报「不可达」而不是空结果 |
| 6 | 重跑生成物（后端分层清单、依赖清单）；整套 pre-push；部署 `shop-app` 与 `job-worker` | 闸门全绿；两个进程 health 回到 200，调度器下一次触发成功 |

**生产影响**：
- **job 这条是生产在用的**：`job-worker` 每一次触发任务都走它。上线后要看调度器下一轮触发的结果，而不只是 health；
- pay 这条：生产是 `embedded` 形态，不走这段代码，上线后行为不变；风险在将来切 `standalone` 那天，以第 5 步冒烟为准。

## §3 选型

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| A. HTTP Interface（`@HttpExchange` + `RestClient`） | spring-web 自带、不引 Spring Cloud；与 MVC 共用序列化；内置 Observation | 无内置负载均衡 / 熔断，要自己接 | ✅ 采用 |
| B. Spring Cloud OpenFeign | 发现、负载均衡、熔断一个注解带上 | 引入整套 Spring Cloud；官方定为只维护；这些能力我们今天都用不上（ADR-023） | ❌ |
| C. 保持手写 `InternalClient` | 零改动 | 路径与类型全在字符串里，每加一个端点多一份手工反序列化；漂移只能靠对方报错才知道 | ❌ |

## §4 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| `/internal` 被全局信封包住 | 200 + 字段全 null，**不报错**（仓库踩过） | AC6 回归用例 |
| JDK HttpClient 走 HTTP/2 发大 body 挂住 | 调用卡死直到超时 | 请求工厂写死 `HTTP_1_1`，`WireTest` 断言协议版本 |
| 异常映射漏了一种 | 运维看到「连不上」却是配置问题，守着一个不会自己好的故障 | 五种 `Outcome` 各一条用例，逐条消融 |
| 将来有人给写操作加重试 | 重复开票 / 重复驳回 | `InternalCalls` 不提供重试；`@Retryable` 只允许加在只读方法上，写进 `PayInternalApi` 的类注释 |
| 客户端与服务端路径漂移 | 调用 404 | AC7：两边引用同一份常量，漂了编译不过 |
| `job-worker` 带上 `spring-web` 后被 Boot 判成 web 应用、起了端口 | 多一个监听端口、多吃内存 | `spring-web` 单独不带 Servlet API，Boot 判不成 web 应用；`JobApplicationSmokeTest` 断言 |
| 自造 `RestClient`（不走 Boot 的 `RestClient.Builder`）拿不到自动的 Observation | 链路追踪暂时不经过这两条 | 今天没有接 tracing，无损失；接 tracing 那天把 `ObservationRegistry` 传进 `ServiceClientSpec` |

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试方法 | 跑过 | 消融验证 |
|---|---|---|---|
| AC1 | `git grep -nE '"/internal/pay\|TypeReference' -- backend/shop-app/src/main/java/ai/neargo/shop/payclient` 为空 | | — |
| AC2 | `PayInternalApiWireTest#fiveOutcomesAreDistinct` · `RemoteFeeRuleFailureTest#notConfiguredIsDistinctFromUnreachable` | | 拦截器里「没配」改成返回空 → 红 |
| AC3 | `RemoteFeeRuleFailureTest#unreachableRulesThrowsInsteadOfEmptyList` / `…EffectiveRates…` | | 适配层吞异常返回空 → 红 |
| AC4 | `RemoteFeeRuleFailureTest#addIsRefusedUntilItHasAnIdempotencyKey` | | — |
| AC5 | `PayInternalApiWireTest#sendsTokenOverHttp11` · `#missingTokenIsNotConfigured` | | 去掉 `HTTP_1_1` / 去掉令牌头 → 红 |
| AC6 | `PayInternalApiWireTest#internalResponseIsNotEnveloped` | | 服务端响应套上 `{code,msg,data}` → 红 |
| AC1b | `HttpBusinessClientTest` 各条 | | 409 分支改成 FAILED → 红 |
| AC7 | 编译：两边注解引用同一常量 | | 服务端改回字面量并改错 → 另一侧不受影响，所以这条靠「只许用常量」的 grep：`git grep -nE '"/internal/(pay\|job)' -- '*.java'` 在 main 代码里为空 | 

| AC8 | `PayInternalApiWireTest#resolvesBaseUrlPerCall` | | 改成启动时写死 → 红 |

## §6 对账二 · 设计 → 实现

（实现完填：`git diff --stat` 与 §2 模块设计逐行比对）

## 偏差说明

（实现完填）
