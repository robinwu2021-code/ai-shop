"use client";

// 主题分类（陈列，批 E）—— **已接真后端** `/ops/topics/**`。
//
// 与同一页上另外三个 tab 管的是三件不同的事：
//   · 类目     = 这是什么货、要什么资质（准入门槛，改动最重）
//   · 标准品库 = 这件货长什么样（录入模板）
//   · 主题     = **这周首页摆什么**（陈列，改动最频繁、后果最轻）
//
// 不与营销活动合并：运营想做「早餐必备」时往往只是想把这 20 件摆到一起，
// 并不想降价。合并的结果是他为了摆个专题被迫建一个 0 折扣的活动。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { fill } from "@/lib/use-copy";
import { fmtTime } from "@/lib/utils";
import type { Topic } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { CheckboxField } from "@/components/ui/checkbox";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, DrawerSection, Field } from "@/components/ui/drawer";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Notice } from "@/components/ui/notice";
import { Toolbar } from "@/components/ui/toolbar";
import type { ProductsCopy } from "./copy";

type Form = {
  topicNo?: string;
  title: string;
  subtitle: string;
  sort: string;
  /** 档期用 `datetime-local` 的字符串形态；**空串 = 常设**，不是 0 */
  startAt: string;
  endAt: string;
};

const EMPTY: Form = { title: "", subtitle: "", sort: "0", startAt: "", endAt: "" };

/** `datetime-local` ↔ 毫秒。空串与 undefined 是同一件事：没有档期 */
const toMillis = (v: string) => (v ? new Date(v).getTime() : undefined);
const toInput = (v?: number) =>
  v ? new Date(v - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 16) : "";

