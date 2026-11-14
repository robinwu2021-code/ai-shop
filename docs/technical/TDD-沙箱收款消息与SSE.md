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

## 2026-11-14 SSE 稳定性定时验证（档位1）

### §0 本轮验收

用户要求先用服务端定时通知验证同一条 App SSE 链路，再验证支付系统发通知闭环；只在当前会话修改对应项目，不跨会话协调。默认10秒一次、30分钟自动结束，限定166 SANDBOX及robin/指定门店。诊断通知不构造支付事实，不生成付款消息、金额、流水、未读或收款语音。

### §1 增量契约

新增非资金 SSE event `diagnostic.ping`：schemaVersion=1、diagnostic=true、runId、sequence、sentAt（UTC毫秒）、storeNo、entityNo。无SSE id，不改变payment.succeeded游标，不落支付/消息表，不补发历史诊断。事件复用现有连接、鉴权复核、有界队列与writer。服务器日志区分generated/queued/sent；App回调只接收匹配当前门店的有效诊断，输出接收序号/时间供比对。旧App忽略未知事件仍可收真实支付。

配置 `shop.payment-collections.diagnostic`：enabled默认false，user-no/entity-no/store-no精确目标；interval-ms默认10000（5000–60000）；duration-ms默认1800000（30000–3600000）。仅API profile、已启用SANDBOX收款流允许创建定时任务。任务实例生成runId，SmartLifecycle开始后按时发送，到期或关闭停止，无新增公开调试入口或授权。

### §2 执行顺序

1. 实现受控诊断任务及stream广播、结构化发送/断开证据；不改支付投影、消息或支付状态。
2. App标准transport添加可选诊断回调，校验类型/目标/schema；测试夹具收集实际字节解析后的样本，金融coordinator继续忽略该事件。
3. 相关Java/Flutter测试、编译与代码复核；增量发布API并在模拟器保持真实登录运行30分钟，统计已投递与收到序号、重复/缺号、到达间隔、断线恢复。不能以服务端send成功替代App收到。
4. 稳定性通过后检查沙箱资格、App就绪和新H5会话，再执行一次已授权的NT$1官方测试卡付款，验证真实PAY回调→HTTP→receipt/message/stream→App详情/语音。后台只补消息未读，不播历史。

### §5 证据要求

任务默认关闭/错误目标配置/生产拒绝、间隔与自动停止、范围隔离、撤权、同一发送队列、无金融副作用及不改游标须有测试；App诊断格式/错店拒绝/真实支付事件兼容有测试。最终运行报告逐项列出服务端生成、入队、发送及App接收数、序号差异/重连，不声称网络永不掉线。实际支付和语音必须用真实沙箱回执及native完成记录证明，不用诊断事件替代。


## 2026-11-14 08:55 实际联调结果（本轮最新）

本轮只在当前会话修改三个项目并执行测试，没有跨会话发送消息。新增受控 `diagnostic.ping` 服务端任务和 App 可选观测回调；诊断没有支付单号、金额、SSE id 或资金/消息表写入，不触发收款播报。默认关闭，只允许 API profile、SANDBOX、精确 user/entity/store；本次10秒一次，30分钟自动结束。

实际运行 08:25:21–08:55:21（北京时间）：生成179，入队175，writer实际写出174，App实收174、唯一174，重复0、非预期缺失0。序号1发生在App连回之前；105–108位于08:42:43–08:43:22主动切后台窗口，其中105入队后HTTP已关闭、没有成功写出。后台诊断不持久化、不补发，不能将这5个未收到样本算作前台丢包，也不能将175次入队视为175次成功送达。连续前台到达间隔中位数10秒；49.959秒的最大间隔包含受控后台。设备钟比服务器钟约慢0.7秒，原始跨机时间差不是精确网络延迟。部署重启阶段的502与初始断线单列，服务健康后的两段前台观察未出现非预期断线。

官方蓝新 SANDBOX NT$1 测试订单 `PYO261114084336221` 实付成功（无真实扣款）：PAY订单SUCCEEDED，来源CHECKOUT/payAppId1/sourceId1/storeId23；自动HTTP通知首次返回200，312ms，ACK=APPLIED，双方eventId/hash/receiptNo逐项匹配。商城收据 `PCR2611140N9Q26DA7A` 一条，对应USR和既有MCH关联身份各一条消息/stream。消息保持未读，未因SSE到达标读。App实际只收到1次真实payment.succeeded，详情success且权威paidAt存在，横幅一次，Android原生TTS started=1/completed=1。诊断期间未出现额外播报。

原H5资格已到期。本轮经现有运营界面的草稿、两个复核节点及执行激活 `SSE166_H5_20261114:1`，审批 `APA8484718472B4302`，限定1小时；两个节点由现有实例策略允许的Neara账号依次完成，未更改审批策略。只读复核显示08:49:46已有第2版接替第1版，当前ACTIVE为第2版、有效至14:49；本轮未创建/修改第2版，也未覆盖该状态。仍不能将试运行说成UAT通过。

