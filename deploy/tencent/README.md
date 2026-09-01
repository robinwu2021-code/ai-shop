# ai-shop · 腾讯云部署（交接文档）

> 状态截至 **2026-08-18**。**后端 + 三个前端已全部上线并验证通过**。
> 线上：https://www.hxmall.top

## 1. 目标机

| 项 | 值 |
|---|---|
| 产品 | **轻量应用服务器 Lighthouse**（非 CVM） |
| 实例 ID | `lhins-98lm5asj` · 地域 `ap-guangzhou`（**大陆地域 → 受备案约束**） |
| 公网 IP | `106.55.27.246`（**Lighthouse 不能绑 EIP，IP 与实例绑死**） |
| 系统 | Ubuntu 24.04.4 LTS · 内存 7.5 GB · 磁盘 59 GB（已用 8.7 G） |
| 域名 | `www.hxmall.top`（DNSPod 托管） |

## 2. 线上拓扑

```
nginx(443/80) ──┬─ /            → /var/www/ai-shop/site     静态（Next.js export·官网）
   唯一入口     ├─ /c/          → /var/www/ai-shop/c-app    静态（uni-app H5·社区好物）
                ├─ /b/          → /var/www/ai-shop/b-app    静态（uni-app H5·邻里商家）
                ├─ /ops-web/    → /var/www/ai-shop/ops-web  静态（Next.js export·平台运营端）
                ├─ /s/<code>    → 302 /c/                   老店铺码链接的退路
                └─ /mp /biz /ops /actuator → 127.0.0.1:8081  shop-app.jar (systemd)
MariaDB 12.3.2（本机 3306 · 库 ai_shop · 115 张表 · Flyway v164）
```

| 组件 | 版本 | 运行方式 |
|---|---|---|
| 后端 | Spring Boot 4.0.7 · Java 21.0.11 | `systemd: ai-shop` · 端口 **8081** |
| MariaDB | **12.3.2**（官方源最新版，非 Ubuntu 自带的 10.11） | `systemd: mariadb` |
| nginx | 1.24.0 | `systemd: nginx` |
| Node | 20.20.2 | 仅构建期用 |

**端口是 8081 不是 application.yml 里写的 8080** —— profile 覆盖所致，nginx 按实测值配。

## 3. 落位

| 路径 | 内容 |
|---|---|
| `/opt/ai-shop/shop-app.jar` | 后端可执行 jar |
| `/opt/ai-shop/shop-app.env` | 运行时环境（600，含真实凭据） |
| `/etc/systemd/system/ai-shop.service` | 服务单元 |
| `/var/lib/ai-shop/sessions` | ehcache 会话（重启不掉线） |
| `/var/log/ai-shop/app.log` | 应用日志 |
| `/var/www/ai-shop/{c-app,b-app,ops-web}` | 三个前端静态产物 |
| `/opt/build/ai-shop` · `/opt/build/ai-neargo` | 构建工作区（源码 rsync 上来） |
| `/etc/nginx/sites-available/www.hxmall.top` | 站点配置 |

## 备份

每天 03:20 由 `/etc/cron.d/ai-shop-backup` 跑 `/opt/ai-shop/backup-to-cos.sh`：
`mariadb-dump`（`--single-transaction`，不锁表）→ gzip → 上传 `hxmall-backup-1301656997/db/`，
本机只留最近 3 天。桶上配了生命周期：30 天转低频、90 天转归档、365 天删。

```bash
sudo /opt/ai-shop/backup-to-cos.sh          # 手动跑一次
tail -20 /var/log/ai-shop/backup.log        # 看结果
```

脚本源文件在仓库 [deploy/tencent/backup-to-cos.sh](./backup-to-cos.sh)，改完要重新 install 到 /opt。

## 商家端 App 分发（COS `download` 桶）

