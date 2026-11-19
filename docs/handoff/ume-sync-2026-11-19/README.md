# UME 门店与商户资料同步 · UME166 · 2026-11-19

用户要求：「核心任务是同步门店和商户的资料并进行验证」「自动进行，不要人工干涉」。脚本均在 `deploy/ume/sync-check/`、`deploy/ume/store-codes/`，可重复执行。

## 结果（对账 `reconcile.sql`，744 个 UME 商户）

| 状态 | 开始前 | 现在 |
|---|---|---|
| 全部同步（主体、店主、门店、支付商户、门店关联、渠道、门店代码） | 2 | **685** |
| 渠道数少于 UME 记录数（差额全部是按规则不登记的：UME 已停用 63、同一商户内重复 119、无对应渠道 5） | — | 56 |
| 没有支付商户 | 739 | 0 |
| ai-shop 无主体（UME 商户 88600248120000008、88600254990000004、88600289990002226） | 3 | 3 |

## 做了什么

1. **迁移建档**（[TDD-UME迁移建档](../../technical/TDD-UME迁移建档.md)，ai-shop `08a0025db`，已部署 `dae705102e65`）：`POST /ops/merchant/{entityNo}/pay-service/migrate`，不带证件资料，门店一并关联；`migrate.py` 逐户建档并核验来源关联。**739 户全部完成**（734 新建 + 5 已有），支付商户 `PM202611191516…`–`PM202611191537…`，来源关联均 VERIFIED，815 家门店投影均无 `UNLINKED_ENTITY`。
2. **门店英文代码**：808 家由子代理按店名英文翻译拟定（清单 `store-codes.csv`），`store-codes/import.py` 校验后写入，**815/815** 家 UME 门店有代码且已同步到 near-pay。门店还没有整店收款码，代码在开码后进支付链接。
3. **渠道账户**：`channels.py` 按 UME 原始导出生成清单（真实渠道商户号；富邦微信用 store_code + 终端号、支付寶用 store_id + terminal_id 作子商户号；凭证指向 UME 原记录）。**新登记 1628 个**，已有 11 个；`verify_channels.py` 复核 **1639/1639** 存在且挂在应挂的支付商户下（UNVERIFIED，须补密钥核验后启用；待开通渠道显示「待開通」）。
   - 跳过：UME 已停用 63、同一商户内重复 119、无对应渠道 6、主体无支付商户 10（即上表 3 户的渠道）。多户共用的渠道商户号：在有支付商户的商户之间没有出现。
   - 修正：UME 原数据 18 个 LINE Pay 渠道号末尾带字面 `\t`，已改为干净值（脚本已加清理与测试）；oscar 的两个富邦微信改为 `P0000476A` + 终端 `A001`/`A002`。

## 过程中发现的问题

- **near-pay 门店投影并发死锁**：ai-shop 多个发件箱线程并发推门店同步时，near-pay `StoreProjectionService.syncOnce` 出现数据库死锁（25 分钟内 68 次），返回 500；ai-shop 把它记成 `OUTCOME_UNKNOWN` 并且不再重试，阻塞该门店后续同步。本次按框架重放逻辑分 9 轮重放（审计 `sys_outbox_operation` 请求号 `ume-sync-replay-20261119-*`），2469 条全部 SENT。
  **已修（用户「修死锁」）**：near-pay `develop/s1` `18e6bee56`：门店投影同步遇死锁 / 取锁超时整体重试至多 4 次（`LockConflictRetry`，测试 3/3，门店相关 56 个测试回归通过）；ai-shop `4abe2f32e`：同步消费端遇 5xx / 429 / 不可达抛 `RetryableOutboxException`，交发件箱退避重试，不再停在「结果未知」（测试 3/3）。
  覆盖部署：near-pay `75b5a2272e78 → cb44f1220b2d`（备份 `pay-svc.jar.before-store-sync-lock-retry-20261119-18e6bee56`）；ai-shop `dae705102e65 → 26830f87ca02`（备份 `shop-app.jar.before-store-sync-retry-20261119-4abe2f32e`）。
  验证：一次触发 300 家 UME 门店同步，300/300 SENT、0 失败；期间 near-pay 仍发生 2 次死锁，均被重试消化。
