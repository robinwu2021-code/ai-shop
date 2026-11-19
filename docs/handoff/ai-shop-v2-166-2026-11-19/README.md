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

## 2026-11-19 追加：v2 的 api 实例与 B 端入口

用户要求「继续部署网关版本的 ai-shop」。按先前定的顺序，运营端之后是 B 端。

- **容器 `ai-shop-api-v2`**：`api` 面（`/biz/**`、`/mp/**`），`127.0.0.1:18182`，与运营端 v2 同一个包（`e084b5d55`）。
  环境文件 `/data0/app/ai-shop-v2/ai-shop-api-v2.env` 由现有的 `ai-shop-api.env` 复制，改了：端口、
  `SHOP_GATEWAY_BIZ_PRECHECK=observe`、Nacos 五项、三项连接池设置（见下）；现有实例用 `-e` 传的管理签名密钥写进了文件。
  堆上限是 512 MiB（现有实例是 768 MiB），为了给 166 省内存。
- **B 端入口**：`https://nearshop.umeshop.ai/shop-api-v2`。规则追加在 `/etc/nginx/snippets/ai-shop-ops-v2.locations.conf` 里
  （改之前的备份 `.before-shop-api-v2-20261119`），放行范围与现有的 `/shop-api/` 相同：只有 `/biz/**`、主数据、App 版本检查，
  其余 `/shop-api-v2/` 路径回 404。**C 端（`/mp`）的入口还没有加。**
- 启动后同步了商户端接口资源 765 条；在 Nacos 里登记为 `ai-shop-api`（`192.168.3.166:18182`）。

```
公网（未登录）：
  /shop-api-v2/common/master-data  200      /shop-api-v2/common/app/version  200
  /shop-api-v2/biz/goods           401      /shop-api-v2/ops/auth/me、/internal/x、/mp/category/tree  404
  /shop-api/biz/goods              401      （现有入口不变）
Nacos：ai-shop-ops 1 个实例、ai-shop-api 1 个实例，均健康
```

### 出过的一个问题：正式库连接数顶到了上限

每个 ai-shop 实例启动后固定占 30 个库连接（主库相关 22、进销存 8），连接池启动即占满。两个 v2 实例把正式库的连接数
从 89 推到了 **149，上限是 150**。发现时没有出现过连接被拒（`Connection_errors_max_connections` 为 0）。

处理：给两个 v2 实例加了 `SHOP_INVENTORY_DATASOURCE_MAX_POOL_SIZE=2`、`SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=4`、
`SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE=1` 并重启（只重启了 v2 的两个，各中断约 2.5 分钟）。
结果：每个 v2 实例从 30 降到 24，**总数 127 / 150**。主库那两项设置没有起作用（主库的连接池是代码里自建的，不读这两项），
降下来的只有进销存那一池；支付 8、密钥 4 两个池的大小写死在代码里。

**现在的余量是 23 个连接，部署 v2 之前是 61 个。** 这个余量偏小：`appops_app` 账号此前有 10 个连接、发现问题时已是 0，
它再连回来余量就只剩 13；夜间备份、支付的连接池增长都要用连接。两条出路，都需要用户定：
把正式库的 `max_connections` 调大（动态生效，改的是生产库设置）；或者改代码让那几个池的大小可配，v2 并存期间调小。

**处理结果（同日，用户选了「调大库连接上限」）：** 正式库的 `max_connections` 由 150 调到 **250**。
用 `SET PERSIST` 动态生效（没有重启数据库），并把 .61 上 `/data0/mysql9/config/my.cnf` 里的同一行改成 250，
改之前的备份是 `my.cnf.before-maxconn-20261119`。调之前连接数已到 137（`appops_app` 的 10 个连回来了），调之后余量 113。
没有调得更大：数据库容器的内存上限是 2 GiB，已用 1.69 GiB，连接越多占的内存越多。
写死的那几个连接池仍然没有改成可配。

### 166 的内存

可用内存约 0.95 GiB（部署 v2 之前是 2.35 GiB），机器没有交换分区。四个 ai-shop 实例各占约 0.7 GiB。

## 2026-11-19 追加：v2 迁到 192.168.3.80

用户要求「统一网关版本部署到 80」，并定了三件事：迁到 .80 后 166 上的 v2 停掉；支付「通过 nacos 连接 nearpay」；
上传文件 .80 用自己的本地目录。**本文件标题里的 166 指的是入口与现有版本所在的机器；v2 的两个实例现在在 .80。**

| 项 | 现在 |
|---|---|
| 运营端 v2 | .80 上容器 `ai-shop-ops-v2`，`192.168.3.80:18181` |
| api v2 | .80 上容器 `ai-shop-api-v2`，`192.168.3.80:18182`（堆恢复为 768 MiB） |
| 目录 | .80 的 `/data0/app/ai-shop-v2/`：包、两个环境文件、`conf/`（四个只读配置）、`run.sh`、`firewall.sh`、`README.md` |
| 容器运行时 | Docker 26.1.4 静态包，systemd 单元 `aishop-docker`；镜像是从 166 原样导过来的同一个（`ad14a2944a48`） |
| 入口 | 不变：`/ops-shop-v2/`、`/shop-api-v2/`。166 的 nginx 把接口转到 `192.168.3.80:18181`、`:18182`；运营端静态页面仍由 166 提供 |
| 166 上的 v2 | 两个容器已删除；`/data0/app/ai-shop-v2/` 下的包与环境文件留着 |

