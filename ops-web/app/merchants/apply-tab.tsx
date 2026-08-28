"use client";

// 入驻审核（P-11.1.1）—— **已接真后端** `/ops/merchant/apply/**`。
//
// 与本页其余 tab 的关键差别：它操作的是**申请单**，不是商家。
// 通过之前商家还不存在，所以动作打在 applyNo 上。
// 曾经把审核建模成「商家的一个状态」，于是驳回的申请会在商家表里留下一行
// 从没开过张的僵尸商家，出现在每一处按主体聚合的地方（结算、积分、报表）。
//
// 这一页要解决的实际问题：**服务范围没人能填**。
// 商家申请时允许留空（ADR-009），本该由运营在通过时补 —— 而运营侧此前没有这个入口。
// 结果是商家通过审核、上完架，却对谁都不可见，且没有任何报错。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useCodeLabel } from "@/lib/api/master-data";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { fmtTime } from "@/lib/utils";
import { fill } from "@/lib/use-copy";
import type { ApplyStatus, MerchantApply } from "@/lib/types";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, DrawerSection, Field, FieldGrid } from "@/components/ui/drawer";
import { FilterSelect } from "@/components/ui/filter-select";
import { Pagination } from "@/components/ui/misc";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { Toolbar } from "@/components/ui/toolbar";
import { Notice } from "@/components/ui/notice";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { CheckboxField } from "@/components/ui/checkbox";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import type { MerchantsCopy } from "./copy";

const STATUS_MAP: StatusMap<ApplyStatus> = {
  PENDING: { label: "待审核", tone: "warning" },
  REVIEWING: { label: "审核中", tone: "info" },
  APPROVED: { label: "已通过", tone: "success" },
  REJECTED: { label: "已驳回", tone: "danger" },
};

/** 服务范围的展示名。此前这一列直接显示 `COMMUNITY` —— 与行业/主体那两列同一个毛病 */
function scopeLabel(c: MerchantsCopy, scope?: string | null): string {
  if (scope === "CITY") return c.applyScopeCity;
  if (scope === "PLATFORM") return c.applyScopePlatform;
  return c.applyScopeCommunity;
}

/** 文案表是扁平的 Record<string,string>，枚举 → 文案的映射在组件里拼 */
const qualTypeLabel = (c: MerchantsCopy): Record<string, string> => ({
  BUSINESS_LICENSE: c.qualBUSINESS_LICENSE,
  FOOD_PERMIT: c.qualFOOD_PERMIT,
  FOOD_WORKSHOP: c.qualFOOD_WORKSHOP,
  OTHER: c.qualOTHER,
});

