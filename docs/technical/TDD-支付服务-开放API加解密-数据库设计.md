# TDD-支付服务-开放API加解密-数据库设计

状态：**草稿**（随 [TDD-支付服务-开放API加解密与密钥分发](./TDD-支付服务-开放API加解密与密钥分发.md) v2.1 一起确认）
配套：[API 清单](./TDD-支付服务-开放API加解密-API清单.md) · [类清单](./TDD-支付服务-开放API加解密-类清单.md)
创建：2026-10-26 · 最后更新：2026-10-26

> **基线**：本篇是**增量**，基于分支 `claude/silly-gates-90cc7e`（W5 密钥模块 + I5 接入层，**尚未合入** `codex/ai-shop-codex`）里已经写好的迁移：
> `db/secret/V1__sec_tables.sql`（`sec_*` 七表）· `db/pay/V7__pay_app_internal.sql`（`pay_app`）· `db/pay/V5__pay_four_way_records.sql`（`pay_api_request_log`、`pay_biz_notify`）· `db/pay/V11__pay_open_access.sql`（`pay_app_grant`、`pay_api_nonce`、`pay_qr_code`）。
> 迁移号接在它们后面：**`db/secret` V2**、**`db/pay` V12**。那个分支合入之前，这两个文件不能落盘。

---

## §1 总览

| 变化 | 表 | 迁移 | 为什么 |
|---|---|---|---|
| 加列 · 改约束 | `sec_key_version` | secret V2 | 存储五档：每个版本记录自己在哪一档（`storage_tier`、`storage_ref`）；L4 / L5 既没有密文也没有公钥，原约束 `ck_sec_key_version_1` 要放宽 |
| 加列 | `sec_key_version` | secret V2 | `display_prefix`：AppSecret 的 `sk_live_` / `sk_test_` 只用于显示，不进材料 |
| 加列 | `pay_app` | pay V12 | `sign_type`（HMAC / RSA） |
| 新表 | `pay_app_developer` | pay V12 | 开发者控制台账号：接入方自己登录、自己生成密钥（运营看不到明文） |
| 新表 | `pay_app_activation` | pay V12 | 一次性激活链接（库里只存令牌哈希） |
| 新表 | `pay_app_key_challenge` | pay V12 | 增强方案上传公钥时的持有证明（无 Redis 时用） |
| 加列 | `pay_biz_notify` | pay V12 | 冻结推送用的 EncryptKey / 签名密钥版本：重推必须用当初那把 |
| 加列 | `pay_api_request_log` | pay V12 | 记下验签命中的密钥版本与加密字段的版本，用于排障与轮换进度统计 |

**不新增密钥表**：AppSecret、EncryptKey、接入方 RSA 公钥、平台签名 / 加密密钥全部登记在 `sec_key` / `sec_key_version`，下面 §2 写清每一种怎么登记。

---

## §2 密钥登记（不建表，`sec_key` 的取值）

`sec_key` 唯一键是 `(namespace, owner_type, owner_id, direction, key_usage, env)`；**轮换只加版本，不新建 `sec_key`**。

| 密钥 | `namespace` | `owner_type` | `owner_id` | `direction` | `key_usage` | `algorithm` | `material_type` | 默认 `storage_tier` | `KeyPurpose` 常量 |
|---|---|---|---|---|---|---|---|---|---|
| AppSecret | PAY | `PARTNER_APP` | `pay_app.id` | INBOUND | HMAC | `HMAC_SHA256` | `SYMMETRIC_SECRET` | L3 | `INBOUND_HMAC`（已有） |
| EncryptKey | PAY | `PARTNER_APP` | `pay_app.id` | INBOUND | ENCRYPT | `AES_256_GCM` | `SYMMETRIC_SECRET` | L3 | `PARTNER_DATA`（新） |
| 接入方 RSA 公钥（增强） | PAY | `PARTNER_APP` | `pay_app.id` | INBOUND | VERIFY | `RSA_SHA256` | `PUBLIC_KEY` | 不适用（公开材料） | `INBOUND_VERIFY`（新） |
| 平台签名密钥对（增强） | PAY | `PLATFORM_OPEN` | `0` | OUTBOUND | SIGN | `RSA_SHA256` | `PRIVATE_KEY` | L3 | `OUTBOUND_SIGN`（已有） |
| 平台加密密钥对（增强） | PAY | `PLATFORM_OPEN` | `0` | INBOUND | ENCRYPT | `RSA_OAEP_SHA256` | `PRIVATE_KEY` | **L5** | `PLATFORM_DECRYPT`（新，同 `PARTNER_DATA` 的方向与用途，靠 `owner_type` 区分） |
| 指纹密钥（P7） | PAY | `PLATFORM_OPEN` | `0` | INBOUND | HMAC | `HMAC_SHA256` | `SYMMETRIC_SECRET` | L3 | 复用 `INBOUND_HMAC` |

