# TDD-一期主数据收敛（门店分类 / 经营范围 / 商品分类）

状态：**阶段一 + 阶段二已实现**（2026-08-11，迁移 `V22`）· 阶段三待 EDI
关联需求：[入驻进件建店](../requirements/入驻进件建店-需求与四层对齐.md) §2.2 · [商户主数据与门店-需求](../requirements/商户主数据与门店-需求.md) · [商家全流程功能矩阵](../requirements/商家全流程功能矩阵.md) §5
关联决策：[ADR-012](./ADR/ADR-012-小程序类目与经营模式选择.md)（自营过桥）· [ADR-009](./ADR/ADR-009-商家经营范围三档模型.md) · [ADR-010](./ADR/ADR-010-主数据模型.md)
创建日期：2026-08-11

---

## 0. 已定稿的三个口径

| # | 决定 | 影响 |
|---|---|---|
| 1 | `FRESH_VEG`/`FRESH_FRUIT` 的资质文案改为**「营业执照（食用农产品）」** | 一期果蔬走初级农产品口径，不需要食品经营许可证。改 `V5` 既有数据 |
| 2 | 「资质挂三级」的约定改为**「资质挂叶子节点」** | 服务类目只有两级，不硬造三级来对齐 |
| 3 | 微信**主营类目定「商家自营 → 日用百货」** | 零资质、过审快；主营类目定后一段时间不可改 |

## 1. 需求摘要

一期以**自营模式**上线微信小程序（ADR-012 B 方案）：平台是销售者，商家是供应商。因此**商家能选的行业、能覆盖的范围、能上架的类目，全部不能超出平台自己营业执照的经营范围**——超出一件，违法的是平台。

三套字典收敛到执照能覆盖的子集，且必须满足一条长期要求：

> **运营端看全量、按开关设置；B/C 端只看启用的。将来切平台模式（A 方案）时，只在后台放开开关，不改代码、不发版。**

验收标准：

1. 入驻表单的「行业」下拉只出现平台执照能覆盖的行业
2. 「经营范围」不出现一期没有商品形态支撑的档位，且**非法值写不进库**
3. 商家建品时的类目树，每个叶子都能在执照里找到对应表述
4. 停用不影响存量商家（[需求](../requirements/商户主数据与门店-需求.md):39 已定：停用不是撤销资质）
5. **运营在 ops-web 能看到全量（含停用）并自行开关**，无需工程介入

## 2. 现状 review（商家注册 / 门店管理 / 商品管理）

### 2.1 三个环节的链路是通的

| 环节 | 关键落点 | 结论 |
|---|---|---|
| **商家注册/入驻** | `OpsServiceImpl:247` 建申请单 · `:303` 小微行业白名单校验（`industryGated`）· `MasterDataServiceImpl.snapshot()` 下发可选项 | ✅ 行业与法律形态的联动是对的 |
| **门店管理** | `MerchantStoreServiceImpl.save():71` · 经营范围**刻意留在主体**（`MchStore` 注释，ADR-011） | ⚠️ 见 D1 |
| **商品管理** | `MerchantGoodsServiceImpl.requireCategoryAuthorized():341` 在**上架**而非保存时卡 `requiredCode` | ✅ 已实现 |

`snapshot()` 已经按 `enabled=true` 过滤，所以**行业一停用就自动从入驻下拉消失**，不需要额外改端上。这条是本方案能成立的基础。

### 2.2 运营端已有的维护面

| 字典 | ops 端点 | 全量？ | 可设置？ |
|---|---|---|---|
| 行业 `sys_industry` | `GET /ops/industries` + `/{industry}/enabled` `/micro-allowed` `/points-forced` | ✅ 含停用，**带 `merchantCount`** | ✅ |
| 类目 `prd_category` | `GET /ops/categories?showArchived` + `save` + `archive` | ✅ | ✅ |
| 授权码 `sys_auth_code` | `GET /ops/merchants/auth-codes` | ❌ 过滤 `enabled` | ❌ 只读 |
| 经营范围 `SERVICE_SCOPE` | —— | ❌ 无 | ❌ 无 |

行业页那个 `merchantCount` 正是这套设计的样板：**改准入之前要知道影响多少家店**，不带计数的停用是盲操作。后面新增的两个面按同一标准做。