为迁移在生产上做的改动：

- **正式库新建了账号 `ai_shop_ume`@`192.168.3.80`**：口令与 `@192.168.3.166` 的相同（复制的是口令摘要），权限相同（两个库各四项读写）。
- **166 的 nginx**：`ai-shop-ops-v2.locations.conf` 里五处转发目标由本机改为 .80，备份 `.before-move-to-80-20261119`。
- **.80 的防火墙**：新增一条链，18181、18182 两个端口只放行 166 与本机，其余丢弃（从 .61 实测连不上）。
  原因是实例要绑内网地址才能让 166 转过来，而应用的内部入口一直靠「只有 nginx 能到」保护。规则由 systemd 单元 `aishop-v2-firewall` 在开机时加上。

.80 上与 166 上不同的配置：`SERVER_ADDRESS=192.168.3.80`、`SHOP_NACOS_REGISTER_IP=192.168.3.80`、
去掉了 `SHOP_SERVICES_TARGETS_PAY`、加了 `SHOP_NACOS_DISCOVERY_SERVICES_PAY=near-pay`；上传与打印私有目录换成 .80 本机的空目录。

```
.80：两个实例健康检查 200；各校验 597 条迁移；Nacos 里 ai-shop-ops、ai-shop-api 各 1 个实例（192.168.3.80），健康
公网（未登录）：/ops-shop-v2/ 200、/ops-shop-v2/ops/auth/me 401、/shop-api-v2/common/master-data 200、
               /shop-api-v2/biz/goods 401、/shop-api-v2/internal/x 404；现有的 /ops-shop/、/shop-api/ 不变
166：可用内存由约 1.0 GiB 回到 2.9 GiB；现有 ops、api 健康检查 200
正式库连接：137 / 250（166 的现有版本 60、.80 的 v2 48、支付 10、appops 10、库存 8）
```

### 当时不能用的：v2 调支付（后面已处理，见下一小节）

**v2 目前调不通支付。** 配置按用户说的改成了向 Nacos 要 `near-pay` 的地址。迁移过程中发现 Nacos 里已经有 `near-pay` 的登记
（`192.168.3.166:18093`，健康），支付容器也在那前后被重启过 —— 不是本次会话做的。但支付仍然只监听 166 的本机回环，
从 .80 连 `192.168.3.166:18093` 连不上。所以 v2 上凡是要调支付的页面与接口都会失败，直到支付在内网可达。
另外有两项配置不走服务发现、仍写着 166 的回环地址，同样连不上：`SHOP_NEARPAY_STORE_SYNC_BASE_URL`（门店同步，v2 已关）、
`NEARPAY_BIZ_ORIGIN`（api 实例的商户进件）。

要补上需要支付那一侧动：让它在内网地址上可达（并只放行 .80），那两项直连地址再跟着改。支付不在本仓库，这次没有碰。

### 同日处理：让支付对 .80 可达（用户选了「改支付监听并重启」）

核对后确认：.80 到 166 的网络是通的，166 的防火墙对内网全放行（网卡在 `trusted` 区域）；连不上只是因为支付监听回环。
中途按用户说的先加过一条放行规则，确认无用后已撤掉。

在 166 上做的改动：

| 改动 | 内容 | 备份 / 撤销 |
|---|---|---|
| 防火墙（先做） | `trusted` 区域加两条规则：18093 端口拒绝 .80 以外的 IPv4 来源；拒绝全部 IPv6 来源。本机回环不经过区域规则，不受影响 | `firewall-cmd --permanent --zone=trusted --remove-rich-rule=…` 后 `--reload` |
| 支付配置 `/data0/app/nearpay-prod/cloud.properties` | `server.address` 由 `127.0.0.1` 改为 `0.0.0.0`；新增 `shop.pay.guard.internal.allowed-ips=127.0.0.1/32,::1/128,192.168.3.80/32` | `cloud.properties.before-lan-listen-20261119` |
| 重启 `nearpay-prod` | `docker restart`，**支付中断约 51 秒**（2026-11-19 14:05 前后） | — |

没有换支付的包，没有重建容器，没有动它的环境文件与 Nacos 配置（那些是当天另一个会话部署的）。

```
重启前后本机各入口的应答相同：/internal/pay/v1/x 401、/pay-ops/v2/x 401、/checkout/v1/x 403、/open/pay/x 404
支付启动 39 秒，重新登记到 Nacos：near-pay 192.168.3.166:18093
从 .80 连支付：/internal 401、/pay-ops 401（到达了守卫，没有被来源白名单拒掉）
从 .61、.201 连支付：连不上
公网：twpay 的 /oms/ 200、/oms-api/pay-ops/v2/x 401、/checkout/v1/x 403；nearshop 的四个入口不变
```

