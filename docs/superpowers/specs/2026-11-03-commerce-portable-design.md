# 门店商业订阅整体设计：包归属、认证、权益与到期提醒

日期：2026-11-03。状态：整体设计评审稿；没有创建产品模块、执行迁移或发送真实通知。

目标：把经营类型、行业Plan、平台商城商品、商家采购订单、门店订阅、整端访问权益、升级抵扣和到期提醒作为一个可整体移植的商业模块；账号、门店身份、真实支付、库存、消息通道继续由宿主提供。

本稿是后续开发的总体入口。业务细则沿用 [原业务设计](./2026-11-03-industry-plan-mall-design.md)；数据见 [表清单、字段字典与ER](../../technical/design/门店行业Plan商城-数据库字典.md)。配套 [开发计划](../plans/2026-11-03-commerce-portable-development-plan.md)、B端原型要求《B端App-注册开店Plan商城-原型需求包》（未入本仓库）、运营端原型要求《平台运营端-经营类型Plan商城权益-原型需求包》（未入本仓库）、[API清单](../../api/门店行业Plan商城-API清单.md)。

## 1. 结论：作为 commerce 模块群整体归属

建议统一源码根目录 `backend/commerce/`、Java根包 `ai.neargo.commerce`。**Plan、对应商城商品、商城订单全部归这里**，按业务功能分包，不拆散到 merchant、core商品域和pay域。

| 对象 | 内核业务包（`ai.neargo.commerce`下） | 数据职责 |
|---|---|---|
| 经营类型、整端目录 | `catalog.business`、`catalog.end` | 产品经营类型、端与唯一商业权益code |
| Plan及其版本 | `catalog.plan` | 每类型三档、不可变版本、包含的整端 |
| 平台商城商品／SKU／组合 | `catalog.product` | 商家向平台采购的商品定义、规格版本、交付清单 |
| 报价 | `quote` | 锁价、组件分摊、升级折价和累计预览 |
| 平台商城订单／退款 | `order`、`refund` | 商业成交、支付引用、退款协调；不拥有真实资金账户 |
| 数字交付、硬件交付协调 | `fulfillment`、`shipping` | 原任务、数字成组交付、包裹与库存合同调用 |
| 门店订阅／来源 | `subscription` | FREE、Plan周期、独立端包、原时长与原价值 |
| 同code排期／准入 | `access` | 实际时间片、连续有效区间、端准入和写入复核 |
| 升级／抵扣 | `conversion`、`credit` | 旧Plan转换、门店抵扣账户、批次和账本 |
| 到期提醒 | `reminder` | 到期策略、候选、收件人通道去重与重试 |
| 旧合同迁移 | `migration` | 旧主体合同按店映射、证据和切换 |

包内按 `application / model / spi` 等职责组织；只有需要时才建立子目录，不先生成一大批空类。各业务写者调用同一事务边界，不能跨包直接改对方Mapper。

### 1.1 方案取舍

| 方案 | 结果 | 结论 |
|---|---|---|
| 沿现有大模块分散存放 | Plan进merchant、商品订单进core、权益进pay；首期改动少，移植需重新拆依赖 | 不推荐本次新链路使用 |
| 一个目录下独立契约、内核、持久化和接入模块 | 一套业务账和事务，可嵌入现有应用，复制模块群即可换宿主适配 | **推荐** |
| 立即部署独立商业微服务 | 可独立发布，但马上引入跨服务开店、授权、支付和迁移一致性问题 | 暂不作为首期前提 |

本方案是可移植的模块化实现，不强制新建运行进程。将来如果选择服务化，再单独定义远程鉴权的接纳语义，不能把本地事务保证直接宣称为跨网络原子保证。

## 2. 现有结构与适配依据

已核对当前源码：

