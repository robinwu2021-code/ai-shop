# TDD-运营端商品治理补齐（P-3.3 库存与预售 · P-3.4 规格模板维护）

> 状态：**已确认（2026-08-13 用户授权批量推进）** · 上游：[需求矩阵-三端](../../requirements/需求矩阵-三端.md) §六 P-3.3、[平台端功能清单](../../requirements/平台端功能清单.md) P-3.4（E27）· 优先级 P0

---

## 一、一句话

把运营端「库存与预售」和「规格模板」这两块**页面早就画好、后端一直 404** 的功能补成真链路：
预售额度真的能让缺货的生鲜继续卖、截单时间真的能截住、超卖真的能报出来；
平台模板真的能维护，商家在 B-4.4 选到的不再是一张空表。

---

## 二、为什么是这个方案（否掉了什么）

### 2.1 预售额度：否掉「只存一个数字」

第一版想法是照 mock 原样做：`prd_sku` 加 `presale_quota`、`cutoff_at`，运营能改能看，收工。

**否掉的理由**：那样的话预售额度是个**谁也不读的字段**。下单仍然走
`stock - locked_stock >= qty` 这一条闸，缺货就是买不了 —— 额度配 500 和配 0
对买家完全一样。运营会以为自己开了预售，而 C 端一件都卖不出去，
且没有任何报错。P-3.3.3 的超卖告警更是永远为空：既然一件都超不出去。

**防住什么**：额度必须落在真实的下单闸门上。所以这一版把
`StockPortImpl.lock()` 改成两级：现货不足时**回落到预售额度**，
额度用完或过了截单时间才真的失败。场景测试 `OpsProductGovernFlowTest`
里那条「现货 1 件、额度 3 件 → 第 2、3、4 件买得到，第 5 件买不到」
撤掉实现就变红。

### 2.2 超卖告警：否掉「下单时允许超额」

既然额度闸门是 `sold_count + qty <= presale_quota`，超卖按定义不该发生。
那 P-3.3.3 报的是什么？

否掉了「把闸门放宽成允许超一点」这个思路 —— 一个**故意留的洞**没法解释给运营听，
也没法定「超多少算超」。

采用的口径是：**超卖只可能由平台自己调额度调出来**。运营把额度从 300 调到 200
而已售 260（次日现采临时收紧、供应商掉链子），这时那 60 件已经卖出去了，
必须有人去决定是补货还是退单。`setSkuPresale` 因此**允许把额度调到已售之下**
（不拦），但调完这条 SKU 立刻出现在 `GET /ops/skus/oversell` 里。

**防住什么**：拦住「额度不能小于已售」看着更严谨，实际是把问题藏起来 ——
运营改不动额度，只好不改，于是那 60 件谁也不知道。告警的价值在于**它是运营自己造成的**，
所以它一定有人认领。

### 2.3 sku 级审核 / 强制下架：否掉「新造一套 sku 审核态」

ops-web 契约声明了 `auditSku` / `forceOffSku`，而后端 `prd_sku` 没有审核态，
审核判的一直是 `prd_goods.audit_status`（标题、图、类目、资质都在 goods 上）。

- **否掉**给 `prd_sku` 加 `audit_status`：同一件商品会被审好几遍，
  而三个规格审出三个不同结论时，这件商品到底能不能卖没有答案。
- **采用**：这两条端点**解析到父商品**再执行，路径保持 sku 粒度（前端形状不动）。
  `auditSku` 等价于 `auditGoods`；`forceOffSku` 是**主体级下架 + 撤社区池**，
  与 goods 级的 `forceOffGoods`（撤销过审、必须重新提审）分成两件事 ——
  这正是 mock 里两者落到 `OFF_SALE` 与 `REJECTED` 的差别。

**防住什么**：如果两条都做成「撤销过审」，运营想临时压一个规格就只能整件打回，
商家必须走完整的重新提审才能恢复一件本来没问题的商品。

### 2.4 规格模板：否掉「再建一张平台模板表」

`prd_spec_template` 已经有 `scope = PLATFORM / MERCHANT` 两档，商家侧
`BizGoodsController.specTemplates` 查的就是「平台的 + 我自己的」。缺的只是**平台侧的维护入口**。

- **否掉**新建 `prd_platform_spec_template`：两张表意味着商家侧那条查询要 union，
  且「平台模板」与「商家模板」的 `templateNo` 会落进两个序列，
  商品保存时记下的 `templateNo` 没法反查是哪张表的。
- **采用**：复用同一张表，平台侧只操作 `scope = PLATFORM` 的行。
  新增一列 `status`（ACTIVE / DISABLED）承载归档 —— 逻辑删除（`deleted`）做不了
  「归档后还能恢复」，而模板停用是常态操作（换季、类目调整），不是删除。

**防住什么**：不加 `status` 就只能真删。删掉的模板一旦有商品引用过它的
`templateNo`，历史规格就再也解释不了「这个 code 是什么意思」。

