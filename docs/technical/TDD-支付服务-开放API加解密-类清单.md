# TDD-支付服务-开放API加解密-类清单

状态：**草稿**（随 [TDD-支付服务-开放API加解密与密钥分发](./TDD-支付服务-开放API加解密与密钥分发.md) v2.2 一起确认）
后续：本篇 K2–K6 的实施归入[开发计划-支付接入守卫与独立部署](./开发计划-支付接入守卫与独立部署.md) M3（类落在 `pay-guard`，包根 `ai.neargo.shop.pay.api.guard`）
配套：[API 清单](./TDD-支付服务-开放API加解密-API清单.md) · [数据库设计](./TDD-支付服务-开放API加解密-数据库设计.md)
创建：2026-10-26 · 最后更新：2026-10-26

> **基线**：分支 `claude/silly-gates-90cc7e`（W5 密钥模块 + I5 接入层，**已合入** `codex/ai-shop-codex`）。表中「已有」指那个分支上的类；
> 「改」写清改什么；「新」给出包与职责。包规划遵循 [references/java.md §七](../../.claude/skills/ai-shop-dev-standards/references/java.md)、
> `PayHasNoControllerTest`（支付域不得有 controller，controller 只在 `pay-portal`）与 `ArchitectureTest`。

---

## §0 一张图

