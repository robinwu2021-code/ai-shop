# 美业独立 Java 工程

> 状态：工程与首批数据层实施中 · 2026-10-20。实际业务 API 尚未交付。

沿零售的 shop-parent、Java 21、MyBatis-Plus、主库事务及审计规范实现。
执行范围、原始方法签名和验收进度见[后端开发计划](../../docs/technical/TDD-美业系统-后端开发计划.md)。

| 构件 | 职责 | 当前实现 |
|---|---|---|
| beauty-api | DTO / Service 接口 | 构件与依赖边界 |
| beauty-core | 领域实现、实体、Mapper | 首批16表迁移、结构校验、主库接线；政策/定义5个实体与Mapper |
| beauty-web | 用户端 HTTP 入口 | 独立条件配置、api/ops profile隔离 |
| beauty-channel | 内部协议适配 | 构件与依赖边界 |
| beauty-server | 独立内部服务 | 可执行启动、服务令牌安全链、健康探针及显式主库配置 |

只有 server 是可执行 JAR。web/channel 不依赖 core；商品、订单、资金、人员、排班、资源、库存及通知打印沿原所有者提供。
当前 readiness 固定 OUT_OF_SERVICE，等待真实业务提供方和业务写入联验。

## 构建与宿主形态

先按平台流程准备已发布到本机/制品库的 shop-parent、shop-base、shop-store-mybatis、shop-base-auth、svc-client；POM不依赖零售源码目录解析父工程。

```sh
mvn -o -f backend-plugins/shop-industry-beauty/pom.xml verify
mvn -o -f backend-plugins/shop-industry-beauty/pom.xml install
mvn -o -f backend/pom.xml -pl shop-app -am -Pbeauty-embedded test -Dtest=BeautyEmbeddedWiringTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -o -f backend/pom.xml -pl shop-app -am -Pbeauty-standalone test -Dtest=BeautyEmbeddedWiringTest -Dsurefire.failIfNoSpecifiedTests=false
```

宿主默认零售构建不带美业。embedded 引入 web/core；standalone 引入 web/channel，enforcer禁止本地 core/server。
运行时 `shop.beauty.enabled` 默认 false；启用后 `shop.beauty.deployment` 必须明确为 embedded 或 standalone，并与构件形态一致。
独立 beauty-server 只接受 standalone。关闭不需要数据库；启用后通过平台 PrimaryPersistenceConfiguration 装配主库。

独立服务参数：`BEAUTY_SERVER_PORT`（默认8087）、`BEAUTY_DB_URL`、`BEAUTY_DB_USER`、`BEAUTY_DB_PASSWORD`、`SHOP_INTERNAL_TOKEN`。
数据库与令牌无内置凭据。服务令牌采用既有 InternalHttp 头协议；业务委托和受权范围仍需正式入口接入。

## 数据所有权与迁移

V1仅创建16项P1美业私有表，不包含14项共用人员/资源结构、P3护理业绩提成或P4疗程计划。
业务唯一键带 tenant/entity 前缀；源行分配 guard 身份不含 source_revision；数量守恒、时间区间由数据库 CHECK 约束保护。

`shop.beauty.migration.mode` 默认 validate，仅校验；migrate 仅供部署时单一受控执行者显式使用。
行业历史表为 bty_flyway_history，Flyway对象不注册为全局Bean，不接管零售的迁移位置及历史。
独立 server 的平台全量 Flyway 默认关闭；宿主的原平台 Flyway保持独立生效。
发现无历史的既有 bty 表、未知表、历史/字段/默认值/索引/检查约束漂移时拒绝启动；不会隐式 baseline 既有美业数据或 repair。

V1首次生成后冻结；后续新增版本，不回写已执行文件。P3/P4按其正式合同再追加迁移。

## 验证

```sh
node --test scripts/tests/beauty-schema-sql.test.mjs
node scripts/gen-beauty-schema.mjs --check
node scripts/verify-beauty-schema-mysql.mjs
node scripts/verify-beauty-persistence.mjs
node scripts/smoke-beauty-server.mjs
```

MySQL脚本只创建和删除自己命名的一次性容器，使用已缓存的 mysql:8.4，不拉镜像、不读取营业库配置。
DDL验证无网络；JDBC测试仅映射本机随机端口，每例独立数据库。IT由脚本明确选择，普通 verify 不冒充已执行真实数据库测试。
JAR smoke明确关闭美业、清空数据源 URL并监听本机随机端口，检查 liveness=200、readiness=503后结束自己启动的进程。

后续依次完成 ENG-03 的可信身份、幂等、事务审计/outbox，DATA-04剩余发布事务与父链校验，再推进 DATA-05预约、DATA-06工单任务、DATA-07来源编排及共用合同。
