# 用户积分调账 G-ADJUST

用户调账由平台运营提交、另一名财务审批。调增：ISSUE.ISSUABLE → USER.AVAILABLE，U → C；审批时按兑换率校验整分及真实可用预算。调减仅 USER.AVAILABLE → ISSUE.ISSUABLE，C → U释放回平台可发预算，资金总额不变且不发起外部转账，不触碰 PENDING/HELD。余额不足整笔拒绝，不部分扣减。

独立 pts_user_adjustment 表保留不可变申请 JSON、提交人、原因、审批人、状态及版本。提交与审批复用 PointsOperatorJournal，业务请求号和操作者绑定幂等；审批仅带申请号/类型/expectedVersion/批准与否，不接受替换用户或金额。锁类型资金池再锁申请，检查原提交人及版本，CAS 后统一 posting；凭证和审批同 payTxManager 回滚。批准失败保持 SUBMITTED，可再次检查但不得伪造成功。

API GET /ops/points/adjustments 使用 finance:settle:read；POST 同路径使用 finance:settle:execute；POST /ops/points/adjustment-review 使用 finance:payout:execute。actor 仅来自 SecurityUtils，平台数据权限检查。分页限制1–100、不暴露内部凭证正文。清单展示申请金额方向、用户、原因、提交/审批人、版本，审批操作使用当前行版本。

失败验证：自批拒绝、旧版本拒绝、审批不可篡改原载荷、调增 U/C 守恒、调减仅 AVAILABLE、C→U预算释放、余额/预算不足不落批准、重复请求不双记、异payload同请求号冲突。先由主执行者统一运行 RED，避免并行 Maven。


## 财务口径确认

用户已明确批准调减 USER.AVAILABLE→ISSUE.ISSUABLE、C→U释放预算，不外部转账。原拟调减到RECI且资金不动的方案因违反现有守恒规则已撤销，未放松Voucher守卫。调增调减均只通过统一posting，审批失败整笔回滚。