```
                 接入方                                   开发者控制台（接入方本人）          运营
                   │ 签名请求 / 收推送                          │ /pay-dev/v1                     │ /pay-ops/v1
┌──────────────────▼───────────────────────── pay-portal ───────▼─────────────────────────────────▼───────────┐
│ auth: OpenSignatureAuthenticator（改）· DevSessionAuthenticator（新）· OpenResponseSigningFilter（新）       │
│       SensitiveFieldDecryptor（新）                                                                         │
│ open: OpenCertificateController · OpenPingController（新）   dev: Dev*Controller（新）  ops: Ops*Controller │
└──────────────────┬───────────────────────────────────────────────────────────────────────────────────────────┘
                   │ 应用服务接口
┌──────────────────▼──────────────── pay-domain ─────────────────────────────────────────────────────────────┐
│ openapp: AppAuthServiceImpl（改）· AppSignatureVerifier（新，取代 AppKeyResolver）· SignatureCanonicalizer（改）│
│          OpenFieldCipher · OpenResourceCipher（新）· PartnerKeyService · PlatformKeyService（新）              │
│          DeveloperAccountService · DeveloperActivationService（新）· PayOpenHousekeepingJob（新）              │
│ notify:  WebhookEnvelopeFactory（新）· WebhookSender（新，投递本体随 I5）                                       │
└──────────────────┬─────────────────────────────────────────────────────────────────────────────────────────┘
                   │ 只依赖 secret-api
┌──────────────────▼──────────────── secret（平台级）────────────────────────────────────────────────────────┐
│ api:  KeyRegistry（改）· SecretVault（已有）· KeyVault（新）· StorageTier · SecretFormat（新）                 │
│ core: RoutingKeyVault（新）── TierBackend ─┬ ConfigTierBackend            L1                               │
│                                           ├ DatabaseTierBackend          L2 / L3（复用 EnvelopeCipher）      │
│                                           │     └ MasterKeyProvider: InjectedMasterKeyProvider（已有，L2）  │
│                                           │                          KmsMasterKeyProvider（已有契约，L3 实现新）│
│                                           ├ ExternalSecretTierBackend    L4                               │
│                                           └ RemoteCryptoBackend          L5（不可导出，转调 KMS / HSM）       │
│       KeyTierPolicy · MaterialCache · LocalCrypto · KeyFingerprints · KeyTierMigrationJob（新）            │
└────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

**依赖方向**：`pay-portal → pay-domain → secret-api ← secret-core`；业务代码只认 `KeyVault` / `KeyRegistry` / `SecretVault` 三个接口，不认任何一档的实现。

---

## §1 secret-api（`backend/secret/secret-api`，包 `ai.neargo.secret.api`）

| 动作 | 类 | 说明 |
|---|---|---|
| **新** | `KeyVault` | 按「操作」抽象的密钥运算入口（TDD §2.8.2；S3）。**不含** `seal / unseal` —— 那是已有的 `SecretVault`，不重复 |
| **新** | `StorageTier` | `L1`–`L5`，带 `exportable()`（L5 为 false） |
| **新** | `SecretFormat` | 平台生成对称密钥的形态：`ALNUM_32`（AppSecret / EncryptKey）· `BASE64URL_32B`（已有形态，终端设备密钥继续用） |
| **改** | `KeyRegistry` | 加 `generate(long keyId, SecretFormat format, String displayPrefix, String issuedBy, String reason)`；加 `List<KeyVersionRef> verificationVersions(long keyId)`（取代返回材料的 `verificationMaterials`，后者标 `@Deprecated` 保留到 `SecretAppKeyResolver` 删除）；加 `Optional<KeyVersionRef> findVersion(long keyId, String externalKeyId)`；`NewVersion` 加 `storageTier`、`storageRef`；`materialOf` 对不可导出档抛 `NOT_EXPORTABLE` |
| **改** | `KeyPurpose` | 加常量 `PARTNER_DATA = (INBOUND, ENCRYPT)`、`INBOUND_VERIFY = (INBOUND, VERIFY)`；平台解密沿用 `PARTNER_DATA` 的方向与用途，靠 `owner_type = PLATFORM_OPEN` 区分 |
| **改** | `SecretException.Reason` | 加 `NOT_EXPORTABLE` · `TIER_NOT_ALLOWED` · `KEY_SERVICE_UNAVAILABLE` |
| 已有 | `SecretVault` · `KeyOwner` · `KeyVersionRef` · `SecretRef` · `SecretNamespace` | 不动 |

```java
/** 密钥运算的唯一入口。五档存储同一个接口：L5 的密钥取不出来，所以这里没有「取明文」。 */
public interface KeyVault {
    /** HMAC-SHA256：P1 / P2 基础方案、P7 指纹 */
    byte[] mac(KeyVersionRef ref, byte[] data);
    /** SHA256withRSA / SHA256withECDSA（按密钥算法）：P2 增强方案 */
    byte[] sign(KeyVersionRef ref, byte[] data);
    /** AES-256-GCM，IV 由实现生成，返回 IV ‖ 密文 ‖ Tag：P4 / P5 */
    byte[] encrypt(KeyVersionRef ref, byte[] plaintext, byte[] aad);
    /** 对称密钥：AES-256-GCM（输入 IV ‖ 密文 ‖ Tag）；RSA 私钥：RSA-OAEP-SHA256，aad 必须为空：P3 */
    byte[] decrypt(KeyVersionRef ref, byte[] ciphertext, byte[] aad);
    StorageTier tierOf(KeyVersionRef ref);
}
```

---

## §2 secret-core（`backend/secret/secret-core`，包 `ai.neargo.secret.core`）

| 动作 | 类 | 说明 |
|---|---|---|
| **新** | `RoutingKeyVault` | `KeyVault` 的唯一实现：读 `sec_key_version.storage_tier` → 可导出档从对应 `TierBackend` 取材料（经 `MaterialCache`）后交 `LocalCrypto` 算；L5 交 `RemoteCryptoBackend`。运算前校验版本状态（入站验签 ACTIVE / 重叠期 ROTATING；出站只 ACTIVE；冻结版本按 §3.4） |
| **新** | `TierBackend` | SPI：`StorageTier tier()` · `byte[] load(VersionRow v)` · `String store(VersionRow v, byte[] material)`（返回 `storage_ref`） |
| **新** | `ConfigTierBackend` | L1：读 `shop.secret.l1.{storageRef}`；生产环境拒绝装配 |
| **新** | `DatabaseTierBackend` | L2 / L3：复用已有 `EnvelopeCipher` + `MasterKeyBook`；L2 与 L3 的区别只在 `MasterKeyProvider` 是注入式还是 KMS（S4） |
| **新** | `ExternalSecretTierBackend` · `ExternalSecretClient` | L4：`storage_ref = 路径#版本`；客户端接口 + 第一个实现（Vault KV v2 或云凭据管家，Q-C1） |
| **新** | `RemoteCryptoBackend` | L5 SPI：`mac / sign / decrypt / generateKeyPair`，密钥不出设备；第一个实现随 Q-C1 |
| **新（实现已有契约）** | `<Provider>KmsMasterKeyProvider` | `KmsMasterKeyProvider` 的第一个实现（L3）；`aad` 作为 KMS 加密上下文 |
| **新** | `KeyTierPolicy` | `default-tier`、`min-tier.{usage}`、`forbid-tiers-in-prod`；启动校验配置、运行时校验每次写入（TDD §2.8.4） |
| **新** | `MaterialCache` | 进程内 LRU：键 `(keyId, version)`，**TTL 60 秒**（安全规范 §2.3「不缓存超过 60 秒」，API 清单 C9），上限 10k；值为不可变副本，淘汰时清零 |
| **新** | `LocalCrypto` | HMAC-SHA256、SHA256withRSA / ECDSA、AES-256-GCM（IV 内部生成）、RSA-OAEP-SHA256（显式 `OAEPParameterSpec`，MGF1-SHA256）；常量时间比较 |
| **新** | `KeyFingerprints` | P7：对称密钥用指纹密钥做 HMAC，公钥 / 证书用 SHA-256(DER)；取代 `DefaultKeyRegistry` 里现有的指纹算法 |
| **新** | `KeyRevocationBroadcaster` · `RedisKeyRevocationBroadcaster` · `NoopKeyRevocationBroadcaster` | 吊销 / 退役后广播 `sec.key.revoked`，各节点清 `MaterialCache`；无 Redis 时只靠 TTL |
| **新** | `KeyTierMigrationJob` | 可导出档之间搬迁（读 → 写新档 → 比指纹 → 改 `storage_tier/storage_ref`），写 `sec_key_event(TIER_MIGRATED)` |
| **改** | `DefaultKeyRegistry` | 实现 §1 的新方法；`generate` 按 `SecretFormat` 生成（`ALNUM_32` 用 `SecureRandom` 逐字符取 62 字母表，拒绝采样避免偏差）；`insertVersion` 的「至少一种材料」校验放宽到含 `storage_ref`；写入时过 `KeyTierPolicy`；吊销后调 `KeyRevocationBroadcaster` |
| **改** | `SecretSettings` | 加档位配置项 |
| 已有 | `EnvelopeCipher` · `MasterKeyBook` · `InjectedMasterKeyProvider` · `DefaultSecretVault` · `SecretLifecycleService/Job` | 不动 |

