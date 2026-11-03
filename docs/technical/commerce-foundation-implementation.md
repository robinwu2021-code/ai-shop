# Commerce foundation implementation — T01–T02

Current status: **T01–T02 accepted**. Task reviews passed; all three Important whole-branch findings were fixed at `de834dc79` and independently re-reviewed with no remaining Critical/Important findings. Controller verification of that committed source passed 48 tests in a fresh commerce-only copy and packaged all 37 host aggregate projects. Business Plan/quote/fulfillment/access/reminder implementations and production host activation remain later tasks. No production database was accessed.


## 最终验收与实施取舍

最终源码提交 `de834dc79` 经独立复核，跨数据源事务、租户身份传递和准入状态三项问题全部关闭。控制会话再次从已提交源码复制独立模块到 `/private/tmp/commerce-final-fixed-source`，运行完整 `clean verify`：48 项测试通过，失败／错误／跳过均为 0；日志 `/private/tmp/commerce-controller-final48.log`。最终 `mvn -f backend/pom.xml -DskipTests package`：37 个聚合项目全部成功；日志 `/private/tmp/commerce-controller-host-final.log`。这项打包不代表运行了宿主全量业务测试。

宿主选定回归仍采用前述基线对照结论：27 项中 24 项通过，3 项打印相关架构失败与变更前提交的断言完全一致。代码保存在 `codex/commerce-foundation` 独立工作区，原有脏工作区未混入本次实施。专用临时 MySQL 在验收结束后关闭；后续复现需重新提供文档中的专用测试环境。

| 实施取舍（按作出顺序） | 原因 | 后续成本或边界 |
|---|---|---|
| 复用设施必须先证明契约和事务等价，否则允许模块自有实现 | 保持移植后仍只有一份权威数据 | 替换适配器前须补等价性证据，不能并行维护两份账 |
| 本次严格截止 T02 | 用户限定实施范围 | 业务功能、桥接、认证与提醒仍须后续任务实施 |
| 在原工作区之外使用独立 native worktree；该路径写入通过工具升级授权执行 | 保留原有未提交修改 | 当前成果保留在独立分支，未混入原工作区 |
| T01 提前给出类型化的未来门面／命令／结果契约 | 让公开边界可编译、可审查 | 后续用例可能需要兼容扩展或显式版本调整 |
| API/core 增加最小 JPMS 描述符 | 用构建约束依赖方向 | 将来模块路径／框架接入变更时可能调整描述符 |
| 独立 parent 自行锁定依赖版本 | 脱离宿主 parent 仍可构建 | 嵌入升级时须核对宿主版本及仓库版本守卫 |
| 不建 mall_sku_stock；库存仍由现有库存域维护 | 避免第二本库存账 | 后续桥接须核验租户／平台业主／商品／库位映射和数值范围 |
| 自有 tenant-scoped inbox/outbox，显式替换端口关闭默认写者 | 既有消息设施尚未证明同一中立契约和本地事务 | 未来复用须证明等价，并处理数据迁移与单一写者切换 |
| 明确事件 aggregateVersion、入站事件／聚合身份及三个 outbox 元数据列 | 唯一性和公开契约需要明确来源，不能猜测 payload | 首批调用者及导出工具须采用修订后的契约／manifest |
| 租户复合身份优先于计划中 deliver(String) 示例 | 不同租户允许相同业务号 | 后续支付／Job／通知调用者须显式传可信 tenant/store |
| 用 Spring 物理事务 SPI 检查单一 JDBC 资源，拒绝混合数据源事务 | 线程事务标志和同步绑定无法证明原子性 | Spring JDBC 升级须重跑双数据源原生回归；不承诺 XA/JPA/任意多 manager 原子性 |

非阻断维护项：两个不同宿主 fixture 的实际映射可继续完善；JDK 依赖允许列表可按 module 判定；32 表全字段回读测试可拆分以改善失败隔离。已披露的 Flyway 新版 MySQL 提示及预期失败迁移日志保留，不影响实际用例结论。

## Enablement and integration contract

