# App 主体页渠道列表 · App 侧需求 · 2026-11-19

依据：[TDD-门店代码与商户渠道展示](../technical/TDD-门店代码与商户渠道展示.md) C1–C3、C6。后端：ai-shop `1c476d3ef`（`codex/ops-fiat-local-closure`）、near-pay `24a2ab6de`（`feat/channel-pending-entity-accounts-20261119`）。**均未推送、未部署 166**；部署前可先用 mock 做。

## 接口（路径不变）

`merchant_api.dart#channels` 照旧调 `GET /biz/merchant/payment?entityNo=`。

主体在 near-pay 有已核验支付商户时（oscar `MUME88600253990002308`、roger `MUME02380159443150569472`），每行改为 near-pay 渠道账户，新增字段（只增不改）：

| 字段 | 含义 |
|---|---|
| `channelMerchantId` | 渠道商户号**全号**（如 `1626862643`、`MS354053380`、`350421`） |
| `sandbox` | 测试渠道为 `true` |
| `channelStatus` | `ENABLED` / `PENDING`（待开通）/ `DISABLED`，运营维护 |
| `pendingReason` | `CHANNEL_NOT_LIVE`（平台尚未支援此渠道）、`ACCOUNT_UNVERIFIED`（待補密鑰並完成核驗）或 `null` |
| `applyStatus` | `ACTIVE`、`FROZEN` 或新值 `PENDING_ACTIVATION`（显示「待開通」） |
| `channelName` | 渠道目录名称；`payChannel` 可能是新编码 `GAMAPAY`、`ALIPAY`、`EZPAY`、`EZPAY_CROSSBORDER`、`FUBON_WECHAT`、`PIPAY`、`ATOME`、`LINEPAY_TEST`、`NEWEBPAY_TEST` |
| `enabledStores` | `[{storeNo}]`（App 原本就读这个字段） |
| `storeNo` | `null`（主体级行） |

主体在 near-pay 没有支付商户时，返回与现在完全一样（本地进件表）。

## App 侧要做

1. `MerchantChannel` 解析上面字段；`PENDING_ACTIVATION` 映射为「待開通」，按 `pendingReason` 显示两种说明。
2. 渠道卡片显示「渠道商戶號 <全号>」与「沙箱」标签；未知 `applyStatus` 仍走 `ChannelState.unavailable`（C6）。
3. 新渠道名优先用后端 `channelName`；l10n（zh-TW / zh-CN / en）加「待開通」「沙箱」「渠道商戶號」「平台尚未支援此渠道」「待補密鑰並完成核驗」。
4. mock 路由带一组样例；`flutter test`；界面变了更新 `docs/technical/design/ui-catalog.json`。

## 上线后在 166 验收

- oscar 主体：LINE Pay `1626862643`、藍新 `TRL00001898` 已启用；橘子支付 `350421`、富邦微信 ×2、ezPay `PG100000254010` 待開通；另有两个沙箱。
- roger 主体：LINE Pay `1629714284`、藍新 `MS354053380`（待補密鑰並完成核驗），橘子支付 `136768`、支付寶 `50766768201`、ezPay `PG300000185100`、Pi拍錢包 `US364LU2N44ALZPG`、Atome（平台尚未支援此渠道）。
