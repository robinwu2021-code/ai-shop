# TW-E2E-20261110-01 · 166 验收记录

状态：业务验收已完成，远端推送待通过仓库检查。日期：2026-11-11。

## 发布与保留范围

166 API、OPS 均运行源码 `78efc45ce` 的制品，SHA-256 为 `7ece70f156aecc37e130a45f571931c523a215417d45969ea91f8558bb4df63a`。两端健康检查 UP，限定草稿配置及共享上传挂载均已复核，发布锁已释放。回滚备份：`/data0/app/ai-shop/releases/tw166-draft-scope-78efc45ce`。

V649/V650 保留原迁移及 checksum；连锁法人和营销迁移改用 V651/V652，已完成备份库演练和 166 迁移。当前 Flyway 版本 652。此前 25 项相关验证通过，限定草稿另有 8 项测试通过。本轮未修改应用代码，因此没有重复执行这两组测试。

原始 ai-shop、bapp 和独立 near-pay 工作区的未提交内容均保留；本批使用独立发布工作树。未部署独立 near-pay、库存或服务业代码。

## 正式入驻审核与法人绑定

- 已使用用户提供的运营账号正常登录；本次直接获得会话，无二次验证挑战。凭据及会话未写入仓库。
- 申请 `MA261111RE680NW3YB` 经正式受理、审核接口变为 APPROVED，原主体 `M261106BF7GK8052W` 原位激活为 ACTIVE、verified=true。
- 原申请的 COMMUNITY 没有社区，166 社区表也为空。本次通过已启用的 CITY 范围审核测试主体；未创建虚构社区、未开启新的全局范围。
- 商户候选 API 确认该 ACTIVE 主体后，品牌 `BR261110Z2FRZ2DHE5` 经法人绑定 API 写入该主体，命令回读一致，品牌营销上下文可读。
- 门店 `ST2611100C2XNFCXRS` 原有 ACTIVE 品牌绑定 `BRB2611100VN7QPQSAZ` 保留。

## 活动草稿

AP023/v1、TWD、指定原门店的草稿 `PT261111TVW6WJ7MM0` 已创建并通过商户接口回读，数据库确认 DRAFT。未发布活动；全局发布开关和执行器就绪检查继续保留。AP023 模板仍为 disabled/unavailable，草稿仅由精确配置的测试范围准入。

真实写入暴露了 166 导入触发器的孤儿 DEFINER：`shop@127.0.0.1` 不存在，导致草稿返回 10500。已恢复锁定、禁止登录的定义账号，仅授予六张 pmt 表的 SELECT/TRIGGER 和 `mkt_counter_campaign_fence` 的 SELECT/INSERT/UPDATE。18 个 pmt 触发器定义未改；负向事务确认不可变主体校验仍拒绝越权改属。执行记录见 [pmt-definer-repair.executed.sql](pmt-definer-repair.executed.sql)。这是 166 环境修复，不改历史 Flyway SQL。

该孤儿账号还被 27 个其他触发器引用；本次未扩展其余表权限，也未验收服务业路径，应由对应功能会话处理。账号生命周期参考 [MySQL 官方说明](https://dev.mysql.com/doc/refman/8.4/en/stored-objects-security.html)。

## 两套套餐线下闭环

FOOD 测试产品 `test-food-plan-166`、PRO/CHAIN 测试版本及 SKU 通过正式运营目录接口配置、行业发布成功，目录版本 2。测试售价：PRO 30 天 NT$13，CHAIN 30 天 NT$26；主体 TEST-PRO-30D 为 NT$10。收款指示为 TEST-NO-TRANSFER，商户和凭证均明确标注测试，未执行真实转账。

| 套餐体系 | 场景 | 订单 | 最终状态 | 测试核款 |
|---|---|---|---|---|
| FOOD | 商户提交、运营审核 | `4b89a2d7d3924ad7a0e3d66c3f7da0de` | COMPLETED | NT$13 |
| FOOD | 运营代购 | `0f87ce2a871a40b5ace6e6882dd55916` | COMPLETED | NT$13 |
| ENTITY | 商户提交、运营审核 | `EQb17f14201a2d46288a6054db7f730e26` | APPLIED | NT$10 |
| ENTITY | 运营代购 | `EQb10d49965eac4ae09fec443edabb6221` | APPLIED | NT$10 |

- 两张 FOOD 订单均 PAID / COMPLETED，商户提交的 claim 回读 CONFIRMED，每单只有一条入账。运营代购验收使用同档 PRO 续期。
- 商户订阅回读 currentPlan=PRO，两份各 30 天的 ORDER 来源按顺序衔接；END_BIZ_APP 为 ACTIVE。测试版本只复用已验证的基础端权益，不代表新增餐饮 POS 等高级能力已发布。
- 主体额度套餐两单均 APPLIED，商户 `/biz/plan` 回读 PRO / ACTIVE，额度按订单冻结的测试 Offer 生效。主体额度与门店商城订阅分别验证，未混用。
- CHAIN 测试版本及 SKU 已配置。普通购买尝试切换到更高档返回 UPGRADE_REQUIRED，符合必须使用升级报价的规则；本批没有宣称完成 CHAIN 升级或运营升级入口验收。

## 异常款退款与解除占用

两套套餐分别创建专用异常测试订单，以 NT$1 的错误金额核款，均进入 UNMATCHED。退款前解除占用均被拒绝；通过 REFUND_PROOF 记录测试退款后，退款为 REFUNDED，原款 refundedMinor=100，pendingRefund=false。相同退款动作重放返回一致，未重复记账。

- 商城异常订单 `56b70c177eb84a028dbc4c73d2a8c8f9`：CLOSED，`mch_subscription_reservation.status=RELEASED`，reserved_credit_minor=0。
- 主体异常订单 `EQ79c3d18d49214ea89b56d0c07d08b4f3`：REFUNDED，`mch_plan_purchase.open_entity IS NULL`。

详细脱敏结果见 [acceptance-166.json](acceptance-166.json)。此前其他批次的待审订单不计入本次验收，也未自动核销或删除。

## 远端提交

业务验收后执行共享检查：packages/shared 共 712 条断言、30 条失败；ops-web 共 955 条、0 失败。共享检查存在超过既有基线的契约字段、枚举、错误说明、数据域及血缘登记问题。未跳过 hook、未放宽 known-* 基线，未把其他会话未提交的修复带入本批。


推送结果：`ab0128503` 已在本地提交，本分支尚未推送成功。第一次 hook 识别出七个本批连锁路由没有跨仓消费登记；已核对独立 bapp 真实调用，补充公开契约、消费清单及 P1 TDD，孤儿检查恢复 88/88 原基线。第二次正常 push 通过该项后，在测试 schema 生成步骤被阻止：V652 的 `ck_brand_audience_counts` 使用 JSON_LENGTH，现有保留型 H2 投影拒绝该函数。未删除该数据库 CHECK、未改已部署 V652 的 checksum，也未绕过 hook。除此之外，上述共享预检问题仍需清理。

后续发布任务按优先级：先为 V652 原生 JSON 约束补齐可验证的测试 schema 投影策略及 MySQL 9 约束证据；再修复共享契约、枚举、数据域与血缘登记检查；全部通过后重新正常推送。其他会话的服务业实现继续独立处理，166 本轮业务验收结果不等于仓库发布检查已通过。
