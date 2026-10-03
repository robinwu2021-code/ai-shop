# 图片走服务器与流量切换 方案

> 状态：**已实现**（2026-09-25，server 模式已上线；direct 模式待 §4 的触发条件）· 上游：[TDD-图片存储与空间回收](TDD-图片存储与门店空间清理.md) §9 ·
> [ADR-026 图片出口用两个自有域名、按配置切换](../ADR/ADR-026-图片出口两个域名按配置切换.md) · 优先级：高（COS 欠费停服中）
>
> 档位：2（跨三端 + 存量数据迁移 + 对外域名是不可逆决策）。
> 关联需求：无独立 PRD —— 口头诉求（2026-09-25）：「目前测试阶段，图片先走服务器网络，将来切换图片流量」；
> 「配置两个域名，一个通过应用服务器，一个通过图片服务器，根据配置切换」。验收标准写在 §5.0。

## 一、一句话

公开图有**两个对外域名，同时在线**：

- `img.hxmall.top`：**经应用服务器**，nginx 走内网从 COS 取图；
- `cdn.hxmall.top`：**经图片服务器**，CDN（或 COS 自定义域名）直出。

库里只存一种**规范地址** `https://img.hxmall.top/<key>`。
接口返回时，按配置 `shop.media.delivery=server|direct` 决定客户端拿到哪一个。
**切换 = 改一个配置项并重启**，不迁数据、不发版、不改 DNS。

## 二、为什么是这个方案

### 起因（2026-09-25 实测）

- COS 返回 `451 UnavailableForLegalReasons`：**账户欠费**。所有商品图、证件、售后图打不开。
- 库里存的是 COS 公网地址 `https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/<key>`，
  客户端直连 COS 下载，每一字节都是 COS 外网下行流量。**出流量的地方被写死在了数据里。**
- 服务器是**轻量应用服务器** `lhins-98lm5asj`（广州四区）：**月流量包 500 GB，本期用了 2.8 GB**；
  峰值带宽 **5 Mbps**。与桶同地域，服务器上桶域名已解析到内网 `169.254.0.47` —— **内网流量不计费**。

所以测试阶段让图片走服务器，流量费一分不多；代价是 5 Mbps 的带宽上限（见 §5.3）。

### 否掉的选项

| 方案 | 否掉的理由 |
|---|---|
| A. 库里改存 `https://www.hxmall.top/cos/<key>`（挂在主站路径下） | 图片和接口绑在一个域名上，将来想把图片交给 CDN 只能连主站一起交 |
| B. 一个图片域名，切换靠改 DNS | 切换要动 DNS，生效受缓存 TTL 影响、不能按环境区分；也做不到「两条路都在线、随时切回」这种配置级的开关 |
| C. 改全局 JSON 序列化，按配置输出域名 | 代码里有 66 处自行序列化，图片数组就是序列化成 JSON 写进库里的。全局改写会让「图片服务器」模式下**把 CDN 地址写进数据库**，切回来就是死链 |
| D. 库里只存裸 key，每个接口出口自己拼域名 | 25 个字段、三端、富文本正文里都有图片；每一处出口都要改，漏一处就是一张裂图 |
| E. **规范地址入库 + 在 HTTP 出入口按配置替换** | ✅ 采用。只在一个过滤器里改写；漏网的出口（推送、导出）拿到的是规范地址，**照样能打开** |

## 三、结构

| 地址 | 经应用服务器（`server`，现在） | 经图片服务器（`direct`，将来） | 为什么这样分 |
|---|---|---|---|
| 公开图原图 | `https://img.hxmall.top/<key>` | `https://cdn.hxmall.top/<key>` | 量最大，是要切的那部分 |
| 公开图缩略图 | `…/<key>!w375`：nginx `image_filter` 现场缩图并缓存 | `…/<key>!w375`：数据万象同名样式（分隔符 `!`） | **两边认同一种写法**，切换时客户端拼的地址不变 |
| 私有图（证件、售后，带签名） | `https://www.hxmall.top/cos-private/<key>?q-sign-…` | **同左，不切** | 量极小；CDN 透传签名要另配鉴权，配错就是把证件照缓存成公开 |