- `backend/pom.xml` 当前聚合 `shop-merchant`、`shop-core`、`pay`、`print` 等；注释和父工程使用Java21、Spring Boot4.0.x。本次不因拆包升级基础框架。
- `backend/print/` 已有 `print-api / print-domain / print-channel` 的组合模式，但其API仍依赖shop-base；新commerce契约要更严格，不能顺手复用整个shop底座。
- 旧Plan在 `shop-merchant/.../MerchantPlanService` 和 `MerchantPlanServiceImpl`，操作 `sys_merchant_plan_def / mch_entity_plan`，是主体额度合同。**旧合同兼容仍留merchant，新门店商业模型归commerce。**
- `shop-app/.../SecurityConfig` 的B链依次接 `MerchantTokenAuthFilter` 和 `BizContextFilter`；后者每请求解析门店归属。新端准入接在已认证身份之后，不取代原身份机制。
- `shop-base-auth/.../TokenStore` 的 `SessionData` 仅承载 `LoginUser`；不为当前门店或Plan修改长期账号会话结构。
- 现有消费者 `paybridge/EntitlementExpiryReminderService` 提醒会员资产，旧 `PlanExpiryJob`处理主体额度宽限；两者均不能直接作为本次门店端提醒的业务真源。

新文件和模块在开发计划中逐项列明；本稿中目录均为目标，不表示已经存在。

## 3. Maven模块、依赖与迁移边界

```text
backend/commerce/
  pom.xml                         commerce-parent：独立构建入口
  commerce-api/                   JDK契约、业务DTO、外部Port
  commerce-core/                  商业规则、用例、内部Repository接口
  commerce-persistence/           本模块MyBatis/JDBC、事务、迁移、消息记录
  commerce-spring-boot-starter/   自动装配、HTTP接入、端上下文、Job入口

backend/shop-app/.../commercebridge/
  宿主身份、门店、支付、库存、通知、审计、任务装配适配
```

### 3.1 强制依赖方向

1. `commerce-api`仅依赖JDK；不依赖Spring、MyBatis、`shop-base`、宿主异常或宿主用户／门店实体。
2. `commerce-core`只依赖api及JDK；时钟用 `java.time.Clock`，事务通过内部 `CommerceUnitOfWork`，不导入Servlet／SecurityContext／Mapper／宿主ThreadLocal。
3. `commerce-persistence`依赖core、api与本项目兼容的数据库／事务库；使用模块自己的持久化记录，不继承宿主BaseEntity、不依赖shop-store-mybatis。
4. starter依赖上述模块及必要Spring接入库，把HTTP／调度转换成API用例；不直接导入shop-merchant、shop-core、pay-domain。
5. `shop-app`装配starter并提供commercebridge；`shop-merchant`若在同事务初始化FREE，仅依赖commerce-api的门面，不能依赖实现或反向被commerce依赖。
6. 商业内核包间禁止环依赖；支付渠道、消息SDK、设备私钥和宿主权限表达式全部在适配层。

commerce-parent自身不继承shop-parent，能够从 `backend/commerce/pom.xml` 独立构建。框架版本从当前有效构建配置核对后锁定，不依赖未复制的宿主父POM属性。Java21为当前移植基线；这不是对任意旧Java版本的兼容承诺。

### 3.2 公开门面与外部Port

