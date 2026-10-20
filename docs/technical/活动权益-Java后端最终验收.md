# 活动、卡券、储值 Java 后端最终验收

状态：N12验收中；当前不作为全部完成声明。源码工作树为`codex/stored-value-entitlement-dev`；合并后的当前交付仍待全量回归、整体审查及提交。

## 范围与依据

按[独立任务表](./任务表-活动卡券储值-Java后端.md)、[模块化设计](./TDD-活动卡券储值-模块化系统设计.md)、[数据库设计](./TDD-活动卡券储值-数据库设计.md)、[API契约](./TDD-活动卡券储值-API契约.md)、[代码结构与类清单](./TDD-活动卡券储值-代码结构与类清单.md)验收。core拥有活动/资格/预算与真实订单源决定；pay拥有权益/发行/核销/储值账。商家实收，平台管理权益与账单；无平台收款托管或新储值/付费卡线上退款。

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

待执行：全量后端回归、最终生成物/权限/契约守卫、一次完整变更审查、必要修复及提交。父任务保持DOING，实际结果完成后更新本节。

### N12首轮问题与定向复验

首轮完整后端执行4017项，22失败/42异常/18条件跳过（`/tmp/ent-final-backend-regression.log`），不是通过证据。已修复：重复H2上下文初始化导致IAM/类目种子部分回放、旧支付回调mock缺少新增业务接口、原退款通知缺真实Core来源fixture、权限断言未含新储值码及端点分类、DDL列/约束/注释解析、规范业务号、明确非媒体业务编号；生产权限未放宽，旧SQL未改。

结构整改后22模块66项通过（`/tmp/ent-final-architecture-green5.log`，含全部16项架构规则和38项支付HTTP契约）；SourceFence/历史及发放业务均通过。最新原生MySQL历史迁入/20并发回补1项、原券占用/确认/回补/取消/并发1项，均无跳过（`/tmp/ent-final-historical-native.log`、`/tmp/ent-final-coupon-use-native.log`）。双JVM/独立库7场景无跳过通过（`/tmp/ent-final-two-process.log`）。真实Handler/Jackson契约3项通过，公开60操作106DTO（`/tmp/ent-final-contract-extract.log`）；C消费明细的主体必选、门店可选，匿名探针提供合法参数后严格验证401。

剩余：最终整体回归、独立整体审查及其必要修复、状态闭合与远端推送。冻结V449脚本保留原末尾空行，这是已知格式minor，不能通过改历史脚本消除。

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
