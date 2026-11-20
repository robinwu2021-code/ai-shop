# 交接：支付中心并入 shop 运营端 · v2 单实例 · near-pay 代建修复（2026-11-20）

> 写给接手这条线的人（下一个会话 / RobinWu / 运维）。读完本文就能知道：线上跑的是什么、代码在哪、今天动了生产什么、怎么回退、还剩什么。
> 细节流水在 [ai-shop-v2-166-2026-11-19/README.md](../ai-shop-v2-166-2026-11-19/README.md)（v2 上线记录，按时间追加）与 near-pay `deploy/nearpay/prod/README.md`（分支 `fix/forop-ops-base-20261120`）。

## 1. 一句话现状

- **ai-shop 只剩 v2 一个进程**（.80:18180，一个 jar 同时提供运营端、B 端、C 端）。v1 的两个容器 16:38 已停，166 nginx 把原来指向 v1 的本机 18081/18082 转到 v2，商家 App、C 端、near-pay 的身份核验全部走 v2。以后只在 v2 上测试。
- **支付中心**（near-pay 运营页面并入 shop 运营端，独立模块）已上线 v2：31 个页签全部可用，商户档案与渠道进件的工作区已原生化，不再跳 near-pay `/oms`。
- **near-pay** 由 RobinWu 在维护发布（17:39 为 V132）。运营代建支付商户的修复已合进 `develop/s1`（`fa708146c`），**线上包还没有**，等他合并后整包发布。

## 2. 线上组成（17:55 核对）

| 部件 | 位置 | 版本 | 源码 |
|---|---|---|---|
| ai-shop v2 后端 | .80 容器 `ai-shop-v2`（`/data0/app/ai-shop-v2/run.sh all …`） | `releases/shop-app-b4335120a-collection-6ee1a6c98.jar`（17:35 由另一方换上；回退包在 `releases/collection-6ee1a6c98-rollback`） | `b4335120a` = ai-shop `release/v2-paycenter-20261120`；`6ee1a6c98`（collection 补丁）**不在本仓库** |
| ai-shop v2 前端 | 166 `/data/www/ai-shop-ops-v2/current` | `releases/gateway-e86b5ddc0` | ai-shop `release/v2-paycenter-20261120` `e86b5ddc0` |
| near-pay | 166 容器 `nearpay-prod` | jar `47d2996bf2bc0289`，支付库 **V132** | RobinWu `59c575bd`，**未推送**（166 `releases/collection-59c575bd-rollback` 留有 V131 回退包） |
| .80 本机转发 | 容器 `nearpay-fwd`（busybox nc） | `127.0.0.1:18093 → 192.168.3.166:18093` | v2 的 `NEARPAY_BIZ_ORIGIN` 只收 https 或回环，靠它连 near-pay 进件服务 |
| 166 nginx | `conf.d/ai-shop-v1-to-v2.conf` | `127.0.0.1:18081`、`:18082` → `192.168.3.80:18180`；`/ops-shop/` 302 → `/ops-shop-v2/` | — |
| 正式库 | .61 MySQL 9.7.4 | `ai_shop_ume` **V685**；`nearpay_prod` **V132**；连接 86/250 | — |

入口：运营端 `https://tw.neargo.ai/ops-shop-v2/`（支付中心 `…/payment-center/`）；B/C 端仍走原来的 `/shop-api/`（nearshop.umeshop.ai、tw.neargo.ai、twpay.umeshop.ai 三个域名）。

## 3. 代码在哪

### ai-shop（都**未推送**）

| 分支 | 工作树 | 说明 |
|---|---|---|
| `feat/ops-pay-center-menu` | `~/work/ai-shop-paycenter` | 支付中心开发线：`bd35f1058` P0/P1 → `8765add55` P2 → `0a6fbec0a` 菜单 V684 → `baecf5033` P3a（V685）→ `c15a55aab` P4（独立模块、商户/进件工作区）→ `1cc0c2b70` 保留 V671 菜单地址、前端映射 → `34f660411` FormDrawer 多选修复 |
| `release/v2-paycenter-20261120` | `~/work/ai-shop-v2rel` | v2 发布线 = 网关分支 `feat/gateway-mp-index`（`c92b384f9`，不含库存 V28 的 `c91d8f839`）+ 上一行；本交接文档在这里 |

