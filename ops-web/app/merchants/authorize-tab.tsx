"use client";

// 类目授权（P-11.1.3）与认证标管理（P-11.1.2）。
//
// 两块都是「平台给商家放行某样东西」，所以放在一个文件里，但**是两个 tab**：
// 类目授权决定"能卖什么"，认证标决定"用户看到什么背书" —— 混成一页会让人
// 以为拿了标就等于扩了类目。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { fill } from "@/lib/use-copy";
import { MAX_MERCHANT_BREACH } from "@/lib/constants";
import type { Merchant } from "@/lib/types";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, DrawerSection, Field, FieldGrid } from "@/components/ui/drawer";
import { Pagination } from "@/components/ui/misc";
import { Toolbar } from "@/components/ui/toolbar";
import { Notice } from "@/components/ui/notice";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Textarea } from "@/components/ui/textarea";
import { useConfirm } from "@/components/ui/confirm-dialog";
import type { MerchantsCopy } from "./copy";

/** 两个 tab 共用：只有过审商家才谈得上授权与认证标。 */
function useApprovedMerchants(keyword: string, page: number, size: number) {
  const q = { keyword, status: "APPROVED", page, size };
  return useQuery({ queryKey: ["merchants", q], queryFn: () => api.listMerchants(q) });
}

export function CategoryTab({ c, canGrant }: { c: MerchantsCopy; canGrant: boolean }) {
  const qc = useQueryClient();
  const [keyword, setKeyword] = useState("");
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);
  const [current, setCurrent] = useState<Merchant | null>(null);
  const [codes, setCodes] = useState<string[]>([]);
  const [reason, setReason] = useState("");

  const list = useApprovedMerchants(keyword, page, size);
  const authCodes = useQuery({ queryKey: ["auth-codes"], queryFn: () => api.listAuthCodes() });

  const save = useMutation({
    mutationFn: () => api.setMerchantAuthCodes({ merchantNo: current!.merchantNo, codes, reason }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["merchants"] });
      setCurrent(null);
      notify.success(c.toastAuthSaved);
    },
  });

  const open = (m: Merchant) => { setCurrent(m); setCodes([...m.categoryCodes]); setReason(""); };
  const nameOf = (code: string) => authCodes.data?.find((a) => a.code === code)?.name ?? code;

  const columns: Column<Merchant>[] = [
    { header: c.colNo, cell: (m) => m.merchantNo, numeric: true, align: "start" },
    { header: c.colName, cell: (m) => m.name },
    { header: c.colCommunity, cell: (m) => m.communityNos.join("、") },
    {
      header: c.colAuthCodes,
      cell: (m) => (
        <div className="flex flex-wrap gap-1">
          {m.categoryCodes.map((code) => <Badge key={code} tone="info">{nameOf(code)}</Badge>)}
        </div>
      ),
    },
    {
      header: c.colQualifications,
      cell: (m) =>
        m.qualifications?.length
          ? m.qualifications.join("、")
          : <span className="text-muted-foreground">{c.noQualification}</span>,
    },
    {
      header: c.colActions,
      cell: (m) => (
        <Button size="sm" variant="outline" disabled={!canGrant} onClick={() => open(m)}>{c.actionGrant}</Button>
      ),
    },
  ];

  return (
    <>
      <Notice className="mb-3">{c.categoryNotice}</Notice>
      <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder={c.searchPlaceholder} />
      <DataTable
        columns={columns} rows={list.data?.records} loading={list.isLoading}
        error={list.error} onRetry={() => list.refetch()}
        rowKey={(m) => m.merchantNo}
        empty={c.emptyApproved}
      />
      <Pagination page={page} size={size} onSize={setSize} total={list.data?.total ?? 0} onPage={setPage} />

      <Drawer
        open={!!current}
        onOpenChange={(o) => !o && setCurrent(null)}
        title={current ? fill(c.grantTitle, { name: current.name }) : ""}
        width="w-[520px]"
        footer={canGrant ? <Button loading={save.isPending} onClick={() => save.mutate()}>{c.btnSaveAuth}</Button> : null}
      >
        {current && (
          <div>
            <DrawerSection first title={c.secQualification}>
              <p className="txt-body">
                {current.qualifications?.length ? current.qualifications.join("、") : c.noQualification}
              </p>
              <p className="mt-1 txt-caption text-muted-foreground">{c.qualificationHint}</p>
            </DrawerSection>

            <DrawerSection title={c.secAuthCodes}>
              <div className="space-y-2">
                {authCodes.data?.map((a) => {
                  // 缺资质的直接禁掉：勾上再保存报错，等于让人白点一次。
                  // `?? []` 不是防御性冗余：后端此前不下发 qualifications，
                  // 少了它这一行会在真接口下抛 TypeError（见 types/merchant.ts 的注）。
                  const blocked =
                    !!a.requiredQualification &&
                    !(current.qualifications ?? []).includes(a.requiredQualification);
                  return (
                    <label key={a.code} className="flex items-start gap-2">
                      <Checkbox
                        checked={codes.includes(a.code)}
                        disabled={!canGrant || blocked}
                        onChange={(v) => setCodes((p) => (v ? [...p, a.code] : p.filter((x) => x !== a.code)))}
                      />
                      <span>
                        <span className="txt-body">{a.name}</span>
                        {a.requiredQualification && (
                          <span className="ml-2 txt-caption text-muted-foreground">
                            {blocked
                              ? fill(c.needQualificationMissing, { q: a.requiredQualification })
                              : fill(c.needQualification, { q: a.requiredQualification })}
                          </span>
                        )}
                      </span>
                    </label>
                  );
                })}
              </div>
              <p className="mt-3 txt-caption text-muted-foreground">{c.authCodesHint}</p>
            </DrawerSection>

            <DrawerSection title={c.secAuthReason}>
              <Field className="mb-0" label={c.fieldReason}>
                <Textarea value={reason} onChange={setReason} placeholder={c.authReasonPlaceholder} rows={2} />
              </Field>
            </DrawerSection>
          </div>
        )}
      </Drawer>
    </>
  );
}

