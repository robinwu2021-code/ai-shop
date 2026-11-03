# T05 门店端上下文与持续准入

本次接入覆盖商家 API。账号继续由 MERCHANT 池认证。商家请求携带账号 bearer、发起请求时的 `X-Store-No`，进入受保护端后再携带 `X-End-Context` 和 `X-Client-Id`。门店与端证明均按请求绑定。切店和旧请求的迟到响应不会改写账号会话。本次没有调整前端，旧响应的显示仍需客户端按请求 generation 丢弃。

## 已登记路由

| 方法与路径 | 准入与结果 |
|---|---|
| POST /biz/auth/login、/biz/auth/staff-login、/biz/auth/otp/send | 保持宿主登录前入口 |
| POST /biz/merchant/apply、/biz/merchant/agreement/accept、/biz/merchant/quick-start | ACCOUNT。申请、协议及首店走宿主身份校验 |
| GET /biz/context、/biz/stores/mine、/biz/store/list | ACCOUNT。真实上下文恢复、跨主体和单主体选店，可不带店号；显式选择受影响门店时严格验证 |
| GET /biz/merchant/profile | ACCOUNT。真实冷启动／主体切换资料恢复，无头由宿主按可信主体类型决定范围；显式选择受影响门店时严格验证，商业到期／撤销不单独阻止恢复 |
| GET /biz/merchant/apply、/biz/business-types、/biz/business-types/{typeCode}/plans | ACCOUNT。申请及类型目录无需端证明；目录处理器由后续任务补齐 |
| GET /biz/auth/context，POST /biz/auth/logout | ACCOUNT 预留策略，当前宿主没有对应的商家处理器；不代替真实恢复／选店接口，也不表示已提供 B 端登出 HTTP |
| POST /biz/store/create | BOOTSTRAP。当前店成员具有 biz:store:admin，建店仍执行原所有权及同事务初始化 |
| GET /biz/stores/{storeNo}/context、/end-access、/subscription、/mall/categories、/mall/products | BOOTSTRAP。精确选店、有效治理、当前成员；表内简写后缀均接在同一门店路径下 |
| POST /biz/stores/{storeNo}/mall/quotes、/mall/orders | BOOTSTRAP。预留商城报价与下单续费入口，限制 biz:store:admin。后续商城处理器仍须执行采购及业务校验 |
| GET /biz/order/{subOrderNo}/trace，POST /biz/order/{subOrderNo}/delivered | BOOTSTRAP。分别要求 biz:order:view、biz:ship。宿主服务按当前主体、门店、已有子单和业务状态校验 |
| GET /biz/message、/biz/message/unread-count，POST /biz/message/{messageNo}/read、/biz/message/read-all | BOOTSTRAP。按真实消息控制器的收件人范围执行；新模式验证当前门店及成员，免商业证明 |
| GET、HEAD /biz/stores/{storeNo}/fulfillment/{channel}/impact | 仅保留经核验的纯旧模型预览：可达旧店、主体及头路径一致、原 biz:store 权限；default 别名仅解析为已有可达旧店，混合／未证明目标及新模型不开放 |
| POST /biz/stores/{storeNo}/end-sessions | BOOTSTRAP。请求体为 endCode、clientId，fresh 核验后签发；输入不含操作者或授权版本 |
| GET、PUT /biz/store/{storeNo}/pay-setting | END_PROTECTED。BIZ_APP / END_BIZ_APP；读取需 biz:store，写入另需 biz:store:admin。PUT 接入真实业务事务 |
| GET /biz/stores/{storeNo}/pos/access | END_PROTECTED。APP_POS / END_APP_POS，需 biz:receive。此探针只验证当前 POS 准入 |

登记表只决定门禁。T05 新增的 HTTP 处理器是签发、按 endCode 和 clientId 查询的单端状态、POS 准入探针。商城、端列表汇总、销售、设备凭据及后续交易等接口由后续任务提供。设备端未接入。单端状态可在证明过期后查询，返回服务器时间、权益版本、连续边界、状态与下一动作。

