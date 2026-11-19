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

- 「接口资源」页上的「消费者端」筛选没有登录进去看过（不用正式账号）。
- C 端是观察档：停用某一条目前只记日志、不拦。要真拦得改成 `enforce`。
- 部署时一条筛环境文件的命令过滤没写严，`SHOP_MANAGEMENT_SIGNING_KEY` 的值出现在了操作会话的输出里（没有写进任何文件或仓库）。是否轮换由用户定。

### 同日：开 C 端的公网入口（用户：「开 C 端入口」）

166 的 `/etc/nginx/snippets/ai-shop-ops-v2.locations.conf` 里加了一个块：`/shop-api-v2/mp/*` → `192.168.3.80:18182/mp/*`
（写法与 `/shop-api-v2/biz/` 相同，放在兜底的 404 之前）。备份 `.before-mp-entry-20261119`；撤掉就是复原这份备份后 `nginx -t`、reload。
**此前 C 端没有公网入口，这一条是新增对外暴露**：C 端里不用登录的入口（商品、店铺等）现在可以从公网读到正式数据。

公网验证（`nearshop.umeshop.ai`，`tw.neargo.ai` 同一个 server 块也通）：

| 地址 | 结果 |
|---|---|
| `/shop-api-v2/mp/goods`（不带令牌） | 200 |
| `/shop-api-v2/mp/order/list`（不带令牌） | 401 |
| `/shop-api-v2/mp/` 下不存在的地址 | 404 |
| `/shop-api-v2/ops/…`、`/shop-api-v2/internal/…`，以及用 `..`、`%2e%2e` 从 `/mp/` 绕过去 | 404 |
| `/shop-api-v2/biz/goods`、`/common/app/version`、`/ops-shop-v2/`、`/ops-shop/`、`/shop-api/common/app/version` | 与之前相同（401 / 200 / 200 / 200 / 200） |

没有做的：登录后的 C 端验证；c-app 还没有指向 `/shop-api-v2` 的构建；C 端的支付回跳、收银台等不在 `/mp/` 下的地址没有 v2 入口。

## 2026-11-19 追加：B 端「只问商家身份」的入口归类（用户：「部署到 v2」）

v2 的两个实例换成了提交 `5c9c6f0d1` 的包（`shop-app-5c9c6f0d1.jar`，sha256 `f058c8128239…`）。方案 §2.26。只换了后端包，页面与 nginx 没有动。

- 改动：.80 的 `run.sh` 指向新包，标签 `gateway-bizlogin-5c9c6f0d1`；备份 `run.sh.before-bizlogin-20261119`，旧包 `shop-app-942d950bc.jar` 仍在 `releases/`。
  回退：复原这份备份后 `run.sh api restart`、`run.sh ops restart`。
- 过程：先 api 实例（约 95 秒起来），再运营端实例（约 85 秒）；两个 v2 入口各中断约一分半，现有版本没有动。库连接前后都是 125/250。
- 正式库 `sys_api_resource`：B 端 123 行从「留给入口判」变成「只要求登录」，`rule_text` 里带着原表达式；B 端留给入口判的剩 6 行（都带方法参数）。
  行数没有变（共 1579），其余各端的类别没有变。回退包之后下次启动会把这 123 行写回旧类别，不需要手工改库。
- 验证：两个实例启动日志里没有新的 ERROR；内网 `/mp/goods` 200、`/mp/order/list` 401、`/biz/goods` 401；
  公网七个入口（运营端 v2、B 端 v2、C 端 v2、v1 的两个）状态码与部署前相同；生产索引与接口清单逐条对过，判权类别 0 条不一致。
- 没有做的：登录后的验证；「接口资源」页上这批行的显示与停用没有登录进去看过。

## 2026-11-19 追加：v2 合成一个进程（用户：「只有一个 jar，同时提供 C 端、B 端和运营端，入口进来后分别通过网关切到不同的 controller，底层是同一套」）

此前 v2 照现有版本的拆法起了两个进程（运营面、对外面）。用户要的是一个进程提供三端，底层（mapper、service、工具、配置）只有一份，各端只带自己的特定配置。

**现在的样子：** .80 上只有一个容器 `ai-shop-v2`，端口 **18180**，profile `api,ops`，包 `shop-app-c0b8c40c3.jar`（带「连接池可配」，sha256 `1ff632abb3a9…`）。
三端在同一个端口上，由网关的三条链按地址前缀分开；对外放哪些路径仍由 166 的 nginx 按白名单把关，规则没有放宽。
在 Nacos 里登记为 `ai-shop`（原来的 `ai-shop-ops`、`ai-shop-api` 是临时实例，停掉后自动摘除）。

| 改了什么 | 在哪 | 备份 / 回退 |
|---|---|---|
| 新环境文件 `ai-shop-all-v2.env`：以 api 实例的为底，加上运营端实例独有的项（101 项，无重复键） | .80 `/data0/app/ai-shop-v2/` | 原来两份环境文件原样保留 |
| `run.sh` 多一个目标 `all`；两份附加配置文件都挂上 | 同上 | `run.sh.before-single-20261119`（注意它指向的是包 `5c9c6f0d1` 之后、`c0b8c40c3` 的版本） |
| 防火墙只放行 18180（来源仍只有 166 与本机） | .80 `firewall.sh`，单元 `aishop-v2-firewall` | `firewall.sh.before-single-20261119` |
| v2 的所有转发改指 `192.168.3.80:18180`；运营端页面用的主数据也改由 v2 提供（原来借用现有版本的 api 实例） | 166 `/etc/nginx/snippets/ai-shop-ops-v2.locations.conf` | `.before-single-20261119` |
| 停掉并删除容器 `ai-shop-ops-v2`、`ai-shop-api-v2` | .80 | `run.sh ops start`、`run.sh api start` 可再起 |

回退：.80 上复原 `firewall.sh` 并重启防火墙单元，`run.sh ops start`、`run.sh api start`；166 上复原 nginx 那份备份后 `nginx -t`、reload；最后 `run.sh all stop`。

