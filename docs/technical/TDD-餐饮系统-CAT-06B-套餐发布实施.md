# CAT-06B 套餐不可变发布

> 档位1 · 承接用户已确认的CAT-06B方案、增强方案P1及ADR040 · 已实现

## §0 需求对账

基于CAT-06A已实现套餐工作定义、CAT-05B配置修订，交付API012、API115的BUNDLE历史及内部get/resolve。旧订单及餐牌可引用不可变版本，不因以后草稿修改改变含义。复用原商品、权限、幂等、审计、Outbox和主体门店体系；无新表，无旧迁移改动。

| AC | 实现与验收 |
|---|---|
| 固定/自选套餐完整发布 | ConfigRevisionService.publishBundle、BundlePublicationPolicy；空草稿及不能形成非空选择拒绝 |
| 旧内容不变，工作头与修订分别计数 | fnb_config_revision追加BUNDLE；BaseEntity.version发布+1，config_version发布不变 |
| 按门店和时点读取 | 原list/get/resolve扩展BUNDLE，不以共享published_revision_no授权 |
| 定义/依赖SKU完整验真与并发一致 | 原getSku事务外预读；SERIALIZABLE锁头后完整重验，禁止嵌套及自身/基准递归 |
| 幂等和失败原子性 | 原findCompleted先重放，execute协调首次并发；修订/头/审计/Outbox/回执同事务 |
| 旧快照兼容及精度 | schema1原字节/摘要可读；schema2套餐专用内容，18,6和12,3数量精确字符串 |
| 双部署/受控启用 | 原GOODS/目录和内部Token；先升级读端，再启用默认关闭的套餐写开关 |

## §1 契约和裁决

API012 POST `/biz/x/food/stores/{storeNo}/bundles/{configNo}:publish`，请求沿PublishConfigReq：version非负严格整数CAS、effectiveAt带时区且毫秒精度、approvalRef可空且最多64；Idempotency-Key必填。主体/操作人取原认证，不接受自报价格库存/范围/快照。输出原PublishConfigResultVO，publication.kind=BUNDLE，包含revisionNo/configNo/revision/effectiveAt/contentHash/status。

API115 GET `/biz/x/food/config-revisions?configKind=BUNDLE&configNo=SKU&page=1&size=20`沿原当前门店；原typed get/resolve同样支持BUNDLE。新增typed `/internal/food/config-revisions/publish-bundle`。时间沿CAT05B：禁止历史回填，5分钟以内过去意图归一为命令开始时刻；未来按绝对时刻生效，不引入营业日切点或刷新任务；同一时刻选最高revision。已完成幂等回执返回首次结果，实时状态另查解析。C端以后读可售餐牌，平台不增加跨主体绕过接口。

复用fnb_bundle/group/choice工作表及fnb_config_revision，不加DDL。工作version为CAS，发布推进+1；config_version只随定义修改变化，发布不增加；revision按主体/kind/configNo全局递增。立即发布更新管理提示指针，未来发布保留指针；消费者必须用门店/时点resolver。取消行仍可审计读取，但不参与生效选择；本批不提供取消写接口。

schema1原PublicationSnapshot不改，配料/选择组继续格式1。新BundlePublicationSnapshot为schema2，仅BUNDLE：contentSchema/configKind/configNo/sourceVersion/bundle完整BundleConfigVO/skuFacts/approvalRef；不伪造未绑定配料或配方修订。PublicationDocuments按版本和类型校验；仅已知版本/类型组合可读，错误拒绝。原哈希规范继续适用，旧内容不重写，新增unitsPerPick精确字符串。数据库迁移版本仍3，与JSON内容schema2无关。

发布先复用BundleDefinitionPolicy规范化，再要求至少一组选项且存在合法非空选择。可选组可min=0，默认可为空，成交时必须完成最终选择。主SKU、成员及baseSkuNo均由原商品服务确认主体/当前店和版本；不得是自身或已登记套餐。仅验证身份，不推断可售、售价、库存或单位执行能力。

