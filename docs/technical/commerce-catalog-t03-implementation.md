# Commerce T03：经营类型、三档 Plan 与商城目录发布

本次在 `backend/commerce/` 完成目录用例、关系型 JDBC 适配及显式选择启用的装配。单一 `DefaultCatalogFacade` 委派 `BusinessTypeService`、`BusinessEndService`、`IndustryPublicationService`、`SkuPublicationService` 和查询投影服务。API 只用 JDK，core 只用 API 与 JDK；没有宿主实体、异常、ThreadLocal、Spring 或 Jackson 依赖。

本次不接入宿主 HTTP／认证／端注册桥接，不启用真实销售，也不实现报价、订单、交付、订阅排期、升级、退款或提醒。测试中的类型、价格、客户端和验收记录只是测试数据，不是线上种子。原有未跟踪的设计输入不属于本次提交。

## 启用与可信边界

原有 `commerce.enabled=true` 仍只装配 T02 持久化设施。新增目录装配同时要求：

```properties
commerce.enabled=true
commerce.catalog.enabled=true
```

宿主须提供同一主 DataSource 及 JDBC transaction manager、宿主 Flyway initializer，并提供以下真实端口：

- `CatalogAuthorizationPort`：验证服务端提供的 actor／会话，并核验 `commerce:catalog:read/edit/publish`。商家购买权益不得换成平台运营权限。
- `BusinessEndReadinessPort`：按完整注册元数据验证客户端、路由策略和验收记录；返回每个经营类型必需的基础入口 code，行业发布要求该集合非空。
- `CommerceIdGenerator`：生成版本、组件、审计和发布业务号。目录没有默认 UUID 或放行实现。所有标识须满足公开 code 格式，长度不超过 64；不同事件命名空间产生的号仍须满足 outbox 租户内唯一性。

缺少依赖时启用目录会装配失败；关闭目录不要求这些端口。只有 `OPERATOR` 且经可信端口批准的 actor 可以使用运营门面。写入在进入用例时、加入本地事务后再次核验；字段权限在当前读取得的数据上判定。

端验收引用来自可信部署注册，不由运营表单自证。`VERIFIED` 保存及发布都核验客户端／路由／验收引用。SYSTEM 端不能作为商家商品，Plan 也不能包含该端；未验收、未启用或不适用于目标类型的端不能发布。代码绑定不可重绑，已引用端的作用域、适用类型和 bootstrap 语义不可变。

## 公开合同的修订

T01 的目录接口是提前给出的占位合同。本次按已批准的领域模型修订：

- `PlanDraft.entitlementCodes` 改为 `List<String>`，保留重复项供服务端拒绝；移除 Plan 上的期限，增加独立版本身份、展示信息、policyVersion、原因。期限属于 SKU 组件。
- `BusinessEndDraft` 明确 STORE／SYSTEM、适用类型、验收状态、可售／必要入口开关及三个可信引用。
- 新增 `ProductDraft`，明确 SPU 的 PLAN／END_ACCESS／HARDWARE／BUNDLE 类型和适用经营类型。
- `SkuDraft` 明确 SPU、SKU 和版本身份、标价／售价、范围、退款政策 JSON 快照、限购、原因。退款政策只验证 JSON 对象语法，保持业务内容不透明。
- `SkuComponent` 明确互斥的 Plan／端／硬件目标、`periodDays`、`unitCount`、`allocationWeight`、gift 与 ON_FULFILLMENT／AFTER_PREVIOUS。API 的 END_PACKAGE 映射数据库 END_ACCESS。
- 保存返回 `DraftSaveResult(entityNo, draftVersionNo, version)`；新 Plan／SKU 版本的 versionNo 为 null 时由生成器创建，非 null 只允许编辑已存在的草稿。新增组件号同样传 null；显式组件号只能引用该草稿已有的组件，不能移用其他版本的组件号。
- `StopSaleCommand` 显式区分 PLAN／SKU／PRODUCT。业务稳定 code 和 SPU／SKU 稳定身份可由运营显式提供。类型创建自动建立 `<typeCode>:FREE/PRO/CHAIN` 三个稳定定义，不创建销售价格或假验收数据。

