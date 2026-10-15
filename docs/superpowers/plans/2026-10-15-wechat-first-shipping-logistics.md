# 微信优先的发货物流同步与快递 100 补订 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** B 端提交快递发货后立即尝试取得微信运单 token、查到的状态在本次响应及 B/C 端一致展示；快递 100 补齐微信无法提供的完整轨迹和服务端推送，并安全支持同运单结束后的重复订阅。

**Architecture:** 本地发货事务先提交，再在独立短预算内做微信传运单→查状态；失败由 outbox 补偿，绝不回滚已发货事实。所有渠道只写 `lgs_waybill` 统一真源。微信关注、微信查询组件绑定、微信发货上报与快递 100 补充订阅有独立台账；快递 100 的次数按账号+公司+单号+自然月跨订单计算。

**Tech Stack:** Java / Spring Boot / MyBatis / Flyway / Maven；UniApp + TypeScript（B App、C 小程序）；现有 `sys_outbox`、`WaybillProgress`、`LogisticsPort`。

**Spec:** [TDD-订单状态及时同步与发货触达](../../technical/TDD-订单状态及时同步与发货触达.md) · [微信物流能力清单](../../technical/微信物流能力清单.md)。本计划聚焦发货物流同步；结算事件、短信模板、H5 跳转按 TDD 的 P0/P3 独立实施。

## Global Constraints

