# 餐饮三端API与独立支付、进销存接入对齐

> 状态：业务API设计待实施；NEXT-01～06基础已落地，NEXT-07源码核验完成，独立交易未验收 · 2026-10-20。本次按用户要求，严格沿现有平台架构、三端接入及代码规范修订M0。所述“云顶端”暂按平台运营端（ops）理解。
> [M0详细方案](./TDD-餐饮系统-M0基础工程与接入方案.md) · [总计划](./TDD-餐饮系统-后端开发计划.md) · [API完整入出参](./design/餐饮系统-API设计.md) · [类方法](./design/餐饮系统-Controller-Service-Mapper方法清单.md)。

## §0 约束与本次修正

1. C/B/平台运营三端API分别沿`/mp/x/food/**`、`/biz/x/food/**`、`/ops/x/food/**`及原realm、profile、权限、数据域、ApiResult/PageData。
2. 餐饮三端Controller源码归餐饮项目food-web，由shop-app引入并统一接入；餐饮独立服务只开放受控内部接口，不另建三端认证链、登录或用户权限解析。
3. food-api承载原包名的Service接口/DTO及餐饮专有内部契约；food-core承载ServiceImpl/Entity/Mapper/业务规则。在原四模块上增加food-web接入jar，共五模块；它属于餐饮整体，不新增进程或数据库，类名包名保持。
4. 复用核心商品、订单、支付和库存边界；餐饮不复制商品订单、支付账或进销存账，也不跨库直写。
5. **目标必须联通独立PAY与独立进销存。** 不是选做项，不能只验证内嵌后宣称完成；缺远程合同即保留阻塞。
6. 餐饮订单、商品和fnb仍共主库；进销存沿其独立库及事务，支付沿支付域自己的资源边界。独立服务不等于所有域共一事务。

### 0.1 验收口径

| AC | 需要证明的结果 |
|---|---|
| ALIGN-01 | 两种餐饮部署中，三端路由只在平台出现，food-server无mp/biz/ops用户入口 |
| ALIGN-02 | 137个餐饮三端API映射到餐饮food-web；3个共享打印API保持原归属；其余1个同步内部API留受控内部侧，公共类/方法/协议不漂移 |
| ALIGN-03 | 食品Service接口与DTO在food-api，Impl在food-core；平台远程形态不依赖food-core实现jar |
| ALIGN-04 | 核心支付链实际进入独立PAY，支付/退款UNKNOWN按原键恢复；不能只用一个已有远程配置作为证据 |
| ALIGN-05 | 库存锁定、确认/分配、出库、取消释放、原路恢复/退货收货进入独立进销存；没有重复扣减 |
| ALIGN-06 | PLATFORM/FOOD/PAY/INVENTORY采用现有ServiceLocator、InternalHttp、svc-client规则；FOOD/INVENTORY常量及相应远程合同尚需补齐 |

## §1 统一架构与当前事实

### 1.1 源码归餐饮，三端运行入口统一挂载

```text
C端（扫码/H5/点餐机顾客模式） → 平台 /mp/x/food
B端（收银平板/服务员/厨房/商家运营/老板App） → 平台 /biz/x/food
平台运营端 → 平台 /ops/x/food
  → 挂载餐饮food-web中的Controller
  → 原认证/判权/门店与对象校验
  → 同一个餐饮Service接口（food-api）
     ├─ embedded：food-core本地实现
     └─ standalone：food-web内foodclient远程实现 → /internal/food/** → food-core
  → 核心商品/订单SPI（本地桥接或内部适配）
  → 订单权威编排
     ├─ SettlePort/现有支付桥接 → 独立PAY
     └─ StockPort/进销存桥接 → 独立进销存
```

餐饮也可以经既有只读Port读取支付证据或库存可售量；**正式订单的支付、库存写入只能由已指定权威编排触发一次**。不能在餐饮下单先锁一遍、核心建单再锁一遍。将来若确定某个餐饮专项操作由餐饮直接发起，须独立指定操作号、幂等和对应权威合同，不以“直连更快”绕过核心订单一致性。

### 1.2 按源码核验，不能把历史草稿当现状

