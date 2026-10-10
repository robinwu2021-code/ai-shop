#!/usr/bin/env python3
# 自动报备发货短信模板（TDD-收件人物流触达与分享裂变 §2.2）。
#
# 阿里云短链服务（AddShortUrl）2023 已下线，自有域名短链只能走「模板里固定域名 + 路径变量」：
#   内容 = 您的包裹已发货…… s.hxmall.top/${code}      变量 code 是 link_param（只放路径段）
# 域名 s.hxmall.top 须已 ICP 备案（hxmall.top 全子域在线，已备案）。
#
# 用法（在生产机上，env 里有 ALI_SMS_AK/SK/SIGN）：
#   set -a; . /data/app/ai-shop/shop-app/shop-app.env; set +a
#   python3 ali-sms-ship-template.py create     # 提交模板，打印 TemplateCode（审核中）
#   python3 ali-sms-ship-template.py status SMS_xxx   # 查审核状态
#
# AK/SK 只从环境变量读，不写进脚本、不打印值。
import os, sys, json, time, uuid, hmac, hashlib, base64, datetime, urllib.parse, urllib.request

AK=os.environ["ALI_SMS_AK"]; SK=os.environ["ALI_SMS_SK"]
SIGN=os.environ.get("ALI_SMS_SIGN","数智邻购")
ENDPOINT="https://dysmsapi.aliyuncs.com/"

def enc(s):
    return urllib.parse.quote(str(s), safe="~").replace("+","%20").replace("*","%2A").replace("%7E","~")

def call(action, params):
    p={"Format":"JSON","Version":"2017-05-25","AccessKeyId":AK,"SignatureMethod":"HMAC-SHA1",
       "Timestamp":datetime.datetime.utcnow().strftime("%Y-%m-%dT%H:%M:%SZ"),
       "SignatureVersion":"1.0","SignatureNonce":uuid.uuid4().hex,"Action":action,"RegionId":"cn-hangzhou"}
    p.update(params)
    q="&".join("%s=%s"%(enc(k),enc(p[k])) for k in sorted(p))
    sts="POST&%2F&"+enc(q)
    sig=base64.b64encode(hmac.new((SK+"&").encode(),sts.encode(),hashlib.sha1).digest()).decode()
    body=("Signature="+enc(sig)+"&"+q).encode()
    req=urllib.request.Request(ENDPOINT,data=body,headers={"Content-Type":"application/x-www-form-urlencoded"})
    try:
        return json.load(urllib.request.urlopen(req,timeout=20))
    except urllib.error.HTTPError as e:
        return json.load(e)

CONTENT="您的包裹已发货，物流与签收进度请点击 s.hxmall.top/${code} 查看。"
REMARK=("虹选商城电商订单：买家下单、商家发货后，系统给收货人发送一条物流进度短信，"
        "内含本商城已 ICP 备案域名 s.hxmall.top 的物流查询短链（302 跳转到小程序/H5 看件页）。"
        "示例：您的包裹已发货，物流与签收进度请点击 s.hxmall.top/AB12345 查看。")

if sys.argv[1]=="create":
    r=call("CreateSmsTemplate",{
        "TemplateType":"1","TemplateName":"虹选-发货物流触达",
        "TemplateContent":CONTENT,"TemplateRule":json.dumps({"code":"link_param"},ensure_ascii=False),
        "RelatedSignName":SIGN,"Remark":REMARK})
    print(json.dumps(r,ensure_ascii=False))
elif sys.argv[1]=="status":
    r=call("GetSmsTemplate",{"TemplateCode":sys.argv[2]})
    print(json.dumps(r,ensure_ascii=False))