- 11-13 首次导入时就有 22 条门店同步卡在同样的失败上，一并重放了。

## 未做 / 待办

- 店员账号（1667 个）：按用户决定暂不同步。
- 渠道密钥：用户执行 `credentials.py` 导入（见下节）；其余渠道（橘子、ezPay 等）平台未接入，保持待开通。收款码与 H5 收款入口在可收款后再开。
- 临时授权仍保留：`ai-shop-ops` 附加配置里两个店主的自动化白名单（见「商户端 API 验收」）；`ST-UME-CONFIG` 的 `SUPER_ADMIN`（`created_by='ume-batch-temp'`）、near-pay 数据范围 `DSB2C80A6DB2924070`、`DS9A7CFE0DD0314A21`（约 2026-11-21 过期）。
- ai-shop 推送：`PayGoesThroughGatewayTest` 已修（`b042d7ca5`）—— 根因是本分支 `c3b53c337` 把旧下单入口改走 `PaymentDispatchService`，通道未接入 / 通道拒绝时会关单；主线（`OrdinaryPaymentInitiationService`）一直是保留原意图待查回。现只对旧入口恢复主线行为，测试 4/4。闸门另补：迁移端点登记为脚本专用（`known-orphan-endpoints.txt`）与权限归属 `merchant:merchant:pay-apply`。
- 全量后端闸门剩 3 红 `ReviewStoreAttributionFlowTest.*`（商户登录取不到验证码），**干净 origin/main 上同样 3 红**，不是本分支引入；按闸门规矩不代改。用户批准计划后已 `--no-verify` 推送到 main（`6e9392abd..930dda43a`）。

## 渠道密钥迁移（用户执行）

对象：[`credential-targets.tsv`](credential-targets.tsv)，1158 个待补密钥的渠道账户（LINE Pay 454、藍新 704；只有编号，2026-11-19 从 166 near-pay 导出）。
脚本：`deploy/ume/sync-check/credentials.py`（测试 `test_credentials.py`）。密钥只在进程内存里，从 UME `msm_mms` 读出后直接交给 near-pay 凭据库；屏幕与报告只有账户号、步骤和结论。

每户步骤（先查后做，可重复执行）：登记凭据 → 录入版本 → 核验（证据为 UME 记录号）→ `CREDENTIAL_ENABLE` 审批（REVIEW1、REVIEW2）→ 启用 → 绑定渠道账户 → 开通产品（LINE Pay `LINEPAY_ONLINE`；藍新 `NEWEBPAY_CARD_ONETIME`、`NEWEBPAY_APPLEPAY_WEB`）→ `CHANNEL_ACCOUNT_VERIFY` 审批 → 由复核人核验 → 启用账户。

```bash
python3 -m venv ~/.venvs/ume-keys && ~/.venvs/ume-keys/bin/pip install pymysql
cd ~/work/ai-shop-fiat
export UME_DB_HOST=<UME msm_mms 库地址> UME_DB_USER=<只读账号>     # 口令运行时提示输入
export AUTOMATION_KEY=~/work/env/ume/automation-ume-ed25519.pem    # 另需 28081 / 28093 隧道
T=docs/handoff/ume-sync-2026-11-19/credential-targets.tsv
~/.venvs/ume-keys/bin/python -I deploy/ume/sync-check/credentials.py describe                       # 1 核对列名
~/.venvs/ume-keys/bin/python -I deploy/ume/sync-check/credentials.py check $T --out check.json        # 2 只读 UME，看能否逐户唯一查到
~/.venvs/ume-keys/bin/python -I deploy/ume/sync-check/credentials.py apply $T --only PM0E0F6CC8F7454D75 --out pilot.json   # 3 先跑 roger
~/.venvs/ume-keys/bin/python -I deploy/ume/sync-check/credentials.py apply $T --out apply.json        # 4 全量
```