**合并时定的配置：**

- 简讯（三竹）凭据：两个实例原来各挂一份不同的，用户定用 **B/C 端那份**。运营端发的简讯现在也走这个账号。
- `COMMERCE_MALL_HTTP_OPERATOR_ENABLED`：运营端是 `true`、B/C 端是 `false`，取 `true`（否则运营端的商城接口会关掉）。
- `SHOP_AUTOMATION_PUBLIC_KEY`：两边的值不同，取了 B/C 端的（用户事后确认：「可以，保留」）。它是自动化登录验票用的公钥；自动化登录的地址不经 nginx 对外。运营端的自动化登录如果用的是另一把钥，在 v2 上会验不过。
- 主库连接池：两份环境文件里早先写的上限 4、空闲 1 随这个包开始生效。现在三端共用这一个池。
- 事后发现的一个副作用：`SHOP_PRINT_ENABLED=true` 原来只在 B/C 端实例上，合并后对整个进程生效，运营端的 4 个打印运营入口（`/ops/print/health`、`/incidents`、`/operations/{operationNo}`、`/reconciliations`）随之装上并进了生产索引（运营端 630 → 634）。它们都要权限码，公网不带令牌是 401。各端配置里还有哪些是进程级的，清点在方案 §2.28。

**过程：** 先在新端口起单实例（约 86 秒），内网验证后才改 nginx，最后停旧实例；公网入口没有中断窗口（reload 切换）。
部署「连接池可配」的那一步中途被打断过：当时 B/C 端实例已换新包、运营端实例还没换，两个都在正常服务；随后直接做了合并。

**验证到的：**

- 切换前，把生产索引里所有不带路径变量的 GET（482 个：运营端 229、支付运营面 4、B 端 188、C 端 61）不带令牌各请求一次，新旧实例的状态码逐个相同（32 个 200、450 个 401）。
- 公网：`/ops-shop-v2/` 200；`/ops-shop-v2/ops/auth/me`、`/ops/api-resources` 401；`/shop-api-v2/biz/goods` 401；`/shop-api-v2/mp/goods` 200、`/mp/order/list` 401；
  主数据与版本检查 200；`/shop-api-v2/ops/…`、`/internal/…` 以及用 `..`、`%2e%2e` 跨端绕过去的五种写法都是 404；现有版本的两个入口照常。
- 库连接：.80 占用从 50 降到 21，总数 125 → 96（上限 250）。.80 可用内存 4.1 GiB → 4.8 GiB。
- 启动日志没有新的 ERROR，容器没有重启过。

**没有验证的：**

- 登录后的任何功能。特别是：运营端登录的简讯现在走 B/C 端的账号，没有真发过一条。
- 主库池上限 4 在三端共用、有真实流量时够不够。现在没有流量，够用只是推断；不够的症状是请求等连接超时。
- 两个实例合并后，原来各自只在一个面上跑的后台任务现在在同一个进程里，没有逐个核对有没有相互影响。

## 2026-11-19 追加：登录后的接口验证、拦截档、测试账号与测试店

用户先后指示：「账号信息可以自动在系统中写入测试账号进行验证」「各个端的用户不同，测试账号不同，密钥也不同」
「检查更换网关后的权限是否生效、是否正确，尤其是 B 端和 C 端，尤其是 pay；权限不在微服务判断，而在网关层判断」
「推送不走闸门，同时可以在生产上建测试账号进行测试」。

**v2 现在的包与配置：** `shop-app-a1e99ece3.jar`；运营端、B 端提前判权为**拦截档**，C 端观察档；自动化登录的公钥按端分。
环境文件的历次备份：`.before-verify-20261119`、`.before-perend-keys-20261119`、`.before-enforce-20261119`、`.before-clerk-20261119`。
退回观察档：把 `SHOP_GATEWAY_OPS_PRECHECK`、`SHOP_GATEWAY_BIZ_PRECHECK` 改回 `observe`，`run.sh all restart`。

**写进正式库的测试数据**（全部带「【网关验证】」；建号 SQL 与清理说明在同目录 `v2test-accounts.sql`）：

| 是什么 | 标识 | 怎么建的 |
|---|---|---|
| 运营管理员 / 运营客服 | `ST-V2TEST-ADMIN`（SUPER_ADMIN）、`ST-V2TEST-SUPPORT`（SUPPORT） | SQL；口令是无人知道的随机散列 |
| 买家、店主、店员三个用户 | `UUMV2TESTBUYER…`、`UUMV2TESTOWNER…`、`UUMV2TESTCLERK…` | SQL |
| 测试主体与门店 | `M261119TS0PN0XBK9`、`ST261119BE70D8B9C9`「【网关验证】测试店·请勿下单」 | 接口「无证照快速开店」；待补证照，买家看不到、下不了单 |
| 店员成员 | `SF2611194QFZKVW2WE`，角色 CLERK，登记电话 `0900000991` | 接口建，SQL 把这一行绑到测试店员用户 |

**副作用：** 测试门店已被现有版本同步到正式支付系统（`sys_outbox` 里两条 `MERCHANT_STORE_CHANGED`，状态 SENT）。建之前已告知用户。
行业选的是「餐饮」（列表第一个），本想选零售；v2 的包不带餐饮插件，对验证没有影响。

**自动化登录：** 加了消费者端（`realm = C`）；公钥按端配（`SHOP_AUTOMATION_PUBLIC_KEY_OPS` / `_B` / `_C`），通用的那把保持合并时定的 B/C 端原值。
B 端、C 端验证用的私钥是新生成的，与运营端那把一起放在本机 `~/work/env/ume/automation-ume{,-b,-c}-ed25519.pem`。
换会话的接口不对公网开放，验证脚本经跳板机在 .80 上换，之后的业务请求走公网入口。

**验证结果**（三份报告在同目录：`verify-三端-观察档.md`、`verify-三端-拦截档.md`、`verify-B端权限-店主与店员.md`；只发 GET）：

