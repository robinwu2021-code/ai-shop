# 沙箱收款消息与 SSE

状态：已实现（本地沙箱验证；未部署）；2026-11-13。档位2，跨端方案和选型已确认，需求承接商户收款既有PRD及 bapp/docs/implementation/H5扫码收款-SSE逐节点技术设计-2026-11-13.md；不改变资金、原有通知或全局未读定义。

## §0 需求到设计
| AC | 实现落点 |
|---|---|
| 签名、SANDBOX、可信来源和门店 | 固定内部端点，默认关闭配置，payment_collection_source显式跨库门店绑定 |
| ACK丢失及不同内容重放 | 永久receipt唯一源事件及hash，同事务已有OutboxEventBus |
| 授权STAFF消息与提交有序流 | 现有BizIdentityResolver逐店复核；消息与投影同事务，scope行锁持有至提交 |
| 隔离、重放、过期、撤权 | AES-GCM scoped cursor；共享MySQL增量；token与身份重查；comments heartbeat |

## §1 契约
POST /internal/payment-results/merchant-collections：原始body签名、5分钟、SANDBOX，ACK顶层eventId/payloadSha256/status/sourceReceiptNo。字段完全遵循跨端固定schemaVersion1/stateVersion2 Snapshot。
GET /biz/payment/events：现有bearer、BizContext、mch:pay:order:view；ready、payment.succeeded、resync_required、access_revoked及comment心跳。游标不透明、带身份及过期边界；不传游标从当前提交高水位开始。
配置前缀shop.payment-collections：enabled默认false；environment只允许SANDBOX；secret和cursor-secret从部署注入；poll-ms1000、heartbeat-ms15000、retention-ms604800000。显式来源绑定通过运维受控SQL配置，不提供公开写API。

## §2 实施计划及幂等卡
新增host paybridge窄服务、内部与Biz controller、V664增量迁移及原生MySQL测试。不修改历史迁移。
| 参数 | 数据库 | 服务端 |
|---|---|---|
| 固定eventId、原rawBody SHA256、受信source tuple+payment store映射 | receipt UNIQUE(environment,event_id)、付款效果唯一(environment,pay_order_no)，notify_message dedup、scope seq PK及投影UNIQUE(scope,event) | receipt+已有平台Outbox同事务；消费receipt行锁防重，notify_message+projection同事务；scope锁按稳定顺序至提交；重复ACK恢复原收据 |
本轮明确要求 shop.outbox.sources.platform.mode=unified；enabled=true而未配置统一模式时装配直接失败。通过现有 OutboxEventBus.publish(DomainEvent) 接入统一runtime，不修改legacy dispatcher，也不声称legacy可靠交付支持，不新建重试任务框架。本链是支付消息可靠跨事务边界；不修改原外部webhook/推送。

## §5 验证
原生测试覆盖签名/ACK、并发与rollback、错配store/source、权限隔离、cursor篡改/跨店/到期、双实例增量。localhost测试launcher使用相同production receiver/projector/stream，测试身份只在test源码。

## §6 实施证据

实现文件族：host paybridge PaymentCollection{Configuration,Properties,Security,Fact,Service,Work,Projection,Stream}、impl/PaymentCollectionServiceImpl；portal/internal/InternalPaymentCollectionController；portal/biz/BizPaymentEventsController；消息实体/DTO/查询增加可空结构化 PAYMENT 关联；ApiResponseWrapper 对 event-stream 原样透传；增量 V664__sandbox_payment_collection_stream.sql；对应原生/签名配置测试和localhost test host。现有 schema-test、BizEndpointPermTest只增量添加本功能内容，保留全部原dirty变更。

新增 payment_collection_source、receipt、scope、stream 四张窄表；source包含显式payment_store_id→store_no/entity_no映射，测试故意9001≠mch_store.id101。scope的floor_seq和清理在同锁事务完成。按distinct scope批量查询共享投影，同scope多设备复用一批增量行；每连接分别验证token和现有strict身份/收款权限。

真实MySQL9.7.4、127.0.0.1:3308、每fixture随机payment_sse_*独立数据库，使用新V664迁移；已有DDL不改。2026-11-13集中验证：
- PaymentCollectionHttpMysqlTest：3 tests，0 failure/error，10.09s。真实Tomcat signed HTTP→现有统一OutboxRunner→MessageServiceImpl→SSE；覆盖ACK顶层/同hash重复/hash冲突、生产环境拒绝、源/门店映射、事务回滚、权限收件人、全局未读、commit序号锁、基线ready.id恢复、replay、双独立SSE实例共享投影、expired resync、tamper400/cross-scope403、token撤销、结构化关联及TWD格式。
- PaymentCollectionNativeMysqlTest：签名/篡改/范围测试通过；另加ApplicationContextRunner启用legacy拒绝配置回归，见最终报告。
- 实际运行ArchitectureTest与BizEndpointPermTest发现9个现有其他dirty功能失败，涉及merchantbridge/promotion/member/未登记现有端点；报告未引用本功能新类。未修改其他会话业务代码以清除这些失败；不声称整仓检查通过。

