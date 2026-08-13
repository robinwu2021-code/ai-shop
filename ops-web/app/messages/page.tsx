"use client";

// 消息与客服（矩阵 P-14）。
//
// 触达频控（14.1.4）**不单独成页**：它是推送前的一层闸门，放在推送任务旁边才有意义 ——
// 单独一页会变成"配了没人看"的设置。
import { Suspense, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { fill, useCopy } from "@/lib/use-copy";
import { MESSAGES_COPY } from "./copy";
import { NotifyLogTab } from "./notify-log-tab";
import { usePaging } from "@/lib/use-paging";
import { usePageTab, useNavTabs } from "@/lib/use-page-tab";
import { fmtTime } from "@/lib/utils";
import { useCan } from "@/lib/use-can";
import { notify } from "@/lib/notify";
import type { FaqEntry, MsgTemplate, PushTask, Ticket } from "@/lib/types";
import { TicketStatusBadge, usePushStatusMap, useTicketStatusMap } from "@/components/status";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, Field, FieldGrid } from "@/components/ui/drawer";
import { FilterSelect } from "@/components/ui/filter-select";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Notice } from "@/components/ui/notice";
import { Pagination } from "@/components/ui/misc";
import { StatusBadge } from "@/components/ui/status-badge";
import { Switch } from "@/components/ui/switch";
import { TabHeader } from "@/components/ui/tab-header";
import { Textarea } from "@/components/ui/textarea";
import { Toolbar } from "@/components/ui/toolbar";

type Copy = (typeof MESSAGES_COPY)["zh"];
const TAB_KEYS = ["push", "tickets", "faq", "notifyLog"] as const;

export default function MessagesPage() {
  return <Suspense fallback={null}><MessagesInner /></Suspense>;
}