The independent reactor remains `backend/commerce/pom.xml`, Java 21, Boot 4.0.7 / Spring 7.0.8 / Flyway 11.14.1. API uses JDK only; core adds only API and JDK. Persistence owns Spring JDBC/Flyway dependencies; starter supplies optional Boot wiring. The host application has not been made to depend on or enable this starter.

To use the foundation in a host, add `commerce-spring-boot-starter` and explicitly set `commerce.enabled=true`. Disabled/missing property creates no commerce schema or beans. Enabled startup requires the host Boot `FlywayMigrationInitializer`, its primary writable DataSource, and the **same canonical DataSource object** in a JDBC `DataSourceTransactionManager` (including `JdbcTransactionManager` subclasses). Missing host initialization or a mismatched/non-JDBC transaction manager fails assembly. Custom transaction implementations are not claimed supported. Configure the MySQL JDBC/session timezone as UTC; audit timestamps use UTC `LocalDateTime` at millisecond precision, while business instants are epoch-millisecond BIGINT values.

`CommerceUnitOfWork.required(Supplier<T>)` is the core-only contract. Its Spring adapter uses REQUIRED propagation, starts standalone transactions and joins the same primary JDBC transaction. Entry rejects an active foreign DataSource transaction before invoking the callback, including when the commerce resource remains physically active beneath a second JDBC manager. Typed repository writes, event writes and account `FOR UPDATE` require a physical transaction owned by their identical DataSource and reject any other bound active JDBC DataSource. The persistence-local probe subclasses `DataSourceTransactionManager` solely to call its protected `doGetTransaction()` / `isExistingTransaction()` SPI: this reads Spring's physical transaction flag without obtaining a connection or starting a transaction. Synchronization-only binding from a read, the thread-wide actual-transaction flag and `autoCommit=false` alone are insufficient. Both connection auto-commit defaults are tested. Support is limited to one canonical DataSource/local JDBC manager transaction; arbitrary multi-manager, XA, JPA or custom transaction participation is not claimed. Repository reads require tenant, including ID lookups; there is no soft-delete filter that could hide historical facts. Inserts ignore the input record's physical id and return the database AUTO_INCREMENT id; stable external business numbers remain independently supplied.

`readEntitlementVersion(StoreRef)` uses a current `FOR SHARE` read, including inside an older repeatable-read snapshot. It can retain a shared lock until a surrounding transaction ends; write admission should directly call `lockSubscriptionAccount(StoreRef)`, which uses one `SELECT ... FOR UPDATE` returning the current `AccountSnapshot` and holds that lock through outer host commit. No external payment/inventory/notification calls occur in these adapters.

## Revised public contracts from final review

Trusted payment adapters supply `VerifiedPaymentFact(tenantNo, eventNo, orderNo, paymentNo, platformMerchantNo, paid, paidAt, verificationReference)`. The tenant must come from verified adapter routing, not an unverified callback body. `ReminderFacade.deliver(String tenantNo, String noticeNo)` and `recover(String tenantNo, String noticeNo)` retain the tenant from the persisted notice/event. `NotificationReceiptQuery(noticeNo, deduplicationKey, StoreRef store, recipient, providerMessageNo)` retains the original notification's trusted store/tenant even when the provider message number is unknown. These revise the original foundation signatures; adapters must update their calls. The spec's tenant-composite identities take precedence over the T10 `deliver(String noticeNo)` example. No global-business-number or ambient-tenant convention is required.

The compiled `TenantOperationContractTest` fixture invokes public payment/reminder/notification interfaces for two tenants with identical event/order/payment/merchant/store/notice/dedup/recipient numbers, and queries unknown-provider receipts in reverse tenant order. It proves the contracts can carry independent routing identities; no callback or reminder worker is implemented.

`AccessStatus` now includes `NOT_PURCHASED`, `REVOKED` and `CHECK_UNAVAILABLE`, preserving the existing outcomes. A compiled consumer selects separate actions through `AccessDecision.status()` without parsing optional denial strings. Runtime access policy, authorization and HTTP response mappings remain later work.

## Owned schema and reuse rulings

