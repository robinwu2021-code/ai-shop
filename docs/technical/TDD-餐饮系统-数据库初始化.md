# 餐饮系统数据库初始化

> 状态：已执行并核验（本机） · 2026-10-20。仅完成库表初始化，不表示餐饮业务、API、支付或打印已经可用。
> 用户要求：餐饮与订单、商品使用同一个库，以前缀区分。目标通过项目主数据源及真实商品/订单表核验为`127.0.0.1:3306/ai_shop`，引擎MariaDB 12.2.2。
> [业务表清单](./design/餐饮系统-数据库表清单.md) · [设计源](./design/schema/food-system-design.json) · [数据库设计](./design/餐饮系统-数据库设计.md)。

## §0 范围

创建73张餐饮自有`fnb_`业务表及1张`fnb_flyway_history`记录表。复用已有商品、订单、账号、主体、门店及资金数据；未修改已有表、写入业务数据或创建新的餐饮数据库。5张共享打印候选表与1张报表投影不在本次执行范围。

字段来自2026-10-20综合设计，保留物理自增ID、业务号、tenant_no/entity_no、审计、version和deleted。表之间为逻辑引用，不新增跨域外键；金额权威、容量仲裁、租约及资金并发仍必须由各业务服务实现。

## §1 迁移与部署策略

主库原`flyway_schema_history`最高已应用V381，而工作区有其他业务的后续迁移。为避免运行无关迁移，餐饮沿已有独立历史方案：使用同一主库/凭据，迁移位置`db/industry/food`，历史表`fnb_flyway_history`，baseline为0，首次版本为1。

独立迁移记录不代表独立数据库或独立资金账。初始化时餐饮业务模块尚未创建，现有主应用默认`db/migration`不会自动运行本位置；本轮用专用Flyway执行器完成初始化。未来food-core自动装配须复用主DataSource并配置同一位置/历史表，和宿主互斥选定一个迁移执行者；禁止把V1复制进核心默认迁移目录再次执行。

建表使用InnoDB，表级字符集/排序规则继承库默认，业务键/幂等键等指定大小写敏感排序。发布快照、数量检查、关系唯一键及父级查询索引均落库。没有DROP/ALTER现有表，也没有修改任何已应用的核心迁移。

已应用V1为冻结产物。`gen-food-schema.mjs`对内容不同的现有V1拒绝覆盖，后续改表必须编写V2及以上增量迁移，不回写V1。

## §2 文件与执行入口

| 文件 | 用途 |
|---|---|
| [V1__food_business_tables.sql](../../backend-plugins/shop-industry-food/food-core/src/main/resources/db/industry/food/V1__food_business_tables.sql) | 73张餐饮表的冻结迁移 |
| [gen-food-schema.mjs](../../scripts/gen-food-schema.mjs) | 从设计源生成或校验首次建表SQL；不能覆盖冻结版本 |
| [food-schema-sql.mjs](../../scripts/lib/food-schema-sql.mjs) | 物理类型/索引/约束渲染与冻结守卫 |
| [FoodSchemaMigrate.java](../../backend/scripts/FoodSchemaMigrate.java) | 仅info/migrate/validate；验证目标已有商品订单，拒绝未跟踪的fnb表，禁用clean |
| [food-schema-sql.test.mjs](../../scripts/tests/food-schema-sql.test.mjs) | 生成/范围/约束/冻结及资源归属测试 |
| [verify-food-schema-mysql.mjs](../../scripts/verify-food-schema-mysql.mjs) | 一次性隔离MySQL真库建表及关系/数量约束验证，不读取业务库凭据 |

执行器沿现有`backend/scripts/FlywayRun.java`方式，由JDK21编译；依赖取当前后端已有Flyway11.14.1及MySQL插件、现有MariaDB JDBC驱动，不在仓库记录口令。通过环境变量DB_URL/DB_USER/DB_PASS传入已解析的主库连接，MIG_DIR指向当前food-core迁移目录；口令不进入命令行、SQL或文档。

```bash
# 在仓库根完成依赖class path与私密环境注入，不要将密码放在命令参数中
export MIG_DIR="$PWD/backend-plugins/shop-industry-food/food-core/src/main/resources/db/industry/food"
java -cp "$FOOD_MIGRATION_CLASSPATH" FoodSchemaMigrate info
java -cp "$FOOD_MIGRATION_CLASSPATH" FoodSchemaMigrate migrate
java -cp "$FOOD_MIGRATION_CLASSPATH" FoodSchemaMigrate validate
```

## §5 实际验证结果

- `node --test scripts/tests/food-schema-sql.test.mjs`：4/4通过。
- `node scripts/gen-food-schema.mjs --check`：73表范围和SQL一致。
- `node scripts/verify-food-schema-mysql.mjs`：一次性MySQL9.7.2完整创建73表；验证一餐牌多门店、一菜多选择组、一菜项多SKU、同SKU多工位/跨店路由、无组件厨房任务去重、源行去重、额度上限与负容量拒绝。
- `node scripts/check-sql-portability.mjs`：没有新增方言依赖。
- 本机Flyway实际输出：`Food migrations applied=1; target=1; expected business tables=73`。
- 再次执行migrate输出`Food migrations applied=0`，没有重复执行V1或写入业务表。
- 主库`fnb_flyway_history`：baseline0成功，`1 / food business tables / success=1`；随后Flyway validate通过。
- 信息架构逐表核验：73张业务表和全部设计字段存在，均为空表；另有1张餐饮迁移历史表。
- 前后对比：原非fnb表的列/类型/默认值元数据摘要一致；核心迁移版本/脚本/checksum/success摘要一致；商品、SKU、支付根订单及门店订单行数一致。

Flyway11.14.1提示MariaDB12.2高于其已验证的11.7版本。实际V1迁移及validate均通过，同时已用MySQL9.7.2验证SQL；该版本提示仍作为环境兼容项记录，不能外推所有后续迁移都已经跨引擎验证。

## §6 实施后续

继续按桌台→菜品→订单模块开发。空表不等于Controller/Service/Mapper、数据库锁SQL、鉴权、幂等、核心合同及设备联调已实现。设计评审列明的18项专项缺口仍待对应模块细化。

开发和启动应连接既有主库并选定一个餐饮迁移执行者；不得误将整套核心待迁移历史连带升级。后续生产/测试环境仍需按部署流程独立应用和核验，本记录只证明本机ai_shop已完成。

## §6 NEXT-04 资源归属补记 · 2026-10-20

原冻结 V1 已原字节移入 food-core，classpath 仍为 db/industry/food，文件名/版本/独立历史不变。
移动前后 SHA-256 均为 `215d76bf3a1c23aefc31bbbaa1eea25fd62c7bad3d7eb9123f5a84f0cc347aa9`。
本轮仅在一次性无网络 MySQL 容器中复验建表与约束，没有访问本机 ai_shop 或修改其 Flyway 历史；已应用环境的 Flyway.validate 留在 NEXT-05。
初始化时的执行记录及校验结论保留；工程最新验证见 [餐饮工程说明](../../backend-plugins/shop-industry-food/README.md)。
