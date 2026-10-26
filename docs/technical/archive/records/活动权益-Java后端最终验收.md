# 活动、卡券、储值 Java 后端最终验收

状态：发券并发连接池问题已修复并通过4项定向验证；遵照用户要求，不重复运行已通过的4019项全量回归。后端提交/部署及B端页面开发联调进行中。

## 范围与依据

按[独立任务表](../../任务表-活动卡券储值-Java后端.md)、[模块化设计](../../TDD-活动卡券储值-模块化系统设计.md)、[数据库设计](../../TDD-活动卡券储值-数据库设计.md)、[API契约](../../TDD-活动卡券储值-API契约.md)、[代码结构与类清单](../../TDD-活动卡券储值-代码结构与类清单.md)验收。core拥有活动/资格/预算与真实订单源决定；pay拥有权益/发行/核销/储值账。商家实收，平台管理权益与账单；无平台收款托管或新储值/付费卡线上退款。

## 功能矩阵到实际验证

下表是实际测试类与关键方法映射；最终通过状态以本文件最后的实际回归记录为准，不把测试文件存在当作通过。

| 功能 | 实际后端验证 |
|---|---|
| U01 预置活动 | `PresetCapabilityAcceptanceTest.allFortyActivitiesAndSixPaidProductsHaveKnownRequirementsAndTruthfulAvailability`；46项默认关闭，缺能力明确限制 |
| U02 预置付费产品 | `PaidCardActivityCapabilityEvidenceTest`、`CardProductBindingFlowTest` |
| U03 活动草稿/发布 | `UnifiedActivityManagementFlowTest`、`ActivityVersionManagementTest` |
| U04 活动暂停/结束 | `UnifiedActivityManagementFlowTest`、`UnifiedActivityHttpContractTest` |
| U05 储值档位/范围 | `StoredValueActivityCapabilityEvidenceTest`、`BeautyStoredValueLifecycleFlowTest` |
| U06 资格/预算预留 | `ActivityParticipationTransactionTest`、`ActivityParticipationServiceImplTest` |
| U07 券/兑换权益模板 | `EntitlementCatalogFlowTest`、`EntitlementTemplateHttpFlowTest` |
| U08 次卡/月卡规则 | `EntitlementValidityPolicyTest`、`PaidCardCheckoutFlowTest` |
| U09 发行控制 | `EntitlementIssuePolicyFlowTest`、`LegacyCouponMigratedIssuanceFlowTest` |
| U10 定向发放/跳过 | `EntitlementBatchHttpFlowTest.createFreezesRealAudienceAndDurableDispatchRecoversWithoutRepeatingIssuance` |
| U11 本人领取/券包 | `LegacyCouponMigratedIssuanceFlowTest.originalConsumerClaimOnlyIssuesToTheActualClaimantAndUsesTheSharedPayAccount` |
| U12 先看后核 | `EntitlementServiceUseHttpFlowTest.memberOrOptionalLocatorStillRequiresConsentAndProducesOneRecord` |
| U13 订单用券 | `LegacyCouponMigratedIssuanceFlowTest.newlyClaimedPayCouponCompletesTheRealOriginalCheckoutAndReportsItsActualUsage`；`EntitlementCheckoutHttpFlowTest` |
| U14 售卡/幂等发行 | `PaidCardCheckoutFlowTest.realCheckoutFreezesFourRightsIssuesOnceAndConsumesViaMemberCode` |
| U15 月卡起算 | `EntitlementValidityPolicyTest`、`PaidCardTwoProcessAcceptanceTest` |
| U16 次数/日限 | `PaidCardCheckoutFlowTest`参数化COUNT、PERIOD_COUNT、PERIOD_DAILY；`EntitlementUseNativeMysqlTest` |
| U17 储值/实收 | `BeautyStoredValueLifecycleFlowTest.publishedBeautyPresetReceivesCreditsConsumesAndKeepsZeroBalanceStoreWithClearRecord` |
| U18 储值账务 | `StoredValueCreditFlowTest`、`StoredValueCreditNativeMysqlTest` |
| U19 储值核销 | `StoredValueConsumptionFlowTest`、`StoredValueConsumptionHttpFlowTest` |
| U20 门店目录 | `StoredValueConsumptionFlowTest`、`BeautyStoredValueLifecycleFlowTest`；有成功储值历史，最大核销/储值时间倒序，耗尽保留 |
| U21 核销明细 | `EntitlementConsumerQueryHttpFlowTest.fullHistoryPagesPastTwentyWithStableTiesAndBoundMemberAndAssetCursors`；归档旧历史不造余额 |
| U22 本人授权 | `EntitlementAuthorizationPersistenceFlowTest`、`EntitlementConsumerQueryHttpFlowTest` |
| U23 团期/赠品兼容 | `ActivityGroupBuyTest`、`ComboActivityFlowTest`、`PresetCapabilityAcceptanceTest` |
| U24 积分/资金门禁 | `PresetCapabilityAcceptanceTest`、`ActivityPresetExecutionPolicyTest`；不扩充资金能力 |
| U25 通知 | `EntitlementNotificationFlowTest`提交后、去重、收件人和仅消息重试；`EntitlementDeadLetterFlowTest` |
| U26 营销效果 | `EntitlementMarketingEffectSubscriberTest`、`ActivityMarketingSummaryServiceTest` |
| U27 异常/对账/恢复 | `OpsEntitlementExceptionListFlowTest`、`OpsEntitlementReconciliationApiTest`、`LegacyCouponCheckoutRecoveryFlowTest` |
| U28 运营权限 | `OpsEntitlementApiTest`、`OpsEntitlementLifecycleApiTest`、`EntitlementApiContractCoverageTest` |
| U29 旧券/迁移 | `LegacyMktCouponCompatibilityFlowTest`、`LegacyCouponMigrationTwoProcessAcceptanceTest`、`LegacyCouponMigrationImportNativeMysqlTest` |
| U30 退款边界 | `LegacyCouponHistoricalMigrationFlowTest`部分退不恢复/整根全退恢复一次；公开契约无新储值/付费卡线上退款 |