### 2.3 review 发现的五个缺陷

| # | 缺陷 | 后果 | 本次 |
|---|---|---|---|
| **D1** | `serviceScope` **没有值域校验**。`MerchantStoreServiceImpl:71` 与 `OpsServiceImpl:247` 都是「为空给默认，非空原样存」 | 不只是 `PLATFORM` 拦不住——传 `"ABC"` 也能写进库，之后按范围查商品会静默漏掉这家店 | 修 |
| **D2** | `CAT300 生活服务` 把 `required_code=SERVICE_REPAIR` 挂在**一级** | 家政会被家电维修资质卡住——一期唯一要上的服务品类，被一张它不需要的证挡住 | 修 |
| **D3** | 授权码 `listCodes()` 过滤 `enabled=true`，且**没有增删改端点** | 运营看不到停用的码，也无法新增。一期要加 `PACKAGED_FOOD`/`HOUSEKEEPING` 只能改代码——**与「升级不用调整」的目标直接冲突** | 修 |
| **D4** | 演示商品直接挂一级类目（`ops-web/lib/mock/db/product.ts:64` 的 `CAT300`） | 停用/改造一级节点会留孤儿，与 `V6` 修过的同形状 | 修 |
| **D5** | [矩阵](../requirements/商家全流程功能矩阵.md):161 的 5.5 写「校验未接」 | 文档状态过期，代码里已接 | 改文档 |

D1 和 D3 都不是「一期要不要做」的问题：**D1 让第 2 条验收标准无法成立，D3 让第 5 条无法成立。**

## 3. 方案设计

### 3.1 总原则：一份数据，两个读口径

```
sys_industry / sys_auth_code / prd_category / sys_setting
                    │
        ┌───────────┴───────────┐
        ▼                       ▼
   ops 读口径                B/C 读口径
   全量（含停用）           只读启用
   + 影响面计数              过滤在 Service 层
   + 开关与审计
```

**过滤只在 Service 的读方法里做一次，不在端上做。** 端上做的话，一期为了收敛会在 B 端硬编码两个 scope、在 C 端硬编码几个类目，切平台模式时要逐个去找——这正是「升级还要调整」的来源。

### 3.2 门店分类 `sys_industry`（7 → 启用 2）

判据：**平台执照能不能卖这个行业的货**。

| 行业 | 一期 | 依据 |
|---|---|---|
| `RETAIL` 线下零售 | ✅ | 日用品、水果、蔬菜、预包装食品、茶叶 批发与零售 |
| `LIFE_SERVICE` 居民生活服务 | ✅ | 「家政服务」。**仅家政**——维修/洗衣执照没有，靠类目卡 |
| `CATERING` 餐饮 | ⛔ | 执照无餐饮服务、无热食制售 |
| `ENTERTAINMENT` / `TRANSPORT` | ⛔ | 执照无 / 一期不做 |
| `ONLINE` 线上虚拟 | ⛔ | 一期不上 `VIRTUAL` |
| `OTHER` 其他 | ⛔ | **自营下「其他」= 平台不知道自己在卖什么**。防的是运营图省事一律选它，把准入判断整个绕过去 |

手段：`UPDATE ... SET enabled = 0` 并改写 `remark`（该列的定义就是「为什么这个行业是这个准入结论」）。**运营端已有开关，切 A 方案时逐条打开即可。**

### 3.3 经营范围 `SERVICE_SCOPE`（3 → 启用 2）

| 档 | 一期 | 依据 |
|---|---|---|
| `COMMUNITY` | ✅ | 主力，自提点履约 |
| `CITY` | ✅ | 家政上门 |
| `PLATFORM` | ⛔ | 无履约半径的只有虚拟/卡券/自营快递品，一期一件都没有 |

**枚举不动**（值域不变，改了会动 `glossary.test.ts` 与端上契约）。改为两层：

1. **值域校验**（修 D1）：写入口校验 `scope ∈ SERVICE_SCOPE`，非法值直接拒。这一条与一期无关，是补一个本就该有的校验。
2. **启用白名单**：`sys_setting` 加一行

