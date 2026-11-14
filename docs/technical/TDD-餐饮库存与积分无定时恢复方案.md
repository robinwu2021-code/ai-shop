# 餐饮库存与积分无定时恢复方案

## 0 范围和状态

用户确认保留生产 food-production-recovery、order-points-recovery 禁用，要求补充同步容错、明确错误及人工处理。本文为实施前代码核对，尚未实现或验收；不可据此发布。

## 1 已有事实与约束

- KitchenTaskServiceImpl 的 MAKE/START 在原出品事件提交后通过 AfterCommit.runOutsideTransaction 调用 ProductionConsumptionService.consume。
- ProductionConsumptionServiceImpl.consume 显式禁止外层事务，先事务内 prepare、再查询原库存回执，必要时消费，最后事务内 finish；异常落 UNKNOWN 后抛出。recover 能扫描未生成耗用记录的原事件及 PREPARED/SUBMITTED/UNKNOWN/REVERSAL_PENDING，覆盖提交后首次调用前的宕机窗口。
- FoodProductionInventoryRouter 按原订单冻结 owner 分发；FoodProductionStockService 的 owner、execute、reverse 同样禁止事务内调用，并核对共享订单的冻结计划与桥接账本；FoodStockReservationServiceImpl 使用自身 TransactionTemplate。不得删除 outside 检查或直接在厨房事务内调用既有跨 owner 端口。
- BizProductionConsumptionController 已提供按当前门店的原事件 consume、get 和门店耗用分页；consume 沿原事件查询已有回执后再处理。已有分页仅覆盖已创建的耗用记录，不能证明覆盖“事件已提交但未创建耗用记录”的缺口。
- FoodMaintenanceServiceImpl 的 PRODUCTION_RECOVERY 同时处理出品、退餐库存和可选库存镜像；仅解决出品不能声称整个任务可禁用。
- 积分恢复服务按下单、退款、事件及人工售后队列恢复原引用，资金由支付域负责；用户没有授权启用任务或接入真实资金。
- 用户约定前端暂缓，现阶段交付后端合同和可供终端使用的状态，不宣称已实现终端提示。

## 2 实施顺序

1. 核实订单/厨房/库存真实数据源、事务管理器及桥接账本的写入关系；设计 FOOD_LOCAL 原子命令，使原订单计划可信校验、厨房事件、原耗用凭据、库存流水和本地余额在同事务内提交。不能用跨服务 RPC 或独立事务冒充原子性；旧 owner 保留原路径。
2. 在调用前仅对可确定的库存不足、禁用和配置缺失返回明确错误；校验失败不得提交 START。确认结果必须对应已提交事实；请求断开用相同幂等键查询原命令。
3. 门店范围提供待处理项：合并原 START 无耗用记录的缺口和已建耗用的待处理状态；使用现有实体/门店授权，分页与索引复用并核实，避免仅展示记录表造成漏报。处理时按原事件查回、重试，使用现有 consume 合同；UNKNOWN 先查回，不直接视作失败或重新扣减。补充退餐库存及可选镜像各自的原凭据和人工入口，不能将它们归为出品完成。
4. 积分先核实既有商家功能开关、原订单/售后操作与可见性，以及服务端可安全关闭新单入口的已有配置；对已有意图提供按商家及门店授权的原号查回/处理。UNKNOWN 不得宣称失败或重复调用；不能修改支付资金事实。
5. 先完成代码和集成/独立形态编译，再针对同事务失败回滚、进程中断、重复键、跨 owner、失联查回、门店隔离和历史 pending 执行必要的定向测试。云端 MySQL 稳定性、负载及隔离迁移先评估，不再次在生产运行高负载完整副本恢复。

## 3 验收边界

取消两个定时任务作为依赖的条件：本地出品无提交间隙；出品、退餐库存、可选镜像和积分各自的历史缺口可发现、可人工处理、可审计；跨服务未知状态先查回；对未使用积分的门店新单不能产生积分意图。人工模式不承诺无人干预的自动恢复。完成这些条件前不切换生产，也不启用任务。

