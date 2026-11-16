# 商户 App 与后端接口对齐 · 交接

日期：2026-11-16。范围：商户 App（ai-shop-bapp）正式版发出的请求与 ai-shop 后端的对齐结果、`feat/entity-v46` 分支的落地状态、后续开发计划。
本文只记录事实和计划，不替代各功能的 TDD：动契约的条目仍按 `.claude/skills/ai-shop-dev-standards` 分档，计划写进对应 TDD §2。

---

## 一、总览

**一句话**：App 正式版的 365 个请求里，最新 main 已有 268 个；`feat/entity-v46` 合并后再补 8 个；其余约 87 个后端没有，App 正式版已拦截（不发请求、入口隐藏），后端补齐并通知后 App 删掉对应拦截即可上线。

| 情况 | 数量 | 说明 |
|---|---|---|
| main 上已有 | 268 | 直接可用 |
| 合并 v46 后补上 | 8 | 店铺设置 4 个、开店申请 2 个、新建主体、上传主体文件 |
| 后端没有 | 约 87 | 明细见第三节 |

**首发前必须完成的两件事**：① 推送并合并 `feat/entity-v46`；② 商户账号管理（尤其注销，应用商店合规要求）。

依据：
- App 侧清单：ai-shop-bapp `docs/implementation/App-API对齐清单-2026-11-16.md` 与同名 CSV（App `feat/core-flow-ui` a275aa1）。
- 后端核对：2026-11-16 抽取 `origin/main`（`3a53f7cab`）与 `feat/entity-v46`（`9390e41de`）代码中的 Controller 映射逐条比对。比对方法用清单里「后端已有」的 269 条自检，267 条吻合。

---

## 二、分组

| 优先级 | 条目 | 状态 | 估计工作量 |
|---|---|---|---|
| P0 | v46 落地（第三节 3.1） | 已 rebase、已提交，**未推送** | 约 1 天 |
| P0 | 环境配置：收款语音推送、付款链接有效期（3.2） | 待运维 | 不占开发 |
| P0 | 商户账号管理（3.3） | TDD 已确认，未实现 | 4–6 天 |
| P1 | 柜台报价状态、门店用量余额、扫顾客付款码（3.4） | 无 TDD | 共 2.5–5 天 |
| P1 | 资金 `/biz/funds/*`（3.5） | TDD 已确认，未实现 | 6–8 天 |
| P1 | 感应收款 SoftPOS（3.6） | 等蓝新合同 | 待定 |
| P2 | 订单事件、餐饮映射、连锁营销、组件退款、人事（3.7） | 未立项 | 先出映射文档再估 |

关键路径：v46 落地（1 天）→ 账号管理（4–6 天）满足首发；P1 可由第二人并行。约 1.5 周可首发，约 3 周完成 P1（不含 SoftPOS）。工作量均为估计。

---

## 三、逐项详情

### 3.1 `feat/entity-v46` 落地

**当前状态**
- 分支仅在本机：worktree `/Users/neargo2026/work/ai/ai-shop-entity`，基于 `origin/main`（`f1988ec07`），领先 21 个提交（含本交接文档）、落后 0。远端没有这个分支。rebase 会改变提交号，以下按提交说明引用。
- 分支默认跟踪的是 `origin/codex/sse-stability-20261114`，推送必须显式写目标，否则会推错分支：

  ```bash
  cd /Users/neargo2026/work/ai/ai-shop-entity && git push --no-verify -u origin feat/entity-v46:feat/entity-v46
  ```

  `--no-verify` 是因为本机缺 `rg`，pre-push 会中途失败；推送前应按 3.1「待办」补跑。
- 本地备份分支：`backup/entity-v46-pre-rebase-20261116`（第一次 rebase 前，`6ac165afe`）、`backup/entity-v46-pre-rebase2-20261116`（第二次 rebase 前）。

**本轮修复**（提交「fix(merchant): lock entity projection job and record account-level entity permissions」）
- `EntityRegistrationProjectionCoordinator#run` 加 `@SchedulerLock(name = "entity-registration-projection", lockAtMostFor = "PT10M")`：一轮最多 100 条，防 worker 多实例重复执行。
- 权限表 `BizEndpointPermTest`：`/biz/entities`、`/biz/entity/{entityNo}`、`/biz/entities/create-results/{requestNo}` 登记为账号级 `@perm.merchantAccount()`（V46 起主体可先于门店存在，归属在方法内由 `personalUser()` 核对）；补登 `/biz/store/settings/contact` 为 `STORE_ADMIN`。
- `BizEntityController` 的新建和查询新建结果从 `@managementAccess.authenticated()` 统一为 `@perm.merchantAccount()`，两者判断相同，行为不变。