| 入口 | 当前事实 | 餐饮接入要求 |
|---|---|---|
| [ServiceName](../../backend/shop-base/src/main/java/ai/neargo/shop/svc/ServiceName.java)、[ConfigServiceLocator](../../backend/shop-base/src/main/java/ai/neargo/shop/svc/ConfigServiceLocator.java) | 当前常量有PLATFORM/PAY/JOB；targets大小写兼容 | 复用现有寻址；FOOD/INVENTORY是需追加常量，不新增自定义core地址键 |
| [PayClientConfig](../../backend/shop-app/src/main/java/ai/neargo/shop/payclient/PayClientConfig.java) | `shop.pay.deployment=standalone`创建PayInternalApi客户端 | 保留实际键名和值；历史支付文档的remote值不作为当前运行配置 |
| [SettlePortImpl](../../backend/shop-app/src/main/java/ai/neargo/shop/paybridge/port/SettlePortImpl.java) | 当前仍直接依赖PaymentLedgerService、RefundExecutionService、网关等本地对象 | 独立支付已存在部分远程接口，不代表订单收款/退款全链路已远程化；逐方法补齐路由与合同 |
| [OrderServiceImpl](../../backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/OrderServiceImpl.java) | 下单/支付依赖StockPort/SettlePort等权威边界 | 餐饮调用核心订单契约，由此统一驱动资金和库存 |
| [StockPort](../../backend/shop-base/src/main/java/ai/neargo/shop/spi/product/StockPort.java) | 已有锁、释放、确认、分配、出库、原路恢复、退货收货和状态等合同，部分默认方法可能空实现 | 对实际使用方法逐项验收；不能让远程实现继承no-op默认后算已支持 |
| [InventoryStockPort](../../backend/shop-app/src/main/java/ai/neargo/shop/invbridge/port/InventoryStockPort.java) | stock-authority=INVENTORY时启用；当前直接调用进销存Service，独立inv事务 | 独立库已实现；本轮未找到完整远程StockPort/独立库存服务HTTP装配，需明确补齐 |
| [支付双形态方案](./design/TDD-支付域-双形态部署与装配.md) | 明确三端Controller留主应用，支付服务只内部接口 | 餐饮采用相同接入原则；不照搬该旧文档里未落地的模块目录/持久层选型 |
| [旧进销存模块化方案](./design/TDD-进销存模块化与独立部署.md) | 文档含“不拆库/热路径不跨进程”等历史论述，顶部已注明部分被替代 | 对独立进销存的新要求以当前Port、独立库和远程补偿合同验证，不据旧论述否定用户目标 |

本轮仅核对当前仓库，不代表已核验外部环境正在运行的支付/进销存版本；未找到远程实现不等于外部部署一定没有，实施时先按真实服务合同确认再补缺失部分。

## §2 三端API、工程与依赖设计

### 2.1 C、B、平台API分工

| 端 | 当前设计数量 | 责任与权限 |
|---|---:|---|
| C端mp | 16 | 目录、扫码会话、台账、购物车、报价下单、结账付款、退餐申请及查询；只操作本人获准会话，不执行商家退款审批 |
| B端biz | 121 | 桌区桌台、预约转并拼拆、菜品餐牌配料套餐、订单、厨房、收银退餐、打印、现金班次、日结报表、专项及第三方配置；按门店/角色/工位权限 |
| 平台ops | 3 | 报表重建、门店餐饮能力配置、集成异常查询；OPERATOR权限及数据域，不能借平台身份代商家收退款 |
| 内部internal | 1 | 原API084离线同步批次；必须受控设备/服务范围及租约校验，不当公开顾客端点 |

140个三端接口包含3个共享打印biz API；共享打印的Service/DTO/持久化仍归原能力。API路径和完整输入输出保持原设计，按端明细见§3。老板端属于B端，不为老板另造第四套身份。平台只有3个已定义接口，不能将B端整套运营/报表接口放开给ops；需求矩阵中其余平台治理需逐项补合同并登记。

### 2.2 文件与装配

| 文件归属 | 包/职责 |
|---|---|
| food-web | `ai.neargo.shop.food.api.mp/biz/ops`；Controller源码、接入校验和装配均属于餐饮工程，宿主不复制 |
| shop-app | 引入food-web并挂载，提供既有认证/权限基础设施；不放餐饮Controller源码；共享打印保持原归属 |
| food-api | `ai.neargo.shop.food.service`接口与`ai.neargo.shop.food.dto`；`ai.neargo.shop.spi.food`餐饮专有服务间合同。不同jar承载原包名，不新加Repository/AppService转发层 |
| food-core | `ai.neargo.shop.food.service.impl/entity/mapper/policy/statemachine/event/job/config`，以及受控内部业务入口；不装三端用户过滤器 |
| food-channel | 餐饮调用核心事实/订单所需HTTP协议适配，复用svc-client；不拥有支付/库存账 |
| food-server | 独立启动、food-core装配、内部密钥/受控设备入口、餐饮迁移、readiness，无用户token登录或三端API |
| food-web中的foodclient | FoodClientConfig和RemoteXxxService，仍是餐饮工程的一部分；复用平台调用设施，实现同一个food-api Service接口，不复制业务规则 |
| shop-app/paybridge、payclient、invbridge | 沿权威域补齐独立支付/库存路由，供零售及餐饮共同使用；不在food复制第二套桥接 |

