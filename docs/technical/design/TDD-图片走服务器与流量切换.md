# 图片走服务器与流量切换 方案

> 状态：**待决策**（2026-09-25）· 上游：[TDD-图片存储与空间回收](TDD-图片存储与门店空间清理.md) §9 ·
> [ADR-026 图片对外只用自有域名](../ADR/ADR-026-图片对外只用自有域名.md) · 优先级：高（COS 欠费停服中）
>
> 档位：2（跨三端 + 存量数据迁移 + 对外域名是不可逆决策）。
> 关联需求：无独立 PRD —— 口头诉求（2026-09-25）：「目前测试阶段，图片先走服务器网络，
> 将来切换图片流量」。验收标准写在 §0。

## 一、一句话

图片对外**只用一个自有域名 `img.hxmall.top`**，库里存的也是它。
测试阶段它解析到我们自己的服务器，服务器经**内网**从 COS 取图；
将来要把流量交出去，**只改这一条 DNS 记录**（指向 CDN），不迁数据、不发版、不改代码。

## 二、为什么是这个方案

### 起因（2026-09-25 实测）

- COS 返回 `451 UnavailableForLegalReasons`：**账户欠费**。所有商品图、证件、售后图打不开。
- 库里存的是 COS 公网地址 `https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/<key>`，
  客户端直连 COS 下载，每一字节都是 COS 外网下行流量。
- 服务器是**轻量应用服务器** `lhins-98lm5asj`（广州四区）：**月流量包 500 GB，本期用了 2.8 GB**；
  峰值带宽 **5 Mbps**。与桶同地域，服务器上桶域名已解析到内网 `169.254.0.47` —— **内网流量不计费**。

所以测试阶段让图片走服务器，流量费一分不多；代价是 5 Mbps 的带宽上限（见 §五）。

### 否掉的选项

| 方案 | 否掉的理由 |
|---|---|
| A. 库里改存 `https://www.hxmall.top/cos/<key>`（挂在主站路径下） | 将来切 CDN 时，只能把**整个主站**交给 CDN，或者再迁一次数据。图片和接口绑死在一个域名上 |
| B. 库里只存裸 key，渲染时再拼域名 | 25 个字段、三端、富文本正文里都有图片地址；每一处出口都要改，漏一处就是一张裂图。收益（换域名不迁数据）用方案 C 也能拿到 |
| C. **自有图片子域名 + 切换靠 DNS** | ✅ 采用。只迁一次数据，之后换任何后端都是改解析 |
| D. 现在就上 CDN | 测试阶段流量很小，服务器流量包用不完；CDN 还要多一套证书、缓存规则和计费项。等 §四的触发条件满足再上 |

## 三、结构

| 对外地址 | 测试阶段（现在） | 切换后（将来） | 为什么这样分 |
|---|---|---|---|
| `https://img.hxmall.top/<key>` 商品图原图 | A 记录 → 服务器 nginx → 内网 COS，服务器磁盘缓存 | CNAME → CDN → 回源 COS | 公开图，量最大，是要切的那部分 |
| `https://img.hxmall.top/<key>!w375` 缩略图 | nginx `image_filter` 现场缩图并缓存 | 数据万象的同名样式 `w375`（分隔符 `!`） | **两边认同一种写法**，切换时客户端不用改 |
| `https://www.hxmall.top/cos-private/<key>?q-sign-…` 证件、售后图（带签名） | 服务器 nginx → 内网 COS | **不切**，永远走服务器 | 量极小（只有商家和运营看），CDN 透传签名要多一套鉴权配置，不值得 |

**防住什么**：
- 防「换一次出口迁一次数据」：库里只有 `img.hxmall.top`，它背后是谁由 DNS 决定。
- 防「切换要发版」：缩略图写法在 nginx 和数据万象两边都认，客户端拼出来的地址不变。
- 防「私有图被 CDN 缓存成公开」：私有图压根不走可切换的那个域名。

## 四、将来的切换方案（详细）

### 4.1 什么时候切（可量的触发条件，满足任一条就启动）

| 条件 | 量法 | 阈值 |
|---|---|---|
| 流量包不够用 | `lighthouse:DescribeInstancesTrafficPackages`（每日脚本，见 §5 T8） | 当期**已用 > 70%**（350 GB），或按日均推算月底超额 |
| 带宽吃满 | 图片 server 块的 access log 按分钟汇总 `$bytes_sent` | 营业时段 1 分钟峰值 **> 4 Mbps 持续 10 分钟**，一周内出现 ≥ 3 天 |
| 转正式运营 | 人为决定 | 真实商家、顾客开始规模化使用前 |

### 4.2 切到哪（推荐顺序）

| 目标 | 适合 | 备注 |
|---|---|---|
| **腾讯云 CDN，源站 = COS 桶**（推荐） | 流量大、分布广 | CDN 下行单价低于 COS 外网下行；回源 COS 走腾讯内部链路。开「回源鉴权」后桶可以改成私有读 |
| 服务器升配带宽 | 只是带宽不够、流量包够用 | 不动 DNS，只升轻量套餐 |
| COS 自定义域名直出（不经 CDN） | 不推荐 | 单价最高，就是这次欠费的来路 |