> 全平台的桶怎么划分、为什么只要三个，见 [cos-buckets.md](./cos-buckets.md)。
>
> 状态（**2026-08-28 复验，结论未变**）：**桶已建、包已传，但默认域名不能用来分发 APK。**
>
> 复验命令（要从**公网出口**跑，不能在服务器上跑）：
>
> ```bash
> curl -s https://hxmall-download-1301656997.cos.ap-guangzhou.myqcloud.com/latest.apk | head -c 200
> # → <Code>DownloadForbidden</Code>
> ```
>
> **上传不受这条限制** —— 挡的只有公网下载。所以包该传还是要传：
> COS 是异地存档（服务器没了它还在），备案下来之后官网换一行直链即可，包不用重传。
> 当前桶里有 `b-app/hxmall-merchant-0.4.32-159.apk`（版本存档）与 `latest.apk`（稳定键），
> ETag 与本地 md5 逐字一致。
>
> 没装 `coscli` 也不必装 SDK：签名 v5 手写三十行就够，见本次会话用的
> `cosput.py`（PUT 一个对象，打印 ETag 与本地 md5 供比对）。
>
> ## ⚠️ COS 默认域名禁止分发 APK / IPA
>
> 包传上去了（`b-app/hxmall-merchant-0.1.0.apk`，ETag 与本地 md5 一致），
> **但公网取回是 403**：
>
> ```
> <Code>DownloadForbidden</Code>
> <Message>The APK/IPA file is not allowed to be distributed in a public network
> using COS default domain, please use custom domain instead.</Message>
> ```
>
> **从服务器上取是 200** —— 它在腾讯云内网，不受这条限制。
> 只用服务器验证会得出「能下载」的错误结论，**必须从公网出口验一次**。
>
> 解封要绑**自定义域名**，而大陆地域桶的自定义域名**要备案**。备案没下来之前这条路走不通。
>
> ### 当前的做法：服务器直出
>
> 包放 `/var/www/ai-shop/dl/`（**不在 `/var/www/ai-shop/site/` 里** —— 官网发布用
> `rsync --delete` 同步 site 目录，放进去每次发官网都会被删掉），
> nginx 用 `location ^~ /dl/` 直出，`www.hxmall.top` 与 `ai-shop-ip` **两个 server 块都要有**。
>
> `site.config.download.merchantAndroid` 填**相对路径** `/dl/xxx.apk`：
> 备案未过时商家多半从 `http://<IP>/` 进来，写死 https 的绝对地址会让 IP 入口的下载
> 跳到一个他打不开的地方。
>
> 备案下来、自定义域名绑好之后，把 `site.config` 换成直链即可，页面不用改。

### 桶

| 项 | 值 | 为什么 |
|---|---|---|
| 名称 | `hxmall-download-1301656997` | COS 桶名必须以 `-APPID` 结尾；与现有 `hxmall-merchant-1301656997` 同一账号 |
| 地域 | `ap-guangzhou` | 与服务器同地域（Lighthouse 在广州），且大陆地域受备案约束 —— 与现有桶保持一致 |
| 访问权限 | **公有读、私有写** | 安装包要能被任何人下载；写入只走密钥 |
| 版本控制 | 关 | 用文件名带版本，见下 |

**与媒体桶分开是有意的**：媒体桶存的是商家上传的商品图，权限、生命周期、防盗链策略都不一样；
把安装包放进去，将来给媒体桶加防盗链会把下载一起挡掉。

### 目录与命名

```
b-app/
  hxmall-merchant-0.1.0.apk     版本存档，永不覆盖
  latest.apk                    稳定链接，每次发版覆盖
```

官网写的是 `latest.apk` —— **不要在官网写带版本号的地址**，否则每次发版都要改一次站点并重新部署。
版本号通过 `site.config.download.merchantAndroidVersion` 显示，便于商家看出下的是哪一版。

### 三个坑

1. **`latest.apk` 的缓存要短。** COS 默认缓存较长，覆盖之后用户可能下到旧包。
   上传时显式设 `Cache-Control: public, max-age=300`；版本存档那份可以设长。
2. **Content-Type 必须是 `application/vnd.android.package-archive`。** 给错类型
   有些浏览器会当文本打开或直接改扩展名，用户拿到一个装不上的文件。
3. **微信内打不开 APK 直链** —— 这是微信的策略，不是链接坏了。下载页已写明「请用手机浏览器打开」；
   给商家发链接时也要带上这句，否则第一反应是「你们的下载坏了」。