export function TopicsTab({ c, canEdit }: { c: ProductsCopy; canEdit: boolean }) {
  const qc = useQueryClient();
  const [form, setForm] = useState<Form | null>(null);
  /** 正在挑商品的专题。与编辑抽屉分开：改名字与换货是两件事，同一个抽屉里会互相打断 */
  const [picking, setPicking] = useState<Topic | null>(null);
  const [picked, setPicked] = useState<string[]>([]);

  const topics = useQuery({ queryKey: ["topics"], queryFn: () => api.listTopics() });
  // 挑商品用商品池：**只列在售的** —— 摆一件下架货进去，C 端点进去是空位
  const pool = useQuery({
    queryKey: ["topic-pool"],
    queryFn: () => api.listGoods({ status: "ON_SALE", page: 1, size: 100 }),
    enabled: !!picking,
  });
  const current = useQuery({
    queryKey: ["topic-goods", picking?.topicNo],
    queryFn: () => api.listTopicGoods(picking!.topicNo, { page: 1, size: 100 }),
    enabled: !!picking,
  });

  const invalidate = () => qc.invalidateQueries({ queryKey: ["topics"] });

  const save = useMutation({
    mutationFn: () =>
      api.saveTopic({
        topicNo: form!.topicNo,
        title: form!.title,
        subtitle: form!.subtitle,
        sort: Number(form!.sort) || 0,
        startAt: toMillis(form!.startAt),
        endAt: toMillis(form!.endAt),
      }),
    onSuccess: () => { invalidate(); setForm(null); notify.success(c.topicSaved); },
  });

  const archive = useMutation({
    mutationFn: (v: { topicNo: string; archived: boolean }) =>
      api.setTopicArchived(v.topicNo, v.archived),
    onSuccess: () => { invalidate(); notify.success(c.topicSaved); },
  });

  const saveGoods = useMutation({
    mutationFn: () => api.setTopicGoods(picking!.topicNo, picked),
    onSuccess: () => {
      invalidate();
      qc.invalidateQueries({ queryKey: ["topic-goods"] });
      setPicking(null);
      notify.success(c.topicGoodsSaved);
    },
  });

  function openPick(t: Topic) {
    setPicking(t);
    // 先给空数组：当前选中项在 `current` 回来之后再灌，避免它闪一下又被覆盖
    setPicked([]);
    api.listTopicGoods(t.topicNo, { page: 1, size: 100 })
      .then((p) => setPicked(p.records.map((g) => g.goodsNo)))
      .catch(() => setPicked([]));
  }

  const columns: Column<Topic>[] = [
    { header: c.topicColTitle, cell: (t) => t.title },
    { header: c.topicColSubtitle, cell: (t) => t.subtitle ?? "—" },
    { header: c.topicColSort, cell: (t) => t.sort, numeric: true },
    {
      header: c.topicColRange,
      // 常设专题（没档期）要一眼看得出来 —— 它与「配错了时间」长得完全不同
      cell: (t) =>
        t.startAt || t.endAt
          ? `${t.startAt ? fmtTime(t.startAt) : "—"} ~ ${t.endAt ? fmtTime(t.endAt) : "—"}`
          : c.topicAlways,
    },
    {
      header: c.topicColGoods,
      // 空专题在 C 端是一个点进去什么都没有的入口 —— 列表要看得见
      cell: (t) =>
        t.goodsCount > 0
          ? fill(c.topicGoodsCount, { n: t.goodsCount })
          : <span className="text-[var(--warning)]">{c.topicEmpty}</span>,
    },
    {
      header: c.topicColStatus,
      cell: (t) =>
        t.status === "ARCHIVED"
          ? <Badge tone="muted">{c.topicArchived}</Badge>
          : <Badge tone="success">{c.topicActive}</Badge>,
    },
    {
      header: c.colActions,
      cell: (t) =>
        canEdit ? (
          <div className="flex gap-2">
            <Button size="sm" variant="ghost" onClick={() => setForm({
              topicNo: t.topicNo, title: t.title, subtitle: t.subtitle ?? "",
              sort: String(t.sort), startAt: toInput(t.startAt), endAt: toInput(t.endAt),
            })}>{c.topicEdit}</Button>
            <Button size="sm" variant="ghost" onClick={() => openPick(t)}>{c.topicPick}</Button>
            <Button
              size="sm"
              variant="ghost"
              onClick={() => archive.mutate({
                topicNo: t.topicNo, archived: t.status !== "ARCHIVED",
              })}
            >
              {t.status === "ARCHIVED" ? c.topicUnarchive : c.topicArchive}
            </Button>
          </div>
        ) : null,
    },
  ];

  return (
    <>
      <Notice className="mb-3">{c.topicNotice}</Notice>

      {canEdit && (
        <Toolbar>
          <Button onClick={() => setForm({ ...EMPTY })}>{c.topicNew}</Button>
        </Toolbar>
      )}

      <DataTable rows={topics.data ?? []} columns={columns} rowKey={(t) => t.topicNo} />

      <Drawer
        open={!!form}
        onOpenChange={(o) => !o && setForm(null)}
        title={form?.topicNo ? c.topicEdit : c.topicNew}
      >
        {form && (
          <DrawerSection first title={c.topicSectionBasic}>
            <Field label={c.topicColTitle}>
              <Input value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} />
            </Field>
            <Field label={c.topicColSubtitle}>
              <Input value={form.subtitle} onChange={(e) => setForm({ ...form, subtitle: e.target.value })} />
            </Field>
            <Field label={c.topicColSort}>
              <Input value={form.sort} onChange={(e) => setForm({ ...form, sort: e.target.value })} />
            </Field>
            {/* 档期两头都可空 —— 常设专题填一个假的结束时间会让它某天悄悄消失 */}
            <Field label={c.topicStart}>
              <Input type="datetime-local" value={form.startAt}
                onChange={(e) => setForm({ ...form, startAt: e.target.value })} />
            </Field>
            <Field label={c.topicEnd}>
              <Input type="datetime-local" value={form.endAt}
                onChange={(e) => setForm({ ...form, endAt: e.target.value })} />
            </Field>
            <p className="mt-1 txt-caption text-muted-foreground">{c.topicRangeHint}</p>
            <Button className="mt-4" disabled={!form.title.trim() || save.isPending}
              onClick={() => save.mutate()}>
              {c.topicSave}
            </Button>
          </DrawerSection>
        )}
      </Drawer>

      <Drawer
        open={!!picking}
        onOpenChange={(o) => !o && setPicking(null)}
        title={picking ? fill(c.topicPickTitle, { name: picking.title }) : ""}
      >
        {picking && (
          <DrawerSection first title={c.topicSectionGoods}>
            <Notice tone="info" className="mb-3">{c.topicPickNotice}</Notice>
            <Label>{fill(c.topicPicked, { n: picked.length })}</Label>
            <div className="mt-2 space-y-2">
              {(pool.data?.records ?? []).map((g) => (
                <CheckboxField
                  key={g.goodsNo}
                  checked={picked.includes(g.goodsNo)}
                  onChange={() =>
                    setPicked((p) =>
                      p.includes(g.goodsNo) ? p.filter((x) => x !== g.goodsNo) : [...p, g.goodsNo],
                    )
                  }
                  label={`${g.title.zh} · ${g.merchantName}`}
                />
              ))}
            </div>
            {!pool.isLoading && !(pool.data?.records ?? []).length && (
              <p className="txt-caption text-muted-foreground">{c.topicPoolEmpty}</p>
            )}
            <Button className="mt-4" disabled={saveGoods.isPending} onClick={() => saveGoods.mutate()}>
              {c.topicSaveGoods}
            </Button>
            {current.isError && <Notice tone="warning" className="mt-2">{c.topicLoadFailed}</Notice>}
          </DrawerSection>
        )}
      </Drawer>
    </>
  );
}