The shipped `commerce-table-manifest.json` accounts for **all 33 candidates / 621 dictionary fields**: **32 physical commerce business tables / 609 dictionary fields**, **one external inventory model / 12 candidate fields**, plus **three explicit outbox metadata columns** (`schema_version`, `occurred_at`, `published_at`) = **612 physical business columns**. There are 32 typed row records and 32 typed table repositories, plus the account current-read adapter, event port adapter, UoW and migration runner. Flyway's technical history is not one of the 33 candidates.

Four migrations in `classpath:db/commerce/migration` create the owned schema and `commerce_schema_history` is the separate history. The schema has **58 tenant-prefixed unique keys**, **19 tenant-prefixed operational indexes**, and **34 CHECK constraints**. Local id primary keys remain global only within each table; every repository lookup includes tenant. Nine configuration tables allow deleted IN (0,1); **23 trade/subscription/reliability fact tables enforce deleted=0 in MySQL**, preserving historical order/payment/source/schedule/credit/migration/notice evidence. Credit account/lot checks enforce nonnegative bounded reservations. Relationships are logical; no foreign keys to host stores/accounts/stock and no host table DDL are emitted.

Inventory ruling: omit `mall_sku_stock`. The inspected `shop-inventory/.../V1__inventory_baseline.sql` already defines independent-DB `inv_stock_balance`, authoritative by `(owner_id,item_id,location_id)`. Access remains through API `HardwareInventoryPort`; no host inventory types are imported and no second stock ledger is created. The manifest maps every candidate field, including omitted soft deletion and the BIGINT candidate quantities to the host INT quantities (the eventual bridge must validate bounds). The later host bridge must resolve trusted tenant/platform-owner/item/location mappings and warehouse configuration; T02 neither invents those mappings nor activates an inventory bridge.

Message ruling: choose **owned** `commerce_event_inbox` / `commerce_outbox`. Inspected host `JdbcOutboxStore` is source-bound to PLATFORM/INVENTORY, lacks tenant isolation and uses its own physical transaction registration, with REQUIRES_NEW operations in its lifecycle; direct reuse cannot establish this task's neutral tenant-scoped same-transaction contract. T02 imports no host event types, mutates no host message tables and invokes no host writer. The default commerce adapter is the sole writer; starter's `@ConditionalOnMissingBean(CommerceEventPersistencePort.class)` disables it when an explicit replacement is supplied, verified with exactly one port bean. A future host adapter must independently prove the same primary DataSource transaction before replacing it; this wiring hook alone is not proof of an arbitrary custom adapter's correctness.

`CommerceEvent.aggregateVersion` is an explicit typed authority version, separate from `schemaVersion`; it is never guessed from payload. It supplies the aggregate uniqueness key. `InboundFact` explicitly supplies producer/fact identity, event type, aggregate and content digest; raw inbound payload stays at its source as designed. Exact duplicate events/facts return false; conflicting content raises an error. JSON is compared structurally by MySQL, not by whitespace. Reads/publication acknowledgements are tenant-scoped and all writes join the business transaction. These are persistence operations, not a publisher, retry worker or business fulfillment implementation.

## Migration ordering and failure behavior

`CommercePersistenceAutoConfiguration` runs after Boot's Flyway auto-configuration. The commerce initializer **depends on the initialized host `FlywayMigrationInitializer`** and privately constructs its own Flyway instance through `CommerceMigrationRunner`; it never exposes another Flyway bean. This preserves Boot's host Flyway conditional configuration. Commerce uses baseline version 0 only for its own history when host objects already exist, then applies V1–V4. Existing conflicting commerce-named tables fail instead of being silently accepted. Commerce UoW/account/event beans depend on successful commerce migration. Downstream initializers that require the schema must likewise depend on `commerceMigrationInitializer`.

Real Boot context tests prove host V100 precedes commerce V1–V4, repeat startup is safe, host V101 still executes later, exactly the host Flyway bean remains, a commerce DDL failure prevents downstream activation, disabled commerce leaves no commerce schema, missing host initialization fails closed, and an explicit event adapter disables the default writer. MySQL DDL is not rollback-atomic: a failed migration can leave a failed history/partial DDL and blocks startup pending normal reviewed repair; automatic destructive cleanup is not implemented.

