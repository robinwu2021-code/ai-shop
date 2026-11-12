# UME 开发者门户

生产地址：https://nearshop.umeshop.ai ，英文：https://nearshop.umeshop.ai/en/ 。

## 实际拓扑

公网 .201 现有 TLS 入口 → .166 现有 nearshop Nginx → 127.0.0.1:3210 文档容器。

容器：`neargo-developer-portal`；镜像：`neargo-developer-portal:20261112-bilingual-portal`。Node 24 Alpine，Docker host 网络，只监听 loopback；restart unless-stopped，内存上限256MiB，CPU上限0.5。启动后实测内存约38MiB，这是本次低负载快照，不是容量保证。

.201 证书和路由未修改。只调整 .166 `/etc/nginx/conf.d/nearshop.umeshop.ai.conf` 的根路径和原404兜底，保留运营端、业务API、收银台跳转等原有locations。生产验证 ops-shop、oms 均返回200。

## 服务器目录

- `/data0/app/neargo-developer-portal/releases/20261112-bilingual-portal`：本次源码快照及release.json。
- `/data0/app/neargo-developer-portal/current`：本次release软链。
- `/data0/app/neargo-developer-portal/content`：中文发布真源，挂载到 /app/content（只读）。
- `/data0/app/neargo-developer-portal/content-en`：英文发布真源，挂载到 /app/content-en（只读）。

程序环境：SITE_URL=https://nearshop.umeshop.ai，ADDRESS=127.0.0.1，PORT=3210；TRUST_PROXY=loopback,192.168.3.201/32。只信任实际代理链，限流按真实客户端区分。无数据库、无业务密钥配置。

## 更新内容

把公开MD及配套资源复制到服务器对应 content/content-en 目录即可，不重建镜像、不重启容器。通过已配置的UME SSH连接操作；不要复制整个内部仓库。

本地先执行 `npm run sync:nearpay` 可刷新中文支付源文档，英文翻译需同步复核。然后上传有变动的文件。使用rsync时排除 `._*`、`.DS_Store`；从macOS打包时使用 `COPYFILE_DISABLE=1 tar --no-xattrs`，避免资源叉元数据变成伪文档。

修改程序、依赖、主题或语言页面配对表时，使用新的release目录和镜像标签构建，检查新容器后切换。UME Docker 19当前无Compose插件，本次使用docker build/run，未升级宿主Docker。

## 回退

Nginx原配置备份：`/etc/nginx/conf.d/nearshop.umeshop.ai.conf.bak-neargo-20261112T133542Z`。恢复此文件后先 `nginx -t`，再reload，即恢复原首页跳转。随后可停止文档容器；不要改其他容器或支付配置。内容与代码独立挂载，镜像替换不会覆盖已发布内容。

## 验证证据

仓库 `docs/handoff/developer-portal-ume-2026-11-12/` 保存公开URL检查及文件hash清单，不包含SSH/TLS私钥。部署前本地8项测试通过；公网15项资源检查通过，两个sitemap各8页；浏览器生产中英文切换检查通过。