---

## §3 secret-store（包 `ai.neargo.secret.store`）

| 动作 | 类 | 说明 |
|---|---|---|
| **改** | `entity.SecKeyVersion` | 加 `storageTier`、`storageRef`、`displayPrefix` |
| **新** | `db/secret/V2__sec_key_storage_tier.sql` | 见数据库设计 §3 |
| **改** | `SecretStoreMigrationTest` | 覆盖 V2（H2 + 本机 MySQL） |

---

## §4 pay-domain（`backend/pay/pay-domain`）

### 4.1 `ai.neargo.shop.pay.openapp` —— 验签与密钥分发

| 动作 | 类 | 说明 |
|---|---|---|
| **新** | `AppSignatureVerifier` | 取代 `AppKeyResolver`：`Optional<Verified> verify(PayApp app, String keySerial, String toSign, String signature)`，`Verified(int keyVersion)`。按 `pay_app.sign_type` 走 HMAC（`KeyVault.mac` + 常量时间比较）或 RSA / ECDSA（`sec_key_version.public_material`）。不再把材料交给调用方 —— L5 的 AppSecret 也能验 |
| **新** | `ConfigAppSignatureVerifier` | 试点过渡（原 `ConfigAppKeyResolver` 的行为）：密钥在部署配置里；`shop.pay.open.key-source=config` 时生效 |
| **新** | `SecretAppSignatureVerifier`（包 `pay.secret`） | `key-source=secret` 时以 `@Primary` 生效，取代 `SecretAppKeyResolver` |
| **删** | `AppKeyResolver` · `ConfigAppKeyResolver` · `pay.secret.SecretAppKeyResolver` | 被上面三个取代；测试 `SecretAppKeyResolverTest` 改写为 `SecretAppSignatureVerifierTest` |
| **改** | `service.impl.AppAuthServiceImpl` | ① 用 `AppSignatureVerifier`；② 读 `X-Key-Serial`；③ 失败原因改为枚举 `AuthFailure`（`APP` · `ENV` · `IP` · `TIMESTAMP` · `SIGNATURE` · `REPLAYED`），由 portal 映射成错误码（API 清单 §1.3）；④ 结果带 `keyVersion` 写进 `pay_api_request_log.sign_key_version`。顺序不变（验签后抢 nonce，已是现状） |
| **改** | `service.AppAuthService` | `SignedRequest` 加 `keySerial`；`Result` 加 `AuthFailure failure`、`Integer keyVersion` |
| **改** | `SignatureCanonicalizer` | 加 `responseStringToSign(timestamp, nonce, bodySha256Hex)`（P2）；请求待签串不变（v1 冻结） |
| **新** | `OpenFieldCipher` | P3：基础方案 `Base64(IV‖密文‖Tag)` + AAD `AppId|字段路径` 的拆包与解密（经 `KeyVault.decrypt`）；增强方案 RSA-OAEP 经平台加密密钥 |
| **新** | `OpenResourceCipher` | P4 / P5：生成 `resource` 信封（nonce 单独字段、`ciphertext` = 密文 ‖ Tag）、对账单文件加密 |
| **新** | `OpenSensitiveFields` | `x-sensitive` 字段清单的唯一来源（端点 → 字段路径），portal 解密、OpenAPI 生成、测试都读它 |
| **新** | `KeySerials` | 生成 `S` + `yyyyMMdd` + 2 位序号（同日多次重置递增） |
| **新** | `service.PartnerKeyService` · `impl.PartnerKeyServiceImpl` | `issueAppSecret`（`ALNUM_32` + `display_prefix`，返回 40 位一次）· `issueEncryptKey` · `markEncryptKeyDeployed` · `endOverlap` · `revoke`（同时临时置 `daily_refund_limit_minor = 0`）· `newChallenge` · `uploadPublicKey`（P8 持有证明）· `listCredentials`（只回 KeySerial、状态、指纹） |
| **新** | `service.PlatformKeyService` · `impl.PlatformKeyServiceImpl` | 平台签名 / 加密密钥对：`certificates()`、`rotate(purpose)`（L5 在 KMS 内生成）、`enable`（两人） |
| **新** | `service.DeveloperAccountService` · `impl.…Impl` | 开发者登录、第二因子、失败锁定、step-up、成员管理；会话进新令牌池 `dtk_` |
| **新** | `service.DeveloperActivationService` · `impl.…Impl` | 签发激活链接（令牌只存 SHA-256）、重发作废、激活设密码 |
| **新** | `entity.PayAppDeveloper` · `entity.PayAppActivation` · `entity.PayAppKeyChallenge` | 数据库设计 §4 |
| **改** | `entity.PayApp` | 加 `signType` |
| **改** | `mapper.openapp.OpenAppMappers` | 加三张新表的 Mapper |
| **新** | `PayOpenHousekeepingJob` | 每日清理激活令牌、challenge（数据库设计 §4.3） |