`CatalogView` 同时提供当前展示 `plans/skus` 和类型、端、Plan 定义／版本、SPU、SKU 定义／版本的类型化 metadata。定义上的 `version` 是主数据锁版本；版本视图上的 `version` 是草稿／合同版本锁版本；`catalogVersion` 专指行业三档组合发布版本。三者不得混用。完整历史／草稿可通过运营读接口取得，旧已发布版本一直保留。

发布选择同时绑定内容行版本：`IndustryPublicationCommand.expectedPlanVersions/expectedSkuVersions` 分别携带每个选中 Plan／SKU 版本的行版本，来自对应公开版本视图。类型分开保存，避免合法同号版本相互混淆；缺少所选项的 token 是无效命令，草稿在预览后被改价／改权益则返回 VERSION_CONFLICT，整包不发布。`expectedCatalogVersion` 继续保护目录当前指针。独立 `SkuPublicationCommand` 同时携带目标草稿的 `expectedVersion` 和来自 `SkuDefinitionView.version` 的 `expectedSkuVersion`，不同草稿不能凭各自有效的草稿 token 静默覆盖同一个旧 SKU 当前指针。

R1 复审修正：行业包还必须携带 `expectedPlanDefinitionVersions`（三个稳定 PlanNo → `PlanDefinitionView.version`）及 `expectedSkuDefinitionVersions`（候选版本所属稳定 SkuNo → `SkuDefinitionView.version`）。两者同样来自公开 metadata 预览，缺少受影响项返回 INVALID_ARGUMENT，主版本已经被独立发布或 Plan／SKU／SPU 停售推进时返回 VERSION_CONFLICT，目录指针、状态和审计均不改变。`catalogVersion` 仍专指行业组合代际，独立发布／停售不改变其语义；不能仅凭它判断预览是否仍与当前主数据一致。

`PublicationResult.publicationNo` 由生成器生成，同时是该发布审计的 eventNo。`publishedVersionNos` 保存本次完整选择的 Plan／SKU 版本引用。独立 SKU 发布用自己的 SKU 版本乐观锁，返回 `catalogVersion=0` 表示未执行行业三档切换。命令中可选 `operationNo` 仅用于审计关联，不承诺配置操作幂等重放；同一期望版本的重复写入会冲突。

## 使用顺序与版本规则

1. 以 `enabled=false` 创建类型，读取公开 `planDefinitions` 获得恰好三档的稳定 PlanNo；创建时传 true 会被拒绝，发布权限也不能绕过。
2. 注册真实端元数据。创建／名称／排序编辑需要 edit；类型 enabled 变化需要 publish，同时修改两组字段要同时具备两种能力。类型首次启用前必须已有成功行业发布。
3. 新建三档 Plan 草稿（versionNo=null、expectedVersion=0）。保留保存结果的 versionNo/version；编辑草稿时原样引用目标号并传回 version。已发布版本不能编辑，只能另建新草稿。
4. 新建 SPU，再新建 SKU 草稿。Plan 组件可以在草稿中引用同包待发布的 Plan 草稿；使用保存返回的 SKU versionNo。
5. 调用 `publishIndustry`，传三档版本号、候选 SKU 版本号、所选版本的行版本映射、三个 Plan 及候选 SKU 的主数据版本映射和当前公开 `catalogVersion`。首次发布不要求 SKU 先单独发布，Plan／SKU 的状态与当前指针、目录版本和审计同事务提交。
6. 后续可独立发布只引用当前已发布、仍可售 Plan 的 SKU 草稿，同时传草稿与稳定 SKU 的两个 token。发布会重新核验该 Plan 的全部业务端，历史 Plan 已发布不能替代当前验收。行业包中的 Plan 组件必须引用包内所选版本；每个稳定 SKU 在同包中只能选择一个版本。
7. 新版本改变 Plan 内容时，仍引用该类型旧 Plan 的在售 SKU 自动停售；FREE 新增永久端时，已有有限端包 SKU 也自动停售。可同时提交匹配的新 SKU 版本。旧版本及原订单／锁价快照不被更新。
8. 使用公开类型／Plan／SKU／SPU metadata 的主数据 version 进行启停。正常停售只改变当前新售状态并保留原指针；Plan／SPU 停售会停售关联 SKU。FREE 不可停售。紧急关闭未付款单不在 T03 实现。