采用小型BundlePublicationSource集中读取/重验工作定义及推进套餐头，由原ConfigRevisionServiceImpl编排共同发布事务，不建第二套发布服务或Repository。套餐发布使用专门SERIALIZABLE事务模板，原配料/组保持READ_COMMITTED；远程调用仍在事务外，锁内重验完整候选和SKU集合。无分布式快照承诺，正式交易须再验真。

新增`shop.food.bundle-publication-enabled`默认false，通过部署配置重启生效，仅控制新的BUNDLE发布，读schema2始终可用、原发布不受影响、已完成回执仍可重放；关闭时新发布返回NOT_FOUND且不新增事实。集成和独立部署所有读取节点先升级完成，再在发布节点设置true；回滚关闭写入但必须保留支持schema2的读取版本，不能把已有schema2交给旧读端。

## §2 实施计划

- [x] 1. 写真实Mapper发布测试RED：固定/自选、源定义冻结、SKU事实、configVersion不随发布增加、空草稿、跨店、定时/取消读取、重放/回滚、定义竞争；新增schema2 DTO与发布策略/来源，接入原事务。
- [x] 2. 补API012、typed及Remote，读端双格式；API权限/范围/未知字段、Remote坏摘要/错误schema-kind/身份/版本、兼容schema1测试先红后绿；默认关闭写开关单独验证。
- [x] 3. 原生MySQL完整迁移、内部Token及实际HTTP发布/历史/resolve；精度、并发CAS及跨店；全餐饮、三宿主、冻结迁移/设计检查。
- [x] 4. 消融候选一致性守卫，反例RED后恢复GREEN；一次fresh只读审查，无Important；精确提交代码/TDD/设计，不吸收其他会话改动。

**类/模块：** food-api.dto.BundlePublicationSnapshot；food-api.policy.PublicationDocuments双格式校验；food-api.service.ConfigRevisionService新增publishBundle及spi.ConfigRevisionInternalApi常量/typed方法。food-core.policy.BundlePublicationPolicy及BundlePublicationSource（直接FoodMappers.Bundle/Group/ChoiceMapper）；food-core.service.impl.ConfigRevisionServiceImpl编排，FoodAutoConfiguration接线新开关。food-web.api.biz.BizConfigRevisionController、foodclient.RemoteConfigRevisionService；food-server.server.api.InternalConfigRevisionController。测试分别位于各模块scenario。

**预检共享接口：** 旧PublicationSnapshot闭合schema1不能加bundle字段，新增schema2类型；既有PublicationDocuments仅处理min/max/default/quantity，需精确支持unitsPerPick；旧resolver和Remote只认两类，必须同步扩展且校验schema-kind；原构造器测试夹具不应被套餐Mapper硬依赖破坏，保留旧构造器并为生产新增来源注入；工作定义允许空，发布须额外拒绝；不得把config_version当工作CAS或发布序号。

**审查重点：** schema1摘要兼容、未知schema拒绝、无版本提升的定义竞争、sourceVersion/configVersion/revision分离、恢复重放不依赖上游、开关关闭读端仍可用、主体门店及计划时刻隔离。

## §5 验收证据

