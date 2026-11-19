# ai-shop v2 · 运营端上线记录（UME 166）

状态：2026-11-19 已上线，与现有版本并存。分支 `feat/gateway-layer-20261118`，提交 `1ce21cf86`，
应用包 SHA-256 `f32688eb5c9588827f7d1ef5a3e5c5bc68cf5e1f1317dfd263a0a40dcded1276`。

## 一句话

v2（网关层分支）的运营端在 166 上另起端口运行，连正式库 `ai_shop_ume`，入口是
`https://nearshop.umeshop.ai/ops-shop-v2/`。现有版本（`/ops-shop/`、`ai-shop-ops`、`ai-shop-api`）没有停、没有换包、没有改配置。
B 端、C 端的 v2 还没做，按用户定的顺序在运营端验证之后逐个来。

## 用户定下的几件事（2026-11-19）

- v2 不是测试环境，对接正式库，一切按正式数据走，先台湾地区。
- 先运营端，再 B 端、C 端逐个来。
- 入口放在同域名下的 `/ops-shop-v2/`。
- 正式库的三条迁移全部执行。
- **部署位置由 192.168.3.80 改为 166**：生产的 ai-shop 调支付用的是 166 本机回环地址，图片目录也在 166 本机，
  数据库账号只允许从 166 连。放在 .80 要给支付内部入口开内网口、另建数据库账号、图片目录分裂；放在 166 这些都不用动。
- 192.168.3.80 上先前的隔离测试环境已整套删除（容器、库、密钥、运行时、systemd 单元），机器恢复到部署前的样子。

## 正式库改了什么

库：.61 上 MySQL 9.7.4 的 `ai_shop_ume`，改之前在 `V678`。应用账号没有建表改表权限，三条都由 root 执行并登记进迁移历史。

| 迁移 | 内容 | 登记的校验和 |
|---|---|---|
| `V679` | 新建 `sys_api_resource`、`sys_api_resource_point` | -1403207376 |
| `V680` | 插入菜单点「接口资源」（系统维护组，排序 260）与操作点 `system:api-resource:update`；菜单点授给超管与技术运维，操作点只授超管 | 572651112 |
| `V681` | `sys_function_point` 加一列 `module_code`（可空） | -831498519 |

- 校验和取自用同一批文件从零重建的库；执行前核对过正式库里 `V678` 的校验和与之相同，说明算法与文件一致。
- 执行前的备份：.61 的 `/data0/mysql9/backups/ai-shop-v2-gateway-20261119/before-V679.sql.gz`
  （`flyway_schema_history`、`sys_function_point`、`sys_role_point`、`sys_function` 四张表）。
- v2 启动后自己写入的数据：接口资源 640 行（运营端 630、支付运营面 10），功能点的模块归属回填 296 行。

**对现有版本的影响（没有登录验证过）：** 库里多了「接口资源」这个菜单点，现有版本的菜单接口会把它返回给超管与技术运维。
现有版本的前端代码里没有这个页签，它在旧界面上是不显示、还是显示成一个点了没有内容的菜单项，需要登录 `/ops-shop/` 看一眼才知道。
接口层面没有影响：现有版本不认识的只是一个菜单点。
现有版本的包最高认到 `V678`，库里多出来的三条对它是「将来的迁移」，Flyway 默认忽略，重启不受影响。

## 166 上新增了什么

| 东西 | 位置 |
|---|---|
| 容器 | `ai-shop-ops-v2`，主机网络，`127.0.0.1:18181`，内存上限 1.5 GiB、堆 768 MiB，`unless-stopped` |
| 应用包 | `/data0/app/ai-shop-v2/releases/shop-app-1ce21cf86.jar` |
| 环境文件 | `/data0/app/ai-shop-v2/ai-shop-v2.env`（权限 600），由现有的 `ai-shop.env` 复制，只改了下面三项 |
| 前端 | `/data/www/ai-shop-ops-v2/releases/gateway-1ce21cf86/ops-shop-v2/`，`current` 指向它 |
| nginx | `/etc/nginx/snippets/ai-shop-ops-v2.locations.conf`；`nearshop.umeshop.ai.conf` 里加了一行 include |

与现有运营端实例不同的配置只有三项：