两个域名**始终都在线**：`img.hxmall.top` 的 nginx 转发在 `direct` 模式下也保留，它是回退路径，
也服务于那些没经过改写的出口。

**防住什么**：
- 防「换一次出口迁一次数据」：库里只有规范地址，出口由配置决定。
- 防「切换要发版」：两个域名的路径和缩略图写法完全一致，只差主机名。
- 防「改写渗进数据库」：改写只发生在 HTTP 响应上；入口把 `cdn.hxmall.top` 反向替换回规范地址再往下走。
- 防「漏改的出口变成裂图」：规范地址本身就是一个能打开的地址（经应用服务器）。

### 改写过滤器 `MediaHostRewriteFilter`

| 方向 | 条件 | 动作 |
|---|---|---|
| 出（响应） | `delivery=direct`，且 `Content-Type` 是 JSON，且路径在 `/mp` `/biz` `/ops` 下 | 响应体里的 `https://img.hxmall.top/` 全部替换成 `https://cdn.hxmall.top/` |
| 入（请求） | `delivery=direct`，且请求体是 JSON | 请求体里的 `https://cdn.hxmall.top/` 替换回 `https://img.hxmall.top/`（客户端把拿到的图片地址提交回来，比如编辑商品） |
| 不处理 | `delivery=server`（默认） | 直接放行，**零开销** |
| 不处理 | SSE（`/ops/stream`）、文件下载、`/internal/**` | 流式或非 JSON，不缓冲 |

替换的是**完整的主机前缀**（含 `https://` 和结尾的 `/`），不会误伤其他文本。
JSON 里的 `/` 不会被 Jackson 转义，按字节替换是安全的；测试里要钉住这一点。

## 四、将来的切换方案（详细）

### 4.1 什么时候切（可量的触发条件，满足任一条就启动）

| 条件 | 量法 | 阈值 |
|---|---|---|
| 流量包不够用 | `lighthouse:DescribeInstancesTrafficPackages`（每日脚本，见 §5.1 T9） | 当期**已用 > 70%**（350 GB），或按日均推算月底超额 |
| 带宽吃满 | `img.hxmall.top` 的 access log 按分钟汇总 `$bytes_sent` | 营业时段 1 分钟峰值 **> 4 Mbps 持续 10 分钟**，一周内出现 ≥ 3 天 |
| 转正式运营 | 人为决定 | 真实商家、顾客开始规模化使用前 |

### 4.2 「图片服务器」接什么

| 目标 | 适合 | 备注 |
|---|---|---|
| **腾讯云 CDN，源站 = COS 桶**（推荐） | 流量大、分布广 | CDN 下行单价低于 COS 外网下行；开「回源鉴权」后桶可以改私有读 |
| COS 自定义域名直出（不经 CDN） | 不推荐 | 单价最高，就是这次欠费的来路 |
| 不切，升级服务器带宽 | 只是带宽不够、流量包够用 | 保持 `server`，只升轻量套餐 |

### 4.3 切换步骤

**T-7 天 · 准备**（`cdn.hxmall.top` 上线但没人用，不影响线上）
1. 复核 `hxmall.top` 的 ICP 备案状态（国内 CDN 加速域名必须备案）。
2. 腾讯云账户：余额充足；**开预算告警**；CDN 开**用量封顶**（超额自动关停，防止再欠一次费）。
3. CDN 新增加速域名 `cdn.hxmall.top`，源站选 COS 桶，开回源鉴权；缓存规则按后缀缓存图片 30 天。
4. 证书：`*.hxmall.top` 通配证书上传到 CDN；定下续期后怎么推到 CDN（§六 Q2）。
5. 数据万象建样式 `w200` / `w375` / `w750`，分隔符 `!`，尺寸与 nginx 一致（§六 Q1）。
6. 防盗链：Referer 白名单放行 `servicewechat.com`（小程序）和 `hxmall.top`，**允许空 Referer**（App 原生请求不带）。
7. DNS：`cdn.hxmall.top` CNAME 到 CDN 分配的地址（`deploy/aliyun/alidns.py`，回读权威记录）。

