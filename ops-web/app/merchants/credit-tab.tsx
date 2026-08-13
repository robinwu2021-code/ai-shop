"use client";

// 信用档案（P-11.1.5）与违规处置·封禁（P-11.1.4）。
//
// 两块同一个文件、两个 tab：档案是**只读的判断依据**，处置是**动作**。
// 合成一页的话，翻档案的人会顺手点处置，而这两件事本该由不同的人在不同的时点做。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { fill } from "@/lib/use-copy";
import { fmtTime } from "@/lib/utils";
import { MAX_MERCHANT_BREACH } from "@/lib/constants";
import { MERCHANT_TRANSITIONS } from "@/lib/types";
import type { Merchant, Violation, ViolationAction, ViolationType } from "@/lib/types";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, DrawerSection, Field } from "@/components/ui/drawer";
import { FilterSelect } from "@/components/ui/filter-select";
import { Pagination } from "@/components/ui/misc";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { Toolbar } from "@/components/ui/toolbar";
import { Notice } from "@/components/ui/notice";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Select } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { MerchantBrief } from "./authorize-tab";
import type { MerchantsCopy } from "./copy";

const useTypeMap = (c: MerchantsCopy): StatusMap<ViolationType> => ({
  FAKE_GOODS: { label: c.vtFake, tone: "danger" },
  BREACH: { label: c.vtBreach, tone: "danger" },
  PRICE_FRAUD: { label: c.vtPrice, tone: "warning" },
  SERVICE: { label: c.vtService, tone: "warning" },
});

const useActionMap = (c: MerchantsCopy): StatusMap<ViolationAction> => ({
  WARN: { label: c.vaWarn, tone: "muted" },
  LIMIT: { label: c.vaLimit, tone: "warning" },
  SUSPEND: { label: c.vaSuspend, tone: "danger" },
});

/** 信用档案：只读。它是处置的依据，不是处置本身。 */
export function CreditTab({ c }: { c: MerchantsCopy }) {
  const typeMap = useTypeMap(c);
  const actionMap = useActionMap(c);
  const [keyword, setKeyword] = useState("");
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);
  const [current, setCurrent] = useState<Merchant | null>(null);

  const q = { keyword, page, size };
  const list = useQuery({ queryKey: ["merchants", q], queryFn: () => api.listMerchants(q) });
  const history = useQuery({
    queryKey: ["violations", current?.merchantNo],
    queryFn: () => api.listViolations({ merchantNo: current!.merchantNo }),
    enabled: !!current,
  });

  const columns: Column<Merchant>[] = [
    { header: c.colNo, cell: (m) => m.merchantNo, numeric: true, align: "start" },
    { header: c.colName, cell: (m) => m.name },
    { header: c.colCommunity, cell: (m) => m.communityNos.join("、") },
    {
      header: c.colBreach,
      cell: (m) =>
        m.breachCount >= MAX_MERCHANT_BREACH
          ? <Badge tone="danger">{fill(c.breachTimes, { n: m.breachCount })}</Badge>
          : fill(c.breachTimes, { n: m.breachCount }),
      numeric: true,
    },
    {
      /*
       * 责任归属：**这一列决定了「毁约次数」旁边那些分该不该算在他头上**。
       * 归集路径下平台是销售主体，服务与时效是平台在做 ——
       * 拿它考核供应商是拿他控制不了的事罚他。
       * 而分照常展示给消费者：那是他们的真实体验，藏起来的后果是没人为它负责。
       */
      header: c.colBorne,
      cell: (m) =>
        m.fundsMode === "AGGREGATED"
          ? <Badge tone="warning">{c.bornePlatform}</Badge>
          : <span className="text-muted-foreground">{c.borneMerchant}</span>,
    },
    {
      header: c.colVerified,
      cell: (m) => (m.verified ? <Badge tone="success">{c.badgeVerified}</Badge> : <span className="text-muted-foreground">{c.badgeUnverified}</span>),
    },
    {
      header: c.colSettleReady,
      cell: (m) => (m.settleAccountReady ? c.settleReady : <Badge tone="warning">{c.settleNotReady}</Badge>),
    },
    {
      header: c.colActions,
      cell: (m) => <Button size="sm" variant="outline" onClick={() => setCurrent(m)}>{c.actionCredit}</Button>,
    },
  ];

  return (
    <>
      <Notice className="mb-3">{fill(c.creditNotice, { n: MAX_MERCHANT_BREACH })}</Notice>
      <Notice className="mb-3">{c.borneNotice}</Notice>
      <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder={c.searchPlaceholder} />
      <DataTable
        columns={columns} rows={list.data?.records} loading={list.isLoading}
        error={list.error} onRetry={() => list.refetch()}
        rowKey={(m) => m.merchantNo}
        empty={c.empty}
      />
      <Pagination page={page} size={size} onSize={setSize} total={list.data?.total ?? 0} onPage={setPage} />

      <Drawer
        open={!!current}
        onOpenChange={(o) => !o && setCurrent(null)}
        title={current ? fill(c.creditTitle, { name: current.name }) : ""}
        width="w-[520px]"
      >
        {current && (
          <div>
            <DrawerSection first title={c.secBrief}>
              <MerchantBrief c={c} m={current} />
            </DrawerSection>
            <DrawerSection title={c.secViolations}>
              {history.isLoading && <p className="txt-caption text-muted-foreground">{c.loading}</p>}
              {history.data?.length === 0 && <p className="txt-caption text-muted-foreground">{c.noViolation}</p>}
              <ul className="space-y-3">
                {history.data?.map((v) => (
                  <li key={v.violationNo} className="border-l-2 border-border pl-3">
                    <div className="flex items-center gap-2">
                      <StatusBadge map={typeMap} value={v.type} />
                      <StatusBadge map={actionMap} value={v.action} />
                    </div>
                    <p className="mt-1 txt-body">{v.detail}</p>
                    <p className="txt-caption text-muted-foreground">{fmtTime(v.at)} · {v.operator}</p>
                  </li>
                ))}
              </ul>
            </DrawerSection>
          </div>
        )}
      </Drawer>
    </>
  );
}

