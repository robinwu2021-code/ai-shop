# 新支付商户资料修订：待发布API增量

本地实现，未部署166。App仅访问ai-shop，宿主转发至独立near-pay；`X-Merchant-No`来自真实typed membership，不从host主体/门店号推导。不需已有门店，从账户侧进入；权限仍由PAY逐请求重读。

| 方法 | 宿主/PAY路径 | 合同 |
|---|---|---|
| GET | `/biz/pay/v2/merchant-profile-revisions` | 返回revision、canSubmit、approvedRevisionNo、items；允许onboarding.query或onboarding.apply |
| POST | 同上 | 要求onboarding.apply、ACTIVE且独立建档PM；requestNo永久幂等，expectedRevision是已读全量最新修订号（初始0） |
| GET | `/biz/pay/v2/merchant-profile-revisions/by-request/{requestNo}` | typed来源身份和PM范围内恢复原提交，返回该修订当前审核状态；未知404，不把它当提交失败 |

POST字段：requestNo（字母数字/下划线/连字符，1–64）、expectedRevision（整数≥0）、legalName（必填≤128），可选registrationNo≤64、registeredAddress≤255、representativeName≤64、contactPhone≤32、industry≤64。商户端用途固定FIAT，不开放任意purpose/materials；证照和结算材料仍由渠道私有流程封存。

回显修订含revisionNo、revision、purpose、reviewStatus、profile、reviewReason、version；登记号、代表人与联系号码只回掩码。App提交前确认，未知先查原键，确认未收到才重试原键及原文；明确版本冲突需刷新后重新确认。切换身份/失权清空内存表单和回显，不把敏感输入落本地存储。

服务端商户行锁内检查expectedRevision并写修订与永久回执；同键不同内容冲突，同键原文重放在版本校验前恢复。OPS审核沿原权限/ApprovalGate；待审期间旧批准版本继续生效，批准新版本后切换既有读取。source管理PM禁止本端直写，避免抢占来源版本号。FIAT/GENERAL旧批准兼容不等于新增GENERAL提交API。

发布前置：独立建档V81及宿主经营主体V648相关能力先完成同一闭包核对，PAY V82再增加永久提交字段。无新166迁移/部署证据前不可开放正式App入口或在部署清单增加这三条。
