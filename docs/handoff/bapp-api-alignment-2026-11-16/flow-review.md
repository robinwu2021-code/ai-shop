# 注册 → 门店 → 主体 → 进件 → 开通收款 → 收款：主链路评审

日期：2026-11-16。只读评审，未改代码、未访问 166。
范围：ai-shop `feat/bapp-account-funds`（`1481cc327`，= origin/main + V46 + 账号中心）与 near-pay `codex/near-pay-migration`（`dfc845ff`，166 运行的 `bfbd4a682` 是其祖先）。
「已核实」= 已对照代码确认；其余为评审结论，动手前逐条再核。凡涉及 166 现网开关、行数、流量的结论，都要先在 166 只读确认。

---

## 一、总览

**一句话**：主链路在生产上能走通，但每个阶段都有「新旧两到三套实现并存」，文档只覆盖了 App 实际调用的一部分，且有 4 个会直接影响用户的缺陷。

| 阶段 | 生产主路 | 并存的旧路 / 副本 | 最要紧的问题 |
|---|---|---|---|
| 注册登录 | `/biz/auth/login`（App 默认）；统一登录 identities 需 App 编译开关 | `staff-login`（已无生产调用）、C 端 `switch-to-merchant` | 统一登录建号不绑人档；「店主→员工」判定写了 4 份且口径已分叉 |
| 建店建主体 | `store-openings`、`/biz/entities`、V46 主体资料 | `quick-start`（App 兜底 + uni-app + 测试夹具）、`mch_entity_apply` 运营审核 | **V46 三条路由在 commerce 开启时被拦 403（已核实）**；店设置未选店时写入静默失败（已核实） |
| 进件开通 | near-pay `/biz/pay/v2/*` + `pay-service` | 第一代 `/biz/merchant/payment*`（App 仍在调，台湾无网关）、第二代宿主内嵌 I4 进件（休眠） | App 仍露出一个台湾走不通的旧进件入口；状态有四套枚举无映射表 |
| 收款 | `/biz/payment/*` → near-pay | 宿主内嵌 pay-domain 收款（停更分叉）、`/biz/pay/*` 遗留路由 | **收款语音 SSE 两端都只认 SANDBOX，真实收款不会播报（已核实）** |

---

## 二、需要先修的缺陷（按影响排序）

> 2026-11-16 已修：#1（路由放行，含 11 条路由的策略测试）、#3（写入要求已选门店，更新行数≠1 回 CONFLICT）、#6（统一登录进入时绑人档）；五节 A 批已做。其余待决定。

| # | 问题 | 位置 | 后果 | 修法 | 风险 |
|---|---|---|---|---|---|
| 1 | V46 路由未放行（已核实） | `CommerceRoutePolicy.java:183-184` 只放行 profile-template/draft/commands 与 review-submissions/files/revisions/channel-requests | `POST /biz/entities/{no}/contact`、`/revision-discards`、`GET …/files/{id}/preview` 在 commerce 开启的部署落到 DENY，有 STORE_V1 门店的账号 403 | 补进 ACCOUNT 放行；补一条开启 commerce 的流程测试 | 低 |
| 2 | 收款语音只在沙箱（已核实） | ai-shop `PaymentCollectionProperties` / `PaymentCollectionSourceProperties` 的 `@Pattern("SANDBOX")`；near-pay `MerchantCollectionNotificationProperties:34`、`MerchantCollectionResultRecorder:47` | 正式收款不推送，P0「3 秒内播报」在生产上做不到 | 产品决定：补 PRODUCTION 链路（两仓），或 App 降级为轮询；同时核实 BIZ_COLLECT 的来源映射要逐操作员配置 | 中（两仓 + 安全） |
| 3 | 店设置写入可能静默失败（已核实） | `BizStoreSettingsController.java:31-50` 用 `currentStoreNo()` | 未选店时写入 `.eq(storeNo,null)` 不更新任何行仍回 200 | 改 `requireStoreNo()`，更新行数≠1 回 CONFLICT | 低 |
| 4 | 开店回执大小写不敏感（已核实） | `V673` 的 `request_no` 未用 `utf8mb4_bin`（V674 用了） | 大小写不同的 requestNo 被当成同一张回执：409 或重放到别人的结果 | 新迁移改列排序规则（166 先查是否已有冲突行） | 中（改正式库） |
| 5 | App 仍调第一代进件 | App `onboarding_api.dart:919-966` → `BizMerchantController:829-917`；`MerchantPaymentServiceImpl:498-513` 只有 Stub/微信/支付宝 | 台湾商户看到一个永远完成不了的进件入口 | App 改走 `/biz/pay/v2` + pay-service；后端旧端点标废弃 | 低 |
| 6 | 统一登录不绑人档 | `AuthServiceImpl.enterVerified:347`、`LoginIdentityServiceImpl` 不调 `bindOnLogin`（旧 login :419 调） | 走统一登录注册的店主没有人档，会员关系挂不上 | `enterVerified` 补 `bindOnLogin`（失败不阻塞） | 低 |
| 7 | 主体激活两条路缺同一把锁 | 运营审核 `activate` 未走 `lockLegacyWrite/managed`（`MerchantGovernServiceImpl:1094/1120` 才有） | 运营审核与 V46 投影可能并发写同一主体 | 运营路径加同样的锁 | 中 |
| 8 | 建店能力位恒为 true（App 会话 2026-11-16 反馈） | `StoreCreationAccessService.Access` 三参构造把 `entityNameSupported`、`storeOpeningSupported` 写死为 true | 功能关闭时仍报支持，App 分不清该走 store-openings 还是 quick-start（关闭时 `canQuickStart=false` 却 `storeOpeningSupported=true`） | 按真实开关取值；去掉三参构造前先确认所有调用方 | 低 |

