# 餐饮系统 TBL02 桌台配置需求细化

> 状态：下一切片需求稿，待进入开发；2026-10-21。依据：原MOD-01、TBL-02、API024及096～100、现有fnb_table与占用表。沿简单逻辑，不新建桌台基础设施。

本切片让授权门店管理人员在已启用桌区中维护实际桌台，交付列表、详情、建桌、编辑、启停和归档。收银平板、服务员端、老板App共用授权查询；配置写入需原平台权限。不新增C端或平台代维护接口。

## 1 范围与已有合同

| 功能 | 原API | 需求 |
|---|---|---|
| 建桌 | 024 POST /biz/x/food/stores/{storeNo}/tables | 同店已启用桌区、唯一编码、名称、正容量、排序；初始ACTIVE及IDLE |
| 列表 | 096 GET /biz/x/food/stores/{storeNo}/tables | page/size、areaNo、status、turnoverState、keyword过滤；records和total同主体门店范围 |
| 详情 | 097 GET /biz/x/food/tables/{tableNo} | 当前授权门店对象，输出业务字段和version，不暴露物理id |
| 编辑 | 098 PUT /biz/x/food/tables/{tableNo} | 修改桌区、名称、容量、排序；code及storeNo不变；活动占用时禁止移区、缩容 |
| 启停 | 099 POST /biz/x/food/tables/{tableNo}:set-status | ACTIVE/DISABLED，当前version及reason；有活动占用不允许停用 |
| 归档 | 100 POST /biz/x/food/tables/{tableNo}:archive | 先停用、无活动占用；保留历史关联和原编码占用，返回原Operation合同 |

复用[原API设计](../technical/design/餐饮系统-API设计.md)及[原数据库设计](../technical/design/餐饮系统-数据库设计.md)，Service继续扩展TableService，不另建TableAreaService、Repository或终端专用业务Service。

## 2 状态和字段规则

配置状态status仅ACTIVE/DISABLED。资源投影turnoverState为IDLE/IN_USE/NEEDS_CLEANING；新桌IDLE，但编辑和启停不能由客户端指定资源状态。已付款不等于桌台空闲。

使用既有fnb_table：entityNo、storeNo、tableNo、areaNo、code、name、capacity、sortNo、status、turnoverState、resourceNo及BaseEntity公共字段。tableNo系统生成，code同店终身不复用；归档不能释放其唯一键。活动占用依据fnb_table_occupancy.ended_at，不用支付状态代替。

下列细则延续桌区方式，作为本稿建议：字符串trim；code最长32、名称最长64；capacity为正int32，不引入业态专用上限；sortNo允许重复，列表按sortNo/tableNo稳定升序；分页默认1/20、size最大100；同状态且version正确时无变更成功。具体DTO结构、字段校验与错误映射在编码前对齐原方法清单。

归档沿BaseEntity逻辑删除，禁止物理删除。返回原归档操作回执；原key重放原回执。归档后正常列表和详情不暴露该桌台，但历史订单、占用、审计引用仍保留。

resourceNo是共享资源引用，不是餐饮自行创建的资源。当前资源权威查询合同尚需核验：如无法验证归属和可绑定能力，应明确拒绝非空resourceNo；不得静默忽略、信任任意编号或假造资源记录。无资源绑定的普通建桌可以独立交付。

## 3 桌区关联与并发

创建、移区及重新启用必须验证目标桌区与主体门店一致且ACTIVE，并锁定NEXT-09使用的同一桌区仲裁行。在同一事务内完成桌区校验和桌台写入，避免桌区刚停用又新增启用桌台。

编辑、停用及归档先校验当前桌台范围和version。活动占用时可以改名和排序；禁止移区、缩容、停用或归档，不自动释放占用或关闭台账。无占用时，移区也只能移到同店启用桌区。

多桌区锁按业务编号稳定顺序获取；未来开台占用写入还需遵循一致的锁顺序。开发设计必须明确锁顺序并用真实MySQL并发测试证明，不用重试掩盖竞态。

所有实际变化沿既有幂等、审计、Outbox同主事务；事件携带前后业务快照，以操作号关联原指纹审计。reason沿已有255长度审计列，操作人来自原认证上下文。

## 4 各端与部署

B端配置与查询沿原STORE权限、门店目录及当前门店范围。仅知道tableNo不能越权；body不接受entityNo、storeNo、actorNo或turnoverState重写。独立内部接口沿原服务令牌，使用相同Service合同、DTO和业务错误，不增加写重试。

厨房和顾客端不开放桌台配置；后续由制作任务或桌码会话获取所需上下文。无桌快餐、外卖不强制创建桌台。支付库存未接通不阻断非交易配置，但不因此开放正式交易。

## 5 验收清单

- 授权门店建桌、列表、详情、修改、启停、归档形成完整调用闭环；空列表和分页total正确。
- 同店编码并发创建只有一个成功，跨店可同编码；归档后不能复用原编码。
- 停用桌区、其他门店桌区以及其他主体对象均不能用于创建、移区或启用。
- 旧version、同键异参/异对象/异actor拒绝；成功重放无重复业务变化。
- 有活动占用时移区、缩容、停用、归档全部拒绝；改名、排序保留占用和历史引用。
- 创建/启用与桌区停用竞争，不得产生DISABLED桌区下新建或刚启用的ACTIVE桌台。
- 客户端不能伪造资源状态；详情不返回物理id；keyword筛选不能越过主体门店范围。
- 实际变更前后值、原因和操作人可追溯；审计或事件失败全回滚。
- 集成、独立部署合同一致；未知资源引用拒绝，不引入支付库存调用。

## 6 后续顺序

先核验resourceNo权威合同和归档回执结构，再补DTO/VO、原TableService方法、Mapper查询及锁顺序设计，按列表/详情→创建→编辑→启停→归档逐项实现。桌码轮换、开台人数座位、占用、清台、转并拼桌、AA及打印仍沿后续模块推进。