## 真实数据库与部署证据

- MariaDB 11.4：`scripts/verify-entitlement-schema-upgrade.mjs`，完整实际Flyway含Java迁移；新建397份至V474，V424增量50份至V474；269张表/列/索引一致，原券不改，余额/owner/资金账未制造，重复migrate/validate成功。日志`/tmp/ent-schema-flyway-native-fast.log`，3项通过无跳过。
- MySQL 9.7.2：隔离原生生产Mapper/Writer财务约束验证；迁入及历史回补各20并发只产生一次账/回执，日志`/tmp/ent-historical-native.log`；原ORDER券占用/确认/回补日志`/tmp/ent-usage-complete-native.log`。这些Writer测试的Core事实为明确边界夹具，不冒充真实业务源。
- 双JVM/双库：`scripts/verify-entitlement-two-process.mjs`，独立pay运行classpath无core/app/merchant；美业储值、三类售卡和加密源暂存5项已执行，新旧券迁移/下单/历史退回2项已执行。首轮测试计数与开关夹具修正后的定向日志`/tmp/ent-legacy-dual-targeted3.log`，4项通过；含MKT晚期旧程序引用阻断。
- 基本回归：`/tmp/ent-mkt-program-gate-green.log`22模块67项；`/tmp/ent-legacy-issue-usage-effects-green.log`22模块50项。最新源码仍需以下全量验收记录覆盖。

以上均为一次性容器、回环端口或本地测试库；未访问生产数据，未部署，未开启业务开关。H2双进程验证和原生数据库验证分别记录，不混为同一种证据。

## 迁移与回退边界

