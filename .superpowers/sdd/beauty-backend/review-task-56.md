# Task56 独立复审：spec 与 code quality

- **SPEC：PASS**。在 Task56 的功能、身份、持久事实及交付边界内，未发现缺失或误解 brief 的产品实现。
- **QUALITY：NEEDS_FIXES**。有 **1 个 Important**：新增 native 重启测试没有接入现有测试隔离机制，默认 Maven 测试入口会产生确定的新失败。
- Findings：Critical **0** / Important **1** / Minor **0**。
- 本结论不构成 BM04 整体验收、Task57–60 验收或部署许可。建议修复下述问题后再结束 Task56。

## 审查对象与方法

只审查 `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex` 的 Task56。输入为 BASE `f33bf12cff693472330aa659b5d1615aee3e9717` 到 staged TREE `deb79c20a5adc3a9da33898e454c6513a8d6a568` 的冻结包 `/tmp/task56-review-f33-package.diff`（87 个文件，743572 bytes，提供的 SHA256 `6d39e8511c0d4d069e26c32553f246fdc2a56fe2f64af0cb602cf97ab2232065`）。未运行 Git，未变更工作树、索引、HEAD、分支或产品文件，未运行容器、部署或远端写操作。

先读 `task-56-brief.md`，按 task-reviewer-prompt 分别判断 spec 与 quality，再读实现论证和报告。报告的历史章节与最终 §§18–22 已区分；技术设计没有被当作高于 brief 的要求。对照了相关 Java 规范、绑定 Checkout 合同及 ADR046。对 diff 作连续分段阅读；输出截断处补读。没有以实现者报告代替代码核对。

父审查已核对冻结 index、87 文件清单、逐文件字节与 V539 固定字节；这里使用该冻结输入，不重复这项 Git/字节审计。以下静态确定的新失败不依赖其 baseline 归类。

## Important finding

### I1 — 默认 Maven 测试入口必然执行失败的 native 专用测试

**位置：** [`CheckoutRestartNativeTest.java:19`](/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex/backend/shop-app/src/test/java/ai/neargo/shop/scenario/CheckoutRestartNativeTest.java:19)，同时涉及该类第 16–18 行的测试声明。

新增 `CheckoutRestartNativeTest` 是普通 `*Test` 类，类和方法均没有 `@Tag`、条件执行注解或其它 native 隔离声明。它的第一个断言是：

```java
assertThat(System.getProperty("checkout.test.native")).isEqualTo("true");
```

当前 [`backend/shop-app/pom.xml:216`](/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex/backend/shop-app/pom.xml:216) 的 Surefire 配置使用 `surefire.groups` / `surefire.excludedGroups`；默认值在第 235–236 行，仅排除 `e2e`，没有 native 类排除，也没有注入 `checkout.test.native=true`。父 `backend/pom.xml` 没有另一套覆盖这些事实的 Surefire/native 配置。因此正常、不带 Task56 专用 native 参数的 Maven test 入口会发现该测试，并在连接数据库之前因 `null != "true"` 确定失败。

这是新文件引入的测试入口回归，不能归入已报告的旧静态检查、架构或 generated 文档欠账。专用 native runner 的 GREEN 只证明带专用开关的运行有效，不能消除默认入口的新失败。该类只有一个测试方法也足以独立证明问题，不依赖其它新增用例在 H2 上的兼容性推断。

**影响：** 默认测试入口新增无条件红灯；维护者运行常规 Maven 测试即失败，且失败原因是测试选择配置，而非被测业务缺陷。会损坏正常验证入口，故定为 Important。

**修复建议：** 将真正要求 native 环境的测试显式纳入 native profile/条件执行机制，并与专用 runner 配套。普通入口应明确不选择该 native 专用类；native 入口应保证该类被实际选择和执行，并继续验证 native 数据库与有效执行数。不要只删除 native 断言、静默吞掉数据库错误，或将条件跳过计为 native 验收通过。可一并核对其它 native 专用类的隔离，但本 finding 的确定范围是上述重启类。

**复验：** 现有 native 重启测试继续真实执行通过；普通 Surefire 入口的测试选择不再触发此无条件失败。无需为本问题重复整个并发/消融/native 全套。

## Spec 核对

