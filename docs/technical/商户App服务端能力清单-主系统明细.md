# 商户 App × ai-shop 后端接口盘点（不含 7 收款 / 8 资金 / 9 感应收款 / 13 进销存）

- 对照基准：工作区 `.claude/worktrees/bapp-handoff-tdd`，分支 `codex/bapp-handoff-tdd` @ `891008be6`（已部署 166）
- 交接文档：`ai-shop-bapp/docs/implementation/功能矩阵与API交接文档.md`
- 只读盘点，2026-11-08。

## 判定口径

- **A 已可用**：路径存在、新模型门店能进（登记在 `ShopStoreMemberRoutes` 或 `CommerceRoutePolicy` 的 ACCOUNT / foundation / mall 通道）、字段对得上。
- **B 有缺口**：路径存在，但新模型门店会 70006 / 字段缺或不一致 / 校验不对 / 路径与 App 不同但后端有等价能力（App 要改走或后端出一层门面）。
- **C 后端没有**。
- 「已完成」：任务说明里列的已做项（第二批门店路由、display-name / push-token / payout-account 登记、附录 A 字段对齐、`/biz/stores/{s}/fulfillment`），本文直接标 A 并注「已完成」。
- 「已冻结 TDD」：`docs/technical/TDD-商户App{统一登录与账号,商户资料提交,资金,外部记账,问题回报与版本,快递公司}.md`，状态都是「已确认、未实现」。

**70006 判定依据**（读过代码确认）：`BizContextFilter`（`backend/shop-base-auth/.../auth/BizContextFilter.java:69`）对所有 `/biz/**` 调 `ShopCommerceEndContextAdapter#select`（`backend/shop-app/.../commercebridge/ShopCommerceEndContextAdapter.java:96`）。路由没命中 ACCOUNT / foundation / host 登记时落 `Lane.DENY`，新模型（STORE_V1）门店一律 `FORBIDDEN` → `70006` + `nextAction=CONTACT_ADMIN`。**`/biz/x/food/**`、`/biz/x/beauty/**`、`/biz/x/commerce/**` 也走这条过滤器，同样没登记**。

**路径缩写**

| 缩写 | 实际目录 |
|---|---|
| `[app]` | `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/` |
| `[core]` | `backend/shop-core/src/main/java/ai/neargo/shop/` |
| `[mch]` | `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/` |
| `[cmr]` | `backend/commerce/commerce-spring-boot-starter/src/main/java/ai/neargo/commerce/starter/` |
| `[food]` | `backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/` |
| `[bty]` | `backend-plugins/shop-industry-beauty/beauty-web/src/main/java/ai/neargo/shop/beauty/api/biz/` |
| `[policy]` | `[cmr]security/CommerceRoutePolicy.java` |
| `[routes]` | `backend/shop-app/src/main/java/ai/neargo/shop/commercebridge/ShopStoreMemberRoutes.java` |
| `[mig]` | `backend/shop-app/src/main/resources/db/migration/`（当前最新 V636） |

---

## 1 账号与登录 · 已可用 6 / 有缺口 0 / 缺失 3

| 接口 | 分类 | 缺口 | 缺的表/字段 | 要写的代码 | 依赖/待拍板 |
|---|---|---|---|---|---|
| `POST /biz/auth/otp/send` | A | —（`[app]BizAuthController.java`；`[policy]` ACCOUNT） | — | — | 10450/10454 等待时间只在 `msg` 里，结构化 `retryAfterSeconds` 在统一登录 TDD D9 列为「单独排期」 |
| `POST /biz/auth/email-otp/send` | A | 同上 | — | — | 同上 |
| `POST /biz/auth/login` | A | 同上 | — | — | 同上 |
| `POST /biz/auth/staff-login` | A | 同上。注意员工号码只认 11 位，见 16 模块 | — | — | 统一登录上线后废弃 |
| `GET /biz/merchant/profile` | A | `[app]BizMerchantController.java`；`[policy]` SELECTION_READS | — | — | — |
| `POST /biz/auth/logout` | A | `[app]BizAuthController.java` | — | — | — |
| `POST /biz/auth/identities` | C | 没有 | 新表 `usr_login_ticket`（TDD 已定） | 按已冻结 TDD 实现：`BizAuthController` 新增 3 个端点 + 票据服务；`[policy]` ACCOUNT 集合加 3 条 | **已冻结 TDD**：统一登录与账号（ADR-053/054） |
| `POST /biz/auth/identities/enter` | C | 同上 | 同上 | 同上 | 同上 |
| `GET /biz/auth/identities/mine` | C | 同上 | 同上 | 同上 | 同上 |

横切问题（1-14「三种 70006」）：`AccessDecision.nextActionFor`（`backend/commerce/commerce-api/.../access/AccessDecision.java:47`）把**所有**准入层 `FORBIDDEN` 都映射成 `CONTACT_ADMIN`：路由未登记、门店头与路径门店不符、主体号不一致、**路由自带权限码不足**（如商城采购要 `biz:store:admin`、`pay-setting`）都是同一个 `CONTACT_ADMIN`。只有控制器 `@PreAuthorize` 失败才是「无 nextAction 的 70006」。App 把 `CONTACT_ADMIN` 当「门店没开放这功能」，店长点商城采购会看到错误的说明。**待拍板**：准入层的权限码不足要不要另给 `nextAction`（建议 `NO_PERMISSION`），改 `ShopCommerceEndContextAdapter#strictScope` 一处。

---

## 2 门店与建店 · 已可用 18 / 有缺口 1 / 缺失 0

| 接口 | 分类 | 缺口 | 缺的表/字段 | 要写的代码 | 依赖/待拍板 |
|---|---|---|---|---|---|
| `GET /biz/stores/mine` | A | `[app]BizEntityController.java`；SELECTION_READS | — | — | — |
| `GET /biz/context` | A | `[app]BizPickupController.java`（类级映射）；SELECTION_READS | — | — | — |
| `GET /biz/merchant/store-setup` | A | `[app]BizFoundationController.java`；ACCOUNT | — | — | — |
| `GET /biz/business-types` | A | 同上 | — | — | — |
| `GET /biz/geo/tips` | A | `[app]BizGeoController.java`；`[routes]` 已登记 | — | — | — |
| `GET /biz/geo/reverse` | A | 同上 | — | — | — |
| `POST /biz/merchant/quick-start` | A | `[app]BizMerchantController.java`；ACCOUNT | — | — | — |
| `GET /biz/store` | A（已完成） | `name/slug/status/isDefault/planSuspended` 已加（`[mch]dto/StoreProfileVO.java:42`） | — | — | — |
| `POST /biz/store` | A（已完成） | 同上；多选配送方式改走 `/biz/stores/{s}/fulfillment` | — | — | — |
| `GET/PUT /biz/stores/{s}/fulfillment` | A（已完成） | `[app]BizStoreFulfillmentController.java`；`[routes]` 已登记 | — | — | — |
| `GET /biz/stores/{s}/fulfillment/{channel}/impact` | B | 刻意没登记（走 LEGACY_PREVIEW，`[routes]` 注释），新模型门店关某一路前读不到影响清单 | — | 按字段对齐 TDD §6 另行开放 | TDD-商户App接口字段对齐 §6 待决 |
| `POST /biz/store/{s}/rename` | A | `[app]BizMerchantController.java`；`[routes]` 已登记 | — | — | — |
| `POST /biz/store/announcement` | A | 同上 | — | — | — |
| `POST /biz/store/announcement/recent/remove` | A | 同上 | — | — | — |
| `POST /biz/store/scope-preview` | A（已完成） | `geometry` 已纳入预估（字段对齐 TDD A2） | — | — | — |
| `POST /biz/store/{s}/status` | A | `[app]BizMerchantController.java`；`[routes]` 已登记 | — | — | — |
| `POST /biz/store/{s}/slug` | A | 同上 | — | — | — |
| `GET /common/master-data` | A | `[core]platform/api/common/CommonMetaController.java`（非 `/biz`，不过准入） | — | — | 166 曾超时，属性能问题 |
| `GET /biz/communities` | A | `[app]BizAuthController.java`；ACCOUNT | — | — | — |

---

## 3 方案、额度与商城 · 已可用 14 / 有缺口 1 / 缺失 2