宿主通过可选依赖引入food-web，不拥有餐饮Controller源码。food-web依赖food-api及共享认证/调用契约，不依赖shop-app；embedded再装配food-core，standalone装配food-web内的远程Service代理。两个形态均使用同一份餐饮Controller源码。enabled=false关闭餐饮路由与业务装配，默认零售可不引餐饮实现；具体构件依赖由宿主发布形态声明。

完整餐饮工程包含food-api、food-core、food-web、food-channel、food-server。增加food-web是可复用HTTP接入的部署边界，不按13个业务模块或Controller/Service/Mapper继续拆Maven工程。独立业务进程仍沿本方案只开放内部合同；Controller归属并不要求另建用户账号体系或复制支付/库存账。

独立food-server只服务内部调用；对外路由清单检验是**不存在**mp/biz/ops映射，不能仅验证它们返回401。

### 2.3 配置与服务寻址

| 项 | 规则 |
|---|---|
| `shop.food.enabled` | 默认false，平台餐饮入口和相关装配受控 |
| `shop.food.deployment` | embedded/standalone，沿支付命名；替代上一版提议shop.food.mode（尚无实现，无需保留兼容别名） |
| `shop.pay.deployment` | 使用现有embedded/standalone；目标验收独立PAY，不因餐饮embedded就改为本地支付 |
| `shop.inventory.stock-authority` | 沿现有PLATFORM/DUAL/INVENTORY语义；目标正式库存权威为INVENTORY，DUAL不是独立服务上线完成态 |
| 进销存进程路由 | 当前未核验到统一既有开关；先确认真实服务合同，再在权威装配处补路由，不杜撰一个现成inventory.deployment属性 |
| 服务地址 | `shop.services.targets.PLATFORM/PAY`为已有；FOOD/INVENTORY须补服务名常量及对应地址；不使用shop.services.targets.core |
| 内部协议 | 原InternalHttp、ServiceLocator、svc-client、X-Internal-Token及错误分类；写操作无幂等查回合同前不自动重试 |

### 2.4 独立支付和进销存的真实接入清单

| 能力 | 消费方/唯一触发者 | 必须有的请求/结果语义 | 当前判定 |
|---|---|---|---|
| 正式建单/库存预留 | 核心订单→StockPort | lockNo、SkuQty（含storeNo/fulfillmentRef/lifecycleVersion）；全成或全失败，UNKNOWN可按原引用查询 | 已有SPI；完整远程路径待核验/补齐 |
| 支付发起/查询/关闭 | 核心支付编排→SettlePort对应权威支付合同 | 原订单支付引用、支付域商户单号、金额币种/收款归属；通道参数与资金证据由PAY返回 | 部分远程设施已存在，SettlePort完整收退款路由尚需闭环 |
| 支付成功→库存分配 | 核心受理/履约编排→StockPort | 使用订单冻结lifecycleVersion选择confirm/allocate；一次性幂等 | 必须验V1/V2实际方法，不能假设confirm总是实物出库 |
| 制作/交接→出库 | 核心合法履约→StockPort.issue | 原fulfillmentRef、订单及幂等事实；与已收款/后付受理条件一致 | 后付/制作扣料与成品出库时点仍需对应餐饮合同 |
| 取消/退款前未出库释放 | 核心取消/售后→StockPort | 原锁定/分配引用；冻结取消事实防迟到reserve重新占用 | 真实远程恢复合同必测 |
| 已付退餐/退款 | 核心售后→独立PAY | 原支付/售后号、金额上限和分摊；UNKNOWN查原执行，退款成功证据与厨房状态分轨 | 全额/部分能力按核心真实合同，不根据本地return状态认定到账 |
| 退货/原路恢复 | 核心售后→StockPort.restoreOriginal/receiveReturn | purposeNo、原lockNo/fulfillmentRef、质量SALEABLE/UNSALEABLE、收货回执 | 仅退款不自动回补；报损/已制作餐食不变回可售成品 |
| 可售查询/盘点 | 已授权商品或进销存应用服务 | SKU和门店→库存项/库位映射；盘点沿进销存单据 | 餐饮停售限量独立于实存，不反向改余额 |

请求里的主体、门店、订单、SKU与收款/库存归属需在权威侧交叉校验，内部密钥不是任意跨店扣款扣货许可。所有网络调用在本地长事务之外；短事务意图、权威幂等、查询恢复和对账组成一致性，不引分布式事务。

### 2.4.1 NEXT-07 实际核验结果 · 2026-10-20