## Strict verification (dedicated MySQL required)

Use a disposable MySQL 8 database account restricted to creating/dropping `commerce_test_%` databases. Tests create unique databases, clean only databases they created and never manage server lifecycle. There is no H2 substitution, environment assumption skip, or default test credential in committed source. Every MySQL test fails if its configuration or connection is missing.

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export COMMERCE_MYSQL_URL='jdbc:mysql://127.0.0.1:23317/?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
export COMMERCE_MYSQL_USER=commerce_test
export COMMERCE_MYSQL_PASSWORD='<dedicated-test-password>'
mvn -f backend/commerce/pom.xml -pl commerce-persistence -am test
mvn -f backend/commerce/pom.xml clean verify
python3 scripts/gen-industry-plan-mall-docs.py --check
```

The URL is an admin connection with no selected database and a query string, as shown; tests append their generated database before the query. This environment used the coordinator's dedicated MySQL 8.4.13 instance on loopback 23317, never a production database. Flyway 11.14.1 emits its known newer-MySQL warning (last vendor-verified version 8.1); all actual MySQL 8.4 tests execute successfully. Expected migration-failure tests deliberately log a DDL error while their assertions pass.

Current final-fix verification (all commands used the dedicated MySQL/JDK environment above):

- Test-first API contract run: **2 tests / 2 failures / zero errors or skips** on the old signatures/status enum. Log `/private/tmp/commerce-final-fix-api-red.log`.
- Test-first native transaction run: **10 cases / 7 failures / zero errors or skips**, demonstrating independent commerce commits, surviving writes after synchronization-only reads, false account-lock acceptance and retained commerce holders under a foreign transaction. The three pre-existing supported-path cases passed. Log `/private/tmp/commerce-final-fix-tx-red.log`.
- Covering `-Dtest=TenantOperationContractTest,CommerceTransactionBoundaryTest,CommerceTransactionTest,CommerceEventPersistenceTest -Dsurefire.failIfNoSpecifiedTests=false` persistence reactor test run: **27 passed**, zero failures/errors/skips. Log `/private/tmp/commerce-final-fix-covering-green.log`.
- Complete source `mvn -f backend/commerce/pom.xml clean verify`: **48 passed** (API 4, core 2, native persistence 37, native Boot startup 5), zero failures/errors/skips. Log `/private/tmp/commerce-final-fix-reactor.log`.
- Independent copy of only commerce with no host parent at `/private/tmp/commerce-final-fix-standalone-ctdk4a3u/commerce`, `mvn -f /private/tmp/commerce-final-fix-standalone-ctdk4a3u/commerce/pom.xml clean verify`: **same 48 passed**, zero failures/errors/skips. Log `/private/tmp/commerce-final-fix-standalone.log`.
- Dictionary/artifact guard remains `CHECK OK: 33 tables; 621 expanded fields; 68 relations; 13 artifacts`; `git diff --check` passes.

The 10 `CommerceTransactionBoundaryTest` cases use two actual MySQL DataSources. They prove entry rejection before work starts, reject typed/outbox/inbox writes after a read binds the other resource for synchronization, reject a falsely scoped account lock, and assert no persisted writes after rollback. Both auto-commit defaults are exercised. Two simultaneous physical holders are rejected even when commerce's outer holder is active. Standalone commit, shared-primary rollback and caught nested REQUIRED failure remain green, alongside the original host lock-lifetime/current-read/atomic-rollback regressions.

Historical T02 verification before the final-fix wave:

- Exact persistence command: **31 tests** (27 native MySQL persistence + 4 API/core), zero failures/errors/skips. Log `/private/tmp/t02-required-command.log`.
- Clean full reactor verification: **36 tests** (32 native MySQL tests, including 5 Boot contexts, + 4 API/core), zero failures/errors/skips. Log `/private/tmp/t02-final-verify.log`.
- Independent copy of only commerce, no host parent: same **36 tests**, clean verify. Log `/private/tmp/t02-standalone.log`.
- Strict missing-environment invocation fails with `Real MySQL required: set COMMERCE_MYSQL_URL`; unreachable-loopback invocation fails with a JDBC connection error. Neither path skips or reports success. Logs `/private/tmp/t02-missing-env.log`, `/private/tmp/t02-unreachable.log`.
- Dictionary/artifact guard: `CHECK OK: 33 tables; 621 expanded fields; 68 relations; 13 artifacts`. This guard checks the design artifacts; the separate real-MySQL metadata and roundtrip tests verify implementation correspondence.
- `git diff --check` passes. Four original API/core contract/dependency tests remain green; no host or Spring dependency entered API/core.

Coverage includes every owned field's SQL type/nullability/AUTO_INCREMENT, all fields round-tripped through each typed repository, generated ids distinct from supplied business identity, tenant isolation, separate source/task/conversion/event unique-key conflicts, tenant-prefixed operational indexes, immutable-history checks and reservation bounds. Eight injected delivery failure points roll back host work, source, segment, credit balance/lot/ledger, schedule event, outbox and authority version together. Commit/outer rollback behavior, lock lifetime through host commit, and both current authority reads after old RR snapshots are tested with real connections.

## Host compatibility and remaining boundary

Coordinator evidence before the final-fix wave: `mvn -f backend/pom.xml -DskipTests package` succeeds for all 37 full host aggregate projects (`/private/tmp/commerce-final-host-aggregate.log`). Earlier selected-host packaging also passed for 24 projects (`/private/tmp/commerce-host-compatibility.log`). These host packaging results are retained baseline evidence, not a rerun of the final fix. Selected existing host regressions run 27 tests: 24 pass and 3 known ArchitectureTest failures (`noFullyQualifiedReferences`, `topLevelPackagesMustBeRegistered`, `portInterfacesOnlyInSpi`) concern existing print code. A clean export of pre-change `53aabe02a` reproduces the same three failures with identical assertion strings (`/private/tmp/commerce-host-baseline.log`, `/private/tmp/commerce-host-regression.log`); they were not repaired in this task. The full host business suite was not claimed run.

No business mock implementations, production host bridge, payment/inventory/notification calls, migration of legacy truth, deployment or push was added. Copied design/spec/dictionary/diagram inputs remain untracked intentionally. The final fix wave stops for scoped independent re-review; later tasks own business invariants beyond these foundational SQL/transaction contracts.

---

# T01 historical evidence

Historical T01 status: subsequently approved at f50c0902e. The empty persistence/starter and no-MySQL statements below describe T01 only; current T02 status is above.

### Delivered boundary

`backend/commerce` is an independent parent plus api/core/persistence/starter reactor. The parent has no host parent, imported host properties, or runtime dependencies. The host adds the aggregate and four version-managed coordinates only; comparison of before/after effective dependency management excluding the new commerce coordinates was equal.

The API contains 100 public contract types and one module descriptor. These include six typed facades, feature-specific commands/results, neutral context/store/money/error records, nine replaceable external ports, stable payment/refund/inventory/event/notice references, and explicit UNKNOWN outcomes. Interfaces contain no fake business implementations. Actor context is a trusted-adapter input; client binding is expressly prohibited in port/facade documentation.

Core has one module descriptor and **zero business classes**. Its JPMS descriptor requires only API. Persistence and starter are intentionally empty T01 placeholders; their jar packaging emits the expected empty-jar warning. No migrations, transaction implementation, auto-configuration, business behavior, or host bridge is claimed.

### Effective version lock

Read the current host effective POM using Java 21: Spring Boot 4.0.7, Spring Framework 7.0.8, JUnit Jupiter 6.0.3, compiler plugin 3.14.1, surefire 3.5.6, dependency plugin 3.9.0, Flyway 11.14.1, MyBatis-Plus 3.5.16. Commerce locks the Boot BOM and build plugins; database/framework runtime dependencies remain absent until the relevant task. The commerce POM does not inherit Boot starter-parent, preventing inherited runtime dependencies. The Java baseline is release 21.

### RED → GREEN evidence

All Maven commands explicitly used:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
```

