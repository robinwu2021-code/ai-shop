# 商家 App · 功能矩阵

> 生成于 2026-08-28 · **本文的每一列都是从代码里读出来的**，不是照着记忆写的：
> 页面与域取自 [`ui-catalog.json`](./ui-catalog.json)（由 `scripts/gen-ui-catalog.py` 生成），
> 权限门取自各页 `<sh-scaffold :denied="!merchant.can('…')">`，
> 端点数取自页面里 `api.mXxx(` 的调用点。
> 有出入时以代码为准，并回来改这份文档 —— 不要反过来。

## 一、一眼看到的四件事

**1. 清单说 68 页，实际 64 页。** 有 4 条路由被算了两次 —— 同一个页面既有
「已实现」的条目，又留着一条「原型」条目：

| 路由 | 已实现 | 还挂着的原型 |
|---|---|---|
| `member-tags` | 标签 | 标签与合并 |
| `member-settings` | 会员设置 | 会员口径设置 |
| `coupons` | 优惠券 | 券列表 |
| `coupon-edit` | 新建券 | 新建券 |

CLAUDE.md 里写着「页面落地后从 `PROTOTYPES` 里删掉」，这四条没删。
后果不严重但会一直存在：清单里的总数偏大，且同一个页面在同一个域里出现两次。

**2. 12 个页面没有页面级权限门**，其中 4 个值得单说：

| 页面 | 后端的门 | 现在会发生什么 |
|---|---|---|
| 我的收入 `income` | `biz:finance` | 店员点进去，页面渲染出来，然后接口 403 |
| 积分 `points` | `biz:finance` | 店员点进去，页面渲染出来，然后接口 403 |
| 发分明细 `points-records` | `biz:finance` | 同上 |
| 预约排期 `schedule` | `biz:store` | 同上 |

**这不是越权口子** —— 后端拦着（`BizEndpointPermTest` 保证每个 `/biz` 端点
都有权限决定）。问题在于店员看到的是**一屏报错**，而不是那一屏干净的
「无权访问」。其余 8 个（登录、入驻、工作台、我的、选择门店、消息、证照两屏）
本来就该人人可进。

**3. 11 个端点做完了，界面上到不了。** 其中三个是进销存的：

| 端点 | 是什么 | 缺口 |
|---|---|---|
| `PUT /biz/inventory/inbounds/:no` | 改入库单 | 无入口 |
| `POST /biz/inventory/inbounds/:no/void` | **作废入库单** | 无入口 |
| `POST /biz/inventory/outbounds/:no/void` | **作废出库单** | 无入口 |

设计文档写着「单据可作废，不可修改：已过账的只能整单作废重录」——
**而界面上没有作废**。「按单查」那一屏能看到单，点不了作废。
录错一张已过账的单，商家现在没有任何自助的补救办法。

其余 8 个：`mAddStaff`（加员工走的是别的路）、`mCustomers`、`mPatchMember`、
`mTagMembers`、`mSaveSpecTemplate`、`mRenameSpecDim`、`mArchiveSpecDim`、
`mSavePresale`（预售）。各自值不值得补，要按域判断，不在本文范围。

**4. 权限分布很不均**：13 个权限码里，`biz:stock`（8 页）、`biz:customer`（9 页）、
`biz:campaign`（8 页）、`biz:store:admin`（8 页）占了一半以上的页面，
而 `biz:verify` / `biz:receive` / `biz:ship` / `biz:review` / `biz:aftersale`
各只守着 1 页。这本身不是问题（履约类动作天然集中在少数几屏），
但它说明**角色差异主要体现在前四个权限上** —— 做角色测试时先覆盖这四个。

## 二、矩阵

「端点」是这一页直接调用的接口数量，用来看这一页有多重 ——
0 表示纯静态或全靠父页传参。

