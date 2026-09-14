#!/usr/bin/env python3
"""标准库封面回填：条码 → Open Food Facts 正面图 → 自家 COS。

**为什么是条码不是关键词**：OFF 的关键词搜索对中文返回 0，只有按条码能命中；
而条码命中的是**那一件商品本身**（不是一张泛用的库存照）。
标准库 311 个 SPU 里 297 个有条码，抽样 25 个的图片命中率 84%。

**为什么必须落到自己的 COS**：小程序只加载白名单内已备案域名，
外链在真机上一律 `url not in domain list`。

⚠️ OFF 的图片是 **CC BY-SA 3.0**：要署名。调用方负责在法务页挂一行。

用法（在服务器上跑，凭据只在那儿）：
    scp scripts/backfill-std-cover.py <服务器>:/tmp/
    ssh <服务器> "sudo python3 /tmp/backfill-std-cover.py --limit 5 --dry"   # 只看会做什么
    ssh <服务器> "sudo python3 /tmp/backfill-std-cover.py --limit 400"        # 真的传

**它只生成 SQL，不执行**（写在 /tmp/*.sql）—— 写库是另一次决定。
回滚：`update prd_spu_std set cover=null where cover like '%/catalog/std/%';`
"""
import argparse, hashlib, hmac, json, os, subprocess, sys, time, urllib.request

UA = "ai-shop-seed/1.0 (catalog cover backfill)"


def cos_put(sid, skey, bucket, region, key, body, ctype):
    """COS 签名 v5 的 PUT。手写而不是装 SDK —— 服务器上没有 qcloud_cos，
    而这套签名只有二十行，装一个 SDK 反而多一层要维护的东西。"""
    host = f"{bucket}.cos.{region}.myqcloud.com"
    now = int(time.time()); exp = now + 600
    ktime = f"{now};{exp}"
    signkey = hmac.new(skey.encode(), ktime.encode(), hashlib.sha1).hexdigest()
    # ⚠️ **必须显式给 public-read。** 后端的 CosMediaStore 是按 key 的第 3 段判的
    #（`entity/store/用途/…`，用途 == goods 才公读），而这里的 key 是 `catalog/std/…`，
    # 落在那套约定之外 —— 不给 ACL 传上去就是 403，而库里写的却是一条看着正常的 URL。
    # 2026-09-08 第一版就是这么传的，靠传完立刻 curl 一次才发现。
    headers = {"content-type": ctype, "host": host, "x-cos-acl": "public-read"}
    hlist = ";".join(sorted(headers))
    hstr = "&".join(f"{k}={urllib.parse.quote(headers[k], safe='')}" for k in sorted(headers))
    http = f"put\n/{key}\n\n{hstr}\n"
    sts = "sha1\n" + ktime + "\n" + hashlib.sha1(http.encode()).hexdigest() + "\n"
    sig = hmac.new(signkey.encode(), sts.encode(), hashlib.sha1).hexdigest()
    auth = (f"q-sign-algorithm=sha1&q-ak={sid}&q-sign-time={ktime}&q-key-time={ktime}"
            f"&q-header-list={hlist}&q-url-param-list=&q-signature={sig}")
    req = urllib.request.Request(f"https://{host}/{key}", data=body, method="PUT",
                                 headers={"Authorization": auth, "Content-Type": ctype,
                                          "Host": host, "x-cos-acl": "public-read"})
    with urllib.request.urlopen(req, timeout=30) as r:
        st = r.status
    # **传完立刻回读一次**：PUT 成功只说明写进去了，不说明取得到
    url = f"https://{host}/{key}"
    chk = urllib.request.urlopen(urllib.request.Request(url, method="HEAD"), timeout=20).status
    if chk != 200:
        raise RuntimeError(f"传上去了但读不到（{chk}）—— 检查 ACL")
    return st, url


