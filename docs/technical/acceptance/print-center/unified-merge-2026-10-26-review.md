# 打印统一分支合并兼容性只读审查

日期：2026-10-26。工作区：`/private/tmp/ai-shop-print-compact-integration`。统一基线：`3d4f48b6b`；待合入：`cc049c132`；共同基点：`ba44c7f0c7339d9ca8f3e0ff620b2536c4697bdb`。

结论：在本次合并兼容 delta 范围内，未发现 Critical / Important 问题。此结论是静态审查，不代表本次整合编译、测试或生产迁移已通过。

## 核对结果

- `cc049c132..HEAD` 为 59 个提交。将共同基点到统一 HEAD 的文件变化，与 `git diff HEAD` 的合并变化交叉比对，非文档交集只有 `backend/shop-app/src/test/resources/schema-test.sql`；没有业务 Java、B 端或运营端源码交集。新增事实/来源类依赖的退款、积分退款、储值事件和打印接口，在这 59 提交中没有发生被 feature 覆盖的变化。重点打印、退款、储值及 bridge 源码对比 `cc049c132` 无额外差异。
- `schema-test.sql` 相对统一 HEAD 只新增 `biz_print_fact` 表和 `OPS_PRINTING` 导航种子；统一已有表、fixture 修复、品牌图片域名尾部迁移保留。没有将 feature 旧 schema 整体覆盖统一 schema。
- CORE 迁移文件版本扫描没有重号。末尾顺序为 V585 owner identity width、V586 media host、V587 industry order intent endpoint width、V588 print ops navigation、V589 business print facts。统一 V587 保留；旧迁移相对 HEAD 没有修改或删除，故没有本次整合引入的旧迁移校验内容变化。新增 V589 的 entity/store/source/order 等身份列保留 64 字符宽度。
- 原 `PrintServiceImpl`、dispatch/receipt/state policy、job command、队列门控和 `PrintReconcileJob` 均没有本次合并差异。新增业务事实只提供不可变快照与执行 guard；`PrintFactReadyConsumer` 仍调用同一个 `PrintFacade.onFact`，自动补偿仍调用原 reconcile，未引入另一套 job/attempt/queue 所有者。
- UNKNOWN 的原执行策略保留：legacy retry 只接收 FAILED；UNKNOWN 仍要求审计重打；统一 state policy 对部分输出/不确定取消/不确定拒绝/不确定失败返回 UNKNOWN 且不请求自动 retry。新增维护扫描扩展的是支持的来源和 scene，没有修改这条执行策略。
- 退款凭证仍以真实退款 v2 的 terminal proof 或积分 owner 确认结果/原 plan 为输入；充值凭证采用独立 `StoredValueReceiptConfirmed` 事件，不变更原 v1 credit contract 的内容。新增 port 注册 REFUND、RECHARGE，SUB_ORDER 原 port 保留。
- 专属读取范围从 actor、source submit、事实预览/模拟、summary 列表过滤一路进入编译文档 scopes。REFUND 使用 SOURCE_REFUND_READ，RECHARGE 使用 SOURCE_RECHARGE_READ，原 SUB_ORDER 继续使用 SOURCE_ORDER_READ。没有合并退回统一订单读取范围。
- 运营 NAV 保留原平台管理子菜单并追加 `/printing`；POINT_CODES、perm map、英文导航标签及 V588 种子一致，受 `shop.print.enabled` 门控。API contract/http/mock 通过新增切片组合，原切片保留。backend-ops-ui-delivery.json 删除的是已由新运营页面调用的四个打印待交付项。
- B 端原打印配置/设备/模板页面保留；新增来源选择、模拟详情和跨门店清理沿用现有 API。`DefaultPrintDocumentCompiler` 对紧凑小票布局仅改变真实来源的 required scopes，原 CompactReceiptRows 调用和 PDF/模拟/原订单生成路径未被删改。
- 审查后段的 `git ls-files -u` 为空；`git diff --check HEAD` 无输出，退出码 0。生成文档由 root 按当前统一分支重生成；本审查未执行任何生成器。

## 非阻塞提示与边界

`docs/technical/acceptance/print-center/operations-and-sources-results.json:45` 仍记录历史专项时的 `V587__business_print_facts.sql`。建议在本次整合验收记录明确该未发布文件现重命名为 V589；历史证据不应当成当前整合迁移或测试结果。

按委派约束，本审查没有修改代码、没有运行构建/测试、没有提交。既有 154 项中 153 通过、1 opt-in skip 的专项结果没有被当作本次合并验证。当前整合的编译、测试、生成契约检查和最终提交由 root 负责；本报告不评估未展示的未来变更、真实设备或生产启用。

## 追加复核：shop-app Surefire Mockito 启动 agent

集成回归后，按 root 请求只读复核 `backend/shop-app/pom.xml` 的最小配置补丁。

结论：配置修复与已展示故障相符，未发现 Critical / Important 问题；实际修复效果仍应以 root 重跑的相同专项结果为准。

- `/tmp/print-unified-20261026/backend-tests.log:943` 起明确显示 `Could not initialize inline Byte Buddy mock maker`，后续根因为 `Could not self-attach to current VM using external process`，运行环境为 Java 21。
- child Surefire 的独立 `argLine` 会覆盖父级同名配置，原 child 行缺少父级的 Mockito 启动 agent。补丁仅在原 `-XX:MaxRAMPercentage=40 -Duser.timezone=Asia/Shanghai` 后追加父级完全相同的 `-javaagent:"${settings.localRepository}/org/mockito/mockito-core/${mockito.version}/mockito-core-${mockito.version}.jar"`，没有改回父级固定 Xmx、取消时区或改变测试选择/分组。
- 新注释准确解释父级覆盖语义及启动时加载 agent 的目的。补丁没有修改业务代码、打印状态/权限或测试断言，没有增加镜像式 POM 测试。该文件 `git diff --check HEAD` 无错误。
- 归因说明：`a522adfa9` 仅在此前已存在的 child `-XX:MaxRAMPercentage=40` 后增加生产时区；父级 agent 被覆盖并非由 a522 单独移除 agent 所致。这里修复的是 child 独立 argLine 遗失父级启动参数的继承兼容问题。

此追加复核未运行 Maven、构建或测试，未修改源码。没有用旧 154 项专项结果替代新整合验证。
