#### Task 54: 共用临客登记认领与本人身份核验

**Files与归属：** shop-base/spi/member新增GuestRegistrationPort/GuestRegistrationInternalApi；CustomerIdentityPort/InternalApi增量本人核验；shop-core/member新增MbrGuestRegistration、MemberGuestRegistrationMapper、MemberGuestRegistrationService/Impl、MemberGuestCrypto及Port实现，扩展MemberCodeResolveService/Impl复用现有会员码；shop-app/portal/internal新增InternalGuestRegistrationController、扩展InternalCustomerIdentityController；beauty-channel新增RemoteGuestRegistrationPort及现有RemoteCustomerIdentityPort/owner接线。追加平台V537__shared_guest_registration.sql（已在本任务原生库执行，冻结字节），新增V538__member_reference_actor_namespace.sql给原mbr_identity_ticket追加可空actor_realm/actor_kind，按需同步当前schema-test夹具及生成字典；原迁移不动。测试/原生脚本按下述实际覆盖新增，配置/错误码/三语同步。

**Interfaces（唯一契约shop-base，不导入其他域实体）：**
- GuestRegistrationPort.Scope(String entityNo,String storeNo,DelegatedIdentity identity)。
- Registration register(Scope scope,RegisterCommand command)；RegisterCommand(String contact,String displayName,boolean consent,String idempotencyKey)；Registration(String guestNo,String claimToken,String state,long revision)。
- GuestFact get(Scope scope,String guestNo)；GuestFact verify(Scope scope,String guestNo,String claimToken)；GuestFact(String guestNo,String state,String userNo,long revision)。
- Claim claim(Scope scope,String guestNo,ClaimCommand command)；ClaimCommand(String verifiedIdentity,String claimToken,String idempotencyKey)；Claim(String guestNo,String userNo,long revision)。
- CustomerIdentityPort增加default ResolvedCustomer verifyConsumerProof(EntitlementContext context,String memberCode)，默认明确不支持，兼容原单抽象方法；真实member owner覆盖。MemberCodeResolveService增加ResolvedCustomer requireConsumerProof(String tenantNo,String userNo,String memberCode)。另增ResolvedCustomer requireGuestClaimReference(MemberCodeScope scope,String customerRef)，新guest认领必须验证票据的当前actor realm/kind；发行新cr1时从真实当前MERCHANT身份绑定两列，旧无namespace票据对新guest认领关闭，旧requireReference合同保持兼容。

**身份/数据：** 公共临客归member，不新增bty_guest。实体沿BaseEntity，含entity/store/guestNo、登记actorNo/Realm/Kind、原keyHash/requestHash、加密contact、displayName、claimTokenHash/密文、REGISTERED/CLAIMED状态和claimedUserNo/claimedAt；原键唯一索引包含身份命名空间与店，认领同guest根锁。含contact/displayName的登记requestHash采用配置密钥用途隔离的HMAC指纹，不以无密钥SHA暴露低熵联系资料猜测oracle；随机token/key摘要可用SHA。contact最长128且可空，displayName最长80，consent必须true；不按contact自动创建账号/会员/订单。claimToken服务端SecureRandom32字节base64url，哈希比对、密文用于同键重放，禁止原凭证出现在日志/outbox/幂等JSON，相关DTO.toString脱敏。
MemberGuestCrypto采用AES-GCM随机IV和tenant/entity/store/guestNo/purpose关联数据，配置shop.member.guest.credential-key为32字节base64，无默认密钥；未配时仅本能力关闭，不破坏默认零售启动。MEMBER_GUEST_UNAVAILABLE=40042 / err.member.guest_unavailable（加前核对唯一），三语和InternalServiceResponses的503映射一起补齐。

owner内部校验当前SecurityUtils完整身份等于Scope.identity，以及真实门店归属/当前当店BizPerms.CUSTOMER权限。机器令牌不赋权。原登记actor仅限定登记幂等重放，不永久占有guest；同entity/store另一当前有CUSTOMER权限的员工可get/verify/claim，使用自己的cr1与原guest凭证，禁止冒充原actor。claim同时校验原guest凭证与当前actor/store绑定cr1引用解析的活动真实用户；绑定后不能改为另一个人，重复原键回原结果、改参冲突。已提交认领的精确原key/hash/完整claim actor与原secret可在cr1自然过期后重放持久结果，仍先重核当前权限及已认领用户活动（外域事实锁外，锁内核相同user/revision）；新认领必须严格有效cr1，不能把重放改为新授权。get/verify不泄露contact/密文。本人proof只认原MEMBER_CODE短票据，同租户/当前CONSUMER-USR及活动用户，不接受登录令牌/手机号/任意字符串。身份核验不提供支付授权；现有BTG仅匿名引用，兼容在55详定，不凭格式伪造公共guest。

**协议：** register/get/verify/claim及verify-consumer-proof强类型完整identity/scope/请求回显核验；错token在owner之前拒绝，DelegatedIdentityContext finally恢复。沿真实400/401/403/404/409/503，HTTP200错误对象不能当事实；旧协议兼容。remote-owner形态唯一装配，embedded/default无重复provider。

- [ ] RED：MemberGuestRegistrationIT及协议测试覆盖登记幂等/改参/跨realm、跨店/他人/撤权、真实mc1过期/他人/伪造拒绝；原生并发认领只有一个真实用户胜出，回滚不新增账号/订单；缺密钥明确不可用、重启后原键重放、存储不含原始contact/token、密文篡改拒绝。编译/环境错误不算行为RED。
- [ ] 实现上述合同、owner、迁移、加密和远程协议；只用自建隔离缓存MySQL8.4、localhost端口、--pull never，finally清理，不并行启动多个native库。
- [ ] GREEN：目标native、真实TCP及全局Advice/当前identity；原会员码流程、三语/架构/SQL方言和beauty装配按影响回归。消融身份或唯一认领约束使反例变红再还原。
- [ ] 精确提交本任务文件及task-54-report.md（列H2/native/TCP/fixture边界），controller主TDD/progress不提交；独立spec/quality审查通过后进入55。54不是BM04模块准出。

