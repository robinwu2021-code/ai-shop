# 李昱達（Oscar）UME 编号对齐 · UME166 · 2026-11-19

用户决定（2026-11-19）：以 UME 导入的主体与门店为准重新配置；原生建的门店改名、将来下架；新门店店主用 `oscar@umeshop.ai`。

## 对照

| | 旧（原生，待下架） | 新（UME 导入，在用） |
|---|---|---|
| 主体 | `M261113Y3QW5XKEFW` | `MUME88600253990002308`（PENDING_LICENSE） |
| 门店 | `ST2611132NS49C2EPQ`「ume早餐店（舊·待下架）」<br>`ST261117JC0WT5KSDV`「ume早餐店 LINE Pay 沙箱（舊·待下架）」 | `STUME12517541085359112192` ume早餐店<br>`STUME12811472160039108608` ume咖啡廳 |
| 店主 | `U261113VG2HFDJMK0`（oscar@umeshop.ai） | 同左（原为 UME 导入账号 `UUMEU518897318557253632`） |
| near-pay 支付商户 | `PM2026111707510001`（同一个，来源关联已改到新主体） | |

## 做了什么（按顺序）

1. **ai-shop 改库（用户同意直接改库）**：`mch_entity` `MUME88600253990002308` 的 `owner_user_no` 与 `mch_account` id 1067（`MAUME88600253990002308`）的 `user_no` 改为 `U261113VG2HFDJMK0`，`updated_by='oscar-align-20261119'`。
   原值：两处均为 `UUMEU518897318557253632`，version 0，`UME_SEED_20261113`。回滚即改回原值。
   未新增成员行：`uk_mch_account_entity_phone` 不允许同主体同手机号两行。
2. **旧门店改名**：`PUT /ops/stores/{storeNo}/basics`（会同步 near-pay）。主体与支付商户名称未改（法定名称无运营改名接口；near-pay 改名需资料修订审批）。旧门店仍持有短链 `ume-breakfast`。
3. **near-pay 缺陷与修复**：发起归属修复 500，`Illegal mix of collations`。V81/V100/V101 三张表写死 `utf8mb4_unicode_520_ci`，与库默认 `utf8mb4_0900_ai_ci` 冲突。
   - 代码：near-pay 分支 `fix/pay-collation-align-20261119` 提交 `83482eeec`（V112 + MySQL 9 测试），**未推送**。
   - 166：已由 root 手工执行 V112 同样的三条 `ALTER TABLE … CONVERT`（行数 1/0/0 不变）。V112 随下次发布再跑一次是无害的重建。
4. **来源归属修复** `RPCCE00527705E4876`（审批 `AP9247DD6777EF48C1`）：`PM2026111707510001` 来源关联 `SL96B723279D5F4F42` 从 `M261113Y3QW5XKEFW` 改到 `MUME88600253990002308`，并关联两家 UME 门店。单人审核模式下由 CONFIG 提交、审批、执行（REVIEW1 无 `pay:merchant:repair:approve`）。
5. **渠道绑定**（均 ACTIVE）：两家新门店绑 LINE Pay `PM-UME-LINEPAY-1626862643`、蓝新 `PM-UME-NEWEBPAY-TRL00001898`；ume早餐店另绑 `LINEPAY_TEST`、`NEWEBPAY_TEST`。渠道账户未转移（本来就在该商户下）。
6. **收款码**：ume早餐店 `mqB-LZBjK8UM4-YO_BwacQ`，ume咖啡廳 `KdlDi3_wegY_ut0N2RlDgA`（单笔 NT$5,000、日 NT$50,000，同旧码）。
7. **H5 收款入口**（照旧门店）：`CEA73AA7B0D7E349DF`（ume早餐店：LINEPAY_ONLINE、LINEPAY_TEST_ONLINE、NEWEBPAY_APPLEPAY_WEB、NEWEBPAY_TEST_CARD_ONETIME）、`CEE82B9810F93E46CA`（ume咖啡廳：LINEPAY_ONLINE、NEWEBPAY_APPLEPAY_WEB），域名 `twpay.umeshop.ai`（别名放行 tpay）。
   核对：`/checkout/v1/codes/…` 两店付款方式分别为 APPLE_PAY/LINE_PAY/CARD(测试) 与 APPLE_PAY/LINE_PAY。

## 影响与遗留

- **旧门店已不能收款**：其投影仍指向旧主体，而支付商户来源关联已改到新主体，收款码 `nAjNe7H2GpWc1mEQu1hESw`、`Maswh_NWjnpkfp1MPfLfGw` 返回 70082。下架时一并停用这两个码与旧入口 `CE3A941F09E7E94974`、`CEE0415EE3D9CD4969`。
- 短链 `/s/ume-breakfast` 仍挂旧门店，要转给新门店需另行决定。
- 测试付款待用户在 ume早餐店收款页用测试渠道付一笔（输入测试卡/LINE 登录须用户本人）。
- 临时授权仍保留：`ST-UME-CONFIG` 的 `SUPER_ADMIN`（`created_by='ume-batch-temp'`）；near-pay 数据范围 `DSB2C80A6DB2924070`、`DS9A7CFE0DD0314A21`（约 2026-11-21 过期）。