### 发布一版

```bash
# 1) 打包（本机）
cd android-shell && ./gradlew :app:assembleMerchantRelease

# 2) 上传（需要 COS 密钥；coscli 或控制台均可）
VER=0.1.0
coscli cp app/build/outputs/apk/merchant/release/app-merchant-release.apk \
  cos://hxmall-download-1301656997/b-app/hxmall-merchant-$VER.apk \
  --meta "Content-Type:application/vnd.android.package-archive"
coscli cp cos://hxmall-download-1301656997/b-app/hxmall-merchant-$VER.apk \
  cos://hxmall-download-1301656997/b-app/latest.apk \
  --meta "Content-Type:application/vnd.android.package-archive#Cache-Control:public, max-age=300"

# 3) 官网：填 site.config.download.merchantAndroid 与 merchantAndroidVersion，重新部署
```


## 4. 部署流程（服务器上自构建）

私有依赖 `ai.neargo:*` 不在公开仓库，已把 **ai-neargo 的 `commons/` 源码**传到服务器
`mvn install` 进本机 `~/.m2`，之后服务器可独立构建，不依赖任何人的笔记本。

```bash
# 1) 同步源码（本机执行）
rsync -az --delete --exclude 'node_modules/' --exclude 'target/' --exclude '.git/' \
  --exclude '.next/' --exclude 'out/' --exclude 'dist/' --exclude 'android-shell/' \
  ~/work/ai/ai-shop/ soukmind-tx:/opt/build/ai-shop/

# 2) 后端（服务器）
ssh soukmind-tx 'cd /opt/build/ai-shop/backend && \
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn package -DskipTests -B'
ssh soukmind-tx 'sudo cp /opt/build/ai-shop/backend/shop-app/target/shop-app-0.1.0-SNAPSHOT.jar \
  /opt/ai-shop/shop-app.jar && sudo systemctl restart ai-shop'

# 3) 前端（服务器）
#    ⚠ 官网构建要读两处仓库内的文件，rsync 时别排除掉：
#      · site/content/**.md      正文（2026-08-20 起内容与代码分离）
#      · brand/logo/mark-red.svg 页头标识的几何（由 brand/build.py 生成）
#      少任一个都是构建期直接报错，不会静默出一个缺内容的站。
#    ⚠ site/fonts/src/ 可以排除（14 MB 源字体）：中文子集产物已进仓库，
#      文案没动时 prebuild 会跳过重新子集化，服务器不必装 fontTools。
#    ⚠ C 端的 H5_BASE 是 /c/ 不是 / —— 根路径 2026-08-19 起归官网
ssh soukmind-tx 'cd /opt/build/ai-shop && \
  npm run build -w ai-shop-site && \
  NEXT_PUBLIC_BASE_PATH=/ops-web NEXT_PUBLIC_API_BASE= NEXT_PUBLIC_USE_MOCK=0 \
    npm run build -w ai-shop-ops-web && \
  H5_BASE=/c/ npm run build:h5 -w ai-shop-c-app && \
  H5_BASE=/b/ npm run build:h5 -w ai-shop-b-app && \
  sudo rsync -a --delete site/out/              /var/www/ai-shop/site/ && \
  sudo rsync -a --delete ops-web/out/           /var/www/ai-shop/ops-web/ && \
  sudo rsync -a --delete c-app/dist/build/h5/   /var/www/ai-shop/c-app/ && \
  sudo rsync -a --delete b-app/dist/build/h5/   /var/www/ai-shop/b-app/'
```

### ⚠️ 发完必须验一句：生产 env 里不许有 IP 字面量

```bash
ssh soukmind-tx 'sudo grep -hoE "(jdbc:mysql://|http://)[0-9]{1,3}(\.[0-9]{1,3}){3}" \
  /opt/ai-shop/shop-app.env /opt/ai-shop-job/job.env'
# 期望：**没有输出**。有输出就是有人又写了 IP。
```