| 接口 | 分类 | 缺口 | 缺的表/字段 | 要写的代码 | 依赖/待拍板 |
|---|---|---|---|---|---|
| `GET /biz/stores/{s}/subscription` | A | `[app]BizFoundationController.java`；`[policy]` 门店 GET 通道。到期日读 `planEndsAt`（App 已改） | — | — | — |
| `GET /biz/stores/{s}/context` | A | 同上 | — | — | — |
| `GET /biz/business-types/{code}/plans` | A | 同上；ACCOUNT 正则 | — | — | — |
| `GET /biz/stores/{s}/mall/products` | B | 组件种类后端只有 `PLAN / END_PACKAGE / HARDWARE`（`backend/commerce/commerce-api/.../common/ComponentKind.java:4`），App 按 `PLAN/HARDWARE/USAGE` 解析，**没有 USAGE，多出 END_PACKAGE** | — | App 认 `END_PACKAGE`；若要卖用量包见 3-13 | 用量包是否在首期商城上架 |
| `POST …/mall/offline/quotes` | A | `[cmr]http/CommerceOfflineBuyerHttpController.java`；`[policy]` mall 通道（需 `biz:store:admin`） | — | — | — |
| `POST …/mall/orders` | A | `[cmr]http/CommerceBuyerMallHttpController.java` | — | — | — |
| `GET …/mall/orders` | A | 同上 | — | — | — |
| `GET …/mall/orders/{o}` | A | 同上 | — | — | — |
| `POST …/mall/offline/orders/{o}/instructions` | A | `[cmr]http/CommerceOfflineBuyerHttpController.java` | — | — | — |
| `GET …/mall/offline/orders/{o}/instructions` | A | 同上 | — | — | — |
| `POST …/offline/orders/{o}/evidence` | A | 同上 | — | — | — |
| `POST …/offline/orders/{o}/evidence/content` | A | 同上 | — | — | — |
| `POST …/offline/orders/{o}/claims` | A | 同上 | — | — | — |
| `GET …/offline/orders/{o}/claims` | A | 同上 | — | — | — |
| `POST …/mall/orders/{o}/component-refunds` | C | 这条路径没有，`[policy]` mall 分支落 DENY。**但后端已有** `POST …/orders/{o}/refund-previews` 与 `…/refunds`，请求体带 `sourcePeriodNos`（按周期/组件选退，`CommerceBuyerMallHttpController.java:157,177`），金额由平台算 | — | 推荐**不新建**：App 改走 refund-previews → refunds；`componentNo → sourcePeriodNo` 的映射需在订单详情 `quote.components` 里回给 App（改 `MallOrderDetail` 视图加 `sourcePeriodNo`） | 待拍板：线下转账付款的订单能否走 `/refunds`（现金退款路径归支付系统 / 商城运营审核）；交接文档 O04 二选一 |
| `GET /biz/stores/{s}/usage` | C | 没有用量账户；`ComponentKind` 无 USAGE；全仓没有 AI token / 短信额度余额表 | 新表 `commerce_usage_account`（店 × 种类余额、到期）、`commerce_usage_entry`（流水：来源订单/消耗/过期）；`ComponentKind` 加 `USAGE`；SKU 组件加单位数 | commerce-api `UsageQueryFacade` + commerce-core 记账服务 + `[cmr]http/` 新 `CommerceUsageHttpController`；`[policy]` 门店 GET 白名单加 `usage`；消耗方（短信发送、AI 调用）接入扣减 | 无现成 TDD；**先拍板**：用量是否按店计、短信/AI 现在谁计费（目前无扣量点） |
| `GET /biz/plan` | A（已完成） | `[mch]api/biz/BizPlanController.java`；`suspendedStoreDetails`、`graceUntil` 已加；`[routes]` 已登记 | — | — | — |

（`POST /biz/plan/trial` 后端有、已登记，App 未接。）

---

## 4 商户与进件 · 已可用 9 / 有缺口 0 / 缺失 3

| 接口 | 分类 | 缺口 | 缺的表/字段 | 要写的代码 | 依赖/待拍板 |
|---|---|---|---|---|---|
| `GET /biz/entities` | A | `[app]BizEntityController.java`；`[routes]` 已登记 | — | — | — |
| `GET /biz/entity/{entityNo}` | A | 同上 | — | — | N4 审核字段补充在商户资料提交 TDD 内 |
| 收款渠道与开通：`GET/POST /biz/merchant/payment`、`GET /biz/merchant/pay-channel`、`POST /biz/merchant/payment/store/{s}`、`POST …/{payChannel}/refresh`、`POST /biz/store/{s}/payment`、`GET /biz/merchant/pay-service`、`POST …/pay-service/apply`、`signRequired/signUrl`、JKOPAY | — | **归支付系统**（路径都在：`[app]BizMerchantController.java`、`[app]BizPayServiceController.java`，`[policy]` foundation 通道；签约字段与 JKOPAY 后端没有） | — | — | — |
| `POST /biz/store/{s}/entity`（+ 预检 `GET …/entity/relink-check`） | C | 没有 | TDD 定：ai-shop 不加表 | 按 TDD：`BizMerchantController` 加端点，门店改挂限占位认领；`[routes]` 登记 `STORE + "entity(?:/relink-check)?"` | **已冻结 TDD**：商户资料提交（ADR-055）；牵动 near-pay 门店投影 |
| `POST /biz/merchant/onboarding` | C | 没有 | 不加表 | 按 TDD 实现（复用 apply / payment 写入） | **已冻结 TDD**：商户资料提交；新旧两套进件以哪套为准已在 TDD 拍板 |
| `POST /biz/merchant/payment/bind` | C | 没有；归支付系统（near-pay `channel-account-claims`） | — | — | **已冻结 TDD**：商户资料提交（ADR-056） |
| `GET /biz/merchant/apply` | A | `[app]BizMerchantController.java`；ACCOUNT | — | — | — |
| `POST /biz/merchant/apply` | A | 同上；foundation | — | — | — |
| `POST /biz/merchant/agreement/accept` | A | 同上 | — | — | — |
| `GET /biz/qualifications` | A | 同上；foundation | — | — | — |
| `POST /biz/qualifications/save` | A | 同上 | — | — | — |
| `POST /biz/qualifications/recognize` | A | `[app]BizCertController.java` | — | — | `validTo` 长期值的字形由识别供应商原样回，未见归一化，联调确认 |
| `POST /biz/upload/image` | A | `backend/shop-channel/.../channel/media/api/BizUploadController.java`；foundation | — | — | — |

---

## 5 我的、消息与通知 · 已可用 9 / 有缺口 2 / 缺失 3

| 接口 | 分类 | 缺口 | 缺的表/字段 | 要写的代码 | 依赖/待拍板 |
|---|---|---|---|---|---|
| `POST /biz/merchant/display-name` | A（已完成） | `[app]BizMerchantController.java`；ACCOUNT | — | — | — |
| `GET /biz/auth/password` | A | `[app]BizAuthController.java`；ACCOUNT | — | — | — |
| `POST /biz/auth/password` | A | 同上 | — | — | — |
| `GET /biz/message` | A | `[core]message/api/biz/BizMessageController.java:42`；`[policy]` 消息通道。无分页参数 | — | （可选）加 `cursor/size` | 消息多了再做 |
| `GET /biz/message/unread-count` | A | 同上 | — | — | — |
| `POST /biz/message/{no}/read` | A | 同上 | — | — | — |
| `POST /biz/message/read-all` | A | 同上 | — | — | — |
| `GET /biz/notify/setting` | B | 路由已登记（`[core]message/api/biz/BizNotifySettingController.java`）。`SettingVO.ownerPhone`（`:88`）对邮箱登录的店主为空，没有 `ownerEmail` | 响应加 `ownerEmail` | `SettingVO` 加字段 | 小改 |
| `PUT /biz/notify/setting` | A | 同上 | — | — | — |
| `PUT /biz/notify/sms-phones` | B | **只认大陆手机**：`[core]message/notify/MerchantNotifyRecipients.java:32` 写死 `^1[3-9]\d{9}$`，台湾 09 开头 / E.164 一律 10400 | — | 改用 `Phones.valid(phone, 门店市场国家码)`（`backend/shop-base/.../common/Phones.java:46` 已有宽判据）；号码规范成 E.164 再存 | 短信通道是否支持 +886 |
| `PUT /biz/notify/email` | A | 同上 | — | — | — |
| 换手机 `POST /biz/auth/phone/change` | C | 没有 | `usr_account_change_log`（TDD 定） | 按 TDD | **已冻结 TDD**：统一登录与账号 |
| 绑邮箱 `POST /biz/auth/email/bind` | C | 没有 | 同上 | 同上 | 同上 |
| 注销 `GET /biz/auth/deregister/precheck`、`POST /biz/auth/deregister` | C | 没有 | 同上 | 同上 | 同上（ADR-054）；头像 TDD 明确不做 |

---

## 6 系统状态 · 已可用 2 / 有缺口 0 / 缺失 3

| 接口 | 分类 | 缺口 | 缺的表/字段 | 要写的代码 | 依赖/待拍板 |
|---|---|---|---|---|---|
| `POST /biz/push-token` | A（已完成） | `[core]message/api/biz/BizPushTokenController.java`；ACCOUNT | — | — | App 未接 |
| `POST /biz/push-token/unregister` | A（已完成） | 同上 | — | — | — |
| `POST /biz/feedback` | C | 没有 | `notify_ticket` 加 9 列 + 索引（TDD 定） | 按 TDD；`[routes]` 或 ACCOUNT 登记（TDD 定为账号范围） | **已冻结 TDD**：问题回报与版本 |
| `GET /biz/feedback/mine` | C | 没有 | 同上 | 同上 | 同上 |
| `GET /app/version` | C | 没有。**TDD 定的路径是 `GET /common/app/version`**，与 App 写的 `/app/version` 不同 | 新表 `sys_app_version` | 按 TDD | App 要改路径；「旧版本写入被拒」错误码在 TDD 内 |

---

## 10 列印 · 已可用 9 / 有缺口 3 / 缺失 0

路由整组已登记（`[routes]` `"/biz/print/.+"`）；附录 A 的 App 侧解析 11/08 已改。