```
setting_key   = merchant.service-scope-enabled
setting_value = ["COMMUNITY","CITY"]
remark        = 一期自营：PLATFORM 无商品形态支撑（无虚拟/卡券/自营快递品）
```

两层分开是有意的：**值域是代码的事实，白名单是运营的决定**。混成一个的话，运营在后台把 `PLATFORM` 打开时会顺手获得「写入任意字符串」的能力。

### 3.4 商品分类 `prd_category` + `sys_auth_code`

一期类目树，每个叶子标出执照里的对应表述：

| 编号 | 层级 | 名称 | `required_code` | 执照依据 |
|---|---|---|---|---|
| `CAT100` | 1 | 食品生鲜 | — | |
| `CAT110` | 2 | 蔬菜 | — | |
| `CAT111` | 3 | 叶菜 | `FRESH_VEG` | 蔬菜 批发与零售 |
| `CAT112` | 3 | 根茎菜 | `FRESH_VEG` | 蔬菜 批发与零售 |
| `CAT120` | 2 | 水果 | — | |
| `CAT121` | 3 | 浆果 | `FRESH_FRUIT` | 水果 批发与零售 |
| **`CAT122`** | 3 | 常温水果 | `FRESH_FRUIT` | 水果 批发与零售 |
| **`CAT130`** | 2 | 预包装食品 | — | |
| **`CAT131`** | 3 | 粮油调味 | `PACKAGED_FOOD` | 预包装食品 批发兼零售 |
| **`CAT132`** | 3 | 休闲零食 | `PACKAGED_FOOD` | 预包装食品 批发兼零售 |
| **`CAT133`** | 3 | 茶叶 | `PACKAGED_FOOD` | 茶叶 批发兼零售 |
| `CAT200` | 1 | 日用百货 | — | |
| `CAT210` | 2 | 纸品清洁 | — | 日用品、卫生用品 |
| **`CAT220`** | 2 | 家居用品 | — | 日用品、家具、纺织品、陶瓷制品 |
| **`CAT230`** | 2 | 个护化妆 | — | 化妆品及卫生用品 |
| `CAT300` | 1 | 生活服务 | **清空**（修 D2） | |
| **`CAT310`** | 2 | 家政保洁 | `HOUSEKEEPING` | 家政服务 |
| `CAT400` | 1 | 卡券 | — | ⛔ `status=ARCHIVED`，执照无预付卡 |

`CAT310` 是二级带资质码——按 §0 定稿 2，约定改为**「资质挂叶子节点」**。

`sys_auth_code`：

| 码 | 一期 | 所需资质 | 说明 |
|---|---|---|---|
| `FRESH_VEG` | ✅ | **营业执照（食用农产品）** | 按 §0 定稿 1 改文案 |
| `FRESH_FRUIT` | ✅ | **营业执照（食用农产品）** | 同上 |
| `DAILY` | ✅ | — | 无门槛 |
| **`PACKAGED_FOOD`** | ✅ 新增 | 仅销售预包装食品备案 | |
| **`HOUSEKEEPING`** | ✅ 新增 | — | 家政无前置许可 |
| `FRESH_DAIRY` | ⛔ | | 冷链乳品要许可证 + 场所核查 |
| `FOOD` 熟食加工 | ⛔ | | 执照无，且要经营场所 |
| `SERVICE_REPAIR` | ⛔ | | 执照无维修服务 |

**没有的东西要显式说**：肉禽蛋水产、散装称重食品在一期类目树里**没有节点**。不是漏了，是执照覆盖不到（ADR-012 §4.2）。

### 3.5 对应的微信类目

| 微信类目 | 对应本树 |
|---|---|
| 商家自营 → 日用百货（**主营**，§0 定稿 3） | `CAT200` |
| 商家自营 → 生鲜果蔬 | `CAT110` `CAT120` |
| 商家自营 → 食品 | `CAT130` |
| 生活服务 → 家政 | `CAT310` |

4 个，在 5 个上限内。

### 3.6 运营端补两个面（修 D3 + scope 无面）

都进 `ops-web/app/system`（已有 `industry-tab.tsx`，同页加 tab），按行业页的标准做：**全量 + 影响面计数 + 必填 remark + 审计**。

**授权码 tab**