**T-1 天 · 预演**
8. 抽 20 个 key：`cdn.hxmall.top/<key>` 与 `img.hxmall.top/<key>` **逐字节一致**；三档缩略图尺寸和格式一致；
   不存在的 key 返回 404。在服务器上 `curl --resolve` 打，**不用本机 `dig`**（本机 DNS 被改写过）。
9. 在一个非生产实例上（本机，或同库的第二个实例）设 `SHOP_MEDIA_DELIVERY=direct`，
   打 `/mp/goods`，确认返回里全是 `cdn.hxmall.top`；提交一次商品编辑，确认入库的是 `img.hxmall.top`。

**T0 · 切换**（选低峰）
10. 服务器 env 设 `SHOP_MEDIA_DELIVERY=direct`、`SHOP_MEDIA_DIRECT_BASE_URL=https://cdn.hxmall.top`，重启 shop-app（约 24 秒，走 `deploy-backend.sh` 的 health 守候）。
    启动日志要打出 `[media] 公开图出口=direct base=https://cdn.hxmall.top`。

**T+1 小时 / T+24 小时 · 观察**
11. `img.hxmall.top` 的日志流量应当只剩老缓存和没经过改写的出口，明显下降。
12. CDN 命中率 > 90%，4xx/5xx 比例不高于切换前；真机（App、小程序）各看一屏列表和一张详情大图，再编辑保存一次商品。

**回滚**（任何一步不对）
13. `SHOP_MEDIA_DELIVERY=server`，重启。客户端下一次拉接口就回到应用服务器；
    已经拿到 `cdn.hxmall.top` 地址的页面，在 CDN 正常时照样能打开。

**T+30 天 · 收口**
14. 桶改私有读（CDN 回源鉴权已开，nginx 取私有图本来就带签名）。
    之后直连 COS 公网地址一律 403，不会再有任何人产生 COS 外网流量。
    **公开图的 nginx 转发也要改成带签名取图**，否则 `server` 模式失效（§六 Q3）。这是收口前的前提。

## 五、现在的执行方案

### 5.0 验收标准（对账一：需求 → 设计）

| AC | 要求 | 落点 |
|---|---|---|
| AC1 | 库里的公开图地址**全部**是规范地址 `img.hxmall.top`（新上传的和存量的都算） | `COS_DOMAIN` 配置 + 迁移 `V3xx__media_host_img.sql` |
| AC2 | `server` 模式：客户端拿到 `img.hxmall.top`，服务器走内网取 COS | nginx `img.hxmall.top` server 块 |
| AC3 | `direct` 模式：接口返回里的公开图全是 `cdn.hxmall.top`；客户端提交回来的 `cdn` 地址入库前换回规范地址 | `MediaHostRewriteFilter` |
| AC4 | 切换只改配置：两个模式下三端代码、库里的数据都不变 | 设计性质；AC1 + AC3 的测试一起证明 |
| AC5 | 私有图的签名地址经服务器能打开；签名过期或篡改后打不开 | `CosMediaStore#signedUrl` 改写域名 + nginx `/cos-private/` |
| AC6 | 列表页用缩略图，单张 ≤ 60 KB | nginx `!wNNN` + 前端 `thumb()` |
| AC7 | 桶根路径列不出目录；GET/HEAD 以外的方法一律拒绝 | nginx 规则 |
| AC8 | 回收扫描认得两个新域名和旧 COS 域名，不会把图误判成孤儿 | `MediaKeys` + `MediaKeyRoundTripTest` |
| AC9 | 新上传的图长边 ≤ 1600 px | `packages/shared/src/ports/media.ts` 压缩 |
| AC10 | `direct` 模式缺少 `direct-base-url` 时拒绝启动，不静默退回 | `MediaDeliveryConfig` 启动校验 |

