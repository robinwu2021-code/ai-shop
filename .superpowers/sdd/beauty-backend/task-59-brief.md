# Task59 BM04公开入口与双向协议（准备稿）

Status: IMPLEMENTATION_APPROVED. Root technical gate passed the complete design/report and exact shared/core scope. DESIGN_BASE 317c1f7c78259e170f9c182b7f785991aad861f5; accepted Task58 ea2ead5c63f4830de566fdb3dcdda4b0a32424a9. The actual IMPLEMENTATION_BASE is the root release commit supplied in dispatch after this documentation commit. Follow approved task-59-design.md sections0-10 and six execution units; design-only restrictions below are historical and superseded ONLY by that root dispatch. No source/test/SQL execution before dispatch.

## 原任务

#### Task 59: BM04公开入口与双向协议

按原API方法清单落Controller、RemoteService、typed内部API和SERVER ingress。api-only公开入口，默认关闭；B-MCH/B-USR/C、当店/本人隔离、完整回显核验及finally清理。原160目标编号保留，增量合同单列；开工前逐API/Bean图冻结实际签名。

- [ ] RED/GREEN真实TCP+全局Advice/鉴权/错scope/错误200/断连及embedded/HOST/SERVER/disabled/ops装配。
- [ ] 提交并独立审查，留真实构件和配置/HTTP清单。



## 设计阶段须闭合

先读本brief，再逐项对照实际Service/API JSON与已验收Task54/55/56/57/58合同；旧候选Java清单不能当已交付签名。第一阶段仅task-59-design.md/report.md、只读源码审计，不改产品/测试/SQL、不构建/测试/stage/commit。根技术核对后发IMPLEMENTATION_BASE。你是唯一实施者，不派子agent。

