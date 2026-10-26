# TDD-支付服务-W5密钥模块

状态：已实现
档位：2 · 依据：[完成度复核与剩余开发计划](./支付服务-完成度复核与剩余开发计划.md) §3.5、D-5、§6 Q1（已定：平台级 `secret` 模块，支付只存引用）· [数据库表结构说明 v4](./TDD-支付服务-数据库表结构说明.md) §3（密钥管理）、§6.8（`sec_*` 七表）、§8.4 · DDL 源 `docs/technical/design/schema/pay-service-review.sql`「密钥管理」段
创建：2026-10-26 · 最后更新：2026-10-26

新建平台级密钥模块 `backend/secret/`（`secret-api` / `secret-store` / `secret-core`，形状照 `job`）：
信封加密（AES-256-GCM，每段密文一把数据密钥，数据密钥由命名空间主密钥包裹，附加认证数据绑定行）、
密钥版本生命周期（DRAFT → VERIFIED → ACTIVE → ROTATING → RETIRED，任意 → REVOKED）、
轮换与到期任务、主密钥轮换后的重新包裹。独立迁移目录 `db/secret`（历史表 `sec_flyway_history`），装配照 `db/pay`。
支付侧只存引用；接入方验签密钥的取用换成 `SecretAppKeyResolver`（开关，默认不开）。
**每片先写会变红的测试，实现后变绿，再撤实现做一次消融。**

## 切片

| 片 | 内容 | 提交 |
|---|---|---|
| S1 | 三个 Maven 模块 + `db/secret` V1（七表，DDL 逐字）+ 实体与 Mapper + H2 / MySQL 测试支撑 | 见 §6 |
| S2 | `secret-core`：`EnvelopeCipher` · `MasterKeyProvider`（`InjectedMasterKeyProvider`、`KmsMasterKeyProvider` 契约）· `MasterKeyBook` · `DefaultSecretVault` · `DefaultKeyRegistry` · `SecretLifecycleService` / `SecretLifecycleJob` | 见 §6 |
| S3 | `shop-app` 装配 `SecretDataSourceConfig`（数据源、事务、迁移、主密钥注入、启动对账）+ 每日任务外壳 `SecretLifecycleSchedule`；H2 schema 重新生成；守卫登记 | 见 §6 |
| S4 | 支付：`pay.secret.SecretAppKeyResolver`（`shop.pay.open.key-source=secret` 时以 `@Primary` 顶替 `ConfigAppKeyResolver`） | 见 §6 |

## §0 对账一 · 需求 → 设计

