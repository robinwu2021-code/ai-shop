# 交接 · 手持收银机后端（门店零售单 P3 等）· 2026-11-24

写给接手的会话。来源会话：处理手持收银机 App 后端需求的 ai-shop 会话。**接手第一件事：读完本页「三、下一步」与「五、红线」再动手。**

## 一、一句话现状

手持收银机 App 提的 18 项后端需求已评估完，写成 [TDD-手持收银机后端](../../technical/TDD-手持收银机后端.md)（草稿，含第一阶段施工图与 62 项任务清单）。
执行顺序按用户要求：**基础 → 零售 → 美业**。第一阶段的 PR-1（W2 店内设定放行）已提交 PR、未合入、未发 v2；PR-2 起未开工。

## 二、文档与 PR 地图

| 内容 | 位置 | 状态 |
|---|---|---|
| 设计、执行顺序、任务清单 T001–T314、第一阶段施工图 | [TDD-手持收银机后端](../../technical/TDD-手持收银机后端.md)（§2「待开发任务清单」「第一阶段（基础）实现方案」） | 草稿；PR [#56](https://github.com/robinwu2021-code/ai-shop/pull/56)（分支 `docs/handheld-pos-backend-tdd`，只有文档） |
| W2 店内设定放行（PR-1） | PR [#57](https://github.com/robinwu2021-code/ai-shop/pull/57)，分支 `feat/pos-w2-store-config-scope`，提交 `dd14beb42` | 待评审合入；未发 v2 |
| App 原始需求 | ai-shop-bapp 仓库 `handheld-pos/docs/手持收银机后端任务与API建议-2026-11-24.md`（分支 `handheld-pos`） | — |
| 门店零售单 P1/P2 与发票 | [TDD-统一订单与开票资料](../../technical/TDD-统一订单与开票资料.md)、[发票交接](../invoice-unified-order-2026-11-24/README.md) | P3 设计已移到本篇 W1 |

## 三、下一步（按顺序）

| # | 事项 | 说明 |
|---|---|---|
| 1 | 评审并合入 #56、#57 | #57 合入后发 v2 要**用户在执行会话里亲口确认**（见五） |
| 2 | PR-2 · W4 收银设备、员工 PIN、POS 会话、订单归属栏位 | 施工图见 TDD「PR-2」节。迁移 **V696**（`ord_order.member_no / operator_no / device_no`）、**V697**（`mch_pos_device`、`mch_pos_activation`、`mch_account_pin`、`mch_pos_session`）；错误码 `70201–70203`。本会话只读过代码、**一行未写** |
| 3 | PR-3 · W5 班次与日结 | V698；依赖 PR-2 的设备与 POS 会话 |
| 4 | PR-4 · W7 会员消费纪录 + W6 营业汇总 | 无迁移；依赖 V696 栏位 |
| 5 | 第二阶段零售（W1-a～d、W8）、第三阶段美业（W3、W9） | 见 TDD 任务清单 |

PR-2 开工前已确认的代码事实（省得重查）：
- `TokenStore.SessionData` 只装 `LoginUser`，带不了门店／设备 → 设计用 `mch_pos_session`（token 哈希）+ `BizContextFilter` 之后的收窄过滤器。
- 员工会话照 `MerchantStaffServiceImpl#issueStaffSession` 的写法签 `LoginUser.merchant(mchAccountNo, displayName)`。
- 限流有 `shop-base` 的 `InMemoryRateLimiter`；bcrypt 有 `spring-security-crypto` 的 `BCryptPasswordEncoder`（inventory 已在用）。
- 内置角色加权限码要照 `V694__store_order_role_permissions.sql` 写 `JSON_ARRAY_APPEND`。
- `ord_item` 已有 `category_no` 快照（W6 按品类汇总可直接用）。

## 四、待用户决定

| # | 问题 | 建议 |
|---|---|---|
| L4-1～L4-7 | TDD「L4 待决」七项（储值付款另立项、未付单逾期、离线补传缺货、券与活动叠加、改价次数、美业全店停约、美业测试主体） | 各项已附建议，用户尚未逐条确认 |
| T001 | 读一次 v2 正式库：`PENDING_LICENSE` 主体（`MUME88600253990002308`）的门店 `mch_store.status` | 需用户同意；门店建立时一律 ACTIVE，预期无问题 |
| PR-2 会员 | 会员号解析要求主体 ACTIVE，审核中主体建单带会员会回 10404 | 建议维持 |

## 五、红线

- **v2 连正式库 `ai_shop_ume`**：发版、改配置、重启、写库，都要用户在执行会话里亲口确认；别的会话转述不算。重启约 40 秒 502，先通知相关会话。
- 美业在 v2 启用不是改开关：要 `-Pbeauty-embedded` 重新构建，并以 `shop.beauty.migration.mode=migrate` 一次性建 `bty_*` 表。
- 共享工作区：每个 PR 开独立 worktree（本会话用 `../ai-shop-wt-*`），不切主目录分支；提交前 `git diff` 自读。

## 六、已知坑

- **三个 `*MaintenanceIT`（人员／排班／资源）在 `main` 上跑不起来**：`schema-test.sql` 含 H2 专用的 `CREATE ALIAS` 与 `IS JSON OBJECT`（自 866e3870f），在 MySQL 上建表失败。#57 新增的用例因此未跑；修 fixture 是独立的活。
- **pre-push 全量测试本机 199 红**：全是 commerce / invoice 的 `*MysqlTest`，缺 `COMMERCE_MYSQL_URL` 等真 MySQL 环境，与业务改动无关。
- **储值扣款只支持 CN/CNY**（`EntitlementServiceOrderServiceImpl`），台湾门店不可用 → L4-1。
- **旧扁平活动模型已可编辑**（`POST /biz/activities` 带 `activityNo`），X24 只需补 `revision` 与进行中改名。
- **商品课税别 10403 有两因**：主体非 ACTIVE（#57 已放行审核中），或非主体负责人（设计如此）。
- **会员载具在 `ivc_user_carrier`**，不是 TDD-统一订单所写的 `ivc_buyer_preference`。
- **错误码号段**：2xxxx 交易、3xxxx 履约、7xxxx 商家；手持新码用 `70200` 起、`2003x` 起。
- **迁移号按执行顺序**：V696 订单归属、V697 设备与 PIN、V698 班次、V699 门店退款；别按工作流编号抢号。

## 七、与其他会话

| 会话 | 关系 | 欠的话 |
|---|---|---|
| 手持收银pos（ai-shop-bapp） | 需求方，等环境与接口 | **还没告诉它**：① X24 活动编辑沿用 `POST /biz/activities` 带 `activityNo`（先前说要新 `PUT`，已更正）；② 课税别 10403 两因；③ 店内设定被拒的新码是 `70200`；④ 执行顺序改为基础 → 零售 → 美业，美业环境会晚给 |
| 发票-鲸跃 | 不做 P3，只做发票测试 | 无 |