| 项 | 结果 |
|---|---|
| 运营端 235 个只读接口，受限角色按权限式该拒 177 条 | 观察档：入口拒 141，另 36 条先报缺参数没走到判权；**拦截档：177 条全部由网关拒**。不一致 0 条 |
| C 端 61 个只读接口 | 匿名 401 的 29 条，带买家令牌后全部 200 |
| 跨端：三种令牌打另外两端（6 种组合） | 全部 401 |
| 钥按端分：拿一端的钥签另一端的票据（6 种组合） | 全部被拒 |
| B 端店员（CLERK）188 个只读接口 | 走到网关判权的 95 条：该拒且被拒 66、该放且放行 29，**不一致 0 条**；另 59 条在网关之前就被门店准入挡住 |
| B 端店主（全部权限）仍被拒 69 条 | 没有一条是网关按角色拒的：门店准入 47、模块自己的准入 14、支付的调用方矩阵 5、业务范围检查 3 |
| 服务器日志 | 拦截档下「提前判权拒绝」317 次（运营端 177、B 端 140），「观察会拒绝」0 次 |

**结论：** 运营端的权限已在网关层判且判得对。B 端走到网关的部分判得对，但一个请求可能被三处拒绝，只有一处是网关；
C 端要不要登录仍在业务代码里。修复方案见 `docs/technical/design/TDD-权限统一到网关层-修复方案.md`（草案，待确认）。

**要看的：** 打印运营两个接口对超级管理员回 HTTP 403（打印模块自己的准入，原因没查）；`/biz/pay/v2/*` 5 个接口 502（支付进件入口从 .80 连不上）。

**没有验证的：** 写操作（POST / PUT）；真的调通一次支付；带路径变量的入口；店员之外的其它角色。

**推送：** 分支 `feat/gateway-mp-index` 按用户指示不走本机闸门推送。闸门在本机配上商城测试库后跑全过一次：6378 条里 5 条红（4 个类），
在不含本分支的合并基点上同样失败；过程中发现并修了本分支一个真缺陷（没有 Web 层的进程起不来）。

## 2026-11-19 追加：修复方案第 1 步上线（用户：「按照建议执行」）

v2 换成包 `shop-app-410f2cc99.jar`；运营端页面换成 166 上的 `releases/gateway-410f2cc99`。备份：.80 `run.sh.before-admission-20261119`；
页面回退把 `current` 指回 `releases/gateway-942d950bc`。重启一次，两个入口中断约一分半。

内容：B 端的门店准入拒绝现在有日志、响应里带 `deniedBy: STORE_ACCESS`；「接口资源」页 B 端每一行显示门店准入通道。只做加法，错误码没有换。
线上验证与带出来的新情况（226 条 B 端入口在准入表里是 `DENY`）见 `docs/technical/design/TDD-权限统一到网关层-修复方案.md` 第 1 步。

## 2026-11-19 追加：补登记第一组上线（用户：「补第一组」）

v2 换成包 `shop-app-575d8d836.jar`（备份 .80 `run.sh.before-routes-20261119`）。重启一次，两个入口中断约一分半；页面与 nginx 没有动。
内容：`ShopStoreMemberRoutes` 多登记 35 条日常经营入口（结算、保证金、配送规则、社区、客户、跨店、区划及几条零散漏掉的），
新模型的门店原来访问它们一律被门店准入 403。同时把「不经过门店准入」的两类入口的标注改准。结果见 `docs/api/B端门店准入-补登记建议.md`。
这张准入表主干上是同一份，现有版本（v1）上同样缺这 35 条，要等这个分支合进主干才会补上。

## 2026-11-19 追加：储值对新模型门店开放（用户：「开放储值」）

v2 换成包 `shop-app-2ed8a76fc.jar`（备份 .80 `run.sh.before-storedvalue-20261119`）。重启一次，两个入口中断约一分半。
内容：84 条储值入口逐条登记进准入表（门店侧 36 条、管理空间 48 条，后者改的是商城模块的 `CommerceRoutePolicy`）。
验证结果与一处要正视的情况（管理空间那 24 个只读入口网关不拦店员，权限在服务里判，线上没能完整验证服务层的拒绝）见 `docs/api/B端门店准入-补登记建议.md`。
回退：复原 `run.sh` 的备份并重启，即回到储值对新模型门店关闭的状态。现有版本（v1）不受影响，它要等分支合进主干。

## 2026-11-19 追加：规则在库里、网关读库；C 端拦未登录；B 端储值活动配规则（用户：「可以，执行并部署」「把没有完成的所有的工作全部完成」）

**v2 现在的状态：** 包 `shop-app-34a8bce7a.jar`；三端提前判权都是**拦截档**（C 端 2026-11-19 由观察档切过来）；`shop.gateway.rules-source` 用默认的 `db`。
备份：.80 `run.sh.before-rulesdb-20261119`、`run.sh.before-mpenforce-20261119`、`ai-shop-all-v2.env.before-mpenforce-20261119`。
回退到「按入口注解判」不用换包：环境里加 `SHOP_GATEWAY_RULES_SOURCE=code` 后重启；C 端退回观察档把 `SHOP_GATEWAY_MP_PRECHECK` 改回 `observe`。

**正式库：** root 执行了 `V682`（`sys_api_resource` 加四列 `adjusted_access` / `adjusted_rule` / `adjusted_by` / `adjusted_at`，纯增量），
迁移历史登记为第 58 行、校验和 `1822499469`（算法用已登记的 V679–V681 三条对过）。执行前的备份：.61 `/data0/mysql9/backups/ai-shop-v2-gateway-20261119/before-V682.sql.gz`。

**库里现在的调整（都是经 `POST /ops/api-resources/{id}/rule` 做的，有审计）：**

