# TDD-b-app（B 端 App · 商家端）

状态：**M0–M5 全部已实现（2026-08-05）· 待接真后端**  · 创建 2026-08-05
关联需求：[需求矩阵-三端](../../requirements/需求矩阵-三端.md) §五（B-10 / B-11）· [C端功能清单](../../requirements/C端功能清单.md)
关联决策：[ADR-001 商家端形态](../ADR/ADR-001-商家端形态与拆分时机.md) · [ADR-004 增长模型](../ADR/ADR-004-增长模型从孵化团长转向商家自带客流.md) · [ADR-005 履约与自提点](../ADR/ADR-005-履约方式与自提点模型.md)
关联方案：[TDD-c-app](./TDD-c-app.md)

---

## 1. 需求摘要

按 [需求矩阵](../../requirements/需求矩阵-三端.md) 的 B 端两域（B-10 自提点履约 / B-11 商家经营）**一一对应**建一个独立的 B 端应用，技术栈与 C 端相同（uni-app + Vue3 + TS + Vite + Pinia + UnoCSS + wot-design-uni）。

验收标准：矩阵里每一个 B-1x.y.z 功能点，在 B 端都有对应页面或入口；契约与类型与 C 端同源不漂移。

> ⚠️ 原本还写了「与 C 端数据联动可验证（B 端核销 → C 端订单完成）」，实测该验收点在
> H5 双 dev server 下不成立（localStorage 按 origin 隔离），已顺延到接真后端 —— 见 §4.4。

---

## 2. ⚠️ 与 ADR-001 的冲突（需明示）

[ADR-001](../ADR/ADR-001-商家端形态与拆分时机.md) 的决策是：**一期不做独立商家小程序**，轻操作内嵌 C 端商家专区，二期开放第三方入驻时才拆。

本次需求是**现在就做独立 B 端 App**。这与 ADR-001 一期决策相反。

不阻塞实施，但需记录：本方案实施即意味着 **ADR-001 的一期结论作废、直接进入其二期形态**。ADR-001 §3 列的四条拆分理由（发版节奏、包体 2MB 限制、权限边界、能力诉求）本来就成立，提前拆的代价是**共享层必须先抽出来**，否则两套代码会立刻分叉。

> 建议：本方案确认后，同步更新 ADR-001 状态为「一期结论已被推翻，直接采用二期形态」，并记录原因。

---

## 3. 当前架构分析（c-app）

```
c-app/src/
  api/        contract.ts(唯一契约) endpoints.ts http.ts mock.ts(827行种子+行为)
  types/      index.ts(650行，契约镜像)
  design/     tokens.ts icons.ts        ← 皮肤 token、图标
  ports/      auth/scan/share/payment/push/theme/location/media/font/direction
  shared/     constants/ money format datetime goods promotion fly
  strategies/ pricing×5  fulfillment×6
  i18n/       zh-CN / en / ar
  stores/     app cart community market theme user
  components/ ui(sh-*) biz(biz-*)
  pages/      24 个页面
```

**可复用（与端无关）**：`types` `design` `shared` `strategies` `i18n` `ports` — 约 2800 行。
**不可复用**：`pages` `components/biz` `stores/cart` `stores/community`。
**需分叉**：`api/contract.ts`（B 端是另一套契约）、`api/mock.ts`（需共享同一份数据）。

---

## 4. 方案选型

### 4.1 代码组织

| 方案 | 做法 | 优点 | 缺点 | 结论 |
|------|------|------|------|------|
| **A 复制地基** | `b-app/` 复制 c-app 的 types/design/shared/… | 零重构，最快出活 | **双份维护，两周内必然分叉**；mock 数据不共享，B 端核销 C 端看不到 | ❌ |
| **B workspace 共享包（推荐）** | 根建 npm workspace，抽 `packages/shared/`（types/design/shared/strategies/i18n/ports/mock-data），c-app 与 b-app 各自依赖 | 单一事实源：类型、种子数据、计价与履约策略只有一处定义 | 需改 c-app 的 import 别名（机械改动，有测试兜底） | ✅ **采用** |
| C 跨目录 alias | b-app 用 vite alias 指到 `../c-app/src/**` | 不动 c-app | 小程序编译对项目根外路径支持不稳；类型与构建边界混乱 | ❌ |

**方案 B 的关键理由**：两端共用 `Order` / `Goods` / `Review` 等主类型与同一份种子数据。复制一份的话，改一处字段要记得改两处 —— 而这种漂移在 mock 阶段看不出来，接后端时才爆。