| 契约 | 关键接口语义 |
|---|---|
| `CatalogFacade` | 保存类型／端／Plan／SKU草稿、组合发布、停售；发布版本不可变 |
| `MallFacade` | `quote(actor, command)`、`placeOrder(actor, command)`、按归属查单／取消；金额只由服务端计算 |
| `FulfillmentFacade` | 接收可信支付事实，幂等执行原数字交付组和硬件协调 |
| `SubscriptionFacade` | `initializeFree(actor, command)`、赠送／撤销、来源及时间片查询 |
| `EndAccessFacade` | `check(AccessCheck)`、`requireForWrite(AccessCheck)`，后者必须在实际写事务中调用 |
| `ReminderFacade` | 扫描连续区间、生成提醒候选、逐目标投递与恢复 |
| `StoreDirectoryPort` | 返回门店、主体、经营类型、治理及模型标记，不向内核暴露MchStore |
| `ActorAuthorizationPort` | 从宿主可信身份取得当前成员、岗位权限、采购／运营能力；请求体不能指定actor |
| `PlatformPaymentPort` | 创建／查询／关闭平台收款意图、原单退款；区分平台收款和商家消费者收款 |
| `HardwareInventoryPort` | 按原组件号幂等预占／查询／释放／实发；库存权威归原系统 |
| `CommerceNotificationPort` | 解析合法受众，按收件人／通道幂等发送并返回SENT／RETRYABLE／UNKNOWN／PERMANENT_FAILURE |
| `CommerceAuditPort` | 记录操作号、对象、操作者、原因及前后版本，复用宿主审计 |
| `CommerceEventPersistencePort` | 同事务追加／读取中立商业事件及去重事实；可复用宿主inbox／outbox，但不暴露SysOutbox或宿主事件类型 |
| `CommerceIdGenerator` | 生成稳定业务号；库内id仍是AUTO_INCREMENT，二者不混用 |

门面、命令、结果、Port以及中立的 `ActorContext / StoreRef / Money / AccessDecision` 放api对应功能包。内部仓储、规则和事务接口放core，不作为宿主直接操作表的出口。异常用模块错误码，在starter／bridge映射到既有 `{code,msg,data}` 和国际化文案。

### 3.3 表、事务与Flyway归属

原32项候选表及新增 `commerce_expiry_notice` 全部纳入commerce数据边界；三项库存／消息设施仍优先复用现有实现。物理表名前缀保留已设计的sys／mall／mch／commerce，**前缀不表示Java包归属**，不为统一命名破坏存量迁移。

- commerce自有迁移放 `commerce-persistence/src/main/resources/db/commerce/migration/`，采用独立 `commerce_schema_history`，内部可从V1开始，避免把大时间戳迁移号混入宿主V序列导致后续宿主迁移被跳过。
- 33项清单不包含Flyway自身的技术历史表；短期端上下文采用签名证明，首期不新增独立端会话业务表。
- 宿主默认Flyway不扫描commerce位置。宿主初始化器先完成宿主迁移，再执行commerce迁移，然后允许对外服务；双初始化器的先后、失败阻断和重复启动须有集成测试。
- 对 `mch_store` 增加类型／模型等字段的迁移仍由宿主维护；commerce迁移不得越界改宿主门店、账号、资金或库存表。
- 嵌入模式使用同一DataSource和本地事务管理器，门店创建＋FREE初始化、数字成组交付、转换＋余额＋排期＋outbox可以原子提交。
- 抵扣账、来源、时间片和重排事件是同一模块事务；外部支付／库存／通知调用不得在持锁事务内等待。先记稳定操作号及待处理事实，再用可靠事件和查询／补偿推进。
- 复用宿主inbox／outbox时，bridge只实现api里的CommerceEventPersistencePort，core内部CommerceEventStore委托该Port；必须证明加入同一DataSource事务，否则采用本模块自有实现并禁用重复写者。不能为复用而让宿主导入core内部仓储，或让core直接使用SysOutbox。
- 旧主体额度与宽限仍由merchant兼容层负责；模型切换门店禁止新旧两套同时续费。历史访问可保护，但无付款证据不能虚构折价基数。

## 4. 业务闭环与不变量

开店选类型→创建门店及FREE→读行业三档→商城报价→锁门店数字变更和余额／库存意图→平台收款确认→全部数字来源、排期及版本一次交付→可进入整端；硬件分项发货。

同code按 `(tenant, store, entitlementCode)` 累计：Plan固定周期先确定，独立端包未用量FIFO填其外的未来空闲区间；保留已用事实，禁止回填重排切点以前。FREE永久来源不参与有限队列。准入不能只读Plan结束日。

升级只转换旧Plan当前及未来周期的剩余实付价值，赠送为0；新Plan折后价允许使用订阅抵扣，硬件／独立端包不使用该余额；超出新价的旧值保留在本店余额。套餐最多一个基础Plan组件，升级仅一个新Plan周期。退款冻结受影响来源，原路退本单现金、按来源恢复抵扣；不同时恢复旧Plan周期与余额。