1. 盘点真实MKT/PMT源；仅MAIN/CNY/MERCHANT、可证明规则/额度与历史的来源进入初始切换。旧邀请有礼或派生COUPON活动仍引用的券须先转换为统一活动，报告`LEGACY_PROGRAM_CONVERSION_REQUIRED`。
2. Core根进入DRAINING、排空原交易、FROZEN；完整清单绑定水位、版本、真实来源与当前引用事实。敏感原明细加密暂存；同号只在有完整等价证明时认领，不按号猜测合并。
3. 固定目标计划仍无余额、发卡或owner切换。Core永久决定之前可以按原栅栏协议ABORT；旧写和新绑定均受根锁约束。
4. Core持久COMMIT_DECIDED之后不回退到旧写者。Pay按固定原号原子导入模板/历史规则/实例余量/发行与个人基线/原基线账/owner/回执，失败按原计划向前恢复；已经接受的原号不因新功能开关关闭而失联。
5. 迁入发行暂停，原历史不发送新到账通知，不补造原现金收款。显式复核后恢复未来发行；保存新规则不更改原已得权益。
6. 消费/取消/历史合法退回分别持久化源决定、pay权益账和Core确认；未知不释放、不换号重扣。可重复原号查询、恢复，禁止SQL调平或双写原券表。
7. 应用版本回退必须仍识别永久来源栅栏；已切换来源不能启用不识别新owner的旧发行/旧核销程序。恢复以向前补偿和原号续跑为准，不设计财务数据逆向删除。

## 本轮裁决

- 旧Fission发奖不伪装本人CENTER领取，不在Core持锁事务远调pay；先转换旧活动引用再切换券。代价是这些来源暂不进入初始迁移，保持原奖励可用。
- 冻结SQL不编辑。MariaDB的DROP CHECK仅由Flyway专用连接在发送DDL时适配为DROP CONSTRAINT；MySQL原语句保留，普通业务连接不包裹；迁移占位符替换关闭，仓库SQL无业务占位符，仅旧注释含Spring字面表达式。新增业务SQL占位符需显式设计，不在本次偷偷引入。
- 大参考数据演练仅在一次性库启用JDBC批量重写以缩短耗时；完整迁移仍执行，未绕过Java迁移、改变生产参数或历史脚本校验和。

## 最终回归与审查

全量回归及审查修复已完成，实际汇总见末尾最终结果；以下保留首轮问题与逐批复验历史。

### N12首轮问题与定向复验

首轮完整后端执行4017项，22失败/42异常/18条件跳过（`/tmp/ent-final-backend-regression.log`），不是通过证据。已修复：重复H2上下文初始化导致IAM/类目种子部分回放、旧支付回调mock缺少新增业务接口、原退款通知缺真实Core来源fixture、权限断言未含新储值码及端点分类、DDL列/约束/注释解析、规范业务号、明确非媒体业务编号；生产权限未放宽，旧SQL未改。

结构整改后22模块66项通过（`/tmp/ent-final-architecture-green5.log`，含全部16项架构规则和38项支付HTTP契约）；SourceFence/历史及发放业务均通过。最新原生MySQL历史迁入/20并发回补1项、原券占用/确认/回补/取消/并发1项，均无跳过（`/tmp/ent-final-historical-native.log`、`/tmp/ent-final-coupon-use-native.log`）。双JVM/独立库7场景无跳过通过（`/tmp/ent-final-two-process.log`）。真实Handler/Jackson契约3项通过，公开60操作106DTO（`/tmp/ent-final-contract-extract.log`）；C消费明细的主体必选、门店可选，匿名探针提供合法参数后严格验证401。

当时剩余：最终整体回归、独立整体审查及其必要修复、状态闭合与远端推送。冻结V449脚本保留原末尾空行，这是已知格式minor，不能通过改历史脚本消除。

### 独立整体审查（fa1c1d60a）

只读审查`7a3449fa8..fa1c1d60a`的财务Writer、原订单与退回协调、迁移归属、生命周期、恢复、身份权限及部署适配。未确认Critical；确认两项Important：主体级储值记录遗漏门店时底层仍拒绝、旧券耗尽后冻结解冻再退回可能恢复次数但不恢复可用状态。进入一次修复批次，以实际失败用例及复验关闭。

审查未判定事项及裁决：

