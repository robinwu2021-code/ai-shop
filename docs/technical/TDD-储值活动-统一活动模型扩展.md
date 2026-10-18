# 储值活动统一活动模型扩展方案

> 模块归属修订（2026-10-18）：[完整模块化系统设计](./TDD-活动卡券储值-模块化系统设计.md)规定活动归核心服务、券/次卡/月卡/储值权益归支付服务。本文相冲突的旧模块归属与表/API草案保留为历史讨论，不得混用实施。


状态：设计提案，待评审 · 档位：2 · 2026-10-18

结论：使用项目现有 promotion/pmt_activity 统一活动体系，新增储值参与场景、固定档位规则、不可变版本和预算预留能力；储值账户与核销留在pay。不建立第二套sv_campaign，不把旧mkt_campaign的商品买赠当作储值赠金。

本提案调整此前[储值系统设计](./TDD-美业储值-系统详细设计.md)中“pay拥有活动规则”的推荐边界，尚未修改运行代码或已应用数据库。其数据库和实施计划须在本提案评审通过后同步，当前不得混用两稿直接实施。

## §0 决策与现状

源码已有两套活动：旧 marketing/campaign 的 MktCampaign 管 COUPON/FULL_CUT/FLASH/BUY_GIFT；统一 promotion 的 PmtActivity 已有触发、利益、排期、受众、商品范围及预算字段。后者有ActivityService、ActivityPricingService、MarketingSummaryService等实际实现，并非只有文档。扩展基于后者。

| 选项 | 评价 |
|---|---|
| 统一模型扩展，储值专用规则与执行器 | 推荐：管理与公共约束复用，资金语义独立 |
| 旧mkt_campaign再增加储值type及可空字段 | 不推荐：强化旧模型，和统一模型持续重复 |
| 新建独立储值活动系统 | 不推荐：身份、权限、排期、统计、发布又建一套 |

统一指统一活动身份、生命周期、公共约束和管理；不要求所有活动使用同一计价函数、同一资金账本或同一种参与流程。无需先重构拼团、集单、优惠券全链路才交付储值。

## §1 活动产品定义

商家在营销→活动中选择“储值充赠”模板，设置活动名称、时间、参与门店、参与对象和档位。例如充1000送200，充2000送600。各档位精确匹配，不默认解释为“超过1000可无限按比例赠送”。常规充值也必须有活动，赠送额允许为0。

首期仅商家出资、同币种固定金额赠金，一笔充值只参加一个储值活动、一个档位；不叠加订单券、积分返利或其他储值活动。不做赠服务、赠次数、跨主体通兑、线上退款。消费者页面不显示内部本金赠金分配，活动编辑可明确配置充赠金额。

必须区分两类范围：participationStoreScope决定在哪里参加充值；redemptionScope决定充值形成的权益在哪里、对哪些项目可用。后者成为支付批次快照，不能用“参与商品范围”含混替代。首期门店引用均需验证属于同一entity。

每人限购按activityNo跨发布版本累计，新版本不重置；需要重新获客则新建活动。活动名额与赠金预算同样按activityNo累计。档位版本变更仍受活动总预算约束，不允许改版重置已用额度。

## §2 公共模型优化

### 2.1 场景先隔离，再扩展利益类型

pmt_activity拟新增scene：ORDER（存量）、RECHARGE（储值）。owner继续复用MERCHANT/PLATFORM，但RECHARGE首期只允许MERCHANT。增加currency、current_version_no、revision；不能缺省币种就累加预算。

RECHARGE使用triggerType=TIER（拟新增，精确命中档位），benefitType=STORED_VALUE_BONUS（拟新增，储值赠金）。不复用GIFT：它的现有语义是商品赠品；不复用CUT：储值赠金不是本次订单减价。scene+trigger+benefit须由服务端白名单组合校验，不开放任意拼装。

排期复用现有Schedule。活动外部状态沿用DRAFT/RUNNING/PAUSED/ENDED与archivedAt；首期储值只开放ONE_OFF及符合现有额度约束的ALWAYS_ON，不新增周期玩法。仍采用现有市场时区服务，禁止写死服务器时区。

所有现有ActivityPricingService候选、商品标签、特价/赠品查询、平台报名入口，都显式筛scene=ORDER。不能只靠“暂时没匹配到新benefit”自然跳过，否则未来改分支时储值活动可能误入订单算价。

### 2.2 不可变版本

