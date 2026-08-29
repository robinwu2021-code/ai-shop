# 数据库可移植性：MariaDB → MySQL

状态：生效中（2026-08-28 拍板「将来一定会切到 MySQL」后建立）
关联：[商品规格参数-模型与查询性能](./商品规格参数-模型与查询性能.md)、`scripts/check-sql-portability.mjs`

---

## 0. 这份文档管什么

拍板之后，**所有新写的表结构、SQL 与方案都必须在 MariaDB 与 MySQL 8 上都能跑**。
这份文档存三样东西：

1. 存量到底有多少不兼容（审计结果，有数）
2. 从今往后的规矩（**由闸门强制**，不靠自觉）
3. 存量怎么收尾（不是靠改历史迁移 —— 那条路走不通）

---

## 1. 审计结果：现在有多少东西切不过去

扫 211 个 Flyway 迁移 + 全部 Java 手写 SQL：

| 问题 | 命中 | 严重度 |
| --- | --- | --- |
| **`utf8mb4_uca1400_ai_ci` 排序规则** | **160 张表，100%**；109 处出现在 36 个迁移文件 | **致命** —— MySQL 上一张表都建不起来 |
| `ADD COLUMN IF NOT EXISTS` | 10 个文件 | 高 —— MySQL 语法错误 |
| `CREATE INDEX IF NOT EXISTS` | 1 个文件 | 高 |
| `DROP INDEX IF EXISTS` | 1 个文件 | 高 |
| Java 侧手写 SQL 的方言 | **0 处** | — |

**Java 侧零方言是个好消息**，而且不是运气：读写都走 MyBatis-Plus 的 lambda 查询，
生成的是两边都认的 SQL。真正的债全在 DDL 里。

### 排序规则为什么是致命的那一条

`utf8mb4_uca1400_ai_ci` 是 MariaDB 11.4.2 起的默认排序规则，基于 UCA 14.0.0。
**MySQL 没有这个名字**，建表直接报 `Unknown collation`。

MySQL 8 的对应物是 `utf8mb4_0900_ai_ci`（UCA 9.0.0）—— 但那个 MariaDB 又没有。
**两边都有的是 `utf8mb4_unicode_520_ci`**（UCA 5.2.0），已在生产库上确认存在。

> 排序规则不只是「字符怎么存」，它决定**字符串比较与排序的语义**：
> 大小写敏不敏感、重音算不算、`'a' = 'A'` 成不成立。
> 换排序规则会改变 `WHERE name = ?` 的命中集合与 `ORDER BY name` 的结果顺序，
> 也会让唯一键的冲突判定跟着变 —— 所以它必须当一次**行为变更**来做，不是格式调整。

---

## 2. 从今往后的规矩（闸门强制）

`scripts/check-sql-portability.mjs`，已挂 pre-push。它**两个方向都查**：

- **MariaDB 独有** → 切 MySQL 那天会炸
- **MySQL 独有** → 今天就炸（生产跑的是 MariaDB）

