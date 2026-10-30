# Task58 来源提交、绑定与逐来源账单

状态：Task57产品741b38178f01a6f4159fc618d57a45d75a3601e0已本地实现并独立审查通过。这里只启动Task58设计，不代表已编码、已开放HTTP或完成BM04部署。唯一工作树 /Users/robin/.codex/worktrees/beauty-data/ai-shop-codex，分支codex/beauty-foundation。DESIGN_BASE由根在派发时给定。

## 先读的需求与真实基线

以下为主计划原Task58全文（不替代后续硬边界）：

#### Task 58: 来源提交、绑定与逐来源账单

消费55真实范围及56/57完整CheckoutPort与OfflineReceiptPort，实现056/071/072/073及P1必要来源确认/trace。外调前持久原意图/原号/摘要，来源buyer/store/行/量/版本核实后按guard→工单→任务的既有锁序CAS绑定；数量守恒，旧范围晚回执不能覆盖。UNKNOWN不新号建单/支付，账单金额读取owner且按币种，缺失明确不完整。开工前补完整签名与绑定/撤销边界。

- [ ] 细化方案后TDD实现，native并发数量/重复绑定/原号恢复/owner异常/账单不完整验证。
- [ ] 提交并独立审查，不提前开放BM05。



绑定依据：主TDD§2 Task58及BM04来源/范围/恢复的既有规则；docs/technical/design/美业系统-API清单.md BAPI-056/071/072/073；美业系统-领域对象与聚合边界.md、Java类与方法清单.md、数据库结构设计/字段字典。真实API/实现优先于旧草稿未实施签名。先核已交付task-55-design.md及55C/55D报告的范围/原身份/初始意图，再核Task56六方法和Task57已接受八方法与真实资金合同。报告中历史未实施条目按其时间语义理解，不重做55–57。

你是唯一实施者，不派子agent。沿CLAUDE与Java/零售规范，Service/Impl+本域Mapper、4空格/新改行<=120/imports/用途Javadoc、统一错误/三语，不造Repository/第二订单或资金账。第一阶段仅写task-58-design.md与task-58-report.md，实际source只读审计；不得先产品/测试/SQL/构建/native/stage/commit。根技术核对后给IMPLEMENTATION_BASE，再真实RED→编码。已有用户授权内不泛化重问批准。

## 必须先闭合的精确方案

1. 逐项核API056提交、071账单、072查意图、073原号恢复以及必要P1来源确认/trace，与现有WorkOrderService、ServiceOperationQuery/Recovery及DTO/本域表逐一对账。给真实类型/完整方法签名/DTO校验、Service/Impl/Mapper/helper文件、启用条件和Bean单向图，说明兼容旧方法/旧接待operation分派。本步本地业务Service/持久内核；公开Controller/Remote/typed SERVER协议按Task59落实，不为装配写空成功。
2. Task55C建单同事务创建的bty_service_submission OPEN/RECEIVED只是草稿，含冻结规范身份/范围摘要/taskRefs，不能自动外部建单。由当前合法当店操作员明确提交后续行，保留规范actor realm/kind/actorNo+endpoint+原key命名空间、原请求摘要/原submission/原checkout operation。先持久意图/原号再外调，恢复只读或续原操作，不用新号重复建单、支付、占量。
3. 真实购买必须是可信USER。原context的USER/GUEST与guest claim receipt按现有owner能力解析，GUEST、BTG、staff/主体号、自报buyer、customerConsent布尔不能冒充消费者。B可按真实权限准备报价、查询来源/账单，不代C confirm/place/initiate、不持久化或重放C token。消费者的本人确认/建单/支付沿真实共用CheckoutPort及已开放HOST C入口完成；旧草稿customerConsent只能按真实owner确认事实闭合。guest未认领或C未确认时明确待处理/不可服务，不能伪成功。
4. P1仅单次先付SERVICE/STORE_VERIFY/COUNT的原普通订单来源。金额/币种/价格/收据/支付结果只读共用owner；不新增美业售价、订单、余额或资金账。已有储值/次数/疗程等P2能力不在本步假接入：真实owner能力未交付时明确关闭，不用mock provider作生产实现。
5. 精确冻结提交、已有来源绑定/新项目或加项的边界：原task范围与本次选择、原/新scopeRevision、definitionVersion、sourceLine稳定ID、sku/单位/数量、buyer/entity/store、source owner revision/fingerprint必须核实。未选项目不能隐式加购；加项不得覆盖旧行、原服务事实或已有占用，不静默用最新规则替换冻结快照。旧范围晚到回执只能留原意图结果/异常，不覆盖新scope。
6. 来源外调/quote/source/支付/身份/resource端口必须在本域事务/DB锁外。绑定短事务按已有guard→workOrder→task确定序锁，锁内只核不可变事实与当前本域根/父链/revision并CAS。说明同sourceLine跨工单/并发绑定的数量守恒，qty=bound+done/voided/remaining等真实不变量、重复绑定与撤销/退款竞争、释放和历史保留；审计既有shared source/执行许可owner，必要缺合同先提真实最小owner方案，不拿只读availableQty当原子占量，也不造假future接口。
7. 原意图、外调已接受但响应丢失、已建单未绑定、绑定提交后响应丢失、当前范围已变、退款/取消先后、source未知/不完整均有精确phase/重试条件/租约/版本与安全结果。确定拒绝和UNKNOWN分清，不能广catch异常当成功/新建。只允许原号查询或有权当前人显式恢复；换员工恢复核当前真实身份/权限，不冒充原actor/C。
8. 来源事实沿真实CheckoutPort.source：canonical paymentOperation/outTradeNo/receiptNo、RR观察pair、金额未知=null、lastVerified pair与当前可服务分开。Task57已证明确证NOT_SENT可显式新key，其他UNKNOWN不关单/换号；别名、反向实际成功/晚款、旧关闭订单可能保留真实RC且BLOCKED。美业不得把ordPAID、截图、历史pair或B声明当当前执行许可。
9. 逐来源账单只聚合经授权、真实匹配的owner事实，分来源列应收/实收/已退/未结与币种/完整度/阻断原因，不能跨币种混算、UNKNOWN补0、另造混合收款单、用任务qty推钱或读账单触发收款。明确部分owner失败/多页水位/重复receipt/canonical别名去重、算术溢出/缺金额。账单读和恢复均核当前当店/父链/身份，敏感支付参数/原requestKey/私有凭证键不回显。
10. 绑定成功不等于开工、核销或完成，不改SERVICE_EXECUTION最低CAS，不生成旧verifyCode/假step/participation/打印通知成功。BM05保持关闭。后续实际执行/逆向/资产经营照原计划，不在58扩P2/P3/P4。

