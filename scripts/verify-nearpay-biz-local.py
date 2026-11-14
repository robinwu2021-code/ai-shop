#!/usr/bin/env python3
"""Loopback, two-JVM/MySQL9 onboarding acceptance. Never connects to a vendor or production DB."""

import argparse
import base64
import configparser
import hashlib
import io
import json
import os
import re
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
JAVA_HOME = Path("/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home")
MYSQL = Path("/opt/homebrew/opt/mysql@9.7/bin/mysql")


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def classpath(report):
    props = ET.parse(report).getroot().find("properties")
    return next(p.get("value") for p in props if p.get("name") == "java.class.path")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--near-pay-root", type=Path, default=ROOT.parent / "near-pay")
    parser.add_argument("--near-pay-repository", type=Path, required=True)
    parser.add_argument("--mysql-config", type=Path, default=Path.home() / ".config/mysql9/client.cnf")
    parser.add_argument("--app-root", type=Path, help="Also run the actual Flutter HTTP client against both JVMs")
    parser.add_argument("--skip-build", action="store_true", help="Use already compiled current test classpaths")
    args = parser.parse_args()
    output = Path(tempfile.mkdtemp(prefix="ai-shop-nearpay-local-"))
    output.chmod(0o700)
    env = os.environ.copy()
    env["JAVA_HOME"] = str(JAVA_HOME)
    env["MAVEN_OPTS"] = "-Xmx1200m -XX:ActiveProcessorCount=4"
    config = configparser.ConfigParser(interpolation=None)
    config.read(args.mysql_config)
    env["ALIGNMENT_MYSQL_USER"] = config["client"]["user"].strip("\"'")
    env["ALIGNMENT_MYSQL_PASSWORD"] = config["client"]["password"].strip("\"'")

    def mysql(sql, schema=None, expected_error=None):
        command = [str(MYSQL), "--defaults-file=" + str(args.mysql_config), "--protocol=TCP",
                   "-h127.0.0.1", "-P3308", "--batch", "--skip-column-names"]
        if schema:
            command.append(schema)
        result = subprocess.run(command, input=sql, text=True, capture_output=True)
        if expected_error is not None:
            if result.returncode and f"ERROR {expected_error} " in result.stderr:
                return ""
            raise AssertionError("Local MySQL expected constraint rejection did not occur")
        if result.returncode:
            raise RuntimeError("Local MySQL operation failed; no credentials or SQL echoed")
        return result.stdout.strip()

    def build(root, tests, log, repository=None):
        command = ["mvn", "-o", "-f", "backend/pom.xml", "-pl",
                   "pay/pay-svc" if repository else "shop-app", "-am", "test",
                   "-Dtest=" + tests, "-Dsurefire.failIfNoSpecifiedTests=false", "-Dsurefire.heap=1200m"]
        if repository:
            command.append("-Dmaven.repo.local=" + str(repository))
        print("Compiling", root.name, flush=True)
        with log.open("w") as stream:
            result = subprocess.run(command, cwd=root, env=env, stdout=stream, stderr=subprocess.STDOUT)
        if result.returncode:
            raise RuntimeError("Build failed; see " + str(log))

    if not args.skip_build:
        build(args.near_pay_root,
              "CheckoutCollectBizNoTest,CollectLinksByBizNoTest,PayBizInternalApiRoundTripTest,BizOnboardingHttpAcceptanceTest,MembershipTypedSubjectTest,MembershipSourceTest,BizSessionAuthenticatorTest,AccessDomainIntegrationTest",
              output / "near-pay-build.log", args.near_pay_repository)
        build(ROOT, "PayBizOnboardingHttpTest,PayBizRequestBodyTest", output / "ai-shop-build.log")

    version = mysql("SELECT VERSION();")
    if not version.startswith("9."):
        raise RuntimeError("MySQL 9 is required")
    suffix = uuid.uuid4().hex[:16]
    host_schema, pay_schema = "ai_shop_align_" + suffix, "near_pay_align_" + suffix
    host_port, pay_port = free_port(), free_port()
    while pay_port == host_port:
        pay_port = free_port()
    env.update({
        "ALIGNMENT_HOST_JDBC_URL": f"jdbc:mysql://127.0.0.1:3308/{host_schema}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
        "ALIGNMENT_PAY_JDBC_URL": f"jdbc:mysql://127.0.0.1:3308/{pay_schema}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
        "ALIGNMENT_HOST_ORIGIN": f"http://127.0.0.1:{host_port}",
        "ALIGNMENT_PAY_ORIGIN": f"http://127.0.0.1:{pay_port}",
        "ALIGNMENT_HOST_PORT": str(host_port), "ALIGNMENT_PAY_PORT": str(pay_port),
        "ALIGNMENT_HOST_READY_FILE": str(output / "host-ready.json"),
        "ALIGNMENT_PAY_READY_FILE": str(output / "pay-ready.json"),
        "ALIGNMENT_MASTER_KEY": base64.b64encode(secrets.token_bytes(32)).decode(),
    })
    processes, created, checks = [], [], []
    host = pay = None

    def start(name, cp, main_class, ready_file, root):
        agent = next((p for p in cp.split(os.pathsep)
                      if Path(p).name.startswith("mockito-core-") and p.endswith(".jar")), None)
        command = [str(JAVA_HOME / "bin/java"), "-Xmx900m", "-XX:ActiveProcessorCount=2",
                   "-Duser.timezone=Asia/Shanghai", "-Dnearpay.aishop.local.acceptance=true"]
        if agent:
            command.append("-javaagent:" + agent)
        command.extend(["-cp", cp, main_class])
        log = output / (name + ".log")
        with log.open("w") as stream:
            process = subprocess.Popen(command, cwd=root, env=env, stdout=stream, stderr=subprocess.STDOUT)
        processes.append(process)
        deadline = time.monotonic() + 120
        last_notice = time.monotonic()
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError(name + " stopped before readiness; see " + str(log))
            if ready_file.exists():
                try:
                    data = json.loads(ready_file.read_text())
                    print(name, "ready", flush=True)
                    return data
                except json.JSONDecodeError:
                    pass  # A producer may still be finishing its private readiness file.
            if time.monotonic() - last_notice > 20:
                print("Waiting for", name, flush=True)
                last_notice = time.monotonic()
            time.sleep(0.2)
        raise RuntimeError(name + " readiness timeout; see " + str(log))

    def call(method, path, data=None, token=None, merchant=None, expected=200,
             raw=None, content_type="application/json", origin=None, extra=None):
        headers = {"X-User-No": "forged", "X-Subject-Type": "OPS", "X-Tenant-No": "OTHER"}
        if token:
            headers["Authorization"] = "Bearer " + token
        if merchant:
            headers["X-Merchant-No"] = merchant
        if extra:
            headers.update(extra)
        body = raw if raw is not None else None if data is None else json.dumps(data, ensure_ascii=False).encode()
        if body is not None:
            headers["Content-Type"] = content_type
        request = urllib.request.Request((origin or host["hostOrigin"]) + path, data=body, headers=headers, method=method)
        try:
            response = urllib.request.urlopen(request, timeout=30)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            payload = response.read()
            status = response.status
            response_headers = response.headers
        if status != expected:
            raise AssertionError(f"{method} {path}: expected HTTP {expected}, got {status}")
        checks.append({"method": method, "path": path, "status": status,
                       "cacheControl": response_headers.get("Cache-Control"),
                       "contentTypeOptions": response_headers.get("X-Content-Type-Options")})
        if response_headers.get_content_type() == "application/json":
            value = json.loads(payload)
            if expected == 200 and value.get("code") != 0:
                raise AssertionError(f"{method} {path}: HTTP 200 with nonzero code")
            return value.get("data")
        return payload

    def command(draft, request=None):
        return {"requestNo": request or uuid.uuid4().hex, "expectedVersion": draft["expectedVersion"],
                "expectedDraftVersion": draft["draftVersion"]}

    def upload(path, draft, slot, image, expected=200):
        boundary = "OriginalAlignmentBoundary"
        parts = []
        for key, value in dict(command(draft), slot=slot).items():
            parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'.encode())
        parts.extend([f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="proof.png"\r\nContent-Type: image/png\r\n\r\n'.encode(), image,
                      f"\r\n--{boundary}--\r\n".encode()])
        return call("POST", path + "/files", token=host["userToken"], merchant=pay["merchantNo"],
                    raw=b"".join(parts), content_type="multipart/form-data; boundary=" + boundary,
                    expected=expected)

    try:
        for schema in (host_schema, pay_schema):
            mysql("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;")
            created.append(schema)
        host_cp = classpath(ROOT / "backend/shop-app/target/surefire-reports/TEST-ai.neargo.shop.payclient.PayBizOnboardingHttpTest.xml")
        pay_cp = classpath(args.near_pay_root / "backend/pay/pay-svc/target/surefire-reports/TEST-ai.neargo.shop.pay.svc.BizOnboardingHttpAcceptanceTest.xml")
        host = start("ai-shop", host_cp, "ai.neargo.shop.payclient.PayBizAiShopLocalLauncher",
                     output / "host-ready.json", ROOT / "backend/shop-app")
        env.update({"ALIGNMENT_OPS_TOKEN": host["opsToken"], "ALIGNMENT_REVIEWER_TOKEN": host["reviewerToken"]})
        pay = start("near-pay", pay_cp, "ai.neargo.shop.pay.svc.BizOnboardingAiShopLocalLauncher",
                    output / "pay-ready.json", args.near_pay_root / "backend/pay/pay-svc")
        user, staff, merchant = host["userToken"], host["staffToken"], pay["merchantNo"]
        assert call("GET", "/biz/auth/session", token=user)["subjectType"] == "USER"
        assert call("GET", "/biz/auth/session", token=staff)["subjectType"] == "MCH_ACCOUNT"
        call("GET", "/biz/auth/session", expected=401)
        call("GET", "/biz/auth/session", token=host["expiredToken"], expected=401)
        call("GET", "/biz/auth/session", token=host["otherTenantToken"], expected=403)
        memberships = call("GET", "/biz/pay/v2/memberships", token=user)
        assert len(memberships) == 1 and "onboarding.apply" in memberships[0]["actions"]
        index_columns = mysql("SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.statistics "
                              "WHERE TABLE_SCHEMA='" + pay_schema + "' AND TABLE_NAME='pay_merchant_membership' "
                              "AND INDEX_NAME='uk_pay_merchant_membership_2';")
        assert index_columns == "tenant_no,merchant_no,source_system,subject_type,subject_ref"
        mysql("INSERT INTO pay_merchant_membership(tenant_no,membership_no,merchant_no,source_system,subject_type,"
              "subject_ref,role_code,store_scope,store_nos,status,granted_by,created_at,updated_at) "
              "SELECT tenant_no,'duplicate-fixture',merchant_no,source_system,subject_type,subject_ref,role_code,"
              "store_scope,store_nos,status,granted_by,NOW(),NOW() FROM pay_merchant_membership WHERE membership_no='"
              + memberships[0]["membershipNo"] + "';", pay_schema, expected_error=1062)
        assert call("GET", "/biz/pay/v2/memberships", token=host["wrongTypeToken"]) == []
        assert len(call("GET", "/biz/pay/v2/memberships", token=staff)) == 1
        same_ref_staff = call("GET", "/biz/pay/v2/memberships", token=host["sameRefStaffToken"])
        assert len(same_ref_staff) == 1, "USER and MCH_ACCOUNT with the same ref need separate memberships"
        assert same_ref_staff[0]["role"] == "CASHIER"
        assert same_ref_staff[0]["membershipNo"] != memberships[0]["membershipNo"]
        member_path = "/biz/pay/v2/members/" + same_ref_staff[0]["membershipNo"]
        member_version = int(mysql("SELECT version FROM pay_merchant_membership WHERE membership_no='"
                                   + same_ref_staff[0]["membershipNo"] + "';", pay_schema))
        suspended = call("POST", member_path + "/suspend", {
            "requestNo": uuid.uuid4().hex, "expectedVersion": member_version, "reason": "同号不同类型停用"},
            user, merchant, origin=pay["payOrigin"])
        assert call("GET", "/biz/pay/v2/memberships", token=host["sameRefStaffToken"]) == []
        assert len(call("GET", "/biz/pay/v2/memberships", token=user)) == 1
        call("POST", member_path + "/resume", {
            "requestNo": uuid.uuid4().hex, "expectedVersion": suspended["version"], "reason": "同号不同类型恢复"},
            user, merchant, origin=pay["payOrigin"])
        call("POST", "/biz/pay/v2/members/" + memberships[0]["membershipNo"] + "/suspend", {
            "requestNo": uuid.uuid4().hex, "expectedVersion": 0, "reason": "本人授权不得停用"},
            user, merchant, origin=pay["payOrigin"], expected=403)
        call("GET", "/biz/pay/v2/capability-progress", token=user, merchant=merchant)
        opened = call("POST", "/biz/pay/v2/onboardings", {"requestNo": uuid.uuid4().hex, "payChannel": "NEWEBPAY"}, user, merchant)
        no = opened["onboardingNo"]
        path = "/biz/pay/v2/onboardings/" + no
        call("GET", "/biz/pay/v2/onboardings?page=1&size=10", token=user, merchant=merchant)
        call("GET", path, token=user, merchant=merchant)
        readiness = call("GET", path + "/readiness", token=user, merchant=merchant)
        assert readiness["channelReady"] is False and readiness["storeReadinessEvaluated"] is False
        draft = call("GET", path + "/draft", token=user, merchant=merchant)
        secrets_for_log_check = []
        refs = {}
        for field in ("MemberPhone", "BankAccount"):
            secret = "synthetic-alignment-" + field + "-" + uuid.uuid4().hex
            secrets_for_log_check.append(secret)
            refs[field] = call("POST", path + "/newebpay-materials", {
                "expectedVersion": draft["expectedVersion"], "fieldCode": field, "secret": secret}, user, merchant)["cipherRef"]
        save = dict(command(draft), fields={"LEGAL_FORM": "COMPANY", "CONTACT_NAME": "合成本地商户",
                    "CONTACT_PHONE": refs["MemberPhone"], "SETTLE_ACCOUNT_TYPE": "CORPORATE_BANK", "SETTLE_ACCOUNT": refs["BankAccount"]})
        draft = call("PUT", path + "/draft", save, user, merchant)
        call("PUT", path + "/draft", save, user, merchant)  # Original command replay.
        call("GET", path + "/draft/commands/" + save["requestNo"], token=user, merchant=merchant)
        changed = dict(save, fields=dict(save["fields"], CONTACT_NAME="different"))
        call("PUT", path + "/draft", changed, user, merchant, expected=409)
        call("PUT", path + "/draft", dict(save, requestNo=uuid.uuid4().hex), user, merchant, expected=409)
        png = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a3ioAAAAASUVORK5CYII=")
        image = png + bytes(2 * 1024 * 1024 - len(png))
        uploaded = upload(path, draft, "LICENSE_1", image)
        draft = uploaded["draft"]
        file_no = uploaded["attachment"]["fileNo"]
        draft = upload(path, draft, "LICENSE_2", image)["draft"]
        assert len(call("GET", path + "/files", token=user, merchant=merchant)) == 2
        upload(path, draft, "LICENSE_3", png + bytes(5 * 1024 * 1024 + 1 - len(png)), expected=413)
        # Chunked: exercise the streaming total limit with both files below the per-file limit.
        # A known oversized Content-Length may be rejected before the client finishes writing.
        total = []
        for number, size in enumerate((3 * 1024 * 1024, 4 * 1024 * 1024)):
            total.extend([f'--TotalBoundary\r\nContent-Disposition: form-data; name="file{number}"; filename="proof{number}.png"\r\nContent-Type: image/png\r\n\r\n'.encode(),
                          png + bytes(size - len(png)), b"\r\n"])
        total.append(b"--TotalBoundary--\r\n")
        call("POST", path + "/files", token=user, merchant=merchant, expected=413,
             raw=io.BytesIO(b"".join(total)), content_type="multipart/form-data; boundary=TotalBoundary")
        call("POST", "/biz/pay/v2/onboardings", token=user, merchant=merchant, expected=413,
             raw=b" " * (1024 * 1024 + 1))
        assert int(mysql("SELECT COUNT(*) FROM pay_onboarding_file;", pay_schema)) == 2
        downloaded = call("GET", path + "/files/" + file_no, token=user, merchant=merchant)
        assert "no-store" in checks[-1]["cacheControl"]
        assert checks[-1]["contentTypeOptions"] == "nosniff"
        assert hashlib.sha256(downloaded).digest() == hashlib.sha256(image).digest()
        assert int(mysql("SELECT byte_length FROM pay_onboarding_file WHERE file_no='" + file_no + "';", pay_schema)) == len(image)
        assert int(mysql("SELECT OCTET_LENGTH(encrypted_content) FROM pay_onboarding_file WHERE file_no='" + file_no + "';", pay_schema)) == len(image) + 16
        draft = call("POST", path + "/draft/confirm", command(draft), user, merchant)
        assert draft["state"] == "CONFIRMED"
        call("POST", path + "/draft/submit", command(draft), user, merchant, expected=404)
        ops_path = "/pay-ops/v2/onboardings/" + no
        draft = call("POST", ops_path + "/draft/submit", command(draft), host["opsToken"], origin=pay["payOrigin"])
        more = dict(command(draft), fields=["LICENSE_2"], reason="请补第二份合成证明")
        draft = call("POST", ops_path + "/draft/request-more", more, host["reviewerToken"], origin=pay["payOrigin"])
        current = call("GET", path + "/draft", token=user, merchant=merchant)
        assert "LICENSE_2" in current["supplementFields"]
        draft = upload(path, current, "LICENSE_2", image)["draft"]
        draft = call("POST", path + "/draft/confirm", command(draft), user, merchant)
        assert draft["state"] == "CONFIRMED"
        assert call("GET", path + "/files/" + file_no, token=user, merchant=merchant) == image
        call("GET", path + "/draft", token=user, merchant=pay["otherMerchant"], expected=403)
        call("PUT", path + "/draft", command(draft), host["cashierToken"], merchant, expected=403)
        call("GET", path + "/draft", token=host["wrongTypeToken"], merchant=merchant, expected=403)
        call("GET", path + "/draft", token=host["sameRefStaffToken"], merchant=merchant, expected=403)
        call("POST", path + "/cancel", dict(command(draft), reason="本机联调取消"), user, merchant)
        mysql("UPDATE pay_merchant_membership SET status='SUSPENDED' WHERE subject_type='USER' AND subject_ref='SAME';", pay_schema)
        call("GET", path + "/draft", token=user, merchant=merchant, expected=403)
        assert len(call("GET", "/biz/pay/v2/memberships", token=host["sameRefStaffToken"])) == 1
        mysql("UPDATE pay_merchant_membership SET status='ACTIVE' WHERE subject_type='USER' AND subject_ref='SAME';", pay_schema)
        mysql("UPDATE mch_session SET revoked_at=NOW() WHERE token_hash='" + hashlib.sha256(user.encode()).hexdigest() + "';", host_schema)
        call("GET", "/biz/pay/v2/memberships", token=user, expected=401)
        call("GET", "/biz/pay/v2/memberships", token=user, expected=401, origin=pay["payOrigin"])
        mysql("UPDATE mch_account SET status='DISABLED' WHERE mch_account_no='MCH-ADMIN';", host_schema)
        call("GET", "/biz/auth/session", token=staff, expected=403)
        call("GET", "/biz/pay/v2/memberships", token=staff, expected=401, origin=pay["payOrigin"])
        mysql("UPDATE mch_account SET status='ACTIVE' WHERE mch_account_no='MCH-ADMIN';", host_schema)
        mysql("RENAME TABLE mch_session TO mch_session_unavailable;", host_schema)
        try:
            call("GET", "/biz/auth/session", token=staff, expected=503)
            call("GET", "/biz/pay/v2/memberships", token=staff, expected=503, origin=pay["payOrigin"])
        finally:
            mysql("RENAME TABLE mch_session_unavailable TO mch_session;", host_schema)
        app_verified = False
        if args.app_root:
            app_env = env.copy()
            app_env["NEARPAY_ALIGNMENT_READY"] = str(output / "host-ready.json")
            with (output / "flutter-http.log").open("w") as stream:
                run = subprocess.run([
                    str(args.app_root / "tooling/flutterw"), "test", "--no-pub",
                    "test/integration/nearpay_aishop_runtime_test.dart", "--reporter=expanded"],
                    cwd=args.app_root / "apps/merchant_app", env=app_env,
                    stdout=stream, stderr=subprocess.STDOUT, timeout=180)
            if run.returncode:
                raise RuntimeError("Flutter HTTP acceptance failed; see " + str(output / "flutter-http.log"))
            app_verified = True
            print("Flutter HTTP client passed against both JVMs", flush=True)
        for logfile in [output / "ai-shop.log", output / "near-pay.log"] + ([output / "flutter-http.log"] if args.app_root else []):
            content = logfile.read_text(errors="replace")
            tokens = [value for key, value in host.items() if key.endswith("Token")]
            if any(secret in content for secret in secrets_for_log_check + tokens + [env["ALIGNMENT_MASTER_KEY"]]):
                raise AssertionError("Sensitive fixture value appeared in " + logfile.name)
        covered = 0
        for route in json.loads((ROOT / "docs/handoff/nearpay-biz-onboarding-2026-11-09/routes.json").read_text())["routes"]:
            pattern = re.sub(r"\\\{[^}]+\\\}", "[^/]+", re.escape(route["path"]))
            assert any(c["method"] == route["method"] and c["status"] == 200
                       and re.fullmatch(pattern, c["path"].split("?", 1)[0]) for c in checks), route
            covered += 1
        result = {"status": "PASS", "checks": len(checks), "mysqlVersion": version,
                  "handoffRoutesCovered": covered, "flutterHttpVerified": app_verified,
                  "aiShopHead": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                  "nearPayHead": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=args.near_pay_root, text=True).strip(),
                  "privateImageSha256": hashlib.sha256(image).hexdigest(), "requests": checks,
                  "scope": "two JVMs, MySQL9 fixture schemas, production BIZ/session/draft/file components; no vendor gateway"}
        (output / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2))
        print("PASS:", len(checks), "HTTP assertions; result:", output / "result.json", flush=True)
    finally:
        for process in reversed(processes):
            process.terminate()
        for process in reversed(processes):
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        for schema in reversed(created):
            mysql("DROP DATABASE " + schema + ";")
        for ready_file in (output / "host-ready.json", output / "pay-ready.json"):
            ready_file.unlink(missing_ok=True)
        print("Local processes and fixture schemas cleaned; logs:", output, flush=True)


if __name__ == "__main__":
    main()