行业首次发布支持匹配的三档Plan草稿和推荐SKU草稿同包原子发布；单独SKU发布只引用已发布Plan。类型启停需发布能力，不是普通编辑人员用enabled字段即可执行。

原设计的锁价、未知支付、过期付款、重试、分摊及迁移规则继续生效；此处不重定义一套新的商业账。

`initializeFree`是宿主在创建门店事务内调用的可信初始化能力，不对外提供可任意指定店号的赠权接口。账号和建店资格在进入创建事务前核验；模块通过当前事务可见的StoreDirectory适配读取刚插入门店及owner事实，不用另一个连接要求尚未提交的新成员已经可见。

支付回调、数字交付、退款和扫描Job使用接入层验证的内部身份及原事实合同，不要求买家持续在线，不因买家登出或商业端到期拒绝履行已付款订单。HTTP调用者不能通过请求体指定SYSTEM身份获得这些能力。

## 5. 认证：账号、门店上下文和商业准入分开

### 5.1 两层凭据

**账号token**沿现有MERCHANT认证池，回答“这个人是谁”。不要加入长期有效的Plan、当前门店、端清单或商业到期日。当前MERCHANT配置有30天会话和缓存／撤销轮询，不代表所有副本能零延迟感知账号撤销。

宿主新增明确的 `AccountSessionVerifier.verifyFresh(bearer, now)` SPI，返回 `AccountSessionVerification(user, sessionFingerprint, expiresAt, verifiedAt)`，不回落到缓存TokenStore.get。DB适配直接用 `SessionDao.findByHash` 和未缓存身份加载器检查撤销、到期与账号状态；会话LocalDateTime按原会话Clock的时区转换为Instant，不能套用门店展示时区。bridge把结果转换为中立ActorContext，不将LoginUser传入commerce。

首期生产付费能力使用DB权威会话及该fresh适配；memory只支持单进程开发／测试，其他存储必须提供等价fresh实现后才启用付费写。缺失fresh能力时commerce付费功能拒绝装配／写入，不能静默降回缓存检查。签发端证明和关键写都调用fresh接口，取得真实账号到期时间用于exp截断。

**短期端上下文**回答“本账号会话获准在这家店访问这个端”。由服务端签发，建议上限5分钟；至少绑定tenant、主体类型／编号、当前账号session指纹、storeNo、endCode、clientId、权益版本、iat、exp和jti。账号token和端证明同时校验，端证明不是可独立替代账号token的登录凭据。

编码／验证通过api的 `EndContextCodecPort`；starter选择成熟的签名令牌实现并锁定兼容版本，不自行设计加密协议。部署注入密钥、keyId和轮换策略，拒绝不允许的算法、失效keyId及任何签名错误。移植宿主可替换为共享的不透明会话实现，但不能降级为每实例内存真源。

`exp = min(签发时刻+5分钟, 账号有效期, 当前端连续区间的下个真实边界)`。永久FREE无需商业截止，但仍受账号及5分钟上限约束。证明中的权益版本用于发现过时上下文，不作为不查库放行的理由。

clientId／包名／header不是认证凭据，移动App内固定secret也不当作强身份。人端可公开标识客户端，但必须由服务端验证账号、门店资格并签发对应端证明；受保护路由只接受其登记的端集合。设备端另外校验宿主设备登记、门店绑定和设备凭据。此方案控制服务端访问，不承诺对客户端安装包提供DRM。

### 5.2 登录、选店与切店