| AC | 验收（完成度复核 §3.5 · 表结构说明 §3） | 落点 |
|---|---|---|
| AC1 | 封存再解开得到原文；库里只有密文：没有明文、没有数据密钥原文；同一明文两次封存密文不同 | `DefaultSecretVault#seal/open` · `EnvelopeCipher` · `sec_secret_blob` |
| AC2 | 附加认证数据绑定 `id + namespace + owner_type + owner_id + env`：密文（连同包裹的数据密钥、甚至按新行算对的 `aad_sha256`）挪到别的行、或改归属，即解密失败 | `EnvelopeCipher#aad` · GCM AAD 两层都带 |
| AC3 | 进程只能解本命名空间、本环境；没有本域 ACTIVE 主密钥封不了；没配 `shop.secret.env` 拒绝加解密 | `DefaultSecretVault#open` · `MasterKeyBook#activeFor` · `SecretSettings` |
| AC4 | 轮换：新版本启用后旧版本 ROTATING、`overlap_until = now + overlap_days`，重叠期内两把都能验签；过了重叠期即不再接受（不等任务）；任务把它转 RETIRED；冻结旧版本的单据仍能取材料（RETIRED 可用） | `DefaultKeyRegistry#enable/verificationMaterials/materialOf` · `SecretLifecycleService` |
| AC5 | 两人控制：启用人 ≠ 录入人；策略要求时只有 VERIFIED 能启用；同一把密钥同一时刻一个 ACTIVE | `DefaultKeyRegistry#enable` · `uk_sec_key_version_2(active_key)` |
| AC6 | 吊销立即失效：新调用、验签、冻结版本取材料一律失败，不自动换版本 | `DefaultKeyRegistry#revoke` |
| AC7 | 到期：`warn_before_days` 内每个版本记一次 `EXPIRING_SOON`；到期后密钥 EXPIRED、新调用失败 | `SecretLifecycleService#expire` |
| AC8 | 平台生成的密钥明文只在发放时给一次，并写 `sec_access_log(REVEAL_ON_ISSUE)`；之后只有指纹 | `DefaultKeyRegistry#generate` · `IssuedSecret#toString` |
| AC9 | 主密钥轮换：注入新主密钥并标 active → 旧的 DECRYPT_ONLY；任务逐条重新包裹，`cipher_text` 一字不变；无引用后旧主密钥 RETIRED；撤掉旧主密钥后照样解得开；没包完就撤 → `NO_MASTER_KEY`（不是乱码） | `MasterKeyBook#sync` · `DefaultSecretVault#rewrap` · `SecretLifecycleService#rewrapAll` |
| AC10 | 日志抽样（TRACE，含 MyBatis 打印的 SQL 参数与失败分支的异常消息）不含任何密钥材料：明文、主密钥的原文 / Base64 / 十六进制 | 守卫 `SecretNeverLoggedTest` |
| AC11 | `db/secret` 独立目录、历史表 `sec_flyway_history`，在 H2 与本机 MySQL 9.7 上都能执行；七表实体与列对齐；不碰平台 / 支付历史表 | `SecretDataSourceConfig#migrateSecret` · `SecretStoreMigrationTest` · `SecretMigrationTest` |
| AC12 | 支付只存引用；接入方验签密钥可从 `sec_key(PAY, PARTNER_APP / INTERNAL_APP, pay_app.id, INBOUND, HMAC)` 取，轮换期两把都返回；找不到 / 不可用返回空表 | `pay.secret.SecretAppKeyResolver` |
| AC13 | `NoSecretDefaultInConfigTest` 保持绿；`ArchitectureTest`、`TableHasProducerTest`、`MapperSmokeTest`、`BackendI18nParityTest`、`SchemaDriftTest` 绿；全量不新增失败 | 守卫 |

**孤立项**：
- 没落点的 AC：无。复核 §3.5 的四个运营接口（录入凭据、启用复核、测试查单、发放接入方密钥）与「CN 凭据迁出 yml」**不在本片**（见偏差 1、2）。
- 挂不上 AC 的设计：`KmsMasterKeyProvider` 只是契约（复核 §3.5 要求「接口占位」），无测试。

## §1 现状与影响面

- **基线**：`codex/pay-i5-access`（P0 安全基线 + I5 S1–S4）。这里有 `NoSecretDefaultInConfigTest` 与 `pay.openapp.AppKeyResolver` / `ConfigAppKeyResolver`；`codex/ai-shop-codex` 两者都没有，所以本分支从 I5 分支拉出。
- **已有的引用列**：`pay_merchant.sec_key_id` / `sec_key_version`、`pay_onboarding_material.sec_secret_blob_id`（db/pay V10）—— 不改。
- **可复用**：`job` 模块的形状（零依赖 api + 自带迁移的 store + 引擎 core）；`PayDataSourceConfig` 的「同库、独立事务管理器、独立 Flyway 历史表、返回凭据而非 Flyway bean」做法；平台 `MybatisPlusInterceptor` 与 `MetaObjectHandler`。
- **会被改到的**：`backend/pom.xml`（加模块）· `shop-app/pom.xml`（依赖 secret-core）· `pay-domain/pom.xml`（依赖 secret-api）· `scripts/gen-test-schema.py` + `schema-test.sql`（接上 db/secret）· `SchemaDriftTest`（接上 db/secret）· `TableHasProducerTest`（登记 `sec_key_policy`）。
- **明确不受影响**：`pay-portal`、`pay.openapp`、`pay-channel`（另两个会话在改）；`ConfigAppKeyResolver` 原样保留，默认仍是它；平台 `MybatisPlusConfig` 的扫描与排除正则不动（`ai.neargo.secret` 不在 `ai.neargo.shop` 之下，全局扫描够不着）。

## §2 方案