### 4.3 切换步骤（以 CDN 为例）

**T-7 天 · 准备**（不影响线上）
1. 复核 `hxmall.top` 的 ICP 备案状态（国内 CDN 加速域名必须备案）。
2. 腾讯云账户：余额充足；**开预算告警**；CDN 开**用量封顶**（超额自动关停，防止再欠一次费）。
3. CDN 新增加速域名 `img.hxmall.top`：源站选 COS 桶，开回源鉴权；缓存规则按后缀缓存图片 30 天，**不忽略**查询参数。
4. 证书：`*.hxmall.top` 通配证书上传到 CDN。acme.sh 每 60 天续期，续期后要推到 CDN。
   要么加 deploy hook，要么改用腾讯云托管证书 —— 二选一，**上线前定**（§六 Q2）。
5. 数据万象建样式 `w200` / `w375` / `w750`，**样式分隔符选 `!`**，参数与 nginx 那边的尺寸一致（§六 Q1）。
6. 防盗链：Referer 白名单放行 `servicewechat.com`（小程序）、`hxmall.top`，**允许空 Referer**（App 原生请求不带）。

**T-1 天 · 预演**
7. 把 `img.hxmall.top` 的 DNS TTL 降到 **60 秒**（阿里云 DNS，`deploy/aliyun/alidns.py`）。
8. 用 CDN 分配的测试域名（`*.cdn.dnsv1.com`）对样本逐一比对，要求**字节一致**：
   20 个 key 的原图，加上三个尺寸的缩略图；再加 1 个不存在的 key，要求 404。
   注意：数据万象缩出来的图和 nginx 的不会逐字节相同，这一项比尺寸和格式。

**T0 · 切换**（选低峰）
9. 把 `img.hxmall.top` 的记录从 `A 106.55.27.246` 改成 `CNAME <CDN 分配的地址>`。用 API 就地改类型，不删再加，避免中间出现无记录的空窗。
10. 回读：用 `alidns.py list` 读权威记录；在服务器上 `curl --resolve` 打 CDN 节点。
    **不用本机 `dig`**：本机 DNS 被内网代理改写过，给的答案不可信。

**T+1 小时 / T+24 小时 · 观察**
11. 服务器上图片 server 块的日志流量应当降到只剩 CDN 回源（CDN 回源 COS 的话接近 0）。
12. CDN 命中率 > 90%，4xx/5xx 比例不高于切换前；真机（App 与小程序）各看一屏列表和一张详情大图。

**回滚**（任何一步不对）
13. 记录改回 `A 106.55.27.246`，TTL 60 秒内生效。**服务器上的 img server 块在切换后至少保留 30 天**，
    这段时间回滚就是改一条记录。

**T+30 天 · 收口**
14. 桶改私有读（CDN 回源鉴权已开）。之后直连 COS 公网地址一律 403，不再有任何人能产生 COS 外网流量。

### 4.4 反向切换（从 CDN 回到服务器）

同一套步骤倒过来走：服务器 img server 块一直保留，DNS 改回 A 记录即可。
前提是那时桶还允许服务器从内网读取。桶改私有之后，nginx 取图要带签名，这一条列为 §六 Q3。

## 五、现在的执行方案

### 5.0 验收标准（对账一：需求 → 设计）

| AC | 要求 | 落点 |
|---|---|---|
| AC1 | 客户端拿到的公开图地址**全部**是 `img.hxmall.top`（新上传的和存量的都算） | `COS_DOMAIN` 配置 + 迁移 `V3xx__media_host_img.sql` |
| AC2 | 服务器取 COS 走内网，客户端不再直连 COS | nginx `img.hxmall.top` server 块 |
| AC3 | 私有图的签名地址经服务器能打开；签名过期或篡改后打不开 | `CosMediaStore#signedUrl` 改写域名 + nginx `/cos-private/` |
| AC4 | 列表页用缩略图，单张 ≤ 60 KB | nginx `!wNNN` + 前端 `thumb()` |
| AC5 | 将来切换只改 DNS：代码里没有任何地方产出 COS 或 CDN 专属地址 | 设计性质；由 AC1 的扫描一起证明 |
| AC6 | 桶根路径列不出目录；GET/HEAD 以外的方法一律拒绝 | nginx 规则 |
| AC7 | 回收扫描认得新域名，不会把图误判成孤儿 | `MediaKeys` + `MediaKeyRoundTripTest` |
| AC8 | 新上传的图长边 ≤ 1600 px | `packages/shared/src/ports/media.ts` 压缩 |

孤立项：无。

### 5.1 任务与顺序

**顺序是硬约束**：先让新地址能打开（T1、T2），再让系统开始产出新地址（T3、T4）。
反过来的话，中间那段时间里新存进库的地址全是死链。

