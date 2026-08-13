# B 端真链路联调纪要（2026-08-12）

状态：**已实现**（12 条缺陷全部已修并有测试；环境与遗留见 §四）
关联：[TDD-B端权限对接整改](./TDD-B端权限对接整改.md) ·
[TDD-B端员工与授权改版](./TDD-B端员工与授权改版.md) ·
[B端功能清单](../../requirements/B端功能清单.md)
创建日期：2026-08-12

---

## 一、这份纪要要防的事

前一轮 review 的结论是「**后端判对不等于用户能用**」。这一轮把 b-app 接上真后端
（8081 `api` profile / 8082 `ops` profile）逐页点过去，结论要再补一句：

> **mock 跑通不等于真链路跑通**。12 条缺陷里有 9 条在 mock 下完全正常，
> 因为 mock 是照着「页面希望的样子」写的，而后端是照着「业务本来的样子」写的。

所以这份纪要按**根因**分组，而不是按页面 —— 同一个根因今天各咬了两次，
下一次还会咬第三次，除非它被写下来。

---

## 二、四个根因，十二条缺陷

### 根因 1：保存是整份覆盖，读取却不整份返回 → 字段静默丢失

| # | 现象 | 修法 |
|---|---|---|
| 1 | **编辑一次商品，主图就没了**。保存无条件带 `cover`，而 `onLoad` 从不回填它 | 详情回填 `cover` |
| 2 | **用中文编辑一次，英文与阿语标题就没了**。详情不下发 `title_i18n`，页面只回填当前语言那一格 | `GoodsVO` 增 `titleI18n/subtitleI18n`（**只在 B 端下发**），页面整份回显 |

两条都**不报错**：主图变成 📷 占位（看着像本来没图），译文缺失时 C 端回落中文
（看着一切正常）。发现它们要等多市场那边的买家反馈，而那时没人说得清是哪次编辑弄丢的。

> 防复发：值得一条 round-trip 契约测试 —— `保存 → 读取 → 原样保存 → 字段不变`，
> 对所有可编辑字段跑一遍。**未做**。

### 根因 2：mock 与后端在「失败/状态长什么样」上分岔

| # | 现象 | 修法 |
|---|---|---|
| 3 | **核销失败被显示成「核销成功」**。后端把失败当业务结果回（200 + code 0 + `success:false`），mock 用抛异常表达失败，端上照 mock 写「try 到底就是成功」 | `mVerify` 返回 `VerifyResult`；**mock 也改成同形返回** |
| 4 | 核销台「待核销」列表**永远是空的**（头部计数却是对的）：按 `status === "ARRIVED"` 过滤，那是 mock 的主单口径，子单根本没有这个取值 | 按后端 `doVerify` 的口径过滤；计数改从同一份列表算 |
| 5 | 分拣页「备货中」同上（按 `PAID` 过滤）；到货登记还把子单上不存在的 `orderNo` 当参数发 | 按 `WAIT_FULFILL` 过滤，发子单号 |

**字段名分岔会被类型挡住，失败形态与状态口径的分岔不会。**
契约同步纠正：`mPickupOrders → PickupOrder[]`、`mVerify → VerifyResult`，
新增具名 `SubOrderStatus`（主单管钱、子单管货 —— 端上此前没有这个类型，
才会拿主单状态去比子单）。

### 根因 3：同一件事算两遍，两处迟早分岔

| # | 现象 | 修法 |
|---|---|---|
| 6 | **`/biz/context` 不下发自定义角色的权限**：判权走 `permsByStore`，而这个端点自己按 `BizPerms.of(staffRoles)` 又算一遍（那张表只认预置角色）。后端放行、界面什么入口都不显示 | 新增 `BizContext.effectivePerms()`，下发与判权同一个来源 |
| 7 | 工作台「顾客还搜不到你」**永远消不掉**：店铺页写的是 `serviceAreas`（新模型），工作台读的是 deprecated 的 `serviceCommunityNos` | 按新模型判，老数据回落旧字段 |
| 8 | 核销台头部「1 待核销」与列表「当前没有待核销的订单」并排 | 见 #4 |

第 7 条比「少一个提示」更糟：它旁边那条「还不能收款」是**真的**，
一条永远消不掉的红字挂在真警报旁边，结果是两条一起被无视。

### 根因 4：状态/口径只在纸面上统一

| # | 现象 | 修法 |
|---|---|---|
| 9 | 枚举登记表写着「AUDITING→PENDING 已归一」，实测**只归在端上**：后端一直发库列原值 `AUDITING`，ops-web 在 http 层偷偷映射了一次，b-app 照 `AUDITING` 写判断 | 后端改发 `PENDING`（库里那列不动）；筛选两个词都收；`MerchantGoodsStatus` 并回 `GoodsStatus` |
| 10 | **审核中的商品显示成「已下架」，还给一个永远点不动的「上架」**（后端必拒 70003） | 按四态渲染，审核中/已驳回不给上架按钮 |

### 其余四条（各自独立）

