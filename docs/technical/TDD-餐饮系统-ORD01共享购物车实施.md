# ORD-01 多端共享购物车

> 状态：后端草稿能力已实现并验收 · 2026-10-22。沿既有餐饮PRD/ADR040及后端计划§2.6，用户“继续”授权推进ORD-01。档位2，复用已确认总体方案，不另起商品/订单架构。当前分支codex/food-next01，基线94ea8bfda；已有其他会话未提交设计/UI改动，保留。

## §0 需求与验收

Spec依据：完整需求D01～D04及后端业务功能清单MOD-03、后端开发计划ORD-01。目标是同一获准台账/会话的真实多端草稿，非正式订单或报价。

| AC | 规则与实现落点 | 验证 |
|---|---|---|
| AC1 | B端037～040/126；真实check/session归属，check至多一个OPEN共享车，锁已有owner行防双建；同车来源/履约不偷偷改 | CartServiceTest：重放、并发创建、外店拒绝 |
| AC2 | 行稳定sourceLineRef，保存menu/menuItem/revision、SKU、数量、选配及套餐选择；不同做法独立源行；原selection JSON保存schema1行包，不增表/改冻结迁移 | CartServiceTest/CartLinePolicyTest：版本、选配边界、同SKU两行 |
| AC3 | 写入购物车整体CAS，actor绑定幂等，审计Outbox同事务；归档只改草稿deleted，不写批次；SUBMITTING/CLOSED拒绝修改 | CartServiceTest：竞争、故障回滚、提交冻结 |
| AC4 | C端127～130：QR凭原哈希凭证；同check共享须staff明确授权session.checkNo及CHECK_CART；不能仅靠TABLE_MENU | CartServiceTest/CartApiTest：过期、撤销、跨账、匿名/登录 |
| AC5 | 在线H5消费者可创建无桌车，买家来自认证，服务端生成CART_H5会话；匿名QR保留原扫码会话。无真实设备凭据的KIOSK/APP不假冒支持 | CartServiceTest/CartApiTest：H5所有者、来源伪造 |
| AC6 | 保存前重验当前有效菜单/菜项/修订及冻结选配；草稿允许需要实际报价的套餐/配方，不能把草稿视为可成交；数量整数COUNT，称重/预约/道次执行另阶段 | CartLinePolicyTest：过期餐牌、必选互斥、套餐选择 |

## §1 架构与接口

沿food-api.dto/CheckCartService/typed CartInternalApi；food-core.entity/FoodMappers嵌套Mapper/CheckCartServiceImpl/policy.CartLinePolicy；food-web内BizCartController/MpCartController及RemoteCheckCartService；food-server内部token入口。账号/主体/门店/权限复用，B读ORDER_VIEW、写VERIFY，C来源与买家来自服务端认证。保持嵌入/独立部署同合同。

复用fnb_check_cart、fnb_check_cart_item及BaseEntity，无新迁移。selection JSON为{schema:1,line:OrderLineInput}，保留当前草稿菜单/组选配身份；已提交快照属于ORD-03，不由本服务改写。归档保留源行墓碑，重加同sourceLineRef不能换goods/SKU；同一车最多300活动行。读取不写数据。审计不记录凭证，事件仅引用车/行/version，不携带私密完整配置。

OrderLineInput={sourceLineRef,menuNo,menuItemNo,menuRevisionNo,goodsNo,skuNo,qty,seatNo?,courseNo?,selections:[{groupNo,sourceKind,sourceNo,qty,configVersion}],bundleChoices:[{choiceNo,qty}]}。数组必需；源行最长64，其他业务键沿32，SPEC_VALUE可64；COUNT qty正整数≤999999；选配按每份菜计量，组/项上下限、互斥与依赖基于冻结组选项。菜单修订必须仍是目录所选修订，变更后请求冲突，不能静默迁移。草稿不计算金额、不预占库存额度，正式提交需ORD-02/03重验。

CreateCartReq={checkNo?,sessionNo?,buyerNo?,orderSource,fulfillment,scheduledAt?}：B当前POS/WAITER，需check或session；buyer不可任意代客；预约待真实合同默认拒绝。CreateCustomerCartReq={checkNo?,sessionNo?,fulfillment,scheduledAt?}：来源QR或H5服务端判定，H5无session时必须已认证CONSUMER；匿名只能使用原扫码session。CartVO包含cartNo/storeNo/checkNo/sessionNo/status/buyerNo?/source/fulfillment/version/lines，顾客输出不泄露同账其他人的身份字段；版本及响应包装沿原API。

同check返回其唯一OPEN共享车（履约不符拒绝），先创建者确定业务来源；后续操作不改来源。顾客保存行同时校验QR目录与车业务来源目录的交集，因此不能通过店员创建的POS车选取顾客不可见餐牌。员工可操作已有QR车。无台购物车按其session隔离。

新增辅助API156：POST /biz/x/food/checks/{checkNo}/cart-sessions，{sessionNo,version}，输出{sessionNo,checkNo,version}。VERIFY店员只可将有效扫码session授权到其桌台当前ACTIVE占用对应的OPEN check；已有其他check绑定拒绝；撤销沿原session结束逻辑；顾客每次创建、读取、编辑及幂等重放前，重验同check/table当前占用及OPEN check，转台或局部清台离开原桌立即失去共享车访问权，写内再验。它不赋予付款/退款或访问其他账单权利。无台H5session由C创建车在事务中签发，只能经真实登录买家再次访问，不发行可伪造的客户端buyerNo。