1. RED: `mvn -f backend/commerce/pom.xml -pl commerce-api,commerce-core -am test` — API tests failed with 2 failures, 0 errors because six production facades and neutral contracts did not exist. Host fixture compilation is captured as an assertion, not an uncontrolled test compilation error. Log: `/tmp/commerce-t01-red.log`.
2. RED: `mvn -f backend/commerce/pom.xml -pl commerce-core -am -Dtest=CoreDependencyTest -Dsurefire.failIfNoSpecifiedTests=false test` — core boundary failed with 1 failure, 0 errors because API production bytecode did not exist. Log: `/tmp/commerce-t01-core-red.log`.
3. GREEN: exact plan command above — 4 tests, 0 failures/errors/skips. Log: `/tmp/commerce-t01-targeted.log`.
4. GREEN: `mvn -f backend/commerce/pom.xml clean verify` — all five reactor projects succeeded; 4 tests passed. Log: `/tmp/commerce-t01-verify.log`.
5. GREEN: copy only `backend/commerce` (excluding target directories) to `/tmp/commerce-t01-standalone`, then `mvn -f /tmp/commerce-t01-standalone/pom.xml verify` — independent reactor succeeded, same 4 tests. No repository parent exists at that location. Log: `/tmp/commerce-t01-standalone.log`.
6. GREEN: `mvn -f backend/pom.xml -pl commerce/commerce-api,commerce/commerce-core -am validate` — host aggregation resolved and selected all three intended projects. Log: `/tmp/commerce-t01-host-validate.log`.
7. GREEN: `mvn -f backend/pom.xml -N help:effective-pom -Doutput=/tmp/commerce-host-effective-after.xml -q`; XML comparison against `/tmp/commerce-host-effective.xml` confirmed existing managed dependency entries unchanged.
8. GREEN: `git diff --check`.

