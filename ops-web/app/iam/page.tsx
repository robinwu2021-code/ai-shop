"use client";

// 员工与权限（矩阵 P-1.1 + §2.3）。它是整个平台端权限的基准面。
//
// 敏感操作二次校验（P-1.1.5）**不单独成页**：它是动作上的一层 —— 停用管理员、
// 授予高危权限时要求手输确认，与资金域的分账下发同一套做法。
import { Suspense, useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { useI18n } from "@/lib/i18n";
import { useAuth } from "@/lib/auth";
import { fill, useCopy } from "@/lib/use-copy";
import { IAM_COPY } from "./copy";
import { usePaging } from "@/lib/use-paging";
import { usePageTab } from "@/lib/use-page-tab";
import { fmtTime } from "@/lib/utils";
import { useCan } from "@/lib/use-can";
import { notify } from "@/lib/notify";
import { CRITICAL_PERMS, ROLE_LABEL } from "@/lib/permissions";
import { NAV } from "@/lib/nav";
import { SCOPED_ROLES, type AuditLog, type RoleDef, type Staff } from "@/lib/types";
import type { Role } from "@/lib/auth";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, Field } from "@/components/ui/drawer";
import { FilterSelect } from "@/components/ui/filter-select";
import { Input, Select } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Notice } from "@/components/ui/notice";
import { Pagination } from "@/components/ui/misc";
import { Switch } from "@/components/ui/switch";
import { TabHeader } from "@/components/ui/tab-header";
import { Toolbar } from "@/components/ui/toolbar";
import { Tree, type TreeNode } from "@/components/ui/tree";
import { useConfirm } from "@/components/ui/confirm-dialog";

type Copy = (typeof IAM_COPY)["zh"];
const TABS = (c: Copy) => [
  { key: "staffs", label: c.tabStaffs },
  { key: "roles", label: c.tabRoles },
  { key: "audit", label: c.tabAudit },
];

const ROLES = Object.keys(ROLE_LABEL) as Role[];

const ENABLED_OPTIONS = (c: Copy) => [{ value: "1", label: c.enabledOn }, { value: "0", label: c.enabledOff }];
const CRITICAL = CRITICAL_PERMS as readonly string[];

export default function IamPage() {
  return <Suspense fallback={null}><IamInner /></Suspense>;
}

