# nearshop 双域名入口

UME166 商城运营端在 `ops-web` 目录构建时使用 `NEXT_PUBLIC_API_BASE=/ops-shop NEXT_PUBLIC_BASE_PATH=/ops-shop NEXT_PUBLIC_USE_MOCK=0 npm run build -- --webpack`。商城接口必须请求 `/ops-shop/ops/*`；空 API 基地址会请求另一服务的根路径 `/ops/*`。发布后在已登录浏览器核对身份与业务接口成功，参见[2026-11-13 验收记录](../../../docs/technical/evidence/ops-admission-consolidation-20261113/verification.json)。

状态：2026-11-11 已在 UME 部署并通过双域名验证。设计和执行顺序见 [TDD](../../../docs/technical/TDD-nearshop双域名渐进迁移.md)。

| 用途 | 新入口 | 保留入口 |
|---|---|---|
| ai-shop 运营端 | https://nearshop.umeshop.ai/ops-shop/ | https://twpay.umeshop.ai/ops-shop/ |
| 商户 App API 基地址 | https://nearshop.umeshop.ai/shop-api | https://twpay.umeshop.ai/shop-api |
| 独立 near-pay 运营端 | https://nearshop.umeshop.ai/oms/ | https://twpay.umeshop.ai/oms/ |
| 支付 H5、支付回调及 Apple 验证 | 保持原域名；新域名 H5 页面临时 307 回旧域名 | https://twpay.umeshop.ai/ |

当前只增加兼容入口，不替换已发布 App 配置、二维码、冻结支付链接或服务间内网地址。新域名浏览器需重新登录。直接访问内部服务、自动化认证和未发布机器接口仍被拒绝。

`app166.conf` 安装到 166 的 `/etc/nginx/conf.d/nearshop.umeshop.ai.conf`，复用既有转发协议 map；`edge201.conf` 安装到 201 的 `/usr/local/nginx/conf/vhost/nearshop.umeshop.ai.conf`。两端 nginx -t 成功后平滑加载，旧 twpay 文件不改。API/OPS 的 SHOP_CORS_EXTRA_ORIGINS 同时保留两个精确 HTTPS 来源，不设置通配来源。TLS 复用现有 *.umeshop.ai 证书，证书原有更新流程需同时保障两域。

后续切换顺序：

1. 新版商户 App 与联调配置选用 `MERCHANT_API_BASE_URL=https://nearshop.umeshop.ai/shop-api`，原已发布 App 继续可用。
2. 运营书签、帮助文档和非支付入口链接逐项改用新域名；每批验证登录、上传、权限和退出行为。
3. 独立库存等尚未发布的 Web 入口按各自正式认证方案接入；不直接暴露内部管理 API。
4. 在旧客户端和合作方迁移完成后，再决定旧域名非支付路径的停用或方法保持型跳转；支付 H5 和支付回调保留独立迁移评审。

两份非支付路由当前来自相同部署快照，后续路由变更应同步核对两个域名，避免只更新一边。此次不更换应用制品、不执行数据库迁移、不重启独立 near-pay 或库存服务。

验证记录：[部署与保留范围](deployment-20261111.json)、[46 项公网矩阵](verification-20261111.json)。另已通过新域名的真实运营登录和身份回读、真实商户身份登录与选店上下文、既有测试凭证双域名签名读取及去签名拒绝。没有记录凭据、令牌或私有签名 URL。

可重复的无凭据公网检查：`python3 deploy/ume/nearshop/verify-public.py`。该脚本访问测试 H5 页面但不创建支付、不调用付款或回调。需能访问公网、使用有效系统 CA，检查期间不关闭 TLS 校验。

回退备份：166 `/data0/app/ai-shop/releases/nearshop-dual-domain-20261111`；201 `/usr/local/nginx/conf/.nearshop-backups/nearshop-dual-domain-20261111`。旧域名两个配置字节哈希不变。可先撤下新增域名虚拟主机再 nginx -t/reload；CORS 回退优先按当前运行配置去除新来源，若期间已有其他应用发布，不回退其他会话的制品。原运行容器和私有 inspect 已保留，辅助脚本位于 166 备份目录 `apply-cors.py`。

配置独立审查发现并修复辅助脚本的失败回退路径；停止、重命名、创建、启动和健康检查五种故障状态的模型验证通过。未在生产环境额外触发故障或演练回退。

### 2026-11-13 图片路径补正

`media.locations.conf` 安装到166的 `/etc/nginx/snippets/ai-shop-media.locations.conf`，**在 nearshop 和 twpay 两个 server 块同时 include**。只增量加入 include，不用旧模板覆盖当前首页等其他会话配置。`/uploads/` 经API公开用途校验，`/media/` 经API签名验证，404再交OPS验证；不alias磁盘、不记录签名访问日志、不走代理缓存。原mallpay专用规则可保留，新的`^~ /media/`统一接管；201通用转发无需变更。两域名nginx配置已备份在166 `/data0/app/ai-shop/releases/image-upload-20261113/nginx-before/`，撤销 include/新增snippet后 nginx -t/reload 可回退。