内部地址一律用名称（`db.svc.internal` / `platform.svc.internal` / `pay.svc.internal`），
映射在服务器 `/etc/hosts` 里 —— **换 IP 那天只改那一处**，
各服务的配置与代码都不动（见 [ADR-023](../../docs/technical/ADR/ADR-023-服务发现先不装中间件.md)）。

**为什么要单列一步检查**：仓库里的默认值仍是 `127.0.0.1`（那是给开发机的），
所以生产漏配某一项时会静默退回 IP —— 而在单机上它恰好能工作，
**漏配要等到换 IP 那天才暴露**，那时的症状是「某个服务连不上，而别的都好」。

加新服务时：先在 `/etc/hosts` 加一行，再在 env 里用名称。

### ⚠️ 发完必须验一句：运营端连的是真后端还是 mock

```bash
ssh soukmind-tx 'curl -sk -H "Host: www.hxmall.top" https://localhost/ops-web/ | grep -o "x-api-mode\" content=\"[a-z]*"'
# 期望 http；若是 mock，就是构建漏了 NEXT_PUBLIC_USE_MOCK=0 —— **重新构建，别只重发**
```

**为什么单列一步**：`NEXT_PUBLIC_USE_MOCK` 的默认值是 mock（`!== "0"`），
所以**漏配不会报错，只会静默退回 mock**。

2026-09-01 就是这么踩的：线上运营端跑了两天 mock 没人发现，
症状是「admin 登录提示无权限」—— 而请求根本没发给后端，
`ops_login_log` 里一条记录都没有。查判权、查角色、查权限点，全是好的。

上面那条构建命令**一直写着 `NEXT_PUBLIC_USE_MOCK=0`**，那次部署还是漏了 ——
所以这里不再靠「记得照着敲」，而是发完探一下产物。
标记由 `ops-web/app/layout.tsx` 输出，随构建固化在 HTML 里。

### 官网接管根路径（2026-08-19）

`/` 从 C 端 H5 换成官网，C 端移到 `/c/`。**这会动到已经发出去的链接**，两条退路都做了：

| 老链接形态 | 退路 | 为什么要这条 |
|---|---|---|
| `https://www.hxmall.top/s/<code>`（店铺码，后端 `SHOP_WEB_BASE_URL` 拼的） | nginx `location ^~ /s/ { return 302 /c/$is_args$args; }` | 路径服务器可见，能重定向。这些码可能已经印在包装上 |
| `https://www.hxmall.top/#/pages/…`（C 端分享） | 官网 `<head>` 里的内联脚本判 `location.hash`，跳 `/c/#/pages/…` | **hash 不发给服务器**，nginx 看不见，只能在浏览器里判 |

回滚（两步，不用重新构建官网）：

```bash
ssh soukmind-tx 'sudo cp /etc/nginx/sites-available/www.hxmall.top.bak-<stamp> \
  /etc/nginx/sites-available/www.hxmall.top && sudo nginx -t && sudo systemctl reload nginx'
ssh soukmind-tx 'cd /opt/build/ai-shop && H5_BASE=/ npm run build:h5 -w ai-shop-c-app && \
  sudo rsync -a --delete c-app/dist/build/h5/ /var/www/ai-shop/c-app/'
```

上线时的 c-app 备份在 `/var/www/ai-shop/c-app.bak-<stamp>`（stamp 见部署当天），
nginx 旧配置在 `/etc/nginx/sites-available/www.hxmall.top.bak-<stamp>`。

### 备案未过期间：用 IP 直连

`http://106.55.27.246/` 是 `ai-shop-ip`（`default_server`，只有 80，不跳 https ——
证书是 `*.hxmall.top`，用 IP 走 443 必然告警）。它的 location **与 `www.hxmall.top` 逐条对齐**：

| 路径 | IP 入口 | 域名入口 |
|---|---|---|
| `/` | 官网 | 官网 |
| `/c/` `/b/` `/ops-web/` | 同 | 同 |
| `/s/<code>` | 302 → `/c/` | 同 |
| `/mp /biz /ops /actuator /uploads /media` | 反代 8081 | 同 |

**改一处必须改两处。** 2026-08-19 官网接管根路径时就漏改过这份：`/` 还指向 c-app，
而 c-app 已改成 `/c/` 基址 —— 结果 `index.html` 出得来、`/c/assets/*.js` 被 `try_files`
兜成 HTML，页面白屏而所有状态码都是 200。