新增pmt_activity_version：activityNo、versionNo、revision、schemaVersion、规则快照、hash、发布人与发布时间。已发布版本不可修改；修改经济规则生成新版本，原子切换currentVersionNo。活动type/scene不允许原地变更。

新增pmt_recharge_tier：versionNo、tierNo、payAmountMinor、bonusAmountMinor、redemptionScopeSnapshot。唯一(versionNo,tierNo)，同版本payAmountMinor唯一，payAmount>0、bonus>=0。复杂范围可采用验证后的结构化JSON快照；查询/索引/金额不埋JSON。

发布、暂停、结束与额度上限变更写审计。调低预算不得低于已用+已占；已结束活动不复活。暂停/结束阻止新参与，不撤销已经成立的充值权益。

### 2.3 预留、参与、确认

原quotaUsed/budgetUsedMinor不足以表达“参与后尚未发放”，新增持久预留能力，不让所有调用只做先查剩余额再更新。

| 拟表 | 归属与字段 |
|---|---|
| pmt_activity_budget | 营销：activityNo,currency,quotaLimit/held/used,bonusLimit/held/used,revision |
| pmt_activity_participant | 营销：activityNo,userNo,heldCount,usedCount；用于跨版本限购 |
| pmt_activity_reservation | 营销：reservationNo,activity/version/tier,user/entity/store,currency,sourceBizType/sourceBizNo,pay/bonus快照,hash,status,deadline,sourceDecisionNo |
| pmt_activity_outbox/inbox | 若无法复用现有同数据源持久事件表，则采用营销自有表；不因命名另建一套事件框架 |

唯一键均包含tenant；参与凭证绑定稳定rechargeNo，业务唯一键(sourceBizType,sourceBizNo)；同键异参冲突。同一活动预算原子检查已用+占用不超上限，每人计数同事务更新。预算是商家承诺赠金的业务上限，不等于真实资金备付。

新增营销参与状态：RESERVED→COMMITTED或RELEASED，终态互斥。RESERVED有展示到期时间，但超时不直接清预算：必须由协调层获得pay充值已取消且不可能再实收/发放的持久事实后释放。源状态不明时保持占用并建异常待办。

已有旧活动预算字段与新预算表不能同时成为同一活动的写入口。首期仅RECHARGE使用新预算表；ORDER保持原路径、按scene显式路由，后续迁移作为独立任务。公共接口统一并不等于首次发布强制搬迁所有存量玩法。

## §3 与支付的调用协议

HTTP在shop-app，活动业务在shop-core/promotion，资金在pay-domain/storedvalue。pay不能引用promotion实体或查pmt表，promotion不能直写sv账户。上层RechargeCoordinator经两个SPI/门面协调。

1. 应用层预生成rechargeNo，将活动、档位、本人身份、商家、实际充值门店等可信参数交营销reserve。
2. 营销校验活动版本、参与范围、受众、预算、限购，同事务写预留与计数；返回不可变资格凭证。
3. 应用层将凭证交pay创建WAIT_COLLECTION充值单。创建失败可重试同一rechargeNo；确认pay未创建或已取消后再释放营销占用，不能凭HTTP超时判断失败。
4. 商家实收登记与确认发生在pay。支付只接受可信服务端传入的营销快照；核验tenant/entity/user/store/currency/sourceNo、实收与档位金额一致，不能让客户端提交赠送值或伪造凭证。
5. pay统一过账：账户、充值批次、不可变分录、充值终态与Credited事件同pay事务提交。批次保留activityNo/versionNo/reservationNo、快照hash及权益范围。
6. 营销通过CreditConfirmed事件将对应占用转为已用，幂等确认参与；回执丢失由持久事件重试。不会因为营销暂未收到结果再次发放余额。
7. 通知模块收到到账事实后生成总额到账通知。营销确认延迟不把已经成功到账显示成失败。
8. 未实收的取消，先让pay取消状态与取消决定持久化，再通知营销释放预算。确认实收与取消必须在pay串行判定：已RECEIVED不可取消，取消终态不可再确认实收。
9. 如果线下实际已付款、系统取消已成立，则转人工异常，保留实际收款凭据和待处理责任；不能在无预算时偷偷赠金或清除证据。首期不因此新增线上退款。

资格凭证建议字段：reservationNo、rechargeNo、activityNo、versionNo、tierNo、tenant/user/entity/store、currency、payAmountMinor、bonusAmountMinor、redemptionScope、issuedAt、hash。通过可信内部通道传输；若异宿主传递，使用服务鉴权和可验证完整性，单纯hash不是防伪授权。