def off_image(bc, tries=4):
    """查 OFF。**必须退避重试** —— 2026-09-08 首次全量跑，0.3s 一次的节奏
    让 117 条里 40 条挂在 `429 Too Many Requests` 上，而失败的样子是「这个商品没图」，
    看日志才知道是被限流。OFF 的产品接口限额约 100 次/分，这里按 1s 一次走。"""
    u = f"https://world.openfoodfacts.org/api/v2/product/{bc}.json?fields=product_name,image_front_url"
    for i in range(tries):
        try:
            r = urllib.request.Request(u, headers={"User-Agent": UA})
            p = (json.load(urllib.request.urlopen(r, timeout=25)).get("product") or {})
            return p.get("image_front_url"), p.get("product_name")
        except urllib.error.HTTPError as e:
            if e.code != 429 or i == tries - 1:
                raise
            time.sleep(4 * (i + 1))
    return None, None


def cos_exists(bucket, region, key):
    """这个 key 是不是已经传过了。

    **脚本不写库**（SQL 只生成不执行），所以重跑时选出来的还是同一批 ——
    没有这一步，每次重跑都要把已经传好的几百张再下再传一遍。"""
    url = f"https://{bucket}.cos.{region}.myqcloud.com/{key}"
    try:
        return urllib.request.urlopen(urllib.request.Request(url, method="HEAD"), timeout=15).status == 200
    except Exception:
        return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=5)
    ap.add_argument("--dry", action="store_true")
    a = ap.parse_args()
    env = {}
    for line in open("/data/app/ai-shop/shop-app/shop-app.env"):
        if "=" in line and not line.startswith("#"):
            k, _, v = line.strip().partition("=")
            env[k] = v
    sid, skey = env["COS_SECRET_ID"], env["COS_SECRET_KEY"]
    bucket, region = env["COS_BUCKET"], env["COS_REGION"]
    q = ("select std_no,barcode,title from prd_spu_std where deleted=0 and (cover is null or cover='') "
         f"and barcode is not null and barcode<>'' limit {a.limit}")
    out = subprocess.run(["mysql", "-u" + env["SHOP_DB_USER"], "-p" + env["SHOP_DB_PASS"],
                          "-N", "-e", q, env.get("SHOP_DB_NAME", "ai_shop")],
                         capture_output=True, text=True).stdout
    rows = [l.split("\t") for l in out.strip().split("\n") if l.strip()]
    print(f"待处理 {len(rows)} 条")
    done = []
    for std_no, bc, title in rows:
        try:
            src, name = off_image(bc)
        except Exception as e:
            print(f"  ✗ {std_no} {title[:16]} OFF 查询失败 {e}"); continue
        if not src:
            print(f"  – {std_no} {title[:16]} OFF 无图"); continue
        if a.dry:
            print(f"  · {std_no} {title[:16]:18s} ← {name} {src[:56]}"); continue
        ext = ".jpg" if src.lower().endswith((".jpg", ".jpeg")) else ".png"
        key = f"catalog/std/{bc}{ext}"
        host = f"{bucket}.cos.{region}.myqcloud.com"
        if cos_exists(bucket, region, key):
            print(f"  ↷ {std_no} {title[:16]:18s} 已在 COS，跳过")
            done.append((std_no, f"https://{host}/{key}"))
            continue
        body = urllib.request.urlopen(urllib.request.Request(src, headers={"User-Agent": UA}), timeout=30).read()
        st, url = cos_put(sid, skey, bucket, region, key, body,
                          "image/jpeg" if ext == ".jpg" else "image/png")
        print(f"  ✓ {std_no} {title[:16]:18s} {len(body)//1024}KB → {url}")
        done.append((std_no, url))
        time.sleep(1.0)   # OFF 限额约 100 次/分
    if done and not a.dry:
        import tempfile
        with tempfile.NamedTemporaryFile("w", suffix=".sql", delete=False) as f:
            for std_no, url in done:
                f.write(f"update prd_spu_std set cover='{url}' where std_no='{std_no}' and deleted=0;\n")
            print(f"\nSQL 写在 {f.name}（**没有执行**，看过再决定）")


if __name__ == "__main__":
    import urllib.parse
    main()