- 先微信、后其他渠道补足能力；微信物流消息只通知真实购买者，收件人短信是独立触达。
- 微信 `waybill_token` 按当前发货身份持久化并复用；更正运单后旧 token 和旧回调不得更新新运单。
- `DELIVERED` 只推进物流状态，不直接把交易改成 `COMPLETED`。
- 微信接口或快递 100 失败不能使已提交发货返回“发货失败”；B 端明确显示“已发货、物流同步中”及原因。
- 快递 100 同账号、同公司+单号每自然月最多订阅 4 次；`501` 为仍在跟踪的重复提交，收到 `abort`/`shutdown` 至少 30 分钟后才允许再次订阅。[官方规则](https://api.kuaidi100.com/document/5f0ffa8f2977d50a94e1023c.html)。
- 本期不接积分，不扩退款，不做并发压力测试；验证使用定向测试、构建和人工真机联调。

## Review Focus

1. 同一个发货按钮连续点击或网络重试：只产生一笔发货和一次有效渠道关注（任务 3、4）。
2. 微信返回 `9300559` 或状态 `0`：保持待核实并安排补查，不将号码判错（任务 2、3）。
3. 快递 100 返回 `501` 但本地找不到属于当前发货身份的活动订阅：不能假定当前回调已生效（任务 4）。
4. 更正单号或旧单号被其他订单复用：历史 token、推送和次数不能覆盖新运单或被清零（任务 1、4）。
5. 微信已签收而快递 100 尚无节点：B/C 都显示物流已签收、轨迹待补，交易仍待确认收货（任务 2、5）。

## 文件与接口边界

| 责任 | 主要文件 |
|---|---|
| 订阅与尝试台账 | 新 Flyway 迁移 `backend/shop-app/src/main/resources/db/migration/V417__logistics_channel_subscription.sql`；新 `backend/logistics/logistics-domain/src/main/java/ai/neargo/shop/logistics/registration/ChannelSubscriptionStore.java`、`Kd100AttemptStore.java` 及同模块 `entity`/`mapper` 实体；`LgsWaybill` 旧单渠道字段过渡读取 |
| 微信关注/查询 | 新 `backend/logistics/logistics-channel/src/main/java/ai/neargo/shop/logistics/channel/wx/WxFollowWaybillGateway.java`；`WxLogisticsClient.java`；`WxBindExecutor.java`、`WxBindPolicy.java` |
| 发货提交和即时查询 | `BizOrderController.java`、`MerchantOrderServiceImpl.java`、`LogisticsPort.java`；新 `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/ShipmentSyncFacade.java` 放在交易域的发货 API 边界，事务外调用物流 SPI |
| 快递 100 订阅/回调 | `SubscribeExecutor.java`、`Kuaidi100Codes.java`、`Kuaidi100Subscriber.java`、`PushIngestion.java`、`LogisticsAdminPortImpl.java` |
| B/C 一致展示 | `backend/shop-core/src/main/java/ai/neargo/shop/trade/dto/OrderVO.java`、`.../service/impl/MerchantOrderServiceImpl.java` 与 `OrderServiceImpl.java`；`b-app/src/pages/orders/index.vue`、`b-app/src/pages/order/index.vue`、`c-app/src/pages/orders/index.vue`、`c-app/src/pages/order/index.vue` 与两端 API contract |

## Task 1: 渠道台账与全局订阅次数

**Interfaces:** `ChannelSubscriptionStore` 按 `shipmentNo + sourceShipmentVersion + channel` 读写关注状态；`Kd100AttemptStore` 按 `accountRef + companyCode + waybillNo + yyyyMM` 统计已经发出的订阅尝试，提供 `reserveAttempt(...)`、`markResult(...)`、`markTerminal(...)`。账号用配置标识，不保存 API key 明文。

**Files:** 新 `V417__logistics_channel_subscription.sql`、`ChannelSubscriptionStore.java`、`Kd100AttemptStore.java` 及对应实体/Mapper；修改 `WaybillReplacement.java`；测试 `SubscribeExecutorTest.java`。

- [ ] 写迁移/领域测试：同一号码跨两张订单共享月度次数；更正运单不清除历史尝试；同一发货身份+渠道幂等；存量 `sub_channel=kuaidi100` 可回填活动台账。运行测试，确认新接口缺失导致失败。
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

## Task 4: 快递 100 兜底和安全重复订阅

**Interfaces:** 订阅身份为 `accountRef + companyCode + waybillNo + callbackId`；`requestResubscribe(shipmentNo, reason)` 只在当前订阅已 `abort/shutdown`、距终止至少 30 分钟、当月尝试少于 4 次且仍有业务需要时受理。重复点击/事件直接返回已有状态，不再对外调用。

**Files:** `SubscribeExecutor.java`、`Kuaidi100Subscriber.java`、`Kuaidi100Codes.java`、`Kuaidi100PushReceiver.java`、`PushReceiver.java`（`Parsed` 增 callbackId 与明确终止类型）、`PushIngestion.java`、`LogisticsAdminPortImpl.java`、`OpsShipmentController.java`；测试 `SubscribeExecutorTest.java`、`Kuaidi100PushTest.java`。

- [ ] 写测试覆盖：`200` 新订、已活动时本地幂等、`501` 已知活动订阅复用、`501` 未知归属标 `CONFLICT`、`abort/shutdown` 前 30 分钟拒补订、30 分钟后且本月不足 4 次准许补订、跨订单仍计 4 次、超限不请求外部。先运行并确认失败。
- [ ] 订阅前先在任务 1 台账登记一次尝试，网络结果不明也占尝试额度，避免超额；回调 URL 带不含个人信息的 `callbackId`。把 `501` 从无条件 `OK` 改为“确认本地活动订阅归属后复用；否则冲突待核”，因为它不能替新请求改写原回调地址。
- [ ] `PushReceiver.Parsed` 增 callbackId 与 `polling/abort/shutdown/updateall` 监控状态；回调先验签并持久化原始事件，再按 callbackId、公司+单号、当前发货身份路由；旧回调仅留审计，不推进更正后的运单。`abort/shutdown` 记录终止时间；已签收的终态单不自动补订，仍需业务持续跟踪的单按规则重订。运营端 replay 增补订原因并展示最早可补订时间、月度余量及 `501` 冲突。
- [ ] 运行 `mvn -pl logistics/logistics-domain,logistics/logistics-channel -am test -Dtest=SubscribeExecutorTest,Kuaidi100PushTest -Dsurefire.failIfNoSpecifiedTests=false`，确认通过；单独提交。

## Task 5: B/C 统一物流摘要与验收

**Interfaces:** `LogisticsPort.summariesOf(Collection<String> subOrderNos)` 一次批量取当前运单的 `status/source/lastEventAt/syncPending`；B/C 列表和详情均同时返回交易状态与物流状态，不从微信/快递 100 各自直查。

**Files:** `backend/shop-base/src/main/java/ai/neargo/shop/spi/logistics/LogisticsPort.java`、`backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/MerchantOrderServiceImpl.java`、同目录 `OrderServiceImpl.java`、`backend/shop-core/src/main/java/ai/neargo/shop/trade/dto/OrderVO.java`；`b-app/src/pages/orders/index.vue`、`b-app/src/pages/order/index.vue`、`c-app/src/pages/orders/index.vue`、`c-app/src/pages/order/index.vue` 与两端 contract；相关后端投影/前端页面测试。

- [ ] 写测试：50 条列表一次批量读物流；列表与详情同一子单状态一致；微信已签收但无轨迹节点时显示“已签收，轨迹待更新”；交易仍为履约中；更正后只显示新运单。先运行并确认失败。
- [ ] 实现批量摘要及两个端的双状态标签、同步中原因和更新时间。B 端展示主订单号，物流信息由当前子单关联，不把子单号当用户看到的订单号。
- [ ] 运行 Java 定向测试、`cd b-app && npm test -- --run && npm run build:app`、`cd c-app && npm test -- --run && npm run build:mp-weixin`；单独提交。

## 发布与人工联调闸门

- [ ] 先核实小程序“物流消息”“查询组件”权限、已发布条件、微信买家 openid/支付交易号及快递 100 回调可达；能力不具备则记录原因并保持兜底通道。
- [ ] 只用新测试单人工覆盖：新号码、已有运输轨迹、已签收旧号码、`9300559`、快递 100 `501`、`abort` 后合法补订、号码更正、买家与收件人不同；对照 B/C/运营端和微信买家消息。遵照用户要求不做并发压力测试。
- [ ] 灰度后按主订单号核对 `发货 → 微信关注/token → 微信首次查询 → 快递100补充订阅/回调 → B/C摘要`；发现旧回调污染、重复通知或月度计数异常即关闭对应渠道开关并修正，不回滚已提交订单状态。