- 生产发布及生产规模性能：仅离线验收，不据测试宣称生产性能；代价是发布前仍需容量验证。
- 新储值/付费卡线上退款和平台资金托管：维持用户明确排除的边界、商家收款；代价是没有在线自助退款。
- 历史日终重建：对账页保持当前快照、业务日仅归因；代价是历史时点余额需另行设计。
- 不支持的初始迁移来源及旧活动引用：保留门禁和转换要求；代价是这些来源暂沿用原路径。
- 前端页面：本轮优先Java后端，保留契约和原型；代价是新完整页面仍需后续接入。

### 审查修复定向结果

两项Important均已RED→GREEN：主体内所有门店消费记录支持省略门店，并验证首/后页、相同时间稳定顺序、本人/主体/门店游标不能互换、HTTP和私有远程DTO；旧券最后一次使用→真实冻结/解冻→原订单退回→新订单再次报价/预占成功，原号退回重放不重复加次，既有冻结保护保持。`/tmp/ent-final-review-green2.log`22模块91项通过（含全部16架构规则、38支付HTTP、5迁移暂存及15售卡承诺）。MySQL真实金融Mapper并发/确认/回补/取消复验1项无跳过通过（`/tmp/ent-final-review-native.log`）。

共享/运营守卫1274条断言：运营722全通过，共享552保留16条既有基线失败，无新增或欠账增长（`/tmp/ent-final-shared-guards2.log`）；没有扩大基线。生成器4项通过（`/tmp/ent-final-generators-unit2.log`），DDL按顶层字段解析，约束续行不再误作列。新来源绑定实体与售卡确认字段已补齐。最终整体回归仍待执行，不以定向结果替代。

## 实施裁决完整留档（按发生顺序）

以下保留各阶段当时的决策与代价。阶段性限制以之后的实现和最终验收结果为准。