## 严格选店与兼容范围

商家链已经由 Spring 选中后，BizContextFilter 不再用原始 URI 二次跳过。原始应用路径含任意 `%` 或不具有字面 `/biz/` 前缀时，统一返回 HTTP 400；启用、关闭模块和纯旧店均如此，查询字符串不受限制。端点策略另外覆盖 GET 隐式映射的 HEAD 与 MVC 路径变量，不能以方法未登记为由绕回旧模型放行。

开启准入后，STORE_V1 业务请求必须给出明确店号。宿主严格解析按 USR 或 MCH 指定的成员键查找，在该主体和门店的岗位范围内验证归属。作用域、路径店、请求店及签名证明店须完全一致。宿主 API 仅接入 MAIN。主体接受 ACTIVE / PENDING_LICENSE，门店须 ACTIVE。暂停或缺失治理、行业、权益账户或不可用查询均阻止新的受保护操作。

混合模型账号可以同时管理多张营业执照。守卫检查已解析主体及该身份全部有效成员主体下所有未删除门店，不按员工门店角色缩小潜在目标；同时独立验证已登记请求的请求店、解析店、路径店。未知或大小写不精确的非旧模型也不能作为纯旧范围。只要实际主体级潜在目标包含新模型，未登记路由即关闭，包括选择老店后通过 body、查询参数或旧服务推导其他目标的入口。已登记且明确归属老店的能力保留原流程，仅参与完全旧模型主体的账号沿用原选店行为。此限制意味着混合模型账号的一部分老店功能要等后续逐路由审查、注册并接入必要的事务复核后才能开放。

OPS 继续使用 operator 过滤器及运营权限。商家证明和客户端声明的 SYSTEM 都不能建立运营或系统身份。回调沿用既有渠道验签，Job 由宿主内部调用；它们均在商家链外。没有新增伪造系统事实的 HTTP 入口。开店仍使用 T04 的原事务读取尚未提交的 owner，fresh 接入不参与该初始化。

账号级申请、协议等操作携带与已解析门店一致的头时，仍按账号办理，不依赖该店经营状态或商业模型查询；这不适用于实际上下文恢复、选店和档案恢复接口的显式选择。头路径矛盾和未知目标仍按严格边界处理。

## 核验与事务

宿主通过公开的 `EndAccessAuthorityPort` 提供会话、成员、岗位与治理事实，调用 `EndAccessFacade`。签名服务、JDBC 仓储和内核的构造放在 starter 的 `CommerceEndAccessConfiguration`；T05 新宿主生产类不导入 core／persistence，已有 T04 装配另属其历史边界。新增 ArchUnit 检查约束这些 T05 类。