孤立项：无。

### 5.1 任务与顺序

**顺序是硬约束**：先让新地址能打开（T1、T2），再让系统开始产出新地址（T3、T4）。
反过来的话，中间那段时间里新存进库的地址全是死链。`cdn.hxmall.top` 现在不建，切换时再建（§4.3）。

| # | 谁 | 做什么 | 验证（每条都要能失败） |
|---|---|---|---|
| T0 | **你** | 腾讯云充值；在控制台开余额/预算告警（我手上的密钥没有财务权限） | 服务器上 `curl` 一张已知商品图，从 451 变成 200 |
| T1 | 我 | DNS：`img.hxmall.top` 加 A 记录 → `106.55.27.246`，TTL 600 | `alidns.py list` 回读；服务器 `curl --resolve img.hxmall.top:443:127.0.0.1` 拿到 200 |
| T2 | 我 | nginx：新建 `deploy/tencent/nginx/img.hxmall.top.conf`；`www.hxmall.top` 加 `/cos-private/` | 见 5.2 的六条 curl，全部符合预期；`nginx -t` 通过后 reload |
| T3 | 我 | 后端配置：`COS_DOMAIN=https://img.hxmall.top`（规范地址）；新增 `shop.media.delivery`（默认 `server`）、`shop.media.direct-base-url`、`shop.cos.private-base-url`；`signedUrl` 改写域名；`MediaKeys` 认两个新域名 | 单测：签名地址的域名被替换、查询串原样保留；`direct` 缺 base-url 启动失败；往返测试覆盖两个域名；**消融**：去掉改写 → 测试变红 |
| T4 | 我 | `MediaHostRewriteFilter`：`direct` 模式下出口替换、入口反向替换；`server` 模式直接放行 | MockMvc：两种模式下 `/mp/goods` 的返回；提交含 `cdn` 地址的商品编辑，**入库**的是 `img`；SSE 与 `/internal/**` 不被缓冲；富文本里的图片地址也被替换；**消融**：注掉入口替换 → 「入库是规范地址」那条变红 |
| T5 | 我 | 迁移 `V3xx__media_host_img.sql`：25 个登记字段做 `REPLACE(旧 COS 前缀 → 规范前缀)` | 测试：每个登记字段都种一行旧地址，迁移后含 `myqcloud` 的行数为 0。**再在本机 MySQL 9.7 上跑一遍**（H2 与 MySQL 在 JSON 列上的语义不同）；按 `information_schema` 扫全部文本列，`myqcloud` 为 0 |
| T6 | 我 | 前端：`packages/shared` 加 `thumb(url, w)`，只对两个图片域名的地址追加 `!wNNN`（w ∈ 200/375/750）；列表页改用它（C 端商品列表、进店页，B 端商品列表，运营端缩略图） | vitest：非图片域名原样返回、宽度不在白名单时取最近档、两个域名都认；页面里量 `<img>` 的实际字节数 |
| T7 | 我 | 上传压缩：`chooseImages` 选完后 `uni.compressImage`，长边 1600 | 真机上传一张原图，看对象的大小和尺寸 |
| T8 | 我 | 部署顺序：T1 → T2 → 后端（T3、T4、T5）→ 前端（T6、T7）→ 验证 → 再发 B 端 0.4.97 与小程序 | 部署后：`/mp/goods`、商品详情、商家 logo 的接口返回里 grep `myqcloud` 为 0；真机列表与详情图片都能打开 |
| T9 | 我 | 流量监控：每日脚本读流量包用量 + 图片日志按分钟的峰值，超过 §4.1 阈值时发运营通知 | 把阈值临时调到 0，确认能收到一条通知 |
| T10 | 我 | 更新《TDD-图片存储与空间回收》§9，指向本方案 | 文档闸门通过 |

