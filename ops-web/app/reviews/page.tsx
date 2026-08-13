"use client";

// 评价治理（矩阵 P-13.1）。
//
// 刷评识别（P-13.1.5）**不单独成页**：它是评价行上的风险标 + 一个筛选开关。
// 单独一页会变成"看得见但没法处置"的孤岛 —— 发现刷评后要做的动作（下架）就在审核队列里。
import { Suspense, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { fill, useCopy } from "@/lib/use-copy";
import { REVIEWS_COPY } from "./copy";
import { usePaging } from "@/lib/use-paging";
import { usePageTab, useNavTabs } from "@/lib/use-page-tab";
import { SCORE_WEIGHT_TOTAL } from "@/lib/constants";
import { fmtTime } from "@/lib/utils";
import { useCan } from "@/lib/use-can";
import { useEditableConfig } from "@/lib/use-editable-config";
import { notify } from "@/lib/notify";
import type { Review, ReviewAppeal, ScoreConfig } from "@/lib/types";
import { AppealStatusBadge, ReviewStatusBadge, RiskFlagBadges, useReviewStatusMap } from "@/components/status";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, Field, FieldGrid } from "@/components/ui/drawer";
import { FilterSelect } from "@/components/ui/filter-select";
import { ConfigCard } from "@/components/ui/config-card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Notice } from "@/components/ui/notice";
import { Pagination } from "@/components/ui/misc";
import { TabHeader } from "@/components/ui/tab-header";
import { Textarea } from "@/components/ui/textarea";
import { Toolbar } from "@/components/ui/toolbar";

type Copy = (typeof REVIEWS_COPY)["zh"];
const TAB_KEYS = ["audit", "appeals", "score"] as const;

const RISKY_OPTIONS = (c: Copy) => [{ value: "1", label: c.riskyOnly }];

/** 星级用字符而不是图标：列表里 5 个图标会把行撑高，且扫描时不如字符整齐。 */
const stars = (n: number) => "★".repeat(n) + "☆".repeat(5 - n);

export default function ReviewsPage() {
  return <Suspense fallback={null}><ReviewsInner /></Suspense>;
}