export function ApplyTab({ c, canAudit }: { c: MerchantsCopy; canAudit: boolean }) {
  const qc = useQueryClient();
  // 行业/主体的展示名来自后端主数据，不在这里再维护一份翻译
  const label = useCodeLabel();
  const [keyword, setKeyword] = useState("");
  const [status, setStatus] = useState("");
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);

  const [current, setCurrent] = useState<MerchantApply | null>(null);
  const [reason, setReason] = useState("");
  const [scope, setScope] = useState("COMMUNITY");
  const [communityNos, setCommunityNos] = useState<string[]>([]);
  /** 通过时授予的经营类目码。与通过同一次提交 —— 分两步会留下「批了但没授码」的店 */
  const [grantCodes, setGrantCodes] = useState<string[]>([]);

  const q = { keyword, status, page, size };
  const list = useQuery({ queryKey: ["applies", q], queryFn: () => api.listApplies(q) });

  // 社区目录：通过时要从这里挑。只取启用中的 —— 归档的小区不该还能被指派
  const communities = useQuery({
    queryKey: ["communities", "for-audit"],
    queryFn: () => api.listCommunities({ page: 1, size: 200 }),
  });

  // 授权码字典：通过时在同一屏里勾。只有启用的会下发
  const authCodes = useQuery({ queryKey: ["auth-codes"], queryFn: () => api.listAuthCodes() });

  const invalidate = () => qc.invalidateQueries({ queryKey: ["applies"] });

  const accept = useMutation({
    mutationFn: (applyNo: string) => api.acceptApply(applyNo),
    onSuccess: () => { invalidate(); notify.success(c.toastAccepted); },
  });

  const audit = useMutation({
    mutationFn: (v: { approved: boolean }) =>
      api.auditApply(current!.applyNo, v.approved, reason, scope, communityNos, grantCodes),
    onSuccess: (_, v) => {
      invalidate();
      setCurrent(null);
      notify.success(v.approved ? c.toastApproved : c.toastRejected);
    },
  });

  function open(a: MerchantApply) {
    setCurrent(a);
    setReason("");
    setScope(a.serviceScope || "COMMUNITY");
    setCommunityNos([...(a.communityNos ?? [])]);
    // 上一轮审核已经定过就沿用，别让运营重勾一遍 —— 重勾只会多出「这次忘了勾」
    setGrantCodes([...(a.categoryCodes ?? [])]);
  }

  function toggleCommunity(no: string) {
    setCommunityNos((prev) => (prev.includes(no) ? prev.filter((x) => x !== no) : [...prev, no]));
  }

  /**
   * 「仅本社区」却一个都没选 = 通过之后对谁都不可见。
   * 后端也会拒，这里先拦是为了给出人话原因，而不是一个 400。
   */
  const scopeReady = scope !== "COMMUNITY" || communityNos.length > 0;
  const decided = current?.status === "APPROVED" || current?.status === "REJECTED";

  const columns: Column<MerchantApply>[] = [
    { header: c.colNo, cell: (a) => a.applyNo, numeric: true, align: "start" },
    { header: c.colName, cell: (a) => a.name },
    { header: c.applyColIndustry, cell: (a) => label.industry(a.industry) },
    { header: c.applyColSubject, cell: (a) => label.subject(a.subject) },
    { header: c.colContact, cell: (a) => `${a.contactName} ${a.contactPhone}` },
    {
      // 服务范围空着的要一眼看出来 —— 它是「通过之后没人看得见」的唯一预警
      header: c.applyColScope,
      cell: (a) =>
        a.communityNos?.length ? (
          // 服务范围是 ADR-009 定的**固定三档**，不是运营可维护的字典 ——
          // 所以用文案表而不是 master-data。判据：取值域会不会被运营改
          `${scopeLabel(c, a.serviceScope)} · ${a.communityNos.length}`
        ) : (
          <span className="text-[var(--warning)]">{c.applyScopeEmpty}</span>
        ),
    },
    { header: c.colStatus, cell: (a) => <StatusBadge value={a.status} map={STATUS_MAP} /> },
    { header: c.colCreatedAt, cell: (a) => fmtTime(new Date(a.createdAt).toISOString()) },
    {
      header: c.colActions,
      cell: (a) => (
        <div className="flex gap-2">
          {canAudit && a.status === "PENDING" && (
            <Button size="sm" variant="outline" onClick={() => accept.mutate(a.applyNo)}>
              {c.applyActionAccept}
            </Button>
          )}
          <Button size="sm" variant="outline" onClick={() => open(a)}>
            {c.actionView}
          </Button>
        </div>
      ),
    },
  ];

  return (
    <>
      <Toolbar
        search={keyword}
        onSearch={(v) => { setKeyword(v); setPage(1); }}
        searchPlaceholder={c.applySearchPh}
      >
        <FilterSelect
          value={status}
          onChange={(v) => { setStatus(v); setPage(1); }}
          options={[
            { value: "", label: c.applyFilterTodo },
            { value: "PENDING", label: STATUS_MAP.PENDING.label },
            { value: "REVIEWING", label: STATUS_MAP.REVIEWING.label },
            { value: "APPROVED", label: STATUS_MAP.APPROVED.label },
            { value: "REJECTED", label: STATUS_MAP.REJECTED.label },
          ]}
        />
      </Toolbar>

      <DataTable
        columns={columns}
        rows={list.data?.records ?? []}
        loading={list.isPending}
        rowKey={(a) => a.applyNo}
      />
      <Pagination
        page={page}
        size={size}
        total={list.data?.total ?? 0}
        onPage={setPage}
        onSize={(v) => { setSize(v); setPage(1); }}
      />

      <Drawer open={!!current} onOpenChange={(o) => !o && setCurrent(null)} title={current?.name ?? ""}>
        {current && (
          <>
            <DrawerSection title={c.applySectionInfo}>
              <FieldGrid>
                <Field label={c.colNo}>{current.applyNo}</Field>
                <Field label={c.applyColSubject}>{label.subject(current.subject)}</Field>
                <Field label={c.applyColIndustry}>{label.industry(current.industry)}</Field>
                <Field label={c.colContact}>{`${current.contactName} ${current.contactPhone}`}</Field>
                <Field label={c.applyColCategory}>{current.category}</Field>
                <Field label={c.colStatus}>
                  <StatusBadge value={current.status} map={STATUS_MAP} />
                </Field>
              </FieldGrid>
              <p className="mt-2 text-sm text-[var(--muted)]">{current.desc}</p>
              {current.asPickupPoint && <Badge className="mt-2">{c.applyAsPickup}</Badge>}
              {current.rejectReason && (
                <Notice tone="danger" className="mt-3">{current.rejectReason}</Notice>
              )}
            </DrawerSection>

            {/*
              资质：需要执照的档位必传，免执照档位没有。缺它正是驳回的主因。

              **结构化的那份优先展示** —— 只列文件名的话，审核员看不出
              「这是执照还是食品证」「什么时候过期」，而通过之后转存进
              mch_qualification 的正是这一份，类型与有效期都来自它。
            */}
            {!!current.qualificationItems?.length && (
              <DrawerSection title={c.applySectionLicenses}>
                <div className="grid grid-cols-2 gap-3">
                  {current.qualificationItems.map((q, i) => (
                    <div key={i} className="rounded-lg border p-2">
                      <div className="flex items-center justify-between">
                        <Badge tone="info">{qualTypeLabel(c)[q.type] ?? q.type}</Badge>
                        <span className="txt-caption text-muted-foreground">
                          {q.expireAt ? fmtTime(q.expireAt) : c.applyQualForever}
                        </span>
                      </div>
                      <div className="mt-1 txt-caption tabular-nums">{q.code || "—"}</div>
                      {q.imageUrl && (
                        <a className="focus-ring" href={q.imageUrl} target="_blank" rel="noreferrer">
                          {/* eslint-disable-next-line @next/next/no-img-element */}
                          <img src={q.imageUrl} alt={q.type} className="mt-2 w-full rounded" />
                        </a>
                      )}
                    </div>
                  ))}
                </div>
              </DrawerSection>
            )}

            {/*
              旧字段：只有图片 URL。**单独一块并标注「非结构化」** ——
              与上面那份混在一起，审核员会以为这些也带类型与有效期。
            */}
            {!!current.licenses?.length && (
              <DrawerSection title={c.applySectionRawFiles}>
                <p className="txt-caption text-muted-foreground">{c.applyRawFilesHint}</p>
                <div className="mt-1 flex flex-wrap gap-2">
                  {current.licenses.map((url) => (
                    <a key={url} href={url} target="_blank" rel="noreferrer" className="focus-ring text-sm underline">
                      {url.split("/").pop()}
                    </a>
                  ))}
                </div>
              </DrawerSection>
            )}

            {canAudit && !decided && (
              <DrawerSection title={c.applySectionDecide}>
                {/*
                  服务范围放在审核动作**上面**：它是通过的前置条件，不是附属选项。
                  放下面的话运营会先点通过再发现被拦，而拦截原因在视线之外。
                */}
                <Notice tone="info" className="mb-3">{c.applyScopeNotice}</Notice>

                <Label>{c.applyScopeLabel}</Label>
                <FilterSelect
                  value={scope}
                  onChange={setScope}
                  options={[
                    { value: "COMMUNITY", label: c.applyScopeCommunity },
                    { value: "CITY", label: c.applyScopeCity },
                    { value: "PLATFORM", label: c.applyScopePlatform },
                  ]}
                />

                {scope === "COMMUNITY" && (
                  <div className="mt-3">
                    <Label>{c.applyCommunities}</Label>
                    <div className="mt-2 flex flex-wrap gap-3">
                      {(communities.data?.records ?? []).map((cm) => (
                        <CheckboxField
                          key={cm.communityNo}
                          checked={communityNos.includes(cm.communityNo)}
                          onChange={() => toggleCommunity(cm.communityNo)}
                          label={cm.name}
                        />
                      ))}
                    </div>
                    {!scopeReady && (
                      <Notice tone="warning" className="mt-2">{c.applyScopeNeedCommunity}</Notice>
                    )}
                  </div>
                )}

                {/*
                  经营类目授权：**与通过同一次提交**。
                  分两步做会留下一个状态 —— 通过了，但一个码都没授：商家收到通过通知、
                  进去建品、上架被拒，而错误说的是「你还没有资质授权」，去哪申请没人告诉他。
                  一个都不勾是合法的：只卖无门槛类目的商家不需要任何码。
                */}
                <div className="mt-3">
                  <Label>{c.applyGrantLabel}</Label>
                  <p className="mt-1 txt-caption text-muted-foreground">
                    {fill(c.applyGrantSaid, { s: current.category || "—" })}
                  </p>
                  <div className="mt-2 flex flex-wrap gap-3">
                    {(authCodes.data ?? []).map((a) => (
                      <CheckboxField
                        key={a.code}
                        checked={grantCodes.includes(a.code)}
                        onChange={() =>
                          setGrantCodes((p) =>
                            p.includes(a.code) ? p.filter((x) => x !== a.code) : [...p, a.code],
                          )
                        }
                        label={a.name}
                      />
                    ))}
                  </div>
                </div>

                <div className="mt-4">
                  <Label>{c.applyReasonLabel}</Label>
                  <Textarea
                    value={reason}
                    onChange={(v) => setReason(v)}
                    placeholder={c.applyReasonPh}
                  />
                </div>

                <div className="mt-4 flex gap-2">
                  <Button
                    disabled={!scopeReady || audit.isPending}
                    onClick={() => audit.mutate({ approved: true })}
                  >
                    {c.applyActionApprove}
                  </Button>
                  <Button
                    variant="outline"
                    // 驳回必须写理由 —— 不写就等于让对方猜着改，而重来的人有一部分不会回来
                    disabled={!reason.trim() || audit.isPending}
                    onClick={() => audit.mutate({ approved: false })}
                  >
                    {c.applyActionReject}
                  </Button>
                </div>
              </DrawerSection>
            )}
          </>
        )}
      </Drawer>
    </>
  );
}