---

## 三、API 文档

**结论**：`openapi-b.yaml` 是旧 uni-app 时代的总表，App 主路的大部分端点不在里面；新端点分散在十几个按功能命名的 OpenAPI 文件里，有重复、有空壳、有与代码冲突。

| 类别 | 具体缺口 |
|---|---|
| 完全没有 schema | `identities` / `enter` / `mine` / `session`（只有路径占位）；`/biz/entity/{entityNo}`（App 收款页在用，无 path）；改挂 5 条（无 requestBody / 响应） |
| 与代码不一致 | `StoreCreationAccess` 缺 `entityNameSupported`、`storeOpeningSupported`（App 靠它选建店路径）；`POST /biz/entities` 在 `platform-entity-store-contact` 与 `entity-profile-workspace` 两份互相冲突（前者过时）；`PayCollectResult` 缺 4 个字段；`PayRefundItem.status` 少 4 个状态，退款只回内部 `payOrderId` 无 `payOrderNo` |
| 不在总表 | `/biz/payment/orders*`（5 条）、`links*`、`collect-status`、`events`；`/biz/merchant/pay-service/apply`；`/biz/pay/v2/*` 21 条 |
| 头与事件 | `X-Captcha-Token`、`X-Store-No` 未写成 parameter；SSE 无事件 payload schema（`payment.succeeded` 实际字段无金额），未写「仅 SANDBOX、关时 404、`diagnostic.ping`」 |
| 内部合同 | 宿主↔near-pay：`/internal/pay/v2/source-links/*`、onboardings、store-claims 只在 TDD 正文；near-pay `contracts/operations.json` 只登记了 `source-links/apply` |
| 文字失真 | `TDD-B端进件接入near-pay` 写 15 条实为 21 条；`TDD-支付服务-I4` 标「已实现」但已被 near-pay 取代；App 对齐清单 CSV 漏 7 条（orders×5、links、pay-channel、merchant/payment GET）且有一处文件路径错误 |

**建议做法**：不再往 `openapi-b.yaml` 里堆。按本链路六个阶段出一份「商户 App 主链路」合同（或在现有按功能文件上补齐并删除重复），以 App 生产实际调用的清单为准逐条对账；宿主↔near-pay 内部合同以 near-pay 为真源出 OpenAPI。

---

## 四、数据库设计