`sec_key_version` 的取值约定：

| 列 | AppSecret | EncryptKey | 接入方公钥 | 平台签名 / 加密 |
|---|---|---|---|---|
| `external_key_id` | KeySerial，`S` + `yyyyMMdd` + 2 位序号，如 `S2026102601`（请求头 `X-Key-Serial`） | 同左（推送 `resource.keySerial`、请求头 `X-Encrypt-Serial`） | 同左 | 证书序列号（`X-Platform-Serial` / `X-Encrypt-Serial`） |
| `source` | `GENERATED` | `GENERATED` | `UPLOADED` | `GENERATED` |
| 材料 | 32 位 `[A-Za-z0-9]` 的 ASCII 字节（**不含前缀**） | 32 位 `[A-Za-z0-9]` 的 ASCII 字节 | `public_material` = SPKI PEM | 私钥按档存；证书 PEM 进 `public_material` |
| `display_prefix` | `sk_live_` / `sk_test_`（按 `sec_key.env`） | NULL | NULL | NULL |
| `fingerprint` | P7：HMAC-SHA256(指纹密钥, 材料) 前 16 位 hex | 同左 | SHA-256(SPKI DER) 前 16 位 | SHA-256(证书 DER) 前 16 位 |

> **AppSecret 的明文只有 32 位**：前缀由环境决定、不含熵，存进去只会让 `SecretAppKeyResolver` 多一步剥离、多一个出错点。
> 发放时由 `PartnerKeyService` 把 `display_prefix + 材料` 拼成 40 位给控制台显示一次。

---

## §3 secret V2

```sql
-- 密钥模块 · 迁移 2：存储分档（TDD-支付服务-开放API加解密与密钥分发 §2.8）+ AppSecret 显示前缀（§2.1）。
-- 存量版本都在库里、由注入的主密钥包裹 —— 即 L2；有 KMS 的环境在 V2 之后由 KeyTierMigrationJob 逐条升到 L3。

ALTER TABLE sec_key_version ADD COLUMN storage_tier VARCHAR(8) NOT NULL DEFAULT 'L2'
  COMMENT 'L1 配置明文 / L2 库 + 注入主密钥 / L3 库 + KMS 主密钥 / L4 外部密钥服务 / L5 KMS·HSM 不可导出；每个版本各自记录';
ALTER TABLE sec_key_version ADD COLUMN storage_ref VARCHAR(255) NULL
  COMMENT '材料在哪：L1 配置键名 / L2·L3 为空（见 sec_secret_blob_id）/ L4 外部路径#版本 / L5 KMS key id 或 HSM 句柄';
ALTER TABLE sec_key_version ADD COLUMN display_prefix VARCHAR(16) NULL
  COMMENT '只用于显示的前缀（AppSecret 的 sk_live_ / sk_test_）；不参与任何运算，不属于材料';

-- 原约束只认「库里有密文或有公钥」；L1 / L4 / L5 的私密材料不在库里，只有 storage_ref
ALTER TABLE sec_key_version DROP CONSTRAINT ck_sec_key_version_1;
ALTER TABLE sec_key_version ADD CONSTRAINT ck_sec_key_version_1
  CHECK (sec_secret_blob_id IS NOT NULL OR public_material IS NOT NULL OR storage_ref IS NOT NULL);
ALTER TABLE sec_key_version ADD CONSTRAINT ck_sec_key_version_3
  CHECK (storage_tier IN ('L1','L2','L3','L4','L5'));

CREATE INDEX idx_sec_key_version_4 ON sec_key_version (storage_tier, status);
```

| 列 | 类型 | 可空 | 默认 | 说明 |
|---|---|---|---|---|
| `storage_tier` | `VARCHAR(8)` | N | `L2` | 本版本的存储档；`RoutingKeyVault` 按它分发 |
| `storage_ref` | `VARCHAR(255)` | Y | | L1 / L4 / L5 的定位；L2 / L3 为空 |
| `display_prefix` | `VARCHAR(16)` | Y | | 仅显示 |

