# 订单结账CKO-08：配置发布与事务

> 状态：4表及内部发布Service已实现 · 2026-10-22 · 档位2 · 基线78eb6144d。需求及方案沿[完整计划](./TDD-餐饮订单结账-完整开发计划.md)、[存储方案§2](./TDD-统一试算引擎-存储与Java实施方案.md)、[ADR-040](./ADR/ADR-040-餐饮销售配置与耗用权威边界.md)。

## L1 定位

在原trade域、商品订单同库增加4张ord_配置表；复用原BaseEntity、TradeMappers、幂等及outbox。模板/门店Service均采用接口＋impl；共用本域PricingPublicationPolicy完成短事务发布。不增加控制器或自动发布种子。

## L2 约束

后端适配层提供可信账号/主体/门店/平台权限上下文和有期限能力证据；不接受客户端自报能力/税档。已有CKO-03编译器负责有限配置校验。本阶段无真实能力/税档来源适配及API发布，相关证据仅由内部可信调用者提供，下一阶段接入原权限目录。

## L3 设计与实施

## §0 需求→设计

| AC | 要求 | 实现 |
|---|---|---|
| P01 | 草稿CAS、首次空指针、唯一头 | 4表约束，按租户/主体/店/业务键读取及父头FOR UPDATE |
| P02 | 不可变版本、锁定行业模板 | revision只插查，门店快照冻结templateRevisionNo/override/effective |
| P03 | 预览与发布绑定，能力/税档门禁 | CKO03编译＋预览摘要绑定上下文、草稿、指针、锁版、证据 |
| P04 | 同幂等键异body拒绝、重放原结果 | 原IdempotencyService；摘要绑定操作/主体/门店/操作者/资源/规范化内容 |
| P05 | 版本/指针/幂等/outbox原子性 | TransactionTemplate及CAS，失败全部回滚 |
| P06 | 空覆盖初始化、升级草稿、回滚新版本 | 初始化即发布；升级不自动生效；回滚重验当前能力并新发revision |

## §1 参数及不变量

Context为内部可信输入：tenantNo（当前MAIN）、actorNo、platformOperator、entityNo/storeNo、capabilities、capabilityRef/expiresAt、verifiedTaxProfiles。预览/发布重验证据未过期；幂等完成结果先重放，不因当前草稿/能力改变而二次发布。

草稿expectedDraftVersion与BaseEntity.version分开；发布请求expectedDraftVersion、expectedActiveRevisionNo（首次null）、previewHash、reason、rollbackFromRevisionNo（普通发布null）。同资源并发只允许一人成功。事务外编译/取证，事务内锁父头重验版本/指针和证据有效期，再插版本并CAS推进指针。每次发布推进草稿版本，旧preview立即失效。回滚源必须属于同店同配置，复制旧覆盖和旧模板锁版，重跑当下强制编译/能力/税档校验。

模板创建/保存/发布限平台Context；门店写入取可信entity/store范围。历史revision没有修改API；BaseMapper技术性写方法不是业务许可，测试约束白名单。门店发布保存新有效配置，不随模板新版自动漂移。

## §2 代码及执行步骤

- 4个trade.entity实体、TradeMappers内4个嵌套Mapper；新核心增量迁移V532。
- trade.dto.PricingConfigCommands：Context及创建/保存/发布请求、DraftVO/PreviewVO/RevisionVO。
- trade.policy.PricingPublicationPolicy：编译预览、作用域、幂等与原子发布；trade.service.PricingTemplateService/StorePricingConfigService及impl薄委托。
- spi.trade.PricingConfigPublished：既有outbox事件。
- PricingConfigServiceTest及verify-pricing-config-mariadb.py：并发、空指针、唯一约束、JSON约束、幂等、强制失败回滚及历史版本保护。
- 统一outbox的archive-only登记PRICING_CONFIG_PUBLISHED，本轮为发布审计证据；无缓存消费者时明确归档，不积累NO_CONSUMER失败。未来接缓存消费者时须移除该归档路由并验收投递。
- 原H2生成器与schema-test.sql同步；pricing-schema-generation.test.mjs验证CREATE/ALTER中的JSON校验等价转换。数据库清单/ER图/实体词典/分层清单通过原生成器刷新。

- [x] 先写测试验证缺失，编译类型后跑可观察RED。
- [x] 增量DDL、实体、Mapper及Service最小实现；不动已应用迁移。
- [x] 一次性MariaDB执行真实事务/并发/约束；消融关键保护。
- [x] 完整回归、架构检查、评审并范围提交。

## L4 验证与交付

## §5 验证

真实MariaDB11.4：最终17项实际通过、0失败/错误/跳过。测试通过MyBatis真实Mapper、DataSourceTransactionManager、原IdempotencyService和OutboxEventBus执行；不是H2或Mock事务证据。运行器只用127.0.0.1随机端口和pricing_test随机库名，不读取业务凭据，结束销毁容器。未在已有业务库执行迁移。

| AC | 实际测试 |
|---|---|
| P01 | firstPublishAllowsNullPointerAndPreviewHasNoWrites；concurrentPublishHasOneWinner；staleDraftAndPointerAndPreviewCannotPublish；realDatabaseRejectsDuplicateHeadsAndInvalidJson |
| P02 | immutableRevisionServicesAndPolicyExposeNoUpdateOrDeleteOperations；initializationPublishesEmptyOverrideAndTemplateUpdatesDoNotMoveStore |
| P03 | invalidOverrideOrExpiredCapabilitiesCannotBecomeActive；previewHashDoesNotDependOnUnorderedTaxProfileSetIteration；draftNormalizationMustNotHideDuplicateJsonFieldsFromCompiler |
| P04 | replayReturnsOriginalRevisionEvenAfterDraftChangesAndRejectsChangedBody；concurrentSameKeyReplaysWhenFirstCommitOccursAfterReceiptMissBeforeHeadRead |
| P05 | failedOutboxInsertionRollsBackRevisionPointerAndIdempotency；newAuditEventHasExplicitUnifiedArchiveRouteUntilThereIsACacheConsumer |
| P06 | rollbackCopiesHistoryToANewRevisionAndRevalidatesCapabilities；rollbackRechecksCurrentCapabilitiesInsteadOfTrustingOldApproval；resetOverrideAndUpgradeOnlyChangeDraftUntilExplicitPublicationAndReplayOriginalResults；anotherEntityOrStoreCannotReadOrReuseKeys |