> **`sites-enabled/ai-shop-ip` 曾经是实体文件副本，不是软链。**
> 于是改 `sites-available/ai-shop-ip` 后 `nginx -t` 通过、reload 成功、行为纹丝不动 ——
> 因为 nginx 读的是 `sites-enabled` 里那份陈旧副本。已改成软链（2026-08-19）。
> 排查时先跑：`diff /etc/nginx/sites-enabled/X /etc/nginx/sites-available/X`。

验证（**不能只在服务器上 curl 127.0.0.1**，那绕开了真实链路）：

```bash
curl --noproxy '*' -s -o /dev/null -w "%{http_code}\n" http://106.55.27.246/
curl --noproxy '*' -s http://106.55.27.246/ | grep -o '<title>[^<]*</title>'
```

2026-08-19 实测：`/` 200「虹选 · 好物 — 社区邻里电商」· `/c/` 200「社区好物」·
C 端资源 `application/javascript` 424 KB · 官网 CSS `text/css` 30 KB ·
`/s/X` 302 → `/c/` · `/nope/` 404 · `/actuator/health` UP。**80 端口未见劫持或注入。**

**地基升级时**（ai-neargo 的 commons 有改动）：

```bash
rsync -az --exclude 'target/' ~/work/ai/ai-neargo/pom.xml ~/work/ai/ai-neargo/commons \
  soukmind-tx:/opt/build/ai-neargo/
ssh soukmind-tx 'cd /opt/build/ai-neargo && JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -N install -DskipTests && \
  cd commons && JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn install -DskipTests -B'
```

> `/opt/build/ai-neargo/commons/pom.xml` 是**部署侧生成的聚合 pom**（仓库里没有），
> 让 Maven 自己算 9 个 commons 模块的构建顺序。rsync 时勿用 `--delete` 覆盖掉它。

## 5. 三个**必须由部署侧覆盖**的配置

仓库默认值直接上生产是错的，已在部署侧处理，改动构建流程时勿丢：

| 配置 | 仓库默认 | 生产必须 | 原因 |
|---|---|---|---|
| `SPRING_FLYWAY_PLACEHOLDER_REPLACEMENT` | （未设） | `false` | 见 §7 缺陷 ① |
| `c-app/.env.production`·`b-app/.env.production` 的 `VITE_API_BASE` | `.env` 里写死 `http://127.0.0.1:8081` | 留空（同源） | Vite 的 `VITE_*` 只从 `.env` 文件读，**shell 环境变量覆盖不了**；不覆盖就把本地回环地址烧进生产包 |
| ops-web 的 `NEXT_PUBLIC_API_BASE` | — | 留空（同源） | 经 nginx 反代到 `/ops/**`，后端刻意没配 CORS |

## 6. 凭据

**全部在仓库外**：`~/work/env/tencent/`

| 文件 | 内容 |
|---|---|
| `tencent.env` | 腾讯云 API 密钥（子用户 `deploy-user`） |
| `ai-shop.env` | 数据库账号密码（32 位随机生成） |
| `soukmind_tx(.pub)` | SSH 部署密钥（名字是历史遗留） |

服务器上 `/opt/ai-shop/shop-app.env`（600）含从本机 `backend/.env.local` · `.env.mail.local` ·
`.env.sms.local` 原样搬运的真实凭据，共 27 个变量。

```bash
ssh soukmind-tx        # deploy 用户（免密 sudo）
ssh soukmind-tx-root   # 救火通道
```

## 7. 部署中发现的两个真缺陷（仓库侧待修）

### ① V121 的注释让全新库无法初始化

`V121__growth_attribution_rule_and_fission.sql:5` 注释里写了 `@Value("${shop.attribution.window-days:30}")`，
**Flyway 连注释里的 `${}` 也当占位符解析**，报 `No value provided for placeholder`。

任何全新库首次启动必挂。现有库因为在 V121 之前就建好了，一直没暴露。

