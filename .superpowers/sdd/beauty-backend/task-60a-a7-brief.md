# Task60A-A7 已认领临客与真实资源替换组合验证
Status: IMPLEMENTATION_APPROVED after A6 product 9136d67c98ff24a6448cd009511e3685370d2dfb. 档位0；只补既有业务组合的真实集成证据与严格测试选择，不新增业务契约。唯一worktree /Users/robin/.codex/worktrees/beauty-data/ai-shop-codex，codex/beauty-foundation，实施BASE根给出。

## Owned/合同
产品仅新增 backend/shop-app/src/test/java/ai/neargo/shop/scenario/BeautyClaimedGuestResourceIT.java，修改 scripts/verify-checkout-payment.mjs 与 scripts/tests/checkout-payment-runner.test.mjs。允许本片report/evidence；不永久改生产Java、SQL、known/POM/package、其它测试/根文档。无subagents/stage/commit/push/merge/部署。下述唯一临时生产消融必须finally逐字恢复并证明；额外业务gap先给根事实，不扩scope。

新class extends CheckoutFlowFixture，@EnabledIfSystemProperty(named="beauty.source.test.native",matches="true")，嵌入真实beauty/merchant/member/resource提供方。默认retail测试编译不得直接依赖beauty-api/core类型：仅在反射边界用Class.forName/helper读取既有API，普通Java import/4空格/新行<=120/AOSP/Javadoc遵守项目规范。可借鉴BeautySourceCheckoutIT的primary checkoutClock测试配置与真实bean/dto反射，不直接继承其spy fixture；严禁BeautyReceptionIdentityIT的FaultConfiguration/ArrivalHoldFixture、mock业务Port/替换Bean、手写booking/预约结果伪造流程。仅clock selection与既有publication archive-only测试配置允许，不换来源/资源/身份/接待业务提供方。

精确三方法：
1. claimedGuestReplacementUsesCanonicalUserAndPreservesReceipts
2. revokedUsrBookingGrantCannotReplaceClaimedGuestAppointment
3. failedClaimedGuestReplacementPreservesOriginalResourceHold

严格runner：在beauty-source模式精确增加此class与三方法；原BeautySourceCheckoutIT默认及四方法、14支付class/原selector+和comma语义、无效输入前置拒绝、fresh真实XML非0零fail/error/skip、env-only凭据、全artifact脱敏、共享串行锁/ownUUID清理均保留。不得用Beauty前缀wildcard或把任意class归beauty。新class在payment模式必须拒绝，精确method选择/unknown选择行为补入原Node测试；不重写原脱敏/selector架构。

## 已核对接口与事实
- GuestRegistrationService.registerGuest/claimGuest，MemberCodeResolveService.issue/resolve（真实mc1→cr1），AppointmentService.previewAppointment(entity,store,actor,PreviewAppointmentReq)、createAppointment(...,CreateAppointmentReq,key)（内部已CONFIRM，无另一个公开confirm）。
- PreviewAppointmentReq(storeNo,customerRef,items,requestedAssignments,startAt,guestNo)；CreateAppointmentReq(previewNo,customerRef,sourceRefs,consentRef,guestNo)；AppointmentAssignmentReq(itemIndex,requirementNo,resourceNo,personnelNo)。
- ReceptionService.checkIn(...,CheckInReq(appointmentNo,ReceptionIdentityProof(customerRef,guestNo,claimToken),expectedRevision),key)；WorkOrderService.openWorkOrder(...,OpenWorkOrderReq(customerRef,storeNo,items,appointmentNo,guestNo,expectedRevision,assignments,startAt),key)。两种身份字段不可同时非空；开单沿用原guestNo且已CLAIMED，不能伪造customerRef。
- AppointmentPreparation是当前A1接受接口，不用已改名旧concrete Service。
- PersonnelService.createPersonnel(entity,store,actor,CreatePersonnelReq,key)真实创建人员/任职/资格和规范STAFF资源；ScheduleService.saveScheduleRule(...,SaveScheduleRuleReq,key)真实周排班。服务政策、定义、目录使用现有真实写Services/API。scripts/lib/beauty-bm03-runtime.mjs setupBusiness仅作数据输入参考，不运行旧部署脚本。
- CheckoutFlowFixture原actor是MCH owner；不能用它做撤权负例。额外创建真实非owner mch_account/user_no、mch_role+mch_store_role当店授权，主要业务动作明确DelegatedIdentity("MAIN","MERCHANT","USR",managerUserNo)。原owner仅配置前置，角色至少保留所需booking view/manage和customer管理权限，精确值读BizPerms，不裸*或伪造人员岗位为授权。MerchantMappers.OperatorAccessMapper当前实时按USR→mch_account映射及JSON role perms查询，AppointmentAccess实际走此owner Port。
- ResourceBookingServiceImpl.lockRoots先按personnelNo排序锁全部人员，再按resourceNo锁；kernel拒绝容量码精确CAPACITY_UNAVAILABLE。WorkOrderCoordinator.resumeAppointment对真实REPLACE结果REJECTED将reception op记REJECTED并抛ErrorCode.CONFLICT。canonical scope parent是LOCK IN SHARE MODE，不会串行排斥另一预约。
- mbr_guest_registration V537内有REGISTERED→CLAIMED、claimed_user_no、claim_* hashes/actor和加密token/contact，无独立guest receipt表。不要虚构receipt表；登记/claim返回与这些永久字段的摘要证明原历史。

