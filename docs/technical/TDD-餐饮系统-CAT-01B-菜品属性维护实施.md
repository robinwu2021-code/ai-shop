# CAT-01B 菜品餐饮属性维护实施

> 2026-10-21 · P1首个垂直切片；以实际测试回执为验收依据。
> 上游：[增强执行计划](./TDD-餐饮系统-餐牌选配套餐与耗用增强方案.md) · [统一Java规范](../../.claude/skills/ai-shop-dev-standards/references/java.md) · [API设计](./design/餐饮系统-API设计.md)。

## 1 交付边界

实现 FOOD-API-016，管理基础商品的餐饮属性草稿，不修改商品/SKU、价格、上架、库存或原订单。属性按 `goodsNo` 唯一，是主体内商品级配置；路径中的门店负责权限与商品归属核验，不创建门店专属的第二份菜品。渠道差异沿后续餐牌条目和不可变修订处理。

商品事实经现有 `GoodsCatalogQueryPort` 获取：集成部署使用原商品域实现，独立部署使用 food-channel 类型化客户端。商品未上架、审核未通过时可以整理属性草稿，这不授予可售资格。跨主体、跨门店或错误商品的回执拒绝，权威服务失败不伪造成功。

当前只接受 `COUNT/EA/scale=0`；EA仅是按份展示标记，不作为库存物料单位。称重、自定义单位、非零允差、自定义 `imageRefs` 暂不接受，须等待真实单位/报价/媒资权威合同。空 `imageRefs` 表示沿用商品基础图片，不删除图片。BUNDLE/ADDON只保存菜品类型，不代表套餐或收费配料已可交易。

API112真实分页、API113包含选配的详情、选择组/配料/套餐/配方发布属于后续切片，本回执不将它们标为完成。C端售卖目录、老板端汇总和平台运营查询沿原计划分端实现，不为属性维护添加越权入口。

## 2 API与参数

`PUT /biz/x/food/stores/{storeNo}/dishes/{goodsNo}`，原B端登录、`BizPerms.GOODS`、当前门店及资源归属校验；必填 `Idempotency-Key`，最长128。主体、操作人从平台上下文取得，不接受请求自报。

```json
{
  "dishKind": "SINGLE",
  "prepSeconds": 60,
  "presentation": {
    "displayName": "招牌奶茶",
    "localizedNames": [{"locale": "en", "name": "Milk tea"}],
    "imageRefs": [],
    "tags": ["热饮"],
    "allergenNotes": ["含奶"]
  },
  "measurement": {"unit": "EA", "mode": "COUNT", "scale": 0}
}
```

| 字段 | 校验及语义 |
|---|---|
| storeNo / goodsNo | 路径必填、最长32；主体、当前门店、目录商品归属一致 |
| dishKind | SINGLE / BUNDLE / ADDON |
| prepSeconds | 必填非负整数 |
| presentation | 必填结构；displayName可清空，最长128；语言名最多20条、语言不重复、名称最长128 |
| imageRefs | 当前必须为空；基础图片继续归原商品 |
| tags / allergenNotes | 可省略为空；各最多20条、不重复；分别最长32/255 |
| measurement | 必填；当前只接受COUNT/EA、scale=0，允差省略或0，保存统一为null |
| version | 创建不传；修改必须传当前非负版本；不能把缺version当覆盖更新 |

创建时 `configVersion=1/version=0`；每次成功修改两个版本分别递增。返回原平台信封，data为 `{"dish": DishVO}`；DishVO含goodsNo、dishKind、prepSeconds、presentation、measurement、configVersion、version，不暴露物理id。未知顶层及展示/计量嵌套字段均拒绝。

错误沿原BAD_REQUEST、FORBIDDEN、NOT_FOUND、CONFLICT等；同键同载荷和同一操作人重放原回执，同键异载荷拒绝。内部接口 `/internal/food/dishes/save` 使用原服务令牌，命令显式携带可信主体、门店、商品、操作人、原幂等键和dish；失败保持HTTP 400/403/404/409，远程写不自动重试。

## 3 类、方法与持久化

所有类位于 `backend-plugins/shop-industry-food`，沿已实现零售及餐饮规范；原13模块/109项需求清单不重复创建另一套分层。

| Maven模块 | 完整类名或包 | 方法 / 职责 |
|---|---|---|
| food-web | ai.neargo.shop.food.api.biz.BizDishController | saveDish：原鉴权、可信作用域、校验、调用Service |
| food-api | ai.neargo.shop.food.service.DishService | saveDish(entityNo,storeNo,goodsNo,actorNo,req,key) |
| food-core | ai.neargo.shop.food.service.impl.DishServiceImpl | 权威事实检查；规范化；本地主库事务、创建/CAS、审计和Outbox |
| food-core | ai.neargo.shop.food.entity.FnbDish | 对齐原V1 fnb_dish，BaseEntity审计/版本/逻辑删除 |
| food-core | ai.neargo.shop.food.mapper.FoodMappers.DishMapper | 原BaseMapper读写，不添加Repository转发层 |
| food-api | ai.neargo.shop.food.dto | SaveDishReq、DishPresentation/LocalizedName、MeasurementPolicy、DishVO、DishResultVO |
| food-api | ai.neargo.shop.food.spi.DishInternalApi | saveDish(SaveCommand)：类型化内部协议 |
| food-web | ai.neargo.shop.food.foodclient.RemoteDishService | 同一Service合同，经原InternalHttp/ServiceCalls访问独立food-server |
| food-server | ai.neargo.shop.food.server.api.InternalDishController | saveDish：内部校验及HTTP错误映射，不承载三端登录 |
| food-api | ai.neargo.shop.spi.food.DishChanged | 已保存配置事实，包含操作号、版本及前后快照；不表示发布/消费库存 |

FoodAutoConfiguration、FoodWebAutoConfiguration、FoodClientConfiguration显式注册；缺商品权威时调用失败，不用空事实回退。商品事实查询在本地写事务前；已有外部事务时拒绝混入。事务内使用原IdempotencyService、ActionLogMapper及foodOutboxPublisher；源数据、审计、事件及幂等回执一起提交或回滚。按entityNo/goodsNo加行锁，数据库全局goodsNo唯一键兜住并发创建，BaseEntity版本完成CAS。清空别名通过结构化JSON更新，避免updateById跳过null字段。

复用已冻结V1的fnb_dish，不增加迁移，不更改V1/V2。P0新增9表仍PLANNED；不把82张目标表当数据库执行回执。正式环境需由既有Outbox所有者配置事件处理/归档策略，本切片不旁建事件系统。

## 4 验证入口

- DishServiceTest：真实Mapper/事务/幂等，创建、修改、重放、CAS、跨主体、权威不可用、未支持参数及Outbox失败回滚。
- DishApiTest：原B端登录/商品权限/信封，当前门店、可信操作人及未知嵌套字段拒绝。
- RemoteDishServiceTest：原类型化HTTP及令牌、409不重试、空/错误资源回执不报成功。
- FoodDishPrimaryDatabaseTest：一次性MySQL真实V1/V2、JSON列、独立FoodApplication、两段类型化HTTP、令牌、修改及回执；不修改既有商品/订单哨兵表。
- 完整餐饮reactor、迁移打包检查、零售/embedded/standalone三种宿主装配及设计守卫同时执行。实际数量和最终结论见增强方案执行回执。

后续进展：API112/113已由[CAT-01C](./TDD-餐饮系统-CAT-01C-菜品查询实施.md)交付管理查询；本文记录的首批范围及未开放交易边界不变。