| 配置 | v2 的值 | 原因 |
|---|---|---|
| `SERVER_PORT` | `18181` | 与现有的 18081 并存 |
| `SHOP_NEARPAY_STORE_SYNC_ENABLED` | `false` | 门店同步只由现有版本做，避免两个实例重复消费 |
| `SHOP_GATEWAY_OPS_PRECHECK` | `observe` | 网关提前判权只记日志、不拦截 |

其余全部相同：`ops` 面、默认市场 `TW`、演示数据关、短信走三竹、支付走独立服务（本机 18093）、会话存库、
挂载同一个上传目录与同样的两份只读配置。镜像与现有实例是同一个（`eclipse-temurin:21-jre-alpine`）。
没有配网关的签名密钥，发往支付的调用与现有版本一样不带签名上下文。

会话存在库里且两个版本共用，所以在 `/ops-shop/` 登录的令牌在 v2 的接口上同样有效（浏览器里两个入口的本地存储各自独立，仍要各登一次）。

## nginx

只动了 166，没有动 201：边缘把 `nearshop.umeshop.ai` 的全部路径（除了被挡掉的几个前缀）原样转到 166。

- 新增的规则在独立文件里，四条：`/ops-shop-v2` 跳转、`/ops-shop-v2/ops/` → `127.0.0.1:18181/ops/`、
  `/ops-shop-v2/common/master-data` → 现有 api 实例、`/ops-shop-v2/` 静态文件。写法照搬现有 `/ops-shop/` 的四条。
- 这个 server 块同时服务 `tw.neargo.ai`，所以 `https://tw.neargo.ai/ops-shop-v2/` 也通。`twpay.umeshop.ai` 没有加。
- 改之前的备份：`/etc/nginx/conf.d/nearshop.umeshop.ai.conf.before-ops-shop-v2-20261119`。`nginx -t` 通过后平滑加载。

## 验证

```
v2 启动：Successfully validated 597 migrations；Started ShopApplication in 68 秒
v2 健康检查 200；现有 ops、api 健康检查 200

公网（未登录）：
  https://nearshop.umeshop.ai/ops-shop-v2/                          200
  https://nearshop.umeshop.ai/ops-shop-v2/ops/auth/me               401
  https://nearshop.umeshop.ai/ops-shop-v2/ops/api-resources/summary 401
  https://nearshop.umeshop.ai/ops-shop/                             200   （现有入口不变）
  https://nearshop.umeshop.ai/ops-shop/ops/auth/me                  401

166 内存：v2 占 659 MiB；三个 ai-shop 实例合计约 2.1 GiB；机器可用内存从 2.35 GiB 降到 1.67 GiB
```

v2 的日志里只有 1 条 ERROR（没有配 `SHIP_TRACK_KEY`，看件令牌停用），现有版本同样没配。

**没有做登录后的验证。** 正式环境的运营账号是真人账号，我没有用它们登录；需要有人在 `/ops-shop-v2/` 上登录后走一遍：
菜单、系统配置里的「接口资源」页签（应当看到 640 条，提前判权显示为已打开）、以及平时常用的几个页面。

## 要留意的

- **166 的内存余量变小了**（可用约 1.67 GiB）。B 端、C 端的 v2 再各起一个实例的话放不下，需要先定是替换现有实例还是扩容。
- **两个运营端实例同时写同一个库。** 现有的 ops 与 api 两个实例本来就是这样运行的；v2 关掉了门店同步这一个消费者。
  其它定时与发件箱任务没有逐个核对过是否适合三个实例并跑。
- **接口资源页的「停用」在 v2 上是真的生效的**（观察档下也生效），而且只对经过 v2 的请求生效；经现有入口的请求不受影响。
- 网关的提前判权是观察档：日志里出现「提前判权（观察）会拒绝」就是网关与方法注解结论不一致的地方，目前 0 条（还没有登录后的流量）。

## 回退

按相反的顺序，每一步都可以单独做：