- Ruling: each axis scan page remains an independent current snapshot; bizDate is attribution, not historic day-end replay — matches the existing RIGHTS implementation and avoids claiming unimplemented temporal reconstruction — cost if wrong: historical balance reconstruction needs a separate design.
- Ruling: first-implementation O07 response is FreezeView with revision instead of draft EntitlementDetail; add read-only lifecycle state — operators need a trusted revision before their first CAS write — cost if wrong: an unimplemented prototype response shape needs updating; no existing live caller is changed.
- Ruling: owner lookup is advisory routing metadata, never a cross-database write lease; no production legacy router activation based solely on a successful registry read — source freezing/draining and atomic import/cutover still belong to JB049/050 — cost if wrong: migration activation remains gated until that protocol is implemented.
- Ruling: first expiry reminder is one INAPP within the final24h for usable ACTIVE assets;identity includes actual expiry,so rescan/restart dedups and changed window may notify again — no prior timing was fixed — cost if wrong: later configurable lead times need a versioned reminder policy. No financial mutations or external notifications in this job.
- N10 source fence refinement reviewed inline against existing TDD migration and source-decision invariants. Ruling: use template-local roots and DRAINING/FROZEN before any import decision,allow original releases only during DRAINING;never treat an owner read as a cross-DB write lease — V232 overlaps and old markUsed-at-create demand a real source barrier — cost if wrong: unsupported templates remain CORE_LEGACY and migration throughput is template-bounded,not silently split. Existing user-approved continuous plan authorizes this implementation refinement;no new production cutover authorization inferred.
- Ruling: OUTBOX_DELIVERY recovery queues the same immutable message only; action DONE means queued, exception PROCESSING remains until actual SENT. Corrupt payloads cannot establish merchant scope, so only actual ALL operators receive safe metadata and no automatic payload mutation/retry — prevents duplicate financial commands and invented ownership.
- Ruling: freeze/export must use a fresh independent RR transaction,never the caller's earlier consistent-read snapshot — actual outer-RR/concurrent-source-change test accepted stale facts before fix (/tmp/ent-frozen-snapshot-isolation-red.log),rejects now — callers must enter migration coordination before holding source/financial locks. Original requireWritable stays MANDATORY.
- Ruling: S03 complete source/provenance inventory precedes S02 durable staging because visible-only evidence must not be certified as a complete import manifest. No change to the final import/cutover acceptance.
- Ruling: staging orchestrator rejects every ambient transaction rather than merely suspending a pay transaction,so no old financial locks remain held during source callback. No import/ownership transition is offered;visible V1 evidence stays STAGED with importReady=false and must not be promoted by editing an old row.
- Ruling: keep schemaVersion1 as wire envelope and version the payload with manifestVersion2 plus a separate stage-manifest URI;old clients/keys cannot accidentally reinterpret V1 as complete. VISIBLE_V1 watermark does not sign tombstones;durable source commit must revalidate the complete contentHash under the source root lock. Every staged mode remains importReady=false. No asset/owner activation implemented or performed.
- Ruling: normalization preserves actual old ORDER allocation (scope ALL/minQty null), exact basis points and original per-instance dates, retaining raw configuration encrypted and reporting differences. Source PAUSED/ENDED/STORE_CODE, missing money/currency/history/finite dates, counter mismatch and V232 collisions remain blocked. Historical expired definitions get structural validation only. Deleted rows stay archival. Report contains no consumer identity/bearer code and importReady is alwaysfalse.
- Ruling: explicit audited recognition is identity declaration,not source/target authority. Strict unused/no-history/equal facts only; PMTcanonical,shadow remainsBLOCKED ALIAS_SHADOW_SOURCE. Both current authenticated fullsources and fixed-comparedAt hash revalidated eachcanonical assessment; new sourcechange/abort/additionalcollision blocks. Raw original facts never rewritten. Opaque metadata only,no customer/code in DTO. Alias writer uses sorted individual locking reads(no optimizer-order assumption),no RR membership scan before insert,uniqueconflict->Biz409 and full command rollback. Post-hardening /tmp/ent-migration-alias-locks.log22modules56tests allgreen; nativererun currentwriterpassed. Internal pay77/core12/public58/106DTO;docs70,SQL/contracts/diff pass.
- Ruling: core source journal is durable intent/decision authority, not a new public purchase API. It may accept only trusted core pricing facts, and actual original OrderService must lock/validate and commit in the same transaction; merely persisting a staged intent does not complete old checkout integration. ABORT/root-row exclusion prevents late creates; lookup unknown=>404, undecided envelope=>200, unavailable=>503.
- Ruling: refund.return-coupon is checked when creating a new committed reversal; already-recorded reversal completes/replays even if the flag closes. AFTER_SALE_REFUNDED is re-read after commit in fresh core snapshot so final concurrent sibling completion is observed; this preserves old whole-order closure including actual recorded POINTS compensation, without labelling points as cash. No pay under core locks.
- Ruling: ent_usage.operation_no 保留真实旧券核销 operationNo，新增 protocol/legacy_instance_no 区分来源，不伪造 ent_use_intent；同一真实操作只能一条消费事实。单门店仅写已知 storeNo，门店名未知为空，跨门店 storeNo 为空，原分摊仍在原操作与订单明细——保持金额只计一次；成本：门店权限只能查明确归属该门店的记录，跨门店汇总由主体权限查看。
- Ruling: 首个金融迁入子批次只接受没有待迁应用流水、净余次等于原总次数的来源；MKT历史不可用继续标未知，绝不补造历史0次。带真实历史的PMT来源暂拒绝，不部分迁入；历史投影和旧售后桥接随后完成——避免先转余额而丢核销依据，成本是这些来源暂不可迁入。
- Ruling: 迁入保留原发行计数/预算/逐人基线，但发行保持暂停（ImportView.issuanceEnabledAtImport=false），不把旧源永久提交当作重新发券许可；完成旧定义/发行兼容后再开放——存量持有权益不受发行暂停影响；成本是迁入期间新领券暂不可用（仅开发和离线演练，未在生产执行）。
- Ruling: 同一capability gate必须同时作用于prepared计划头和支付import，当前尚未支持的历史来源不能先被核心永久提交——避免明知无法导入却不可逆关闭旧写；成本是历史映射接通前这类来源只可规划，不可提交。
- Ruling: 历史来源不再一概拒绝，但prepared与import都先认证原证据并核对逐券净历史次数=原总次-原余次；迁入不造旧支付/旧消费金融流水，旧订单退回作为新的真实权益账。旧发行仍暂停；业务事件入口、RPC及恢复链验证尚未完成，不提前关闭N10/N11。
- Ruling: 初始normalizer只接ACTIVE来源（实际LegacyCouponMigrationNormalizer已拒绝PAUSED/ENDED），因此target初始ACTIVE+issuer disabled不会复活已结束源，无需臆造新的迁入状态字段。后续状态修改只影响发行，不改原已得权益。
- Ruling: 旧发行兼容复用现有PAY/TARGETED持久批次及Grant内核，Core仅解析可信受众/资格并保存完成后的展示映射，不另造Core金融发行决定或向冻结旧实例表双写。新增pay兼容关联与core展示映射两表；完整执行约束已写总设计N10续。代价是需把新的pay实例映入旧身份/券包，并为Core展示失败提供耐久事件及恢复；不可用虚假旧券号或首个门店替代。
- Ruling: 初始迁移不把旧Fission事务发奖改成不受持久来源约束的pay远调。仍被旧邀请有礼/派生COUPON活动引用的模板须先转换到统一活动，完整清单及源提交前实况hash阻断，保存绑定/启用加源根栅栏。代价：这类旧券暂不能切换，但保持旧奖励路径可用，不产生部分切换后发奖失败。
- Ruling: 不改已冻结SQL；Flyway专用DataSource按实际MariaDB元数据重写仅DROP CHECK为DROP CONSTRAINT，普通core/pay连接不改、MySQL保持原句。V121注释中Spring字面占位符使完整原生迁移RED；由于仓库无任何业务SQL占位符，统一关闭Flyway占位符替换。代价为将来引入业务占位符须单独设计；原资源CRC保留。
- Ruling: 跨旧MKT/PMT/交易的源协调与Mapper放核心服务宿主shop-app的paybridge，并仍用Core事务/SqlSessionFactory；Core各域只经共享SPI互用写屏障和源身份，不把事实归属移到pay。Service拆同名契约与.impl，原bean身份、事务和财务决定保留；代价是包路径与内部固定计划读取契约需同步生成物及原生/双部署复验，均已执行。
- Final: Ruling: production rollout/scale remains outside offline acceptance — no production operation or throughput claim — cost if wrong: separate release/capacity verification required.
- Final: Ruling: no new online refunds/custody — explicit user boundary, merchant receives cash — cost if wrong: no consumer self-service refund.
- Final: Ruling: reconciliation remains current snapshots — no historical reconstruction claim — cost if wrong: separate temporal design.
- Final: Ruling: unsupported/old-program sources stay gated — preserve old path until conversion — cost if wrong: limited initial cutover coverage.
- Final: Ruling: UI implementation remains deferred — Java-first instruction, contracts/prototypes retained — cost if wrong: separate frontend integration required.
- Final: Ruling: entitlement and coordination tables use explicit domain authorization, not newly injected request-thread scope predicates — Pay standalone callers must carry authenticated tenant/owner facts, and Core records use trusted order/activity source IDs; arbitrary SELF predicates break merchant handling/recovery — cost if wrong: every new domain query must maintain explicit owner filters and denial tests. Guard registrations document this boundary without increasing debt baselines. Existing identity/use coordination tokens remain fail-closed for COMMUNITY/PICKUP-only Ops.
- Final: Ruling: enum registrations with no independent client declaration keep clients empty, not DISMISSED — Java/OpenAPI-generated DTO remains authoritative and UI is deferred — cost if wrong: future client enum must be bound when introduced. Preset AP/EP values are ID prefixes, not a two-value enum; usage protocol includes actual MIGRATED despite frozen old comment.
- Final: Ruling: frontend-first orphan debt cannot classify an explicitly authorized Java-backend-first delivery — add a separate exact staged API manifest checked against live handlers, published contracts and a concrete frontend task table; connected/deleted entries must leave the manifest, new entries are never automatically exempt — cost if wrong: 43 B/C endpoints still lack actual pages and must not be presented as UI-complete. Legacy orphan baseline unchanged; unit negative cases are a required push gate.
- Final: Ruling: backend-first staged UI accounting applies to Ops as well as B/C — 43 B/C plus13 Ops remain56 real frontend integrations across7 tasks — cost if wrong: consumers/operators cannot use new pages until that independent UI delivery; APIs/metadata are not UI completion.