### 4.2 目录结构（方案 B）

```
ai-shop/
  package.json            ← workspaces: ["packages/*", "c-app", "b-app"]
  packages/shared/
    src/
      types/       ← 从 c-app 移入（+ B 端新增类型）
      design/      ← tokens / icons
      shared/      ← constants / money / format / datetime / goods / promotion
      strategies/  ← pricing / fulfillment（+ neighbor-pickup，ADR-005 E15）
      i18n/        ← 三语基础词条（各端再叠加自己的业务词条）
      ports/       ← 端能力抽象
      mock/        ← 种子数据 + 状态机（两端共读写）
  c-app/           ← 改 import：@/types → @shared/types，其余不动
  b-app/           ← 新建
```

### 4.3 B 端契约

新建 `b-app/src/api/contract.ts` 定义 `MerchantApi`，端点前缀 **`/biz/**`**（对应 C 端 `/mp/**`）。
> ⚠️ 原定 `/mb/**`，后端独立实现成了 `/biz/**`，两边一条也对不上 —— 见 [ADR-007](ADR/ADR-007-B端API契约独立.md) 抬头。
`types` 全部来自 `@shared/types`，**不重复定义**。

```ts
export interface MerchantApi {
  // 账号与入驻（B-11.1）
  mLogin(req: LoginReq): Promise<MerchantLoginResp>;
  mProfile(): Promise<MerchantProfile>;
  mApply(payload: MerchantApplyReq): Promise<MerchantProfile>;
  mApplyStatus(): Promise<MerchantApplyStatus>;

  // 店铺与获客（B-11.2）
  mStore(): Promise<Merchant>;
  mSaveStore(payload: Partial<Merchant>): Promise<Merchant>;
  mStoreQrcode(): Promise<{ url: string; printUrl: string }>;
  mShareKit(goodsNo?: string): Promise<{ text: string; posterUrl: string }>;

  // 商品（B-11.3）
  mGoodsList(q: PageQuery & { status?: GoodsStatus }): Promise<PageResult<Goods>>;
  mSaveGoods(payload: GoodsDraft): Promise<Goods>;
  mToggleGoods(goodsNo: string, onSale: boolean): Promise<Goods>;
  mSaveStock(skuNo: string, stock: number): Promise<Goods>;

  // 订单与配送（B-11.4）
  mOrderList(q: PageQuery & { status?: string }): Promise<PageResult<Order>>;
  mOrderDetail(orderNo: string): Promise<Order>;
  mShip(orderNo: string, payload: { expressNo?: string }): Promise<Order>;
  mDelivered(orderNo: string): Promise<Order>;          // 商家自送「已送达」
  mSaveDeliveryRule(rule: DeliveryRule): Promise<Merchant>;

  // 自提点履约（B-10）
  mPickupOrders(): Promise<Order[]>;
  mPickingList(): Promise<PickingRow[]>;
  mMarkArrived(orderNos: string[]): Promise<Order[]>;
  mVerify(code: string): Promise<Order>;

  // 售后（B-11.5）
  mAfterSaleList(): Promise<Order[]>;
  mHandleAfterSale(orderNo: string, agree: boolean, reply: string): Promise<Order>;

  // 团购与报价（B-11.6）
  mGroupList(): Promise<GroupBuy[]>;
  mCreateGroup(payload: { goodsNo: string; minCount: number; priceMinor: number }): Promise<GroupBuy>;
  mRequestList(): Promise<GroupRequest[]>;
  mQuote(requestNo: string, payload: QuoteReq): Promise<GroupRequest>;

  // 评价（B-11.7）
  mReviewList(): Promise<Review[]>;
  mReplyReview(reviewNo: string, reply: string): Promise<Review>;

  // 结算（B-11.9）
  mSettleList(): Promise<SettleBill[]>;
  mSettleDetail(billNo: string): Promise<SettleBill>;

  // 数据（B-11.11，P1）
  mStats(): Promise<MerchantStats>;
}
```

### 4.4 与 C 端的联动点

