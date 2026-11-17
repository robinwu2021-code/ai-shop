# App 店主权限 · 支付渠道三端统一 · 收银台付款方式耗时 交接

日期：2026-11-17 至 2026-11-18；环境：UME166；验证账号：Oscar（`U261113VG2HFDJMK0`，主体「李昱達」`M261113Y3QW5XKEFW`，支付商户 `PM2026111707510001`，门店 ume早餐店 `ST2611132NS49C2EPQ`）。

## 一、版本

### 代码（均已推送）

| 仓库 | 分支 | 提交 | 本交接相关提交 |
|---|---|---|---|
| near-pay（PAY） | `release/166-live-20261117` | `c761ed8fa` | `a289204cf` 来源委托；`b198eda6c` 商户渠道视图 + 运营端渠道总览；`d1ecb836b` 门店 ID 修正；`a21e4a150` 门店页问收银台；`c761ed8fa` 收银台显示只读缓存 |
| ai-shop（Shop） | `release/166-live-20261117` | `8ae9a4a9b`（本文档提交在其后） | `e64a2288e` 店主权限委托；`341a03065` 转发 `merchant-channels`；`57132abe8` TDD 记录；`0a0aa81fb` 接口登记；`8ae9a4a9b` 独立控制器 |
| ai-shop-bapp（App） | `feat/store-payment-methods` | `0363d56e1` | `6d9fe6a`/`8b4be70` 渠道审核分组、无权限提示；`5d9e4b8` 无在线申请渠道不给申请入口；`f8ec65b` 主体详情读 near-pay 渠道视图、门店页收银台标记；`0363d56` 无可申请渠道不显示「新增收款渠道」 |

ai-shop 三次推送均因本机 `commerce_test` MySQL 账号无密码导致 143 个 commerce 测试失败而被 pre-push 拦下，经用户批准 `--no-verify`；其余闸门（接口登记、控制器内聚）已按要求修正后通过。

### 166 线上制品（overlay 发布，均有备份与基线校验）

| 服务 | 当前 SHA-256 前 12 位 | 本轮备份（`*.before-<RID>`，按时间倒序） |
|---|---|---|
| near-pay `/data0/app/nearpay-prod/pay-svc.jar` | `3d01baaf9f84` | `np-checkout-display-cache-20261118`、`np-checkout-display-20261117-a21e4a150`、`np-checkout-store-id-20261117-d1ecb836b`、`np-channel-view-20261117-b198eda6c`、`np-source-delegation-20261117-a289204cf` |
| 运营端静态页 `/data/www/oms-root/oms` | `nearpay-oms-releases/np-channel-view-20261117-b198eda6c`（上一版 `sandbox-unify-20261117-0a08c0b3`，已包含在本分支） | — |
| ai-shop `/data0/app/ai-shop/shop-app.jar`（api + ops 两容器） | `351b61d3616f` | `as-channel-controller-20261118-8ae9a4a9b`、`as-channel-view-20261117-341a03065`、`as-source-delegation-20261117-e64a2288e` |
| App | nearboss 品牌 debug 包 `0.1.0 (1)`，连 `https://twpay.umeshop.ai/shop-api`，APK SHA-256 前 12 位 `8bb68b5009fa`（未发应用商店，仅模拟器验证） | — |

无库表迁移（ai-shop 迁移版本仍为 678，near-pay schema V107）。

## 二、做了什么

### 1. App 店主权限由 ai-shop 决定（TDD-App支付权限由ai-shop决定）

- 问题：运营代办开通的支付商户在 near-pay 成员表里没有任何管理员，店主 Oscar 在 App 看渠道审核提示无权限。
- 用户约定：App 权限由 ai-shop 决定，near-pay 只对 ai-shop 提供微服务；店主默认全部权限，不在运营端设置。
- 做法：ai-shop 对店主名下的支付商户自行确定商户号，带来源调用方凭据转发 `/biz/pay/v2/*`；near-pay 收到经核验的来源凭据即按该商户管理员处理，不查成员表。App 伪造的 `X-Internal-*` 头一律去掉。`/memberships` 对店主由 ai-shop 直接返回。
- 未做：店员的支付权限仍走 near-pay 成员表，后续在 App 员工管理里按同一原则迁移。

### 2. 支付渠道三端统一数据源（TDD-支付渠道三端统一数据源）

- 问题：App 主体详情读的是 ai-shop 旧本地进件表（只剩一条旧 WECHAT），near-pay 里在收款的 4 个账户一个不显示；App 门店页与 H5 各算各的。
- 做法：
  - near-pay `MerchantChannelViewService`：账户（含已开通付款方式、正式/测试）+ 无账户渠道的最新未结束进件，合为一份；另给本市场可选渠道及能否在线申请。出口：运营端 `GET /pay-ops/v2/merchants/{merchantNo}/channels`、商户端 `GET /biz/pay/v2/merchant-channels`（ai-shop 店主委托转发）。
  - 运营端商户抽屉「渠道」页签顶部加「渠道总览」。
  - App 主体详情「渠道审核」分四组：已開通、申請中、可新增（只含能在线申请的藍新）、暫未開放（由平台开通）。
  - 门店付款方式每项带 `checkout`（收银台此刻是否显示），App 对「开着但收银台不显示」标「收銀台目前不顯示」。
- 用户确认口径：测试付款方式照常显示并标「測試」；App 只能开关门店级付款方式，账户与商户级由运营端控制。