### 4.2 `ai.neargo.shop.pay.notify` —— 推送（新包）

| 动作 | 类 | 说明 |
|---|---|---|
| **新** | `WebhookEnvelopeFactory` | 由 `pay_biz_notify` 行组装 §3 的推送体：冻结 `enc_key_version` / `sign_key_version`（首投时写入，重推读取），调 `OpenResourceCipher` 加密、`KeyVault` 签名 |
| **新** | `WebhookSender` | HTTP 投递（5s 超时、防 SSRF 二次解析）；投递本体、退避与 `pay_biz_notify_attempt` 记录随 I5 一起实现，本方案只规定它调 `WebhookEnvelopeFactory` |
| **改** | `access.entity.PayApiRequestLog` | 加 `signKeyVersion`、`encryptSerial` |
| **改** | `entity.PayBizNotify`（随 I5 新建） | 加 `encKeyVersion`、`signKeyVersion` |

---

## §5 pay-portal（`backend/pay/pay-portal`，包 `ai.neargo.shop.pay.api`）

| 动作 | 类 | 说明 |
|---|---|---|
| **改** | `PortalFace` | 加 `DEV("dev", "/pay-dev/v1")`（待 TDD Q2 定位置；定为 ops-web 内页面时撤销此项） |
| **改** | `PortalRouteRules` · `PortalRouteRulesTest` | 覆盖第六个入口 |
| **改** | `auth.OpenSignatureAuthenticator` | 读 `X-Key-Serial` 传给 `AppAuthService`；把 `AuthFailure` 放进请求属性供错误映射 |
| **改** | `auth.PortalAuthFilter` · `common.PortalExceptionHandler` | `AuthFailure` → API 清单 §1.3 的错误码；生产只给统一文案，沙箱附细节 |
| **新** | `auth.DevSessionAuthenticator` | `/pay-dev/v1`：开发者会话 + 角色；运营会话一律 401 |
| **新** | `auth.OpenResponseSigningFilter` | 包装响应（缓存响应体）→ 算 P2 → 加 `X-Timestamp` · `X-Nonce` · `X-Key-Serial`/`X-Platform-Serial` · `X-Signature`；401 / 403 也签（AppId 不存在时不签） |
| **新** | `auth.SensitiveFieldDecryptor` | 校验链 ⑧：按 `OpenSensitiveFields` 在 JSON 树上就地解密，明文只活在本次请求；传明文 → `FIELD_ENCRYPT_REQUIRED` |
| **改** | `auth.PortalMasking` | 脱敏规则加 `X-Signature`、`resource.ciphertext`、`x-sensitive` 字段、`\bsk_(live|test)_[A-Za-z0-9]{32}\b` |
| **新** | `open.OpenCertificateController` | `GET /certificates` |
| **新** | `open.OpenPingController` | `POST /ping`；`@ConditionalOnProperty(shop.pay.open.environment=sandbox)`，生产不装配（404） |
| **改** | `open.OpenPaymentController` · `open.OpenRefundController` · `open.OpenViews` | 敏感字段只回掩码；对账单改为返回加密文件描述（API 清单 §2.2） |
| **新** | `dev.DevAuthController` | 激活、登录、第二因子、登出 |
| **新** | `dev.DevAppController` · `dev.DevCredentialController` · `dev.DevPublicKeyController` · `dev.DevWebhookController` · `dev.DevRequestLogController` · `dev.DevMemberController` | API 清单 §4 |
| **新** | `ops.OpsAppDeveloperController` · `ops.OpsAppCredentialController` · `ops.OpsPlatformKeyController` · `ops.OpsSecretTierController` | API 清单 §5（`ops` 子包随 I5 的运营端迁移一起建） |

