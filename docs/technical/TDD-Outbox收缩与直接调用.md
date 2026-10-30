# Outbox收缩与直接调用

## 0 需求与边界

用户确认：非必要不用Outbox，同进程Service/Port直接调用，跨进程用已有客户端；先统一编码编译，再集中相关测试。依据本轮全仓51个业务处理实现评估。数据库仅MySQL9，冻结迁移不改；资金等关键跨库事实保留。

## 1 契约和存储审查

餐饮18类默认归档事件仅在存在真实消费者时投递，否则跳过，原业务操作日志保留。管理事件实际还是幂等结果及品牌审计的数据源：增加CORE V622的mch_management_command，同事务保存原commandId/payload/createdAt，迁移旧MANAGEMENT记录并保留原id，审计分页和结果不变；旧generic记录不删除。打印PDF/模拟及积分下单已有任务/意图和恢复入口，停止新增唤醒事件；切换前需启用既有任务，不另加调度器。

库存复用fnb_stock_sync_delivery，保留原payload、键、generation、版本和回执；本地提交后直接推进，失败/进程退出由餐饮既有PRODUCTION_RECOVERY任务恢复。FIFO claim和超时恢复使用现有status/next_attempt_at/version，无新增同步表或任务。food-channel使用独立inventory-client直连，food-core不依赖库存client。旧Outbox handler/API只为切换前积压兼容，新写路径不再创建通用事件。

## 2 实施清单

- [x] A 餐饮纯归档停止新写；管理命令账本迁移/幂等/审计改为直接存取；空消费者退役需有迁移证据。
- [x] B 打印导出/模拟与积分下单停止重复唤醒，复用原任务和直接恢复，保持关键资金事件。
- [x] C 库存同步原业务记录直接投递，短事务认领、事务外HTTP、回执恢复；复用餐饮维护任务，补直接库存客户端。
- [x] D 统一编译embedded/standalone相关代码后集中验证归档、品牌幂等/审计、任务恢复和MySQL9真实库存同步；只定向复测修复。
- [ ] E 后续拆分厨房/KDS/消耗/渠道/报表混合消费者，逐个具备直接调用与恢复替代后退役；本轮不将未迁移的混合消费者标记完成。

## 5 验收与切换

新增普通维护不产生仅归档Outbox；有真实渠道订阅仍可靠投递。管理原幂等/冲突/审计权限和游标不变。库存OFF不依赖远端，变更与原投递记录同事务，远端失败不改本地成功，丢响应查回、分页基线、并发、启停及重启可恢复。新库存同步不写sys_outbox。异步业务仍只有既有任务负责恢复。

部署前停止旧管理写者并迁移命令账本；启用打印artifact维护和积分恢复任务；保留旧库存handler直到旧积压查回完成，不伪造SENT回执。不在编码阶段部署或切生产开关。历史迁移及旧事件记录不删除；最终证据登记统一实施07。


## 6 兼容与回滚

V622切换前停止旧管理写者及对应运行器，防止迁移后仍写旧审计源。已有被手工禁用的打印artifact维护/积分恢复任务需显式启用，代码默认值不会覆写持久任务定义。旧库存handler仅清历史积压，新写使用原业务记录直接客户端。回滚不能仅换旧JAR：须迁回切换后新增的管理命令并移交未完成库存业务投递，避免读源分裂或恢复缺口。

测试夹具兼容修复仅限H2：生成器删除代码区的MySQL内联charset语法、平台夹具增加真实SHA2实现；V621重命名和已有库存descriptor的投影清单同步，不修改冻结迁移或原生约束。测试中发现取消订单返回详情误用事务外鉴权入口，改为读取已验证属主详情；保留属主查询条件。详07验收记录。


## 7 E1生产耗用直接触发实施计划

目标：去掉DINING_SERVICE_EVENT对food-production-consumption-v1的新投递，不改变打印/KDS/渠道/报表的事实交接。复用fnb_service_event与fnb_production_consumption，无DDL、无新增公网API/任务。