API增量部署SHA `b3da994752b77fcf5780612f49ae307b680b865bd8640dda6b69692dfefa0640`。API/OPS均健康，原容器与绑定inode保持；PAY文件及静态指针前后摘要一致。任务到期停止后已将私有配置diagnostic.enabled置false，不重启服务，其余配置字节保持。未执行资金业务SQL或伪造回调。

验证：新增Java诊断8项通过，既有签名配置3项、沙箱只读路由22项此前定向结果有效；Flutter诊断2项、coordinator12项通过，原传输/事件测试保持已通过证据，3文件analyze无问题。部署12个故障演练通过；常规App构建通过。真实观察夹具在服务器自动停止后由宿主结束，不将其宣称为完整45分钟testWidgets PASS。

常规nearBoss包已用原签名install -r恢复，没有卸载或清数据；恢复后停在登录页，未完成常规入口自动恢复会话与付款后列表的二次核对。该项单列待排查，不能以观察夹具的登录/语音成功代替常规入口验收。本轮也没有在App后台再做第二笔付款，因此“后台付款后只补列表/未读、不补历史语音”的真实渠道场景仍待单独验证，已有coordinator隔离回归不等于这项现场验收。

证据目录：`docs/review/sse-stability-2026-11-14/`。


### 2026-11-14 常规入口续测检查点

用户确认先完成现有SSE收款闭环，暂不引入QoS1、JetStream及消费ACK。已在正常nearBoss页面通过现有166受保护测试配置填写robin邮箱登录，正常安全策略触发滑块验证；本次验证码动作确认已请求、尚未收到答复，未拖动、未绕过或关闭安全配置，登录尚未完成。没有继续创建或支付新订单。

新增真实正常入口验收脚本 `integration_test/payment_notification_live_closeout_test.dart`：直接启动main_onboarding_live，恢复真实会话，核对指定用户/门店、既有成功订单/未读消息；计划分别观察前台新付款原生语音完成，以及后台付款后恢复列表/未读且不增加语音完成次数。没有嵌入凭据、token、模拟支付或伪造通知。脚本APK编译成功，静态检查No issues found，尚未安装和执行，不能标为现场验收通过。后续新沙箱付款须在实际付款前取得相应条款/本次测试范围确认；前次单笔授权已完成，不视为无限次授权。

保留常规包与登录表单，未清App数据、未更改服务部署。旧会话无法恢复的原因仍未确证；需先完成正常登录，再验证强制结束/重启后的会话恢复，不能据本次安全验证认定恢复缺陷已修复。


### 2026-11-14 10:22 常规 App 闭环验收通过（替代前述待执行状态）

本会话获准完成当前滑块后，通过正常密码登录恢复 robin@neargo.ai；强制结束再启动，真实会话和目标门店均恢复。未清数据、未注入 token；旧会话失效的历史原因仍未确证，不能声称修复了未知登录缺陷。

新增现场集成测试直接运行 `main_onboarding_live.dart`，恢复真实安全存储与指定 USR/门店，使用实际 HTTP/SSE、消息列表和原生 TTS 状态；没有模拟付款或回调。用户在提交前另行确认最多两笔 NT$1 官方沙箱测试及条款。结果：

| 场景 | 沙箱支付单 | 实测结果 |
|---|---|---|
| App 前台 | PYO261114101752261 | 成功，原生 started=1/completed=1，对应消息未读 |
| App 后台（HOME，paused）付款后回到前台 | PYO261114102023262 | 成功，列表恢复、未读由1变3，恢复后观察30秒，started/completed仍为1，未补播 |

服务端只读核验：两单均 SANDBOX/SUCCEEDED、paidMinor=100；各1条收款 HTTP 通知 DELIVERED（首次 AUTO，HTTP200，分别67ms/24ms），各1条 APPLIED 回执与 SHOP receipt 的 hash/编号一致。各2条授权 USR/关联 MCH 消息和 stream 是两个正常收件人，非重复支付；消息保持未读。正常收款页实际显示 TWD3、3笔成功；完整交易列表还含既有待付款订单，不能把列表总数8误认作成功数。

现场集成测试最终 `All tests passed!`（1项），详见 `normal-closeout-result.json`、`normal-live-closeout.log` 与两份 `normal-*-db-proof.json`。原正常包保留数据恢复安装结果另记。未引入QoS1、JetStream或App消费ACK；未接系统后台通知，不承诺任意断网时长的可靠消费或生产SLA。本轮只完成模拟器常规流程与官方沙箱验收，无真实扣款。


#### 测试收尾修正：必须禁用 Flutter 自动卸载