**两次 rebase 的冲突处理**（均采用 main 的写法）

| 文件 | 处理 |
|---|---|
| `schema-test.sql`（生成文件） | 第一次 rebase 时用 `backend/scripts/gen-test-schema.py` 重新生成；v46 的生成器会去掉 `COLLATE utf8mb4_bin`，main 新增的收款表也随之去掉 |
| `MarketContactPolicyPortImpl` | 两边各自新增，逻辑相同，取 main |
| `CommerceStoreCreationFlowTest` | 取 main 的审核口径：有审核权限的提交人可审核自己的申请 |
| `MallStorefrontMysqlTest`、`CommerceOfflineOpsHttpController` | 只差注释与空格 |
| 4 个场景测试（`BeautyPolicyEndToEndIT` 等） | main 已有同一 `TestComponent` import，避免重复 |
| `BizLoginStoreNativeMysqlTest` | main 已有同样改法，验证码用 `135790` |

原 25 个提交中 5 个被丢弃：2 个支付提交 main 已有相同补丁，3 个修复 main 已独立做过（变为空提交）。v46 迁移 `V673`–`V675` 排在 main 的 `V672` 之后，无重号。

**已验证**（本地各跑一次）：`ArchitectureTest` 23（1 跳过）、`BizEndpointPermTest` 12、`SchemaDriftTest` 6、`PlatformEntityCreationFlowTest` 2、`EntityProfileAccountFlowTest` 12、`PersonnelControllerTest` / `ResourceControllerTest` / `ScheduleControllerTest` 各 2 全部通过。

**待办**
1. 连本机 MySQL 9 跑 `CommerceStoreCreationFlowTest#nativeQuickStartAndOperatorApprovalCommitHostAndCommerceTogether` 与 `BizLoginStoreNativeMysqlTest`：这两处冲突所在的测试本轮被跳过，未经验证。
2. `schema-test.sql` 与生成器输出差 53 行。原分支 `6ac165afe` 就有，非 rebase 引入；`SchemaDriftTest` 只比表和列，未拦下。
3. 安装 `rg` 后完整跑 `bash .githooks/pre-push </dev/null`（含 `vue-tsc` 与后端编译）。
4. 推送 → PR → 合并（合并需用户确认）。
5. 核对 App 的 `POST /biz/entities/{no}/{action}`：后端是逐条具体路径，需确认 App 用到的每个 action 都有对应。

### 3.2 环境配置（运维）

| 项 | 要做什么 | 备注 |
|---|---|---|
| 收款语音推送 `GET /biz/payment/events` | 各环境配置 `shop.payment-collections.enabled=true`、`environment`、`secret` 与 `cursor-secret`（各至少 32 字符）、outbox unified 模式、按门店 `source-bindings` | 仓库的配置文件里没有这些项，只有测试设置；接口已发 `X-Accel-Buffering: no`，前面若有 CDN 或其他网关需单独确认不缓冲 |
| 付款链接有效期 | 确认台湾测试与生产的 `shop.pay.collect.max-expire-minutes` ≥ 1440 | 代码默认 1440（含），仓库未覆盖；App 新建链接默认 `expireMinutes=1440` |

验收：真机收款后 3 秒内播报；断网重连靠 `Last-Event-ID` 不漏不重。

### 3.3 商户账号管理（P0）

设计：[TDD-商户App统一登录与账号](../../technical/TDD-商户App统一登录与账号.md)（已确认，D1–D18 均已拍板；11-10 已完成身份发现、进入与切换）。本次实现其余部分（AC11–AC22），字段与 App 清单一致。

