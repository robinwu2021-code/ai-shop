# UME166 门店与商户同步 · 交接（状态与下一步）· 2026-11-19

> 一页看全局。过程细节、命令与证据见同目录 [README.md](README.md)；oscar/roger 对齐见 [oscar-ume-align-2026-11-19](../oscar-ume-align-2026-11-19.md)；App 侧需求见 [app-entity-channels-2026-11-19](../app-entity-channels-2026-11-19.md)。
> 目标（用户 2026-11-19）：同步 UME 的门店和商户资料并验证，自动进行。

## 一、完成状态（166 实测，2026-11-19）

| 项 | 状态 | 数量 / 证据 |
|---|---|---|
| 商户：UME 主体 → near-pay 支付商户 + 已核验来源关联 | ✅ | 741 条 VERIFIED（批量迁移 739 + 原有 2）；对账 `5_NO_PAY_MERCHANT` = 0（[TDD-UME迁移建档](../../technical/TDD-UME迁移建档.md)） |
| 门店：英文门店代码，同步到 near-pay | ✅ | 815 家；`store-codes.csv` |
| 渠道账户：按 UME 导出登记，商户端可见 | ✅ | 1642 条；UME 清单 1639 条全在、0 缺失（库内按 `MerchantChannelQuery` 口径复算） |
| 渠道目录：已接入 ENABLED，未接入 7 个 PENDING（待开通） | ✅ | GAMAPAY、ALIPAY、EZPAY、EZPAY_CROSSBORDER、FUBON_WECHAT、PIPAY、ATOME |
| 商户端 API 验收 `GET /biz/merchant/payment` | ✅ | oscar 8 行、roger 7 行，与库内预期逐行一致（`verify_merchant_api.py`） |
| roger 重复支付寶 `PM9C0E0F6696DF4660` | ✅ 软删除 | 用户同意；`deleted=1`，可改回 |
| 门店同步死锁 / 结果未知 | ✅ 已修并上线 | near-pay `18e6bee56`、ai-shop `4abe2f32e`；300/300 复验 |
| **渠道密钥（LINE Pay / 藍新）** | ❌ **未导入** | 1158 个账户 `UNVERIFIED`、无凭据；仅 3 个账户可收款（oscar 两个 + 1） |
| 店员账号 | 不同步（用户决定） | 1667 个 |
| 3 个无主体 UME 商户 | 未导入（建议不导） | `88600248120000008`、`88600254990000004`、`88600289990002226`，UME 无在营门店 |

### 代码与部署

| 仓库 | 状态 |
|---|---|
| ai-shop | `main` 已到 `930dda43a`（用户批准计划后 `--no-verify` 推送；闸门剩 3 红 `ReviewStoreAttributionFlowTest.*`，**干净 origin/main 上同样 3 红**，非本次引入，待负责人修）。此后本地新提交（脚本与交接，本文件同批提交）见第四节 |
| ai-shop 166 运行 jar | `26830f87ca02`（覆盖部署，备份见 README） |
| near-pay | `develop/s1` `18e6bee56` 已推送；166 运行 jar `cb44f1220b2d` |
| App（ai-shop-bapp） | 分支 `feat/entity-channel-accounts-20261119` `7f1098b` 已推送（未合 main）：「待開通」+ 原因、渠道商戶號全号、沙箱、新渠道目录名；单测 1849 全过；nearboss 166 调试包已构建，未做模拟器验收（用户改为 API 验收） |

## 二、仍在生效的临时授权（收尾必须撤销）

| 授权 | 位置 | 撤销方式 |
|---|---|---|
| `ST-UME-CONFIG` 超管 | ai-shop `sys_role_member`（`created_by='ume-batch-temp'`）与 `sys_ops_staff.roles` | 删该成员行、还原 roles |
| near-pay 数据范围 | `DSB2C80A6DB2924070`、`DS9A7CFE0DD0314A21` | 约 2026-11-21 自动过期，或提前撤销 |
| 两个店主自动化白名单 | 166 `/home/ume/v46-entity-closure-20261115/ops-entity.properties` 末行 `shop.auth.automation.subjects=...`（备份 `.bak-before-automation-b-20261119`） | 删该行，`docker restart ai-shop-ops` |