**三处要跟着改**（不改会出事）：
1. `backend/scripts/gen-test-schema.py` 目前只认 `CREATE TABLE / ALTER ADD / RENAME / CREATE INDEX`，**不认 `DROP CONSTRAINT`** —— 不补的话 H2 测试库留着旧约束，L4 / L5 的版本在单测里插不进去，而生产能插：测试和生产行为相反。
2. `SecKeyVersion` 实体加三个字段；`SchemaDriftTest` 会抓漏。
3. `DefaultKeyRegistry#insertVersion` 里与 `ck_sec_key_version_1` 同步的那条 Java 校验（「私密材料与公开材料至少一个」）同步放宽。

---

## §4 pay V12

```sql
-- 支付服务 v4 · 迁移 12：开放 API 加解密与密钥分发（TDD-支付服务-开放API加解密与密钥分发 v2.1）。
-- 密钥本身不在这里：登记在 secret 模块（sec_key / sec_key_version）。

ALTER TABLE pay_app ADD COLUMN sign_type VARCHAR(16) NOT NULL DEFAULT 'HMAC'
  COMMENT 'HMAC 基础方案（AppSecret）/ RSA 增强方案（接入方私钥 + 平台证书）；一个 app 只能一种';

CREATE TABLE pay_app_developer (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  pay_app_id BIGINT NOT NULL COMMENT '接入方 pay_app.id',
  login_email VARCHAR(128) NOT NULL COMMENT '登录邮箱（也是激活链接与第二因子的收件地址）',
  phone_masked VARCHAR(32) NULL COMMENT '手机号掩码，仅显示',
  phone_secret_blob_id BIGINT NULL COMMENT '手机号密文 sec_secret_blob.id（发短信验证码时解开）',
  password_hash VARCHAR(100) NULL COMMENT 'bcrypt；激活前为空',
  role VARCHAR(24) NOT NULL COMMENT 'OWNER 可生成 / 重置密钥 / DEVELOPER 只读（看 AppId、指纹、请求记录）',
  status VARCHAR(24) NOT NULL COMMENT 'PENDING 待激活 / ACTIVE / LOCKED / DISABLED',
  failed_logins INT NOT NULL DEFAULT 0 COMMENT '连续失败次数（成功清零）',
  locked_until DATETIME(3) NULL COMMENT '锁定到',
  last_login_at DATETIME(3) NULL COMMENT '最近登录',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pay_app_developer_1 (pay_app_id, login_email),
  KEY idx_pay_app_developer_1 (login_email, status)
) COMMENT='开发者控制台账号：接入方自己的人。生成 / 重置密钥只能在这里做，运营会话做不了';

CREATE TABLE pay_app_activation (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  pay_app_developer_id BIGINT NOT NULL COMMENT '被激活的账号 pay_app_developer.id',
  token_sha256 CHAR(64) NOT NULL COMMENT '激活令牌的 SHA-256（令牌 32 字节随机，只出现在邮件链接里）',
  expire_at DATETIME(3) NOT NULL COMMENT '过期（签发 + 24h）',
  used_at DATETIME(3) NULL COMMENT '使用时间；非空即作废',
  sent_to_masked VARCHAR(128) NOT NULL COMMENT '发往（掩码）',
  issued_by VARCHAR(64) NOT NULL COMMENT '签发的运营',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pay_app_activation_1 (token_sha256),
  KEY idx_pay_app_activation_1 (pay_app_developer_id, used_at),
  KEY idx_pay_app_activation_2 (expire_at)
) COMMENT='一次性激活链接：24h、单次；重发即作废旧的';

CREATE TABLE pay_app_key_challenge (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键（Java Long）',
  pay_app_id BIGINT NOT NULL COMMENT '接入方 pay_app.id',
  challenge VARCHAR(64) NOT NULL COMMENT '32 位随机串（不是秘密：对方要拿私钥签它）',
  expire_at DATETIME(3) NOT NULL COMMENT '过期（签发 + 5 分钟）',
  used_at DATETIME(3) NULL COMMENT '使用时间；非空即作废',
  created_at DATETIME(3) NOT NULL COMMENT 'UTC',
  created_by VARCHAR(64) NULL COMMENT '创建人（审计字段）',
  updated_at DATETIME(3) NOT NULL COMMENT 'UTC',
  updated_by VARCHAR(64) NULL COMMENT '修改人（审计字段）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除；资金与历史事实不删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pay_app_key_challenge_1 (challenge),
  KEY idx_pay_app_key_challenge_1 (expire_at)
) COMMENT='上传 RSA 公钥的持有证明（P8）；有 Redis 时用 Redis，表是落库实现';

ALTER TABLE pay_biz_notify ADD COLUMN enc_key_version INT NULL
  COMMENT 'WEBHOOK：加密 resource 用的 EncryptKey 版本（sec_key_version.version_index）；重推一律用它';
ALTER TABLE pay_biz_notify ADD COLUMN sign_key_version INT NULL
  COMMENT 'WEBHOOK：签名用的密钥版本（HMAC 为 AppSecret，RSA 为平台签名密钥）；重推一律用它';

ALTER TABLE pay_api_request_log ADD COLUMN sign_key_version INT NULL
  COMMENT '验签命中的密钥版本；未通过为空。统计「还有谁在用旧版本」靠它';
ALTER TABLE pay_api_request_log ADD COLUMN encrypt_serial VARCHAR(64) NULL
  COMMENT '请求头 X-Encrypt-Serial（有加密字段时）';
```