| 步骤 | 内容 | 天数 |
|---|---|---|
| 1 | 迁移：`usr_account_change_log`（迁移号实施当天再取） | 0.25 |
| 2 | `AccountCredentialService`：换手机、绑邮箱 | 1 |
| 3 | `UserServiceImpl#deregister` 抽出 `deregisterCore`，买家端照旧调用 | 0.5 |
| 4 | `MerchantDeregisterPrecheck` 与订单、资金、门店套餐三个只读端口（最大风险） | 1.5–2 |
| 5 | `BizAccountController` 5 个接口：`GET /biz/auth/account`、`POST /biz/auth/phone/change`、`POST /biz/auth/email/bind`、`GET /biz/auth/deregister/precheck`、`POST /biz/auth/deregister`；路由登记约 9 处；错误码 10475 / 70100 三语 | 1.5 |
| 6 | 测试，并补齐 TDD §5、§6 | 1 |

完成后 App 删掉 `/biz/auth/*` 的拦截。

### 3.4 P1 小项

| 功能 | 建议 | 档位 | 天数 |
|---|---|---|---|
| 柜台报价保存状态 | 在现有 `GET /biz/service-orders/counter-quotes/{no}` 里返回 `canAccept`、`confirmation`、`result{orderNo}`，不新开 `/state` | 1 档，需 TDD | 0.5–1 |
| 门店用量余额 `GET /biz/stores/{storeNo}/usage` | 新接口，返回 `balances[]{kind: AI_TOKEN\|SMS, balance, expiresAt}` 与 `entries[]{kind, title, at, delta, until}`；先确认 AI 额度和短信余额的数据源。现有 `/biz/entitlement-usage` 是买家权益核销，不等价 | 1 档，需 TDD | 1–2 |
| 扫顾客付款码 | 把终端已有的 `POST /terminal/pay/v1/payments/{no}/customer-code` 包装到 `/biz/payment` 下：先建单再扣款，返回成功或待确认 | 1 档，需 TDD | 1–2 |

### 3.5 资金 `/biz/funds/*`（P1）

设计：[TDD-商户App资金](../../technical/TDD-商户App资金.md)（已确认，2 档，**还缺 ADR**）。6 个接口：`overview`、`daily`、`payouts`、`reconcile`、`accounts`、`rates`。数据真源在独立支付服务 near-pay，需它新增查询；一期只能给「预计拨款」和平台自查对账，实际拨款与渠道账单要等 near-pay 建表。估计 6–8 天。

### 3.6 感应收款 SoftPOS（P1，阻塞）

需要 `/biz/softpos/devices`（GET/POST）、`/biz/softpos/devices/{id}/unbind`、`/biz/softpos/charges`（POST）、`/biz/softpos/charges/{no}`（GET）。现在只有终端接口 `POST /terminal/pay/v1/payments/{no}/card`。**等蓝新合同**，签约前不排期。

### 3.7 P2

- **先出映射文档再估工期**：
  - 餐饮 44 个接口：App 用旧的 `/biz/food/*`，后端是 `/biz/x/food/*`，模型不同。
  - 连锁营销 3 个：App 的 `/biz/ops/*` 对应到 `/biz/management/brands/{no}/marketing/*`。
- **需要开发**：
  - 订单实时事件：在 `/biz/payment/events` 或独立的流里加 `order.created`、`order.stage_changed`。
  - 统一的 `orderChannel` 枚举。
  - 商城组件退款 `/biz/stores/{no}/mall/orders/{no}/component-refunds`。
- **等产品决定**：人事 `/biz/hr/*`（21 个接口：打卡、排班、请假、工资）是否立项。

### 3.8 不需要后端做

- 拼团、接龙 `/biz/activities`：字段已核对一致，App 已放开（App 提交 853f499）。
- 主体证件识别：用现有 `POST /biz/qualifications/recognize`，由 App 改为上传图片并映射字段。
- 商品素材 3 个 `/mock/retail/goods/*`：App 内仅 mock。
- `POST /biz/auth/market`：只在 App 旧版页面使用，App 会清理。
- 旧美业 `/biz/beauty/*`：App 已改用 `/biz/x/beauty/*`，旧页面会删。

---

## 四、注意事项

- **共享工作区**：主目录 `/Users/neargo2026/work/ai/ai-shop` 常有多个会话同时在改。v46 的工作都在独立 worktree `ai-shop-entity` 里做，不要在主目录切到这个分支。
- **推送凭据**：本机 Claude 会话的 shell 没有 GitHub 登录，推送需在用户自己的终端执行。
- **通知 App**：每完成一项，告诉 App 会话可以删掉哪些拦截（pendingContracts 名单），App 再放开对应入口。
- **工作量均为估计**，实施时以各 TDD §2 的计划为准。