function MessagesInner() {
  const c = useCopy(MESSAGES_COPY);
  const tabs = useNavTabs("/messages", TAB_KEYS);
  const qc = useQueryClient();
  const allow = useCan();

  const [tab, setTab] = usePageTab(tabs, () => { setPage(1); setKeyword(""); setStatus(""); });

  const { page, setPage, size, setSize } = usePaging();
  const [keyword, setKeyword] = useState("");
  const [status, setStatus] = useState("");
  const [current, setCurrent] = useState<Ticket | null>(null);
  const [assignee, setAssignee] = useState("");
  const [proxyAction, setProxyAction] = useState("");
  const [faqEditing, setFaqEditing] = useState<FaqEntry | null>(null);
  const [faqForm, setFaqForm] = useState({ question: "", answer: "", category: "" });

  const canEditTemplate = allow("message:template:update");
  const canHandleTicket = allow("message:ticket:handle");
  const canEditFaq = allow("message:faq:update");

  const pushStatusMap = usePushStatusMap();
  const ticketStatusMap = useTicketStatusMap();

  const templates = useQuery({ queryKey: ["msg-templates"], queryFn: () => api.listMsgTemplates({ size: 100 }), enabled: tab === "push" });
  const tasks = useQuery({ queryKey: ["push-tasks"], queryFn: () => api.listPushTasks({ size: 100 }), enabled: tab === "push" });
  const quota = useQuery({ queryKey: ["notify-quota"], queryFn: () => api.getNotifyQuota(), enabled: tab === "push" });
  const ticketQ = { keyword, status, page, size };
  const tickets = useQuery({ queryKey: ["tickets", ticketQ], queryFn: () => api.listTickets(ticketQ), enabled: tab === "tickets" });
  const faqs = useQuery({ queryKey: ["faqs"], queryFn: () => api.listFaqs({ size: 100 }), enabled: tab === "faq" });

  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ["msg-templates"] });
    qc.invalidateQueries({ queryKey: ["push-tasks"] });
    qc.invalidateQueries({ queryKey: ["tickets"] });
    qc.invalidateQueries({ queryKey: ["faqs"] });
    qc.invalidateQueries({ queryKey: ["notify-quota"] });
  };

  const toggleTemplate = useMutation({
    mutationFn: (v: { no: string; enabled: boolean }) => api.setTemplateEnabled(v.no, v.enabled),
    onSuccess: () => { invalidate(); notify.success(c.toastTemplateSaved); },
  });
  const sendTask = useMutation({
    mutationFn: (no: string) => api.sendPushTask(no),
    onSuccess: (t) => { invalidate(); notify.success(fill(c.toastSent, { name: t.name })); },
  });
  const [quotaForm, setQuotaForm] = useState<{ daily: string; interval: string } | null>(null);
  const editingQuota = quotaForm ?? (quota.data ? { daily: String(quota.data.dailyPerUser), interval: String(quota.data.minIntervalHours) } : null);
  const saveQuota = useMutation({
    mutationFn: () => api.saveNotifyQuota({ dailyPerUser: Number(editingQuota!.daily), minIntervalHours: Number(editingQuota!.interval) }),
    onSuccess: () => { invalidate(); setQuotaForm(null); notify.success(c.toastQuotaSaved); },
  });
  const assign = useMutation({
    mutationFn: () => api.assignTicket(current!.ticketNo, assignee),
    onSuccess: (t) => { invalidate(); setCurrent(t); notify.success(c.toastAssigned); },
  });
  const addProxy = useMutation({
    mutationFn: () => api.addProxyAction(current!.ticketNo, proxyAction),
    onSuccess: (t) => { invalidate(); setCurrent(t); setProxyAction(""); notify.success(c.toastProxyLogged); },
  });
  const closeTicket = useMutation({
    mutationFn: () => api.closeTicket(current!.ticketNo),
    onSuccess: () => { invalidate(); setCurrent(null); notify.success(c.toastTicketClosed); },
  });
  const saveFaq = useMutation({
    mutationFn: () => api.saveFaq({ faqNo: faqEditing?.faqNo ?? "", ...faqForm }),
    onSuccess: () => { invalidate(); setFaqEditing(null); notify.success(c.toastSaved); },
  });
  const publishFaq = useMutation({
    mutationFn: (v: { no: string; published: boolean }) => api.setFaqPublished(v.no, v.published),
    onSuccess: () => { invalidate(); notify.success(c.toastPublished); },
  });

  const openNewFaq = () => {
    setFaqEditing({ faqNo: "", question: "", answer: "", category: "", published: false, views: 0 });
    setFaqForm({ question: "", answer: "", category: "" });
  };

  const templateColumns: Column<MsgTemplate>[] = [
    { header: c.colTemplateNo, cell: (t) => t.templateNo, numeric: true, align: "start" },
    { header: c.colName, cell: (t) => t.name },
    { header: c.colChannel, cell: (t) => ({ SUBSCRIBE: c.channelSubscribe, PUSH: c.channelPush, INBOX: c.channelInbox })[t.channel] },
    { header: c.colContent, cell: (t) => t.content, className: "whitespace-normal", width: "24rem" },
    { header: c.colSent30d, cell: (t) => t.sentCount, numeric: true },
    {
      header: c.colEnabled,
      cell: (t) => (
        <Switch checked={t.enabled} disabled={!canEditTemplate} aria-label={fill(c.ariaEnable, { name: t.name })}
          onChange={(v) => toggleTemplate.mutate({ no: t.templateNo, enabled: v })} />
      ),
    },
  ];

  const taskColumns: Column<PushTask>[] = [
    { header: c.colTaskNo, cell: (t) => t.taskNo, numeric: true, align: "start" },
    { header: c.colName, cell: (t) => t.name },
    { header: c.colAudience, cell: (t) => t.audience, className: "whitespace-normal", width: "18rem" },
    {
      header: c.colReach,
      numeric: true,
      // 0 说明人群是空的：发了等于白发，还会污染后面的效果分析
      cell: (t) => (t.estimatedReach > 0 ? t.estimatedReach : <Badge tone="danger">{c.reachEmpty}</Badge>),
    },
    { header: c.colScheduledAt, cell: (t) => fmtTime(t.scheduledAt) },
    { header: c.colStatus, cell: (t) => <StatusBadge map={pushStatusMap} value={t.status} /> },
    {
      header: c.colActions,
      cell: (t) =>
        canEditTemplate && t.status !== "SENT" && t.status !== "CANCELLED" ? (
          <Button size="sm" onClick={() => sendTask.mutate(t.taskNo)}>{c.btnSendNow}</Button>
        ) : <span className="text-muted-foreground">—</span>,
    },
  ];

  const ticketColumns: Column<Ticket>[] = [
    { header: c.colTicketNo, cell: (t) => t.ticketNo, numeric: true, align: "start" },
    { header: c.colTitle, cell: (t) => t.title, className: "whitespace-normal", width: "20rem" },
    { header: c.colUser, cell: (t) => t.userNickname },
    { header: c.colOrder, cell: (t) => t.orderNo ?? "—" },
    { header: c.colAssignee, cell: (t) => t.assignee ?? <span className="text-[var(--warning)]">{c.unassigned}</span> },
    {
      header: c.colProxy,
      // 代客操作是替用户改数据/退款，有几条要能一眼看见（矩阵 P-14.2.3）
      cell: (t) => (t.proxyActions?.length ? <Badge tone="info">{fill(c.proxyCount, { n: t.proxyActions.length })}</Badge> : <span className="text-muted-foreground">{c.none}</span>),
    },
    { header: c.colStatus, cell: (t) => <TicketStatusBadge value={t.status} /> },
    {
      header: c.colActions,
      cell: (t) => (
        <Button size="sm" variant="outline" onClick={() => { setCurrent(t); setAssignee(t.assignee ?? ""); setProxyAction(""); }}>
          {t.status === "CLOSED" ? c.actionView : c.actionHandle}
        </Button>
      ),
    },
  ];

  const faqColumns: Column<FaqEntry>[] = [
    { header: c.colFaqNo, cell: (f) => f.faqNo, numeric: true, align: "start" },
    { header: c.colQuestion, cell: (f) => f.question, className: "whitespace-normal", width: "20rem" },
    { header: c.colCategory, cell: (f) => f.category },
    {
      header: c.colAnswer,
      cell: (f) => (f.answer ? <span className="line-clamp-1 text-muted-foreground">{f.answer}</span> : <Badge tone="warning">{c.answerMissing}</Badge>),
      className: "whitespace-normal",
      width: "22rem",
    },
    { header: c.colViews, cell: (f) => f.views, numeric: true },
    {
      header: c.colPublished,
      cell: (f) => (
        <Switch checked={f.published} disabled={!canEditFaq} aria-label={fill(c.ariaPublish, { q: f.question })}
          onChange={(v) => publishFaq.mutate({ no: f.faqNo, published: v })} />
      ),
    },
    {
      header: c.colActions,
      cell: (f) =>
        canEditFaq ? (
          <Button size="sm" variant="outline"
            onClick={() => { setFaqEditing(f); setFaqForm({ question: f.question, answer: f.answer, category: f.category }); }}>
            {c.actionEdit}
          </Button>
        ) : <span className="text-muted-foreground">—</span>,
    },
  ];

  return (
    <div>
      <TabHeader tabs={tabs} value={tab} onChange={setTab} />

      {tab === "push" && (
        <div className="space-y-4">
          <Card className="max-w-xl">
            <CardHeader><CardTitle>{c.quotaTitle}</CardTitle></CardHeader>
            <CardContent>
              {!canEditTemplate && <ReadOnlyNotice what={c.quotaReadOnlyWhat} perm="message:template:update" className="mb-3" />}
              <Notice className="mb-3">
                {c.quotaNotice}
              </Notice>
              {editingQuota && (
                <div className="flex flex-wrap items-end gap-3">
                  <div className="space-y-1">
                    <Label htmlFor="q-daily" required>{c.fieldDaily}</Label>
                    <Input id="q-daily" className="w-32" disabled={!canEditTemplate} value={editingQuota.daily}
                      onChange={(e) => setQuotaForm((p) => ({ ...(p ?? editingQuota), daily: e.target.value }))} />
                  </div>
                  <div className="space-y-1">
                    <Label htmlFor="q-int" required>{c.fieldInterval}</Label>
                    <Input id="q-int" className="w-32" disabled={!canEditTemplate} value={editingQuota.interval}
                      onChange={(e) => setQuotaForm((p) => ({ ...(p ?? editingQuota), interval: e.target.value }))} />
                  </div>
                  <Button className="mb-0.5" disabled={!canEditTemplate} loading={saveQuota.isPending} onClick={() => saveQuota.mutate()}>{c.save}</Button>
                </div>
              )}
            </CardContent>
          </Card>

          <div>
            <div className="mb-2 txt-strong">{c.sectionTasks}</div>
            <DataTable
              columns={taskColumns} rows={tasks.data?.records} loading={tasks.isLoading}
              error={tasks.error} onRetry={() => tasks.refetch()}
              rowKey={(t) => t.taskNo}
              empty={c.emptyTasks}
            />
          </div>

          <div>
            <div className="mb-2 txt-strong">{c.sectionTemplates}</div>
            <DataTable
              columns={templateColumns} rows={templates.data?.records} loading={templates.isLoading}
              error={templates.error} onRetry={() => templates.refetch()}
              rowKey={(t) => t.templateNo}
              empty={c.emptyTemplates}
            />
          </div>
        </div>
      )}

      {tab === "tickets" && (
        <>
          {!canHandleTicket && <ReadOnlyNotice what={c.ticketReadOnlyWhat} perm="message:ticket:handle" className="mb-3" />}
          <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder={c.searchTickets}>
            <FilterSelect aria-label={c.filterStatus} value={status} onChange={(v) => { setStatus(v); setPage(1); }} options={ticketStatusMap} allLabel={c.filterStatusAll} />
          </Toolbar>
          <DataTable
            columns={ticketColumns} rows={tickets.data?.records} loading={tickets.isLoading}
            error={tickets.error} onRetry={() => tickets.refetch()}
            rowKey={(t) => t.ticketNo}
            empty={c.emptyTickets}
          />
          <Pagination page={page} size={size} onSize={setSize} total={tickets.data?.total ?? 0} onPage={setPage} />
        </>
      )}

      {/* 发送记录与测试发送。写权限用 message:template:update ——
          后端 /ops/notify-logs/test-send 用的是同一个码 */}
      {tab === "notifyLog" && (
        <NotifyLogTab c={c} canWrite={allow("message:template:update")} />
      )}

      {tab === "faq" && (
        <>
          <Notice className="mb-3">
            {c.faqNotice}
          </Notice>
          <Toolbar
            onAdd={canEditFaq ? openNewFaq : undefined}
            addLabel={c.addFaqLabel}
          />
          <DataTable
            columns={faqColumns} rows={faqs.data?.records} loading={faqs.isLoading}
            error={faqs.error} onRetry={() => faqs.refetch()}
            rowKey={(f) => f.faqNo}
            empty={c.emptyFaq}
            emptyAction={canEditFaq ? <Button size="sm" onClick={openNewFaq}>{c.addFaqLabel}</Button> : undefined}
          />
        </>
      )}

      {/* 工单处理 */}
      <Drawer
        open={!!current}
        onOpenChange={(o) => !o && setCurrent(null)}
        title={current?.title ?? ""}
        desc={current ? `${current.ticketNo} · ${current.userNickname}` : undefined}
        width="w-[520px]"
        footer={
          current && current.status !== "CLOSED" && canHandleTicket ? (
            <Button variant="outline" onClick={() => closeTicket.mutate()}>{c.btnCloseTicket}</Button>
          ) : null
        }
      >
        {current && (
          <div>
            <FieldGrid>
              <Field className="mb-3" label={c.colStatus}><TicketStatusBadge value={current.status} /></Field>
              <Field className="mb-3" label={c.colOrder}>{current.orderNo ?? "—"}</Field>
            </FieldGrid>

            {current.status !== "CLOSED" && canHandleTicket && (
              <>
                <div className="mb-4 space-y-1">
                  <Label htmlFor="tk-assignee" required>{c.fieldAssignee}</Label>
                  <div className="flex gap-2">
                    <Input id="tk-assignee" className="flex-1" placeholder={c.assigneePlaceholder} value={assignee}
                      onChange={(e) => setAssignee(e.target.value)} />
                    <Button onClick={() => assign.mutate()}>{c.btnAssign}</Button>
                  </div>
                </div>

                <div className="mb-4 space-y-1">
                  <Label htmlFor="tk-proxy">{c.fieldProxy}</Label>
                  <Textarea value={proxyAction} onChange={setProxyAction}
                    placeholder={c.proxyPlaceholder} />
                  <Button size="sm" className="mt-2" onClick={() => addProxy.mutate()}>{c.btnLogProxy}</Button>
                  <p className="txt-caption text-muted-foreground">
                    {c.proxyHint}
                  </p>
                </div>
              </>
            )}

            <Field label={c.fieldProxyLog}>
              {current.proxyActions?.length ? (
                <ul className="list-inside list-disc space-y-1">
                  {current.proxyActions.map((a, i) => <li key={i}>{a}</li>)}
                </ul>
              ) : c.none}
            </Field>
          </div>
        )}
      </Drawer>

      {/* FAQ 编辑 */}
      <Drawer
        open={!!faqEditing}
        onOpenChange={(o) => !o && setFaqEditing(null)}
        title={faqEditing?.faqNo ? c.faqDrawerEdit : c.faqDrawerNew}
        desc={faqEditing?.faqNo || undefined}
        footer={<Button loading={saveFaq.isPending} onClick={() => saveFaq.mutate()}>{c.save}</Button>}
      >
        <div className="space-y-4">
          <div className="space-y-1">
            <Label htmlFor="fq-q" required>{c.fieldQuestion}</Label>
            <Input id="fq-q" className="w-full" value={faqForm.question}
              onChange={(e) => setFaqForm((p) => ({ ...p, question: e.target.value }))} />
          </div>
          <div className="space-y-1">
            <Label htmlFor="fq-cat">{c.fieldCategory}</Label>
            <Input id="fq-cat" className="w-full" placeholder={c.categoryPlaceholder} value={faqForm.category}
              onChange={(e) => setFaqForm((p) => ({ ...p, category: e.target.value }))} />
          </div>
          <div className="space-y-1">
            <Label htmlFor="fq-a">{c.fieldAnswer}</Label>
            <Textarea value={faqForm.answer} onChange={(v) => setFaqForm((p) => ({ ...p, answer: v }))}
              placeholder={c.answerPlaceholder} />
            <p className="txt-caption text-muted-foreground">{c.answerHint}</p>
          </div>
        </div>
      </Drawer>
    </div>
  );
}
