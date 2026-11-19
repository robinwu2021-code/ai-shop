# UME166 发布与联调 · 会话交接（2026-11-17）

范围：2026-11-16 至 11-17「优化」会话在 ai-shop、near-pay、UME166 上做过的事、现状和未完成项。
发布细节（jar 哈希、回退路径）在同目录 [`release-166-20261116.json`](release-166-20261116.json) 的各节，本文不重复。

---

## 一、现状（2026-11-17 07:00 UTC）

| 项 | 现状 |
|---|---|
| ai-shop 发布线 | `release/166-live-20261117`（本文提交时为 9b45e10f9 之后），线上 jar 由它重建 |
| near-pay 发布线 | `release/166-live-20261117` 59f130e0，对应线上 pay 4ae18f18（Oscar ADR-046） |
| 支付结果投递 | **已切到 PRODUCTION**（05:12 UTC），见第三节；**尚未用一笔成功付款验证** |
| 实体投影 | 进程内定时任务已上线（另一会话，`entityProjectionRelease`），个人实体 ID_CARD 投影已核对 |
| near-pay 配置归属 | Oscar 会话（ADR-046 之后）。重启 nearpay-prod 前必须先通知它并等回复：ume-breakfast 是线上门店，重启约 40 秒 502 |

## 二、已发布到 166（按时间）

均已记录在 `release-166-20261116.json`：

1. 证件识别（`recognitionRelease`）。
2. near-pay 文件内容接口 + 运营端资料展示与自动识别（`nearPayEvidenceRelease`）。
3. TW_ENTITY_2 与字段级错误透传（`entityTw2Release`）。
4. near-pay V46：联系人、渠道补充资料、修订撤回、缩略图，含迁移 V104（`entityV46PayRelease`）。
5. 发布线整合：线上 jar 改由两条 `release/166-live-20261117` 重建（`consolidation20261117`）。
6. 个人（自然人）实体（`naturalPersonRelease`）。
7. 滑块验证码规则（`captchaRulesRelease`）；`SHOP_AUTH_CAPTCHA_ENABLED` 在 166 上仍关闭。
8. 支付结果投递切到 PRODUCTION（`productionDelivery20261117`，第三节）。

## 三、支付结果投递切到 PRODUCTION

**原因**：ADR-046 之后 near-pay 全部按 PRODUCTION 执行（`shop.pay.environment.default=PRODUCTION`、worker 与绑定都是 PRODUCTION），但收款结果只记录、投递 SANDBOX，ai-shop 与 App 收不到任何收款结果。

**改动（只改配置，无新 jar）**：

| 位置 | 改动 |
|---|---|
| `/data0/app/ai-shop/payment-sse.private.properties`（ai-shop-api） | 新增 `shop.payment-collections.environments=SANDBOX,PRODUCTION`、`secrets.PRODUCTION`；SANDBOX 沿用原 `secret`（source-bindings 校验仍要求 SANDBOX） |
| `/data0/app/nearpay-prod/payment-sse.private.properties` | `merchant-collections.environment=PRODUCTION`，新增 `environments=PRODUCTION`、`secrets.PRODUCTION`；`delivery-targets[SANDBOX][0]` 改名为 `[PRODUCTION][0]`（同一 18082 地址；两环境目标不得重叠） |

- PRODUCTION 签名密钥在服务器上生成，两边共用，仅 root 可读：`/home/ume/prod-delivery-20261117/production.key`。改前文件也在该目录（`*.before`）。
- 回退：把 `*.before` 原地写回（ai-shop 是单文件挂载，必须保留 inode），重启对应容器。
- 顺序按 ADR-059：先 ai-shop-api（05:10），后 nearpay-prod（05:12）。两边健康、启动日志无签名或投递错误。

**更新（08:05 UTC）**：已跑通。切换后暴露一个故障：PRODUCTION 结果记录器在结算事务里调用 166 上不存在的门店来源接口，异常回滚了结算，05:12–07:59 的已付订单都卡在待付款。near-pay 1edc910e 改为查不到门店时发原始快照（已部署 61a6fb2e、合入发布线 60dbdbc9），ai-shop 补了门店 2070、2074 的 PRODUCTION 来源绑定。PYO261117144731387 已成功并投递，ai-shop 回执 PCR261117CFS1VKAEX8。App 播报未由本会话确认。详见发布记录 `collectionStoreFix20261117`。

原记录：还没有一笔 CHECKOUT_QR 订单付款成功，所以「near-pay 推送 → ai-shop 收据 → SSE → App 播报」还没跑通。待验证订单：

| 订单 | 门店 | 渠道 | 状态 |
|---|---|---|---|
| ume早餐店 NT$1（07:0x UTC 建） | 2070 | 蓝新沙箱信用卡（ccore） | 停在蓝新付款页，等用户填测试卡 |
| 99 / 100 | 2074 | LINEPAY_TEST | 停在 LINE 登录 |
| 98 | 2074 | SANDBOX_H5（非 CHECKOUT_QR，按设计不投递） | 可忽略 |

蓝新测试卡见 near-pay `docs/渠道开发文档/蓝新/参考资料/藍新MPG技術串接手冊_1.1.1-6d410751bb.md` 第三章：只接受 4000-2211-1111-1111，有效期和末三码随意填写。卡号需由用户本人填写。