幂等/回滚测试：receiptAndProjectionRollbackDedupConcurrentSourceChecksAndScopedCommitOrder。
HTTP/权限/恢复：httpReceiverCommitsDurableMessageSseReadyReplayAndPermissionRevocation。
多实例/过期：secondInstanceRecoversBizCollectionAndExpiredCursorsRequireExplicitResync。
签名/范围：signedContractRejectsMutationAndWrongEnvironment。
启用条件：enablingReceiverWithLegacyOutboxFailsClosedAtConfigurationBoundary。

部署前提：配置SANDBOX密钥（secret与cursor-secret均至少32字符）、显式来源/门店绑定、统一Outbox运行及新迁移，反向代理不缓冲/超时长于心跳。本轮未做生产部署、真实资金或负载目标验收。SSE每连接使用有界队列和独立virtual writer，poll/auth不阻塞socket；queue-full/write deadline断开单连接，控制帧预留槽且每批入队按可用budget。大规模负载预算未压测，不承诺P95。



### 独立review修复及最终增量证据

- CHECKOUT_QR→CHECKOUT为单向约束，允许CHECKOUT+BIZ_COLLECT并原样保留sourceType；受影响真实HTTP方法1/1通过，19.30s。
- PAYMENT历史每条按真实entity/store现查权限，跨店global历史保留已授权项，list/read/readall均过滤撤权店。Badge单条SQL返回同快照global count+PAYMENT grouped counts，按distinctscope授权扣除隐藏数，不加载正文。新增covering index。
- 每连接默认send-queue-capacity64（合法2..1024）、write-timeout-ms5000；独立有界writer并以remainingCapacity−1为业务入队budget，过载不误杀健康replay。closed/timeout cleanup不在shared poller获取Spring emitter的send锁。
- 最后一次受影响测试批：HTTP5项中原3项和120积压小队列replay通过；slow-writer 1/1通过（3.889s）；签名/配置3/3通过（0.097s）。唯一初次history失败来自test fixture缺少生产既有OptimisticLocker插件；仅补插件后定向history方法1/1通过（3.857s），覆盖A店撤PAY_ORDER_VIEW但B店保留，list/read(A)/read(B)/readall无A详情，global authorized unread 3→2→0，generic消息保留。
- 日志：/tmp/payment-collection-reviewed.log、/tmp/payment-collection-history.log。复用已有效其他证据，不重复全量/Architecture失败。未commit/push/deploy。


### 联调关断竞态增量修复

真实joint host发现HTTP AsyncContext.complete()已推进COMPLETING后，独立abort线程再emitter.completeWithError导致IllegalStateException。仅在已stop()的连接abort清理finally捕获此IllegalStateException；不捕获任意RuntimeException，不把HTTP abort或Spring send锁迁回shared poller。

定向测试 alreadyCompletingHttpAbortDoesNotEscapeItsIndependentCleanupThread：受控阻塞Spring completion，证明close不等待、HTTP abort先完成，随后COMPLETING异常不会逃逸独立清理线程。MySQL9定向单方法编译+测试退出0；见/tmp/payment-collection-abort-race.log。没有重跑已有效其他用例、没有重启root正在使用的host。


### 当前API发布基线上的独立候选验证（2026-11-13）

基线为实际已部署验证码API源码 `d3342239ac68a0ad50c4cc92adb56633938d90fd`；提取SSE本功能25文件，不携带原工作区无关dirty，并叠加经跨服务复核的canonical共享SPI兼容补丁。V663保持原样，V664未占用。权限登记补齐SSE PAY_ORDER_VIEW及基线既有captcha challenge/verify的PUBLIC元数据，控制器行为未改。

完整reactor、原生MySQL9功能10项全部通过：HTTP5（14.35s）、签名/配置3（0.098s）、slow-writer2（4.443s）。Architecture/BizEndpoint初次35项只有未登记端点一项失败、1skip；补精确登记后BizEndpointPermTest整类12/12通过（17.64s）。canonical SPI叠加后使用隔离m2执行完整production `clean package -DskipTests`退出0；Boot入口为ShopApplication，包含生产SSE及V664，排除测试host。

候选证据：`/tmp/payment-sse-deploy-20261113/shop/release-feature-tests.log`、`release-architecture-tests.log`、`release-endpoint-final.log`、`release-package.log`。交付29文件清单/patch和完整报告见跨仓bapp `.superpowers/sdd/payment-sse-2026-11-13/shop-release-report.md`。当前尚未提交/推送/部署；最终部署制品在提交后用实际git.sha重新构建。