> ⚠️ **实测修正（2026-08-05）**：共享 `packages/shared/mock/db` 只共享**代码与种子数据**，
> **不共享运行时状态**。mock 落盘走 `uni.setStorageSync` → H5 下是 `localStorage`，而
> localStorage 按 **origin** 隔离；两端跑在不同端口（c-app :5173 / b-app :5273）即不同 origin，
> 各自一份。所以「B 端核销 → C 端订单变完成」**目前在 H5 双服务器下验证不了**。
>
> 三条出路，按代价排序：
> 1. **接真后端**（E1）—— 状态本来就在服务端，问题自动消失。**推荐，也是最终形态**。
> 2. 同 origin 部署（一个 dev server 挂两个 base path），localStorage 自然共享 —— 只为演示改工程结构，不值。
> 3. mock 落盘换成同源 `BroadcastChannel` + 共享后端存根 —— 为 mock 造基础设施，不值。
>
> 结论：**这个验收点顺延到接真后端之后**，不作为 M2 的门槛。M2 仍验证 B 端自身的状态机
> （核销后本端订单进 COMPLETED、重复核销报错、非本点报错）。

| B 端动作 | C 端可见结果 |
|---|---|
| `mMarkArrived` | 用户订单变「待自提」+ 到货消息 |
| `mVerify` | 订单 → `COMPLETED`，可发表评价 |
| `mShip` / `mDelivered` | 物流状态 / 已送达 |
| `mHandleAfterSale` | 同意/驳回；同意后按类型分流（仅退款直退 / 退货退款等寄回） |
| `mConfirmReturn` | 确认收到退货 → 随即退款 |
| `mQuote` | 求团页出现新报价（锁价快照，ADR-003） |
| `mToggleGoods` | 商品下架 → 购物车进失效区 |
| `mSaveStore` | 门店主页店招/公告变化 |

---

## 5. 页面清单（**与矩阵一一对应**）

| # | 页面 | 矩阵 ID | 说明 | 批次 |
|---|------|---------|------|:---:|
| 1 | `login` | B-11.1 | 手机号 OTP（B 端不走微信一键） | M1 |
| 2 | `apply` | B-11.1.1~6 | 入驻：主体类型 / 资质 / 类目 / 结算账户 / 协议 / 审核状态 | M1 |
| 3 | `home`（工作台） | B-10.1 + B-11 汇总 | 今日订单/待发货/待核销/待处理售后/待回复评价 | M1 |
| 4 | `goods-list` | B-11.3.5/3.6 | 在售/下架/审核中；上下架、改库存 | M1 |
| 5 | `goods-edit` | B-11.3.1~3.7 | 五品类模板 + 多规格 SKU 矩阵 + 多语言 + 多市场价 | M1 |
| 6 | `orders` / `order` | B-11.4.1/4.2 | 列表分 tab + 详情 + 接单/备货 | M1 |
| 7 | `ship` | B-11.4.3 | 快递发货：运单号回填 | M2 |
| 8 | `delivery` | B-11.4.6/4.7 | 商家自送：范围/起送价配置 + 待配送列表 + 一键已送达 | M2 |
| 9 | `verify`（核销台） | B-10.2 | 扫码/输码/批量核销 + 失败原因 | M2 |
| 10 | `picking`（分拣单） | B-10.3 | 按商品/按用户 + 导出 + 缺货标记 | M2 |
| 11 | `arrival` | B-10.4 | 到货批次签收 + 破损短少上报 | M2 |
| 12 | `store`（店铺装修） | B-11.2.1~2.5 | 资料/营业时间/服务范围/店招/公告/主推排序 | M2 |
| 13 | `qrcode`（店铺码） | B-11.2.6 | 店铺码 + 可打印版 | M2 |
| 14 | `share-kit`（分享素材） | B-11.2.7 | 卡片/海报/群发文案一键生成 | M2 |
| 15 | `after-sale` | B-11.5 | 售后待处理 + 同意/驳回 + 举证 | M3 |
| 16 | `groups` | B-11.6.1/6.2 | 商家团配置与进度 | M3 |
| 17 | `quotes`（求团报价） | B-11.6.3~6.5 | 需求单列表 + 报价 + 改价留痕 | M3 |
| 18 | `reviews` | B-11.7 | 评价列表 + 回复 + 申诉 | M3 |
| 19 | `settle` | B-11.9 | 结算单 + 分账状态 + 费率说明 | M4 |
| 20 | `stats` | B-11.11 | 销量/评分/报价成功率（P1） | M4 |
| 21 | `me` | 复用 | 外观（4 皮肤×明暗×三语×多市场）+ 设置 | M1 |

**tabBar（4 项）**：工作台 · 订单 · 商品 · 我的。履约台（核销/分拣/到货）从工作台进。

---

## 6. 复用与新建