| 接口 | 分类 | 缺口 | 缺的表/字段 | 要写的代码 | 依赖/待拍板 |
|---|---|---|---|---|---|
| `GET /biz/print/printers` | A | `[app]BizPrintDeviceController.java`（分页，App 已拆 `records`） | — | — | — |
| `POST /biz/print/printers` | B | 接口在；**飞鹅凭据加密要配置**：`AesGcmPrintCredentialCipher` 读 `shop.print.credentials.key`（`backend/print/print-channel/.../credential/AesGcmPrintCredentialCipher.java:25`），166 没配就存不了 user/ukey | — | 无代码；166 配 `shop.print.credentials.key`（+`key-version`） | 运维配密钥 |
| `PUT /biz/print/printers/{p}` | A | 同上 | — | — | — |
| `POST /biz/print/printers/{p}/status-changes` | A | 同上（App 已改用它暂停） | — | — | — |
| `POST /biz/print/printers/{p}/test-jobs` | B | 接口在；飞鹅区域是**全局单值** `shop.print.feie.region`，默认 `CN`（`api.feieyun.cn`），另一个值 `OVERSEAS`（`api.de.feieyun.com`）（`…/feie/DefaultFeieHttpClient.java:28`）；另有 `shop.print.feie.native-profile-verified` 默认 false | — | 166 配置；若大陆、台湾店共用一个部署要用不同的端点，就得改成按门店市场选择 | **待拍板**：台湾飞鹅机走哪个 API 域名 |
| `GET /biz/print/health` | A | `[app]BizPrintMonitorController.java`（App 用列表自算异常） | — | — | — |
| `GET /biz/print/jobs` | A | `[app]BizPrintJobQueryController.java` | — | — | — |
| `POST /biz/print/jobs/{j}/result-queries` | A | `[app]BizPrintJobCommandController.java` | — | — | — |
| `POST /biz/print/jobs/{j}/manual-confirmations` | A | 同上 | — | — | — |
| `POST /biz/print/jobs/{j}/reprints` | A | 同上（`targetPrinterNo/expectedRevision/reason`） | — | — | — |
| `GET /biz/print/scene-policies` | A | `[app]BizPrintSceneController.java` | — | — | — |
| `PUT /biz/print/scene-policies/{scene}` | B | 打印机与**份数**不在场景策略里：份数只在路由规则 `RouteTarget.copies`（`backend/print/print-api/.../dto/PrintConfigDtos.java:51`），App 只写了 `fallbackPrinterNo`，份数丢 | — | 两选一：App 走 `POST /biz/print/routes` → `…/versions` → `route-versions/{v}/publish`（三步）；或后端出 App 门面 `PUT /biz/print/scene-policies/{scene}/simple {enabled, printerNo, copies, expectedRevision}`，在 `BizPrintSceneController` 内部建/发布单目标路由 | **待拍板**：App 直接走路由三步，还是后端出门面（建议门面，App 侧更省事、语义更安全） |

---

## 11 商品 · 已可用 30 / 有缺口 1 / 缺失 1

整组已登记（`[routes]` 商品、规格、sku-identity、`STORE+categories`、`slug`）。出处：`[core]product/api/biz/BizGoodsController.java`（商品、规格、类目树、`spu-std`）、`[core]product/api/biz/BizSkuIdentityController.java`、`[app]BizMerchantController.java`（货架分类、slug）。

| 接口 | 分类 | 缺口 | 缺的表/字段 | 要写的代码 | 依赖/待拍板 |
|---|---|---|---|---|---|
| `GET /biz/goods`、`GET /biz/goods/inv-mode`、`GET /biz/goods/{g}/draft`、`POST /biz/goods/save`、`…/submit`、`…/toggle`、`…/store-stock`、`…/store-price`、`GET/PUT …/pay-mode`、`POST /biz/goods/parse-text`、`recognize`、`describe`、`GET …/publish-preview`、`POST …/publish`、`…/draft/discard`、`GET …/revisions`、`…/revisions/{r}`、`POST …/fork`（共 19 条） | A | — | — | — | — |
| `GET /biz/goods/{g}` | B | `GoodsVO` 没有送审时间（`[core]product/dto/GoodsVO.java`，只有 `auditReason`），G02 显示不了 | 响应加 `submittedAt`（取最近一次送审的版本时间，商品版本表已有时间，不用加列） | `GoodsVO` 组装处加字段 | 清单 2-9 |
| `GET/POST /biz/store/{s}/categories`、`GET /biz/category/tree`、`GET /biz/my-spec-dims`、`POST /biz/spec-dims`、`POST /biz/spec-values`、`POST /biz/my-spec-dims/{d}/rename\|archive`、`GET /biz/sku-identity/export`、`POST /biz/sku-identity/barcode`、`POST /biz/store/{s}/slug`（共 11 条） | A | — | — | — | — |
| 按状态计数（建议 `GET /biz/goods/counts`） | C | 现在要发 5 个 `size=1` 请求 | — | `BizGoodsController` 加 GROUP BY 状态的计数；已在 `/biz/goods(?:/.*)?` 登记范围内 | 可选，P2 |

另：G10 按条码查标准品，`GET /biz/spu-std` 只有 `keyword/categoryNo`（`BizGoodsController.java:363`），是否按条码匹配待确认。

---

## 12 订单与履约 · 已可用 30 / 有缺口 4 / 缺失 1

整组已登记（`/biz/order`、`/biz/after-sale`、`/biz/pickup`、`/biz/pickup-points`、`STORE+ship-setting|freight-template`）。出处：`[core]trade/api/biz/BizOrderController.java`、`[core]trade/api/biz/BizAfterSaleController.java`、`[core]trade/api/biz/BizExpressController.java`（运费模板）、`[mch]api/biz/BizStoreShipController.java`、`[app]BizPickupController.java`、`[app]BizPickupPointController.java`。

| 接口 | 分类 | 缺口 | 缺的表/字段 | 要写的代码 | 依赖/待拍板 |
|---|---|---|---|---|---|
| `GET /biz/order`、`GET …/{o}/full`、`POST …/prepare`、`…/ship/precheck`、`…/ship`、`GET …/express/quotes`、`POST/GET …/express`、`POST …/express/cancel`、`…/delivered`、`…/confirm-offline-pay`、`GET …/fulfillment-options`、`POST …/fulfillment-change`、`GET …/trace`、`POST …/waybill`、`GET …/logistics-updates`（共 16 条） | A | — | — | — | `confirm-offline-pay` 的记账口径与外部记账 TDD 有关（归支付系统盘点） |
| `GET /biz/order/worklist` | B | `WorkItem(order, actionKind, priority, afterSaleNo, afterSaleStatus)`（`[core]trade/service/MerchantOrderService.java:83`）没有截止时间 | 不加列：按动作推算（待付款截止、售后处理时限、自提逾期） | `WorkItem` 加 `dueAt`，在 worklist 组装处算 | 清单 2-9；各动作的时限口径 |
| `GET /biz/after-sale/worklist`、`GET /biz/after-sale/{a}`、`POST …/reject`、`…/receive`、`…/confirm-refund`（共 5 条） | A | — | — | — | 退款执行归支付系统 |
| `POST /biz/after-sale/{a}/approve` | B | `ApproveReq(remark, refundMinor, refundMode)`（`BizAfterSaleController.java:163`）支持部分退款，但没有「需寄回 / 仅退款」选择 | 售后单可能要加 `return_required` 标志（未查到现有列，实现前确认） | `ApproveReq` 加 `returnRequired`；`AfterSaleService.approve` 分支 | 清单 2-9；退款方式除 CASH 外还有什么（归支付系统） |
| `GET/PUT /biz/store/{s}/ship-setting`、`GET /biz/store/{s}/freight-template`（共 3 条） | A | — | — | — | 默认承运商代码随快递公司 TDD 按市场分表 |
| `GET /biz/pickup/overview` | B | `PickupOverviewVO` 没有逾期数；`arrivedBatches` **恒为 0**（`[core]fulfillment/service/impl/PickupServiceImpl.java:90`） | 不加列（按到货时间算） | VO 加 `overdueCount`，补 `arrivedBatches` 计算 | 清单 2-9；逾期口径 |
| `GET /biz/pickup/orders`、`/picking`、`POST /biz/pickup/arrived`、`/{o}/report`、`/verify/batch`、`GET /verify/search`（共 6 条） | A | — | — | — | 门店不是自提点时 10403，App 已改用 `pickupNos` 判断 |
| `POST /biz/pickup/verify` | B | `VerifyResultVO(success, subOrderNo, reason)`（`[core]fulfillment/dto/VerifyResultVO.java:10`）没有核销时间与经手人 | 核销记录里已有操作人/时间就不用加列（实现前确认） | VO 加 `verifiedAt, operatorName` | 清单 2-9 |
| `GET /biz/express/companies` | C | 没有 | 无（TDD 定） | 按 TDD：`ExpressCompanies` 按市场分表、`LogisticsPort` 加只读方法；`[routes]` 登记 | **已冻结 TDD**：快递公司 |

---

## 14 餐饮 · 已可用 0 / 有缺口 19 / 缺失 2