本轮付款断言通过后，Flutter测试默认清理卸载了App，导致恢复常规包后登录数据不存在。已核对当前SDK `flutter_tools/lib/src/commands/test.dart` 中 `uninstall` 默认true，以及 `test/integration_test_device.dart:kill()` 调用卸载。Android包 firstInstallTime 随常规包重新安装重置。此前ADB保护wrapper未覆盖SDK实际使用的ADB路径（android/local.properties 指向Homebrew SDK），不能宣称该wrapper已保护数据。此为测试执行遗漏，不能归因为产品登录恢复bug；测试前常规App仅强制结束/重启的恢复验证独立有效。

后续在已有会话设备运行本集成测试，命令必须包含 `flutter test --no-uninstall integration_test/payment_notification_live_closeout_test.dart ...`；安装前后核对firstInstallTime，使用同签名 `adb install -r` 恢复常规包，不通过卸载/清数据切换。实际支付与语音断言结果仍为PASS，本轮重新登录收尾结果另记。

收尾只读核验：API仍为本轮b3da994752b77fcf5780612f49ae307b680b865bd8640dda6b69692dfefa0640；PAY现为96499ce1ce56456c1616590b5d55326f22dd64fd6168679435bd59773040a391，已不同于先前预检基线。本会话这次没有重发PAY或覆盖制品，来源/切换时间未核定，不声称PAY制品未变。受保护配置及三个静态指针核验均与原预检一致；详见normal-closeout-runtime.json、normal-closeout-live-sha.txt。常规包重新安装成功，重新登录遇到新的滑块，已请求本次确认，尚未操作；付款测试PASS与该收尾待办分开记录。


#### 10:30 最终收尾（此前登录待办已完成）

用户已确认本次新滑块操作；刷新过期挑战后，实际页面随后进入真实门店列表，未修改验证配置或注入凭据。重新选择目标店，常规App首页显示TWD3/3笔；再强制结束重启，仍恢复同一USR与门店。我的页实际未读徽标3，消息页实际展示10:20、10:19、08:45三条收款成功，均未点击标记已读。见 `normal-final-home-restarted.png`、`normal-final-unread-badge.png`、`normal-final-message-list.png`；最终状态 `normal-closeout-result.json`。常规APK已恢复安装，不留集成测试入口；代码与验收文件尚在本轮本地分支，未提交/推送。


## 2026-11-14 真机沙箱验收结果

用户明确同意本轮两笔 NT$1 官方蓝新沙箱付款及条款，已使用 2/2；不使用真实卡，不发生真实扣款。目标为 robin 的创势沙箱验收店，设备为 REDMI 2510DRK44C。

| 场景 | 支付单 | 结果 |
|---|---|---|
| 前台 | PYO261114112637264 | 真实 HTTP/SSE、原生 started=1/completed=1，对应消息未读 |
| 常规包后台后恢复 | PYO261114114049265 | 未读 4→5；同一进程的原生 started/completed 均为 0，历史收款未补播 |

后台活动日志记录 11:44:34 进入后台、11:44:48 付款成功、11:45:04 恢复前台。正常进程 PID 27163 保持不变；03:43:26Z 基线与 03:51:04Z 只读原生状态均为 0，覆盖恢复后 30 秒。通过 debug VM 调用已有 MethodChannel `status`，未修改计数、业务状态或凭据；付款期间已断开调试连接。窗口单帧未识别 launcher，后台事实以活动 pause/stop/resume 日志为准。

两单均为 SANDBOX/SUCCEEDED、paidMinor=100。各一条收款通知首次 AUTO 即 DELIVERED，HTTP 200（58ms/25ms）；APPLIED 与 SHOP receipt 的 hash/编号一致。USR 与关联 MCH 各一条授权消息和 stream，是两个收件人，不是重复付款。

真机常规收款页显示 TWD5／5 笔成功收款，完整列表还有待付款单；消息徽标和列表均为 5，未标记已读。常规 APK 已恢复，强制结束重启后用户、门店和 TWD5／5 笔仍恢复。

**自动化限制：** 两次管理式长驻集成夹具均未完整通过。后台后连接中断，随后系统记录 ADB 测试清理 force-stop；未找到原生崩溃证据，原 VM 断连根因尚未完全确定。不能把 `physical-live-closeout.log` 标为全套 PASS。前台证据来自真实夹具；后台业务场景由常规 APK、只读原生计数、活动日志及真实消息/订单独立验证。后续仍需完善该机型的长后台自动化运行方式。

详见 `physical-final-result.json`、`physical-normal-speech-*.json`、`physical-*-db-proof.json`、`physical-background-lifecycle.log` 及实际截图。私有调试 URL、认证码和服务凭据不入库。本轮未增加产品代码、未调整后台安全限制；QoS1、JetStream、消费 ACK 与系统后台推送仍不在范围内。