逐方法输入输出、幂等/查询恢复、环境证据与CORE/PAY/RET子任务见[独立支付库存合同核验](./TDD-餐饮系统-独立支付库存合同核验.md)。该册是本节“待核验”事项的实际源码回填，不能与正式营业验收混淆。

| 项目 | 实际结论 |
|---|---|
| 普通收款/查询/关闭 | SettlePortImpl仍直接调用PaymentLedgerService/网关等本地服务；SettlePort无close合同，账本close不代表通道关单；普通链缺typed远程合同 |
| PAY已存在的远程能力 | PayInternalApi已含费率/发票、权益/储值/历史券等；不是“只有费率”，也不能拿这些代替普通订单收退款 |
| 退款/原款/证据 | 本地已有原execution/原支付证据、UNKNOWN与prepared流程；普通退款的同义远程请求与原键查回未在当前PayInternalApi落地 |
| 库存远程路径 | InventoryStockPort仍直接调Service；独立inv库/事务不等于独立进程；open/v1的items/ledger/sync不是订单预留RPC |
| 预留恢复风险 | StockPort存在默认no-op/未跟踪/MAX_VALUE；本地同key预留未比较全部行/数量/生命周期，远程必须补幂等内容核对与结果查询 |
| 后付/称重 | lifecycleVersion=2不等于未付餐饮受理；StockPort.qty为int，BOM/称重单位精度、制作与交接时点需CORE-02A合同 |
| 部署事实 | 有PAY启动/systemd模板，未核验运行实例/版本/普通交易回路；仓库未找到完整独立库存合同，不推断外部环境不存在 |
| 两项独立验收 | recoverUnknownPaymentByOriginalKey、reserveOnceAndReleaseAfterCancellation均保留BLOCKED_CONTRACT_AND_ENVIRONMENT；不生成假测试关闭 |

已有PayInternalApiRoundTripTest在本轮5项通过，零失败/错误/跳过；它是费率/发票真HTTP与鉴权测试、领域服务为替身，只作为传输基线。本轮没有支付/库存实际写入或共享库迁移；NEXT-07核验完成不代表ALIGN-04/05通过。

### 2.5 M0及后续任务调整

- FND-01先建food-api共享接口/DTO、food-core业务骨架、food-web三端Controller与可复用装配、仅内部food-server；不创建RemoteTokenStore或餐饮三端SecurityConfig。
- FND-02接主库/独立fnb迁移及平台原鉴权，登记FOOD服务合同与内部可信范围；撤回上一版新增FOOD-INFRA-001用户session解析接口。门店目录远程事实仍可在既有StoreDirectoryPort缺少传输时补最小适配，不能转成第二套用户鉴权。
- CORE-01建单合同同步验独立库存reserve与失败查回；PAY-01/RET-01在真实独立PAY上验收；CORE-02后付库存承诺需独立进销存实际支持。
- M0允许先验工程，但正式营业门槛必须“餐饮本地＋支付独立＋库存独立”和“餐饮独立＋支付独立＋库存独立”两种组合均完成目标范围合同。
- 不把补齐支付/库存远程链路冒充本轮已编码。每个缺口先登记权威域签名/文件/读写/恢复测试，再实施，保留零售回归。

## §3 当前API按端清单

以下仅按端整理既有141项，未新增/删除/更名业务端点。完整参数类型、必填性、响应字段见既有API册；该册与机器清单仍为输入输出权威。内部餐饮RPC按Service用例另立typed合同，不能直接把未鉴权的同名前端路径挂到food-server。

### C端（16项）

| 编号 | 功能 | 方法与路径 |
|---|---|---|
| FOOD-API-023 | 顾客菜单视图 | `GET /mp/x/food/catalog` |
| FOOD-API-026 | 桌码换短会话 | `POST /mp/x/food/sessions` |
| FOOD-API-127 | 顾客购物车读取 | `GET /mp/x/food/carts/{cartNo}` |
| FOOD-API-128 | 顾客创建购物车 | `POST /mp/x/food/stores/{storeNo}/carts` |
| FOOD-API-129 | 顾客替换草稿行 | `PUT /mp/x/food/carts/{cartNo}/lines/{sourceLineRef}` |
| FOOD-API-130 | 顾客归档购物车草稿行 | `POST /mp/x/food/carts/{cartNo}/lines/{sourceLineRef}:archive` |
| FOOD-API-131 | 顾客点单报价 | `POST /mp/x/food/stores/{storeNo}/quotes` |
| FOOD-API-132 | 顾客提交点餐批次 | `POST /mp/x/food/stores/{storeNo}/batches` |
| FOOD-API-133 | 顾客批次结果查询 | `GET /mp/x/food/batches/{batchNo}` |
| FOOD-API-134 | 顾客读取预结账 | `GET /mp/x/food/settlements/{settlementNo}` |
| FOOD-API-135 | 顾客结账pay | `POST /mp/x/food/settlements/{settlementNo}:pay` |
| FOOD-API-136 | 顾客退餐申请 | `POST /mp/x/food/stores/{storeNo}/returns` |
| FOOD-API-137 | 顾客退餐查询 | `GET /mp/x/food/returns/{returnNo}` |
| FOOD-API-138 | 顾客就餐进度 | `GET /mp/x/food/checks/{checkNo}` |
| FOOD-API-139 | 主动结束顾客会话 | `POST /mp/x/food/sessions/{sessionNo}:end` |
| FOOD-API-140 | 顾客申请获准范围结账 | `POST /mp/x/food/checks/{checkNo}/settlements` |

