# 代填独立新建与权限审核

状态：已实现并上线，独立建店已验收 · 2026-11-12

## §0 需求与验收合同

依据：[入驻进件建店需求](../requirements/入驻进件建店-需求与四层对齐.md)，用户 2026-11-12 确认独立新建、有权限可以自审。

| AC | 要求 | 落点 |
|---|---|---|
| AC1 | 店主已有待补证主体，运营代填仍建立独立申请，不改旧主体 | createApplyOnBehalf |
| AC2 | 有审核权限可同人审核，审计留痕；无权限拒绝 | 原 Controller 权限 + MERCHANT_AUDIT |
| AC3 | 一人一份进行中、协议不代勾、商户补证兼容 | 原唯一约束、createApply/createMerchantApply |
| AC4 | 旧误关联申请正常驳回后重提，不修改旧主体 | 原 auditApply + 新代填 |

## §1 合同与边界

复用 POST /ops/merchants/apply-on-behalf 和 /ops/merchant/apply/{applyNo}/audit。无新增端点、权限码、JSON字段或迁移。代填申请 entity_no 初始为空，审核激活后记录新主体号；原 B/C 补证语义不变。

保留原状态机、active_owner 唯一约束及审核/激活同事务。审核权限由既有 @perm.can 现算；允许同人审核，日志明确 submittedBy、auditor、sameActor。不授予当前账号任何新权限。协议同意留空，不改变支付开关，不向渠道发送 MOCK 资料。

## §2 实现计划

1. 代填不再选择旧主体，复用公共校验、落库、额度与激活流程。
2. 移除无条件同人禁止，保留原运营权限，记录审核人与代填人。
3. 更新运营提示与接口注释，明确独立新建及协议未确认。
4. 验证独立新建、同人审核、权限拒绝、驳回重提及补证兼容。
5. 线上通过正常驳回 MA2611129BSJV6PBQB 后重提，不直接改库。

幂等遵循[统一规范](reference/幂等设计规范.md)：申请由原 active_owner 唯一约束保护，审核沿原申请号状态机及同事务激活，重复通过返回原回执。驳回释放活动申请后新建，保留两次申请的完整历史。

## §5 验收与证据

| AC | 测试 | 结果 |
|---|---|---|
| AC1 | onBehalfDoesNotReusePendingEntity | 通过，旧主体名称、状态不变 |
| AC2 | submitterWithAuditPermissionCanAuditOwnApply / operatorWithoutAuditPermissionCannotReview | 通过，同人审计与无权限拒绝均覆盖 |
| AC3 | recordsSubmitterAndLeavesAgreementBlank / MerchantApplyEditFlowTest | 通过 |
| AC4 | rejectedLegacyAssociationCanBeRefiledIndependently | 通过 |

后端执行 OnBehalfMerchantFlowTest（14）、MerchantApplyEditFlowTest（5）、OpsMerchantRegionalContactTest（5），24 项全部通过，BUILD SUCCESS。前端类型检查通过，page-copy 与 merchant-status-literals 两组 71 项通过。独立只读 review 未发现阻断问题。证据：本机 `/private/tmp/shop-independent-review-tests.log`、`shop-independent-review-ts.log`、`shop-independent-review-webtests.log`。

候选 JAR 与现网制品比对：574 个顶层迁移文件内容一致，依赖库集合无增删。本轮无需数据库迁移。

线上操作及支付开通按真实权限和实际返回登记，离线测试不代替线上验收。

## §6 实现与线上验收

本轮落点：OpsService 接口说明、OpsServiceImpl 代填选择器和审核审计、OnBehalfMerchantFlowTest、运营申请提示，以及本 TDD、原需求和旧设计说明。无新增端点或迁移。功能提交 `80705a078`；合并现网繁体及登录更新 `f351c7342` 后为 `be71b4e98`，避免覆盖其他会话已发布功能。原方案不增加任何主体修复接口，改用已有驳回和重提操作留完整历史。

采纳独立 review 建议，正向自审改为只有 `merchant:apply:audit` 的普通运营身份，14 项代填测试再通过。合并现网前端后 9 组 108 项通过，类型检查及生产构建通过。相关接口检查仍为已知 8 项欠账，没有新增。

上线 `independent-onboarding-20261112-80705a078-r2`，JAR `589bb379e995f3b99ab01f1c33fce28d1ce86a69fb068b968d0429354ce3216e`；全部 577 份 SQL 资源与现网一致，实际 Flyway 校验 V662 无需迁移。首次启动窗口 120 秒短于实测 133 秒，自动回滚后原版健康 200；第二次窗口 300 秒、逐个重启成功，OPS/API 均 200。配置与 NearPay JAR 不变，繁体和共享登录保留。

正常 UI 已驳回旧申请 `MA2611129BSJV6PBQB`；新建 `MA261112T1YGXFWAZX`（创建后 entity_no 为空）并通过，独立主体 `M261112G44KM1QND5`，默认店 `ST261112G3TMS4DWM7`，TW/TWD、FOOD/FOOD_BREAKFAST、FREE、CITY。原 QA166 主体及两店保持。真实代填/审核者均 ST-NEARA，日志 sameActor=true，agreed_at 仍为空。支付登记尚未完成，未创建渠道进件、未发起支付。
