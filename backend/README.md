# ai-shop backend

模块化单体（Spring Boot 4.0.x + Java 21）。架构与取舍见 [TDD-backend](../docs/technical/design/TDD-backend.md)，
端点全集见 [API清单](../docs/technical/reference/API清单.md)，分层现状见 [后端分层清单](../docs/technical/reference/后端分层清单.md)（生成物）。

## 构建

**必须用 JDK 21**（`neargo-build-parent` 的 enforcer 锁 `[21,22)`；本机默认 JDK 可能是 17 或 26）：

```bash
JAVA_HOME=<任一 JDK 21 的 Home> mvn -f backend/pom.xml test
# 例：Homebrew 装的 /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home；
#     解压装的 ~/.local/opt/jdk-21.0.13+9/Contents/Home
```

框架包在公司 Nexus（`nexus.neargo.ai`，public 组）。`~/.m2/settings.xml` 里配好 server 与仓库、
账号走环境变量，**不进仓库**。**首次构建要联网并带 `-U`**（拉 `2.0.0-SNAPSHOT` 与插件）；之后可 `-o` 离线。
SNAPSHOT 会被框架方重发，行为变了先 `-U` 重拉再判断。

继承 `ai.neargo:neargo-build-parent:2.0.0-SNAPSHOT`，由其继承的 `neargo-bom:2.0.0-SNAPSHOT` 统一管理框架依赖。`neargo-common-*` 等 `ai.neargo` 构件只允许 `2.0.0-SNAPSHOT`，包含传递依赖；项目自身的 `ai.neargo.shop` 模块版本保持独立。

首次构建前，构建环境需已安装或能从配置仓库解析 neargo-framework 的 `2.0.0-SNAPSHOT` 构件（BOM、构建父 POM、框架聚合 POM及公共模块）。项目不再使用旧 `neargo-parent:1.0.0-SNAPSHOT`。不要通过命令行或模块 POM 覆盖回 1.x，Maven Enforcer 会拒绝。

## 模块

> 2026-10-25 按 `backend/pom.xml` 的 reactor 重写。此前这张表还是 2026-08 的
> `shop-common / shop-spi / shop-svc-*`，那三者早已合并进 `shop-base` 与 `shop-core`。
> 每个模块为什么单独成工程，写在 `pom.xml` 对应 `<module>` 上方的注释里 —— 以那里为准。

| 模块 | 职责 |
|------|------|
| `shop-base` | 地基：横切基础设施 + 跨域契约（`spi` 下的 Port 与 record）。零业务依赖 |
| `shop-store-mybatis` | 持久层的 MyBatis 实现，依赖 shop-base、被业务域依赖 |
| `shop-auth-store` · `shop-base-auth` | 会话与登录日志的存储 · 鉴权与 web 接入 |
| `svc-client` | 服务间调用的传输零件（`@HttpExchange` + RestClient），只依赖 spring-web |
| `shop-core` | 核心领域服务（交易、订单、商品、营销、消息……一个工程多个包） |
| `shop-merchant` | 商户主数据与门店（变更节奏不同，单独成工程） |
| `pay/`（`pay-channel` · `pay-domain` · `pay-svc`） | 支付域：通道适配 · 领域（积分、储值、权益）· 独立部署形态 |
| `shop-inventory` | 进销存，独立数据源与迁移历史（`db/inventory`） |
| `shop-report` | 报表持久层，独立库、只存可重建的派生数据 |
| `logistics/`（`logistics-domain` · `logistics-channel`） | 物流：运单与轨迹 · 渠道适配（ADR-032） |
| `shop-channel` | 外部通道适配，只装适配器不装业务判断 |
| `shop-notify` | 触达中台：短信 / 邮件 / 微信订阅 / 推送通道 |
| `job/`（`job-api` · `job-store` · `job-core` · `job-worker`） | 定时任务：契约 · 持久层 · 调度引擎 · 独立调度器（包名 `ai.neargo.job.*`） |
| `elec/elec-api` | 元器件内部契约。元器件本体 2026-09-30 已迁到独立仓库 ai-hxkey |
| `shop-app` | 启动模块：`portal/{mp,biz,ops}` 三端 Controller + config + 装配 |

行业插件在仓库根的 `backend-plugins/`：餐饮 `shop-industry-food`（`food-api/core/web/channel/server`），
见它自己的 [README](../backend-plugins/shop-industry-food/README.md)；
`-P food-embedded` 时 api/core/web 三个模块并入本 reactor。

**模块依赖规则由 `ArchitectureTest` 强制**：域间依赖、Controller 只能在 `portal` 下、
领域 Service 必须接口化、Controller 不得碰 Mapper、Port 只在 spi。违反即构建失败。

**演示数据**：`shop.seed.enabled=true` 才灌（默认关），2 社区 / 2 自提点 / 2 商家 / 4 商品。
**生产不要开**：它会建出口令为公开约定的运营账号。

## 本地运行

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home mvn -f backend/pom.xml -pl shop-app spring-boot:run
```

需要 MySQL（`ai_shop` 库）；不需要 Redis。

会话存储 `shop.auth.token-store` 三选一，**分界线只有一条：部署几个副本**：

| 值 | 活过重启 | 多副本共享 | 外部依赖 | 用在哪 |
|---|:--:|:--:|---|---|
| `memory`（默认） | ✗ | ✗ | 无 | 测试、临时调试 |
| `ehcache` | ✓ | ✗ | 无（本地磁盘） | 本地开发、**单实例生产** |
| `redis` | ✓ | ✓ | Redis | **多副本生产** |

一个副本 → `ehcache` 就够，省一个中间件；两个及以上 → **必须** `redis`，
否则同一个人被负载均衡打到另一个实例上就是未登录，而这个症状是**间歇性**的，最难查。

`ehcache` 的磁盘目录（`shop.auth.ehcache.dir`，默认 `./data/sessions`）
**每个实例必须独占** —— Ehcache 会对它加文件锁，两个实例指同一个目录时后启动的起不来。