### 推送门禁收口

服务单资源拆为`MpEntitlementServiceOrderController`，原URL、本人身份来源和结果不变；21模块18项权限/查询/契约通过（`/tmp/ent-final-controller-green.log`）。新增真实Handler→契约反向断言，RED捕获只写在补充文字中的4条路由，主表补齐后3项提取通过（`/tmp/ent-final-contract-reverse-green.log`）。当前公开64操作/107DTO，私有pay102/core19；四条是已有功能契约补全，不是新增业务。

[前端独立接入表](../../任务表-活动权益-前端接入.md)列明7项页面接入任务及43条B/C端待接入接口。`backend-ui-delivery.json`按明确后端先行范围登记，检查真实Handler、专项公开契约及清单陈旧性；3项负向/正常单测通过并接入pre-push。没有增加历史孤儿基线，仍28条；新增未登记接口仍报错。不能将此清单当作前端已完成证据。

B端189项、C端547项单测通过，双方类型检查通过。生成物24项及70份V2文档守卫通过。最终完整后端回归和远端推送仍待确认；之前两次因已定位失败或明确门禁收口而中止的回归不作为通过证据。

## 最终验收结果

代码`684b0f074`，完整命令`JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -o -f backend/pom.xml test`：27模块BUILD SUCCESS，700个测试类/场景组、4019项，失败0、异常0、条件跳过18。日志`/tmp/ent-final-backend-regression4.log`。条件跳过的原生数据库/双进程按本文独立命令执行，不作为未执行场景冒充通过。

