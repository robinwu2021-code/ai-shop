import json,urllib.request,urllib.error,urllib.parse,hashlib,re,concurrent.futures
from pathlib import Path
hosts=['https://twpay.umeshop.ai','https://nearshop.umeshop.ai']
class NoRedirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,*args,**kwargs):return None
opener=urllib.request.build_opener(NoRedirect)
def fetch(url,method='GET',headers=None,data=None):
 try:r=opener.open(urllib.request.Request(url,data=data,headers=headers or {},method=method),timeout=25)
 except urllib.error.HTTPError as e:r=e
 body=r.read();h=dict((k.lower(),v) for k,v in r.headers.items())
 try:j=json.loads(body)
 except (ValueError,UnicodeDecodeError):j=None
 return dict(status=r.code,headers=h,body=body,json=j)
results=[];cache={}
def check(host,path,kind,method='GET',headers=None):
 r=fetch(host+path,method,headers);code=(r['json'] or {}).get('code') if isinstance(r['json'],dict) else None
 if kind=='page':assert r['status']==200 and b'<html' in r['body'].lower(),(host,path,r['status'])
 elif kind=='public-api':assert r['status']==200 and code==0,(host,path,r['status'],code)
 elif kind=='auth':assert r['status'] in [401,403] or code==10401,(host,path,r['status'],code)
 elif kind=='blocked':assert r['status']==404,(host,path,r['status'],code)
 elif kind=='cors':assert r['status']==200 and r['headers'].get('access-control-allow-origin')==host,(host,path,r['status'],r['headers'])
 elif kind=='cors-deny':assert r['status']==403,(host,path,r['status'])
 cache[host+path]=r
 result=dict(origin=host,path=path,method=method,status=r['status'],businessCode=code,check=kind,passed=True)
 if kind=='cors':result['allowedOrigin']=r['headers']['access-control-allow-origin']
 return result
jobs=[]
for host in hosts:
 for path,kind in [('/ops-shop/','page'),('/ops-shop/login/','page'),('/oms/','page'),('/shop-api/common/master-data','public-api'),('/ops-shop/ops/auth/me','auth'),('/shop-api/biz/context','auth'),('/oms-api/auth/me','auth'),('/internal/','blocked'),('/common/auth/automation','blocked'),('/ops/','blocked'),('/pay-ops/','blocked'),('/api/internal/','blocked'),('/shop-api/internal/','blocked'),('/shop-api/common/auth/automation','blocked'),('/media/DOMAINPROBE/DOMAINPROBE/mallpay/202611/DOMAINPROBE.jpg','blocked')]:jobs.append((host,path,kind))
 for path in ['/shop-api/biz/auth/identities','/ops-shop/ops/auth/login']:
  jobs.append((host,path,'cors','OPTIONS',{'Origin':host,'Access-Control-Request-Method':'POST','Access-Control-Request-Headers':'content-type,authorization'}))
 jobs.append((host,'/shop-api/biz/auth/identities','cors-deny','OPTIONS',{'Origin':'https://untrusted.example.invalid','Access-Control-Request-Method':'POST'}))
with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
 futures=[pool.submit(check,*j) for j in jobs]
 for f in futures:results.append(f.result())
for path in ['/ops-shop/','/ops-shop/login/','/oms/']:
 assert cache[hosts[0]+path]['body']==cache[hosts[1]+path]['body'],'static HTML differs'
 assets=re.findall(rb'<script[^>]+src="([^"]+)"',cache[hosts[1]+path]['body'])[:2]
 for raw in assets:
  asset=raw.decode().replace('&amp;','&');content=[]
  for host in hosts:
   u=urllib.parse.urljoin(host+path,asset);r=fetch(u);assert r['status']==200,(u,r['status']);content.append(r['body'])
  assert content[0]==content[1],'static script differs'
  results.append(dict(path=asset,check='same-static-asset',status=200,sha256=hashlib.sha256(content[0]).hexdigest(),passed=True))
new=hosts[1];old=hosts[0];r=fetch(new+'/');assert r['status']==302 and r['headers']['location']=='/ops-shop/'
results.append(dict(origin=new,path='/',status=r['status'],location=r['headers']['location'],passed=True))
p='/h5/q/DOMAINPROBE?domainProbe=1';r=fetch(new+p);assert r['status']==307 and r['headers']['location']==old+p
assert r['headers'].get('referrer-policy')=='no-referrer'
results.append(dict(origin=new,path=p,status=307,location=r['headers']['location'],check='payment-h5-stays-old-origin',passed=True))
r=fetch(old+p);assert r['status']==200 and b'<html' in r['body'].lower()
results.append(dict(origin=old,path=p,status=200,check='old-payment-h5-preserved',passed=True))
r=fetch('http://nearshop.umeshop.ai/ops-shop/');assert r['status']==301 and r['headers']['location']==new+'/ops-shop/'
results.append(dict(origin='http://nearshop.umeshop.ai',path='/ops-shop/',status=301,location=r['headers']['location'],passed=True))
Path('/tmp/nearshop-public-verification.json').write_text(json.dumps(results,ensure_ascii=False,indent=2)+'\n')
print('verified checks',len(results),'TLS verification enabled; both management entries and API origins, static equality, auth boundaries, H5 and HTTP redirects passed')
