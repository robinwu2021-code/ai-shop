# TDD-App支付权限由ai-shop决定

状态：已确认（2026-11-17，用户「按最快方案开工」）
档位：2（权限归属变更：ai-shop `/biz/pay/v2/*` 转发增加来源调用方凭据；near-pay BIZ 入口新增来源委托；不改库表、不改 App）
关联：`TDD-B端进件接入near-pay.md`（原「独立 PAY 自己授权」口径，本 TDD 对店主名下商户改为 ai-shop 授权）
创建：2026-11-17

## 验收标准（需求）

用户原话：「oscar 是 app 端的权限……默认应该 oscar 拥有 app 的所有权限，不用设置」「nearpay 对 ai-shop 只提供微服务接口，权限由 ai-shop 来定」。

- **AC1** Given 账号是某经营主体的店主、主体已有支付商户（无论 App 自助还是运营代办开通），When 在 App 打开主体详情「渠道审核」/进件/补件/资料修订/开通进度，Then 可查看并操作，不需要任何人在运营端或 near-pay 授权。
- **AC2** Given 账号不是该支付商户所属主体的店主，When App 请求带上该商户号，Then ai-shop 不为其委托，仍由 near-pay 原成员授权决定（非成员 403）。
- **AC3** Given App 伪造 `X-Internal-Caller` / `X-Internal-Credential`，When 请求经 ai-shop 转发，Then 这些头被去掉，不能冒充来源委托。
- **AC4** Given near-pay 收到带来源调用方凭据的 BIZ v2 请求，When 凭据不对或调用方未登记为来源，Then 不算委托，按原成员授权处理。

不做：店员的支付权限（仍走 near-pay 成员授权，后续在 App 员工管理里定）；删除 near-pay `/biz/pay/v2` 令牌入口与成员表；SoftPOS 终端直连。

## §0 对账一 · 需求 → 设计

| AC | 落点 |
|---|---|
| AC1 | ai-shop `PayBizSourceDelegation`：店主主体（`MerchantEntityService#myEntities`）→ `PaySourceLinkApi#status` 的 `merchantRef`；`PayBizOnboardingRelay` 对店主商户改写 `X-Merchant-No` 并加 `X-Internal-Caller/Credential`（取 `shop.nearpay.store-sync.caller/credential`）；`/memberships` 由 ai-shop 直接返回店主商户（MERCHANT_ADMIN 动作集）。near-pay `BizSessionAuthenticator`：来源委托 → 以指定商户的商户管理员放行，不查成员表 |
| AC2 | `PayBizSourceDelegation#select`：App 指定的商户号不在店主名下 → 不委托，原样转发 |
| AC3 | `PayBizSourceDelegation#withoutInternal` / `#headers`：任何 `x-internal-*` 先去掉 |
| AC4 | near-pay：`InternalCallerRegistry#resolve` 必须通过（不看 verify-credential 开关），且调用方在 `near-pay.platmch.source-callers` |

## §2 实施计划

| # | 改动 | 测试 |
|---|---|---|
| 1 | near-pay `pay-guard` `BizSessionAuthenticator` 来源委托 | `BizDelegationTest`（4）+ 原 `BizSessionAuthenticatorTest` |
| 2 | ai-shop `PayBizSourceDelegation`、`PayBizOnboardingRelay`、`BizPayOnboardingController`、`BizPayMerchantController`（资料修订）、`BizChannelAccountClaimController`、两个 forwarder 放行内部头 | `PayBizSourceDelegationTest`（6）+ 原 relay/forwarder 测试 |
| 3 | 166 overlay 发布两端；Oscar 在 nearboss 手机模拟器验证 AC1 | 线上证据 |

前提：166 near-pay 的 `shop.pay.internal-callers.<ai-shop 调用方>` 已配置凭据摘要、`near-pay.platmch.source-callers` 含该调用方（店铺同步与来源申请已在用）。

## §3 风险

- 店主变更后委托即时跟随 ai-shop（每次请求现查），无存量数据需迁移。
- ai-shop 每次 v2 请求多一次来源状态查询（店主通常 1–3 个主体）。
