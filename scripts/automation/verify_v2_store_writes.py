"""v2 运营端门店写操作验证：只动【网关验证】测试门店，做完逐项复原。

有权（BD）能做、无权（商品运营、财务）被网关拒；锁路→解锁、改名→改回、第三方模式被业务规则拒。
用法：cd scripts/automation && V2_SSH=<u80.sh> python verify_v2_store_writes.py
"""
import json
from v2_session import call, login
ST="ST261119BE70D8B9C9"; M="M261119TS0PN0XBK9"; CH="STORE_PICKUP"
tok={r:login("OPS",a) for r,a in [("ADMIN","ST-V2TEST-ADMIN"),("BD","ST-V2TEST-BD"),("GOODS","ST-V2TEST-GOODS-OPS"),("FIN","ST-V2TEST-FINANCE")]}
def c(who,m,p,body=None):
    s,code,b=call("OPS",m,p,tok[who],body=body)
    d=b.get("data") if isinstance(b,dict) else b
    msg=b.get("msg") or b.get("message") if isinstance(b,dict) else None
    print(f"{who:5} {m:4} {p} -> {s} code={code} msg={msg}")
    return s,code,d
def store(): return c("ADMIN","GET",f"/ops/stores/{ST}")[2]["store"]
def chan():
    d=c("ADMIN","GET",f"/ops/merchants/{M}/fulfillment")[2]
    return [x for x in d[0]["channels"] if x.get("channel",x.get("code"))==CH] if d else d
before=store(); print("BEFORE name/mode/status:",before["name"],before["businessMode"],before["status"])
print("BEFORE channel:",json.dumps(chan(),ensure_ascii=False))
print("\n== A 锁路/解锁")
for w in ("GOODS","FIN"): c(w,"POST",f"/ops/stores/{ST}/channels/{CH}/lock",{"reason":"网关验证"})
s,_,_=c("BD","POST",f"/ops/stores/{ST}/channels/{CH}/lock",{"reason":"网关验证·写操作测试"})
print(" after lock:",json.dumps(chan(),ensure_ascii=False))
c("BD" if s==200 else "ADMIN","POST",f"/ops/stores/{ST}/channels/{CH}/unlock")
print(" after unlock:",json.dumps(chan(),ensure_ascii=False))
print("\n== B 改门店名")
nb={"name":before["name"]+"·改名测试","reason":"网关验证·写操作测试"}
for w in ("GOODS","FIN"): c(w,"PUT",f"/ops/stores/{ST}/basics",nb)
s,_,_=c("BD","PUT",f"/ops/stores/{ST}/basics",nb)
print(" after rename:",store()["name"])
c("BD" if s==200 else "ADMIN","PUT",f"/ops/stores/{ST}/basics",{"name":before["name"],"reason":"网关验证·改回原名"})
print(" after restore:",store()["name"])
print("\n== C 经营模式")
for w in ("GOODS","FIN"): c(w,"PUT",f"/ops/stores/{ST}/business-mode",{"businessMode":"SELF_OPERATED"})
c("BD","PUT",f"/ops/stores/{ST}/business-mode",{"businessMode":"THIRD_PARTY"})
print(" after THIRD_PARTY attempt:",store()["businessMode"])
c("BD","PUT",f"/ops/stores/{ST}/business-mode",{"businessMode":"SELF_OPERATED"})
after=store()
print("\nAFTER name/mode/status:",after["name"],after["businessMode"],after["status"])
print("UNCHANGED:",{k:before[k]==after[k] for k in before})