| # | 谁 | 做什么 | 验证（每条都要能失败） |
|---|---|---|---|
| T0 | **你** | 腾讯云充值；在控制台开余额/预算告警（我手上的密钥没有财务权限） | 服务器上 `curl` 一张已知商品图，从 451 变成 200 |
| T1 | 我 | DNS：`img.hxmall.top` 加 A 记录 → `106.55.27.246`，TTL 600 | `alidns.py list` 回读；服务器 `curl --resolve img.hxmall.top:443:127.0.0.1` 拿到 200 |
| T2 | 我 | nginx：新建 `deploy/tencent/nginx/img.hxmall.top.conf`；`www.hxmall.top` 加 `/cos-private/` | 见 5.2 的六条 curl，全部符合预期；`nginx -t` 通过后 reload |
| T3 | 我 | 后端：服务器 env 设 `COS_DOMAIN=https://img.hxmall.top`；新配置 `shop.cos.private-base-url`；`signedUrl` 改写域名；`MediaKeys` 认新域名 | 单测：签名地址的域名被替换、查询串原样保留；往返测试覆盖「配了 domain」的情况；**消融**：去掉改写 → 测试变红 |
| T4 | 我 | 迁移 `V3xx__media_host_img.sql`：25 个登记字段做 `REPLACE(旧前缀 → 新前缀)` | 测试：每个登记字段都种一行旧地址，迁移后含 `myqcloud` 的行数为 0。**再在本机 MySQL 9.7 上跑一遍**（H2 与 MySQL 在 JSON 列上的语义不同）；按 `information_schema` 扫全部文本列，`myqcloud` 为 0 |
| T5 | 我 | 前端：`packages/shared` 加 `thumb(url, w)`，只对 `img.hxmall.top` 的地址追加 `!wNNN`（w ∈ 200/375/750）；列表页改用它（C 端商品列表、进店页，B 端商品列表，运营端缩略图） | vitest：非本域地址原样返回、宽度不在白名单时取最近档；页面里量 `<img>` 的实际字节数 |
| T6 | 我 | 上传压缩：`chooseImages` 选完后 `uni.compressImage`，长边 1600 | 真机上传一张原图，看 COS 里对象的大小和尺寸 |
| T7 | 我 | 部署顺序：T1 → T2 → 后端（含 T3、T4）→ 前端（T5、T6）→ 验证 → 再发 B 端 0.4.97 与小程序 | 部署后：`/mp/goods`、商品详情、商家 logo 的接口返回里 grep `myqcloud` 为 0；真机列表与详情图片都能打开 |
| T8 | 我 | 流量监控：每日脚本读流量包用量 + 图片日志按分钟的峰值，超过 §4.1 阈值时发运营通知 | 把阈值临时调到 0，确认能收到一条通知 |
| T9 | 我 | 更新《TDD-图片存储与空间回收》§9，指向本方案 | 文档闸门通过 |

### 5.2 nginx 规则要点（T2）

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

### 5.3 带宽要说在前面

5 Mbps 约等于 600 KB/s，接口和图片共用：
- 一屏 20 张 375 宽的缩略图（按每张 40 KB 估）约 800 KB，满速约 1.3 秒；
- 一张没压缩的 3 MB 原图约 5 秒。

测试阶段够用；**T5（缩略图）和 T6（上传压缩）不是优化，是这个方案能用的前提**，所以和转发一起上线。
每张图实际多大还没量过（COS 停服，读不到对象），T0 之后第一件事就是抽样量一次。

### 5.4 回滚（现在这一阶段）

- **T1、T2**：删 DNS 记录、删 server 块即可，此时还没有任何数据指向新域名。
- **T3、T4 之后**：再跑一次反向替换的迁移（`img.hxmall.top` → COS 公网域名），并清空 `COS_DOMAIN`。
  客户端又会直连 COS，也就是回到欠费前的状态。**只在新域名整体不可用时才用**；
  平时的问题都应该在 nginx 那层修。

## 六、待确认

| # | 问题 | 卡住谁 | 默认做法 |
|---|---|---|---|
| Q1 | 数据万象的样式分隔符是否支持 `!` | 将来切换 4.3 第 5 步 | 不支持的话，改用它支持的分隔符，并**在 T5 上线前**统一两边的写法。一旦产出过缩略图地址，再改写法就要发版 |
| Q2 | CDN 证书续期：acme deploy hook 还是腾讯云托管证书 | 将来切换 4.3 第 4 步 | 切换前定，不影响现在 |
| Q3 | 桶改私有之后，服务器取图要带签名（nginx 做不了签名） | 将来反向切换 | 反向切换前先把桶改回公有读，或者让后端代取。现在不做 |
| Q4 | 欠费是否真的来自 COS 流量 | 判断本方案能不能根治欠费 | 请在费用中心按产品看明细；如果主要来自别的服务（比如数据万象），本方案只解决图片这一项 |