### 契约变更
- 端点：无（运营接口随 I5 / 后续，见偏差 1）。
- 库表：新迁移目录 `db/secret`（`backend/secret/secret-store/src/main/resources/db/secret`），**V1 `sec_tables`**：`sec_master_key` · `sec_secret_blob` · `sec_key_policy` · `sec_key` · `sec_key_version` · `sec_key_event` · `sec_access_log`，DDL 与 `pay-service-review.sql` 第 1002–1194 行逐字一致（`diff` 为空）。历史表 `sec_flyway_history`。
- 权限码：无新增（`sec:key:*` 随运营接口）。`sec_access_log.accessor_role` 写 `sec:key:write`（表结构说明 §3.5 录入 / 生成版本的权限码）。
- i18n：无（密钥模块抛 `SecretException(Reason)`，不进平台 ErrorCode；调用方翻译）。
- 配置项（全部无默认值、生产 yml 不写字面量）：
  - `shop.secret.env`：`SANDBOX` / `PRODUCTION`；不配则拒绝加解密（不猜）。
  - `shop.secret.master-keys[n].alias / namespace / material / active`：注入的主密钥；`material` 为 32 字节 Base64，只经环境变量注入。
  - `shop.secret.flyway.enabled`（默认 true）· `shop.secret.flyway.locations`（默认 `classpath:db/secret`）。
  - `shop.job.secret-lifecycle.cron`（默认 `0 20 3 * * *`）。
  - `shop.pay.open.key-source=secret`：接入方验签密钥改从 `sec_key` 取（默认不开）。

### 模块设计
| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `backend/secret/pom.xml` | 聚合，只列模块（同 `job/pom.xml` 规矩） |
| 新增 | `secret-api` `ai.neargo.secret.api.{SecretVault, KeyRegistry, SecretRef, KeyPurpose, KeyOwner, KeyVersionRef, SecretNamespace, SecretException}` | 零依赖契约 |
| 新增 | `secret-store` `ai.neargo.secret.store.entity.{SecBaseEntity, Sec*×7}` · `store.mapper.SecretMappers` · `db/secret/V1__sec_tables.sql` | 不依赖 shop-base；测试支撑 `SecretStoreTestSupport`（真迁移，H2 默认、`-Dsecret.mysql.url` 时真 MySQL）打成 test-jar |
| 新增 | `secret-core` `ai.neargo.secret.core.{EnvelopeCipher, MasterKeyProvider, InjectedMasterKeyProvider, KmsMasterKeyProvider, MasterKeyBook, SecretSettings, SecretStoreMappers, KeyPolicy, DefaultSecretVault, DefaultKeyRegistry, SecretLifecycleService, SecretLifecycleJob}` | 只用 JDK `javax.crypto`；事务用 `TransactionTemplate`（宿主给 `secretTxManager`） |
| 新增 | `shop-app` `config.{SecretDataSourceConfig, SecretProperties, SecretLifecycleSchedule}` | 装配；任务外壳（`shop.job.enabled`） |
| 新增 | `pay-domain` `pay.secret.SecretAppKeyResolver` | 开关 + `@Primary` |
| 修改 | `backend/pom.xml` · `shop-app/pom.xml` · `pay-domain/pom.xml` | 模块与依赖 |
| 修改 | `scripts/gen-test-schema.py` · `shop-app/src/test/resources/schema-test.sql`（生成物） · `SchemaDriftTest` | 接上 db/secret（与 db/pay 同口径） |
| 修改 | `TableHasProducerTest` | `sec_key_policy` 进 `NO_PRODUCER`（无策略行时用 DDL 默认值；运营维护随密钥运营接口） |

### 关键接口

