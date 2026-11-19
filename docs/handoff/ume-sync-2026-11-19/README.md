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
- 临时授权仍保留：`ST-UME-CONFIG` 的 `SUPER_ADMIN`（`created_by='ume-batch-temp'`）、near-pay 数据范围 `DSB2C80A6DB2924070`、`DS9A7CFE0DD0314A21`（约 2026-11-21 过期）。
- ai-shop 推送：`PayGoesThroughGatewayTest` 两个用例与 ADR-047 的「确证未发送即关单」冲突，待负责人决定；本地分支已含全部提交，未推送。

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
运营接口关闭被拒（near-pay S1 未开放「关闭渠道账户」，409 10409）；直接改库软删除被自动审查拦下，**待用户决定**：在 166 执行
`UPDATE nearpay_prod.pay_merchant SET deleted=1, updated_by='ume-dedup-20261119', version=version+1 WHERE pay_merchant_no='PM9C0E0F6696DF4660' AND version=0 AND sec_key_id IS NULL;`
未处理前 roger 在 App 会看到两张支付寶卡片。

## App 主体页渠道列表

ai-shop-bapp 分支 `feat/entity-channel-accounts-20261119`（工作树 `~/work/ai-shop-bapp-entchan`，基于 origin/main），按 [App 侧需求](../app-entity-channels-2026-11-19.md)：
新状态「待開通」及两种原因说明、渠道商戶號全号、沙箱标记、新渠道用后端目录名；同一渠道多个账户（富邦微信两台终端）不再被合并、卡片键不重复。未推送、未出包。