部署侧用 `SPRING_FLYWAY_PLACEHOLDER_REPLACEMENT=false` 绕开（全库仅此一处 `${}`）。
**没有改迁移文件** —— `validate-on-migrate: true`，改注释会变 checksum，修一个环境会弄坏另一个。
根治建议：在 `application.yml` 里显式设 `spring.flyway.placeholder-replacement: false`。

### ② 建表 collation 依赖 MariaDB 版本

97 张表写 `DEFAULT CHARSET=utf8mb4`，其中 **81 张显式 `COLLATE=utf8mb4_uca1400_ai_ci`，
另 16 张没写**。而 `CHARSET=utf8mb4` 不带 `COLLATE` 时用的是**字符集的默认排序规则**
（不是库的默认值），这个值随 MariaDB 版本变：

- MariaDB 10.11（Ubuntu 24.04 自带）→ `utf8mb4_general_ci` → **V150 迁移在 JOIN 时报 1267 Illegal mix of collations**
- MariaDB 11.8 / 12.x（本机是 12.2.2，服务器 12.3.2）→ `utf8mb4_uca1400_ai_ci` → 一致，正常

已把服务器 MariaDB 换成官方源 **12.3.2** 对齐开发环境（noble 上可装的最新版；11.8 是 LTS，12.3 是短期版，支持期约一年）。
根治建议：给那 16 张表补上显式 `COLLATE`，别让建表结果取决于服务器版本。

> 补充：V150 那段回填 DML 的注释自己就写着「测试库只重放 DDL 不跑 DML，
> 这一条在 CI 里从来不会被执行，上生产前必须在预发库单独验一次」—— 这次就是那一次，且确实炸了。

## 8. 遗留 / 待办

1. **备案未确认** —— `hxmall.top` 备案了吗？广州是大陆地域，未备案域名的 80/443 会被拦。
   目前从本机访问正常，但本机 DNS 走内网代理，**不能代表真实公网路径**，需用外部网络复验。
2. **`ops` 面与 C/B 面同机部署** —— `SPRING_PROFILES_ACTIVE=api,ops`。项目设计原意
   （S8 / `DeploymentProfileTest`）是 ops 独立部署在内网，它权限最高（改费率、批提现、封商家）。
   收紧办法：去掉 `ops` profile，或在 nginx 对 `/ops` 加 IP 白名单。
3. **短信/邮件/微信登录是真通道**（`SHOP_SMS_STUB=false`·`SHOP_MAIL_STUB=false`·
   `SHOP_WX_LOGIN_STUB=false`，从本机 env 原样搬来）。`/mp/user/otp/send` 是**公网未鉴权端点**，
   发码限流默认开着（`SHOP_OTP_RATE_LIMIT=true`）兜底，但仍建议确认限流阈值。
4. **22 端口对 `0.0.0.0/0` 开放** —— 建议收窄到办公 IP + CI 出口 IP。
5. ~~证书 90 天到期无人续~~ **已解决（2026-08-18）** —— 见下方「10. 证书」。
6. **`DNS_AUTO` 自动验证不生效** —— 实测回落成手动模式，`_dnsauth` TXT 是手工补的，脚本未处理。
   这也是改用 acme.sh 的原因之一：它自己写 TXT、自己轮询、自己清理。原
   `setup-tls.sh` 已被 [setup-tls-acme.sh](setup-tls-acme.sh) 取代，保留仅作参考。
7. **无演示数据** —— 需要的话加 `shop.seed.enabled=true`（2 社区 / 2 自提点 / 2 商家 / 4 商品）。
8. **android-shell 未部署** —— rsync 时排除了，客户端打包不属服务器部署范围。

## 9. 验证与排障

```bash
curl https://www.hxmall.top/actuator/health          # {"status":"UP"}
curl https://www.hxmall.top/mp/community/nearby      # {"code":0,...}
ssh soukmind-tx 'sudo tail -f /var/log/ai-shop/app.log'
ssh soukmind-tx 'systemctl status ai-shop mariadb nginx'
```

上线时实测：`/` `/b/` `/ops-web/` `/actuator/health` `/mp/community/nearby` 均 200，
`/ops/media` 401（路由在、要鉴权），三端 title 分别为「社区好物」「邻里商家」「邻里购 · 平台运营端」。

