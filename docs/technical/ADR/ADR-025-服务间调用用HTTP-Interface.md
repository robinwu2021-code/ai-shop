# ADR-025 · 服务间调用用 HTTP Interface，不用 Feign

**日期**：2026-09-24 　**状态**：提议 　**相关**：[ADR-023 服务发现先不装中间件](ADR-023-服务发现先不装中间件.md) ·
[TDD-服务间调用换 HTTP Interface](../design/TDD-服务间调用换HTTP-Interface.md)

## 背景

进程之间的调用今天由 `InternalClient`（shop-base，JDK HttpClient）承担：调用方自己拼路径、
自己用 `TypeReference` 反序列化。只有 `shop-app → pay-svc` 一条链路、5 个端点时还能撑住，
但每加一个端点就多一份手工代码，客户端与服务端的路径漂移只能等对方报 404 才知道。

## 决定

**服务间调用一律用 Spring 的 HTTP Interface（`@HttpExchange` + `RestClient`），不引入 OpenFeign。**

- 每个被调服务一个 `@HttpExchange` 接口，放在调用方；
- `RestClient` 由 shop-base 统一构造，保留四条老规矩：HTTP/1.1、`X-Internal-Token`、不记 body、五种失败分类；
- 地址用**逻辑名**（`http://PAY`）加拦截器，每次调用现查 `ServiceLocator` —— ADR-023 留下的那个接缝不变。

## 为什么不是 Feign

- Feign 的卖点是「一个注解带上服务发现、负载均衡、熔断」，而这三样 ADR-023 已经决定**现在不装**；
  为用不上的能力引入整套 Spring Cloud BOM 并跟 Boot 4 对齐版本，不值得；
- Spring Cloud 官方已把 OpenFeign 定为只维护，推荐迁到 HTTP Interface；
- HTTP Interface 在 `spring-web` 里，与 MVC 共用一套 Jackson 配置和 `HttpMessageConverter`，
  `RestClient` 自带 Micrometer Observation。

## 将来

多实例那天，治理能力都接在 `RestClient` 这一层，接口与业务代码不动：

| 需要 | 接法 |
|---|---|
| 服务发现 | 给 `ServiceLocator` 加一个 `DiscoveryClient` 实现（Consul / Nacos），或上 K8s 后改写 Service 域名 |
| 负载均衡 | Spring Cloud LoadBalancer：`@LoadBalanced RestClient.Builder`，逻辑名 `http://PAY` 正好是它认的格式 |
| 重试 | Framework 7 的 `@Retryable`，**只加在只读方法上** |
| 熔断 | 真的需要时再上 Resilience4j |

## 不适用

- **`job-worker`**：刻意不带 web 依赖（见其 `pom.xml`），只调两个端点，保持自带的 JDK HttpClient；
- **第三方 API**（微信、个推、FCM、APNs、短信、视觉识别）：不是服务间调用，不受本 ADR 约束。
