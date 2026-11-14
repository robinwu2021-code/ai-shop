# 运营打印页面独立只读审查

审查范围：`/tmp/print-next-wave/ops-review.diff` 所列 Task 2 改动；对照计划 Global Constraints / Review Focus / Task 2，以及当前 OpsPrintController、PrintMonitorDtos、PrintActorFactory、PrintOpsServiceImpl、PrintOperationServiceImpl、PrintReconcileJob 和既有权限/菜单/HTTP 封装。基线 ba44c7f0c，未提交实现。

## Findings

未发现可确认的 Critical / Important 缺陷。

## Spec 结论

**通过静态审查，待集中验收与 root 的菜单迁移/生成物集成。**

- 四个 HTTP 方法正确接入组合 API；DTO 与实际 Java records 对齐，分页使用 records/total/page/size，Instant 使用 ISO 字符串；POST 保留第三参数查询串语义，以第四参数传递 Idempotency-Key，经公共 req 合并认证头。
- 查询与写入均显式传 tenantNo/entityNo/storeNo；64 字符引用校验和 trim 合理。前端可编辑目标不构成授予范围：后端 PrintActorFactory 校验当前操作员租户、数据域以及真实门店所属主体/状态，PrintOpsServiceImpl 再按已授权目标建立作用域。
- READ 控制页面和所有查询入口，RESOLVE 控制提交；新权限映射齐全。nav 登记 shop.print.enabled；页面使用后端菜单 NOT_IMPLEMENTED 结果控制深链，控制器本身也由该开关装配。菜单尚未加载时沿用既有 isPointUnimplemented 的静态回落行为，最终关闭仍由后端保证。
- 修改目标立即卸载 Scoped；身份/权限变化用 auth key 卸载 Target，查询 session 隔离缓存。迟到查询或 mutation 的 setState 只影响已卸载实例，不能渲染到新目标。操作编号编辑立即清除当前 lookup/提交结果。
- 同步 ref 锁先于 await，pending 时禁用对账/操作表单；未修改的失败请求保留幂等键，编辑表单或切换目标生成新意图；目标修改不会继承旧操作结果。
- 默认 dryRun，实际执行要求明确目标、时间范围、来源、理由和额外确认；UNKNOWN/PENDING 明确未完成，SUCCEEDED 仅描述维护完成，没有自动未知任务重试或设备门控释放入口。SUB_ORDER 与真实适配器 sourceTypes 一致；未知来源由后端 registry 拒绝，mock 无真实授权来源时关闭。

## Quality 结论

**未发现需要阻断集成的质量问题；现有行为测试覆盖主要风险路径。**

新增测试涵盖只读/处置权限、目标/时间表单、分页、迟到请求、目标切换、并发双提交、精确失败意图恢复、操作编号编辑、UNKNOWN/PENDING 语义及 HTTP transport。未通过阅读冒充测试执行结果。

本次按指示未运行测试、typecheck、build、服务器或浏览器，未改源码、未 commit。契约生成、真实菜单迁移 V588、全量权限/API 守卫及运行验收由 root 后续统一执行，不能由本次静态结论替代。


## 最后 delta 复核（2026-10-25）

仅只读检查来源 Select、引用长度限制及异常空态文案；未运行任何测试或构建。

**Important / P2：默认筛选下的异常“刷新”按钮没有发请求。**

位置：`ops-web/app/printing/page.tsx:68`。异常筛选提交仅调用 `setStatus(filter.trim())` 和 `setPage(1)`；初始空筛选、page=1，或者已应用同一筛选且在第一页时，两项 state 均不变，queryKey 也不变，因而点击“刷新”不会调用接口。新空态文案明确引导运营刷新查看；如果另一操作已生成异常，运营点击后仍看旧空态，错误恢复路径无效。建议在筛选值及页码都未改变时显式调用 `incidents.refetch()`，避免目标变更时额外查询旧 key。这与操作查询中的同编号显式 refetch 是同类情形。

其余 delta：Select 仅提供 SUB_ORDER / REFUND / RECHARGE，均与当前后端 sourceTypes 一致，cashier 未暴露；helper 同步限制来源集合；表单和 helper 均采用 tenant32/entity64/store64；zh/en 空态提供调整筛选/刷新下一步，双语业务来源标签清晰。

**最终 Spec 结论：存在上述局部刷新接线缺陷，修复后可通过本次静态复核。最终 Quality 结论：该 P2 应在收口前修复，其余此次 delta 未发现阻断问题。**

root 提供的集中验收结果：ops 全量 879 passed、typecheck 与生产 webpack 构建通过；Turbopack 受 worktree 软链环境限制。该执行结果为 root 提供，本审查未重新执行。

## P2 修复最终复核

已只读核对 `ops-web/app/printing/page.tsx:68-74`：规范化后的筛选未变且 page=1 时显式 `incidents.refetch()`；筛选变化或从第二页返回第一页时仅更新 queryKey，不额外刷新旧筛选/旧页。此前 P2 已修复。

新增两条行为测试分别断言默认/空白规范化筛选刷新，以及筛选变化、翻页、返回第一页时的准确请求次数和目标参数。测试内容覆盖该缺陷及重复请求风险；执行结果由 root 集中复验，本审查未运行。

**最终 Spec 结论：通过静态审查，无未解决的 Critical / Important 缺陷。最终 Quality 结论：通过本次最终复核；集中验收最终结果仍以 root 证据为准。**
