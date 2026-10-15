# 微信优先、微信与快递 100 双渠道订阅 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** B 端提交快递发货后，同一笔发货同时建立微信物流关注与快递 100 轨迹订阅；立即尝试取得微信运单 token 并查询状态，B/C 端展示一致结果。快递 100 回调补齐微信未提供的商家服务端轨迹推送与完整节点。

**Architecture:** 本地发货事务先提交，随后为微信关注和快递 100 订阅分别派发独立 outbox 任务；B 请求在短预算内先尝试微信传运单→查状态。两个订阅任务都要执行，任一成功、失败或超时均不得跳过另一渠道，也不得回滚已发货事实。微信状态查询优先、快递 100 回调补充轨迹；两者经 `WaybillProgress` 汇入 `lgs_waybill` 统一真源。微信关注、微信查询组件绑定、微信发货上报与快递 100 订阅各有独立台账和重试。

**Tech Stack:** Java / Spring Boot / MyBatis / Flyway / Maven；UniApp + TypeScript（B App、C 小程序）；现有 `sys_outbox`、`WaybillProgress`、`LogisticsPort`。

**Spec:** [TDD-订单状态及时同步与发货触达](../../technical/TDD-订单状态及时同步与发货触达.md) · [微信物流能力清单](../../technical/微信物流能力清单.md)。本计划聚焦发货物流同步；结算事件、短信模板、H5 跳转按 TDD 的 P0/P3 独立实施。

## Global Constraints

