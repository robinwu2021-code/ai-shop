"use client";

// 测试号固定验证码白名单（TDD-测试号固定验证码）—— **已接真后端** `/ops/test-phones/**`。
//
// 这一页与旁边几个 tab 有一处不同，值得写下来：**它管的不是配置，是钥匙**。
// 列表里的号请求验证码时不发真实短信，收到的码恒为这里配的值 —— 也就是说
// 能看这一页就等于知道那几个号的登录码，所以读与写各有一个独立权限码，
// 两个都不配给任何角色，只有超管的通配能到（见 Perms.SYSTEM_TESTPHONE_UPDATE）。
//
// 页面上刻意把「已有账号的号录不进来」单独摆一条说明（tpGuard）：
// 真正拦住「拿白名单登进别人的店」的是后端那条护栏，不是权限码。
// 不说的话，下一个人会以为这里只是个白名单，顺手把店主的号加进去试功能。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import type { OtpTestPhone } from "@/lib/types";
import { DataTable, type Column } from "@/components/ui/data-table";
import { HelpNote } from "@/components/ui/help-note";
import { Notice } from "@/components/ui/notice";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Drawer, DrawerSection, Field, FieldGrid } from "@/components/ui/drawer";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import type { SystemCopy } from "./copy";

const EMPTY = { phone: "", code: "", remark: "" };

export function TestPhoneTab({ c, canWrite }: { c: SystemCopy; canWrite: boolean }) {
  const qc = useQueryClient();
  const [current, setCurrent] = useState<OtpTestPhone | null>(null);
  const [creating, setCreating] = useState(false);
  const [form, setForm] = useState(EMPTY);

  const list = useQuery({ queryKey: ["test-phones"], queryFn: () => api.listTestPhones() });
  const invalidate = () => qc.invalidateQueries({ queryKey: ["test-phones"] });
  const close = () => { setCurrent(null); setCreating(false); };

  const save = useMutation({
    mutationFn: () => api.saveTestPhone({
      phone: form.phone.trim(),
      code: form.code.trim(),
      remark: form.remark.trim() || undefined,
    }),
    onSuccess: () => { invalidate(); close(); notify.success(c.tpSaved); },
  });

  const enabled = useMutation({
    mutationFn: (v: { id: number; enabled: boolean }) => api.setTestPhoneEnabled(v.id, v.enabled),
    onSuccess: () => { invalidate(); close(); notify.success(c.tpSaved); },
  });

  const remove = useMutation({
    mutationFn: (id: number) => api.removeTestPhone(id),
    onSuccess: () => { invalidate(); close(); notify.success(c.tpSaved); },
  });

  const open = (row: OtpTestPhone) => {
    setCurrent(row);
    setCreating(false);
    setForm({ phone: row.phone, code: row.code, remark: row.remark ?? "" });
  };

  const columns: Column<OtpTestPhone>[] = [
    { header: c.tpColPhone, cell: (r) => <code className="txt-caption">{r.phone}</code>, align: "start" },
    // 明文显示：不显示的话运营没法把它填进苹果审核资料，而那是这张表的全部意义
    { header: c.tpColCode, cell: (r) => <code className="txt-caption">{r.code}</code>, align: "start" },
    {
      header: c.tpColEnabled,
      cell: (r) => (r.enabled ? <Badge tone="warning">{c.tpEnabled}</Badge> : <Badge>{c.tpDisabled}</Badge>),
    },
    // 空 = 没写，不是「没有原因」—— 显示成「—」会让人以为这一列不用填
    {
      header: c.tpColRemark,
      cell: (r) => r.remark ?? <span className="text-muted-foreground">{c.tpNoRemark}</span>,
    },
    {
      header: c.tpColActions,
      cell: (r) => <Button size="sm" variant="outline" onClick={() => open(r)}>{c.tpEdit}</Button>,
    },
  ];

  return (
    <>
      <HelpNote className="mb-3">{c.tpNotice}</HelpNote>
      <Notice className="mb-3">{c.tpGuard}</Notice>

      {canWrite && (
        <div className="mb-3">
          <Button
            variant="outline"
            onClick={() => { setCreating(true); setCurrent(null); setForm(EMPTY); }}
          >
            {c.tpNew}
          </Button>
        </div>
      )}

      <DataTable
        columns={columns}
        rows={list.data}
        loading={list.isLoading}
        error={list.error}
        onRetry={() => list.refetch()}
        rowKey={(r) => String(r.id)}
        empty={c.tpEmpty}
      />

      <Drawer
        open={!!current || creating}
        onOpenChange={(o) => !o && close()}
        title={creating ? c.tpNew : (current?.phone ?? "")}
      >
        {(current || creating) && (
          <>
            {current && (
              <DrawerSection title={c.tpSectionNow}>
                <FieldGrid>
                  <Field label={c.tpColPhone}>{current.phone}</Field>
                  <Field label={c.tpColCode}>{current.code}</Field>
                  <Field label={c.tpColEnabled}>{current.enabled ? c.tpEnabled : c.tpDisabled}</Field>
                </FieldGrid>
              </DrawerSection>
            )}

            {canWrite && (
              <DrawerSection title={creating ? c.tpSectionNew : c.tpEdit}>
                <div className="space-y-1">
                  <Label htmlFor="tp-phone" required>{c.tpFieldPhone}</Label>
                  <Input
                    id="tp-phone" value={form.phone} disabled={!creating}
                    placeholder={c.tpFieldPhonePh}
                    onChange={(e) => setForm((p) => ({ ...p, phone: e.target.value }))}
                  />
                  {!creating && <p className="txt-caption text-muted-foreground">{c.tpPhoneLocked}</p>}
                </div>
                <div className="mt-3 space-y-1">
                  <Label htmlFor="tp-code" required>{c.tpFieldCode}</Label>
                  <Input
                    id="tp-code" value={form.code} placeholder={c.tpFieldCodePh}
                    onChange={(e) => setForm((p) => ({ ...p, code: e.target.value }))}
                  />
                </div>
                <div className="mt-3 space-y-1">
                  <Label htmlFor="tp-remark">{c.tpFieldRemark}</Label>
                  <Input
                    id="tp-remark" value={form.remark} placeholder={c.tpFieldRemarkPh}
                    onChange={(e) => setForm((p) => ({ ...p, remark: e.target.value }))}
                  />
                  {/* 理由放常驻说明不放 placeholder：placeholder 一打字就消失，
                      而最该看见「不写清楚半年后没人敢删」的那一刻正是在打字 */}
                  <p className="txt-caption text-muted-foreground">{c.tpRemarkWhy}</p>
                </div>

                <div className="mt-4">
                  <Button onClick={() => save.mutate()} disabled={save.isPending}>{c.tpSave}</Button>
                </div>

                {current && (
                  <>
                    <div className="mt-6 flex gap-2">
                      <Button
                        variant="outline"
                        onClick={() => enabled.mutate({ id: current.id, enabled: !current.enabled })}
                      >
                        {current.enabled ? c.tpDisableAct : c.tpEnableAct}
                      </Button>
                      <Button
                        variant="outline"
                        onClick={() => {
                          if (confirm(c.tpRemoveConfirm)) remove.mutate(current.id);
                        }}
                      >
                        {c.tpRemoveAct}
                      </Button>
                    </div>
                    <Notice className="mt-3">{c.tpEnabledNote}</Notice>
                  </>
                )}
              </DrawerSection>
            )}
          </>
        )}
      </Drawer>
    </>
  );
}
