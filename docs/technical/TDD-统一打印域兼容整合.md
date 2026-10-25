# TDD · 统一打印域兼容整合

状态：适配实现及集中专项验收完成，已完成最新统一基线47c0aaed0的交叉验证；全仓既有失败见合并记录。

## 0. 需求与验收映射

依据：用户2026-10-25明确选择“适配现有统一打印域”；[餐饮后端功能清单 MOD-07](../requirements/餐饮系统-后端业务模块功能清单.md)、[打印完整方案](design/打印中心-完整方案与模块封装.md)。本稿覆盖两套实现发生冲突时的整合边界，优先于独立分支旧稿中的22表独占和独立投递状态机描述。

| AC | 目标 | 实现落点 | 验收证据 |
|---|---|---|---|
| UPR-01 | 原餐饮PrintPort、内部HTTP和设备桥接调用继续有效，历史编号与回执不失效 | core/printing唯一PrintService及兼容协议 | 原PrintDeliveryTest、餐饮CorePrintPort/工作流回归 |
| UPR-02 | 仅统一owner能创建任务和attempt、领取设备队列、归并回执 | owner内部命令；新模块仅编排和适配 | 新旧入口同时领取、旧token与撤销epoch、所有权架构守卫 |
| UPR-03 | 场景模板、模拟、PDF、受控下载、通知与订单支付功能保留 | print-api、纯编译/布局/文件组件、统一owner扩展与shop-app桥接 | 模板/PDF内容、ORDER_PAID、权限/数据域和通知幂等回归 |
| UPR-04 | 不确定输出不自动重打，补打保持原冻结内容；文件就绪不等于出纸 | 同一投递策略、设备门控、补打和证据策略 | 不打印、接收无结果、部分输出、晚回执、人工处置与补打 |
| UPR-05 | 空库和既有统一库均可升级，无重复建表，无历史迁移checksum变化 | 撤出未发布候选迁移，V582起兼容迁移与附属数据 | 空库迁移、V567/V579历史库升级与原任务/lease回执 |

## 1. 核心决策

采用 [ADR-041](ADR/ADR-041-统一打印投递所有权与兼容整合.md)。`PrintService`继续拥有`prn_device/prn_template/prn_route/prn_job/prn_attempt`。新模块原四张同名表分别表示不同概念，不能直接注册第二套实体/Mapper，也不能通过改前缀保留第二个在线投递器。

- 设备、任务、attempt、当前lease和队列门控由统一owner维护；禁止两个claim入口各自分配有效attempt。
- 目录、富模板版本、条件路由计划、来源事实、request/batch、凭据/agent信息、冻结附件、模拟、导出、审计可作为附属能力，必须指向统一设备/任务身份。
- 旧文本模板和文档继续按既有格式处理；新编译文档以显式格式版本入队，不强行扩展或绕过旧七场景校验。
- 旧协议`PENDING/SENT/SUCCESS/FAILED/UNKNOWN`保持兼容。新API详细阶段必须由owner真实状态和证据推导，不把SENT、ACCEPTED、PASSED、READY映射为完成。
- 批次/异常通知来自统一owner事件投影；站内消息幂等，PUSH外部终端不承诺严格恰好一次。
- 任何新旧跨域转换均校验tenant/entity/store和动作权限。ops查询不以增加数据域豁免代替授权。
- 飞鹅简洁V2及Windows实机仍未验证，保留真实能力限制；只有ORDER_PAID有正式业务来源，其余19场景不自动启用。

### 迁移原则

V567、V579及其他已发布迁移字节不变。独立打印分支V551/V552/V553未部署生产，从统一应用的默认迁移序列撤出并原样归档；通过未占用的V582及后续版本增加兼容数据结构。不得在一个正常迁移序列中先建新四表、再让V567建同名表。若以后导入独立分支测试库数据，使用显式导入映射，不能伪造同一Flyway history。

同一物理设备须复用规范设备身份；存在未核验旧设备时，新登记必须显式收编原设备编号，不能另建编号绕过队列门控。商家页面提供原设备编号输入，服务端核验门店及物理身份。

历史任务不重新渲染、重编号或重置UNKNOWN；已有有效lease继续受原协议校验。新增功能关闭时，旧调用仍可运行；回滚不得通过重新排队未知任务实现。

## 2. 连续实施任务

遵循用户“先代码和用例，统一编译，集中验收”的执行顺序，不在每个子任务后单独请求许可。