列名与默认不同（`describe` 输出对不上）时，在脚本同目录放 `ume-columns.json` 覆盖，例如 `{"NEWEBPAY": {"key": "CHN_MER_NO", "hashKey": "HASH_KEY", "hashIv": "HASH_IV", "where": {"CHN_ID": "CH380202039616995328"}}}`。
结论：`check` 为 FOUND / MISSING / AMBIGUOUS / MISMATCH / INVALID；`apply` 为 ACTIVATED / DONE / FAILED（附出错步骤与接口返回）。

## 商户端渠道展示核对（2026-11-19）

按 near-pay `MerchantChannelQuery` 的同一口径（已核验来源关联 → 支付商户 → 未关闭渠道账户 × 渠道目录）在 166 逐主体复算，与 UME 渠道清单 `channel-plan.tsv` 比对：
739 个主体全部可见；清单 1639 条全部出现、0 缺失。可见 1643 行：可收款 4（oscar 两个正式 + 两个沙箱），`ACCOUNT_UNVERIFIED` 1158（LINE Pay / 藍新待补密钥），`CHANNEL_NOT_LIVE` 481（7 个待开通渠道）。
渠道目录：NEWEBPAY、LINEPAY 等已接入为 ENABLED；GAMAPAY、ALIPAY、EZPAY、EZPAY_CROSSBORDER、FUBON_WECHAT、PIPAY、ATOME 为 PENDING。
（`/biz/merchant/payment` 端到端要店主登录，内部接口要服务密钥，均未走；以库内复算代替。）

清单外 4 行：oscar 两个沙箱、roger Atome（均为有意登记），以及 **roger 支付寶重复**：同一 UME 记录 `CM434194562391605248` 有两个账户，`PM7DE952F9858C4FCA`（终端 `TDL001`，按批量规则）与早先手工登记的 `PM9C0E0F6696DF4660`（无子商户号、版本 0、无门店绑定、无凭据）。
运营接口关闭被拒（near-pay S1 未开放「关闭渠道账户」，409 10409）；**用户同意后已软删除**（2026-11-19，影响 1 行，`deleted=1`、`version` 0→1，可改回）：
`UPDATE nearpay_prod.pay_merchant SET deleted=1, updated_by='ume-dedup-20261119', version=version+1 WHERE pay_merchant_no='PM9C0E0F6696DF4660' AND version=0 AND sec_key_id IS NULL;`
roger 现只剩 `PM7DE952F9858C4FCA`（`TDL001`）一张支付寶卡片。

## App 主体页渠道列表

ai-shop-bapp 分支 `feat/entity-channel-accounts-20261119`（工作树 `~/work/ai-shop-bapp-entchan`，基于 origin/main），按 [App 侧需求](../app-entity-channels-2026-11-19.md)：
新状态「待開通」及两种原因说明、渠道商戶號全号、沙箱标记、新渠道用后端目录名；同一渠道多个账户（富邦微信两台终端）不再被合并、卡片键不重复。已推送远端（codeup）；nearboss 166 调试包已构建（`artifacts/android/nearboss-166-debug.apk`，sha256 前缀 `de867ac06bdaa6ea`）。模拟器 pos_phone 上已有不同签名的同包名应用，覆盖安装失败，为免抹掉他人数据未卸载；且验收 UME 渠道需店主登录，模拟器验收待用户或店主账号就绪后做。

## 商户端 API 验收（2026-11-19，`verify_merchant_api.py`）

