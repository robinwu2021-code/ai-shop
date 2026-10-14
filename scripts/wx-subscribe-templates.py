#!/usr/bin/env python3
"""微信订阅消息**模板注册**：把「去后台点选模板」变成可复现、可登记的一条命令。

  python3 scripts/wx-subscribe-templates.py categories          # 本小程序挂了哪些类目
  python3 scripts/wx-subscribe-templates.py search 发货         # 公共模板库里搜标题
  python3 scripts/wx-subscribe-templates.py keywords 401        # 某个公共模板有哪几格
  python3 scripts/wx-subscribe-templates.py add 401 1,2,3 "订单发货后通知买家"
  python3 scripts/wx-subscribe-templates.py mine                # 已选进账号的模板
  python3 scripts/wx-subscribe-templates.py del <priTmplId>

**为什么不手工去后台点**：后台点完只留下一个模板号，没人知道当初为什么选这一个、
那几格分别映射到哪个业务字段。走脚本的话，`add` 的入参（tid + kidList + 场景说明）
本身就是决策记录，而 `search` / `keywords` 的输出可以贴进 TDD 当依据。

## access_token 用 stable_token，不用 cgi-bin/token

**这一条不能改**。老接口每次调用都签发新 token 并挤掉旧的 ——
这个脚本一跑就会把**正在跑的后端**的 token 挤掉，表现是线上随机 40001，
而没有任何东西会指向「刚才有人跑了个脚本」。stable_token 在有效期内返回同一个，
与 `WxSubscribeGateway` 取的是同一个，天然共存。

## 只能在生产服务器上跑（IP 白名单）

微信对这些接口做 **IP 白名单**，名单里是生产服务器 —— 从开发机直连拿到的是
`40164 invalid ip ... not in whitelist`。所以本脚本检测到不在服务器上时，
**会把自己传过去执行**，你在本机敲同一条命令即可，不用自己 scp。

## 凭据从哪来

appid/secret 在服务器的 shop-app.env 里，**本脚本不打印它们**，也不接受命令行传入
（命令行会进 shell 历史与 ps）。脚本在服务器上执行时直接读那个文件。
"""
import json
import os
import subprocess
import sys
import urllib.parse
import urllib.request

API = "https://api.weixin.qq.com"
ENV_PATH = os.environ.get("WX_ENV_PATH", "/data/app/ai-shop/shop-app/shop-app.env")
HOST = os.environ.get("HOST", "soukmind-tx")


def _creds() -> tuple[str, str]:
    """取 appid/secret。**只取这两个键，不整份读出来** —— env 里还有数据库口令等等。"""
    if os.path.exists(ENV_PATH):
        text = open(ENV_PATH, encoding="utf8").read()
    else:
        text = subprocess.run(
            ["ssh", HOST, f"sudo grep -E '^(WX_APPID|WX_SECRET)=' {ENV_PATH}"],
            capture_output=True, text=True, check=True).stdout
    vals = {}
    for line in text.splitlines():
        if line.startswith(("WX_APPID=", "WX_SECRET=")):
            k, _, v = line.partition("=")
            vals[k] = v.strip()
    if not vals.get("WX_APPID") or not vals.get("WX_SECRET"):
        sys.exit(f"在 {ENV_PATH} 里找不到 WX_APPID / WX_SECRET（HOST={HOST}）")
    return vals["WX_APPID"], vals["WX_SECRET"]


def _token() -> str:
    appid, secret = _creds()
    body = json.dumps({"grant_type": "client_credential", "appid": appid,
                       "secret": secret, "force_refresh": False}).encode()
    req = urllib.request.Request(f"{API}/cgi-bin/stable_token", data=body,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=15) as r:
        data = json.load(r)
    if "access_token" not in data:
        sys.exit(f"取 token 失败：{data}")
    return data["access_token"]


def _get(path: str, **params) -> dict:
    params["access_token"] = _token()
    url = f"{API}{path}?{urllib.parse.urlencode(params)}"
    with urllib.request.urlopen(url, timeout=15) as r:
        return json.load(r)