| 主题 | 发现 | 建议 |
|---|---|---|
| 手机号多处存放 | `usr_account.phone`（三处双写）、`usr_identity` PHONE、`mch_account.login_phone`、`usr_person` 哈希；已有读方回退旧列 | 定 `usr_identity` 为唯一来源：先迁读方，再停双写，最后删列 |
| 凭证表缺唯一键 | `usr_identity` 无 `(user_no, identity_type)` 唯一；`setPassword` 先查后插，并发可插出两条 PASSWORD | 加唯一键（PHONE/EMAIL/PASSWORD），上线前查重 |
| 缺索引 | `mch_account` 按 `login_phone` 查 4 处无可用索引；`pay_order(store_id, succeeded_at)`、`pay_refund(store_id, status, succeeded_at)`（汇总与日流水按成功时间查） | `mch_account(login_phone, status)`；pay 两个索引加在 near-pay 迁移 |
| 同名表两库两义 | `pay_merchant.status`、`pay_merchant_binding.active_key`、`pay_onboarding.inflight_key` 在宿主 V10 与 near-pay V25/V26 语义不同 | 宿主内嵌副本标弃用，不再改；文档写明 near-pay 为准 |
| 状态无映射 | 主体审核三套（`mch_entity.status`、`mch_entity_apply.status`、PAY platformReview，且 `EntityVO.review` 被 `platformReview` 覆盖）；进件四套（旧表、宿主 pay_onboarding、near-pay status+review_status、pay-service 对 App） | 各出一张状态映射表；拆开 `review` 与 `platformReview` |
| 幂等回执两张表口径不同 | 开店回执用 `actor_key`、建主体回执用 `user_no`；排序规则与 tenant_no 长度不一 | 新表统一 `utf8mb4_bin` 与 `tenant_no VARCHAR(64)`；后续考虑通用回执表 |
| 时间类型混用 | `pay_*` 用 `DATETIME(3)` UTC，`payment_collection_*` 用 BIGINT 毫秒 | 新表按域统一，接口统一 epoch 毫秒 |
| 只写不读 / 过时注释 | `usr_login_ticket.purpose`；`mch_entity.status`、`usr_account`、`mch_account`、`pay_order.biz_type` 的表 / 列注释与现状不符；两套测试白名单表 `usr_otp_test_phone` 与 `usr_test_identity` | 注释随下一个迁移修；白名单收敛到 `usr_test_identity` |

---

## 五、冗余代码与注释清理清单

按「删了会不会伤到人」分三批。

**A 批：现在就能安全做（零或极低风险）**
- 注释错位：`BizMerchantController:837-847`（pay-channel 的注释挂在 debt 上）、`:286-297`（apply 的注释无主）、`:395`（`@param` 挂错 record）；`BizStoreEntityController:18`（写「只读」但已有 POST）。
- 过时注释：`MerchantPaymentServiceImpl:508`（「唯一实现是 Stub」）；`PayDataSourceConfig:37-53`（「不需要第二个 Flyway」与代码相反）；`CompositeIdentityLoader:11`、`SubjectKind:16`、`UserService:13`（员工从 staff-login 进）。
- 死代码：`AuthService.sendEmailOtp`（零调用）；`StoreOpeningServiceImpl` 未用 import；`BizPaymentEventsController` 权限码改用 `BizPerms` 常量并加 `@Profile("api")`。

**B 批：要先改测试或核对调用方**
- `EntityCreationService` 4 参 `create`、`QuickStartReq` 两个旧构造、`PayBizFacade` / `CheckoutServiceImpl` 的 5 参 `collect` 重载：只剩测试在用。
- 重复小工具合并：`personalUser()` 与 `MerchantAccountServiceImpl.personal`；三处「按 userNo 取手机号」。
- 门店行业 / 坐标写入三份实现（含 `StoreOpeningServiceImpl` 的裸 UPDATE）合一。

**C 批：要先在 166 只读确认，或等调用方下线**
- 宿主内嵌 I4 进件整套（`OnboardingServiceImpl`、`PayOnboardingPollJob`、`PayMerchantMirror*` / `Backfill*` / `Compare*`，约 1300 行）：先确认 166 的双写 / 读切开关与表行数；读切影响商城资金，必须确认从未开过。
- `/biz/pay/*` 五个遗留控制器（两仓）：先查 166 访问日志。
- `staff-login`、`quick-start`、`/biz/store/list`：先迁测试夹具，App 去兜底、uni-app 下线后再删。
- 两仓 `PayBizInternalApi` / `InternalPayBizEndpoint` 复制件：抽共享构件或加两仓比对闸门。

---

## 六、建议的执行顺序

1. **缺陷 1、3、6**（低风险、单仓、可测）——本分支直接修。
2. **清理 A 批**——随第 1 步一起提交。
3. **文档**：补 StoreCreationAccess 能力位、删重复的 `/biz/entities`、补 identities/session/entity 详情/改挂 schema、补收款 orders/links/events 与 SSE 事件 schema、修 TDD 失真与 App 清单 CSV。
4. **需要决定的**：缺陷 2（生产 SSE 做不做）、缺陷 5（App 何时切掉旧进件）、缺陷 4 与各索引（改正式库，随下次发布）。
5. **B、C 批**——按调用方下线进度分批。