消费核销只读pay历史权益快照。活动暂停、结束、调整受众或营销服务不可用，都不能改变已充值余额的适用范围，也不再占营销预算。消费后可发统计事实，但其失败不影响核销。

## §4 API与代码扩展

管理复用现有ActivityService及统一活动Controller，在当前路由规范下扩展scene筛选与储值类型表单；不增加/pay/.../campaign管理端点。新增的动作包括创建储值草稿、发布版本、暂停、历史版本和参与记录查询。准确URL在现有ActivityController契约核对后冻结，不能建立两个URL写同一活动。

内部协议：

- RechargeActivityPort.reserve(ReserveRechargeActivity)→RechargeEntitlement。
- RechargeActivityPort.confirm(ConfirmRechargeActivity)→ReservationResult，必须带pay权威voucherNo/rechargeNo及eventNo。
- RechargeActivityPort.release(ReleaseRechargeActivity)→ReservationResult，必须引用pay持久取消决定；未知结果拒绝释放。
- RechargeActivityPort.query(sourceBizNo)→ReservationView，供恢复和对账使用。

Reserve不得接收客户端自行计算的bonus；confirm/release不能被普通B端HTTP调用。

```text
shop-core/.../promotion/
  service/ActivityService                      统一管理入口，按scene分派
  service/RechargeActivityService              储值档位与资格校验
  service/ActivityReservationService           预算、限购预留与确认/释放
  policy/RechargeTierPolicy                    精确档位规则
  policy/ActivityScenePolicy                   场景与利益组合白名单
  entity/PmtActivityVersion                    不可变发布版本
  entity/PmtRechargeTier                       档位
  entity/PmtActivityReservation                参与凭证
  port/RechargeActivityPortImpl                SPI实现
  event/RechargeCreditedConsumer               消费pay事实
shop-base/.../spi/promotion/RechargeActivityPort
shop-app/.../paybridge/RechargeCoordinator      跨域恢复、可信快照编排
pay-domain/.../storedvalue/                     充值、账户、批次、核销及资金对账
```

不新造万能表达式引擎或允许自定义脚本。首期只实现一套有类型的储值规则，公共服务逐步抽取，避免将原ActivityService替换为巨型switch。

## §5 统计与现有功能保护

现有pmt_apply的amountMinor会被MarketingSummaryService计入订单优惠额。储值赠金不得直接追加到该旧账本或填成订单discount。首期储值参与统计由reservation及pay确认事实构成，明确区分参与人次、充值实收、赠金发放及参与转化；实际核销额由pay提供，只作可重建统计投影。

若以后统一活动发生账，须先增加effectKind（ORDER_DISCOUNT/ASSET_GRANT等）、业务来源与计量单位并改造所有统计/结算消费者，不能本期只加字段就混写。活动首页可汇总活动数量，但订单GMV、订单减价、储值实收、赠金发放分别显示，不相加。

## §6 实施切片及验收

1. 对齐统一活动现行Controller/DTO与promotion真源，确认scene、版本及预算兼容策略；补三端模板与权限设计。
2. 给存量ORDER活动补scene并确保所有订单候选/标签/算价显式隔离，回归现有满减、券、拼团、集单。
3. 新增储值版本、档位、预算预留及参与凭证，验证并发限购/最后预算只有一笔成功。
4. 编排充值与pay发放，验证“创建失败、响应超时、已收款未发放、发放成功营销确认丢失、取消与实收竞争”全链路恢复。
5. 统计与通知接入，确认储值不进入订单折扣/结算，不暴露内部池拆分。
6. 同步旧储值schema：删除未实施的sv_campaign/version/budget/participation设计，改引用pmt活动版本与凭证。尚未应用的设计可以替换；若实施时已有真实迁移或数据，则另做迁移方案，不能直接删表。

关键验收：新活动不进订单候选；版本更改不改历史权益；发布新版本不重置个人限购/总预算；暂停不阻塞旧凭证收尾；超时不释放实收交易的预算；同一充值只发放一次；跨主体/币种拒绝；营销不可用仍可消费已有储值；活动赠金不污染减价统计。

本轮仅设计与源码对照，未执行建表或业务测试。方案通过后再修订储值系统详细设计、数据库和T00–T11实施计划，不能直接按原pay内活动方案开始开发。