## 数据与验证放行

11. 只读核真实schema/mapper/query/数据域/manifest/ready。当前beauty ready7/27私表，V1–V7冻结；平台V539和V540已执行冻结，V5407477/SHAca76b6512cf79c6f25fa71a4441f533e03cc609c6320611a0dcdc9634a10f745。需要新表/约束时先核对应真实最大号、完整候选DDL/存量升级/null组合/字节hash/生成真源，根首次执行前独立pin；不改旧DDL、不假IFNOTEXISTS/repair/clean漂移。初步设计应说明为何现表不足或无需新表。
12. 设计逐AC的真实selector/夹具边界/预期RED：当前MCH/USR/USER及跨店/软删父链、认领前后来源身份、同/异键、同/异工单数量竞争、source owner changed/退款/旧scope晚回执、RPC丢响应/原号/重启恢复、UNKNOWN资金/账单不完整/币种、锁外IO/无相互Service循环。核实际host bridge真实trade/pay测试如何落地；beauty-core外部owner夹具只能证明contract，不冒称真实订单/资金已验。至少一个有效最小防线消融→逐字恢复GREEN；0执行/skip/编译/setup不能称业务RED。
13. native仍本机cached mysql:9.7.2、DISK64M、colima-hxkey-tests/profile hxkey-tests、MemAvailable>=524288KiB、串行自有UUID/随机loopback库/--pull never/秘密env/finally只清自己。不停他人容器/改VM/共享autotest或远程环境。JDK21=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home；Maven -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2。复用真实runner及native具体类隔离，强制fresh非零0skip报告。
14. 相关beauty常规/现source/预约接待/零售餐饮owner/架构/三语/SQL/生成验证按变化范围，非无限全量重复。Task60已记旧OpsPoints FQN、store/job/beauty域登记、V506/实体CHECK解析/12表、booking标签/common.delete/库存placeholder、生成漂移、四旧B端路径登记、55D告警、claim→USER/真实REPLACE与插件SQL定向覆盖。新增Task57 M1 runner未知/空selector可假绿也必须Task60最终门修复；本步合法selectorfresh执行证据有效，不能把旧债宣布清零或用它掩新失败。
15. 报告按设计阶段/实施阶段分别持续记录：真实审计file:line、裁决/风险成本、逐AC方法与真实provider、精确commands/logs/counts、SQL冻结、RED/firstGREEN/消融/资源清理、未验边界和精确owned。设计交接只design/report两文件，根主TDD/progress/brief/ADR/pins/reviews不改不stage。实施完成后精确hash/只owned diff冻结，未commit交根独立spec+quality审查；通过后根精确提交，不push/merge/deploy。整个BM04部署与whole-branch审查仍Task60。

## 根技术放行（2026-10-24）

已完整核对task-58-design.md及report；6.2必须包含旧epoch更高可信版本提升最高水位，以及可信UNKNOWN有/无pair使全部在途ticket失效的原子分支，不可简化为discard。三条新增AC及消融selector已核。候选V8 literal根独立提取4582字节/SHA c5b54d574624a5265366cf4bf7897ec949d107bd302f171539c461a89a9d8343；旧beauty1–7/平台539/540未变、max7/540。允许按设计写产品/测试/生成器及构建，IMPLEMENTATION_BASE为本次根文档提交HEAD。首次执行任何V8前必须先报告实际生成文件供根独立pin；未取得实际文件pin，不得运行会触发V8的测试/应用。无平台新DDL。

先真实行为RED再最小实现/GREEN；源码编译缺失或夹具失败不算RED。当前已有全部用户授权，不需再次确认。按精确owned清单变更，新增必要路径先记录理由；不改根主TDD/progress/brief/ADR/pins或其他会话工作区，不stage/commit，不派子agent。提交独立审查前冻结完整owned hash/差异与真实证据。Task58非部署准出，BM05关闭。