发布器要求 FREE／PRO／CHAIN 恰好三档且类型匹配、FREE 包含可信必要入口与 bootstrap 端、高档包含低档全部 code。同 code 不允许重复。永久 FREE 端不可另售有限端包；该判断也覆盖尚未首次发布的可信必要入口，不能通过关闭 caller 输入的 bootstrap 标记绕过。

两条发布路径共用 core 根层的 Plan 端校验：每个引用端必须当前 enabled、VERIFIED、STORE、适用于目标类型，持有完整客户端／路由／验收引用且仍经可信 registry 批准。撤回可信验收、停用、RETIRED、付费端关闭 sale_enabled 或旧行缺证明，都会阻止新 Plan 型 SKU 发布。行业包按所选 FREE 版本，独立 SKU 按当前已发布 FREE 版本区分永久与付费端：永久包含的端不要求独立可售；其余付费端必须 sale_enabled，bootstrap 标志不能替代付费销售许可。此校验不修改已发布历史合同。未知 END_PACKAGE 目标统一返回 CatalogException／NOT_FOUND。

组件最多一个基础 Plan；不允许把 FREE 作为有限 Plan 出售。数字组件明确每周期 UTC 天数和周期份数，硬件使用件数且不含数字期限。数量为正，数字总时长不可溢出 UTC 毫秒范围；金额以最小货币单位存储，币种一致、标价不低于售价。gift 权重必须为 0，正售价必须有正分摊权重，权重合计不得溢出。具体订单分摊及抵扣留待后续用例。

硬件裁定：T03 只冻结明确的硬件权威目标号，校验目标互斥、件数、期限及分摊；发布不承诺目标存在或当前可供货。现有 `HardwareInventoryPort` 只有原意图预占／查询／释放／实发合同。本次不发起库存查询。T06 报价／库存桥接必须针对原目标核验存在性和供货，未知结果不能进入收款；T07 继续协调原意图，不盲建新意图。

## 错误与审计

`CatalogException` 是 API 中仅依赖 JDK 的模块异常，携带 `CatalogErrorCode`。后续 HTTP 适配按 code 映射，不能解析英文消息：

| code | 语义 |
|---|---|
| INVALID_ARGUMENT | 类型／范围／组件／验收／金额等校验失败 |
| NOT_FOUND | 当前可信租户下不存在目标 |
| VERSION_CONFLICT | 期望版本过旧或写入发生并发冲突 |
| FORBIDDEN | 非可信运营身份或缺少能力 |
| IMMUTABLE_VERSION | 尝试编辑已发布合同 |

基础设施／事务装配失败不冒充业务校验错误；T02 对物理事务和跨 DataSource 的拒绝语义保持不变。

目录 repository.save 显式接收 `CatalogWriteContext(principalNo, occurredAt)`，来自经验证 actor 和该操作一次读取的 Clock。所有新增目录／关系行的 created_by、updated_by、创建／更新时间均来自这个上下文；编辑保留创建人和创建时间，只更新变化行的更新人／时间。草稿端关系和组件采用差异更新，重排保留已有关系的创建身份，已发布历史不更新。操作原因允许 1024 字符，只进入审计；sys_business_type.remark 是独立业务备注，新建保持 null、更新保留旧值，不借存 reason。

审计写入既有 commerce_outbox，与目录数据使用同一本地事务。实体种类分别为 CATALOG_TYPE／END／PLAN／PLAN_VERSION／PRODUCT／SKU／SKU_VERSION，事件区分保存、发布和各类停售，合法同号不会串查版本或撞聚合唯一键。payload 包含可信操作者、原因、关联 operationNo、前后权威版本和完整发布引用。后续目录指针变化不影响追溯原发布包。不存在独立“已审计成功”的外部调用：提交失败时，目录和审计一同回滚；加入宿主外层事务时，返回值在外层提交之前仍属待提交结果。

## JDBC、迁移及吞吐边界

`CatalogRepository` 是 core 中的事务快照接口，`JdbcCatalogRepository` 将快照映射到既有九张目录关系表，另通过 outbox 记录审计。没有生产内存仓库。写入必须处于 `JdbcCommerceUnitOfWork` 所代表的相同 canonical DataSource 的物理事务。