---

## §6 shop-app 装配

| 动作 | 类 / 文件 | 说明 |
|---|---|---|
| **改** | `config.SecretDataSourceConfig` | 装配 `RoutingKeyVault`、各 `TierBackend`、`KeyTierPolicy`；生产检测到 L1 拒绝启动 |
| **改** | `config.SecretProperties` | `shop.secret.default-tier` · `min-tier.*` · `forbid-tiers-in-prod` · `kms.*` · `external.*` · `l1.*` |
| **改** | `config.SecurityConfig` | `/pay-dev/v1/**` 一条独立链（无 CORS、开发者令牌池） |
| **改** | `application.yml` | 上面的配置项，**不带默认密钥值**（`NoSecretDefaultInConfigTest`） |
| **改** | `i18n/messages*.properties` | API 清单 §1.3 的五个错误码三语 |
| **改** | `shop-base` `common.ErrorCode` | 同上五个 |
| **改** | `backend/scripts/gen-test-schema.py` | 支持 `ALTER … DROP CONSTRAINT` / `ADD CONSTRAINT`（数据库设计 §3 第 1 条） |

---

## §7 测试与守卫

| 类 | 管什么 |
|---|---|
| `KeyVaultContractTest`（抽象）+ 每档一个子类 | 同一组用例跑五档：mac / sign / encrypt-decrypt 往返、吊销后失败、L5 `materialOf` 抛 `NOT_EXPORTABLE` |
| `RoutingKeyVaultTest` | 同一把密钥不同版本在不同档共存；状态校验 |
| `KeyTierPolicyTest` | 生产 L1 拒绝启动；低于 `min-tier` 的写入被拒 |
| `MaterialCacheTest` | TTL 60 秒；广播后立即失效；淘汰清零 |
| `AlnumSecretGenerationTest` | `ALNUM_32` 字母表分布无偏（拒绝采样） |
| `OpenApiVectorsTest` | API 清单 §7 五组向量逐字比对（含前缀反例） |
| `AppAuthServiceTest`（改） | 失败枚举、`X-Key-Serial` 命中、重叠期两版本、密钥版本写入请求记录 |
| `SensitiveFieldDecryptorTest` | 明文被拒、字段路径被换后解密失败、OAEP MGF1-SHA1 统一报错 |
| `OpenResponseSigningFilterTest` | 每个响应带签名且可用 P2 验；401 也签 |
| `WebhookEnvelopeFactoryTest` | 重推用冻结版本、nonce 每次不同 |
| `PartnerKeyServiceTest` | 明文只给一次；运营会话不能生成；吊销同时把退款日限额置 0 |
| `DevSessionAuthenticatorTest` | 运营令牌打 `/pay-dev/v1` 一律 401 |
| `PortalMaskingTest`（改） | `sk_live_…` 模式被脱敏 |
| `ArchitectureTest` 新增一条 | `ai.neargo.shop.pay..` 里除 `LocalCrypto` 以外不得出现 `Mac.getInstance` / `Cipher.getInstance`（加解密只经 `KeyVault`），渠道网关按现状列白名单 |