```java
// secret-api
interface SecretVault {
    SecretRef seal(KeyOwner owner, byte[] plaintext);           // SENSITIVE_FIELD
    byte[] open(SecretNamespace namespace, SecretRef ref);
}
interface KeyRegistry {
    long register(NewKey key);                                  // 同归属×用途×环境已存在则返回原 id
    Optional<KeyInfo> find(KeyOwner owner, KeyPurpose purpose);
    KeyVersionRef addVersion(long keyId, NewVersion version);   // DRAFT
    IssuedSecret generate(long keyId, int lengthBytes, String issuedBy, String reason); // 明文只此一次 + 访问记录
    void markVerified(KeyVersionRef ref, String evidence, String operator);
    void enable(KeyVersionRef ref, String operator, String approvalRef);
    void revoke(KeyVersionRef ref, String operator, String reason);
    KeyVersionRef current(long keyId);                          // 新调用
    byte[] materialOf(KeyVersionRef ref);                       // 冻结版本：ACTIVE / ROTATING / RETIRED
    List<byte[]> verificationMaterials(long keyId);             // 入站验签：ACTIVE + 重叠期内 ROTATING
    String fingerprintOf(KeyVersionRef ref);
}
record KeyPurpose(Direction direction, Usage usage)            // INBOUND_HMAC / OUTBOUND_SIGN / OUTBOUND_ENCRYPT / COUNTERPARTY_VERIFY
record SecretRef(long blobId)                                    // 业务表存的就是它
record KeyVersionRef(long keyId, int versionIndex)               // 业务单据冻结的就是它

// secret-core
interface MasterKeyProvider {                                    // 只有包裹 / 解包，没有「取出主密钥」
    String providerType(); List<Descriptor> declared(); boolean has(String alias);
    byte[] wrap(String alias, byte[] dataKey, byte[] aad); byte[] unwrap(String alias, byte[] wrapped, byte[] aad);
}
```

**封存写两次**：AAD 含行自己的自增 id，插入前不知道 —— 先插占位行（密文列空串）拿 id，再加密回填，同一事务。密钥版本多一步：先占位密文、再插版本（`ck_sec_key_version_1` 要求有密文 id）、最后带版本 id 回填密文归属。

**指纹**：对称密钥 = 以材料为 HMAC 密钥对固定标签做 HMAC-SHA256、取前 32 个十六进制字符；公钥 / 证书 = PEM 的 SHA-256。

**生成的密钥**：材料是 Base64URL 文本本身（≥ 32 字节随机数编码），对方拿到的字符串按 UTF-8 字节即 HMAC 密钥，与 `AppAuthServiceImpl` 现在对配置密钥的用法一致，两边不会差一层编码。

## §3 选型

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| A 平台级 `secret` 模块，进程内库 | 全平台一处登记 / 轮换 / 审计；依赖方向正确；明文不走网络 | 多一个模块与一套表 | ✅ 采用（复核 D-5 / §6 Q1 已定） |
| B 表放在支付内 | 少一层 | 第二个域要密钥时复制或反向依赖支付 | ❌ |
| C 远程密钥服务返回明文 | 集中 | 明文走网络 | ❌ |
| 主密钥：注入式（本片）→ KMS | 注入式零外部依赖、可本地测试；接口按 KMS 形状（只有 wrap / unwrap）设计 | 注入式的主密钥在进程内存里 | ✅ 注入式先行；`KmsMasterKeyProvider` 契约占位，选型随生产部署定 |
| Mapper：MyBatis-Plus（与支付一致） | 与宿主同一套拦截器 / 填充器 | secret-store 带上 MyBatis | ✅ 任务要求「entities + mappers」；job-store 的 JdbcClient 路线因此不照搬 |

不可逆部分（密钥归属平台模块）已在复核 §6 Q1 确认，不另开 ADR（见偏差 6）。

## §4 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 主密钥丢失 | 该命名空间全部密文不可恢复 | 注入式：主密钥材料由运维按 KMS / 密码库备份；`sec_master_key` 只存名字，DR 演练随 W5 交付标准「轮换演练一次」 |
| 没重新包裹完就撤旧主密钥 | 那部分密文解不开 | 解密报 `NO_MASTER_KEY`（`droppingOldMasterKeyTooEarlyFailsLoudly`）；任务对缺主密钥的批次计失败（`SECRET_LIFECYCLE_PENDING`），旧主密钥不会被标 RETIRED |
| `shop.secret.env` 漏配 | 加解密全部失败 | 刻意如此（不猜沙箱 / 生产）；有主密钥却没配环境时启动即失败（`MasterKeyBook#sync`） |
| MyBatis DEBUG 日志打印 SQL 参数 | 若有人把明文写进任何一列就会进日志 | 明文从不进 SQL；`SecretNeverLoggedTest` 在 TRACE 下抽样兜底 |
| `SecretAppKeyResolver` 开关打开但接入方密钥未迁入 | 该接入方验签全部失败 | 默认关；先登记、验证、两人启用，再切开关 |

