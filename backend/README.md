# ai-shop backend

模块化单体（Spring Boot 4.0.x + Java 21）。架构与取舍见 [docs/technical/TDD-backend.md](../docs/technical/TDD-backend.md)，
端点全集见 [docs/technical/API清单.md](../docs/technical/API清单.md)。

## 构建

**必须用 JDK 21**（`neargo-build-parent` 的 enforcer 锁 `[21,22)`；本机默认 JDK 可能是 17 或 26）：

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home mvn -f backend/pom.xml test
```

继承 `ai.neargo:neargo-build-parent:2.0.0-SNAPSHOT`，由其继承的 `neargo-bom:2.0.0-SNAPSHOT` 统一管理框架依赖。`neargo-common-*` 等 `ai.neargo` 构件只允许 `2.0.0-SNAPSHOT`，包含传递依赖；项目自身的 `ai.neargo.shop` 模块版本保持独立。

首次构建前，构建环境需已安装或能从配置仓库解析 neargo-framework 的 `2.0.0-SNAPSHOT` 构件（BOM、构建父 POM、框架聚合 POM及公共模块）。项目不再使用旧 `neargo-parent:1.0.0-SNAPSHOT`。不要通过命令行或模块 POM 覆盖回 1.x，Maven Enforcer 会拒绝。

## 模块

| 模块 | 职责 |
|------|------|
| `shop-common` | 契约包 · 认证双池 · 数据域 · Outbox · 幂等 · 异常 · i18n。**零业务依赖** |
| `shop-spi` | 跨域契约：Port 接口 + 领域事件。只有接口与 record，零实现 |
| `shop-svc-*` | 八个领域服务（user/product/trade/fulfillment/marketing/settle/message/platform） |
| `shop-app` | 启动模块：`portal/{mp,biz,ops}` + config + 装配 |

**模块依赖规则由 `ArchitectureTest` 强制**：svc 之间不得互相依赖、Controller 只能在 `portal` 下、
领域 Service 必须接口化、Controller 不得碰 Mapper。违反即构建失败。

## 当前状态（S0 + S1 完成，20 个测试全绿）

**S0 地基**：三条过滤器链（`/mp` `/biz` `/ops`）· 数据域防线 ①③ · Outbox · 幂等 · i18n · Flyway · 契约包。

**S1 逛的链路**（8 个端点）：

| 端点 | 说明 |
|------|------|
| `POST /mp/user/otp/send` · `POST /mp/user/login` | 三种 grantType 共用一条建户主干；微信/Apple 的渠道校验待 S4 |
| `GET /mp/user/profile` · `POST /mp/user/community` | 归属绑定会校验「自提点属于该社区」 |
| `GET /mp/community/nearby` | 社区 + 自提点 + 承接商家，按距离排序 |
| `GET /mp/goods` · `GET /mp/goods/{no}` | 平台逛 / 频道 / 搜索 / **店内搜索**同一端点 |
| `GET /mp/merchant` · `GET /mp/merchant/{no}` | 商家列表与详情 |

**价格模型已按 TDD-backend §6.3 落地**：`prd_sku` 唯一键 `(merchant_no, sku_no, market)`，
社区池只决定可见性不存价 —— 双入口不同价在结构上不可能发生。

**演示数据**：`shop.seed.enabled=true` 才灌（默认关），2 社区 / 2 自提点 / 2 商家 / 4 商品。

下一步 S2：`svc-trade` 购物车 → 拆单 → 支付 → 订单。

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
