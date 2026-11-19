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
- 短链已切到新门店（用户 2026-11-19 要求）：旧门店 slug 改为 `ume-breakfast-old`（同步后旧码 `nAjNe7H2GpWc1mEQu1hESw` 的门店码随之改名、释放原码），新门店 slug 设为 `ume-breakfast`，同步后门店码落到 `mqB-LZBjK8UM4-YO_BwacQ`。`/s/ume-breakfast` 已打开新 ume早餐店收银台。
- 测试付款待用户在 ume早餐店收款页用测试渠道付一笔（输入测试卡/LINE 登录须用户本人）。
- 临时授权仍保留：`ST-UME-CONFIG` 的 `SUPER_ADMIN`（`created_by='ume-batch-temp'`）；near-pay 数据范围 `DSB2C80A6DB2924070`、`DS9A7CFE0DD0314A21`（约 2026-11-21 过期）。

## roger@umeshop.ai（創科系統有限公司，UME 商户 02380159443150569472）· 同日

- 账号、主体 `MUME02380159443150569472`、5 家门店与 UME 一致；支付商户 `PM0E0F6CC8F7454D75`（名称仍为「TEST ONLY 創科系統有限公司 UME166」，正式使用前要改名）已关联 5 家门店。
- **LINE Pay 登记**（用户同意以 UME 导出为凭证）：渠道账户 `PM1998CCD12EEE4748`，渠道号 `1629714284`，凭证 `file:ume/msm_mms/chn_mer_scan_linepay_info/CM443256451075538944`（原记录快照在 `nearpay_prod.stg_ume_active_payment_merchants_20261112`）。状态 UNVERIFIED / PENDING：须商户或运营补 LINE Pay 密钥并核验后才能启用、绑定门店。
- **蓝新 `MS354053380` 未登记**：UME 内 3 户共用（02380159443150569472、88600248120000008、88600254990000004），无法按 UME 数据定归属。
- **未迁**：near-pay 暂不支持的渠道（两户：橘子支付、支付寶、ezPay 簡單付、Pi 拍錢包、Atome、富邦微信）须先做渠道目录「待开通」（TDD）；UME 店员账号（oscar 28、roger 43）未导入，多数无手机号。
- 另：166 near-pay 于 05:04 UTC 被其他会话重新部署（jar `a6219a718557`），已核对收银域名并存与门店付款方式仍在。

## 待开通渠道与蓝新（用户 2026-11-19 决定）

- **渠道目录新增 7 个「待开通」渠道**（near-pay `POST /pay-ops/v2/channels`，市场 TW、FIAT，适配器未注册，状态 DISABLED，要等适配器上线才能启用）：`GAMAPAY` 橘子支付、`ALIPAY` 支付寶、`EZPAY` ezPay簡單付、`EZPAY_CROSSBORDER` ezPay境外、`FUBON_WECHAT` 富邦微信、`PIPAY` Pi拍錢包、`ATOME` Atome。
- **渠道账户（均 UNVERIFIED / PENDING，凭证 `file:ume/msm_mms/chn_mer_scan_<alias>_info/<UME 记录号>`）**：
  - oscar `PM2026111707510001`：GAMAPAY `350421`、FUBON_WECHAT `CM529807626322706433` 与 `CM529807626700193793`（原系统无渠道商户号，用 UME 记录号）、EZPAY `PG100000254010`。
  - roger `PM0E0F6CC8F7454D75`：GAMAPAY `136768`、ALIPAY `50766768201`（store_id）、EZPAY `PG300000185100`、PIPAY `US364LU2N44ALZPG`（partner_id，channel_id BEECOMB）、ATOME `02380159443150569472`。
  - 计划表 `mig_ume_channel_20261117` 对 ezPay / 支付寶 / Pi 填的是 UME 记录号，登记时改用原始导出里的真实渠道商户号。
- **蓝新 `MS354053380`** 按 UME 数据登记给 roger（原记录商户名「创科」）：`PM80D58ACC11C044CF`，UNVERIFIED / PENDING。UME 内另 2 户（88600248120000008、88600254990000004）引用同号，批量迁移时再按 UME 数据处理。
- 收银台不受影响：停用渠道没有产品，不出现在付款方式里。商户 App 目前也看不到这些渠道，要显示「待开通」需改 App 与收款方式接口（TDD）。
- UME 店员账号按用户要求暂不同步。

## 门店英文代码（用户 2026-11-19：代码用英文，先完成 roger、oscar）

运营接口 `PUT /ops/stores/{storeNo}/basics` 写入 `slug`，店主可改：`ume-breakfast`（已有）、`ume-cafe`、`chuangke-pay`（創科系統收款帳號）、`jeff-first-home`、`ume-plan-pay`（ume 套餐收款店）、`chuangke-sam`、`chuangke-oscar`。roger 的 5 家店还没有整店收款码，代码要等开码后才挂到支付链接。

## ume咖啡廳 门店同步重放（用户 2026-11-19 同意改库）

`sys_outbox` 18911（`MERCHANT_STORE_CHANGED`，04:25:56）投递 `sys_outbox_delivery` 846 以 `OUTCOME_UNKNOWN / IllegalStateException` 失败（同时刻 near-pay 有一次数据库死锁），按门店顺序投递阻塞了后续 3 条。按 `JdbcOutboxStore` 的 replay 逻辑手工执行：投递改回 PENDING（`replay_count` 1）、补 `sys_outbox_operation` 审计 `oscar-align-20261119-replay-846`、消息改回 PENDING。原值备份在会话临时目录。之后 4 条全部 SENT，`/s/ume-cafe` 打开 ume咖啡廳 收银台。

## 门店代码与商户渠道展示上线（2026-11-19，用户「推送并部署」）

- near-pay `develop/s1` `525207556`：渠道 `PENDING` 状态与运营接口、主体渠道账户内部接口、ops-web 待开通操作、V112。覆盖部署 `dbc3a6b2da0a → 75b5a2272e78`（备份 `pay-svc.jar.before-entity-channels-20261119-525207556`）。重启时另一会话 14:03 改的 `cloud.properties`（监听 0.0.0.0、内部来源加 .80）一并生效。
- V112：DDL 早前已由 root 执行；本次补登记 `flyway_schema_history` rank 66（checksum −1256008217，用 V111 验证过算法）。
- 7 个 UME 渠道改为 `PENDING`（运营接口）。
- ai-shop 覆盖部署 `d0ade3d80fc0 → a43238a6ecc1`（备份 `shop-app.jar.before-entity-channels-20261119-1c476d3ef`），`ai-shop-api`、`ai-shop-ops` 已重启。App 走 `tw.neargo.ai` → 166 本机 18082，即这台。
- 未完成：`/biz/merchant/payment` 的端到端验收需店主登录（App 会话）；App 显示改动见 [app-entity-channels-2026-11-19](app-entity-channels-2026-11-19.md)。