付成功后核对：`nearpay_prod.pay_order` 变 SUCCEEDED → `pay_biz_notify` 投递成功 → `ai_shop_ume.payment_collection_receipt` 有记录 → App 播报。

## 四、收款测试入口（Oscar 会话配置）

| 入口 | 可选方式 | 说明 |
|---|---|---|
| `https://twpay.umeshop.ai/h5/s/ume-breakfast`（= `/h5/q/nAjNe7H2GpWc1mEQu1hESw`） | Apple Pay、LINE Pay（正式）、信用卡（测试） | 线上门店，Apple Pay 与 LINE Pay 是真钱 |
| `https://twpay.umeshop.ai/h5/q/Maswh_NWjnpkfp1MPfLfGw` | LINE Pay（测试）、信用卡（测试） | 测试门店 ST261117JC0WT5KSDV，李昱達名下 |

- 沙箱账户已从 PM082A 转给李昱達（PM2026111707510001）：LINEPAY_TEST PM7C90169391874975、NEWEBPAY_TEST PM5731C712FF9145B8。PM082A 现在没有沙箱账户。
- 试运行资格有效至 2026-11-18 约 06:00 UTC，过期后测试方式会消失。
- 现行 H5 只在 Android 或 LINE 内显示 LINE Pay；桌面 Chrome 测 LINE Pay 需把 UA 改成 Android + `Line/`。
- 运营端 QR 接口返回的链接缺 `/h5`（`/q/...` 打开旧版先输金额页），未修。

## 五、H5 改动（未部署，交给 Oscar 会话）

near-pay 分支 `feat/h5-all-methods-20261117`（5dc84dd3，已推送；Oscar 以 830a6131 并入，07:15 UTC 随 method-options 发布上线）：

- 显示服务端授权的全部方式，Apple Pay 只在设备可用时显示；设备只决定顺序（LINE 内或 Android：LINE Pay 首位；Apple Pay 可用：Apple Pay 首位）。
- LINE 内只在 LINE Pay 是唯一方式时隐藏选择框。
- 备注去掉「例如：少冰、桌号」示例（7 种语言）。
- 281 项 vitest 通过；首屏 101.0/102 KB gzip。

Oscar 会话会以此为基础，加上方式区块对齐修复，以及「同一方式的正式与沙箱分开显示、各走各的渠道」（后端 + H5），再一起部署。**不要单独部署 5dc84dd3。**

## 六、测试数据

| 数据 | 处理 |
|---|---|
| 个人实体 M261117Q484GXZQ06（oscar，王測試） | near-pay 核准 PM2026111713380001-R1（APA3E3A87DA10B4674，未送蓝新）；ID_CARD 投影核对通过后，ai-shop 软删除店主行 2076、实体、ID_CARD 资格 11，投影 BLOCKED |
| 前两个个人实体 M2611171209QTX20X、M2611173EP67AQ7RC | 同样已软删除；near-pay 商户 PM2026111710400001、PM2026111712180001 保留 |
| oscar 名下现存实体 | 李昱達、NearGo科技測試主體-OSCAR-1115、NearGo UI TEST 1116 |

## 七、待决事项（需用户决定）

1. 个人实体 `biz_qualification` 用 UNREGISTERED（现行）还是 EXEMPT。
2. 是否在 near-pay 停用测试商户 PM2026111710400001、PM2026111712180001、PM2026111713380001。
3. 是否打开 `SHOP_AUTH_CAPTCHA_ENABLED`。
4. 蓝新正式 MID 给李昱達，还是长期共用沙箱 MID MS33843411（TDD Q1 原定 PM082A 保留，今天已按用户指示转走）。

## 八、进件会话提出的后端问题（未处理）

来源：ai-shop-bapp `feat/core-flow-ui` `docs/review/oscar-app-check-2026-11-17.md`（d93f7f7）。

1. App 门店渠道页读 `GET /biz/merchant/payment?entityNo=M261113Y3QW5XKEFW`，只有旧 WECHAT 记录；near-pay 侧导入的蓝新 TRL00001898、LINE Pay 1626862643（渠道账户转移 + stores/{store}/bindings）没有反映到该接口。
2. 「開通與渠道管理」提示无进件查询权限：PM2026111707510001 由运营代建，oscar 没有归属映射，但首页显示「可收款」。
3. 收款码正式入口：App 立牌用 `/h5/s/ume-breakfast`，运营静态码是 `/q/nAjNe7H2GpWc1mEQu1hESw`（应为 `/h5/q/...`）。
4. 收款记录里的 NEWEBPAY 订单「待確認」：都没付款，状态正确。
5. 数据：门店地址缺县市和区；主体行业 RETAIL 与餐饮门店不一致；测试门店 ST261117JC0WT5KSDV 是故意建的。

## 九、协作约定

- 别的会话转达的「用户同意」不能代替本会话用户的确认；核准、清理、部署都在本会话问过用户再做。
- 重启 nearpay-prod 或改 near-pay 配置前通知 Oscar 会话，并核对文件 mtime，防止覆盖它的改动。
- db61 MySQL 内存 2G，查询保持小；root 通过 mysql9 容器，凭据不打印。
- 部署脚本带 fcntl/flock 锁、sha256 前置校验、原地写入保 inode、健康检查失败自动回退。