| # | 现象 | 修法 |
|---|---|---|
| 11 | **登录过期被说成「这页不归你管」**。`loadScope` 把 401 和「没授权」当成同一件事吞掉 | 401 显式退出并回登录页。**跳转试了三次才稳**：`navigateTo` → `reLaunch` → `setTimeout + reLaunch`（前两版在 App 启动那一刻会被丢掉） |
| 12 | **刷新在商品页，门店切换条整条消失**：`stores` 只有首页会拉，而当前门店号还在本地存着照发 —— 页面显示的是另一家店的库存，界面上却没有一处说明 | 补 `ensureStores()`，与当初给 perms 补 `ensureScope()` 同一位置 |
| 13 | 建店超额报「请求参数有误」（10400）—— 店主会回去反复改名字，而他要做的是升套餐 | 新增 70020 + 文案；名字为空仍是 10400，两者分开钉住 |
| 14 | 按店库存的代价没告知：任意一家店设过，该 SKU 就整体转按店算，没设的店按 0。实测在新店设 5 件，**主店 80 件当场变成 0** | 提示补上后半句 |
| 15 | 员工列表里**老板那一行是空白**（他的 `login_phone` 为空，走 C 端账号登录） | 姓名回落「老板」 |
| 16 | 运营端商家列表联系人渲染成字面量 `null null` | 两个字段都空时显示 `-` |

> 编号到 16 是因为按根因分组时有几条合并计数；实际提交 8 个 commit。

---

## 三、真链路怎么起（下次直接照抄）

```
后端 api： mvn -o -f shop-app/pom.xml spring-boot:run \
             -Dspring-boot.run.arguments='--server.port=8081 --spring.profiles.active=api --shop.seed.enabled=true'
后端 ops： 同上，--server.port=8082 --spring.profiles.active=ops
b-app：   PORT=5174 npm run dev:h5      c-app： PORT=5173      ops-web： PORT=3100
```

三个坑：

1. **改了 `shop-core`/`shop-base`/`shop-merchant` 要 `mvn install` 那个模块**，
   否则 `spring-boot:run` 用的是 `.m2` 里的旧 jar —— 表现是「代码改了、接口没变」。
2. **OTP 从后端日志读**（`[DEV-ONLY] otp for …`）。所以启动命令要重定向到一个你读得到的文件。
3. **孤儿 JVM**：`mvn` 被 kill 后 forked 的 JVM 仍占着 8081，它跑的是旧构建。
   本次据此误判过一次状态。查：`lsof -nP -iTCP:8081 -sTCP:LISTEN`。

支付走 stub 回调（`/callback/pay/stub`，profile `api`）：

```
curl -X POST localhost:8081/callback/pay/stub -H 'Content-Type: application/json' \
  -d '{"outTradeNo":"SO…","transactionId":"TXN-1","sign":"stub-secret"}'
```

---

## 四、遗留

- **没走过的**：发货（自送/快递）、分拣页的「标记到货」、售后、收款进件、
  营销/团/报价、评价回复，以及六个角色在真链路的读写边界（只验了店员的商品页）。
- **两条守卫未做**：round-trip 字段丢失（根因 1）、共享状态必须挂 App 壳（根因 3 的 #12）。
- dev 库里留着：`M0001` 的 owner 指向联调账号、5 个测试员工（六角色样本，有用）。

---

## 五、留给权限点那条链路的一条（2026-08-13，dev 库当前卡住）

**现象**：后端 `api` profile 起不来，Flyway 停在 V74。

```
Migration to version "74 - perm backend only points" failed!
SQL State 23000 / 1062: Duplicate entry 'ACT__AFTERSALE_REFUND_READ' for key 'uk_point'
Location: db/migration/V74__perm_backend_only_points.sql  Line 15
```

**已经查到的**：那两条功能点（`ACT__AFTERSALE_REFUND_READ` /
`ACT__COMMUNITY_REGION_READ`）**库里已经有了**，内容与脚本要插的一字不差 ——
说明 V74 之前跑过一次、插到一半失败，`flyway_schema_history` 里那行现在是
`success = 0`。于是每次启动都重跑、每次都死在同一行。

**根因是脚本内部两套写法不一致**：第 41 行往后的 `sys_role_point` 插入都写了
`WHERE NOT EXISTS`（可重入），而第 15–16 行的 `sys_function_point` 插入是裸 `INSERT`。
迁移**只要有任何一步可能失败，它就必须整体可重入** —— 否则第一次失败之后，
库就进入一个「脚本改对了也起不来」的状态，而报错指向的是那条无辜的插入。

**两步解**（都要做，顺序不能反）：

1. 把第 15–16 行改成与下面同样的 `WHERE NOT EXISTS`（或 `INSERT IGNORE`）；
2. 清掉失败记录再重跑：`flyway repair`，或直接
   `DELETE FROM flyway_schema_history WHERE version='74' AND success=0;`

> ⚠️ `flyway_schema_history` 是全库共用的状态，dev 机上有几个会话同时连着 —— 
> 改它之前先喊一声。B 端真链路（发货、售后）在这条修好之前跑不了：
> `api` 侧起不来，c-app 与 b-app 都连不上。