### 2.5 `optionCode` 必填：这是平台模板存在的**唯一理由**

平台模板保存时**每个选项都必须带 `code`**，否则拒绝。

**防住什么**：B-4.5 记着这条 —— 自由文本下三家店会把同一件事写成
「5 斤」「五斤」「2.5kg」，聚合、比价、搜索全部对不上。
平台模板带 code 才能把三家店的「5 斤」认成同一个规格。
一个没有 code 的平台模板与商家自己手输的模板没有任何区别，
它唯一的作用是让人**以为**规格统一了。

---

## 三、结构

### 3.1 两块功能的落点

| 需求 | 端点 | 权限码 | 数据落点 |
|---|---|---|---|
| P-3.3.1 预售额度配置 | `POST /ops/skus/{skuNo}/presale` | `product:sku:audit` | `prd_sku.presale_quota` |
| P-3.3.2 截单时间配置 | 同上（同一次提交） | `product:sku:audit` | `prd_sku.cutoff_at` |
| P-3.3.3 超卖告警 | `GET /ops/skus/oversell` | `product:sku:read` | `sold_count > presale_quota` |
| P-3.3 列表 | `GET /ops/skus` | `product:sku:read` | `prd_sku × prd_goods` |
| P-3.2 sku 级审核 | `POST /ops/skus/{skuNo}/audit` | `product:sku:audit` | 解析到 `prd_goods` |
| P-3.2.3 sku 级强制下架 | `POST /ops/skus/{skuNo}/force-off` | `product:sku:audit` | `prd_goods.on_sale` + 撤池 |
| P-3.4 模板列表 | `GET /ops/spec-templates` | `product:category:read` | `prd_spec_template` |
| P-3.4 模板保存 | `POST /ops/spec-templates` | `product:category:update` | 同上，`scope=PLATFORM` |
| P-3.4 模板归档/恢复 | `POST /ops/spec-templates/{no}/archive`·`/unarchive` | `product:category:update` | `prd_spec_template.status` |

**一个新权限码都没造**。理由写在 §五。

### 3.2 预售在下单链路上的位置

```
POST /mp/order
   └─ StockPort.lock(lockNo, items)
        ├─ 该 SKU 启用了分店库存？ → 走 prd_store_stock（**预售不参与**，见 §四.3）
        └─ 主体级：
             ① UPDATE prd_sku SET locked_stock += n WHERE stock - locked_stock >= n
                └─ 影响 1 行 → 现货成交，锁行 presale = 0
             ② 影响 0 行（现货不足）→ 回落预售：
                UPDATE prd_sku SET sold_count += n
                 WHERE presale_quota > 0
                   AND (cutoff_at IS NULL OR cutoff_at > NOW())
                   AND sold_count + n <= presale_quota
                └─ 影响 1 行 → 预售成交，锁行 presale = 1
                └─ 影响 0 行 → 真的卖不了（额度满 / 已截单 / 没开预售）
```

图上看不出来的那一条：**两级是有先后的，现货优先**。反过来（先吃额度）会让
有现货的时候也在消耗预售额度，而预售额度对应的是次日现采的采购计划 ——
采购会按一个虚高的数字去备货。

---

## 四、详细设计

### 4.1 数据

**V100** `prd_sku` 加四列：

| 列 | 类型 | 说明 |
|---|---|---|
| `presale_quota` | `INT NOT NULL DEFAULT 0` | 0 = 不做预售。**默认 0 是关键**：存量 SKU 的下单行为一个字节都不变 |
| `sold_count` | `INT NOT NULL DEFAULT 0` | 预售期内已售（锁定即计入，释放即回退） |
| `cutoff_at` | `DATETIME NULL` | 截单时间。NULL = 不设截单，只靠额度封顶 |
| `arrive_at` | `DATETIME NULL` | 到货时间，与履约批次对齐。**只做校验基准**，不驱动履约 |

`prd_sku` 是「一市场一行」（唯一键 `entity_no, sku_no, market`），四列在各市场行上重复。
与库存同一口径：**库存与预售额度都不分市场**（货就那么多，卖到哪个市场都是同一批），
分市场的只有价格。所有写入按 `sku_no` 更新，与既有的 `lockStock` 逐字同构。

**V101** `prd_stock_lock` 加 `presale TINYINT NOT NULL DEFAULT 0`。

**防住什么**：不记这一位的话，释放时不知道该把数减回 `locked_stock` 还是 `sold_count`。
减错的后果是「取消一单预售，现货库存凭空多一件」——
而这件货根本不存在，下一个买家会买到一件永远发不出去的商品。

**V102** `prd_spec_template` 加 `status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'`。
商家侧 `specTemplates()` 同步只查 ACTIVE ——
**归档了商家还能选，等于没归档**，而运营会以为自己把那套错的规格下线了。