**关键事实**：后端**有完整的餐饮系统**，在 `backend-plugins/shop-industry-food`（约 60 个 B 端控制器、37 个迁移、`fnb_*` 百余张表），路径前缀是 **`/biz/x/food/*`**，模型是「账单 check / 购物车 cart / 批次 batch / 出品任务 task / 菜单 menu / 菜品 dish（以 goodsNo 为键）/ 售卖限制 sales-restriction」，与 App 自拟的 `/biz/food/*`（单一 DiningOrder 整包）**形状完全不同**。另有三个前提问题：

1. **构建形态**：`backend/shop-app/pom.xml:286` 默认 `food.build.shape=retail`，只有 `-Pfood-embedded` 才挂载餐饮；`scripts/deploy-backend.sh:9` 要显式带 `BUILD_PROFILES=food-embedded,…`，运行时还要 `shop.food.enabled`。**166 是否这样部署过，代码里看不出，需运维确认。**
2. **准入**：`/biz/x/food/**` 不在 `[routes]`，也不匹配 `[policy]` 的门店正则 `/biz/(store|stores)/…`，新模型门店一律 70006。
3. **金额 / 枚举**：交接附录 A 已提，冻结时统一。

| App 接口 | 分类 | 后端等价（出处） | 缺的表/字段 | 要写的代码 | 依赖/待拍板 |
|---|---|---|---|---|---|
| `GET /biz/food/orders` | B | `GET /biz/x/food/stores/{s}/checks`（`[food]BizCheckController.java`） | — | 二选一（见本节末） | — |
| `GET /biz/food/orders/{o}` | B | `GET /biz/x/food/checks/{c}`，加 `…/actions`、`…/courses` | — | 同上 | — |
| `POST …/{o}/dishes`（加菜） | B | `POST /biz/x/food/checks/{c}/cart-sessions` → `PUT /carts/{cart}/lines/{l}` → `POST /stores/{s}/batches`（下单批次） | — | 同上；门面要把「`{菜品 id: 数量}`」翻成购物车 + 批次 | 沽清校验由售卖限制自动做 |
| `POST …/{o}/returns`（退菜，只停做） | B | `POST /biz/x/food/tasks/{t}:void`（`[food]BizKitchenTaskController.java`）；退款型退货另有 `/biz/x/food/returns/*` | — | 门面按 dishId → taskNo 映射 | 「退菜」不退钱的口径与 `:void` 一致，确认 |
| `POST …/{o}/checkout` | B | `POST /biz/x/food/stores/{s}/quotes` → `POST /quotes/{q}:confirm` → `GET /settlements/{n}`（`[food]BizDiningPricingController.java`）；**付款与发票归支付系统** | — | 门面只做试算与结算单，付款走收款模块 | 拆单 = 多条 payments 由支付侧接 |
| `POST …/{o}/accept`（外送接单） | C | 没找到「商家接外送单 + 备餐分钟」的 B 端动作；平台单经 `/biz/x/commerce/*`（`shop-commerce-integration`）与 `/biz/stores/{s}/channel-order-policies`（自动接单策略） | 视方案而定 | 若要手工接单：在 commerce-integration 加接单命令；或 App 改为只读 + 自动接单 | **待拍板**：首期外送是自动接单还是手工接单 |
| `POST …/{o}/reject` | C | 同上 | 同上 | 同上 | 同上 |
| `GET /biz/food/summary` | B | `GET /biz/x/food/operating-reports/realtime`（`[food]BizDiningReportController.java`）+ 桌台 / 任务计数 | — | 门面聚合 | — |
| `GET /biz/food/menu` | B | `GET /biz/x/food/stores/{s}/menus`、`/catalog`、`/dishes`、`/option-groups` | — | 门面平铺成 MenuItem | — |
| `POST /biz/food/menu/items` | B | 菜品以商品为本：`POST /biz/goods/save` 建商品 → `PUT /biz/x/food/stores/{s}/dishes/{goodsNo}`（`[food]BizDishController.java`）设出品站等 → 选项组 `/stores/{s}/option-groups` + `:publish` → 菜单 `PUT /menus/{m}/definition` + `:publish` | — | 门面一次完成（多步、带发布） | 编辑即发布还是先草稿 |
| `POST …/items/{id}/status` | B | 沽清：`POST /biz/x/food/stores/{s}/sales-restrictions`（`fnb_sales_restriction.mode=SOLD_OUT`，`[food]BizSalesRestrictionController.java`）；下架：商品下架或菜单移除 | — | 门面 | — |
| `POST …/items/{id}/remaining` | B | 同表 `mode=QUOTA`（`food-core/.../V1__food_business_tables.sql:1771`） | — | 门面 | 「卖完自动沽清到打烊」后端是否如此，确认 |
| `GET /biz/food/tables` | B | `GET /biz/x/food/stores/{s}/tables`、`/table-areas`（`[food]BizTableController.java`）；桌码链接走 `POST /tables/{t}:share` / `:rotate-token` | — | 门面 | — |
| `POST /biz/food/tables` | B | `POST /biz/x/food/stores/{s}/tables` | — | 门面 | 区域要先建 table-area |
| `POST /biz/food/tables/{t}` | B | `PUT /biz/x/food/tables/{t}` | — | 门面 | — |
| `POST /biz/food/tables/{t}/delete` | B | `POST /biz/x/food/tables/{t}:archive` | — | 门面 | — |
| `GET /biz/food/kitchen` | B | `GET /biz/x/food/stores/{s}/kitchen/tasks`（催菜 `:urge` 有） | — | 门面 | — |
| `POST /biz/food/kitchen/{o}/{d}/start\|done` | B | `POST /biz/x/food/tasks/{t}:start` / `:ready`（另有 `:serve`） | — | 门面 | 「做好」对应 ready 还是 serve |
| `GET /biz/food/platforms` | B | `GET /biz/x/commerce/stores/{s}/connections`、`/statistics/providers` | — | 门面 | 首期是否做外送平台 |
| `POST /biz/food/platforms/{code}` | B | `POST /biz/stores/{s}/channel-order-policies/{key}`（`[app]BizChannelOrderPolicyController.java`）——是否等于「暂停接单」待确认；该路径是 `/biz/stores/` 复数且未在 `[policy]` 开放，对所有账号都会 DENY | — | 门面 + 登记 | 同上 |
| `GET /biz/food/report` | B | `GET /biz/x/food/operating-reports/{daily\|period\|trend\|rankings…}`、`/metrics` | — | 门面 | `turnover/deliveryShare` 显示串由谁拼 |

**二选一（要先拍板）**
- 方案甲（推荐）：在 `food-web` 加 App 门面 `BizFoodAppFacadeController`（`/biz/food/*`，按 App 契约冻结后实现），内部调 food-api 的 Service，不复制领域逻辑；`[routes]` 登记 `/biz/food/.+`。改动集中在后端，App 只删 `pendingContracts` 一行。
- 方案乙：App 直接接 `/biz/x/food/*`，`[routes]` 登记 `/biz/x/food/.+`；App 要重写 `industry_http.dart` 的整套模型，工作量大。
- 无论哪种：166 用 `food-embedded` 构建 + `shop.food.enabled=true`，并确认店的 `businessTypeCode` 能进餐饮。
- 现成文档：`docs/technical/TDD-餐饮系统-三端API与独立服务对齐.md`、`TDD-餐饮功能与API实现对齐评审.md` 等（餐饮系统自身），**没有针对 App 契约的 TDD**，要新写「TDD-商户App餐饮门面」。

---

## 15 服务业 · 已可用 0 / 有缺口 17 / 缺失 6

**关键事实**：美业插件在 `backend-plugins/shop-industry-beauty`（`/biz/x/beauty/*`，`bty_*` 30 张表）。README 写明 BM01–03（政策、项目定义、预约）完成，**BM04–06（履约、营业）未交付，整体 readiness=OUT_OF_SERVICE**。构建同样默认 `retail`（`shop-app/pom.xml:287`），要 `-Pbeauty-embedded`。人员、资源、排班在主应用共用底座：`[mch]api/biz/BizPersonnelController.java`、`BizResourceController.java`、`BizScheduleController.java`（`/biz/personnel`、`/biz/resources`、`/biz/schedules`、`/biz/schedule-rules`），**这 4 组和 `/biz/x/beauty/**` 都没登记**，新模型门店 70006。

