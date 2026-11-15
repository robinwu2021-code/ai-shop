# TDD-门店联系与独立平台主体

状态：已实现
关联需求：docs/requirements/门店注册到进件-流程节点与代码对齐.md N1/N7；本轮用户确认单店联系资料、独立平台经营主体、正式门店重挂。
创建：2026-11-15 · 最后更新：2026-11-15

## §0 对账一 · 需求 → 设计

| AC | 需求 | 落点 |
|---|---|---|
| AC1 | 门店管理员独立修改联系人和电话，可清空 | StoreSettingsService CONTACT、profile、POST /biz/store/settings/contact |
| AC2 | 旧 BASIC/HOURS 保存不覆盖联系人；过期 CONTACT 拒绝 | sectionVersions.CONTACT；同一 ownership 行锁下比较内容版本 |
| AC3 | 个人账号无选店即可独立建平台主体并读取自己名下零门店主体；MCH不读个人owner列表；不建门店、不审批、不绑定支付 | POST/GET /biz/entities；MerchantAdminPort.createPendingEntity；PENDING_LICENSE/verified=false |
| AC4 | 原键同内容重放、异内容冲突、丢响应可查询；当前owner权限有效 | mch_entity_creation_request；GET /biz/entities/create-results/{requestNo} |
| AC5 | 复用账号锁、主体额度、owner成员和FREE初始化；市场从账号取 | EntityCreationServiceImpl、MerchantPortImpl共用待补证主体初始化 |
| AC6 | 正式重挂保留占位、无历史、同市场、目标审核及支付围栏 | 复用 BizStoreEntityController、StoreRelinkPrecheck、StoreEntityClaimServiceImpl，不扩宽业务准入 |

孤立项：无。

## §1 现状与影响面

已有 StoreSettingsService 的 BASIC/HOURS 各有内容版本，ownership.lockAndCheck 在事务中串行归属变化。profile统一从 mch_store读取。新增CONTACT只更新本节字段。
平台主体为 mch_entity/entityNo，与 mch_operating_entity/operatingEntityId不同。独立主体为零门店；既有列表和显式证照提交支持该状态。快速开店仅复用有默认门店的待补证主体，避免将独立主体改成建店意图或空指针。StoreCreationAccess 同步仅将有门店的占位视为可恢复快速开店。
已搜索 reference 与既有组织/创建服务；永久entity-only回执无适用存量表，新增单表。已有正式重挂执行事务与Pay reservation继续使用。

## §2 方案与实施计划

端点：GET /biz/store/settings profile新增contactName/contactPhone、CONTACT版本与EDIT_CONTACT；POST /biz/store/settings/contact {expectedVersion,contactName,contactPhone}返回同View。
联系人非null、允许空串、trim，长度64/32；电话框架Pattern允许常见国际数字及空格、+、括号、点、横线。STORE_ADMIN权限与BASIC一致。
POST /biz/entities {requestNo,name,legalForm?}；requestNo `[A-Za-z0-9_-]{1,64}`，name必填≤64；legalForm经现有主数据canonicalSubject识别；不接受市场。返回 {requestNo,entity:EntityVO,replayed}。查询同形状，当前活跃owner才能读取，未知键404。
创建及GET /biz/entities列表接口走ACCOUNT路由，无选店头；只接受MERCHANT/USR。列表沿用myEntities按当前用户的活跃owner成员过滤，不接受调用方指定owner，不以当前选店STORE_ADMIN作为个人主体管理资格。usr_account正常账号行锁串行主体额度（与商业建店同一账号行锁）；市场来自锁住的账号。永久回执唯一键(tenant_no,user_no,request_no)，原始输入内容hash不可变，request_no使用utf8mb4_bin区分大小写；创建/回执同事务。
迁移：V674__store_contact_and_entity_creation.sql：mch_store nullable contact_name VARCHAR(64)、contact_phone VARCHAR(32)；mch_entity_creation_request永久回执。历史迁移不修改。