公开 catalog 查询和写入均在同一 UoW 内，按固定表顺序对本租户目录行作当前读：纯查询用 FOR SHARE，写入用 FOR UPDATE，保守地串行化同租户目录写入，包括不同行业；不会把旧 repeatable-read 快照作为写入依据。保存只比较 before/after 的变化行，按 tenant＋业务键＋version 更新，原样保留未变化行；不进行整租户全量覆盖。已发布版本在适配层也禁止再次修改。即使宿主外层采用 READ_COMMITTED，也不会在九次查询间混入新一代 Plan／SKU。共享锁支持宿主 readOnly=true，原生用例同时检查服务端 transaction_read_only=1。读锁持续到外层宿主事务提交，调用者应保持事务短小。此方案适合低频运营目录，吞吐边界是同租户目录写事务互斥，纯读可共享并发但会阻塞发布；将来若细化行业锁，须重新证明端、SPU 跨行业范围及发布原子性。不同租户始终显式隔离。

V1–V4 文件不变，V5 `commerce_catalog_evidence_snapshots` 增加四个 nullable 字段：

| 表 | 附加字段 | 用途 |
|---|---|---|
| sys_business_end | client_id、acceptance_ref | 可信部署／验收证据引用 |
| mall_sku_version | sku_name_snapshot、max_per_order_snapshot | 名称／限购属于不可变版本快照 |

从 V4 升级不会伪造旧证据或旧成交内容。旧已发布行缺少证明／SKU 快照时仍可公开历史读取，但不能新发布／新售卖沿用；应创建完整新版本，DRAFT 可明确编辑补齐。新 SKU 版本可以修改名称和限购，不要求换稳定 SKU 身份。

manifest 保留原 33 候选／621 字典字段，其中 32 自有表／609 字典字段，外部库存模型 12 字段。加上原 3 个 outbox 附加字段和本次 4 个字段，实际自有业务物理列为 616。新增字段纳入 `additional_fields`、typed record／repository 和 MySQL 回读测试，不修改设计输入的 621 字段口径。

## 验证记录

测试必须使用专用 MySQL，不使用 H2、不跳过缺失环境，且只创建／清理本次生成的 `commerce_test_%` 测试库。环境变量及严格执行方式沿用 foundation 文档；密码不提交。

本次先落三类测试并观察未实现 RED；后续必要入口绕过、实体审计同号、旧 SKU 被新包重新启用、FREE 扩展后的端包、模块错误码和时长溢出都补充了实际断言失败后再修复。最初缺类 RED 是编译失败；商品未实现阶段是 UnsupportedOperationException，报告不把它们写成零错误的业务断言失败。完整日志保留在 `/private/tmp/t03-*.log`。

- 指定三类：IndustryPublicationTest、SkuPublicationTest、CatalogWritePermissionTest。
- 原生 CatalogPublicationMysqlTest：首次行业／SKU 草稿同包发布；写完目录和审计后注入失败的整组回滚；旧 Plan／SKU、锁价和订单快照不变；租户隔离；同一期望目录版本并发单赢家；不同行业并发不覆盖；旧 RR 快照中当前读拒绝过期发布；确定性 READ_COMMITTED 读／发布锁等待及重试证明完整目录世代一致；V4→V5 升级及 V1–V4 checksum 保留；旧缺证明／缺快照失败关闭；SKU 新版名称／限购不改旧版；同号不同实体审计隔离；完全通过公开门面的完整生命周期。
- 原生 Boot 验证：默认关闭目录无需可信端口、显式启用缺依赖失败、单一 Facade、自有 JDBC 仓库及事务审计实际工作。原有 T02 事务、跨库拒绝、权威当前读与迁移顺序回归继续执行。
- 最终命令、测试计数和独立源拷贝验证结果在下方验收补记中记录。

宿主选定回归由协调会话运行：27 项中 24 通过，3 项 ArchitectureTest 的打印／printbridge 失败名称和完整断言信息与变更前 `53aabe02a` 基线逐字相同，零 error／skip。失败项为 `noFullyQualifiedReferences`、`portInterfacesOnlyInSpi`、`topLevelPackagesMustBeRegistered`；日志 `/private/tmp/commerce-t03-host-regression.log`。这不是宿主全部业务测试。宿主 37 项目打包由协调会话在最终提交后另行运行。

### 最终验收补记

最终源码于 2026-11-03 完成以下验证，均使用 JDK 21 和协调会话提供的专用 MySQL 8.4.13：

