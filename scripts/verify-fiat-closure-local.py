#!/usr/bin/env python3
"""Local fiat closure: registration → on-behalf store → independent review → chain → pay onboarding → activation.

Two real processes on this machine, no vendor or remote access:
  * ai-shop  : the packaged shop-app jar, profiles api,ops, pay deployment standalone, private schema (default ai_shop_fiat)
  * near-pay : the real PayApplication (profiles ops,api) via the test-classpath FiatClosureLocalLauncher, on a fresh
               disposable schema near_pay_fiat_<hex>; NEWEBPAY onboarding uses the existing stub gateway.

Every business step is a real HTTP call with real sessions:
  owner email-OTP registration (stub mail, code read from the host log) · operator A on-behalf application ·
  operator B approval (atomic entity/store/owner/FREE plan) · owner management space + brand + store brand change ·
  operator A pay-service apply (ai-shop → near-pay source link) · near-pay profile approval and source verify ·
  NEWEBPAY onboarding submit/review/dispatch/refresh · channel account verify, product open, credential, activate ·
  store binding · readback from both near-pay and ai-shop.
Three operators exist because near-pay's sensitive approvals (credential enable) need two reviewers distinct from the maker.
Fixture rows are limited to: operator staff/roles, store industry, market TW, and the near-pay catalog admission
(see FiatClosureLocalLauncher). Nothing is written to a business table to skip a step.

Usage:
  python3 scripts/verify-fiat-closure-local.py --pay-classpath <file> [--host-db ai_shop_fiat] [--keep]
Prerequisites: local MySQL 9 on 127.0.0.1:3306 (root without password, local only), host schema built by the
rebuild tool, shop-app jar packaged, near-pay pay-svc test-compiled with its classpath written to --pay-classpath.
Credentials and tokens are generated per run and never printed; the evidence JSON contains only business numbers.
"""
import argparse
import base64
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
JAVA = Path(subprocess.run(['/usr/libexec/java_home', '-v', '21'], capture_output=True, text=True).stdout.strip()) / 'bin/java'
MYSQL = Path.home() / '.local/opt/mysql/bin/mysql'
HOST_JAR = ROOT / 'backend/shop-app/target/shop-app-0.1.0-SNAPSHOT.jar'
OPERATORS = ('fiat-maker', 'fiat-reviewer', 'fiat-second')


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


class Step(Exception):
    pass