- `AccountSessionVerifier.verifyFresh(bearer, now)` 返回宿主 LoginUser、指纹、真实会话到期时刻及核验时刻。`DbAccountSessionVerifier.withFresh` 在主库 REQUIRES_NEW、REPEATABLE_READ 的短事务内执行 SessionDao.findByHash、CompositeIdentityLoader 分派及成员／治理读取。使用原账号 Clock 的时区转换 LocalDateTime。原普通登录缓存和撤销轮询保持原延迟语义；签发、端状态与受保护读写均现读。账号 bearer 仅在已认证宿主上下文取用，短期证明和密钥不打印。
- `EndAccessFacade.check` 每次通过主库读取当前商业账户和当前排期。T05 的保守实现对读请求也短暂取得账户锁，换取账户版本与时间片的一致视图。按已验收排期快照、来源状态、真实经营类型、目录中已验收的 STORE 端、端号／权益码及 clientId 判定。关闭销售并不会单独使既有购买失效。权限仍取宿主岗位，不能用购买替代。
- `requireForWrite` 要求当前物理事务使用同一数据源。具体接点在 `StorePaySettingServiceImpl.save` 内。账户锁从准入持有到业务提交或回滚。版本和时间片用锁定读取得，按取得锁并完成 fresh 后的服务端时间评价半开区间。权益写者使用相同账户锁，因此先提交的撤销会阻止后续写，已获准的写在先完成。这是数据库本地事务，不持锁调用支付、通知、库存等外部系统。
- 账号、成员与治理的接纳点是新的主库快照。此前已提交的停用必须被拒，之后发生的宿主变更允许已接纳的短事务结束。这里需要独立连接，连接池须能同时承载业务事务与 fresh 短事务。
- 新上下文的 exp 取签发时刻加 TTL、真实账号到期、当前连续覆盖边界的最小值，按 UTC 毫秒判断。FREE 永久覆盖仍受账号与 TTL 限制。自然到期在 end 时刻生效。任何权益版本变化都需要刷新证明。非账号拒绝保留商业原因（含 EXPIRED、REVOKED、NOT_STARTED、NOT_PURCHASED、CHECK_UNAVAILABLE），只有账号失效使用 401。

当前门店采购事实由 `EndAccessAuthorityPort.Authority.canProcure` 单独提供，来自同一权威选店快照的 `biz:store:admin`，与商城 BOOTSTRAP 报价／下单岗位一致。相同 EXPIRED／NOT_PURCHASED 下，采购者得到 VIEW_SUBSCRIPTION，只有入端岗的员工得到 CONTACT_OWNER；另一主体的老板资格或请求体 canProcure 不能改变结果。状态查询、签发错误和受保护请求错误都传递已裁定动作，不再按状态重新生成购买引导。该字段不增加任何采购权限。

认证后恢复 GET 按可信 USR／MCH 类型读取证照、显示名和手机号；个人申请及消费者资料仅由 USR 路径读取。MERCHANT 身份缓存命中时核对会话记录的主体类型，避免同号类型串读；缓存键、TTL、撤销及其余 realm 语义不变。自定义 `BizIdentityResolver` 需实现 `resolveAuthenticated`，默认空范围，不能靠旧的无类型解析回落。此证据不等于对全部历史裸编号宿主业务调用完成了权限审计。

## 配置和轮换

宿主 api profile 在 `commerce.enabled=true` 时装配商家准入，`commerce.store-creation.enabled` 和销售开关不控制这层门禁。其他历史默认配置保持关闭。需要 DB 形态的 MERCHANT 会话（全局或 `shop.auth.token-store-by-realm.merchant=db`）。缺少等价 fresh 实现不能装配。memory 仍可用于单进程旧业务开发测试，本任务没有给 memory、Redis 或 Ehcache 提供生产付费写适配。

| 配置 | 约束 |
|---|---|
| commerce.access.active-key-id | 当前签发使用的 keyId，必须存在 |
| commerce.access.keys.{keyId} | 部署注入 Base64 编码的专用随机密钥，每把解码后至少 32 字节；没有内置密钥 |
| commerce.access.end-context-ttl | 默认 5m，大于零且最长 5m |
| fresh 核验 | 签发及关键写固定要求，无配置可静默回落到账号缓存 |

starter 固定 Nimbus JOSE + JWT 10.4 和 HS256，验证本地 keyId、算法、签名、audience、毫秒时间及身份、门店、端、客户端和版本绑定。轮换时先把新验证密钥分发给全部实例，再切 active-key-id，旧证明最长有效期结束后从全部验证集合移除旧密钥。未新增业务表和迁移。API 与 core 的依赖边界保持 JDK 和内部 API。

当模块关闭时，宿主 api 在数据库初始化后检查存量门店。已有 STORE_V1 将阻止该实例启动。如果其他实例在它启动后写入 STORE_V1，关闭模块的实例仍按请求检查可达门店和目标模型，向受影响的业务请求返回 503。精确账号路由保持可达。该检查也接入已登记的门店收款设置写事务。不得通过关闭整个模块回滚已有新模型，实际灰度与切换由后续任务负责。