| 命令／范围 | 结果 | 日志 |
|---|---|---|
| brief 指定三类测试命令 | 23 通过，零失败／错误／跳过 | `/private/tmp/t03-submit-required.log` |
| 工作区 `mvn -o -f backend/commerce/pom.xml clean verify` | 94 通过：API 5、core 25、persistence 55、starter 9 | `/private/tmp/t03-submit-reactor.log` |
| 只复制 commerce 的独立 reactor `clean verify` | 相同 94 通过，零失败／错误／跳过 | `/private/tmp/t03-submit-standalone.log` |
| `git diff --check` | 通过 | 提交前执行 |

独立拷贝位于 `/private/tmp/commerce-t03-submit-oct3o0m2/commerce`。对全部 245 个 commerce 源文件／资源／构建描述文件作字节比较，与最终工作区源码一致；未复制 target 或宿主 parent。依赖锁、各模块 POM、V1–V4 均无修改。

`CatalogPublicationMysqlTest` 最终执行 18 个原生案例，含 READ_COMMITTED 的普通／真实只读事务两条路径、不同 SKU 草稿竞争同一主版本、旧发布包遇到 Plan 权益或 SKU 价格编辑时整组拒绝。三类业务测试为 Industry 10、SKU 6、权限 7。完整 reactor 还执行原 T02 全部事务／当前读／迁移／schema 回归与新的 Boot 装配用例。

RED 证据另包括：`t03-rc-red.log`（普通 RC 读未阻塞发布的断言失败）、`t03-row-audit-red.log`（原 reason 写入 remark 导致 DataTooLong）、`t03-sku-master-red.log`（不同草稿覆盖 SKU 指针）、`t03-package-tokens-red.log`（2 个草稿内容 token 断言失败，零 error／skip）。只读路径调试曾额外设置 Spring enforceReadOnly，Connector/J 在创建事务时拒绝它的 executeUpdate；改回宿主默认 manager 路径并断言服务端只读状态后通过，生产事务基础设施未为此更改。

Flyway 的已知较新 MySQL 版本提示、预期失败迁移用例的 DDL 日志，以及并发用例刻意触发的锁等待失败，均为已断言的测试路径；不是跳过或隐瞒失败。T03 到此停止，后续由协调会话进行独立差异评审及宿主打包，不执行推送、合并、部署或真实销售启用。

### R1 复审修复验收

R1 仅修正三项发布边界：行业包绑定受影响稳定 Plan／SKU 主版本；独立 Plan 型 SKU 发布重新验收全部引用端，并区分永久 FREE 端与付费可售端；未知 END_PACKAGE 目标改为类型化 NOT_FOUND。公共端校验位于 core 根层，未新增 plan↔product 包循环，未改 catalogVersion 语义、依赖、迁移、宿主或销售流程。

补充核心 13 个场景及原生 MySQL 13 个镜像场景，覆盖包与独立发布、Plan／SKU／SPU 停售竞争，缺少主版本 token，撤回验收、disabled／RETIRED／sale_disabled、旧证明缺失，以及无需独立销售许可的永久 FREE 端正例。新原生类为 `CatalogPublicationReviewMysqlTest`。

| R1 验证 | 结果 | 日志 |
|---|---|---|
| 核心三类 RED | 36 项中 12 failure、1 error；error 为原实现拒绝合法永久 FREE 端正例 | `/private/tmp/t03-review-r1-red.log` |
| 新原生类 RED | 13 项中 12 failure、1 error；同一正例被拒绝 | `/private/tmp/t03-review-r1-native-red.log` |
| 三类核心＋两类原生目录覆盖 | 67 通过（36＋31），零 failure／error／skip | `/private/tmp/t03-review-r1-covering-green.log` |
| 完整 reactor clean verify | 120 通过（API 5／core 38／persistence 68／starter 9） | `/private/tmp/t03-review-r1-reactor.log` |
| 独立 commerce-only reactor clean verify | 相同 120 通过，零 failure／error／skip | `/private/tmp/t03-review-r1-standalone.log` |

R1 独立拷贝为 `/private/tmp/commerce-t03-r1-source-2rqxyvo2/commerce`，246 个源文件／资源／构建文件与最终工作区逐字节一致。完整验证在改动收敛后各执行一次。预期负向路径与 Flyway/MySQL 兼容提示仍按原记录披露，本轮不改依赖或日志策略；等待限定范围的再次评审。