| 端 | 条数 | 调成什么 | 依据 |
|---|---:|---|---|
| C 端 | 106 | 要登录（`LOGIN`） | GET：线上实测匿名 401 的 48 条 + 端上声明要登录而先报参数错的 4 条；写接口：c-app 声明要登录的 54 条。登录、登出、发验证码 3 条被锁定规则挡住没配 |
| B 端 | 9 | `sv.brand.activity.read / manage / publish @BRAND(brandNo)` | 品牌储值活动；码在控制器源码里直接看得到 |

撤销某一条：同一个接口传 `{"access": null}`。全部撤销：`UPDATE sys_api_resource SET adjusted_access=NULL, adjusted_rule=NULL WHERE adjusted_access IS NOT NULL`，60 秒内各实例生效。

**新增的测试数据：** 管理空间 `MGS261119EWAQFNKNYM`「【网关验证】测试管理空间」、品牌 `BR261119TH8Z9X3FQW`「【网关验证】测试品牌」（测试店主经现有接口建的）；
给测试店员授了两个码：`management.enter`（空间）、`sv.brand.activity.read`（该品牌）。

**线上验证：**

| 验了什么 | 结果 |
|---|---|
| 改成读库判权后重跑三端只读验证 | 运营端受限角色该拒 177 条、实拒 177 条；C 端 61 条带令牌全部通过；B 端店员走到网关的 118 条判权全部一致 |
| C 端：106 条配成要登录的入口 | 匿名请求全部被**网关**回 401（GET 52 条、写接口 54 条；日志里「提前判权拒绝 … 未登录」正好 106 次）；带买家令牌的 GET 全部通过；没配的 48 条 GET 行为不变 |
| B 端：品牌储值活动 | 配之前店员得到的是业务的「缺参数」（网关没拦）；配之后不在管理空间里的店员被网关拦（10403）、店主照常；给店员只授读权限后，读的那条放行、要 manage 的两条仍被网关拦 |
| 写操作：受限账号对没有权限的写接口 | 运营端客服 267 条全部被网关拒；B 端店员 183 条里 173 条被网关按角色拒、10 条先被门店或模块准入拒；没有一条漏过去。被拦的请求不进入口，不改数据 |
| 调整接口本身 | 受限角色调不了（10403）；读不懂的式子、调成公开被拒（10400）；调整后撤销恢复原样 |

**做过的真实写操作**（都在拦截档下、由有权限的测试账号完成，说明有权的写没有被网关误拦）：快速开店、加店员、授门店角色、建管理空间、建品牌、发管理授权、调整接口规则。

**没有做的，以及为什么：**

- **B 端其余「只问是不是商家」的入口的规则**（储值 43 条、非储值 68 条）：业务服务里的判断很细，哪条入口对应哪个码，从代码里不能都读得确定。
  配错的后果是网关多拦一个本来有权的人。只配了能从源码直接确认的 9 条；其余要逐条对着业务代码确认后再配，不猜。
- **B 端的门店准入并进网关**：它是业务（商城）自己的门店上下文逻辑。按「不因为网关改业务」的原则没有动；已经做到的是让它可见、与网关的拒绝分得开。
- **餐饮、美业、商城集成插件打进 v2 的包**（408 个入口）：要在正式库执行这些功能的整套建表迁移，并在 v2 上打开现有版本没有的功能。这是产品上线的决定，没有擅自做。
- **真的调通一次支付**：测试店没有在支付系统里完成进件，`/biz/payment/*` 被支付的调用方矩阵拒（70082）；支付进件入口从 .80 也连不上（502）。要先解决进件通路。
- **有权限的写操作的系统性验证**：只有上面列的那几次。逐个入口去发有效的写请求需要每个入口的合法请求体和可清理的数据。
- **现有版本（v1）**：没有动。今天的准入补登记、开放储值要等分支合进主干。
- **PR**：分支已推送，没有开 PR。

## 2026-11-19 追加：B 端、C 端功能矩阵与一次性配对（用户：「先彻底整理 b 端和 c 端的功能矩阵，基于角色，功能点整理清单，然后一次性配对」）

矩阵在 `docs/api/B端C端功能矩阵.md`：B 端门店角色 × 功能点（权限码）× 入口、管理范围的码表、114 条「只问是不是商家」的入口逐条的现状与建议；C 端功能域 × 访客 / 登录买家。

**一次配进库的**：B 端储值相关 32 条（加上此前的 9 条，共 41 条带管理范围的码）。每条的码都是从业务源码里读出来的，依据逐条写在矩阵 1.5 节。
没有部署、没有改代码，全部经 `POST /ops/api-resources/{id}/rule`。

**抽查**（测试店主与测试店员，七个入口，配之前 / 配之后）：两人在每个入口上「被拒还是放行」前后一致——配之前是业务拒的，配之后是网关拒的（日志里有对应的「提前判权拒绝 … 缺 [码]」）。
唯一的差别：读一张不存在的结算单的事件，业务原来回「数据不存在」，现在网关先因缺 `sv.settlement.audit` 拒绝。更严，不是更松。

**没配的**：管理空间、品牌、品牌营销、门店换品牌共 35 条（码还没从源码逐条读出来）；8 条待确认（只认主体属主的、协调器里判的）；36 条账户级入口保持登录即可。
C 端还有 15 条写接口没定（端上没有声明要不要登录，也没法安全实测）。

写接口的规则要等商户 App 带上 `X-Management-No` 才开始生效：现在管理空间号在请求体里，网关读不到，对它们不判。

## 2026-11-19 追加：B 端第二批配对（用户：「继续读并配」）

v2 换成包 `shop-app-b78b36ecc.jar`（备份 .80 `run.sh.before-spacepath-20261119`）：网关多认一种取法——管理空间号在路径里（`/biz/management/spaces/{managementNo}/…`），
并且「空间」这类资源直接指当前的管理空间。重启一次。

读完品牌、管理空间、品牌营销、门店换品牌四个控制器及其服务，**再配 31 条**（B 端带管理范围码的入口共 72 条）。依据逐条写在 `docs/api/B端C端功能矩阵.md` 1.5 节。
品牌营销的 8 条只配了「能进这个管理空间」这道基线：它按活动类型和动作再细分的码在业务里，有的在请求体里，网关判不了。