| 域 | 页面 | 路由 | 权限门 | 端点 |
|---|---|---|---|:--:|
| **工作台** | 工作台 | `home` | — | 6 |
| **订单与履约** | 订单 | `orders` | `biz:order:view`<br>看订单 | 2 |
|  | 详情 | `order` | `biz:order:view`<br>看订单 | 4 |
|  | 核销台 | `verify` | `biz:verify`<br>核销 | 7 |
|  | 分拣单 | `picking` | `biz:receive`<br>接单 | 4 |
|  | 商家自送 | `delivery` | `biz:ship`<br>发货 | 4 |
|  | 售后处理 | `after-sale` | `biz:aftersale`<br>处理售后 | 5 |
| **商品** | 商品 | `goods-list` | `biz:stock`<br>管库存 | 10 |
|  | 编辑商品 | `goods-edit` | `biz:goods`<br>管商品 | 16 |
|  | 类目与规格 | `store-categories` | `biz:store:admin`<br>改店铺 | 3 |
|  | 商品规格 | `my-specs` | `biz:goods`<br>管商品 | 8 |
| **账号与设置** | 我的 | `me` | — | 3 |
|  | 商家登录 | `login` | — | 1 |
|  | 入驻申请 | `apply` | — | 5 |
|  | 员工详情 | `staff-detail` | `biz:store:admin`<br>改店铺 | 6 |
|  | 角色 | `role-detail` | `biz:store:admin`<br>改店铺 | 6 |
|  | 员工 | `staff` | `biz:store:admin`<br>改店铺 | 3 |
| **消息与评价** | 消息 | `messages` | — | 3 |
|  | 评价与回复 | `reviews` | `biz:review`<br>管评价 | 3 |
| **其它** | 预约排期 | `schedule` | — | 3 |
|  | 商品编码导入导出 | `sku-identity` | `biz:goods`<br>管商品 | 3 |
|  | 证照与账户 | `entities` | — | 1 |
|  | 证照详情 | `entity-detail` | — | 3 |
|  | 添加会员 | `member-add` | `biz:customer`<br>看客户与数据 | 2 |
|  | 人群 | `member-segments` | `biz:customer`<br>看客户与数据 | 4 |
|  | 发放记录 | `coupon-issues` | `biz:campaign`<br>做活动 | 3 |
|  | 店铺活动 | `activities` | `biz:campaign`<br>做活动 | 2 |
|  | 新建活动 | `activity-edit` | `biz:campaign`<br>做活动 | 3 |
|  | 给会员发消息 | `member-reach` | `biz:customer`<br>看客户与数据 | 3 |
| **钱** | 我的收入 | `income` | — | 1 |
|  | 积分 | `points` | — | 2 |
|  | 发分明细 | `points-records` | — | 1 |
|  | 收款设置 | `payment` | `biz:finance`<br>管钱 | 4 |
|  | 结算单 | `settle` | `biz:finance`<br>管钱 | 5 |
|  | 我的套餐 | `plan` | `biz:store:admin`<br>改店铺 | 2 |
| **门店** | 门店管理 | `stores` | `biz:store:admin`<br>改店铺 | 10 |
|  | 资质证照 | `qualifications` | `biz:store`<br>看店铺 | 2 |
|  | 店铺设置 | `store` | `biz:store`<br>看店铺 | 6 |
|  | 公告 | `store-notice` | `biz:store`<br>看店铺 | 3 |
|  | 经营范围与送货 | `store-scope` | `biz:store`<br>看店铺 | 8 |
|  | 选择门店 | `store-pick` | — | 0 |
| **团购与求团** | 邻里求团报价 | `quotes` | `biz:campaign`<br>做活动 | 2 |
|  | 商家团 | `groups` | `biz:campaign`<br>做活动 | 3 |
| **数据** | 经营数据 | `stats` | `biz:customer`<br>看客户与数据 | 1 |
|  | 跨店总览 | `cross-store` | `biz:customer`<br>看客户与数据 | 1 |
| **会员与营销** | 营销活动 | `marketing` | `biz:campaign`<br>做活动 | 4 |
|  | 会员 | `customers` | `biz:customer`<br>看客户与数据 | 5 |
|  | 会员详情 | `member-detail` | `biz:customer`<br>看客户与数据 | 1 |
|  | 标签 | `member-tags` | `biz:customer`<br>看客户与数据 | 4 |
|  | 会员设置 | `member-settings` | `biz:store:admin`<br>改店铺 | 2 |
|  | 优惠券 | `coupons` | `biz:campaign`<br>做活动 | 4 |
|  | 新建券 | `coupon-edit` | `biz:campaign`<br>做活动 | 2 |
|  | 手工录入会员 | `members/add` | — | 0 |
|  | 人群 | `segments` | — | 0 |
|  | 发放结果 | `coupon-issue` | — | 0 |
| **进销存** | 库存 | `stock` | `biz:stock`<br>管库存 | 2 |
|  | 库存明细 | `stock-detail` | `biz:stock`<br>管库存 | 3 |
|  | 盘点 | `stock-check` | `biz:stock`<br>管库存 | 5 |
|  | 进货 | `purchase-edit` | `biz:stock`<br>管库存 | 3 |
|  | 按单查 | `stock-docs` | `biz:stock`<br>管库存 | 2 |
|  | 报损 | `stock-out` | `biz:stock`<br>管库存 | 3 |
|  | 调拨 | `transfer` | `biz:stock`<br>管库存 | 6 |
|  | 报表 | `stock-report` | `biz:customer`<br>看客户与数据 | 2 |
|  | 库位 | `locations` | `biz:store:admin`<br>改店铺 | 3 |

## 三、按能力看（进销存那一块）

其余 11 个域的「能力 → 界面」映射，见各域自己的设计文档。
进销存这一块已经单独理过，且是唯一有完整对齐清单的：

- [进销存 · 四层对齐清单](./进销存-四层对齐清单.md) —— 能力 / 端点 / 表 / 界面四层对齐
- [进销存 · B 端交互梳理](./进销存-B端交互梳理.md) —— 九屏按「看 / 记 / 查 / 配」重排
- [进销存 · 真机验证清单](./进销存-真机验证清单.md) —— 五条，待人工执行

**这里补一条上面两份都没写的**：作废没有入口（见 §1.3）。
它不是界面细节，是「录错了怎么办」这个问题在这套系统里目前无解。

## 四、怎么重新生成

页面与域：`python3 scripts/gen-ui-catalog.py`（pre-push 有闸门）。
权限门与端点数：本文 §2 的表由一段一次性脚本从 `.vue` 里读出来 ——
它没有沉淀成生成器，因为「页面调了几个接口」这个数只在做这类盘点时有用，
不值得再加一道每次推送都要跑的闸门。要重算就照 §0 的三条规则再读一遍代码。
