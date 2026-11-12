# 门店创建审核与法币支付 · 交接（当前状态与下一步）· 2026-11-12

> 本篇是接手入口，只写**现在是什么样、动过线上什么、还差什么**。过程细节见同目录：
> [`README.md`](README.md)（合并、门禁、166 部署、法币闭环）、[`store-review-online-test.md`](store-review-online-test.md)（门店审核线上测试矩阵与门禁修复）。
> 上游交接：[`ops-flow-ume-2026-11-11/completed-work-checkpoint.md`](../ops-flow-ume-2026-11-11/completed-work-checkpoint.md)。

## 一、一句话状态

- **从注册到开通支付的闭环**：在 UME 166 上跑通到「蓝新进件平台审核通过」，按约定**未派发**真实蓝新；该测试进件已取消。
- **门店创建审核**：166 线上测试 37 项、35 项符合预期；不符合的 2 项是同一个缺陷「主体与业态门禁失效」，**已修复、过完整门禁、已部署 166 并复验**。
- **仍有 3 个已知缺陷未修**（§五），若干场景未覆盖（§六）。

## 二、代码与分支

| 仓库 | 分支 | 位置 | 说明 |
|---|---|---|---|
| ai-shop | `codex/ai-shop-codex`（主开发分支） | 最后一次代码改动 `9f44771a1`，之后只有文档提交 | 含合并、门禁修复、主体业态门禁修复与全部交接文档 |
| ai-shop | `codex/ops-fiat-local-closure` | 同上 | 本工作的闭环分支，已与主开发分支对齐 |
| near-pay | `develop/s1` | `6e1f0bdee` | 合入 `codex/pay-ops-menu-matrix` |
| near-pay | `codex/ops-fiat-local-closure` | `a72f53aa4` | 仅本地闭环启动器草稿 |

本轮代码提交（ai-shop）：

| 提交 | 内容 | 门禁 |
|---|---|---|
| `08f719868` | 修复：入驻门禁对未知主体、未登记行业 fail-closed | 完整门禁通过 |
| `13b24b99c`、`9f44771a1` | 两个原生 MySQL 测试的主数据替身补齐（规则未放宽） | 完整门禁通过：后端无新增失败（仍为基线 2 条）、餐饮 1264/0、美业无新增 |
| `85b98d2fa` / near-pay `a72f53aa4` | 本地闭环脚本与启动器**草稿，未运行** | **跳过门禁**（用户要求） |
| 其余 `docs(handoff)` | 文档 | 跳过门禁，仅文档 |

已知失败基线 2 条（`backend/known-failures.txt`，支付重试复用原单，归支付负责人）：`PaymentLedgerCloseRaceFlowTest.unconfirmedPrepayCannotCloseOrAllocateAnotherOriginalNumber`、`PaymentRequestSnapshotTest.retry_reuses_original_store_and_account_even_if_the_caller_has_new_configuration`。

## 三、166 当前运行状态

| 项 | 值 |
|---|---|
| 后端制品 | `9f44771a1`，JAR SHA256 `e8c2c19136c2cf6cca1ec46860d4e9c039fbd02c5d911782bd5aa98037e703eb`，`ai-shop-api` / `ai-shop-ops` 共用 `/data0/app/ai-shop/shop-app.jar` |
| 数据库 `ai_shop_ume` | V662，无失败迁移 |
| 运营前端 | `/data/www/ai-shop-ops/current → …/fiat-closure-202611112106/ai-shop-web` |
| near-pay | 本批未改动 |

## 四、本轮对 166 的全部改动与回退