def _post(path: str, payload: dict) -> dict:
    url = f"{API}{path}?access_token={_token()}"
    req = urllib.request.Request(url, data=json.dumps(payload).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.load(r)


def _die_on_err(d: dict) -> dict:
    if d.get("errcode"):
        sys.exit(f"微信返回 {d.get('errcode')}：{d.get('errmsg')}")
    return d


def categories():
    d = _die_on_err(_get("/wxaapi/newtmpl/getcategory"))
    for c in d.get("data", []):
        print(f"{c['id']:>6}  {c['name']}")
    print("\n（search 默认用这些 id；公共模板库按类目开放，不在这几个类目下的模板选不了）")


def search(keyword: str):
    ids = ",".join(str(c["id"]) for c in _die_on_err(_get("/wxaapi/newtmpl/getcategory")).get("data", []))
    hit = total = 0
    start = 0
    while True:
        d = _die_on_err(_get("/wxaapi/newtmpl/getpubtemplatetitles",
                             ids=ids, start=start, limit=30))
        rows = d.get("data", [])
        total = d.get("count", 0)
        for t in rows:
            if keyword in t["title"]:
                print(f"tid={t['tid']:>6}  {t['title']}   [{t.get('type')}]")
                hit += 1
        start += len(rows)
        if not rows or start >= total:
            break
    print(f"\n公共模板库共 {total} 条，命中「{keyword}」{hit} 条")
    if not hit:
        print("一条都没命中 —— 换个词再搜，别急着下「微信没有这个模板」的结论")


def keywords(tid: str):
    d = _die_on_err(_get("/wxaapi/newtmpl/getpubtemplatekeywords", tid=tid))
    print(f"tid={tid} 的可选格（add 时按 kid 挑，顺序即模板里的顺序）：")
    for k in d.get("data", []):
        print(f"  kid={k['kid']:>3}  {k['name']:<12} 例：{k.get('example','')}   规则：{k.get('rule','')}")
    print("\n⚠️ 挑**能填满**的那几格：字段填不满会被审核打回，"
          "或者发出去是一条看不懂的消息。")


def add(tid: str, kid_list: str, scene: str):
    kids = [int(x) for x in kid_list.split(",") if x.strip()]
    d = _die_on_err(_post("/wxaapi/newtmpl/addtemplate",
                          {"tid": tid, "kidList": kids, "sceneDesc": scene}))
    print(f"priTmplId = {d['priTmplId']}")
    print("\n把它写进两处，**必须同值**：")
    print("  后端  WX_TPL_*（服务器 shop-app.env）")
    print("  前端  VITE_WX_TPL_*（c-app/.env.production）")
    print("不同值的症状是「用户点了允许、额度记下了、一条都发不出去」，两边各自看都像配好了。")


def mine():
    d = _die_on_err(_get("/wxaapi/newtmpl/gettemplate"))
    rows = d.get("data", [])
    for t in rows:
        print(f"{t['priTmplId']}  {t['title']}")
        print(f"    {t.get('content','').strip()}")
    print(f"\n共 {len(rows)} 个（一次性订阅上限 50）")


def delete(pri: str):
    _die_on_err(_post("/wxaapi/newtmpl/deltemplate", {"priTmplId": pri}))
    print(f"已删除 {pri}")


def _run_on_server() -> None:
    """把自己传到服务器执行 —— 微信的 IP 白名单里只有生产机。

    用 `cat > /tmp/...` 而不是 scp：scp 在有些 ssh 配置下要额外的 sftp 子系统，
    而这里只需要把一个文本文件送过去。执行完不删，下次改了再覆盖。
    """
    src = open(__file__, "rb").read()
    remote = "/tmp/wx-subscribe-templates.py"
    quoted = " ".join(f"'{a}'" for a in sys.argv[1:])
    p = subprocess.run(
        ["ssh", HOST, f"cat > {remote} && python3 {remote} {quoted}"],
        input=src, capture_output=True)
    sys.stdout.write(p.stdout.decode("utf8", "replace"))
    sys.stderr.write(p.stderr.decode("utf8", "replace"))
    sys.exit(p.returncode)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    # 不在服务器上（读不到那份 env）→ 传过去跑。带 WX_NO_HOP 可以强行本地跑。
    if not os.path.exists(ENV_PATH) and not os.environ.get("WX_NO_HOP"):
        _run_on_server()
    cmd, args = sys.argv[1], sys.argv[2:]
    if cmd == "categories":
        categories()
    elif cmd == "search" and args:
        search(args[0])
    elif cmd == "keywords" and args:
        keywords(args[0])
    elif cmd == "add" and len(args) >= 3:
        add(args[0], args[1], " ".join(args[2:]))
    elif cmd == "mine":
        mine()
    elif cmd == "del" and args:
        delete(args[0])
    else:
        print(__doc__)
        sys.exit(2)


if __name__ == "__main__":
    main()