## 三个真实用例
共用真实准备：自有UUID主体/门店/商品SKU与已发布目录/政策/定义（真实STAFF需求一份）；两个实际有效任职/技能人员及全日UTC周班表，资源来自createPersonnel而非写mch_resource假身份。选稳定未来UTC服务时间，容量STAFF=1。SQL只允许auth/goods前置及只读观测；人员/班表/预约/hold/签到/claim/工单均真实owner动作，不写mch_booking_*或bty当前安排/源决定作为测试种子。

1. 真实registerGuest→该GUEST预约preview/create得到CONFIRMED旧hold（原人员A）→真实claim到活动C账号→当前mc1 resolve为本非owner B-USR作用域cr1→真实checkIn→读最新expectedRevision→openWorkOrder仍给原guestNo但换到合法人员B/同有效时段。必须实际REPLACE，不能空assignments沿用旧hold。断言工单root.customer_ref USER:buyer、context.identity_kind USER/identity_ref buyer，真实一个工单/任务及正确安排；预约原GUEST历史与postclaim row/登记或claim receipt摘要不被开单重写；真实command.action REPLACE/statusSUCCEEDED，owner.current_hold为新hold、旧hold被替代，冻结plan/hash/目标资源/源fence/容量总数一致无双占。原开单key重放同workOrder/操作回执，不新REPLACE/工单/任务；原claim/登记重放仍合法同receipt。资金订单/支付不应新增，本用例不发起交易或证明执行完工。

2. 独立相同真实已认领/签到场景，保留真实非ownerUSR和BOOKING_VIEW/customer权限，撤销当店BOOKING_MANAGE（改实际role/grant，无owner bypass）。同样合法换人开单须ErrorCode.FORBIDDEN，不能只断言任意失败；旧CONFIRMED hold、owner current pointer、旧单位/计数、当前安排和guest/claim事实保留，无新工单/任务/REPLACE效果。证明查询仍可且当前grant确实已失效，不用失效session/错误body充当撤权。

3. 必须真实资源所有者REPLACE容量拒绝，而非仅在preview发现忙或mock返回。确定性门闩：按personnelNo排序选原人员A较小、目标B较大，在独立JDBC事务仅锁mch_personnel A行FOR UPDATE（不写业务事实）；异步真实openWorkOrder，轮询到其真实REPLACE ACCEPTED且bty_appointment_resource_intent.phase_code=1（已许可）后，另一个真实guest预约通过真实preview/create占据B同一窗口并CONFIRMED。主调用阻在第一个人员锁，不持B锁，第二预约不涉及A；释放A门闩后主REPLACE必须真实REJECTED/rejection_code=CAPACITY_UNAVAILABLE、开单CONFLICT。旧A hold/current安排/占用仍在、peerB hold合法在、无新当前hold/工单/任务，拒绝命令与来源/reception终态/栅栏对账可观测。不要固定root所有version必须不变（许可/恢复可推进版本）；原hold/plan/arrangement事实不可丢。所有线程/连接/锁finally释放、有限poll/timeouts，不用任意长sleep或spyBean；若实际读路径与此门闩不同，给根具体证据先调整。

不得日志打印mc1/cr1/guest claimToken/contact/session/internal凭据；对不可变receipt/加密字段比较摘要和业务投影，避免失败断言输出原敏感payload。

## TDD/最小验证
- [ ] 新class有限selector Node正例先RED（旧runner拒绝未知class是真实缺口），补精确名单后Node专属文件GREEN；旧业务组合若已正确可首次原生GREEN，不能制造假业务RED或用missingclass/编译失败算RED。
- [ ] 实际3case native：CHECKOUT_PAYMENT_TESTS=BeautyClaimedGuestResourceIT node scripts/verify-checkout-payment.mjs --beauty-source。freshXML恰三方法/非0/零fail,error,skip；日志/源码编译指纹/effects记录完整。若真实业务缺陷，停止扩大产品范围并交根精确owner/行为。
- [ ] 一次真实授权防线临时消融：仅 backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/mapper/MerchantMappers.java 的OperatorAccessMapper中 allowed表达式暂换为1 AS allowed（其他scope/actor SQL不动），运行新class#revokedUsrBookingGrantCannotReplaceClaimedGuestAppointment须真实断言RED。此临时Java不进入提交，finally字节恢复原SHA、确认Maven编译当前源而非旧bytecode；再同selector恢复GREEN。无需重复原3case同代码，报告原3绿与最终1绿明确分批，不声称另一次3全绿。
- [ ] 默认retail Maven shop-app -am test-compile（JDK21/既有离线缓存，-DskipTests）证明无beauty硬编译依赖；不是额外业务全套。Node及native受影响检查按真实源码版本记录，不反复不变套件。
- [ ] native仅cached mysql:9.7.2 --pull never、普通DISK64M、context colima-hxkey-tests/VM hxkey-tests、可用内存>=524288KiB，复用/tmp/codex-beauty-bm04-native.lock串行与env-only密钥/随机UUID/loopback/finally清自己资源，不停别人/不碰远端共享/不pull。只有此必要隔离开发测试，不是正式部署。严格沿A2 runner，资源/警告/清理记录。
- [ ] AOSP仅新Java全文件/改动hunks，格式变更须在最终验证前；manifest三产品+临时Java精确恢复/完整BASEdiff、实际命令输出/RED-GREEN/选择节点/真实DB证明/警告/环境失败单列，自审写task-60a-a7-report.md/evidence。根独立spec+quality后精确提交。

本片不实现BM05开工/执行/超时，不开放ManagedServiceCompletionPolicy受管理执行旁路，不新增P2-P4能力、不宣称BM04或部署整体完成。连续执行已授权，不重复询问用户。