.80 上 v2 的配置随之改了一项：`SHOP_NEARPAY_STORE_SYNC_BASE_URL` 指向 `http://192.168.3.166:18093`（门店同步在 v2 仍是关的）。

**没能改的一项：`NEARPAY_BIZ_ORIGIN`（api 实例的渠道账户认领转发）。** 改成 166 的内网地址后 api 实例启动失败（B 端 v2 入口 502 了约 5 分钟，
现有版本不受影响）。原因是代码里的一条安全校验：这条转发带着用户凭据，目标只允许 https，或本机回环的 http。没有绕过它，
已改回回环地址，实例恢复。所以 **v2 在 .80 上的渠道账户认领仍然不可用**，要用就得给支付一个 https 的内网入口。

**还没有验证到的：** v2 登录后真的调一次支付。现在验证到的是网络与来源白名单这一层；`服务 PAY 的地址取自 Nacos` 这条日志要有登录后的调用才会出现。

### 其它要知道的

- **文件是分开的**：v2 上传的图片存在 .80，现有版本看不到；166 上已有的图片 v2 读不到，旧图在 v2 的页面上会是裂图。
- .80 上还跑着别人部署的 `appops`（18090、18091，用户 `appops`），不是本次部署的，没有动。
- 启动用了约 160 秒（两个实例同时起，.80 是 2 核）。
- 登录后的功能仍然没有验证。

## 2026-11-19 追加：C 端接入网关（用户：「C 端接入网关」）

v2 的两个实例换成了提交 `942d950bc` 的包（`shop-app-942d950bc.jar`，sha256 `0a99d8ec770a…`），C 端的入口进了网关索引。方案 §2.25。

| 改了什么 | 在哪 | 备份 / 回退 |
|---|---|---|
| 启动脚本指向新包，标签改为 `gateway-mp-942d950bc` | .80 `/data0/app/ai-shop-v2/run.sh` | `run.sh.before-mp-20261119`；旧包 `shop-app-e084b5d55.jar` 仍在 `releases/` |
| api 实例加 `SHOP_GATEWAY_MP_PRECHECK=observe` | .80 `ai-shop-api-v2.env` | `ai-shop-api-v2.env.before-mp-20261119` |
| 运营端页面（「接口资源」页的端筛选多了「消费者端」） | 166 `/data/www/ai-shop-ops-v2/releases/gateway-942d950bc`，`current` 已指过去 | 把 `current` 指回 `releases/gateway-1ce21cf86` |
| 正式库 `sys_api_resource` 多了 174 行（`end_code = mp`，全部是「留给入口判」、启用） | .61 `ai_shop_ume` | `delete from sys_api_resource where end_code = 'mp'`；实例若还开着 C 端档位，下次启动会再写回 |

回退整件事：把两个 `.before-mp-20261119` 复制回去，`run.sh api restart`、`run.sh ops restart`，再把 `current` 指回旧目录。

**过程：** 先重启 api 实例（约 88 秒起来），确认后再重启运营端实例（约 85 秒）。两个 v2 入口各自中断了一分半左右；现有版本（v1）没有动。
库连接重启前 137/250，重启后 129/250。

**验证到的：**

- 内网直连 api 实例：`/mp/goods` 不带令牌 200、`/mp/order/list` 不带令牌 401、不存在的地址 404 —— 与接入前的行为一致，网关没有多拦。
- 运营端实例上 `/mp/**`、`/biz/**` 仍是 404。
- 公网：`/ops-shop-v2/` 200，`/ops-shop-v2/ops/auth/me`、`/ops/api-resources` 401，`/shop-api-v2/biz/goods` 401，`/shop-api-v2/common/app/version` 200，`/ops-shop/` 200；页面已是新构建。
- 正式库里 C 端 174 行，与清单里默认构建的 C 端入口逐条相同；其余三类行（运营端 630、支付运营面 10、B 端 765）没有变化。

**没有做的：**

- **C 端没有对外入口。** `/shop-api-v2/mp/**` 在公网仍是 404。166 的 nginx 上没有找到现有的 C 端公网入口，开这一条是新增对外暴露，等用户定。
- 「接口资源」页上的「消费者端」筛选没有登录进去看过（不用正式账号）。
- C 端是观察档：停用某一条目前只记日志、不拦。要真拦得改成 `enforce`。
- 部署时一条筛环境文件的命令过滤没写严，`SHOP_MANAGEMENT_SIGNING_KEY` 的值出现在了操作会话的输出里（没有写进任何文件或仓库）。是否轮换由用户定。

## 怎么连

经 UME 跳板机（资料在 `~/work/env/ume/`）。那份 `ssh.config` 里 `ProxyCommand` 用了 `%d`，本机的 ssh 不认，
`ume-app166` 等别名直接用会报错；可用的写法是：

```
ssh -F ~/work/env/ume/ssh.config -J ume-jump -i ~/work/ume/ume-id_rsa \
    -o UserKnownHostsFile=~/work/env/ume/ssh-known-hosts ume@192.168.3.166
```