抽查（测试店主与测试店员，九个入口）：配之前与配之后两人的结论完全一致。请求上带着管理空间号的入口（空间的授权对象、资源、主体的收款商户、门店经营上下文）现在由网关拒，日志里有对应记录；
品牌详情、品牌审计这类请求上没有管理空间号的，网关暂不判，仍由业务拒。

B 端「只问是不是商家」的 123 条现在的归属：72 条已配、38 条保持登录即可（账户级）、10 条待确认、3 条不处理。

## 2026-11-19 追加：按矩阵核对网关的配置（用户：「先基于矩阵配置网关的权限」）

对账发现一处没配齐：在库里调整过规则的 72 条 B 端入口，没有写进「入口—权限码」关联表（`sys_api_resource_point`）——调整规则的代码当时只改了规则本身。
已修并上线：v2 换成包 `shop-app-da7adb5c4.jar`（备份 .80 `run.sh.before-points-20261119`），重启一次；之后把 72 条规则原样重配一遍完成回填。
对账结果见 `docs/api/B端C端功能矩阵.md` 第四节：三端 1182 条按权限码判的入口在关联表里全部有对应的码，没有多的也没有少的。

## 怎么连

经 UME 跳板机（资料在 `~/work/env/ume/`）。那份 `ssh.config` 里 `ProxyCommand` 用了 `%d`，本机的 ssh 不认，
`ume-app166` 等别名直接用会报错；可用的写法是：

```
ssh -F ~/work/env/ume/ssh.config -J ume-jump -i ~/work/ume/ume-id_rsa \
    -o UserKnownHostsFile=~/work/env/ume/ssh-known-hosts ume@192.168.3.166
```

## 2026-11-19 晚：合并主干、开 PR、补齐 B 端最后 10 条

- 分支已合并 `origin/main`（主干最新迁移 V678，与 V679–V682 不冲突），PR：robinwu2021-code/ai-shop#20。**v2 上跑的仍是合并前的包 `shop-app-da7adb5c4.jar`**，合并后的包还没有部署。
- 运营端「接口资源」页加了「调整规则」（接已有的 `POST /ops/api-resources/{id}/rule`），代码已在分支上，**前端静态包还没有重新发布到 166**。
- B 端「只问是不是商家」的 123 条现在：82 条已配、38 条保持登录即可、3 条不处理。后补的 10 条逐条规则见 `docs/api/B端C端功能矩阵.md` 1.5。
  这 10 条的范围号都有一部分不在地址里，网关只判每条路径都会判的那部分（进入管理空间、门店上的码、品牌发行方配置），其余留给业务。
- 验证（测试门店，店主与店员）：`financial-brand-change-original`、`brand-changes/check` 带管理空间号时由网关拒（缺 `store.read`），不带时由业务拒，两边结论一致；
  `issuers/{issuerNo}` 仍先被门店准入挡住（属于还关着的 83 条）。
- 注意：6 条入口的管理空间号在请求体里。客户端带了 `X-Management-No` 头时网关按头里的空间判，业务按请求体里的判；两者不一致时以更严的为准（网关拒了就进不去）。b-app 统一带头之前这 6 条在网关上不判。

### 同晚续：支付回落地址、Nacos 备份、连接池、打印接口

- **支付回落地址**：v2 环境文件加了 `SHOP_SERVICES_TARGETS_PAY=http://192.168.3.166:18093`（与 Nacos 里 near-pay 登记的是同一实例；备份 `ai-shop-all-v2.env.before-payfallback-20261119`），已重启，88 秒起好。Nacos 取不到 near-pay 时回落到它。回落路径只有单测覆盖，没有用真实支付调用演练过。
- **Nacos 备份**：脚本 `/data0/nacos/nacos-backup.sh`（root）：停 Nacos → 打包 `data` 与 `conf` → 起 Nacos → 保留最近 14 份，放 `/data0/nacos/backups/`。已手动跑一次：`nacos-data-20261119-222523.tar.gz`（448K，data 实际内容 11M，raft 日志是预分配的稀疏文件），冷重启后 `near-pay`、`ai-shop` 各 1 个健康实例，两个账号都在。
  **没有挂定时任务**，2026-11-19 用户说先不用处理；需要时手动跑脚本。以后要挂的话（root）：
  `echo "30 3 * * * root /data0/nacos/nacos-backup.sh >> /data0/nacos/backups/backup.log 2>&1" > /etc/cron.d/nacos-backup`
- **连接池**：v2 日志里没有取连接超时；正式库峰值用过 184/250，没有因连接数满被拒。v2 主库池上限 4，连到 .61 共 16 条。指标接口（`/actuator/metrics`）没有对外开，要持续看等待数需要打开它，属于配置变更，没做。
- **后台任务**：v2 关着（`SHOP_JOB_ENABLED=false`），定时任务只在 v1 跑，v2 这边不涉及。
- **超管访问 `/ops/print/health`、`/ops/print/incidents` 得 403**：不是权限问题。这两个接口必须带 `tenantNo`、`entityNo`、`storeNo`，打印模块按门店判可见范围，没带就拒。带上测试门店后超管 200；客服被网关按 `ops:print:read` 拒，判定正确。

### 同晚续：合并后的版本上线 v2

- v2 换成包 `shop-app-50d549bf4.jar`（合并主干后的分支，PR robinwu2021-code/ai-shop#20；容器标签 `gateway-merge-50d549bf4`），备份 .80 `run.sh.before-merge-20261119`。
  重启一次，82 秒起好，没有报错，已重新登记到 Nacos。没有新迁移（主干最新 V678，分支 V679–V682 早已在正式库）。
