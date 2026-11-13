"""Build one atomic UME seed data import; source snapshots and hashes stay private."""
import json,re,csv,argparse
from pathlib import Path
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('private_dir',type=Path,help='Private directory containing stores.jsonl and password-hashes.txt; SQL and mapping stay here')
W=parser.parse_args().private_dir;BATCH='UME_SEED_20261113'
def sql(v):
 if v is None:return 'NULL'
 if isinstance(v,int):return str(v)
 return "CONVERT(0x"+str(v).encode().hex()+" USING utf8mb4) COLLATE utf8mb4_unicode_520_ci"
def values(rows):return ',\n'.join('('+','.join(map(sql,r))+')' for r in rows)
def check(q,label):return "INSERT INTO seed_assert(ok) SELECT IF(("+q+"),1,0); -- "+label+'\n'
source=[json.loads(l) for l in (W/'stores.jsonl').read_text().splitlines()];source.sort(key=lambda s:(s['merchantId'],s['storeId']))
merchants={}
for s in source:
 assert s['payload']['store_status']=='1'
 assert re.fullmatch(r'[A-Za-z0-9_-]+',s['merchantId']) and re.fullmatch(r'[A-Za-z0-9_-]+',s['storeId'])
 m=merchants.setdefault(s['merchantId'],s)
 assert m['email'].strip().lower()==s['email'].strip().lower()
assert len(merchants)==741 and len(source)==815
hashes=(W/'password-hashes.txt').read_text().splitlines();assert len(hashes)==len(set(hashes))==741
users=[];entities=[];stores=[];mapping=[];default=set()
for (mid,s),pw in zip(merchants.items(),hashes):
 email=s['email'].strip().lower();a=[a for a in s['payload']['merchant_accounts'] if a['email'].strip().lower()==email]
 assert len(a)==1 and str(a[0]['status'])=='1' and '@' in email and len(email)<=191
 a=a[0];uid='UUME'+a['account_id'];eid='MUME'+mid;aid='MAUME'+mid;name=s['payload']['merchant_name'];assert len(uid)<=32 and len(name)<=64
 mobile=str(a.get('mobile') or '').strip();area=str(a.get('mobile_area') or '').strip()
 if mobile.lower()=='null':mobile=''
 # Contact only: no PHONE identity, OTP send or verified_at is created.
 phone=((area if area.startswith('+') else '+'+area)+mobile if area and mobile else mobile) or None
 users.append((uid,email,name,pw))
 entities.append((eid,name,uid,aid,phone,name,mid))
for s in source:
 mid=s['merchantId'];eid='MUME'+mid;sid='STUME'+s['storeId'];is_default=int(mid not in default);default.add(mid)
 stores.append((sid,eid,s['name'],is_default,'UME'+s['storeId'],mid,s['storeId'],s['storeNo']))
 owner=next(u for u in users if u[0]==next(e[2] for e in entities if e[0]==eid))
 mapping.append((mid,s['storeId'],s['storeNo'],s['payload']['merchant_name'],s['name'],eid,sid,owner[0],owner[1]))
s='''SET NAMES utf8mb4 COLLATE utf8mb4_unicode_520_ci;
USE ai_shop_ume;
CREATE TEMPORARY TABLE seed_assert(ok TINYINT NOT NULL CHECK(ok=1));
CREATE TEMPORARY TABLE seed_user(user_no VARCHAR(32) PRIMARY KEY,email VARCHAR(191) UNIQUE,nickname VARCHAR(64),password_hash VARCHAR(191)) COLLATE utf8mb4_unicode_520_ci;
CREATE TEMPORARY TABLE seed_entity(entity_no VARCHAR(64) PRIMARY KEY,name VARCHAR(128),user_no VARCHAR(32),account_no VARCHAR(64),phone VARCHAR(32),display_name VARCHAR(32),source_merchant VARCHAR(60)) COLLATE utf8mb4_unicode_520_ci;
CREATE TEMPORARY TABLE seed_store(store_no VARCHAR(64) PRIMARY KEY,entity_no VARCHAR(64),name VARCHAR(128),is_default TINYINT,store_code VARCHAR(32),source_merchant VARCHAR(60),source_store VARCHAR(60),source_no VARCHAR(100)) COLLATE utf8mb4_unicode_520_ci;
'''
for table,rows in [('seed_user',users),('seed_entity',entities),('seed_store',stores)]:s+='INSERT INTO '+table+' VALUES '+values(rows)+';\n'
s+='START TRANSACTION;\n'
s+=check("SELECT COUNT(*)=815 FROM seed_store p JOIN stg_ume_active_stores_20261113 t ON t.merchant_id=p.source_merchant AND t.store_id=p.source_store AND BINARY t.store_name=BINARY p.name",'source stores unchanged')
for table,key,temp,tmpkey in [('usr_account','user_no','seed_user','user_no'),('mch_entity','entity_no','seed_entity','entity_no'),('mch_account','mch_account_no','seed_entity','account_no'),('mch_store','store_no','seed_store','store_no')]:
 s+=check(f"SELECT COUNT(*)=0 FROM {table} a JOIN {temp} p ON a.{key}=p.{tmpkey} WHERE a.created_by<>{sql(BATCH)} OR a.created_by IS NULL OR a.deleted<>0 OR a.tenant_no<>'MAIN'",'no foreign '+table+' collisions')