### 4.1 新表字段说明

**`pay_app_developer`** —— 一个接入方可以有多个开发者，至少一个 OWNER。

| 字段 | 说明 |
|---|---|
| `role` | OWNER：生成 / 重置 / 作废 AppSecret 与 EncryptKey、上传公钥、改 webhook；DEVELOPER：只读 |
| `phone_secret_blob_id` | 手机号经 `SecretVault.seal(KeyOwner(PAY, "APP_DEVELOPER", id))` 封存，与进件敏感字段同一做法；表里只留掩码 |
| `failed_logins` · `locked_until` | 规则同运营登录（安全规范 §2.7）：15 分钟 5 次失败锁 15 分钟 |

**`pay_app_activation`** —— 令牌本身不落库；重发激活链接时，同账号未用的旧记录一律置 `used_at`。

**`pay_app_key_challenge`** —— 校验时：存在、未过期、未使用、`pay_app_id` 一致 → 置 `used_at` 后再验签名（先占后验，防止同一 challenge 被并发用两次）。

### 4.2 关系

| 表 | 列 | → 表 | 基数 | 类型 |
|---|---|---|---|---|
| `pay_app_developer` | `pay_app_id` | `pay_app` | N : 1 | 同模块 |
| `pay_app_developer` | `phone_secret_blob_id` | `sec_secret_blob` | N : 0..1 | 跨模块 id 引用 |
| `pay_app_activation` | `pay_app_developer_id` | `pay_app_developer` | N : 1 | 同模块 |
| `pay_app_key_challenge` | `pay_app_id` | `pay_app` | N : 1 | 同模块 |
| `sec_key` | `owner_id`（`owner_type=PARTNER_APP`） | `pay_app` | N : 1 (poly) | 多态 id 引用（已有） |
| `pay_biz_notify` | `enc_key_version` · `sign_key_version` | `sec_key_version.version_index` | N : 1 | 版本号，不是外键（与 `pay_order.sec_key_version` 同一做法） |

### 4.3 清理

| 表 | 规则 | 由谁 |
|---|---|---|
| `pay_app_activation` | `expire_at` 过后 30 天删除 | `PayOpenHousekeepingJob`（每日） |
| `pay_app_key_challenge` | `expire_at` 过后 1 天删除 | 同上 |
| `pay_api_nonce` | 已有：`expire_at` 过后删除 | 已有任务 |

---

## §5 同步到唯一真源

[数据库表结构说明](./TDD-支付服务-数据库表结构说明.md) 写明它由 `design/schema/pay-service-database.json` 生成、勿手改，**但生成器不在仓库里**（`scripts/`、`backend/scripts/` 与全部分支历史都没有引用 `existingTableAdditions` 的脚本）。
所以本篇的 DDL **暂不写进 JSON**：手改 JSON 而不重新生成，说明文档就和真源不一致了，比现在更糟。
处理：合入 W5 / I5 分支后，先把生成器找回或补进 `scripts/`（见类清单 Q-C3），再一次性把本篇 §3、§4 写进 JSON 并重新生成。

---

## 待确认

| # | 问题 | 倾向 |
|---|---|---|
| Q-D1 | 开发者账号是否复用平台已有的账号体系（商家账号 / C 端账号），而不是新建 `pay_app_developer` | 新建：接入方不是商家也不是顾客，混进去权限模型要加一类人 |
| Q-D2 | `storage_tier` 的默认值：存量是 L2；生产有 KMS 后是否在迁移里直接写 L3 | 不在迁移里猜：迁移一律 L2，升档由 `KeyTierMigrationJob` 做，留事件记录 |