- [x] KitchenTaskServiceImpl注入原ProductionConsumptionService；MAKE START提交后在事务外consume原eventNo，回滚不调用，远端失败不改变已提交厨房动作。MATERIAL_SUPPLEMENT/WASTE原入口本已提交后直接consume，继续复用。
- [x] ServiceEventMapper新增有界未建耗用记录查询，沿同租户/主体的任务、厨单、批次归属取原entity/store/event；仅MAKE且START/补料/损耗、已接单/未删除事实。ProductionConsumptionServiceImpl.recover预留至多半批预算（至少1项）读取首次缺口，已有待确认使用剩余预算；两组先取快照再处理，缺口为空时已有待确认可用满批预算，保留同键幂等、BLOCKED人工处理；不重复投递已建记录。
- [x] FoodOutboxRouting去掉新生产target；DiningProductionConsumer.supports不再接受新事件，但handle按原类型继续处理已有delivery；运行器按稳定handlerId处理历史记录，无须扩展通用Outbox框架。
- [x] 集成/独立相关代码统一编译后，集中测试真实Mapper范围/过滤/limit、提交/回滚/故障恢复、原生产算法和旧handler兼容、独立producer。只有失败修复定向复测。

本轮保留：接单消费者同时调用厨房与远程打印；KDS消费者同时本地投影与远程打印；渠道通知跨独立commerce库，报表更正跨独立报表库。它们尚无覆盖首次交接缺口的完整原业务恢复，直接删除会丢事实，后续按原业务任务替代，不为减少数量另造队列。


E1复核发现已有未决记录满批会饿死首次缺口。真实MySQL回归先复现失败，再改为预留首次缺口预算，相关复测通过；单批limit=1时优先补首次缺口，无缺口再推进旧记录。原默认任务limit=50时两类均可前进。该修复不引入新状态表或任务。旧BLOCKED仍需人工修复，不计完成；原生事实、金额/库存owner均不改。

## 8 E2工位与语音状态直接写KDS

需求：暂停/恢复、语音QUEUED/SENT/UNKNOWN/PLAYED等工位状态只更新本库KDS，无纸张动作。沿原command/status/sourceVersion/payload语义，直接调用FoodKdsService.append，同一事务持久化工位命令、真实回执及KDS事件；回滚一起撤销，无提交后窗口。不增加表、任务、权限或公网API。订单/退菜/换台等需要远程打印的交接仍保留，不另造一张打印意图表代替必要Outbox。

- [x] FoodStationRuntimeServiceImpl去掉Outbox依赖，构造原STATION_CONTROL/VOICE_CALL EventDraft；工位控制、领取、回执统一在修改命令前锁原KDS cursor，保持锁顺序，复用原幂等返回。
- [x] FoodKdsAutoConfiguration移除publisher注入；FoodOutboxRouting不再声明工位新投递；DiningKdsEventConsumer仅对历史工位delivery保留handle，新supports不含工位。非打印事件先返回，不为其访问打印owner。
- [x] 先统一embedded/standalone编译，再集中原工位/语音、KDS流、旧consumer和producer测试；原生MySQL验证事务回滚、顺序/幂等、真实语音回执及控制/领取并发。只补测失败修复。

切换时暂停工位写入并处理/核对原工位KDS积压后再开放新写，以免旧异步事件在新同步事件之后追加；失败不伪造SENT，尚未收敛的门店暂不切换。旧handle保持可执行。未部署、不改线上开关，完整厨房/订单打印交接与渠道/报表边界仍保留。


E2已完成工位/语音本地KDS直接写入；其余厨房/订单/退菜/换台的远程打印交接仍保留，不将必要交付替换成另一套同义队列。原生MySQL与相关接口/路由24项最终通过。工位命令状态APPLIED、工位状态PAUSED/RUNNING与语音真实PLAYED分别保留原语义，游标为本地事件序列，sourceVersion仍是原命令版本。验收与切换见统一实施07。
