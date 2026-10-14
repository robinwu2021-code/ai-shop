# TDD-商品属性维度补全（预包装食品合规组 + TEXT 自由文本维度）

状态：已实现
关联需求：docs/requirements/PRD-商品快速录入-压缩包与文字识别.md（本 TDD 是其 AC11/AC13「识别→落到维度」的前置依赖）
          + 会话内《商品展示字段对标淘宝/拼多多》缺口分析
创建：2026-10-05

档位：**1** · 依据：契约只动三处——`SpecTemplateVO`/`SpecTemplate` 加 `valueType` 字段（对外 JSON）、
新种子迁移（数据，非库表结构）、新 `value_type=TEXT` 常量。
**复用**既有 product/spec-library 域与 `prd_spec_dim`/`prd_spec_value`/`prd_category_spec` 三张表，
无新域、无新表族、无新端点、无新权限码、无不可逆决策，故不是档 2。
产出：本文件 + 迁移 + 两端改动 + 测试。

> **跨端说明**：本任务在 B 端加录入、C 端复用既有展示，两端都只是**扩展既有 params 链路**
> （C 端 `goods/index.vue` 已通用渲染 `params`，B 端 `goods-edit` 已有参数区），
> 不是新拉一条跨端通道，仍按档 1 做足三处对账。

---

## §0 对账一 · 需求 → 设计

| AC | 需求（一句话） | 落点 | 期 |
|---|---|---|---|
| AC1 | 新增 6 个 PROP 维度：品牌 · 净含量 · 配料 · 厂名/厂址 · 生产许可证(SC) · 执行标准 | 迁移 `INSERT prd_spec_dim`；`PrdSpecDim.TEXT` 常量 | P1 |
| AC2 | 配料/厂名厂址/SC/执行标准/净含量这类**每件唯一**的字段走 TEXT：**不入平台值池**，label 直落商品 | `value_type=TEXT`；B 端走文本输入不调 `mAddSpecValue` | P1 |
| AC3 | 品牌走 ENUM（可聚合、买家可筛），池起步为空、商家填了即入池；universal 全类目可加 | 维度 `value_type=ENUM universal=1`；复用现有「加可选值」入池路径 | P1 |
| AC4 | 预包装食品类目默认带出这几项参数（不用商家手动加） | 迁移 `INSERT IGNORE prd_category_spec` 绑 CAT131/132/133/160/710/720/730/750 | P1 |
| AC5 | `value_type` 下发到端上，端上据此分流「选值 chip」与「文本输入」 | `SpecTemplateVO.valueType` + 共享 `SpecTemplate.valueType` | P1 |
| AC6 | B 端 TEXT 维度录入：填一行字直接成为参数 label，保存进 `prd_goods.params` | `params.ts#setParamText` + `index.vue` 参数区 TEXT 分支 | P1 |
| AC7 | C 端商品详情展示这些参数（复用既有 params 渲染，不新做组件） | `c-app/pages/goods/index.vue` 既有 `params` 循环，无需改 | P1 |

**孤立项**：无 AC 没落点；无落点挂不上 AC。

> **不做（本期显式排除，避免范围蔓延）**：
> - C 端参数**分组展示**（食品合规组/生鲜体验组的分组标题）——现有扁平列表已能显示，分组是纯展示增强，留二期。
> - 产季 `SD_SEASON`、等级已存在 `SD_GRADE`——本期不新增生鲜维度，生鲜保质期/储存/口感 V349 已绑。
> - 运营端维度管理界面——`OpsSpecLibraryController` 已能管，TEXT 维度靠迁移落种子即可。

## §1 现状与影响面

- **维度模型**：`prd_spec_dim`（`value_type` ∈ {ENUM, QUANT}，本期加 TEXT）、`prd_spec_value`（枚举/量纲值池）、
  `prd_category_spec`（类目→维度绑定，`usage_type=PROP`）。
- **候选参数怎么到端上**：`SpecLibraryServiceImpl#propsForCategory`（=B 端 `mSpecProps`）只给**绑定到该类目**的维度；
  `pickableProps`（「添加参数」面板）另给 universal 平台维度 + 本店自建。
- **保存**：`MerchantGoodsServiceImpl` 第 ~903 行，params 只按 `label` 非空过滤后整存 JSON，**不校验 valueNo**——
  即「只有 label、无 code/valueNo」的自由文本参数**存储层本就支持**（这正是 TEXT 不入池能成立的原因）。