| 层 | 处置 |
|---|---|
| `types` `design` `shared` `strategies` `i18n` `ports` | **移入 `packages/shared`**，两端共享 |
| `components/ui/sh-*` | ✅ 已移入 `packages/ui`（`sh-scaffold` `sh-tabbar` `sh-icon` `sh-rating` `sh-theme-sheet`）；C 端私有的飞入小球并入 `components/app-overlay.vue`（ADR-008） |
| `components/biz/*` | **不复用**，B 端建自己的 `biz-order-row` `biz-goods-row` `biz-picking-row` 等 |
| `stores/theme` `stores/market` `stores/app` | ✅ 已移入 `packages/ui`（三份逐字节相同） |
| `stores/user` | 分叉：B 端为 `stores/merchant`（`merchantNo` + 审核状态） |
| `api/mock.ts` | **拆分**：`packages/shared/mock/db.ts`（种子+状态）+ 各端 `mock-api.ts`（行为） |

---

## 7. 约束（沿用 C 端规范）

- 组件层**禁止写死颜色**（hex/rgb/oklch），一律走 token；圆角仅五档 —— 由单测拦截
- 页面内**禁止 `#ifdef`**，端差异下沉到 `ports/`
- 金额一律最小货币单位整数
- 三语 + 阿语 RTL 整体镜像；数值方向隔离
- 零硬编码：常量进 `packages/shared/constants`

---

## 8. 测试策略

| 层 | 覆盖 |
|---|---|
| 规范测试 | 无写死颜色 / 圆角五档 / 无 `#ifdef` / 色板 JS-CSS 一致 |
| 单元 | 分拣汇总（按商品/按用户）、核销状态机（重复核销/非本点/已退款）、报价锁价快照、结算金额拆分 |
| 联动 | **B 端核销 → C 端订单 COMPLETED**（跨端 mock 共享的验收点） |
| 类型 | `vue-tsc --noEmit` 两端均需通过 |

---

## 9. 风险

| # | 风险 | 应对 |
|---|---|---|
| 1 | 抽共享包会动到已跑通的 c-app | 只改 import 别名（机械变更）；改完先跑 `type-check` + 规范测试再继续 |
| 2 | 小程序主包 2MB | B 端页面多且有表格，`goods-edit` 与 `stats` 走分包 |
| 3 | B 端权限边界 | 数据裁剪在 mock/后端做，**不靠前端隐藏**；自提点履约只返回履约必需字段（B12） |
| 4 | 矩阵里若干功能点依赖未定业务口径（B9 服务费、B10 费率、B13 脱敏） | 页面按默认值实现并在 UI 标注「口径待定」，不阻塞 |
| 5 | ADR-005 的 `PickupPoint` 实体尚未落地 | B 端履约台先按 `type=STORE` 实现；`NEIGHBOR` 属 C 端轻核销，不在本方案内 |

---

## 10. 实现任务

**M0 地基** ✅ 已完成
- [x] 建 npm workspace，`packages/shared` 骨架
- [x] 移入 types / design / utils（原 shared）/ strategies / ports / mock
- [x] c-app import 别名迁移（`@/x` → `@shared/x`）+ `type-check` 通过 + H5 实测正常
- [x] 去掉 shared 对 `@/i18n` 的隐式反向依赖，改为 `utils/locale` 显式注入
- [ ] ~~i18n / sh-* 组件移入共享~~ —— **未做，且不建议做**：i18n 词条两端语义不同
      （商家看「待发货」，消费者看「待收货」）；`.vue` 放到项目根之外对小程序编译有风险

**M1 能登录能卖货** ✅ 已完成
- [x] b-app 工程初始化（package/tsconfig/vite/uno/manifest/pages.json/index.html）
- [x] B 端契约 `MerchantApi` + 端点表 `/biz/**` + mock + http 三件套
- [x] `login`（手机号 OTP）`apply`（入驻，主体类型驱动资质与结算账户）
- [x] `home`（工作台：待办数字网格 + 今日 + 自带客流占比）`me`（外观面板复用）
- [x] `goods-list`（分 tab + 行内上下架）`goods-edit`（单规格）
- [x] `orders`（按「我要做什么」分 tab）`order`（发货 / 已送达）
- [x] 两端 `type-check` 通过；b-app H5 实测走通 登录 → 入驻 → 工作台 → 商品列表

