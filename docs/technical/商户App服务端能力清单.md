# 商户App服务端能力清单

> 2026-11-08 盘点。对照：商户 App 交接文档（ai-shop-bapp `docs/implementation/功能矩阵与API交接文档.md`，main `31a56c1`，21 个模块、约 345 个接口）× ai-shop `codex/bapp-handoff-tdd` @ `891008be6`（已部署 166）× near-pay `develop/s1` @ `993cf6b49`。
> 明细：[主系统明细](./商户App服务端能力清单-主系统明细.md)（模块 1–6、10–12、14–21）· [支付明细](./商户App服务端能力清单-支付明细.md)（模块 3 在线付款、4、7、8、9）。
> **进销存（模块 13）搁置**：独立进销存系统不在本仓库 `backend/inventory/`，位置确认后另行盘点。

## 一、结论

- 交接文档里 App 要调的接口，**主系统范围**：可用 181、有缺口 82、缺失 46；**支付范围**：可用 26、有缺口 18、缺失 20。
- 「有缺口」大多不是没写代码，而是**没开**或**没接对**：路由没登记（新模型门店 70006）、插件没部署、读错了数据源、App 用的路径和后端不同。真正要新建的域只有**考勤与工资**（整域）、**资金查询**（near-pay 侧）、**外部记账**、**统一登录**、**商城用量**。
- 台湾一期有几处**现在就挡路**、改动又小的问题，应最先做：台湾手机号在三处被拒（加不了员工、加不了会员）；台湾渠道进件状态读的是大陆进件表（恒显示「未开通」）；LINE Pay 已上线但 0 次确认扣款；团购建活动一提交就失败。

## 二、按系统的分工

| 系统 | 负责 | 本清单里的位置 |
|---|---|---|
| ai-shop 主系统（shop-app 及各域） | 账号、门店、商品、订单、营销、员工、列印、商城；对支付只做 BFF（认人、判权、定门店、准入登记） | §三 |
| ai-shop 行业插件（`backend-plugins/shop-industry-food`、`shop-industry-beauty`） | 餐饮、服务业的真实能力，前缀 `/biz/x/food/*`、`/biz/x/beauty/*`；默认构建**不挂载**（`shop-app/pom.xml` `food.build.shape=retail`），166 当前的包也没挂 | §三 |
| near-pay 独立支付服务 | 收单、退款、收款码、付款链接、进件与渠道账户、终端 / SoftPOS、对账；资金数据真源（ADR-052） | §四 |
| 独立进销存系统 | 进销存（模块 13） | 搁置 |

## 三、主系统要补的

### 3.1 新表

| 表 | 模块 | 有 TDD |
|---|---|---|
| `usr_login_ticket`、`usr_account_change_log` | 1 统一登录、5 换手机 / 绑邮箱 / 注销 | 有 |
| `sys_app_version`；`notify_ticket` 加 9 列 | 6 问题回报与版本 | 有 |
| `ord_offline_record`、`ord_offline_record_event` | 7 外部记账（trade 域，不进支付） | 有 |
| `commerce_usage_account`、`commerce_usage_entry`；SKU 组件加 `USAGE` | 3 商城用量 | 无 |
| `mch_schedule_exception` | 15 停约、17 请假 | 无 |
| `hr_*` 13 张（班次、打卡规则、排班周 / 格、打卡记录、申请、假期余额、月考勤、工资方案及版本、算薪批次 / 明细 / 更正） | 17 考勤与工资 | 无 |

改已有表：`mkt_group_buy` 加 `fail_reason`（20）；美业插件库服务定义 / 预约加订金列（15，若首期要）；`mch_personnel_store` 加 `bookable`（15）；`mch_payment_merchant` 加 `source`、`channel_merchant_masked`（4，N2）。
166 的应用账号**没有建表权限**，带 DDL 的迁移要由 root 先执行；当前主库迁移最大号 V636，多会话并行，动手时再取号。