`PortableApiContractTest` compiles two distinct host port implementations against actual production contracts and invokes their behavior; it also checks the six facades expose typed interface methods. `CoreDependencyTest` checks the actual Maven-resolved runtime classpath (API empty, core only API), rejects empty API output, and invokes JDK jdeps on each production output **separately** to inspect class references. API may reference only JDK/API; core may also reference core. Scanning separately avoids jdeps collapsing the two same-named `classes` directories. A nonzero actual class-reference count is required.

### Rulings and limits

- Ruling: T01 plan permits signatures to be filled in later, while the approved execution request asks for fully typed future signatures now. Implemented future command/result/port contracts now, with no business implementations. Cost if wrong: later task review may require source-compatible extensions or an explicitly reviewed API revision.
- Ruling: add minimal JPMS descriptors to encode the production dependency boundary even while core has no business classes. Tests run on the classpath so compiler/XML test tooling does not broaden production modules. Cost if wrong: later framework/module-path integration may require descriptor changes; classpath consumers are unaffected.
- Ruling: independent commerce parent owns its dependency version lock despite the host's general no-child-version convention; this follows the explicit portability requirement. Cost if wrong: a repository documentation/version guard may need to recognize the independent reactor boundary rather than forcing host parent inheritance.
- No whole-host business suite, MySQL, payments, inventory, notifications, production database, frontend, deployment, or T02 acceptance was run. The 4 tests establish T01 contract/build boundaries, not free initialization, payment, fulfillment, expiry admission, or reminder behavior. Fresh authorization/session implementation and fail-closed assembly belong to later tasks.
- Expected intermediate failure: initial JPMS test compilation could not read `java.compiler`; resolved by test-only classpath configuration. No production dependency on compiler/testing libraries was added.
- Maven's first offline metadata attempt failed because help-plugin 3.5.1 was not locally available; online authorized resolution succeeded. Workspace/cache writes required automatic sandbox escalation and were approved. No remaining environment blocker.
- Copied design/spec/schema/diagram inputs are deliberately uncommitted. No push, deployment, production mutation, or unrelated workspace changes.

T01 independent review completed before this T02 implementation.