**V103** 新菜单叶子「规格模板」的功能点与角色授权（`sys_function_point` +
`sys_role_point`）。不出这条迁移的表现是**静默降级**：页面、路由、代码全在，
菜单里就是没有这一项，且没有任何报错。

> 四条迁移**同步写进** `backend/shop-app/src/test/resources/schema-test.sql`。
> 那份是测试库 DDL，漏同步的表现是全量测试红在一个与本次改动无关的地方。

### 4.2 `GET /ops/skus` 的形状

一行 = 一个逻辑 SKU（各市场行按 `sku_no` 聚合成一张价格表），字段与 ops-web 的
`Sku` 类型逐条对齐：`skuNo / title{zh,en,ar} / merchantNo / merchantName /
categoryNo / categoryName / status / prices{CN,SG} / stock / presaleQuota /
soldCount / cutoffAt / arriveAt / createdAt / reason`。

`status` 取父商品的状态并按词典 §11 翻成 `PENDING`（库里那列仍是 `AUDITING`）——
与 `GET /ops/goods` 同一套口径，两处不一致会让同一件商品在两个 tab 里显示成两种状态。

新增查询参数 `presaleOnly`：只给「库存与预售」tab 用。
**防住什么**：不给这个参数的话前端只能拉一页再自己 `filter(presaleQuota > 0)` ——
真实库里预售 SKU 大概率不在第一页，那个 tab 会**长期显示为空**，
而接口 200、数据也是真的。

### 4.3 预售的三条边界

1. **启用了分店库存的 SKU 不走预售**。分店库存的语义是「没设过库存的店视为 0」，
   叠上一个主体级的预售额度，等于给没设库存的店开了一个后门。
   一期把预售限定在主体级，界面上也只在主体级 SKU 上给配置入口。
2. **截单时间必须早于到货时间**。否则货到了还能继续下单 —— 那批订单没有对应的采购。
   校验落在 `setSkuPresale`，与 mock 同一条规则。
3. **额度只能非负**。负额度会让 `sold_count + n <= presale_quota` 恒不成立，
   表现是「开了预售反而更买不了」。

### 4.4 平台模板的三条校验

| 校验 | 防住什么 |
|---|---|
| 每个选项必须有 `code` | 见 §2.5 —— 没有 code 的平台模板是个摆设 |
| 同一 `categoryType` 下模板名唯一 | 商家下拉里出现两个「重量」，选哪个都对不上 |
| 选项 `code` 组内唯一 | 两个 code 相同的选项，聚合时会把「500g」和「1kg」并成一个 |

平台模板的 `scope` **由后端写死**为 `PLATFORM`，请求体里的 scope 一律忽略 ——
否则运营端一个笔误就能造出归属不明的商家模板，而它会出现在**所有**商家的下拉里。

---

## 五、取舍记录

| 冲突 | 让了谁 | 为什么 |
|---|---|---|
| 「预售额度」该不该有独立权限码 | 让给复用 `product:sku:audit` | ops-web 的 `product:stock:update` 一直是**真翻译**（`perm-map.ts` / `NEAREST_CODE` 两处都映到 `product:sku:audit`）。新造一个后端码要同时改 `Perms.java`、`ROLE_PERMS`、两处映射表、库里的功能点 —— 而改预售额度的人与审商品的人本来就是同一拨（商品运营） |
| 规格模板该归类目权限还是商品权限 | 让给 `product:category:*` | 模板是**按类目预置**的，与类目树、资质码字典同一个维护面（`/ops/auth-codes` 也归在这里）。归商品权限的话，只有审核员能维护模板，而审核员不碰类目结构 |
| sku 粒度动作要不要改前端契约 | 让给前端 | 路径与形状照 `ops-web/lib/api/https/product.ts` 原样实现，后端去对齐。改前端会牵动 mock、页面与三份类型 |
| `presaleOnly` 是新增参数 | 让给新增 | 见 §4.2。这是**加**一个可选参数，既有调用一个字节不用改 |
| 超卖后要不要自动关单 | 让给只报不处置 | 补货还是退单要人判断。自动关单会把还能补上的团也关掉，而那批订单已经收了钱 |

---

## 六、待确认

1. **预售的 `arrive_at` 谁来填**。现在它只在 `setSkuPresale` 里做「截单必须早于到货」的校验基准，
   由运营手填。真正对齐履约批次（P-5.1.1）要等批次域把到货批次做成主数据，
   那时应该改成从批次带出来，而不是两处各填一遍。
2. **预售订单的履约路径**。本次只解决「能不能卖」，预售单与现货单在履约上目前完全一样。
   次日现采到货后要不要单独一个「预售到货确认」动作，等 P-5.1 落地后再定。
3. **模板与类目的绑定粒度**。现在按 `category_type`（五品类）预置，
   与 B-4.4 商家侧查询的轴一致。若以后要精确到三级类目节点，
   要给 `prd_spec_template` 加 `category_no` 并决定两个轴的优先级。