| App 接口 | 分类 | 后端等价（出处） | 缺的表/字段 | 要写的代码 | 依赖/待拍板 |
|---|---|---|---|---|---|
| `GET /biz/beauty/bookings?date=` | B | `GET /biz/x/beauty/appointments`（`[bty]BizAppointmentController.java`） | — | 门面（同 14 节甲乙方案） | — |
| `GET /biz/beauty/bookings/{b}` | B | `GET /biz/x/beauty/appointments/{a}` + `work-orders/{w}` | — | 门面 | — |
| `GET …/{b}/staff`（可指派技师） | B | `POST /biz/x/beauty/availability/queries`（`[bty]BizAvailabilityController.java`）+ `GET /biz/personnel` | — | 门面 | — |
| `POST …/{b}/check-in` | B | `POST /biz/x/beauty/arrivals`（`[bty]BizReceptionController.java`）→ `POST /appointments/{a}/work-orders` | — | 门面 | 指派技师在建工单时传 |
| `POST …/{b}/cancel` | B | `POST /appointments/{a}/cancellations` | — | 门面 | — |
| `POST …/{b}/checkout` | B | `GET /work-orders/{w}/bill`（`[bty]BizWorkOrderController.java`）；**扣储值与刷卡/现金归支付系统**（储值在 `/biz/stored-value/*`） | — | 门面只出账单 | BM04–06 未交付，结账链路未通 |
| `POST …/{b}/reschedule` | B | `POST /appointments/{a}/changes/previews` → `…/changes` | — | 门面 | — |
| `POST …/{b}/items`（服务中加项） | B | `POST /work-orders/{w}/service-submissions` 或 `tasks/{t}/preparations`，对应关系待确认 | — | 门面 | 加项属 BM04–06 范围 |
| `GET /biz/beauty/summary` | C | 没有汇总 | — | 门面内聚合预约/工单/账单 | — |
| `GET /biz/beauty/timeline` | C | 没有；可由预约 + `/biz/schedules` 拼 | — | 门面 | — |
| `GET /biz/beauty/availability` | B | `POST /biz/x/beauty/availability/queries` | — | 门面 | — |
| `GET /biz/beauty/customers?phone=` | B | `GET /biz/members?phone=`（`MemberQuery.phone`，`[core]member/dto/MemberVOs.java:25`，`/biz/members` 已登记） | — | App 改走或门面转调 | — |
| `POST /biz/beauty/bookings`（代客，含订金） | B | `POST /biz/x/beauty/appointments`（+`/previews`）、`/walk-ins`、`/guest-registrations` | **没有订金**：`bty_*` 全无 deposit 列 | 订金要加：`bty_service_definition_version` 加 `deposit_minor`、`bty_appointment` 加 `deposit_status`（改已有表） | **待拍板**：订金是否首期、尾款怎么抵（交接已提） |
| `GET /biz/beauty/catalog` | B | `GET /biz/x/beauty/service-definitions`（`[bty]BizServiceDefinitionController.java`）；缓冲 `buffer_before/after_min` 已有（`beauty-core/.../V1__beauty_p1_tables.sql:90`） | — | 门面 | — |
| `POST /biz/beauty/catalog` | B | `POST /biz/x/beauty/service-definitions/versions`（版本化） | 同上：订金列 | 门面 | — |
| `GET /biz/beauty/staff-resources` | B | `GET /biz/personnel`（含技能 `mch_personnel_skill`）+ `GET /biz/resources` | — | 门面 + 登记 | 技师用名字当键要改成 `personnelNo` |
| `POST …/staff-resources/{name}/bookable` | C | 没有「可预约」开关（`mch_personnel` 只有 `ACTIVE/SUSPENDED/LEFT`，`[mig]V592__shared_personnel_foundation.sql`） | `mch_personnel_store` 加 `bookable`（改已有表），或用资源停用代替 | `BizPersonnelController` 加 `POST /biz/personnel/{p}/bookability` | 路径改用编号 |
| `GET /biz/beauty/slots`（停约预览） | C | 美业预约按排班规则算可约，**没有「封锁时段」**；`/biz/stores/{s}/appointment-slots` 是老模型（`[app]BizStoreFulfillmentController.java`），`[policy]` 只给 LEGACY 通道，新模型门店进不去 | 新表 `mch_schedule_exception`（资源 × 时段 × 原因，停约/请假共用，17 模块复用） | 排班服务加例外；可约计算扣除；门面出预览 | **待拍板**：停约用新例外表，还是老 `appointment-slots` 模型扩到新门店 |
| `POST /biz/appointment-slots/{slotNo}/close` | B | 路由在（`[app]BizStoreFulfillmentController.java`），不收请求体；**未登记**，新模型门店 70006；且与美业模型脱节 | — | 若沿用：`[routes]` 登记 + 加 `reason`；建议改为上行的例外接口 | 同上 |
| `GET /biz/beauty/schedule` | B | `GET /biz/schedules`（`[mch]api/biz/BizScheduleController.java:41`，`biz:store:admin`） | — | 门面 + 登记 | 店长也要能看，权限码要放宽 |
| `GET …/schedule/impact` | C | 没有预览端点；`POST /biz/schedule-rules` 存的时候「有未解决影响就拒」（同文件注释） | — | 把影响检查拆成只读预览 `POST /biz/schedule-rules/previews` | — |
| `POST /biz/beauty/schedule` | B | `POST /biz/schedule-rules`（要 `Idempotency-Key`；按周规则，非按日格子） | — | 门面把「员工 × 星期 × 班次」翻成规则 | — |
| `GET /biz/beauty/report` | C | 没有美业报表 | — | 新写报表查询（到店率、爽约、按技师业绩） | 业绩口径 |

结论：要先定「美业首期是否上」——BM04–06 未交付，就算门面写完，结账、加项也跑不通。现成文档：`docs/technical/TDD-美业系统-后端开发计划.md`；**没有 App 契约 TDD**。

---

## 16 员工与权限 · 已可用 14 / 有缺口 2 / 缺失 0

整组已登记（`[routes]` staff / roles / role / management）。出处：`[app]BizStaffController.java`、`[app]BizManagementController.java`、`[app]BizPickupController.java`（`/biz/context`）。

| 接口 | 分类 | 缺口 | 缺的表/字段 | 要写的代码 | 依赖/待拍板 |
|---|---|---|---|---|---|
| `GET /biz/staff` | B | 邮箱登录的店主那一行 `displayName/loginPhone` 为空；`StaffVO`（`[mch]dto/StaffVO.java:19`）没有 `loginEmail` | 响应加 `loginEmail` | `StaffVO` + 组装处 | 统一登录 TDD 未覆盖 |
| `POST /biz/staff` | B | **台湾号码加不进来**：`MerchantStaffServiceImpl#add` 写死 `loginPhone.matches("\\d{11}")`（`[mch]service/impl/MerchantStaffServiceImpl.java:188`），E.164 `+886…`（带 +）和台湾 10 位 09 号都回 10400；另「不填手机也能加」（2-9）未支持 | — | 改用 `Phones.valid(phone, 市场)` 并统一存 E.164；`StaffReq` 允许空手机要配合统一登录的身份模型 | **P0**；员工登录 `staff-login` 也按存的号码查，存法要一致 |
| `POST /biz/staff/{no}/status` | A | — | — | — | — |
| `POST /biz/staff/{no}/store` | A | 自定义角色码已支持（字段对齐 TDD A5） | — | — | — |
| `GET /biz/staff/logs` | A | — | — | — | — |
| `GET /biz/roles` | A | — | — | — | — |
| `GET /biz/role-perms` | A | — | — | — | 退款上限类权限点（2-9）没有 |
| `POST /biz/roles` | A | — | — | — | — |
| `POST /biz/role/{code}` | A | — | — | — | — |
| `POST /biz/role/{code}/delete` | A | — | — | — | — |
| `GET /biz/context` | A | — | — | — | — |
| `GET /biz/management/context` | A | — | — | — | — |
| `POST /biz/management/spaces` | A | — | — | — | — |
| `GET /biz/management/spaces/{m}/resources` | A | — | — | — | — |
| `POST /biz/management/spaces/{m}/grants` | A | — | — | — | — |
| `DELETE /biz/management/spaces/{m}/grants/{g}` | A | — | — | — | — |

---

## 17 考勤与工资 · 已可用 0 / 有缺口 0 / 缺失 22

全仓搜 `attendance / clock / payroll / payslip / leave / biz:hr`：**无任何实现、无迁移、无 TDD**。可复用的只有共用底座：人员 `mch_personnel*`（`[mig]V592`）、资源 `mch_resource`（`V593`）、按周排班规则 `mch_schedule_rule`（`V594`，按「资源 × 星期掩码 × 起止分钟」，**不是按日排班格子，也没有发布状态**）。