| 端点 | 说明 |
|---|---|
| `GET /ops/auth-codes` | **全量含停用**，带 `merchantCount`（多少家店持有）与 `categoryCount`（多少个类目在用） |
| `POST /ops/auth-codes` | 新建/更新（码、名称、所需资质文案、排序） |
| `POST /ops/auth-codes/{code}/enabled` | 启停，必填 remark |

现有 `GET /ops/merchants/auth-codes`（过滤 enabled）**保留不动**——那是「给商家授权时的可选项」，本来就该只给启用的。两个端点服务两个场景，不是重复。

> ⚠️ 停用一个码要拦：**还有类目在引用它就不许停**。否则那些类目会变成「要求一个不存在的码」，即永远拒绝所有人——`V5` 的注释里写过这个形状：「一个只会拒绝的校验比没有校验更糟」。

**经营范围 tab**

三档全量展示，开关写 `sys_setting`，带「当前有多少商家在用这一档」。停用某档不影响存量（同行业口径）。

### 3.7 变更清单

- `V22__phase1_master_data.sql`（当前最大 `V21`）
- `MerchantStoreServiceImpl` / `OpsServiceImpl` 的 scope 值域 + 白名单校验
- 新增 `OpsAuthCodeController` + Service 的增删改启停
- `ops-web/app/system` 加两个 tab
- 同步 `ops-web/lib/mock/db/product.ts`、`merchant.ts`（`V4` 定的规矩：编号与 mock 逐条对齐，不同步就会「mock 跑得通、连真库找不到类目」）
- 同步 `DevSeeder:206`
- 改 [矩阵](../requirements/商家全流程功能矩阵.md):161 的 5.5 状态（D5）

### 3.8 迁移顺序（不能反）

照 `V6` 的教训：**先改指，再停用**。

1. 挂在 `CAT300` 一级的商品改指 `CAT310`
2. 挂在待停用类目上的商品改指同级可用节点
3. 清 `CAT300.required_code`、置 `CAT400` 为 `ARCHIVED`（**不是另造一个 `INACTIVE`** —— `CategoryServiceImpl` 认的就是 `ARCHIVED`，归档时间与 `unarchive` 都只认它；多一个没人写入的状态值，运营端会看到一条「既不在售、又没有归档时间」的类目）
4. 最后停用行业与授权码

反了会留下 `category_no` 指向已停用节点的商品——既不为空又不属于任何可用类目，类目筛选与准入校验会一起漏掉它们。

## 4. 分阶段

| 阶段 | 内容 | 阻塞一期上线？ |
|---|---|---|
| **一** | `V22` 数据 + scope 值域校验（D1）+ 白名单读取 + `CAT300` 修复（D2）+ mock/Seeder 同步（D4） | ✅ 是 |
| **二** | 授权码 ops 维护面（D3）+ 经营范围 ops tab | ❌ 否，但**不做则切 A 方案要改代码** |
| **三** | 切平台模式：后台放开行业、`PLATFORM` 档、停用的授权码；补肉禽蛋水产类目 | 拿到 EDI 后 |

阶段二可以在一期上线后补，但**必须在切 A 方案之前完成**——否则「升级不用调整」这个目标就落空了，届时新增类目和放开授权码又要发一次版。

## 5. 测试策略

新增 `backend/shop-app/src/test/.../scenario/Phase1MasterDataTest.java`：

| # | 场景 | 断言 |
|---|---|---|
| 1 | 入驻可选行业 | `snapshot()` 只含 `RETAIL` `LIFE_SERVICE` |
| 2 | 存量商家行业被停用 | 商家详情仍正常返回（停用≠撤销） |
| 3 | 家政上架 | 有 `HOUSEKEEPING` 即可，**不再要求维修资质**（D2 回归） |
| 4 | 预包装食品上架 | 无 `PACKAGED_FOOD` → `CATEGORY_NOT_AUTHORIZED` |
| 5 | scope 值域 | 传 `"ABC"` 被拒（D1 回归） |
| 6 | scope 白名单 | `PLATFORM` 被拒；`COMMUNITY`/`CITY` 通过 |
| 7 | 类目树 | 不含 `CAT400`；每个 `required_code` 都挂在叶子上 |
| 8 | 迁移后无孤儿 | 无在售商品指向已归档节点 |
| 9 | ops 全量 | `GET /ops/industries` 与 `/ops/auth-codes` 返回**含停用**的行 |
| 10 | 停用码保护 | 仍有类目引用时停用该码 → 拒绝 |