- 先微信、后其他渠道补足能力；微信物流消息只通知真实购买者，收件人短信是独立触达。
- “双渠道订阅”特指同一发货身份分别调用微信 `follow_waybill` 和快递 100 `/poll`，两项独立记录与重试；不是对快递 100 同一单号重复调用 `/poll`。微信能力优先指状态判定与买家触达优先，不能据此省略快递 100 的订阅。
- 微信 `waybill_token` 按当前发货身份持久化并复用；更正运单后旧 token 和旧回调不得更新新运单。
- `DELIVERED` 只推进物流状态，不直接把交易改成 `COMPLETED`。
- 微信接口或快递 100 失败不能使已提交发货返回“发货失败”；B 端明确显示“已发货、物流同步中”及原因。
- 快递 100 同账号、同公司+单号每自然月最多订阅 4 次；`501` 为仍在跟踪的重复提交，收到 `abort`/`shutdown` 至少 30 分钟后才允许再次订阅。[官方规则](https://api.kuaidi100.com/document/5f0ffa8f2977d50a94e1023c.html)。
- 本期不接积分，不扩退款，不做并发压力测试；验证使用定向测试、构建和人工真机联调。

## Review Focus

1. 同一个发货按钮连续点击或网络重试：只产生一笔发货，以及微信、快递 100 各一项有效订阅（任务 1、3、4）。
2. 微信返回 `9300559` 或状态 `0`：保持待核实并安排补查，不将号码判错（任务 2、3）。
3. 微信成功、快递 100 失败，或相反：两个结果分别落库，失败渠道独立补偿，成功渠道继续更新物流（任务 1、2、4）。
4. 更正单号或旧单号被其他订单复用：两渠道历史 token、推送和次数不能覆盖新运单或被清零（任务 1、4）。
5. 微信已签收而快递 100 尚无节点：B/C 都显示物流已签收、轨迹待补，交易仍待确认收货（任务 2、5）。

## 文件与接口边界

| 责任 | 主要文件 |
|---|---|
| 订阅与尝试台账 | 新 Flyway 迁移 `backend/shop-app/src/main/resources/db/migration/V417__logistics_channel_subscription.sql`；新 `backend/logistics/logistics-domain/src/main/java/ai/neargo/shop/logistics/registration/ChannelSubscriptionStore.java`、`Kd100AttemptStore.java` 及同模块 `entity`/`mapper` 实体；`LgsWaybill` 旧单渠道字段过渡读取 |
| 微信关注/查询 | 新 `backend/logistics/logistics-channel/src/main/java/ai/neargo/shop/logistics/channel/wx/WxFollowWaybillGateway.java`；`WxLogisticsClient.java`；`WxBindExecutor.java`、`WxBindPolicy.java` |
| 发货提交和即时查询 | `BizOrderController.java`、`MerchantOrderServiceImpl.java`、`LogisticsPort.java`；新 `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/ShipmentSyncFacade.java` 放在交易域的发货 API 边界，事务外调用物流 SPI |
| 快递 100 订阅/回调 | `SubscribeExecutor.java`、`Kuaidi100Codes.java`、`Kuaidi100Subscriber.java`、`PushIngestion.java`、`LogisticsAdminPortImpl.java` |
| B/C 一致展示 | `backend/shop-core/src/main/java/ai/neargo/shop/trade/dto/OrderVO.java`、`.../service/impl/MerchantOrderServiceImpl.java` 与 `OrderServiceImpl.java`；`b-app/src/pages/orders/index.vue`、`b-app/src/pages/order/index.vue`、`c-app/src/pages/orders/index.vue`、`c-app/src/pages/order/index.vue` 与两端 API contract |

## Task 1: 双渠道台账与订阅尝试约束

**Interfaces:** `ChannelSubscriptionStore` 按 `shipmentNo + sourceShipmentVersion + channel` 读写关注状态；`Kd100AttemptStore` 按 `accountRef + companyCode + waybillNo + yyyyMM` 统计已经发出的订阅尝试，提供 `reserveAttempt(...)`、`markResult(...)`、`markTerminal(...)`。账号用配置标识，不保存 API key 明文。

**Files:** 新 `V417__logistics_channel_subscription.sql`、`ChannelSubscriptionStore.java`、`Kd100AttemptStore.java` 及对应实体/Mapper；修改 `WaybillReplacement.java`；测试 `SubscribeExecutorTest.java`。

- [ ] 写迁移/领域测试：同一发货身份能同时保存微信和快递 100 两条订阅记录；一条失败不改变另一条；同一发货身份+渠道幂等；更正运单保留旧台账；存量 `sub_channel=kuaidi100` 可回填活动台账。快递 100 同号跨订单尝试次数仍受官方限制。运行测试，确认新接口缺失导致失败。
- [ ] 建 `lgs_channel_subscription`（当前发货身份+渠道唯一，状态、token/ref、错误、最后查询时间）与 `lgs_kd100_sub_attempt`（账号/公司/号码/月、尝试时间、结果、callbackId、终止时间），增加必要索引并回填存量。保留旧 `lgs_waybill.sub_*` 供过渡读取，不让它代表微信和快递 100 的合并状态。
- [ ] 实现持久化和唯一键约束；运行 `mvn -pl logistics/logistics-domain -am test -Dtest=SubscribeExecutorTest -Dsurefire.failIfNoSpecifiedTests=false`，确认通过；单独提交。

## Task 2: 微信传运单后立即查状态

**Interfaces:** `WxFollowWaybillGateway.follow(ShipmentSource) -> ChannelOutcome`（token 放在现有 `ref` 字段）；`queryFollow(token) -> Optional<TraceResult>`。`9300559` 为 `NOT_READY`，状态 `0` 为“未收录或未揽收”而不是无效号码；成功结果经 `WaybillProgress` 入库。

**Files:** 新 `WxFollowWaybillGateway.java` 及测试；修改 `WxLogisticsClient.java`、`WxBindPolicy.java`、`WxBindExecutor.java`、`application.yml`；测试 `WxBindTest.java`。

- [ ] 写微信接口桩测试：真实订单传入 `openid/trans_id/receiver_phone/delivery_id/goods_info/waybill_id`；成功保存关注 token 并立即查询；`9300559`、配额、超时、状态 `0` 各自有不同结果，重复事件不重复关注。先运行并确认失败。
- [ ] 新接 `follow_waybill` 与 `query_follow_trace`，token 按当前发货身份入任务 1 台账；查到状态调用现有 `WaybillProgress`。单独保留查询组件 `trace_waybill` token，不能默认两种 token 互通。
- [ ] 发货信息上报任务改为事务后立即派发；查询组件改成上报成功后尝试 `on-ship`，`9300559` 由揽收事件/补偿限频重试。收敛旧 30 分钟绑定路径，保证只有一条写 token 路径。两套微信能力都可能给买家通知，真机联调核对是否重复；若重复，发布时只启用一套买家消息能力，同时保留查询组件展示。运行 `mvn -pl logistics/logistics-domain,logistics/logistics-channel -am test -Dtest=WxBindTest -Dsurefire.failIfNoSpecifiedTests=false`，确认通过；单独提交。

## Task 3: B 端发货预检与事务后即时回显

**Interfaces:** `POST /biz/order/{subOrderNo}/ship/precheck` 返回 `VALID / INVALID / UNKNOWN`、渠道、状态和查询时间；原 `POST /biz/order/{subOrderNo}/ship` 增加预检令牌/幂等键，请求成功后返回订单与 `logisticsSync={status,source,lastEventAt,pending,reason}`。新 `LogisticsPort.syncAfterShip(subOrderNo, sourceShipmentNo, sourceShipmentVersion, budgetMs)` 负责在事务外执行任务 2 的微信尝试，并读取统一摘要。

**Files:** `BizOrderController.java`、`MerchantOrderServiceImpl.java`、`LogisticsPort.java`；新 `ShipmentSyncFacade.java`；`Kuaidi100TraceProvider.java` 增结构化短超时预检结果；B API contract 与 `b-app/src/pages/order/index.vue`；测试新 `ShipmentSyncFacadeTest.java` 和 B 端页面用例。

- [ ] 写测试：微信已有当前 token 先查微信；新号码本地重复绑定检查后才用快递 100；查不到/超时为 `UNKNOWN`；已签收旧号提示二次确认；已提交发货的微信超时响应仍是“已发货”。先运行并确认失败。
- [ ] 将预检和提交接口落地。`MerchantOrderServiceImpl.ship()` 的事务保持只写本地数据；控制器调用返回后由 `ShipmentSyncFacade` 在独立短预算内执行微信传运单/查询并读取最新摘要，过预算立即返回 `pending=true`，outbox 接续。查询与落库均核对当前发货身份。
- [ ] B 页面展示预检结果与“已签收旧号”明确确认；发货后用响应更新详情，并重新加载列表。运行对应 Java 定向测试和 `cd b-app && npm test -- --run`、`npm run build:app`；单独提交。

## Task 4: 双渠道独立派发与快递 100 轨迹回调

**Interfaces:** 发货 outbox 分别派发 `WX_FOLLOW_WAYBILL` 与 `KD100_SUBSCRIBE`，按 `shipmentNo + sourceShipmentVersion + channel` 幂等、分别记录结果与重试时间；即使微信即时查询成功，也必须派发快递 100 订阅。快递 100 外部订阅身份为 `accountRef + companyCode + waybillNo + callbackId`。重复点击/事件返回该渠道已有状态，不重复对外调用。

**Files:** `SubscribeExecutor.java`、`Kuaidi100Subscriber.java`、`Kuaidi100Codes.java`、`Kuaidi100PushReceiver.java`、`PushReceiver.java`（`Parsed` 增 callbackId 与明确终止类型）、`PushIngestion.java`、`LogisticsAdminPortImpl.java`、`OpsShipmentController.java`；测试 `SubscribeExecutorTest.java`、`Kuaidi100PushTest.java`。

- [ ] 写测试覆盖：一次发货分别创建微信与快递 100 任务；微信成功不跳过快递 100，快递 100 成功也不跳过微信；任一渠道失败只重试该渠道；重复发货事件不会重复调用已经成功的渠道。快递 100 `501` 未知归属标 `CONFLICT`，不伪装成已订阅。先运行并确认失败。
- [ ] 将当前 `SubscribeExecutor` 的“路由链首个成功即返回”改为按渠道分别执行、分别确认；消费任务时校验当前发货版本。订阅前记录快递 100 尝试，网络结果不明也占尝试额度；回调 URL 带不含个人信息的 `callbackId`。`501` 仅在证明已有活动订阅属于当前发货身份时复用，否则进入冲突待核。
- [ ] `PushReceiver.Parsed` 增 callbackId 与 `polling/abort/shutdown/updateall` 监控状态；回调先验签并持久化原始事件，再按 callbackId、公司+单号、当前发货身份路由；旧回调仅留审计，不推进更正后的运单。运营端展示两渠道各自状态、错误和重试时间。对快递 100 同号再次订阅，仅在其官方允许的 `abort/shutdown` 后至少 30 分钟、当月次数不足 4 次且确有继续跟踪需求时受理；这是接口防护，不是双渠道订阅的验收目标。
- [ ] 运行 `mvn -pl logistics/logistics-domain,logistics/logistics-channel -am test -Dtest=SubscribeExecutorTest,Kuaidi100PushTest -Dsurefire.failIfNoSpecifiedTests=false`，确认通过；单独提交。

## Task 5: B/C 统一物流摘要与验收

**Interfaces:** `LogisticsPort.summariesOf(Collection<String> subOrderNos)` 一次批量取当前运单的 `status/source/lastEventAt/syncPending`；B/C 列表和详情均同时返回交易状态与物流状态，不从微信/快递 100 各自直查。

**Files:** `backend/shop-base/src/main/java/ai/neargo/shop/spi/logistics/LogisticsPort.java`、`backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/MerchantOrderServiceImpl.java`、同目录 `OrderServiceImpl.java`、`backend/shop-core/src/main/java/ai/neargo/shop/trade/dto/OrderVO.java`；`b-app/src/pages/orders/index.vue`、`b-app/src/pages/order/index.vue`、`c-app/src/pages/orders/index.vue`、`c-app/src/pages/order/index.vue` 与两端 contract；相关后端投影/前端页面测试。

- [ ] 写测试：50 条列表一次批量读物流；列表与详情同一子单状态一致；微信已签收但无轨迹节点时显示“已签收，轨迹待更新”；交易仍为履约中；更正后只显示新运单。先运行并确认失败。
- [ ] 实现批量摘要及两个端的双状态标签、同步中原因和更新时间。B 端展示主订单号，物流信息由当前子单关联，不把子单号当用户看到的订单号。
- [ ] 运行 Java 定向测试、`cd b-app && npm test -- --run && npm run build:app`、`cd c-app && npm test -- --run && npm run build:mp-weixin`；单独提交。

## 发布与人工联调闸门

- [ ] 先核实小程序“物流消息”“查询组件”权限、已发布条件、微信买家 openid/支付交易号及快递 100 回调可达；能力不具备则记录原因并保持兜底通道。
- [ ] 只用新测试单人工覆盖：同一笔发货两渠道均成功、微信成功/快递 100 失败、快递 100 成功/微信失败、已有运输轨迹、`9300559`、快递 100 `501`、号码更正、买家与收件人不同；对照 B/C/运营端和微信买家消息。遵照用户要求不做并发压力测试。
- [ ] 灰度后按主订单号核对 `发货 → 微信关注/token 与快递100订阅各自回执 → 微信首次查询/快递100回调 → 统一状态 → B/C摘要`；任一渠道失败均能看到该渠道独立补偿，不能误报双渠道已成功。发现旧回调污染、重复通知或月度计数异常即关闭对应渠道开关并修正，不回滚已提交订单状态。