| 要求 | 代码与证据判断 |
|---|---|
| 六方法共享合同与职责归属 | `shop-base` 的 `CheckoutPort` 提供 prepare/readQuote/confirm/place/findOperation/source；内部 HTTP 为四个 B 方法，C 端为三条 HOST 路由。没有把 Task57 支付方法预先加入接口，也没有将共享所有者能力放入 beauty-core。新增 owner SPI 有明确默认 fail-closed 行为及本地实现。 |
| MAIN/CNY、单实体单门店、COUNT 数量与严格输入 | DTO 与服务验证覆盖 1–20 行、唯一 SKU、1–999 整数量、严格整数解码、未知输入字段拒绝，以及 SERVICE/STORE_VERIFY 约束。OrdItem 未被加入 uom 同义字段；业务单位保存在稳定行映射。 |
| C 端可信当前身份 | `CheckoutAccessPolicy.requireCurrentBuyer()` 核对 SecurityUtils 身份、可信 details、CONSUMER TokenStores 的实际当前会话和活动账户，并检查 tenant/realm/kind/user 匹配。B/代理/伪造或撤销会话不能成为顾客授权。请求体不提供可替代当前身份的 buyer；持久记录不保存原始 token。 |
| B 端权限与数据范围 | prepare 使用新增 `trade:checkout:prepare`，读操作使用既有订单查看权限与真实经营主体/门店范围。customerRef 只解析顾客标识，不替代 C 端确认。没有扩充默认角色授权或引入同义鉴权层。 |
| 唯一普通 writer 与最终封口 | `OrderServiceImpl` 仍是实际普通订单 writer。共享定价计算复用原普通能力；真实行的 paid share 来自同一次 LineShares 分摊。`OrderServiceImpl:2169` 的 `checkoutGuard.requireMatches` 紧邻并先于第 2171 行 `creationRecovery.register`，再进入库存等创建流程。封口检查不仅比较总价，还覆盖行价、数量、承担方、选择和履约/授权元数据。 |
| 显式顾客定价与支持范围 | goods/campaign/coupon 以显式顾客读取，而非 B 操作员冒充顾客；普通金额活动/优惠券复用既有算法。积分、储值/权益、团、周期、赠品及不支持组合显式拒绝。营销/券的 current selection 和来源 fence 在 owner 本地锁定检查；没有把未知复杂权益降格为普通金额优惠。 |
| 持久幂等、原意图及事务 | permanent operation/prepare/confirm/place 约束在数据库中；第一次 place key 的持久 claim 与最终业务事务区分符合 ADR046。最终业务事务涵盖订单、确认消费、operation CREATED 与稳定行映射；行映射在真实 OrdItem 插入后绑定其 ID。CREATED 返回原单；既有 creation intent 使用原号，RELEASED 分支不重新下单。没有把临时缓存作为永久幂等依据。 |
| 过期和最终时点 | guard 在取得 catalog/coupon/activity 锁后重新读取有效版本和时钟，并在最终 seal 再核对经济快照。新授权过期不能继续创建；已有 intent 通过 findOperation 保持可观察。未把恢复权限当作买家授权或完工授权。 |
| 冻结 DDL 与所有者边界 | V539 的四张 owner 过程/映射表、永久唯一键、source revision/hash 配对与持久 fulfillment authority 对应代码使用。既有版本不被改写；字节冻结由父审查确认。未发现以 IF NOT EXISTS 掩盖历史漂移或在美业实现导入商品/订单/Pay/库存实体 Mapper 的扩域写入。 |
| managed 订单全路径保护 | `SERVICE_EXECUTION` 是持久字段；业务策略在旧入口读取并阻断 managed 完工，`StatusCasMapper:58` 的最低 SQL 条件再次拒绝 managed COMPLETED。支付链不生成普通核销码/发货证明；旧取消、超时和合法退款仍保留。未发现只保护新增 façade、遗漏最低状态写入的实现。 |
| source 当前事实与一致性 | source 在本地新 RR 事务内以 operation 行锁作为第一条 SQL，再读真实 order/sub/item/mapping/refund 事实；支付成功事实预取不在锁事务内进行。检查身份、币种、金额、行映射和 receipt 一致性，不可证实则 UNKNOWN，保留上次可信 revision/hash。稳定 hash 按事实变化推进；不是只使用变化时间或版本号替代事实。取消/关闭/退款以及处理中售后影响 availability。 |
| HTTP 与兼容性 | HOST C 路由和有版本的 internal B 路由完整，非法版本/身份/参数 fail-closed。未擅自增加 C 端 internal 授权入口。实际跨工程 beauty HTTP 装配属于 Task59，没有将设计占位包装为本次已交付。 |

`CheckoutServiceImpl` 的 prepare、confirm、place、findOperation 分别从第 160、315、449、642 行进入；`CheckoutQuoteGuard` 的 final seal、稳定行写入和 final consume 分别在第 102、123、159 行。这些是本次业务结论的关键实现位置。

## 质量与验证证据

值得保留的实现点：

- 最终封口直接位于唯一 writer 的副作用边界前，且实际保存的分摊结果与被封口结果相同，避免“先校验另一份报价、再由旧 writer 重算”的二次定价窗口。
- managed 完工同时保护业务入口与最低 CAS；取消/超时/退款的正例也纳入验证，没有把安全护栏变成所有状态转换的一刀切拒绝。
- source 对不完整或不一致证据返回 UNKNOWN，并保留最后可信配对；没有伪造付款或履约状态，也没有把正在进行的退款当作已完成结果。
- 本地 owner 的 catalog/coupon/activity fence 是具体能力，不是依赖泛化 RPC 接口在事务中“通常很快”的假设。
- 契约、真实 HTTP、身份、优惠分摊、事务回滚、原号重试、重启、竞态与消融验证覆盖了本任务最危险的边界；测试设计包含真实 writer/Mapper，而不只是为 façade 配 mock 返回值。