## 10. 证书（2026-08-18 起全自动）

三张 Let's Encrypt 证书由服务器上的 acme.sh v3.1.5 托管，**到期前自动续签并 reload nginx，
无需人工介入**。原腾讯云 TrustAsia 证书（90 天、无自动续期）已弃用，旧证书备份在
`/etc/nginx/ssl/www.hxmall.top.bak-20260818`。

| 证书 | 覆盖 | 证书目录 | DNS 插件（解析在哪家） | 下次自动续期 |
|---|---|---|---|---|
| `*.hxmall.top` | 泛域名 + 裸域 | `/etc/nginx/ssl/hxmall.top/` | `dns_tencent`（DNSPod） | 2026-10-18 |
| `*.ichain.top` | 泛域名 + 裸域 | `/etc/nginx/ssl/ichain.top/` | `dns_ali`（阿里云） | 2026-10-17 |
| `*.hxtech.top` | 泛域名 + 裸域 | `/etc/nginx/ssl/hxtech.top/` | `dns_ali`（阿里云） | 2026-10-17 |

续期 cron：`51 4,10,16,22 * * * /root/.acme.sh/acme.sh --cron`（root）。
续期时间由 Let's Encrypt 的 ARI 接口给出，不是固定 60 天。

重签或新增域名用 [setup-tls-acme.sh](setup-tls-acme.sh)，日常**不需要跑**：

```bash
bash deploy/tencent/setup-tls-acme.sh '*.hxmall.top,hxmall.top' dns_tencent
bash deploy/tencent/setup-tls-acme.sh '*.ichain.top,ichain.top' dns_ali
```

### 验证过的事实（别再重新怀疑）

- **签证书不需要 ICP 备案**。DNS-01 只写 TXT，不碰 80/443，不要求网站可访问。
  `ichain.top` / `hxtech.top` 备案未办就已拿到证书。
- **泛域名免费**。「阿里云/腾讯云不支持泛域名」指的是它们**自家的免费证书产品**；
  这里 CA 是 Let's Encrypt，云厂商只当 DNS 服务商被调 API 写一条 TXT。
- **不需要切 NS**。解析在阿里云就用 `dns_ali`，在 DNSPod 就用 `dns_tencent`。
- **无人值守路径已实测**：用 `env -i` 清空环境跑 `--issue`，acme.sh 自行从
  `/root/.acme.sh/account.conf` 读出 AK → 写 TXT → 验证 → 删 TXT → 装证书 → reload，
  全程无外部输入。这是 cron 的真实路径。
  （注意：`--renew --force` **不能**验证这一条 —— LE 会缓存 30 天内的域名授权，
  日志出现 `already verified, skipping dns-01` 时 DNS 写入根本没跑。要验必须换新子域。）

### 签泛域名的两个坑（都踩过）

- **`-d www.x.com` 不能和 `-d '*.x.com'` 写在同一张证书里** —— Let's Encrypt 直接拒单：
  `Domain name "www.x.com" is redundant with a wildcard domain in the same request`。
  泛域名本来就覆盖 `www`，去掉即可。
- **主域名换成 `*.x.com` 会改变证书目录** —— 脚本把 `*.` 剥掉，目录从
  `/etc/nginx/ssl/www.x.com/` 变成 `/etc/nginx/ssl/x.com/`。**nginx 配置不跟着改的话，
  新证书装到了新目录，nginx 还在读旧目录里的旧证书，而且一切正常、不报任何错**，
  直到旧证书到期才暴露。签完务必确认 `ssl_certificate` 指的是新路径，并用
  `openssl s_client -servername <域名>` 实测握手返回的是哪张。

### 已知副作用

acme.sh 的 `dns_ali` 清理逻辑会误伤**同名的既有 TXT 记录**：`ichain.top` 上一条签发前就
存在的 `_acme-challenge` 记录被改成了停用（值未变），已手工恢复。之后在这两个域名上签证书，
跑完用 `python3 deploy/aliyun/alidns.py list <域名>` 检查一遍 `_acme-challenge` 的状态。