### 3.2 新接口

| 接口 | 模块 | 状态 |
|---|---|---|
| `/biz/auth/identities`、`/enter`、`/mine`；`/biz/auth/account`、`/phone/change`、`/email/bind`、`/deregister(/precheck)` | 1、5 | TDD 已冻结（ADR-053/054） |
| `POST /biz/feedback`、`GET /biz/feedback/mine`、`GET /common/app/version` | 6 | TDD 已冻结；App 要把 `/app/version` 改成 `/common/app/version` |
| `GET /biz/express/companies` | 12 | TDD 已冻结 |
| `/biz/payment/offline-records*`（4 个） | 7 | TDD 已冻结 |
| `POST /biz/merchant/onboarding`；`POST /biz/store/{s}/entity` + `relink-check`；`POST /biz/merchant/payment/bind` | 4 | TDD 已冻结（ADR-055/056）；bind 依赖 near-pay |
| `GET /biz/funds/{overview,daily,payouts,reconcile,rates,accounts}` | 8 | TDD 已冻结（ADR-052）；依赖 near-pay |
| `/biz/food/*` App 门面（约 21 条，转调 `/biz/x/food/*`） | 14 | 无 TDD；后端能力 19/21 已有，真缺的只有外送手工接单 / 拒单 |
| `/biz/beauty/*` App 门面（约 22 条）+ 排班例外 / 排班影响预览 / 可预约开关 / 汇总 / 时间线 / 报表 | 15 | 无 TDD；美业插件 BM04–06（营业、履约）未交付 |
| `/biz/hr/*`（22 条） | 17 | 无 TDD，2 档 |
| `GET /biz/stores/{s}/usage` | 3 | 无 TDD；全仓没有扣量点，先定商业模式 |

### 3.3 改已有接口

| 接口 | 改动 | 模块 |
|---|---|---|
| `POST /biz/staff`、`POST /biz/members`、`PUT /biz/notify/sms-phones` | 号码校验从大陆 11 位改为按市场（已有 `Phones.valid(phone, cc)`），存 E.164 | 16、18、5 |
| `GET /biz/notify/setting`、`GET /biz/staff` | 加 `ownerEmail` / `loginEmail` | 5、16 |
| `POST /biz/member-segments(/preview)` | 规则加 `idleDays`（App 送了、后端静默忽略，人数算错） | 18 |
| `GET /biz/coupons` | 加 `status` 过滤（暂停的券现在也返回） | 18 |
| `GET /biz/activities`；`GET /biz/groups`、`/biz/group/{g}` | 加 `triggerType` 过滤、`goods[]`；加 `failReason`、`refundedMinor` | 20 |
| `GET /biz/entities`、`/biz/entity/{e}` | 加 `review / applyNo / rejectReason / taxNo / owner` | 4 |
| `GET …/mall/orders/{o}` | 组件带 `sourcePeriodNo`，按组件退款改走已有 `/refunds`（不新建接口） | 3 |
| 订单 / 自提 / 售后 / 商品若干 VO | 补 `dueAt`、`overdueCount`、`verifiedAt`、`returnRequired`、`submittedAt` 等；修自提 `arrivedBatches` 恒 0 | 11、12 |
| `ShopCommerceEndContextAdapter#strictScope` | 准入层「权限不足」与「路由未登记」分开给 `nextAction`（现在都是 `CONTACT_ADMIN`，店长会看到「门店没开放这个功能」） | 全局 |

### 3.4 新路由登记（不登记，新模型门店一律 70006）