---

## §8 实施切片（建议）

| 片 | 内容 | 依赖 |
|---|---|---|
| K1 | secret V2 + `StorageTier` / `SecretFormat` / `KeyVault` + `RoutingKeyVault` + L1 / L2 后端 + `KeyTierPolicy` + `MaterialCache` | W5 分支合入 |
| K2 | `AppSignatureVerifier` 替换 `AppKeyResolver`；错误码细分；`X-Key-Serial` | K1 |
| K3 | 响应签名、字段解密、`/certificates`（基础方案返回空）、`/ping` | K2 |
| K4 | pay 控制台三张表（加列已在 V14）+ 开发者控制台后端（激活、登录、生成 / 重置 / 作废） | K1；TDD Q2 定位置 |
| K5 | 推送信封（随 I5 webhook 投递） | K3 · I5 |
| K6 | L3 KMS 主密钥实现、L4 外部服务、L5 远程运算 + 平台密钥对 + 增强方案 | Q-C1 |

---

## 待确认

| # | 问题 | 倾向 |
|---|---|---|
| Q-C1 | KMS / 外部密钥服务先实现哪家（= TDD Q1） | 按首个生产部署所在云定 |
| Q-C2 | `KeyRegistry#verificationMaterials` 何时删除 | `SecretAppKeyResolver` 删掉的同一提交里删 |
| Q-C3 | 表结构说明的生成器不在仓库，K4 之前要找回或补写 | 补进 `scripts/gen-pay-schema-doc.mjs`，并在 pre-push 加 `--check` |

---

## §9 实施记录 · K1-DB（基于数据库保存密钥，2026-10-27，分支 `claude/key-store-db`，未提交）

范围：只做数据库的两档（L2 注入主密钥 / L3 KMS 主密钥，二者只差 `MasterKeyProvider`），加上 AppSecret / EncryptKey 的生成与保存。