| 编号 | 范围 | 具体改造与交付 |
|---|---|---|
| UPR-T01 | 统一owner与数据结构 | 核实历史迁移，归档候选迁移；扩展统一服务内部命令、冻结附属数据、幂等及统一设备锁 |
| UPR-T02 | 管理与模板规划 | 将新模板头/版本、路由规则、设备扩展和59接口适配到统一owner，保留当前权限及真实allowedActions |
| UPR-T03 | 执行与恢复 | 替换新模块直接写任务、attempt、runtime的路径；cloud/agent/callback/maintenance均经统一命令，移除第二套owner |
| UPR-T04 | 订单、模拟、文件和通知 | 复用纯组件；JOB/BATCH仅读统一冻结附件，模拟无投递副作用，统一事件驱动通知与批次投影 |
| UPR-T05 | 兼容元数据与文档 | 更新DDL/继承字段/权限种子解析，保持新平台规则；不扩known基线、不隐藏实体冲突；生成文档从最终真源重建 |
| UPR-T06 | 集中验收与合并 | 编译全部受影响模块；原餐饮与订单打印、迁移升级、单owner并发、前端和守卫验收；通过后才生成合并提交 |

### 文件与职责

- `backend/shop-core/.../printing/service`、`service/impl/PrintServiceImpl`：唯一可投递任务和尝试的所有者，旧契约兼容与新受控命令。
- `backend/shop-base/.../spi/printing/PrintPort`及餐饮客户端：保留现有契约；不在调用方复制任务/设备状态。
- `backend/shop-core/.../printing/center`：统一owner内部的富模板、来源、请求、投递、监控、审计和文件编排；直接复用规范job/attempt实体。`print-domain`仅为兼容聚合依赖，不含第二套Java实现。
- `backend/shop-core/.../printing/center/template`、`layout`及`backend/print/print-channel/.../pdf`、`font`：纯编译、排版、PDF和字体组件；新增跨域Port保留于print-api构件，包名遵循现有`spi.printing`规范。
- `backend/shop-app/.../printbridge`：订单事实、统一Outbox/Job、通知及协议装配；不能同时启动第二套投递消费者。
- `backend/shop-app/src/main/resources/db/migration`：兼容增量迁移；`backend/scripts/gen-test-schema.py`及guard解析器只按实际SQL语义更新。
- 商家端与公开API：优先保持现有打印页面能力，必要的契约调整必须同步DTO、端点、三语和生成文档，不能只改后端。

## 3. 异常队列治理契约

`POST /internal/printing/deviceRecovery`以`scope`和`deviceNo`读取设备版本、当前attempt及所有未决执行，供治理前核对；不需要直连数据库。`POST /internal/printing/resolveDeviceUncertainty`沿用同一内部服务token校验，两个接口均独立于`shop.print.enabled`。请求包含`scope`和`resolution`；后者字段为`deviceNo`、`expectedVersion`、`activeAttemptNo`、`reason`、`evidenceRef`、`oldExecutorStopped`、`requestKey`。仅在核实旧执行器与物理队列已停止后调用。owner核对门店、设备版本与当前attempt，按幂等键保留审计，标记对应执行已释放；原任务保持UNKNOWN，不自动重排、不认定出纸成功。历史上同设备多个未决任务须逐个处置，不能一次清空队列证据。

商家新中心原有J21治理接口继续保留；此内部接口保障仅运行旧打印域时的受控恢复。不得向商家前端暴露内部服务token。

## 5. 验收计划与实际记录

具体新测试方法名随实现补齐，完成前不将本表标为通过。必须包括：

1. 旧PrintPort/HTTP合同与PrintDeliveryTest；新旧同时领取同一设备仅一个active attempt。
2. 空库与V567/V579既有库升级，历史task/attempt/UNKNOWN/有效旧lease保持；迁移重放幂等。
3. 订单支付事实与Outbox同事务、重复消费、冻结内容、退款/取消提交前guard。
4. 20场景/58与80mm编译、JSON富模板与旧文本模板兼容、长名称/金额/零值/补打警示。
5. 模拟/PDF无真实任务完成副作用；PDF内容、签名、权限、跨店及过期清理。
6. 接收未完成、结果未知、部分输出、晚回执、取消、撤销授权、人工证据、补打/加印及通知。
7. 设备/凭据/agent租约跨门店隔离；ops数据域；旧餐饮厨房/KDS事件不被出纸结果改变。
8. owner架构检查：只有统一owner写规范job/attempt/设备gate，不能用换表名绕过。

合并准备阶段商家端331项及类型检查通过，脚本6项通过；后端/共享检查存在未解决问题，不能把独立分支72项结果当作本次适配完成。最终命令、数量及限制追加到[合并记录](acceptance/print-center/unified-merge-2026-10-25.md)。

## 6. 实现对账

UPR-T01～05已实现：规范实体与单设备gate、物理身份收编、归档候选及V582～V584、模板/模拟/PDF/通知和订单接入、SPI及数据域元数据。UPR-T06集中专项分轮144项通过，最新统一基线追加37项交叉回归通过；打印整合提交260674ffa，最终合并提交见Git历史。未改生产环境。具体类名、命令及既有失败见合并记录。