- **会被改到**：`PrdSpecDim`（+常量）、新迁移、`SpecTemplateVO`（+字段，5 处构造点）、
  `SpecLibraryServiceImpl`（3 处构造）、`MerchantGoodsServiceImpl`（1 处构造）、
  `packages/shared/src/types/product.ts`、`b-app/pages/goods-edit/{params.ts,index.vue}`、b-app i18n。
- **明确不受影响**：`prd_goods` 表结构（不加列）；C 端展示（通用渲染）；运费/规格/SKU 链路；权限码；端点。

## §2 方案

### 契约变更
- 端点：**无**。
- 权限码：**无**（复用 `BizPerms.GOODS`）。
- 库表：**无结构变更**；新种子迁移 `V374__prop_dims_food_compliance.sql`（迁移号用前先查撞车）。
- i18n（端上）：B 端 TEXT 输入占位词条 `goods.paramTextPlaceholder`（三语）。
- 对外 JSON：`SpecTemplateVO` + 共享 `SpecTemplate` 加 `valueType?: "ENUM"|"QUANT"|"TEXT"`。

### 模块设计
| 动作 | 路径 | 说明 |
|---|---|---|
| 修改 | `product/entity/PrdSpecDim.java` | `+ public static final String TEXT = "TEXT";` |
| 新增 | `db/migration/V374__prop_dims_food_compliance.sql` | 6 维度 `INSERT`；品牌 `universal=1 value_type=ENUM`；其余 TEXT/净含量 TEXT；`INSERT IGNORE prd_category_spec` 绑 8 个食品类目（usage_type=PROP） |
| 修改 | `product/dto/SpecTemplateVO.java` | 第 9 个字段 `String valueType`；5 处构造点补 `dim.getValueType()`（legacy 模板那处传 `null`） |
| 修改 | `SpecLibraryServiceImpl.java` | 3 处 `new SpecTemplateVO(...)` 带 valueType |
| 修改 | `MerchantGoodsServiceImpl.java:3630` | 该处来自 legacy `prd_spec_template`，传 `null` |
| 修改 | `packages/shared/src/types/product.ts` | `SpecTemplate.valueType?` |
| 修改 | `b-app/pages/goods-edit/params.ts` | `+setParamText(dim, text)`：TEXT 维度直接 `paramValues[dimNo]={dimNo,name,label}`（**无 code、不调 mAddSpecValue**）；`isTextDim(d)` |
| 修改 | `b-app/pages/goods-edit/index.vue` | 参数区：`valueType==='TEXT'` 渲染 `input`，其余照旧 chip 选择 |
| 修改 | `b-app` i18n（zh/en/ar） | `goods.paramTextPlaceholder` |

### 关键取舍（§3 的核心一条，不另开 ADR）
**TEXT = 不入池**。配料/厂名厂址/SC/执行标准每件商品几乎唯一，入 `prd_spec_value` 只会让值池堆满永不复用的唯一串，
而养这个池的唯一理由是跨店聚合——唯一串聚不起来。所以 TEXT 维度的值**只作为快照存在 `prd_goods.params[].label`**，
不拿 code、不进池。品牌反过来：长尾但高度可聚合、买家要筛，仍走 ENUM 入池。
判据：**「这个字段的值，另一家店会不会填出同一个、且买家想按它筛？」** 会→ENUM/QUANT 入池；不会→TEXT。

### 关键接口
```ts
// params.ts
function isTextDim(d: SpecTemplate): boolean;          // d.valueType === "TEXT"
function setParamText(dim: SpecTemplate, text: string): void;  // 直接落 label，不入池
```
```java
// PrdSpecDim
public static final String TEXT = "TEXT";              // 既有 ENUM / QUANT 之外
// SpecTemplateVO 末位 +
String valueType                                       // ENUM|QUANT|TEXT；null 视同 ENUM
```

## §3 选型（档位 1，择要）

| 决策 | 选 | 弃 | 理由 |
|---|---|---|---|
| 自由文本放哪 | **TEXT 维度 · label 落商品不入池** | 全走现有 ENUM/QUANT 入池 | 唯一串入池只污染不聚合（见上「关键取舍」） |
| 品牌 | **ENUM universal 入池** | TEXT | 可聚合、买家要按品牌筛，长尾不是不入池的理由 |
| 默认可见怎么实现 | **绑类目**（prd_category_spec） | 只 universal | universal 只进「添加参数」面板要手动点；合规项必须默认带出 |
| 绑哪些类目 | 预包装食品 8 个二级类目 | 全类目 | 配料/SC/执行标准只对预包装食品成立；生鲜 CAT110/120 另有维度且 V349 已绑 |

