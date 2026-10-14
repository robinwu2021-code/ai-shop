#!/usr/bin/env python3
"""COS 上传（签名 v5 手写）。没装 coscli 也没装 SDK —— 手写 30 行比在别人机器上
装一个全局包干净，而且这段逻辑只用一次。"""
import hashlib, hmac, os, sys, time, urllib.request

SID = os.environ["TENCENTCLOUD_SECRET_ID"]
SKEY = os.environ["TENCENTCLOUD_SECRET_KEY"]

def sign(method, host, uri, headers):
    now = int(time.time()); exp = now + 600
    keytime = f"{now};{exp}"
    signkey = hmac.new(SKEY.encode(), keytime.encode(), hashlib.sha1).hexdigest()
    hl = sorted(k.lower() for k in headers)
    hstr = "&".join(f"{k}={urllib.parse.quote(str(headers[[x for x in headers if x.lower()==k][0]]), safe='')}" for k in hl)
    http_string = f"{method.lower()}\n{uri}\n\n{hstr}\n"
    sts = f"sha1\n{keytime}\n{hashlib.sha1(http_string.encode()).hexdigest()}\n"
    sig = hmac.new(signkey.encode(), sts.encode(), hashlib.sha1).hexdigest()
    return ("q-sign-algorithm=sha1&q-ak=" + SID + "&q-sign-time=" + keytime +
            "&q-key-time=" + keytime + "&q-header-list=" + ";".join(hl) +
            "&q-url-param-list=&q-signature=" + sig)

def put(bucket, region, key, path):
    host = f"{bucket}.cos.{region}.myqcloud.com"
    uri = "/" + key
    body = open(path, "rb").read()
    headers = {"Host": host, "Content-Length": str(len(body))}
    auth = sign("put", host, uri, headers)
    req = urllib.request.Request("https://" + host + uri, data=body, method="PUT",
                                 headers={**headers, "Authorization": auth})
    with urllib.request.urlopen(req, timeout=300) as r:
        return r.status, r.headers.get("ETag", "")

if __name__ == "__main__":
    bucket, region, key, path = sys.argv[1:5]
    st, etag = put(bucket, region, key, path)
    md5 = hashlib.md5(open(path, "rb").read()).hexdigest()
    print(f"{st}  key={key}\n  ETag={etag}\n  本地 md5={md5}  ← 两者要一致（分块上传才会不同）")
