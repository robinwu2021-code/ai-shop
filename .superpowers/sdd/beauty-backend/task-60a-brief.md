# Task60A BM04代码与质量收尾（设计启动稿）

Status: READY_FOR_DESIGN_DISPATCH. Accepted Task59 product commit c9d9518798ee74f93d7a1f899d356c4760d57b43. Separate root dispatch releases design/source audit only; product implementation requires a later technical gate. Actual deployment belongs60B after allP1–P4 code under mainTDD§0.2. This brief is root-owned.

## 需求与范围

先读 task-60-preflight.md，它列出源文件、旧债证据和已核查边界。原任务为主TDD§2.22 Task60；用户将实际部署统一后置，本任务负责其代码/质量部分，不生成新的产品范围。实施前沿 ai-shop-dev-standards 复核分档，详细方案写入主TDD§2并与实际文件对账。读取已有设计/报告不等于重做已验收Task54–59。

第一轮为设计与只读源码审计：仅编写task-60a-design.md/task-60a-report.md，不改产品/测试/SQL、构建、native、stage/commit。设计必须按以下独立关口拆分到可独立测试/审查的小切片，给精确owned文件、接口、失败测试selector和AC映射；每片有可信RED→GREEN/最小消融和代码审查，不能把全部治理写成一次巨型改动。根核定后逐片实施，未核定不动代码。

1. 架构：同模块store.config.JobLockConfiguration迁job.config，更新两个import；现有锁、唯一Bean和disabled无DataSource行为不变。beauty登记为DOMAINS业务域，在embedded形态按原规则验证并修实际越界。OpsPoints PathVariable改正常import；不加infra豁免、不扩大棘轮。
2. 有限测试可靠性：旧verify-checkout-payment.mjs在任何工具/资源前严格拒绝空、未知、混合/不支持selector；新鲜非零XML及零skip。原密码argv问题须用既有env-secret模式修复，严禁先运行旧不安全路径。ServiceSubmissionIT跨动作竞争允许SUBMIT_SOURCE/ADD_SOURCE任一合法胜者，验证唯一prepare/source和胜者原始不可变receipt，不能固定调度胜者。保留原回放/冲突断言。
3. SQL和schema：V506 CHECK/共享AND解析以及严格12项无Entity表边界，基于实际字节定位原因；已执行迁移绝不改。永久同一SQL规则定向覆盖美业插件，新增坏SQL负例证明生效，不复制规则、不放宽known基线。若确需新迁移先列最新最大号/完整SQL/bytes/hash和升级方案，未获根actual-file执行gate不能执行。
4. 真实业务组合：CLAIMED→USER并在共用resource owner执行实际REPLACE；先审计既有owner夹具与权限/当前身份，给可执行native设计。现有静态兼容不是通过证据，必要隔离集成仍可执行，不作为正式部署。
5. 权限/词条/生成对账：预约view/manage三语和重复中文、B端common.delete/两个STOCK_SHARED_SOURCE占位、四旧Bregistry、旧Ops八行矩阵与README四处状态日期。逐项先验证存在及真实端点/权限含义，不能通过宽泛PUBLIC/角色授予消警。更新真源后生成，不手改生成结果。55D告警按真实故障/预期环境分别记录，不能静默抑制WARN。

## 共同边界

只在/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex、codex/beauty-foundation工作。以接受后的Task59提交为每片起点，不使用其他会话的原工作区。不派子agent；不改根brief/progress/ADR/pins/review。正常imports/4space/新改行<=120/Javadoc，Service/Impl+本域Mapper，不建Repository或第二账本。共用所有者语义和原160目标保持。

MySQL仅cached9.7.2 --pull never、正常DISK64M、colima-hxkey-tests/profile hxkey-tests、MemAvailable>=524288KiB、串行自有UUID/randomloopback/env秘密/finally只清自己；不停止他人或改VM，不连共享/远程库。JDK21和离线Maven缓存沿Task59。普通skip不计native通过，不重跑不受影响测试。actualembedded/standalone启动与整个BM04部署仍DEFERRED，BM05暂不在本片编码。

设计报告须逐债给当前源file:line、推荐最小方案/代价、是否改契约及文档三处对账、每个独立切片的文件方法和RED/GREEN命令；遇到原假设不成立给事实与裁定建议，不假造缺失实现。最终报告只返回状态/文件/hash/关注点；完整证据留报告。设计准备不宣称任何债已修复或BM04已部署。

Task59代码验收补充：产品提交c9d951879，R1两项Important已独立复审关闭，当前HOST仅两项已登记架构失败。另将review-task-59.md的M1夹具质量列入60A一个独立验证项：BeautyBm04EmbeddedHttpIT使用otherwise-valid确认请求验证错误realm的明确拒绝及无确认/订单/支付副作用；不启动后置部署。M2告警保留真实来源分类。设计先写task-60a-design.md/report.md，根技术核对后同步主TDD§2再发逐片实施基线；不要修改根主TDD来绕过设计仅两文件的边界。