已有 `CategoryTreeFlowTest` 会因节点增加受影响，一并核对（P6：不改断言语义，只补新增节点）。

## 6. 风险与注意事项

1. **一期没有肉禽蛋水产**，是 ADR-012 §5 取舍的直接后果。若一期必须卖，方案不成立，回到「体验版内测 + 等 EDI」。
2. **停用行业会影响 B 端行业下拉的回显**：存量商家的行业已停用时，编辑页要显示当前值而不是空白。
3. 微信类目审核每轮 1–7 天，被打回要重来；主营类目定后一段时间不可改。
4. §0 定稿 1 要改 `V5` 的既有数据（资质文案），属于修改已有主数据——**按 P6，改动只涉及展示文案，不动 `code` 与准入逻辑**，既有测试不受影响。

## 7. 实现任务

**阶段一** ✅ 2026-08-11
- [x] `V22__phase1_master_data.sql`（按 §3.8 顺序）
- [x] scope 值域校验 + 白名单：新增 `ServiceScopes`（值域）、`MasterDataService#assertServiceScopeAllowed`（值域 + 白名单）、`MasterDataPort` 同名方法；两个写入口（`MerchantStoreServiceImpl.save`、`OpsServiceImpl` 的建单与审核）都接上
- [x] 新错误码 `SERVICE_SCOPE_NOT_ALLOWED(70007)` + 三语词条
- [x] 同步 `ops-web` mock 两个文件 + `packages/shared` mock + `DevSeeder`
- [x] `Phase1MasterDataTest` 10 条
- [x] 核对 `CategoryTreeFlowTest`（资质文案）、`M9aOpsFlowTest`（停用行业不再下发）、`ops-web/governance.test.ts`（改用仍启用的 `PACKAGED_FOOD`）
- [x] 改矩阵文档 5.4 / 5.5（D5）

**阶段二** ✅ 2026-08-11
- [x] `OpsAuthCodeController` + `AuthCodeAdminService`（全量含停用、增改、启停、引用保护）
- [x] `OpsPlatformController` 加 `/ops/service-scopes/**` + `ServiceScopeService`（白名单）与 `ServiceScopeAdminService`（全量 + 计数）
- [x] 新增 `CategoryUsagePort`（merchant → product，只暴露计数）；`MerchantQueryPort` 加 `countByServiceScope` / `countByAuthCode`
- [x] `ops-web/app/system` 加两个 tab + types / contracts / https / mocks
- [x] `Phase1MasterDataTest` 补到 19 条

**阶段二的一处设计返工**：`ServiceScopeService` 最初把「读白名单」和「数商家」放在一个
Service 里，于是依赖链成了
`MerchantStoreService → MasterDataPort → MasterDataService → ServiceScopeService →
MerchantQueryPort → MerchantPortImpl → MerchantStoreService` —— 一个真实的环，Spring 直接起不来。
拆成 `ServiceScopeService`（白名单，写入路径用）与 `ServiceScopeAdminService`（全量 + 计数，叶子 bean）之后解开。
这与 `MerchantAuthCodeService`（发证可选项）和 `AuthCodeAdminService`（字典维护）是同一种拆法：
**同一份数据的两个受众，调用频次和依赖面都不一样，合在一起迟早出事。**

## 8. 实现中发现的问题（阶段一之外）

### 8.1 `gen-test-schema.py` 已修好，但**产物还没重新生成**

`schema-test.sql` 抬头写着「自动生成，勿手改」，而那个生成器一度**根本跑不通** ——
也就是说这份文件实际上已经在手工维护，抬头还宣称它是生成的。
这正是 [文档规范](../文档规范.md) §六「生成物不手改」要防的反面。

修掉的四个 bug（2026-08-11）：