## §5 对账三 · 实现 → 需求（测试）

先写测试（编译不过即红），再实现。`secret-core` 的 14 条与 `secret-store` 的 1 条**在 H2 与本机 MySQL 9.7.4 上各跑一遍**
（`-Dsecret.mysql.url=jdbc:mysql://127.0.0.1:3306/`，每个用例一个新库 `sec_t<pid>_<n>`，真迁移建表）：均 15/15。

消融用脚本逐条做：改一行 → **删掉 `target/classes` 再编** → 跑对应测试 → 还原并 `touch`。
（第一轮消融的还原没 `touch`、恢复后的源文件比消融时编出的 class 旧，增量编译留下了消融版的 class；
在 MySQL 那轮里表现为「访问记录没写」的假红。删 class 重编后两库都绿，消融全部重做，下表是重做的结果。）

| AC | 测试方法 | 跑过 | 消融验证（撤掉的那一行 → 变红的用例） |
|---|---|---|---|
| AC1 | `EnvelopeRoundTripTest#sealThenOpenRoundTrips` | ✅ H2 / MySQL | 由 AC2 的 A1 覆盖（同一条加解密路径） |
| AC2 | `EnvelopeRoundTripTest#ciphertextMovedToAnotherRowFails`（攻击者把密文、包裹的数据密钥搬到自己的行，并按自己的行算对 `aad_sha256`；再改归属 id） | ✅ | **A1** 去掉 `c.updateAAD(aad)` → `ciphertextMovedToAnotherRowFails:66` 红 ✅ |
| AC3 | `EnvelopeRoundTripTest#namespaceIsolation` · `#noEnvRefuses` · `#sandboxCannotOpenProduction`（同一个库，生产进程封、沙箱进程开） | ✅ | **A2** 去掉 `open` 的命名空间 / 环境比对 → `namespaceIsolation:86`、`sandboxCannotOpenProduction:119` 红 ✅ |
| AC4 | `KeyRotationTest#rotationOverlapThenRetire` | ✅ | **A4** 重叠期不判 → `:67`「过了重叠期，任务还没跑也不再接受」红；**A6** 任务不退役 → `:70` 红；**A7** 启用不降旧 ACTIVE → `:57` `DuplicateKey`（`uk_sec_key_version_2`）红 ✅ |
| AC5 | `KeyRotationTest#dualControlAndVerifyBeforeEnable` | ✅ | **A3** 去掉「启用人 ≠ 录入人」→ `:90` 红 ✅ |
| AC6 | `KeyRotationTest#revokeIsImmediate` | ✅ | **A5** 冻结可用状态里加上 REVOKED → `:107` 红 ✅ |
| AC7 | `KeyRotationTest#expiryWarnsOnceThenExpires` | ✅ | **A8** 到期不改 EXPIRED → `:136` 红 ✅ |
| AC8 | `KeyRotationTest#generatedSecretRevealedOnceAndLogged` · `#publicMaterialStoredInClear` | ✅ | **A11** 不写 `sec_access_log` → `:154` 红 ✅ |
| AC9 | `MasterKeyRotationTest#rewrapMovesEveryBlobToTheNewMasterKey` · `#droppingOldMasterKeyTooEarlyFailsLoudly` | ✅ | **A9** 重新包裹仍用旧主密钥 → `:50` 红 ✅ |
| AC10 | 守卫 `SecretNeverLoggedTest#noKeyMaterialInAnyLogLine`（根日志 TRACE，抓到的日志行 > 20 条、失败分支 3 个为对照量；MyBatis 的 `==> Parameters:` 在内） | ✅ | **A10** 在 `fill` 里 `debug` 一次明文 → `:105`「日志 / 异常里出现了密钥材料」红 ✅ |
| AC11 | `SecretStoreMigrationTest`（H2 + MySQL）· shop-app `SecretMigrationTest`（H2；`-Dsecret.mysql.url` 时 MySQL）· `SchemaDriftTest` · `MapperSmokeTest`（7 个新 mapper 进上下文、对自己的表 SELECT） | ✅ | — |
| AC12 | `SecretAppKeyResolverTest`（2 条） | ✅ | **A12** INTERNAL 也映射成 PARTNER_APP → `internalAndMissingCases:58` 红 ✅ |
| AC13 | 守卫与全量：见下 | ✅ | — |