**证据边界：** 本审查没有重新执行测试套件、native 数据库、循环竞态或容器。使用既有运行记录及父审查已经核实的日志，辅以本次静态代码审查。各数字是不同时间点的 checkpoint，不能累加成“最终完整套件全绿”：

- 主 native checkpoint：Contract 4 + app 83。
- owner 重构后：Contract 4 + app 18；后续 fixture 2、cancel/expiry 2。
- food embedded 5 通过，standalone-only 1 是条件跳过，不是 standalone 验证通过。
- 最终字节 compile/checkpoint：Contract 4 + architecture 1。
- RED、两组消融与恢复日志已由父审查核实；这里审查了这些测试对应的产品机制，没有重新制造 RED 或重跑消融。

现有 shared static/full generated/部分回归基线不是全绿。报告对 old OpsPoints FQN、store package 等架构欠账及 generated 日期/旧内容漂移有明确记录；这些未因属于旧基线就被视为“已通过”，但在本次修改中没有得到它们被进一步恶化的具体证据，因此不另造 merge-blocking finding。H2/Flyway、重复 Mapper 名称、未配置外部通道、OTP 配置和故障注入 WARN 均应按实际作用判断，未发现足以单独形成 Task56 新产品缺陷的证据。I1 是可从新增代码与现有 POM 直接确定的新增默认入口问题，独立于这些历史噪音。

## diff 外的聚焦检查

未作广泛代码库爬取。必要的 diff 外检查及原因如下：

1. **锁内 IO/最终副作用顺序。** `OrderServiceImpl` 的 diff hunk 在旧定价/创建函数中间截断，因此补读当前文件第 1620–1710、2060–2165 行，核对运费、积分、履约准入、pay capability 与最终 seal 的实际调用顺序。这些补读用于还原函数，未把不相关旧模块扩展为审查范围。
2. **锁内返回值是否暗含 RPC。** 新 `checkoutCreatedView` 调用旧 `payView`，diff 没有展开整个旧投影链。聚焦跟踪 `OrderServiceImpl` 第 3910–4105 行的 payView/orderView/storeBriefOf，随后只核对 `shop-merchant/.../MerchantPortImpl.storeNames` 第 440 行起的实现：当前 HOST 实现直接查询本地 Store Mapper，没有在该链发现网络 IO。未把接口名称等同于远程调用。
3. **native 测试是否真的由默认测试入口排除。** 聚焦读取 `backend/pom.xml` 与 `backend/shop-app/pom.xml` 的 Surefire/profile 配置，确认 I1。只做静态检查，没有运行普通全套来重复证明首条断言的必然失败。
4. **合同权威与实现论证的关系。** 读取明确被引用的绑定 BM04 Checkout 合同和 ADR046，用于判断六方法、身份边界和持久 first-key claim；没有阅读整份主 TDD 或扩展后续任务。

为精确输出 file:line，最后从同一冻结 diff 的 hunk 行号提取了关键方法位置；没有重新生成 diff、重新查询 Git 或另读 changed-file 全文。

## CannotVerify / 后续范围

- **Task57 支付闭环：** source 校验成功 receipt 的逻辑与测试是本次范围；测试中构造/种入支付事实不等于真实 Pay 发起、回调、对账或全链路已验收。
- **Task58 美业绑定、Task59 独立工程 wire：** 共享 SPI 和 HOST 入口准备就绪，不代表美业端绑定和真实跨进程部署已完成。
- **Task60 整体集成与部署：** 没有启动部署、独立 food/beauty 运行包或真实外部通道，本报告不据 embedded 通过推断 standalone 已通过。
- **其它库存部署形态：** 现有 H2/native owner 测试与此处静态路径检查不替代所有 DUAL/INVENTORY 真实部署验证。
- **完整最终测试总表：** 当前证据是多个 checkpoint，不能据此声称所有最终字节上的全仓测试均通过。若 Task60 要求完整最终套件，应在那里按既定范围完成并单列 baseline 债务；本次只需针对 I1 完成测试入口修复验证。

## 结论

Task56 的产品实现满足当前 brief，且关键事务、身份、真实 owner 定价、持久幂等和 managed 最低 CAS 保护有实质代码与验证支撑。当前不能给 QUALITY APPROVED，原因是 I1 明确破坏默认 Maven 测试入口。修复测试选择机制并保留真实 native 重启执行证据后，可对该 focused delta 复审，无需据此重开整个 BM04 或重复所有已完成 native 套件。