### 3. 入口修正

- 「LINE Pay 申請」进去无可选项：没有在线进件的渠道不再给申请入口。
- 正式渠道都已开通时「新增收款渠道」进去是空页：此时不显示该按钮。

## 三、验证（2026-11-18，手机模拟器 retail_phone，nearboss 包，Oscar 登录）

| 端 | 结果 |
|---|---|
| 运营端 李昱達「渠道总览」 | 4 行：藍新金流 `****1898` Apple Pay、LINE Pay `****2643`、LINE Pay 測試 `****0931`、NEWEBPAY Sandbox `****3411` 信用卡一次付清；均「可收款」 |
| App 主体详情「渠道审核」 | 「已開通的渠道」同上 4 行，测试带「測試」；无 WECHAT；无「新增收款渠道」按钮 |
| H5 收款码 `https://twpay.umeshop.ai/q/nAjNe7H2GpWc1mEQu1hESw` | `payMethods=[APPLE_PAY, LINE_PAY, CARD]`，`testMethods=[CARD]`，`sandboxMethods=[LINE_PAY, CARD]` |
| App 门店「付款方式」 | 正式 LINE Pay、Apple Pay；测试 LINE Pay、信用卡；均开、无「不显示」标记，与 H5 一致 |
| 接口耗时（ai-shop 访问日志） | `/biz/pay/v2/merchant-channels` 328 ms；`/biz/payment/store-method` 731 ms |
| 自动化 | Flutter 全量 1797 通过；运营端 ops-web 488 通过；near-pay 新增/改动用例通过（pay-domain、pay-svc 既有 H2 环境类失败已在改动前代码复现，与本轮无关）；ai-shop 转发与路由相关 32 个通过 |

## 四、收银台付款方式耗时分析（未修，待决定）

### 缓存的是什么、在哪

- 不是渠道列表：App 主体详情的渠道视图每次直查，无缓存。
- 缓存的是 H5 收银台的「付款方式列表」（`APPLE_PAY / LINE_PAY / CARD` 及测试标记），在 near-pay 进程内存 `CheckoutServiceImpl.codeMethodsCache`，键 `环境|门店|市场|币种`，5 分钟过期，启动预热（`CheckoutDisplayWarmup`，预热 2 个码耗时约 23.5 秒）。此缓存原本就有（扫码首页用），不是本轮新增。
- App 门店「付款方式」页为了与 H5 一致，读的是同一份（`CheckoutService#displayMethods`）：有缓存直接返回（过期也先返回并后台刷新），无缓存本次返回空、不标收银台状态。

### 实测

| 请求 `GET /checkout/v1/codes/nAjNe7H2GpWc1mEQu1hESw` | 耗时 |
|---|---|
| 缓存已过期（当场重算） | 7.29 s |
| 命中缓存 | 0.57 s（基本是本机到台湾的网络往返） |

即一次计算约 6.7 秒。顾客在某门店 5 分钟无人打开后扫码，同样要等这几秒（现状问题，非本轮引入）。

### 慢在哪（代码层，未做线上 SQL 计时）

`RouteSelectionPolicy.available(storeId, market, currency, null, H5)`：

1. 门店绑定的每个渠道账户（李昱達门店 4 个）各算一遍；
2. 每个账户都调 `EffectiveCapabilityQuery.available` → `CatalogRepository.snapshot()`，**整份产品目录（15 个产品）连同子表重新读一遍**；
3. 每个匹配产品再逐层独立查询：账户产品（`accounts.check`）、门店投影 `projectionsByExternalId` + 商户/门店收款策略（`stores.check` → `CollectionPolicyService#decide`）、风控、开通证明（`readiness`）。

一次计算上百次数据库往返，大部分是重复读同一批数据。ai-shop → near-pay 这一跳本身不慢（渠道视图 328 ms 为证）。各查询的精确耗时拆分尚未测量。

### 建议

1. 先在 near-pay 加一次计算内的分段计时（或本机复现 + SQL 计数），确认耗时分布；
2. 同一次计算内目录只读一次、各层检查按门店/商户批量查，去掉逐账户 × 逐产品的重复查询；
3. 降到百毫秒级后，门店页可直接实时计算，不再依赖收银台缓存与后台刷新。

## 五、遗留

| 项 | 说明 |
|---|---|
| 收银台付款方式计算慢 | 见第四节，待决定是否优化 |
| 店员支付权限 | 仍走 near-pay 成员表，按「权限由 ai-shop 决定」后续迁移 |
| 渠道显示名 | App 中藍新正式账户显示为「信用卡（藍新）」，实际只开 Apple Pay，建议改名「藍新」 |
| 测试付款方式对顾客可见 | ume早餐店 H5 仍显示两个测试项，测试结束后在 App 门店「付款方式」关闭 |
| 本机 commerce 测试库 | `commerce_test` 无密码，ai-shop pre-push 全量测试会一直拦，需重设本机账号或调整闸门环境 |
| ai-shop 旧接口 | `/biz/merchant/payment` 主体详情已不再使用，接口保留，待确认无其他调用后下线 |

## 六、相关文档

- `docs/technical/TDD-App支付权限由ai-shop决定.md`
- `docs/technical/TDD-支付渠道三端统一数据源.md`
- `docs/technical/TDD-门店付款方式-App设置.md`
- `docs/handoff/oscar-channel-sync-2026-11-16/README.md`（李昱達主体与渠道账户迁入记录）
