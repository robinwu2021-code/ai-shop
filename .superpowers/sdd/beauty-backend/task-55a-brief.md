# Task55A：身份引用、认领回执、到店码与V5基础
此任务是BM04 Task55第一个实现检查点。需求真源为主TDD §2 Task55，完整摘录task-55-brief.md及已冻结task-55-design.md；先读本文件，再按需读设计§2–5/8–11。只实现本步，不顺带实现55B–D。

## 范围及准确决策
- 真实GuestRegistrationService#registerGuest/claimGuest、DTO、共用member两个Port消费；031直接owner调用，完整namespace派生key，不建本域登记operation，不存contact/displayName的无密钥摘要。owner明确能力不可用/权限拒绝原样失败，只有通信结果未知返回PENDING/operationNo=null/REQUIRES_ORIGINAL_ACTOR。
- 032先持久已核验guestNo/USER规范目标和稳定原号，再外调，成功回执+operation+ActionLog+安全Outbox同事务。原actor同键重放；known guest未知当前有权员工get核已固定CLAIMED才能补receipt；REGISTERED需新的当前证明，不冒充原人。receipt不可改绑。
- CustomerOwnership与Appointment preview/create guestNo可选字段/旧构造器兼容：真owner核guest，CLAIMED映射USER，REGISTERED真guestRef；旧BTG匿名不认领。C get/list/count/根归属在SQL及同scope receipt判断，不先分页再过滤；软删/跨父链/他人隔离。
- ReceptionService先只增加真实issueArrivalChallenge方法及必要实现。32byte SecureRandom base64url无padding43字符；90秒固定TTL；当前当店BOOKING_MANAGE、完整actor namespace原键，同键不续期；码只该表/即时VO，无日志/operation结果/outbox。
- V5四表/entity/BeautyMappers/customSQL/严格manifest/ready版本5和必要装配，本步一次冻结真实V5；V1–V4绝不改。详情见设计§4，不随意删减后续已设计字段。脚本生成真源/字典同步，旧目标41表/160编号不改成已实现。
- 安全BeautyReceptionStateChanged和真实共享Outbox路由在本步随receipt使用即落地，不留到55C。事件只businessNo/entityNo/storeNo/revision/state；无联系资料、reason、proof、frozenPlan。ACCOUNT不是技师。
- 本步不要新增052默认方法/OperationVO字段或genericOperation分派（所属55C/D）；不要实现签到、重排、工单/任务、直客、recovery scanner/Job、公开Controller和远程接待wire。未实现方法不生成空壳。
- ReceptionPreparation/Access/Identity/CustomerOwnership按本步需要做真实最小实现，future功能不预填空方法。生产不依赖shop-core，真实member消费链测试在shop-app BeautyReceptionIdentityIT；beauty-core原生GuestClaimReceiptIT用明确controlled owner边界，不冒称owner网络/容量已验收。

## 验收
1. GuestClaimReceiptIT原生MySQL：认领先验目标/原号、owner提交后本域回滚重放补receipt、异USER/异scope拒绝、C分页total/items本人并集、软删/父链/匿名拒绝。
2. BeautyReceptionIdentityIT真实member/mc1/cr1/guest存储的新消费链：当前身份/权限、同店交接、原key改contact由owner HMAC冲突、未知register无本域联系方式hash/原人恢复、真实claim及缺capability明确拒绝。
3. 到店码随机形状/TTL/同键不续期/跨店/撤权；本步不声称C签到已实现。
4. 正式V1→V5升级/历史checksum/27私表严格列索引CHECK漂移/当前fixture。V5首次执行后记录SHA冻结；后改仅新版本。
5. 至少一次有效消融，选择receipt归属或关键同scope/不可改绑防线，实际行为反例变RED并还原GREEN；编译/环境错误不算RED。
6. 美业常规reactor、BM03受影响预约业务回归、目标身份bridge、SQL/架构/i18n/五生成器；不重跑未修改Task54整套或无关全backend。全局已知三守卫如实披露，不改ratchet。实际命令/执行数/失败原因保留报告。

## 环境及交付
工作树/branch以dispatch为准；JDK21 /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home；离线m2 /tmp/codex-beauty-bm01-m2。共享构件改变先install后消费。
只一自建MySQL8.4 native或9.7.2 deploy同时运行，--pull never、tmpfs、随机localhost端口、TCP readiness、finally清理自己资源；六个既存food容器不能动。无共享/生产操作。
先TDD RED→GREEN。完整报告task-55a-report.md：确切AC→测试、commands/log outputs、RED/GREEN/消融、schemaSHA、owned paths/commit和fixture边界。只提交自己产品/生成物/报告；排除controller主TDD/progress/55brief/本brief/总体design。不要spawn subagents。完成本步停给控制器独立审查，不能自行越到55B。
