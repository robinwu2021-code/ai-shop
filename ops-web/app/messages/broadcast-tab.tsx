"use client";

// 平台营销广播（触达推送中台 N6/N6b）。运营主动发起的群发：
// 圈人群 → **预估触达** → 定时下发。与「发送记录」里的事件触达（钱扣了/货到了）分开：
// 那是「系统必须告诉用户」，这是「平台想推给用户」，只发给装了 App 的人（opt-in）。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { fmtTime } from "@/lib/utils";
import { usePaging } from "@/lib/use-paging";
import type { NotifyPushTask } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Input, Select } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { Label } from "@/components/ui/label";
import { HelpNote } from "@/components/ui/help-note";
import { Pagination } from "@/components/ui/misc";
import { notify } from "@/lib/notify";
import { useCan } from "@/lib/use-can";
import type { MessageCopy } from "./copy";

export function BroadcastTab({ c }: { c: MessageCopy }) {
  const qc = useQueryClient();
  const canWrite = useCan()("message:template:update");
  const { page, setPage, size, setSize } = usePaging();

  const tasks = useQuery({
    queryKey: ["push-tasks", page, size],
    queryFn: () => api.listPushTasks({ page, size }),
  });

  const audLabel: Record<string, string> = {
    ALL_APP_USER: c.bcAudUser, ALL_STAFF: c.bcAudStaff,
  };
  const statusBadge = (s: string) => {
    switch (s) {
      case "DONE": return <Badge tone="success">{c.bcStDone}</Badge>;
      case "RUNNING": return <Badge tone="warning">{c.bcStRunning}</Badge>;
      case "CANCELLED": return <Badge tone="muted">{c.bcStCancelled}</Badge>;
      default: return <Badge tone="info">{c.bcStQueued}</Badge>;
    }
  };

  const cancel = useMutation({
    mutationFn: (taskNo: string) => api.cancelPushTask(taskNo),
    onSuccess: () => {
      notify.success(c.bcCancelled);
      void qc.invalidateQueries({ queryKey: ["push-tasks"] });
    },
  });

  const cols: Column<NotifyPushTask>[] = [
    { header: c.bcName, cell: (r) => r.name },
    { header: c.bcColAudience, cell: (r) => audLabel[r.audienceType] ?? r.audienceType },
    { header: c.bcColStatus, cell: (r) => statusBadge(r.status) },
    // 实发/预估一列并列：预估是发起时的快照，实发是真结果，两个数一起看才知道覆盖率
    { header: c.bcColReach, cell: (r) => `${r.sentCount} / ${r.estimatedCount}` },
    { header: c.bcColSchedule, cell: (r) => r.scheduledAt ? fmtTime(r.scheduledAt) : c.bcSoon },
    {
      header: "",
      // 只有待发的能取消 —— 已在下发/完成的拦不住了
      cell: (r) => r.status === "QUEUED" && canWrite
        ? <Button variant="ghost" size="sm" disabled={cancel.isPending}
                  onClick={() => cancel.mutate(r.taskNo)}>{c.bcCancel}</Button>
        : null,
    },
  ];

  return (
    <div className="space-y-4">
      <HelpNote>{c.bcNotice}</HelpNote>
      {canWrite && <CreateBroadcastCard c={c} onCreated={() =>
        void qc.invalidateQueries({ queryKey: ["push-tasks"] })} />}

      <Card>
        <CardHeader><CardTitle>{c.bcTitle}</CardTitle></CardHeader>
        <CardContent>
          <DataTable columns={cols} rows={tasks.data?.records ?? []} loading={tasks.isLoading}
                     rowKey={(r) => r.taskNo} empty={c.bcEmpty} />
        </CardContent>
      </Card>
      <Pagination page={page} size={size} onSize={setSize}
                  total={tasks.data?.total ?? 0} onPage={setPage} />
    </div>
  );
}

/** 新建广播 + 实时预估触达。 */
function CreateBroadcastCard({ c, onCreated }: { c: MessageCopy; onCreated: () => void }) {
  const [name, setName] = useState("");
  const [audience, setAudience] = useState("ALL_APP_USER");
  const [title, setTitle] = useState("");
  const [body, setBody] = useState("");
  const [link, setLink] = useState("");
  const [scheduledAt, setScheduledAt] = useState("");

  // 预估随人群变，**发起前就看得到覆盖多少人**（后端 /ops/push-tasks/estimate）
  const est = useQuery({
    queryKey: ["push-task-estimate", audience],
    queryFn: () => api.estimatePushTask(audience),
  });

  const create = useMutation({
    mutationFn: () => api.createPushTask({
      name, audienceType: audience, title, body,
      link: link || undefined, scheduledAt: scheduledAt || undefined,
    }),
    onSuccess: () => {
      notify.success(c.bcCreated);
      setName(""); setTitle(""); setBody(""); setLink(""); setScheduledAt("");
      onCreated();
    },
  });

  const ready = name.trim() && title.trim() && body.trim();

  return (
    <Card>
      <CardHeader><CardTitle>{c.bcCreate}</CardTitle></CardHeader>
      <CardContent className="space-y-3">
        <div className="grid gap-3 sm:grid-cols-2">
          <div className="space-y-1">
            <Label htmlFor="bc-name">{c.bcName}</Label>
            <Input id="bc-name" value={name} placeholder={c.bcNamePh}
                   onChange={(e) => setName(e.target.value)} />
          </div>
          <div className="space-y-1">
            <Label htmlFor="bc-aud">{c.bcAudience}</Label>
            <Select id="bc-aud" value={audience} onChange={(e) => setAudience(e.target.value)}>
              <option value="ALL_APP_USER">{c.bcAudUser}</option>
              <option value="ALL_STAFF">{c.bcAudStaff}</option>
            </Select>
          </div>
        </div>
        <div className="space-y-1">
          <Label htmlFor="bc-title">{c.bcTitleField}</Label>
          <Input id="bc-title" value={title} onChange={(e) => setTitle(e.target.value)} />
        </div>
        <div className="space-y-1">
          <Label htmlFor="bc-body">{c.bcBody}</Label>
          {/* Textarea 的 onChange 直接给值（本仓 README 约定），不是 event */}
          <Textarea id="bc-body" rows={2} value={body} onChange={(v) => setBody(v)} />
        </div>
        <div className="grid gap-3 sm:grid-cols-2">
          <div className="space-y-1">
            <Label htmlFor="bc-link">{c.bcLink}</Label>
            <Input id="bc-link" value={link} placeholder={c.bcLinkPh}
                   onChange={(e) => setLink(e.target.value)} />
          </div>
          <div className="space-y-1">
            <Label htmlFor="bc-sched">{c.bcSchedule}</Label>
            <Input id="bc-sched" type="datetime-local" value={scheduledAt}
                   onChange={(e) => setScheduledAt(e.target.value)} />
          </div>
        </div>
        <div className="flex items-center gap-3">
          <Button disabled={!ready || create.isPending} onClick={() => create.mutate()}>
            {c.bcSubmit}
          </Button>
          {/* 发起前就把覆盖面摆在按钮边上 —— 发之前先知道这一发有多大 */}
          <span className="txt-caption text-muted-foreground">
            {est.isLoading ? c.bcEstimating
              : `${c.bcEstimatePrefix}${est.data?.count ?? 0}${c.bcEstimateSuffix}`}
          </span>
        </div>
      </CardContent>
    </Card>
  );
}