- 运营端页面换成 166 `/data/www/ai-shop-ops-v2/releases/gateway-50d549bf4`（`current` 已指过去），「接口资源」页有了「调整规则」。
- 回退：.80 复原 `run.sh.before-merge-20261119` 后 `./run.sh all restart`；166 把 `current` 指回 `releases/gateway-410f2cc99`。
- 接口验证（`verify_v2_gateway.py`）：运营端受限角色该拒 177 条、实际拒 177 条；C 端 32 条匿名被拒、带令牌后 0 条被误拒；三端密钥互不通用，令牌跨端全部被拒。
  要看的 8 条都是已知原因：
  - `/ops/print/health`、`/ops/print/incidents`：探测没带门店参数（见上一节），不是权限问题；
  - `/biz/pay/v2/*` 6 条 502：v2 的 `NEARPAY_BIZ_ORIGIN` 是 `127.0.0.1`，从 .80 连不到 166 上的支付进件服务。比上次多的 `merchant-channels` 是主干新加的接口，同一个原因。要修得把它指到 166 上可达的支付进件地址，涉及支付那边对内网开口，没动。
- B 端店主与店员（`verify_v2_biz_perms.py`，报告 `verify-B端权限-合并后.md`）：191 个 GET，**店员的判权全部一致**（该拒且被拒 90，该放且放行 30）。
  店主放行后仍被拒的从 69 条降到 35 条（之前开放了门店准入）。新出现的 `/biz/payment/store-method`、`/biz/payment/store-method-preference`
  是主干新加的接口，转发到支付侧后被支付侧的调用方校验拒了（70082），和已有的 7 条同类，属于业务层，网关已放行。

### 同晚续：剩余开发项做完，统一验证（包 `db8cc5405`）

- 做了什么：五类对象接入登记（`IntegrationRegistry`，关掉的对象前缀 404，启动时五条校验）；库存签名上下文（网关签、库存只比对）；
  `mch:pay:store-method` 查清不映射；验证脚本给打印接口带门店参数。各项细节见开发计划 §2.6b 与方案 §2.22「落地情况」。第 17 项（各端配置按端取值）复核后不改，理由见方案 §2.28。
- near-pay 的签名动作比对已进 `develop/s1`（`ecc3c6ec1`），**未部署**。
- v2 换成包 `shop-app-db8cc5405.jar`（标签 `gateway-integrations-db8cc5405`），备份 .80 `run.sh.before-integrations-20261119`；81 秒起好，没有报错。
  启动日志：`网关代理对象：retail=开 embedded; commerce=开 embedded; food=关; beauty=关; pay=开 standalone; inventory=开 embedded`。运营端页面没有变化，未重新发布。
  回退：复原 `run.sh.before-integrations-20261119` 后 `./run.sh all restart`。
- 测试：ai-shop 全量 9063 条里 37 个类失败，把这 37 个类在 `origin/main` 上单独跑，逐类失败数完全相同（多数是本机测试环境发不出验证码），本批没有引入新失败；
  `PayStandaloneAssemblyTest` 因新的启动校验需要支付地址，补上与生产一致的地址后通过。库存模块、near-pay 守卫与策略的测试全部通过。
- 接口验证（报告 `verify-三端-接入登记后.md`、`verify-B端权限-接入登记后.md`）：运营端该拒 177 条实拒 177 条；C 端匿名 32 条被拒、带令牌 0 条误拒；
  三端密钥与令牌互不通用；B 端店员判权全部一致，店主仍被拒的 35 条与上次逐条相同；打印接口不再误报。剩下 6 处都是已知的 `/biz/pay/v2/*` 502（进件服务连不到）。
  抽查：`/biz/x/food/**`、`/biz/x/beauty/**` 回 404（对象关着），`/biz/goods` 正常；v2 的包里没有 `/x/commerce` 接口（商城插件未打进 v2），所以不存在被误封的商城接口。

### 同晚续：运营端配置与测试（用户：「完成运营端的配置和测试」）

- **接口资源页本机实测**（工作树的包 + H2 内存库与种子账号，不碰任何共用库）：调整规则 → 网关按新规则拒（日志 `提前判权拒绝 … 缺 [system:app-version:update]`）→ 撤销后放行；
  写错的权限式被拒、库里不变；停用后超管也得到 404、恢复后正常。「角色权限」页新建角色、勾功能点后对账号立即生效。
  修了两处体验：切到「需要权限码」时带入原规则；写错时提示「权限式读不懂」而不是「请求参数有误」。
- **逐角色测试账号**：14 个运营角色各一个（`ST-V2TEST-<角色>`，建号与清理 SQL 见 `v2test-ops-roles.sql`），已加进 v2 的自动化登录名单
  （`SHOP_AUTOMATION_SUBJECTS`，备份 `ai-shop-all-v2.env.before-opsroles-20261119`）。
- **发现并修了一处网关没接管的权限**：商城插件 46 个运营端接口没有入口注解，权限写在方法体里（`context.operator(码)`），
  被当成登录一类的会话外壳锁住、不能在库里调规则，所以对所有非超管角色是业务代码回 70006。改为：调规则只锁会话外壳（登录、验证码、当前用户、菜单等），
  停用的锁不变（提交 `2b2e1436b`）；v2 换成包 `shop-app-059658126.jar`（备份 `run.sh.before-adjustlock-20261119`），页面换成 `releases/gateway-059658126`；
  然后把 46 条按代码里实际判的码在库里配成需要权限码（`commerce:catalog:read/edit/publish`、`commerce:mall:*` 等，判法与 `Permissions.matches` 同口径）。
- **验证**（`verify_v2_ops_roles.py`，按库里生效的规则比对）：14 个角色每个都 0 处不一致（报告 `verify-运营端逐角色.md`）；三端验证运营端该拒 187 实拒 187，余下 6 处是已知的进件 502。
- **运营端浏览器登录被跨域拦截**（用户在 v2 运营端页面登录得到「无权限」）：浏览器请求带 `Origin: https://nearshop.umeshop.ai`，
  而 v2 的 `SHOP_CORS_EXTRA_ORIGINS` 只有 `https://twpay.umeshop.ai`，后端回 403 `Invalid CORS request`。此前一直用脚本验证（不带 Origin），所以没暴露。
  已在 v2 环境文件追加 `https://nearshop.umeshop.ai`（备份 `ai-shop-all-v2.env.before-cors-20261119`）并重启；v1 未动。
  B/C 端若有从这个域名打开的网页（H5）会遇到同样的问题，这一项配置也覆盖了它们。