**M2 能履约** ✅ 已完成
- [x] `verify` 核销台：输码 + 扫码 + 待核销列表 + 失败原因分型（已核销/非本点/码无效）
- [x] `picking` 分拣单：按商品 / 按用户双视图 + 标记到货并通知
- [x] `delivery` 商家自送：范围与起送价配置 + 待配送列表 + 一键「已送达」
- [x] `store` 店铺装修 + 店铺码 + 分享素材（三合一页，B-11.2.5~2.7）
- [x] `ship` 快递发货：运单号回填（并入 `order` 详情页，不单开页面 —— 一个订单只给一个主动作）
- [x] 演示订单种子 `api/demo-orders.ts`：入驻后补一批，覆盖自提/自送/快递三条线
- [x] 实测：分拣 → 标记到货 → 核销台出现该单 → 核销 → 重复核销报「该订单已核销」；自送「已送达」→ COMPLETED
- [ ] ~~`arrival` 到货确认单开页面~~ —— 到货标记已并入 `picking`（同一时刻的同一件事）；
      **破损/短少上报顺延到 M3**，它的下游是售后流程，拆开做没有闭环
- [ ] ~~跨端联动验收~~ —— 见 §4.4，顺延到接真后端

**M3 能经营** ✅ 已完成
- [x] `after-sale` 售后处理：同意退款 / **驳回必须填理由**；驳回**不改订单状态**，用户仍可升级平台介入
- [x] `reviews` 评价与回复：**未回复排前面**（商家是来「把该回的回掉」，不是翻历史）；回复可修改
- [x] `quotes` 邻里求团报价：报价 / 改价；**只公示涨价**、已锁价不可改（ADR-003）
- [x] `groups` 商家团：开团 + 成团进度；**今日已截单拒绝开团**（否则开出来就是 00:00:00 的死团）
- [x] 共享 db 补落盘：`groupSeeds` / `requests` / 评价回复 —— B 端的写操作此前刷新即丢，违反「真改 db，重开能读回」
- [x] 实测：报价 380 → 420 公示「曾报 ¥380」；再降到 350 **不新增**公示条目；
      驳回空理由被拦下；同意退款后订单进 REFUNDED；回复后待回复数 2 → 1
- [ ] B-10.4.2 破损/短少上报顺延 M4（下游是售后责任判定，与 M4 结算同批更合理）

**M5 登录 / 多规格 / 营销**（2026-08-05 追加）✅ 已完成
- [x] **注册登录多端化**：`ports/auth` 改为 `loginMethods()` 按端返回可用方式，页面**不写 `#ifdef`**
      · 小程序 → 微信一键取手机号（主）+ 手机号 OTP　· App → OTP + 微信开放平台 + Apple（**仅 iOS**）　· H5 → OTP
- [x] **登录即注册**：首次登录建号后直接引导入驻，去掉中间那步没有信息增量的注册表
- [x] 协议勾选（默认不勾、不勾不放行）+ 60s 验证码倒计时；`LoginReq.agreed` 由 mock 强制校验
- [x] 手机号确立为商家账号**主标识**：第三方登录后仍需补绑（`MerchantProfile.loginBy` 标记）
- [x] **多规格 SKU 矩阵**：规格组 × 选项 → 笛卡尔积；**改规格保留已填价与库存**（按选项组合匹配）、
      **复用原 skuNo**（历史订单/购物车/库存流水都引用它）、批量设价设库存、展示价取最低 SKU 价
- [x] **营销活动**：店铺券 / 满减 / 限时特价 / 买赠 **合成一个模型**（四张表会得到四份互不知情的叠加规则）
      三条护栏在服务侧强制：券必须设发放总量、限时特价必须选商品、已结束不可复活也不可改
- [x] 共享 db 再补落盘：`goodsSeeds` / `campaigns` —— 商家新建的商品与配的活动此前刷新即丢
- [x] 实测：未勾协议被拦；2×2 矩阵生成正确，加第 3 个选项后原价 19.9 保留；
      落盘为「5 斤 ¥19.9 / 10 斤 ¥29.9」且展示价 19.9；限时特价无商品、券无总量均被拒；活动暂停/开启正常

**M6 原型完善**（2026-08-06 追加）✅ 已完成
- [x] **改库存快捷入口**（B-11.3.6）：`mSaveStock` 此前契约有、无页面消费 —— 店主改个数量要走完整编辑表单。
      现在商品列表就地改；**多规格不给快捷入口**（改哪个 SKU 说不清），显式引导进编辑页
