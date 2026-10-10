# 阿里云 DNS 解析（域名在阿里云，服务器在腾讯云）

`ichain.top` / `hxtech.top` 注册与解析都在阿里云（NS 是 hichina），服务器在腾讯云
Lighthouse `106.55.27.246`。两边分属不同厂商，所以 [deploy/tencent](../tencent) 里那套
`setup-tls.sh` 的 `DvAuthMethod DNS_AUTO` **在这两个域名上用不了**——它要求域名托管在
同账号 DNSPod 才能自动写 TXT 验证记录。

没有把 NS 整体切到 DNSPod，是因为这两个域名下各有 11 / 18 条在用记录（企业邮箱 MX、
证书验证 CNAME、其他站点 A 记录），整体搬迁风险大于收益。

## 工具

[alidns.py](alidns.py)，只用 Python 标准库，不需要装 `aliyun` CLI。

```bash
python3 deploy/aliyun/alidns.py domains                                # 列域名
python3 deploy/aliyun/alidns.py list hxtech.top                        # 列解析记录
python3 deploy/aliyun/alidns.py set  hxtech.top www A 106.55.27.246    # 幂等：无则建，有则改
python3 deploy/aliyun/alidns.py rm   hxtech.top www A
```

`set` 幂等：同名同类型不存在就新建，存在就改值（值和 TTL 都相同则跳过、不发写请求），
记录处于停用状态会一并启用。同名同类型有多条时**拒绝自动改**，避免猜错改坏。

### 凭据

**不入库**。默认读 `~/private/公司/ali/accesskey-*.txt`（阿里云控制台下载的原始格式，
每行 `字段名<空白>值`）。用 `ALI_CRED=/path/to/file` 覆盖。

## 验证解析要走 API 回读，别信 dig

部分开发环境的 DNS 被本地解析器劫持，`dig +short www.hxtech.top @dns25.hichina.com`
会返回一个私网地址，三个不同 NS 返回值还一模一样。用 `alidns.py list` 从权威接口回读，
或换手机热点 / `curl https://1.1.1.1/dns-query` 验证。

## 备案约束（大陆地域）

目标机在 `ap-guangzhou`，**大陆地域受 ICP 备案约束**：域名必须已备案并「接入」到腾讯云
这个实例，80/443 才对外可用。解析改过去会立刻生效，但没备案的话腾讯云会拦截这两个端口
——表现为解析对、页面打不开。签证书、配 nginx 之前先去腾讯云控制台确认备案状态。

## 当前状态

2026-08-18 的改动，**全部只改了这三条 A 记录**，MX / 企业邮箱 / 证书验证记录一律未动：

| 记录 | 改后 | 原值（回滚用） |
|---|---|---|
| `www.hxtech.top` A | `106.55.27.246` | `193.112.132.86`（当时是停用状态） |
| `www.ichain.top` A | `106.55.27.246` | `35.194.228.47` |
| `ichain.top` @ A | `106.55.27.246` | `35.194.228.47` |

回滚：`python3 alidns.py set ichain.top www A 35.194.228.47`（@ 同理）。

**未改动但要知道**：`ichain.top` 的泛解析 `*` 以及 `shop` / `news` / `chip` / `seller`
仍指向 `35.194.228.47`（那台跑 nginx 1.17.0，80 跳 443，`shop` 当时已 502）。
`hxtech.top` 的 `@` 仍指向 `35.194.228.47`。

## TLS 证书

用 [../tencent/setup-tls-acme.sh](../tencent/setup-tls-acme.sh)，加 `dns_ali` 参数：

```bash
bash deploy/tencent/setup-tls-acme.sh '*.ichain.top,ichain.top' dns_ali
```

**不需要备案**：ACME 走 DNS-01 验证，只写 TXT 记录，不碰 80/443，也不要求网站可访问。
备案只决定站点能不能对外提供服务，与能否拿到证书无关 —— 证书可以现在就备好。

**不需要切 NS**：`dns_ali` 插件直接调阿里云 DNS API 写验证记录。

**泛域名可以签**。常见误解是「阿里云/腾讯云不支持泛域名证书」—— 那说的是**它们自家的免费
证书产品**（只给单域名，泛域名要付费）。本脚本的 CA 是 **Let's Encrypt**，阿里云在这里
只是被调 API 写一条 TXT 的 DNS 服务商，泛不泛域名对它没区别。
泛域名验证的记录名是 `_acme-challenge.<裸域>`，和单域名验证完全一样。

注意 `*.ichain.top` **不覆盖裸域** `ichain.top`，两个都要就按上面那样一起列出来；
它们的验证记录同名，靠同名两条 TXT 区分，阿里云支持，acme.sh 自行添加和清理。

腾讯云免费证书那条路（[../tencent/setup-tls.sh](../tencent/setup-tls.sh) 的 `DNS_AUTO`）
在这两个域名上**用不了** —— 它要求域名托管在同账号 DNSPod 才能自动写 TXT。

nginx 站点配置可复用 `setup-tls.sh` 里的模板（SSE 关缓冲那段对本项目是必需的）。