| 接口 | 分类 | 缺口 | 缺的表/字段 | 要写的代码 | 依赖/待拍板 |
|---|---|---|---|---|---|
| `GET /biz/hr/shifts` | C | 无 | `hr_shift`（班次模板）、`hr_clock_rule`（宽限、半径、跨日切点，按店） | 新模块 `shop-hr`（或放 shop-merchant 下 `hr` 包）：`BizHrShiftController` + `HrShiftService` | 放哪个模块 |
| `POST /biz/hr/shifts` | C | 无 | 同上 | 同上 | — |
| `GET /biz/hr/roster` | C | 无 | `hr_roster_week`（店 × 周一，`published`、revision）、`hr_roster_cell`（人员 × 日期 × 值 shiftNo/OFF/LEAVE） | `BizHrRosterController` + `HrRosterService`；冲突提示读美业预约（依赖 15） | 与 `mch_schedule_rule` 的关系：发布排班后是否同步成可约规则 |
| `PUT /biz/hr/roster/cell` | C | 无 | 同上 | 同上 | — |
| `POST /biz/hr/roster/copy-last` | C | 无 | 同上 | 同上 | — |
| `POST /biz/hr/roster/publish` | C | 无 | 同上 | 同上 | — |
| `GET /biz/hr/me/shifts` | C | 无 | 读 roster + 打卡 + 假期余额 | `BizHrSelfController` | — |
| `POST /biz/hr/clock` | C | 无 | `hr_clock_record`（人员、店、IN/OUT、服务器时间、坐标、是否在范围） | `HrClockService`（服务器记时、判半径） | 是否需要定位；用门店坐标 + `coord_sys` |
| `POST /biz/hr/leave` | C | 无 | `hr_request`（LEAVE/CLOCK_FIX 共表：类型、日期、理由、状态、审批人、驳回理由）、`hr_leave_balance` | `HrRequestService`；与预约冲突时回 `warning` | 假别与额度按劳基法（特休）谁定 |
| `POST /biz/hr/clock-fix` | C | 无 | 同上 + 每月补卡次数 | 同上 | 次数上限 |
| `GET /biz/hr/approvals` | C | 无 | 同上 | `BizHrApprovalController` | — |
| `POST /biz/hr/approvals/{r}/approve` | C | 无 | 同上 | 同上（自己的不能批） | — |
| `POST /biz/hr/approvals/{r}/reject` | C | 无 | 同上 | 同上 | — |
| `GET /biz/hr/attendance/monthly` | C | 无 | `hr_attendance_month`（店 × 月 × 确认状态）+ 计算视图 | `HrAttendanceService` | 工时、迟到口径 |
| `POST /biz/hr/attendance/monthly/confirm` | C | 无 | 同上 | 同上 | — |
| `GET /biz/hr/pay-plans` | C | 无 | `hr_pay_plan`、`hr_pay_plan_version`（底薪/时薪、服务/商品提成比、生效期） | `BizHrPayController` + `HrPayPlanService` | 提成取订单/美业业绩，依赖 15 |
| `POST /biz/hr/pay-plans/{p}/revise` | C | 无 | 同上 | 同上 | 金额按币种 scale（TWD 0 位） |
| `GET /biz/hr/payroll/{period}` | C | 无 | `hr_payroll_run`（店 × 期、状态、冻结的方案版本）、`hr_payroll_line`（结构化：类型 + 基数 + 比例 + 金额） | `HrPayrollService` | **劳基法计算范围（加班、休息日、特休）先拍板** |
| `POST …/recalculate`、`…/confirm`（交接列一行） | C | 无 | 同上 | 同上 | — |
| `GET /biz/hr/payroll/{period}/staff/{staffNo}` | C | 无 | `hr_payroll_revision`（冻结后修订并入下期） | 同上 | `staffNo` 用 `personnelNo` 还是 `mchAccountNo`（`mch_personnel_account` 可互查） |
| `GET /biz/hr/payslips/mine` | C | 无 | 读 run/line | `BizHrSelfController` | 员工只看本人 |

（recalculate / confirm 两个端点合在一行写，接口按交接文档计 22 条。）另需：权限码 `biz:hr:roster`、`biz:hr:approve`、`biz:hr:pay`（加进 `BizPerms` 与角色模板、i18n）；`[routes]` 登记 `/biz/hr/.+`。**需要先写 TDD（2 档，新域 + 劳动法口径）。**

---

## 18 会员与营销 · 已可用 26 / 有缺口 21 / 缺失 0

### v20 `/biz/ops/*`（App 自拟）——后端没有这条前缀，但**每一条都有等价的已有接口**，全部记 B（路径不同，App 改走已有接口；推荐作废 `/biz/ops/*`）

| App 接口 | 分类 | 改走的已有接口（出处） | 缺的表/字段 | 要写的代码 | 依赖/待拍板 |
|---|---|---|---|---|---|
| `GET /biz/ops/activities` | B | `GET /biz/activities`（`[app]BizActivityController.java:55`，老模型，主体级列表）或 `?model=unified`（`[app]BizVersionedActivityController.java:44`，分页） | — | App 映射 kind：满减→`AMOUNT+CUT`、特价→`PRICE`、买 N 送 M→`QTY+GIFT`、打折→`COMBO` 规则里的 `PERCENT`（`[core]promotion/dto/ActivityVOs.java:68`） | **待拍板**：App 接老模型还是统一模型（`TDD-营销-活动统一模型与集单.md`）；`chainName` 两边都没有 |
| `POST /biz/ops/activities` | B | `POST /biz/activities` | — | App 改请求体（常驻必须有限量或预算、改价/送品必须限量，`[core]promotion/service/impl/ActivityServiceImpl.java:480`） | 同上 |
| `POST …/{no}/publish` | B | 老模型 `PUT /biz/activities/{no}/status`；统一模型 `POST /biz/activities/{no}/publish` | — | App | — |
| `POST …/{no}/pause` | B | `PUT /biz/activities/{no}/status {status: PAUSED}` | — | App | — |
| `POST …/{no}/resume` | B | 同上 `RUNNING` | — | App | — |
| `POST …/{no}/end` | B | 同上 `ENDED` | — | App | — |
| `GET /biz/ops/coupons` | B | `GET /biz/coupons`（`[app]BizCouponController.java:43`） | — | App 映射 `benefitMode` CASH/PERCENT… | — |
| `POST /biz/ops/coupons` | B | `POST /biz/coupons`（`CouponSaveCmd`，`[core]promotion/dto/CouponVOs.java:19`） | — | App | 折扣券必须封顶、发行量必须有数 |
| `POST …/{no}/pause` | B | `PUT /biz/coupons/{no}/status` | — | App | — |
| `POST …/{no}/resume` | B | 同上 | — | App | — |
| `POST …/{no}/send` | B | `POST /biz/coupons/{no}/issue`（即 v24 定向发券） | — | App | — |
| `GET /biz/ops/coupons/lookup?code=` | B | `GET /biz/coupon-redeem/{code}` | — | App | — |
| `POST /biz/ops/coupons/redeem` | B | `POST /biz/coupon-redeem {code}` | — | App | — |
| `GET /biz/ops/stored-value` | B | `/biz/stored-value/plans`、`/accounts`、`/records` 等（`[app]BizStoredValue*Controller.java`）；**整组未登记**，新模型门店 70006 | — | `[routes]` 登记 `/biz/stored-value(?:/.*)?`（及 `/biz/stores/{s}/stored-value/*` 要在 `[policy]` 开放） | 储值实收**归支付系统**；**待拍板**：台湾首期开不开储值 |
| `POST /biz/ops/stored-value/plans` | B | `POST /biz/stored-value/plans`（`[app]BizStoredValuePlanController.java`） | — | 同上 | 同上 |
| `GET /biz/ops/members` | B | `GET /biz/members` + `GET /biz/members/stats`（`[app]BizMemberController.java:78,100`），字段能对上（`MemberVO` 有 `phoneMasked/level/totalSpentMinor/orderCount/daysSinceLast/tagNames`） | — | App | — |
| `POST /biz/ops/members/{m}/tags` | B | `POST /biz/members/tags`（`TagReq(memberNos, add, remove)`）或 `PUT /biz/members/{m}` | — | App | 10 个上限后端有没有，确认 |

### v24（现有路由，已登记）

| 接口 | 分类 | 缺口 | 缺的表/字段 | 要写的代码 | 依赖/待拍板 |
|---|---|---|---|---|---|
| `GET/PUT /biz/member-settings` | A | `[app]BizMemberController.java` | — | — | — |
| `POST /biz/members` | B | **只收大陆手机**：`EnrollReq` 上 `@Pattern(regexp = Phones.CN_MOBILE)`（`BizMemberController.java:461`，`^1[3-9]\d{9}$`），台湾号与 E.164 一律 10400 | — | 去掉注解，服务里按门店市场 `Phones.valid(phone, cc)`，存 E.164 | **P0**（清单 2-3） |
| `GET /biz/member-tags`、`POST /biz/member-tags`、`PUT /biz/member-tags/{t}`、`GET …/usage`、`POST …/merge` | A | 同文件 | — | — | — |
| `GET /biz/member-segments`、`GET …/{s}`、`POST …/{s}/remove` | A | 同文件 | — | — | — |
| `POST /biz/member-segments/preview` | B | 规则里没有 `idleDays`：`MemberQuery` 只有 `lastOrderBefore/lastOrderAfter`（毫秒，`[core]member/dto/MemberVOs.java:27`），App 送的 `idleDays` 会被**静默忽略**，人数算错 | — | 二选一：App 换算成 `lastOrderBefore = now - idleDays`；或 `MemberQuery` 加 `idleDays`（存人群规则时相对天数更合理） | 建议后端加 `idleDays`，否则存下来的人群会随时间漂移 |
| `POST /biz/member-segments` | B | 同上 | 同上 | 同上 | 同上 |
| `POST /biz/members/audience-preview` | A | — | — | — | — |
| `GET /biz/coupons?status=ACTIVE` | B | 后端只认 `includeEnded`（`BizCouponController.java:44`），`status` 被忽略，会把 PAUSED 一起返回 | — | App 自己按 `status` 过滤；或后端加 `status` 参数 | 小改 |
| `POST /biz/coupons/{c}/issue` | A | App 已不送 `audienceDesc` | — | — | — |
| `GET /biz/coupon-issues` | A | — | — | — | — |
| `POST /biz/member-reach/plan`、`/send`、`GET …/task`、`…/task/{t}` | A | `BizMemberController.java` | — | — | — |
| `GET /biz/platform-activity`、`/{a}`、`POST …/enrollment`、`…/withdraw` | A | `[app]BizPlatformActivityController.java` | — | — | 台湾首期开不开（2-8） |
| `GET /biz/points/store-account`、`POST …/store-agreement/sign`、`…/terminate`、`…/store-statement-confirm`、`GET …/store-entries` | A | `[app]pay/BizStorePointsController.java`、`BizStorePointsAgreementController.java`（`store-terms` 也有） | — | — | 积分结算资金归支付系统 |