| # | 改动 | 回退 |
|---|---|---|
| 1 | 后端 JAR 换为 `9f44771a1` | `cp shop-app.jar.before-9f44771a1-20261112-154918 shop-app.jar` 后重启两个容器（`649057db0` 版） |
| 2 | 自动化票据公钥轮换（`ai-shop.env` 的 `SHOP_AUTOMATION_PUBLIC_KEY`），重建 `ai-shop-api` / `ai-shop-ops` | 旧容器 `*-pre-autokey-11120401`、备份 `ai-shop.env.bak-pre-autokey-11120401`；**旧私钥不在本机，回退后本机票据失效** |
| 3 | `ai-shop-ops` 打开 `COMMERCE_STORE_CREATION_ENABLED=true`：容器已重建，且已写入 `ai-shop.env` | 旧容器 `ai-shop-ops-pre-storecreation-*`；备份 `ai-shop.env.before-storecreation-*`（**不建议回退**：关闭后审核通过即报 10400） |
| 4 | 商城访问密钥轮换：`ai-shop-api` 的 `COMMERCE_ACCESS_KEYS_UME1` → `UME2`，`COMMERCE_ACCESS_ACTIVE_KEY_ID=ume2` | 备份 `ai-shop-api.env.before-rotate-commerce-*`、旧容器 `ai-shop-api-commerce-rot-*`（旧密钥已泄露，**不应回退**）。**密钥 ID 须小写** |
| 5 | 主数据补齐：`sys_legal_form` + NATURAL_PERSON、INDIVIDUAL；`sys_industry` + RETAIL、LIFE_SERVICE（启用）、ENTERTAINMENT、TRANSPORT、ONLINE、OTHER（停用） | `DELETE … WHERE created_by='SEED-RESTORE-20261112'`（**不建议**：删后自然人与零售入驻全部被拒） |
| 6 | 测试运营长期功能点：`NP_IT_CONFIG` + 代客进件、测试号白名单查看/修改；`NP_IT_REVIEW` + 入驻审核（用户决定，替代临时超管） | `DELETE FROM ai_shop_ume.sys_role_point WHERE created_by='STORE_REVIEW_TEST';` |
| 7 | V660–V662 迁移（root 临时授权后已收回，`log_bin_trust_function_creators` 已复位） | 不回退 |

已收尾、无残留：临时 SUPER_ADMIN（`created_by='fiat-closure-temp'`）0 行；测试白名单邮箱 id 4–9 全部停用；166 本机店主令牌文件与测试脚本已删除；测试进件 `OB010C12DDF672422A` 已 `CANCELLED`。

## 五、已知缺陷（未修）

| # | 缺陷 | 证据 | 建议 |
|---|---|---|---|
| 1 | **协议门禁**：审核不读 `agreed_at`；自助开店的商户未签协议即可通过，且 `agreementPending` 只对代客单显示，**自助商户永远不会被提示签协议** | 测试报告 §三.2，场景 B 申请 `MA261112R45F3F6ZEQ`（`agreedAt=0` 已通过） | 需产品决定：审核前强制已签，或通过后对所有未签商户提示。用户此前决定「先不改，只记录」 |
| 2 | **B 端台湾手机号**：`packages/shared/src/utils/validate.ts` 的 `isPhone` 只认大陆号段，`b-app/src/pages/apply/index.vue` 据此拦截，台湾商户在 App 上提交不了入驻 | 读代码；服务端不校验，接口直调可提交 | 按市场校验（复用 `MarketPhonePolicyService` 口径）。**需在模拟器复现确认** |
| 3 | **B 端快速开店**：`b-app/src/pages/home/index.vue:407` 只发 `{storeName}`，`b-app/src/pages` 无任何页面传 `businessTypeCode`；166 开启强类型建店后必失败 | 读代码 | App 改走三步建店（市场/行业/业态），或服务端给默认业态。**需在模拟器复现确认** |

## 六、未覆盖的测试

1. B 端 App 真机/模拟器（§五 2、3 均为读代码结论）。
2. C 端入驻意向 `/mp/merchant/apply`（不带主体、停用行业照收）未在线上测。
3. 新版入驻接口 `/biz/merchant/onboarding`（B 端未调用）未测。
4. COMMUNITY 服务范围未测（166 无社区数据），只测了 PLATFORM。
5. 门禁修复部署后只复验了门禁本身，未整套重跑场景 A/B/D。
6. 真实蓝新的派发、开户、开产品、凭据、门店绑定未验证：派发前须补齐 28 项 `NWP_*` 渠道资料（真实统编、银行），不能用测试占位。
7. 本地闭环脚本 `scripts/verify-fiat-closure-local.py` 与 near-pay `FiatClosureLocalLauncher` 未运行。