s+=check("SELECT COUNT(*)=0 FROM usr_identity i JOIN seed_user p ON i.identity_type='EMAIL' AND i.identity_value=p.email WHERE i.user_no<>p.user_no OR i.deleted<>0 OR i.tenant_no<>'MAIN'",'email ownership')
base="tenant_no,created_at,created_by,updated_at,updated_by"
meta="'MAIN',NOW(),"+sql(BATCH)+",NOW(),"+sql(BATCH)
s+=f"INSERT INTO usr_account(user_no,nickname,avatar,status,market,{base}) SELECT p.user_no,p.nickname,'','NORMAL','TW',{meta} FROM seed_user p WHERE NOT EXISTS(SELECT 1 FROM usr_account a WHERE a.user_no=p.user_no);\n"
for typ,field in [('EMAIL','email'),('PASSWORD','password_hash')]:
 s+=f"INSERT INTO usr_identity(user_no,identity_type,identity_value,channel,{base}) SELECT p.user_no,'{typ}',p.{field},'APP',{meta} FROM seed_user p WHERE NOT EXISTS(SELECT 1 FROM usr_identity i WHERE i.user_no=p.user_no AND i.identity_type='{typ}');\n"
s+=f"INSERT INTO mch_entity(entity_no,name,owner_user_no,status,market,funds_mode,biz_qualification,self_operated,{base}) SELECT p.entity_no,p.name,p.user_no,'PENDING_LICENSE','TW','DIRECT','UNREGISTERED',0,{meta} FROM seed_entity p WHERE NOT EXISTS(SELECT 1 FROM mch_entity a WHERE a.entity_no=p.entity_no);\n"
s+=f"INSERT INTO mch_account(mch_account_no,entity_no,user_no,is_owner,is_primary,status,login_phone,display_name,{base}) SELECT p.account_no,p.entity_no,p.user_no,1,1,'ACTIVE',p.phone,p.display_name,{meta} FROM seed_entity p WHERE NOT EXISTS(SELECT 1 FROM mch_account a WHERE a.mch_account_no=p.account_no);\n"
s+=f"INSERT INTO mch_store(store_no,entity_no,name,is_default,status,business_mode,commerce_model,store_code,{base}) SELECT p.store_no,p.entity_no,p.name,p.is_default,'ACTIVE','SELF_OPERATED','LEGACY',p.store_code,{meta} FROM seed_store p WHERE NOT EXISTS(SELECT 1 FROM mch_store a WHERE a.store_no=p.store_no);\n"
s+=check("SELECT COUNT(*)=741 FROM seed_user p JOIN usr_account a ON a.user_no=p.user_no AND a.status='NORMAL' AND a.market='TW' AND a.deleted=0 JOIN usr_identity i ON i.user_no=p.user_no AND i.identity_type='EMAIL' AND BINARY i.identity_value=BINARY p.email AND i.deleted=0 JOIN usr_identity pw ON pw.user_no=p.user_no AND pw.identity_type='PASSWORD' AND BINARY pw.identity_value=BINARY p.password_hash AND pw.deleted=0",'all email/password users')
s+=check("SELECT COUNT(*)=741 FROM seed_entity p JOIN mch_entity e ON e.entity_no=p.entity_no AND e.owner_user_no=p.user_no AND BINARY e.name=BINARY p.name JOIN mch_account a ON a.mch_account_no=p.account_no AND a.entity_no=p.entity_no AND a.user_no=p.user_no AND a.is_owner=1 AND a.is_primary=1 AND a.status='ACTIVE' AND a.deleted=0",'all primary owner memberships')
s+=check("SELECT COUNT(*)=815 FROM seed_store p JOIN mch_store s ON s.store_no=p.store_no AND s.entity_no=p.entity_no AND BINARY s.name=BINARY p.name AND s.is_default=p.is_default AND s.status='ACTIVE' AND s.commerce_model='LEGACY' AND s.pay_merchant_no IS NULL AND s.deleted=0",'all stores and exact ownership; no payments')
s+=check("SELECT COUNT(*)=741 FROM (SELECT p.entity_no FROM seed_entity p JOIN mch_store s ON s.entity_no=p.entity_no AND s.deleted=0 GROUP BY p.entity_no HAVING SUM(s.is_default)=1) d",'one default store per entity')
s+=check("SELECT COUNT(*)=0 FROM seed_user p JOIN usr_identity i ON i.user_no=p.user_no AND i.identity_type='PHONE'",'no phone login or verification')
s+="SELECT 'seed_users',COUNT(*) FROM usr_account WHERE created_by="+sql(BATCH)+";\nSELECT 'seed_entities',COUNT(*) FROM mch_entity WHERE created_by="+sql(BATCH)+";\nSELECT 'seed_stores',COUNT(*) FROM mch_store WHERE created_by="+sql(BATCH)+";\nSELECT 'seed_memberships',COUNT(*) FROM mch_account WHERE created_by="+sql(BATCH)+";\n"
for mode,ending in [('dry-run','ROLLBACK'),('apply','COMMIT')]:
 p=W/(mode+'.sql');p.touch(mode=0o600);p.write_text(s+ending+';\n')
p=W/'store-login-map.csv';p.touch(mode=0o600)
with p.open('w') as f:
 writer=csv.writer(f);writer.writerow(['source_merchant_id','source_store_id','source_store_no','merchant_name','store_name','entity_no','store_no','user_no','login_email']);writer.writerows(mapping)
(W/'plan.json').write_text(json.dumps({'batch':BATCH,'users':741,'entities':741,'stores':815,'memberships':741,'subaccountsImported':0,'source':'ai_shop_ume.stg_ume_first_batch_store_accounts_20261113','storeSource':'ai_shop_ume.stg_ume_active_stores_20261113','mode':'email-primary-only','market':'TW','commerceModel':'LEGACY','phoneIdentityCreated':False,'paymentEnabled':False},indent=2))
print('Prepared atomic dry-run and apply SQL, 741 primary emails / 815 stores; no child accounts.')