```
# 1. 撤入口：删掉 include 那一行（或用备份覆盖），测试后加载
sudo cp -p /etc/nginx/conf.d/nearshop.umeshop.ai.conf.before-ops-shop-v2-20261119 /etc/nginx/conf.d/nearshop.umeshop.ai.conf
sudo nginx -t && sudo systemctl reload nginx
# 2. 停 v2
sudo docker rm -f ai-shop-ops-v2
# 3. 库：新表与新列留着不影响现有版本。要去掉菜单项时删这几行（先确认没有别的角色被授过这两个点）：
#    DELETE FROM sys_role_point WHERE point_code IN ('OPS_SYSTEM__TAB_APIRESOURCE','ACT__SYSTEM_API_RESOURCE_UPDATE');
#    DELETE FROM sys_function_point WHERE point_code IN ('OPS_SYSTEM__TAB_APIRESOURCE','ACT__SYSTEM_API_RESOURCE_UPDATE');
```

迁移历史里的三行不要删：删了之后 v2 再启动会尝试重新执行，而应用账号没有建表权限。

## 2026-11-19 追加：v2 接入 Nacos

用户要求「基于 nacos，pay、aishop 都接入进来，先用 aishop 接入」，并要求自动创建账号。

- **Nacos**：192.168.3.80 上的 3.3.0，单机、内置存储。控制台 `http://192.168.3.80:8080/`，服务端 `192.168.3.80:8848`。
  管理员 `nacos` 已初始化；给 ai-shop 建了账号 `ai-shop`（角色 `ai-shop-svc`，对 `public` 命名空间读写）。
  两个口令都在 .80 上随机生成，存在 `/data0/nacos/secrets/`（权限 600），没有出现在任何输出里。
- **v2 换了包**：提交 `e084b5d55`（分支 `feat/gateway-nacos-discovery`），SHA-256 `3b1549313a652c6bb382eba06233e24453061ae12192a0588cc0e5954bce8381`，
  在 `/data0/app/ai-shop-v2/releases/shop-app-e084b5d55.jar`。上一个包 `shop-app-1ce21cf86.jar` 还在同一目录。
- **v2 的环境文件加了五项**（改之前的备份是 `ai-shop-v2.env.before-nacos-20261119`）：
  `SHOP_NACOS_ENABLED=true`、`SHOP_NACOS_SERVER_ADDR=192.168.3.80:8848`、`SHOP_NACOS_USERNAME=ai-shop`、`SHOP_NACOS_PASSWORD`、`SHOP_NACOS_REGISTER_IP=192.168.3.166`。
- **只打开了登记，发现名单是空的**：所有服务地址（包括支付）仍然读配置，调用路径没有变化。日志里没有一次「取自 Nacos」或「回落」。

```
v2 启动：Started ShopApplication in 74 秒；已登记到 Nacos：ai-shop-ops@DEFAULT_GROUP 192.168.3.166:18181
Nacos 里 ai-shop-ops 的实例数: 1
  192.168.3.166:18181 healthy=True ephemeral=True metadata={buildTime, scheme=http, face=ops}
公网：/ops-shop-v2/ 200，/ops-shop-v2/ops/auth/me 401，/ops-shop/ops/auth/me 401
现有 ops、api 实例健康检查 200；166 可用内存约 1.48 GiB
```

要留意的：

- 登记的地址是 `192.168.3.166:18181`，而实例只绑 `127.0.0.1`，别的机器按这个地址连不上。现在登记的作用是在 Nacos 里看得见实例与版本。
- v2 换包重启时中断了约 75 秒；现有版本没有动。
- 现有的 `ai-shop-ops`、`ai-shop-api`（v1）没有接入 Nacos：它们的包里没有这段代码。
- 支付的接入要在 near-pay 的仓库里做。它登记之后，在 v2 的环境里加 `SHOP_NACOS_DISCOVERY_SERVICES_PAY=<它登记的服务名>` 即可切到按 Nacos 发现；取不到会回落到现在的配置地址。

回退这一步：用 `ai-shop-v2.env.before-nacos-20261119` 覆盖环境文件，并把容器换回 `shop-app-1ce21cf86.jar` 重建。

## 怎么连

经 UME 跳板机（资料在 `~/work/env/ume/`）。那份 `ssh.config` 里 `ProxyCommand` 用了 `%d`，本机的 ssh 不认，
`ume-app166` 等别名直接用会报错；可用的写法是：

```
ssh -F ~/work/env/ume/ssh.config -J ume-jump -i ~/work/ume/ume-id_rsa \
    -o UserKnownHostsFile=~/work/env/ume/ssh-known-hosts ume@192.168.3.166
```