## 七、166 上保留的测试数据（均 TEST ONLY）

| 用途 | 编号 |
|---|---|
| 法币闭环主体 / 门店 | `M261112N1B45EVMKE` / `ST2611120GBMQH23X2`，near-pay 商户 `PM4183D38BDC47421E` |
| 场景 A 自然人（**门禁失效时以「自然人 + 线上」通过，不得用于收款**） | `M261112SMSE46Y54G` / `ST261112ZYT7BVJTG1` |
| 场景 B 企业（驳回后重提通过，未签协议） | `M261112WXMP8F8P3J` / `ST261112RSZMTVPZ1Y` |
| 场景 D 代客企业 | `M261112MWJ4GK3318` / `ST261112XKX3ET3J8D` |

## 八、安全与凭据

- 166 自动化票据私钥已换新：`~/work/env/ume/automation-ume-ed25519.pem`（本机，0600，仓库外）。其他机器或云端会话跑 166 自动化须用 `AUTOMATION_KEY` 指向新私钥；本机的 `~/work/env/ai-shop/automation-ed25519.pem` 不是 166 的钥匙。
- 票据登录端点 `/common/auth/automation` 只能经 166 本机 18081（SSH 隧道）访问。
- 测试身份验证码只在 166 `ai-shop.env` 中，不得复制出主机。
- 旧商城访问密钥 `UME1` 曾在会话输出中泄露，已作废；它仍留在 166 的旧 env 备份与旧容器中，清理旧容器时一并删除。

## 九、建议的下一步

1. 整套回归场景 A/B/D（约 10 分钟，脚本思路见测试报告）。
2. Android 模拟器（`pos_tablet@5580`）实测 §五 2、3，确认后修 B 端 App。
3. 产品决定协议门禁（§五 1）。
4. 支付负责人修 2 条已知失败后从 `known-failures.txt` 删除。
5. 清理 166 旧容器与旧 env 备份（含已作废的 UME1）。
6. `codex/food-next01`、`fix/food-p0-schema-scope` 的 V657/V659/V660 合入主开发分支前改号。

## 十、NearPay进件看板接入增量（2026-11-12，本地已验证，未部署）

用户确定进件对接为优先任务后，已补齐：

- NearPay `GET /internal/pay/v2/source-links/onboardings`：服务器tenant和登记caller决定来源，scope/entity精确定位支付商户，脱敏返回来源/资料审核及真实渠道进件历史。
- ai-shop `GET /ops/payment-onboardings`：沿`merchant:admission:read`及原主体治理数据域先筛可见主体，再查询NearPay。按主体分页（最多20条），关键词为主体号/名称；接口故障明确显示失败。
- OPS现有进件看板改接新接口；来源审核、平台审核、渠道状态、结果码、账户候选交接、ezPay关联各自展示。刷新只读取NearPay已存事实；渠道写动作仍在NearPay。旧接口供已有客户端保留，不再作为该看板数据源。
- 支付服务与看板的公开跳转使用`/merchants/onboarding-review?tab=fiat&keyword=<支付商户号>`。NearPay实际读取的是`keyword`，不能误用`merchantNo`导致打开全量列表。

验证：ai-shop13项、NearPay10项、OPS相关17项及文案70项通过；两侧类型检查及变更lint通过。受限财务角色的真实Spring测试已证明只查M0001，关键词M0002不能越域；目的页参数消费回归1项通过。详见原TDD§9。

本批未部署，前述166线上版本与历史验收不代表新看板已上线。先发布NearPay只读API，再发布ai-shop服务端及OPS页面，随后用现有受限角色执行线上只读联调；不要用临时超管掩盖权限配置缺口。真实蓝新新增商店仍须完整真实材料及合法未使用MID，未在本批执行。

静态守卫仍有基线问题：四个系统维护端点的权限细化登记缺失、四张C1保护表的H2结构缺失；本批未修改相关业务或扩大欠账登记。