/** 违规处置与封禁：动作在这里，依据在信用档案那一页。 */
export function BanTab({ c, canBan }: { c: MerchantsCopy; canBan: boolean }) {
  const qc = useQueryClient();
  const typeMap = useTypeMap(c);
  const actionMap = useActionMap(c);
  const [keyword, setKeyword] = useState("");
  const [type, setType] = useState("");
  const [target, setTarget] = useState<Merchant | null>(null);
  const [form, setForm] = useState<{ type: ViolationType; action: ViolationAction; detail: string }>({
    type: "BREACH", action: "WARN", detail: "",
  });

  const violations = useQuery({ queryKey: ["violations", "all"], queryFn: () => api.listViolations() });
  const merchants = useQuery({
    queryKey: ["merchants", "ban", keyword],
    queryFn: () => api.listMerchants({ keyword, size: 100 }),
  });

  const record = useMutation({
    mutationFn: () => api.recordViolation({ merchantNo: target!.merchantNo, ...form }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["violations"] });
      qc.invalidateQueries({ queryKey: ["merchants"] });
      setTarget(null);
      notify.success(c.toastViolationRecorded);
    },
  });

  const unban = useMutation({
    mutationFn: (m: Merchant) => // 解封 = 回到可经营（ACTIVE），不是「审核通过」
      api.setMerchantStatus(m.merchantNo, "ACTIVE", c.unbanRemark),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["merchants"] });
      notify.success(c.toastUnbanned);
    },
  });

  const rows = (violations.data ?? []).filter((v) => !type || v.type === type);

  const columns: Column<Violation>[] = [
    { header: c.colViolationNo, cell: (v) => v.violationNo, numeric: true, align: "start" },
    { header: c.colName, cell: (v) => v.merchantName },
    { header: c.colViolationType, cell: (v) => <StatusBadge map={typeMap} value={v.type} /> },
    { header: c.colViolationAction, cell: (v) => <StatusBadge map={actionMap} value={v.action} /> },
    { header: c.colDetail, cell: (v) => v.detail },
    { header: c.colAt, cell: (v) => fmtTime(v.at) },
    { header: c.colOperator, cell: (v) => v.operator },
  ];

  const banned = (merchants.data?.records ?? []).filter((m) => m.status === "SUSPENDED");

  return (
    <>
      <Notice className="mb-3">{c.banNotice}</Notice>

      <Toolbar search={keyword} onSearch={setKeyword} searchPlaceholder={c.searchPlaceholder}>
        <FilterSelect aria-label={c.filterViolationType} value={type} onChange={setType}
          options={typeMap} allLabel={c.filterViolationTypeAll} />
      </Toolbar>

      {/* 先选商家再记违规：从「商家」出发而不是从「新建一条记录」出发，
          是因为处置的第一步永远是确认对象，而不是填表 */}
      {canBan && (
        <div className="mb-4 flex flex-wrap gap-2">
          {(merchants.data?.records ?? []).slice(0, 8).map((m) => (
            <Button key={m.merchantNo} size="sm" variant="outline"
              onClick={() => { setTarget(m); setForm({ type: "BREACH", action: "WARN", detail: "" }); }}>
              {fill(c.btnRecordFor, { name: m.name })}
            </Button>
          ))}
        </div>
      )}

      {banned.length > 0 && (
        <div className="mb-4 space-y-2">
          <p className="txt-label text-muted-foreground">{c.secBanned}</p>
          <div className="flex flex-wrap gap-2">
            {banned.map((m) => (
              <div key={m.merchantNo} className="flex items-center gap-2 rounded-[var(--r-chip)] bg-muted px-3 py-1">
                <span className="txt-body">{m.name}</span>
                <Button size="sm" variant="ghost" disabled={!canBan} onClick={() => unban.mutate(m)}>{c.actionUnban}</Button>
              </div>
            ))}
          </div>
        </div>
      )}

      <DataTable
        columns={columns} rows={rows} loading={violations.isLoading}
        error={violations.error} onRetry={() => violations.refetch()}
        rowKey={(v) => v.violationNo}
        empty={c.emptyViolation}
      />

      <Drawer
        open={!!target}
        onOpenChange={(o) => !o && setTarget(null)}
        title={target ? fill(c.recordTitle, { name: target.name }) : ""}
        width="w-[520px]"
        footer={<Button loading={record.isPending} onClick={() => record.mutate()}>{c.btnRecordViolation}</Button>}
      >
        {target && (
          <div>
            <DrawerSection first title={c.secBrief}>
              <MerchantBrief c={c} m={target} />
            </DrawerSection>
            <DrawerSection title={c.secViolationForm}>
              <div className="mb-3 space-y-1">
                <Label htmlFor="vl-type" required>{c.colViolationType}</Label>
                <Select id="vl-type" className="w-full" value={form.type}
                  onChange={(e) => setForm((p) => ({ ...p, type: e.target.value as ViolationType }))}>
                  {Object.entries(typeMap).map(([k, v]) => <option key={k} value={k}>{v.label}</option>)}
                </Select>
                <p className="txt-caption text-muted-foreground">{fill(c.typeHint, { n: MAX_MERCHANT_BREACH })}</p>
              </div>
              <div className="mb-3 space-y-1">
                <Label htmlFor="vl-action" required>{c.colViolationAction}</Label>
                <Select id="vl-action" className="w-full" value={form.action}
                  onChange={(e) => setForm((p) => ({ ...p, action: e.target.value as ViolationAction }))}>
                  {Object.entries(actionMap).map(([k, v]) => (
                    // 封禁只在状态机允许时可选：已封禁的再封会抛错，选项摆出来是在骗人点一次
                    <option key={k} value={k}
                      disabled={k === "SUSPEND" && !MERCHANT_TRANSITIONS[target.status].includes("SUSPENDED")}>
                      {v.label}
                    </option>
                  ))}
                </Select>
              </div>
              <Field className="mb-0" label={c.colDetail}>
                <Textarea value={form.detail} onChange={(v) => setForm((p) => ({ ...p, detail: v }))}
                  placeholder={c.detailPlaceholder} rows={3} />
              </Field>
              <p className="mt-1 txt-caption text-muted-foreground">{c.detailHint}</p>
            </DrawerSection>
          </div>
        )}
      </Drawer>
    </>
  );
}