function ReviewsInner() {
  const c = useCopy(REVIEWS_COPY);
  const tabs = useNavTabs("/reviews", TAB_KEYS);
  const riskyOptions = RISKY_OPTIONS(c);
  const qc = useQueryClient();
  const allow = useCan();

  const [tab, setTab] = usePageTab(tabs, () => { setPage(1); setKeyword(""); });

  const { page, setPage, size, setSize } = usePaging();
  const [keyword, setKeyword] = useState("");
  /*
   * **默认看全部，不是「待审核」**。
   *
   * 这一版是**先发后审**：后端 `ReviewServiceImpl.create()` 直接落 `PASSED`，
   * 从来不产生 `PENDING`（P-13.1.1 的敏感词/风控入队还没做）。
   * 默认筛 PENDING 的结果是：运营每次打开这一页都看到「待审队列已清空」，
   * 而库里躺着几十条评价 —— 他会以为审核功能在正常空转，直到有人投诉才发现
   * 从没审过任何一条。
   *
   * 敏感词入队做出来之后，把这里改回 PENDING，并同步改 emptyAudit 的文案。
   */
  const [status, setStatus] = useState("");
  const [risky, setRisky] = useState("");
  const [current, setCurrent] = useState<Review | null>(null);
  const [reason, setReason] = useState("");
  const [appeal, setAppeal] = useState<ReviewAppeal | null>(null);
  const [verdict, setVerdict] = useState("");

  const canAudit = allow("review:review:audit");
  const canEditScore = allow("review:score:update");
  const statusMap = useReviewStatusMap();

  const reviewQ = { keyword, status, risky, page, size };
  const reviews = useQuery({ queryKey: ["reviews", reviewQ], queryFn: () => api.listReviews(reviewQ), enabled: tab === "audit" });
  const appealQ = { keyword, page, size };
  const appeals = useQuery({ queryKey: ["review-appeals", appealQ], queryFn: () => api.listReviewAppeals(appealQ), enabled: tab === "appeals" });
  const score = useQuery({ queryKey: ["score-config"], queryFn: () => api.getScoreConfig(), enabled: tab === "score" });

  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ["reviews"] });
    qc.invalidateQueries({ queryKey: ["review-appeals"] });
  };

  const decide = useMutation({
    mutationFn: (v: { reviewNo: string; pass: boolean; reason?: string }) => api.decideReview(v.reviewNo, v.pass, v.reason),
    onSuccess: (r) => {
      invalidate(); setCurrent(null); setReason("");
      notify.success(r.status === "PASSED" ? c.toastPassed : c.toastTakenDown);
    },
  });

  const decideAppeal = useMutation({
    mutationFn: (v: { appealNo: string; uphold: boolean }) => api.decideAppeal(v.appealNo, v.uphold, verdict),
    onSuccess: (a) => {
      invalidate(); setAppeal(null); setVerdict("");
      notify.success(a.status === "UPHELD" ? c.toastAppealUpheld : c.toastAppealDismissed);
    },
  });

  const cfg: ScoreConfig | undefined = score.data;
  const { form: editing, set: setField, reset: resetForm } = useEditableConfig(cfg, (d) => ({
    weightProduct: String(d.weightProduct),
    weightFulfill: String(d.weightFulfill),
    weightService: String(d.weightService),
    newMerchantProtectDays: String(d.newMerchantProtectDays),
    decayHalfLifeDays: String(d.decayHalfLifeDays),
  }));
  const weightSum = editing
    ? Number(editing.weightProduct) + Number(editing.weightFulfill) + Number(editing.weightService)
    : 0;

  const saveScore = useMutation({
    mutationFn: () =>
      api.saveScoreConfig({
        weightProduct: Number(editing!.weightProduct),
        weightFulfill: Number(editing!.weightFulfill),
        weightService: Number(editing!.weightService),
        newMerchantProtectDays: Number(editing!.newMerchantProtectDays),
        decayHalfLifeDays: Number(editing!.decayHalfLifeDays),
      }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["score-config"] }); resetForm(); notify.success(c.toastScoreSaved); },
  });

  const reviewColumns: Column<Review>[] = [
    { header: c.colReviewNo, cell: (r) => r.reviewNo, numeric: true, align: "start" },
    { header: c.colMerchant, cell: (r) => r.merchantName },
    { header: c.colScore, cell: (r) => <span title={fill(c.scoreTitle, { n: r.score })}>{stars(r.score)}</span> },
    {
      header: c.colContent,
      width: "24rem",
      // 长文案换行会把行撑高，列表里给一行 + 抽屉看全文
      className: "whitespace-normal",
      cell: (r) => <span className="line-clamp-1">{r.content}</span>,
    },
    { header: c.colImages, cell: (r) => r.imageCount, numeric: true },
    { header: c.colRiskFlags, cell: (r) => <RiskFlagBadges flags={r.riskFlags} /> },
    { header: c.colTime, cell: (r) => fmtTime(r.createdAt) },
    { header: c.colStatus, cell: (r) => <ReviewStatusBadge value={r.status} /> },
    {
      header: c.colActions,
      cell: (r) => (
        <Button size="sm" variant="outline" onClick={() => { setCurrent(r); setReason(r.reason ?? ""); }}>
          {r.status === "PENDING" && canAudit ? c.actionAudit : c.actionView}
        </Button>
      ),
    },
  ];

  const appealColumns: Column<ReviewAppeal>[] = [
    { header: c.colAppealNo, cell: (a) => a.appealNo, numeric: true, align: "start" },
    { header: c.colMerchant, cell: (a) => a.merchantName },
    { header: c.colRelatedReview, cell: (a) => a.reviewNo, numeric: true, align: "start" },
    {
      header: c.colReason,
      width: "24rem",
      className: "whitespace-normal",
      cell: (a) => <span className="line-clamp-1 text-muted-foreground">{a.reason}</span>,
    },
    { header: c.colEvidence, cell: (a) => a.evidenceCount, numeric: true },
    { header: c.colSubmittedAt, cell: (a) => fmtTime(a.submittedAt) },
    { header: c.colStatus, cell: (a) => <AppealStatusBadge value={a.status} /> },
    {
      header: c.colActions,
      cell: (a) => (
        <Button size="sm" variant="outline" onClick={() => { setAppeal(a); setVerdict(a.verdict ?? ""); }}>
          {a.status === "PENDING" && canAudit ? c.actionDecide : c.actionView}
        </Button>
      ),
    },
  ];

  const activeList = tab === "audit" ? reviews : appeals;

  return (
    <div>
      <TabHeader tabs={tabs} value={tab} onChange={setTab} />

      {tab !== "score" && !canAudit && (
        <ReadOnlyNotice what={c.readOnlyWhat} perm="review:review:audit" note={c.readOnlyNote} className="mb-3" />
      )}

      {tab === "audit" && (
        <Notice className="mb-3">
          {c.notice}
        </Notice>
      )}

      {tab !== "score" && (
        <Toolbar
          search={keyword}
          onSearch={(v) => { setKeyword(v); setPage(1); }}
          searchPlaceholder={tab === "audit" ? c.searchAudit : c.searchAppeal}
        >
          {tab === "audit" && (
            <>
              <FilterSelect aria-label={c.filterStatus} value={status} onChange={(v) => { setStatus(v); setPage(1); }} options={statusMap} allLabel={c.filterStatusAll} />
              <FilterSelect aria-label={c.filterRisky} value={risky} onChange={(v) => { setRisky(v); setPage(1); }} options={riskyOptions} allLabel={c.filterRiskyAll} />
            </>
          )}
        </Toolbar>
      )}

      {tab === "audit" && (
        <DataTable
          columns={reviewColumns} rows={reviews.data?.records} loading={reviews.isLoading}
          error={reviews.error} onRetry={() => reviews.refetch()}
          rowKey={(r) => r.reviewNo}
          empty={c.emptyAudit}
        />
      )}

      {tab === "appeals" && (
        <DataTable
          columns={appealColumns} rows={appeals.data?.records} loading={appeals.isLoading}
          error={appeals.error} onRetry={() => appeals.refetch()}
          rowKey={(a) => a.appealNo}
          empty={c.emptyAppeals}
        />
      )}

      {/* 一页一个分页器，绑当前 tab 的查询（activeList）。此前两个 tab 各写了一遍
          **一模一样**的这行 —— 加第三个分页 tab 时必然漏掉一个。 */}
      {tab !== "score" && (
        <Pagination page={page} size={size} onSize={setSize} total={activeList.data?.total ?? 0} onPage={setPage} />
      )}

      {tab === "score" && (
        <ConfigCard
          title={c.scoreTitleCard}
          readOnly={!canEditScore && (
              <ReadOnlyNotice what={c.scoreReadOnlyWhat} perm="review:score:update" note={c.scoreReadOnlyNote} className="mb-3" />
            )}
          notice={
            c.scoreNotice
          }
          onSave={() => saveScore.mutate()}
          saving={saveScore.isPending}
          canSave={canEditScore}
          updatedAt={cfg?.updatedAt}
          updatedBy={cfg?.updatedBy}
        >
          {editing && (
            <>
                <div className="grid grid-cols-3 gap-3">
                  {([
                    ["weightProduct", c.weightProduct],
                    ["weightFulfill", c.weightFulfill],
                    ["weightService", c.weightService],
                  ] as const).map(([k, label]) => (
                    <div key={k} className="space-y-1">
                      <Label htmlFor={k} required>{label}</Label>
                      <Input
                        id={k} disabled={!canEditScore} value={editing[k]}
                        onChange={(e) => setField(k, e.target.value)}
                      />
                    </div>
                  ))}
                </div>
                <p className={weightSum === SCORE_WEIGHT_TOTAL ? "txt-caption text-muted-foreground" : "txt-caption text-[var(--destructive)]"}>
                  {fill(c.weightSum, { sum: weightSum, total: SCORE_WEIGHT_TOTAL })}
                  {weightSum !== SCORE_WEIGHT_TOTAL && c.weightSumBad}
                </p>

                <div className="space-y-1">
                  <Label htmlFor="protect" required>{c.fieldProtect}</Label>
                  <Input
                    id="protect" className="w-full" disabled={!canEditScore} value={editing.newMerchantProtectDays}
                    onChange={(e) => setField("newMerchantProtectDays", e.target.value)}
                  />
                  <p className="txt-caption text-muted-foreground">{c.protectHint}</p>
                </div>
                <div className="space-y-1">
                  <Label htmlFor="decay" required>{c.fieldDecay}</Label>
                  <Input
                    id="decay" className="w-full" disabled={!canEditScore} value={editing.decayHalfLifeDays}
                    onChange={(e) => setField("decayHalfLifeDays", e.target.value)}
                  />
                  <p className="txt-caption text-muted-foreground">{c.decayHint}</p>
                </div>
            </>
          )}
        </ConfigCard>
      )}

      {/* 评价审核抽屉 */}
      <Drawer
        open={!!current}
        onOpenChange={(o) => !o && setCurrent(null)}
        title={current ? `${current.merchantName} · ${fill(c.drawerScore, { n: current.score })}` : ""}
        desc={current?.reviewNo}
        width="w-[520px]"
        footer={
          current?.status === "PENDING" && canAudit ? (
            <>
              <Button variant="outline" onClick={() => decide.mutate({ reviewNo: current.reviewNo, pass: false, reason })}>{c.btnTakeDown}</Button>
              <Button onClick={() => decide.mutate({ reviewNo: current.reviewNo, pass: true })}>{c.btnPass}</Button>
            </>
          ) : null
        }
      >
        {current && (
          <div>
            <FieldGrid>
              <Field className="mb-3" label={c.fieldScoreProduct}>{current.scoreProduct}</Field>
              <Field className="mb-3" label={c.fieldScoreFulfill}>{current.scoreFulfill}</Field>
              <Field className="mb-3" label={c.fieldScoreService}>{current.scoreService}</Field>
              <Field className="mb-3" label={c.fieldOrder}>{current.orderNo}</Field>
            </FieldGrid>
            <Field label={c.fieldContent}><p className="whitespace-pre-wrap">{current.content}</p></Field>
            <Field label={c.fieldImages}>{current.imageCount ? fill(c.imagesCount, { n: current.imageCount }) : c.none}</Field>
            <Field label={c.colRiskFlags}><RiskFlagBadges flags={current.riskFlags} /></Field>
            <Field label={c.fieldTakeDownReason}>
              {current.status === "PENDING" && canAudit ? (
                <Textarea value={reason} onChange={setReason} placeholder={c.takeDownPlaceholder} />
              ) : (
                current.reason || "-"
              )}
            </Field>
          </div>
        )}
      </Drawer>

      {/* 申诉裁决抽屉 */}
      <Drawer
        open={!!appeal}
        onOpenChange={(o) => !o && setAppeal(null)}
        title={appeal ? fill(c.drawerAppeal, { name: appeal.merchantName }) : ""}
        desc={appeal?.appealNo}
        width="w-[520px]"
        footer={
          appeal?.status === "PENDING" && canAudit ? (
            <>
              <Button variant="outline" onClick={() => decideAppeal.mutate({ appealNo: appeal.appealNo, uphold: false })}>
                {c.btnKeepReview}
              </Button>
              <Button onClick={() => decideAppeal.mutate({ appealNo: appeal.appealNo, uphold: true })}>
                {c.btnUphold}
              </Button>
            </>
          ) : null
        }
      >
        {appeal && (
          <div>
            {/* **被申诉的那条评价要先出现**：裁决人判的是它，不是申诉书。
                只给单号的话，他要切页签、改筛选、自己去列表里找 ——
                实际发生的是没人去找，于是裁决只听得到商家一方的陈述。 */}
            <Field label={c.colRelatedReview}>
              <span className="text-muted-foreground">{appeal.reviewNo}</span>
              <p className="mt-1 whitespace-pre-wrap">
                <span className="mr-2">{"★".repeat(appeal.reviewRating)}{"☆".repeat(Math.max(0, 5 - appeal.reviewRating))}</span>
                {appeal.reviewContent}
              </p>
            </Field>
            <Field label={c.fieldAppealReason}><p className="whitespace-pre-wrap">{appeal.reason}</p></Field>
            <Field label={c.fieldEvidence}>{appeal.evidenceCount ? fill(c.evidenceCount, { n: appeal.evidenceCount }) : c.none}</Field>
            <Field label={c.fieldVerdict}>
              {appeal.status === "PENDING" && canAudit ? (
                <Textarea
                  value={verdict}
                  onChange={setVerdict}
                  placeholder={c.verdictPlaceholder}
                />
              ) : (
                appeal.verdict || "-"
              )}
            </Field>
          </div>
        )}
      </Drawer>
    </div>
  );
}