还原后基线（同一脚本末尾，删 class 重编）：`SecretStoreMigrationTest` 1 · `EnvelopeRoundTripTest` 5 · `MasterKeyRotationTest` 2 · `KeyRotationTest` 6 · `SecretNeverLoggedTest` 1 · `SecretAppKeyResolverTest` 2，全绿。

守卫（`mvn -o -pl shop-app -am test -Dtest=…`，2026-10-26 14:48–14:50 +04）：
`ArchitectureTest` 20 · `BackendI18nParityTest` 6 · `NoSecretDefaultInConfigTest` 1 · `TableHasProducerTest` 2 · `MapperSmokeTest` 1 · `SchemaDriftTest` 4 ·
`ScheduledJobConventionTest`（定时任务约定）6 · `PayMigrationTest` 3 · `SecretMigrationTest` 3（1 跳过 = MySQL 那条未给 URL），全绿。
扫描面：`ArchitectureTest` 只导入 `ai.neargo.shop`（`noBeanMayExposeFlyway` 导入 `ai.neargo`）—— `ai.neargo.secret` 的分层规则它不管；
`TableHasProducerTest` 扫 `backend/*` 与 `backend/*/*` 两层模块，`secret-*` 在内。

全量 `mvn -o -pl shop-app -am test`（2026-10-26 14:56–15:28 +04，32 分钟）：**BUILD SUCCESS，6443 条，失败 0、错误 0、跳过 164**（跳过为既有 `@Disabled` / 条件跳过）。
本机 MySQL 9.7.4：shop-app `SecretMigrationTest` 指向临时库 `ai_shop_secret_w5_scratch`（3/3，`sec_flyway_history` v1 `sec tables` success，七表齐）；
secret-core / secret-store 全部 15 条另在每用例新建的 MySQL 库上跑过（跑完已删）。

## §6 对账二 · 设计 → 实现

```
d016f1bfe feat(secret): W5 platform key module — api, store (db/secret V1), envelope core
 backend/pom.xml · backend/secret/pom.xml · secret-api/pom.xml
 secret-api  …/api/{KeyOwner, KeyPurpose, KeyRegistry, KeyVersionRef, SecretException, SecretNamespace, SecretRef, SecretVault}.java
 secret-core/pom.xml
 secret-core …/core/{DefaultKeyRegistry, DefaultSecretVault, EnvelopeCipher, InjectedMasterKeyProvider, KeyPolicy,
                     KmsMasterKeyProvider, MasterKeyBook, MasterKeyProvider, SecretLifecycleJob, SecretLifecycleService,
                     SecretSettings, SecretStoreMappers}.java
             test {EnvelopeRoundTripTest, KeyRotationTest, MasterKeyRotationTest, SecretFixture, SecretNeverLoggedTest}.java
 secret-store/pom.xml
 secret-store …/entity/{SecBaseEntity, SecAccessLog, SecKey, SecKeyEvent, SecKeyPolicy, SecKeyVersion, SecMasterKey, SecSecretBlob}.java
              …/mapper/SecretMappers.java · db/secret/V1__sec_tables.sql
              test {SecretMigrationH2Test, SecretStoreTestSupport}.java
 42 files changed, 3049 insertions(+)

53aabd657 feat(secret): wire W5 key module into shop-app and pay
 backend/pay/pay-domain/pom.xml · pay/secret/SecretAppKeyResolver.java · test SecretAppKeyResolverTest.java
 backend/scripts/gen-test-schema.py
 secret-core/pom.xml · secret-store/pom.xml（MySQL 驱动，测试作用域）
 SecretMigrationH2Test.java => SecretStoreMigrationTest.java · SecretStoreTestSupport.java（可选 MySQL）
 backend/shop-app/pom.xml · config/{SecretDataSourceConfig, SecretLifecycleSchedule, SecretProperties}.java
 test config/SecretMigrationTest.java · arch/TableHasProducerTest.java · infra/SchemaDriftTest.java · schema-test.sql
 16 files changed, 828 insertions(+), 19 deletions(-)
```