### 对外域名：`tw.neargo.ai` 为首选（2026-11-19 用户）

| 入口 | 首选地址 | 仍可用 |
|---|---|---|
| 运营端页面 | `https://tw.neargo.ai/ops-shop-v2/` | `https://nearshop.umeshop.ai/ops-shop-v2/` |
| B 端接口 | `https://tw.neargo.ai/shop-api-v2/biz/**` | `nearshop.umeshop.ai` 同路径 |
| C 端接口 | `https://tw.neargo.ai/shop-api-v2/mp/**` | `nearshop.umeshop.ai` 同路径 |

- nginx 本来就把两个域名配在同一个站点（166 `nearshop.umeshop.ai.conf` 的 `server_name nearshop.umeshop.ai tw.neargo.ai`），证书有效，**nginx 与证书未改**。
- v2 允许的浏览器来源改为 `SHOP_CORS_EXTRA_ORIGINS=https://tw.neargo.ai,https://nearshop.umeshop.ai,https://twpay.umeshop.ai`（首选放第一位；备份 `ai-shop-all-v2.env.before-twneargo-20261119`），已重启；名单外的来源仍回 `Invalid CORS request`。v1 未动。
- 验证脚本默认入口改为 `https://tw.neargo.ai`（`V2_PUBLIC_BASE` 可覆盖）；三端用自动化票据经它登录都成功。
- **没有改的**：`SHOP_PAY_CHECKOUT_BASE_URL`、`SHOP_PAY_OPS_PUBLIC_BASE_URL` 仍是 `twpay.umeshop.ai`——那是支付服务自己的站点（与 `tpay.neargo.ai` 同一个 nginx 站点），不是商城域名。
  `SHOP_WEB_BASE_URL`（分享链接、店铺码落地页）v1、v2 都没配，所以不发这类链接；这个域名下的 `/s/<码>` 目前被 nginx 转去支付收银台，
  若现在配成 `tw.neargo.ai` 发出去的链接会落到收银台，所以不配，等商城落地页在 nginx 上有了自己的路径再定。

### 运营端页面逐项测试（2026-11-19，用户在 `https://tw.neargo.ai/ops-shop-v2/` 用自己的超管账号登录）

**用户/权限**（写操作只对【网关验证】测试数据做，做完复原；审计日志里 8 条操作齐全，带变更前后）：

| 项 | 结果 |
|---|---|
| 登录、当前用户、菜单 | 通过（先修了跨域来源，见上节） |
| 运营账号：列表、搜索、角色与状态筛选、翻页 | 通过 |
| 运营账号：停用 → 该账号换不到会话 → 启用 → 能登录 | 通过（停用没有二次确认） |
| 运营账号：改角色（加客服 → 权限 27→39 → 去掉） | 通过 |
| 运营账号：新建 | **未提交**：登录名必须是邮箱，保存会往该邮箱发初始密码邮件，需用户同意并给可收信的地址 |
| 角色权限：列表、角色矩阵 | 通过 |
| 角色权限：新建测试角色 → 配功能点 → 改名 → 删除 | 通过 |
| 菜单配置 | 显示正常；上下移动会改全体用户的生产菜单，未操作 |
| 审计日志 | 通过 |
| 接口资源：列表、筛选、商城 46 条显示「已调整」、调整抽屉带入原规则 | 通过（未保存，生产规则未改；写操作已在本机完整测过） |

**其余 146 个已上线页面**：逐页打开（只读），收集后端请求失败与页面错误字样。

| 结论 | 页面 |
|---|---|
| 正常 | 绝大多数页面 |
| 要先输入查询条件才查（设计如此） | 购买订单、库存流水、商家欠款、打印运营；开放对接是签发表单 |
| 预期的 404 | 运行监控 `/ops/jobs`、库存对账日志 `/ops/jobs/inv-recon/logs`（菜单标「后端尚未实现」，v2 也关着任务开关） |
| **真问题：退款回补页「加载失败」** | `/ops/refund-split-backs` 查售后单时报 MySQL 1267 `Illegal mix of collations (utf8mb4_unicode_520_ci) and (utf8mb4_0900_ai_ci)`：正式库两张表排序规则不一致。与网关无关，v1 连同一个库也会遇到。修要 root 在正式库改表排序规则 |

**发现的其它问题**（都不是网关层的）：

1. 运营账号的角色筛选下拉写死了 12 个预置角色，缺库里的「入驻审核」与两个 near-pay 集成角色。（旧码 `CS`/`MERCHANT_BD`/`PRODUCT_OPS` 后端兼容，筛选结果正确。）
2. `NP_IT_CONFIG` 与 `NP_IT_REVIEW` 两个角色名称都叫「Nearpay integration」，在账号编辑里分不出来。
3. 用户自己的账号带「必须修改密码」标记，但登录后页面没有提示改密码。
4. `GET /ops/merchants` 慢：4.5～9 秒（`api.slow` 日志）。
5. 运营端推送 `/ops-shop-v2/ops/stream` 走通用转发（读超时 100 秒、开缓冲），空闲 100 秒后回 504，前端重连；B 端推送有专门的 location（关缓冲、3600 秒），运营端缺一个。
6. 验证脚本打 GET 时碰到 `GET /ops/payables/payout-list`（下载付款清单），在生产审计留了两条「导出付款清单」高危记录（0 家 0 分）；脚本已加入跳过。

### 三个浏览器页面测试用户（2026-11-19，用户：「创建 2-3 个测试用户，设置不同的角色……从菜单展示到页面点击，都通过 v2」）