### B端（121项）

| 编号 | 功能 | 方法与路径 |
|---|---|---|
| FOOD-API-001 | 查询营业策略 | `GET /biz/x/food/stores/{storeNo}/policy` |
| FOOD-API-002 | 发布营业策略 | `POST /biz/x/food/stores/{storeNo}/policies` |
| FOOD-API-003 | 餐饮接单日历例外 | `PUT /biz/x/food/stores/{storeNo}/calendar/{localDate}` |
| FOOD-API-004 | 创建menus | `POST /biz/x/food/stores/{storeNo}/menus` |
| FOOD-API-005 | 读取menus | `GET /biz/x/food/stores/{storeNo}/menus/{configNo}` |
| FOOD-API-006 | 发布menus | `POST /biz/x/food/stores/{storeNo}/menus/{configNo}:publish` |
| FOOD-API-007 | 创建option-groups | `POST /biz/x/food/stores/{storeNo}/option-groups` |
| FOOD-API-008 | 读取option-groups | `GET /biz/x/food/stores/{storeNo}/option-groups/{configNo}` |
| FOOD-API-009 | 发布option-groups | `POST /biz/x/food/stores/{storeNo}/option-groups/{configNo}:publish` |
| FOOD-API-010 | 创建bundles | `POST /biz/x/food/stores/{storeNo}/bundles` |
| FOOD-API-011 | 读取bundles | `GET /biz/x/food/stores/{storeNo}/bundles/{configNo}` |
| FOOD-API-012 | 发布bundles | `POST /biz/x/food/stores/{storeNo}/bundles/{configNo}:publish` |
| FOOD-API-013 | 创建stations | `POST /biz/x/food/stores/{storeNo}/stations` |
| FOOD-API-014 | 读取stations | `GET /biz/x/food/stores/{storeNo}/stations/{configNo}` |
| FOOD-API-015 | 发布stations | `POST /biz/x/food/stores/{storeNo}/stations/{configNo}:publish` |
| FOOD-API-016 | 菜品餐饮属性 | `PUT /biz/x/food/stores/{storeNo}/dishes/{goodsNo}` |
| FOOD-API-017 | 菜单组成与时段配置 | `PUT /biz/x/food/menus/{menuNo}/definition` |
| FOOD-API-018 | 套餐选择组配置 | `PUT /biz/x/food/bundles/{skuNo}/definition` |
| FOOD-API-019 | 配置选择项 | `PUT /biz/x/food/option-groups/{groupNo}/items` |
| FOOD-API-020 | 配料组合配置 | `PUT /biz/x/food/modifiers/{modifierNo}` |
| FOOD-API-021 | 工位路由配置 | `PUT /biz/x/food/stores/{storeNo}/sku-routes/{skuNo}` |
| FOOD-API-022 | 沽清与恢复 | `POST /biz/x/food/stores/{storeNo}/sales-restrictions` |
| FOOD-API-024 | 建桌 | `POST /biz/x/food/stores/{storeNo}/tables` |
| FOOD-API-025 | 桌码轮换 | `POST /biz/x/food/tables/{tableNo}:rotate-token` |
| FOOD-API-027 | 创建预约 | `POST /biz/x/food/stores/{storeNo}/reservations` |
| FOOD-API-028 | 预约cancel | `POST /biz/x/food/reservations/{reservationNo}:cancel` |
| FOOD-API-029 | 预约no-show | `POST /biz/x/food/reservations/{reservationNo}:no-show` |
| FOOD-API-030 | 预约arrive | `POST /biz/x/food/reservations/{reservationNo}:arrive` |
| FOOD-API-031 | 开台 | `POST /biz/x/food/stores/{storeNo}/checks` |
| FOOD-API-032 | 台账桌台查询 | `GET /biz/x/food/stores/{storeNo}/checks` |
| FOOD-API-033 | 台账transfer | `POST /biz/x/food/checks/{checkNo}:transfer` |
| FOOD-API-034 | 合并就餐服务台账 | `POST /biz/x/food/checks/{checkNo}:merge` |
| FOOD-API-035 | 拆分就餐服务台账 | `POST /biz/x/food/checks/{checkNo}:split` |
| FOOD-API-036 | 台账clear | `POST /biz/x/food/checks/{checkNo}:clear` |
| FOOD-API-037 | 购物车读取 | `GET /biz/x/food/carts/{cartNo}` |
| FOOD-API-038 | 创建购物车 | `POST /biz/x/food/stores/{storeNo}/carts` |
| FOOD-API-039 | 替换草稿行 | `PUT /biz/x/food/carts/{cartNo}/lines/{sourceLineRef}` |
| FOOD-API-040 | 归档购物车草稿行 | `POST /biz/x/food/carts/{cartNo}/lines/{sourceLineRef}:archive` |
| FOOD-API-041 | 点单报价 | `POST /biz/x/food/stores/{storeNo}/quotes` |
| FOOD-API-042 | 提交点餐批次 | `POST /biz/x/food/stores/{storeNo}/batches` |
| FOOD-API-043 | 批次结果查询 | `GET /biz/x/food/batches/{batchNo}` |
| FOOD-API-044 | 工位队列 | `GET /biz/x/food/stores/{storeNo}/kitchen/tasks` |
| FOOD-API-045 | 制作任务start | `POST /biz/x/food/tasks/{taskNo}:start` |
| FOOD-API-046 | 制作任务ready | `POST /biz/x/food/tasks/{taskNo}:ready` |
| FOOD-API-047 | 制作任务serve | `POST /biz/x/food/tasks/{taskNo}:serve` |
| FOOD-API-048 | 制作任务hold | `POST /biz/x/food/tasks/{taskNo}:hold` |
| FOOD-API-049 | 制作任务fire | `POST /biz/x/food/tasks/{taskNo}:fire` |
| FOOD-API-050 | 制作任务urge | `POST /biz/x/food/tasks/{taskNo}:urge` |
| FOOD-API-051 | 制作任务void | `POST /biz/x/food/tasks/{taskNo}:void` |
| FOOD-API-052 | 制作任务remake | `POST /biz/x/food/tasks/{taskNo}:remake` |
| FOOD-API-053 | 创建course | `POST /biz/x/food/checks/{checkNo}/courses` |
| FOOD-API-054 | 道次叫起 | `POST /biz/x/food/courses/{courseNo}:fire` |
| FOOD-API-055 | 交付及配送交接 | `POST /biz/x/food/batches/{batchNo}:handover` |
| FOOD-API-056 | 冻结结账范围 | `POST /biz/x/food/stores/{storeNo}/settlements` |
| FOOD-API-057 | 读取预结账 | `GET /biz/x/food/settlements/{settlementNo}` |
| FOOD-API-058 | 结账pay | `POST /biz/x/food/settlements/{settlementNo}:pay` |
| FOOD-API-059 | 结账cancel | `POST /biz/x/food/settlements/{settlementNo}:cancel` |
| FOOD-API-060 | 结账finalize | `POST /biz/x/food/settlements/{settlementNo}:finalize` |
| FOOD-API-061 | 收费优惠调整申请 | `POST /biz/x/food/settlements/{settlementNo}/adjustments` |
| FOOD-API-062 | 退餐申请 | `POST /biz/x/food/stores/{storeNo}/returns` |
| FOOD-API-063 | 退餐审批执行 | `POST /biz/x/food/returns/{returnNo}:execute` |
| FOOD-API-064 | 退餐查询 | `GET /biz/x/food/returns/{returnNo}` |
| FOOD-API-065 | 提交打印 | `POST /biz/print/jobs` |
| FOOD-API-066 | 打印查询 | `GET /biz/print/jobs/{jobNo}` |
| FOOD-API-067 | 人工补打 | `POST /biz/print/jobs/{jobNo}:reprint` |
| FOOD-API-068 | 开班 | `POST /biz/x/food/stores/{storeNo}/cash-shifts` |
| FOOD-API-069 | 非销售现金操作 | `POST /biz/x/food/cash-shifts/{shiftNo}/entries` |
| FOOD-API-070 | 实盘与交班 | `POST /biz/x/food/cash-shifts/{shiftNo}:count` |
| FOOD-API-071 | 接班确认 | `POST /biz/x/food/cash-shifts/{shiftNo}:handover` |
| FOOD-API-072 | 营业日预结 | `POST /biz/x/food/stores/{storeNo}/closes` |
| FOOD-API-073 | 日结查询 | `GET /biz/x/food/closes/{closeNo}` |
| FOOD-API-074 | 异常结转申请 | `POST /biz/x/food/closes/{closeNo}/exceptions/{exceptionNo}:carry` |
| FOOD-API-075 | 签核日结 | `POST /biz/x/food/closes/{closeNo}:sign` |
| FOOD-API-076 | 关账后更正 | `POST /biz/x/food/closes/{closeNo}/adjustments` |
| FOOD-API-077 | 经营指标报表 | `GET /biz/x/food/reports/{reportCode}` |
| FOOD-API-078 | 异步报表导出 | `POST /biz/x/food/report-exports` |
| FOOD-API-079 | 导出结果 | `GET /biz/x/food/report-exports/{exportNo}` |
| FOOD-API-082 | 外部映射 | `PUT /biz/x/food/integrations/{provider}/mappings` |
| FOOD-API-085 | 自助权益入场 | `POST /biz/x/food/checks/{checkNo}/entitlements` |
| FOOD-API-086 | 续时及最后点单校验 | `POST /biz/x/food/entitlements/{entitlementNo}:extend` |
| FOOD-API-087 | 宴会计划 | `POST /biz/x/food/stores/{storeNo}/banquet-plans` |
| FOOD-API-088 | 宴会人数及加席 | `POST /biz/x/food/banquet-plans/{planNo}:confirm-attendance` |
| FOOD-API-089 | 寄存建账 | `POST /biz/x/food/stores/{storeNo}/custodies` |
| FOOD-API-090 | 寄存取出及报损 | `POST /biz/x/food/custodies/{custodyNo}/entries` |
| FOOD-API-091 | 房账转挂 | `POST /biz/x/food/settlements/{settlementNo}/room-charges` |
| FOOD-API-092 | 桌区列表 | `GET /biz/x/food/stores/{storeNo}/table-areas` |
| FOOD-API-093 | 创建桌区 | `POST /biz/x/food/stores/{storeNo}/table-areas` |
| FOOD-API-094 | 编辑桌区 | `PUT /biz/x/food/table-areas/{areaNo}` |
| FOOD-API-095 | 桌区启停 | `POST /biz/x/food/table-areas/{areaNo}:set-status` |
| FOOD-API-096 | 桌台列表 | `GET /biz/x/food/stores/{storeNo}/tables` |
| FOOD-API-097 | 桌台详情 | `GET /biz/x/food/tables/{tableNo}` |
| FOOD-API-098 | 编辑桌台 | `PUT /biz/x/food/tables/{tableNo}` |
| FOOD-API-099 | 桌台启停 | `POST /biz/x/food/tables/{tableNo}:set-status` |
| FOOD-API-100 | 归档桌台 | `POST /biz/x/food/tables/{tableNo}:archive` |
| FOOD-API-101 | 吊销桌码 | `POST /biz/x/food/tables/{tableNo}:revoke-token` |
| FOOD-API-102 | 就餐台账详情 | `GET /biz/x/food/checks/{checkNo}` |
| FOOD-API-103 | 修改人数与座位 | `PUT /biz/x/food/checks/{checkNo}/seats` |
| FOOD-API-104 | 并入桌台 | `POST /biz/x/food/checks/{checkNo}:combine-tables` |
| FOOD-API-105 | 同桌新开独立台账 | `POST /biz/x/food/tables/{tableNo}:share` |
| FOOD-API-106 | 桌台操作恢复查询 | `GET /biz/x/food/table-operations/{operationNo}` |
| FOOD-API-107 | 预约列表 | `GET /biz/x/food/stores/{storeNo}/reservations` |
| FOOD-API-108 | 就餐操作历史 | `GET /biz/x/food/checks/{checkNo}/actions` |
| FOOD-API-109 | 餐牌列表 | `GET /biz/x/food/stores/{storeNo}/menus` |
| FOOD-API-110 | 编辑餐牌头 | `PUT /biz/x/food/menus/{menuNo}` |
| FOOD-API-111 | 餐牌停用 | `POST /biz/x/food/menus/{menuNo}:disable` |
| FOOD-API-112 | 菜品列表 | `GET /biz/x/food/stores/{storeNo}/dishes` |
| FOOD-API-113 | 菜品详情 | `GET /biz/x/food/stores/{storeNo}/dishes/{goodsNo}` |
| FOOD-API-114 | 经营端可售餐牌 | `GET /biz/x/food/stores/{storeNo}/catalog` |
| FOOD-API-115 | 配置发布历史 | `GET /biz/x/food/config-revisions` |
| FOOD-API-116 | 回滚配置为新修订 | `POST /biz/x/food/config-revisions/{revisionNo}:republish` |
| FOOD-API-117 | 配料组合发布 | `POST /biz/x/food/modifiers/{modifierNo}:publish` |
| FOOD-API-118 | 选配组列表 | `GET /biz/x/food/stores/{storeNo}/option-groups` |
| FOOD-API-119 | 套餐列表 | `GET /biz/x/food/stores/{storeNo}/bundles` |
| FOOD-API-120 | 配料列表 | `GET /biz/x/food/stores/{storeNo}/modifiers` |
| FOOD-API-121 | 工位列表 | `GET /biz/x/food/stores/{storeNo}/stations` |
| FOOD-API-122 | 可售限制查询 | `GET /biz/x/food/stores/{storeNo}/sales-restrictions` |
| FOOD-API-123 | 按台账或条件查询批次 | `GET /biz/x/food/stores/{storeNo}/batches` |
| FOOD-API-124 | 取消正式批次 | `POST /biz/x/food/batches/{batchNo}:cancel` |
| FOOD-API-125 | 批次操作恢复查询 | `GET /biz/x/food/batches/{batchNo}/operations` |
| FOOD-API-126 | 关闭草稿购物车 | `POST /biz/x/food/carts/{cartNo}:archive` |
| FOOD-API-141 | 创建配料或配料组合 | `POST /biz/x/food/stores/{storeNo}/modifiers` |