| 动作 | 模块/完整类型或路径 | 说明 |
|---|---|---|
| 修改 | shop-merchant / ai.neargo.shop.merchant.entity.MchStore、dto.StoreProfileVO、service.impl.MerchantStoreServiceImpl、StoreSettingsServiceImpl及接口 | 联系资料读写 |
| 修改 | shop-app / ai.neargo.shop.portal.biz.BizStoreSettingsController、BizEntityController | HTTP校验、权限、review组装 |
| 新增 | shop-merchant / ai.neargo.shop.merchant.service.EntityCreationService、service.impl.EntityCreationServiceImpl | 永久幂等回执和账号/当前归属 |
| 修改 | shop-base / ai.neargo.shop.spi.user.MerchantAdminPort；shop-merchant / ai.neargo.shop.merchant.port.MerchantPortImpl | 复用主体初始化、额度、owner、FREE；独立主体不建店 |
| 修改 | shop-merchant / service.impl.StoreCreationAccessServiceImpl | 无门店主体不是快速开店恢复锚点 |
| 修改 | commerce CommerceRoutePolicy；shop-app ShopCommerceEndContextAdapter | 精确ACCOUNT路由，创建/查询无选店 |
| 新增/修改 | shop-app迁移、定向测试、docs/api/platform-entity-store-contact.openapi.json | 契约与验收 |

## §5 对账三 · 实现 → 需求

| AC | 定向用例 |
|---|---|
| AC1/AC2 | StoreSettingsMysqlTest.contactHasIndependentVersionAndCanClear；concurrentContactHasOneWinner；StoreSettingsVersionTest |
| AC3/AC4/AC5 | EntityCreationMysqlTest.createsIndependentPendingEntityAndRecoversReceipt；sameKeyConflictAndCurrentOwnerRevocation；concurrentRequestCreatesOnce；failureRollsBackReceipt；concurrentDifferentRequestsRetainExistingEntityQuota；PlatformEntityCreationFlowTest.personalAccountCreatesAndRecoversWithoutSelectedStore；validationRejectsBadKeyAndUnknownLegalFormBeforeCreating |
| AC6 | StoreRelinkPrecheckFlowTest（已有准入/支付拒绝用例） |

验证（增量执行，仅运行本任务定向类）：

- 第一批：`mvn -o -f backend/pom.xml -pl shop-app -am test -Dtest=StoreSettingsVersionTest,StoreSettingsMysqlTest,EntityCreationMysqlTest,StoreOpeningRoutePolicyTest,StoreRelinkPrecheckFlowTest -Dsurefire.failIfNoSpecifiedTests=false`。真实输出：StoreOpeningRoutePolicyTest 1/1、StoreSettingsMysqlTest 4/4、StoreSettingsVersionTest 1/1、StoreRelinkPrecheckFlowTest 5/5通过；EntityCreationMysqlTest因测试夹具mapper早于GlobalConfig初始化，created_at未填而失败；已修正夹具装配。
- 第二批原生主体测试5/5通过；新增HTTP场景发现H2不支持旧额度助手硬编码FOR SHARE。复用已有数据库锁方言选择，MySQL仍FOR SHARE，H2使用FOR UPDATE。
- 第三批 `-Dtest=EntityCreationMysqlTest,PlatformEntityCreationFlowTest`：HTTP 2/2通过；主体测试partial mock未执行既有datasource初始化导致锁字符串null；已为夹具调用真实初始化方法，只重跑该测试类。
- 最终原生主体测试：`mvn -o -f backend/pom.xml -pl shop-app -am test -Dtest=EntityCreationMysqlTest -Dsurefire.failIfNoSpecifiedTests=false`：`Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`，退出0。全部本次定向类合计18个用例通过（复用未受后续变更影响的既有有效结果）。
- SQL方言脚本退出0；报告的16处存量命中均不在本次修改文件内。新迁移在原生MySQL 9 UUID临时库实际执行；临时库测试结束清理。`git diff --check`通过。

日志：`/tmp/v40-backend-targeted-tests.log`、`/tmp/v40-entity-targeted-tests.log`、`/tmp/v40-entity-targeted-tests-final.log`、`/tmp/v40-entity-mysql-final.log`。未部署166或其他环境，未提交，未运行全量测试。