计数：v20 17 条全 B；v24 30 条，A 26、B 4。

---

## 19 连锁 · 已可用 0 / 有缺口 7 / 缺失 0

后端「品牌 / 经营组织」已实现（`[app]BizBrandController.java`、`[app]BizStoreOperatingContextController.java`，服务在 `[mch]brand/`），语义就是连锁：建品牌、门店改挂品牌（先 check 再提交）、总部裁决、离开（`BrandFinancialChangeServiceImpl.java:40` 有 `LEAVE`）。**问题是全部没登记**：`/biz/management/brands*`、`/biz/management/brand-changes/*` 不在 `[routes]`（那里只登记了 `management/context` 和 `spaces`），`/biz/stores/{s}/brand-changes*`、`operating-context` 落 `[policy]` 的 `/biz/stores/` 分支 → DENY（newModelOnly，**任何账号都进不去**）。另外这些接口都要 `managementNo`（管理空间）、`Idempotency-Key`、`expectedRevision`。

| App 接口 | 分类 | 后端等价（出处） | 缺的表/字段 | 要写的代码 | 依赖/待拍板 |
|---|---|---|---|---|---|
| `GET /biz/ops/chain` | B | `GET /biz/management/brands?managementNo=` + `/brands/{b}/stores` + `GET /biz/stores/{s}/operating-context` + 管理空间 grants | — | App 改走（多请求拼）或门面 `GET /biz/chain/snapshot` | 先有管理空间（`POST /biz/management/spaces`）才能建品牌 |
| `POST /biz/ops/chain` | B | `POST /biz/management/brands {managementNo, name, logo, status}` | — | App / 登记 | — |
| `GET …/chain/links/check` | B | `POST /biz/stores/{s}/brand-changes/check {managementNo, toBrandNo}` | — | 登记（`[policy]` 的 `/biz/stores/` 分支或 `[routes]`） | 未通过项结构由 `impact.blocker` 给 |
| `POST /biz/ops/chain/links` | B | `POST /biz/stores/{s}/brand-changes {…, checkToken, expectedStoreRevision}` | — | 同上 | — |
| `POST …/links/{c}/approve` | B | `POST /biz/management/brand-changes/{c}/decisions {decision, expectedRevision, reason}` | — | 登记 | — |
| `POST …/links/{c}/reject` | B | 同上 | — | 登记 | — |
| `POST …/chain/stores/{s}/leave` | B | `brand-changes` 把 `toBrandNo` 置空（服务只拒 from==to，`[mch]brand/impl/BrandServiceImpl.java:81`）——是否即「退出」，确认 | — | 登记 | 退出时储值余额、连锁活动怎么处理（交接待决） |

**新路由登记**：`[routes]` 加 `GET,POST,PUT /biz/management/(?:brands|brand-changes)(?:/.*)?`；`[policy]` 的 `/biz/stores/` 分支为 `brand-changes`、`brand-changes/check`、`operating-context` 开放（BOOTSTRAP + 路径门店）。现成文档：`docs/technical/TDD-P1-经营组织与品牌管理-详细设计.md`。

---

## 20 团购 · 已可用 11 / 有缺口 4 / 缺失 0

路由全部已登记（`[routes]` groups/group/group-request/quote/period/activities）。出处：`[core]marketing/api/biz/BizCampaignController.java`（groups）、`[app]BizGroupPickupController.java`、`[app]BizPeriodController.java`、`[core]marketing/api/biz/BizQuoteController.java`、`[app]BizActivityController.java`。

| 接口 | 分类 | 缺口 | 缺的表/字段 | 要写的代码 | 依赖/待拍板 |
|---|---|---|---|---|---|
| `GET /biz/activities?triggerType=GROUP\|CUTOFF` | B | 后端**支持** GROUP / CUTOFF 及 `groupHours/cutoffTime/pickupOffset/pickupFrom/minQty/periodQuota`（`[core]promotion/dto/ActivityVOs.java` `ActivityVO`）；但 `triggerType` 参数被忽略（只认 `includeEnded`，`BizActivityController.java:56`）；只有 `goodsNos`，没有 App 要的 `goods[{goodsNo,title,priceMinor}]` | — | App 自己按 `triggerType` 过滤；或控制器加 `triggerType` 过滤 + VO 加 `goods[]`（批量读商品标题与价） | 清单 2-4 |
| `POST /biz/activities`（建拼团 / 集单） | B | **App 现在的请求体会被拒**：GROUP/CUTOFF 要求 `benefitType=PRICE`（`ActivityServiceImpl.java:399–420`），而改价类**必须有 `quota`**（`:487` `ACTIVITY_QUOTA_REQUIRED`）；App 不送 `benefitType`、`quota` | — | App 补 `benefitType: PRICE`、`quota`、`scheduleType/startAt/endAt`；`benefitAmountMinor` 的含义（团价还是减多少）要对齐 | 首期开不开团购（2-4）；TWD scale |
| `GET /biz/groups` | B | `GroupBuyVO`（`[core]marketing/group/dto/GroupVOs.java:30`）有 `activityName`，**没有 `failReason`、`refundedMinor`** | `mkt_group_buy` 没有失败原因列（`[mig]V1__baseline.sql:237`，只有 status）→ 加 `fail_reason`；退款额由成员子单退款汇总，不加列 | VO 加两字段；失败/散团时写原因 | — |
| `GET /biz/group/pickups` | A | — | — | — | — |
| `POST /biz/groups` | A | — | — | — | — |
| `GET /biz/group/{g}` | B | 同 `GET /biz/groups` 的 VO 缺口 | 同上 | 同上 | — |
| `POST /biz/group/{g}/dissolve` | A | — | — | — | 散团原因可顺便写进 `fail_reason` |
| `GET /biz/period`、`/{p}`、`POST …/cutoff`、`…/decision`、`GET …/purchase-lines` | A | `PeriodVO` 字段与 App 一致（`[core]promotion/dto/PeriodVOs.java:18`，含 `decideDeadline`） | — | — | — |
| `GET /biz/group-request/pool`、`POST …/{r}/quote`、`POST /biz/quote/{q}/revise` | A | — | — | — | — |

---

## 21 评价 · 已可用 3 / 有缺口 0 / 缺失 0

| 接口 | 分类 | 缺口 | 缺的表/字段 | 要写的代码 | 依赖/待拍板 |
|---|---|---|---|---|---|
| `GET /biz/review` | A | `[core]product/api/biz/BizReviewController.java`；`[routes]` 已登记。不分页、无筛选 | — | （可选，2-10）加筛选与汇总 | — |
| `POST /biz/review/{r}/reply` | A | 同上 | — | — | — |
| `POST /biz/review/{r}/appeal` | A | 同上 | — | — | — |

---

## 汇总

### 计数

| 模块 | 已可用 A | 有缺口 B | 缺失 C |
|---|---|---|---|
| 1 账号与登录 | 6 | 0 | 3 |
| 2 门店与建店 | 18 | 1 | 0 |
| 3 方案与商城 | 14 | 1 | 2 |
| 4 商户与进件 | 9 | 0 | 3 |
| 5 我的与通知 | 9 | 2 | 3 |
| 6 系统状态 | 2 | 0 | 3 |
| 10 列印 | 9 | 3 | 0 |
| 11 商品 | 30 | 1 | 1 |
| 12 订单与履约 | 30 | 4 | 1 |
| 14 餐饮 | 0 | 19 | 2 |
| 15 服务业 | 0 | 17 | 6 |
| 16 员工与权限 | 14 | 2 | 0 |
| 17 考勤与工资 | 0 | 0 | 22 |
| 18 会员与营销 | 26 | 21 | 0 |
| 19 连锁 | 0 | 7 | 0 |
| 20 团购 | 11 | 4 | 0 |
| 21 评价 | 3 | 0 | 0 |
| **合计** | **181** | **82** | **46** |

（4 模块的收款进件那一行归支付系统，不计入。）

### 新表

| 表 | 模块 | 说明 | 有 TDD？ |
|---|---|---|---|
| `usr_login_ticket`、`usr_account_change_log` | 1、5 | 统一登录票据、换手机 / 绑邮箱 / 注销留痕 | 有（统一登录与账号） |
| `sys_app_version` | 6 | 版本策略 | 有（问题回报与版本） |
| `commerce_usage_account`、`commerce_usage_entry`（commerce 库） | 3 | 用量余额与流水 | 无 |
| `mch_schedule_exception` | 15、17 | 停约 / 请假等时段例外（资源 × 时段 × 原因） | 无 |
| `hr_shift`、`hr_clock_rule`、`hr_roster_week`、`hr_roster_cell`、`hr_clock_record`、`hr_request`、`hr_leave_balance`、`hr_attendance_month`、`hr_pay_plan`、`hr_pay_plan_version`、`hr_payroll_run`、`hr_payroll_line`、`hr_payroll_revision` | 17 | 考勤与工资整域（13 张） | 无 |

