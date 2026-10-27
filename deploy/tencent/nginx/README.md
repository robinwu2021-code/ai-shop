# nginx 站点配置（线上快照）

服务器上的 `/etc/nginx/sites-available/` 两份配置的**副本**，2026-08-24 22:40 取自生产。

留一份在仓库里的理由：这两份文件此前**只存在于那台机器上** —— 重装、换机、
或者谁误删一次 `sites-available`，就得凭记忆重配，而里面攒着好几条踩出来的规则
（子站尾斜杠 301、`/s/` 老店铺码退路、uploads/media 与 www 对齐、缓存策略）。

> ⚠️ **这是快照，不是部署源。** 线上以服务器上那两份为准；
> 改配置仍然直接改服务器（改前 `cp -p` 备份、`nginx -t`、`systemctl reload`），
> 改完把快照同步回来。别写脚本从这里往上覆盖 —— 两个 server 块里
> 有证书路径这类与机器绑定的东西。

> ⚠️ **2026-10-26 起这份快照领先于服务器**（安全规范 §4，P0 · G1 / G2），在服务器照做之前两边不一致：
> 1. `mkdir -p /etc/nginx/ai-shop`，按 `ops-allow.conf.example` 写 `/etc/nginx/ai-shop/ops-allow.conf`（办公网 / VPN 出口网段，**不进仓库**）；
> 2. 把 `www.hxmall.top.conf` 的这几处改动合到服务器那份：全部 `X-Forwarded-For` 改为 `$remote_addr`（覆写，不追加）；
>    `/ops-web/`、`/ops-web/assets/` 加白名单 include + `deny all`；公共正则 location 去掉 `ops`，另起 `/ops/`、`/pay-ops/` 两段；
>    80 端口 server 加 `/internal/` deny；
> 3. `cp -p` 备份 → `nginx -t` → `systemctl reload nginx`；从办公网与外网各开一次 `/ops-web/`，外网应 403；
> 4. 与应用发布**先后无关**：现网 nginx 早已写 `X-Real-IP $remote_addr`，新应用（`ClientMeta` 只认本机 / 私网转来的 `X-Real-IP`）先上也拿得到真 IP；旧应用读被覆写后的 `X-Forwarded-For` 同样是真 IP。
> 仓库里由 `NginxEdgeRulesTest` 守住这几条，改快照时它会先红。

| 文件 | 对应 server |
|---|---|
| `ai-shop-ip.conf` | 按 IP 访问（`http://106.55.27.246/...`）。**商家端那个薄壳走的就是这条** —— `android-shell` release 的 `shell_entry` 指着 `http://106.55.27.246/b/` |
| `www.hxmall.top.conf` | 域名访问（80/443）。`hxmall.top` 备案未过期间 443 被按 SNI 拦，这份暂时形同虚设，但结构要与 IP 那份保持一致 |

## 缓存策略（2026-08-24 加）

三个子站（`/b/`、`/c/`、`/ops-web/`）统一：

- **HTML `Cache-Control: no-cache`** —— 每次都回源校验
- **`*/assets/` `public, max-age=31536000, immutable`** —— 文件名带哈希，可以放心长缓存

起因：此前两者都没有 `Cache-Control`，浏览器与 WebView 按启发式规则自己决定、
且常常不回源。商家端那个壳因此把 `index.html` 缓存住，里面引用的还是上一版的哈希文件名 ——
**发了新版真机上仍是旧界面，而服务器这边一切正常、查不出异常**。
排查时唯一的线索是「清了应用缓存就好了」。

⚠️ `expires 1y` 与 `add_header Cache-Control` **不要并用**：会发出两个 Cache-Control 头，
客户端取哪一个不确定。用一条 `add_header ... always` 就够。