## 4 事务边界进一步核对

集成形态下，KitchenTaskServiceImpl、FoodStockReservationServiceImpl及共享FoodStockBridgeJournal均注入平台transactionManager；同库本地事务具备基础条件，但不能直接复用现有调用链。共享FoodProductionStockService.outside 明确要求不持有 CORE 事务；FoodStockBridgeJournal.acquire 也明确要求先提交桥接命令后再远程执行，并在唯一键竞争时通过独立事务读取。原路径的这些规则不能为新路径全局解除，否则跨服务调用和并发冲突语义改变。

独立部署时，FOOD 与 CORE 可以分别使用不同数据库和事务管理器；原订单冻结计划由 CORE 持有，FOOD 不能在自己的本地事务中同时读写 CORE 并声称原子。因此仅对已经证明为同库同事务管理器的集成形态提供原子路径；独立形态需要预先在可信边界取得不可变计划证据，并在 FOOD 本地事务内校验和执行，如何防止计划过期、伪造及重复仍需实现和验证。无法满足前须保持待确认、原号查回/人工恢复，不能标记已完成。

生产事件缺口查询目前 FoodMappers.ProductionEventMapper.unconsumed(limit) 仅供后台全局恢复；商家分页必须新增主体、门店过滤和稳定分页条件，不得直接把无范围的全局扫描暴露给商家。优先沿现有租户/门店校验、原事件编号和远程 SPI 合同扩展，验证跨租户隔离及旧订单兼容。

## 5 第一批编码：待处理出品查询

已编码，尚未部署；不代表本方案整体完成。

新增商家 GET /biz/x/food/stores/{storeNo}/production-pending，权限沿 BizPerms.STOCK 和 FoodBizStoreScope。输入 afterId（默认0，>=0）、limit（默认20，1–100），由框架校验；返回 PendingVO 列表：sourceId、sourceEventNo、taskNo、action、status。下页使用最后一项 sourceId，空列表结束；这是实时游标查询，不是冻结快照。首次无耗用记录为 NOT_STARTED；既有 PREPARED/SUBMITTED/UNKNOWN/REVERSAL_PENDING/BLOCKED/FAILED 可见。已完成记录排除；已有待处理记录不因批次取消被隐藏，无记录的已取消批次不再列出。显示状态不构成库存成功证据。

独立调用新增 POST /internal/food/production/pending，输入 PendingCommand(entity,store,actor,query)，沿原服务令牌与门店业务校验。服务接口 ProductionConsumptionService.pending、RemoteProductionConsumptionService、InternalProductionConsumptionController 与商家 Controller 完整对齐。原 get/:consume 接口继续按 sourceEventNo 查询或处理，不新增自动扫描、任务或定时器。NOT_STARTED 需要调用原 consume 才会创建耗用凭据；UNKNOWN 仍按原实现先查询库存回执。无表/字段/迁移增量。

SQL 在 ServiceEventMapper 显式过滤 MAIN、entity、store、deleted、MAKE 与原动作，按源事件id排他翻页；读取本地事实，不调用库存服务。缺口与既有耗用 LEFT JOIN，避免列表只读耗用表而漏报。参数通过 MyBatis 绑定。

验证：宿主与food-server package成功（/private/tmp/food-pending-build.log）。KitchenLifecycleAcceptanceTest.actualMissingSourceQueryRespectsOriginalScopeRoleActionAndExistingReceipts 执行真实H2/MyBatis SQL，覆盖原事件缺口、PACK/已删除排除、门店/主体隔离、游标、BLOCKED/完成记录及取消批次，相关测试日志 /private/tmp/food-pending-tests.log，取消批次补验日志 /private/tmp/food-pending-final-tests.log。该证据不代替MySQL原生或真实HTTP验收。代码只读审查无实质问题。

剩余必须继续：FOOD_LOCAL原子出品路径及回滚验证、退餐库存/可选镜像的人工处理可见性、积分功能及历史未决意图的授权查回。这些完成前仍不能取消原恢复任务依赖或部署依赖它们的新版本。