1. 账号认证成功→读取可访问门店；无店可进入开店，不能先要求付费端证明。
2. 选择／恢复门店时，宿主现读成员、主体／治理状态和岗位，严格核对请求店号。**请求门店、实际解析门店、路径店号与端证明店号必须一致**；不一致拒绝，不静默执行到默认店。
3. 查询FREE、付费Plan及每端连续可用状态，同时返回 `serverNow / nextBoundaryAt / entitlementVersion / notices / nextAction`。
4. 进入某端时，服务端核验适用类型、人员端权限与当前商业资格后签发端上下文。无采购权的人看到联系老板，不看到再次购买来解决岗位无权的诱导。
5. 切店签发新的不可变端上下文，不修改账号token里的全局“当前店”。客户端递增请求generation，丢弃旧店迟到响应；每个请求固定带发起时的店号和证明。
6. 前台唤醒、断网恢复和到达nextBoundaryAt刷新界面；这些是体验优化，不能替代服务器逐请求检查。

### 5.3 每请求和写入复核

```text
账号认证 → 当前门店归属／治理 → 端证明绑定与路由允许集合
       → 当前商业区间／版本 → 现有岗位权限 → 业务状态 → 执行
关键写入：在实际本地事务内再次复核账号／成员状态、权益时间／版本并排序提交
```

端签发接口先检查人员是否可进入该端；受保护API仍执行已有岗位权限。商业到期、岗位无权、门店停用是不同原因，不混成“请购买”。

路由策略只有三种：ACCOUNT（认证／选店等）、BOOTSTRAP（基础经营台／商城等仍需门店成员）、END_PROTECTED（必须匹配端证明及可用资格）。对store模型的新路由显式登记；未登记的受保护命名空间默认拒绝，不能因少一条配置就变为免费绕过。

BOOTSTRAP保留登录后续费、商城、消息和明确的历史业务处理合同，不意味着可以用免费经营台证明调用POS专用路由。历史履约／售后接口限定既有订单，不能以救援名义开放新接单。

商业状态至少返回 ALLOWED／NOT_PURCHASED／NOT_STARTED／EXPIRED／REVOKED／CHECK_UNAVAILABLE；上下文失效另给刷新动作。只有账号认证失效使用既有401，商业到期不清账号token。

### 5.4 自然到期、撤销和缓存的一致性

- 有效区间为 `[start,end)`，在end时刻即不可访问，不等待Job跑完、token过期或客户端重登。
- 缓存键含tenant/store/code/权益版本，结果不得跨nextBoundaryAt。初期每次受保护请求从主库读取门店权益版本；同版本的区间可以缓存，区间和时钟必须重新比较。
- 续费、升级、撤销、退款和重排与版本增量、outbox同事务；新请求只有读到权威版本后才能放行，不以异步缓存失效单独承诺即时撤销。
- 嵌入模式关键写调用 `requireForWrite`，在宿主同一数据源事务中锁住门店权益账户直到业务提交。锁定读取直接返回最新权威状态，不能在MySQL旧快照中“先锁行，再普通SELECT读旧版本”。商业权益撤销／退款／转换均使用该账户锁，与新写接纳形成先后。
- 宿主账号、成员和门店治理的线性化点收窄为**fresh核验通过的请求接纳时刻**：核验前已提交的失效必须拒绝，核验后才发生的宿主治理变更不强行中止此前已合法接纳的短事务。本期不宣称改造所有宿主治理入口以提供跨表即时中止保证。fresh适配须读主库最新已提交状态，不能重用宿主长事务旧快照；外部副作用仍按状态机／outbox执行，不持锁等待网络。
- 商业核验异常时付费端拒绝新的受保护操作，返回可恢复错误；账号、续费入口独立保持可达。不能沿用角色解析器“解析失败回退旧权限”的商业放行策略。
- 现有DbTokenStore有会话／身份缓存和撤销轮询。关键付费写还需宿主提供强一致账号／成员复核；普通身份读保留现有延迟语义，必须明确，不能声称全系统零延迟账号撤销。

## 6. 到期提醒方案

### 6.1 以业务端连续覆盖区间为准

提醒单位是门店＋业务端，不是单张Plan或某一来源。合并该code的有效Plan时间片、独立端包和赠送，取覆盖当前时刻的连续区间终点 `coverageEndAt`；无缝衔接继续合并，真实空档不能隐藏。永久FREE端不发商业到期提醒。