以店主身份（自动化票据 B 域）调 `GET /biz/merchant/payment?entityNo=`，逐行比对 near-pay 预期：
- oscar `MUME88600253990002308`：**OK 8 行** —— LINE Pay `1626862643`、藍新 `TRL00001898` 可收款（各绑 3 店），两个沙箱可收款并标 `sandbox=true`，橘子 `350421`、ezPay `PG100000254010`、富邦微信 ×2 待開通（CHANNEL_NOT_LIVE）。
- roger `MUME02380159443150569472`：**OK 7 行** —— LINE Pay `1629714284`、藍新 `MS354053380` 待開通（ACCOUNT_UNVERIFIED，待补密钥），橘子、支付寶（去重后 1 条）、ezPay、Pi、Atome 待開通（CHANNEL_NOT_LIVE）。

为此在 166 运营实例（`ai-shop-ops`，18081）的附加配置 `/home/ume/v46-entity-closure-20261115/ops-entity.properties` 追加一行（用户同意，备份 `.bak-before-automation-b-20261119`），重启 `ai-shop-ops`：
`shop.auth.automation.subjects=${SHOP_AUTOMATION_SUBJECTS},B:U261113VG2HFDJMK0,B:UUMEU380162366651760640`（保留原白名单，只加两个店主）。
商户端实例 `ai-shop-api` 未改动。**收尾时与其他临时授权一起删掉这一行并重启。**

## 渠道密钥：先备份到 near-pay 库，再导入（用户执行）

**为什么不直接写库**：near-pay 凭据由它的密钥服务加密后存 `sec_key_version` / 密文表，直接 SQL 写不出合法凭据，也绕过两人启用审批，所以入库必须走运营接口（`credentials.py`）。

1. **备份**（ume-db61 上执行；密钥只在服务器内流式传递，会话关闭 binlog，不打印）：
   ```bash
   scp -F <ssh 配置> deploy/ume/sync-check/stage_keys.sh ume-db61:/tmp/ && ssh -t -F <ssh 配置> ume-db61 'bash /tmp/stage_keys.sh'
   ```
   结果：`nearpay_prod.stg_ume_chn_mer_scan_linepay_info_20261119`、`stg_ume_chn_mer_card_inf_20261119`，屏幕只有行数。
2. **导入**（本机执行，经隧道调 near-pay 运营接口；源改为上面的备份表）：
   ```bash
   ssh -F <ssh 配置> -f -N -L 23307:127.0.0.1:3307 ume-db61
   export UME_DB_HOST=127.0.0.1 UME_DB_PORT=23307 UME_DB_USER=root UME_COLUMNS=deploy/ume/sync-check/ume-columns.stage.json
   export AUTOMATION_KEY=~/work/env/ume/automation-ume-ed25519.pem
   T=docs/handoff/ume-sync-2026-11-19/credential-targets.tsv
   ~/.venvs/ume-keys/bin/python -I deploy/ume/sync-check/credentials.py describe      # 列名对不上就把输出发来
   ~/.venvs/ume-keys/bin/python -I deploy/ume/sync-check/credentials.py check $T --out check.json
   ~/.venvs/ume-keys/bin/python -I deploy/ume/sync-check/credentials.py apply $T --only PM0E0F6CC8F7454D75 --out pilot.json
   ~/.venvs/ume-keys/bin/python -I deploy/ume/sync-check/credentials.py apply $T --out apply.json
   ```
3. **清理**：全部导入后在 ume-db61 执行 `bash /tmp/stage_keys.sh --drop`，删掉明文备份表。

**更新（一键、免输入）**：`bash deploy/ume/sync-check/run_keys_all.sh`。源改为 .61 上 .60 的每日全库备份（`/data0/data_bak/60/mysqldump_master/`，最新 2026-11-08，62 GB 单文件 dump；之后的每日备份未再生成），在 db61 上流式抽 `msm_mms` 两张表进 near-pay 备份表，不需要 UME 口令；mysql9 root 口令由脚本读本机 `nearpay-prod/mysql9-root.env`。11-08 之后才在 UME 新增的密钥会记为 MISSING 并跳过，报告单列。
