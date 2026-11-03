# T05 门店端上下文与持续准入

本次接入覆盖商家 API。账号继续由 MERCHANT 池认证。商家请求携带账号 bearer、发起请求时的 `X-Store-No`，进入受保护端后再携带 `X-End-Context` 和 `X-Client-Id`。门店与端证明均按请求绑定。切店和旧请求的迟到响应不会改写账号会话。本次没有调整前端，旧响应的显示仍需客户端按请求 generation 丢弃。

## 已登记路由

| 方法与路径 | 准入与结果 |
|---|---|
| POST /biz/auth/login、/biz/auth/staff-login、/biz/auth/otp/send | 保持宿主登录前入口 |
| POST /biz/auth/logout、/biz/merchant/apply、/biz/merchant/agreement/accept、/biz/merchant/quick-start | ACCOUNT。注销、申请、协议及首店走宿主身份校验 |
| GET /biz/auth/context、/biz/store/list、/biz/merchant/apply、/biz/business-types、/biz/business-types/{typeCode}/plans | ACCOUNT。选店及类型目录无需端证明 |
| POST /biz/store/create | BOOTSTRAP。当前店成员具有 biz:store:admin，建店仍执行原所有权及同事务初始化 |
| GET /biz/stores/{storeNo}/context、/end-access、/subscription、/mall/categories、/mall/products | BOOTSTRAP。精确选店、有效治理、当前成员；表内简写后缀均接在同一门店路径下 |
| POST /biz/stores/{storeNo}/mall/quotes、/mall/orders | BOOTSTRAP。预留商城报价与下单续费入口，限制 biz:store:admin。后续商城处理器仍须执行采购及业务校验 |
| GET /biz/order/{subOrderNo}/trace，POST /biz/order/{subOrderNo}/delivered | BOOTSTRAP。分别要求 biz:order:view、biz:ship。宿主服务按当前主体、门店、已有子单和业务状态校验 |
| POST /biz/stores/{storeNo}/end-sessions | BOOTSTRAP。请求体为 endCode、clientId，fresh 核验后签发；输入不含操作者或授权版本 |
| GET、PUT /biz/store/{storeNo}/pay-setting | END_PROTECTED。BIZ_APP / END_BIZ_APP；读取需 biz:store，写入另需 biz:store:admin。PUT 接入真实业务事务 |
| GET /biz/stores/{storeNo}/pos/access | END_PROTECTED。APP_POS / END_APP_POS，需 biz:receive。此探针只验证当前 POS 准入 |

登记表只决定门禁。T05 新增的 HTTP 处理器是签发、按 endCode 和 clientId 查询的单端状态、POS 准入探针。商城、端列表汇总、销售、设备凭据及后续交易等接口由后续任务提供。设备端未接入。单端状态可在证明过期后查询，返回服务器时间、权益版本、连续边界、状态与下一动作。

## 严格选店与兼容范围

开启准入后，STORE_V1 业务请求必须给出明确店号。宿主严格解析按 USR 或 MCH 指定的成员键查找，在该主体和门店的岗位范围内验证归属。作用域、路径店、请求店及签名证明店须完全一致。宿主 API 仅接入 MAIN。主体接受 ACTIVE / PENDING_LICENSE，门店须 ACTIVE。暂停或缺失治理、行业、权益账户或不可用查询均阻止新的受保护操作。

混合模型账号可以同时管理多张营业执照。守卫跨该身份的全部有效成员行检查老板可达门店或职员授权门店，并检查请求店、解析店、路径店。只要可达 STORE_V1，未登记路由即关闭，包括选择老店后通过 body、查询参数或旧服务推导其他目标的入口。已登记且明确归属老店的能力保留原流程，所有店均属旧模型的账号沿用原选店行为。此限制意味着混合模型账号的一部分老店功能要等后续逐路由审查、注册并接入必要的事务复核后才能开放。