| 不要写 | 改成 | 为什么 |
| --- | --- | --- |
| `utf8mb4_uca1400_*` | `utf8mb4_unicode_520_ci` | MySQL 没有 |
| `utf8mb4_0900_*` | 同上 | MariaDB 没有 |
| `CREATE INDEX IF NOT EXISTS` | 去掉 `IF NOT EXISTS` | 迁移只跑一次，幂等靠 Flyway 版本号 |
| `DROP INDEX IF EXISTS` | 去掉 `IF EXISTS` | 同上 |
| `ADD/DROP/MODIFY COLUMN IF (NOT) EXISTS` | 去掉 | 同上 |
| `INSERT ... RETURNING` | 先写后读 / `LAST_INSERT_ID()` | MySQL 没有 |
| `CREATE SEQUENCE` / `NEXTVAL()` | `AUTO_INCREMENT` 或项目的 `BizKey` | MySQL 没有 |
| 生成列的 `PERSISTENT` | `STORED` | 两边都认的拼法 |
| `x MEMBER OF (json)` | `JSON_CONTAINS(json, x)` | MariaDB 12.2 实测没有 |
| `CAST(... AS ARRAY)`（多值索引） | 倒排子表 | MariaDB 报 `ERROR 4161`；且[实测倒排表更快](./商品规格参数-模型与查询性能.md#44-四个问题的直接回答json-能不能建索引--有没有必要--能不能用冗余字段) |

### 两边都能用、可以放心用的

实测于 MariaDB 12.2（生产 12.3）与 MySQL 8 文档：

`JSON_VALUE` · `JSON_EXTRACT` · `JSON_CONTAINS` · `JSON_OVERLAPS` · `JSON_TABLE` ·
`JSON_ARRAYAGG` · `JSON_SCHEMA_VALID` · 生成列（`VIRTUAL` / `STORED`）+ 索引 ·
CTE · 窗口函数 · `CHECK` 约束 · `INSTANT` 加列

> `JSON_SCHEMA_VALID` 两边都有，这一条后面用得上（§4）。

### 基线：`backend/known-sql-dialect.txt`

存量 50 条（文件 × 规则）全部进基线。**它不是待办清单，是止血线** ——
每一条都改不得：已应用的 Flyway 迁移动一个字符 checksum 就对不上，线上起不来。

基线支持「陈行检查」：某个文件不再命中却还留在名单上，`--check` 会红。
不然名单会慢慢变成一张过期的免检单。

---

## 3. 存量怎么收尾

**不能改那 36 个迁移文件。** 那不是保守，是硬约束：Flyway 按文件内容算 checksum，
改了之后已经跑过这条迁移的环境（生产、每个人的本地库）启动即失败。

正确的做法是**一条新的迁移**，把现有 160 张表转过去：

```sql
-- 形如（真做的时候要按表生成，并分批）
ALTER TABLE prd_goods CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_520_ci;
```

### 现在做，因为这是最便宜的时刻

生产库现在 **160 张表 / 66 万行 / 253 MB**。`CONVERT TO CHARACTER SET` 会**重建表**，
成本随数据量线性增长。今天做是几分钟的事；等商品到百万级、订单到千万级再做，
就是一次要停机窗口的大动作。

**这件事要单独排期、单独一个工单**，不夹在别的改动里，因为：

1. 它改的是**比较语义**（§1 末尾），要先回答「有没有依赖大小写不敏感的地方」；
2. 唯一键的冲突判定会变 —— 转换过程中可能撞出「重复键」而失败，
   必须**先在一份生产快照上试跑一遍**，把会撞的表找出来；
3. 转换期间那张表是锁住的。

> ⚠️ **不要顺手做。** 这一段写在这里是为了说清「该怎么做」，不是「现在就做」。
> 先有工单、先有快照试跑、先有回滚方案。

### 切换那天的其余事项

| 项 | 说明 |
| --- | --- |
| `information_schema` 差异 | 运维脚本里查表结构的地方要复核（`table_rows` 等统计列口径不同） |
| `AUTO_INCREMENT` 重启行为 | MySQL 8 之前重启会重算，8.0 起持久化；MariaDB 仍会重算。依赖连续性的地方要查 |
| 时间函数精度 | 两边 `NOW()` 的默认精度一致，但 `CURRENT_TIMESTAMP(6)` 的默认值语法要复核 |
| Flyway 的 `baseline` | 切库是全新实例，用 dump/restore 而不是重跑 211 条迁移 —— 重跑会把 §1 的方言全撞一遍 |

---

## 4. 切过去之后能拿到什么（要求：新特性提升设计便捷与产品能力）

这一节回答「换库能换来什么」，只列**对本项目真有用**的，并注明真实限制。

### 4.1 原生 JSON 类型：读快，但**部分更新几乎用不上**

MySQL 的 `JSON` 是真正的二进制类型：

> "JSON documents stored in JSON columns are converted to an internal format that
> permits quick read access to document elements… the server can look up subobjects
> or nested values directly by key or array index without reading all values before
> or after them."

MariaDB 的 `JSON` 只是 `LONGTEXT COLLATE utf8mb4_bin` 的别名，每次都要整段解析。
**这一条是实打实的收益。**

至于常被宣传的「部分原地更新」，官方限制有五条，其中两条直接把我们的场景挡在门外：

> "All changes replace existing array or object values with new ones, and
> **do not add any new elements** to the parent object or array."
> "The value being replaced **must be at least as large as the replacement value**."

我们真实发生过的那次（`V229` 给每个规格组补 `templateNo`）**恰恰是「新增一个 key」** ——
**不满足条件，照样是整块重写**。所以不要指望换库能省掉那种回填。

> 把这一条写下来，是因为「MySQL 有部分更新」很容易被拿来当换库的理由，
> 而它在我们这个场景里不成立。

### 4.2 `JSON_SCHEMA_VALID` + `CHECK`：**这一条现在就能用**

[§3.7](./商品规格参数-模型与查询性能.md) 记着一个真实缺陷：`spec_groups` 的形状
随代码演进（后来加了 `templateNo`、`optionCodes`），而已写入的行不会跟着变，
数据库也不报错 —— 于是「线上 198 件里只有 1 件带 templateNo」是靠人翻数据翻出来的。

`JSON_SCHEMA_VALID()` MariaDB 与 MySQL 8 都有，挂成 `CHECK` 约束就能让数据库替我们守住形状：

> ⚠️ **有版本下限，低于它是「静默给错答案」而不是报错。**
> 本机 MariaDB **12.2.2** 上实测，`{"type":"array","items":{"type":"object"}}`
> 校验 `[{"a":1}]` 返回 **0（判为不合法）**；而 `items:{"type":"string"}` 是对的 ——
> 只有「对象数组」这一种形状错，**而那正是 `spec_groups` 的形状**。
>
> 这是 [MDEV-38033](https://jira.mariadb.org/browse/MDEV-38033)：
> *"JSON_SCHEMA_VALID() is returning incorrect result with JSON having array of objects"*，
> 状态 **Closed(Fixed)**，影响 12.1.1，**修复于 11.4.13 / 11.8.9 / 12.3.2**。
>
> **生产是 12.3.2，已经在修复版本上** —— 同一条 schema 在生产库上实测返回 **1**。
> 所以这条建议成立，但要写明下限：**MariaDB ≥ 12.3.2（或 11.4.13 / 11.8.9）**。
> 本机版本低于它的人跑这条约束会看到「所有数据都不合规」，
> 而那不是数据的问题。切 MySQL 后要在目标版本上重验一次。
>
> 这件事本身也是个例子：我本机跑出来是「坏的」，生产跑出来是「好的」，
> **只有查 Jira 才知道两者都对、差的是版本**。

```sql
ALTER TABLE prd_goods ADD CONSTRAINT ck_goods_spec_groups
  CHECK (spec_groups IS NULL OR JSON_SCHEMA_VALID('{
    "type":"array",
    "items":{"type":"object",
      "required":["name","options"],
      "properties":{"templateNo":{"type":"string"}}}}', spec_groups));
```

**不用等换库**，这是本次审计里可迁移性之外的最大收获。
但要注意：加约束之前必须先确认**存量全部合规**，否则任何一次 UPDATE 都会失败 ——
而存量恰恰是不合规的（那 71 件回填不了的）。所以顺序是：先收敛存量，再加约束。

### 4.3 多值索引：**不构成换库理由**

MySQL 8.0.17+ 有，MariaDB 在做（MDEV-25848，目标 13.2）。但官方限制里
「不支持排序」「不能覆盖」两条，让它解决不了我们真正的查询形状。
详见[商品规格参数那份设计的 §4.4 问题四](./商品规格参数-模型与查询性能.md)——
结论是一张普通倒排子表在现有 MariaDB 上就比它快。

### 4.4 降序索引：小收益，别当理由

MySQL 8 支持真正的降序索引；MariaDB 解析 `DESC` 但忽略它。
我们的热查询是 `ORDER BY sales DESC` —— 但**升序索引反向扫同样能服务它**，
差别只在极少数混合升降序的复合排序上。列在这里是为了避免有人把它当成换库的理由。

---

## 5. 怎么验证

| 事项 | 判据 |
| --- | --- |
| 新 SQL 不引入方言 | `node scripts/check-sql-portability.mjs --check` 绿。**做过消融**：塞一条 `CREATE INDEX IF NOT EXISTS` + 一条 `utf8mb4_0900_ai_ci`，两个方向各点名一条，撤掉恢复绿 |
| 存量转换不炸 | 在**生产快照**上试跑全部 `CONVERT TO`，把撞唯一键的表列出来；不是在空库上跑 |
| 比较语义没变坏 | 转换前后各跑一次登录、搜索、唯一键冲突三类用例 —— 它们是最可能被排序规则影响的 |
| 真能在 MySQL 上起来 | 到那一步时：拿 dump 在一个 MySQL 8 实例上 restore + 起服务 + 跑全量场景测试。**在 MariaDB 上跑绿不能证明这一点** |