- [x] 输入校验：负数与非数字挡住 —— 库存写成 -5 会让 C 端置灰与到货提醒逻辑全乱
- [x] **我的客户**（B-11.2.8，`pages/customers`）：客户数 / 复购率 / **沉默客户**
      · 沉默 = 买过 ≥2 次且 ≥14 天没来 —— 只买过一次的不算流失，那是关系没建立
      · 沉默排最前：这是店主唯一能立刻行动的信号，埋在列表底部等于没有
      · 只给脱敏昵称不给手机号（B12），联系走平台消息通道
- [x] 演示数据补历史单：原来全是当天的单，**复购率与沉默客户恒等于 0**，这页的两个信号一个都验证不到
- [x] 修掉过时文案：`goods.skuHint` 还写着「多规格矩阵在后续批次开放」，但 M5 已经做了
- [x] 实测：复购率 33%、沉默 1 人排首位、库存 150→88 就地生效、多规格正确落到编辑页

**M10 B 端 UI 对齐 C 端 + 公共件抽取**（2026-08-06）✅ 已完成
- [x] `sh-empty`（27 处）与 `sh-tabs`（12 处）抽进 `packages/ui`；筛选条顺带把
      「chip 横排 / 方块」两套实现统一成一套（ADR-008 §3d）
- [x] `.field*` 表单件收进 b-app 全局样式：11 个页面各一份且**已漂移**（88/30 vs 84/28）。
      只有 B 端用，故不进平台层
- [x] 列表密度对齐 C 端：`orders` / `goods-list` / `picking` / `verify` 行距 20~24 → 14rpx，
      表单字段间距 28 → 20rpx，条目类间距统一 14rpx
- [x] 修 `ensureDemoOrders` 只在 onLaunch 跑一次的问题 —— 那时还没入驻，
      新商家进来订单/核销/分拣三页永远空，看着像功能坏了（老账号有旧数据反而看不出）
- [x] 新增断言：页面不许再自定义 `.empty` 与 `.tabs__item`
- [x] **B 端演示态补齐**（用户报「商家端没有正常展示、底部菜单丢失」后定位）：
      根因不是样式 —— **mock 从来没有「已开店」的初始状态**，全靠开发机上历史遗留的本地数据撑着；
      换存储命名空间后每个新浏览器都是空壳（工作台「还没有开店」，而店铺/营销这类详情页本就没有 tabbar）。
      三处修复：
      · `ensureDemoMerchant()` 播一个已开店的演示商家（M002 阿明果蔬合作社 —— 有商品、有评价、
        且是自提点，三条履约线都能跑通；M001 是平台自营，不代表普通店主视角）
      · `useDemoSession()` 在 mock 且无 token 时建立演示会话，否则 db 里有店但界面仍未登录
      · 演示数据**落盘**（原先只改内存，刷新即失），且补单判据从「db 有没有单」改成
        「本店有没有**待办**单」—— 共享种子自带 3 条 C 端已完成单，旧判据会被它们挡住，
        工作台六个数字全是 0
- [x] 修 `orders` 行内状态标签：原先拼的是 tab 的 key（`order.tab${PAID ? "ToShip" : "All"}`），
      于是除待发货外**一律显示「全部」**，整列没有信息。补 `order.status*` 三语词条，
      并按「要动手的用主色、售后用警示色、终态中性」上色

---

**M9 组件库 + 四端与部署**（2026-08-06）✅ 已完成
- [x] `packages/ui`：共享组件 + `theme/app/market` 三个同构 store；差异走 `configureShell()` 注入，
      库里没有一行知道自己跑在 C 端还是 B 端（[ADR-008](ADR/ADR-008-共享组件库与四端部署.md)）
- [x] 常驻覆盖层从 `<component :is>` 改为约定组件 `<app-overlay>` —— 动态组件小程序端**编译失败**
- [x] 组件库软链进各 app 自己的 `node_modules`（`postinstall`）+ `preserveSymlinks` ——
      否则小程序端 chunk 路径越出根目录，rollup 拒绝
- [x] PC 窄栏：`--sh-app-max` + 框内滚动 + rpx 钉 480；桌面端滚回顶部改用 `scrollToTop()`
- [x] 四端实跑：`build:h5` / `build:mp-weixin` / `build:app` × 两端，六个构建全过
- [x] 底层继续下沉：全局样式基座、uno 配置、i18n 引擎、HTTP 传输层四样合一；
      `gen-skins` 改为只写共享的 `base.css`（原先往两个 App.vue 各写一遍）
- [x] `npm run build:web` 合成单站点：C 端 `/`、B 端 `/m/`，本地静态托管实测两个入口均 200

---

**M8 商品国际化 + 售后闭环**（2026-08-06）✅ M8-1 / M8-2 / M8-3 / M8-4 全部完成

