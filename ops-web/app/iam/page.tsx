"use client";

// 员工与权限（矩阵 P-1.1 + §2.3）。它是整个平台端权限的基准面。
//
// 敏感操作二次校验（P-1.1.5）**不单独成页**：它是动作上的一层 —— 停用管理员、
// 授予高危权限时要求手输确认，与资金域的分账下发同一套做法。
import { Suspense, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { useI18n } from "@/lib/i18n";
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
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
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
  const [pickedRole, setPickedRole] = useState<Role | null>(null);
  const [checked, setChecked] = useState<string[]>([]);

  const canGrant = allow("iam:role:grant");
  const canReadAudit = allow("iam:audit:read");

  const staffQ = { keyword, role, enabled, page, size };
  const staffs = useQuery({ queryKey: ["staffs", staffQ], queryFn: () => api.listStaffs(staffQ), enabled: tab === "staffs" });
  const roles = useQuery({ queryKey: ["roles"], queryFn: () => api.listRoles(), enabled: tab === "roles" });
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
  const setRoleMut = useMutation({
    mutationFn: (v: { staffNo: string; role: Role }) => api.setStaffRole(v.staffNo, v.role),
    onSuccess: () => { invalidate(); notify.success(c.toastRoleChanged); },
  });
  const setScopeMut = useMutation({
    mutationFn: () => api.setStaffScope(editing!.staffNo, scopeForm),
    onSuccess: () => { invalidate(); setEditing(null); notify.success(c.toastScopeChanged); },
  });
  const setPermsMut = useMutation({
    mutationFn: (v: { role: Role; perms: string[] }) => api.setRolePerms(v.role, v.perms),
    onSuccess: () => { invalidate(); notify.success(c.toastPermsSaved); },
  });

  /** 权限码 → 按导航模块分组的树。数据源是 nav，保证"能配的"与"界面上有的"一致。 */
  const permTree: TreeNode[] = useMemo(
    () =>
      NAV.filter((s) => (s.children ?? []).some((l) => l.perm)).map((s) => {
        const perms = [...new Set((s.children ?? []).map((l) => l.perm).filter(Boolean) as string[])];
        return {
          key: `m:${s.module}`,
          label: s.label,
          children: perms.map((p) => ({
            key: p,
            label: (
              <span className="flex items-center gap-2">
                <code className="txt-caption">{p}</code>
                {CRITICAL.includes(p) && <Badge tone="danger">{c.critical}</Badge>}
              </span>
            ),
          })),
        };
      }),
    [],
  );

  const openRole = (r: RoleDef) => {
    setPickedRole(r.role);
    // '*' 展开成具体码，否则树里一个都勾不上（超管是内置角色，本来也不可编辑）
    setChecked(r.perms.includes("*") ? permTree.flatMap((m) => (m.children ?? []).map((c) => c.key)) : r.perms);
  };

  const staffColumns: Column<Staff>[] = [
    { header: c.colStaffNo, cell: (s) => s.staffNo, numeric: true, align: "start" },
    { header: c.colName, cell: (s) => s.name },
    { header: c.colUsername, cell: (s) => s.username },
    {
      header: c.colRole,
      cell: (s) =>
        canGrant ? (
          <Select
            className="w-32" aria-label={fill(c.ariaRoleOf, { name: s.name })} value={s.role}
            onChange={async (e) => {
              const next = e.target.value as Role;
              if (next === "SUPER_ADMIN") {
                const ok = await confirm({
                  title: fill(c.confirmPromoteTitle, { name: s.name }),
                  desc: c.confirmPromoteDesc,
                  danger: true, confirmText: c.confirmPromoteOk, requireText: s.staffNo,
                });
                if (!ok) return;
              }
              setRoleMut.mutate({ staffNo: s.staffNo, role: next });
            }}
          >
            {roleOptions.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
          </Select>
        ) : roleLabel(s.role),
    },
    {
      header: c.colScope,
      // 全量角色不显示"—"而是显示"全量"：留白会被读成"还没配"
      cell: (s) => {
        if (!SCOPED_ROLES.includes(s.role)) return <span className="text-muted-foreground">{c.scopeAll}</span>;
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
            if (!v && s.role === "SUPER_ADMIN") {
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
      cell: (s) =>
        canGrant && SCOPED_ROLES.includes(s.role) ? (
          <Button size="sm" variant="outline"
            onClick={() => {
              setEditing(s);
              setScopeForm({ merchantNo: s.merchantNo ?? "", communityNo: s.communityNo ?? "", pickupNo: s.pickupNo ?? "" });
            }}>
            {c.actionScope}
          </Button>
        ) : <span className="text-muted-foreground">—</span>,
    },
  ];

  const roleColumns: Column<RoleDef>[] = [
    { header: c.colRoleLabel, cell: (r) => r.label },
    { header: c.colRoleCode, cell: (r) => <code className="txt-caption">{r.role}</code> },
    { header: c.colStaffCount, cell: (r) => r.staffCount, numeric: true },
    {
      header: c.colPermCount,
      numeric: true,
      cell: (r) => (r.perms.includes("*") ? <Badge tone="danger">{c.permAll}</Badge> : r.perms.length),
    },
    {
      header: c.colCriticalPerms,
      cell: (r) => {
        const hits = r.perms.includes("*") ? CRITICAL : r.perms.filter((p) => CRITICAL.includes(p));
        return hits.length ? <Badge tone="danger">{fill(c.criticalCount, { n: hits.length })}</Badge> : <span className="text-muted-foreground">{c.none}</span>;
      },
    },
    {
      header: c.colActions,
      cell: (r) =>
        r.builtin
          ? <span className="text-muted-foreground">{c.roleBuiltIn}</span>
          : canGrant
            ? <Button size="sm" variant="outline" onClick={() => openRole(r)}>{c.actionPerms}</Button>
            : <span className="text-muted-foreground">—</span>,
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
        <Notice className="mb-3">
          {c.notice}
        </Notice>
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
        <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_24rem]">
          <DataTable
            columns={roleColumns} rows={roles.data} loading={roles.isLoading}
            error={roles.error} onRetry={() => roles.refetch()}
            rowKey={(r) => r.role}
            empty={c.emptyRoles}
          />
          <Card>
            <CardHeader><CardTitle>{pickedRole ? fill(c.permCardTitle, { role: roleLabel(pickedRole) }) : c.permCardEmpty}</CardTitle></CardHeader>
            <CardContent>
              {!pickedRole ? (
                <p className="txt-body text-muted-foreground">{c.permCardHint}</p>
              ) : (
                <div className="space-y-3">
                  {/* 权限树用的就是 Tree 的 checkable 语义：值只认叶子（权限码），父节点三态 */}
                  <Tree
                    nodes={permTree}
                    empty={c.emptyPerms}
                    checkable
                    checkedKeys={checked}
                    onCheckedChange={setChecked}
                    collapseFrom={1}
                  />
                  <div className="flex items-center gap-2">
                    <Button
                      onClick={async () => {
                        const added = checked.filter((p) => CRITICAL.includes(p));
                        if (added.length) {
                          const ok = await confirm({
                            title: fill(c.confirmGrantTitle, { role: roleLabel(pickedRole) }),
                            desc: fill(c.confirmGrantDesc, { n: added.length, list: added.join("、") }),
                            danger: true, confirmText: c.confirmGrantOk, requireText: pickedRole,
                          });
                          if (!ok) return;
                        }
                        setPermsMut.mutate({ role: pickedRole, perms: checked });
                      }}
                    >
                      {c.save}
                    </Button>
                    <span className="txt-caption text-muted-foreground">{fill(c.selectedN, { n: checked.length })}</span>
                  </div>
                </div>
              )}
            </CardContent>
          </Card>
        </div>
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

      {/* 数据域授权 */}
      <Drawer
        open={!!editing}
        onOpenChange={(o) => !o && setEditing(null)}
        title={editing ? fill(c.scopeDrawerTitle, { name: editing.name }) : ""}
        desc={editing ? `${editing.staffNo} · ${roleLabel(editing.role)}` : undefined}
        footer={editing ? <Button loading={setScopeMut.isPending} onClick={() => setScopeMut.mutate()}>{c.save}</Button> : null}
      >
        {editing && (
          <div className="space-y-4">
            <Notice>
              {c.scopeNotice}
            </Notice>
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
            <Field label={c.fieldHowItWorks}>
              {c.scopeHowHint}
            </Field>
          </div>
        )}
      </Drawer>

      {dialog}
    </div>
  );
}