function IamInner() {
  const { t } = useI18n();
  const c = useCopy(IAM_COPY);
  // 角色名走框架层 i18n（`role.*` 中英各一份），不再用 lib/permissions 里那份中文常量 ——
  // 那份是给非 React 处用的，页面上直接渲染它会在英文界面漏出中文。
  const roleLabel = (r: Role) => t(`role.${r}`);
  const roleOptions = ROLES.map((r) => ({ value: r, label: roleLabel(r) }));
  const tabs = TABS(c);
  const enabledOptions = ENABLED_OPTIONS(c);
  const qc = useQueryClient();
  const allow = useCan();
  const { confirm, dialog } = useConfirm();

  const [tab, setTab] = usePageTab(tabs, () => { setPage(1); setKeyword(""); });

  const { page, setPage, size, setSize } = usePaging();
  const [keyword, setKeyword] = useState("");
  const [role, setRole] = useState("");
  const [enabled, setEnabled] = useState("");
  const [critical, setCritical] = useState("");
  const [editing, setEditing] = useState<Staff | null>(null);
  const [scopeForm, setScopeForm] = useState({ merchantNo: "", communityNo: "", pickupNo: "" });
  // 自定义角色不在 Role 联合类型里 —— 用 string
  const [pickedRole, setPickedRole] = useState<string | null>(null);
  const [newRole, setNewRole] = useState<{ roleCode: string; name: string } | null>(null);
  const [rolesForm, setRolesForm] = useState<string[]>([]);
  const [newStaff, setNewStaff] = useState<{ username: string; realName: string; roles: string[] } | null>(null);
  /** 建号后一次性展示的初始密码。**关掉就没了** —— 后端也不再返回 */
  const [createdPassword, setCreatedPassword] = useState<{ username: string; password: string } | null>(null);
  /*
   * 自己那行不能编辑：后端会拒（10420），但**让人点开抽屉、改完、点保存才报错，
   * 是最差的一种**。禁用 + tooltip 说明理由。
   */
  const myUsername = useAuth((st) => st.username);
  const [checked, setChecked] = useState<string[]>([]);

  const canGrant = allow("iam:role:grant");
  const canReadAudit = allow("iam:audit:read");

  const staffQ = { keyword, role, enabled, page, size };
  const staffs = useQuery({ queryKey: ["staffs", staffQ], queryFn: () => api.listStaffs(staffQ), enabled: tab === "staffs" });
  // **员工编辑抽屉也要用到角色列表**（多选按钮的数据源），不能只在 roles tab 下拉取 ——
  // 第一版漏了这个，staffs tab 下点「编辑」，角色多选区一个按钮都没有，
  // 页面看着正常（没有报错），只是选项集合是空的。
  const roles = useQuery({ queryKey: ["roles"], queryFn: () => api.listRoles(), enabled: tab === "roles" || tab === "staffs" });
  // 功能点全集。**不按人切片** —— 配角色时要看到全部，包括自己没有的
  const permFns = useQuery({ queryKey: ["perm-functions"], queryFn: () => api.listPermFunctions(), enabled: tab === "roles" });
  const rolePts = useQuery({
    queryKey: ["role-points", pickedRole],
    queryFn: () => api.getRolePoints(pickedRole!),
    enabled: tab === "roles" && !!pickedRole,
  });
  const auditQ = { keyword, critical, page, size };
  const logs = useQuery({ queryKey: ["audit-logs", auditQ], queryFn: () => api.listAuditLogs(auditQ), enabled: tab === "audit" });

  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ["staffs"] });
    qc.invalidateQueries({ queryKey: ["roles"] });
    qc.invalidateQueries({ queryKey: ["audit-logs"] });
  };

  const setEnabledMut = useMutation({
    mutationFn: (v: { staffNo: string; enabled: boolean }) => api.setStaffEnabled(v.staffNo, v.enabled),
    onSuccess: (s) => { invalidate(); notify.success(s.enabled ? c.toastEnabled : c.toastDisabled); },
  });
  /**
   * 保存员工：角色与数据域**一次提交**。
   *
   * 两个接口串行而不是并行：数据域的校验依赖角色（全量角色不许配 scope），
   * 并行的话「先落 scope 再落 roles」会被旧角色拒掉。
   */
  const saveStaffMut = useMutation({
    mutationFn: async () => {
      const s = editing!;
      const rolesChanged = rolesForm.join() !== s.roles.join();
      if (rolesChanged) await api.setStaffRoles(s.staffNo, rolesForm);
      const scopeChanged = (scopeForm.merchantNo || "") !== (s.merchantNo ?? "")
        || (scopeForm.communityNo || "") !== (s.communityNo ?? "")
        || (scopeForm.pickupNo || "") !== (s.pickupNo ?? "");
      // 只在受限角色下提交 scope —— 全量角色配 scope 后端直接拒
      if (scopeChanged && rolesForm.some((r) => SCOPED_ROLES.includes(r as Role))) {
        await api.setStaffScope(s.staffNo, scopeForm);
      }
    },
    onSuccess: () => { invalidate(); setEditing(null); notify.success(c.toastStaffSaved); },
  });
  const createStaffMut = useMutation({
    mutationFn: () => api.createStaff(newStaff!.username.trim(), newStaff!.realName.trim(), newStaff!.roles),
    onSuccess: (r) => {
      invalidate();
      setNewStaff(null);
      // **先展示密码再关抽屉** —— 它只出现这一次
      setCreatedPassword({ username: r.staff.username, password: r.initialPassword });
    },
  });
  const renameRoleMut = useMutation({
    mutationFn: (v: { roleCode: string; name: string }) => api.renameRole(v.roleCode, v.name),
    onSuccess: () => { invalidate(); notify.success(c.toastRoleRenamed); },
  });
  const createRoleMut = useMutation({
    mutationFn: () => api.createRole(newRole!.roleCode.trim(), newRole!.name.trim()),
    onSuccess: (r) => {
      invalidate();
      setNewRole(null);
      // 建完直接选中它 —— 否则用户要在 11 行里找自己刚建的那个
      setPickedRole(r.roleCode);
      notify.success(c.toastRoleCreated);
    },
  });
  const setPermsMut = useMutation({
    mutationFn: (v: { roleCode: string; points: string[] }) => api.setRolePoints(v.roleCode, v.points),
    onSuccess: () => {
      invalidate();
      qc.invalidateQueries({ queryKey: ["role-points"] });
      notify.success(c.toastPermsSaved);
    },
  });

  /*
   * 权限树：**功能 → 功能点**两层，数据源是服务端的功能点全集。
   *
   * **2026-08-12 换粒度**：此前勾的是「权限码」（去重后只有 16 个），
   * 而库里存的是功能点。勾权限码的话保存时要把码反向翻译成功能点集合，
   * 一个码对应多个点，反向只能「全给」—— 那就是翻译层，
   * 而这个仓库里绝大多数跨端缺陷都出自翻译层两边各写一套。
   *
   * 现在：勾什么 = 库里存什么 = 那个人登录后菜单长什么样。
   */
  const permTree: TreeNode[] = useMemo(
    () =>
      (permFns.data ?? [])
        .filter((f) => f.points.some((p) => p.pointType === "MENU" || p.pointType === "ACTION"))
        .map((f) => ({
          key: `f:${f.functionCode}`,
          label: f.name,
          children: f.points.map((p) => {
            const unbuilt = p.backendStatus === "NOT_IMPLEMENTED";
            return {
              key: p.pointCode,
              // **未实现的点渲染但标出来**，不是藏起来：勾了它不报错，但也不生效 ——
              // 不标的后果很具体：运营给风控角色勾满了功能点，那个人登录进去还是空看板，
              // 而没有任何东西告诉他们原因
              disabled: unbuilt,
              label: (
                <span className={`flex items-center gap-2 ${unbuilt ? "text-muted-foreground" : ""}`}>
                  {p.name}
                  {p.uiPermCode && <code className="txt-caption text-muted-foreground">{p.uiPermCode}</code>}
                  {unbuilt && <Badge tone="muted">{c.notImplemented}</Badge>}
                  {p.uiPermCode && CRITICAL.includes(p.uiPermCode) && <Badge tone="danger">{c.critical}</Badge>}
                </span>
              ),
            };
          }),
        })),
    [permFns.data, c],
  );

  const openRole = (r: RoleDef) => setPickedRole(r.roleCode);
  const pickedRoleDef = roles.data?.find((r) => r.roleCode === pickedRole);
  /** 抽屉在「新建」与「改名」之间复用：角色码已存在就是改名 */
  const renaming = !!newRole && (roles.data ?? []).some((r) => r.roleCode === newRole.roleCode);
  /** 功能点码 → UI 权限码。危险项判定要用它 —— 树的 key 是功能点码 */
  const pointUiCode: Record<string, string> = useMemo(() => {
    const m: Record<string, string> = {};
    for (const f of permFns.data ?? []) for (const p of f.points) if (p.uiPermCode) m[p.pointCode] = p.uiPermCode;
    return m;
  }, [permFns.data]);

  // 选中角色时把库里已勾的拉过来。**不能用 openRole 里同步塞** ——
  // 那要等接口回来，而 setState 是同步的，结果是勾选框空一拍
  useEffect(() => { setChecked(rolePts.data ?? []); }, [rolePts.data]);

  const staffColumns: Column<Staff>[] = [
    { header: c.colStaffNo, cell: (s) => s.staffNo, numeric: true, align: "start" },
    { header: c.colName, cell: (s) => s.name },
    { header: c.colUsername, cell: (s) => s.username },
    {
      header: c.colRole,
      /*
       * **只显示，不在行内改。**
       *
       * 此前这里是个行内下拉、选完立即提交 —— 它没有「确认」这一步，
       * 而这一列改的是「这个人能干什么」。选错一格权限当场就变了，
       * 而旁边的数据域反倒有抽屉、有保存按钮：两个同等重要的操作，
       * 一个能反悔一个不能。现在统一进抽屉。
       */
      cell: (s) => (
        <span className="flex flex-wrap gap-1">
          {s.roles.length
            ? s.roles.map((r) => <Badge key={r} tone={r === "SUPER_ADMIN" ? "danger" : "muted"}>{roleLabel(r as Role)}</Badge>)
            : <span className="text-[var(--warning)]">{c.roleNone}</span>}
        </span>
      ),
    },
    {
      header: c.colScope,
      // 全量角色不显示"—"而是显示"全量"：留白会被读成"还没配"
      cell: (s) => {
        // **持有任一受限角色就受限** —— 只要有一个身份受限，数据域就该生效。
        // 反过来（全部受限才算）会让「社区运营 + 某个全量角色」的人
        // 悄悄拿到全量数据，而界面上那一栏还写着社区号
        if (!s.roles.some((r) => SCOPED_ROLES.includes(r as Role))) {
          return <span className="text-muted-foreground">{c.scopeAll}</span>;
        }
        const parts = [
          s.merchantNo && fill(c.scopeMerchant, { no: s.merchantNo }),
          s.communityNo && fill(c.scopeCommunity, { no: s.communityNo }),
          s.pickupNo && fill(c.scopePickup, { no: s.pickupNo }),
        ].filter(Boolean);
        return parts.length ? parts.join(" · ") : <span className="text-[var(--warning)]">{c.scopeUnbounded}</span>;
      },
    },
    { header: c.colLastLogin, cell: (s) => fmtTime(s.lastLoginAt) },
    {
      header: c.colEnabled,
      cell: (s) => (
        <Switch
          checked={s.enabled}
          disabled={!canGrant}
          aria-label={fill(c.ariaEnableSwitch, { name: s.name })}
          onChange={async (v) => {
            if (!v && s.roles.includes("SUPER_ADMIN")) {
              const ok = await confirm({
                title: fill(c.confirmDisableTitle, { name: s.name }),
                desc: c.confirmDisableDesc,
                danger: true, confirmText: c.confirmDisableOk, requireText: s.staffNo,
              });
              if (!ok) return;
            }
            setEnabledMut.mutate({ staffNo: s.staffNo, enabled: v });
          }}
        />
      ),
    },
    {
      header: c.colActions,
      /*
       * **一个入口，进去改全部** —— 角色、数据域在同一个抽屉里一次保存。
       * 此前角色是行内下拉、数据域是抽屉，而且抽屉只对受限角色才出现，
       * 于是「给全量角色的人改角色」和「改数据域」是两种完全不同的操作路径。
       */
      cell: (s) => (
        <Button size="sm" variant="outline"
          disabled={!canGrant || s.username === myUsername}
          title={s.username === myUsername ? c.cannotEditSelf : undefined}
          onClick={() => {
            setEditing(s);
            setRolesForm([...s.roles]);
            setScopeForm({ merchantNo: s.merchantNo ?? "", communityNo: s.communityNo ?? "", pickupNo: s.pickupNo ?? "" });
          }}>
          {c.actionEdit}
        </Button>
      ),
    },
  ];

  const roleColumns: Column<RoleDef>[] = [
    { header: c.colRoleLabel, cell: (r) => r.name },
    { header: c.colRoleCode, cell: (r) => <code className="txt-caption">{r.roleCode}</code> },
    // staffCount 摆在这里是有用途的：它是删角色前唯一能看出「会影响谁」的信息。
    // 后端也拦（10441），但那是拦在点下去之后
    { header: c.colStaffCount, cell: (r) => r.staffCount, numeric: true },
    { header: c.colPermCount, cell: (r) => r.pointCount, numeric: true },
    {
      header: c.colRoleKind,
      // **预置角色渲染但不可编辑**，不是藏起来 —— 超管、BD、客服是运营最常看的三个，
      // 藏起来等于「角色列表里没有客服」
      cell: (r) => (r.builtin
        ? <Badge tone="muted">{c.builtinRole}</Badge>
        : <span className="text-muted-foreground">{c.customRole}</span>),
    },
    {
      header: c.colActions,
      /*
       * **预置角色也要有按钮**，只是进去之后是只读的。
       * 第一版这里只渲染一行「内置不可改」—— 而 11 个角色全是预置的，
       * 于是权限树永远打不开：功能做完了但没有入口，浏览器点一下才发现。
       */
      cell: (r) => (
        <span className="flex gap-2">
          <Button size="sm" variant="outline" onClick={() => openRole(r)}>
            {r.builtin ? c.actionView : c.actionPerms}
          </Button>
          {canGrant && !r.builtin && (
            <Button size="sm" variant="ghost"
              onClick={() => setNewRole({ roleCode: r.roleCode, name: r.name })}>
              {c.actionRename}
            </Button>
          )}
        </span>
      ),
    },
  ];

  const logColumns: Column<AuditLog>[] = [
    { header: c.colTime, cell: (l) => fmtTime(l.at) },
    { header: c.colOperator, cell: (l) => l.operator },
    { header: c.colAction, cell: (l) => l.action },
    { header: c.colTarget, cell: (l) => l.target },
    { header: c.colDetail, cell: (l) => l.detail, className: "whitespace-normal", width: "22rem" },
    { header: c.colCritical, cell: (l) => (l.critical ? <Badge tone="danger">{c.yes}</Badge> : <span className="text-muted-foreground">{c.no}</span>) },
  ];

  return (
    <div>
      <TabHeader tabs={tabs} value={tab} onChange={setTab} />

      {tab !== "audit" && !canGrant && (
        <ReadOnlyNotice what={c.readOnlyWhat} perm="iam:role:grant" note={c.readOnlyNote} className="mb-3" />
      )}

      {tab === "roles" && (
        <>
          <Notice className="mb-3">{c.notice}</Notice>
          {canGrant && (
            <div className="mb-3">
              <Button size="sm" onClick={() => setNewRole({ roleCode: "", name: "" })}>
                {c.actionNewRole}
              </Button>
            </div>
          )}
        </>
      )}

      {/* 主操作放在表格上方，**不放进 Toolbar** —— 那里是筛选区，
          守卫要求进去的控件都能回显成 chip，而「新建」不是筛选条件 */}
      {tab === "staffs" && canGrant && (
        <div className="mb-3">
          <Button size="sm" onClick={() => setNewStaff({ username: "", realName: "", roles: [] })}>
            {c.newStaffTitle}
          </Button>
        </div>
      )}

      {tab !== "roles" && (
        <Toolbar
          search={keyword}
          onSearch={(v) => { setKeyword(v); setPage(1); }}
          searchPlaceholder={tab === "staffs" ? c.searchStaff : c.searchAudit}
        >
          {tab === "staffs" && (
            <>
              <FilterSelect aria-label={c.filterRole} value={role} onChange={(v) => { setRole(v); setPage(1); }} options={roleOptions} allLabel={c.filterRoleAll} />
              <FilterSelect aria-label={c.filterStatus} value={enabled} onChange={(v) => { setEnabled(v); setPage(1); }} options={enabledOptions} allLabel={c.filterStatusAll} />
            </>
          )}
          {tab === "audit" && (
            <FilterSelect aria-label={c.filterCritical} value={critical} onChange={(v) => { setCritical(v); setPage(1); }}
              options={[{ value: "1", label: c.filterCriticalOnly }]} allLabel={c.filterCriticalAll} />
          )}
        </Toolbar>
      )}

      {tab === "staffs" && (
        <>
          <DataTable
            columns={staffColumns} rows={staffs.data?.records} loading={staffs.isLoading}
            error={staffs.error} onRetry={() => staffs.refetch()}
            rowKey={(s) => s.staffNo}
            empty={c.emptyStaff}
          />
          <Pagination page={page} size={size} onSize={setSize} total={staffs.data?.total ?? 0} onPage={setPage} />
        </>
      )}

      {tab === "roles" && (
        <DataTable
          columns={roleColumns} rows={roles.data} loading={roles.isLoading}
          error={roles.error} onRetry={() => roles.refetch()}
          rowKey={(r) => r.roleCode}
          empty={c.emptyRoles}
        />
      )}

      {/*
        权限配置改成「表格 + 抽屉」，不用左右分栏 —— 与页面其它主操作
        （员工编辑、新建员工、新建/改名角色、初始密码展示）同一套交互，
        减少「同一个页面里一半弹抽屉、一半左右排列」的不一致。
      */}
      {tab === "roles" && (
        <Drawer
          open={!!pickedRole}
          onOpenChange={(o) => !o && setPickedRole(null)}
          title={pickedRole ? fill(c.permCardTitle, { role: pickedRoleDef?.name ?? pickedRole }) : ""}
          footer={pickedRole ? (
            <div className="flex items-center gap-2">
              <Button
                disabled={pickedRoleDef?.builtin}
                loading={setPermsMut.isPending}
                onClick={async () => {
                  // 危险项按功能点的 UI 码判 —— 树的 key 是功能点码，不是权限码
                  const added = checked
                    .map((pc) => pointUiCode[pc])
                    .filter((ui): ui is string => !!ui && CRITICAL.includes(ui));
                  if (added.length) {
                    const ok = await confirm({
                      title: fill(c.confirmGrantTitle, { role: pickedRoleDef?.name ?? pickedRole }),
                      desc: fill(c.confirmGrantDesc, { n: added.length, list: added.join("、") }),
                      danger: true, confirmText: c.confirmGrantOk, requireText: pickedRole,
                    });
                    if (!ok) return;
                  }
                  setPermsMut.mutate({ roleCode: pickedRole, points: checked });
                }}
              >
                {c.save}
              </Button>
              <span className="txt-caption text-muted-foreground">{fill(c.selectedN, { n: checked.length })}</span>
            </div>
          ) : null}
        >
          {pickedRole && (
            <div className="space-y-3">
              {/*
                预置角色「渲染但只读」：它们是 Perms.java 的镜像，改了会与回落表分叉，
                而什么时候走回落不由前端决定。后端也拒（10440）——
                但让人勾完点保存才报错，是最差的一种。
              */}
              {pickedRoleDef?.builtin && (
                <Notice tone="muted">{c.builtinReadOnly}</Notice>
              )}
              {/* 权限树用的就是 Tree 的 checkable 语义：值只认叶子（功能点码），父节点三态 */}
              <Tree
                nodes={permTree}
                empty={c.emptyPerms}
                checkable
                checkedKeys={checked}
                onCheckedChange={pickedRoleDef?.builtin ? () => {} : setChecked}
                collapseFrom={1}
              />
            </div>
          )}
        </Drawer>
      )}

      {tab === "audit" && (
        <>
          <Notice className="mb-3">
            {c.auditNotice}
          </Notice>
          {!canReadAudit ? (
            <ReadOnlyNotice what={c.auditReadOnlyWhat} perm="iam:audit:read" />
          ) : (
            <>
              <DataTable
                columns={logColumns} rows={logs.data?.records} loading={logs.isLoading}
                error={logs.error} onRetry={() => logs.refetch()}
                rowKey={(l) => l.logNo}
                empty={c.emptyAudit}
              />
              <Pagination page={page} size={size} onSize={setSize} total={logs.data?.total ?? 0} onPage={setPage} />
            </>
          )}
        </>
      )}

      {/* 新建自定义角色 */}
      <Drawer
        open={!!newRole}
        onOpenChange={(o) => !o && setNewRole(null)}
        title={renaming ? c.renameRoleTitle : c.newRoleTitle}
        desc={renaming ? c.renameRoleDesc : c.newRoleDesc}
        footer={newRole ? (
          <Button
            loading={createRoleMut.isPending || renameRoleMut.isPending}
            disabled={!newRole.roleCode.trim() || !newRole.name.trim()}
            onClick={() => (renaming
              ? renameRoleMut.mutate({ roleCode: newRole.roleCode, name: newRole.name.trim() },
                  { onSuccess: () => setNewRole(null) })
              : createRoleMut.mutate())}
          >
            {c.save}
          </Button>
        ) : null}
      >
        {newRole && (
          <div className="space-y-4">
            <div className="space-y-1">
              <Label htmlFor="nr-code">{c.fieldRoleCode}</Label>
              {/* 改名时角色码只读 —— 码是授权的键（sys_role_point / sys_role_member
                  都指着它），改了等于换一个角色 */}
              <Input id="nr-code" className="w-full" placeholder={c.phRoleCode}
                disabled={renaming}
                value={newRole.roleCode}
                onChange={(e) => setNewRole({ ...newRole, roleCode: e.target.value.toUpperCase() })} />
            </div>
            <div className="space-y-1">
              <Label htmlFor="nr-name">{c.fieldRoleName}</Label>
              <Input id="nr-name" className="w-full" placeholder={c.phRoleName}
                value={newRole.name}
                onChange={(e) => setNewRole({ ...newRole, name: e.target.value })} />
            </div>
          </div>
        )}
      </Drawer>

      {/* 员工编辑：角色 + 数据域，一次保存 */}
      <Drawer
        open={!!editing}
        onOpenChange={(o) => !o && setEditing(null)}
        title={editing ? fill(c.staffDrawerTitle, { name: editing.name }) : ""}
        desc={editing ? `${editing.staffNo} · ${editing.username}` : undefined}
        footer={editing ? (
          <Button loading={saveStaffMut.isPending} onClick={() => saveStaffMut.mutate()}>{c.save}</Button>
        ) : null}
      >
        {editing && (
          <div className="space-y-4">
            <Field label={c.fieldRoles}>
              {/*
                多选而不是下拉：一个人可以同时是客服和风控，权限取并集。
                库早就支持（sys_role_member 唯一键含 role_code），是写接口压成了单值。
              */}
              <div className="flex flex-wrap gap-2">
                {(roles.data ?? []).map((r) => {
                  const on = rolesForm.includes(r.roleCode);
                  return (
                    <Button
                      key={r.roleCode}
                      size="sm"
                      variant={on ? "default" : "outline"}
                      onClick={() => setRolesForm(on
                        ? rolesForm.filter((x) => x !== r.roleCode)
                        : [...rolesForm, r.roleCode])}
                    >
                      {r.name}
                    </Button>
                  );
                })}
              </div>
              {rolesForm.length === 0 && (
                <p className="txt-caption text-[var(--warning)] mt-2">{c.roleNoneHint}</p>
              )}
            </Field>

            {rolesForm.some((r) => SCOPED_ROLES.includes(r as Role)) ? (
              <>
                <Notice>{c.scopeNotice}</Notice>
                <div className="space-y-1">
                  <Label htmlFor="sc-community">{c.fieldCommunityNo}</Label>
                  <Input id="sc-community" className="w-full" placeholder={c.phCommunity} value={scopeForm.communityNo}
                    onChange={(e) => setScopeForm({ ...scopeForm, communityNo: e.target.value })} />
                </div>
                <div className="space-y-1">
                  <Label htmlFor="sc-merchant">{c.fieldMerchantNo}</Label>
                  <Input id="sc-merchant" className="w-full" placeholder={c.phMerchant} value={scopeForm.merchantNo}
                    onChange={(e) => setScopeForm({ ...scopeForm, merchantNo: e.target.value })} />
                </div>
                <div className="space-y-1">
                  <Label htmlFor="sc-pickup">{c.fieldPickupNo}</Label>
                  <Input id="sc-pickup" className="w-full" placeholder={c.phPickup} value={scopeForm.pickupNo}
                    onChange={(e) => setScopeForm({ ...scopeForm, pickupNo: e.target.value })} />
                </div>
                <Field label={c.fieldHowItWorks}>{c.scopeHowHint}</Field>
              </>
            ) : (
              /* **不是留白**：留白会被读成「还没配」，而实际是「配了也不生效」 */
              <Field label={c.fieldScope}>{c.scopeAllHint}</Field>
            )}
          </div>
        )}
      </Drawer>

      {/* 新建员工 */}
      <Drawer
        open={!!newStaff}
        onOpenChange={(o) => !o && setNewStaff(null)}
        title={c.newStaffTitle}
        desc={c.newStaffDesc}
        footer={newStaff ? (
          <Button
            loading={createStaffMut.isPending}
            disabled={!newStaff.username.trim() || !newStaff.realName.trim() || newStaff.roles.length === 0}
            onClick={() => createStaffMut.mutate()}
          >{c.save}</Button>
        ) : null}
      >
        {newStaff && (
          <div className="space-y-4">
            <div className="space-y-1">
              <Label htmlFor="ns-username">{c.fieldUsername}</Label>
              <Input id="ns-username" className="w-full" placeholder={c.phUsername} value={newStaff.username}
                onChange={(e) => setNewStaff({ ...newStaff, username: e.target.value })} />
            </div>
            <div className="space-y-1">
              <Label htmlFor="ns-name">{c.fieldRealName}</Label>
              <Input id="ns-name" className="w-full" placeholder={c.phRealName} value={newStaff.realName}
                onChange={(e) => setNewStaff({ ...newStaff, realName: e.target.value })} />
            </div>
            <Field label={c.fieldRoles}>
              <div className="flex flex-wrap gap-2">
                {(roles.data ?? []).map((r) => {
                  const on = newStaff.roles.includes(r.roleCode);
                  return (
                    <Button key={r.roleCode} size="sm" variant={on ? "default" : "outline"}
                      onClick={() => setNewStaff({
                        ...newStaff,
                        roles: on ? newStaff.roles.filter((x) => x !== r.roleCode) : [...newStaff.roles, r.roleCode],
                      })}>
                      {r.name}
                    </Button>
                  );
                })}
              </div>
            </Field>
            <Notice>{c.newStaffPasswordNotice}</Notice>
          </div>
        )}
      </Drawer>

      {/* 一次性初始密码。**关掉就取不到了** */}
      <Drawer
        open={!!createdPassword}
        onOpenChange={(o) => !o && setCreatedPassword(null)}
        title={c.initialPasswordTitle}
        desc={createdPassword ? fill(c.initialPasswordDesc, { username: createdPassword.username }) : undefined}
        footer={<Button variant="outline" onClick={() => setCreatedPassword(null)}>{c.gotIt}</Button>}
      >
        {createdPassword && (
          <div className="space-y-4">
            <Notice tone="warning">{c.initialPasswordWarn}</Notice>
            <code className="block rounded-card bg-muted px-4 py-3 txt-body-strong tracking-widest">
              {createdPassword.password}
            </code>
          </div>
        )}
      </Drawer>

      {dialog}
    </div>
  );
}