## 三、接下来要做的事

| # | 事项 | 谁 | 前置 |
|---|---|---|---|
| 0 | near-pay 新增免审批迁移导入端点 `POST /pay-ops/v2/channel-accounts/{no}/migration-credential`（near-pay `ac710c329`，TDD-UME渠道密钥迁移导入；仅 `file:ume/` 迁移账户、开关控制，一次到可收款）——**已覆盖部署 166**（`cb44f1220b2d → e1732435b4be`，备份 `pay-svc.jar.before-ume-credential-migration-20261119-ac710c329`），冒烟：非迁移账户被拒且未写入。`credentials.py apply` 默认改走该端点 | ✅ 完成 | — |
| 0b | 该端点改为**迁移窗口内免认证**（near-pay `6ea6f1a40`，用户「整段免认证」；守卫只放行这一条 OPS 路径，无会话用系统身份 `SYS-UME-MIGRATION-API`）——**已覆盖部署 166**（`e1732435b4be → 5206f2c68749`，备份 `pay-svc.jar.before-20261119-anon-6ea6f1a40`），冒烟：无令牌调该端点达业务校验（非迁移账户被拒）、其它 OPS 路径无令牌仍 401。脚本默认 `UME_KEYS_ANON=1` 不取令牌 | ✅ 完成 | — |
| 1 | **导入渠道密钥**：本机执行 `bash deploy/ume/sync-check/run_keys_all.sh`（免输入：从 db61 上 .60 的 2026-11-08 全库备份抽 `msm_mms` 两张表到 near-pay 备份表 → 列名 → check → roger 试点 → 全量 1158 户 → 删备份表）。Claude 不代跑：涉及把支付密钥写入凭据库 | **用户** | 隧道与 venv 已就绪（`~/.venvs/ume-keys`） |
| 1a | 若停在 check：多半是列名映射（脚本按经验猜的），把 `~/work/env/ume/keys-run-*/describe.txt`、`check.log` 交给 Claude 改 `ume-columns.stage.json` 后重跑；已完成的户自动跳过 | 用户 + Claude | — |
| 1b | 11-08 之后 UME 新增的密钥会记 MISSING 跳过（`missing.txt`）；需另行取数（.60 在线口令或新备份） | 用户 | .60 每日备份 11-08 后未再生成，需排查备份任务 |
| 2 | 逐户核对启用结果，修复失败户 | Claude | 1 |
| 3 | 门店绑定渠道，开通收款码与 H5 收款入口（脚本） | Claude | 2 |
| 4 | 商户端 API 复验（`verify_merchant_api.py`）+ 抽样真实小额收款 | Claude；真实付款由用户确认 | 3 |
| 5 | 撤销第二节全部临时授权，更新功能矩阵 | Claude | 1–4 完成 |
| 6 | App 分支合入 main 并出正式包 | 待用户决定 | — |
| 7 | `ReviewStoreAttributionFlowTest` 3 红（主线既有，商户登录取不到验证码） | 测试负责人 | — |
| 8 | 7 个待开通渠道的适配器（橘子、ezPay 等 481 条账户） | 支付团队 | 产品排期 |

## 四、本批提交（ai-shop，`main` 之后）

- `a3fa362a0` 交接：推送与 App 调试包
- `c7cfe79b7`、`fc522ecf4` 商户端 API 验收脚本与结果
- `ae6e804df`、`3df7f3d92`、`16a1f31bc` 密钥迁移：备份到 near-pay 库、一键脚本、改从 .60 全库备份取数
- 本文件