## §6 对账二 · 设计 → 实现

未提交，因此以工作区diff和新增文件清单对账；没有git show提交统计。§2各模块均有对应实现。

- `backend/commerce/commerce-spring-boot-starter/src/main/java/ai/neargo/commerce/starter/security/CommerceRoutePolicy.java`
- `backend/commerce/commerce-spring-boot-starter/src/test/java/ai/neargo/commerce/starter/security/StoreOpeningRoutePolicyTest.java`
- `backend/shop-app/src/main/java/ai/neargo/shop/commercebridge/ShopCommerceEndContextAdapter.java`
- `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizEntityController.java`
- `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizStoreSettingsController.java`
- `backend/shop-app/src/test/java/ai/neargo/shop/commercebridge/CommerceAccessSecurityFlowTest.java`
- `backend/shop-app/src/test/java/ai/neargo/shop/merchant/StoreSettingsMysqlTest.java`
- `backend/shop-app/src/test/resources/schema-test.sql`
- `backend/shop-base/src/main/java/ai/neargo/shop/spi/user/MerchantAdminPort.java`
- `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/dto/StoreProfileVO.java`
- `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/entity/MchStore.java`
- `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/port/MerchantPortImpl.java`
- `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/service/StoreSettingsService.java`
- `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/service/impl/MerchantStoreServiceImpl.java`
- `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/service/impl/StoreCreationAccessServiceImpl.java`
- `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/service/impl/StoreSettingsServiceImpl.java`
- `backend/shop-app/src/main/resources/db/migration/V674__store_contact_and_entity_creation.sql`
- `backend/shop-app/src/test/java/ai/neargo/shop/merchant/EntityCreationMysqlTest.java`
- `backend/shop-app/src/test/java/ai/neargo/shop/scenario/PlatformEntityCreationFlowTest.java`
- `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/service/EntityCreationService.java`
- `backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/service/impl/EntityCreationServiceImpl.java`
- `docs/api/platform-entity-store-contact.openapi.json`
- `docs/technical/TDD-门店联系与独立平台主体.md`

偏差说明：

- 复用现有数据源方言锁选择用于额度当前读，是HTTP/H2定向用例暴露的实际兼容问题；MySQL锁语义保持一致。
- CommerceAccessSecurityFlowTest既有手工装配BizEntityController，补充新增构造器依赖的mock，未扩展其业务行为。
- schema-test.sql同步contact列与新回执表；原生夹具从迁移前门店结构应用V674，验证新迁移，而非只在H2验证。
- 既有正式重挂执行已完整：创建永久claim → 当前事实复查 → Pay prepare预约与订阅围栏 → 原子归属变更 → Pay完成/恢复。保留执行开关、source PENDING_LICENSE、无交易/库存/组织/经营配置历史、目标ACTIVE owner同市场及目标门店额度。预检allowed/executionReady、storeVersion及blockers作为客户端条件，执行方仍复查。未改这些业务规则或开关默认值。

复核补齐（2026-11-15）：发现零门店独立主体创建后，GET /biz/entities仍依赖选店STORE_ADMIN，导致不能重新进入列表。改为ACCOUNT路由和个人USR校验，保留现有当前用户活跃owner过滤；MCH身份明确拒绝。PlatformEntityCreationFlowTest在创建后无选店GET列表断言实体号/storeCount=0，并以真实MCH会话断言403、另一USR列表为空。只补跑HTTP场景与路由测试，不重跑未受影响CONTACT、重挂或原生实体测试。

复核补齐验证：`mvn -o -f backend/pom.xml -pl shop-app -am test -Dtest=PlatformEntityCreationFlowTest,StoreOpeningRoutePolicyTest -Dsurefire.failIfNoSpecifiedTests=false`：HTTP 2/2、路由1/1通过，均0失败/0错误/0跳过，`BUILD SUCCESS`；日志 `/tmp/v40-entity-list-targeted.log`。未重跑CONTACT、预检、原生创建测试，未部署或提交。
