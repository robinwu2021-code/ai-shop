"use client";

// 榜单与问答（矩阵 P-15.2.2 / 15.2.3）。
//
// 同一个 tab 里两块，因为它们是首页「内容位」的两种填充物：
// 榜单填货架，问答填信任。分成两页会让人以为可以只做一半。
//
// 榜单这块最要紧的一条：`MANUAL` 与算出来的三类**校验路径完全不同**，
// 所以人工选品区只在 kind=MANUAL 时出现，而不是常驻然后保存时报错。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { fill } from "@/lib/use-copy";
import { fmtTime } from "@/lib/utils";
import { MAX_RANKING_SIZE } from "@/lib/constants";
import type { Question, QuestionStatus, Ranking, RankingKind } from "@/lib/types";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, DrawerSection, Field } from "@/components/ui/drawer";
import { FilterSelect } from "@/components/ui/filter-select";
import { Pagination } from "@/components/ui/misc";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { Toolbar } from "@/components/ui/toolbar";
import { Notice } from "@/components/ui/notice";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Input, Select } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import type { ContentsCopy } from "./copy";

interface RankForm {
  rankNo?: string;
  name: string;
  kind: RankingKind;
  size: string;
  manualSkus: string[];
  enabled: boolean;
}

const useQStatusMap = (c: ContentsCopy): StatusMap<QuestionStatus> => ({
  PENDING: { label: c.qsPending, tone: "warning" },
  ANSWERED: { label: c.qsAnswered, tone: "success" },
  HIDDEN: { label: c.qsHidden, tone: "muted" },
});