### 5.2 配置项

| 配置 | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| `shop.cos.domain` | `COS_DOMAIN` | 空（= COS 公网域名） | **规范地址前缀**，生产设为 `https://img.hxmall.top`。新上传的图按它入库 |
| `shop.media.delivery` | `SHOP_MEDIA_DELIVERY` | `server` | `server` = 经应用服务器；`direct` = 经图片服务器 |
| `shop.media.direct-base-url` | `SHOP_MEDIA_DIRECT_BASE_URL` | 空 | `direct` 模式的对外前缀，如 `https://cdn.hxmall.top`。`direct` 下为空则拒绝启动 |
| `shop.cos.private-base-url` | `COS_PRIVATE_BASE_URL` | 空（= COS 公网域名） | 私有图签名地址的对外前缀，生产设为 `https://www.hxmall.top/cos-private` |

这几项统一用 `@ConfigurationProperties` 绑定，不用 `@Value`：`@Value` 不做宽松绑定，
`${shop.media.direct-base-url}` 这种 kebab-case 的键从环境变量读不到值，**配了等于没配，而且不报错**
（仓库里踩过）。启动日志打出生效值，部署后回读日志确认。

### 5.3 nginx 规则要点（T2）

```nginx
# 缓存：key 名随机、从不覆盖，缓存安全；10G 上限，磁盘还剩 38G
proxy_cache_path /data/cache/img levels=1:2 keys_zone=img:50m max_size=10g inactive=30d use_temp_path=off;

# 用 upstream 而不是在 proxy_pass 里直接写域名：缩略图那条 location 带变量（$k），
# 带变量的 proxy_pass 要在运行时解析域名，没配 resolver 就是 502。upstream 在启动时解析一次，
# 服务器上它解析到内网 169.254.0.47。Host 要显式设成桶域名，否则 COS 收到的是 "cos_bucket"。
upstream cos_bucket { server hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com:80; }

server {
    listen 443 ssl; server_name img.hxmall.top;
    ssl_certificate /etc/nginx/ssl/hxmall.top/fullchain.crt;   # *.hxmall.top 通配证书，现成
    if ($request_method !~ ^(GET|HEAD)$) { return 405; }
    location = / { return 403; }                               # 桶是公有读，根路径会列出全部对象

    # 缩略图：只认三档宽度，防止任意宽度把缓存撑爆
    location ~ ^/(?<k>.+)!w(?<w>200|375|750)$ {
        proxy_pass http://cos_bucket/$k;
        proxy_set_header Host hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com;
        image_filter resize $w -; image_filter_buffer 8M;
        proxy_cache img; proxy_cache_valid 200 30d;
        add_header Cache-Control "public, max-age=2592000, immutable";
    }
    location / {
        proxy_pass http://cos_bucket;
        proxy_set_header Host hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com;
        proxy_cache img; proxy_cache_valid 200 30d; proxy_cache_valid 404 1m;
        proxy_hide_header x-cos-request-id;  # 以及其余 x-cos-*
        add_header Cache-Control "public, max-age=2592000, immutable";
    }
}
# www.hxmall.top 里：带签名的私有图，不缓存，Host 保持桶域名，签名才对得上
location ^~ /cos-private/ {
    proxy_pass http://cos_bucket/;
    proxy_set_header Host hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com;
    proxy_cache off;
}
```

上线前的六条验证：
1. 已知 key 返回 200，内容与 COS 原图逐字节一致；
2. `!w375` 返回 200，宽度 375；
3. `!w376` 返回 404；
4. 根路径 `/` 返回 403；
5. `POST` 返回 405；
6. 私有 key 不带签名返回 403，带有效签名返回 200，签名改一个字符返回 403。

### 5.4 带宽要说在前面