## §4 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 迁移号撞车（并行会话） | 本地不报、线上起不来 | 落盘前 `ls` 查最大号；本机 clean package 过一遍 |
| 绑的 CAT 码与线上对不上 | 线上不带出参数 | CAT 码由迁移确定性种下，各环境一致（本地≠线上的是启用/数据，非码）；绑二级（forCategory 会从子类目回溯父级） |
| 新字段 `valueType` 漏改某处构造点 | 编译红或端上恒走 chip | 5 处构造点逐一改；vue-tsc + 后端编译兜底 |
| TEXT 误走入池路径 | 值池污染 | AC6 消融：断言 `mAddSpecValue` **未被调**；走回入池则红 |

## §5 对账三 · 实现 → 需求（测试，实现时填）

| AC | 测试方法 | 跑过 | 消融 |
|---|---|---|---|
| AC1/AC2/AC3 | `SpecLibraryPropDimsTest#foodComplianceDimsSeeded` | ✅ 3/3 绿 | ✅ 配料 TEXT→ENUM：`foodComplianceDimsSeeded` 与下发断言各红一处 |
| AC4 | `SpecLibraryPropDimsTest#propsForCategoryCarryValueType`：`propsForCategory(m,"CAT130")` 含 6 项 | ✅ | （与 AC5 同测） |
| AC5 | 同测断言配料项 `valueType()==TEXT` | ✅ | ✅ 消融里配料改 ENUM 时此断言同样变红 |
| AC6 | b-app `tests/param-text-dim.test.ts`（3 例：isTextDim 判定 / 落 label 不带 code 不调 mAddSpecValue / 空白=删） | ✅ 3/3 绿 | 断言 `mAddSpecValue` `not.toHaveBeenCalled()`——走入池路径即红 |
| AC7 | C 端 `goods/index.vue` 既有 params 循环通用渲染，无需改动（params[].label 原样显示）；vue-tsc b-app 绿 | ✅ 无需新增 | —— |

消融每条必做：撤实现 → 对应测试变红。

## §6 对账二 · 设计 → 实现

实现落点与 §2 逐行对得上（`git diff --stat`，只列本任务文件）：

```
后端：
 product/entity/PrdSpecDim.java              +9    （+TEXT 常量）
 product/dto/SpecTemplateVO.java             +10/-1（+valueType 字段）
 product/service/impl/SpecLibraryServiceImpl.java   +9/-6 （4 处构造带 valueType）
 product/service/impl/MerchantGoodsServiceImpl.java +2/-1 （legacy 构造传 null）
 product/api/biz/BizGoodsController.java     +4/-1 （自建维度构造传 ENUM）
 db/migration/V374__prop_dims_food_compliance.sql   新增 91 行（6 维度 + 7 类目×6 绑定）
 shop-app/.../schema-test.sql                +62   （gen-test-schema 重生成）
 shop-app/.../arch/SpecLibraryPropDimsTest.java     新增 91 行（3 用例）
前端：
 packages/shared/src/types/product.ts        +7    （SpecTemplate.valueType?）
 b-app/.../goods-edit/params.ts              +26   （isTextDim / setParamText）
 b-app/.../goods-edit/index.vue              +17/-1（TEXT 分支 input + 既有 chip 块 v-else）
 b-app/src/i18n/locale/{zh-CN,en,ar}.ts      各 +1 （paramTextPlaceholder）
 b-app/tests/param-text-dim.test.ts          新增（3 用例）
生成物（随源码重生成，与 §2「i18n/对外 JSON」对应）：
 docs/api/openapi{,-b}.yaml                  各 +7 （SpecTemplate.valueType schema）
 docs/api/API详情-B端.md                      +4   （valueType 行 ×4 处 SpecTemplate 表）
 docs/technical/reference/{中英文对照-词条,中英文对照-实体与字典,静态常量清单,glossary.json}
```

**偏差**：§2 说「5 处构造点」，实际是 **6 处**——`BizGoodsController#addSpecDim` 有一处
跨行构造，首轮 grep 漏了（编译器当场报出，已补）。另：C 端展示（AC7）§2 预判「无需改」，
实测属实，C 端一行未动。

## §7 偏差说明

（实现中与本设计不一致处写这里，先改文档再改代码）