| 文件 | 动作 |
|---|---|
| `secret-store/…/db/secret/V2__sec_key_storage_tier.sql` | 新：`sec_key_version` 加 `storage_tier`（默认 L2）、`display_prefix` |
| `secret-store/…/entity/SecKeyVersion` | 改：两个字段 |
| `secret-api/…/StorageTier` · `SecretFormat` · `KeyVault` | 新 |
| `secret-api/…/KeyRegistry` | 改：`generate(keyId, Generation)`、`verificationVersions`、`findVersion`；`IssuedSecret` 加 `externalKeyId` |
| `secret-api/…/KeyPurpose` · `SecretException` | 改：`PARTNER_DATA`、`Reason.WRONG_USAGE` |
| `secret-core/…/DatabaseKeyVault` · `SecretFormats` | 新 |
| `secret-core/…/DefaultKeyRegistry` · `DefaultSecretVault` | 改：按形态生成、KeySerial、写入存储档 |
| `pay-domain/…/openapp/service/PartnerKeyService` + `impl.PartnerKeyServiceImpl` | 新 |
| `shop-app/…/config/SecretDataSourceConfig` | 改：`DatabaseKeyVault` bean |
| `shop-app/src/test/resources/schema-test.sql` | 重新生成（+2 列） |
| 测试 | 新 `DatabaseKeyVaultTest`（8）、`PartnerKeyServiceTest`（5）；改 `SecretStoreMigrationTest`、`SecretMigrationTest`（迁移数 1 → 2）、`SecretAppKeyResolverTest`（桩补三个方法） |

**偏差说明**（与 §1–§4 对不上的地方，及原因）：

| # | 设计 | 实际 | 为什么 |
|---|---|---|---|
| D1 | `RoutingKeyVault` + `TierBackend` SPI | 只有 `DatabaseKeyVault implements KeyVault` | 只有一种后端时路由层是空壳；第二档落地时在 `KeyVault` 前面加路由，业务代码不变 |
| D2 | V2 含 `storage_ref` 并放宽 `ck_sec_key_version_1` | 只加 `storage_tier`、`display_prefix` | 材料都在库里时用不到；也就暂不需要给 `gen-test-schema.py` 补 `DROP CONSTRAINT` |
| D3 | `MaterialCache` 独立类 + 吊销广播 | `DatabaseKeyVault` 内部缓存，**每次运算都查一次版本状态** | 状态查询走唯一索引，代价小；换来吊销对所有节点的下一次运算立即生效，不需要广播 |
| D4 | P7 指纹改用「指纹密钥」HMAC | 沿用 W5 的 `symmetricFingerprint`（以材料为密钥对固定标签做 HMAC） | 已满足「不能用裸哈希离线比对」；改算法会让存量指纹全部变掉 |
| D5 | EncryptKey 的 KeySerial 与 AppSecret 同为 `S…` | EncryptKey 用 `E…`（`E2026102701`） | 同一接入方两把密钥同一天各自从 01 起，同前缀会撞号，控制台上分不清 |
| D6 | `PartnerKeyServiceImpl` 直接注入 `KeyRegistry` | 注入 `ObjectProvider<KeyRegistry>`，缺失时调用即失败 | 独立形态 `pay-svc` 扫描整个 `ai.neargo.shop.pay` 但不装密钥模块，直接注入会让它起不来（`PayApplicationBootTest` 抓到） |

**验证**：`secret-store` 2/2 · `secret-core` 22/22（新增 8）· `PartnerKeyServiceTest` 5/5 · `SecretAppKeyResolverTest` 2/2 · `PayApplicationBootTest` 6/6 · `OpenApiFlowTest` 17/17 · `ArchitectureTest` 20/20 · `SchemaDriftTest` 4/4 · `SecretMigrationTest` 3/3（1 个 MySQL 用例按设计跳过）· `TableHasProducerTest` · `NoSecretDefaultInConfigTest` · `MapperSmokeTest` · `BackendI18nParityTest` 全绿。
消融 6 次，各自只让目标用例变红：拒绝采样改取模 → `alnumAlphabetIsUnbiased`；缓存命中不查状态 → `revokedVersionFailsEvenWhenCached`；去掉用途校验 → `wrongUsageAndDraftRejected`；OAEP 用 Java 默认 MGF1-SHA1 → `rsaPrivateKeysSignAndDecrypt`；前缀对调 → `sandboxAppSecret` / `productionAppSecret`；放行内部接入方 → `onlyActivePartnersGetKeys`。
**未跑**：后端全量测试与 `pre-push` 全套闸门。