## 2026-11-19 晚 · 密钥导入结果、全渠道直录上线、安全事件

**密钥导入（用户执行，22:04–22:19 服务器时间）**：1140 个凭据版本写入并启用——藍新 704/704、LINE Pay 436/454 已 `ACTIVE`。剩 **18 个 LINE Pay**：UME `channel_id` 带字面 `\t`（与早先修过渠道号的 18 户一致），脚本比对不等被跳过；脚本已修（`clean_id`），重跑时已启用账户直接 `DONE` 跳过、不读密钥、不轮换。重跑：`bash deploy/ume/sync-check/run_keys_all.sh`（默认带运营令牌）。

**全渠道录入 / 更新密钥（用户要求，免审批、开放给所有渠道账户）**：near-pay `786470b5d`——有商户凭据类型的渠道（NEWEBPAY、LINEPAY、JKOPAY、PAYBY、MOCKPAY）的 `MERCHANT_OWN` 账户：待核验时一次到可收款（产品取目录里已启用的），已启用时轮换。运营端「渠道凭证」页签新增录入 / 更新表单。待开通 7 渠道无适配器，不能录。
- 后端覆盖部署 166：`5206f2c68749 → 18f2a134f440`（备份 `pay-svc.jar.before-20261119-allchan-786470b5d`）；冒烟：无令牌 401；待开通渠道 400；已启用账户格式错的密钥 400 且版本未变。
- 运营前端 `/oms`：从线上同一提交 `07a5af196` 只叠本次前端改动构建（避免带入未部署后端所需的其它前端改动），发布 `nearpay-oms-releases/channel-key-20261119-786470b5d`，`/data/www/oms-root/oms` 已切换；回滚：把该链接指回 `previous-channel-key-20261119-786470b5d.txt` 记的 `entry-edit-20261119-07a5af196`。

**安全事件**：迁移端点免认证上线后发现 166 nginx 把 `/oms-api/pay-ops/v2/` 转发到公网，端点对公网无认证可写密钥。已立即 `anonymous=false` 并重启，公网路径无令牌复验 401；日志确认无外部调用，期间写入均为用户导入。今后 `/pay-ops/**` 一律会话认证（near-pay TDD §8）。

**覆盖包核查更正**：此前三次覆盖的 javap 比对脚本有误（路径带 `./`，比对为空），已用正确类名重做：首包无成员缺失；`5206f2c68749` 包里 `GuardFilter` 换成了 `18e6bee56` 源码版——私有 `onboardingMultipart` 改名 `trustedMultipart`，多放行渠道账单与认领文件两类 multipart（线上原 pay-guard 略早于 `18e6bee56`）；本次包只改直录服务与请求体的构造器和私有成员。


## 2026-11-19 晚 · 收口：密钥导入结束，直录转为日常维护

用户：「密钥导入的任务已经完成了，以上是解决日常的渠道信息维护」。
- 导入结束：藍新 704、LINE Pay 436 已启用；**其余 18 个 LINE Pay（UME 渠道号带 `\t`）不再跑脚本，由运营在「渠道凭证」页签人工录入**；今后各渠道密钥的录入、更换都走该页签。
- **直录开关常驻**：`near-pay.credential.migration.enabled=true` 不再是临时授权，**不要关**（关了运营端录入会报「未开启」）；`anonymous=false` 必须保持。
- **权限补回**（near-pay `00dbaf37e`）：直录端点要求运营会话 + `pay:channel-account:manage` + 数据范围，去掉无会话回退。覆盖部署 166 `18f2a134f440 → d09e409d202d`（备份 `pay-svc.jar.before-20261119-perm-00dbaf37e`）；冒烟：无令牌 401、超管到业务校验、无管理权限的复核人 403。
- 仍待撤的临时授权：`ST-UME-CONFIG` 超管、near-pay 两个数据范围（约 11-21 过期）、`ai-shop-ops` 的两个店主自动化白名单（见第二节）。