两项Important修复及18项Controller权限/查询验证、真实契约64/107、原生金融、完整新建/升级及7双进程场景均有独立证据。前端双类型检查与736项单测通过；共享守卫保留16条历史基线，无新增或增长，运营722断言全通过。

Java任务55 DONE/0 DOING/0 TODO。前端7项接入任务和43条B/C接口仍待实际页面开发。只推送当前开发分支，不合并、不部署、不执行生产切换。

延期minor：冻结V449末尾空行保留，格式不影响执行，不能为消除空白警告修改历史校验和。

### 运营端推送守卫补齐

首次正式推送被13条运营端无页面入口拦下，未将失败推送计作成功。与B/C后端先行边界保持一致，新增`backend-ops-ui-delivery.json`精确登记13条，并逐项核验真实Handler、64操作公开契约和独立FE-06接入任务；已接入或删除必须移出，重复/错误端/无契约或Handler均拒绝。新增3项单测RED→GREEN并纳入pre-push。运营端真实有出口412条、后端先行待接入13条、历史无出口欠账10条；不增加历史基线，也不将待接入列算作已有页面。

当前前端实际待接入共56条（B/C 43、运营13），仍归7项前端任务。Java代码没有改变，27模块4019项完整回归证据继续对应`684b0f074`；本次仅补齐守卫、元数据与交付说明，最终推送仍须实际pre-push通过。

### 发券并发连接池修复（2026-10-20）

根因为事务管理器默认`SYNCHRONIZATION_ALWAYS`：Core或Pay的`NOT_SUPPORTED`边界虽无真实事务，仍开启同步上下文；MyBatis Pay读取连接因此绑定当前线程。并发提交先各自读取并等待Core决定，8个请求占满8连接，继而每个写事务都需额外连接，形成应用连接池饥饿。

Core、嵌入式Pay、独立Pay管理器已统一切换到`SYNCHRONIZATION_ON_ACTUAL_TRANSACTION`。修复后只跑受影响范围：发券同步边界/连接释放与8路同grant并发3项（`/tmp/ent-pool-starvation-fix-targeted.log`），独立Pay配置1项（`/tmp/ent-pay-sync-fix-targeted.log`），均通过；Java21 Maven编译通过。没有重跑历史全量回归。后端提交、部署与B端联调状态另行补记。