5 Mbps 约等于 600 KB/s，接口和图片共用：
- 一屏 20 张 375 宽的缩略图（按每张 40 KB 估）约 800 KB，满速约 1.3 秒；
- 一张没缩尺寸的 3 MB 原图约 5 秒。

测试阶段够用；**T6（缩略图）和 T7（上传压缩）不是优化，是 `server` 模式能用的前提**，所以和转发一起上线。
每张图实际多大还没量过（COS 停服，读不到对象）。现有的选图只做了系统压缩（`sizeType: compressed`），
降质量、不缩尺寸。T0 之后第一件事就是抽样量一次。

### 5.5 回滚（现在这一阶段）

- **T1、T2**：删 DNS 记录、删 server 块即可，此时还没有任何数据指向新域名。
- **T3–T5 之后**：再跑一次反向替换的迁移（`img.hxmall.top` → COS 公网域名），并清空 `COS_DOMAIN`。
  客户端又会直连 COS，也就是回到欠费前的状态。**只在应用服务器这条路整体不可用时才用**；
  平时的问题都应该在 nginx 那层修。

## 六、待确认

| # | 问题 | 卡住谁 | 默认做法 |
|---|---|---|---|
| Q1 | 数据万象的样式分隔符是否支持 `!` | 切换 4.3 第 5 步 | 不支持的话，改用它支持的分隔符，并**在 T6 上线前**统一两边的写法。一旦产出过缩略图地址，再改写法就要发版 |
| Q2 | CDN 证书续期：acme deploy hook 还是腾讯云托管证书 | 切换 4.3 第 4 步 | 切换前定，不影响现在 |
| Q3 | 桶改私有之后，`img.hxmall.top` 的公开图转发也要带签名（nginx 做不了签名） | 切换 4.3 第 14 步 | 收口前改由后端签名转发，或者桶保持公有读、只靠 CDN 防盗链。现在不做 |
| Q4 | 切换要不要做到不重启 | 切换 4.3 第 10 步 | 先做「改配置 + 重启」（约 24 秒）。要不停机切换，可以把 `delivery` 挂到运营端现有的功能开关上 |
| Q5 | 欠费是否真的来自 COS 流量 | 判断本方案能不能根治欠费 | 请在费用中心按产品看明细；如果主要来自别的服务（比如数据万象），本方案只解决图片这一项 |

## 七、实现记录（2026-09-25）

### 对账二 · 设计 → 实现

| 任务 | 实际落点 | 与设计一致？ |
|---|---|---|
| T0 | 用户充值，COS 恢复（服务器上同一张图 451 → 200） | ✅ |
| T1 | DNSPod 加 `img A 106.55.27.246 TTL 600`（域名在腾讯云 DNSPod，不在阿里云） | ✅ |
| T2 | `deploy/tencent/nginx/img.hxmall.top.conf`（新）· `www.hxmall.top.conf` 加 `/cos-private/`；服务器装 `libnginx-mod-http-image-filter` | ⚠️ 偏差 1、2 |
| T3 | `MediaDeliveryConfig`（新）· `CosMediaStore#signedUrl` + `rebase` · `MediaKeys` 认 `cos-private/` · `application.yml` 三个配置项 | ⚠️ 偏差 3、4 |
| T4 | `MediaHostRewriteFilter`（新） | ✅ |
| T5 | `V347__media_host_img.sql`（25 条 UPDATE） | ⚠️ 偏差 5 |
| T6 | `packages/shared/src/utils/media-thumb.ts`（新）· `sh-cover` 加 `w` · 17 个调用点 + 详情长图 + 店头像 | ⚠️ 偏差 6 |
| T7 | `packages/shared/src/ports/media.ts`：`fitLongEdge` + `shrink` | ✅ |
| T8 | 线上验证：`/mp/goods` 返回里 COS 地址 6 → 0、`img.hxmall.top` 0 → 6；三张图原图与 `!w375` 均 200 | ✅ |
| T8 | 发版：B 端 0.4.98（官网回读已指向 0.4.98）· 小程序 0.1.61 开发版（体验版跟随；提审与发布待人工） | ✅ |
| T9 | `deploy/tencent/logwatch.sh` 第 4d 节（已装到服务器） | ⚠️ 偏差 7 |
| T10 | 本节 + 《TDD-图片存储与空间回收》§9 更正 | ✅ |