| 编号 | 登录名 | 角色 | 权限码 | 菜单项 |
|---|---|---|---:|---:|
| `ST-V2UI-CS` | `gw-cs@example.invalid` | 客服 | 17 | 29 |
| `ST-V2UI-FINANCE` | `gw-finance@example.invalid` | 财务 | 20 | 31 |
| `ST-V2UI-GOODS` | `gw-goods@example.invalid` | 商品运营 | 27 | 40 |

- 直接写库（`v2test-ui-users.sql`，含清理语句），不走新建员工流程，所以不发邮件；免二次验证、不强制改密；已加进 v2 自动化登录名单（备份 `ai-shop-all-v2.env.before-v2ui-20261119`）。
- **密码由用户自己设**：`scripts/automation/set_test_staff_password.py`，在用户终端里输入（不回显），本机算 bcrypt 后只把哈希写库；只认 `ST-V2UI-` / `ST-V2TEST-` 开头的测试账号。生产上 Claude 不设、不输入任何密码。
- 菜单核对（按角色勾的功能点 vs 持有的码）：客服、商品运营完全对应；财务有 `pay:order:view` 但角色没勾「订单 → 支付单」「订单 → 支付异常」两个菜单点，接口能访问、菜单看不到——角色配置问题，要不要补由用户定。

### 门店、商家、渠道设置（2026-11-20，用户超管会话，`https://tw.neargo.ai/ops-shop-v2/`，只读）

| 页面 | 结果 |
|---|---|
| 商户中心 → 商家档案：列表 17 条 | 正常；**列表接口 `/ops/merchants` 要 6.2 秒**（与前面 `api.slow` 记录一致） |
| 商家详情（测试主体 `M261119TS0PN0XBK9`）：商家档案、履约配置、人员与授权、支付服务 | 四个标签都正常，接口约 0.2 秒 |
| 支付服务（已开户的 `M261114Y28YADQANZ`） | 申请 APPROVED、平台收款商户号、来源核验 VERIFIED，两个跳支付运营端的链接（`twpay.umeshop.ai/oms/...`）正常；未开户的主体显示「尚未配置支付运营端访问地址」是因为没开户，不是配置缺失 |
| 店招审核 | 待审队列为空，正常 |
| 门店档案：列表、测试门店 `ST261119BE70D8B9C9` 的基本信息、经营状况、订单、经营审核、覆盖与取货 | 全部正常，接口约 0.2 秒 |
| 支付渠道 | 只有「打开支付渠道」按钮，跳同域 `/oms/`（near-pay 运营端）；`/oms-api/pay-ops/v2/me/access` 200 |
| 渠道费率、渠道报文 | 能打开但**都是空的**（「还没有配置支付通道」）：实际通道与费率配在独立的 near-pay 里，ai-shop 这两张表在正式库一直没用；v1 同一个库，结果相同，不是 v2 的问题 |

写操作没做：门店与商家的写操作（提交经营审核、改履约配置、门店下线等）会同步到支付系统，测试数据上也撤不干净，等用户点头再做。

### 运营端页面按角色（浏览器，三个 UI 测试账号，2026-11-20）

报告：[verify-运营端页面按角色.md](verify-运营端页面按角色.md)。客服 24 页、财务 30 页、商品运营 39 页逐页打开；每人另直接打开 10 个菜单外的页面。

- **网关判权：三人漏判 0、误拒 0**；侧栏都没露出不该看的页面。
- 「页面依赖了他没有的权限」都是网关拒得对：多数在越权页上；菜单内的两处是页面顺带调了别的模块的读接口（财务的渠道费率调 `/ops/markets` 要 `system:param:read`；商品运营的商品管理调 `/ops/merchants` 要 `merchant:merchant:read`），页面会缺一块（下拉选项之类），要不要给这两个角色补码由用户定。
- 菜单外的页面只有 3/10 在前端层面拦住，其余靠接口被网关拒成空页——数据不漏，体验是空白页，属前端改进项。
- 两处加载失败，都是已知、与网关无关：
  1. 财务 → 退款分账 `/ops/refund-split-backs` 10500：正式库排序规则不一致（前面已记，需 root 改表）。
  2. 商品运营 → 库存对差 `/ops/jobs/inv-recon/logs` 404：v2 的 `SHOP_JOB_ENABLED=false`，任务控制器整个没装（`OpsJobController` 依赖该开关）——即「正式环境业务任务哪儿都没跑」那一条；页面应在任务模块关闭时降级显示，而不是报错。

### 给财务、商品运营补跨模块读码（V683，2026-11-20）

用户要求补上页面验证里「菜单内页面缺一块」的两处：

| 角色 | 补的码 | 为哪一页 | 授予方式 |
|---|---|---|---|
| 财务 FINANCE | `system:param:read` | 经营财务 / 渠道费率 读 `/ops/markets` | 现有仅后端动作点 `ACT__SYSTEM_PARAM_READ` |
| 商品运营 GOODS_OPS | `merchant:merchant:read` | 商品资料 / 商品管理 读 `/ops/merchants` | 新建仅后端动作点 `ACT__MERCHANT_MERCHANT_READ`（超管同时授予） |

- 只授动作点、不授菜单点：两人的菜单没有多出系统设置或商家档案（核对过 `/ops/menu`）。
- 代码：`V683__ops_role_cross_module_reads.sql`、`Perms.ROLE_PERMS` 两行（`OpsPermConfigFlowTest` 逐条比对库与代码）、H2 schema 重新生成；`OpsPermConfigFlowTest` / `SchemaGeneratorTest` / `SchemaDriftTest` 43 条通过。
- **正式库**：已用 root 直接执行 V683 的语句（纯插入、可重复执行）。迁移历史**没有**手工登记：下次部署时 Flyway 会把 V683 当待执行跑一遍，语句都带 `NOT EXISTS`，不会重复插。回滚：删 `sys_role_point` 里这三行和 `sys_function_point` 的 `ACT__MERCHANT_MERCHANT_READ`。
- 复验：`ST-V2UI-FINANCE` 调 `/ops/markets`、`ST-V2UI-GOODS` 调 `/ops/merchants` 都已 200（之前网关拒）；权限即时生效，不用重登。