> 排序理由：不按清单顺序逐条做，按**同一问题域打包** —— 多语言与多市场定价都是
> 「商品在三语三市场下怎么表达」，分两次做要动同一批文件两遍。

**M8-1 词条一致性测试** ✅ 已完成
- 背景：`apply` 整个词条块在 `en/ar` 里**丢失**，中文正常、另外两语显示裸 key。
  这类问题没有任何东西能挡 —— 只会在真机上以「界面出现英文 key」的形式被发现，那时通常已上线。
- 做法：断言两端三语的 **key 集合完全一致**（递归比较嵌套结构），缺失/多余都报出具体路径。
- 顺带断言：**占位符一致** —— `{n}` 在中文有、英文漏写，会渲染成半截句子。
- 落地：`packages/shared/tests/i18n-parity.test.ts`，两端 × 两条断言。
  **一上来又抓到一条**：`fulfillmentDesc.NEIGHBOR_PICKUP` 在 en/ar 被插错块（进了 `fulfillment` 名称块）。
- 实测有效：删一条词条 → 报「缺少 1 条」；抽掉一个 `{m}` → 报「占位符不一致」。

**M8-2 商品多语言录入**（B-4.9）✅ 已完成
- 现状：`toI18n()` 把中文抄进三语，等于「切到英文看到中文」。三语是一期范围（C端清单 §五之二），
  但商品文案只有一份 —— 这是一期范围内的实质缺口，不是二期功能。
- 做法：`GoodsDraft.title/subtitle` 从 `string` 改为 `I18nText`；编辑页加语言 tab（中/英/阿），
  **只有中文必填**，其余留空时回落中文并显式标注「未翻译」。
- 关键判断：**不做自动翻译**。机翻的商品名会直接出现在下单页与小票上，错了没人兜底；留空回落至少是诚实的。
- 交互取舍：**一个输入框 + 语言 tab**，不给三个框并排 —— 并排把表单撑长两倍，
  而店主九成时间只填中文；未填的语言在 tab 上标点，省得逐个点过去才知道漏哪门。
- 识别结果只写进**中文格**：识别出来的本来就是中文，塞进英文格是假装翻译过。
- 实测：中文「五常大米」+ 英文「Premium Rice」→ 落库 ar 回落中文，提示从「EN、ع 未填」变「ع 未填」。

**M8-3 多市场分别定价**（B-4.10 / B6）✅ 已完成
- 现状：mock 用固定汇率换算（`FX` 表）。真实系统里**必须分别定价** ——
  汇率换算出的价格没有价格心理学（¥29.9 换成 $4.19 不是任何人会标的价），
  且汇率一动全店价格跟着抖。
- 做法：`Sku.price` 从 `number` 改为 `Record<CurrencyCode, number>`；编辑页按市场分别填；
  **未填的市场不在该市场售卖**（而不是用汇率兜底）—— 这比错价上架安全。
- **实际做法比原方案小得多**：先量了影响面 —— `.price` 有 66 处引用，改契约类型会波及购物车/订单/结算。
  但架构里已有现成模式：种子存全量、`toGoods` 按当前语言拍平。**定价照做即可，契约层不动**：
  · 种子层 `SkuSeed.priceByMarket`（真源）→ `toGoods` 按 `currentCurrency()` 取价
  · 契约 `Sku.price: number` 保持不变 → 零波及
- 汇率降级为**只给老种子补初始价**，不参与运行时取价；划线价/卡面额/团购价/报价仍按汇率折算，
  但它们是**派生展示值**不是定价 —— 注释里点明了这个区别
- 编辑页：SKU 矩阵加**市场 tab**（与语言 tab 同一套交互）。不给三列并排 ——
  矩阵本就可能 8 行，再乘 3 列在手机上没法填。批量填价只作用当前市场，避免把美元价误批到人民币
- 实测：CNY ¥29.90 + USD $5.99 落库为 `{CNY:2990, USD:599}`；**汇率换算会得到 $4.19** —— 差别正是这件事的意义。
  AED 未填 → 提示「不会在这些市场售卖」

**M8-4 售后闭环后半段**（B-7.3/7.4）✅ 已完成
- [x] 售后从「一个布尔」升级为 `AfterSale{type,status,...}`：
      **仅退款** 直接退；**退货退款** 走 `PENDING → AGREED → RETURNING → RECEIVED → DONE`。
      合成一条路的后果就是「退款了货没回来」