区间计算结果还必须含 `mostRecentEndedCoverage`。D0首次扫描发生在到期后时，currentCoverage为空，应从最近正常结束的连续区间取原终点，确认当前没有新的覆盖、没有被撤销／转换取代、且尚在24小时窗口，才能通知。不得依赖之前一定生成过D7候选，也不能把有空档的未来片并入旧区间。

例：Plan的POS到12月1日，端包接续到12月31日，则POS提醒以12月31日计算，不在12月1日误报停用。Plan自身到期可在订阅页展示，但不是“该端已不可用”。

### 6.2 建议默认提醒策略

| 阈值 | 到期前时长／条件 | 行为 |
|---|---|---|
| D7 | 168小时 | 预计7天后到期，提供本店续费入口 |
| D3 | 72小时 | 临期提醒 |
| D1 | 24小时 | 临期提醒 |
| D0 | 区间终点且确已失去该端资格 | 告知到期，保留返回经营台／续费；未来恢复时间可另展示 |

使用精确UTC时长计算，展示按门店／市场时区；示例时区为Asia/Shanghai。每15分钟按固定扫描轮次分页，排期变化事件另外触发重算。后台扫描延迟不延长使用资格。默认D0外推送补发窗口24小时，其他错过阈值只补当前最紧迫的一档，避免停机恢复连发三条。

首期扫描协议固定如下，不采用仅按updated_at增量（否则时间自然走到D3不会被发现）：

- 每轮建立ScanRun，固定scanNo、scanNow、租户／分片和该分片upperAccountId；按订阅账户稳定id从起点分页，包含当前无覆盖但可能刚到期的账户。默认每页100、可配置上限500。
- 每批候选及outbox成功提交后才推进本轮内存afterCursor；批失败不推进。扫至upperAccountId才算本轮成功，下一轮重置游标和scanNow；本轮新增账户下一轮处理，事件可提前触发。
- 首期不增加持久checkpoint表：进程崩溃／超时后从该分片起点重扫，已提交候选由notice唯一键幂等去重；投递重试只读持久notice，不依赖扫描器内存。不得把易失cursor当作永久跳过对象的凭据。
- 默认Job执行上限10分钟、互斥租约11分钟、每15分钟一轮；分片数在部署配置固定，保证每片能扫完。超时未扫完返回失败并暴露积压，不能报成功后永久跳过后半账户；容量不足调整分片或另立持久checkpoint增强，不能默默丢提醒。

这些阈值是本方案建议默认值，可经配置发布版本调整；不自动扣款、不代用户下单，也不在本轮创建Codex自动化任务。

### 6.3 受众、投递与去重

默认发给老板和明确有本店续费采购权限的人员。普通店员只在端入口看到可用性提示，不接收采购金额或余额。渠道默认站内消息，可接App推送；短信默认关闭，仅在正式授权与渠道接通后启用。

新增 `commerce_expiry_notice`，一行对应一收件人一通道的一次阈值通知，记录覆盖终点、策略、观察版本、到期窗口、状态、重试、租约及回执。唯一身份是tenant/store/code/coverageEndAt/threshold/recipientType/recipientNo/channel的摘要，**不包含entitlementVersion**，避免无关变更重复提醒。

1. 核算连续区间，选择受众，候选与outbox同事务写入。
2. 发送前重新核验该覆盖终点仍有效、目标仍有资格、提醒未过窗口；已续费／撤销旧区间等候选标SUPERSEDED。
3. 每收件人／通道分别投递；站内信使用同一去重键。站内成功、推送失败时仅重试推送。
4. 返回SENT／RETRYABLE／UNKNOWN／PERMANENT_FAILURE，不能把“没有抛异常”当送达；租约超时恢复，失败按持久nextAttemptAt重试。
5. 外部通道无幂等或回执未知时查原消息／人工处理，不用盲目无限重发承诺exactly-once。策略发送租约与上限由实施配置固定并可观测。