独立评审的Set摘要不稳定、并发同键receipt窗口，均在真实MariaDB补确定失败测试（合计2断言失败、0错误）后修复。恢复继承、统一事件路由、重复JSON字段也分别RED后实现。最终评审复查无新增重要问题。

消融：临时删除TransactionTemplate包装，强制outbox插入失败后出现孤立revision，测试1断言失败、0错误；原代码已恢复，17项真实测试再次全绿。H2 JSON转换新增用例先RED再GREEN，连同既有约束生成用例2项通过。

全量core reactor BUILD SUCCESS：shop-base228实际通过，shop-core431总计/414实际通过/17条件跳过；这17项已经上面的独立MariaDB运行实际全绿，不把普通回归的跳过说成真库通过。其他依赖模块均无失败。

相关架构/schema检查24项：22通过、2项历史基线失败。ArchitectureTest.noFullyQualifiedReferences仍指向未修改的OpsPointsInspectionController；SchemaDriftTest.migrationAndTestSchemaMatch在旧pay_ent_exception_action解析不一致，已用78eb6144d原始迁移及原始schema隔离副本复现同一失败。未改旧守卫或其生产迁移。SchemaGeneratorTest（实际H2建库）、SchemaParityTest、BizKeyConventionTest、OutboxRoutingTest通过。

复现命令（Java21、离线Maven；真实库命令需要允许本机Docker）：

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home python3 scripts/verify-pricing-config-mariadb.py
node --test scripts/tests/pricing-schema-generation.test.mjs scripts/tests/test-schema-constraints.test.mjs
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -o -f backend/pom.xml -pl shop-core -am test
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -o -f backend/pom.xml -pl shop-app -am test -Dtest=ArchitectureTest,SchemaParityTest,SchemaDriftTest,SchemaGeneratorTest,BizKeyConventionTest,OutboxRoutingTest -Dsurefire.failIfNoSpecifiedTests=false
```

本机临时证据：/private/tmp/cko08-restored-real.log、cko08-full-core-suite.log、cko08-ablation-transaction.log、cko08-architecture-schema-final.log及cko08-schema-baseline.log；非仓库永久附件。最后一条命令会复现上述两项基线失败。

## §6 对账与裁决

共享分支沿用用户已选餐饮分支；只提交本轮确切文件，保留其他会话改动。平台/门店权限解析、真实owner证据、Controller及种子导入在CKO-09，不把内部Context构造成功当成真实鉴权验收。


### 6.1 实施参数及边界

| 服务操作 | 入参 | 返回 |
|---|---|---|
| Template.create | Context、industryCode/templateCode/name/definition、key | DraftVO |
| 两端getDraft / getRevision | Context、资源No；revision另含revisionNo | DraftVO / RevisionVO，不返回物理id |
| saveDraft | Context、资源No、expectedDraftVersion/content/templateRevisionNo、key | 新draftVersion，activeRevisionNo不变 |
| preview | Context、资源No、expectedDraftVersion、rollbackFrom可空 | previewHash、CompilationResult（有效配置或issues），无写入 |
| publish / rollback | Context、资源No、PublishReq、key；rollback要求历史来源非空 | 新RevisionVO，原历史不改 |
| Store.initialize | Context、templateRevisionNo/reason、key | 空覆盖发布RevisionVO |
| resetOverrides | Context、资源No、expectedDraftVersion/paths（合法可改叶路径）、key | 删除覆盖后的DraftVO |
| upgradeTemplate | Context、资源No、expectedDraftVersion/目标templateRevisionNo、key | 仅草稿升级的DraftVO |

DraftVO含no/industryCode/draftVersion/activeRevisionNo/content/templateRevisionNo。RevisionVO含revisionNo/parentNo/revision/templateRevisionNo/overrideSnapshot/effectiveSnapshot/effectiveHash/previousRevisionNo/rollbackFromRevisionNo。快照实体另外保留完整审计、schemaVersion、engineSpecVersion和实际规则目录摘要。

有限编译器无远端IO，业务身份/能力证据已在调用前解析。模板及门店发布写入使用同一主事务管理器；数据域仅在已接纳Context后局部适配，每条配置SQL仍含tenant及门店/主体约束。预览摘要只排序无序税档集合，普通业务数组保持原顺序；同键重试在预检查竞争后重查原receipt。

本轮22个代码/迁移/运行配置/测试/手工设计文件，另14个原生成器产物，共36个文件。生成输入来自当前已提交HEAD加本轮文件，不纳入其他会话未提交的前端及原型内容。共用生成文档如已有他人工作区编辑，提交使用独立生成快照，保留其工作区字节。

下一步CKO-09：受控种子导入、平台/门店配置API、原账号权限解析和真实能力/税档来源接线；在这些验收前不将内部Context或示例税档暴露为任意客户端写入入口。

生成产物对账：数据库表清单、后端分层清单、实体与字典、静态常量/依赖清单、glossary.json、技术文档索引、数据库ER索引及6张数据库SVG按隔离生成快照提交；8个已有他人编辑的工作区文件保留原字节，不能据此声称整个共享工作区无修改。