| 登记 | 模块 |
|---|---|
| ACCOUNT 通道：统一登录、换手机 / 绑邮箱 / 注销、`/biz/feedback*` | 1、5、6 |
| `/biz/express/companies`、`/biz/funds/*`、`STORE + entity(/relink-check)` | 12、8、4 |
| `/biz/food/.+`（或 `/biz/x/food/.+`），**并以 `-Pfood-embedded` 构建部署** | 14 |
| `/biz/beauty/.+`、`/biz/(personnel|resources|schedules|schedule-rules)(/.*)?`，**并以 `-Pbeauty-embedded` 构建** | 15 |
| `/biz/management/(brands|brand-changes)(/.*)?`；策略放开 `/biz/stores/{s}/(brand-changes(/check)?|operating-context)` | 19（现在**任何账号都进不去**） |
| `/biz/stored-value(/.*)?` 及 `/biz/stores/{s}/stored-value/*` | 18（储值首期开放时） |
| `/biz/hr/.+` | 17 |
| 关路影响清单 `/biz/stores/{s}/fulfillment/{channel}/impact` | 2（要在 `LEGACY_PREVIEW` 按门店模型分流并补 MySQL 安全测试，不能简单登记） |

### 3.5 App 侧改路径即可、后端不用新建

- 营销 v20 的 `/biz/ops/*`（17 条）全部有等价接口：`/biz/activities`、`/biz/coupons*`、`/biz/coupon-redeem`、`/biz/members*`、`/biz/stored-value/*`。建议作废 `/biz/ops/*`。
- 连锁：用已实现的品牌 / 经营组织接口（登记路由后）。
- 团购建活动：App 补 `benefitType=PRICE` 与 `quota`（现在一提交就 10400 / `ACTIVITY_QUOTA_REQUIRED`）。
- 商城按组件退款：走已有 `/refund-previews` + `/refunds`。

## 四、支付（near-pay）要补的

### 4.1 near-pay 侧

| 类型 | 项 | 优先级 |
|---|---|---|
| 新接口 | `/internal/pay/v1/biz/stores/{s}/funds/{overview,daily,payouts,reconcile,rates,accounts}` + `MerchantFundsQueryService` | P0 |
| 改接口 | `source-links/apply` 的 `profile` 加 `settlement`（只存掩码与密文） | P0 |
| 新代码 | 信用卡当日取消授权（Apple Pay 已上线，退款受限） | P0 |
| 排查 | LINE Pay：11-05 起建付款页 22 次、Confirm 0 次；166 到 LINE Pay 间歇超时 | P0 |
| 配置 | 台湾 `sys_pay_channel_rate` 费率种子（否则手续费、费率页为空） | P0 |
| 配置 | **迁移号段分配**：near-pay 实际最大 V70，Google Pay / SoftPOS 启用 / 收款链接三份文档都写「从 V71 起」 | P0（开工前） |
| 新接口 | 渠道账户认领 `POST /internal/pay/v2/source-links/channel-account-claims`（+查询） | P1 |
| 新表 | `pay_channel_bill`、`pay_channel_bill_line`、`pay_reconciliation_run`（design-v2 已定义未实现）；`pay_channel_payout`（design-v2 没有，要先设计）；认领单 | P1（资金一期 B） |
| 改表 | `stl_recon_diff` 加 `store_id` 等 | P1 |
| 新代码 | 蓝新、LINE Pay 账单下载（`StatementCapability` 实现）；`file:<assetKey>` 材料取图；收款来源加 `SOFTPOS` | P1 |
| 新代码 | SoftPOS TW-S02～S05；Google Pay | P1 / P2（等厂商与蓝新答复） |

### 4.2 ai-shop 侧

| 类型 | 项 | 优先级 |
|---|---|---|
| 新接口 + 端口 | `BizFundsController` / `BizFundsAppService`，`backend/pay` 同形镜像 | P0（near-pay 先发） |
| 新接口 | `POST /biz/merchant/onboarding`（登记在 **foundation** 通道，不进 `ShopStoreMemberRoutes`） | P0 |
| 改端口 | `PaySourceLinkApi.Profile` 加 `settlement` | P0（与 near-pay 同发） |
| 改接口 | `/biz/merchant/payment*` 在台湾改读 near-pay 进件进度（已有 `/internal/pay/v2/merchants/{m}/capability-progress`，含 `signUrl`），现在台湾恒为 `NONE` | P1 |
| 新接口 | N1 门店改挂、N2 自带商户号 | P1 |
| 改代码 | near-pay 不可达统一转 10505 | P2 |
| 不建 | `/biz/softpos/*`：先定对接形态（见 §六） | — |
| 改代码 | 商城平台代收远程化（现在直连 `payDataSource`、在线路由恒不可用） | P2（App 未接） |