### 对账三 · 实现 → 需求（测试）

| AC | 测试 | 消融 |
|---|---|---|
| AC1 | `MediaHostMigrationTest#coversExactlyTheRegistry` / `#replacesEveryForm` | 删迁移一行 → 覆盖面测试点名缺 `prd_goods.detail_images` ✅ |
| AC2 | 线上六条 curl（§5.3）全部符合预期 | — |
| AC3 | `MediaHostRewriteFilterTest`（7 条）· `MediaDirectDeliveryFlowTest`（真 Tomcat） | 去掉入口替换 → 「入库的」变红；配置改 server → 真 Tomcat 那条变红 ✅ |
| AC5 | `CosSignedUrlRebaseTest`（2 条）；线上签名有效 200 / 篡改 403 | 去掉 rebase → 变红 ✅ |
| AC6 | `media-thumb.test.ts`（5 条）；线上 910 KB → `!w375` 22 KB | — |
| AC7 | 线上：根路径 403、POST 405、`!w376` 404 | — |
| AC8 | `MediaKeyRoundTripTest`（加了 direct 出口与签名地址两条） | 去掉 `cos-private/` → 签名地址抠不回 key ✅ |
| AC9 | `media-fit.test.ts`（3 条） | — |
| AC10 | `MediaHostRewriteFilterTest#misconfigurationFailsFast` | — |

### 偏差说明

1. **缩略图分两层**：`image_filter` 作用在代理响应之后，与 `proxy_cache` 写在同一层时缓存存的是原图。
   改为外层缓存、内层 `127.0.0.1:8095` 缩图。
2. **nginx 顺带升级**：缩图模块要求 nginx 1.24.0-2ubuntu7.18，连带从 7.15 升上来（同版本安全补丁，重启约 1 秒，三个站点回读均 200）。
3. **配置绑定沿用 yml 显式映射 + `@Value`**，没有改成 §5.2 写的 `@ConfigurationProperties`：
   `application.yml` 里每个键都写成 `${ENV_VAR:默认}`，环境变量能读到，这正是这个文件一直以来的写法。
   `@Value` 不做宽松绑定的坑只在「键名直接对环境变量」时出现，这里不是。
4. **`MediaKeys` 多认一个前缀 `cos-private/`**：设计里说它不用改（它先剥掉 `协议://主机`），
   但签名地址的路径多一段 `cos-private/`，客户端原样提交回来时会抠出错的 key。按它「宁可多抠」的原则加上。
5. **迁移没在 MySQL 空库上从 V1 跑到 V347**：V1 用了 MariaDB 专有排序规则 `utf8mb4_uca1400_ai_ci`，
   MySQL 起不来 —— 生产库是从 MariaDB 导入的，不是从 V1 建的。改为在 MySQL 9.7 容器里按本机真实列类型
   建出 25 列（两列原生 JSON），整份执行 V347：50 行 0 异常，相似域名一条没误换。
6. **运营端缩略图没做**：「存储管理 → 待回收」拼的是本地盘 `/uploads/` 路径，COS 下一直是裂的（存量问题）。
   修它要后端返回缩略图地址（动契约），另开任务。
7. **流量量具用 nginx 日志，不用网卡计数器**：网卡 tx 含经内网往 COS 传备份的流量，开机以来 15 GB 而流量包只记 2.8 GB。
   带宽触发条件从「一周 ≥3 天」简化为「近一小时 ≥10 分钟超 4 Mbps」（每小时判一次，按天聚合交给人看日志）。
   首次安装按 Lighthouse API 的权威用量（3055053696 字节）播种。