1. BM04仅为真实已交付的身份/认领、到店码/签到、直客、异常协调、预约转工单/开工单/详情/本人详情、来源提交/账单/原号查询恢复/trace提供公开入口及协议。逐BAPI047/048/052/053/054/055/056/071/072/073/137/154/155核对真实签名/DTO/权限/状态/HTTP名单；160及既有原号恢复入口保持原语义并扩展真实schema2分派。原160目标编号稳定，新认领/到店challenge等必要增量合同单列。BM05任务开始/步骤/完工/关闭等未交付方法保持关闭，不造空成功端点或以路径存在冒充功能。
2. 给完整Controller/RemoteService/typed内部API/SERVER ingress/反向owner端口/配置Bean单向图及每种部署条件。beauty-web公开入口依赖API层，不引core/mapper；默认关闭、api-only、disabled/ops/no-api保持边界。已有真实Service为唯一业务实现，Controller只认证取当前scope、校验、委派、统一响应。
3. 当前B-MCH/B-USR、真实当店/本人C隔离不靠body自报。冻结req/path/store的冲突规则、幂等header、未知字段/必填校验、三语安全错误与Advice；原cr1/mc1/claim proof/idempotency raw key/付款参数/私有证据不能进日志、长期意图或响应。到店/认领不授付款权。对052/054别名若共用同一OPEN业务入口，必须明确canonical namespace与同键重放规则，不让换URL或path/body冲突重复建单。
4. 原任务52的真实机器身份/令牌/nonce与DelegatedIdentityContext协议先读再复用：typed request+response完整回显，endpoint/action/entity/store/realm/kind/actor/op/key/revision/原payload均不能串线；所有成功/失败/解析/超时/断连后finally清理上下文。机器身份不能冒充当前B/C授权，不持久化或重放C token。真实HOST token-store/session撤权和账号状态规则必须核实；禁止测试空provider代生产实现。
5. standalone HOST无beauty-core，SERVER私有beauty schema只维护行业事实；共用会员/商品/普通订单/资金owner仍HOST。Task58实际只调用CheckoutPort的prepare/readQuote/findOperation/source；明确B-only反向协议和其余C操作权限，不通过机器RPC代C确认/建单/付款。消费者沿现有真实HOST C路由完成购买。cash仍现有HOST当前B实收端点，server不另建资金账。
6. 新Remote GuestRegistration/Reception/WorkOrder/Submission/Bill等以实际缺口为准，不复制新持久状态机。原Remote OperationQuery/Recovery保留预约/接待兼容。已有Service方法的scope/父链/已认领不可变凭据/量/epoch/lease规则不得因remote transport削弱；对GET观察投影、历史BOUND与当前UNKNOWN/BLOCKED字段、nullable金额保真，不把HTTP200错误body/空body/格式错/缺回显当成功或默认0。
7. 保留锁外IO和原号恢复：RPC响应丢失不换采购/订单/付款号；已接受业务结果与HTTP可用性分开。无consumer token/原短proof时别冒充原actor重建。当前真实owner终态、确定拒绝与UNKNOWN的合同以Task58最终审查修正为准。
8. 单独列public route与internal ingress allowlist、权限码registry/角色/标签、i18n、配置及原有API160/方法/端点清单生成真源更新。复用现有权限，不为方便给角色新增资金权限。公共C入口不得暴露任意用户/门店工单，公开B详情/trace不得以外部order/payment ID绕过本域父链。
9. 设计真实TCP/真实全局Advice/真实当前身份测试：正向B-MCH/B-USR/C、跨店/错realm/撤权/已删除父链/未知字段/path-body错配、同异key、remote错回显/错误200/空body/断连/受控丢响应/原号重放/上下文清理；embedded、无core HOST+私有SERVER、默认retail、disabled/ops装配。单元transport fixtures只证明合同，至少真实service+owner native桥接，不把test Bean/readiness当部署。Task60整体真实JAR部署仍下一关。
10. 本步优先无需新DDL：beauty当前V8 ready8/28，1–8及平台539/540已执行冻结，绝不改旧字节。若确需迁移，先报实际最大号/完整literal/bytes/hash/存量升级，根实际文件首次执行前pin。不要为网络重试新造第二业务/资金意图表。
11. 遵CLAUDE/零售Java规范：Service/Impl+本域Mapper、正常imports/4space/新改行<=120/Javadoc、安全错误/三语、无Repository/循环Service。只owned，生成前后核状态，更新真源并生成，不改checker/棘轮遮盖债。
12. native仅本机cached mysql:9.7.2/DISK64M、colima-hxkey-tests/profile hxkey-tests、MemAvailable>=524288KiB、串行自有UUID/random loopback/env秘密/--pull never/finally只清自己；不停止他人容器/改VM/共享库或远程环境。JDK21=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home，Maven -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2。native fresh XML非零0skip，普通隔离跳过不算业务验收。
13. Task60仍有旧OpsPoints FQN、store/job与beauty域登记、V506/schema parser/标签/生成漂移、四旧Bregistry、SQL插件定向规则+坏SQL负例、Task57 M1 unknown/empty selector、Task58 M1跨动作幂等竞争测试误定胜者、55D告警及完整BM04构件部署/whole-branch审查等债。Task59按实际影响做有限回归，不宣称旧债清零，也不拿旧债掩新失败。
14. 设计报告给逐AC/完整签名/DTO/方法/Bean条件/权限及真实文件:行审计、错误传播/关闭能力/选型成本、预期有效RED selector与provider边界、精确owned。实施阶段真实RED→GREEN+最小消融/逐字恢复、当前证据归档和资源清理、完整hash/diff。先不stage/commit；根独立spec+quality审查后精确提交，不push/merge/deploy。根主TDD/progress/brief/ADR/pins/reviews不改。

Accepted baseline is fixed above. This brief releases design only; implementation requires a separate root IMPLEMENTATION_BASE after concrete signature/Bean/transport verification. Do not modify root-owned brief/main TDD/progress/ADR/reviews/pins.

Root implementation release approves the exact optional safe-error header/client metadata surface and minimal canonical OPEN correction. No new platform session/nonce, C/cash machine authority, database migration, source ledger or Task60 debt repair. Approved detailed design is binding over abbreviated historical preparation wording. New DDL still requires a separate actual-file execution gate.