| 差异 | 说明 |
|---|---|
| TDD 里没有、实际改了的文件 | 无。`SecretMigrationH2Test` 第二个提交里改名 `SecretStoreMigrationTest`（它现在也跑 MySQL） |
| TDD 列了、实际没动的文件 | 无 |

### 偏差说明

1. **运营接口不在本片**：复核 §3.5 的四个 `/pay-ops/v1/...credentials` 接口在 `pay-portal`（本会话被要求避开，I5 会话在改）。`KeyRegistry` 已备齐它们要调的动作（录入 / 生成并发放一次 / 测试通过 / 两人启用 / 吊销 / 指纹），接口落地时只做接入层。`sec_key_policy` 因此暂无写入方（`TableHasProducerTest` 已登记理由），无策略行时用 DDL 默认值。
2. **CN 凭据迁出 yml 不在本片**：要动 `pay-channel`（另一会话在改）与运维录入流程；`ConfigAppKeyResolver` → `SecretAppKeyResolver` 已做成开关（默认关），迁入并启用接入方密钥后切 `shop.pay.open.key-source=secret`。
3. **两人控制对启用一律生效**，不看策略的 `dual_control`：库上的 `ck_sec_key_version_2`（`enabled_by <> entered_by`）在 MySQL 9 上执行、不分策略，服务层与它保持一致；`dual_control` 只留给「吊销是否需要复核」（吊销允许一人先做、事后补复核，§3.5），本片吊销一人即可。
4. **`use_count` / `last_used_at` 未刷新**：表结构说明要求「异步刷新」，需要一个调用计数缓冲与定时落库，随首个真实调用方（渠道凭据）一起做；本片的验签 / 取材料路径不写库。
5. **吊销当前版本时密钥状态不变**、`current_version` 置 0：`current()` 因此报 `VERSION_UNUSABLE`，不自动换版本（§3.4）。表结构说明没规定此时 `sec_key.status` 该是什么，暂不新增状态语义。
6. **没另开 ADR**：「密钥归属平台级模块」已由用户在复核 §6 Q1 确认、记在 D-5，本 TDD §3 引用它。KMS 选型（不可逆部分）随生产部署另定。
7. **测试支撑跑真迁移，不读生成的 H2 脚本**（与 job-store 不同）：`sec_*` 不 ALTER 平台表，H2 MySQL 模式能原样执行，于是「迁移在 H2 上跑得通」每个用例都验一遍；H2 实际执行了两条 CHECK。
8. **I4 留下的 `ck_pay_merchant_2`**（MERCHANT_OWN 必须有密钥引用）是 db/pay 的事，仍待渠道凭据迁入后由支付另起迁移加回。
9. **生成文档未重跑**：`node scripts/gen-glossary.mjs --check` 在基线上已经是陈的（I5 的 pay-portal 常量与 `err.pay.app_refund_limit`），本片新增模块会让「依赖清单」多 4 行；为不把别人的产物混进本提交，没有重跑，合并后统一重新生成。`gen-perm-endpoint-matrix` 在基线上就红（P0 的 `/ops/auth/login/verify`），与本片无关。

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-10-26 | 方案按复核 §3.5 / 表结构说明 §3、§6.8 落地，开始实现 |
| 2026-10-26 | 已实现；secret 全部用例 H2 + MySQL 9.7 绿，消融 12 条全红；守卫 `ArchitectureTest`（`ai.neargo.shop` / `ai.neargo`）· `TableHasProducerTest`（`backend/*`、`backend/*/*`）· `MapperSmokeTest` · `BackendI18nParityTest` · `SchemaDriftTest` · `NoSecretDefaultInConfigTest` · 定时任务约定 绿；`check-sql-portability` 无新增；全量 6443 条 0 失败 |