“续费已提交”和“通知已送达”之间存在外部竞态。发送前核验避免已知过期候选；续费发生在外部通道已接纳之后的旧消息无法绝对撤回，消息内应提示以门店当前权益为准，点击后现读新状态。

SENT表示站内消息已持久化或通道确认接纳，不等于用户已读。推送适配须区分无合法设备、通道拒绝与结果未知，不能把返回0台设备等同成功。建议每notice最多5次自动尝试（含首次），重试间隔1／5／15／60分钟；投递租约120秒、外部调用超时10秒。超过次数或窗口入DEAD／SUPERSEDED；UNKNOWN先查回执，不能直接套重试间隔再次发送。人工处理仍沿原notice和目标，不创造另一条重复提醒。

### 6.4 客户端及运营端呈现

门店context／end-access返回临期摘要及CTA，B端工作台一处临期提示、权益页逐端显示；过期端显示续费或联系老板，不清登录。消息深链绑定门店和code，打开时重新校验成员与采购能力，不复用旧报价。

运营端新增提醒投递记录视图：按门店、端、覆盖终点、阈值、通道、状态检索，查看重试与回执；有专门权限才重试原notice，不能任意更换收件人或群发。核心认证／提醒接口补充见API清单。本稿不把原型演示误认为真实消息已经接通。

### 6.5 启用与运行配置

首期模块装配与销售／提醒开关分开。新模型启用前完成fresh验证器、平台收款、路由策略和通知通道检查；未接通能力不展示为可售。

| 配置 | 建议默认值／约束 |
|---|---|
| `commerce.enabled` | 初始未启用；有STORE_V1存量时禁止简单关闭整模块回滚，必须保留准入、查单、交付和基础续费合同 |
| `commerce.sales.enabled` | 灰度前关闭；可单独停止新售，不删除已购资格 |
| `commerce.access.end-context-ttl` | 300秒，并受账号／商业边界截断 |
| `commerce.access.fresh-account-required` | 签发与关键写固定要求，不允许生产配置为静默回落 |
| `commerce.reminder.enabled` | 通道与受众接通前关闭；到期准入检查始终不依赖它 |
| `commerce.reminder.scan-period / timeout / lock` | 15／10／11分钟，稳定分片与单轮扫尾协议见§6.2 |
| `commerce.reminder.batch-size / max-batch-size` | 100／500 |
| `commerce.reminder.channels` | 站内优先，推送需能力；短信默认关闭 |
| `commerce.reminder.max-attempts / send-lease / send-timeout` | 5次／120秒／10秒，UNKNOWN不盲重发 |

监控至少包括付费准入拒绝原因、上下文过期／跨店不匹配、权益查询失败、数字占用积压、交付重试、提醒扫描未扫完、待投递／UNKNOWN／DEAD数量及延迟。指标和日志不记录原始账号token、端证明或密钥。字段、资金和审计保留约定沿原数据库方案。

## 7. 可移植交付包与验收

交付包含：commerce源码模块群、独立POM、独立迁移历史及schema、公开API与事件版本、默认配置样例、端路由策略、测试fixture、宿主Port实现样例、运维与迁移清单。配置中没有源码写死的商户号、密钥、域名、用户号或货币价格。

移植时只需替换身份门店、支付、库存、消息、审计和任务适配，导入本模块数据与外部门店映射，保持来源号、时间片、余额批次、事件幂等身份和提醒历史。不能只复制Plan和当前到期日而丢掉旧值谱系。

验收必须证明：api／core独立编译且无shop依赖；第二个最小测试宿主只用内存／测试Port就能跑通开店FREE、购买、累计、升级及提醒；接入现有宿主后角色与成员检查仍生效；改价不追改历史；重复支付／投递不加发；到期无需Job即被拒绝；续费不误报；跨店证明及迟到切店响应不能落错店。

开发范围分期见配套计划。设计和计划完成后才进入实现，不在本轮自动建立真实支付、启用生产提醒或部署新服务。