### 平台运营端（3项）

| 编号 | 功能 | 方法与路径 |
|---|---|---|
| FOOD-API-080 | 重算投影 | `POST /ops/x/food/report-rebuilds` |
| FOOD-API-081 | 平台能力配置 | `PUT /ops/x/food/stores/{storeNo}/capabilities` |
| FOOD-API-083 | 接入异常查询 | `GET /ops/x/food/integration-exceptions` |

### 原内部接口（1项）

| 编号 | 功能 | 方法与路径 |
|---|---|---|
| FOOD-API-084 | 边缘增量认领 | `POST /internal/food/sync-batches` |

## §5 验证与完成标准

1. 设计守卫：餐饮137个三端API指向food-web，3个共享打印API保持原归属；食品Service接口/DTO指向food-api，Impl指向food-core；原API084保持内部；API141/表79唯一归属和原方法签名不变。
2. Java实施：依赖树证明独立餐饮前端接入产物不含food-core实现；food-server路由清单证明无三端用户API；原身份/权限/数据域回归通过。
3. 支付合同：独立PAY真实收款/查询/关闭/退款及未知恢复；本地桥接关闭后不能靠核心本地PaymentLedgerService悄悄通过。
4. 库存合同：真实独立库存全锁/缺货回滚/迟到取消/部分响应/重复提交/出库/退货；禁止默认no-op与双重预留，断连时不返回成功。
5. 联合场景：扫码或B端下单→独立库存→独立PAY→获准厨房制作/交付→退餐→退款/退货分轨→打印→日结报表；后付单独合同验收。