## 最终独立验证

最终代码提交为 `e8c7be5b8`，位于本地 `codex/commerce-foundation`。使用 Java 21、离线 Maven 和专用回环 MySQL 8.4.13，测试创建并清理随机 commerce_test_ 库，没有连接开发或生产数据库。执行原生用例必须提供 COMMERCE_MYSQL_URL、COMMERCE_MYSQL_USER、COMMERCE_MYSQL_PASSWORD；未执行原生用例不能以 H2 或跳过结果替代验收。

| 验证 | 实际结果 |
|---|---|
| 宿主源码之外复制 commerce，`mvn -o -f <独立目录>/pom.xml clean verify` | API 5、core 60、persistence 86、starter 12，共 163 项通过，无失败、异常或跳过。末次宿主修复未改 commerce；逐文件确认副本与最终 commerce 源码一致 |
| 下列宿主根聚合命令 | 37 个项目打包成功；base-auth 17、merchant 5、shop-app 103，共 125 项通过，无失败、异常或跳过 |
| 原生重点场景 | CommerceMerchantScopeFlowTest 8、CommerceAccessSecurityFlowTest 5、FreshAccountSessionVerifierTest 1 均实际执行；包含真实控制器、会话、MyBatis、物理事务和数据库变更断言 |
| 数据库设计守卫 | 33 张表、621 个展开字段、68 条关系、13 项产物；本次无迁移变更 |

```sh
mvn -o -f backend/pom.xml "-Dtest=CommerceMerchantScopeFlowTest,CommerceAccessSecurityFlowTest,FreshAccountSessionVerifierTest,DbTokenStoreTest,SelfRenameTest,CommerceStoreCreationFlowTest,QuickStartFlowTest,DbSessionFlowTest,StagedSessionCutoverTest,MultiEntityIdentityFlowTest,BizMemberSessionFlowTest,IdentityUnificationFlowTest,StorePaymentRouteTest,ErrorCodeUniqueTest,SchemaDriftTest,BizEndpointPermTest,RealmIsolationTest,ControllerProfileTest,OpsEndpointPermTest,JobHandlerEndpointTest,ChannelPayCallbackFlowTest,OpsPermConfigFlowTest#riskCannotDecideAfterSale" -Dsurefire.failIfNoSpecifiedTests=false package
```

功能与兼容性范围包含账号／主体恢复、同号会话隔离、员工资料、旧建店和 FREE 原子初始化、收款设置、运营岗位、端点清单、真实支付回调和内部 Job。没有把未来商城或设备流程计入已通过的业务闭环。

Flyway 对 MySQL 版本、测试 Mockito 动态 agent、已有 Mapper 重复扫描及编译提示仍可见。本次不升级框架或清理无关告警；选定测试通过不表示宿主全量测试全部通过。

## 本次裁定与后续成本

1. T05 先交付最小的真实受保护路由与写事务接点，其他 STORE_V1 业务路由默认拒绝。后续 T11 必须逐条登记方法、端、岗位、目标店与事务钩子，完成前不能把全业务界面视为已接入。
2. 混合模型身份不能以 LEGACY 店头绕过未登记路由。代价是这类账号的部分旧店功能暂时不可用，后续需通过明确的路由目标约束恢复；仅完全旧模型主体保持原行为。
3. 对关闭模块实施启动及运行期保护，防止已有或随后出现的 STORE_V1 被当作旧店放行。代价是含新模型的部署必须先配置有效 fresh 能力与密钥，不能简单关闭开关回滚，并增加相关请求的主库模型检查。
4. 初期受保护读也短暂锁住订阅账户，保证当前版本与排期一致。代价是同店读写竞争及额外连接需求；扩大路由接入前需评估吞吐，后续优化不能削弱主库版本与时间边界校验。

