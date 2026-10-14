#!/usr/bin/env python3
"""阿里云 DNS(Alidns) 最小客户端 —— 只用标准库，不需要装 aliyun CLI。

凭据**故意在仓库外**：默认读 ~/private/公司/ali/accesskey-*.txt，
格式是阿里云控制台下载的那份（每行 `字段名<空白>值`）：
    accessKeyId      LTAI...
    accessKeySecret  ...
用 ALI_CRED 环境变量可指到别的文件。

用法：
    python3 alidns.py domains                          # 列出账号下所有域名
    python3 alidns.py list  ichain.top                 # 列出解析记录
    python3 alidns.py set   ichain.top shop A 106.55.27.246
    python3 alidns.py set   ichain.top _acme-challenge TXT "xxx"
    python3 alidns.py rm    ichain.top shop A
set 是幂等的：同名同类型已存在就改值（值相同则跳过），不存在才新建。
"""
import base64, datetime, glob, hashlib, hmac, json, os, sys, urllib.error, urllib.parse, urllib.request, uuid

DEFAULT_CRED_GLOB = os.path.expanduser("~/private/公司/ali/accesskey-*.txt")
ENDPOINT, VERSION = "alidns.aliyuncs.com", "2015-01-09"


def load_cred():
    path = os.environ.get("ALI_CRED") or next(iter(sorted(glob.glob(DEFAULT_CRED_GLOB))), None)
    if not path or not os.path.exists(path):
        sys.exit(f"✗ 找不到阿里云凭据文件（{os.environ.get('ALI_CRED') or DEFAULT_CRED_GLOB}）")
    kv = {}
    for line in open(path):
        parts = line.split()
        if len(parts) >= 2:
            kv[parts[0].strip()] = parts[1].strip()
    try:
        return kv["accessKeyId"], kv["accessKeySecret"]
    except KeyError:
        sys.exit(f"✗ {path} 里没有 accessKeyId / accessKeySecret 两行")


def _pct(s):
    # 阿里云签名要求的 percent-encode，与 RFC3986 差在 + / * / ~ 三处
    return (urllib.parse.quote(str(s), safe="-_.~")
            .replace("+", "%20").replace("*", "%2A").replace("%7E", "~"))


def call(action, **params):
    """签名 v1 (HMAC-SHA1) 的 RPC 调用。"""
    ak, sk = load_cred()
    p = {
        "Action": action, "Version": VERSION, "Format": "JSON", "AccessKeyId": ak,
        "SignatureMethod": "HMAC-SHA1", "SignatureVersion": "1.0",
        "SignatureNonce": uuid.uuid4().hex,
        "Timestamp": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }
    p.update({k: v for k, v in params.items() if v is not None})
    canon = "&".join(f"{_pct(k)}={_pct(p[k])}" for k in sorted(p))
    p["Signature"] = base64.b64encode(
        hmac.new((sk + "&").encode(), ("GET&%2F&" + _pct(canon)).encode(), hashlib.sha1).digest()).decode()
    try:
        with urllib.request.urlopen(f"https://{ENDPOINT}/?" + urllib.parse.urlencode(p), timeout=20) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        try:
            err = json.load(e)
            sys.exit(f"✗ {err.get('Code')}: {err.get('Message')}")
        except (json.JSONDecodeError, ValueError):
            sys.exit(f"✗ HTTP {e.code}: {e.read().decode()[:400]}")


def records(domain):
    return call("DescribeDomainRecords", DomainName=domain, PageSize=500)["DomainRecords"]["Record"]


def cmd_domains():
    for d in call("DescribeDomains", PageSize=100)["Domains"]["Domain"]:
        ns = ",".join(d.get("DnsServers", {}).get("DnsServer", []))
        print(f"{d['DomainName']:<20} 记录 {d.get('RecordCount'):>3}  NS {ns}")


def cmd_list(domain):
    for r in records(domain):
        flag = "" if r.get("Status") == "ENABLE" else "  [停用]"
        print(f"{r['RR']:<24} {r['Type']:<6} {r['Value']:<48} TTL={r['TTL']}{flag}")


def cmd_set(domain, rr, rtype, value, ttl="600"):
    rtype = rtype.upper()
    hit = [r for r in records(domain) if r["RR"] == rr and r["Type"] == rtype]
    if not hit:
        rid = call("AddDomainRecord", DomainName=domain, RR=rr, Type=rtype,
                   Value=value, TTL=ttl)["RecordId"]
        print(f"✓ 新建 {rr}.{domain} {rtype} → {value}  (RecordId={rid})")
        return
    if len(hit) > 1:
        sys.exit(f"✗ {rr}.{domain} 有 {len(hit)} 条同类型记录，拒绝自动改。先用 list 看清楚再手工处理")
    old = hit[0]
    same = old["Value"] == value and str(old["TTL"]) == str(ttl)
    enabled = old.get("Status") == "ENABLE"
    if same and enabled:
        print(f"= 已是目标值，跳过：{rr}.{domain} {rtype} → {value}")
        return
    # 值与 TTL 都没变时**不能**调 UpdateDomainRecord —— 阿里云会以 DomainRecordDuplicate 拒绝。
    # 这种情况只需要把停用的记录启用回来。
    if same:
        call("SetDomainRecordStatus", RecordId=old["RecordId"], Status="Enable")
        print(f"✓ 启用 {rr}.{domain} {rtype} → {value}（值未变）")
        return
    call("UpdateDomainRecord", RecordId=old["RecordId"], RR=rr, Type=rtype, Value=value, TTL=ttl)
    if not enabled:
        call("SetDomainRecordStatus", RecordId=old["RecordId"], Status="Enable")
    print(f"✓ 改值 {rr}.{domain} {rtype}: {old['Value']} → {value}")


def cmd_rm(domain, rr, rtype):
    rtype = rtype.upper()
    hit = [r for r in records(domain) if r["RR"] == rr and r["Type"] == rtype]
    if not hit:
        print(f"= 无此记录，跳过：{rr}.{domain} {rtype}")
        return
    for r in hit:
        call("DeleteDomainRecord", RecordId=r["RecordId"])
        print(f"✓ 删除 {rr}.{domain} {rtype} → {r['Value']}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    cmd, args = sys.argv[1], sys.argv[2:]
    fn = {"domains": cmd_domains, "list": cmd_list, "set": cmd_set, "rm": cmd_rm}.get(cmd)
    if not fn:
        sys.exit(f"✗ 未知子命令 {cmd}\n{__doc__}")
    fn(*args)