## §6 本轮交付

本轮修订设计真源的部署归属及清单生成规则，重生成类/方法清单；最新归属守卫先失败于Controller仍落shop-app，再在迁至food-web的设计后通过。未更改业务API路径/参数、冻结V1、Java业务或数据库。

本轮实际检查：

- 四组方法/模块/设计/DDL结构测试28项通过、0失败、0跳过；新增三端归属测试在旧规则先失败，再在修订规则通过。
- `node scripts/gen-food-schema.mjs --check`通过；冻结73表SQL未变，未连接数据库。
- 同一生成器重复运行，6份生成产物摘要一致；相关人工/生成文档本地链接及空白检查通过，定向`git diff --check`通过。
- 三端明细141个编号无重复：mp16、biz121、ops3、internal1；137个餐饮对外API文件落food-web，3个共享打印API保持原归属。BusinessIdentityPort副本已从当前代码/方法计划去除，已有StoreDirectory提供方被直接复用。

上述是设计与清单验证；独立服务联通、Java编译和业务测试尚未执行。

### Controller整体归属确认 · 2026-10-20

按用户最新要求，137个餐饮三端API的Controller源码统一归food-web，作为餐饮工程完整交付；平台只引入挂载，不保存一份Controller副本。新增food-web只增加一个可复用jar，五模块仍只包含原food-server一个餐饮独立业务启动单元。共享打印、账号、支付、库存不收编为餐饮私有副本。

本轮真实验证：最新模块归属守卫先在旧shop-app文件路径失败，修订后四组结构测试28项通过、0失败、0跳过；冻结DDL检查通过；137个餐饮三端API/3个共享打印API/1个内部API计数与归属检查、本地链接检查通过。尚未创建Java模块或访问数据库。