OPS 继续使用 operator 过滤器及运营权限。商家证明和客户端声明的 SYSTEM 都不能建立运营或系统身份。回调沿用既有渠道验签，Job 由宿主内部调用；它们均在商家链外。没有新增伪造系统事实的 HTTP 入口。开店仍使用 T04 的原事务读取尚未提交的 owner，fresh 接入不参与该初始化。

## 核验与事务

- `AccountSessionVerifier.verifyFresh(bearer, now)` 返回宿主 LoginUser、指纹、真实会话到期时刻及核验时刻。`DbAccountSessionVerifier.withFresh` 在主库 REQUIRES_NEW、REPEATABLE_READ 的短事务内执行 SessionDao.findByHash、CompositeIdentityLoader 分派及成员／治理读取。使用原账号 Clock 的时区转换 LocalDateTime。原普通登录缓存和撤销轮询保持原延迟语义；签发、端状态与受保护读写均现读。账号 bearer 仅在已认证宿主上下文取用，短期证明和密钥不打印。
- `EndAccessFacade.check` 每次通过主库读取当前商业账户和当前排期。T05 的保守实现对读请求也短暂取得账户锁，换取账户版本与时间片的一致视图。按已验收排期快照、来源状态、真实经营类型、目录中已验收的 STORE 端、端号／权益码及 clientId 判定。关闭销售并不会单独使既有购买失效。权限仍取宿主岗位，不能用购买替代。
- `requireForWrite` 要求当前物理事务使用同一数据源。具体接点在 `StorePaySettingServiceImpl.save` 内。账户锁从准入持有到业务提交或回滚。版本和时间片用锁定读取得，按取得锁并完成 fresh 后的服务端时间评价半开区间。权益写者使用相同账户锁，因此先提交的撤销会阻止后续写，已获准的写在先完成。这是数据库本地事务，不持锁调用支付、通知、库存等外部系统。
- 账号、成员与治理的接纳点是新的主库快照。此前已提交的停用必须被拒，之后发生的宿主变更允许已接纳的短事务结束。这里需要独立连接，连接池须能同时承载业务事务与 fresh 短事务。
- 新上下文的 exp 取签发时刻加 TTL、真实账号到期、当前连续覆盖边界的最小值，按 UTC 毫秒判断。FREE 永久覆盖仍受账号与 TTL 限制。自然到期在 end 时刻生效。任何权益版本变化都需要刷新证明。非账号拒绝保留商业原因（含 EXPIRED、REVOKED、NOT_STARTED、NOT_PURCHASED、CHECK_UNAVAILABLE），只有账号失效使用 401。

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

## 本地验证

使用 Java 21、离线 Maven 和专用回环 MySQL 8.4.13。原生测试创建随机 commerce_test_ 前缀库，并在测试结束时删除。仅配置测试库连接时才会执行原生用例。下列计数来自配置了该连接的运行。

| 命令 | 结果 |
|---|---|
| `mvn -o -f backend/commerce/pom.xml -pl commerce-core -am test` | API 5 项、core 60 项通过 |
| `mvn -o -f backend/pom.xml -pl shop-app -am -Dtest=CommerceAccessSecurityFlowTest -Dsurefire.failIfNoSpecifiedTests=false test` | 4 项通过，包括缺 fresh 装配、方法／路径策略、已关闭实例的启动与运行期保护，以及真实 SecurityConfig、MyBatis 和门店业务事务 |
| `mvn -o -f backend/pom.xml -pl shop-app -am -Dtest=CommerceAccessSecurityFlowTest,FreshAccountSessionVerifierTest,EndContextBindingTest,EndAccessTest -Dsurefire.failIfNoSpecifiedTests=false test` | 四类测试分别 4、1、3、4 项 |
| 同一宿主 reactor 追加 DbSessionFlowTest、StagedSessionCutoverTest、MultiEntityIdentityFlowTest、BizMemberSessionFlowTest | 既有认证场景 23 项通过 |

已观察到的环境告警包括 Flyway 对 MySQL 8.4 的已验证版本提示、测试 Mockito 动态装载 agent，以及宿主既有 Mapper 重复扫描提示。它们没有导致上述用例失败。本次未改变生产开关，未提供密钥样例或发布任何新端。