## 五、建议排期

> **2026-11-08 进度**：主系统 P0 已完成（未发版）：台湾手机号（员工、会员；短信接收号码刻意保持只收大陆号，因为没有台湾短信通道）、外部记账（V637）、问题回报与版本（V638/V639）、快递公司。166 列印密钥、团购请求体对齐（App 侧）不在后端代码范围内，仍待处理。


| 优先级 | 内容 | 依赖 |
|---|---|---|
| **P0** | 台湾手机号三处校验；`/biz/merchant/payment*` 台湾改读 near-pay（或先在 App 隐藏）；外部记账、问题回报与版本、快递公司三份 1 档 TDD；团购请求体对齐（App）；166 列印密钥；LINE Pay 排查、当日取消授权、台湾费率种子；near-pay 迁移号段 | 无 / near-pay |
| **P0（跨仓库）** | 资金一期 A、商户资料提交 N3 / N4、统一登录与账号 | near-pay 先发版；统一登录还要另开「员工登录邮箱」TDD |
| **P1** | 餐饮门面 + 构建部署 + 路由；营销改走已有接口（后端补 `idleDays`、`status`）；连锁路由；列印份数；各处 VO 补字段；N1 / N2；资金一期 B（等渠道账单格式） | 首批店型拍板 |
| **P2** | 考勤与工资整域；商城用量；服务业（BM04–06 未交付）；体验项 | 先写 2 档 TDD |

## 六、待拍板

1. **首批店型**：餐饮、服务业哪个先上。决定 P1 的门面要做哪一套，以及 166 用哪种构建形态。
2. **SoftPOS 对接形态**：App 自拟的 `/biz/softpos/*`（经 ai-shop、凭 `txnNo` 回报）与 near-pay 已定的 `/terminal/pay/v2` 执行链（设备签名、只记待核实）冲突，建议以 near-pay 为准，App 改。
3. **商城在线付款与原路退款归谁**：现在 ai-shop 直连自己的支付库，不经 near-pay，与 ADR-021 / 043 不一致。
4. **准入层 70006 是否区分「没开放」与「没权限」**（§3.3 末行）。
5. **储值首期开不开**（决定 §3.4 储值路由）。
6. **飞鹅打印机在台湾走哪个 API 域名**（现在是全局单值，默认 CN）。
7. **考勤与工资的劳基法口径**、排班与美业「可约规则」的关系。
8. **商城用量的商业模式**（现在没有任何计量点）。

## 七、风险

- **跨仓库发布顺序**：资金、N3 `settlement`、N2 认领都必须 near-pay 先发；反过来 166 会调到不存在的内部路径。
- **两份 pay 代码分叉**：`PayBizFacade`、`MerchantPaymentQueryServiceImpl` 在两仓库已分叉；资金查询要两边各写，建议先写 near-pay 再逐字拷回。
- **分支未合入集成线**：本清单基线分支 `codex/bapp-handoff-tdd`（含 PR #2）还没合进 `codex/ai-shop-codex`，从集成线打包发 166 会覆盖已上线的修复。
- **安全**：near-pay 进度文档记载生产主密钥明文曾出现在会话输出中，蓝新与 LINE Pay 凭据都由它加密，应安排轮换（盘点只转述，未核实）。
- **单人审批开关**（near-pay V69、V70）目前开着，上线运营前要决定是否关闭。