锁序：既有桌资源（仅入账授权）→check→guest session→cart→items；无check则guest session→cart。所有读取均显式entity/store，不依赖绕域后的默认SQL过滤。菜单与原权威读取在本地写事务外；写内复核owner/权限/版本及本地状态，不能声称跨服务原子快照。

## §2 实施计划

使用executing-plans本人推进，唯一最终独立审查，Important一轮TDD修复，Minor登记；保留用户指定餐饮分支，不另建工作树、不动其他暂存。

- [x] ORD01-1：冻结DTO/Service/内部合同及纯行校验；先测试选配、套餐、菜单版本、严格JSON与数量。
- [x] ORD01-2：实体/Mapper/事务服务与显式会话授权，先真实仓储测试创建共享、权限、CAS、幂等、归档和回滚。
- [x] ORD01-3：B/C Controller、内部端点及Remote；三种宿主装配，先鉴权/类型化回执与原号恢复测试。
- [x] ORD01-4：更新canonical API/DTO及模块方法真源，再生成设计；不覆盖其他会话已有修改。全量food、隔离MySQL/MariaDB、相关桌台及三宿主回归，做关键消融，最终审查/精确提交。

精确新类：dto.CreateCartReq/CreateCustomerCartReq/OrderLineInput/CartSelectionInput/CartBundleChoiceInput/ReplaceCartLineReq/CartVersionReq/CartVO/CartResultVO/CartVersionVO/AuthorizeCartSessionReq/CartSessionGrantVO/CartAccess；service.CheckCartService；spi.CartInternalApi；policy.CartInputPolicy/CartLinePolicy；entity.FnbCheckCart/FnbCheckCartItem；FoodMappers.CheckCartMapper/CheckCartItemMapper；service.impl.CheckCartServiceImpl；api.biz.BizCartController；api.mp.MpCartController；foodclient.RemoteCheckCartService；server.api.InternalCartController。公共装配仅补既有配置，不复制事件/幂等设施。

Review Focus：同check私有顾客范围与凭证失效竞争；关闭/提交/归档撞并发编辑；唯一owner仲裁防空结果双建；旧餐牌与动态菜价不变成伪成交；幂等重放在上游不可用时不再次写入；不允许顾客通过POS草稿绕过QR餐牌。

## §5 验收记录

| 验证 | 真实结果 |
|---|---|
| TDD RED→GREEN | DTO缺失、核心实现缺失、首客失效隔离/未授权绑定、转台后旧授权、生成器合同偏差均观察失败后修复 |
| 购物车核心 | 10仓储 +11冻结目录规则，含双建/同版本编辑竞争、幂等与Outbox回滚 |
| 接口传输 | Web7 +独立内部入口1，全部执行无跳过 |
| 餐饮全量 | 531项，485实际执行通过，46项需外部环境而跳过；0失败/错误 |
| 文档真源 | Node57项全部通过，实际CartAccess、标量会话Query及墓碑Mapper合同对齐 |
| 原生数据库 | 最终MySQL304 +原规格4、Maria78实际通过，均无跳过 |
| 三宿主 | retail/embedded/standalone分别5/5/6项实际通过，前两者各1个不适用条件跳过；新CheckCart类路径与远端/本地实现边界通过 |
| 关键守卫消融 | 临时移除当前check/table成员守卫，转台回归用例按预期失败；源码按原字节恢复后21项核心测试再次通过 |
| 冻结迁移 | V1–V7不变，无新迁移、未操作经营库 |

唯一独立审查发现旧扫码授权可跨转台存续，新增用例先RED，再补当前占用守卫并全量GREEN。两个清单问题因用户明确要求与真实代码对齐，作为交付问题一并修复：不再生成undefined注解/重复customer前缀或不存在的购物车Mapper方法。不留未处理审查项。

## §6 对账与偏差

订单CORE-01/报价、正式提交、额度预占、厨房支付不在此切片；新增156是现有TABLE_MENU不足以授权共享台账的必要合同补充。H5沿真实登录用户身份，不新增匿名线上身份体系。已有155接口规划并非全部已实现。其余文件以最终精确清单/提交对账。


## §7 下一阶段

ORD-02：在草稿基础上接入原商品报价/订单试算合同，返回菜品、规格、小料、配料组合及套餐的分项金额、币种、可售/库存和额度校验结果；保留菜单与配置版本，明确不确定结果，不在购物车写金额真源。随后ORD-03正式提交及加菜批次，复用原订单意图、独立支付及进销存，支持先付后吃/先吃后付，再推进厨房与打印。

实施取舍：沿已确认总方案细化并直接执行；保留用户指定餐饮分支与其他会话改动；新增156而不把TABLE_MENU当作共享账权限；H5只用真实买家，匿名线上/设备凭据另阶段；独立纯规则和传输分工但构建串行；清单瑕疵按交付对齐问题修复。代价分别是后续若总体方案变化需调整、无独立工作树隔离、员工多一步授权、匿名/设备暂不可用、集成由主任务协调及生成器少量扩展。保留当前分支，不推送或合并。
