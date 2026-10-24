# 餐饮系统API设计

> 状态：待评审 · 2026-10-21 · V1含73张fnb_业务表；当前目标85张，后续迁移可用至V15（含V9耗用、V10排队及V11～V15当前业务增量，均不表示经营库已应用），另2张PLANNED；历史经营库执行证据仍V1/73；目标API不代表全部交付；共享打印V556可用，报表保持原共享owner，不声明生产已应用。
> 由 `node scripts/gen-food-system-design.mjs` 生成，勿手改；真源：[领域数据库API设计源](./schema/food-system-design.json)。生成依赖沿用 @viz-js/viz，可用 FOOD_ER_VIZ_MODULE 指定模块路径。
> 业务入口：[后端业务模块功能清单](../../requirements/餐饮系统-后端业务模块功能清单.md) · [数据库与API评审对齐](../TDD-餐饮系统-数据库与API评审对齐.md)。按桌台→菜品→订单查阅，A–L仍保留为持久化分域编号。
> 项目约束：[底座兼容对齐](./餐饮系统-项目底座兼容对齐.md)，账号、门店、营业时间、公共实体及协议冲突时优先按该对齐说明修订。
> 产品依据：[完整需求文档](../../requirements/餐饮系统-完整需求文档.md) · [109项需求矩阵](../../requirements/餐饮系统-需求矩阵.md)。

## 零、按业务模块阅读

先查主责模块，再查表字段或接口编号。13个业务模块与A–L持久化域不是一一对应；模块可协作调用相同服务，不能据此复制实体。109项追踪结果：目标已定义32项，依赖共享/核心合同60项，尚缺专项设计17项。以上均为设计状态，非实现验收。

| 模块 | 名称 | 需求数 | 关联表数 | 关联API数 |
|---|---|---|---|---|
| MOD-01 | 桌台与就餐管理 | 8 | 13 | 40 |
| MOD-02 | 菜品与餐牌管理 | 12 | 27 | 41 |
| MOD-03 | 点餐与订单管理 | 9 | 14 | 30 |
| MOD-04 | 厨房与履约管理 | 10 | 10 | 17 |
| MOD-05 | 收银与结账管理 | 11 | 7 | 13 |
| MOD-06 | 退餐与售后管理 | 8 | 7 | 12 |
| MOD-07 | 打印、通知与设备管理 | 8 | 5 | 3 |
| MOD-08 | 班次与现金管理 | 4 | 5 | 5 |
| MOD-09 | 营业日、日结与对账管理 | 6 | 6 | 6 |
| MOD-10 | 经营报表管理 | 14 | 3 | 4 |
| MOD-11 | 专项业态管理 | 6 | 6 | 8 |
| MOD-12 | 第三方接入与离线营业 | 7 | 8 | 7 |
| MOD-13 | 营业配置与权限治理 | 6 | 7 | 7 |

逐需求表/API引用与未决缺口见API册第八节；首三模块扩展及评审结论见[评审对齐记录](../TDD-餐饮系统-数据库与API评审对齐.md)。

## 一、协议与能力边界

165个目标端点，统一JSON语义；路径为建议合同，未全部注册在当前Controller。本地适配器和远程适配器遵守相同DTO、权限和幂等。

- /biz为商家作业，/mp为顾客，/ops为平台，/internal仅服务身份。老板App/收银/厨房复用biz服务，按角色门店工位鉴权，不把平台管理员当商家收银员。
- Authorization为获准身份；X-Request-Id用于追踪。所有有副作用操作必需Idempotency-Key；作用域含entity/store/operation/key，规范payload哈希同键异参返回409。operator/entity从身份获取，不接受客户端冒充。
- 版本字段为CAS expected version；配置发布、结账冻结、日结签核还核验规则版/指纹/水位。path同名字段不得在body覆盖。GET只读，普通分页沿用PageData(records,total,page,size)，page默认1、size默认20最大100；报告专有metadata不改变统一外层。
- 输出沿用ApiResult<T>：{code:0,msg:"success",data:T}，Controller直接返回VO，由ApiResponseWrapper包装；异常沿用ErrorCode/BizException与现有全局处理器。追踪号使用既有链路，不新增必填requestId响应字段。业务处理中状态放data.status，HTTP状态/异步语义按宿主异常与包装链校验，不建立另一套HTTP-only错误规范。
- 普通分页沿用PageData(records,total,page,size)；DTO示意不得迫使现有前端改分页壳。int64金额与业务号遵循现有共享序列化，不能将全项目金额强制改字符串；新增decimal数量使用明确精度合同。UTC对外时刻与数据库LocalDateTime转换必须通过现有配置验证，date明确营业日或自然日基准。
- B端复用Realm.MERCHANT和BizContext；X-Store-No用于解析主体门店。路径/body显式门店必须与已解析的获准门店匹配，不能因过滤器回落默认店而把显式目标写到另一店。跨店报表逐店验证主体及权限集合。
- 参数表“是”为必须出现；条件字段的必填性由说明与守卫补充。写入枚举限定域值，服务端拒绝未知字段/未授权能力。通用配置data按路径对应数据库字段白名单解析，不能作为任意属性注入口。

## 二、分域接口目录

| 域 | 编号 | 方法与路径 | 功能 |
|---|---|---|---|
| A | FOOD-API-001 | GET `/biz/x/food/stores/{storeNo}/policy` | 查询营业策略 |
| A | FOOD-API-002 | POST `/biz/x/food/stores/{storeNo}/policies` | 发布营业策略 |
| A | FOOD-API-003 | PUT `/biz/x/food/stores/{storeNo}/calendar/{localDate}` | 餐饮接单日历例外 |
| B | FOOD-API-004 | POST `/biz/x/food/stores/{storeNo}/menus` | 创建menus |
| B | FOOD-API-005 | GET `/biz/x/food/stores/{storeNo}/menus/{configNo}` | 读取menus |
| B | FOOD-API-006 | POST `/biz/x/food/stores/{storeNo}/menus/{configNo}:publish` | 发布餐牌 |
| B | FOOD-API-007 | POST `/biz/x/food/stores/{storeNo}/option-groups` | 创建option-groups |
| B | FOOD-API-008 | GET `/biz/x/food/stores/{storeNo}/option-groups/{configNo}` | 读取option-groups |
| B | FOOD-API-009 | POST `/biz/x/food/stores/{storeNo}/option-groups/{configNo}:publish` | 发布option-groups |
| B | FOOD-API-010 | POST `/biz/x/food/stores/{storeNo}/bundles` | 创建bundles |
| B | FOOD-API-011 | GET `/biz/x/food/stores/{storeNo}/bundles/{configNo}` | 读取bundles |
| B | FOOD-API-012 | POST `/biz/x/food/stores/{storeNo}/bundles/{configNo}:publish` | 发布bundles |
| B | FOOD-API-013 | POST `/biz/x/food/stores/{storeNo}/stations` | 创建stations |
| B | FOOD-API-014 | GET `/biz/x/food/stores/{storeNo}/stations/{configNo}` | 读取stations |
| B | FOOD-API-015 | POST `/biz/x/food/stores/{storeNo}/stations/{configNo}:publish` | 发布工位 |
| B | FOOD-API-016 | PUT `/biz/x/food/stores/{storeNo}/dishes/{goodsNo}` | 菜品餐饮属性 |
| B | FOOD-API-017 | PUT `/biz/x/food/menus/{menuNo}/definition` | 菜单组成与时段配置 |
| B | FOOD-API-018 | PUT `/biz/x/food/bundles/{skuNo}/definition` | 套餐选择组配置 |
| B | FOOD-API-019 | PUT `/biz/x/food/option-groups/{groupNo}/items` | 配置选择项 |
| B | FOOD-API-020 | PUT `/biz/x/food/modifiers/{modifierNo}` | 配料组合配置 |
| B | FOOD-API-021 | PUT `/biz/x/food/stores/{storeNo}/sku-routes/{skuNo}` | 工位路由配置 |
| B | FOOD-API-022 | POST `/biz/x/food/stores/{storeNo}/sales-restrictions` | 沽清与恢复 |
| B | FOOD-API-023 | GET `/mp/x/food/catalog` | 顾客菜单视图 |
| C | FOOD-API-024 | POST `/biz/x/food/stores/{storeNo}/tables` | 建桌 |
| C | FOOD-API-025 | POST `/biz/x/food/tables/{tableNo}:rotate-token` | 桌码轮换 |
| C | FOOD-API-026 | POST `/mp/x/food/sessions` | 桌码换短会话 |
| C | FOOD-API-027 | POST `/biz/x/food/stores/{storeNo}/reservations` | 创建预约 |
| C | FOOD-API-028 | POST `/biz/x/food/reservations/{reservationNo}:cancel` | 预约cancel |
| C | FOOD-API-029 | POST `/biz/x/food/reservations/{reservationNo}:no-show` | 预约no-show |
| C | FOOD-API-030 | POST `/biz/x/food/reservations/{reservationNo}:arrive` | 预约arrive |
| C | FOOD-API-031 | POST `/biz/x/food/stores/{storeNo}/checks` | 开台 |
| C | FOOD-API-032 | GET `/biz/x/food/stores/{storeNo}/checks` | 台账桌台查询 |
| C | FOOD-API-033 | POST `/biz/x/food/checks/{checkNo}:transfer` | 台账transfer |
| C | FOOD-API-034 | POST `/biz/x/food/checks/{checkNo}:merge` | 合并就餐服务台账 |
| C | FOOD-API-035 | POST `/biz/x/food/checks/{checkNo}:split` | 拆分就餐服务台账 |
| C | FOOD-API-036 | POST `/biz/x/food/checks/{checkNo}:clear` | 台账clear |
| D | FOOD-API-037 | GET `/biz/x/food/carts/{cartNo}` | 购物车读取 |
| D | FOOD-API-038 | POST `/biz/x/food/stores/{storeNo}/carts` | 创建购物车 |
| D | FOOD-API-039 | PUT `/biz/x/food/carts/{cartNo}/lines/{sourceLineRef}` | 替换草稿行 |
| D | FOOD-API-040 | POST `/biz/x/food/carts/{cartNo}/lines/{sourceLineRef}:archive` | 归档购物车草稿行 |
| D | FOOD-API-041 | POST `/biz/x/food/stores/{storeNo}/quotes` | 点单报价 |
| D | FOOD-API-042 | POST `/biz/x/food/stores/{storeNo}/batches` | 提交点餐批次 |
| D | FOOD-API-043 | GET `/biz/x/food/batches/{batchNo}` | 批次结果查询 |
| E | FOOD-API-044 | GET `/biz/x/food/stores/{storeNo}/kitchen/tasks` | 工位队列 |
| E | FOOD-API-045 | POST `/biz/x/food/tasks/{taskNo}:start` | 制作任务start |
| E | FOOD-API-046 | POST `/biz/x/food/tasks/{taskNo}:ready` | 制作任务ready |
| E | FOOD-API-047 | POST `/biz/x/food/tasks/{taskNo}:serve` | 制作任务serve |
| E | FOOD-API-048 | POST `/biz/x/food/tasks/{taskNo}:hold` | 制作任务hold |
| E | FOOD-API-049 | POST `/biz/x/food/tasks/{taskNo}:fire` | 制作任务fire |
| E | FOOD-API-050 | POST `/biz/x/food/tasks/{taskNo}:urge` | 制作任务urge |
| E | FOOD-API-051 | POST `/biz/x/food/tasks/{taskNo}:void` | 制作任务void |
| E | FOOD-API-052 | POST `/biz/x/food/tasks/{taskNo}:remake` | 制作任务remake |
| E | FOOD-API-053 | POST `/biz/x/food/checks/{checkNo}/courses` | 创建course |
| E | FOOD-API-054 | POST `/biz/x/food/courses/{courseNo}:fire` | 道次叫起 |
| E | FOOD-API-055 | POST `/biz/x/food/batches/{batchNo}:handover` | 交付及配送交接 |
| F | FOOD-API-056 | POST `/biz/x/food/stores/{storeNo}/settlements` | 冻结结账范围 |
| F | FOOD-API-057 | GET `/biz/x/food/settlements/{settlementNo}` | 读取预结账 |
| F | FOOD-API-058 | POST `/biz/x/food/settlements/{settlementNo}:pay` | 结账pay |
| F | FOOD-API-059 | POST `/biz/x/food/settlements/{settlementNo}:cancel` | 结账cancel |
| F | FOOD-API-060 | POST `/biz/x/food/settlements/{settlementNo}:finalize` | 结账finalize |
| F | FOOD-API-061 | POST `/biz/x/food/settlements/{settlementNo}/adjustments` | 收费优惠调整申请 |
| G | FOOD-API-062 | POST `/biz/x/food/stores/{storeNo}/returns` | 退餐申请 |
| G | FOOD-API-063 | POST `/biz/x/food/returns/{returnNo}:execute` | 退餐审批执行 |
| G | FOOD-API-064 | GET `/biz/x/food/returns/{returnNo}` | 退餐查询 |
| H | FOOD-API-065 | POST `/biz/print/jobs` | 提交打印 |
| H | FOOD-API-066 | GET `/biz/print/jobs/{jobNo}` | 打印查询 |
| H | FOOD-API-067 | POST `/biz/print/jobs/{jobNo}:reprint` | 人工补打 |
| I | FOOD-API-068 | POST `/biz/x/food/stores/{storeNo}/cash-shifts` | 开班 |
| I | FOOD-API-069 | POST `/biz/x/food/cash-shifts/{shiftNo}/entries` | 非销售现金操作 |
| I | FOOD-API-070 | POST `/biz/x/food/cash-shifts/{shiftNo}:count` | 实盘与交班 |
| I | FOOD-API-071 | POST `/biz/x/food/cash-shifts/{shiftNo}:handover` | 接班确认 |
| I | FOOD-API-072 | POST `/biz/x/food/stores/{storeNo}/closes` | 营业日预结 |
| I | FOOD-API-073 | GET `/biz/x/food/closes/{closeNo}` | 日结查询 |
| I | FOOD-API-074 | POST `/biz/x/food/closes/{closeNo}/exceptions/{exceptionNo}:carry` | 异常结转申请 |
| I | FOOD-API-075 | POST `/biz/x/food/closes/{closeNo}:sign` | 签核日结 |
| I | FOOD-API-076 | POST `/biz/x/food/closes/{closeNo}/adjustments` | 关账后更正 |
| J | FOOD-API-077 | GET `/biz/x/food/reports/{reportCode}` | 经营指标报表 |
| J | FOOD-API-078 | POST `/biz/x/food/report-exports` | 异步报表导出 |
| J | FOOD-API-079 | GET `/biz/x/food/report-exports/{exportNo}` | 导出结果 |
| J | FOOD-API-080 | POST `/ops/x/food/report-rebuilds` | 重算投影 |
| K | FOOD-API-081 | PUT `/ops/x/food/stores/{storeNo}/capabilities` | 平台能力配置 |
| K | FOOD-API-082 | PUT `/biz/x/food/integrations/{provider}/mappings` | 外部映射 |
| K | FOOD-API-083 | GET `/ops/x/food/integration-exceptions` | 接入异常查询 |
| K | FOOD-API-084 | POST `/internal/food/sync-batches` | 边缘增量认领 |
| L | FOOD-API-085 | POST `/biz/x/food/checks/{checkNo}/entitlements` | 自助权益入场 |
| L | FOOD-API-086 | POST `/biz/x/food/entitlements/{entitlementNo}:extend` | 续时及最后点单校验 |
| L | FOOD-API-087 | POST `/biz/x/food/stores/{storeNo}/banquet-plans` | 宴会计划 |
| L | FOOD-API-088 | POST `/biz/x/food/banquet-plans/{planNo}:confirm-attendance` | 宴会人数及加席 |
| L | FOOD-API-089 | POST `/biz/x/food/stores/{storeNo}/custodies` | 寄存建账 |
| L | FOOD-API-090 | POST `/biz/x/food/custodies/{custodyNo}/entries` | 寄存取出及报损 |
| L | FOOD-API-091 | POST `/biz/x/food/settlements/{settlementNo}/room-charges` | 房账转挂 |
| C | FOOD-API-092 | GET `/biz/x/food/stores/{storeNo}/table-areas` | 桌区列表 |
| C | FOOD-API-093 | POST `/biz/x/food/stores/{storeNo}/table-areas` | 创建桌区 |
| C | FOOD-API-094 | PUT `/biz/x/food/table-areas/{areaNo}` | 编辑桌区 |
| C | FOOD-API-095 | POST `/biz/x/food/table-areas/{areaNo}:set-status` | 桌区启停 |
| C | FOOD-API-096 | GET `/biz/x/food/stores/{storeNo}/tables` | 桌台列表 |
| C | FOOD-API-097 | GET `/biz/x/food/tables/{tableNo}` | 桌台详情 |
| C | FOOD-API-098 | PUT `/biz/x/food/tables/{tableNo}` | 编辑桌台 |
| C | FOOD-API-099 | POST `/biz/x/food/tables/{tableNo}:set-status` | 桌台启停 |
| C | FOOD-API-100 | POST `/biz/x/food/tables/{tableNo}:archive` | 归档桌台 |
| C | FOOD-API-101 | POST `/biz/x/food/tables/{tableNo}:revoke-token` | 吊销桌码 |
| C | FOOD-API-102 | GET `/biz/x/food/checks/{checkNo}` | 就餐台账详情 |
| C | FOOD-API-103 | PUT `/biz/x/food/checks/{checkNo}/seats` | 修改人数与座位 |
| C | FOOD-API-104 | POST `/biz/x/food/checks/{checkNo}:combine-tables` | 并入桌台 |
| C | FOOD-API-105 | POST `/biz/x/food/tables/{tableNo}:share` | 同桌新开独立台账 |
| C | FOOD-API-106 | GET `/biz/x/food/table-operations/{operationNo}` | 桌台操作恢复查询 |
| C | FOOD-API-107 | GET `/biz/x/food/stores/{storeNo}/reservations` | 预约列表 |
| C | FOOD-API-108 | GET `/biz/x/food/checks/{checkNo}/actions` | 就餐操作历史 |
| B | FOOD-API-109 | GET `/biz/x/food/stores/{storeNo}/menus` | 餐牌列表 |
| B | FOOD-API-110 | PUT `/biz/x/food/menus/{menuNo}` | 编辑餐牌头 |
| B | FOOD-API-111 | POST `/biz/x/food/menus/{menuNo}:disable` | 餐牌停用 |
| B | FOOD-API-112 | GET `/biz/x/food/stores/{storeNo}/dishes` | 菜品列表 |
| B | FOOD-API-113 | GET `/biz/x/food/stores/{storeNo}/dishes/{goodsNo}` | 菜品详情 |
| B | FOOD-API-114 | GET `/biz/x/food/stores/{storeNo}/catalog` | 经营端可售餐牌 |
| B | FOOD-API-115 | GET `/biz/x/food/config-revisions` | 配置发布历史 |
| B | FOOD-API-116 | POST `/biz/x/food/config-revisions/{revisionNo}:republish` | 回滚配置为新修订 |
| B | FOOD-API-117 | POST `/biz/x/food/modifiers/{modifierNo}:publish` | 配料组合发布 |
| B | FOOD-API-118 | GET `/biz/x/food/stores/{storeNo}/option-groups` | 选配组列表 |
| B | FOOD-API-119 | GET `/biz/x/food/stores/{storeNo}/bundles` | 套餐列表 |
| B | FOOD-API-120 | GET `/biz/x/food/stores/{storeNo}/modifiers` | 配料列表 |
| B | FOOD-API-121 | GET `/biz/x/food/stores/{storeNo}/stations` | 工位列表 |
| B | FOOD-API-122 | GET `/biz/x/food/stores/{storeNo}/sales-restrictions` | 可售限制查询 |
| D | FOOD-API-123 | GET `/biz/x/food/stores/{storeNo}/batches` | 按台账或条件查询批次 |
| D | FOOD-API-124 | POST `/biz/x/food/batches/{batchNo}:cancel` | 取消正式批次 |
| D | FOOD-API-125 | GET `/biz/x/food/batches/{batchNo}/operations` | 批次操作恢复查询 |
| D | FOOD-API-126 | POST `/biz/x/food/carts/{cartNo}:archive` | 关闭草稿购物车 |
| D | FOOD-API-127 | GET `/mp/x/food/carts/{cartNo}` | 顾客购物车读取 |
| D | FOOD-API-128 | POST `/mp/x/food/stores/{storeNo}/carts` | 顾客创建购物车 |
| D | FOOD-API-129 | PUT `/mp/x/food/carts/{cartNo}/lines/{sourceLineRef}` | 顾客替换草稿行 |
| D | FOOD-API-130 | POST `/mp/x/food/carts/{cartNo}/lines/{sourceLineRef}:archive` | 顾客归档购物车草稿行 |
| D | FOOD-API-131 | POST `/mp/x/food/stores/{storeNo}/quotes` | 顾客点单报价 |
| D | FOOD-API-132 | POST `/mp/x/food/stores/{storeNo}/batches` | 顾客提交点餐批次 |
| D | FOOD-API-133 | GET `/mp/x/food/batches/{batchNo}` | 顾客批次结果查询 |
| F | FOOD-API-134 | GET `/mp/x/food/settlements/{settlementNo}` | 顾客读取预结账 |
| F | FOOD-API-135 | POST `/mp/x/food/settlements/{settlementNo}:pay` | 顾客结账pay |
| G | FOOD-API-136 | POST `/mp/x/food/stores/{storeNo}/returns` | 顾客退餐申请 |
| G | FOOD-API-137 | GET `/mp/x/food/returns/{returnNo}` | 顾客退餐查询 |
| C | FOOD-API-138 | GET `/mp/x/food/checks/{checkNo}` | 顾客就餐进度 |
| C | FOOD-API-139 | POST `/mp/x/food/sessions/{sessionNo}:end` | 主动结束顾客会话 |
| F | FOOD-API-140 | POST `/mp/x/food/checks/{checkNo}/settlements` | 顾客申请获准范围结账 |
| B | FOOD-API-141 | POST `/biz/x/food/stores/{storeNo}/modifiers` | 创建配料或配料组合 |
| B | FOOD-API-142 | POST `/biz/x/food/stores/{storeNo}/menu-channel-bindings` | 创建餐牌渠道绑定 |
| B | FOOD-API-143 | GET `/biz/x/food/stores/{storeNo}/menu-channel-bindings` | 渠道绑定列表 |
| B | FOOD-API-144 | GET `/biz/x/food/menu-channel-bindings/{bindingNo}` | 渠道绑定详情 |
| B | FOOD-API-145 | PUT `/biz/x/food/menu-channel-bindings/{bindingNo}` | 修改渠道绑定 |
| K | FOOD-API-146 | POST `/biz/x/food/menu-channel-bindings/{bindingNo}/syncs` | 发起餐牌渠道同步 |
| K | FOOD-API-147 | GET `/biz/x/food/catalog-syncs/{syncNo}` | 查询餐牌同步结果 |
| B | FOOD-API-148 | POST `/biz/x/food/stores/{storeNo}/recipes` | 创建配方工作头 |
| B | FOOD-API-149 | GET `/biz/x/food/stores/{storeNo}/recipes` | 配方列表 |
| B | FOOD-API-150 | GET `/biz/x/food/recipes/{recipeNo}` | 配方详情 |
| B | FOOD-API-151 | PUT `/biz/x/food/recipes/{recipeNo}/definition` | 维护配方物料与选配调整 |
| B | FOOD-API-152 | POST `/biz/x/food/recipes/{recipeNo}:publish` | 发布配方 |
| E | FOOD-API-153 | GET `/biz/x/food/stores/{storeNo}/production-consumptions` | 制作耗用列表 |
| E | FOOD-API-154 | GET `/biz/x/food/production-consumptions/{consumptionNo}` | 制作耗用详情 |
| B | FOOD-API-155 | GET `/biz/x/food/stores/{storeNo}/sku-routes/{skuNo}` | 读取SKU工位路由 |
| D | FOOD-API-156 | POST `/biz/x/food/checks/{checkNo}/cart-sessions` | 授权扫码会话加入共享购物车 |
| C | FOOD-API-157 | POST `/biz/x/food/queue-tickets` | 排队取号 |
| C | FOOD-API-158 | GET `/biz/x/food/queue-tickets` | 排队列表 |
| C | FOOD-API-159 | GET `/biz/x/food/queue-tickets/{ticketNo}` | 原排队票 |
| C | FOOD-API-160 | POST `/biz/x/food/queue-tickets:call-next` | 叫下一位 |
| C | FOOD-API-161 | POST `/biz/x/food/queue-tickets/{ticketNo}:call` | 指定叫号 |
| C | FOOD-API-162 | POST `/biz/x/food/queue-tickets/{ticketNo}:cancel` | 取消排队 |
| C | FOOD-API-163 | POST `/biz/x/food/queue-tickets/{ticketNo}:no-show` | 排队过号 |
| C | FOOD-API-164 | POST `/biz/x/food/queue-tickets/{ticketNo}:seat` | 排队转原开台 |
| C | FOOD-API-165 | POST `/biz/x/food/queue-tickets/{ticketNo}:recover-seating` | 原排队就座恢复 |

## 三、公共DTO输入输出字段

### Money

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| minor | int64 | 是 | 最小货币单位 |
| currency | string | 是 | ISO币种 |

### Selection

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| groupNo | string | 是 | 选择组 |
| sourceKind | string | 是 | SPEC_VALUE或MODIFIER |
| sourceNo | string | 是 | 编号 |
| qty | decimal | 是 | 数量 |
| configVersion | int64 | 是 | 规则版本 |

### OrderLineInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| sourceLineRef | string | 是 | 端侧稳定源行最长64 |
| menuNo | string | 是 | 当前餐牌 |
| menuItemNo | string | 是 | 餐牌菜项 |
| menuRevisionNo | string | 是 | 仍有效的冻结餐牌修订 |
| goodsNo | string | 是 | 原商品 |
| skuNo | string | 是 | 原SKU |
| qty | decimal | 是 | COUNT正整数不超过999999 |
| seatNo | string | 否 | 同台账座位 |
| courseNo | string | 否 | 当前阶段拒绝道次 |
| selections | Selection[] | 是 | 每份菜选择必需数组 |
| bundleChoices | CartBundleChoiceInput[] | 是 | 套餐选择必需数组 |

### QuoteLine

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| sourceLineRef | string | 是 | 源行 |
| skuNo | string | 是 | SKU |
| qty | decimal | 是 | 数量 |
| amount | Money | 是 | 核心金额 |
| coreBreakdownRef | string | 是 | 核心分摊引用 |

### Quote

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| quoteNo | string | 是 | 报价 |
| expiresAt | datetime | 是 | 失效 |
| scopeHash | string | 是 | 范围指纹 |
| lines | QuoteLine[] | 是 | 逐行金额 |
| total | Money | 是 | 应收 |
| capabilities | string[] | 是 | 可用能力 |

### Check

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string | 是 | 台账 |
| storeNo | string | 是 | 门店 |
| businessDate | date | 是 | 开台营业日 |
| status | string | 是 | 营业状态 |
| payMode | string | 是 | 先后付 |
| partySize | int32 | 是 | 人数 |
| tableNos | string[] | 是 | 占桌 |
| version | int64 | 是 | 版本 |

### Task

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| taskNo | string | 是 | 任务 |
| foodLineNo | string | 是 | 菜品源行 |
| stationNo | string | 是 | 工位 |
| status | string | 是 | 状态 |
| targetQty | decimal | 是 | 目标量 |
| readyQty | decimal | 是 | 出品量 |
| servedQty | decimal | 是 | 上菜量 |
| voidQty | decimal | 是 | 撤销量 |
| version | int64 | 是 | 版本 |

### Batch

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| batchNo | string | 是 | 批次 |
| checkNo | string | 否 | 原台账 |
| storeNo | string | 是 | 门店 |
| businessDate | date | 是 | 本批归期 |
| payMode | string | 是 | 先后付 |
| orderSource | string | 是 | 真实入口 |
| fulfillment | string | 是 | 履约 |
| status | string | 是 | 含UNKNOWN及CANCEL_PENDING |
| paymentRootNos | string[] | 是 | 核心支付根 |
| storeOrderNos | string[] | 是 | 门店单 |
| foodLineNos | string[] | 是 | 餐饮行 |
| version | int64 | 是 | 版本 |

### MetricCell

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| metricCode | string | 是 | 指标 |
| value | decimal | 是 | 值 |
| unit | string | 是 | 单位 |
| currency | string | 否 | 金额币种 |
| definitionVersion | int64 | 是 | 口径版本 |

### Report

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| rows | MetricCell[] | 是 | 指标行 |
| asOf | datetime | 是 | 数据截至 |
| complete | boolean | 是 | 完整性 |
| timeBasis | string | 是 | 时间基准 |
| watermark | string | 是 | 源水位 |
| total | int64 | 是 | 总行数 |
| page | int32 | 是 | 页码 |
| size | int32 | 是 | 每页条数 |

### ScopeItem

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeOrderNo | string | 是 | 核心门店单 |
| coreAllocationRef | string | 否 | 部分分摊引用 |

### Settlement

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| settlementNo | string | 是 | 结账 |
| status | string | 是 | 状态 |
| scopeHash | string | 是 | 范围 |
| due | Money | 是 | 冻结应收 |
| coreCashierRef | string | 否 | 权威收银引用 |
| version | int64 | 是 | 版本 |

### Close

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| closeNo | string | 是 | 日结 |
| businessDate | date | 是 | 营业日 |
| currency | string | 是 | 币种 |
| revision | int32 | 是 | 版本 |
| status | string | 是 | 状态 |
| balances | MetricCell[] | 是 | 快照 |
| exceptionNos | string[] | 是 | 异常 |
| watermark | string | 是 | 源水位 |
| snapshotHash | string | 是 | 结果指纹 |
| version | int64 | 是 | 对象版本 |

### Config

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configNo | string | 是 | 对象编号 |
| kind | string | 是 | 受控配置类型 |
| revision | int64 | 是 | 版本 |
| status | string | 是 | 草稿或已发布 |
| data | object | 是 | 按对应数据库字段定义且拒未知字段 |

### ReturnItem

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| foodLineNo | string | 是 | 源行 |
| componentNo | string | 否 | 组件 |
| qty | decimal | 是 | 退量 |
| disposition | string | 是 | CANCEL或LOSS或KEEP或REMAKE |

### Page

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | object[] | 是 | 该端点明确的业务VO |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 页码 |
| size | int32 | 是 | 每页条数 |

### MenuCategoryInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| categoryNo | string | 否 | 新分类省略由服务端生成，旧号必须属于本餐牌 |
| name | string | 是 | 分类名 |
| sortNo | int32 | 是 | 同级非负唯一 |
| items | MenuItemInput[] | 是 | 必需，允许空 |

### MenuItemInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| menuItemNo | string | 是 | 项号 |
| goodsNo | string | 是 | 商品 |
| sortNo | int32 | 是 | 排序 |
| enabled | boolean | 是 | 启用 |
| skuScope | string | 是 | ALL或EXPLICIT |
| skuNos | string[] | 是 | EXPLICIT必须非空且属于商品 |
| optionMode | string | 是 | INHERIT或REPLACE |
| optionGroupNos | string[] | 是 | REPLACE显式组，空表示禁选 |
| skuRecipes | MenuSkuRecipeInput[] | 是 | 特定SKU配方，必须EXPLICIT白名单 |

### WeekScheduleInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| weekday | int32 | 是 | 1至7 |
| startTime | string | 是 | 严格HH:mm |
| endTime | string | 是 | 严格HH:mm |
| endDayOffset | int32 | 是 | 0或1；时长大于0不超过24小时，周内含跨周重叠拒绝 |

### ExceptionInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| localDate | date | 是 | 本地开始日期且在适用范围内 |
| mode | string | 是 | OPEN或CLOSED |
| startTime | string | 否 | OPEN必填 |
| endTime | string | 否 | OPEN必填 |
| endDayOffset | int32 | 是 | 0或1，CLOSED为0且无时间；日期唯一 |

### ContextInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| orderSource | string | 是 | 受控来源 |
| fulfillment | string | 是 | 受控履约 |
| channelBindingNo | string | 否 | 受权渠道绑定，本地省略 |
| pricePolicyRef | string | 否 | 权威价格策略引用，不接受金额 |

### BundleGroupInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| groupNo | string | 是 | 组号 |
| name | string | 是 | 名称 |
| minQty | decimal | 是 | 最少 |
| maxQty | decimal | 是 | 最多 |
| baseSkuNo | string | 否 | 补差基准 |
| sortNo | int32 | 是 | 排序 |

### BundleChoiceInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| choiceNo | string | 是 | 选项 |
| groupNo | string | 是 | 组 |
| skuNo | string | 是 | SKU |
| unitsPerPick | decimal | 是 | 每选用量 |
| priceMode | string | 是 | INCLUDED或ADD_SKU或DIFF_BASE |
| defaultQty | decimal | 是 | 默认 |
| minQty | decimal | 是 | 下限 |
| maxQty | decimal | 是 | 上限 |
| sortNo | int32 | 是 | 排序 |

### OptionItemInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| optionItemNo | string | 是 | 稳定业务引用最长32 |
| sourceKind | string | 是 | SPEC_VALUE或MODIFIER |
| specValueNo | string | 否 | 原字典值最长64 |
| modifierNo | string | 否 | 配料 |
| maxQty | decimal | 是 | 上限 |
| defaultQty | decimal | 是 | 默认 |
| sortNo | int32 | 是 | 排序 |
| minQty | decimal | 是 | 本选项下限 |

### ComponentInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| memberNo | string | 是 | 叶子配料 |
| quantity | decimal | 是 | 正用量 |

### RouteInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| stationNo | string | 是 | 同店启用工位 |
| taskRole | string | 是 | MAKE或ASSEMBLE或PACK，关系版本只由服务端产生 |

### VersionInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string | 是 | 台账 |
| version | int64 | 是 | 版本 |

### DeliveryLineInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| foodLineNo | string | 是 | 餐饮行 |
| qty | decimal | 是 | 交付量 |

### TableArea

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| areaNo | string | 是 | 桌区号 |
| storeNo | string | 是 | 门店 |
| code | string | 是 | 编码 |
| name | string | 是 | 名称 |
| sortNo | int32 | 是 | 排序 |
| status | string | 是 | 启停 |
| version | int64 | 是 | 版本 |

### Table

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| tableNo | string | 是 | 桌号 |
| storeNo | string | 是 | 门店 |
| areaNo | string | 是 | 桌区 |
| code | string | 是 | 店内编码 |
| name | string | 是 | 名称 |
| capacity | int32 | 是 | 容量 |
| sortNo | int32 | 是 | 排序 |
| status | string | 是 | ACTIVE或DISABLED |
| turnoverState | string | 是 | IDLE或IN_USE或NEEDS_CLEANING |
| resourceNo | string | 否 | 共享资源引用，绑定写入待权威合同 |
| version | int64 | 是 | 版本 |

### Occupancy

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| occupancyNo | string | 是 | 占用号 |
| tableNo | string | 是 | 桌台 |
| checkNo | string | 是 | 获准台账 |
| seats | int32 | 是 | 占座 |
| startedAt | datetime | 是 | 开始 |

### SeatInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| seatNo | string | 否 | 创建省略由服务端发号，更新只能引用本台账座位 |
| label | string | 是 | 台账内唯一座位标签 |
| guestRef | string | 否 | 获准匿名或顾客引用 |

### Dish

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| goodsNo | string | 是 | 商品 |
| dishKind | string | 是 | SINGLE或BUNDLE或ADDON |
| prepSeconds | int32 | 是 | 制作时间 |
| presentation | DishPresentation | 是 | 覆盖展示 |
| measurement | MeasurementPolicy | 是 | 计量 |
| configVersion | int64 | 是 | 规则版本 |
| version | int64 | 是 | 并发版本 |

### DishPresentation

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| displayName | string | 否 | 餐饮别名 |
| localizedNames | LocalizedName[] | 是 | 语言名称 |
| imageRefs | string[] | 是 | 已有媒资引用 |
| tags | string[] | 是 | 标签 |
| allergenNotes | string[] | 是 | 食材过敏提示非医疗保证 |

### LocalizedName

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| locale | string | 是 | 受控语言 |
| name | string | 是 | 名称 |

### MeasurementPolicy

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| unit | string | 是 | 受控单位 |
| mode | string | 是 | COUNT或WEIGHT |
| scale | int32 | 是 | 精度 |
| allowedDeviation | decimal | 否 | 需顾客确认的允差 |

### ConfigRevision

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| revisionNo | string | 是 | 冻结发布号 |
| configKind | string | 是 | OPTION_GROUP或MODIFIER或BUNDLE或RECIPE |
| configNo | string | 是 | 对象 |
| revision | int64 | 是 | 发布修订非工作CAS |
| effectiveAt | datetime | 是 | 生效时刻 |
| contentHash | string | 是 | 规范摘要 |
| status | string | 是 | SCHEDULED或EFFECTIVE或CANCELLED |

### Menu

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| menuNo | string | 是 | 餐牌 |
| storeNo | string | 是 | 当前授权绑定门店 |
| name | string | 是 | 名称 |
| menuType | string | 是 | STANDARD或TIME或SEASON或EVENT |
| timezone | string | 是 | 当前门店策略时区 |
| priority | int32 | 是 | 优先级 |
| exclusiveGroup | string | 否 | 互斥组 |
| status | string | 是 | DRAFT或PUBLISHED或DISABLED |
| version | int64 | 是 | 并发版 |
| configVersion | int64 | 是 | 规则版；CAT-02A为工作头，不冒充已生效修订 |

### BatchDetail

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| batch | Batch | 是 | 批次 |
| lines | FoodLine[] | 是 | 冻结源行 |
| financialStatus | string | 是 | 核心资金投影 |
| fulfillmentStatus | string | 是 | 核心履约投影 |
| pendingOperationNos | string[] | 是 | 未知操作 |

### FoodLine

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| foodLineNo | string | 是 | 餐饮行 |
| sourceLineRef | string | 是 | 原源行 |
| goodsNo | string | 是 | 商品 |
| skuNo | string | 是 | SKU |
| qty | decimal | 是 | 数量 |
| unit | string | 是 | 单位 |
| seatNo | string | 否 | 座位 |
| configRevisionNos | string[] | 是 | 发布版本 |
| displaySnapshot | object | 是 | 受控历史展示 |
| coreAllocations | CoreLineAllocation[] | 是 | 受权核心分配 |

### CoreLineAllocation

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| allocationNo | string | 是 | 分配号 |
| qty | decimal | 是 | 数量 |
| amount | Money | 是 | 核心分配金额 |
| coreSnapshotHash | string | 是 | 快照摘要 |

### Cart

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cartNo | string | 是 | 购物车 |
| storeNo | string | 是 | 真实门店 |
| checkNo | string | 否 | 台账 |
| sessionNo | string | 否 | 无台会话或首建来源不构成共享车授权 |
| buyerNo | string | 否 | B端真实买家且C端始终隐去 |
| status | string | 是 | OPEN或SUBMITTING或CLOSED |
| orderSource | string | 是 | POS或WAITER或QR或H5 |
| fulfillment | string | 是 | DINE_IN或PICKUP或DELIVERY |
| scheduledAt | datetime | 否 | 本阶段非空拒绝 |
| version | int64 | 是 | 整个草稿CAS版本 |
| lines | OrderLineInput[] | 是 | 活动草稿行 |

### Operation

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作 |
| status | string | 是 | 含处理中未知失败 |
| version | int64 | 是 | 当前版本 |
| resultRef | string | 否 | 结果号 |

### TableVersionInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| tableNo | string | 是 | 桌台 |
| version | int64 | 是 | 资源版本 |

### SelectionRules

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| mutexSets | string[][] | 是 | 不可同选的optionItemNo集合 |
| requires | SelectionDependency[] | 是 | 选择依赖 |

### SelectionDependency

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| optionItemNo | string | 是 | 被选项 |
| requiresItemNos | string[] | 是 | 必须同时选项 |

### Reservation

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| reservationNo | string | 是 | 预约 |
| partySize | int32 | 是 | 人数 |
| startAt | datetime | 是 | 开始 |
| endAt | datetime | 是 | 结束 |
| status | string | 是 | 资源状态 |
| depositRef | string | 否 | 获准订金凭据 |
| checkNo | string | 否 | 到店台账 |
| version | int64 | 是 | 版本 |

### ActionLog

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| actionNo | string | 是 | 记录 |
| action | string | 是 | 动作 |
| actorRef | string | 是 | 审计操作人 |
| occurredAt | datetime | 是 | 时间 |
| reason | string | 否 | 原因 |

### CatalogItem

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| menuNo | string | 是 | 餐牌 |
| menuItemNo | string | 是 | 菜项身份，不能仅按SKU合并 |
| categoryNo | string | 是 | 分类 |
| categoryName | string | 是 | 分类名 |
| goodsNo | string | 是 | 原商品 |
| title | string | 是 | 当前原标题 |
| cover | string | 否 | 当前原封面 |
| dish | Dish | 是 | 冻结餐饮属性 |
| optionGroupNos | string[] | 是 | 冻结显式选配 |
| skus | CatalogSku[] | 是 | 当前权威及限制提示 |

### SalesRestriction

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| restrictionNo | string | 是 | 约束 |
| storeNo | string | 是 | 授权店 |
| skuNo | string | 是 | 原SKU |
| mode | string | 是 | SOLD_OUT或QUOTA或PREORDER_LIMIT |
| businessDate | date | 否 | 业务日 |
| quota | decimal | 否 | 额度 |
| reservedQty | decimal | 是 | 原子预占 |
| consumedQty | decimal | 是 | 已用 |
| startsAt | datetime | 是 | 开始 |
| expiresAt | datetime | 否 | 到期 |
| status | string | 是 | 当前时点状态含EXPIRED |
| reason | string | 是 | 原因 |
| orderSource | string | 否 | 来源过滤 |
| fulfillment | string | 否 | 履约过滤 |
| version | int64 | 是 | CAS |

### MenuSkuRecipeInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| skuNo | string | 是 | 本菜品SKU |
| recipeNo | string | 是 | 本SKU获准配方变体 |

### MenuChannelBinding

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| bindingNo | string | 是 | 服务端业务号 |
| menuNo | string | 是 | 本店餐牌 |
| storeNo | string | 是 | 门店 |
| provider | string | 是 | 渠道 |
| channelAccountRef | string | 是 | 仅经营授权端可读账号引用 |
| externalStoreRef | string | 是 | 外部门店引用 |
| status | string | 是 | DRAFT或ACTIVE或DISABLED |
| version | int64 | 是 | CAS |

### CatalogSync

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| syncNo | string | 是 | 任务 |
| bindingNo | string | 是 | 绑定 |
| revisionNo | string | 是 | 冻结修订 |
| status | string | 是 | 真实同步状态 |
| items | CatalogSyncItem[] | 是 | 分项结果 |
| version | int64 | 是 | CAS |

### CatalogSyncItem

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| lineNo | int32 | 是 | 行 |
| menuItemNo | string | 是 | 菜项 |
| status | string | 是 | 状态 |
| externalObjectRef | string | 否 | 外部编号 |
| errorCode | string | 否 | 脱敏错误 |
| confirmedRevisionNo | string | 否 | 确认修订 |

### Recipe

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| recipeNo | string | 是 | 配方 |
| storeNo | string | 是 | 门店 |
| skuNo | string | 是 | SKU |
| recipeCode | string | 是 | DEFAULT或受控变体 |
| yieldQty | decimal | 是 | 正配置份数 |
| yieldUnit | string | 是 | 固定EA，非物理单位 |
| status | string | 是 | DRAFT或ACTIVE；发布后编辑回DRAFT |
| configVersion | int64 | 是 | 内容版本 |
| version | int64 | 是 | CAS |
| lines | RecipeLineVO[] | 是 | 保存的物料事实 |
| adjustments | RecipeAdjustmentVO[] | 是 | 选配工作调整，旧回执缺失时空数组 |

### RecipeLineInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| lineNo | string | 否 | 新行省略服务端发号，既有行不能换物料 |
| itemRef | string | 是 | 32位内获准库存物料 |
| qty | decimal | 是 | 正净用量18,6 |
| unit | string | 是 | 必须等于物料baseUom |
| lossRate | decimal | 是 | 0至1间损失比例，最多6位小数 |

### RecipeAdjustmentInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| adjustmentNo | string | 否 | 创建省略服务端发号 |
| optionItemNo | string | 是 | 选项 |
| targetLineNo | string | 是 | 本配方行 |
| action | string | 是 | SET或ADD或REMOVE |
| qty | decimal | 否 | SET/ADD非负，REMOVE省略 |
| unit | string | 是 | 与目标一致 |

### ProductionConsumption

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| consumptionNo | string | 是 | 耗用号 |
| storeNo | string | 是 | 门店 |
| sourceEventNo | string | 是 | 原制作事件 |
| purpose | string | 是 | 耗用目的 |
| status | string | 是 | 真实状态 |
| businessDate | date | 是 | 营业日 |
| inventoryOperationRef | string | 否 | 库存原操作 |
| lines | ConsumptionLine[] | 是 | 明细 |

### ConsumptionLine

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| lineNo | int32 | 是 | 序号 |
| componentPath | string | 是 | 实例路径 |
| itemRef | string | 是 | 物料 |
| theoryQty | decimal | 是 | 理论量 |
| unit | string | 是 | 权威单位 |
| recipeRevisionNo | string | 是 | 历史配方 |
| status | string | 是 | 回执状态 |
| actualQty | decimal | 否 | 仅真实库存回执 |
| cost | Money | 否 | 仅真实历史总成本 |
| inventoryReceiptRef | string | 否 | 回执行号 |

### ChannelQuoteReq

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| entityNo | string | 是 | 可信服务身份解析的经营主体 |
| storeNo | string | 是 | 已授权门店 |
| channelBindingNo | string | 否 | 仅适配器认证所得渠道绑定 |
| menuRevisionNo | string | 是 | 不可变餐牌修订 |
| menuItemNo | string | 是 | 原餐牌条目不可按SKU合并 |
| sourceLineRef | string | 是 | 购买实例引用 |
| skuNo | string | 是 | 当前菜项内实际购买规格 |
| quantity | decimal | 是 | 购买份数 |
| selections | QuoteSelection[] | 是 | 按购买实例绑定的口味加料套餐选择 |
| pricePolicyRef | string | 否 | 报价权威校验的策略引用 |
| currency | string | 是 | ISO币种 |
| selectionRevisionNos | string[] | 是 | 已发布选配套餐配方修订 |
| demands | FrozenMaterialDemand[] | 是 | 唯一库存需求树 |

### ChannelQuoteResult

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| quoteRef | string | 是 | 权威报价引用 |
| policyRevisionNo | string | 是 | 权威策略版本 |
| expiresAt | datetime | 是 | 到期时需重报 |
| currency | string | 是 | 币种 |
| totalMinor | int64 | 是 | 最小货币单位金额 |
| allocations | QuoteLineAllocation[] | 是 | 核心行分摊合计等于总额 |
| stockDemandTree | FrozenMaterialDemand[] | 是 | 与受理一致且只执行一次的需求树 |

### QuoteLineAllocation

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| sourceLineRef | string | 是 | 购买实例引用 |
| componentPath | string | 是 | 完整套餐选配实例路径 |
| coreLineRef | string | 是 | 核心行分配引用 |
| qty | decimal | 是 | 数量 |
| amountMinor | int64 | 是 | 优惠后权威分摊金额 |

### FrozenMaterialDemand

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| sourceLineRef | string | 是 | 购买实例引用 |
| componentPath | string | 是 | 不能仅按SKU折叠的路径 |
| executionMode | string | 是 | SELLABLE_SKU/RECIPE_LEAVES/NONE |
| itemRef | string | 是 | 库存权威物料或成品引用 |
| recipeRevisionNo | string | 否 | 配方不可变修订 |
| qty | decimal | 是 | 按权威数量精度的毛需求 |
| unit | string | 是 | 库存权威单位 |
| conversionRevision | string | 是 | 不可变换算版本 |

### InventoryMaterialFacts

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| itemRef | string | 是 | 权威物料引用 |
| storeNo | string | 是 | 所属门店 |
| unit | string | 是 | 库存基本单位 |
| conversionRevision | string | 是 | 单位换算版本 |
| quantityScale | int32 | 是 | 数量有效小数位 |
| valuationScale | int32 | 是 | 单位成本内部精度 |
| roundingMode | string | 是 | 权威数量及成本舍入方式 |
| status | string | 是 | ACTIVE/DISABLED |

### MaterialConsumptionReq

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| entityNo | string | 是 | 可信主体 |
| storeNo | string | 是 | 获准门店 |
| productionEventNo | string | 是 | 稳定制作事实编号 |
| requestKey | string | 是 | 重试不变的业务键 |
| requestHash | string | 是 | 同键异载荷冲突检测 |
| originalReservationRef | string | 是 | 受理时原预占 |
| purpose | string | 是 | PRODUCTION/REMAKE/MATERIAL_WASTE/REVERSAL |
| originalOperationRef | string | 否 | 冲回必须引用原已确认操作 |
| demands | FrozenMaterialDemand[] | 是 | 来自受理冻结树的本次制作数量 |

### MaterialConsumptionResult

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationRef | string | 是 | 库存权威操作编号 |
| requestKey | string | 是 | 原请求键 |
| status | string | 是 | CONFIRMED/PARTIAL/UNKNOWN/FAILED |
| receipts | MaterialConsumptionReceipt[] | 是 | 逐行确认不以请求数量冒充实耗 |
| occurredAt | datetime | 是 | 权威事实发生时点 |

### MaterialConsumptionReceipt

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| sourceLineRef | string | 是 | 原购买实例 |
| componentPath | string | 是 | 原冻结路径 |
| receiptRef | string | 否 | 已确认库存流水引用 |
| status | string | 是 | CONFIRMED/UNKNOWN/FAILED |
| actualQty | decimal | 否 | 仅确认后返回实耗不得用理论量填充 |
| unit | string | 是 | 库存单位 |
| costMinor | int64 | 否 | 权威历史分摊成本未知为null |
| currency | string | 否 | 成本币种 |
| valuationRevision | string | 否 | 历史计价依据 |

### QuoteSelection

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| componentPath | string | 是 | 购买实例内完整组件路径 |
| groupRevisionNo | string | 是 | 已发布组修订 |
| optionItemNo | string | 是 | 该修订内合法选项 |
| quantity | decimal | 是 | 选择份数 |
| selectionInstanceRef | string | 是 | 同SKU不同选择仍独立的实例号 |

### ProductSummary

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| goodsNo | string | 是 | 基础商品编号 |
| entityNo | string | 是 | 已验证主体 |
| storeNo | string | 是 | 已验证门店 |
| title | string | 是 | 商品名称 |
| cover | string | 否 | 原商品图片 |
| baseOnSale | boolean | 是 | 基础上下架事实非餐饮可售 |
| auditStatus | string | 是 | 审核事实 |
| version | int64 | 是 | 原商品版本 |

### ProductSku

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| skuNo | string | 是 | 基础规格编号 |
| spec | string | 否 | 规格展示 |
| version | int64 | 是 | 基础规格版本 |

### ProductCatalog

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| goodsNo | string | 是 | 基础商品编号 |
| entityNo | string | 是 | 已验证主体 |
| storeNo | string | 是 | 已验证门店 |
| title | string | 是 | 商品名称 |
| cover | string | 否 | 原商品图片 |
| baseOnSale | boolean | 是 | 基础上下架事实非餐饮可售 |
| auditStatus | string | 是 | 审核事实 |
| version | int64 | 是 | 原商品版本 |
| skus | ProductSku[] | 是 | 原商品规格事实，不含金额库存 |

### DishCatalogItem

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| dish | Dish | 是 | 餐饮属性 |
| product | ProductSummary | 是 | 当前门店商品权威图文与管理状态 |

### DishOptionItem

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| optionItemNo | string | 是 | 当前组条目 |
| sourceKind | string | 是 | SPEC_VALUE或MODIFIER |
| specValueNo | string | 否 | 原口味值 |
| modifierNo | string | 否 | 餐饮配料 |
| minQty | decimal | 是 | 下限 |
| maxQty | decimal | 是 | 上限 |
| defaultQty | decimal | 是 | 默认量 |
| sortNo | int32 | 是 | 顺序 |

### DishOptionData

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| name | string | 是 | 组名称 |
| minQty | decimal | 是 | 组下限 |
| maxQty | decimal | 是 | 组上限 |
| publishedRevisionNo | string | 否 | 已发布引用独立于当前草稿 |
| selectionRules | object | 是 | 存储的管理选择规则非执行授权 |
| items | DishOptionItem[] | 是 | 真实当前条目 |

### DishOptionGroup

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configNo | string | 是 | 选择组编号 |
| kind | string | 是 | 固定OPTION_GROUP |
| revision | int64 | 是 | 当前管理草稿并发版本 |
| status | string | 是 | 管理状态 |
| data | DishOptionData | 是 | 实际工作头与条目 |

### ModifierComponentInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| memberNo | string | 是 | 单项配料业务号 |
| quantity | decimal | 是 | 正组合份数DECIMAL12,3 |

### ModifierComponentVO

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| componentNo | string | 是 | 服务端生成明细业务号 |
| memberNo | string | 是 | 单项配料 |
| quantity | decimal | 是 | 每组合份数 |

### ModifierConfigData

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| name | string | 是 | 名称 |
| salesSkuNo | string | 否 | 原计价SKU |
| publishedRevisionNo | string | 否 | 已有发布引用 |
| components | ModifierComponentVO[] | 是 | 当前工作成员 |

### ModifierConfigVO

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configNo | string | 是 | 配料业务号 |
| kind | string | 是 | ADDON或COMBO |
| version | int64 | 是 | 工作头CAS |
| status | string | 是 | DRAFT或ACTIVE或DISABLED |
| data | ModifierConfigData | 是 | 管理定义 |

### PublishConfigReq

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 工作头CAS整数 |
| effectiveAt | datetime | 是 | 带时区毫秒精度 |
| approvalRef | string | 否 | 审批业务引用 |

### ConfigRevisionContent

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| publication | ConfigRevision | 是 | 修订元数据 |
| definitionSnapshot | string | 是 | 仅内部读取的完整JSON冻结文档 |

### PublicationDependency

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| revisionNo | string | 是 | 依赖修订 |
| configKind | string | 是 | 类型 |
| configNo | string | 是 | 对象 |
| revision | int64 | 是 | 单调修订 |
| contentHash | string | 是 | 规范摘要 |

### CreateBundleReq

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| skuNo | string | 是 | 原主SKU业务号 |

### CreateBundleResultVO

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configNo | string | 是 | 原SKU |
| version | int64 | 是 | 0 |
| status | string | 是 | DRAFT |

### ReplaceBundleDefinitionReq

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 严格非负整数CAS |
| groups | BundleGroupInput[] | 是 | 最多30 |
| choices | BundleChoiceInput[] | 是 | 最多100 |

### BundleMutationVO

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configNo | string | 是 | 原SKU |
| version | int64 | 是 | 更新后工作头CAS |

### BundleConfigVO

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configNo | string | 是 | 原SKU |
| version | int64 | 是 | 工作头CAS |
| status | string | 是 | DRAFT或ACTIVE或DISABLED |
| data | BundleConfigData | 是 | 真实管理定义 |

### BundleConfigData

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configVersion | int64 | 是 | 定义变更次数非发布修订 |
| publishedRevisionNo | string | 否 | 管理提示 |
| groups | BundleGroupInput[] | 是 | 有序组 |
| choices | BundleChoiceInput[] | 是 | 有序选项 |

### GetBundleVO

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| config | BundleConfigVO | 是 | 真实管理定义 |

### ListBundlesQuery

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |

### BundlePublicationSnapshot

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| contentSchema | int32 | 是 | 固定2 |
| configKind | string | 是 | 固定BUNDLE |
| configNo | string | 是 | 主SKU |
| sourceVersion | int64 | 是 | 严格整数工作CAS |
| bundle | BundleConfigVO | 是 | 完整冻结定义 |
| skuFacts | SkuIdentityFact[] | 是 | 所有显式SKU身份版本 |
| approvalRef | string | 否 | 仅审批引用 |

### SkuIdentityFact

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| skuNo | string | 是 | 原SKU |
| goodsNo | string | 是 | 原商品 |
| entityNo | string | 是 | 权威经营主体 |
| storeNo | string | 是 | 验真门店 |
| version | int64 | 是 | 严格非负整数身份版本 |

### RecipeLineVO

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| lineNo | string | 是 | 行号 |
| itemRef | string | 是 | 物料 |
| qty | decimal | 是 | 净用量 |
| unit | string | 是 | 保存基础单位 |
| lossRate | decimal | 是 | 损失比例 |
| materialDefinitionHash | string | 是 | 真实物料定义摘要，不是CAS或换算版本 |

### RecipeAdjustmentVO

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| adjustmentNo | string | 是 | 服务端稳定调整编号 |
| optionItemNo | string | 是 | 选项 |
| targetLineNo | string | 是 | 本配方行 |
| action | string | 是 | SET或ADD或REMOVE |
| qty | decimal | 否 | SET/ADD非负，REMOVE省略 |
| unit | string | 是 | 与目标一致 |
| optionIdentityHash | string | 是 | 服务端保存来源身份摘要，非物理行号或CAS |

### RecipePublicationSnapshot

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| contentSchema | int32 | 是 | 固定3 |
| configKind | string | 是 | RECIPE |
| configNo | string | 是 | 配方号 |
| sourceVersion | int64 | 是 | 工作CAS |
| recipe | Recipe | 是 | 完整工作定义 |
| skuFacts | SkuIdentityFact[] | 是 | 根规格 |
| materialFacts | MaterialFact[] | 是 | 真实基础单位物料 |
| optionGroups | RecipeOptionGroupSnapshot[] | 是 | 完整组选项依赖 |
| approvalRef | string | 否 | 审批依据 |

### MaterialFact

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| itemId | string | 是 | 原库存物料 |
| entityNo | string | 是 | 所属主体 |
| name | string | 是 | 来源名称 |
| baseUom | string | 是 | 基础单位 |
| weighed | int32 | 是 | 原计量标记 |
| dataSource | string | 是 | 原来源 |
| unitSupport | string | 是 | BASE_ONLY |
| postingSupport | string | 是 | INTEGER_ONLY |
| definitionHash | string | 是 | 语义摘要非CAS |

### RecipeOptionGroupSnapshot

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| goodsNo | string | 是 | 实际根商品绑定 |
| publication | PublicationDependency | 是 | 不可变依赖 |
| definition | object | 是 | 完整schema1组选项文档 |

### CreateMenuReq

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| name | string | 是 | 1到64文本 |
| menuType | string | 是 | STANDARD或TIME或SEASON或EVENT |
| timezone | string | 是 | 门店当前生效IANA时区 |
| priority | int32 | 是 | 必需 |
| exclusiveGroup | string | 否 | 1到32文本；禁止自报主体/门店/状态/价格 |

### UpdateMenuReq

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| name | string | 是 | 1到64文本 |
| menuType | string | 是 | STANDARD或TIME或SEASON或EVENT |
| timezone | string | 是 | 门店当前生效IANA时区 |
| priority | int32 | 是 | 必需 |
| exclusiveGroup | string | 否 | 1到32文本；禁止自报主体/门店/状态/价格 |
| version | int64 | 是 | 非负且小于Long.MAX_VALUE |

### MenuSchedule

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| validFrom | date | 否 | 来源起始日含首日 |
| validTo | date | 否 | 来源起始日含末日 |
| weekSchedules | WeekScheduleInput[] | 是 | 必需 |
| exceptions | ExceptionInput[] | 是 | 必需，日期覆盖周规则 |

### ReplaceMenuScheduleReq

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| section | string | 是 | 本批仅SCHEDULE |
| version | int64 | 是 | 草稿CAS |
| weekSchedules | WeekScheduleInput[] | 是 | 必需，允许[]不允许null |
| exceptions | ExceptionInput[] | 是 | 必需，允许[]不允许null |
| validFrom | date | 否 | 含首日 |
| validTo | date | 否 | 含末日；完整storeNos/contexts/categories分支尚未实现，不接受这些字段 |

### MenuComposition

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| categories | MenuCategoryInput[] | 是 | 显式空清空组成，不改时段和上下文 |

### Station

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configNo | string | 是 | 工位号 |
| version | int64 | 是 | 工作CAS |
| status | string | 是 | 工作状态 |
| storeNo | string | 是 | 授权门店 |
| name | string | 是 | 名称 |
| stationType | string | 是 | HOT或COLD或BAR或PACK |
| enabled | boolean | 是 | 启用 |
| publishedRevisionNo | string | 否 | 历史发布指针 |

### StationRoutes

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| skuNo | string | 是 | 原SKU |
| version | int64 | 是 | 整个店SKU路由版本，清空仍保留计数 |
| routes | RouteInput[] | 是 | 当前有效路由 |

### Catalog

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string | 是 | 获准店 |
| orderSource | string | 是 | 可信来源 |
| fulfillment | string | 是 | 履约 |
| asOf | datetime | 是 | 服务端当前时点 |
| menus | CatalogMenu[] | 是 | 生效发布 |
| items | CatalogItem[] | 是 | 保留菜项身份 |

### CatalogMenu

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| menuNo | string | 是 | 餐牌 |
| revisionNo | string | 是 | 冻结发布号 |
| revision | int64 | 是 | 发布序号 |
| name | string | 是 | 冻结名称 |
| priority | int32 | 是 | 优先级 |
| exclusiveGroup | string | 否 | 互斥组 |
| dependencies | CatalogDependency[] | 是 | 冻结依赖 |

### CatalogDependency

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| revisionNo | string | 是 | 冻结发布号 |
| configKind | string | 是 | 类型 |
| configNo | string | 是 | 对象 |
| revision | int64 | 是 | 序号 |
| contentHash | string | 是 | 校验 |
| optionGroup | DishOptionGroup | 否 | 公开选配定义 |
| modifier | CatalogModifier | 否 | 公开小料元数据 |
| bundle | BundleConfigVO | 否 | 公开套餐选项 |
| recipe | CatalogRecipe | 否 | 仅配方身份，无原料和耗用 |
| specValues | CatalogSpecValue[] | 是 | 冻结选项标签及版本 |

### CatalogSku

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| skuNo | string | 是 | 原SKU |
| spec | string | 否 | 当前规格 |
| recipeNo | string | 否 | 冻结配方 |
| priceMinor | int64 | 否 | 真实原CN价格，未知为null，不伪造0 |
| currency | string | 否 | 已知CN价为CNY |
| pricingStatus | string | 是 | KNOWN或UNAVAILABLE或UNKNOWN |
| available | int32 | 否 | 仅权威PLATFORM物理可售件数，未知为null |
| stockStatus | string | 是 | KNOWN或UNKNOWN或UNAVAILABLE |
| onSale | boolean | 是 | 当前原上架及审核 |
| restricted | boolean | 是 | 当前餐饮限制 |
| remainingQuota | decimal | 否 | 剩余额度 |
| transactable | boolean | 是 | 保守当前展示提示，不是报价或下单承诺 |
| requiresSelectionQuote | boolean | 是 | 选配小料套餐配方必须重新权威报价 |
| reason | string | 否 | 不可成交原因 |

### RepublishConfigReq

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 当前工作CAS |
| effectiveAt | datetime | 是 | 新生效时刻 |
| reason | string | 是 | 必需原因 |
| approvalRef | string | 否 | 审批引用 |

### CatalogModifier

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configNo | string | 是 | 小料号 |
| version | int64 | 是 | 版本 |
| status | string | 是 | 状态 |
| name | string | 是 | 名称 |
| kind | string | 是 | ADDON或COMPONENT |
| salesSkuNo | string | 否 | 销售SKU |
| salesOffer | CatalogSku | 否 | 当前原SKU展示提示，不含组件用量 |

### CatalogRecipe

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| recipeNo | string | 是 | 配方号 |
| skuNo | string | 是 | 原SKU |
| storeNo | string | 是 | 授权门店 |
| version | int64 | 是 | 版本 |
| configVersion | int64 | 是 | 配置版，不公开原料配比 |

### CatalogSpecValue

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| valueNo | string | 是 | 选项 |
| dimNo | string | 是 | 维度 |
| label | string | 是 | 公开标签 |
| dimName | string | 是 | 公开维度名 |
| valueType | string | 是 | 值类型 |
| usageType | string | 是 | 用途 |
| valueVersion | int64 | 是 | 值版本 |
| dimVersion | int64 | 是 | 维度版本 |

### CartBundleChoiceInput

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| choiceNo | string | 是 | 冻结套餐选项 |
| qty | decimal | 是 | 每份套餐选择数量 |

### QueueTicketView

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| ticketNo | string | 是 | 原排队号 |
| businessDate | date | 是 | 营业日 |
| partySize | int32 | 是 | 人数 |
| status | string | 是 | 真实排队状态 |
| calledAt | datetime | 否 | 叫号时间 |
| checkNo | string | 否 | 原开台回执 |
| version | int64 | 是 | CAS |
| notificationStatus | string | 是 | 真实通知状态 |

### QueueTicketPage

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | QueueTicketView[] | 是 | 当前门店排队票 |
| total | int64 | 是 | 同条件总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

## 四、逐接口输入输出

### FOOD-API-001 · 查询营业策略

`GET /biz/x/food/stores/{storeNo}/policy`

权限与守卫：门店范围与对象权限；仅授权读取，无写入副作用。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| at | datetime | 否 | 查询时点 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| policyNo | string | 是 | 策略 |
| timezone | string | 是 | IANA时区 |
| cutoverMinute | int32 | 是 | 切点 |
| payPolicy | string | 是 | 先后付 |
| capabilities | string[] | 是 | 启用能力 |
| version | int64 | 是 | 版本 |

### FOOD-API-002 · 发布营业策略

`POST /biz/x/food/stores/{storeNo}/policies`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等；version首次0、追加递增且effectiveFrom晚于最新发布；能力与审批未交付前非空拒绝。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| timezone | string | 是 | IANA时区 |
| cutoverMinute | int32 | 是 | 0至1439 |
| effectiveFrom | datetime | 是 | 生效 |
| payPolicy | string | 是 | PREPAY或POSTPAY |
| capabilities | string[] | 是 | 能力码 |
| approvalRef | string | 否 | 审批 |
| version | int64 | 是 | 最新发布版，首次0，含未来版 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作号 |
| status | string | 是 | 操作状态 |
| version | int64 | 是 | 对象版本 |
| resultRef | string | 否 | 结果业务号 |

### FOOD-API-003 · 餐饮接单日历例外

`PUT /biz/x/food/stores/{storeNo}/calendar/{localDate}`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等；不是门店openHours编辑入口，不修改共享营业时间；localDate按策略营业日切点；CLOSED关闭该营业日，OPEN与原营业时间取交集；policy_no仅追溯。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| localDate | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| mode | string | 是 | OPEN或CLOSED |
| intervals | string[] | 是 | 本地起止时段 |
| version | int64 | 是 | 版本 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作号 |
| status | string | 是 | 操作状态 |
| version | int64 | 是 | 对象版本 |
| resultRef | string | 否 | 结果业务号 |

### FOOD-API-004 · 创建menus

`POST /biz/x/food/stores/{storeNo}/menus`

权限与守卫：原GOODS权限/BizContext/StoreDirectory与实际同主体门店绑定；未知字段拒绝；写操作CAS/幂等/审计/Outbox同事务；timezone与当前生效门店策略一致；只写单店无override头，多店编辑409；完成回执优先重放。

能力依赖：已实现餐牌工作定义管理；发布与历史读取独立，不能将草稿当可售。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| name | string | 是 | 菜单名 |
| menuType | string | 是 | STANDARD或TIME或SEASON或EVENT |
| timezone | string | 是 | 时区 |
| priority | int32 | 是 | 优先级 |
| exclusiveGroup | string | 否 | 互斥组 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configNo | string | 是 | 配置号 |
| version | int64 | 是 | 草稿版本 |
| status | string | 是 | DRAFT |

### FOOD-API-005 · 读取menus

`GET /biz/x/food/stores/{storeNo}/menus/{configNo}`

权限与守卫：原GOODS权限/BizContext/StoreDirectory与实际同主体门店绑定；未知字段拒绝；写操作CAS/幂等/审计/Outbox同事务；timezone与当前生效门店策略一致；只写单店无override头，多店编辑409；完成回执优先重放。

能力依赖：已实现餐牌工作定义管理；发布与历史读取独立，不能将草稿当可售。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| configNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| config | Menu | 是 | 当前工作头 |
| schedule | MenuSchedule | 否 | 旧提供方缺省为未知 |
| composition | MenuComposition | 否 | 分类菜项SKU及选配配方 |
| contexts | ContextInput[] | 是 | 明确来源及履约 |

### FOOD-API-006 · 发布餐牌

`POST /biz/x/food/stores/{storeNo}/menus/{configNo}:publish`

权限与守卫：真实GOODS及获准门店；MENU开关独立；外部原身份先于本地事务核验；店级仲裁锁/CAS/actor绑定幂等；冻结定义与依赖、原商品身份且不含价格库存；完成回执先重放；历史内容不变；CHANNEL互斥按冻结的主体/门店/提供方/渠道账号/外部门店身份比较，不能按餐牌私有bindingNo判离散。

能力依赖：ALL-03/04已实现，生效时间及门店范围内选择冻结修订；不表示订单厨房事务已接入。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| configNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 草稿版本 |
| effectiveAt | datetime | 是 | 生效时刻 |
| approvalRef | string | 否 | 审批 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| publication | ConfigRevision | 是 | 冻结发布修订 |

### FOOD-API-007 · 创建option-groups

`POST /biz/x/food/stores/{storeNo}/option-groups`

权限与守卫：原商品权限及当前主体门店校验；主体共享配置；服务端编号与DRAFT/0；数量DECIMAL(18,6)非负且min≤max；归一化幂等、审计与Outbox同事务；拒绝未知字段。

能力依赖：已实现草稿管理，不代表选项维护或发布。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| name | string | 是 | 组名 |
| minQty | decimal | 是 | 非负下限 |
| maxQty | decimal | 是 | 不小于下限 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configNo | string | 是 | 配置号 |
| version | int64 | 是 | 草稿版本 |
| status | string | 是 | DRAFT |

### FOOD-API-008 · 读取option-groups

`GET /biz/x/food/stores/{storeNo}/option-groups/{configNo}`

权限与守卫：原商品权限及当前主体门店校验；仅返回当前主体共享工作头与真实有序条目；跨主体404；拒绝查询参数；不作为已发布授权。

能力依赖：已实现管理查询，不代表可售。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| configNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| config | Config | 是 | 具体配置，类型由路径确定 |

### FOOD-API-009 · 发布option-groups

`POST /biz/x/food/stores/{storeNo}/option-groups/{configNo}:publish`

权限与守卫：已实现；原GOODS及门店目录权限，主体共享头；完整聚合及可满足性校验；按门店和父生效时点冻结不可变依赖；事务外原SKU/规格权威预读，锁头重验一致；CAS/幂等/审计/Outbox原子；禁止历史回填。

能力依赖：已实现OPTION_GROUP/MODIFIER配置发布；不授予价格库存或可售权限。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| configNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 工作头CAS |
| effectiveAt | datetime | 是 | 带时区毫秒精度生效时刻 |
| approvalRef | string | 否 | 审批业务引用 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| publication | ConfigRevision | 是 | 冻结发布修订 |

### FOOD-API-010 · 创建bundles

`POST /biz/x/food/stores/{storeNo}/bundles`

权限与守卫：已实现；原GOODS及当前门店目录；原SKU身份事务外验真，主SKU唯一且不得已为其他套餐成员或补差基准；CAS/幂等回执/审计/Outbox；不接受自报主体或价格库存。

能力依赖：已实现套餐空草稿登记；冻结发布API012及成交报价后续。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| skuNo | string | 是 | 主SKU |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configNo | string | 是 | 主SKU业务号 |
| version | int64 | 是 | 草稿CAS |
| status | string | 是 | DRAFT |

### FOOD-API-011 · 读取bundles

`GET /biz/x/food/stores/{storeNo}/bundles/{configNo}`

权限与守卫：已实现；原GOODS及当前门店目录；主体共享工作头，跨主体404；拒绝查询参数，不依赖上游在线。

能力依赖：已实现草稿查询，不代表门店可售。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| configNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| config | BundleConfigVO | 是 | 真实套餐工作头和有序组/选项 |

### FOOD-API-012 · 发布bundles

`POST /biz/x/food/stores/{storeNo}/bundles/{configNo}:publish`

权限与守卫：已实现；原GOODS及门店目录；完整非空可行套餐、SKU身份和禁止嵌套校验；事务外权威预读后SERIALIZABLE锁头重验；schema2冻结完整定义及SKU身份版本，CAS/幂等/审计/Outbox原子；禁止回填历史。

能力依赖：已实现；shop.food.bundle-publication-enabled默认false，先升级schema2读端再开发布；不授予报价库存或可售权限。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| configNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 严格非负整数CAS |
| effectiveAt | datetime | 是 | 带时区毫秒精度 |
| approvalRef | string | 否 | 仅审批业务引用 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| publication | ConfigRevision | 是 | 冻结发布修订 |

### FOOD-API-013 · 创建stations

`POST /biz/x/food/stores/{storeNo}/stations`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：ALL-04已实现店内工位管理；不派发厨房订单。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| name | string | 是 | 工位名 |
| stationType | string | 是 | HOT或COLD或BAR或PACK |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configNo | string | 是 | 配置号 |
| version | int64 | 是 | 草稿版本 |
| status | string | 是 | DRAFT |

### FOOD-API-014 · 读取stations

`GET /biz/x/food/stores/{storeNo}/stations/{configNo}`

权限与守卫：门店范围与对象权限；仅授权读取，无写入副作用。

能力依赖：ALL-04已实现。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| configNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| config | Station | 是 | 授权工位工作定义及发布指针 |

### FOOD-API-015 · 发布工位

`POST /biz/x/food/stores/{storeNo}/stations/{configNo}:publish`

权限与守卫：真实GOODS及获准门店；STATION开关独立；外部原身份先于本地事务核验；店级仲裁锁/CAS/actor绑定幂等；冻结定义与依赖、原商品身份且不含价格库存；完成回执先重放；历史内容不变。

能力依赖：ALL-03/04已实现，生效时间及门店范围内选择冻结修订；不表示订单厨房事务已接入。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| configNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 草稿版本 |
| effectiveAt | datetime | 是 | 生效时刻 |
| approvalRef | string | 否 | 审批 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| publication | ConfigRevision | 是 | 冻结发布修订 |

### FOOD-API-016 · 菜品餐饮属性

`PUT /biz/x/food/stores/{storeNo}/dishes/{goodsNo}`

权限与守卫：商品同主体；缺version仅当餐饮扩展不存在时创建，存在则冲突；传version只允许CAS修改已存在扩展；基础图文价格库存仍在商品域；当前仅COUNT/EA整数份数展示；自定义imageRefs/WEIGHT待权威合同，明确拒绝。

能力依赖：已实现餐饮属性草稿维护；COUNT/EA展示、原商品图片沿用；不含称重、自定义媒资及可售发布。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| goodsNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| dishKind | string | 是 | SINGLE或BUNDLE或ADDON |
| prepSeconds | int32 | 是 | 非负 |
| presentation | DishPresentation | 是 | 餐饮展示覆盖 |
| measurement | MeasurementPolicy | 是 | 计量规则 |
| version | int64 | 否 | 创建时不传，修改时必须CAS |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| dish | Dish | 是 | 菜品 |

### FOOD-API-017 · 菜单组成与时段配置

`PUT /biz/x/food/menus/{menuNo}/definition`

权限与守卫：三个分段只替换自身；真实同主体当前店；CAS及actor绑定幂等审计Outbox同事务；原Goods/SKU事实先在本地事务外批量核验；分类及菜项ID不能借用别的餐牌；ALL/EXPLICIT、INHERIT/REPLACE严格区分；配方同店同SKU；时段按来源日期覆盖、左闭右开；不改历史。

能力依赖：ALL-01/02已实现工作组成、时段及上下文；发布另验依赖和冲突，不提供价格或成交授权。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| menuNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| section | string | 是 | SCHEDULE或COMPOSITION或CONTEXT显式互斥分段 |
| version | int64 | 是 | 当前草稿CAS |
| weekSchedules | WeekScheduleInput[] | 否 | SCHEDULE必需 |
| exceptions | ExceptionInput[] | 否 | SCHEDULE必需 |
| validFrom | date | 否 | SCHEDULE来源起日 |
| validTo | date | 否 | SCHEDULE来源末日 |
| categories | MenuCategoryInput[] | 否 | COMPOSITION必需，空清空 |
| contexts | ContextInput[] | 否 | CONTEXT必需，空清空 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| menu | Menu | 是 | 新工作CAS |
| schedule | MenuSchedule | 否 | 仅SCHEDULE分段回执 |
| composition | MenuComposition | 否 | 仅COMPOSITION分段回执 |
| contexts | ContextInput[] | 否 | 仅CONTEXT分段回执 |

### FOOD-API-018 · 套餐选择组配置

`PUT /biz/x/food/bundles/{skuNo}/definition`

权限与守卫：已实现；原GOODS及当前门店；原主SKU/成员/补差基准身份事务外验真；SERIALIZABLE双向禁止嵌套；业务号不可盗用或切换身份；数量精度/默认/容量校验，DIFF_BASE必须有基准；CAS/幂等/审计/Outbox原子；只替换工作定义。

能力依赖：已实现固定/自选套餐草稿；金额由原报价权威确定，正式库存展开后续。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| skuNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 严格非负整数CAS |
| groups | BundleGroupInput[] | 是 | 完整组集合最多30 |
| choices | BundleChoiceInput[] | 是 | 完整选项集合最多100 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configNo | string | 是 | SKU |
| version | int64 | 是 | 新版本 |

### FOOD-API-019 · 配置选择项

`PUT /biz/x/food/option-groups/{groupNo}/items`

权限与守卫：原GOODS权限、可信主体及当前门店；V3规格引用64字符；原规格ENUM或主体有效配料工作头；循环/互斥矛盾/默认约束拒绝；CAS幂等审计Outbox同事务；保留外店及无法确认归属的原绑定；仅修改工作头。

能力依赖：已实现工作头选项与当前门店商品绑定，不代表发布或可售。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| groupNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| items | OptionItemInput[] | 是 | 最多100 |
| goodsNos | string[] | 是 | 当前获准店绑定完整集合最多100 |
| selectionRules | SelectionRules | 是 | 互斥依赖结构 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configNo | string | 是 | 组 |
| version | int64 | 是 | 新版本 |

### FOOD-API-020 · 配料组合配置

`PUT /biz/x/food/modifiers/{modifierNo}`

权限与守卫：已实现草稿管理；同主体CAS更新，kind不可改变；COMBO仅同主体有效ADDON成员，拒重复/嵌套；仅组合份数，不承担物料单位换算；SKU原权威主体门店校验。

能力依赖：已实现草稿管理；发布与收费成交待后续P1/P3。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| modifierNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| name | string | 是 | 名称 |
| kind | string | 是 | ADDON或COMBO创建后不可改变 |
| salesSkuNo | string | 否 | 原计价SKU可显式清空 |
| components | ModifierComponentInput[] | 是 | ≤100成员及正数量 |
| version | int64 | 是 | 工作头CAS |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configNo | string | 是 | 配料 |
| version | int64 | 是 | 新版本 |

### FOOD-API-021 · 工位路由配置

`PUT /biz/x/food/stores/{storeNo}/sku-routes/{skuNo}`

权限与守卫：原SKU同主体当前店；只允许同店启用工位；店级仲裁行锁；清空墓碑保留路由计数防ABA；actor绑定幂等及审计Outbox同事务；不改实存、不派发历史厨房任务。

能力依赖：ALL-04已实现路由配置，派工读取冻结发布需订单阶段集成。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| skuNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 整个店SKU关系CAS，首次0 |
| routes | RouteInput[] | 是 | 最多100个stationNo及taskRole，空清空，不接受客户端routeVersion |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作号 |
| status | string | 是 | 操作状态 |
| version | int64 | 是 | 对象版本 |
| resultRef | string | 否 | 结果业务号 |

### FOOD-API-022 · 沽清与恢复

`POST /biz/x/food/stores/{storeNo}/sales-restrictions`

权限与守卫：店SKU仲裁行锁；CLEAR只清指定版本；完成回执优先；过期按服务器时点恢复；餐饮额度通过既有OnlineQuotaPort本地原子预占消费释放一次，不重复建立实存账；UNKNOWN不释放；orderSource非空只允许POS/WAITER/KIOSK/QR/H5/APP/CHANNEL，fulfillment非空只允许DINE_IN/PICKUP/DELIVERY，CHANNEL显式履约只允许DELIVERY；null保留通配语义，非法值不得写审计/Outbox。

能力依赖：ALL-05已实现餐饮限制及在线额度；不表示物理库存或订单报价可用。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| skuNo | string | 是 | 同店原SKU |
| mode | string | 是 | SOLD_OUT或QUOTA或PREORDER_LIMIT或CLEAR |
| restrictionNo | string | 否 | 编辑或CLEAR对象 |
| version | int64 | 否 | 已有对象必需CAS |
| quota | decimal | 否 | 额度模式正decimal(18,6)，物理件数与交易计量另验 |
| businessDate | date | 否 | 适用营业日 |
| startsAt | datetime | 否 | 缺省当前 |
| expiresAt | datetime | 否 | 到期 |
| reason | string | 是 | 原因 |
| orderSource | string | 否 | 受控来源 |
| fulfillment | string | 否 | 履约 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| restrictionNo | string | 是 | 约束 |
| storeNo | string | 是 | 授权店 |
| skuNo | string | 是 | 原SKU |
| mode | string | 是 | SOLD_OUT或QUOTA或PREORDER_LIMIT |
| businessDate | date | 否 | 业务日 |
| quota | decimal | 否 | 额度 |
| reservedQty | decimal | 是 | 原子预占 |
| consumedQty | decimal | 是 | 已用 |
| startsAt | datetime | 是 | 开始 |
| expiresAt | datetime | 否 | 到期 |
| status | string | 是 | 当前时点状态含EXPIRED |
| reason | string | 是 | 原因 |
| orderSource | string | 否 | 来源过滤 |
| fulfillment | string | 否 | 履约过滤 |
| version | int64 | 是 | CAS |

### FOOD-API-023 · 顾客菜单视图

`GET /mp/x/food/catalog`

权限与守卫：服务器当前时间；仅已发布冻结定义及其时段来源履约；每菜项保留身份；当前原商品审核上架、原CN/CNY门店及特价和餐饮限制重新读取；未知价格及库存为null且不可成交；复杂选配/套餐/配方需权威报价；公开及顾客目录不暴露channelAccount账号、外部门店、pricePolicyRef或内部依赖快照，渠道上下文不能由用户伪造；不接受任意at未来预览。

能力依赖：ALL-06已实现保守当前展示；PLATFORM物理库存可明确读取，DUAL/INVENTORY或多市场歧义为UNKNOWN；没有报价保留禁成交，不保证下单。

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string | 是 | 公开门店或原顾客会话门店 |
| orderSource | string | 是 | H5公开或QR原顾客会话，不能伪造CHANNEL |
| fulfillment | string | 是 | H5仅PICKUP或DELIVERY，QR仅DINE_IN |
| sessionNo | string | 否 | QR必需，另需X-Food-Session头原会话凭据 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string | 是 | 获准店 |
| orderSource | string | 是 | 可信来源 |
| fulfillment | string | 是 | 履约 |
| asOf | datetime | 是 | 服务端当前时点 |
| menus | CatalogMenu[] | 是 | 生效发布 |
| items | CatalogItem[] | 是 | 保留菜项身份 |

### FOOD-API-024 · 建桌

`POST /biz/x/food/stores/{storeNo}/tables`

权限与守卫：主体门店及启用桌区一致；锁桌区仲裁行后创建；同店code精确唯一且归档后不复用；初始ACTIVE/IDLE、version0；不接受客户端占用状态；非空resourceNo在权威查询合同未齐时拒绝。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| areaNo | string | 是 | 桌区 |
| code | string | 是 | 门店唯一桌台编码 |
| name | string | 是 | 名称 |
| capacity | int32 | 是 | 正座位数 |
| sortNo | int32 | 是 | 排序 |
| resourceNo | string | 否 | 共享资源 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| table | Table | 是 | 桌台 |

### FOOD-API-025 · 桌码轮换

`POST /biz/x/food/tables/{tableNo}:rotate-token`

权限与守卫：原STORE权限及主体门店范围；桌区→桌台稳定锁和CAS；轮换撤销旧码及该桌旧会话；只保存SHA-256，明文仅首次响应；幂等收据不存凭证，同键重放CONFLICT且不再次轮换。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| tableNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| expiresAt | datetime | 是 | 到期 |
| version | int64 | 是 | 版本 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| token | string | 是 | 仅本次返回明文 |
| expiresAt | datetime | 是 | 到期 |

### FOOD-API-026 · 桌码换短会话

`POST /mp/x/food/sessions`

权限与守卫：桌码哈希校验后才解析主体门店，经原StoreDirectoryPort确认ACTIVE；签发最长30分钟且不超过桌码到期的TABLE_MENU会话；不自动绑定现有check。明文只首次返回，同键重放CONFLICT；设备认证合同未齐时非空deviceCredential拒绝。。

能力依赖：餐饮新增。

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| tableToken | string | 是 | 桌码 |
| deviceCredential | string | 否 | 可验证设备凭据，不信任deviceNo自报 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| sessionNo | string | 是 | 会话 |
| accessToken | string | 是 | 短期令牌 |
| expiresAt | datetime | 是 | 到期 |
| checkNo | string | 否 | 获准台账 |

### FOOD-API-027 · 创建预约

`POST /biz/x/food/stores/{storeNo}/reservations`

权限与守卫：目标沿共享resourceNo/hold权威做预约、释放和到店接力，资源取消不等于订金退款；当前共享资源合同未验收，此写入口未开放，不生成假hold引用。资源与代办授权。

能力依赖：共享预约订金契约。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| resourceNo | string | 是 | 资源 |
| startAt | datetime | 是 | 开始 |
| endAt | datetime | 是 | 结束 |
| partySize | int32 | 是 | 人数 |
| buyerNo | string | 否 | 顾客 |
| contactRef | string | 是 | 加密联系人 |
| depositQuoteRef | string | 否 | 订金报价 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| reservationNo | string | 是 | 预约 |
| resourceHoldNo | string | 是 | 占位 |
| status | string | 是 | 状态 |

### FOOD-API-028 · 预约cancel

`POST /biz/x/food/reservations/{reservationNo}:cancel`

权限与守卫：目标沿共享resourceNo/hold权威做预约、释放和到店接力，资源取消不等于订金退款；当前共享资源合同未验收，此写入口未开放，不生成假hold引用。预约权限及版本；订金退款/没收单独走资金审批合同，资源取消不意味着已退款。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| reservationNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| reason | string | 是 | 取消或爽约原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operation | Operation | 是 | 资源释放和资金处理进度 |

### FOOD-API-029 · 预约no-show

`POST /biz/x/food/reservations/{reservationNo}:no-show`

权限与守卫：目标沿共享resourceNo/hold权威做预约、释放和到店接力，资源取消不等于订金退款；当前共享资源合同未验收，此写入口未开放，不生成假hold引用。预约权限及版本；订金退款/没收单独走资金审批合同，资源取消不意味着已退款。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| reservationNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| reason | string | 是 | 取消或爽约原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operation | Operation | 是 | 资源释放和资金处理进度 |

### FOOD-API-030 · 预约arrive

`POST /biz/x/food/reservations/{reservationNo}:arrive`

权限与守卫：目标沿共享resourceNo/hold权威做预约、释放和到店接力，资源取消不等于订金退款；当前共享资源合同未验收，此写入口未开放，不生成假hold引用。新占用失败保留原预约资源；到店只生成一个台账；原预约和资金凭据保留。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| reservationNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 预约版本 |
| tableNos | string[] | 是 | 占桌 |
| tableVersions | TableVersionInput[] | 是 | 资源版本 |
| partySize | int32 | 是 | 实际人数 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作 |
| status | string | 是 | 状态 |
| checkNo | string | 否 | 开台结果 |

### FOOD-API-031 · 开台

`POST /biz/x/food/stores/{storeNo}/checks`

权限与守卫：原VERIFY权限及门店范围；所有目标桌完整CAS，桌区→桌台有序锁后原子新增check/occupancy/seat/audit/outbox；V2持久化EXCLUSIVE/SHARED；营业日复用门店策略。当前只开放无交易PREPAY，POSTPAY及未经验证的预约/员工/顾客引用拒绝；空tableNos支持无桌接待。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| tableNos | string[] | 是 | 空为无桌 |
| partySize | int32 | 是 | 正人数 |
| payMode | string | 是 | PREPAY或POSTPAY |
| reservationNo | string | 否 | 来源预约 |
| tableVersions | TableVersionInput[] | 是 | 所有目标桌版本 |
| sharingMode | string | 是 | EXCLUSIVE或SHARED |
| seats | SeatInput[] | 是 | 座位 |
| serviceEmployeeNo | string | 否 | 获准员工 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| check | Check | 是 | 台账 |

### FOOD-API-032 · 台账桌台查询

`GET /biz/x/food/stores/{storeNo}/checks`

权限与守卫：原ORDER_VIEW权限；主体及当前门店固定过滤，状态/营业日与records/total同口径，分页1/20最大100。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| status | string | 否 | 过滤 |
| businessDate | date | 否 | 营业日 |
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | Check[] | 是 | 列表 |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 当前页 |
| size | int32 | 是 | 每页条数 |

### FOOD-API-033 · 台账transfer

`POST /biz/x/food/checks/{checkNo}:transfer`

权限与守卫：原VERIFY权限、check CAS及全部来源/目标桌CAS；当前仅无批次/结账/退餐/权益/未完成草稿的PREPAY台账；先核验并占完整目标再结束原占用，同主库事务回滚不丢原桌；approvalRef未核验时拒绝；不搬订单或资金。 外部resourceNo/resourceClaimNo或未完成资源意图拒绝本地变更。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| reason | string | 是 | 原因 |
| approvalRef | string | 否 | 审批 |
| targetTableNos | string[] | 是 | 转入桌台 |
| tableVersions | TableVersionInput[] | 是 | 所有来源目标桌版本 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operation | Operation | 是 | 转台受理及恢复结果 |

### FOOD-API-034 · 合并就餐服务台账

`POST /biz/x/food/checks/{checkNo}:merge`

权限与守卫：目标保留原批次及资金归属，按完整membership与冻结合同合拆；当前ORD-03/PAY-01未齐，此写入口未开放。仅合并服务/占用展示归属；原批次check及核心资金归属不改；结账冻结/未知资金时拒绝；合账收款另走settlement。

能力依赖：餐饮桌台操作意图及追加归属；资金合账取决于核心收银。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 来源版本 |
| targetCheckNo | string | 是 | 目标台账 |
| targetVersion | int64 | 是 | 目标版本 |
| tableVersions | TableVersionInput[] | 是 | 桌台版本 |
| reason | string | 是 | 原因 |
| approvalRef | string | 否 | 审批 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operation | Operation | 是 | 协作结果 |

### FOOD-API-035 · 拆分就餐服务台账

`POST /biz/x/food/checks/{checkNo}:split`

权限与守卫：目标保留原批次及资金归属，按完整membership与冻结合同合拆；当前ORD-03/PAY-01未齐，此写入口未开放。仅调整批次服务归属，不改成交和资金；需锁批次且拒绝冻结/未知资金范围；按菜拆账走核心应收分摊。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 来源版本 |
| targetCheckNo | string | 是 | 目标台账 |
| targetVersion | int64 | 是 | 目标版本 |
| selectedBatchNos | string[] | 是 | 允许调整展示归属的完整批次 |
| reason | string | 是 | 原因 |
| approvalRef | string | 否 | 审批 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operation | Operation | 是 | 协作结果 |

### FOOD-API-036 · 台账clear

`POST /biz/x/food/checks/{checkNo}:clear`

权限与守卫：原VERIFY权限及check CAS；仅释放本check明确occupancyNos，空数组只允许本来无桌；任何交易事实或未完成/未知草稿拒绝普通清台。当前只验收无交易接待，最后释放记VOIDED/RELEASED，其他客组保留；已清洁空桌IDLE；不声称资金结清或支持异常放行。 外部resourceNo/resourceClaimNo或未完成资源意图拒绝本地变更。 部分清台先锁全部现有桌位，按原人数重分配剩余占座；剩余容量不足则整笔拒绝。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| reason | string | 是 | 原因 |
| approvalRef | string | 否 | 审批 |
| occupancyNos | string[] | 是 | 结束占用不删除未收应收 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作号 |
| status | string | 是 | 操作状态 |
| version | int64 | 是 | 对象版本 |
| resultRef | string | 否 | 结果业务号 |

### FOOD-API-037 · 购物车读取

`GET /biz/x/food/carts/{cartNo}`

权限与守卫：ORD01已实现；B端原门店范围及ORDER_VIEW/VERIFY；C端原哈希凭证或真实CONSUMER买家，TABLE_MENU不授予共享车权限；写入整体CAS与actor绑定幂等，C响应buyerNo脱敏。。

能力依赖：只持久化草稿，无金额计算或库存预占；正式报价/提交属于ORD02/03。菜单及冻结选配必须仍有效。。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cartNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cart | Cart | 是 | 获准购物车 |

### FOOD-API-038 · 创建购物车

`POST /biz/x/food/stores/{storeNo}/carts`

权限与守卫：ORD01已实现；B端原门店范围及ORDER_VIEW/VERIFY；C端原哈希凭证或真实CONSUMER买家，TABLE_MENU不授予共享车权限；写入整体CAS与actor绑定幂等，C响应buyerNo脱敏。。

能力依赖：只持久化草稿，无金额计算或库存预占；正式报价/提交属于ORD02/03。菜单及冻结选配必须仍有效。。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string | 否 | 真实OPEN台账 |
| sessionNo | string | 否 | 已获准会话 |
| buyerNo | string | 否 | 当前拒绝客户端代客 |
| orderSource | string | 是 | POS或WAITER |
| fulfillment | string | 是 | 履约 |
| scheduledAt | datetime | 否 | 当前拒绝 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cart | Cart | 是 | 唯一OPEN共享车 |

### FOOD-API-039 · 替换草稿行

`PUT /biz/x/food/carts/{cartNo}/lines/{sourceLineRef}`

权限与守卫：ORD01已实现；B端原门店范围及ORDER_VIEW/VERIFY；C端原哈希凭证或真实CONSUMER买家，TABLE_MENU不授予共享车权限；写入整体CAS与actor绑定幂等，C响应buyerNo脱敏。。

能力依赖：只持久化草稿，无金额计算或库存预占；正式报价/提交属于ORD02/03。菜单及冻结选配必须仍有效。。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cartNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| sourceLineRef | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| line | OrderLineInput | 是 | 完整替换源行 |
| version | int64 | 是 | 当前整个车版本 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cartNo | string | 是 | 购物车 |
| version | int64 | 是 | 整体新版本 |

### FOOD-API-040 · 归档购物车草稿行

`POST /biz/x/food/carts/{cartNo}/lines/{sourceLineRef}:archive`

权限与守卫：ORD01已实现；B端原门店范围及ORDER_VIEW/VERIFY；C端原哈希凭证或真实CONSUMER买家，TABLE_MENU不授予共享车权限；写入整体CAS与actor绑定幂等，C响应buyerNo脱敏。。

能力依赖：只持久化草稿，无金额计算或库存预占；正式报价/提交属于ORD02/03。菜单及冻结选配必须仍有效。。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cartNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| sourceLineRef | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 当前整个车版本 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cartNo | string | 是 | 购物车 |
| version | int64 | 是 | 整体新版本 |

### FOOD-API-041 · 点单报价

`POST /biz/x/food/stores/{storeNo}/quotes`

权限与守卫：报价绑定主体门店/车版本/源行内容/规则版本；确认价格变化；服务端最终重验可售及核心库存能力。

能力依赖：已有报价适配；选配计量等分项门控。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string | 否 | 台账 |
| buyerNo | string | 否 | 真实买家 |
| fulfillment | string | 是 | 履约 |
| orderSource | string | 是 | 经认证来源 |
| lines | OrderLineInput[] | 是 | 源行 |
| cartNo | string | 是 | 源车 |
| cartVersion | int64 | 是 | 车版本 |
| scheduledAt | datetime | 否 | 出餐时点 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| quote | Quote | 是 | 权威报价 |

### FOOD-API-042 · 提交点餐批次

`POST /biz/x/food/stores/{storeNo}/batches`

权限与守卫：提交必须匹配报价指纹、源行集合和车版本；冻结selected行；绑定核心成功后仅清理这些行；UNKNOWN同键恢复；payMode由获准策略校验。

能力依赖：当前OrderService适配及通用缺口。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cartNo | string | 是 | 购物车 |
| cartVersion | int64 | 是 | 版本 |
| selectedLineRefs | string[] | 是 | 本次行 |
| quoteNo | string | 是 | 已确认报价 |
| payMode | string | 是 | 先后付 |
| splitByPerson | boolean | 是 | 预分单 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| batch | Batch | 是 | 绑定或处理中 |

### FOOD-API-043 · 批次结果查询

`GET /biz/x/food/batches/{batchNo}`

权限与守卫：门店范围与对象权限；仅授权读取，无写入副作用。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| batchNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| detail | BatchDetail | 是 | 原批次及资金履约独立状态 |

### FOOD-API-044 · 工位队列

`GET /biz/x/food/stores/{storeNo}/kitchen/tasks`

权限与守卫：门店范围与对象权限；仅授权读取，无写入副作用。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| stationNo | string | 是 | 工位 |
| status | string | 否 | 状态 |
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | Task[] | 是 | 任务 |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 当前页 |
| size | int32 | 是 | 每页条数 |

### FOOD-API-045 · 制作任务start

`POST /biz/x/food/tasks/{taskNo}:start`

权限与守卫：获准工位/传菜权限；只推进合法前态数量，CAS及累计数量守恒；重放不重复加量。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| taskNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 任务版本 |
| qty | decimal | 是 | 本次正数量 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| task | Task | 是 | 任务状态 |
| eventNo | string | 是 | 数量事实 |

### FOOD-API-046 · 制作任务ready

`POST /biz/x/food/tasks/{taskNo}:ready`

权限与守卫：获准工位/传菜权限；只推进合法前态数量，CAS及累计数量守恒；重放不重复加量。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| taskNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 任务版本 |
| qty | decimal | 是 | 本次正数量 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| task | Task | 是 | 任务状态 |
| eventNo | string | 是 | 数量事实 |

### FOOD-API-047 · 制作任务serve

`POST /biz/x/food/tasks/{taskNo}:serve`

权限与守卫：获准工位/传菜权限；只推进合法前态数量，CAS及累计数量守恒；重放不重复加量。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| taskNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 任务版本 |
| qty | decimal | 是 | 本次正数量 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| task | Task | 是 | 任务状态 |
| eventNo | string | 是 | 数量事实 |

### FOOD-API-048 · 制作任务hold

`POST /biz/x/food/tasks/{taskNo}:hold`

权限与守卫：获准工位/服务权限；hold/fire仅操作尚未制作部分，urge只记录催菜不加制作数量或销量。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| taskNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 任务版本 |
| reason | string | 否 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| task | Task | 是 | 任务状态 |
| eventNo | string | 是 | 状态事实 |

### FOOD-API-049 · 制作任务fire

`POST /biz/x/food/tasks/{taskNo}:fire`

权限与守卫：获准工位/服务权限；hold/fire仅操作尚未制作部分，urge只记录催菜不加制作数量或销量。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| taskNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 任务版本 |
| reason | string | 否 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| task | Task | 是 | 任务状态 |
| eventNo | string | 是 | 状态事实 |

### FOOD-API-050 · 制作任务urge

`POST /biz/x/food/tasks/{taskNo}:urge`

权限与守卫：获准工位/服务权限；hold/fire仅操作尚未制作部分，urge只记录催菜不加制作数量或销量。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| taskNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 任务版本 |
| reason | string | 否 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| task | Task | 是 | 任务状态 |
| eventNo | string | 是 | 状态事实 |

### FOOD-API-051 · 制作任务void

`POST /biz/x/food/tasks/{taskNo}:void`

权限与守卫：工位/传菜/退餐分别鉴权；累计数量守恒；重做审批不直接改资金。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| taskNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 任务版本 |
| qty | decimal | 是 | 正撤销量 |
| reason | string | 是 | 原因 |
| returnNo | string | 是 | 已获准退餐凭据 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| task | Task | 是 | 任务状态 |
| eventNo | string | 是 | 撤菜事实 |

### FOOD-API-052 · 制作任务remake

`POST /biz/x/food/tasks/{taskNo}:remake`

权限与守卫：工位/传菜/退餐分别鉴权；累计数量守恒；重做审批不直接改资金。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| taskNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 任务版本 |
| qty | decimal | 是 | 正重做量 |
| reason | string | 是 | 原因 |
| approvalRef | string | 是 | 重做授权 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| task | Task | 是 | 原任务 |
| eventNo | string | 是 | 重做事实 |
| newTaskNo | string | 是 | 关联重做任务 |

### FOOD-API-053 · 创建course

`POST /biz/x/food/checks/{checkNo}/courses`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| name | string | 是 | 道次名 |
| sequenceNo | int32 | 是 | 顺序 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| courseNo | string | 是 | 道次 |
| status | string | 是 | HELD |
| version | int64 | 是 | 版本 |

### FOOD-API-054 · 道次叫起

`POST /biz/x/food/courses/{courseNo}:fire`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| courseNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| seatNos | string[] | 是 | 座位范围 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作号 |
| status | string | 是 | 操作状态 |
| version | int64 | 是 | 对象版本 |
| resultRef | string | 否 | 结果业务号 |

### FOOD-API-055 · 交付及配送交接

`POST /biz/x/food/batches/{batchNo}:handover`

权限与守卫：履约权限；核销码防重复。

能力依赖：堂食通用交付缺口，其余复用已有履约。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| batchNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| kind | string | 是 | DINE_IN或PICKUP或DELIVERY |
| coreFulfillmentRef | string | 否 | 核心履约凭据 |
| lines | DeliveryLineInput[] | 是 | foodLineNo及交付qty |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作 |
| coreDeliveryRef | string | 是 | 正式交付引用 |
| status | string | 是 | 结果 |

### FOOD-API-056 · 冻结结账范围

`POST /biz/x/food/stores/{storeNo}/settlements`

权限与守卫：收银权限及核心范围独占防双收。

能力依赖：跨单/部分分摊取决于核心收银。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| items | ScopeItem[] | 是 | 范围 |
| currency | string | 是 | 币种 |
| quoteNo | string | 是 | 权威应收 |
| checkVersions | VersionInput[] | 是 | checkNo和version |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| settlement | Settlement | 是 | 结账 |

### FOOD-API-057 · 读取预结账

`GET /biz/x/food/settlements/{settlementNo}`

权限与守卫：门店范围与对象权限；仅授权读取，无写入副作用。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| settlementNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| settlement | Settlement | 是 | 当前状态 |
| confirmedAmount | Money | 是 | 核心已确认 |
| pendingIntentNos | string[] | 是 | 未知请求 |

### FOOD-API-058 · 结账pay

`POST /biz/x/food/settlements/{settlementNo}:pay`

权限与守卫：服务端查询核心资金；不接受markPaid。现金仅biz收银权限且班次/完整单根影响集匹配，找零由服务端计算；顾客端只获准线上工具；未知状态按原意图恢复。

能力依赖：核心支付/现金确认；混合与跨根需扩展。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| settlementNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| tool | string | 是 | 支付工具 |
| amount | Money | 是 | 本次金额 |
| corePaymentToken | string | 否 | 临时凭证 |
| shiftNo | string | 否 | 现金时必填当班 |
| cashTendered | Money | 否 | 现金时必填实收 |
| cashEvidenceRef | string | 否 | 现金凭据引用 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| intentNo | string | 是 | 请求意图 |
| status | string | 是 | 含UNKNOWN |
| coreReference | string | 否 | 核心结果 |
| paymentAction | object | 否 | 核心返回端上支付动作 |

### FOOD-API-059 · 结账cancel

`POST /biz/x/food/settlements/{settlementNo}:cancel`

权限与守卫：仅无成功/未知不可撤销资金时由核心释放；与付款回调串行仲裁；不得仅按客户端状态解冻。

能力依赖：核心支付/现金确认；混合与跨根需扩展。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| settlementNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| reason | string | 是 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operation | Operation | 是 | 取消冻结范围结果 |

### FOOD-API-060 · 结账finalize

`POST /biz/x/food/settlements/{settlementNo}:finalize`

权限与守卫：scopeHash与冻结版本一致；核心结清且无未知资金；不能接受markPaid或以上菜/清台作为已付证据。

能力依赖：核心支付/现金确认；混合与跨根需扩展。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| settlementNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| scopeHash | string | 是 | 冻结范围指纹 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| settlement | Settlement | 是 | 核心已结清且无未知请求时终结 |

### FOOD-API-061 · 收费优惠调整申请

`POST /biz/x/food/settlements/{settlementNo}/adjustments`

权限与守卫：费用小费分期门控及授权审批。

能力依赖：核心应收调整或AR能力。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| settlementNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| kind | string | 是 | DISCOUNT或FEE或TIP或AR_TRANSFER |
| amount | Money | 是 | 申请金额 |
| reason | string | 是 | 原因 |
| approvalRef | string | 否 | 审批 |
| coreQuoteRef | string | 是 | 报价 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| intentNo | string | 是 | 意图 |
| coreAdjustmentRef | string | 否 | 核心调整 |
| status | string | 是 | 结果 |

### FOOD-API-062 · 退餐申请

`POST /biz/x/food/stores/{storeNo}/returns`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string | 否 | 台账 |
| items | ReturnItem[] | 是 | 逐行退量 |
| reason | string | 是 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| returnNo | string | 是 | 申请 |
| status | string | 是 | REQUESTED |
| version | int64 | 是 | 版本 |

### FOOD-API-063 · 退餐审批执行

`POST /biz/x/food/returns/{returnNo}:execute`

权限与守卫：申请审批责任分离；当前部分退款禁用。

能力依赖：核心取消/售后；行级调整退款缺口。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| returnNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| approvalRef | string | 是 | 共享审批 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作 |
| financialStatus | string | 是 | 财务轨道 |
| kitchenStatus | string | 是 | 厨房轨道 |
| coreCaseRef | string | 否 | 售后 |

### FOOD-API-064 · 退餐查询

`GET /biz/x/food/returns/{returnNo}`

权限与守卫：门店范围与对象权限；仅授权读取，无写入副作用。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| returnNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| returnNo | string | 是 | 申请 |
| items | ReturnItem[] | 是 | 明细 |
| status | string | 是 | 申请状态 |
| financialStatus | string | 是 | 资金状态 |
| coreCaseRef | string | 否 | 核心售后 |
| version | int64 | 是 | 版本 |
| kitchenStatus | string | 是 | 厨房轨道 |

### FOOD-API-065 · 提交打印

`POST /biz/print/jobs`

权限与守卫：设备与场景权限；金额内容由服务端组装不信客户端。

能力依赖：共享打印待建设。

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| sourceEventNo | string | 是 | 事件 |
| scene | string | 是 | 受控场景 |
| sourceRef | string | 是 | 业务凭据 |
| deviceNos | string[] | 否 | 授权设备 |
| templateVersion | int64 | 否 | 模板版 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| jobNos | string[] | 是 | 分单任务 |
| status | string | 是 | 受理 |

### FOOD-API-066 · 打印查询

`GET /biz/print/jobs/{jobNo}`

权限与守卫：门店打印范围。

能力依赖：共享打印待建设。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| jobNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| jobNo | string | 是 | 任务 |
| status | string | 是 | 含UNKNOWN |
| attempts | object[] | 是 | 次数渠道任务回执 |
| originalJobNo | string | 否 | 原任务 |

### FOOD-API-067 · 人工补打

`POST /biz/print/jobs/{jobNo}:reprint`

权限与守卫：补打权限及审计不再次派工。

能力依赖：共享打印待建设。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| jobNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| reason | string | 是 | 原因 |
| deviceNo | string | 否 | 获准新设备 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| jobNo | string | 是 | 新补打任务 |
| originalJobNo | string | 是 | 原任务 |
| status | string | 是 | 状态 |

### FOOD-API-068 · 开班

`POST /biz/x/food/stores/{storeNo}/cash-shifts`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| drawerNo | string | 是 | 钱箱 |
| currency | string | 是 | 币种 |
| openingMinor | int64 | 是 | 非负备用金 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| shiftNo | string | 是 | 班次 |
| status | string | 是 | OPEN |
| version | int64 | 是 | 版本 |

### FOOD-API-069 · 非销售现金操作

`POST /biz/x/food/cash-shifts/{shiftNo}/entries`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| shiftNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| kind | string | 是 | FLOAT_IN或EXPENSE或DEPOSIT或TRANSFER |
| amountMinor | int64 | 是 | 正金额 |
| direction | string | 是 | IN或OUT |
| voucherRef | string | 是 | 凭证 |
| approvalRef | string | 否 | 审批 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| entryNo | string | 是 | 事实 |
| status | string | 是 | 结果 |

### FOOD-API-070 · 实盘与交班

`POST /biz/x/food/cash-shifts/{shiftNo}:count`

权限与守卫：盲盘前不泄露应有金额；差异须审批。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| shiftNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| countedMinor | int64 | 是 | 实盘 |
| version | int64 | 是 | 班次版本 |
| reason | string | 否 | 差异说明 |
| approvalRef | string | 否 | 审批 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| countNo | string | 是 | 盘点 |
| differenceMinor | int64 | 否 | 获准后展示差异 |
| status | string | 是 | 状态 |

### FOOD-API-071 · 接班确认

`POST /biz/x/food/cash-shifts/{shiftNo}:handover`

权限与守卫：接班人登录确认不得客户端传acceptedBy冒充。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| shiftNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| countNo | string | 是 | 实盘 |
| version | int64 | 是 | 版本 |
| approvalRef | string | 否 | 差异审批 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| shiftNo | string | 是 | 班次 |
| status | string | 是 | HANDED_OVER |

### FOOD-API-072 · 营业日预结

`POST /biz/x/food/stores/{storeNo}/closes`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| businessDate | date | 是 | 营业日 |
| currency | string | 是 | 币种 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| close | Close | 是 | 草稿或处理中 |

### FOOD-API-073 · 日结查询

`GET /biz/x/food/closes/{closeNo}`

权限与守卫：门店范围与对象权限；仅授权读取，无写入副作用。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| closeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| close | Close | 是 | 版本快照 |

### FOOD-API-074 · 异常结转申请

`POST /biz/x/food/closes/{closeNo}/exceptions/{exceptionNo}:carry`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| closeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| exceptionNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| targetBusinessDate | date | 是 | 下一归属日 |
| ownerNo | string | 是 | 责任人 |
| reason | string | 是 | 原因 |
| approvalRef | string | 是 | 审批 |
| version | int64 | 是 | 日结版本 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作号 |
| status | string | 是 | 操作状态 |
| version | int64 | 是 | 对象版本 |
| resultRef | string | 否 | 结果业务号 |

### FOOD-API-075 · 签核日结

`POST /biz/x/food/closes/{closeNo}:sign`

权限与守卫：日结签核权限和责任分离；关键未知仅列例外不伪造成功。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| closeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| snapshotHash | string | 是 | 待签指纹 |
| watermark | string | 是 | 待签水位 |
| approvalRef | string | 否 | 强制例外审批 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| close | Close | 是 | 正式版本 |

### FOOD-API-076 · 关账后更正

`POST /biz/x/food/closes/{closeNo}/adjustments`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| closeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| coreReference | string | 是 | 已发生核心调整 |
| currentBusinessDate | date | 是 | 发生日 |
| reason | string | 是 | 原因 |
| approvalRef | string | 是 | 审批 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| adjustmentNo | string | 是 | 更正 |
| newCloseNo | string | 否 | 重审版本 |
| status | string | 是 | 状态 |

### FOOD-API-077 · 经营指标报表

`GET /biz/x/food/reports/{reportCode}`

权限与守卫：reportCode受控目录；门店币种日期权限；最大同步日期范围按配置。

能力依赖：已有shop-report适配＋餐饮投影新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| reportCode | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNos | string[] | 是 | 授权门店 |
| from | date | 是 | 起日 |
| to | date | 是 | 止日不含 |
| timeBasis | string | 是 | BUSINESS_DATE或NATURAL_DATE |
| currency | string | 是 | 币种 |
| metricVersion | int64 | 否 | 口径 |
| stationNo | string | 否 | 工位 |
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| report | Report | 是 | 报表 |

### FOOD-API-078 · 异步报表导出

`POST /biz/x/food/report-exports`

权限与守卫：导出权限水印脱敏，按查询权限重新校验。

能力依赖：餐饮新增。

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| reportCode | string | 是 | 受控报表 |
| storeNos | string[] | 是 | 授权门店 |
| from | date | 是 | 起日 |
| to | date | 是 | 止日不含 |
| currency | string | 是 | 币种 |
| timeBasis | string | 是 | 日期基准 |
| format | string | 是 | CSV或XLSX |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| exportNo | string | 是 | 任务 |
| status | string | 是 | 状态 |

### FOOD-API-079 · 导出结果

`GET /biz/x/food/report-exports/{exportNo}`

权限与守卫：本人或获准导出范围。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| exportNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| exportNo | string | 是 | 任务 |
| status | string | 是 | 状态 |
| downloadUrl | string | 否 | 短时签名链接 |
| expiresAt | datetime | 否 | 过期 |

### FOOD-API-080 · 重算投影

`POST /ops/x/food/report-rebuilds`

权限与守卫：工单授权只重建投影不重开日结。

能力依赖：餐饮新增。

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string | 是 | 门店 |
| from | date | 是 | 起日 |
| to | date | 是 | 止日不含 |
| metricVersion | int64 | 是 | 版本 |
| reason | string | 是 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| runNo | string | 是 | 任务 |
| status | string | 是 | 状态 |

### FOOD-API-081 · 平台能力配置

`PUT /ops/x/food/stores/{storeNo}/capabilities`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| capabilities | string[] | 是 | 允许能力 |
| version | int64 | 是 | 配置版 |
| workOrderRef | string | 是 | 授权工单 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作号 |
| status | string | 是 | 操作状态 |
| version | int64 | 是 | 对象版本 |
| resultRef | string | 否 | 结果业务号 |

### FOOD-API-082 · 外部映射

`PUT /biz/x/food/integrations/{provider}/mappings`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等；映射按账号范围/修订追加，旧订单不按当前映射重解释。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| provider | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string | 是 | 门店 |
| objectType | string | 是 | 受控类型 |
| externalNo | string | 是 | 外部编号 |
| internalNo | string | 是 | 内部编号 |
| authority | string | 是 | 权威角色 |
| channelBindingNo | string | 否 | 外部账号绑定，本地省略 |
| mappingRevisionNo | string | 是 | 不可变映射修订 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作号 |
| status | string | 是 | 操作状态 |
| version | int64 | 是 | 对象版本 |
| resultRef | string | 否 | 结果业务号 |

### FOOD-API-083 · 接入异常查询

`GET /ops/x/food/integration-exceptions`

权限与守卫：工单范围脱敏诊断。

能力依赖：餐饮新增。

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string | 是 | 授权门店 |
| status | string | 否 | 状态 |
| page | int32 | 否 | 页码 |
| size | int32 | 否 | 每页条数 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | object[] | 是 | 编号来源类型状态责任人 |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 当前页 |
| size | int32 | 是 | 每页条数 |

### FOOD-API-084 · 边缘增量认领

`POST /internal/food/sync-batches`

权限与守卫：服务身份签名租约顺序校验；事件类型白名单。

能力依赖：边缘导入正式合同待实施。

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string | 是 | 门店 |
| edgeNo | string | 是 | 节点 |
| epoch | int64 | 是 | 代次 |
| sequenceFrom | int64 | 是 | 起点 |
| sequenceTo | int64 | 是 | 终点 |
| payloadHash | string | 是 | 签名摘要 |
| events | object[] | 是 | 事件ID类型来源号版本与冻结事实 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| syncNo | string | 是 | 批次 |
| status | string | 是 | 状态 |
| acceptedThrough | int64 | 是 | 连续接收水位 |
| conflictRefs | string[] | 是 | 冲突 |

### FOOD-API-085 · 自助权益入场

`POST /biz/x/food/checks/{checkNo}/entitlements`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| coreOrderRef | string | 是 | 有效消费单 |
| personClass | string | 是 | ADULT或CHILD或OTHER |
| persons | int32 | 是 | 人数 |
| ruleVersion | int64 | 是 | 门店规则 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| entitlementNo | string | 是 | 权益 |
| startsAt | datetime | 是 | 开始 |
| expiresAt | datetime | 是 | 到期 |
| version | int64 | 是 | 版本 |

### FOOD-API-086 · 续时及最后点单校验

`POST /biz/x/food/entitlements/{entitlementNo}:extend`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| entitlementNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| minutes | int32 | 是 | 正时长 |
| coreAdjustmentRef | string | 是 | 正式收费或减免 |
| reason | string | 是 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作号 |
| status | string | 是 | 操作状态 |
| version | int64 | 是 | 对象版本 |
| resultRef | string | 否 | 结果业务号 |

### FOOD-API-087 · 宴会计划

`POST /biz/x/food/stores/{storeNo}/banquet-plans`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| reservationNo | string | 是 | 预约 |
| guaranteedPersons | int32 | 是 | 保证人数 |
| menuVersion | int64 | 是 | 餐牌 |
| coreQuoteRef | string | 是 | 合同价 |
| depositRef | string | 否 | 订金 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| planNo | string | 是 | 计划 |
| status | string | 是 | 状态 |
| version | int64 | 是 | 版本 |

### FOOD-API-088 · 宴会人数及加席

`POST /biz/x/food/banquet-plans/{planNo}:confirm-attendance`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| planNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| actualPersons | int32 | 是 | 实际人数 |
| coreQuoteRef | string | 是 | 加席调整报价 |
| reason | string | 否 | 变化原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作号 |
| status | string | 是 | 操作状态 |
| version | int64 | 是 | 对象版本 |
| resultRef | string | 否 | 结果业务号 |

### FOOD-API-089 · 寄存建账

`POST /biz/x/food/stores/{storeNo}/custodies`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| ownerRef | string | 是 | 客户 |
| skuNo | string | 是 | 酒水 |
| qty | decimal | 是 | 数量 |
| unit | string | 是 | 单位 |
| expiresAt | datetime | 是 | 期限 |
| voucherRef | string | 是 | 凭证 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| custodyNo | string | 是 | 编号 |
| version | int64 | 是 | 版本 |

### FOOD-API-090 · 寄存取出及报损

`POST /biz/x/food/custodies/{custodyNo}/entries`

权限与守卫：门店范围与对象权限；写操作CAS版本及幂等。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| custodyNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| kind | string | 是 | WITHDRAW或LOSS |
| qty | decimal | 是 | 正数量 |
| version | int64 | 是 | 版本 |
| reason | string | 否 | 报损原因 |
| approvalRef | string | 否 | 审批 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string | 是 | 操作号 |
| status | string | 是 | 操作状态 |
| version | int64 | 是 | 对象版本 |
| resultRef | string | 否 | 结果业务号 |

### FOOD-API-091 · 房账转挂

`POST /biz/x/food/settlements/{settlementNo}/room-charges`

权限与守卫：房账权限不含支付成功确认。

能力依赖：核心应收与PMS契约。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| settlementNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| propertyNo | string | 是 | 酒店 |
| roomRef | string | 是 | 已校验房账 |
| guestAuthorization | string | 是 | 授权凭据 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| intentNo | string | 是 | 意图 |
| status | string | 是 | 含UNKNOWN |
| externalRef | string | 否 | PMS结果 |

### FOOD-API-092 · 桌区列表

`GET /biz/x/food/stores/{storeNo}/table-areas`

权限与守卫：原canAnyBiz(STORE,ORDER_VIEW)读取桌区/桌台配置元数据；配置写入仍需STORE。客组占用沿获准check详情，不在Table配置对象内嵌；主体门店及原始请求头继续一致核验。主体门店对象一致；列表与total同时按主体及当前门店过滤；page默认1、size默认20最大100；status省略含ACTIVE和DISABLED；按sortNo、areaNo升序；不暴露物理id。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |
| status | string | 否 | 状态 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | TableArea[] | 是 | 授权列表 |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-093 · 创建桌区

`POST /biz/x/food/stores/{storeNo}/table-areas`

权限与守卫：主体门店对象一致；原STORE权限、可信actor及幂等；code/name去两端空白，同店code精确唯一且不可修改，名称可重名；初始ACTIVE、version沿平台0；不依赖支付库存。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| code | string | 是 | 店内唯一 |
| name | string | 是 | 名称 |
| sortNo | int32 | 是 | 排序 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| area | TableArea | 是 | 桌区 |

### FOOD-API-094 · 编辑桌区

`PUT /biz/x/food/table-areas/{areaNo}`

权限与守卫：主体及当前门店与对象一致；原STORE权限、CAS版本及幂等；只改name和sortNo，保留code及归属；摘要包含areaNo与version；不暴露物理id。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| areaNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| name | string | 是 | 名称 |
| sortNo | int32 | 是 | 排序 |
| version | int64 | 是 | 版本 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| area | TableArea | 是 | 桌区 |

### FOOD-API-095 · 桌区启停

`POST /biz/x/food/table-areas/{areaNo}:set-status`

权限与守卫：主体及当前门店与对象一致；原STORE权限、CAS版本及幂等；有启用桌台或活动占用禁止停用；reason非空最长255并写原审计；同状态版本正确时无变更成功，不级联桌台或清台。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| areaNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| status | string | 是 | ACTIVE或DISABLED |
| version | int64 | 是 | 版本 |
| reason | string | 是 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| area | TableArea | 是 | 桌区 |

### FOOD-API-096 · 桌台列表

`GET /biz/x/food/stores/{storeNo}/tables`

权限与守卫：原canAnyBiz(STORE,ORDER_VIEW)读取桌区/桌台配置元数据；配置写入仍需STORE。客组占用沿获准check详情，不在Table配置对象内嵌；主体门店及原始请求头继续一致核验。主体及当前门店过滤records与total；默认page1/size20最大100；areaNo/status/turnoverState及字面关键词过滤；按sortNo/tableNo稳定排序；排除归档，不以支付状态代替桌态。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |
| areaNo | string | 否 | 桌区 |
| status | string | 否 | 配置状态 |
| turnoverState | string | 否 | 资源状态 |
| keyword | string | 否 | 编码名称 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | Table[] | 是 | 授权列表 |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-097 · 桌台详情

`GET /biz/x/food/tables/{tableNo}`

权限与守卫：原canAnyBiz(STORE,ORDER_VIEW)读取桌区/桌台配置元数据；配置写入仍需STORE。客组占用沿获准check详情，不在Table配置对象内嵌；主体门店及原始请求头继续一致核验。主体及当前门店与对象一致；沿原门店目录核验；归档不显示，不暴露物理id。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| tableNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| table | Table | 是 | 桌台详情 |

### FOOD-API-098 · 编辑桌台

`PUT /biz/x/food/tables/{tableNo}`

权限与守卫：主体及当前门店与对象一致；原STORE权限、CAS版本和幂等；按编号锁原/目标桌区再锁桌台并复核；移区目标须启用，有活动占用禁止移区缩容；code及归属不改。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| tableNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| areaNo | string | 是 | 同店桌区 |
| name | string | 是 | 名称 |
| capacity | int32 | 是 | 正容量 |
| sortNo | int32 | 是 | 排序 |
| version | int64 | 是 | 版本 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| table | Table | 是 | 桌台 |

### FOOD-API-099 · 桌台启停

`POST /biz/x/food/tables/{tableNo}:set-status`

权限与守卫：主体及当前门店与对象一致；原STORE权限、CAS版本和幂等；锁桌区再桌台；重新启用要求桌区启用，有活动占用禁止停用；同状态且版本正确无变更成功；reason非空最长255。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| tableNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| status | string | 是 | ACTIVE或DISABLED |
| version | int64 | 是 | 版本 |
| reason | string | 是 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| table | Table | 是 | 桌台 |

### FOOD-API-100 · 归档桌台

`POST /biz/x/food/tables/{tableNo}:archive`

权限与守卫：主体及当前门店与对象一致；原STORE权限、CAS版本和幂等；先停用且无活动占用；锁桌区再桌台并CAS逻辑归档；code唯一键及历史保留；原key重放Operation回执；reason非空最长255。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| tableNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| reason | string | 是 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operation | Operation | 是 | 归档记录 |

### FOOD-API-101 · 吊销桌码

`POST /biz/x/food/tables/{tableNo}:revoke-token`

权限与守卫：原STORE权限、主体门店及桌台CAS；同事务撤销有效码及该桌旧会话，不取消订单；安全操作收据可重放，不返回凭证。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| tableNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 桌台版本 |
| reason | string | 是 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operation | Operation | 是 | 吊销结果 |

### FOOD-API-102 · 就餐台账详情

`GET /biz/x/food/checks/{checkNo}`

权限与守卫：原ORDER_VIEW权限，台账主体门店与当前门店一致；返回该check的活动占用和座位，不混入同桌其他客组。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| check | Check | 是 | 台账 |
| seats | SeatInput[] | 是 | 座位 |
| occupancies | Occupancy[] | 是 | 占用 |

### FOOD-API-103 · 修改人数与座位

`PUT /biz/x/food/checks/{checkNo}/seats`

权限与守卫：原VERIFY权限及check CAS；共用稳定容量锁，保留座位号/标签历史，创建省略seatNo由服务端发号，已有seatNo必须属于本check；未验证guestRef拒绝。当前有正式交易或未完成/未知草稿则拒绝，等待成交座位合同；不挤占他组座位。 外部resourceNo/resourceClaimNo或未完成资源意图拒绝本地变更。 无交易座位改名保留seatNo与变更前后快照；标签唯一冲突整笔回滚。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 台账版本 |
| partySize | int32 | 是 | 人数 |
| seats | SeatInput[] | 是 | 完整座位 |
| reason | string | 是 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| check | Check | 是 | 台账 |

### FOOD-API-104 · 并入桌台

`POST /biz/x/food/checks/{checkNo}:combine-tables`

权限与守卫：原VERIFY权限、check CAS及新增桌CAS；同check并用多桌，共用有序资源锁与容量仲裁；当前限无交易台账，同事务完成并可按原operationNo查询；不合并资金/订单。 外部resourceNo/resourceClaimNo或未完成资源意图拒绝本地变更。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 台账版本 |
| tableVersions | TableVersionInput[] | 是 | 新增桌台 |
| reason | string | 是 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operation | Operation | 是 | 并桌结果 |

### FOOD-API-105 · 同桌新开独立台账

`POST /biz/x/food/tables/{tableNo}:share`

权限与守卫：原VERIFY权限、桌版本和SHARED容量原子仲裁；EXCLUSIVE占用即使桌容量扩大仍拒绝拼桌；新check独立，不继承原客组/购物车/支付权限；当前只PREPAY无交易接待。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| tableNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 桌版本 |
| partySize | int32 | 是 | 正人数 |
| payMode | string | 是 | 获准先后付 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| check | Check | 是 | 新独立台账 |

### FOOD-API-106 · 桌台操作恢复查询

`GET /biz/x/food/table-operations/{operationNo}`

权限与守卫：原ORDER_VIEW及主体门店范围；读取真实fnb_table_operation及冻结结果版本，原operationNo结果稳定。当前本地无交易拓扑仅APPLIED，不伪造外部资源恢复成功。 既存未完成操作返回真实持久化状态及操作版本，不伪造已落定的check版本。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operationNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operation | Operation | 是 | 受权操作进度 |

### FOOD-API-107 · 预约列表

`GET /biz/x/food/stores/{storeNo}/reservations`

权限与守卫：原ORDER_VIEW及主体门店范围；from/to为UTC毫秒半开窗口，按`startAt < to`且`endAt > from`重叠筛选；起止合法、状态白名单与分页同过滤；不返回联系人或buyer，depositRef仅引用不表示订金已结。创建/释放仍待共享资源合同。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |
| from | datetime | 是 | 起含 |
| to | datetime | 是 | 止不含 |
| status | string | 否 | 状态 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | Reservation[] | 是 | 预约 |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-108 · 就餐操作历史

`GET /biz/x/food/checks/{checkNo}/actions`

权限与守卫：原ORDER_VIEW及台账主体门店范围；真实fnb_action_log分页，保留可信操作人及原因，不输出令牌、联系人或幂等原文。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | ActionLog[] | 是 | 获准审计 |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-109 · 餐牌列表

`GET /biz/x/food/stores/{storeNo}/menus`

权限与守卫：原GOODS权限/BizContext/StoreDirectory与实际同主体门店绑定；未知字段拒绝；写操作CAS/幂等/审计/Outbox同事务；timezone与当前生效门店策略一致；只写单店无override头，多店编辑409；完成回执优先重放；keyword字面包含按数据库排序规则匹配，%/_非通配符；按menuNo稳定排序后真实分页。

能力依赖：CAT-02A已实现餐牌头管理；时段规则、菜项与发布仍待后续，不表示可售。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |
| status | string | 否 | 状态 |
| keyword | string | 否 | 名称 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | Menu[] | 是 | 授权列表 |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-110 · 编辑餐牌头

`PUT /biz/x/food/menus/{menuNo}`

权限与守卫：原GOODS权限/BizContext/StoreDirectory与实际同主体门店绑定；未知字段拒绝；写操作CAS/幂等/审计/Outbox同事务；timezone与当前生效门店策略一致；只写单店无override头，多店编辑409；完成回执优先重放。

能力依赖：已实现餐牌工作定义管理；发布与历史读取独立，不能将草稿当可售。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| menuNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| name | string | 是 | 名称 |
| menuType | string | 是 | 受控类型 |
| timezone | string | 是 | 时区 |
| priority | int32 | 是 | 优先级 |
| exclusiveGroup | string | 否 | 互斥组 |
| version | int64 | 是 | 版本 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| menu | Menu | 是 | 草稿头 |

### FOOD-API-111 · 餐牌停用

`POST /biz/x/food/menus/{menuNo}:disable`

权限与守卫：主体门店对象一致；鉴权及写操作CAS/幂等；不暴露物理id；旧订单快照保留。

能力依赖：已实现餐牌工作定义管理；发布与历史读取独立，不能将草稿当可售。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| menuNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| reason | string | 是 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| menu | Menu | 是 | 停用后的工作头，不影响旧订单快照 |

### FOOD-API-112 · 菜品列表

`GET /biz/x/food/stores/{storeNo}/dishes`

权限与守卫：当前主体门店和原商品权限；先按原商品权威过滤及名称匹配，再统计与分页；goodsNo稳定排序；不接受未知或重复查询参数；不返回价格库存假值。

能力依赖：已实现管理查询，不代表可售。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |
| keyword | string | 否 | 最长128商品名或餐饮别名多语言名，忽略大小写字面子串 |
| dishKind | string | 否 | SINGLE或BUNDLE或ADDON |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | DishCatalogItem[] | 是 | 当前店授权菜品与原商品图文 |
| total | int64 | 是 | 全部授权筛选后数量 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-113 · 菜品详情

`GET /biz/x/food/stores/{storeNo}/dishes/{goodsNo}`

权限与守卫：当前主体门店原商品权限；真实组绑定及条目同主体；目标丢失/跨主体绑定CONFLICT，不用空组隐藏；发布引用独立返回，管理草稿不作为可售定义。

能力依赖：已实现管理详情，不代表发布或可交易。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| goodsNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| dish | Dish | 是 | 当前餐饮配置 |
| optionGroups | DishOptionGroup[] | 是 | 当前真实管理草稿组 |
| coreCatalogRef | string | 是 | 已核验的基础商品编号 |
| coreCatalog | ProductCatalog | 是 | 基础图文及规格 |

### FOOD-API-114 · 经营端可售餐牌

`GET /biz/x/food/stores/{storeNo}/catalog`

权限与守卫：服务器当前时间；仅已发布冻结定义及其时段来源履约；每菜项保留身份；当前原商品审核上架、原CN/CNY门店及特价和餐饮限制重新读取；未知价格及库存为null且不可成交；复杂选配/套餐/配方需权威报价；公开及顾客目录不暴露channelAccount账号、外部门店、pricePolicyRef或内部依赖快照，渠道上下文不能由用户伪造；不接受任意at未来预览。

能力依赖：ALL-06已实现保守当前展示；PLATFORM物理库存可明确读取，DUAL/INVENTORY或多市场歧义为UNKNOWN；没有报价保留禁成交，不保证下单。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| orderSource | string | 是 | POS或WAITER或KIOSK或QR或H5或APP |
| fulfillment | string | 是 | DINE_IN或PICKUP或DELIVERY |
| channelBindingNo | string | 否 | 尚无真实渠道目录授权时拒绝 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string | 是 | 获准店 |
| orderSource | string | 是 | 可信来源 |
| fulfillment | string | 是 | 履约 |
| asOf | datetime | 是 | 服务端当前时点 |
| menus | CatalogMenu[] | 是 | 生效发布 |
| items | CatalogItem[] | 是 | 保留菜项身份 |

### FOOD-API-115 · 配置发布历史

`GET /biz/x/food/config-revisions`

权限与守卫：已实现；原GOODS及可信当前门店；主体与冻结门店范围过滤先于分页；status按查询时点派生，不使用可变工作头指针作为发布权威。

能力依赖：已实现OPTION_GROUP/MODIFIER/BUNDLE发布历史；完整快照读取及按时点解析为内部typed契约；schema1旧内容及schema2套餐分别解码。

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configKind | string | 是 | OPTION_GROUP或MODIFIER或BUNDLE |
| configNo | string | 是 | 配置号 |
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | ConfigRevision[] | 是 | 授权列表 |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-116 · 回滚配置为新修订

`POST /biz/x/food/config-revisions/{revisionNo}:republish`

权限与守卫：原主体门店及当前工作CAS；只允许已实现kind白名单；重新验真当前原身份及依赖授权，用旧冻结内容追加新发布号；保留原内容哈希及旧引用，不覆盖历史；理由与actor审计同事务；CHANNEL互斥按冻结的主体/门店/提供方/渠道账号/外部门店身份比较，不能按餐牌私有bindingNo判离散。

能力依赖：ALL-03已实现新修订回滚；开关和冲突时间线继续适用；不回滚历史订单事实。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| revisionNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 当前头版本 |
| effectiveAt | datetime | 是 | 生效 |
| reason | string | 是 | 原因 |
| approvalRef | string | 否 | 审批 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| publication | ConfigRevision | 是 | 新发布号 |

### FOOD-API-117 · 配料组合发布

`POST /biz/x/food/modifiers/{modifierNo}:publish`

权限与守卫：已实现；原GOODS及门店目录权限，主体共享头；完整聚合及可满足性校验；按门店和父生效时点冻结不可变依赖；事务外原SKU/规格权威预读，锁头重验一致；CAS/幂等/审计/Outbox原子；禁止历史回填。

能力依赖：已实现OPTION_GROUP/MODIFIER配置发布；不授予价格库存或可售权限。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| modifierNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 工作头CAS |
| effectiveAt | datetime | 是 | 带时区毫秒精度生效时刻 |
| approvalRef | string | 否 | 审批业务引用 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| publication | ConfigRevision | 是 | 发布快照 |

### FOOD-API-118 · 选配组列表

`GET /biz/x/food/stores/{storeNo}/option-groups`

权限与守卫：原商品权限及当前主体门店校验；先主体过滤再SQL统计分页，groupNo稳定排序，批量读取本页真实条目；拒绝未知或重复参数；不作为已发布授权。

能力依赖：已实现管理查询，不代表可售。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | Config[] | 是 | 授权列表 |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-119 · 套餐列表

`GET /biz/x/food/stores/{storeNo}/bundles`

权限与守卫：已实现；原GOODS及门店入口权限；主体过滤先于count分页，SKU稳定排序，真实有序组/选项；拒绝未知/重复参数；无物理id。

能力依赖：已实现草稿管理，非可售目录。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | BundleConfigVO[] | 是 | 主体共享管理列表 |
| total | int64 | 是 | 真实总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-120 · 配料列表

`GET /biz/x/food/stores/{storeNo}/modifiers`

权限与守卫：已实现管理查询；原GOODS及门店入口权限；主体过滤先于count分页，批量读回真实成员；不含价格库存或可售授权。

能力依赖：已实现草稿管理；门店可售目录待发布及交易校验。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | ModifierConfigVO[] | 是 | 主体共享配料管理列表 |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-121 · 工位列表

`GET /biz/x/food/stores/{storeNo}/stations`

权限与守卫：主体门店对象一致；鉴权及写操作CAS/幂等；不暴露物理id。

能力依赖：ALL-04已实现。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | Station[] | 是 | 授权工位 |
| total | int64 | 是 | 相同过滤总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-122 · 可售限制查询

`GET /biz/x/food/stores/{storeNo}/sales-restrictions`

权限与守卫：主体门店对象一致；鉴权及写操作CAS/幂等；不暴露物理id。

能力依赖：ALL-05已实现服务器时点状态及主体门店过滤。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |
| skuNo | string | 否 | SKU |
| status | string | 否 | 状态 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | SalesRestriction[] | 是 | 约束 |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-123 · 按台账或条件查询批次

`GET /biz/x/food/stores/{storeNo}/batches`

权限与守卫：主体门店对象一致；鉴权及写操作CAS/幂等；不暴露物理id；资金按原核心单归属，查询不更新历史。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |
| checkNo | string | 否 | 原/当前服务台账 |
| membershipMode | string | 否 | ORIGINAL或CURRENT |
| status | string | 否 | 受理状态 |
| businessDate | date | 否 | 批次营业日 |
| orderSource | string | 否 | 来源 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | Batch[] | 是 | 授权列表 |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-124 · 取消正式批次

`POST /biz/x/food/batches/{batchNo}:cancel`

权限与守卫：主体门店对象一致；鉴权及写操作CAS/幂等；不暴露物理id；调用核心取消；支付竞争/已制作按核心守卫和退餐处理；未知不换键重建。

能力依赖：核心取消及后付库存合同。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| batchNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 批次版 |
| reason | string | 是 | 原因 |
| approvalRef | string | 否 | 审批 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operation | Operation | 是 | 取消进度 |
| batch | Batch | 是 | 批次 |

### FOOD-API-125 · 批次操作恢复查询

`GET /biz/x/food/batches/{batchNo}/operations`

权限与守卫：主体门店对象一致；鉴权及写操作CAS/幂等；不暴露物理id。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| batchNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | Operation[] | 是 | 建单取消受理恢复 |
| total | int64 | 是 | 总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-126 · 关闭草稿购物车

`POST /biz/x/food/carts/{cartNo}:archive`

权限与守卫：ORD01已实现；B端原门店范围及ORDER_VIEW/VERIFY；C端原哈希凭证或真实CONSUMER买家，TABLE_MENU不授予共享车权限；写入整体CAS与actor绑定幂等，C响应buyerNo脱敏。。

能力依赖：只持久化草稿，无金额计算或库存预占；正式报价/提交属于ORD02/03。菜单及冻结选配必须仍有效。。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cartNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 当前整个车版本 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operation | Operation | 是 | 归档回执 |

### FOOD-API-127 · 顾客购物车读取

`GET /mp/x/food/carts/{cartNo}`

权限与守卫：ORD01已实现；B端原门店范围及ORDER_VIEW/VERIFY；C端原哈希凭证或真实CONSUMER买家，TABLE_MENU不授予共享车权限；写入整体CAS与actor绑定幂等，C响应buyerNo脱敏。sessionNo query及X-Food-Session头不属于业务DTO。。

能力依赖：只持久化草稿，无金额计算或库存预占；正式报价/提交属于ORD02/03。菜单及冻结选配必须仍有效。。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cartNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| sessionNo | string | 否 | 扫码必需且H5可省略 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cart | Cart | 是 | 脱敏获准草稿 |

### FOOD-API-128 · 顾客创建购物车

`POST /mp/x/food/stores/{storeNo}/carts`

权限与守卫：ORD01已实现；B端原门店范围及ORDER_VIEW/VERIFY；C端原哈希凭证或真实CONSUMER买家，TABLE_MENU不授予共享车权限；写入整体CAS与actor绑定幂等，C响应buyerNo脱敏。。

能力依赖：只持久化草稿，无金额计算或库存预占；正式报价/提交属于ORD02/03。菜单及冻结选配必须仍有效。。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string | 否 | 必须等于已获准会话台账 |
| sessionNo | string | 否 | 原扫码会话或服务端H5会话 |
| fulfillment | string | 是 | 履约 |
| scheduledAt | datetime | 否 | 当前拒绝 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cart | Cart | 是 | 脱敏获准草稿 |

### FOOD-API-129 · 顾客替换草稿行

`PUT /mp/x/food/carts/{cartNo}/lines/{sourceLineRef}`

权限与守卫：ORD01已实现；B端原门店范围及ORDER_VIEW/VERIFY；C端原哈希凭证或真实CONSUMER买家，TABLE_MENU不授予共享车权限；写入整体CAS与actor绑定幂等，C响应buyerNo脱敏。sessionNo query及X-Food-Session头不属于业务DTO。。

能力依赖：只持久化草稿，无金额计算或库存预占；正式报价/提交属于ORD02/03。菜单及冻结选配必须仍有效。。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cartNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| sourceLineRef | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| line | OrderLineInput | 是 | 完整替换源行 |
| version | int64 | 是 | 当前整个车版本 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cartNo | string | 是 | 购物车 |
| version | int64 | 是 | 整体新版本 |

### FOOD-API-130 · 顾客归档购物车草稿行

`POST /mp/x/food/carts/{cartNo}/lines/{sourceLineRef}:archive`

权限与守卫：ORD01已实现；B端原门店范围及ORDER_VIEW/VERIFY；C端原哈希凭证或真实CONSUMER买家，TABLE_MENU不授予共享车权限；写入整体CAS与actor绑定幂等，C响应buyerNo脱敏。sessionNo query及X-Food-Session头不属于业务DTO。。

能力依赖：只持久化草稿，无金额计算或库存预占；正式报价/提交属于ORD02/03。菜单及冻结选配必须仍有效。。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cartNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| sourceLineRef | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 当前整个车版本 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cartNo | string | 是 | 购物车 |
| version | int64 | 是 | 整体新版本 |

### FOOD-API-131 · 顾客点单报价

`POST /mp/x/food/stores/{storeNo}/quotes`

权限与守卫：会话/本人及授权台账范围；不能仅凭桌码访问同桌他人订单；禁止现金确认、审批、签核、任意代付身份；配置来源由服务端确定。

能力依赖：已有报价适配；选配计量等分项门控。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string | 否 | 台账 |
| fulfillment | string | 是 | 履约 |
| orderSource | string | 是 | 经认证来源 |
| lines | OrderLineInput[] | 是 | 源行 |
| cartNo | string | 是 | 源车 |
| cartVersion | int64 | 是 | 车版本 |
| scheduledAt | datetime | 否 | 出餐时点 |
| sessionNo | string | 是 | 获准会话 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| quote | Quote | 是 | 权威报价 |

### FOOD-API-132 · 顾客提交点餐批次

`POST /mp/x/food/stores/{storeNo}/batches`

权限与守卫：会话/本人及授权台账范围；不能仅凭桌码访问同桌他人订单；禁止现金确认、审批、签核、任意代付身份；配置来源由服务端确定。

能力依赖：当前OrderService适配及通用缺口。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| cartNo | string | 是 | 购物车 |
| cartVersion | int64 | 是 | 版本 |
| selectedLineRefs | string[] | 是 | 本次行 |
| quoteNo | string | 是 | 已确认报价 |
| payMode | string | 是 | 先后付 |
| splitByPerson | boolean | 是 | 预分单 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| batch | Batch | 是 | 绑定或处理中 |

### FOOD-API-133 · 顾客批次结果查询

`GET /mp/x/food/batches/{batchNo}`

权限与守卫：会话/本人及授权台账范围；不能仅凭桌码访问同桌他人订单；禁止现金确认、审批、签核、任意代付身份；配置来源由服务端确定。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| batchNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| detail | BatchDetail | 是 | 原批次及资金履约独立状态 |

### FOOD-API-134 · 顾客读取预结账

`GET /mp/x/food/settlements/{settlementNo}`

权限与守卫：会话/本人及授权台账范围；不能仅凭桌码访问同桌他人订单；禁止现金确认、审批、签核、任意代付身份；配置来源由服务端确定。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| settlementNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| settlement | Settlement | 是 | 当前状态 |
| confirmedAmount | Money | 是 | 核心已确认 |
| pendingIntentNos | string[] | 是 | 未知请求 |

### FOOD-API-135 · 顾客结账pay

`POST /mp/x/food/settlements/{settlementNo}:pay`

权限与守卫：服务端查询核心资金；不接受markPaid。现金仅biz收银权限且班次/完整单根影响集匹配，找零由服务端计算；顾客端只获准线上工具；未知状态按原意图恢复。

能力依赖：核心支付/现金确认；混合与跨根需扩展。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| settlementNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 版本 |
| tool | string | 是 | 获准线上工具 |
| amount | Money | 是 | 本次付款 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| intentNo | string | 是 | 请求意图 |
| status | string | 是 | 含UNKNOWN |
| coreReference | string | 否 | 核心结果 |
| paymentAction | object | 否 | 核心返回端上支付动作 |

### FOOD-API-136 · 顾客退餐申请

`POST /mp/x/food/stores/{storeNo}/returns`

权限与守卫：会话/本人及授权台账范围；不能仅凭桌码访问同桌他人订单；禁止现金确认、审批、签核、任意代付身份；配置来源由服务端确定。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string | 否 | 台账 |
| items | ReturnItem[] | 是 | 逐行退量 |
| reason | string | 是 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| returnNo | string | 是 | 申请 |
| status | string | 是 | REQUESTED |
| version | int64 | 是 | 版本 |

### FOOD-API-137 · 顾客退餐查询

`GET /mp/x/food/returns/{returnNo}`

权限与守卫：会话/本人及授权台账范围；不能仅凭桌码访问同桌他人订单；禁止现金确认、审批、签核、任意代付身份；配置来源由服务端确定。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| returnNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| returnNo | string | 是 | 申请 |
| items | ReturnItem[] | 是 | 明细 |
| status | string | 是 | 申请状态 |
| financialStatus | string | 是 | 资金状态 |
| coreCaseRef | string | 否 | 核心售后 |
| version | int64 | 是 | 版本 |
| kitchenStatus | string | 是 | 厨房轨道 |

### FOOD-API-138 · 顾客就餐进度

`GET /mp/x/food/checks/{checkNo}`

权限与守卫：目标仅显式获准的本人check/session membership，不能扫码即读取同桌其他客组；当前入席、正式批次/资金视图合同未齐，本入口未开放。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| check | Check | 是 | 获准视图 |
| foodLines | FoodLine[] | 是 | 仅获准行 |
| financialStatus | string | 是 | 权限过滤的资金状态 |

### FOOD-API-139 · 主动结束顾客会话

`POST /mp/x/food/sessions/{sessionNo}:end`

权限与守卫：X-Food-Session短凭证及原Idempotency-Key必需；只允许有效本人/匿名持有人会话，已绑定buyer须匹配平台CONSUMER，匿名持有人登录后可结束但不自动认领买家身份。过期或撤销拒绝新命令，已成功安全收据可重放；不取消原订单。。

能力依赖：餐饮新增。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| sessionNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| operation | Operation | 是 | 吊销结果 |

### FOOD-API-140 · 顾客申请获准范围结账

`POST /mp/x/food/checks/{checkNo}/settlements`

权限与守卫：会话本人范围；服务端解析核心单及分配，不接受任意核心单号；整桌代付须台账明确授权；冻结排他与未知资金守卫同biz；不得创建现金/免单/跨币种结账。

能力依赖：核心应收冻结；部分/跨根能力门控。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 台账版本 |
| foodLineNos | string[] | 是 | 获准源行范围，空代表本人获准未结范围 |
| scopeHash | string | 是 | 顾客已确认应收摘要 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| settlement | Settlement | 是 | 核心冻结范围 |

### FOOD-API-141 · 创建配料或配料组合

`POST /biz/x/food/stores/{storeNo}/modifiers`

权限与守卫：已实现草稿创建；原GOODS与门店权限、主体共享库、创建幂等；SKU原权威主体门店校验；COMBO仅同主体有效ADDON且不重复/嵌套；服务端生成业务号，拒客户端价格库存。

能力依赖：已实现草稿管理；发布与收费成交待后续P1/P3。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| name | string | 是 | 名称 |
| kind | string | 是 | ADDON或COMBO |
| salesSkuNo | string | 否 | 原计价SKU |
| components | ModifierComponentInput[] | 是 | 组合成员或空 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| configNo | string | 是 | 服务端生成配料业务号 |
| version | int64 | 是 | 草稿CAS版本 |
| status | string | 是 | DRAFT |

### FOOD-API-142 · 创建餐牌渠道绑定

`POST /biz/x/food/stores/{storeNo}/menu-channel-bindings`

权限与守卫：同主体当前店餐牌；新绑定为DRAFT，不宣称已获账号授权；服务端号/CAS/actor绑定幂等/审计Outbox；创建及修改失效餐牌工作CAS。

能力依赖：ALL-02已实现渠道引用草稿及受控启停；真实渠道账号/价格授权缺失时ACTIVE拒绝；Keeta仅规划，无真实同步或成交。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| menuNo | string | 是 | 本店餐牌 |
| provider | string | 是 | 受控渠道 |
| channelAccountRef | string | 是 | 已有受权账号引用 |
| externalStoreRef | string | 是 | 受权外部门店 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| bindingNo | string | 是 | 服务端业务号 |
| menuNo | string | 是 | 本店餐牌 |
| storeNo | string | 是 | 门店 |
| provider | string | 是 | 渠道 |
| channelAccountRef | string | 是 | 仅经营授权端可读账号引用 |
| externalStoreRef | string | 是 | 外部门店引用 |
| status | string | 是 | DRAFT或ACTIVE或DISABLED |
| version | int64 | 是 | CAS |

### FOOD-API-143 · 渠道绑定列表

`GET /biz/x/food/stores/{storeNo}/menu-channel-bindings`

权限与守卫：原主体门店及权限；可信actor；写必需幂等/CAS；不能客户端自造业务号；当前为目标合同不注册空实现。。

能力依赖：ALL-02已实现渠道引用草稿及受控启停；真实渠道账号/价格授权缺失时ACTIVE拒绝；Keeta仅规划，无真实同步或成交。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |
| provider | string | 否 | 渠道 |
| menuNo | string | 否 | 餐牌 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | MenuChannelBinding[] | 是 | 授权记录 |
| total | int64 | 是 | 同过滤总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-144 · 渠道绑定详情

`GET /biz/x/food/menu-channel-bindings/{bindingNo}`

权限与守卫：真实GOODS权限及获准对象门店；仅经营端返回账号引用，公开目录不得返回。

能力依赖：ALL-02已实现渠道引用草稿及受控启停；真实渠道账号/价格授权缺失时ACTIVE拒绝；Keeta仅规划，无真实同步或成交。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| bindingNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| bindingNo | string | 是 | 服务端业务号 |
| menuNo | string | 是 | 本店餐牌 |
| storeNo | string | 是 | 门店 |
| provider | string | 是 | 渠道 |
| channelAccountRef | string | 是 | 仅经营授权端可读账号引用 |
| externalStoreRef | string | 是 | 外部门店引用 |
| status | string | 是 | DRAFT或ACTIVE或DISABLED |
| version | int64 | 是 | CAS |

### FOOD-API-145 · 修改渠道绑定

`PUT /biz/x/food/menu-channel-bindings/{bindingNo}`

权限与守卫：对象门店/CAS/actor绑定幂等；ACTIVE需真实账号及外部门店授权，Keeta或缺提供方拒绝；provider/账号/menu/store不可换义；停用不取消历史订单。

能力依赖：ALL-02已实现渠道引用草稿及受控启停；真实渠道账号/价格授权缺失时ACTIVE拒绝；Keeta仅规划，无真实同步或成交。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| bindingNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | CAS |
| status | string | 是 | ACTIVE或DISABLED |
| externalStoreRef | string | 是 | 获准外部门店 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| bindingNo | string | 是 | 服务端业务号 |
| menuNo | string | 是 | 本店餐牌 |
| storeNo | string | 是 | 门店 |
| provider | string | 是 | 渠道 |
| channelAccountRef | string | 是 | 仅经营授权端可读账号引用 |
| externalStoreRef | string | 是 | 外部门店引用 |
| status | string | 是 | DRAFT或ACTIVE或DISABLED |
| version | int64 | 是 | CAS |

### FOOD-API-146 · 发起餐牌渠道同步

`POST /biz/x/food/menu-channel-bindings/{bindingNo}/syncs`

权限与守卫：原主体门店及权限；可信actor；写必需幂等/CAS；不能客户端自造业务号；当前为目标合同不注册空实现。当前Keeta没有启用适配；无真实提供方拒绝，不能返回虚构成功。。

能力依赖：餐饮新增，未实现；按P1/P2/P4/P5所属切片验收。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| bindingNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| revisionNo | string | 是 | 本绑定餐牌已发布修订 |
| version | int64 | 是 | 绑定CAS |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| sync | CatalogSync | 是 | 原号任务 |

### FOOD-API-147 · 查询餐牌同步结果

`GET /biz/x/food/catalog-syncs/{syncNo}`

权限与守卫：原主体门店及权限；可信actor；写必需幂等/CAS；不能客户端自造业务号；当前为目标合同不注册空实现。部分/未知分列；旧ACK不能覆盖新目标。。

能力依赖：餐饮新增，未实现；按P1/P2/P4/P5所属切片验收。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| syncNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| sync | CatalogSync | 是 | 持久化真实状态 |

### FOOD-API-148 · 创建配方工作头

`POST /biz/x/food/stores/{storeNo}/recipes`

权限与守卫：原主体门店及权限；可信actor；写必需幂等/CAS；不能客户端自造业务号；本批仅EA计份、真实物料baseUom；无库存命令；内部Token不替代B端门店权限。单位与物料目录未联通时不得假校验。。

能力依赖：RECIPE-01A/01B草稿已实现：基础物料及选配调整，本地和typed远程一致；发布及实际选择求值未实现。。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| skuNo | string | 是 | 本店SKU |
| recipeCode | string | 是 | DEFAULT或受控变体 |
| yieldQty | decimal | 是 | 正产出 |
| yieldUnit | string | 是 | 受控单位 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| recipe | Recipe | 是 | 服务端生成配方 |

### FOOD-API-149 · 配方列表

`GET /biz/x/food/stores/{storeNo}/recipes`

权限与守卫：原主体门店及权限；可信actor；写必需幂等/CAS；不能客户端自造业务号；本批仅EA计份、真实物料baseUom；无库存命令；内部Token不替代B端门店权限。。

能力依赖：RECIPE-01A/01B草稿已实现：基础物料及选配调整，本地和typed远程一致；发布及实际选择求值未实现。。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |
| skuNo | string | 否 | 本店SKU |
| status | string | 否 | 状态 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | Recipe[] | 是 | 记录 |
| total | int64 | 是 | 同过滤总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-150 · 配方详情

`GET /biz/x/food/recipes/{recipeNo}`

权限与守卫：原主体门店及权限；可信actor；写必需幂等/CAS；不能客户端自造业务号；本批仅EA计份、真实物料baseUom；无库存命令；内部Token不替代B端门店权限。。

能力依赖：RECIPE-01A/01B草稿已实现：基础物料及选配调整，本地和typed远程一致；发布及实际选择求值未实现。。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| recipeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| recipe | Recipe | 是 | 获准配置 |

### FOOD-API-151 · 维护配方物料与选配调整

`PUT /biz/x/food/recipes/{recipeNo}/definition`

权限与守卫：可信主体门店及商品权限；幂等/CAS；基础单位验真；调整目标为本次保留的既有基础行，选项同主体且组DRAFT/ACTIVE；既有调整编号不能重绑，单位与目标一致；SET/ADD非负、REMOVE空；仅草稿配置，不进行选择求值/扣料/发布。。

能力依赖：RECIPE-01A/01B草稿已实现：基础物料及选配调整，本地和typed远程一致；发布及实际选择求值未实现。。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| recipeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | CAS |
| yieldQty | decimal | 是 | 正产出 |
| yieldUnit | string | 是 | 固定EA配置份数 |
| lines | RecipeLineInput[] | 是 | 最多100基础行 |
| adjustments | RecipeAdjustmentInput[] | 否 | 最多100调整，省略或null保留、空数组清空 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| recipe | Recipe | 是 | 新草稿版本 |

### FOOD-API-152 · 发布配方

`POST /biz/x/food/recipes/{recipeNo}:publish`

权限与守卫：原商品权限/可信主体门店/actor/幂等/CAS；recipe-publication-enabled默认false；schema3冻结基础单位物料事实和实际绑定的已发布组选项；来源身份/hash变化拒绝，锁内重捕获；不意味着报价/扣料已就绪。。

能力依赖：RECIPE-01C已实现：原ConfigRevisionService单店定时不可变发布及schema3读；写入默认关闭，部署须先升级读者。。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| recipeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | CAS |
| effectiveAt | datetime | 是 | 生效时间 |
| approvalRef | string | 否 | 可选审批依据 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| publication | ConfigRevision | 是 | 不可变修订 |

### FOOD-API-153 · 制作耗用列表

`GET /biz/x/food/stores/{storeNo}/production-consumptions`

权限与守卫：原主体门店及权限；可信actor；写必需幂等/CAS；不能客户端自造业务号；本批真实只读route与DTO见nonPaymentImplementation；保留目标合同差异与待验收状态。只读不补扣库存；未知成本与实际量保持为空。。

能力依赖：餐饮目标合同保留；本批真实SQL分页/COUNT与DTO已编码，CODED_PENDING_VERIFICATION；共享PageData字段类型差异见nonPaymentImplementation，不代表目标模型与全部业务已验收。。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| page | int32 | 否 | 默认1 |
| size | int32 | 否 | 默认20最大100 |
| from | date | 是 | 起日 |
| to | date | 是 | 止日不含 |
| skuNo | string | 否 | 本店SKU |
| itemRef | string | 否 | 物料 |
| status | string | 否 | 真实状态 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| records | ProductionConsumption[] | 是 | 获准事实 |
| total | int64 | 是 | 同过滤总数 |
| page | int32 | 是 | 页 |
| size | int32 | 是 | 页长 |

### FOOD-API-154 · 制作耗用详情

`GET /biz/x/food/production-consumptions/{consumptionNo}`

权限与守卫：原主体门店及权限；可信actor；写必需幂等/CAS；不能客户端自造业务号；当前为目标合同不注册空实现。只读核验逐行回执，不能以退款推返料。。

能力依赖：餐饮新增，未实现；按P1/P2/P4/P5所属切片验收。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| consumptionNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| consumption | ProductionConsumption | 是 | 原事实与回执 |

### FOOD-API-155 · 读取SKU工位路由

`GET /biz/x/food/stores/{storeNo}/sku-routes/{skuNo}`

权限与守卫：原GOODS权限及真实同主体当前门店；只读店SKU当前路由及整体关系版本，不返回物理id，不写审计或库存。

能力依赖：ALL-04已实现；StationService.getRoutes/BizStationController.getRoutes，空关系保留版本而非重置。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| storeNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |
| skuNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| skuNo | string | 是 | 原SKU |
| version | int64 | 是 | 整个店SKU路由版本，清空仍保留计数 |
| routes | RouteInput[] | 是 | 当前有效路由 |

### FOOD-API-156 · 授权扫码会话加入共享购物车

`POST /biz/x/food/checks/{checkNo}/cart-sessions`

权限与守卫：原VERIFY门店权限；锁当前区域桌台/check/session，当前有效桌台占用必须属于台账；不可改绑其他台账；原会话撤销过期立即生效。

能力依赖：ORD01辅助合同，复用原GuestSession.checkNo及scope追加CHECK_CART，不增加成员表；审计及DiningSessionChanged Outbox同事务。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| checkNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| sessionNo | string | 是 | 原扫码会话 |
| version | int64 | 是 | 会话当前版本 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| sessionNo | string | 是 | 获准会话 |
| checkNo | string | 是 | 当前OPEN台账 |
| version | int64 | 是 | 会话新版本 |

### FOOD-API-157 · 排队取号

`POST /biz/x/food/queue-tickets`

权限与守卫：原VERIFY门店权限、可信主体/门店/actor；写请求原键/CAS；不接受客户端主体归属。

能力依赖：非支付排队V10；新就座同food primary事务调用内部CheckService.openQueuedCheck，冲突全回滚CALLED；旧SEATING仅兼容原键恢复，无虚构资源占用或短信成功。。

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| partySize | int32 | 是 | 正整数至1000 |
| contactRef | string | 否 | 已有联系引用 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| ticket | QueueTicketView | 是 | 原键创建结果 |

### FOOD-API-158 · 排队列表

`GET /biz/x/food/queue-tickets`

权限与守卫：原VERIFY门店权限、可信主体/门店/actor；写请求原键/CAS；不接受客户端主体归属。

能力依赖：非支付排队V10；新就座同food primary事务调用内部CheckService.openQueuedCheck，冲突全回滚CALLED；旧SEATING仅兼容原键恢复，无虚构资源占用或短信成功。。

请求Query：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| businessDate | date | 是 | 营业日 |
| status | string | 否 | 状态 |
| page | int32 | 否 | 页 |
| size | int32 | 否 | 页长 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| page | QueueTicketPage | 是 | 门店FIFO列表 |

### FOOD-API-159 · 原排队票

`GET /biz/x/food/queue-tickets/{ticketNo}`

权限与守卫：原VERIFY门店权限、可信主体/门店/actor；写请求原键/CAS；不接受客户端主体归属。

能力依赖：非支付排队V10；新就座同food primary事务调用内部CheckService.openQueuedCheck，冲突全回滚CALLED；旧SEATING仅兼容原键恢复，无虚构资源占用或短信成功。。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| ticketNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Query：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| ticket | QueueTicketView | 是 | 当前票 |

### FOOD-API-160 · 叫下一位

`POST /biz/x/food/queue-tickets:call-next`

权限与守卫：原VERIFY门店权限、可信主体/门店/actor；写请求原键/CAS；不接受客户端主体归属。

能力依赖：非支付排队V10；新就座同food primary事务调用内部CheckService.openQueuedCheck，冲突全回滚CALLED；旧SEATING仅兼容原键恢复，无虚构资源占用或短信成功。。

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| businessDate | date | 是 | 营业日 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| ticket | QueueTicketView | 是 | 实际最早WAITING票 |

### FOOD-API-161 · 指定叫号

`POST /biz/x/food/queue-tickets/{ticketNo}:call`

权限与守卫：原VERIFY门店权限、可信主体/门店/actor；写请求原键/CAS；不接受客户端主体归属。

能力依赖：非支付排队V10；新就座同food primary事务调用内部CheckService.openQueuedCheck，冲突全回滚CALLED；旧SEATING仅兼容原键恢复，无虚构资源占用或短信成功。。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| ticketNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 原CAS |
| reason | string | 否 | 依据 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| ticket | QueueTicketView | 是 | 已叫号 |

### FOOD-API-162 · 取消排队

`POST /biz/x/food/queue-tickets/{ticketNo}:cancel`

权限与守卫：原VERIFY门店权限、可信主体/门店/actor；写请求原键/CAS；不接受客户端主体归属。

能力依赖：非支付排队V10；新就座同food primary事务调用内部CheckService.openQueuedCheck，冲突全回滚CALLED；旧SEATING仅兼容原键恢复，无虚构资源占用或短信成功。。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| ticketNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 原CAS |
| reason | string | 否 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| ticket | QueueTicketView | 是 | 已取消 |

### FOOD-API-163 · 排队过号

`POST /biz/x/food/queue-tickets/{ticketNo}:no-show`

权限与守卫：原VERIFY门店权限、可信主体/门店/actor；写请求原键/CAS；不接受客户端主体归属。

能力依赖：非支付排队V10；新就座同food primary事务调用内部CheckService.openQueuedCheck，冲突全回滚CALLED；旧SEATING仅兼容原键恢复，无虚构资源占用或短信成功。。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| ticketNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 原CAS |
| reason | string | 否 | 原因 |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| ticket | QueueTicketView | 是 | 真实过号 |

### FOOD-API-164 · 排队转原开台

`POST /biz/x/food/queue-tickets/{ticketNo}:seat`

权限与守卫：原VERIFY门店权限、可信主体/门店/actor；写请求原键/CAS；不接受客户端主体归属。

能力依赖：非支付排队V10；新就座同food primary事务调用内部CheckService.openQueuedCheck，冲突全回滚CALLED；旧SEATING仅兼容原键恢复，无虚构资源占用或短信成功。。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| ticketNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| version | int64 | 是 | 原CAS |
| check | object | 是 | 原OpenCheckReq完整受控开台参数，见QueueContracts |

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| ticket | QueueTicketView | 是 | 原子开台成功SEATED；旧SEATING仅兼容查原回执 |

### FOOD-API-165 · 原排队就座恢复

`POST /biz/x/food/queue-tickets/{ticketNo}:recover-seating`

权限与守卫：原VERIFY门店权限、可信主体/门店/actor；写请求原键/CAS；不接受客户端主体归属。

能力依赖：非支付排队V10；新就座同food primary事务调用内部CheckService.openQueuedCheck，冲突全回滚CALLED；旧SEATING仅兼容原键恢复，无虚构资源占用或短信成功。。

路径输入：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| ticketNo | string (path) | 是 | 必须与授权主体及门店一致的路径标识 |

请求Body：

无额外参数。

成功输出（data）：

| 参数 | 类型 | 必填 | 意义 |
|---|---|---|---|
| ticket | QueueTicketView | 是 | 旧SEATING只恢复原冻结开台回执，未知仍保留 |

## 五、顾客与设备端复用

顾客catalog/session与购物车、报价、批次、进度、结账、退餐申请/查询、主动结束会话均已逐项登记API编号。只有目录中的 /mp/x/food 白名单可实现，不能将整个biz控制器自动暴露到mp。顾客作用域绑定本人或明确授权的台账/源行，桌码仅授权桌级会话，不自动共享同桌其他独立台账。顾客禁止代客buyerNo、现金确认、审批、签核、任务操作和自造corePaymentToken；点餐机使用已验证设备凭据与短会话。

支付动作对象由核心通道按真实客户端环境产生，业务服务不自拼收银参数。共享审批、商品编辑、会员资产、发票、税费、配送、排队及房账平台有各自合同；此处端点不重复实现它们的核心逻辑。

## 六、示例与错误恢复

点餐输入示例（选配能力启用时）：

```json
{"cartNo":"CART-1","cartVersion":1,"checkNo":"CHECK-1","buyerNo":"BUYER-1","fulfillment":"DINE_IN","orderSource":"WAITER","lines":[{"sourceLineRef":"LINE-1","goodsNo":"G-1","skuNo":"SKU-1","qty":"1","selections":[{"groupNo":"ICE","sourceKind":"SPEC_VALUE","sourceNo":"LESS_ICE","qty":"1","configVersion":3}]}]}
```

批次受理返回示例：

```json
{"code":0,"msg":"success","data":{"batch":{"batchNo":"BATCH-1","status":"PLACING","paymentRootNos":[],"storeOrderNos":[],"foodLineNos":[],"version":1}}}
```

data.status为PLACING/UNKNOWN时按batchNo查询；HTTP状态仍沿宿主包装及异常协议，不强制返回202。请求受理后，不换幂等键重建；合法受理后也需按先付/后付条件判断是否制作。冲突错误沿宿主错误码返回当前version或冲突资源引用，但不泄露无权业务。日结签核前水位变化返回CLOSE_WATERMARK_CHANGED，重新预结审阅；依赖能力未开返回CAPABILITY_NOT_READY，不尝试餐饮直接写核心数据。

## 七、餐饮与基础域的输入输出合同

> 状态：待评审 · 2026-10-19 · 本文件是API生成输入片段；正式阅读入口为技术设计目录中的《餐饮系统API设计》。

Catalog.goodsMetadata已实现为GoodsCatalogQueryPort及typed内部查询；其余名称仍为目标端口，不是已存在Java类。集成部署实现为宿主桥接，独立部署实现为服务调用；不将跨库实现细节传入餐饮领域。字段中的ref均须受权查询，不允许客户端自造已付或已退款凭据。

| 端口 | 输入（必填，?为可选） | 输出 | 当前边界 |
|---|---|---|---|
| Identity.authorize | actorToken:string, entityNo:string, storeNo:string, action:string, objectRef?:string | actorNo:string, buyerScope:string[], allowed:boolean, limits:Money[] | 复用身份；匿名就餐主体与代办需正式合同 |
| Catalog.goodsMetadata | entityNo:string, storeNo:string, goodsNo:string | GoodsFact:{goodsNo,entityNo,storeNo,title,cover?,baseOnSale,auditStatus?,version,skus:{skuNo,spec?,version}[]} | CAT-01A基础管理事实；明确商品真实归属及全部CN规格，无价格库存，不表示最终可售；独立经GoodsCatalogInternalApi读取PLATFORM |
| Catalog.snapshot | entityNo:string, storeNo:string, skuNos:string[], channel:string, at:datetime | items:{goodsNo,skuNo,title,unit,currency,priceMinor,onSale,availableQty,version}[] | 读取商品服务，不直接跨库JOIN |
| Catalog.validateSelections | skuNo:string, selections:Selection[], configVersion:int64 | valid:boolean, normalized:Selection[], recipeRef?:string | 强销售约束提交前校验及收费组合为缺口 |
| Order.quote | buyerNo:string, storeNo:string, fulfillment:string, channel:string, lines:OrderLineInput[] | Quote及sourceLineRef到报价行映射 | 当前preview可适配基础SKU；选配小数不直接传Item |
| Order.place | requestKey:string, quoteNo:string, buyerNo:string, storeNo:string, lines:OrderLineInput[], payMode:string | paymentRootNos:string[], storeOrderNos:string[], stableLines:{sourceRef,orderItemId,qty,amount,kind}[], phase:string | 当前create/createFor适配；源行和后付需补能力 |
| Order.findPlacement | entityNo:string, requestKey:string | phase:string, originalResult?:Batch | 超时恢复必须查同键，不任意重建 |
| Inventory.acceptDeferred | storeOrderNos:string[], acceptanceKey:string, expiresPolicy:string | commitmentRef:string, status:string, expiresAt?:datetime | 后付承诺与取消守卫缺口，不能餐饮自行二次锁库存 |
| Fulfillment.deliver | storeOrderNo:string, lineQty:{orderItemId,qty}[], operationKey:string, kind:string | deliveryRef:string, status:string | 自取配送适配现有；堂食及未付交付需补合同 |
| Cashier.freeze | scopes:ScopeItem[], currency:string, scopeHash:string | cashierRef:string, due:Money, version:int64, expiresAt:datetime | 通用应收范围预占和跨单收银缺口 |
| Payment.open | cashierRef或合法paymentRootNo:string, tool:string, amount:Money, actorNo:string, requestKey:string | paymentRef:string, status:string, clientAction?:object | 现有单根pay可适配；混合/跨根分配不可伪造 |
| Payment.query | paymentRef:string, entityNo:string | status:string, confirmedAmount:Money, allocations:{orderRef,amount}[], occurredAt?:datetime | 唯一可信支付结果，退款保持原分配追踪 |
| Payment.confirmCash | storeOrderNos:string[], requestKey:string, actorNo:string, cashEvidence:string | corePaymentRef:string, status:string | 当前confirmOfflinePay完整单店影响集限制保留 |
| Order.adjustReceivable | scope:ScopeItem[], kind:string, amount:Money, reason:string, approvalRef:string, requestKey:string | adjustmentRef:string, newQuote:Quote, status:string | 订单金额不可餐饮直接update；通用调整缺口 |
| AfterSale.request | storeOrderNo:string, lines?:{orderItemId,qty}[], reason:string, requestKey:string | caseNo:string, refundable:Money, status:string | 当前仅门店整单全额；lines部分退受能力门控 |
| Resource.holdOrOccupy | storeNo:string, resourceNos:string[], startAt?:datetime, endAt?:datetime, seats:int32, requestKey:string | claimNos:string[], status:string, expiresAt?:datetime | 时段预约与开放占用分开，统一仲裁者 |
| Resource.transfer | oldClaims:string[], newResources:string[], expectedVersion:int64, requestKey:string | newClaims:string[], status:string, recoveryRef?:string | 先占后放，失败保留可恢复占用 |
| Approval.requestOrVerify | actorNo:string, action:string, amount?:Money, objectRef:string, policyVersion:int64, approvalRef?:string | decision:string, approvalRef:string, approverNo?:string | 复用审批；未存在时建共享能力，不把审批塞进任意JSON |
| Reporting.queryOrBuild | storeNos:string[], range:{from,to}, timeBasis:string, currency:string, metricVersion:int64 | Report及runRef:string | 复用shop-report，新增营业日投影不改旧统计 |
| Reconcile.evidence | storeNo:string, dateRange:{from,to}, channel:string, currency:string, watermark?:string | totals:MetricCell[], differences:{ref,type,amount,status}[], asOf:datetime | 支付对账非餐饮重建资金账 |
| Assets.applyOrReverse | settlementRef:string, assetRef:string, amount:Money, requestKey:string, originalRef?:string | operationRef:string, allocations:{principal,gift}[], status:string | 会员资产唯一扣减，充值不计消费收入 |
| AR.transferOrApply | customerRef:string, scope:ScopeItem[], amount:Money, approvalRef:string, requestKey:string | arRef:string, balance:Money, status:string | 企业挂账/PMS房账待通用应收能力 |
| Print.submit | sourceEventNo:string, scene:string, sourceRef:string, contentVersion:int64 | jobNos:string[], status:string | 通用打印业务与渠道分层；内容服务端冻结 |

核心命令需要最终校验报价、权限、库存及资金版本；餐饮预校验不能替代核心提交校验。发票、排队、PMS、税费与第三方专项协议以其权威系统合同为准，实施前补充协议版本及失败恢复，不在此臆造现成接口。

## 八、逐需求与业务模块对齐

目标已定义仅表示本轮结构与目标合同有明确落点，不表示接口已实现。依赖共享/核心合同必须由权威模块接通验证；尚缺专项设计不能作为已覆盖需求进入开发完成统计。跨域关系为逻辑引用，不允许餐饮直接写核心表。

### MOD-01 桌台与就餐管理

| 需求 | 对齐状态 | 表/权威事实 | API编号 | 结论与残余工作 |
|---|---|---|---|---|
| FNB-A04 | 目标已定义 | `fnb_table_area`、`fnb_table`、`fnb_table_token` | FOOD-API-024、FOOD-API-025、FOOD-API-092、FOOD-API-093、FOOD-API-094、FOOD-API-095、FOOD-API-096、FOOD-API-097、FOOD-API-098、FOOD-API-099、FOOD-API-100、FOOD-API-101 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-C01 | 尚缺专项设计 | `fnb_reservation` | FOOD-API-027、FOOD-API-030、FOOD-API-107 | 预约资源已设计；预点菜草稿到预约的引用与预点命令未定义，实施预约预点前补齐，不能提前生成交易单 |
| FNB-C02 | 依赖共享/核心合同 | `fnb_reservation` | FOOD-API-027、FOOD-API-028、FOOD-API-029 | 订金/预付款核销、没收审批与退款状态取权威资金合同；deposit_ref只存引用，不表示已覆盖全部资金动作 |
| FNB-C03 | 依赖共享/核心合同 | `fnb_queue_ticket`、`fnb_check`、`fnb_action_log` | FOOD-API-157、FOOD-API-158、FOOD-API-159、FOOD-API-160、FOOD-API-161、FOOD-API-162、FOOD-API-163、FOOD-API-164、FOOD-API-165 | 已有正式排队创建/叫号/过号/取消/原开台就座恢复合同；共享开台仲裁及真实通知分别验收，不把排队号当取餐号或伪造短信成功。 |
| FNB-C04 | 目标已定义 | `fnb_reservation`、`fnb_table_operation`、`fnb_table_occupancy` | FOOD-API-027、FOOD-API-030、FOOD-API-031 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-C05 | 目标已定义 | `fnb_check`、`fnb_guest_session`、`fnb_seat`、`fnb_table_occupancy` | FOOD-API-026、FOOD-API-031、FOOD-API-102、FOOD-API-103、FOOD-API-105、FOOD-API-138、FOOD-API-139 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-C06 | 目标已定义 | `fnb_table_operation`、`fnb_table_occupancy`、`fnb_check_relation`、`fnb_check_batch_membership` | FOOD-API-033、FOOD-API-034、FOOD-API-035、FOOD-API-104、FOOD-API-105、FOOD-API-106 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-C08 | 目标已定义 | `fnb_check`、`fnb_table_occupancy`、`fnb_table_operation` | FOOD-API-036、FOOD-API-096、FOOD-API-102、FOOD-API-108 | 结构与目标接口已定义；仍需实现与业务验收 |

### MOD-02 菜品与餐牌管理

| 需求 | 对齐状态 | 表/权威事实 | API编号 | 结论与残余工作 |
|---|---|---|---|---|
| FNB-B01 | 依赖共享/核心合同 | `fnb_dish` | FOOD-API-016、FOOD-API-112、FOOD-API-113 | 基础SKU/上下架/价格库存复用商品服务；本文不新增餐饮商品副本 |
| FNB-B02 | 目标已定义 | `fnb_menu`、`fnb_menu_store`、`fnb_menu_context`、`fnb_menu_schedule`、`fnb_menu_exception`、`fnb_menu_category`、`fnb_menu_item`、`fnb_menu_item_sku`、`fnb_config_revision`、`fnb_menu_channel_binding`、`fnb_menu_item_option_group` | FOOD-API-004、FOOD-API-005、FOOD-API-006、FOOD-API-017、FOOD-API-109、FOOD-API-110、FOOD-API-111、FOOD-API-114、FOOD-API-115、FOOD-API-116、FOOD-API-142、FOOD-API-143、FOOD-API-144、FOOD-API-145 | 时段/渠道范围及菜项覆盖已入设计；报价引用须权威核验，Keeta仅规划，实际发布/同步未实现。 |
| FNB-B03 | 目标已定义 | `fnb_dish`、`fnb_option_group`、`fnb_option_group_item`、`fnb_dish_option_group`、`fnb_config_revision` | FOOD-API-007、FOOD-API-008、FOOD-API-009、FOOD-API-016、FOOD-API-019、FOOD-API-118 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-B04 | 目标已定义 | `fnb_dish`、`fnb_option_group`、`fnb_option_group_item`、`fnb_dish_option_group`、`fnb_config_revision`、`fnb_menu_item_option_group` | FOOD-API-007、FOOD-API-008、FOOD-API-009、FOOD-API-016、FOOD-API-019、FOOD-API-118 | 商品默认选配与餐牌REPLACE显式分开；免费口味复用SPEC_VALUE，未实现运行时选择校验。 |
| FNB-B05 | 依赖共享/核心合同 | `fnb_bundle`、`fnb_bundle_group`、`fnb_bundle_choice`、`fnb_config_revision` | FOOD-API-010、FOOD-API-011、FOOD-API-012、FOOD-API-018、FOOD-API-041、FOOD-API-119 | 配置及版本已定义，套餐成交分摊、补差、组件库存展开需核心组合报价合同 |
| FNB-B06 | 依赖共享/核心合同 | `fnb_modifier`、`fnb_modifier_component`、`fnb_option_group_item`、`fnb_config_revision` | FOOD-API-019、FOOD-API-020、FOOD-API-041、FOOD-API-117、FOOD-API-120、FOOD-API-141 | 组合配置防循环；双拼/半份的单位换算与收费必须核心计量/报价通过后启用 |
| FNB-B07 | 依赖共享/核心合同 | `fnb_dish`、`fnb_store_policy` | FOOD-API-041、FOOD-API-061 | 餐段/渠道价、茶位低消服务费等规则归共享报价；当前缺明确通用费用规则配置合同，不在settings任意JSON里影子计价 |
| FNB-B08 | 目标已定义 | `fnb_sales_restriction`、`fnb_sales_quota_claim` | FOOD-API-022、FOOD-API-122 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-B09 | 尚缺专项设计 | `fnb_dish`、`fnb_order_line` | FOOD-API-016、FOOD-API-041 | 计量规则有位置；标称/实称/允差确认及补差动作尚缺正式DTO和命令，不能只配置就售卖称重菜 |
| FNB-B10 | 依赖共享/核心合同 | `fnb_dish`、`fnb_store_policy` | FOOD-API-041、FOOD-API-061 | 餐段/渠道价、茶位低消服务费等规则归共享报价；当前缺明确通用费用规则配置合同，不在settings任意JSON里影子计价 |
| FNB-B11 | 尚缺专项设计 | `fnb_dish`、`fnb_config_revision` | FOOD-API-016、FOOD-API-112、FOOD-API-113 | 展示字段及冻结已补；批量导入预检/结果/导出尚依赖商品导入合同，明确接入后才算完成 |
| FNB-K01 | 目标已定义 | `fnb_config_revision`、`fnb_menu_store` | FOOD-API-006、FOOD-API-017、FOOD-API-115、FOOD-API-116 | 结构与目标接口已定义；仍需实现与业务验收 |

### MOD-03 点餐与订单管理

| 需求 | 对齐状态 | 表/权威事实 | API编号 | 结论与残余工作 |
|---|---|---|---|---|
| FNB-D01 | 依赖共享/核心合同 | `fnb_guest_session`、`fnb_check_cart`、`fnb_order_batch` | FOOD-API-026、FOOD-API-038、FOOD-API-041、FOOD-API-042、FOOD-API-127、FOOD-API-128、FOOD-API-129、FOOD-API-130、FOOD-API-131、FOOD-API-132、FOOD-API-133、FOOD-API-138、FOOD-API-139 | 顾客路由已逐项编号；匿名主体、代客、设备鉴权及各客户端支付参数必须沿身份/资金权威合同 |
| FNB-D02 | 依赖共享/核心合同 | `fnb_guest_session`、`fnb_check_cart`、`fnb_order_batch` | FOOD-API-026、FOOD-API-038、FOOD-API-041、FOOD-API-042、FOOD-API-127、FOOD-API-128、FOOD-API-129、FOOD-API-130、FOOD-API-131、FOOD-API-132、FOOD-API-133、FOOD-API-138、FOOD-API-139 | 顾客路由已逐项编号；匿名主体、代客、设备鉴权及各客户端支付参数必须沿身份/资金权威合同 |
| FNB-D03 | 目标已定义 | `fnb_check_cart`、`fnb_check_cart_item`、`fnb_seat` | FOOD-API-037、FOOD-API-038、FOOD-API-039、FOOD-API-040、FOOD-API-103、FOOD-API-126、FOOD-API-127、FOOD-API-128、FOOD-API-129、FOOD-API-130 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-D04 | 依赖共享/核心合同 | `fnb_check_cart`、`fnb_order_batch` | FOOD-API-041、FOOD-API-131 | 报价绑定草稿版本及指纹；价格变化须顾客确认；核心报价是权威 |
| FNB-D05 | 依赖共享/核心合同 | `fnb_order_batch`、`fnb_order_line`、`fnb_core_line_allocation`、`fnb_order_modifier`、`fnb_order_component` | FOOD-API-042、FOOD-API-043、FOOD-API-123、FOOD-API-125、FOOD-API-132、FOOD-API-133 | 源行/批次追踪已补；当前核心Item缺源行映射，需正式扩展合同 |
| FNB-D06 | 尚缺专项设计 | `fnb_return`、`fnb_return_item`、`fnb_charge_intent` | FOOD-API-061、FOOD-API-062、FOOD-API-063 | 赠菜/换菜/缺货替换的顾客确认和原新源行关系未定义专用命令；不能仅凭调整或退餐接口宣称覆盖 |
| FNB-D07 | 依赖共享/核心合同 | `fnb_order_batch`、`fnb_charge_intent` | FOOD-API-042、FOOD-API-058、FOOD-API-124、FOOD-API-125、FOOD-API-132、FOOD-API-135 | 先付权威回调与核心取消竞争；UNKNOWN恢复不能重建 |
| FNB-D08 | 依赖共享/核心合同 | `fnb_check`、`fnb_order_batch`、`fnb_settlement` | FOOD-API-031、FOOD-API-042、FOOD-API-056、FOOD-API-058 | 后付受理、库存承诺、未付堂食交付、超时取消守卫需核心扩展，不能用延长支付期限代替 |
| FNB-D09 | 尚缺专项设计 | `fnb_check_cart`、`fnb_order_batch` | FOOD-API-038、FOOD-API-041、FOOD-API-042 | scheduled_at位置已补；预售截止/产能预占/定时派工的正式策略及恢复合同仍待细化 |

### MOD-04 厨房与履约管理

| 需求 | 对齐状态 | 表/权威事实 | API编号 | 结论与残余工作 |
|---|---|---|---|---|
| FNB-E01 | 目标已定义 | `fnb_kitchen_ticket`、`fnb_kitchen_task` | FOOD-API-044、FOOD-API-045 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-E02 | 目标已定义 | `fnb_kitchen_task`、`fnb_service_event` | FOOD-API-048、FOOD-API-049、FOOD-API-050 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-E03 | 目标已定义 | `fnb_kitchen_task`、`fnb_service_event` | FOOD-API-046、FOOD-API-047 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-E04 | 目标已定义 | `fnb_course`、`fnb_kitchen_task` | FOOD-API-053、FOOD-API-054、FOOD-API-048、FOOD-API-049 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-E05 | 尚缺专项设计 | `fnb_service_event`、`fnb_kitchen_task` | FOOD-API-046、FOOD-API-047、FOOD-API-055 | 出品/上菜已分轨，撤回交接和漏菜反馈动作需明确数量冲销规则及端点，不以负qty复用serve |
| FNB-E06 | 尚缺专项设计 | `fnb_kitchen_task`、`fnb_order_component` | FOOD-API-044、FOOD-API-046、FOOD-API-055 | 有组件与跨工位任务，但齐套/打包清单确认缺专用合同，不能仅全部任务ready替代人工打包核对 |
| FNB-E07 | 依赖共享/核心合同 | `fnb_order_batch` | FOOD-API-055 | 叫号/核销/配送状态复用对应履约配送合同；handover不能代替叫号及防重核销全部步骤 |
| FNB-E08 | 依赖共享/核心合同 | `fnb_order_batch` | FOOD-API-055 | 叫号/核销/配送状态复用对应履约配送合同；handover不能代替叫号及防重核销全部步骤 |
| FNB-E09 | 依赖共享/核心合同 | `fnb_kitchen_task`、`fnb_service_event`、`fnb_return_item` | FOOD-API-051、FOOD-API-052、FOOD-API-062、FOOD-API-063 | 制作损耗及退餐回库由库存权威判定；重做不自动计新增销量 |
| FNB-E10 | 尚缺专项设计 | `fnb_store_policy`、`fnb_kitchen_task` | FOOD-API-044、FOOD-API-081 | 工位队列已有；超时阈值/拥堵暂停的配置、告警订阅与恢复命令尚未明确 |

### MOD-05 收银与结账管理

| 需求 | 对齐状态 | 表/权威事实 | API编号 | 结论与残余工作 |
|---|---|---|---|---|
| FNB-C07 | 依赖共享/核心合同 | `fnb_settlement`、`fnb_settlement_item`、`fnb_charge_intent` | FOOD-API-056、FOOD-API-057、FOOD-API-058、FOOD-API-059、FOOD-API-060、FOOD-API-140 | 主责MOD-05；桌台merge/split只改服务归属，资金拆合必须核心应收范围及分配合同 |
| FNB-F01 | 依赖共享/核心合同 | `fnb_settlement`、`fnb_settlement_item` | FOOD-API-056、FOOD-API-057、FOOD-API-059、FOOD-API-060、FOOD-API-134、FOOD-API-140 | 冻结范围及防重复占应收需核心权威仲裁；预结不是已付款 |
| FNB-F02 | 依赖共享/核心合同 | `fnb_charge_intent`、`fnb_cash_shift`、`fnb_cash_payment_link` | FOOD-API-058、FOOD-API-068、FOOD-API-070、FOOD-API-071、FOOD-API-135 | 单根支付可适配；现金仍遵守完整门店单影响集限制，班次/证据必须齐全 |
| FNB-F03 | 依赖共享/核心合同 | `fnb_settlement`、`fnb_settlement_item`、`fnb_charge_intent` | FOOD-API-056、FOOD-API-058、FOOD-API-140 | 跨根、按人按菜AA、混合支付及会员资产原路退款分配需核心合同；单根成功不能markPaid其他单 |
| FNB-F04 | 依赖共享/核心合同 | `fnb_settlement`、`fnb_settlement_item`、`fnb_charge_intent` | FOOD-API-056、FOOD-API-058、FOOD-API-140 | 跨根、按人按菜AA、混合支付及会员资产原路退款分配需核心合同；单根成功不能markPaid其他单 |
| FNB-F05 | 依赖共享/核心合同 | `fnb_settlement`、`fnb_settlement_item`、`fnb_charge_intent` | FOOD-API-056、FOOD-API-058、FOOD-API-140 | 跨根、按人按菜AA、混合支付及会员资产原路退款分配需核心合同；单根成功不能markPaid其他单 |
| FNB-F06 | 依赖共享/核心合同 | `fnb_charge_intent` | FOOD-API-061 | 折扣抹零免单及税费小费归权威报价/审批；小费保留P4且一期不收超额，不能覆盖旧成交快照 |
| FNB-F07 | 依赖共享/核心合同 | `fnb_charge_intent` | FOOD-API-061 | 折扣抹零免单及税费小费归权威报价/审批；小费保留P4且一期不收超额，不能覆盖旧成交快照 |
| FNB-F08 | 依赖共享/核心合同 | `fnb_charge_intent`、`fnb_room_charge_intent` | FOOD-API-061、FOOD-API-091 | 企业挂账及房账须权威应收/信用核销合同；不能通过餐饮调整伪装已付款 |
| FNB-F09 | 依赖共享/核心合同 | 共享服务/运行合同 | 待专项合同，不假造端点 | 押金器具租赁引用共享资金/资源合同，当前未设计完整动作和引用模型；押金不得计营收，须专项细化后启用 |
| FNB-F10 | 依赖共享/核心合同 | 共享服务/运行合同 | 待专项合同，不假造端点 | 税务发票/电子收据/冲红由共享票据服务提供正式合同；普通打印不是发票 |

### MOD-06 退餐与售后管理

| 需求 | 对齐状态 | 表/权威事实 | API编号 | 结论与残余工作 |
|---|---|---|---|---|
| FNB-G01 | 依赖共享/核心合同 | `fnb_check_cart_item`、`fnb_order_batch` | FOOD-API-040、FOOD-API-124、FOOD-API-126 | 草稿归档已定义；正式取消受核心支付/制作守卫约束 |
| FNB-G02 | 依赖共享/核心合同 | `fnb_return`、`fnb_return_item` | FOOD-API-062、FOOD-API-063、FOOD-API-064、FOOD-API-136、FOOD-API-137 | 当前只可整门店单全额；未付应收调整/部分退分摊均需合同。退款按原请求恢复，厨房结果分轨 |
| FNB-G03 | 依赖共享/核心合同 | `fnb_return`、`fnb_return_item` | FOOD-API-062、FOOD-API-063、FOOD-API-064、FOOD-API-136、FOOD-API-137 | 当前只可整门店单全额；未付应收调整/部分退分摊均需合同。退款按原请求恢复，厨房结果分轨 |
| FNB-G04 | 依赖共享/核心合同 | `fnb_return`、`fnb_return_item` | FOOD-API-062、FOOD-API-063、FOOD-API-064、FOOD-API-136、FOOD-API-137 | 当前只可整门店单全额；未付应收调整/部分退分摊均需合同。退款按原请求恢复，厨房结果分轨 |
| FNB-G05 | 依赖共享/核心合同 | `fnb_return`、`fnb_return_item` | FOOD-API-062、FOOD-API-063、FOOD-API-064、FOOD-API-136、FOOD-API-137 | 当前只可整门店单全额；未付应收调整/部分退分摊均需合同。退款按原请求恢复，厨房结果分轨 |
| FNB-G06 | 尚缺专项设计 | `fnb_action_log`、`fnb_order_batch` | FOOD-API-108、FOOD-API-125 | 错单重开/离线补录认领关系及申请审批命令未定义，不能删除旧单重造销量 |
| FNB-G07 | 依赖共享/核心合同 | `fnb_close_adjustment`、`fnb_charge_intent` | FOOD-API-076、FOOD-API-061 | 关账后更正有模型；反结账和坏账必须权威应收审批合同，不重写原签核 |
| FNB-G08 | 依赖共享/核心合同 | `fnb_action_log` | FOOD-API-108 | 审计表与查询已设计；复用全平台不可篡改审计存储/权限，餐饮本地日志本身不构成不可抵赖证据 |

### MOD-07 打印、通知与设备管理

| 需求 | 对齐状态 | 表/权威事实 | API编号 | 结论与残余工作 |
|---|---|---|---|---|
| FNB-A08 | 尚缺专项设计 | `prn_device`、`prn_route` | FOOD-API-065、FOOD-API-066 | 设备自检/开班检查聚合查询尚无明确请求响应；不可用打印任务接口代替，模块07实施前补齐 |
| FNB-H01 | 尚缺专项设计 | `prn_device`、`prn_template`、`prn_route` | FOOD-API-065 | 打印配置表已设计，设备绑定/模板版本/路由维护与测试端点尚缺明确共享合同 |
| FNB-H02 | 依赖共享/核心合同 | `prn_job`、`prn_template` | FOOD-API-065、FOOD-API-066、FOOD-API-067 | 内容由餐饮冻结业务凭据，投递归共享打印；日结/交班票依赖对应模块真实版本 |
| FNB-H03 | 依赖共享/核心合同 | `prn_job`、`prn_template` | FOOD-API-065、FOOD-API-066、FOOD-API-067 | 内容由餐饮冻结业务凭据，投递归共享打印；日结/交班票依赖对应模块真实版本 |
| FNB-H04 | 依赖共享/核心合同 | `prn_job`、`prn_template` | FOOD-API-065、FOOD-API-066、FOOD-API-067 | 内容由餐饮冻结业务凭据，投递归共享打印；日结/交班票依赖对应模块真实版本 |
| FNB-H05 | 目标已定义 | `prn_job`、`prn_attempt` | FOOD-API-066、FOOD-API-067 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-H06 | 尚缺专项设计 | `prn_device` | 待专项合同，不假造端点 | KDS订阅/通知/语音和远程设备诊断权限、配置下发、状态失效合同未定义；不能以任务查询代替推送治理 |
| FNB-H07 | 尚缺专项设计 | `prn_device` | 待专项合同，不假造端点 | KDS订阅/通知/语音和远程设备诊断权限、配置下发、状态失效合同未定义；不能以任务查询代替推送治理 |

### MOD-08 班次与现金管理

| 需求 | 对齐状态 | 表/权威事实 | API编号 | 结论与残余工作 |
|---|---|---|---|---|
| FNB-I01 | 目标已定义 | `fnb_cash_shift`、`fnb_cash_entry`、`fnb_cash_payment_link`、`fnb_cash_count` | FOOD-API-068、FOOD-API-069、FOOD-API-070、FOOD-API-071 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-I02 | 目标已定义 | `fnb_cash_shift`、`fnb_cash_entry`、`fnb_cash_payment_link`、`fnb_cash_count` | FOOD-API-068、FOOD-API-069、FOOD-API-070、FOOD-API-071 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-I03 | 目标已定义 | `fnb_cash_shift`、`fnb_cash_entry`、`fnb_cash_payment_link`、`fnb_cash_count` | FOOD-API-068、FOOD-API-069、FOOD-API-070、FOOD-API-071 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-I07 | 尚缺专项设计 | `fnb_cash_shift`、`fnb_close_exception` | FOOD-API-071、FOOD-API-074 | 班次交接有入口，跨班未收责任移交/认领需细化非现金应收责任合同，不迁移原营业日 |

### MOD-09 营业日、日结与对账管理

| 需求 | 对齐状态 | 表/权威事实 | API编号 | 结论与残余工作 |
|---|---|---|---|---|
| FNB-I04 | 依赖共享/核心合同 | `fnb_business_day`、`fnb_close_run`、`fnb_close_balance`、`fnb_close_exception` | FOOD-API-072、FOOD-API-073 | 预结快照引用权威对账和支付证据；通道账期不等同营业日 |
| FNB-I05 | 依赖共享/核心合同 | `fnb_business_day`、`fnb_close_run`、`fnb_close_balance`、`fnb_close_exception` | FOOD-API-072、FOOD-API-073 | 预结快照引用权威对账和支付证据；通道账期不等同营业日 |
| FNB-I06 | 目标已定义 | `fnb_close_run`、`fnb_close_balance` | FOOD-API-072、FOOD-API-073、FOOD-API-075 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-I08 | 依赖共享/核心合同 | `fnb_close_exception`、`fnb_close_run` | FOOD-API-074、FOOD-API-075 | 例外需获准审批；资金UNKNOWN只能移交证据，不能改成成功 |
| FNB-I09 | 目标已定义 | `fnb_close_adjustment`、`fnb_close_run` | FOOD-API-076 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-I10 | 依赖共享/核心合同 | `fnb_report_run`、`fnb_close_run` | FOOD-API-080 | 复用任务水位/幂等补跑；投影重建不等于重新签核或重开钱箱 |

### MOD-10 经营报表管理

| 需求 | 对齐状态 | 表/权威事实 | API编号 | 结论与残余工作 |
|---|---|---|---|---|
| FNB-J01 | 目标已定义 | `fnb_metric_definition`、`fnb_report_run`、`rpt_food_metric_daily` | FOOD-API-077、FOOD-API-078、FOOD-API-079、FOOD-API-080 | 按受控reportCode及指标版本查询，权限水位币种时间基准明确；细分维度实施前登记指标定义 |
| FNB-J02 | 目标已定义 | `fnb_metric_definition`、`fnb_report_run`、`rpt_food_metric_daily` | FOOD-API-077、FOOD-API-078、FOOD-API-079、FOOD-API-080 | 按受控reportCode及指标版本查询，权限水位币种时间基准明确；细分维度实施前登记指标定义 |
| FNB-J03 | 目标已定义 | `fnb_metric_definition`、`fnb_report_run`、`rpt_food_metric_daily` | FOOD-API-077、FOOD-API-078、FOOD-API-079、FOOD-API-080 | 按受控reportCode及指标版本查询，权限水位币种时间基准明确；细分维度实施前登记指标定义 |
| FNB-J04 | 依赖共享/核心合同 | `fnb_metric_definition`、`fnb_report_run`、`rpt_food_metric_daily` | FOOD-API-077、FOOD-API-078、FOOD-API-079、FOOD-API-080 | 应收账龄依赖共享AR核销流水 |
| FNB-J05 | 目标已定义 | `fnb_metric_definition`、`fnb_report_run`、`rpt_food_metric_daily` | FOOD-API-077、FOOD-API-078、FOOD-API-079、FOOD-API-080 | 按受控reportCode及指标版本查询，权限水位币种时间基准明确；细分维度实施前登记指标定义 |
| FNB-J06 | 目标已定义 | `fnb_metric_definition`、`fnb_report_run`、`rpt_food_metric_daily` | FOOD-API-077、FOOD-API-078、FOOD-API-079、FOOD-API-080 | 按受控reportCode及指标版本查询，权限水位币种时间基准明确；细分维度实施前登记指标定义 |
| FNB-J07 | 目标已定义 | `fnb_metric_definition`、`fnb_report_run`、`rpt_food_metric_daily` | FOOD-API-077、FOOD-API-078、FOOD-API-079、FOOD-API-080 | 按受控reportCode及指标版本查询，权限水位币种时间基准明确；细分维度实施前登记指标定义 |
| FNB-J08 | 目标已定义 | `fnb_metric_definition`、`fnb_report_run`、`rpt_food_metric_daily` | FOOD-API-077、FOOD-API-078、FOOD-API-079、FOOD-API-080 | 按受控reportCode及指标版本查询，权限水位币种时间基准明确；细分维度实施前登记指标定义 |
| FNB-J09 | 目标已定义 | `fnb_metric_definition`、`fnb_report_run`、`rpt_food_metric_daily` | FOOD-API-077、FOOD-API-078、FOOD-API-079、FOOD-API-080 | 按受控reportCode及指标版本查询，权限水位币种时间基准明确；细分维度实施前登记指标定义 |
| FNB-J10 | 依赖共享/核心合同 | `fnb_metric_definition`、`fnb_report_run`、`rpt_food_metric_daily` | FOOD-API-077、FOOD-API-078、FOOD-API-079、FOOD-API-080 | 会员充值/本金/赠金/消费沿资产权威，不重复记销售 |
| FNB-J11 | 依赖共享/核心合同 | `fnb_metric_definition`、`fnb_report_run`、`rpt_food_metric_daily`、`fnb_recipe`、`fnb_recipe_line`、`fnb_recipe_adjustment`、`fnb_production_consumption`、`fnb_production_consumption_line` | FOOD-API-077、FOOD-API-078、FOOD-API-079、FOOD-API-080 | 配方/制作理论耗用/真实回执分开；单位数量及历史估值合同PENDING，当前不能把领料当逐菜实耗或估算为实际成本。 |
| FNB-J12 | 依赖共享/核心合同 | `fnb_metric_definition`、`fnb_report_run`、`rpt_food_metric_daily` | FOOD-API-077、FOOD-API-078、FOOD-API-079、FOOD-API-080 | 预约排队/客诉的权威事实及分母合同需补齐 |
| FNB-J13 | 目标已定义 | `fnb_metric_definition`、`fnb_report_run`、`rpt_food_metric_daily` | FOOD-API-077、FOOD-API-078、FOOD-API-079、FOOD-API-080 | 按受控reportCode及指标版本查询，权限水位币种时间基准明确；细分维度实施前登记指标定义 |
| FNB-L06 | 依赖共享/核心合同 | `fnb_metric_definition`、`rpt_food_metric_daily` | FOOD-API-077、FOOD-API-078、FOOD-API-079 | 专项报表只对已开通能力开放，依赖对应事实和口径定义 |

### MOD-11 专项业态管理

| 需求 | 对齐状态 | 表/权威事实 | API编号 | 结论与残余工作 |
|---|---|---|---|---|
| FNB-D10 | 依赖共享/核心合同 | `fnb_banquet_plan` | FOOD-API-087、FOOD-API-088 | 宴会保证人数、实际加席及收费依赖预约/报价资金合同；冻结菜单与计划 |
| FNB-L01 | 依赖共享/核心合同 | `fnb_dining_entitlement`、`fnb_charge_intent` | FOOD-API-085、FOOD-API-086、FOOD-API-061 | 人数限时规则已有；频控/最后点单在提交守卫校验，超时浪费费用需核心报价和审批 |
| FNB-L02 | 依赖共享/核心合同 | `fnb_dining_entitlement`、`fnb_charge_intent` | FOOD-API-085、FOOD-API-086、FOOD-API-061 | 人数限时规则已有；频控/最后点单在提交守卫校验，超时浪费费用需核心报价和审批 |
| FNB-L03 | 依赖共享/核心合同 | `fnb_dining_entitlement`、`fnb_charge_intent` | FOOD-API-085、FOOD-API-086、FOOD-API-061 | 人数限时规则已有；频控/最后点单在提交守卫校验，超时浪费费用需核心报价和审批 |
| FNB-L04 | 依赖共享/核心合同 | `fnb_bottle_custody`、`fnb_custody_entry` | FOOD-API-089、FOOD-API-090 | 寄存归属及授权须实名/凭据合同；超领CAS拒绝，报损不等同销售 |
| FNB-L05 | 依赖共享/核心合同 | `fnb_room_charge_intent` | FOOD-API-091 | PMS授权、应收转挂及原键查询撤销需专项合同，UNKNOWN不能结清 |

### MOD-12 第三方接入与离线营业

| 需求 | 对齐状态 | 表/权威事实 | API编号 | 结论与残余工作 |
|---|---|---|---|---|
| FNB-K02 | 尚缺专项设计 | `fnb_external_mapping`、`fnb_integration_exception` | FOOD-API-082、FOOD-API-083 | 仅映射与异常查询，第三方订单导入/回调鉴权/商品同步/配送及对账适配仍需选定供应商协议；不能宣称已完整接入 |
| FNB-K03 | 尚缺专项设计 | `fnb_edge_lease`、`fnb_sync_batch`、`fnb_integration_exception` | FOOD-API-084 | 批次认领已有；租约获取/续租/隔离、离线可售额度与冲突处理协议未完整定义，需专项合同并实机演练 |
| FNB-K04 | 尚缺专项设计 | `fnb_edge_lease`、`fnb_sync_batch`、`fnb_integration_exception` | FOOD-API-084 | 批次认领已有；租约获取/续租/隔离、离线可售额度与冲突处理协议未完整定义，需专项合同并实机演练 |
| FNB-K05 | 依赖共享/核心合同 | `fnb_store_policy` | FOOD-API-081 | 双部署本地远程同合同、开关及版本灰度需装配/CI验证；HTTP接口不能证明可独立部署 |
| FNB-K06 | 依赖共享/核心合同 | `fnb_store_policy` | FOOD-API-081 | 双部署本地远程同合同、开关及版本灰度需装配/CI验证；HTTP接口不能证明可独立部署 |
| FNB-K07 | 依赖共享/核心合同 | `fnb_action_log` | FOOD-API-081、FOOD-API-083、FOOD-API-108 | 沿底座隔离、脱敏、审计和导出权限；平台诊断无营业收退款权 |
| FNB-K08 | 依赖共享/核心合同 | 共享服务/运行合同 | 待专项合同，不假造端点 | 备份恢复告警灾备为运行合同和演练，不能伪造餐饮CRUD接口覆盖 |

### MOD-13 营业配置与权限治理

| 需求 | 对齐状态 | 表/权威事实 | API编号 | 结论与残余工作 |
|---|---|---|---|---|
| FNB-A01 | 依赖共享/核心合同 | `fnb_store_policy`、`fnb_action_log` | FOOD-API-001、FOOD-API-081 | 沿现有身份/门店授权及共享审批；金额阈值和权限种子需实施时逐项登记，不能由客户端自报主体或审批人 |
| FNB-A02 | 目标已定义 | `fnb_store_policy`、`fnb_service_calendar`、`fnb_business_day` | FOOD-API-001、FOOD-API-002、FOOD-API-003 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-A03 | 目标已定义 | `fnb_store_policy`、`fnb_config_revision` | FOOD-API-001、FOOD-API-002、FOOD-API-081 | 结构与目标接口已定义；仍需实现与业务验收 |
| FNB-A05 | 依赖共享/核心合同 | `fnb_store_policy`、`fnb_check_cart` | FOOD-API-001、FOOD-API-002、FOOD-API-038、FOOD-API-041 | 渠道/履约/先后付独立字段；核心需确认堂食和后付可用合同 |
| FNB-A06 | 依赖共享/核心合同 | `fnb_store_policy`、`fnb_service_calendar`、`fnb_sales_restriction` | FOOD-API-002、FOOD-API-003、FOOD-API-022 | 暂停/节日限制可配置；全局结构化营业时间与接单容量依赖共享合同，当前openHours文本不能假定覆盖多时段多时区硬校验 |
| FNB-A07 | 依赖共享/核心合同 | `fnb_store_policy`、`fnb_action_log` | FOOD-API-001、FOOD-API-081 | 沿现有身份/门店授权及共享审批；金额阈值和权限种子需实施时逐项登记，不能由客户端自报主体或审批人 |

## 九、增强跨域合同（目标交易合同与已实现只读管理分开验收）

字段类型见上方DTO字典。主体、渠道由可信服务身份解析；终端不可自报价格策略授权。

| 权威 | 输入DTO | 输出DTO | 实现状态 | 失败与恢复 |
|---|---|---|---|---|
| pricing / PLATFORM product/quote | ChannelQuoteReq | ChannelQuoteResult | PENDING | BAD_REQUEST/FORBIDDEN/NOT_FOUND/CONFLICT/UNAVAILABLE/UNKNOWN；用原requestKey/operationRef查询UNKNOWN；不可另起业务键重发 |
| inventory / INVENTORY | MaterialConsumptionReq | MaterialConsumptionResult | PENDING | BAD_REQUEST/FORBIDDEN/NOT_FOUND/CONFLICT/UNAVAILABLE/UNKNOWN；用原requestKey/operationRef查询UNKNOWN；不可另起业务键重发 |
| specValues / PLATFORM original product/spec dictionary | SpecValueCatalogInternalApi.BatchLookup | SpecValueCatalogQueryPort.SpecValueFact[] | IMPLEMENTED_IDENTITY_ONLY | BAD_REQUEST/FORBIDDEN/NOT_FOUND/CONFLICT/UNAVAILABLE；纯只读查询，无写入UNKNOWN；权威不可用时失败，不伪造空数组 |

Keeta：首批规划渠道，按用户决定暂不接入；不注册真实适配器或同步任务。真实报价和库存合同未验收前，增强交易保持关闭。

## 十、历史结账与售后切片（保留原验收范围）

完整目标保留在前文。以下保留既有切片记录，原证据见开发计划§2.4和§6；本批明确排除支付执行。POSTPAY及当前非支付实际路由见第十一节，历史切片不改标为本批全部完成。

| 接口 | 输入 | 输出 | 授权及剩余边界 |
|---|---|---|---|
| `POST /biz/x/food/stores/{storeNo}/quotes` | DiningQuoteReq{cartNo,version,selection?:{couponNos,storedValueAccountNo,pointsToUse}}；Idempotency-Key | PricingCheckoutPort.QuoteVO{quoteNo,status,expiresAt,sourceVersion,sourceHash,currency,lines,totals,confirmationDigest} | biz:order:view；认证主体/当前门店由FoodBizStoreScope解析；actor来自登录；不授予资金写权限；完整原购物车受控报价；权益/活动及复杂场景按owner门控；不接原目标lines/buyer/价格字段 |
| `GET /biz/x/food/quotes/{quoteNo}` | query cartNo,version | PricingCheckoutPort.QuoteVO{quoteNo,status,expiresAt,sourceVersion,sourceHash,currency,lines,totals,confirmationDigest} | biz:order:view；认证主体/当前门店由FoodBizStoreScope解析；actor来自登录；不授予资金写权限；绑定原车及版本读取，不是任意报价号授权 |
| `POST /biz/x/food/quotes/{quoteNo}:confirm` | DiningQuoteConfirmReq{cartNo,version,confirmationDigest} | PricingCheckoutPort.QuoteVO{quoteNo,status,expiresAt,sourceVersion,sourceHash,currency,lines,totals,confirmationDigest} | biz:order:view；认证主体/当前门店由FoodBizStoreScope解析；actor来自登录；不授予资金写权限；仅revalidate；不扣款/核销权益，不代替正式提交 |
| `POST /mp/x/food/stores/{storeNo}/quotes` | DiningQuoteReq{cartNo,version,selection?:{couponNos,storedValueAccountNo,pointsToUse}}；Idempotency-Key；optional query sessionNo/header X-Food-Session | PricingCheckoutPort.QuoteVO{quoteNo,status,expiresAt,sourceVersion,sourceHash,currency,lines,totals,confirmationDigest} | CartAccess由consumer登录或原sessionNo/X-Food-Session生成；原购物车服务逐次鉴权；完整原购物车受控报价；权益/活动及复杂场景按owner门控；不接原目标lines/buyer/价格字段 |
| `GET /mp/x/food/quotes/{quoteNo}` | query cartNo,version；optional query sessionNo/header X-Food-Session | PricingCheckoutPort.QuoteVO{quoteNo,status,expiresAt,sourceVersion,sourceHash,currency,lines,totals,confirmationDigest} | CartAccess由consumer登录或原sessionNo/X-Food-Session生成；原购物车服务逐次鉴权；绑定原车及版本读取，不是任意报价号授权 |
| `POST /mp/x/food/quotes/{quoteNo}:confirm` | DiningQuoteConfirmReq{cartNo,version,confirmationDigest}；optional query sessionNo/header X-Food-Session | PricingCheckoutPort.QuoteVO{quoteNo,status,expiresAt,sourceVersion,sourceHash,currency,lines,totals,confirmationDigest} | CartAccess由consumer登录或原sessionNo/X-Food-Session生成；原购物车服务逐次鉴权；仅revalidate；不扣款/核销权益，不代替正式提交 |
| `POST /mp/x/food/stores/{storeNo}/batches` | CreateDiningOrderReq{cartNo,version,quoteNo,confirmationDigest,payMode}；Idempotency-Key；optional session/token经原鉴权 | DiningOrderBatchVO{batchNo,storeNo,checkNo,cartNo,cartVersion,quoteNo,status,payMode,businessDate,orderNo,subOrderNo,coreStatus,currency,amountMinor,version} | 仅登录consumer本人；服务端核验原批次/订单归属；无匿名session付款或客户端buyer；仅H5/PICKUP/PREPAY、MAIN整数完整车；DTO接受POSTPAY但服务拒绝；无selectedLineRefs/splitByPerson |
| `GET /mp/x/food/batches/{batchNo}` | optional query sessionNo/header X-Food-Session | DiningOrderBatchVO{batchNo,storeNo,checkNo,cartNo,cartVersion,quoteNo,status,payMode,businessDate,orderNo,subOrderNo,coreStatus,currency,amountMinor,version} | CartAccess由consumer登录或原sessionNo/X-Food-Session生成；原购物车服务逐次鉴权；原本人范围；UNKNOWN不是受理/支付成功 |
| `GET /biz/x/food/batches/{batchNo}` | 无body | DiningOrderBatchVO{batchNo,storeNo,checkNo,cartNo,cartVersion,quoteNo,status,payMode,businessDate,orderNo,subOrderNo,coreStatus,currency,amountMinor,version} | biz:order:view；认证主体/当前门店由FoodBizStoreScope解析；actor来自登录；不授予资金写权限；B端042正式提交仍未实现 |
| `POST /mp/x/food/batches/{batchNo}/settlements` | CreateDiningSettlementReq{confirmationDigest}；Idempotency-Key | DiningSettlementVO{settlementNo,storeNo,batchNo,checkNo,orderNo,subOrderNo,status,currency,dueMinor,scopeHash,confirmationDigest,version,batchStatus,coreOrderStatus,payment,readyToAccept} | 仅登录consumer本人；服务端核验原批次/订单归属；无匿名session付款或客户端buyer；单完整原批次/单支付根增量；不实现140台账范围冻结，不支持AA/整桌代付/跨根 |
| `GET /mp/x/food/settlements/{settlementNo}` | 无body/query | DiningSettlementVO{settlementNo,storeNo,batchNo,checkNo,orderNo,subOrderNo,status,currency,dueMinor,scopeHash,confirmationDigest,version,batchStatus,coreOrderStatus,payment,readyToAccept} | 仅登录consumer本人；服务端核验原批次/订单归属；无匿名session付款或客户端buyer；customerQuery查询原支付并合并持久状态，非纯本地只读；无目标confirmedAmount/pendingIntentNos字段 |
| `POST /mp/x/food/settlements/{settlementNo}:pay` | PayDiningSettlementReq{version,confirmationDigest,payChannel}；Idempotency-Key | DiningSettlementVO{settlementNo,storeNo,batchNo,checkNo,orderNo,subOrderNo,status,currency,dueMinor,scopeHash,confirmationDigest,version,batchStatus,coreOrderStatus,payment,readyToAccept} | 仅登录consumer本人；服务端核验原批次/订单归属；无匿名session付款或客户端buyer；canonical路由一致；仅真实owner支持线上渠道；无客户端amount/tool/现金；单根单尝试，UNKNOWN按原意图查回 |
| `POST /mp/x/food/settlements/{settlementNo}:finalize` | 无body/query | DiningSettlementVO{settlementNo,storeNo,batchNo,checkNo,orderNo,subOrderNo,status,currency,dueMinor,scopeHash,confirmationDigest,version,batchStatus,coreOrderStatus,payment,readyToAccept} | 仅登录consumer本人；服务端核验原批次/订单归属；无匿名session付款或客户端buyer；依原核心支付/库存证明推进；厨房实际处理独立；非B端060 |
| `POST /mp/x/food/settlements/{settlementNo}:cancel` | DiningSettlementVersionReq{version} | DiningSettlementVO{settlementNo,storeNo,batchNo,checkNo,orderNo,subOrderNo,status,currency,dueMinor,scopeHash,confirmationDigest,version,batchStatus,coreOrderStatus,payment,readyToAccept} | 仅登录consumer本人；服务端核验原批次/订单归属；无匿名session付款或客户端buyer；受未知/成功支付仲裁约束；非B端059，不以本地状态冒充渠道关单 |
| `GET /biz/x/food/settlements/{settlementNo}` | 无body | DiningSettlementVO{settlementNo,storeNo,batchNo,checkNo,orderNo,subOrderNo,status,currency,dueMinor,scopeHash,confirmationDigest,version,batchStatus,coreOrderStatus,payment,readyToAccept} | biz:order:view；认证主体/当前门店由FoodBizStoreScope解析；actor来自登录；不授予资金写权限；仅门店读取；056/058/059/060/061写入口未实现；响应非目标包装 |
| `POST /mp/x/food/stores/{storeNo}/returns` | DiningReturnApplyReq{batchNo,type:REFUND_ONLY/RETURN_REFUND,reason,reasonCode?,images[],lines:[{sourceLineRef,quantity}]}；Idempotency-Key | DiningReturnVO{returnNo,batchNo,storeNo,status,financialStatus,kitchenStatus,coreCaseRef,reason,lines,financialCase}；没有version | 仅登录consumer本人；服务端核验原批次/订单归属；无匿名session付款或客户端buyer；选中原MAIN整数行退量，走原核心售后/退款上限；无客户端金额/任意itemId/厨房命令；不实现B审批执行；部分/余量必须原子单WAIT_FULFILL且所有原行shippedQty为0，混合履约拒绝 |
| `GET /mp/x/food/returns/{returnNo}` | 无body/query | DiningReturnVO{returnNo,batchNo,storeNo,status,financialStatus,kitchenStatus,coreCaseRef,reason,lines,financialCase}；没有version | 仅登录consumer本人；服务端核验原批次/订单归属；无匿名session付款或客户端buyer；查询原核心售后并刷新持久状态；financialCase可空；厨房独立，不返回目标version |
| `POST /mp/x/food/returns/{returnNo}:cancel` | 空body且无query | DiningReturnVO{returnNo,batchNo,storeNo,status,financialStatus,kitchenStatus,coreCaseRef,reason,lines,financialCase}；没有version | 仅登录consumer本人；服务端核验原批次/订单归属；无匿名session付款或客户端buyer；撤回本人原售后；未知申请保留CANCEL_PENDING并查原键，不是退款执行 |
| `GET /biz/x/food/returns/{returnNo}` | 无body/query | DiningReturnVO{returnNo,batchNo,storeNo,status,financialStatus,kitchenStatus,coreCaseRef,reason,lines,financialCase}；没有version | biz:order:view；认证主体/当前门店由FoodBizStoreScope解析；actor来自登录；不授予资金写权限；仅本地授权读取，financialCase可空；062/063未实现，不返回目标version |

未实现目标：FOOD-API-042、FOOD-API-056、FOOD-API-058、FOOD-API-059、FOOD-API-060、FOOD-API-061、FOOD-API-062、FOOD-API-063、FOOD-API-140。台账范围结账FOOD-API-140仍未开放；当前按单一原批次冻结，不能代表AA或跨根结账。

## 十一、本批非支付实际API与目标对账

50条实际公开biz路由，均为 **CODED_PENDING_VERIFICATION**。该标记是逐接口完整目标/营业验收状态，后端批次编译及测试结果独立记录在verification和原TDD收据。此处逐项记录现有Controller/DTO/权限，与前文目标API并列；目标路径相同不表示DTO或完整业务验收一致。原始接口编号、表及历史V1迁移哈希保留。

当前差异：POSTPAY仅沿真实已登录H5/PICKUP身份和门店POSTPAY策略，采用原库存lifecycle3接受未付款履约；父单WAIT_PAY、paidAt为空。额度消费、FOOD ACCEPTED及厨房出站事实同本域事务，不代表收款。

- No actual printer hardware implementation
- No KDS/SSE/push subscription or notification delivery proof from polling APIs
- Generic report exports API078/079 and close exception carry API074 have no matching new public route
- Printing and business close target paths differ and require explicit target reconciliation, rather than silently assigning exact implementation status
- POSTPAY仅已登录H5/PICKUP现有身份：真实lifecycle3 reserve/acceptUnpaid，原父单保持WAIT_PAY/paidAt为空，原子单履约可推进；不能称已收款。
- FOOD ACCEPTED、额度consume及DINING_ORDER_ACCEPTED出站事实同本域事务；物理出纸、通知推送与财务验收必须由各owner真回执另证。
- 原料执行只接受真实冻结叶子计划；缺少既有配方权威计划时保留BLOCKED，不能通过成品/原料双扣或虚构成本完成。
- 日结/报表仅输出已核实源事实与完整性状态，财务未闭合不补造SIGNED/资金成功；异步导出和异常结转目标仍未交付。
- 新就座锁定原票后，在同一food primary事务内调用CheckService.openQueuedCheck，仅限内部Java调用，不能经HTTP/remote；原开台、SEATED、queue动作与outbox同提交。冲突整体回滚，原CALLED票可重选；不把永久SEATING作为新流程。
- 旧SEATING仅兼容原冻结命令/OPEN_CHECK原键回执恢复；未知保持未知，不创建替代开台。新seat走同primary原子事务。
- 首次打印身份不包含客户端Idempotency-Key；ORDER/KITCHEN/RETURN按原单据+scene/station固定，PREBILL按原文档snapshot版本；换key同内容不新增首次任务。额外纸张必须走关联原任务+原因+操作人的审计reprint；UNKNOWN禁止普通retry。
- 制作耗用列表API153已编码真实scope-bound SQL分页/COUNT；共享PageData<long page/size>及ConsumptionVO与目标模型差异仍显式登记；查询不触发原料出库、恢复或补造成本。
- DINING_ORDER_ACCEPTED出站处理先幂等持久化原MAKE票，再按已配置ORDER/KITCHEN纸张route入队；无route不造任务。claim/ACK才提供设备领取与回执，自动入队不代表已出纸。
- KDS默认未指定batchNo时仅返回ACCEPTED批次的未CLOSED票和非SERVED/VOIDED任务；显式batchNo可查历史，保留原MAKE/REMAKE及原票关系；轮询不等于实时推送。

### 11.1 实际路由、权限与目标映射

| 实际编号 | 实际方法与路径 | 目标编号/对齐状态 | 请求DTO或参数 | data返回类型 | 实际权限 |
|---|---|---|---|---|---|
| FOOD-NONPAY-IMPL-001 | `POST /biz/x/food/queue-tickets` | FOOD-API-157 / EXACT_PATH | ai.neargo.shop.food.dto.QueueContracts.IssueReq | `ai.neargo.shop.food.dto.QueueContracts.QueueVO` | `biz:verify` |
| FOOD-NONPAY-IMPL-002 | `GET /biz/x/food/queue-tickets` | FOOD-API-158 / EXACT_PATH | query:businessDate LocalDate; query:status String; query:page Integer; query:size Integer | `ai.neargo.shop.common.PageData<ai.neargo.shop.food.dto.QueueContracts.QueueVO>` | `biz:order:view` |
| FOOD-NONPAY-IMPL-003 | `GET /biz/x/food/queue-tickets/{ticketNo}` | FOOD-API-159 / EXACT_PATH | path:ticketNo String | `ai.neargo.shop.food.dto.QueueContracts.QueueVO` | `biz:order:view` |
| FOOD-NONPAY-IMPL-004 | `POST /biz/x/food/queue-tickets:call-next` | FOOD-API-160 / EXACT_PATH | query:businessDate LocalDate | `ai.neargo.shop.food.dto.QueueContracts.QueueVO` | `biz:verify` |
| FOOD-NONPAY-IMPL-005 | `POST /biz/x/food/queue-tickets/{ticketNo}:call` | FOOD-API-161 / EXACT_PATH | ai.neargo.shop.food.dto.QueueContracts.ChangeReq | `ai.neargo.shop.food.dto.QueueContracts.QueueVO` | `biz:verify` |
| FOOD-NONPAY-IMPL-006 | `POST /biz/x/food/queue-tickets/{ticketNo}:cancel` | FOOD-API-162 / EXACT_PATH | ai.neargo.shop.food.dto.QueueContracts.ChangeReq | `ai.neargo.shop.food.dto.QueueContracts.QueueVO` | `biz:verify` |
| FOOD-NONPAY-IMPL-007 | `POST /biz/x/food/queue-tickets/{ticketNo}:no-show` | FOOD-API-163 / EXACT_PATH | ai.neargo.shop.food.dto.QueueContracts.ChangeReq | `ai.neargo.shop.food.dto.QueueContracts.QueueVO` | `biz:verify` |
| FOOD-NONPAY-IMPL-008 | `POST /biz/x/food/queue-tickets/{ticketNo}:seat` | FOOD-API-164 / EXACT_PATH | ai.neargo.shop.food.dto.QueueContracts.SeatQueueReq | `ai.neargo.shop.food.dto.QueueContracts.QueueVO` | `biz:verify` |
| FOOD-NONPAY-IMPL-009 | `POST /biz/x/food/queue-tickets/{ticketNo}:recover-seating` | FOOD-API-165 / EXACT_PATH | path:ticketNo String | `ai.neargo.shop.food.dto.QueueContracts.QueueVO` | `biz:verify` |
| FOOD-NONPAY-IMPL-010 | `GET /biz/x/food/stores/{storeNo}/kitchen/tasks` | FOOD-API-044 / EXACT_PATH | path:storeNo String; query:stationNo String; query:status String; query:page int; query:size int | `ai.neargo.shop.common.PageData<ai.neargo.shop.food.dto.KitchenContracts.TaskVO>` | `biz:receive` |
| FOOD-NONPAY-IMPL-011 | `POST /biz/x/food/tasks/{taskNo}:start` | FOOD-API-045 / EXACT_PATH | ai.neargo.shop.food.dto.KitchenContracts.TaskDeltaReq | `ai.neargo.shop.food.dto.KitchenContracts.EventVO` | `biz:receive` |
| FOOD-NONPAY-IMPL-012 | `POST /biz/x/food/tasks/{taskNo}:ready` | FOOD-API-046 / EXACT_PATH | ai.neargo.shop.food.dto.KitchenContracts.TaskDeltaReq | `ai.neargo.shop.food.dto.KitchenContracts.EventVO` | `biz:receive` |
| FOOD-NONPAY-IMPL-013 | `POST /biz/x/food/tasks/{taskNo}:serve` | FOOD-API-047 / EXACT_PATH | ai.neargo.shop.food.dto.KitchenContracts.TaskDeltaReq | `ai.neargo.shop.food.dto.KitchenContracts.EventVO` | `biz:receive` |
| FOOD-NONPAY-IMPL-014 | `POST /biz/x/food/tasks/{taskNo}:hold` | FOOD-API-048 / EXACT_PATH | ai.neargo.shop.food.dto.KitchenContracts.TaskDeltaReq | `ai.neargo.shop.food.dto.KitchenContracts.EventVO` | `biz:receive` |
| FOOD-NONPAY-IMPL-015 | `POST /biz/x/food/tasks/{taskNo}:fire` | FOOD-API-049 / EXACT_PATH | ai.neargo.shop.food.dto.KitchenContracts.TaskDeltaReq | `ai.neargo.shop.food.dto.KitchenContracts.EventVO` | `biz:receive` |
| FOOD-NONPAY-IMPL-016 | `POST /biz/x/food/tasks/{taskNo}:urge` | FOOD-API-050 / EXACT_PATH | ai.neargo.shop.food.dto.KitchenContracts.TaskDeltaReq | `ai.neargo.shop.food.dto.KitchenContracts.EventVO` | `biz:receive` |
| FOOD-NONPAY-IMPL-017 | `POST /biz/x/food/tasks/{taskNo}:void` | FOOD-API-051 / EXACT_PATH | ai.neargo.shop.food.dto.KitchenContracts.TaskDeltaReq | `ai.neargo.shop.food.dto.KitchenContracts.EventVO` | `biz:aftersale` |
| FOOD-NONPAY-IMPL-018 | `POST /biz/x/food/tasks/{taskNo}:loss` | 新增，目标未编号 / ADDED_IMPLEMENTATION_NO_TARGET_API | ai.neargo.shop.food.dto.KitchenContracts.TaskDeltaReq | `ai.neargo.shop.food.dto.KitchenContracts.EventVO` | `biz:stock` |
| FOOD-NONPAY-IMPL-019 | `POST /biz/x/food/tasks/{taskNo}:remake` | FOOD-API-052 / EXACT_PATH | ai.neargo.shop.food.dto.KitchenContracts.RemakeReq | `ai.neargo.shop.food.dto.KitchenContracts.TicketVO` | `biz:aftersale` |
| FOOD-NONPAY-IMPL-020 | `GET /biz/x/food/tasks/{taskNo}/events` | 新增，目标未编号 / ADDED_IMPLEMENTATION_NO_TARGET_API | path:taskNo String | `java.util.List<ai.neargo.shop.food.dto.KitchenContracts.EventVO>` | `biz:receive` |
| FOOD-NONPAY-IMPL-021 | `POST /biz/x/food/checks/{checkNo}/courses` | FOOD-API-053 / EXACT_PATH | ai.neargo.shop.food.dto.KitchenContracts.CourseReq | `ai.neargo.shop.food.dto.KitchenContracts.CourseVO` | `biz:receive` |
| FOOD-NONPAY-IMPL-022 | `GET /biz/x/food/checks/{checkNo}/courses` | 新增，目标未编号 / ADDED_IMPLEMENTATION_NO_TARGET_API | path:checkNo String | `java.util.List<ai.neargo.shop.food.dto.KitchenContracts.CourseVO>` | `biz:receive` |
| FOOD-NONPAY-IMPL-023 | `POST /biz/x/food/courses/{courseNo}:act` | 新增，目标未编号 / ADDED_IMPLEMENTATION_NO_TARGET_API | ai.neargo.shop.food.dto.KitchenContracts.CourseActionReq | `ai.neargo.shop.food.dto.KitchenContracts.CourseVO` | `biz:receive` |
| FOOD-NONPAY-IMPL-024 | `POST /biz/x/food/courses/{courseNo}:fire` | FOOD-API-054 / EXACT_PATH | ai.neargo.shop.food.dto.KitchenContracts.CourseFireReq | `ai.neargo.shop.food.dto.KitchenContracts.CourseVO` | `biz:receive` |
| FOOD-NONPAY-IMPL-025 | `POST /biz/x/food/batches/{batchNo}:handover` | FOOD-API-055 / EXACT_PATH | ai.neargo.shop.food.dto.KitchenContracts.HandoverReq | `ai.neargo.shop.food.dto.KitchenContracts.HandoverVO` | `biz:verify` |
| FOOD-NONPAY-IMPL-026 | `GET /biz/x/food/batches/{batchNo}/pickup-verification` | 新增，目标未编号 / ADDED_IMPLEMENTATION_NO_TARGET_API | path:batchNo String | `ai.neargo.shop.food.dto.DiningPickupContracts.PickupVerificationVO` | `biz:verify` |
| FOOD-NONPAY-IMPL-027 | `POST /biz/x/food/batches/{batchNo}:verify-pickup` | 新增，目标未编号 / ADDED_IMPLEMENTATION_NO_TARGET_API | ai.neargo.shop.food.dto.DiningPickupContracts.VerifyPickupReq | `ai.neargo.shop.food.dto.DiningPickupContracts.PickupVerificationVO` | `biz:verify` |
| FOOD-NONPAY-IMPL-028 | `POST /biz/x/food/print-jobs` | FOOD-API-065 / PATH_CHANGED | ai.neargo.shop.food.dto.PrintDocumentReq | `java.util.List<ai.neargo.shop.spi.printing.PrintPort.JobVO>` | `biz:verify` |
| FOOD-NONPAY-IMPL-029 | `GET /biz/x/food/print-jobs` | 新增，目标未编号 / ADDED_IMPLEMENTATION_NO_TARGET_API | query:afterJobNo String; query:limit int | `java.util.List<ai.neargo.shop.spi.printing.PrintPort.JobVO>` | `biz:order:view` |
| FOOD-NONPAY-IMPL-030 | `GET /biz/x/food/print-jobs/{jobNo}` | FOOD-API-066 / PATH_CHANGED | path:jobNo String | `ai.neargo.shop.spi.printing.PrintPort.JobVO` | `biz:order:view` |
| FOOD-NONPAY-IMPL-031 | `POST /biz/x/food/print-jobs/{jobNo}:retry` | 新增，目标未编号 / ADDED_IMPLEMENTATION_NO_TARGET_API | path:jobNo String | `ai.neargo.shop.spi.printing.PrintPort.JobVO` | `biz:verify` |
| FOOD-NONPAY-IMPL-032 | `POST /biz/x/food/print-jobs/{jobNo}:reprint` | FOOD-API-067 / PATH_CHANGED | ai.neargo.shop.food.api.biz.BizPrintJobController.ReprintReq | `ai.neargo.shop.spi.printing.PrintPort.JobVO` | `biz:verify` |
| FOOD-NONPAY-IMPL-033 | `POST /biz/x/food/print-devices` | 新增，目标未编号 / ADDED_IMPLEMENTATION_NO_TARGET_API | ai.neargo.shop.spi.printing.PrintPort.DeviceReq | `ai.neargo.shop.spi.printing.PrintPort.DeviceVO` | `biz:store:admin` |
| FOOD-NONPAY-IMPL-034 | `POST /biz/x/food/print-devices/{deviceNo}:heartbeat` | 新增，目标未编号 / ADDED_IMPLEMENTATION_NO_TARGET_API | ai.neargo.shop.food.api.biz.BizPrintDeviceController.HeartbeatReq | `ai.neargo.shop.spi.printing.PrintPort.DeviceVO` | `biz:verify` |
| FOOD-NONPAY-IMPL-035 | `POST /biz/x/food/print-devices/{deviceNo}:claim` | 新增，目标未编号 / ADDED_IMPLEMENTATION_NO_TARGET_API | path:deviceNo String | `ai.neargo.shop.spi.printing.PrintPort.Claim` | `biz:verify` |
| FOOD-NONPAY-IMPL-036 | `POST /biz/x/food/print-devices/{deviceNo}:acknowledge` | 新增，目标未编号 / ADDED_IMPLEMENTATION_NO_TARGET_API | ai.neargo.shop.spi.printing.PrintPort.Acknowledge | `ai.neargo.shop.spi.printing.PrintPort.JobVO` | `biz:verify` |
| FOOD-NONPAY-IMPL-037 | `GET /biz/x/food/print-configuration` | 新增，目标未编号 / ADDED_IMPLEMENTATION_NO_TARGET_API | 无Body | `ai.neargo.shop.spi.printing.PrintPort.Configuration` | `biz:store:admin` |
| FOOD-NONPAY-IMPL-038 | `POST /biz/x/food/print-configuration/templates` | 新增，目标未编号 / ADDED_IMPLEMENTATION_NO_TARGET_API | ai.neargo.shop.spi.printing.PrintPort.TemplateReq | `ai.neargo.shop.spi.printing.PrintPort.TemplateReq` | `biz:store:admin` |
| FOOD-NONPAY-IMPL-039 | `POST /biz/x/food/print-configuration/routes` | 新增，目标未编号 / ADDED_IMPLEMENTATION_NO_TARGET_API | ai.neargo.shop.spi.printing.PrintPort.RouteReq | `ai.neargo.shop.spi.printing.PrintPort.RouteReq` | `biz:store:admin` |
| FOOD-NONPAY-IMPL-040 | `POST /biz/x/food/business-days/{date}:open` | 新增，目标未编号 / ADDED_IMPLEMENTATION_NO_TARGET_API | path:date LocalDate | `ai.neargo.shop.food.dto.BusinessCloseContracts.DayVO` | `biz:finance` |
| FOOD-NONPAY-IMPL-041 | `GET /biz/x/food/reports/business-day` | FOOD-API-077 / PARTIAL_TARGET_SUBSET | query:businessDate LocalDate; query:currency String | `ai.neargo.shop.food.dto.BusinessCloseContracts.ReportVO` | `biz:order:view` |
| FOOD-NONPAY-IMPL-042 | `POST /biz/x/food/closes:preview` | FOOD-API-072 / PATH_AND_INPUT_CHANGED | ai.neargo.shop.food.dto.BusinessCloseContracts.PreviewReq | `ai.neargo.shop.food.dto.BusinessCloseContracts.CloseVO` | `biz:finance` |
| FOOD-NONPAY-IMPL-043 | `GET /biz/x/food/closes/{closeNo}` | FOOD-API-073 / EXACT_PATH | path:closeNo String | `ai.neargo.shop.food.dto.BusinessCloseContracts.CloseVO` | `biz:order:view` |
| FOOD-NONPAY-IMPL-044 | `POST /biz/x/food/closes:sign` | FOOD-API-075 / PATH_AND_INPUT_CHANGED | ai.neargo.shop.food.dto.BusinessCloseContracts.SignReq | `ai.neargo.shop.food.dto.BusinessCloseContracts.CloseVO` | `biz:finance` |
| FOOD-NONPAY-IMPL-045 | `POST /biz/x/food/closes:adjust` | FOOD-API-076 / PATH_AND_INPUT_CHANGED | ai.neargo.shop.food.dto.BusinessCloseContracts.AdjustmentReq | `ai.neargo.shop.food.dto.OperationVO` | `biz:finance` |
| FOOD-NONPAY-IMPL-046 | `POST /biz/x/food/production-events/{eventNo}:consume` | 新增，目标未编号 / ADDED_IMPLEMENTATION_NO_TARGET_API | path:eventNo String | `ai.neargo.shop.food.dto.ProductionContracts.ConsumptionVO` | `biz:stock` |
| FOOD-NONPAY-IMPL-047 | `GET /biz/x/food/production-events/{eventNo}` | FOOD-API-154 / DIFFERENT_RESOURCE_KEY | path:eventNo String | `ai.neargo.shop.food.dto.ProductionContracts.ConsumptionVO` | `biz:order:view` |
| FOOD-NONPAY-IMPL-048 | `GET /biz/x/food/returns/{returnNo}/kitchen` | 新增，目标未编号 / ADDED_IMPLEMENTATION_NO_TARGET_API | path:returnNo String | `ai.neargo.shop.food.dto.KitchenReturnContracts.ReturnKitchenVO` | `biz:aftersale` |
| FOOD-NONPAY-IMPL-049 | `POST /biz/x/food/returns/{returnNo}/kitchen` | 新增，目标未编号 / ADDED_IMPLEMENTATION_NO_TARGET_API | ai.neargo.shop.food.dto.KitchenReturnContracts.HandleReq | `ai.neargo.shop.food.dto.KitchenReturnContracts.HandledVO` | `biz:aftersale` |
| FOOD-NONPAY-IMPL-050 | `GET /biz/x/food/stores/{storeNo}/production-consumptions` | FOOD-API-153 / EXACT_PATH | path:storeNo String; query:from LocalDate; query:to LocalDate; query:page Integer; query:size Integer; query:skuNo String; query:itemRef String; query:status String | `ai.neargo.shop.common.PageData<ai.neargo.shop.food.dto.ProductionContracts.ConsumptionVO>` | `biz:order:view` |

### 11.2 实际参数与合同差异

#### FOOD-NONPAY-IMPL-001 · BizQueueController.issue

`POST /biz/x/food/queue-tickets` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizQueueController.java:46`。权限：`biz:verify`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:verify。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| body | body | `ai.neargo.shop.food.dto.QueueContracts.IssueReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.QueueContracts.QueueVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-157 `POST /biz/x/food/queue-tickets`；EXACT_PATH。

- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`ticket`。

边界：排队号不等于取餐号；notificationStatus是事实状态，不表示已发送通知。

#### FOOD-NONPAY-IMPL-002 · BizQueueController.list

`GET /biz/x/food/queue-tickets` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizQueueController.java:53`。权限：`biz:order:view`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:order:view。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| query | businessDate | `java.time.LocalDate` | 是 | — | @RequestParam |
| query | status | `String` | 否 | — | @RequestParam(required=false) |
| query | page | `Integer` | 否 | — | @RequestParam(required=false) |
| query | size | `Integer` | 否 | — | @RequestParam(required=false) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.common.PageData<ai.neargo.shop.food.dto.QueueContracts.QueueVO>`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-158 `GET /biz/x/food/queue-tickets`；EXACT_PATH。


边界：排队号不等于取餐号；notificationStatus是事实状态，不表示已发送通知。

#### FOOD-NONPAY-IMPL-003 · BizQueueController.get

`GET /biz/x/food/queue-tickets/{ticketNo}` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizQueueController.java:60`。权限：`biz:order:view`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:order:view。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | ticketNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.QueueContracts.QueueVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-159 `GET /biz/x/food/queue-tickets/{ticketNo}`；EXACT_PATH。

- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`ticket`。

边界：排队号不等于取餐号；notificationStatus是事实状态，不表示已发送通知。

#### FOOD-NONPAY-IMPL-004 · BizQueueController.callNext

`POST /biz/x/food/queue-tickets:call-next` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizQueueController.java:66`。权限：`biz:verify`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:verify。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| query | businessDate | `java.time.LocalDate` | 是 | — | @RequestParam |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.QueueContracts.QueueVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-160 `POST /biz/x/food/queue-tickets:call-next`；EXACT_PATH。

- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`ticket`。

边界：排队号不等于取餐号；notificationStatus是事实状态，不表示已发送通知。

#### FOOD-NONPAY-IMPL-005 · BizQueueController.call

`POST /biz/x/food/queue-tickets/{ticketNo}:call` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizQueueController.java:73`。权限：`biz:verify`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:verify。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | ticketNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |
| body | body | `ai.neargo.shop.food.dto.QueueContracts.ChangeReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.QueueContracts.QueueVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-161 `POST /biz/x/food/queue-tickets/{ticketNo}:call`；EXACT_PATH。

- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`ticket`。

边界：排队号不等于取餐号；notificationStatus是事实状态，不表示已发送通知。

#### FOOD-NONPAY-IMPL-006 · BizQueueController.cancel

`POST /biz/x/food/queue-tickets/{ticketNo}:cancel` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizQueueController.java:80`。权限：`biz:verify`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:verify。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | ticketNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |
| body | body | `ai.neargo.shop.food.dto.QueueContracts.ChangeReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.QueueContracts.QueueVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-162 `POST /biz/x/food/queue-tickets/{ticketNo}:cancel`；EXACT_PATH。

- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`ticket`。

边界：排队号不等于取餐号；notificationStatus是事实状态，不表示已发送通知。

#### FOOD-NONPAY-IMPL-007 · BizQueueController.noShow

`POST /biz/x/food/queue-tickets/{ticketNo}:no-show` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizQueueController.java:87`。权限：`biz:verify`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:verify。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | ticketNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |
| body | body | `ai.neargo.shop.food.dto.QueueContracts.ChangeReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.QueueContracts.QueueVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-163 `POST /biz/x/food/queue-tickets/{ticketNo}:no-show`；EXACT_PATH。

- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`ticket`。

边界：排队号不等于取餐号；notificationStatus是事实状态，不表示已发送通知。

#### FOOD-NONPAY-IMPL-008 · BizQueueController.seat

`POST /biz/x/food/queue-tickets/{ticketNo}:seat` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizQueueController.java:95`。权限：`biz:verify`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:verify。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | ticketNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |
| body | body | `ai.neargo.shop.food.dto.QueueContracts.SeatQueueReq` | 是 | — | @Valid @RequestBody |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.QueueContracts.QueueVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-164 `POST /biz/x/food/queue-tickets/{ticketNo}:seat`；EXACT_PATH。

- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`ticket`。

边界：新就座锁定原票后，在同一food primary事务内调用CheckService.openQueuedCheck，仅限内部Java调用，不能经HTTP/remote；原开台、SEATED、queue动作与outbox同提交。冲突整体回滚，原CALLED票可重选；不把永久SEATING作为新流程。

#### FOOD-NONPAY-IMPL-009 · BizQueueController.recover

`POST /biz/x/food/queue-tickets/{ticketNo}:recover-seating` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizQueueController.java:102`。权限：`biz:verify`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:verify。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | ticketNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.QueueContracts.QueueVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-165 `POST /biz/x/food/queue-tickets/{ticketNo}:recover-seating`；EXACT_PATH。

- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`ticket`。

边界：旧SEATING仅兼容原冻结命令/OPEN_CHECK原键回执恢复；未知保持未知，不创建替代开台。新seat走同primary原子事务。

#### FOOD-NONPAY-IMPL-010 · BizKitchenTaskController.queue

`GET /biz/x/food/stores/{storeNo}/kitchen/tasks` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizKitchenTaskController.java:52`。权限：`biz:receive`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:receive。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | storeNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |
| query | stationNo | `String` | 是 | — | @RequestParam @NotBlank @Size(max=32) |
| query | status | `String` | 否 | — | @RequestParam(required=false) |
| query | page | `int` | 否 | 1 | @RequestParam(defaultValue="1") |
| query | size | `int` | 否 | 20 | @RequestParam(defaultValue="20") |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.common.PageData<ai.neargo.shop.food.dto.KitchenContracts.TaskVO>`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-044 `GET /biz/x/food/stores/{storeNo}/kitchen/tasks`；EXACT_PATH。


边界：任务动作带CAS version及原始数量增量；队列为当前持久任务的分页投影，非推送订阅。

#### FOOD-NONPAY-IMPL-011 · BizKitchenTaskController.start

`POST /biz/x/food/tasks/{taskNo}:start` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizKitchenTaskController.java:67`。权限：`biz:receive`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:receive。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | taskNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |
| body | req | `ai.neargo.shop.food.dto.KitchenContracts.TaskDeltaReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.KitchenContracts.EventVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-045 `POST /biz/x/food/tasks/{taskNo}:start`；EXACT_PATH。

- ACTUAL_INPUT_FIELDS_ADDED：`reason`。
- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`task`。

边界：任务动作带CAS version及原始数量增量；队列为当前持久任务的分页投影，非推送订阅。

#### FOOD-NONPAY-IMPL-012 · BizKitchenTaskController.ready

`POST /biz/x/food/tasks/{taskNo}:ready` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizKitchenTaskController.java:76`。权限：`biz:receive`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:receive。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | taskNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |
| body | req | `ai.neargo.shop.food.dto.KitchenContracts.TaskDeltaReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.KitchenContracts.EventVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-046 `POST /biz/x/food/tasks/{taskNo}:ready`；EXACT_PATH。

- ACTUAL_INPUT_FIELDS_ADDED：`reason`。
- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`task`。

边界：任务动作带CAS version及原始数量增量；队列为当前持久任务的分页投影，非推送订阅。

#### FOOD-NONPAY-IMPL-013 · BizKitchenTaskController.serve

`POST /biz/x/food/tasks/{taskNo}:serve` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizKitchenTaskController.java:85`。权限：`biz:receive`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:receive。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | taskNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |
| body | req | `ai.neargo.shop.food.dto.KitchenContracts.TaskDeltaReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.KitchenContracts.EventVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-047 `POST /biz/x/food/tasks/{taskNo}:serve`；EXACT_PATH。

- ACTUAL_INPUT_FIELDS_ADDED：`reason`。
- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`task`。

边界：任务动作带CAS version及原始数量增量；队列为当前持久任务的分页投影，非推送订阅。

#### FOOD-NONPAY-IMPL-014 · BizKitchenTaskController.hold

`POST /biz/x/food/tasks/{taskNo}:hold` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizKitchenTaskController.java:94`。权限：`biz:receive`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:receive。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | taskNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |
| body | req | `ai.neargo.shop.food.dto.KitchenContracts.TaskDeltaReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.KitchenContracts.EventVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-048 `POST /biz/x/food/tasks/{taskNo}:hold`；EXACT_PATH。

- ACTUAL_INPUT_FIELDS_ADDED：`qty`。
- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`task`。

边界：任务动作带CAS version及原始数量增量；队列为当前持久任务的分页投影，非推送订阅。

#### FOOD-NONPAY-IMPL-015 · BizKitchenTaskController.fire

`POST /biz/x/food/tasks/{taskNo}:fire` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizKitchenTaskController.java:103`。权限：`biz:receive`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:receive。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | taskNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |
| body | req | `ai.neargo.shop.food.dto.KitchenContracts.TaskDeltaReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.KitchenContracts.EventVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-049 `POST /biz/x/food/tasks/{taskNo}:fire`；EXACT_PATH。

- ACTUAL_INPUT_FIELDS_ADDED：`qty`。
- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`task`。

边界：任务动作带CAS version及原始数量增量；队列为当前持久任务的分页投影，非推送订阅。

#### FOOD-NONPAY-IMPL-016 · BizKitchenTaskController.urge

`POST /biz/x/food/tasks/{taskNo}:urge` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizKitchenTaskController.java:112`。权限：`biz:receive`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:receive。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | taskNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |
| body | req | `ai.neargo.shop.food.dto.KitchenContracts.TaskDeltaReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.KitchenContracts.EventVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-050 `POST /biz/x/food/tasks/{taskNo}:urge`；EXACT_PATH。

- ACTUAL_INPUT_FIELDS_ADDED：`qty`。
- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`task`。

边界：任务动作带CAS version及原始数量增量；队列为当前持久任务的分页投影，非推送订阅。

#### FOOD-NONPAY-IMPL-017 · BizKitchenTaskController.voidTask

`POST /biz/x/food/tasks/{taskNo}:void` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizKitchenTaskController.java:121`。权限：`biz:aftersale`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:aftersale。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | taskNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |
| body | req | `ai.neargo.shop.food.dto.KitchenContracts.TaskDeltaReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.KitchenContracts.EventVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-051 `POST /biz/x/food/tasks/{taskNo}:void`；EXACT_PATH。

- TARGET_INPUT_FIELD_NOT_PRESENT_IN_ACTUAL_PUBLIC_CONTRACT：`returnNo`。
- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`task`。

边界：任务动作带CAS version及原始数量增量；队列为当前持久任务的分页投影，非推送订阅。

#### FOOD-NONPAY-IMPL-018 · BizKitchenTaskController.loss

`POST /biz/x/food/tasks/{taskNo}:loss` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizKitchenTaskController.java:130`。权限：`biz:stock`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:stock。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | taskNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |
| body | req | `ai.neargo.shop.food.dto.KitchenContracts.TaskDeltaReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.KitchenContracts.EventVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：本批新增实际路由；未删改旧目标或借用不等价编号。


边界：任务动作带CAS version及原始数量增量；队列为当前持久任务的分页投影，非推送订阅。

#### FOOD-NONPAY-IMPL-019 · BizKitchenTaskController.remake

`POST /biz/x/food/tasks/{taskNo}:remake` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizKitchenTaskController.java:139`。权限：`biz:aftersale`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:aftersale。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | taskNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |
| body | req | `ai.neargo.shop.food.dto.KitchenContracts.RemakeReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.KitchenContracts.TicketVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-052 `POST /biz/x/food/tasks/{taskNo}:remake`；EXACT_PATH。

- TARGET_INPUT_FIELD_NOT_PRESENT_IN_ACTUAL_PUBLIC_CONTRACT：`approvalRef`。
- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`eventNo`、`newTaskNo`、`task`。

边界：任务动作带CAS version及原始数量增量；队列为当前持久任务的分页投影，非推送订阅。

#### FOOD-NONPAY-IMPL-020 · BizKitchenTaskController.events

`GET /biz/x/food/tasks/{taskNo}/events` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizKitchenTaskController.java:147`。权限：`biz:receive`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:receive。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | taskNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |

成功输出为ApiResult&lt;T&gt;的data：`java.util.List<ai.neargo.shop.food.dto.KitchenContracts.EventVO>`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：本批新增实际路由；未删改旧目标或借用不等价编号。


边界：任务动作带CAS version及原始数量增量；队列为当前持久任务的分页投影，非推送订阅。

#### FOOD-NONPAY-IMPL-021 · BizCourseController.create

`POST /biz/x/food/checks/{checkNo}/courses` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizCourseController.java:48`。权限：`biz:receive`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:receive。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | checkNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |
| body | req | `ai.neargo.shop.food.dto.KitchenContracts.CourseReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.KitchenContracts.CourseVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-053 `POST /biz/x/food/checks/{checkNo}/courses`；EXACT_PATH。


边界：源事实权限与版本守卫；本次为只读代码对账。

#### FOOD-NONPAY-IMPL-022 · BizCourseController.courses

`GET /biz/x/food/checks/{checkNo}/courses` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizCourseController.java:55`。权限：`biz:receive`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:receive。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | checkNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |

成功输出为ApiResult&lt;T&gt;的data：`java.util.List<ai.neargo.shop.food.dto.KitchenContracts.CourseVO>`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：本批新增实际路由；未删改旧目标或借用不等价编号。


边界：源事实权限与版本守卫；本次为只读代码对账。

#### FOOD-NONPAY-IMPL-023 · BizCourseController.act

`POST /biz/x/food/courses/{courseNo}:act` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizCourseController.java:60`。权限：`biz:receive`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:receive。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | courseNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |
| body | req | `ai.neargo.shop.food.dto.KitchenContracts.CourseActionReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.KitchenContracts.CourseVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：本批新增实际路由；未删改旧目标或借用不等价编号。


边界：源事实权限与版本守卫；本次为只读代码对账。

#### FOOD-NONPAY-IMPL-024 · BizCourseController.fire

`POST /biz/x/food/courses/{courseNo}:fire` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizCourseController.java:67`。权限：`biz:receive`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:receive。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | courseNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |
| body | req | `ai.neargo.shop.food.dto.KitchenContracts.CourseFireReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.KitchenContracts.CourseVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-054 `POST /biz/x/food/courses/{courseNo}:fire`；EXACT_PATH。

- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`operationNo`、`resultRef`。

边界：源事实权限与版本守卫；本次为只读代码对账。

#### FOOD-NONPAY-IMPL-025 · BizDiningHandoverController.handover

`POST /biz/x/food/batches/{batchNo}:handover` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizDiningHandoverController.java:45`。权限：`biz:verify`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:verify。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | batchNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |
| body | req | `ai.neargo.shop.food.dto.KitchenContracts.HandoverReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.KitchenContracts.HandoverVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-055 `POST /biz/x/food/batches/{batchNo}:handover`；EXACT_PATH。

- TARGET_INPUT_FIELD_NOT_PRESENT_IN_ACTUAL_PUBLIC_CONTRACT：`coreFulfillmentRef`。
- ACTUAL_INPUT_FIELDS_ADDED：`reason`。

边界：本地交接不自动等于核心交付；deliveryPending/coreDeliveryRef必须按实际返回值解释。

#### FOOD-NONPAY-IMPL-026 · BizDiningPickupVerificationController.get

`GET /biz/x/food/batches/{batchNo}/pickup-verification` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizDiningPickupVerificationController.java:46`。权限：`biz:verify`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:verify。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | batchNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.DiningPickupContracts.PickupVerificationVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：本批新增实际路由；未删改旧目标或借用不等价编号。


边界：读取/核销原核心履约收据；当前GET也要求Idempotency-Key，可能进行原证据对账，不可省略该头。

#### FOOD-NONPAY-IMPL-027 · BizDiningPickupVerificationController.verify

`POST /biz/x/food/batches/{batchNo}:verify-pickup` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizDiningPickupVerificationController.java:53`。权限：`biz:verify`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:verify。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | batchNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |
| body | req | `ai.neargo.shop.food.dto.DiningPickupContracts.VerifyPickupReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.DiningPickupContracts.PickupVerificationVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：本批新增实际路由；未删改旧目标或借用不等价编号。


边界：读取/核销原核心履约收据；当前GET也要求Idempotency-Key，可能进行原证据对账，不可省略该头。

#### FOOD-NONPAY-IMPL-028 · BizPrintJobController.enqueue

`POST /biz/x/food/print-jobs` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizPrintJobController.java:46`。权限：`biz:verify`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:verify。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| body | req | `ai.neargo.shop.food.dto.PrintDocumentReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) |

成功输出为ApiResult&lt;T&gt;的data：`java.util.List<ai.neargo.shop.spi.printing.PrintPort.JobVO>`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-065 `POST /biz/print/jobs`；PATH_CHANGED。共享打印目标以餐饮适配入口实现；仅ORDER/KITCHEN/PREBILL/RETURN，非通用共享biz入口。

- TARGET_INPUT_FIELD_NOT_PRESENT_IN_ACTUAL_PUBLIC_CONTRACT：`deviceNos`、`sourceEventNo`、`sourceRef`、`templateVersion`。
- ACTUAL_INPUT_FIELDS_ADDED：`sourceNo`、`stationNo`。
- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`jobNos`、`status`。

边界：首次打印身份不包含客户端Idempotency-Key；ORDER/KITCHEN/RETURN按原单据+scene/station固定，PREBILL按原文档snapshot版本；换key同内容不新增首次任务。额外纸张必须走关联原任务+原因+操作人的审计reprint；UNKNOWN禁止普通retry。

#### FOOD-NONPAY-IMPL-029 · BizPrintJobController.list

`GET /biz/x/food/print-jobs` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizPrintJobController.java:52`。权限：`biz:order:view`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:order:view。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| query | afterJobNo | `String` | 否 | — | @RequestParam(value="afterJobNo",required=false) |
| query | limit | `int` | 否 | 20 | @RequestParam(value="limit",defaultValue="20") |

成功输出为ApiResult&lt;T&gt;的data：`java.util.List<ai.neargo.shop.spi.printing.PrintPort.JobVO>`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：本批新增实际路由；未删改旧目标或借用不等价编号。


边界：首次打印身份不包含客户端Idempotency-Key；ORDER/KITCHEN/RETURN按原单据+scene/station固定，PREBILL按原文档snapshot版本；换key同内容不新增首次任务。额外纸张必须走关联原任务+原因+操作人的审计reprint；UNKNOWN禁止普通retry。

#### FOOD-NONPAY-IMPL-030 · BizPrintJobController.get

`GET /biz/x/food/print-jobs/{jobNo}` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizPrintJobController.java:58`。权限：`biz:order:view`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:order:view。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | jobNo | `String` | 是 | — | @PathVariable("jobNo") @NotBlank @Size(max=32) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.spi.printing.PrintPort.JobVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-066 `GET /biz/print/jobs/{jobNo}`；PATH_CHANGED。共享目标查询经餐饮网关暴露，实际路径不同。


边界：首次打印身份不包含客户端Idempotency-Key；ORDER/KITCHEN/RETURN按原单据+scene/station固定，PREBILL按原文档snapshot版本；换key同内容不新增首次任务。额外纸张必须走关联原任务+原因+操作人的审计reprint；UNKNOWN禁止普通retry。

#### FOOD-NONPAY-IMPL-031 · BizPrintJobController.retry

`POST /biz/x/food/print-jobs/{jobNo}:retry` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizPrintJobController.java:63`。权限：`biz:verify`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:verify。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | jobNo | `String` | 是 | — | @PathVariable("jobNo") @NotBlank @Size(max=32) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.spi.printing.PrintPort.JobVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：本批新增实际路由；未删改旧目标或借用不等价编号。


边界：首次打印身份不包含客户端Idempotency-Key；ORDER/KITCHEN/RETURN按原单据+scene/station固定，PREBILL按原文档snapshot版本；换key同内容不新增首次任务。额外纸张必须走关联原任务+原因+操作人的审计reprint；UNKNOWN禁止普通retry。

#### FOOD-NONPAY-IMPL-032 · BizPrintJobController.reprint

`POST /biz/x/food/print-jobs/{jobNo}:reprint` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizPrintJobController.java:69`。权限：`biz:verify`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:verify。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | jobNo | `String` | 是 | — | @PathVariable("jobNo") @NotBlank @Size(max=32) |
| body | req | `ai.neargo.shop.food.api.biz.BizPrintJobController.ReprintReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.spi.printing.PrintPort.JobVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-067 `POST /biz/print/jobs/{jobNo}:reprint`；PATH_CHANGED。实际路径不同；保留原任务、原因、操作人、幂等键。

- TARGET_INPUT_FIELD_NOT_PRESENT_IN_ACTUAL_PUBLIC_CONTRACT：`deviceNo`。

边界：首次打印身份不包含客户端Idempotency-Key；ORDER/KITCHEN/RETURN按原单据+scene/station固定，PREBILL按原文档snapshot版本；换key同内容不新增首次任务。额外纸张必须走关联原任务+原因+操作人的审计reprint；UNKNOWN禁止普通retry。

#### FOOD-NONPAY-IMPL-033 · BizPrintDeviceController.bind

`POST /biz/x/food/print-devices` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizPrintDeviceController.java:45`。权限：`biz:store:admin`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:store:admin。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| body | req | `ai.neargo.shop.spi.printing.PrintPort.DeviceReq` | 是 | — | @Valid @RequestBody |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.spi.printing.PrintPort.DeviceVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：本批新增实际路由；未删改旧目标或借用不等价编号。


边界：bridge actor必须与设备绑定一致；claim空闲为null，SENT不代表已出纸，超时为UNKNOWN。

#### FOOD-NONPAY-IMPL-034 · BizPrintDeviceController.heartbeat

`POST /biz/x/food/print-devices/{deviceNo}:heartbeat` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizPrintDeviceController.java:51`。权限：`biz:verify`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:verify。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | deviceNo | `String` | 是 | — | @PathVariable("deviceNo") @NotBlank @Size(max=32) |
| body | req | `ai.neargo.shop.food.api.biz.BizPrintDeviceController.HeartbeatReq` | 是 | — | @Valid @RequestBody |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.spi.printing.PrintPort.DeviceVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：本批新增实际路由；未删改旧目标或借用不等价编号。


边界：bridge actor必须与设备绑定一致；claim空闲为null，SENT不代表已出纸，超时为UNKNOWN。

#### FOOD-NONPAY-IMPL-035 · BizPrintDeviceController.claim

`POST /biz/x/food/print-devices/{deviceNo}:claim` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizPrintDeviceController.java:57`。权限：`biz:verify`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:verify。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | deviceNo | `String` | 是 | — | @PathVariable("deviceNo") @NotBlank @Size(max=32) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.spi.printing.PrintPort.Claim`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：本批新增实际路由；未删改旧目标或借用不等价编号。


边界：bridge actor必须与设备绑定一致；claim空闲为null，SENT不代表已出纸，超时为UNKNOWN。

#### FOOD-NONPAY-IMPL-036 · BizPrintDeviceController.acknowledge

`POST /biz/x/food/print-devices/{deviceNo}:acknowledge` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizPrintDeviceController.java:62`。权限：`biz:verify`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:verify。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | deviceNo | `String` | 是 | — | @PathVariable("deviceNo") @NotBlank @Size(max=32) |
| body | req | `ai.neargo.shop.spi.printing.PrintPort.Acknowledge` | 是 | — | @Valid @RequestBody |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.spi.printing.PrintPort.JobVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：本批新增实际路由；未删改旧目标或借用不等价编号。


边界：bridge actor必须与设备绑定一致；claim空闲为null，SENT不代表已出纸，超时为UNKNOWN。

#### FOOD-NONPAY-IMPL-037 · BizPrintConfigurationController.configuration

`GET /biz/x/food/print-configuration` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizPrintConfigurationController.java:44`。权限：`biz:store:admin`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:store:admin。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.spi.printing.PrintPort.Configuration`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：本批新增实际路由；未删改旧目标或借用不等价编号。


边界：设备路由、模板归共享打印owner；模板发布后不可覆盖。

#### FOOD-NONPAY-IMPL-038 · BizPrintConfigurationController.publish

`POST /biz/x/food/print-configuration/templates` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizPrintConfigurationController.java:47`。权限：`biz:store:admin`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:store:admin。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| body | req | `ai.neargo.shop.spi.printing.PrintPort.TemplateReq` | 是 | — | @Valid @RequestBody |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.spi.printing.PrintPort.TemplateReq`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：本批新增实际路由；未删改旧目标或借用不等价编号。


边界：设备路由、模板归共享打印owner；模板发布后不可覆盖。

#### FOOD-NONPAY-IMPL-039 · BizPrintConfigurationController.route

`POST /biz/x/food/print-configuration/routes` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizPrintConfigurationController.java:52`。权限：`biz:store:admin`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:store:admin。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| body | req | `ai.neargo.shop.spi.printing.PrintPort.RouteReq` | 是 | — | @Valid @RequestBody |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.spi.printing.PrintPort.RouteReq`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：本批新增实际路由；未删改旧目标或借用不等价编号。


边界：设备路由、模板归共享打印owner；模板发布后不可覆盖。

#### FOOD-NONPAY-IMPL-040 · BizBusinessCloseController.open

`POST /biz/x/food/business-days/{date}:open` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizBusinessCloseController.java:46`。权限：`biz:finance`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:finance。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | date | `java.time.LocalDate` | 是 | — | @PathVariable |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.BusinessCloseContracts.DayVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：本批新增实际路由；未删改旧目标或借用不等价编号。


边界：仅按实际源事实提供报表/关账证据；资金完整性缺口和异常不可由本地状态伪造消除。

#### FOOD-NONPAY-IMPL-041 · BizBusinessCloseController.report

`GET /biz/x/food/reports/business-day` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizBusinessCloseController.java:53`。权限：`biz:order:view`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:order:view。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| query | businessDate | `java.time.LocalDate` | 是 | — | @RequestParam |
| query | currency | `String` | 是 | — | @RequestParam |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.BusinessCloseContracts.ReportVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-077 `GET /biz/x/food/reports/{reportCode}`；PARTIAL_TARGET_SUBSET。只实现营业日固定报表+businessDate/currency，未实现任意reportCode报表。

- TARGET_INPUT_FIELD_NOT_PRESENT_IN_ACTUAL_PUBLIC_CONTRACT：`from`、`metricVersion`、`page`、`size`、`stationNo`、`storeNos`、`timeBasis`、`to`。
- ACTUAL_INPUT_FIELDS_ADDED：`businessDate`。
- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`report`。

边界：仅按实际源事实提供报表/关账证据；资金完整性缺口和异常不可由本地状态伪造消除。

#### FOOD-NONPAY-IMPL-042 · BizBusinessCloseController.preview

`POST /biz/x/food/closes:preview` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizBusinessCloseController.java:61`。权限：`biz:finance`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:finance。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| body | body | `ai.neargo.shop.food.dto.BusinessCloseContracts.PreviewReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.BusinessCloseContracts.CloseVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-072 `POST /biz/x/food/stores/{storeNo}/closes`；PATH_AND_INPUT_CHANGED。storeNo来自可信当前门店，营业日与currency来自PreviewReq；不是目标stores/{storeNo}/closes路径。

- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`close`。

边界：仅按实际源事实提供报表/关账证据；资金完整性缺口和异常不可由本地状态伪造消除。

#### FOOD-NONPAY-IMPL-043 · BizBusinessCloseController.get

`GET /biz/x/food/closes/{closeNo}` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizBusinessCloseController.java:68`。权限：`biz:order:view`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:order:view。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | closeNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.BusinessCloseContracts.CloseVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-073 `GET /biz/x/food/closes/{closeNo}`；EXACT_PATH。

- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`close`。

边界：仅按实际源事实提供报表/关账证据；资金完整性缺口和异常不可由本地状态伪造消除。

#### FOOD-NONPAY-IMPL-044 · BizBusinessCloseController.sign

`POST /biz/x/food/closes:sign` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizBusinessCloseController.java:74`。权限：`biz:finance`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:finance。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| body | body | `ai.neargo.shop.food.dto.BusinessCloseContracts.SignReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.BusinessCloseContracts.CloseVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-075 `POST /biz/x/food/closes/{closeNo}:sign`；PATH_AND_INPUT_CHANGED。closeNo由SignReq提供；真实资金与异常守卫仍生效，存在端点不等于当前具备完整财务签核能力。

- TARGET_INPUT_FIELD_NOT_PRESENT_IN_ACTUAL_PUBLIC_CONTRACT：`approvalRef`、`version`、`watermark`。
- ACTUAL_INPUT_FIELDS_ADDED：`closeNo`。
- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`close`。

边界：仅按实际源事实提供报表/关账证据；资金完整性缺口和异常不可由本地状态伪造消除。

#### FOOD-NONPAY-IMPL-045 · BizBusinessCloseController.adjust

`POST /biz/x/food/closes:adjust` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizBusinessCloseController.java:81`。权限：`biz:finance`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:finance。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| body | body | `ai.neargo.shop.food.dto.BusinessCloseContracts.AdjustmentReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.OperationVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-076 `POST /biz/x/food/closes/{closeNo}/adjustments`；PATH_AND_INPUT_CHANGED。originalCloseNo/currentDayNo/coreReference/approvalRef均在请求体；不得修改原已签快照。

- TARGET_INPUT_FIELD_NOT_PRESENT_IN_ACTUAL_PUBLIC_CONTRACT：`currentBusinessDate`。
- ACTUAL_INPUT_FIELDS_ADDED：`currentDayNo`、`originalCloseNo`。
- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`adjustmentNo`、`newCloseNo`。

边界：仅按实际源事实提供报表/关账证据；资金完整性缺口和异常不可由本地状态伪造消除。

#### FOOD-NONPAY-IMPL-046 · BizProductionConsumptionController.consume

`POST /biz/x/food/production-events/{eventNo}:consume` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizProductionConsumptionController.java:50`。权限：`biz:stock`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:stock。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | eventNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.ProductionContracts.ConsumptionVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：本批新增实际路由；未删改旧目标或借用不等价编号。


边界：只允许引用既存source event；BLOCKED、UNKNOWN和原库存回执均保真；不接受客户端数量/金额。

#### FOOD-NONPAY-IMPL-047 · BizProductionConsumptionController.get

`GET /biz/x/food/production-events/{eventNo}` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizProductionConsumptionController.java:56`。权限：`biz:order:view`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:order:view。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | eventNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.ProductionContracts.ConsumptionVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-154 `GET /biz/x/food/production-consumptions/{consumptionNo}`；DIFFERENT_RESOURCE_KEY。实际按source eventNo读耗用；目标按consumptionNo读取，不可宣称路径或资源键对齐。

- TARGET_OUTPUT_FIELD_OR_WRAPPER_NOT_PRESENT_IN_ACTUAL_RESPONSE：`consumption`。

边界：只允许引用既存source event；BLOCKED、UNKNOWN和原库存回执均保真；不接受客户端数量/金额。

#### FOOD-NONPAY-IMPL-048 · BizKitchenReturnController.get

`GET /biz/x/food/returns/{returnNo}/kitchen` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizKitchenReturnController.java:47`。权限：`biz:aftersale`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:aftersale。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | returnNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.KitchenReturnContracts.ReturnKitchenVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：本批新增实际路由；未删改旧目标或借用不等价编号。


边界：只处理厨房处置，不触发退款成功或资金执行。

#### FOOD-NONPAY-IMPL-049 · BizKitchenReturnController.handle

`POST /biz/x/food/returns/{returnNo}/kitchen` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizKitchenReturnController.java:53`。权限：`biz:aftersale`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:aftersale。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | returnNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |
| body | req | `ai.neargo.shop.food.dto.KitchenReturnContracts.HandleReq` | 是 | — | @Valid @RequestBody |
| header | Idempotency-Key | `String` | 是 | — | @RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.food.dto.KitchenReturnContracts.HandledVO`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：本批新增实际路由；未删改旧目标或借用不等价编号。


边界：只处理厨房处置，不触发退款成功或资金执行。

#### FOOD-NONPAY-IMPL-050 · BizProductionConsumptionController.list

`GET /biz/x/food/stores/{storeNo}/production-consumptions` · **CODED_PENDING_VERIFICATION**

源码：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizProductionConsumptionController.java:39`。权限：`biz:order:view`；可信BizContext主体/当前门店 + StoreDirectoryPort真实归属 + X-Store-No范围校验 + biz:order:view。

| 输入位置 | 名称 | Java类型/DTO | 必须提供 | 默认值 | 声明约束 |
|---|---|---|---|---|---|
| path | storeNo | `String` | 是 | — | @PathVariable @NotBlank @Size(max=32) |
| query | from | `java.time.LocalDate` | 是 | — | @RequestParam |
| query | to | `java.time.LocalDate` | 是 | — | @RequestParam |
| query | page | `Integer` | 否 | 1 | @RequestParam(required=false) |
| query | size | `Integer` | 否 | 20 | @RequestParam(required=false) |
| query | skuNo | `String` | 否 | — | @RequestParam(required=false) |
| query | itemRef | `String` | 否 | — | @RequestParam(required=false) |
| query | status | `String` | 否 | — | @RequestParam(required=false) |

成功输出为ApiResult&lt;T&gt;的data：`ai.neargo.shop.common.PageData<ai.neargo.shop.food.dto.ProductionContracts.ConsumptionVO>`。Body及返回字段见11.3的实际DTO声明；打印claim空闲时data为null，不能据此推断原任务是否已投递。

目标映射：FOOD-API-153 `GET /biz/x/food/stores/{storeNo}/production-consumptions`；EXACT_PATH。

- PAGINATION_SCALAR_TYPE_DIFFERS_FROM_TARGET：`page`、`size`。

边界：原storeNo路径+可信actor/门店归属；from/to为营业日且to不含，范围最多93日；page默认1、size默认20且最大100；SKU/物料/status均与同过滤COUNT分页。只读已持久耗用事实，不补扣库存；ConsumptionVO/LineVO递归返回原回执，BLOCKED/UNKNOWN、实际物耗未测量与未知成本保持原值。

### 11.3 实际DTO字段声明

以下来自真实Java record；声明约束为空不推断运行时字段必定非空。来源对象、权限及owner真回执仍由Service验证。

#### `ai.neargo.shop.common.PageData`

来源：`backend/shop-base/src/main/java/ai/neargo/shop/common/PageData.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| records | `List<T>` |  |
| total | `long` |  |
| page | `long` |  |
| size | `long` |  |

#### `ai.neargo.shop.food.api.biz.BizPrintDeviceController.HeartbeatReq`

来源：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizPrintDeviceController.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| online | `boolean` |  |

#### `ai.neargo.shop.food.api.biz.BizPrintJobController.ReprintReq`

来源：`backend-plugins/shop-industry-food/food-web/src/main/java/ai/neargo/shop/food/api/biz/BizPrintJobController.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| reason | `String` | @NotBlank @Size(max=256) |

#### `ai.neargo.shop.food.dto.BusinessCloseContracts.AdjustmentReq`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/BusinessCloseContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| originalCloseNo | `String` | @NotBlank @Size(max=32) |
| currentDayNo | `String` | @NotBlank @Size(max=32) |
| coreReference | `String` | @NotBlank @Size(max=64) |
| reason | `String` | @NotBlank @Size(max=255) |
| approvalRef | `String` | @NotBlank @Size(max=64) |

#### `ai.neargo.shop.food.dto.BusinessCloseContracts.CloseVO`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/BusinessCloseContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| closeNo | `String` |  |
| dayNo | `String` |  |
| currency | `String` |  |
| revision | `int` |  |
| status | `String` |  |
| snapshotHash | `String` |  |
| previousCloseNo | `String` |  |
| signedBy | `String` |  |
| signedAt | `LocalDateTime` |  |
| report | `ReportVO` |  |
| version | `long` |  |

#### `ai.neargo.shop.food.dto.BusinessCloseContracts.DayVO`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/BusinessCloseContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| dayNo | `String` |  |
| businessDate | `LocalDate` |  |
| policyNo | `String` |  |
| startsAt | `LocalDateTime` |  |
| endsAt | `LocalDateTime` |  |
| status | `String` |  |
| currentCloseRefs | `String` |  |
| version | `long` |  |

#### `ai.neargo.shop.food.dto.BusinessCloseContracts.Issue`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/BusinessCloseContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| kind | `String` |  |
| sourceRef | `String` |  |

#### `ai.neargo.shop.food.dto.BusinessCloseContracts.PreviewReq`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/BusinessCloseContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| businessDate | `LocalDate` | @NotNull |
| currency | `String` | @NotBlank @Pattern(regexp="[A-Z]{3}") |

#### `ai.neargo.shop.food.dto.BusinessCloseContracts.ReportVO`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/BusinessCloseContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| businessDate | `LocalDate` |  |
| currency | `String` |  |
| batchCount | `long` |  |
| quantitiesByUnit | `Map<String, BigDecimal>` |  |
| originalOrderServiceQuantitiesByActionUnit | `Map<String, BigDecimal>` |  |
| returnAndServiceScope | `String` |  |
| settlementAttemptCount | `long` |  |
| requestedDueMinor | `long` |  |
| returnCount | `long` |  |
| salesCompleteness | `String` |  |
| settlementCompleteness | `String` |  |
| refundCompleteness | `String` |  |
| issues | `List<Issue>` |  |
| sourceHash | `String` |  |

#### `ai.neargo.shop.food.dto.BusinessCloseContracts.SignReq`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/BusinessCloseContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| closeNo | `String` | @NotBlank @Size(max=32) |
| snapshotHash | `String` | @NotBlank @Pattern(regexp="[a-f0-9]{64}") |

#### `ai.neargo.shop.food.dto.DiningPickupContracts.PickupVerificationVO`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/DiningPickupContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| batchNo | `String` |  |
| localHandoverRecorded | `boolean` |  |
| originalFulfillment | `Receipt` |  |

#### `ai.neargo.shop.food.dto.DiningPickupContracts.VerifyPickupReq`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/DiningPickupContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| expectedVersion | `Long` | @JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull |
| verifyCode | `String` | @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Pattern(regexp="[0-9]{6}") |

#### `ai.neargo.shop.food.dto.KitchenContracts.CourseActionReq`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/KitchenContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| version | `Long` | @JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull |
| action | `String` | @NotBlank |
| reason | `String` | @Size(max=255) |
| seatNos | `List<String>` |  |

#### `ai.neargo.shop.food.dto.KitchenContracts.CourseFireReq`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/KitchenContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| version | `Long` | @JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull |
| seatNos | `List< String>` | @NotNull @Size(max=100) @NotBlank |

#### `ai.neargo.shop.food.dto.KitchenContracts.CourseReq`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/KitchenContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| name | `String` | @NotBlank @Size(max=64) |
| sequenceNo | `Integer` | @NotNull |

#### `ai.neargo.shop.food.dto.KitchenContracts.CourseVO`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/KitchenContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| courseNo | `String` |  |
| checkNo | `String` |  |
| name | `String` |  |
| sequenceNo | `int` |  |
| status | `String` |  |
| version | `long` |  |

#### `ai.neargo.shop.food.dto.KitchenContracts.EventVO`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/KitchenContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| eventNo | `String` |  |
| taskNo | `String` |  |
| action | `String` |  |
| qty | `BigDecimal` |  |
| reason | `String` |  |
| inventoryDocNo | `String` |  |

#### `ai.neargo.shop.food.dto.KitchenContracts.HandoverLine`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/KitchenContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| foodLineNo | `String` | @NotBlank |
| qty | `BigDecimal` | @JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) @NotNull |

#### `ai.neargo.shop.food.dto.KitchenContracts.HandoverReq`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/KitchenContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| kind | `String` | @NotBlank |
| lines | `List<HandoverLine>` | @NotNull |
| reason | `String` | @NotBlank @Size(max=255) |

#### `ai.neargo.shop.food.dto.KitchenContracts.HandoverVO`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/KitchenContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| operationNo | `String` |  |
| batchNo | `String` |  |
| status | `String` |  |
| deliveryPending | `boolean` |  |
| coreDeliveryRef | `String` |  |

#### `ai.neargo.shop.food.dto.KitchenContracts.RemakeReq`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/KitchenContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| version | `Long` | @JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull |
| qty | `BigDecimal` | @JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) @NotNull |
| reason | `String` | @NotBlank @Size(max=255) |

#### `ai.neargo.shop.food.dto.KitchenContracts.TaskDeltaReq`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/KitchenContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| version | `Long` | @JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull |
| qty | `BigDecimal` | @JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) @NotNull |
| reason | `String` | @Size(max=255) |

#### `ai.neargo.shop.food.dto.KitchenContracts.TaskVO`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/KitchenContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| taskNo | `String` |  |
| ticketNo | `String` |  |
| batchNo | `String` |  |
| foodLineNo | `String` |  |
| componentNo | `String` |  |
| stationNo | `String` |  |
| taskRole | `String` |  |
| routeVersion | `long` |  |
| qty | `BigDecimal` |  |
| makingQty | `BigDecimal` |  |
| readyQty | `BigDecimal` |  |
| servedQty | `BigDecimal` |  |
| voidQty | `BigDecimal` |  |
| status | `String` |  |
| seatNo | `String` |  |
| courseNo | `String` |  |
| version | `long` |  |
| ticketKind | `String` |  |
| originalTicketNo | `String` |  |

#### `ai.neargo.shop.food.dto.KitchenContracts.TicketVO`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/KitchenContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| ticketNo | `String` |  |
| batchNo | `String` |  |
| kind | `String` |  |
| originalTicketNo | `String` |  |
| status | `String` |  |
| tasks | `List<TaskVO>` |  |

#### `ai.neargo.shop.food.dto.KitchenReturnContracts.HandleReq`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/KitchenReturnContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| version | `Long` | @JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull |
| decisions | `List< ItemDecision>` | @NotNull @Size(min=1,max=1000) @Valid |
| reason | `String` | @NotBlank @Size(max=255) |

#### `ai.neargo.shop.food.dto.KitchenReturnContracts.HandledVO`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/KitchenReturnContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| operationNo | `String` |  |
| returnNo | `String` |  |
| kitchenStatus | `String` |  |

#### `ai.neargo.shop.food.dto.KitchenReturnContracts.ItemDecision`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/KitchenReturnContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| returnItemNo | `String` | @NotBlank |
| disposition | `String` | @NotBlank |

#### `ai.neargo.shop.food.dto.KitchenReturnContracts.ItemVO`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/KitchenReturnContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| returnItemNo | `String` |  |
| foodLineNo | `String` |  |
| componentNo | `String` |  |
| qty | `BigDecimal` |  |
| disposition | `String` |  |

#### `ai.neargo.shop.food.dto.KitchenReturnContracts.ReturnKitchenVO`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/KitchenReturnContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| returnNo | `String` |  |
| batchNo | `String` |  |
| kitchenStatus | `String` |  |
| version | `long` |  |
| items | `List<ItemVO>` |  |

#### `ai.neargo.shop.food.dto.OperationVO`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/OperationVO.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| operationNo | `String` |  |
| status | `String` |  |
| version | `long` |  |
| resultRef | `String` |  |

#### `ai.neargo.shop.food.dto.PrintDocumentReq`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/PrintDocumentReq.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| scene | `String` | @NotBlank @Size(max=16) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) |
| sourceNo | `String` | @NotBlank @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) |
| stationNo | `String` | @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) |

#### `ai.neargo.shop.food.dto.ProductionContracts.ConsumptionVO`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ProductionContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| consumptionNo | `String` |  |
| sourceEventNo | `String` |  |
| purpose | `String` |  |
| status | `String` |  |
| requestHash | `String` |  |
| inventoryOperationRef | `String` |  |
| businessDate | `LocalDate` |  |
| occurredAt | `LocalDateTime` |  |
| lines | `List<LineVO>` |  |
| quantityEvidence | `String` |  |
| consumptionPending | `boolean` |  |
| totalCostMinor | `Long` |  |
| costCurrency | `String` |  |
| version | `long` |  |

#### `ai.neargo.shop.food.dto.ProductionContracts.LineVO`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ProductionContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| lineNo | `int` |  |
| componentPath | `String` |  |
| itemRef | `String` |  |
| theoryQty | `BigDecimal` |  |
| unit | `String` |  |
| recipeRevisionNo | `String` |  |
| inventoryReceiptRef | `String` |  |
| inventoryIssuedQty | `BigDecimal` |  |
| costMinor | `Long` |  |
| currency | `String` |  |
| status | `String` |  |

#### `ai.neargo.shop.food.dto.ProductionContracts.ListQuery`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/ProductionContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| page | `Integer` |  |
| size | `Integer` |  |
| from | `LocalDate` |  |
| to | `LocalDate` |  |
| skuNo | `String` |  |
| itemRef | `String` |  |
| status | `String` |  |

#### `ai.neargo.shop.food.dto.QueueContracts.ChangeReq`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/QueueContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| version | `Long` | @NotNull @Min(0) |
| reason | `String` | @Size(max=255) |

#### `ai.neargo.shop.food.dto.QueueContracts.IssueReq`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/QueueContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| partySize | `Integer` | @NotNull @Min(1) @Max(1000) |
| contactRef | `String` | @Size(max=64) |

#### `ai.neargo.shop.food.dto.QueueContracts.QueueVO`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/QueueContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| ticketNo | `String` |  |
| businessDate | `LocalDate` |  |
| partySize | `int` |  |
| status | `String` |  |
| calledAt | `LocalDateTime` |  |
| checkNo | `String` |  |
| version | `long` |  |
| notificationStatus | `String` |  |

#### `ai.neargo.shop.food.dto.QueueContracts.SeatQueueReq`

来源：`backend-plugins/shop-industry-food/food-api/src/main/java/ai/neargo/shop/food/dto/QueueContracts.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| version | `Long` | @NotNull @Min(0) |
| check | `OpenCheckReq` | @NotNull @Valid |

#### `ai.neargo.shop.spi.printing.PrintPort.Acknowledge`

来源：`backend/shop-base/src/main/java/ai/neargo/shop/spi/printing/PrintPort.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| attemptNo | `String` |  |
| leaseToken | `String` |  |
| status | `String` |  |
| channelJobNo | `String` |  |
| responseCode | `String` |  |

#### `ai.neargo.shop.spi.printing.PrintPort.AttemptVO`

来源：`backend/shop-base/src/main/java/ai/neargo/shop/spi/printing/PrintPort.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| attemptNo | `String` |  |
| sequenceNo | `int` |  |
| status | `String` |  |
| responseCode | `String` |  |
| startedAt | `LocalDateTime` |  |
| finishedAt | `LocalDateTime` |  |

#### `ai.neargo.shop.spi.printing.PrintPort.Claim`

来源：`backend/shop-base/src/main/java/ai/neargo/shop/spi/printing/PrintPort.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| jobNo | `String` |  |
| deviceNo | `String` |  |
| attemptNo | `String` |  |
| leaseToken | `String` |  |
| leaseUntil | `LocalDateTime` |  |
| renderedContent | `String` |  |

#### `ai.neargo.shop.spi.printing.PrintPort.Configuration`

来源：`backend/shop-base/src/main/java/ai/neargo/shop/spi/printing/PrintPort.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| devices | `List<DeviceVO>` |  |
| templates | `List<TemplateReq>` |  |
| routes | `List<RouteReq>` |  |

#### `ai.neargo.shop.spi.printing.PrintPort.DeviceReq`

来源：`backend/shop-base/src/main/java/ai/neargo/shop/spi/printing/PrintPort.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| deviceNo | `String` |  |
| vendor | `String` |  |
| connectionType | `String` |  |
| credentialRef | `String` |  |
| bridgeActorNo | `String` |  |
| capabilities | `String` |  |
| active | `boolean` |  |

#### `ai.neargo.shop.spi.printing.PrintPort.DeviceVO`

来源：`backend/shop-base/src/main/java/ai/neargo/shop/spi/printing/PrintPort.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| deviceNo | `String` |  |
| vendor | `String` |  |
| connectionType | `String` |  |
| bridgeActorNo | `String` |  |
| status | `String` |  |
| capabilities | `String` |  |
| lastSeenAt | `LocalDateTime` |  |
| online | `boolean` |  |

#### `ai.neargo.shop.spi.printing.PrintPort.Document`

来源：`backend/shop-base/src/main/java/ai/neargo/shop/spi/printing/PrintPort.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| schemaVersion | `int` |  |
| scene | `String` |  |
| sourceRef | `String` |  |
| title | `String` |  |
| currency | `String` |  |
| lines | `List<Line>` |  |
| notice | `String` |  |

#### `ai.neargo.shop.spi.printing.PrintPort.JobVO`

来源：`backend/shop-base/src/main/java/ai/neargo/shop/spi/printing/PrintPort.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| jobNo | `String` |  |
| storeNo | `String` |  |
| sourceEventNo | `String` |  |
| scene | `String` |  |
| deviceNo | `String` |  |
| partNo | `String` |  |
| copyIndex | `int` |  |
| templateNo | `String` |  |
| status | `String` |  |
| originalJobNo | `String` |  |
| reprintReason | `String` |  |
| requestedBy | `String` |  |
| document | `Document` |  |
| renderedContent | `String` |  |
| attempts | `List<AttemptVO>` |  |

#### `ai.neargo.shop.spi.printing.PrintPort.Line`

来源：`backend/shop-base/src/main/java/ai/neargo/shop/spi/printing/PrintPort.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| label | `String` |  |
| quantity | `String` |  |
| amountMinor | `Long` |  |
| note | `String` |  |

#### `ai.neargo.shop.spi.printing.PrintPort.RouteReq`

来源：`backend/shop-base/src/main/java/ai/neargo/shop/spi/printing/PrintPort.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| routeNo | `String` |  |
| scene | `String` |  |
| stationNo | `String` |  |
| deviceNo | `String` |  |
| templateNo | `String` |  |
| copies | `int` |  |

#### `ai.neargo.shop.spi.printing.PrintPort.TemplateReq`

来源：`backend/shop-base/src/main/java/ai/neargo/shop/spi/printing/PrintPort.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| templateNo | `String` |  |
| templateCode | `String` |  |
| revision | `int` |  |
| scene | `String` |  |
| content | `String` |  |

#### `ai.neargo.shop.spi.trade.IndustryFulfillmentPort.Receipt`

来源：`backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/IndustryFulfillmentPort.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| fulfillmentNo | `String` |  |
| reference | `Reference` |  |
| status | `String` |  |
| subOrderStatus | `String` |  |
| requireBuyerConfirm | `boolean` |  |
| pending | `boolean` |  |
| inventoryReservationNo | `String` |  |
| inventoryState | `String` |  |
| inventoryLifecycleVersion | `int` |  |
| subOrderVersion | `long` |  |

#### `ai.neargo.shop.spi.trade.IndustryFulfillmentPort.Reference`

来源：`backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/IndustryFulfillmentPort.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| scope | `Scope` |  |
| source | `PricingSourceRef` |  |
| orderNo | `String` |  |
| subOrderNo | `String` |  |

#### `ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope`

来源：`backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/PricingSourceFactsPort.java`。

| 字段 | Java类型 | 声明约束 |
|---|---|---|
| tenantNo | `String` |  |
| entityNo | `String` |  |
| storeNo | `String` |  |

### 11.4 未对齐目标与设备未知回执

| 目标 | 目标路径 | 本批对齐状态 | 实际路径 |
|---|---|---|---|
| FOOD-API-044 | `GET /biz/x/food/stores/{storeNo}/kitchen/tasks` | EXACT_PATH / PATH_AND_TOP_LEVEL_FIELD_NAMES_ALIGNED | `/biz/x/food/stores/{storeNo}/kitchen/tasks` |
| FOOD-API-045 | `POST /biz/x/food/tasks/{taskNo}:start` | EXACT_PATH / EXACT_PATH_WITH_DTO_DIFFERENCES | `/biz/x/food/tasks/{taskNo}:start` |
| FOOD-API-046 | `POST /biz/x/food/tasks/{taskNo}:ready` | EXACT_PATH / EXACT_PATH_WITH_DTO_DIFFERENCES | `/biz/x/food/tasks/{taskNo}:ready` |
| FOOD-API-047 | `POST /biz/x/food/tasks/{taskNo}:serve` | EXACT_PATH / EXACT_PATH_WITH_DTO_DIFFERENCES | `/biz/x/food/tasks/{taskNo}:serve` |
| FOOD-API-048 | `POST /biz/x/food/tasks/{taskNo}:hold` | EXACT_PATH / EXACT_PATH_WITH_DTO_DIFFERENCES | `/biz/x/food/tasks/{taskNo}:hold` |
| FOOD-API-049 | `POST /biz/x/food/tasks/{taskNo}:fire` | EXACT_PATH / EXACT_PATH_WITH_DTO_DIFFERENCES | `/biz/x/food/tasks/{taskNo}:fire` |
| FOOD-API-050 | `POST /biz/x/food/tasks/{taskNo}:urge` | EXACT_PATH / EXACT_PATH_WITH_DTO_DIFFERENCES | `/biz/x/food/tasks/{taskNo}:urge` |
| FOOD-API-051 | `POST /biz/x/food/tasks/{taskNo}:void` | EXACT_PATH / EXACT_PATH_WITH_DTO_DIFFERENCES | `/biz/x/food/tasks/{taskNo}:void` |
| FOOD-API-052 | `POST /biz/x/food/tasks/{taskNo}:remake` | EXACT_PATH / EXACT_PATH_WITH_DTO_DIFFERENCES | `/biz/x/food/tasks/{taskNo}:remake` |
| FOOD-API-053 | `POST /biz/x/food/checks/{checkNo}/courses` | EXACT_PATH / PATH_AND_TOP_LEVEL_FIELD_NAMES_ALIGNED | `/biz/x/food/checks/{checkNo}/courses` |
| FOOD-API-054 | `POST /biz/x/food/courses/{courseNo}:fire` | EXACT_PATH / EXACT_PATH_WITH_DTO_DIFFERENCES | `/biz/x/food/courses/{courseNo}:fire` |
| FOOD-API-055 | `POST /biz/x/food/batches/{batchNo}:handover` | EXACT_PATH / EXACT_PATH_WITH_DTO_DIFFERENCES | `/biz/x/food/batches/{batchNo}:handover` |
| FOOD-API-065 | `POST /biz/print/jobs` | PATH_CHANGED / TARGET_RECONCILIATION_REQUIRED | `/biz/x/food/print-jobs` |
| FOOD-API-066 | `GET /biz/print/jobs/{jobNo}` | PATH_CHANGED / TARGET_RECONCILIATION_REQUIRED | `/biz/x/food/print-jobs/{jobNo}` |
| FOOD-API-067 | `POST /biz/print/jobs/{jobNo}:reprint` | PATH_CHANGED / TARGET_RECONCILIATION_REQUIRED | `/biz/x/food/print-jobs/{jobNo}:reprint` |
| FOOD-API-072 | `POST /biz/x/food/stores/{storeNo}/closes` | PATH_AND_INPUT_CHANGED / TARGET_RECONCILIATION_REQUIRED | `/biz/x/food/closes:preview` |
| FOOD-API-073 | `GET /biz/x/food/closes/{closeNo}` | EXACT_PATH / EXACT_PATH_WITH_DTO_DIFFERENCES | `/biz/x/food/closes/{closeNo}` |
| FOOD-API-074 | `POST /biz/x/food/closes/{closeNo}/exceptions/{exceptionNo}:carry` | NO_IMPLEMENTED_PUBLIC_ROUTE_IN_THIS_BATCH / TARGET_NOT_IMPLEMENTED_IN_THIS_BATCH | 未注册对应实际路由 |
| FOOD-API-075 | `POST /biz/x/food/closes/{closeNo}:sign` | PATH_AND_INPUT_CHANGED / TARGET_RECONCILIATION_REQUIRED | `/biz/x/food/closes:sign` |
| FOOD-API-076 | `POST /biz/x/food/closes/{closeNo}/adjustments` | PATH_AND_INPUT_CHANGED / TARGET_RECONCILIATION_REQUIRED | `/biz/x/food/closes:adjust` |
| FOOD-API-077 | `GET /biz/x/food/reports/{reportCode}` | PARTIAL_TARGET_SUBSET / TARGET_RECONCILIATION_REQUIRED | `/biz/x/food/reports/business-day` |
| FOOD-API-078 | `POST /biz/x/food/report-exports` | NO_IMPLEMENTED_PUBLIC_ROUTE_IN_THIS_BATCH / TARGET_NOT_IMPLEMENTED_IN_THIS_BATCH | 未注册对应实际路由 |
| FOOD-API-079 | `GET /biz/x/food/report-exports/{exportNo}` | NO_IMPLEMENTED_PUBLIC_ROUTE_IN_THIS_BATCH / TARGET_NOT_IMPLEMENTED_IN_THIS_BATCH | 未注册对应实际路由 |
| FOOD-API-153 | `GET /biz/x/food/stores/{storeNo}/production-consumptions` | EXACT_PATH / EXACT_PATH_WITH_DTO_DIFFERENCES | `/biz/x/food/stores/{storeNo}/production-consumptions` |
| FOOD-API-154 | `GET /biz/x/food/production-consumptions/{consumptionNo}` | DIFFERENT_RESOURCE_KEY / TARGET_RECONCILIATION_REQUIRED | `/biz/x/food/production-events/{eventNo}` |
| FOOD-API-157 | `POST /biz/x/food/queue-tickets` | EXACT_PATH / EXACT_PATH_WITH_DTO_DIFFERENCES | `/biz/x/food/queue-tickets` |
| FOOD-API-158 | `GET /biz/x/food/queue-tickets` | EXACT_PATH / PATH_AND_TOP_LEVEL_FIELD_NAMES_ALIGNED | `/biz/x/food/queue-tickets` |
| FOOD-API-159 | `GET /biz/x/food/queue-tickets/{ticketNo}` | EXACT_PATH / EXACT_PATH_WITH_DTO_DIFFERENCES | `/biz/x/food/queue-tickets/{ticketNo}` |
| FOOD-API-160 | `POST /biz/x/food/queue-tickets:call-next` | EXACT_PATH / EXACT_PATH_WITH_DTO_DIFFERENCES | `/biz/x/food/queue-tickets:call-next` |
| FOOD-API-161 | `POST /biz/x/food/queue-tickets/{ticketNo}:call` | EXACT_PATH / EXACT_PATH_WITH_DTO_DIFFERENCES | `/biz/x/food/queue-tickets/{ticketNo}:call` |
| FOOD-API-162 | `POST /biz/x/food/queue-tickets/{ticketNo}:cancel` | EXACT_PATH / EXACT_PATH_WITH_DTO_DIFFERENCES | `/biz/x/food/queue-tickets/{ticketNo}:cancel` |
| FOOD-API-163 | `POST /biz/x/food/queue-tickets/{ticketNo}:no-show` | EXACT_PATH / EXACT_PATH_WITH_DTO_DIFFERENCES | `/biz/x/food/queue-tickets/{ticketNo}:no-show` |
| FOOD-API-164 | `POST /biz/x/food/queue-tickets/{ticketNo}:seat` | EXACT_PATH / EXACT_PATH_WITH_DTO_DIFFERENCES | `/biz/x/food/queue-tickets/{ticketNo}:seat` |
| FOOD-API-165 | `POST /biz/x/food/queue-tickets/{ticketNo}:recover-seating` | EXACT_PATH / EXACT_PATH_WITH_DTO_DIFFERENCES | `/biz/x/food/queue-tickets/{ticketNo}:recover-seating` |

内部打印claim采用ClaimResult信封：显式claim=null表示成功空闲，信封缺失仍报错。历史P2已编码修复：claim必需字段、非空有界key及规范UUID校验；ACK只认最新连续唯一尝试、规范状态及responseCode一致。保留owner允许的FAILED→retry/PENDING重放，远程适配器不以本地时钟拒绝过去租约。失去claim响应可能已留下SENT租约；超时为UNKNOWN，不自动补投。普通retry仅限真实NOT_PRINTED失败，未知回执需原尝试对账或带原单原因的人工补打。

### 11.5 自动打印、默认KDS与本批已编码修复

打印归属：实际公开BizPrintJobController位于food-web的ai.neargo.shop.food.api.biz。enqueue先调用FoodPrintService冻结文档，再调用shop-base的ai.neargo.shop.spi.printing.PrintPort；list/get/retry/reprint直接调用该PrintPort。共享PrintService/PrintServiceImpl、prn Entity与PrintMappers唯一归shop-core的ai.neargo.shop.printing。原/biz/print/jobs仍为PLANNED_SHARED_B_ENTRY_NOT_REGISTERED，目标Req/VO仅PLANNED_TRANSPORT_CONTRACT；实际DTO以本节真实Java声明为准。

DINING_ORDER_ACCEPTED先持久化/重放原MAKE票，再读取共享打印配置，对已配置ORDER及原工位KITCHEN route自动入队；与手动首次打印共用原逻辑文档身份。无配置route不造打印任务，入队不代表物理出纸。

默认KDS只读真实ACCEPTED批次、未CLOSED票及非SERVED/VOIDED任务；显式batchNo可查原历史。MAKE/REMAKE及原票引用保持，轮询不提供SSE/推送送达证明。

CorePrint回执历史审查保留为历史证据；当前修复登记为CODED_PENDING_VERIFICATION，覆盖显式空闲/缺失信封、最新attempt与规范状态/responseCode、key与UUID及晚到/重复原回执。测试源码：`backend-plugins/shop-industry-food/food-channel/src/test/java/ai/neargo/shop/food/channel/CorePrintPortTest.java`。这不替代硬件真回执或将全目标判定完成。

## 十二、当前员工代客下单切片（本批后端编译及测试已执行）

本节覆盖后续员工下单增量；前两节保留历史切片的原时点。实际状态为BACKEND_TESTED_SCOPE_LIMITED，不代表FOOD-API-042完整目标完成。当前支持POS/WAITER、POSTPAY、真实注册顾客、PICKUP或有有效台账的DINE_IN，以及完整整数MAIN及已支持ADDON；员工身份与顾客分开，禁止冒充消费者Credentials，不执行收款或资产消费。

`POST /biz/x/food/stores/{storeNo}/batches` · BizOrderBatchController.place · biz:verify

请求：`ai.neargo.shop.food.dto.CreateStaffDiningOrderReq`，Header：`Idempotency-Key`；输出data：`ai.neargo.shop.food.dto.DiningOrderBatchVO`。

| 输入字段 | 类型 | 必填 | 含义 |
|---|---|---|---|
| buyerNo | String | 是 | 真实已注册顾客；非操作员工，不授予付款授权 |
| cartNo | String | 是 | 原当前门店POS/WAITER完整购物车 |
| version | long | 是 | 原车版本，与报价一致 |
| quoteNo | String | 是 | 当前员工创建的staff报价 |
| confirmationDigest | String | 是 | 原展示确认摘要，服务端重验 |
| payMode | String | 是 | 当前只支持POSTPAY |

守卫：FoodBizStoreScope.path + real registered buyer + authenticated staff; POS/WAITER POSTPAY PICKUP or DINE_IN integer MAIN/ADDON whole cart; DINE_IN live check/cart locks and original frozen hashes; quote authority and original identity recovery。剩余边界：匿名/设备、外送正式下单、堂食核心履约完成、套餐库存策略、小数成交、selectedLineRefs/splitByPerson；无新增支付/退款/资产执行。

员工身份持久化复用原fnb批次、原核心订单及来源绑定，组件等结构增量见第十三节；buyer_no保存顾客，operator_no保存员工，actor_realm沿MERCHANT原枚举。内部合同：DiningOrderInternalApi.staffPlace、IndustryOrderPort.placeForStore、IndustryOrderPort.queryForStore、IndustryOrderPort.placeForDiningRoom、IndustryOrderInternalApi.placeForDiningRoom。恢复只查持久化原员工/顾客/批次键，不重开单。

本批已完成记录范围的后端编译和集中测试；真实结果及跳过项见原TDD§2.14。未完成业务与目标合同差异仍保持待办。

## 十三、连续业务编码的实际合同（本批后端编译及测试已执行）

涉及29条实际路由，其中20条为相对原提交的新增公开路由；已有路由的DTO/业务扩展与新增分开展示。当前批次状态为BACKEND_TESTED_SCOPE_LIMITED，仅表示本批已记录的源码或测试范围；原50条非支付收据仍保留其历史时点，目标API与完整需求差异不自动消除。

| 编码编号 | 方法与实际路径 | Java入口 | 请求 | data响应 | 新增路由 | 目标映射 |
|---|---|---|---|---|---|---|
| FOOD-BUSINESS-CODE-001 | `POST /biz/x/food/stores/{storeNo}/batches` | BizOrderBatchController.place | storeNo:String:path;body:CreateStaffDiningOrderReq:body;Idempotency-Key:String:header | ai.neargo.shop.food.dto.DiningOrderBatchVO | 是 | FOOD-API-042 / EXACT_PATH_WITH_DTO_DIFFERENCES |
| FOOD-BUSINESS-CODE-002 | `POST /biz/x/food/batches/{batchNo}:cancel` | BizOrderBatchController.cancel | batchNo:String:path;body:CancelDiningOrderReq:body;Idempotency-Key:String:header | ai.neargo.shop.food.dto.DiningOrderBatchVO | 是 | FOOD-API-124 / EXACT_PATH_WITH_DTO_DIFFERENCES |
| FOOD-BUSINESS-CODE-003 | `GET /biz/x/food/batches/{batchNo}` | BizOrderBatchController.get | batchNo:String:path | ai.neargo.shop.food.dto.DiningOrderBatchVO | 沿用/扩展 | FOOD-API-043 / EXACT_PATH_WITH_DTO_DIFFERENCES |
| FOOD-BUSINESS-CODE-004 | `GET /biz/x/food/stores/{storeNo}/reservations` | BizReservationController.listReservations | storeNo:String:path;query:ListReservationsQuery:query-object | ai.neargo.shop.common.PageData<ai.neargo.shop.food.dto.ReservationVO> | 沿用/扩展 | FOOD-API-107 / EXACT_PATH_WITH_DTO_DIFFERENCES |
| FOOD-BUSINESS-CODE-005 | `GET /biz/x/food/reservations/{reservationNo}` | BizReservationController.get | reservationNo:String:path | ai.neargo.shop.food.dto.ReservationContracts.DetailVO | 是 | 本次新增业务合同 / ADDED_PUBLIC_CONTRACT_NEEDS_TARGET_REGISTRATION |
| FOOD-BUSINESS-CODE-006 | `POST /biz/x/food/stores/{storeNo}/reservations` | BizReservationController.create | storeNo:String:path;body:CreateReq:body;Idempotency-Key:String:header | ai.neargo.shop.food.dto.ReservationContracts.DetailVO | 沿用/扩展 | FOOD-API-027 / EXACT_PATH_WITH_DTO_DIFFERENCES |
| FOOD-BUSINESS-CODE-007 | `POST /biz/x/food/reservations/{reservationNo}:change` | BizReservationController.change | reservationNo:String:path;body:ChangeReq:body;Idempotency-Key:String:header | ai.neargo.shop.food.dto.ReservationContracts.DetailVO | 是 | 本次新增业务合同 / ADDED_PUBLIC_CONTRACT_NEEDS_TARGET_REGISTRATION |
| FOOD-BUSINESS-CODE-008 | `POST /biz/x/food/reservations/{reservationNo}:cancel` | BizReservationController.cancel | reservationNo:String:path;body:TransitionReq:body;Idempotency-Key:String:header | ai.neargo.shop.food.dto.ReservationContracts.DetailVO | 是 | FOOD-API-028 / EXACT_PATH_WITH_DTO_DIFFERENCES |
| FOOD-BUSINESS-CODE-009 | `POST /biz/x/food/reservations/{reservationNo}:no-show` | BizReservationController.noShow | reservationNo:String:path;body:TransitionReq:body;Idempotency-Key:String:header | ai.neargo.shop.food.dto.ReservationContracts.DetailVO | 是 | FOOD-API-029 / EXACT_PATH_WITH_DTO_DIFFERENCES |
| FOOD-BUSINESS-CODE-010 | `POST /biz/x/food/reservations/{reservationNo}:arrive` | BizReservationController.arrive | reservationNo:String:path;body:ArriveReq:body;Idempotency-Key:String:header | ai.neargo.shop.food.dto.ReservationContracts.DetailVO | 是 | FOOD-API-030 / EXACT_PATH_WITH_DTO_DIFFERENCES |
| FOOD-BUSINESS-CODE-011 | `POST /biz/x/food/batches/{batchNo}:handover` | BizDiningHandoverController.handover | batchNo:String:path;req:HandoverReq:body;Idempotency-Key:String:header | ai.neargo.shop.food.dto.KitchenContracts.HandoverVO | 沿用/扩展 | FOOD-API-055 / EXACT_PATH_WITH_DTO_DIFFERENCES |
| FOOD-BUSINESS-CODE-012 | `POST /biz/x/food/batches/{batchNo}:confirm-packing` | BizDiningHandoverController.confirmPacking | batchNo:String:path;req:PackingReq:body;Idempotency-Key:String:header | ai.neargo.shop.food.dto.KitchenContracts.CustodyReceiptVO | 是 | 本次新增业务合同 / ADDED_PUBLIC_CONTRACT_NEEDS_TARGET_REGISTRATION |
| FOOD-BUSINESS-CODE-013 | `GET /biz/x/food/batches/{batchNo}/custody-receipts` | BizDiningHandoverController.custodyReceipts | batchNo:String:path | java.util.List<ai.neargo.shop.food.dto.KitchenContracts.CustodyReceiptVO> | 是 | 本次新增业务合同 / ADDED_PUBLIC_CONTRACT_NEEDS_TARGET_REGISTRATION |
| FOOD-BUSINESS-CODE-014 | `POST /biz/x/food/business-days/{date}:open` | BizBusinessCloseController.open | date:LocalDate:path;Idempotency-Key:String:header | ai.neargo.shop.food.dto.BusinessCloseContracts.DayVO | 沿用/扩展 | 本次新增业务合同 / ADDED_PUBLIC_CONTRACT_NEEDS_TARGET_REGISTRATION |
| FOOD-BUSINESS-CODE-016 | `POST /biz/x/food/closes:preview` | BizBusinessCloseController.preview | body:PreviewReq:body;Idempotency-Key:String:header | ai.neargo.shop.food.dto.BusinessCloseContracts.CloseVO | 沿用/扩展 | FOOD-API-072 / TARGET_RECONCILIATION_REQUIRED |
| FOOD-BUSINESS-CODE-017 | `GET /biz/x/food/closes/{closeNo}` | BizBusinessCloseController.get | closeNo:String:path | ai.neargo.shop.food.dto.BusinessCloseContracts.CloseVO | 沿用/扩展 | FOOD-API-073 / EXACT_PATH_WITH_DTO_DIFFERENCES |
| FOOD-BUSINESS-CODE-018 | `POST /biz/x/food/closes:sign` | BizBusinessCloseController.sign | body:SignReq:body;Idempotency-Key:String:header | ai.neargo.shop.food.dto.BusinessCloseContracts.CloseVO | 沿用/扩展 | FOOD-API-075 / TARGET_RECONCILIATION_REQUIRED |
| FOOD-BUSINESS-CODE-019 | `POST /biz/x/food/closes:adjust` | BizBusinessCloseController.adjust | body:AdjustmentReq:body;Idempotency-Key:String:header | ai.neargo.shop.food.dto.OperationVO | 沿用/扩展 | FOOD-API-076 / TARGET_RECONCILIATION_REQUIRED |
| FOOD-BUSINESS-CODE-020 | `GET /biz/x/food/closes/{closeNo}/exceptions` | BizBusinessCloseController.exceptions | closeNo:String:path | java.util.List<ai.neargo.shop.food.dto.BusinessCloseContracts.ExceptionVO> | 是 | 本次新增业务合同 / ADDED_PUBLIC_CONTRACT_NEEDS_TARGET_REGISTRATION |
| FOOD-BUSINESS-CODE-021 | `POST /biz/x/food/closes/{closeNo}/exceptions/{exceptionNo}:propose-carry` | BizBusinessCloseController.proposeCarry | closeNo:String:path;exceptionNo:String:path;body:ProposeCarryReq:body;Idempotency-Key:String:header | ai.neargo.shop.food.dto.BusinessCloseContracts.ExceptionVO | 是 | 本次新增业务合同 / ADDED_PUBLIC_CONTRACT_NEEDS_TARGET_REGISTRATION |
| FOOD-BUSINESS-CODE-022 | `POST /biz/x/food/closes/{closeNo}/exceptions/{exceptionNo}:approve-carry` | BizBusinessCloseController.approveCarry | closeNo:String:path;exceptionNo:String:path;body:ApproveCarryReq:body;Idempotency-Key:String:header | ai.neargo.shop.food.dto.BusinessCloseContracts.ExceptionVO | 是 | 本次新增业务合同 / ADDED_PUBLIC_CONTRACT_NEEDS_TARGET_REGISTRATION |
| FOOD-BUSINESS-CODE-023 | `POST /biz/x/food/checks/{checkNo}/business-share-plans` | BizBusinessShareController.create | checkNo:String:path;request:CreateReq:body;Idempotency-Key:String:header | ai.neargo.shop.food.dto.BusinessShareContracts.PlanVO | 是 | 本次新增业务合同 / ADDED_PUBLIC_CONTRACT_NEEDS_TARGET_REGISTRATION |
| FOOD-BUSINESS-CODE-024 | `GET /biz/x/food/checks/{checkNo}/business-share-plans/{planNo}` | BizBusinessShareController.get | checkNo:String:path;planNo:String:path | ai.neargo.shop.food.dto.BusinessShareContracts.PlanVO | 是 | 本次新增业务合同 / ADDED_PUBLIC_CONTRACT_NEEDS_TARGET_REGISTRATION |
| FOOD-BUSINESS-CODE-025 | `POST /biz/x/food/checks/{checkNo}/business-share-plans/{planNo}:confirm` | BizBusinessShareController.confirm | checkNo:String:path;planNo:String:path;request:ConfirmReq:body;Idempotency-Key:String:header | ai.neargo.shop.food.dto.BusinessShareContracts.PlanVO | 是 | 本次新增业务合同 / ADDED_PUBLIC_CONTRACT_NEEDS_TARGET_REGISTRATION |
| FOOD-BUSINESS-CODE-026 | `POST /biz/x/food/checks/{checkNo}/business-share-plans/{planNo}:cancel` | BizBusinessShareController.cancel | checkNo:String:path;planNo:String:path;request:CancelReq:body;Idempotency-Key:String:header | ai.neargo.shop.food.dto.BusinessShareContracts.PlanVO | 是 | 本次新增业务合同 / ADDED_PUBLIC_CONTRACT_NEEDS_TARGET_REGISTRATION |
| FOOD-BUSINESS-CODE-027 | `GET /biz/x/food/stores/{storeNo}/batches` | BizDiningBatchQueryController.list | storeNo:String:path;query:ListDiningBatchesQuery:query-object | ai.neargo.shop.common.PageData<ai.neargo.shop.food.dto.DiningBatchSummaryVO> | 是 | FOOD-API-123 / EXACT_PATH_WITH_DTO_DIFFERENCES |
| FOOD-BUSINESS-CODE-028 | `POST /biz/x/food/batches/{batchNo}/business-returns` | BizBusinessReturnController.create | batchNo:String:path;request:CreateReq:body;Idempotency-Key:String:header | ai.neargo.shop.food.dto.BusinessReturnContracts.ReturnVO | 是 | 本次新增业务合同 / ADDED_PUBLIC_CONTRACT_NEEDS_TARGET_REGISTRATION |
| FOOD-BUSINESS-CODE-029 | `GET /biz/x/food/business-returns/{returnNo}` | BizBusinessReturnController.get | returnNo:String:path | ai.neargo.shop.food.dto.BusinessReturnContracts.ReturnVO | 是 | 本次新增业务合同 / ADDED_PUBLIC_CONTRACT_NEEDS_TARGET_REGISTRATION |
| FOOD-BUSINESS-CODE-030 | `POST /biz/x/food/business-returns/{returnNo}:cancel` | BizBusinessReturnController.cancel | returnNo:String:path;request:CancelReq:body;Idempotency-Key:String:header | ai.neargo.shop.food.dto.BusinessReturnContracts.ReturnVO | 是 | 本次新增业务合同 / ADDED_PUBLIC_CONTRACT_NEEDS_TARGET_REGISTRATION |

### 共享人工调价（shop-core）

复用 CouponCalculation + PricingEngine + TaxEngine，真实门店策略与审批只授权让价，报价在下单时重新核验；不执行收款、退款或资产消费。员工自审限额固定0。

| 方法 | 实际路径 | 输入 | 输出 |
|---|---|---|---|
| GET | /biz/pricing-manual-policies/{currency}/{reasonCode}/{kind} | currency/reasonCode/kind path | PolicyVO |
| PUT | /biz/pricing-manual-policies/{currency}/{reasonCode}/{kind} | Save(expectedVersion,enabled,selfLimitMinor=0,maxLimitMinor,minPayableRateBp); Idempotency-Key | PolicyVO |
| POST | /biz/pricing-manual-adjustments | Request(source,kind,amountMinor?,payableRateBp?,reasonCode,reason); Idempotency-Key | AdjustmentVO |
| GET | /biz/pricing-manual-adjustments/{no} | no path | AdjustmentVO |
| POST | /biz/pricing-manual-adjustments/{no}:approve | Approve(version); Idempotency-Key | AdjustmentVO |
| POST | /biz/pricing-manual-adjustments/{no}:quote | no path; Idempotency-Key | PricingCheckoutPort.QuoteVO |

阻断项：cash rounding；employee self-limit above zero without cumulative quota owner；bundles；unsupported tax basis；funds execution。

### 处理中批次撤销

仅MERCHANT POS/WAITER POSTPAY original batch with no local binding, PREPARED/PLACING/UNKNOWN only。CANCEL_PENDING表示已保存撤销意图，只有原核心RELEASED才能标取消（无原意图时写持久tombstone，有原预占时须原库存确认释放）；正式订单先创建则保留原债务并记录撤销拒绝。

来源验证采用原批次冻结价格事实、完整原Command/履约/键及持久撤销审计，不重新检查当前菜单、剩余额度、库存、规则或税配置。缺能力默认拒绝，不回退实时可售接口。复用fnb_order_batch/fnb_action_log，无新表或迁移；已受理退餐、退款及返库不在该取消合同内。本批后端编译及测试结果见原TDD§2.14。

### 实际DTO字段（当前源码）

#### ai.neargo.shop.common.PageData

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| records | List<T> | 否 |  |
| total | long | 是 |  |
| page | long | 是 |  |
| size | long | 是 |  |

#### ai.neargo.shop.food.dto.BusinessCloseContracts.AdjustmentReq

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| originalCloseNo | String | 是 | @NotBlank @Size(max=32) |
| currentDayNo | String | 是 | @NotBlank @Size(max=32) |
| coreReference | String | 是 | @NotBlank @Size(max=64) |
| reason | String | 是 | @NotBlank @Size(max=255) |
| approvalRef | String | 是 | @NotBlank @Size(max=64) |

#### ai.neargo.shop.food.dto.BusinessCloseContracts.ApproveCarryReq

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| version | Long | 是 | @NotNull @Min(0) |

#### ai.neargo.shop.food.dto.BusinessCloseContracts.CloseVO

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| closeNo | String | 否 |  |
| dayNo | String | 否 |  |
| currency | String | 否 |  |
| revision | int | 是 |  |
| status | String | 否 |  |
| snapshotHash | String | 否 |  |
| previousCloseNo | String | 否 |  |
| signedBy | String | 否 |  |
| signedAt | LocalDateTime | 否 |  |
| report | ReportVO | 否 |  |
| version | long | 是 |  |

#### ai.neargo.shop.food.dto.BusinessCloseContracts.DayVO

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| dayNo | String | 否 |  |
| businessDate | LocalDate | 否 |  |
| policyNo | String | 否 |  |
| startsAt | LocalDateTime | 否 |  |
| endsAt | LocalDateTime | 否 |  |
| status | String | 否 |  |
| currentCloseRefs | String | 否 |  |
| version | long | 是 |  |

#### ai.neargo.shop.food.dto.BusinessCloseContracts.ExceptionVO

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| exceptionNo | String | 否 |  |
| closeNo | String | 否 |  |
| kind | String | 否 |  |
| sourceRef | String | 否 |  |
| status | String | 否 |  |
| ownerNo | String | 否 |  |
| nextDayNo | String | 否 |  |
| reason | String | 否 |  |
| proposedBy | String | 否 |  |
| approvedBy | String | 否 |  |
| approvedAt | LocalDateTime | 否 |  |
| approvalRef | String | 否 |  |
| version | long | 是 |  |

#### ai.neargo.shop.food.dto.BusinessCloseContracts.Issue

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| kind | String | 否 |  |
| sourceRef | String | 否 |  |

#### ai.neargo.shop.food.dto.BusinessCloseContracts.PreviewReq

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| businessDate | LocalDate | 是 | @NotNull |
| currency | String | 是 | @NotBlank @Pattern(regexp="[A-Z]{3}") |

#### ai.neargo.shop.food.dto.BusinessCloseContracts.ProposeCarryReq

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| version | Long | 是 | @NotNull @Min(0) |
| ownerNo | String | 是 | @NotBlank @Size(max=32) |
| nextDayNo | String | 是 | @NotBlank @Size(max=32) |
| reason | String | 是 | @NotBlank @Size(max=255) |

#### ai.neargo.shop.food.dto.BusinessCloseContracts.ReportVO

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| businessDate | LocalDate | 否 |  |
| currency | String | 否 |  |
| batchCount | long | 是 |  |
| quantitiesByUnit | Map<String, BigDecimal> | 否 |  |
| originalOrderServiceQuantitiesByActionUnit | Map<String, BigDecimal> | 否 |  |
| returnAndServiceScope | String | 否 |  |
| settlementAttemptCount | long | 是 |  |
| requestedDueMinor | long | 是 |  |
| returnCount | long | 是 |  |
| salesCompleteness | String | 否 |  |
| settlementCompleteness | String | 否 |  |
| refundCompleteness | String | 否 |  |
| issues | List<Issue> | 否 |  |
| sourceHash | String | 否 |  |

#### ai.neargo.shop.food.dto.BusinessCloseContracts.SignReq

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| closeNo | String | 是 | @NotBlank @Size(max=32) |
| snapshotHash | String | 是 | @NotBlank @Pattern(regexp="[a-f0-9]{64}") |

#### ai.neargo.shop.food.dto.BusinessReturnContracts.CancelReq

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| version | Long | 是 | @JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull |
| reason | String | 是 | @NotBlank @Size(max=255) |

#### ai.neargo.shop.food.dto.BusinessReturnContracts.ComponentSource

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| sourceComponentRef | String | 否 |  |
| kind | String | 否 |  |
| allocationNo | String | 否 |  |
| orderItemId | Long | 否 |  |
| originalQty | BigDecimal | 否 |  |
| unit | String | 否 |  |
| coreSnapshotHash | String | 否 |  |
| allocationVersion | Long | 否 |  |
| componentNo | String | 否 |  |
| componentVersion | Long | 否 |  |

#### ai.neargo.shop.food.dto.BusinessReturnContracts.CreateReq

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| batchVersion | Long | 是 | @JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull |
| lines | List< Line> | 是 | @NotNull @Size(min=1,max=256) @Valid |
| reason | String | 是 | @NotBlank @Size(max=255) |

#### ai.neargo.shop.food.dto.BusinessReturnContracts.Frozen

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| kind | String | 否 |  |
| schema | int | 是 |  |
| entityNo | String | 否 |  |
| storeNo | String | 否 |  |
| batchNo | String | 否 |  |
| batchVersion | Long | 否 |  |
| cartNo | String | 否 |  |
| cartVersion | Long | 否 |  |
| bindingNo | String | 否 |  |
| bindingVersion | Long | 否 |  |
| buyerNo | String | 否 |  |
| actorNo | String | 否 |  |
| reason | String | 否 |  |
| sourceHash | String | 否 |  |
| ownerRead | OwnerRead | 否 |  |
| items | List<SourceItem> | 否 |  |

#### ai.neargo.shop.food.dto.BusinessReturnContracts.ItemVO

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| returnItemNo | String | 否 |  |
| foodLineNo | String | 否 |  |
| sourceLineRef | String | 否 |  |
| qty | BigDecimal | 否 |  |
| kitchenDisposition | String | 否 |  |
| mainAllocationNo | String | 否 |  |

#### ai.neargo.shop.food.dto.BusinessReturnContracts.Line

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| foodLineNo | String | 是 | @NotBlank @Size(max=32) |
| qty | BigDecimal | 是 | @JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) @NotNull |

#### ai.neargo.shop.food.dto.BusinessReturnContracts.OwnerRead

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| checkedAt | String | 否 |  |
| original | Result | 否 |  |

#### ai.neargo.shop.food.dto.BusinessReturnContracts.ReturnVO

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| returnNo | String | 否 |  |
| batchNo | String | 否 |  |
| storeNo | String | 否 |  |
| version | long | 是 |  |
| status | String | 否 |  |
| financialStatus | String | 否 |  |
| kitchenStatus | String | 否 |  |
| coreCaseRef | String | 否 |  |
| refundRequested | boolean | 是 |  |
| originalPayableChanged | boolean | 是 |  |
| reason | String | 否 |  |
| actorNo | String | 否 |  |
| items | List<ItemVO> | 否 |  |
| source | Frozen | 否 |  |

#### ai.neargo.shop.food.dto.BusinessReturnContracts.SourceItem

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| foodLineNo | String | 否 |  |
| sourceLineRef | String | 否 |  |
| mainAllocationNo | String | 否 |  |
| mainOrderItemId | Long | 否 |  |
| qty | BigDecimal | 否 |  |
| originalQty | BigDecimal | 否 |  |
| lineVersion | Long | 否 |  |
| components | List<ComponentSource> | 否 |  |

#### ai.neargo.shop.food.dto.BusinessShareContracts.AllocationSource

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| allocationNo | String | 否 |  |
| batchNo | String | 否 |  |
| foodLineNo | String | 否 |  |
| sourceLineRef | String | 否 |  |
| sourceComponentRef | String | 否 |  |
| orderItemId | Long | 否 |  |
| originalQty | BigDecimal | 否 |  |
| unit | String | 否 |  |
| coreSnapshotHash | String | 否 |  |
| allocationVersion | Long | 否 |  |
| lineVersion | Long | 否 |  |
| selectionVersion | Long | 否 |  |
| lineSnapshotHash | String | 否 |  |
| currency | String | 否 |  |
| originalAmountMinor | long | 是 |  |

#### ai.neargo.shop.food.dto.BusinessShareContracts.BatchSource

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| batchNo | String | 否 |  |
| originalCheckNo | String | 否 |  |
| version | Long | 否 |  |
| bindingNo | String | 否 |  |
| bindingVersion | Long | 否 |  |
| orderNo | String | 否 |  |
| storeOrderNo | String | 否 |  |
| frozenRequestHash | String | 否 |  |
| memberships | List<MembershipScope> | 否 |  |

#### ai.neargo.shop.food.dto.BusinessShareContracts.CancelReq

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| version | Long | 是 | @JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull |
| reason | String | 是 | @NotBlank @Size(max=255) |

#### ai.neargo.shop.food.dto.BusinessShareContracts.ConfirmReq

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| version | Long | 是 | @JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull |
| sourceHash | String | 是 | @NotBlank @Size(min=64,max=64) |
| planHash | String | 是 | @NotBlank @Size(min=64,max=64) |

#### ai.neargo.shop.food.dto.BusinessShareContracts.CreateReq

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| checkVersion | Long | 是 | @JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull |
| mode | String | 是 | @NotBlank |
| people | List< Person> | 是 | @NotNull @Size(min=1,max=50) @Valid |
| lineWeights | List< LineWeight> | 否 | @Size(max=1000) @Valid |
| reason | String | 是 | @NotBlank @Size(max=255) |

#### ai.neargo.shop.food.dto.BusinessShareContracts.Division

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| people | List<PersonTotal> | 否 |  |
| cells | List<ShareCell> | 否 |  |

#### ai.neargo.shop.food.dto.BusinessShareContracts.LineWeight

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| foodLineNo | String | 是 | @NotBlank @Size(max=32) |
| personNo | String | 是 | @NotBlank @Size(max=32) |
| weight | Long | 是 | @JsonDeserialize(using=CatalogRevisionDeserializer.class) @NotNull |

#### ai.neargo.shop.food.dto.BusinessShareContracts.MembershipScope

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| membershipNo | String | 否 |  |
| checkNo | String | 否 |  |
| batchNo | String | 否 |  |
| version | Long | 否 |  |
| attachedAt | LocalDateTime | 否 |  |
| operationNo | String | 否 |  |

#### ai.neargo.shop.food.dto.BusinessShareContracts.Person

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| personNo | String | 是 | @NotBlank @Size(max=32) |
| amountMinor | Long | 否 | @JsonDeserialize(using=DishOfferFacts.MinorDeserializer.class) |

#### ai.neargo.shop.food.dto.BusinessShareContracts.PersonTotal

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| personNo | String | 否 |  |
| amountMinor | long | 是 |  |

#### ai.neargo.shop.food.dto.BusinessShareContracts.PlanVO

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| planNo | String | 否 |  |
| checkNo | String | 否 |  |
| version | long | 是 |  |
| status | String | 否 |  |
| storedStatus | String | 否 |  |
| mode | String | 否 |  |
| currency | String | 否 |  |
| originalAmountMinor | long | 是 |  |
| sourceHash | String | 否 |  |
| planHash | String | 否 |  |
| localSourcesCurrent | boolean | 是 |  |
| settlementEligible | boolean | 是 |  |
| financialAuthorityPending | boolean | 是 |  |
| source | SourceSnapshot | 否 |  |
| division | Division | 否 |  |
| definition | CreateReq | 否 |  |
| reason | String | 否 |  |
| createdBy | String | 否 |  |
| confirmedBy | String | 否 |  |
| cancelledBy | String | 否 |  |

#### ai.neargo.shop.food.dto.BusinessShareContracts.ShareCell

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| allocationNo | String | 否 |  |
| foodLineNo | String | 否 |  |
| orderItemId | Long | 否 |  |
| personNo | String | 否 |  |
| amountMinor | long | 是 |  |

#### ai.neargo.shop.food.dto.BusinessShareContracts.SourceSnapshot

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| entityNo | String | 否 |  |
| storeNo | String | 否 |  |
| checkNo | String | 否 |  |
| checkVersion | Long | 否 |  |
| currency | String | 否 |  |
| originalAmountMinor | long | 是 |  |
| batches | List<BatchSource> | 否 |  |
| allocations | List<AllocationSource> | 否 |  |

#### ai.neargo.shop.food.dto.CancelDiningOrderReq

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| version | Long | 是 | @NotNull @JsonDeserialize(using = CatalogRevisionDeserializer.class) |
| reason | String | 是 | @NotBlank @Size(max = 255) @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) |

#### ai.neargo.shop.food.dto.CreateStaffDiningOrderReq

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| buyerNo | String | 是 | @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) |
| cartNo | String | 是 | @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) |
| version | long | 是 | @JsonProperty(required = true) @JsonDeserialize(using = CatalogRevisionDeserializer.class) @Min(0) |
| quoteNo | String | 是 | @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Size(max = 32) |
| confirmationDigest | String | 是 | @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Pattern(regexp = "[0-9a-f]{64}") |
| payMode | String | 是 | @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) @NotBlank @Pattern(regexp = "POSTPAY") |

#### ai.neargo.shop.food.dto.DiningBatchSummaryVO

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| batchNo | String | 否 |  |
| storeNo | String | 否 |  |
| originalCheckNo | String | 否 |  |
| currentCheckNos | List<String> | 否 |  |
| membershipAuthoritative | boolean | 是 |  |
| status | String | 否 |  |
| orderSource | String | 否 |  |
| businessDate | LocalDate | 否 |  |
| fulfillment | String | 否 |  |
| payMode | String | 否 |  |
| quoteNo | String | 否 |  |
| quoteHash | String | 否 |  |
| createdAt | Instant | 否 |  |
| acceptedAt | Instant | 否 |  |
| scheduledAt | Instant | 否 |  |
| version | long | 是 |  |
| originalBindings | List<OriginalBindingVO> | 否 |  |

#### ai.neargo.shop.food.dto.DiningOrderBatchVO

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| batchNo | String | 否 |  |
| storeNo | String | 否 |  |
| checkNo | String | 否 |  |
| cartNo | String | 否 |  |
| cartVersion | long | 是 |  |
| quoteNo | String | 否 |  |
| status | String | 否 |  |
| payMode | String | 否 |  |
| businessDate | LocalDate | 否 |  |
| orderNo | String | 否 |  |
| subOrderNo | String | 否 |  |
| coreStatus | String | 否 |  |
| currency | String | 否 |  |
| amountMinor | Long | 否 |  |
| version | long | 是 |  |

#### ai.neargo.shop.food.dto.KitchenContracts.CoreLineScope

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| allocationNo | String | 否 |  |
| orderItemId | Long | 否 |  |
| sourceComponentRef | String | 否 |  |
| originalQty | BigDecimal | 否 |  |
| unit | String | 否 |  |
| coreSnapshotHash | String | 否 |  |
| version | Long | 否 |  |

#### ai.neargo.shop.food.dto.KitchenContracts.CustodyReceiptVO

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| operationNo | String | 否 |  |
| entityNo | String | 否 |  |
| storeNo | String | 否 |  |
| batchNo | String | 否 |  |
| orderNo | String | 否 |  |
| storeOrderNo | String | 否 |  |
| bindingNo | String | 否 |  |
| bindingVersion | Long | 否 |  |
| batchVersion | Long | 否 |  |
| frozenRequestHash | String | 否 |  |
| action | String | 否 |  |
| kind | String | 否 |  |
| lines | List<HandoverLine> | 否 |  |
| sources | List<CustodySourceLine> | 否 |  |
| recipientNo | String | 否 |  |
| recipientRef | String | 否 |  |
| actorNo | String | 否 |  |
| recordedAt | String | 否 |  |
| complete | boolean | 是 |  |
| coreDeliveryPending | boolean | 是 |  |

#### ai.neargo.shop.food.dto.KitchenContracts.CustodySourceLine

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| foodLineNo | String | 否 |  |
| sourceLineRef | String | 否 |  |
| originalLines | List<CoreLineScope> | 否 |  |
| tasks | List<KitchenTaskScope> | 否 |  |

#### ai.neargo.shop.food.dto.KitchenContracts.HandoverLine

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| foodLineNo | String | 是 | @NotBlank |
| qty | BigDecimal | 是 | @JsonDeserialize(using=SalesRestrictionPolicy.DecimalDeserializer.class) @NotNull |

#### ai.neargo.shop.food.dto.KitchenContracts.HandoverReq

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| kind | String | 是 | @NotBlank |
| lines | List<HandoverLine> | 是 | @NotNull |
| reason | String | 是 | @NotBlank @Size(max=255) |
| recipientNo | String | 否 | @Size(max=64) |
| recipientRef | String | 否 | @Size(max=128) |

#### ai.neargo.shop.food.dto.KitchenContracts.HandoverVO

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| operationNo | String | 否 |  |
| batchNo | String | 否 |  |
| status | String | 否 |  |
| deliveryPending | boolean | 是 |  |
| coreDeliveryRef | String | 否 |  |

#### ai.neargo.shop.food.dto.KitchenContracts.KitchenTaskScope

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| taskNo | String | 否 |  |
| componentNo | String | 否 |  |
| stationNo | String | 否 |  |
| taskRole | String | 否 |  |
| routeVersion | long | 是 |  |
| version | long | 是 |  |

#### ai.neargo.shop.food.dto.KitchenContracts.PackingReq

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| lines | List<HandoverLine> | 是 | @NotNull |
| reason | String | 是 | @NotBlank @Size(max=255) |

#### ai.neargo.shop.food.dto.ListDiningBatchesQuery

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| page | Integer | 否 | @Min(1) @Max(10000) |
| size | Integer | 否 | @Min(1) @Max(100) |
| originalCheckNo | String | 否 | @Size(max=32) |
| checkNo | String | 否 | @Size(max=32) |
| status | String | 否 | @Size(max=24) |
| businessDate | LocalDate | 否 | @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) |
| orderSource | String | 否 | @Size(max=24) |

#### ai.neargo.shop.food.dto.ListReservationsQuery

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| page | Integer | 否 | @Min(1) |
| size | Integer | 否 | @Min(1) @Max(100) |
| from | Instant | 是 | @NotNull |
| to | Instant | 是 | @NotNull |
| status | String | 否 |  |

#### ai.neargo.shop.food.dto.OperationVO

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| operationNo | String | 否 |  |
| status | String | 否 |  |
| version | long | 是 |  |
| resultRef | String | 否 |  |

#### ai.neargo.shop.food.dto.ReservationContracts.ArriveReq

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| version | Long | 是 | @NotNull @Min(0) |
| tables | List< TableVersionReq> | 是 | @NotNull @Size(min=1,max=100) @Valid |

#### ai.neargo.shop.food.dto.ReservationContracts.ChangeReq

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| version | Long | 是 | @NotNull @Min(0) |
| appointment | CreateReq | 是 | @NotNull @Valid |
| reason | String | 是 | @NotBlank @Size(max=255) |

#### ai.neargo.shop.food.dto.ReservationContracts.CreateReq

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| partySize | Integer | 是 | @NotNull @Min(1) |
| startAt | Instant | 是 | @NotNull |
| endAt | Instant | 是 | @NotNull |
| contactRef | String | 是 | @NotBlank @Size(max=64) |
| tables | List< TableVersionReq> | 是 | @NotNull @Size(min=1,max=100) @Valid |

#### ai.neargo.shop.food.dto.ReservationContracts.DetailVO

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| reservation | ReservationVO | 否 |  |
| tableNos | List<String> | 否 |  |

#### ai.neargo.shop.food.dto.ReservationContracts.TransitionReq

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| version | Long | 是 | @NotNull @Min(0) |
| reason | String | 是 | @NotBlank @Size(max=255) |

#### ai.neargo.shop.food.dto.ReservationVO

| 字段 | Java类型 | 必填 | 声明约束 |
|---|---|---|---|
| reservationNo | String | 否 |  |
| partySize | int | 是 |  |
| startAt | Instant | 否 |  |
| endAt | Instant | 否 |  |
| status | String | 否 |  |
| depositRef | String | 否 |  |
| checkNo | String | 否 |  |
| version | long | 是 |  |

### 当前边界

预约为FOOD本地真实桌台时段分配，不冒充外部资源或已收订金；转桌/加桌只变同台账物理占位，不迁移原订单/资金；AA为原金额责任分摊，settlementEligible=false，不替代FOOD-API-140；包装/交接始终保留coreDeliveryPending，实物凭证不替代核心库存出库。营业日仅SYNC异常双人交接，AR/退款/支付未知不自动消除。批次查询不返回推断的当前欠款或资金终态。

未完成：anonymous and device formal order principals；DELIVERY formal ordering and DINE_IN/DELIVERY core fulfillment completion；bundles with exclusive inventory leaf strategy；fractional original order/inventory quantities；actual recipe material posting/cost；funds execution；cross-check order merging and financial AA；complete terminal UI and offline operation；real vendor hardware/network validation；reports and Keeta actual integration。完整任务保持原TDD未完成状态，不用当前源码条目数量推断全部需求已交付。