class Runner:
    def __init__(self, args):
        self.args = args
        self.run_id = uuid.uuid4().hex[:12]
        self.out = Path(tempfile.mkdtemp(prefix='fiat-closure-'))
        self.out.chmod(0o700)
        self.secrets = []
        self.processes = {}
        self.evidence = {'runId': self.run_id, 'status': 'FAIL', 'steps': [], 'remoteAccess': False,
                         'vendorAccess': False, 'paySchema': None, 'hostSchema': args.host_db}

    # ---------- infrastructure ----------
    def secret(self, value):
        self.secrets.append(value)
        return value

    def redact(self, text):
        for value in self.secrets:
            if value:
                text = text.replace(value, '***')
        return text

    def mysql(self, sql, schema=None):
        cmd = [str(MYSQL), '-uroot', '-h127.0.0.1', '-P3306', '--batch', '--skip-column-names']
        if schema:
            cmd.append(schema)
        r = subprocess.run(cmd, input=sql, text=True, capture_output=True)
        if r.returncode:
            raise Step('mysql failed: ' + self.redact(r.stderr.strip())[:400])
        return r.stdout.strip()

    def http(self, origin, method, path, body=None, token=None, headers=None, expect_ok=True):
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(origin + path, data=data, method=method)
        req.add_header('Content-Type', 'application/json')
        req.add_header('Accept', 'application/json')
        if token:
            req.add_header('Authorization', 'Bearer ' + token)
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                status, raw = resp.status, resp.read().decode()
        except urllib.error.HTTPError as e:
            status, raw = e.code, e.read().decode()
        try:
            payload = json.loads(raw) if raw else {}
        except ValueError:
            payload = {'raw': raw[:300]}
        if expect_ok and (status >= 400 or (isinstance(payload, dict) and payload.get('code') not in (0, None))):
            raise Step(f'{method} {path} -> HTTP {status} {self.redact(json.dumps(payload, ensure_ascii=False))[:600]}')
        return payload

    def data(self, payload):
        return payload.get('data') if isinstance(payload, dict) and 'data' in payload else payload

    def record(self, name, **facts):
        entry = {'step': name, **facts}
        self.evidence['steps'].append(entry)
        print('✓', name, json.dumps(facts, ensure_ascii=False))

    def wait_ready(self, name, url_path, origin, log, timeout=420):
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self.processes[name].poll() is not None:
                raise Step(f'{name} exited early; see {log}')
            try:
                with urllib.request.urlopen(origin + url_path, timeout=3) as resp:
                    if resp.status < 500:
                        return
            except urllib.error.HTTPError as e:
                if e.code < 500:
                    return
            except OSError:
                pass
            time.sleep(2)
        raise Step(f'{name} not ready in {timeout}s; see {log}')

    def start(self, name, command, env):
        log = self.out / (name + '.log')
        stream = log.open('w')
        log.chmod(0o600)
        self.processes[name] = subprocess.Popen(command, cwd=str(ROOT), env=env, stdout=stream, stderr=subprocess.STDOUT)
        return log

    def stop(self):
        for p in self.processes.values():
            if p.poll() is None:
                p.terminate()
        for p in self.processes.values():
            try:
                p.wait(timeout=30)
            except subprocess.TimeoutExpired:
                p.kill()

    # ---------- fixtures ----------
    def seed_host(self):
        db = self.args.host_db
        if not re.fullmatch(r'ai_shop_fiat[a-z0-9_]*', db):
            raise Step('host schema must be a private ai_shop_fiat* schema')
        self.mysql("UPDATE sys_market SET enabled=1 WHERE market='TW';", db)
        if self.mysql("SELECT COUNT(*) FROM sys_market WHERE market='TW' AND enabled=1;", db) != '1':
            raise Step('sys_market TW missing in host schema')
        self.mysql(
            "INSERT INTO sys_store_industry(industry_code,sub_industry_code,business_type_code,name_zh,name_tw,enabled,created_at,updated_at) "
            "SELECT 'FIAT_RETAIL','','RETAIL','零售','零售',1,NOW(),NOW() WHERE NOT EXISTS(SELECT 1 FROM sys_store_industry WHERE industry_code='FIAT_RETAIL' AND sub_industry_code='');"
            "INSERT INTO sys_store_industry(industry_code,sub_industry_code,business_type_code,name_zh,name_tw,enabled,created_at,updated_at) "
            "SELECT 'FIAT_RETAIL','SHOP','RETAIL','门店','門店',1,NOW(),NOW() WHERE NOT EXISTS(SELECT 1 FROM sys_store_industry WHERE industry_code='FIAT_RETAIL' AND sub_industry_code='SHOP');", db)
        self.op_password = self.secret(secrets.token_urlsafe(18))
        hashed = subprocess.run(['htpasswd', '-nbBC', '10', 'x', self.op_password], capture_output=True, text=True).stdout.strip().split(':', 1)[1]
        for op in OPERATORS:
            self.mysql(
                f"DELETE FROM sys_ops_staff WHERE staff_no='{op}';"
                f"INSERT INTO sys_ops_staff(staff_no,username,password,real_name,roles,status,created_at,updated_at) "
                f"VALUES('{op}','{op}@local.test','{hashed}','{op}','[\"SUPER_ADMIN\"]','ACTIVE',NOW(),NOW());", db)

    # ---------- flow ----------
    def run(self):
        a = self.args
        token, credential = self.secret(secrets.token_urlsafe(36)), self.secret(secrets.token_urlsafe(36))
        master = self.secret(base64.b64encode(secrets.token_bytes(32)).decode())
        pay_port, host_port = free_port(), free_port()
        pay_origin, host = f'http://127.0.0.1:{pay_port}', f'http://127.0.0.1:{host_port}'
        pay_schema = 'near_pay_fiat_' + self.run_id
        self.evidence['paySchema'] = pay_schema
        self.mysql(f'CREATE DATABASE {pay_schema} DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;')
        self.created_schema = pay_schema
        self.seed_host()

        env = os.environ.copy()
        env.update({'JAVA_TOOL_OPTIONS': '-Duser.timezone=UTC',
                    'CLOSURE_PAY_JDBC_URL': f'jdbc:mysql://127.0.0.1:3306/{pay_schema}?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC',
                    'CLOSURE_MYSQL_USER': 'root', 'CLOSURE_MYSQL_PASSWORD': '',
                    'CLOSURE_PAY_REPO_ROOT': str(a.near_pay_root.resolve()), 'CLOSURE_PAY_PORT': str(pay_port),
                    'CLOSURE_PAY_READY_FILE': str(self.out / 'pay-ready.json'),
                    'CLOSURE_INTERNAL_TOKEN': token, 'CLOSURE_PAY_INTERNAL_CREDENTIAL': credential,
                    'CLOSURE_MASTER_KEY': master, 'CLOSURE_HOST_ORIGIN': host})
        cp = a.pay_classpath.read_text().strip()
        pay_log = self.start('pay', [str(JAVA), '-Xmx1500m', '-Dnearpay.fiat.closure.local.acceptance=true', '-cp', cp,
                                     'ai.neargo.shop.pay.svc.FiatClosureLocalLauncher'], env)
        host_args = [
            f'--server.port={host_port}', '--server.address=127.0.0.1', '--spring.profiles.active=api,ops',
            f'--spring.datasource.url=jdbc:mysql://127.0.0.1:3306/{a.host_db}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8',
            '--shop.pay.deployment=standalone', f'--shop.services.targets.PAY={pay_origin}',
            f'--shop.services.internal-token={token}', '--shop.pay.internal-caller=AI_SHOP_UME',
            f'--shop.pay.internal-caller-credentials.AI_SHOP_UME={credential}',
            '--shop.nearpay.store-sync.enabled=true', f'--shop.nearpay.store-sync.base-url={pay_origin}',
            f'--shop.nearpay.store-sync.credential={credential}', '--shop.nearpay.store-sync.scan-interval=PT2S',
            '--shop.mail.otp-stub-allowed=true', '--logging.level.ai.neargo.shop.user.service.impl.EmailOtpServiceImpl=DEBUG',
            '--shop.otp.rate-limit=false', f'--shop.session.ehcache.dir={self.out}/sessions',
        ] + a.host_arg
        host_env = env.copy()
        host_env.update({'SHOP_DB_USER': 'shop', 'SHOP_DB_PASS': 'shop', 'SHOP_OPS_SECOND_FACTOR': 'off',
                         'SHOP_DEFAULT_MARKET': 'TW', 'SHOP_REGISTRATION_MARKETS': 'TW'})
        host_log = self.start('host', [str(JAVA), '-Xmx2g', '-jar', str(HOST_JAR)] + host_args, host_env)
        self.wait_ready('host', '/ops/auth/me', host, host_log)
        self.record('host-started', origin='loopback', schema=a.host_db)
        self.wait_ready('pay', '/pay-ops/v2/merchants', pay_origin, pay_log)
        self.record('pay-started', origin='loopback', schema=pay_schema)

        # operators
        ops = {}
        for op in OPERATORS:
            r = self.data(self.http(host, 'POST', '/ops/auth/login', {'username': op + '@local.test', 'password': self.op_password}))
            ops[op] = self.secret(r['token'])
        A, B, C = (ops[o] for o in OPERATORS)
        self.record('operators-logged-in', operators=list(OPERATORS))

        # 1 owner registration via email OTP
        email = f'owner-{self.run_id}@fiat.local.test'
        self.http(host, 'POST', '/biz/auth/email-otp/send', {'email': email, 'locale': 'zh-Hant'})
        code = None
        for _ in range(20):
            m = re.findall(r'\[mail-stub\] otp to \S+ = (\d{6})', host_log.read_text(errors='ignore'))
            if m:
                code = m[-1]
                break
            time.sleep(0.5)
        if not code:
            raise Step('email OTP not observed in host stub log')
        login = self.data(self.http(host, 'POST', '/biz/auth/login', {'grantType': 'EMAIL_OTP', 'principal': email,
                                                                        'credential': code, 'agreed': True, 'marketCode': 'TW'}))
        owner_token = self.secret(login['token'])
        self.record('owner-registered', userNo=(login.get('user') or {}).get('userNo') or login.get('userNo'))

        # 2 on-behalf application (operator A)
        apply = self.data(self.http(host, 'POST', '/ops/merchants/apply-on-behalf', {
            'name': f'Fiat closure {self.run_id}', 'subject': 'NATURAL_PERSON', 'market': 'TW', 'ownerEmail': email,
            'phone': '0912 345 678', 'contactName': 'Local owner', 'serviceScope': 'PLATFORM', 'communityNos': [],
            'qualifications': [], 'qualificationItems': [], 'asPickupPoint': False,
            'storeProfile': {'name': f'閉環門店 {self.run_id[:6]}', 'address': 'Taipei local test-only address',
                             'industryCode': 'FIAT_RETAIL', 'subIndustryCode': 'SHOP', 'businessTypeCode': 'RETAIL'}}, A))
        apply_no = apply['applyNo']
        self.record('on-behalf-submitted', applyNo=apply_no, ownerUserNo=apply['ownerUserNo'])

        # 3 independent review (A cannot self-review; B approves)
        self_review = self.http(host, 'POST', f'/ops/merchant/apply/{apply_no}/audit',
                                {'approved': True, 'serviceScope': 'PLATFORM', 'communityNos': [], 'grantCodes': []}, A, expect_ok=False)
        if self_review.get('code') in (0, None):
            raise Step('maker was able to approve own application')
        approved = self.data(self.http(host, 'POST', f'/ops/merchant/apply/{apply_no}/audit',
                                       {'approved': True, 'serviceScope': 'PLATFORM', 'communityNos': [], 'grantCodes': []}, B))
        entity_no, store_no = approved['merchantNo'], approved['storeNo']
        db = a.host_db
        facts = self.mysql(f"SELECT e.status,e.market,s.commerce_model,(SELECT COUNT(*) FROM mch_subscription_period p WHERE p.store_no=s.store_no AND p.source_type='FREE'),"
                           f"(SELECT COUNT(*) FROM mch_account m WHERE m.entity_no=e.entity_no AND m.is_owner=1) FROM mch_entity e JOIN mch_store s ON s.entity_no=e.entity_no "
                           f"WHERE e.entity_no='{entity_no}' AND s.store_no='{store_no}';", db).split('\t')
        if facts[:3] != ['ACTIVE', 'TW', 'STORE_V1'] or facts[3] != '1' or facts[4] != '1':
            raise Step('approval did not create ACTIVE TW entity, STORE_V1 store, FREE period and owner: ' + str(facts))
        self.record('store-created', applyNo=apply_no, entityNo=entity_no, storeNo=store_no, selfReviewRejectedCode=self_review.get('code'),
                    entity='ACTIVE/TW', store='STORE_V1', freePeriods=1, owners=1)

        # 4 chain: owner logs in again to see the new merchant account, creates management space, brand, moves store
        self.http(host, 'POST', '/biz/auth/email-otp/send', {'email': email, 'locale': 'zh-Hant'})
        time.sleep(0.5)
        code = re.findall(r'\[mail-stub\] otp to \S+ = (\d{6})', host_log.read_text(errors='ignore'))[-1]
        login = self.data(self.http(host, 'POST', '/biz/auth/login', {'grantType': 'EMAIL_OTP', 'principal': email,
                                                                        'credential': code, 'agreed': True, 'marketCode': 'TW'}))
        owner_token = self.secret(login['token'])
        context = self.data(self.http(host, 'GET', '/biz/management/context', token=owner_token))
        account = self.mysql(f"SELECT mch_account_no FROM mch_account WHERE entity_no='{entity_no}' AND is_owner=1;", db)
        space = self.data(self.http(host, 'POST', '/biz/management/spaces', {'name': f'連鎖 {self.run_id[:6]}', 'sourceAccountNo': account},
                                    owner_token, {'Idempotency-Key': 'space-' + self.run_id}))
        mgmt = space['managementNo']
        brand = self.data(self.http(host, 'POST', '/biz/management/brands', {'managementNo': mgmt, 'name': f'品牌 {self.run_id[:6]}', 'status': 'ACTIVE'},
                                    owner_token, {'Idempotency-Key': 'brand-' + self.run_id}))
        brand_no = brand['brandNo']
        check = self.data(self.http(host, 'POST', f'/biz/stores/{store_no}/brand-changes/check', {'managementNo': mgmt, 'toBrandNo': brand_no}, owner_token))
        submit = self.data(self.http(host, 'POST', f'/biz/stores/{store_no}/brand-changes', {
            'managementNo': mgmt, 'toBrandNo': brand_no, 'checkToken': check['checkToken'], 'expectedStoreRevision': check['storeVersion'],
            'expectedScopeRevision': 0, 'expectedFromBrandRevision': None, 'expectedToBrandRevision': 0, 'reason': 'local closure'},
            owner_token, {'Idempotency-Key': 'change-' + self.run_id}))
        change_no = submit['changeNo']
        decided = self.data(self.http(host, 'POST', f'/biz/management/brand-changes/{change_no}/decisions',
                                      {'decision': 'APPROVE', 'expectedRevision': 0, 'reason': 'local closure'},
                                      owner_token, {'Idempotency-Key': 'decide-' + self.run_id}))
        binding = self.mysql(f"SELECT status FROM mch_brand_store_binding WHERE store_no='{store_no}' AND brand_no='{brand_no}' ORDER BY id DESC LIMIT 1;", db)
        if binding != 'ACTIVE':
            raise Step('store was not bound to the brand: ' + binding)
        self.record('chain-joined', managementNo=mgmt, brandNo=brand_no, changeNo=change_no, decision=decided.get('status'), binding=binding,
                    contextKeys=sorted(context.keys()) if isinstance(context, dict) else None)

        # 5 pay-service apply (operator A, ai-shop -> near-pay source link)
        request_no = 'FIAT' + self.run_id
        applied = self.data(self.http(host, 'POST', f'/ops/merchant/{entity_no}/pay-service/apply', {
            'legalName': f'Fiat closure {self.run_id}', 'registrationNo': '9' + self.run_id[:7].translate(str.maketrans('abcdef', '123456')),
            'representativeName': 'Local owner', 'contactPhone': '0912345678', 'registeredAddress': 'Taipei local test-only address',
            'requestNo': request_no}, A))
        self.record('pay-service-applied', requestNo=request_no, response=applied)
        merchant_no = None
        for _ in range(60):
            status = self.data(self.http(host, 'GET', f'/ops/merchant/{entity_no}/pay-service', token=A))
            merchant_no = (status or {}).get('merchantNo') or (status or {}).get('merchantRef')
            if merchant_no:
                break
            time.sleep(1)
        listing = self.data(self.http(pay_origin, 'GET', '/pay-ops/v2/merchants?profileStatus=SUBMITTED', token=A))
        if not merchant_no:
            merchant_no = listing['records'][0]['merchantNo'] if listing.get('records') else None
        if not merchant_no:
            raise Step('near-pay merchant not found after apply')
        self.record('near-pay-merchant', merchantNo=merchant_no, hostStatus=status)
        self.pay_flow(pay_origin, host, A, B, C, merchant_no, store_no, entity_no)
        self.evidence['status'] = 'PASS'

    # ---------- near-pay ----------
    def rn(self, tag):
        return f'{tag}-{self.run_id}-{secrets.token_hex(3)}'[:64]

    def approve(self, pay, A, B, subject_type, subject_ref, revision, action, reviewers):
        sub = self.data(self.http(pay, 'POST', '/pay-ops/v2/approvals', {'requestNo': self.rn('ap'), 'subjectType': subject_type,
                                  'subjectRef': subject_ref, 'subjectRevision': revision, 'action': action, 'reason': 'local closure'}, A))
        no, version, material = sub['approvalNo'], sub['version'], sub['materialVersion']
        for reviewer in reviewers:
            r = self.data(self.http(pay, 'POST', f'/pay-ops/v2/approvals/{no}/review', {'requestNo': self.rn('rv'), 'expectedVersion': version,
                                    'decision': 'APPROVE', 'reason': 'local closure', 'materialVersion': material}, reviewer))
            version = r['version']
        ex = self.data(self.http(pay, 'POST', f'/pay-ops/v2/approvals/{no}/execute', {'requestNo': self.rn('ex'), 'expectedVersion': version,
                                 'reason': 'local closure'}, reviewers[0]))
        return no, ex

    def pay_flow(self, pay, host, A, B, C, merchant_no, store_no, entity_no):
        get = lambda path, t=A: self.data(self.http(pay, 'GET', path, token=t))
        post = lambda path, body, t=A: self.data(self.http(pay, 'POST', path, body, t))
        detail = get(f'/pay-ops/v2/merchants/{merchant_no}')['record']
        for link in detail.get('sourceLinks', []):
            if link.get('verifyStatus') != 'VERIFIED':
                post(f"/pay-ops/v2/merchants/{merchant_no}/source-links/{link['linkNo']}/verify",
                     {'requestNo': self.rn('lv'), 'expectedVersion': link['version'], 'result': 'VERIFIED', 'reason': 'local closure'}, B)
        profile = next(p for p in detail['profiles'] if p['reviewStatus'] == 'SUBMITTED')
        approval, _ = self.approve(pay, A, B, 'MERCHANT_PROFILE_APPROVE', profile['revisionNo'], profile['version'] + 1, 'APPROVE', [B])
        detail = get(f'/pay-ops/v2/merchants/{merchant_no}')['record']
        self.record('profile-approved', revisionNo=profile['revisionNo'], approvalNo=approval,
                    links=[l['verifyStatus'] for l in detail.get('sourceLinks', [])],
                    profiles=[p['reviewStatus'] for p in detail['profiles']])

        opened = post('/pay-ops/v2/onboardings', {'requestNo': self.rn('ob'), 'merchantNo': merchant_no, 'payChannel': 'NEWEBPAY'})
        onboarding = opened['record']['onboarding']['onboardingNo']
        o = lambda: get(f'/pay-ops/v2/onboardings/{onboarding}')
        cur = o()
        post(f'/pay-ops/v2/onboardings/{onboarding}/submit', {'requestNo': self.rn('os'), 'expectedVersion': cur['version'], 'materialVersion': 1,
             'materials': {'legalForm': 'COMPANY', 'contactName': 'Local owner', 'contactPhone': 'cipher:phone/local-test',
                           'licenses': ['file:license/local-test.pdf'], 'settleAccountType': 'CORPORATE_BANK',
                           'settleAccount': 'cipher:bank/local-test'}})
        cur = o()
        approval, _ = self.approve(pay, A, B, 'ONBOARDING_REVIEW', onboarding, cur['version'] + 1, 'APPROVE', [B])
        cur = o()
        post(f'/pay-ops/v2/onboardings/{onboarding}/dispatch', {'requestNo': self.rn('od'), 'expectedVersion': cur['version'],
             'channelApplyNo': None, 'reason': 'local closure'})
        statuses = []
        for _ in range(6):
            cur = o()
            post(f'/pay-ops/v2/onboardings/{onboarding}/refresh', {'requestNo': self.rn('or'), 'expectedVersion': cur['version'], 'reason': 'local closure'})
            ob = o()['record']['onboarding']
            statuses.append(ob['status'])
            if ob['status'] == 'APPROVED' and ob.get('candidateStatus') == 'ACCEPTED' and ob.get('payMerchantNo'):
                break
        pm = ob.get('payMerchantNo')
        if not pm:
            raise Step('onboarding did not produce an accepted channel account: ' + json.dumps(ob, ensure_ascii=False)[:400])
        self.record('onboarding-approved', onboardingNo=onboarding, reviewApprovalNo=approval, refreshStatuses=statuses, payMerchantNo=pm)

        acct = lambda: get(f'/pay-ops/v2/channel-accounts/{pm}')
        a = acct()
        self.approve(pay, A, B, 'CHANNEL_ACCOUNT_VERIFY', pm, a['record']['account']['version'] + 1, 'VERIFY', [B])
        a = acct()
        opened = post(f'/pay-ops/v2/channel-accounts/{pm}/products/NEWEBPAY_TWQR/open', {'requestNo': self.rn('po'), 'expectedVersion': a['version'],
                      'source': 'OPS_CONFIRMED', 'evidenceRef': 'local-closure-evidence', 'reason': 'local closure'})
        key = post('/pay-ops/v2/credentials', {'credentialType': 'NEWEBPAY_MERCHANT_HASH', 'ownerRef': pm, 'reason': 'local closure'})
        key_id = key['keyId']
        hash_key, hash_iv = self.secret(secrets.token_hex(16)), self.secret(secrets.token_hex(8))
        version = post(f'/pay-ops/v2/credentials/{key_id}/versions', {'hashKey': hash_key, 'hashIv': hash_iv, 'reason': 'local closure'})['versionIndex']
        post(f'/pay-ops/v2/credentials/{key_id}/versions/{version}/verify', {'evidenceRef': 'local-closure', 'reason': 'local closure'}, B)
        self.approve(pay, A, B, 'CREDENTIAL_ENABLE', f'{key_id}:{version}', version, 'ENABLE', [B, C])
        a = acct()
        self.http(pay, 'PUT', f'/pay-ops/v2/channel-accounts/{pm}/credential', {'requestNo': self.rn('cb'), 'expectedVersion': a['version'],
                  'keyId': key_id, 'reason': 'local closure'}, A)
        a = acct()
        post(f'/pay-ops/v2/channel-accounts/{pm}/activate', {'requestNo': self.rn('ac'), 'expectedVersion': a['version'], 'reason': 'local closure'})
        account = acct()['record']['account']
        if account['status'] != 'ACTIVE' or account['verificationStatus'] != 'VERIFIED':
            raise Step('channel account not collectable: ' + json.dumps(account, ensure_ascii=False)[:300])
        self.record('channel-account-active', payMerchantNo=pm, status=account['status'], verification=account['verificationStatus'],
                    credentialKeyId=key_id, credentialVersion=version, product='NEWEBPAY_TWQR')

        stores = []
        for _ in range(60):
            stores = get(f'/pay-ops/v2/stores?merchantNo={merchant_no}').get('records', [])
            if any(s['storeNo'] == store_no and s.get('sourceStatus') == 'ACTIVE' for s in stores):
                break
            time.sleep(1)
        bound = post(f'/pay-ops/v2/stores/{store_no}/bindings', {'requestNo': self.rn('sb'), 'payChannel': 'NEWEBPAY', 'payMerchantNo': pm,
                     'reason': 'local closure'})
        policy = get(f'/pay-ops/v2/stores/{store_no}/collection-policy')
        twqr = next((p for p in policy.get('preview', []) if p.get('productCode') == 'NEWEBPAY_TWQR'), None)
        bindings = get(f'/pay-ops/v2/stores/{store_no}/bindings')
        progress = get(f'/pay-ops/v2/merchants/{merchant_no}/capability-progress')
        host_status = self.data(self.http(host, 'GET', f'/ops/merchant/{entity_no}/pay-service', token=A))
        if not twqr or not twqr.get('available'):
            raise Step('NEWEBPAY_TWQR not available for store: ' + json.dumps(twqr, ensure_ascii=False))
        self.record('store-collectable', storeNo=store_no, binding=(bound.get('record') or bound).get('status'),
                    bindings=[b.get('status') for b in (bindings.get('records') or bindings if isinstance(bindings, list) else bindings.get('records', []))],
                    twqrAvailable=twqr.get('available'), capability=[(i.get('capability'), i.get('status')) for i in progress.get('items', [])],
                    hostPayService=host_status)

    def finish(self):
        self.stop()
        if not self.args.keep and getattr(self, 'created_schema', None):
            try:
                self.mysql(f'DROP DATABASE IF EXISTS {self.created_schema};')
            except Step:
                pass
        target = Path(self.args.evidence) if self.args.evidence else self.out / 'evidence.json'
        target.write_text(json.dumps(self.evidence, ensure_ascii=False, indent=1))
        print(self.evidence['status'], 'evidence:', target, 'logs:', self.out)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--near-pay-root', type=Path, default=ROOT.parent / 'near-pay-fiat')
    p.add_argument('--pay-classpath', type=Path, required=True)
    p.add_argument('--host-db', default='ai_shop_fiat')
    p.add_argument('--host-arg', action='append', default=[])
    p.add_argument('--evidence')
    p.add_argument('--keep', action='store_true', help='keep the near-pay schema for inspection')
    args = p.parse_args()
    r = Runner(args)
    try:
        r.run()
    except Step as e:
        r.evidence['error'] = str(e)
        print('✗', e, file=sys.stderr)
    finally:
        r.finish()
    sys.exit(0 if r.evidence['status'] == 'PASS' else 1)


if __name__ == '__main__':
    main()
