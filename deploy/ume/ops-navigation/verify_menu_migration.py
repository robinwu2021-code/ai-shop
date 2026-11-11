#!/usr/bin/env python3
"""菜单迁移前后核对（V658 起；演练库与 166 生产通用）。

  MYSQL_DEFAULTS=<客户端配置文件> python3 verify_menu_migration.py snapshot <db> <out.json>
  MYSQL_DEFAULTS=… python3 verify_menu_migration.py seed <db>          # 仅隔离演练库：插入合成自建角色
  MYSQL_DEFAULTS=… python3 verify_menu_migration.py verify <db> <before.json> <expected.json> <v657.sql> <out.json> \
        --version 658 --removed OPS_AFTERSALE__TAB_REFUND_BACK --merged-into OPS_FINANCE__TAB_REFUND_BACK

连接只经 MYSQL_DEFAULTS 指向的客户端配置文件（含主机/账号/口令），命令行与输出里不出现凭据。
只读：snapshot/verify 只做 SELECT；seed 写库，脚本拒绝在名字不含 rehearsal 的库上执行。
"""
import argparse, hashlib, json, os, re, subprocess, sys

MYSQL_BIN = os.environ.get("MYSQL_BIN", "mysql")
CUSTOM = "V658_REHEARSAL"   # 合成的运营自建角色：只授售后那条退款分账，用来走「授权并入」分支（只在演练库）


def mysql_args():
    defaults = os.environ.get("MYSQL_DEFAULTS")
    if not defaults:
        sys.exit("MYSQL_DEFAULTS 未设置：指向含 host/user/password 的 mysql 客户端配置文件")
    return [MYSQL_BIN, f"--defaults-file={defaults}", "-N", "-B"]


def q(db, sql):
    out = subprocess.run(mysql_args() + [db, "-e", sql], check=True, capture_output=True, text=True).stdout
    return [line.split("\t") for line in out.splitlines() if line]


def snapshot(db):
    return {
        "functions": q(db, "SELECT function_code,name,sort,enabled FROM sys_function WHERE end_code='OPS' ORDER BY function_code"),
        "points": q(db, "SELECT point_code,function_code,name,IFNULL(group_name,''),IFNULL(href,''),point_type,"
                        "IFNULL(ui_perm_code,''),IFNULL(perm_code,''),backend_status,ui_ready,IFNULL(gated_by,''),sort "
                        "FROM sys_function_point ORDER BY point_code"),
        "roles": q(db, "SELECT role_code,name,end_code,builtin,wildcard FROM sys_role ORDER BY role_code"),
        "grants": q(db, "SELECT role_code,point_code,end_code,IFNULL(entity_no,'<NULL>'),tenant_no,deleted FROM sys_role_point "
                        "ORDER BY role_code,point_code,entity_no"),
        "members": q(db, "SELECT * FROM sys_role_member ORDER BY 1"),
        "flyway": q(db, "SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank"),
    }


def sha(rows):
    return hashlib.sha256(json.dumps(rows, ensure_ascii=False).encode()).hexdigest()


def visible(snap):
    """role → 可见菜单 href 集合 与 后端权限码集合（授权点 join 功能点）。"""
    pts = {p[0]: p for p in snap["points"]}
    hrefs, perms = {}, {}
    for role, pc, end, ent, tenant, deleted in snap["grants"]:
        if deleted != "0" or pc not in pts:
            continue
        p = pts[pc]
        if p[5] == "MENU" and p[4]:
            hrefs.setdefault(f"{role}|{ent}", set()).add(p[4])
        if p[7]:
            perms.setdefault(f"{role}|{ent}", set()).add(p[7])
    return hrefs, perms