（14 餐饮、19 连锁不需要新表：能力和表都已有。注意 166 应用账号没有建表权限，带 DDL 的迁移要 root 先执行。）

### 改已有表

| 表 | 改动 | 模块 |
|---|---|---|
| `notify_ticket` | 加 9 列 + 1 索引 | 6（TDD 已定） |
| `mkt_group_buy` | 加 `fail_reason` | 20 |
| `bty_service_definition_version`、`bty_appointment`（美业插件库） | 加 `deposit_minor` / `deposit_status` | 15（订金若首期要） |
| `mch_personnel_store` | 加 `bookable`（或改用资源停用，不加列） | 15 |
| 售后单表 | 可能加 `return_required`（实现前确认现有列） | 12 |
| commerce SKU 组件 | `ComponentKind` 加 `USAGE`（枚举 + 组件单位数） | 3 |

### 新接口

| 接口 | 模块 | 备注 |
|---|---|---|
| `/biz/auth/identities`、`/enter`、`/mine`；`/biz/auth/phone/change`、`/email/bind`、`/deregister(/precheck)`、`/account(/otp/send)` | 1、5 | TDD 已冻结 |
| `POST /biz/feedback`、`GET /biz/feedback/mine`、`GET /common/app/version`（App 改路径） | 6 | TDD 已冻结 |
| `POST /biz/store/{s}/entity`（+relink-check）、`POST /biz/merchant/onboarding`、`POST /biz/merchant/payment/bind` | 4 | TDD 已冻结；bind 归支付系统 |
| `GET /biz/express/companies` | 12 | TDD 已冻结 |
| `GET /biz/stores/{s}/usage` | 3 | 无 TDD，待拍板 |
| `/biz/food/*` App 门面（约 21 条，转调 `/biz/x/food/*`） | 14 | 方案甲；要新写 TDD |
| `/biz/beauty/*` App 门面（约 22 条，转调 `/biz/x/beauty/*` + personnel/schedules） | 15 | 同上；另需 summary / timeline / report / 例外 / 排班影响预览 / 可预约开关 6 个真正的新能力 |
| `POST /biz/schedule-rules/previews`、`POST /biz/personnel/{p}/bookability`、排班例外 CRUD | 15、17 | 无 TDD |
| `/biz/hr/*` 22 条 | 17 | 无 TDD，2 档 |
| `PUT /biz/print/scene-policies/{scene}/simple`（若选门面） | 10 | 无 TDD |
| `GET /biz/goods/counts`（可选） | 11 | — |
| `GET /biz/chain/snapshot`（可选门面） | 19 | — |

### 改已有接口

| 接口 | 改动 | 模块 |
|---|---|---|
| `POST /biz/staff` | 号码校验从 `\d{11}` 改按市场，存 E.164 | 16 |
| `POST /biz/members` | 去掉 `CN_MOBILE`，按门店市场校验 | 18 |
| `PUT /biz/notify/sms-phones` | `MerchantNotifyRecipients` 号码规则按市场 | 5 |
| `GET /biz/notify/setting` | 加 `ownerEmail` | 5 |
| `GET /biz/staff` | 加 `loginEmail` | 16 |
| `POST /biz/member-segments(/preview)` | 规则加 `idleDays`（相对天数） | 18 |
| `GET /biz/coupons` | 加 `status` 过滤（可选） | 18 |
| `GET /biz/activities` | 加 `triggerType` 过滤、VO 加 `goods[]` | 20 |
| `GET /biz/groups`、`GET /biz/group/{g}` | 加 `failReason`、`refundedMinor` | 20 |
| `GET /biz/goods/{g}` | 加 `submittedAt` | 11 |
| `GET /biz/order/worklist` | 加 `dueAt` | 12 |
| `GET /biz/pickup/overview` | 加 `overdueCount`，修 `arrivedBatches` 恒 0 | 12 |
| `POST /biz/pickup/verify` | 回 `verifiedAt`、`operatorName` | 12 |
| `POST /biz/after-sale/{a}/approve` | 加 `returnRequired` | 12 |
| `GET …/mall/orders/{o}` | 组件带 `sourcePeriodNo`，供按组件退款走 `/refunds` | 3 |
| `ShopCommerceEndContextAdapter#strictScope` | 准入层权限不足另给 `nextAction`（不再一律 `CONTACT_ADMIN`） | 1 |

### 新路由登记（`ShopStoreMemberRoutes` / `CommerceRoutePolicy`）

| 登记 | 模块 | 说明 |
|---|---|---|
| ACCOUNT：`/biz/auth/identities*`、换手机 / 绑邮箱 / 注销、`/biz/feedback*` | 1、5、6 | 随 TDD 实现一起登记 |
| `STORE + entity(?:/relink-check)?` | 4 | 随 TDD |
| `/biz/express/companies` | 12 | 随 TDD |
| `/biz/food/.+`（方案甲）或 `/biz/x/food/.+`（方案乙） | 14 | 另需 166 以 `food-embedded` 构建 |
| `/biz/beauty/.+` 或 `/biz/x/beauty/.+`；`/biz/(?:personnel|resources|schedules|schedule-rules)(?:/.*)?` | 15 | 另需 `beauty-embedded` |
| `/biz/appointment-slots/[^/]+/close`（若沿用老时段模型） | 15 | 与例外表二选一 |
| `/biz/hr/.+` | 17 | — |
| `/biz/stored-value(?:/.*)?`；`[policy]` 放开 `/biz/stores/{s}/stored-value/*` | 18 | 储值首期开放时 |
| `/biz/management/(?:brands|brand-changes)(?:/.*)?`；`[policy]` 放开 `/biz/stores/{s}/(?:brand-changes(?:/check)?|operating-context)` | 19 | 现在任何账号都进不去 |
| `/biz/x/commerce/.+`、`/biz/stores/{s}/channel-order-policies*` | 14（外送平台） | 首期做外送平台才要 |
| `/biz/stores/{s}/usage` 加进 `[policy]` 门店 GET 白名单 | 3 | 随用量实现 |

### 建议优先级

**P0（上线前必须，改动小、影响面大或已冻结）**
1. **台湾手机号三处校验**（`MerchantStaffServiceImpl#add` 的 `\d{11}`、`BizMemberController.EnrollReq` 的 `CN_MOBILE`、`MerchantNotifyRecipients.PHONE`）。理由：台湾店主现在**加不了员工**、加不了会员、设不了额外短信手机；工具类 `Phones.valid` 已有，几行代码的事，又卡着员工管理主链路。
2. **6 份已冻结 TDD 里本盘点范围内的 4 份**（统一登录与账号、商户资料提交、问题回报与版本、快递公司）。理由：已拍板、App 侧开关已备好；版本检查要顺带让 App 改成 `/common/app/version`。
3. **166 配置**：`shop.print.credentials.key`，`shop.print.feie.region` 怎么取。理由：不配就加不了飞鹅机；无代码改动。
4. **团购建活动请求体对齐**（App 补 `benefitType=PRICE`、`quota`）。理由：路由已开、App 已放开入口，现在一提交就 10400，纯对齐。

**P1（首批店型与营销定案后）**
5. **餐饮门面（方案甲）+ `food-embedded` 部署 + 路由登记**——若首批店型含餐饮。理由：后端能力基本齐全，缺的是 App 形状门面和准入，投入产出比高；外送手工接单是唯一真缺口，可推迟。
6. **营销两套统一**：作废 `/biz/ops/*`，App 改走 `/biz/activities`、`/biz/coupons*`、`/biz/coupon-redeem`、`/biz/members*`；后端补 `idleDays`、`coupons?status`。理由：后端已全有，主要是 App 改路径，后端只有零星改动。
7. **连锁**：登记 brands / brand-changes 路由（包括放开 `/biz/stores/{s}/brand-changes`），App 改走品牌接口。理由：能力已实现但**任何账号都进不去**，开路由是前提。
8. **列印份数**（场景策略门面或 App 走路由三步），**商城按组件退款改走 `/refunds`**（订单详情补 `sourcePeriodNo`），订单 / 自提 / 售后 / 团购 VO 补字段，`notify/setting`、`staff` 补 `ownerEmail/loginEmail`，准入层 `nextAction` 区分。理由：都是已有接口加字段，零散但影响显示正确性。
9. **服务业**：先定首期是否上——BM04–06 未交付；若上，同餐饮做门面 + 登记人员 / 排班 / 美业路由 + 例外表 + 订金。

**P2（新域，先写 TDD 再排期）**
10. **考勤与工资**整域（13 张表、22 个接口、新权限码）。理由：完全没有、牵涉劳基法计算口径与「排班 vs 可约规则」关系，必须先 2 档 TDD；依赖服务业预约做冲突提示与业绩提成。
11. **商城用量**（`ComponentKind.USAGE` + 用量账户）。理由：目前没有任何扣量点（短信 / AI 都不计量），先定商业模式。
12. 商品按状态计数、评价筛选汇总、消息分页、美业报表 / 时间线 / 汇总：体验优化，可延后。