| 症状 | 后果 |
|---|---|
| 不认 `DELETE FROM`（V6 就有） | 直接 `SystemExit`，生成器完全跑不动 |
| `ADD COLUMN ... AFTER x` 没剥掉 `AFTER` | 落进 `CREATE TABLE`，H2 建表即语法错，**整个 Spring 上下文起不来**，而报错指向一个毫不相干的 Controller |
| 新列插到 `PRIMARY KEY (id)` 之后 | 同上 |
| `ADD COLUMN IF NOT EXISTS` 未处理 | `IF NOT EXISTS` 被当成列名 |

外加一条口径修正：生成器原先把 `UPDATE` 一律当「作用于生产存量数据，H2 是空的」跳过。
那对业务数据成立，**对主数据不成立** —— `V22` 正是用 `UPDATE` 停用行业与授权码，
改的是 `V2`/`V5` 用 `INSERT` 灌进来的种子行，而那些种子就在这份产物里。

现在按**有没有 `JOIN` / 子查询**区分：单表常量条件的重放（改种子），带 JOIN 的跳过（回填存量）。
后者不只是「没意义」，`V16` 那条 `UPDATE ... JOIN` 是 MySQL 方言，**H2 直接语法错**。

验证方式：生成到临时文件后用 H2 的 `RunScript` 实跑一遍 ——

```bash
python3 backend/scripts/gen-test-schema.py /tmp/gen.sql   # 新增：可指定输出路径，先比对再覆盖
java -cp ~/.m2/.../h2-2.4.240.jar org.h2.tools.RunScript \
  -url "jdbc:h2:mem:x;MODE=MySQL" -user sa -script /tmp/gen.sql
```

当前产出：69 张表全部建起来，**与在用的 `schema-test.sql` 表集合、逐表列集合完全一致**。

> ⚠️ **`schema-test.sql` 本身还没重新生成**。原因是另一个会话正在手工往里补表
> （V25 的 `mkt_coupon_issue`），而重新生成会带来约 478 行的列顺序变动 —— 两边必然撞车。
> 等工作区安定下来再跑一次覆盖即可。

覆盖之后还有两件收尾：
1. `DevSeeder` 里那段 `disableIndustry` 可以删掉 —— 它是绕开「UPDATE 不重放」的权宜，
   根因修好后就多余了（留着不会错，只是两处都在做同一件事）
2. 值得加一条守卫：生成器产出与仓库里的文件必须一致。否则这份文件迟早再次悄悄变成手工维护

### 8.2 ⚠️ `gen:api` 会产出**非法 YAML**（`anyOf` 里的数组）

`npm run gen:api` 生成的 `openapi-ops.yaml` 解析不了：`Map keys must be unique; "type" is repeated`。
出问题的是 `MerchantCampaign.goodsNos`（`string[] | null`）：

```yaml
        goodsNos:
          anyOf:
            - type: "array"
              items:            # ← 空值
              type: "string"    # ← 应该缩进到 items 下，现在和上面的 type 同级
            - type: "null"
```

后果不是「文件难看」，是 **`spec-completeness.test.ts` 直接挂死**——它 parse 这三份 spec，
而 `yaml` 撞上重复键时不是快速失败，是卡住。症状看起来像「shared 测试跑不完」，
跟契约生成毫无关系。本次为此排查了很久。

已把三份 spec 退回 HEAD（它们是生成物，且这次重跑还混进了并行会话的在途改动）。
**修生成器之前不要再跑 `npm run gen:api`。**

### 8.3 生成的 OpenAPI 规格在仓库里是过期的

`api-align.py` 一度报「✗ 存在 1 条阻塞差异」，**跑一次 `npm run gen:api` 就绿了** ——
也就是说 `docs/api/openapi*.yaml` 与契约源已经漂了一段时间，只是没人重跑。

漂的不止本次涉及的 ops 规格：`openapi.yaml`（C 端）与 `openapi-b.yaml`（B 端）
各补进 43 行，内容是 `BizScope` 这类**与本次改动无关**的类型。

`POST /callback/pay/stub` 那条「域外路由」提示一直都在，但它不是阻塞项。

> 教训与 §8.1 是同一个：**生成物没人重跑，就会安静地变成手工维护的过期文件**，
> 而校验脚本报出来的差异指向的是最近改动的人，不是当初漏跑的人。

---
确认记录：§0 三项 2026-08-11 确认；阶段一方案 2026-08-11 确认并实现
