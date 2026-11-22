# 交接 · 支付三端经网关透传、权限由提供接口的服务判（2026-11-22）

分支：`docs/pay-gateway-proxy-20261122`（从 `origin/main` `1cc4fef64` 切出），工作树 `~/work/ai-shop-payproxy`。
本次只出方案与盘点，**没有改业务代码，没有动任何环境**。

## 1. 用户要求

> 统一网关针对支付部分也调整为仅仅做代理，权限统一交给支付，或者说谁提供 controller，谁提供权限，微服务服务没有权限，先整理方案
>
> 形成方案以及交接文档，提交代码

## 2. 结论（按推荐项定稿，用户可改选）

- 支付请求在 ai-shop 网关**只认证、只转发**，不判权限。
- **谁提供 controller，谁判权限**：near-pay 的接口 near-pay 判；ai-shop 进程里的接口 ai-shop 判。同一请求只判一次。
- near-pay **不放权限数据**：不建角色表、不维护映射；权限码、数据范围在 ai-shop 授予，near-pay 调统一认证接口取（按令牌缓存 30 秒）。
- 运营端 11-21 已按此上线；本次推广到 **B 端 `/biz/pay/v2`**，以及 ai-shop 里只是转调 near-pay 的适配接口。
- 不推广到库存等其他微服务。

## 3. 产物

| 文件 | 内容 |
|---|---|
| [TDD-支付三端经网关透传与权限归属.md](../../technical/TDD-支付三端经网关透传与权限归属.md) | 方案正文：现状、原则、三端做法、B 端统一认证接口、near-pay 改动、分期回退、测试对账、选项、实施清单、风险 |
| [附录-入口盘点](../../technical/TDD-支付三端经网关透传与权限归属-附录-入口盘点.md) | 57 个支付相关 controller、330 个接口，逐个列出现有判权码与依赖，按「B 转发 / C1 薄转调 / C2 ai-shop 有业务」初判 |
| `scripts/inventory-pay-endpoints.py` | 生成上面附录的脚本，代码变了重跑：`python3 scripts/inventory-pay-endpoints.py . > 附录正文` |
| [ADR-060](../../technical/ADR/ADR-060-网关层独立模块与鉴权不进路由网关.md) 文末「修订」 | 第 3、5、6 条在支付上的新口径 |
| `docs/technical/README.md` | 文档索引（`gen-doc-index.mjs` 生成） |

## 4. 现状速览（盘点结果）

| 端 | B 转发（ai-shop 与 near-pay 各判一次） | C1 初判（薄转调，候选迁透传） | C2 初判（ai-shop 有业务，保留） |
|---|---|---|---|
| 运营 | — | 110 | 31 |
| B 端 | 22 | 58 | 89 |
| C 端 | — | 2 | 18 |

运营端支付中心（near-pay `/pay-ops/v2` 约 329 个接口）已是透传，不在上表。

几个容易误会的事实：
- v2 上 `SHOP_PAY_DEPLOYMENT=standalone`，支付全在 near-pay（166:18093），ai-shop 里的支付 controller 都经 `payclient` 远程调用。
- near-pay B 端目前按**自己的成员表**（`pay_merchant_membership`、`pay_access_membership`）判权，店主靠 ai-shop 转发时加的来源委托头获得全权（TDD-App支付权限由ai-shop决定）。方案把这两件事都改由统一认证接口给出。
- 本仓库 b-app 调的是 ai-shop 的 `/biz/payment/**`（形态 C），**不是** near-pay 的旧版 `/biz/pay/*`；`/biz/pay/v2` 的调用方是 App（nearboss），不在本仓库。

## 5. 下一步（按 TDD §5）

| 阶段 | 做什么 | 谁 | 前置 |
|---|---|---|---|
| 0 | 把附录的 C1/C2 初判逐个人工确认（near-pay 有无公开对应接口、ai-shop 有无拼数据） | 本仓库 | 无 |
| 1 | 透传注册按端配置；B 端 `/biz/pay/v2/**` 透传（开关 `shop.gateway.biz.pay-proxy.enabled` 默认关）；`/internal/auth/v1/merchant-staff` | 本仓库 | 无，不依赖 near-pay |
| 2 | near-pay B 端改调新认证接口 + 缓存；`canBiz` 判权；`near-pay.biz.permission-source=host+membership` | near-pay（与 RobinWu 对齐排期） | 阶段 1 上线 |
| 3 | 开 B 端透传开关；下线 4 个 B 端转发 controller 与委托改写 | 本仓库 | 阶段 1 |
| 4 | 观察一周无「只靠成员表通过」后切 `host`，成员表只读保留一个周期 | near-pay + 用户 | 阶段 2 |
| 5 | C1 逐批迁透传 | 两边 | 阶段 0 |

实施时按 TDD §8 的清单与 §6 的测试走；档位 2，动 `Perms` 或端点要同步生成文档（`node scripts/check-generated-docs.mjs`，裸工作树要先链 `node_modules`）。

## 6. 仍需用户拍板的（已按推荐项写入方案，见 TDD §7）

1. 「微服务没有权限」= 不建角色与权限表、但在服务内判。
2. 店员的支付权限码沿用 ai-shop B 端员工角色。
3. near-pay 成员表观察期后只读保留一个发布周期再删。
4. near-pay 旧版 `/biz/pay/*` 不经网关开放。
5. 不推广到其他微服务。
6. near-pay 排期。

## 7. 相关记忆与文档

- 记忆：`pay-ops-authz-in-service`（运营端同一决定）、`gateway-decoupled-from-business`、`app-perms-decided-by-ai-shop`、`ai-shop-v2-ume166`。
- 文档：TDD-支付运营端经网关转发与权限下沉、TDD-App支付权限由ai-shop决定、ADR-045、ADR-060、ADR-061。