export function VerifyTab({ c, canGrant }: { c: MerchantsCopy; canGrant: boolean }) {
  const qc = useQueryClient();
  const { confirm, dialog } = useConfirm();
  const [keyword, setKeyword] = useState("");
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);

  const list = useApprovedMerchants(keyword, page, size);
  const setVerified = useMutation({
    mutationFn: (v: { merchantNo: string; verified: boolean }) => api.setMerchantVerified(v.merchantNo, v.verified),
    onSuccess: (m) => {
      qc.invalidateQueries({ queryKey: ["merchants"] });
      notify.success(m.verified ? c.toastVerifyGranted : c.toastVerifyRevoked);
    },
  });

  const columns: Column<Merchant>[] = [
    { header: c.colNo, cell: (m) => m.merchantNo, numeric: true, align: "start" },
    { header: c.colName, cell: (m) => m.name },
    { header: c.colCommunity, cell: (m) => m.communityNos.join("、") },
    {
      header: c.colVerified,
      cell: (m) => (m.verified ? <Badge tone="success">{c.badgeVerified}</Badge> : <span className="text-muted-foreground">{c.badgeUnverified}</span>),
    },
    {
      header: c.colBreach,
      // 毁约次数达上限时标红：它是"这家能不能拿标"的唯一硬条件
      cell: (m) =>
        m.breachCount >= MAX_MERCHANT_BREACH
          ? <Badge tone="danger">{fill(c.breachTimes, { n: m.breachCount })}</Badge>
          : fill(c.breachTimes, { n: m.breachCount }),
      numeric: true,
    },
    {
      header: c.colActions,
      cell: (m) => {
        // 达上限的不给「授予」按钮：mock 层也会拒，但摆一个必然报错的按钮是在骗人点一次
        const blocked = !m.verified && m.breachCount >= MAX_MERCHANT_BREACH;
        return (
          <Button size="sm" variant="outline" disabled={!canGrant || blocked}
            onClick={async () => {
              const ok = await confirm({
                title: m.verified ? c.confirmRevokeTitle : c.confirmGrantTitle,
                desc: m.verified ? fill(c.confirmRevokeDesc, { name: m.name }) : fill(c.confirmGrantDesc, { name: m.name }),
                danger: m.verified,
              });
              if (ok) setVerified.mutate({ merchantNo: m.merchantNo, verified: !m.verified });
            }}>
            {m.verified ? c.actionRevokeVerify : c.actionGrantVerify}
          </Button>
        );
      },
    },
  ];

  return (
    <>
      <Notice className="mb-3">{fill(c.verifyNotice, { n: MAX_MERCHANT_BREACH })}</Notice>
      <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder={c.searchPlaceholder} />
      <DataTable
        columns={columns} rows={list.data?.records} loading={list.isLoading}
        error={list.error} onRetry={() => list.refetch()}
        rowKey={(m) => m.merchantNo}
        empty={c.emptyApproved}
      />
      <Pagination page={page} size={size} onSize={setSize} total={list.data?.total ?? 0} onPage={setPage} />
      {dialog}
    </>
  );
}

/** 两个 tab 都要用的「商家概况」块，抽出来免得两处各写各的字段顺序。 */
export function MerchantBrief({ c, m }: { c: MerchantsCopy; m: Merchant }) {
  return (
    <FieldGrid>
      <Field className="mb-3" label={c.colNo}>{m.merchantNo}</Field>
      <Field className="mb-3" label={c.colCommunity}>{m.communityNos.join("、")}</Field>
      <Field className="mb-3" label={c.colVerified}>{m.verified ? c.badgeVerified : c.badgeUnverified}</Field>
      <Field className="mb-3" label={c.colBreach}>{fill(c.breachTimes, { n: m.breachCount })}</Field>
    </FieldGrid>
  );
}
