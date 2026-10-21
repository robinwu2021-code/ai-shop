# CAT-05B 选择组与配料配置发布

> 档位1 · 承接P1及ADR040 · 已实现

## §0 需求对账

提供API009/117发布与API115历史，复用既有fnb_config_revision，按门店和时点解析不可变修订；不建立第二套商品、金额或库存权威。

| AC | 落点 |
|---|---|
| 完整定义、依赖版本冻结，后续草稿不影响旧单 | ConfigRevisionService、PublicationSnapshot、fnb_config_revision |
| 原规格、SKU主体门店及身份有效 | 原SpecValueCatalogQueryPort、GoodsCatalogQueryPort；事务外预读 |
| 组合不嵌套，选择组规则可满足 | 原规则规范化+有界可满足性检查；已发布依赖解析 |
| 定时生效不提前使用，门店隔离 | effectiveAt及冻结scopeStoreNos，按时点选有效快照 |
| CAS/幂等/审计/Outbox原子性 | 原基础设施；锁工作头分配单调revision |
| 本地与独立部署，原GOODS权限 | B端发布/历史Controller、typed内部API/RemoteService |

## §1 契约与取舍

POST API009 `/biz/x/food/stores/{storeNo}/option-groups/{configNo}:publish`，POST API117 `/biz/x/food/modifiers/{modifierNo}:publish`，输入version非负、effectiveAt（带时区ISO datetime，毫秒精度）、approvalRef可空。仅保存审批业务引用，不声称执行审批流。时间不可早于本次命令开始前5分钟，允许将来时刻；立即发布将请求过去时刻归一为命令开始时刻，禁止回填历史。Idempotency-Key必填，指纹包含原始归一到UTC的请求时间而非每次服务器now，重放不新增修订。

发布输出publication{revisionNo,configKind,configNo,revision,effectiveAt,contentHash,status}。revision从1递增，与工作头version分别计数；成功发布也推进工作头version。scopeStoreNos固定为当前获准门店，不接受客户端自报范围。API115 GET `/biz/x/food/config-revisions?configKind=OPTION_GROUP|MODIFIER&configNo=...&page=1&size=20`，当前门店从BizContext；过滤主体/门店后分页，不暴露物理id或完整定义。服务另提供getRevision与resolveRevision，内部typed协议供后续消费者取得完整冻结内容。

状态按查询时点派生：计划时刻未到SCHEDULED，已到EFFECTIVE；当前采用effectiveAt不晚于时点的最大值，同刻按revision最大。数据库行保存发布时状态，不在定时任务中修改冻结内容。publishedRevisionNo仅保留最近一次立即发布的管理提示；由于工作头为主体共享、修订有门店范围，**消费者必须通过按门店/时点的解析服务，不使用工作头指针或工作头status作为当前发布权威**。立即发布头ACTIVE，计划发布头保持DRAFT；草稿修改均不取消已有计划修订。

完整快照contentSchema=1，包含发布前真实工作头/子项、不可变依赖引用、原SKU/规格身份和版本、审批引用；contentHash由PublicationDocuments按FoodCommandFingerprint的带长度前缀SHA256规则计算，参数为"config-publication:v1"及规范JSON；对象键排序、数字无损规范化，数量字段以精确十进制字符串冻结，避免原生JSON浮点舍入。已发布行只能insert/select；业务Mapper不暴露update/delete。前修订链按对象全局单调序列，不代表前一门店可售内容。

ADDON必须引用真实salesSkuNo才能发布；无SKU仍可维护草稿，不猜免费。COMBO必须有真实组合计价SKU及非空ADDON成员，成员必须已有当前门店不晚于父生效时点的发布，冻结该修订，不递归读取成员当前可变定义。组MODIFIER项同样引用已发布快照；SPEC_VALUE须原可见ENUM。所有依赖的SKU在父发布前重新校验当前归属，发布不替代成交时的实价、库存及状态复验。发布依赖可有未发布的新草稿，其旧发布仍可明确冻结；快照显示实际所选修订。

通过原IdempotencyService.findCompleted先只读查询已完成回执，保留操作人和请求指纹检查。命中即返回原结果，不受后来草稿不完整或上游不可用影响。未命中才预读工作定义及依赖，远程原权威查询均在本地事务外；写事务仍执行原execute处理并发首次尝试。锁头后重读候选快照，若头或已选依赖/子项与预读不同返回409，不能将远程验证用于另一份内容。每次发布只承诺本次读到的依赖，不提供跨服务分布式快照。

组数量使用DECIMAL18,6；复用原互斥/依赖图规范化，发布时另验证至少存在合法选择。可满足性搜索有界（最多100项、10000状态），复杂度超限拒绝发布并要求简化规则，不能返回假成功。历史按单对象读取并在内存做门店过滤后分页；当前不建立跨域投影，规模增长时再做分页索引。

## §2 实施计划

1. 真实Mapper发布场景先红：新增实体FnbConfigRevision与仅追加RevisionMapper，DTO、ConfigRevisionService/Impl、PublicationSnapshot、组可满足性策略；覆盖快照不变、跨店、计划解析、依赖冻结、CAS、重放和出箱回滚。
2. 增加B端ConfigRevision资源Controller及原权限/门店目录；内部typed publish/history/get/resolve与RemoteService，原自动装配。API/协议测试先红后绿。
3. 加原生MySQL全迁移+真实内部token用例，资源打包和三宿主回归；规范真源及API文档标记实际实现，不改V1/V2/V3。
4. 消融验证关键守卫；一次独立只读审查，重要项一轮TDD修正；精确提交，保留共享工作区其他改动。