def verify(db, before_path, expected_path, v657_path, version, removed_points, merged_into):
    before, after = json.load(open(before_path)), snapshot(db)
    exp = json.load(open(expected_path))
    aliases = dict(re.findall(r"point_code IN \('([^']+)','([^']+)'\)", open(v657_path).read()))
    checks, problems = {}, []

    # 1 角色 / 成员 / 旧 Flyway 历史不变
    for k in ("roles", "members"):
        checks[k] = {"rows": len(after[k]), "unchanged": before[k] == after[k]}
        if before[k] != after[k]: problems.append(f"{k} 变了")
    checks["previousFlywayHistoryUnchanged"] = after["flyway"][:len(before["flyway"])] == before["flyway"]
    new = after["flyway"][len(before["flyway"]):]
    checks["flyway"] = new
    if [r[0] for r in new] != [version] or new[0][2] != "1": problems.append(f"Flyway 新记录不是唯一成功的 {version}：{new}")

    # 2 功能点：只少售后退款分账一条；身份/权限/门禁/就绪全不变
    bp, ap = {p[0]: p for p in before["points"]}, {p[0]: p for p in after["points"]}
    removed, added = set(bp) - set(ap), set(ap) - set(bp)
    checks["pointsRemoved"], checks["pointsAdded"] = sorted(removed), sorted(added)
    if removed != set(removed_points) or added: problems.append(f"功能点增删不符：-{removed} +{added}")
    gate_cols = lambda p: (p[4], p[5], p[6], p[7], p[8], p[9], p[10])   # href,type,ui_perm,perm,status,ready,gated
    drift = [pc for pc in ap if gate_cols(ap[pc]) != gate_cols(bp[pc])]
    checks["pointIdentityPermissionsAndGates"] = {"rows": len(ap), "unchanged": not drift}
    if drift: problems.append(f"权限/门禁漂移：{drift[:5]}")

    # 3 菜单与 nav.ts 逐点一致（含实库 OPS_PAY_0x 别名）
    matched, mismatched = 0, []
    for pc, leaf in exp["leaves"].items():
        row = ap.get(pc) or ap.get(aliases.get(pc, ""))
        want = (leaf["fn"], leaf["name"], leaf["group"])
        if row and (row[1], row[2], row[3]) == want: matched += 1
        else: mismatched.append((pc, want, row[1:4] if row else None))
    checks["menuMappings"] = {"expected": len(exp["leaves"]), "matched": matched, "allMatched": not mismatched}
    if mismatched: problems.append(f"菜单不一致：{mismatched[:5]}")
    fns = {f[0]: f for f in after["functions"]}
    for s in exp["sections"]:
        f = fns.get(s["fn"])
        if not f or (f[1], int(f[2]), f[3]) != (s["name"], s["sort"], "1"): problems.append(f"分区不符：{s} vs {f}")
    retired = {fn: (fns.get(fn, [None, None, None, None])[3], sum(1 for p in ap.values() if p[1] == fn))
               for fn in ("OPS_MEMBER", "OPS_POINTS", "OPS_MESSAGE")}
    checks["retiredFunctions"] = {fn: {"enabled": e, "pointsLeft": n} for fn, (e, n) in retired.items()}
    for fn, (e, n) in retired.items():
        if e != "0" or n: problems.append(f"{fn} 未清空停用：enabled={e} points={n}")

    # 4 授权：每个角色(×entity_no)的可见菜单 href 与后端权限码集合完全不变
    bh, bperm = visible(before); ah, aperm = visible(after)
    role_drift = sorted(k for k in set(bh) | set(ah) if bh.get(k) != ah.get(k))
    perm_drift = sorted(k for k in set(bperm) | set(aperm) if bperm.get(k) != aperm.get(k))
    checks["roleVisibleHrefs"] = {"subjects": len(set(bh) | set(ah)), "unchanged": not role_drift}
    checks["rolePermCodes"] = {"subjects": len(set(bperm) | set(aperm)), "unchanged": not perm_drift}
    if role_drift: problems.append(f"可见菜单变了：{role_drift}")
    if perm_drift: problems.append(f"权限码变了：{perm_drift}")
    bg = {tuple(g) for g in before["grants"]}; ag = {tuple(g) for g in after["grants"]}
    checks["grants"] = {"before": len(bg), "after": len(ag),
                        "removed": sorted(g[:2] + (g[3],) for g in bg - ag),
                        "added": sorted(g[:2] + (g[3],) for g in ag - bg)}
    if any(g[1] not in removed_points for g in bg - ag): problems.append("撤了声明以外的授权")
    if any(g[1] not in merged_into for g in ag - bg): problems.append("新增了并入目标以外的授权")
    # 被撤点上的每条授权（角色×entity_no）都必须在并入目标上存在
    lost = sorted({(g[0], g[3]) for g in bg if g[1] in removed_points}
                  - {(g[0], g[3]) for g in ag if g[1] in merged_into})
    checks["grantsMerged"] = {"lost": lost}
    if lost: problems.append(f"这些授权没有并入：{lost}")
    if any(r[0] == CUSTOM for r in before["roles"]):
        custom = {g[3] for g in ag if g[0] == CUSTOM and g[1] in merged_into}
        checks["customRoleMerged"] = sorted(custom)
        if custom != {"<NULL>", "ENT-REHEARSAL"}: problems.append(f"自建角色授权未完整并入：{custom}")
    return checks, problems, after


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["snapshot", "seed", "verify"])
    ap.add_argument("db")
    ap.add_argument("paths", nargs="*")
    ap.add_argument("--version")
    ap.add_argument("--removed", action="append", default=[])
    ap.add_argument("--merged-into", action="append", default=[])
    a = ap.parse_args()
    if a.cmd == "snapshot":
        json.dump(snapshot(a.db), open(a.paths[0], "w"), ensure_ascii=False)
    elif a.cmd == "seed":
        if "rehearsal" not in a.db:
            sys.exit("拒绝：seed 只用于名字含 rehearsal 的隔离演练库")
        q(a.db, f"INSERT INTO sys_role (role_code,name,end_code,builtin,wildcard,sort,created_at,updated_at) "
                f"VALUES ('{CUSTOM}','演练自建角色','OPS',0,0,999,NOW(),NOW())")
        for ent in ("NULL", "'ENT-REHEARSAL'"):
            q(a.db, f"INSERT INTO sys_role_point (role_code,point_code,end_code,entity_no,created_at,updated_at) "
                    f"VALUES ('{CUSTOM}','OPS_AFTERSALE__TAB_REFUND_BACK','OPS',{ent},NOW(),NOW())")
    else:
        before, expected, v657, out = a.paths
        if not (a.version and a.removed and a.merged_into):
            sys.exit("verify 需要 --version、--removed、--merged-into")
        checks, problems, after = verify(a.db, before, expected, v657, a.version, a.removed, a.merged_into)
        json.dump({"flyway": dict(zip(("version", "checksum", "success"),
                                      (checks["flyway"][0][0], int(checks["flyway"][0][1]), int(checks["flyway"][0][2]))))
                   if checks["flyway"] else {}, "checks": checks, "problems": problems},
                  open(out, "w"), ensure_ascii=False, indent=1)
        print("✗ " + "\n✗ ".join(problems) if problems else "✓ 全部核对通过")
        sys.exit(1 if problems else 0)