export function RankTab({ c, canEdit }: { c: ContentsCopy; canEdit: boolean }) {
  const qc = useQueryClient();
  const qStatusMap = useQStatusMap(c);
  const [editing, setEditing] = useState<RankForm | null>(null);
  const [qKeyword, setQKeyword] = useState("");
  const [qStatus, setQStatus] = useState("");
  const [qPage, setQPage] = useState(1);
  const [qSize, setQSize] = useState(10);
  const [answering, setAnswering] = useState<Question | null>(null);
  const [answer, setAnswer] = useState("");
  const [hideReason, setHideReason] = useState("");

  const kindLabel: Record<RankingKind, string> = {
    SALES: c.rkSales, RATING: c.rkRating, NEW: c.rkNew, MANUAL: c.rkManual,
  };

  const rankings = useQuery({ queryKey: ["rankings"], queryFn: () => api.listRankings() });
  // 人工榜只能选在售商品：下拉里就不给下架的，与 mock 层的规则同向
  const onSaleSkus = useQuery({
    queryKey: ["skus", "onsale"],
    queryFn: () => api.listSkus({ status: "ON_SALE", size: 100 }),
    enabled: editing?.kind === "MANUAL",
  });

  const qList = useQuery({
    queryKey: ["questions", { qKeyword, qStatus, qPage, qSize }],
    queryFn: () => api.listQuestions({ keyword: qKeyword, status: qStatus, page: qPage, size: qSize }),
  });

  const saveRank = useMutation({
    mutationFn: () =>
      api.saveRanking({
        rankNo: editing!.rankNo,
        name: editing!.name,
        kind: editing!.kind,
        size: Number(editing!.size),
        manualSkus: editing!.manualSkus,
        enabled: editing!.enabled,
      }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["rankings"] }); setEditing(null); notify.success(c.toastRankSaved); },
  });
  const toggleRank = useMutation({
    mutationFn: (v: { rankNo: string; enabled: boolean }) => api.setRankingEnabled(v.rankNo, v.enabled),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["rankings"] }); notify.success(c.toastRankToggled); },
  });

  const doneQ = () => { qc.invalidateQueries({ queryKey: ["questions"] }); setAnswering(null); };
  const answerMut = useMutation({
    mutationFn: () => api.answerQuestion({ questionNo: answering!.questionNo, answer }),
    onSuccess: () => { doneQ(); notify.success(c.toastAnswered); },
  });
  const hideMut = useMutation({
    mutationFn: () => api.hideQuestion({ questionNo: answering!.questionNo, reason: hideReason }),
    onSuccess: () => { doneQ(); notify.success(c.toastHidden); },
  });

  const rankColumns: Column<Ranking>[] = [
    { header: c.colRankName, cell: (r) => r.name },
    { header: c.colRankKind, cell: (r) => <Badge tone={r.kind === "MANUAL" ? "warning" : "info"}>{kindLabel[r.kind]}</Badge> },
    { header: c.colRankSize, cell: (r) => fill(c.topN, { n: r.size }), numeric: true },
    {
      header: c.colManualSkus,
      cell: (r) =>
        r.kind === "MANUAL"
          ? fill(c.pickedSkus, { n: r.manualSkus.length })
          : <span className="text-muted-foreground">{c.autoComputed}</span>,
    },
    {
      header: c.colRankStatus,
      cell: (r) => (r.enabled ? <Badge tone="success">{c.rankOn}</Badge> : <span className="text-muted-foreground">{c.rankOff}</span>),
    },
    { header: c.colUpdatedAt, cell: (r) => `${fmtTime(r.updatedAt)} · ${r.updatedBy}` },
    {
      header: c.colActions,
      cell: (r) => (
        <div className="flex gap-2">
          <Button size="sm" variant="outline" disabled={!canEdit}
            onClick={() => setEditing({ ...r, size: String(r.size), manualSkus: [...r.manualSkus] })}>
            {c.actionEditRank}
          </Button>
          <Button size="sm" variant="ghost" disabled={!canEdit}
            onClick={() => toggleRank.mutate({ rankNo: r.rankNo, enabled: !r.enabled })}>
            {r.enabled ? c.actionRankOff : c.actionRankOn}
          </Button>
        </div>
      ),
    },
  ];

  const qColumns: Column<Question>[] = [
    { header: c.colQuestionNo, cell: (q) => q.questionNo, numeric: true, align: "start" },
    { header: c.colSku, cell: (q) => q.skuTitle },
    { header: c.colQuestion, cell: (q) => q.content },
    { header: c.colAnswer, cell: (q) => q.answer ?? <span className="text-muted-foreground">{c.none}</span> },
    { header: c.colQStatus, cell: (q) => <StatusBadge map={qStatusMap} value={q.status} /> },
    { header: c.colCreatedAt, cell: (q) => fmtTime(q.createdAt) },
    {
      header: c.colActions,
      cell: (q) => (
        <Button size="sm" variant="outline" disabled={!canEdit}
          onClick={() => { setAnswering(q); setAnswer(""); setHideReason(""); }}>
          {q.status === "PENDING" ? c.actionAnswer : c.actionView}
        </Button>
      ),
    },
  ];

  return (
    <>
      <Notice className="mb-3">{fill(c.rankNotice, { n: MAX_RANKING_SIZE })}</Notice>
      <Toolbar
        onAdd={() => setEditing({ name: "", kind: "SALES", size: "10", manualSkus: [], enabled: false })}
        addLabel={c.actionNewRank} canAdd={canEdit}
      />
      <DataTable
        columns={rankColumns} rows={rankings.data} loading={rankings.isLoading}
        error={rankings.error} onRetry={() => rankings.refetch()}
        rowKey={(r) => r.rankNo}
        empty={c.emptyRank}
      />

      <h3 className="mt-8 mb-3 txt-label text-muted-foreground">{c.secQuestions}</h3>
      <Notice className="mb-3">{c.qaNotice}</Notice>
      <Toolbar search={qKeyword} onSearch={(v) => { setQKeyword(v); setQPage(1); }} searchPlaceholder={c.searchQuestion}>
        <FilterSelect aria-label={c.filterQStatus} value={qStatus} onChange={(v) => { setQStatus(v); setQPage(1); }}
          options={qStatusMap} allLabel={c.filterQStatusAll} />
      </Toolbar>
      <DataTable
        columns={qColumns} rows={qList.data?.records} loading={qList.isLoading}
        error={qList.error} onRetry={() => qList.refetch()}
        rowKey={(q) => q.questionNo}
        empty={c.emptyQuestion}
      />
      <Pagination page={qPage} size={qSize} onSize={setQSize} total={qList.data?.total ?? 0} onPage={setQPage} />

      <Drawer
        open={!!editing}
        onOpenChange={(o) => !o && setEditing(null)}
        title={editing?.rankNo ? fill(c.editRankTitle, { name: editing.name }) : c.newRankTitle}
        width="w-[560px]"
        footer={canEdit ? <Button loading={saveRank.isPending} onClick={() => saveRank.mutate()}>{c.btnSaveRank}</Button> : null}
      >
        {editing && (
          <div>
            <DrawerSection first title={c.secRankBasic}>
              <div className="mb-3 space-y-1">
                <Label htmlFor="rk-name" required>{c.colRankName}</Label>
                <Input id="rk-name" className="w-full" value={editing.name}
                  onChange={(e) => setEditing((p) => p && { ...p, name: e.target.value })} />
              </div>
              <div className="mb-3 space-y-1">
                <Label htmlFor="rk-kind" required>{c.colRankKind}</Label>
                <Select id="rk-kind" className="w-full" value={editing.kind}
                  onChange={(e) => {
                    // 切成算出来的榜就把人工选品清掉：留着保存会被拒，那是个只能靠报错发现的坑
                    const kind = e.target.value as RankingKind;
                    setEditing((p) => p && { ...p, kind, manualSkus: kind === "MANUAL" ? p.manualSkus : [] });
                  }}>
                  {(Object.keys(kindLabel) as RankingKind[]).map((k) => <option key={k} value={k}>{kindLabel[k]}</option>)}
                </Select>
                <p className="txt-caption text-muted-foreground">{c.kindHint}</p>
              </div>
              <div className="space-y-1">
                <Label htmlFor="rk-size" required>{c.colRankSize}</Label>
                <Input id="rk-size" className="w-full" value={editing.size}
                  onChange={(e) => setEditing((p) => p && { ...p, size: e.target.value })} />
              </div>
            </DrawerSection>

            {/* 人工选品区只在 MANUAL 时出现 —— 常驻然后保存时报错，是在骗人填一遍 */}
            {editing.kind === "MANUAL" && (
              <DrawerSection title={c.secManualSkus}>
                <div className="space-y-2">
                  {onSaleSkus.data?.records.map((s) => (
                    <label key={s.skuNo} className="flex items-center gap-2">
                      <Checkbox
                        checked={editing.manualSkus.includes(s.skuNo)}
                        onChange={(v) => setEditing((p) => p && {
                          ...p,
                          manualSkus: v === true
                            ? [...p.manualSkus, s.skuNo]
                            : p.manualSkus.filter((x) => x !== s.skuNo),
                        })}
                      />
                      <span className="txt-body">{s.title.zh}</span>
                    </label>
                  ))}
                </div>
                <p className="mt-3 txt-caption text-muted-foreground">
                  {fill(c.manualSkusHint, { n: editing.manualSkus.length, size: editing.size })}
                </p>
              </DrawerSection>
            )}
          </div>
        )}
      </Drawer>

      <Drawer
        open={!!answering}
        onOpenChange={(o) => !o && setAnswering(null)}
        title={answering ? fill(c.answerTitle, { no: answering.questionNo }) : ""}
        desc={answering ? qStatusMap[answering.status].label : undefined}
        width="w-[520px]"
        footer={
          answering && canEdit && answering.status === "PENDING" ? (
            <>
              <Button variant="outline" loading={hideMut.isPending} onClick={() => hideMut.mutate()}>{c.btnHide}</Button>
              <Button loading={answerMut.isPending} onClick={() => answerMut.mutate()}>{c.btnAnswer}</Button>
            </>
          ) : null
        }
      >
        {answering && (
          <div>
            <DrawerSection first title={c.secQuestion}>
              <Field className="mb-3" label={c.colSku}>{answering.skuTitle}</Field>
              <Field className="mb-0" label={c.colQuestion}>{answering.content}</Field>
            </DrawerSection>

            {answering.status === "PENDING" ? (
              <>
                <DrawerSection title={c.secAnswer}>
                  <Field className="mb-0" label={c.colAnswer}>
                    <Textarea value={answer} onChange={setAnswer} rows={3} placeholder={c.answerPlaceholder} />
                  </Field>
                  <p className="mt-1 txt-caption text-muted-foreground">{c.answerHint}</p>
                </DrawerSection>
                <DrawerSection title={c.secHide}>
                  <Field className="mb-0" label={c.fieldHideReason}>
                    <Textarea value={hideReason} onChange={setHideReason} rows={2} placeholder={c.hideReasonPlaceholder} />
                  </Field>
                </DrawerSection>
              </>
            ) : (
              <DrawerSection title={c.secAnswer}>
                <p className="txt-body">{answering.answer ?? answering.hideReason ?? c.none}</p>
                <p className="mt-1 txt-caption text-muted-foreground">
                  {answering.answeredAt ? `${fmtTime(answering.answeredAt)} · ${answering.answeredBy}` : ""}
                </p>
              </DrawerSection>
            )}
          </div>
        )}
      </Drawer>
    </>
  );
}