## §5 验收证据

| AC / 测试 | 当前证据 |
|---|---|
| ConfigRevisionServiceTest 10项 | 冻结/重放、定时解析及门店范围、旧发布依赖、SPEC64、预读重验、JSON重排/篡改、精度、回滚；无效草稿后重放及取消历史回归先红，修正后10项通过 |
| OptionPublicationPolicyTest 4项 | 必选但不默认、零容量依赖、重叠互斥、默认数量上限；最后一项先红，补判断后4项通过 |
| ConfigRevisionApiTest 2项、RemoteConfigRevisionServiceTest 4项 | 最终6项通过：原GOODS/目录/未知字段/小数版本拒绝，可信命令、内容摘要、时点、坏身份/重复历史及错误不重试 |
| CoreSpecValueCatalogQueryPortTest 6项 | 小数版本HTTP返回先红（未抛错），补目录非负整数token解析器后通过 |
| FoodConfigPublicationPrimaryDatabaseTest | 原生启动先红：仅追加Mapper没有被BaseMapper扫描器发现；显式FactoryBean接线后原生联验通过 |
| FoodIdempotencyTest 7项 | 缺少findCompleted方法先红；不抢占工作、门店操作域隔离、操作人/指纹/未完成回执守卫及原execute行为，修正后7项通过 |

JSON重排反例先出现Invalid frozen configuration document；规范化摘要后8项核心场景通过。DECIMAL18,6字符串冻结反例先红（isString=false）；实现后完整精度场景通过。冻结V1/V2/V3不修改。

## §6 实现对账和边界

| §2模块 | 类及方法 |
|---|---|
| 聚合与修订 | FnbConfigRevision、FoodMappers.ConfigRevisionMapper.append/findObject/findRevision（无通用update/delete），显式FactoryBean及DataScope登记 |
| 发布业务 | ConfigRevisionService/Impl.publishModifier/publishOptionGroup/listRevisions/getRevision/resolveRevision；锁头重验、依赖解析、追加、推进头及原子审计/事件 |
| 规则与文档 | OptionPublicationPolicy、PublicationDocuments.freeze/hash、PublicationSnapshot/Dependency及6个请求/回执DTO |
| 权威版本 | 原目录CatalogRevisionDeserializer及SpecValueFact、SkuIdentityFact字段注解；发布请求和修订回执同样严格整数 |
| 幂等修正 | 原IdempotencyService.findCompleted只读完成查询，复用原回执校验；原execute仍做最终唯一键并发检查，无餐饮回执影子表 |
| API与双部署 | BizConfigRevisionController、ConfigRevisionInternalApi、InternalConfigRevisionController、RemoteConfigRevisionService；本地/网关自动配置 |
| 测试与设计 | 持久化/规则/API/typed/原生应用及原目录wire回归；主设计真源/API生成物和MySQL验证脚本 |

仅发布OPTION_GROUP/MODIFIER；菜单、套餐、配方及执行报价/耗用仍按原P1～P4次序；Keeta实际同步继续延期。发布只冻结配置，不改变商品、订单、支付、进销存事实。生产事件路由沿原Outbox所属方案，不为此新建事件平台。

审查前最终验证：全模块321总计/282实际/39条件跳过，原商品/规格及内部接口21实际，一次性MySQL171实际无跳过，三宿主5+5+6实际、设计43项通过。消融删除定义一致性判断后，并发修改定义而不变版本的用例真实变红；原字节恢复后发布8+规则4通过。

一次独立审查：Critical0、Important2、Minor0。已完成发布因合法草稿清空SKU而不能重放（BAD_REQUEST），以及取消修订从历史消失（total1而非2），均补测试复现RED。单轮修正增加原幂等所有者的只读完成查询，沿用原有回执身份校验；取消状态只在生效选择中排除，历史和按修订号读取仍返回CANCELLED及冻结内容。原零售execute的并发及回执语义不变，不修改sys_idempotent库表。修正后全模块324总计/285实际/39条件跳过、一次性MySQL174实际无跳过（19基础+146守卫+5独立部署+4原规格权威）通过。

独立部署复测曾发现本地缓存公共依赖被旧JAR覆盖，HTTP桥接原先掩盖了NoSuchMethodError。测试桥接补充底层Throwable报告，重新构建原依赖后验证缓存及最终可执行JAR均包含findCompleted，随后原生联验通过；未以生产业务绕行掩盖构建问题。

最终三宿主复验：零售5实际+1条件跳过，集成餐饮5实际+1条件跳过，独立餐饮6实际；无失败。设计43/43及冻结迁移检查通过，V1/V2/V3保持原字节。上述数据库联验只使用一次性本地容器，未操作业务数据库。

边界：取消/审批执行和独立调度管理端点后续补齐；一次只接受当前授权门店。跨服务原子快照、历史回填、批量跨店和大历史数据库分页本批不提供；按门店/时点解析代替自动刷新共享头指针。未新增迁移，也不吸收其他会话的前端/库存草稿。