设计与记录：`docs/technical/TDD-支付中心菜单整合.md`、`docs/technical/TDD-支付中心运营页面.md`（§2.9 模块边界、§2.10 P4 契约、§6 各期实现记录与偏差 1–23）。
待执行 SQL：`docs/technical/pending-sql/ops_payment_center_own_module.sql`（原 V686，把 5 个 V671 支付菜单点改到 `/payment-center`；**v1 已停，现在可以执行**，执行后删前端的 `LEGACY_MENU_HREFS` 映射）。

### near-pay（Codeup `p2027/nearpay`）

| 分支 | 状态 | 说明 |
|---|---|---|
| `develop/s1` | **已推送** `fa708146c` | = `8b70706e8`（V131 源码，pay 模块与线上 V131 包字节码一致）+ 修复二 `824f32ca7` |
| `release/nearpay-v131-fix2-20261120` | 已推送 | 只含修复二 |
| `fix/forop-ops-base-20261120` | 未推送 | 早先的修复一 + 修复二 + 发布记录；**修复一已作废**（ai-shop 单实例后不需要），发布记录在这里 |
| `codex/near-pay-migration` | 远端 | YitePay 迁移用了 V126–V128，**与正式库已执行的 V126/V127 同号不同内容，不能原样发布**；`develop/s1` 已改号为 V129–V131 |

## 4. 今天对生产做了什么（时间为 CST，均可回退）

| 时间 | 动作 | 回退 |
|---|---|---|
| 12:19 | 发 near-pay P3a 失败自动回退（线上 jar 与分支迁移版本不一致），中断约 2 分钟 | — |
| 12:43 / 12:49 | near-pay P3a 挑到线上那条线并发布，修储值交易 SQL | 已被后续发布替换 |
| 12:22–12:26 | v2 发支付中心（V684/V685 只插功能点） | `.80 run.sh.before-39bea6a65` |
| 14:24–14:29 | v2 发 P4（`b4335120a`），**V686 未执行**，前端映射旧菜单地址 | `.80 run.sh.before-b4335120a`；166 `current` 指回 `gateway-39bea6a65` |
| 14:58 / 15:21 | near-pay 两次补丁包（代建修复一、二） | 已被 15:57 RobinWu 的 V131 覆盖 |
| 15:08 | v2 前端换 `gateway-e86b5ddc0`（多选修复） | `current` 指回 `gateway-b4335120a` |
| 15:22 | 正式库 `mch_operating_entity` 测试经营主体 `OE26111074KRRAD8Q5` 的 `legal_subject_no` 设值，**已复原**（改前备份失败，回退语句 `.61 …/ai-shop-v2-gateway-20261119/rollback-oe-legal-subject-20261120.sql`） | 已复原并核对 |
| 16:35–16:38 | **停 v1、切 v2**：v2 开门店同步、主库池 4→16、拷 v1 上传文件；.80 起 `nearpay-fwd`；166 停 `ai-shop-ops`/`ai-shop-api`、加 nginx 转发与 `/ops-shop/` 跳转；商家 App 中断约 21 秒 | 删 `conf.d/ai-shop-v1-to-v2.conf`，两个站点配置复原 `.before-20261120-v1off`，reload，`docker update --restart=unless-stopped ai-shop-ops ai-shop-api && docker start …`；.80 复原 `ai-shop-all-v2.env.before-20261120-v1off` 后 `./run.sh all restart` |
| 15:57 / 17:35 / 17:39 | **另一方**发 near-pay V131、v2 collection 补丁包、near-pay V132 | 见各自 `releases/*-rollback` |

## 5. 测试数据与遗留