- [x] **极速退只对仅退款成立** —— 小额自动通过是为了省一次人工，不是为了把货和钱一起送出去
- [x] 驳回**不改订单状态**，用户可 `raiseDispute` 上升 → `DISPUTED`；商家侧只读，裁决在 ops-web（未建）
- [x] 退款三件事收敛进 `settleRefund()`：改状态 + 回收已得积分 + 退还已用积分。
      散在三处时必然漏一处 —— 这次三条路径（仅退款 / 确认收货 / 驳回后平台退）复用同一个
- [x] **`delay()` 改为返回深拷贝**：mock 原样返回 db 里的对象，`order.value = await api.x()` 拿到同一引用，
      Vue 判定「没变」→ 填完运单号界面纹丝不动。真实 HTTP 每次都是新对象，mock 必须一致，
      否则这类 bug 只在 mock 下出现（或反过来：页面顺手改返回值 = 偷改数据库）
- [x] B 端按售后状态给动作，不再一律「同意/驳回」；`afterSale` 缺失时按待处理的仅退款兜底，
      不能让单子卡在页面上没有任何按钮
- [x] 实测两端全链路：C 选退货退款（极速退提示消失）→ B「同意退货」→ C 填运单号 `SF999888777` →
      B「确认收到退货并退款」→ 订单 `REFUNDED`、售后 `DONE`、列表清空；
      驳回分支：C「申请平台介入」→ `DISPUTED`，B 端显示只读的平台介入中

**不做（越界）**：平台端规格模板维护属 `ops-web`（Next.js，另一条线），本批不碰以免冲突。

---

**M7 规格模板 + 拍照建品**（2026-08-06 追加）✅ 已完成
- [x] **规格模板两层**（B-11.3.2）：平台按类目预置（生鲜→重量/等级，日用→包装/香型，服务→时长）
      + 商家「存为我的常用」。模板是**建议不是强制** —— 卖手工酱菜的没有匹配模板，硬选只能瞎选
- [x] **`SpecOption.code` 是这件事的真正理由**：自由文本下三家店会写成「5斤」「五斤」「2.5kg」，
      在库里毫无关系，二期想做规格筛选 / 同规格比价全部落空，**且不可回溯**（历史商品已写死）。
      平台模板带 code 可聚合；商家自存的与手输的只有 label，照常展示但不参与聚合
- [x] 手改模板带来的选项文字 → 该位置 code 自动作废（值已不是模板那个值）
- [x] 一期**只写入不消费**，聚合搜索留二期 —— 但字段现在就落库，实测 `P_BAG/P_BOX/…` + `ST_GOODS_PACK` 端到端进 db
- [x] **拍照建品**（B-11.3.7 / E9）：拍一张设主图 + 识别猜标题
      · 端差异不在「能不能拍」，而在：小程序上传需域名白名单、**不能跑本地模型**、切后台会挂起
      · 因此**识别统一放服务端** —— 两端一套逻辑，小程序不掉队，App 不必为端侧模型撑大包体
      · 置信度 <0.6 不预填只提示；已填标题不覆盖（店主写的优先于机器猜的）
      · **绝不自动上架** —— 识别错了价格也错，货会以错价卖出去
- [x] 实测：H5 下投喂图片 → 主图显示 → 猜出「洗衣液 大容量装」并预填标题与品类

**M4 能算账** ✅ 已完成
- [x] `settle` 结算单：按自然周分期；**退款从应结中扣回**（已分账要先回退再退款，ADR-002）
- [x] 佣金**按 `trafficSource` 分档**：自带客流 0%、平台客流 2%（占位，B10 待定）
- [x] 履约服务费按件单列（占位 ¥0.30/件，B9 待定）—— 供货方付、承接方收，本店两角色都担时账面抵消但分别列示
- [x] `stats` 经营数据：今日 / 本月 / 评分 / **自带客流占比**（带进度条）。不做图表 —— 一天几十单的折线图读不出东西
- [x] B-10.4.2 **破损/短少上报**（补做）：分拣页点邻居名字 → 短少/破损 → 填说明；
      **只留痕并通知用户，不自动退款** —— 责任归属未定（矩阵 M4），自动退等于默认平台兜底
- [x] 实测：完成 2 单后本期应结 ¥133.81 = 毛 ¥149.00 − 退 ¥12.80 − 佣金 ¥1.79 − 服务费 ¥0.60，逐项对得上；
      上报后订单时间线留痕 + 用户收到站内信

---
确认记录：待用户确认
