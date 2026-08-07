# ai-shop backend

模块化单体（Spring Boot 4.0.x + Java 21）。架构与取舍见 [docs/technical/TDD-backend.md](../docs/technical/TDD-backend.md)，
端点全集见 [docs/technical/API清单.md](../docs/technical/API清单.md)。

## 构建

**必须用 JDK 21**（`neargo-parent` 的 enforcer 锁 `[21,22)`；本机默认 JDK 可能是 17 或 26）：

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home mvn -f backend/pom.xml test
```

依赖 `ai.neargo:neargo-parent` 与 `neargo-common-*`，从本机 `~/.m2` 解析（与 powerbank 同一地基）。

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

需要 MySQL（`ai_shop` 库）；不需要 Redis（`shop.auth.token-store` 默认 `memory`，**生产必须改 `redis`**）。