- 写操作测试对象（用户定）：Oscar 门店 `STUME12494685770267754496` → near-pay 测试商户 `PM0E0F6CC8F7454D75`「TEST ONLY 創科系統有限公司 UME166」。商户默认收款策略改过又复原；建过 LINE Pay 进件 `OBBC1D76F1555048FB` 并已撤销（留一条已撤销记录）。
- 测试商户 `PM70F51AF977C84528`「NearGo科技测试收款商户-1110-01」上留有一版**未提交**的代填资料草稿（法定名称、行业）；near-pay 没有删草稿的接口。
- 测试经营主体 `OE26111074KRRAD8Q5` 已有支付商户 `PM70F51AF977C84528`（11-10 自助创建），所以对它代建会被正确拒绝；要完整测一次代建，需要另一个**有门店、还没有支付商户**的经营主体。

## 6. 待办（按急缓）

| # | 事项 | 谁 |
|---|---|---|
| 1 | near-pay `59c575bd`（V132）推送，并合入 `develop/s1`（含修复二 `fa708146c`）后整包发布；之后代建支付商户才可用 | RobinWu |
| 2 | ai-shop `6ee1a6c98`（v2 上的 collection 补丁）推送并合入发布线，否则下次发 v2 会丢 | 发布它的一方 |
| 3 | 推送 ai-shop 两个分支（`feat/ops-pay-center-menu`、`release/v2-paycenter-20261120`） | 需用户同意 |
| 4 | 观察 v2 承接正式流量：网关拦截档下有无误拒、连接池与内存 | 运维 / 下一个会话 |
| 5 | 执行 `pending-sql/ops_payment_center_own_module.sql`（原 V686），删前端旧地址映射 | 下一个会话 |
| 6 | 支付中心各页按岗位授权（现只有超管） | 用户定口径 |
| 7 | v2 对不带签名的付款凭证图返回 404 时报 `ClassCastException ApiResult → Resource` | 下一个会话 |
| 8 | 带 `?action=create` 直接打开时新建抽屉不出现 | 下一个会话 |
| 9 | 补测：蓝新进件草稿编辑、附件上传、证照识别、提交审核到审批（需要一笔可编辑的蓝新进件） | 下一个会话 |
| 10 | near-pay 蓝新资料修改查看码写成 `pay:onboarding:read`（疑似笔误） | RobinWu |
| 11 | 稳定后清理 v1 容器与老目录，nginx 的 18081/18082 转发可改为直指 v2 | 运维 |
| 12 | `codex/near-pay-migration` 的迁移号冲突：改号或停用 | RobinWu |

## 7. 必须知道的约束与教训

1. **ai-shop 只能是一个实例**（用户：「ai-shop 也是一个实例，如果两个就是错误的」）。不要再按部署面拆实例。
2. **不要在本机整包构建 near-pay 去发生产**：near-pay 会打进 ai-shop 共享库（shop-base、shop-base-auth、svc-client 等），本机 Maven 仓库里的版本与线上不同，会顺带换掉认证、令牌等公共代码。急用时只做「线上包 + 只换 pay 类」，并先用 `javap` 逐类比对线上包与修复前源码一致。
3. **发 near-pay 前先比对线上 jar 里的迁移版本与正式库版本**，并先和 RobinWu 对齐，避免互相覆盖（今天发生了两次）。
4. **改正式库前的备份必须确认有数据行**（`grep -c "INSERT INTO"` 大于 0）；带引号的 SQL 用 heredoc 经 stdin 传，不要在 `ssh … sh -c "…"` 里嵌转义。
5. **改共用正式库的菜单 / 权限前先想清另一个版本会怎样**（V686 就是因此暂缓；现在 v1 已停）。
6. v2 正式流量都在网关**拦截档**；规则有漏，会直接表现为真实用户请求被拒。
7. 连接方式：`ssh -F ~/work/env/ume/ssh.config -J ume-jump -i ~/work/ume/ume-id_rsa -o UserKnownHostsFile=~/work/env/ume/ssh-known-hosts ume@192.168.3.<166|61>`；.80 用带它主机公钥的临时配置（见上线记录「怎么连」）。生产上不输入、不设置任何密码；测试运营账号的口令由用户自己设。
