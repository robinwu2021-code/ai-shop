# ADR 044 支付接入层模块 pay-portal

> 状态：已接受 · 2026-10-25（按[开发计划 · 支付服务](../开发计划-支付服务.md) P1 推荐项执行）
> 关联：[完整设计方案](../TDD-支付服务-完整设计方案.md) D1 · [I0 地基](../TDD-支付服务-I0地基.md)
> 修订：ADR-021 §L1（「支付域没有 controller，三端 controller 全在主应用」）—— 增加一个**支付自己的接入层模块**
> 编号：原 ADR-042，2026-10-26 与「统一销售渠道接入」撞号，顺延为 044（[完成度复核](../支付服务-完成度复核与剩余开发计划.md) D-1）

## 1 背景

支付要直接面向五类调用方：支付运营端、第三方（开放 API）、刷卡机、商家端、扫码收银 H5。
它们的鉴权方式各不相同（运营会话 + `pay:*` 权限码、AppId + 签名、设备密钥 + 店长授权码、商家会话、一次性收银令牌），
且将来要随支付服务独立部署。

ADR-021 §L1 规定 `pay-domain` 没有 controller，换来四条收益：领域不鉴权、能进 AOT、不读会话、判权只在接入层。
`PayHasNoControllerTest` 守着它。把这五类 controller 塞进 `shop-app` 则独立部署时搬不走；塞进 `pay-domain` 则四条收益全失。

## 2 决定

1. 新建 **`backend/pay/pay-portal`** 模块：支付的全部面向人与第三方的 controller 与鉴权过滤器住这里；`pay-domain` 仍然**零** controller。
2. **包名 `ai.neargo.shop.pay.api.{ops,open,terminal,biz,checkout}`**，鉴权在 `ai.neargo.shop.pay.api.auth`。
   - 用 `api` 而不是设计稿里的 `portal`：`ArchitectureTest#controllersInDomainApiOrPortal` 只允许 controller 住在 `..api..` 或 `ai.neargo.shop.portal..`；
     单域的 API 面跟着域走，正是 `..api..` 的定义。
   - `pay` 是 `ArchitectureTest` 的领域包，领域包不得依赖 Servlet；鉴权过滤器必然依赖它。
     因此 `ai.neargo.shop.pay.api..` 与餐饮的 `food.api..` 一样，从「领域不得碰 Web 运行时」规则里豁免 —— **只豁免这一个子包**。
3. **每个入口一个固定前缀**，由 `PortalFace` 枚举唯一定义：`/pay-ops/v1`、`/open/pay/v1`、`/terminal/pay/v1`、`/biz/pay`、`/checkout/v1`。
   **2026-10-26 补**：渠道回调 `/pay/callback` 作为第六个面 `CALLBACK`，自 `shop-core` 迁入（[完成度复核](../支付服务-完成度复核与剩余开发计划.md) D-6；I5 S2）。回调入口同样不能进 `pay-domain`（`PayHasNoControllerTest`），而它的鉴权（渠道验签）与其余五个面同样属于接入层。
   controller 的类级映射必须落在本包对应的前缀下（`PortalRouteRules` 守卫）。
4. **两种装配**：内嵌（今天）作为依赖装进 `shop-app`，直调 `pay-domain` 应用服务；独立（I5 起）单独成进程，经 `/internal/pay` 调 `pay-svc`。
   鉴权以 `PortalAuthenticator` 接口隔离，换形态只换实现、不改 controller。
5. `pay-svc` 只暴露 `/internal/**`（`PayApplicationBootTest` 按运行时映射校验）。

## 3 未采用方案

| 方案 | 不采用原因 |
|---|---|
| controller 放 `shop-app/portal/ops/pay/**`（现状延续） | 独立部署时搬不走；开放 API、终端的鉴权链与主应用会话耦合 |
| controller 放 `pay-domain` | 破坏 ADR-021 §L1 的四条收益（AOT、无会话、判权在接入层） |
| 包名 `ai.neargo.shop.pay.portal..` | 被 `controllersInDomainApiOrPortal` 拒；为它放宽规则等于给所有域开口子 |

## 4 代价

- `ArchitectureTest` 多一处豁免（`pay.api..`），与 `food.api..` 同理由；豁免范围由测试本身钉死在这一个子包。
- 现有 `portal/ops/pay/**` 24 个 controller 分批迁入（I5），旧路径保留转发一个版本周期。
- 独立形态启动类推迟到 I5（届时才有 controller 与 Remote 实现）；I0 只立模块、前缀与鉴权接口。