5. 在已匹配的商家链中统一拒绝含编码字符的非规范应用路径，消除过滤器与 MVC 的解释差异。代价是所有模式的客户端都需使用规范路径；必须编码的值需使用已支持的查询／请求体契约，不再接受编码路径别名。

6. 旧入口范围证明覆盖已解析主体和全部有效成员主体，不以员工已分配门店缩小。未分配、停用或未知模型的同主体门店也可被旧接口请求体触及。代价是混合主体的旧店员工同样受未登记路由限制；T11 需逐条审查目标合同后恢复。
7. 未审查旧入口可能按 user_no／mch_account_no 两列重建身份，旧回落和默认预览须覆盖两列潜在主体；已登记接口仍按真实会话类型办理。代价是同号的另一类型账号拥有新店时，本人旧店的未登记入口也可能暂时被拒；经认证的证照列表、详情和归属方法另外按主体类型裁剪。

## 评审修复记录

首轮发现并修复五项问题：编码命名空间跳过门禁、真实选店／消息入口遗漏、新端点缺少权限清单决策、宿主直接依赖内核实现，以及真实运营／回调／Job 证据不足。R1 增加实际 HTTP 失败回归、三个入口的方法权限声明、公开 authority Port 与 starter 装配、原回调与 Job 的正负验收，以及对真实商家命令伪造 SYSTEM 的拒绝。复审确认五项均已修复。

R2 进一步用真实控制器复现并修复纯旧店履约影响预览和停用新店下的账号级申请查询。精确旧路由不扩大为整个履约命名空间豁免；商家端证明不能代替岗位权限，也不能建立运营或系统身份。每轮先见失败再验证修复，未以编译通过代替运行场景。


最终整段评审还以真实员工跨店写入、三个真实选店入口、冷启动资料和续费动作补充四项修复。回归先观察到两种开关下取货点越权新增、主体同号串读和员工购买引导错误，再验证主体级封闭、缓存类型校验和权威采购动作。恢复合同包括实际单主体／跨主体 picker 和档案 GET；不把预留策略项当作已实现处理器。

复审补充的反向同号场景也先由实际 GET /biz/entity/OTHER 复现私有资料披露，再验证启用模式 403、关闭实例出现新店后 503；纯旧控制组的真实证照详情／列表按认证类型裁剪，另一主体不可见。未知旧入口的回落证明覆盖两种潜在命名空间，不把这种扩大拒绝误当成获得另一身份权限。

## 启用前仍需完成的验收

有界连接池竞争测试未完成。当前实现可能在外层事务占用连接并等待账户锁时，无法为 fresh 的 REQUIRES_NEW 再取得连接；原生测试使用无界 DriverManagerDataSource，不证明生产池的吞吐或进展。启用真实流量前必须验证保留连接容量、约束并发或隔离 fresh 容量的有效方案，不能仅增加池大小就声称保证进展。该项在最终评审中归为不阻断默认关闭的 T05 代码验收、但阻断真实启用的门槛。

更广的端路由与前端接入、支付交付、设备身份、提醒、存量切换及生产密钥分发按后续任务推进。本次未做全宿主历史裸编号调用审计，未宣称所有旧业务命名空间都已完成类型化授权改造。

最终宿主 `ArchitectureTest` 单独执行 16 项，13 项通过、3 项失败，异常及跳过为 0。与接入前 `53aabe02a` 的 Surefire XML 逐项比较，失败测试名和完整断言消息逐字节一致：noFullyQualifiedReferences、topLevelPackagesMustBeRegistered、portInterfacesOnlyInSpi，均为既有 print／printbridge 边界。本次没有新增架构违例，也未修改规则豁免它们。整体安全评审及定向复审在 `e8c7be5b8` 认可代码验收，保留上述启用前容量门槛。