| AC / 用例 | 证据 |
|---|---|
| BundlePublicationServiceTest 8项 | 新方法/来源缺失先编译RED；实现后8项及原ConfigRevisionServiceTest10项通过 |
| 定义、版本、重放 | freezesCompleteDefinitionAndKeepsDefinitionCounterSeparateFromPublication：发布推进工作CAS但不增加config_version，清空草稿且上游离线后原回执仍可重放，操作人和指纹不匹配拒绝 |
| 门店、时间、取消 | futureVersionsAndSharedHeadPointerNeverBypassStoreTimeResolution/cancelledBundleRemainsAuditableAndFallsBackToPreviousEffectiveVersion：全局头指针不代替门店解析；计划未到不生效；取消内容可读、解析回退 |
| 不完整与竞争 | rejectsEmptyZeroCapacityAndNestedDefinitionsWithoutDurablePublication/rereadsWholeDefinitionAfterRemoteAuthorityEvenWhenHeadVersionWasNotBumped：空与零容量、嵌套拒绝，远程预读期间无版本提升的定义改变409，坏原SKU事实拒绝 |
| 事务和开关 | publisherFailureRollsBackRevisionHeadAuditOutboxAndReceipt/disabledWriterStillReadsAndReplaysButDoesNotStartNewPublication：全事实回滚；默认关闭仅阻止新发布，关闭后仍读取并重放 |
| 精度和损坏 | exactSchemaTwoQuantitiesSurviveJsonReformattingAndRejectUnknownSchemaOrTampering：18,6/12,3文本精确、JSON重排、未知schema和内容篡改；hash不依赖物理空格 |
| 公共/Remote/兼容 | ConfigRevisionApiTest3 +RemoteConfigRevisionServiceTest6 +BundleSnapshotCompatibilityTest2通过；新路由缺失时真实RED；schema1独立固定摘要（37348b6f开头）保持一致；schema2错误类型/源版本/数值数量/未知格式拒绝 |
| 实际MySQL应用 | FoodBundlePublicationPrimaryDatabaseTest已单独通过：真实迁移、内部Token、HTTP发布/历史/时点解析、精度、并发同CAS仅一成功、重启默认关闭后仍读schema2 |

首轮全模块356总计/314实际/42条件跳过通过；原生首轮失败于测试对JSON物理空格的假设，改为解析内容与字符串类型断言后新发布原生场景通过。消融仅去掉BUNDLE完整候选比较，未提升头版本的定义竞争测试真实RED；原字节恢复后最终全模块356/314实际/42条件跳过通过。最终完整MySQL195实际无跳过（19基础+165守卫+7独立部署+4原规格权威）；三宿主零售5实际+1条件跳过、集成5实际+1条件跳过、独立6实际通过；设计43/43、冻结迁移schema3通过。数据库仅使用一次性本地容器。

一次fresh独立只读审查：Critical0、Important0、Minor0，无需修复轮次。审查覆盖20个精确归属文件，不运行Maven或修改工作区。原有其他切片的待改进项保持原记录，不计作本批新增发现。

## §6 实现对账与边界

| §2模块 | 实现 |
|---|---|
| 发布业务 | ConfigRevisionServiceImpl.publishBundle共用原发布编排；BundlePublicationSource.capture/advance直连三类Mapper；BundlePublicationPolicy复用定义校验并拒绝空/零容量发布 |
| 内容与读取 | BundlePublicationSnapshot格式2；PublicationDocuments.freeze/validate双格式及unitsPerPick文本；旧PublicationSnapshot格式1及原hash算法保留 |
| API与部署 | BizConfigRevisionController、ConfigRevisionInternalApi、InternalConfigRevisionController新增publishBundle；RemoteConfigRevisionService支持BUNDLE及双格式；FoodAutoConfiguration注入默认关闭写开关 |
| 验证与设计 | 新增核心、快照兼容、实际MySQL应用测试；扩展原公共API/Remote测试和MySQL无跳过校验；真源API012/115、DTO及生成API文档同步 |

报价/金额分摊、实际库存/配方、餐牌绑定、取消审批执行与Keeta继续后续阶段。审批仅保存业务引用，不执行审批工作流。历史页保持原按单对象门店过滤后内存分页；大历史索引优化另行处理。原Outbox只保证原子持久化，生产消费者/路由仍需部署侧配置。

审查裁决及代价：发布不等于可售，后续阶段接报价库存与消费者；原商品验真不提供分布式原子快照，正式交易必须重验；读端升级及生产Outbox路由由部署负责，操作者须显式启用写入，首次写schema2后保留对应读取能力；无关前端/库存改动不纳入20文件提交，需各自审查交付。
