"use client";

// 标准品库（TDD-标准品库）—— **已接真后端** `/ops/spu-std/**`。
//
// 后端在 V166 那一轮就通了，商家侧的「从标准品开始」也已经能用 ——
// **缺的一直是运营录入这一步**。而这个功能唯一可能失败的方式就是「上线了没人用」，
// 覆盖率全靠人手录：没有这个页面，等于把它锁在门外。
//
// 与规格模板那一页长得像，但管的是两件事：
//   · 规格模板  = 「重量」这个维度有哪几档（跨商品复用）
//   · 标准品    = 「本地菠菜」这件货长什么样（标题/图/类目/规格组）
//
// 页面上唯一"多余"的东西同样是每个选项前的编码输入框，理由也同样：
// 没有编码的标准品与商家手输没有区别，它唯一的作用是让人**以为**规格统一了。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { fill } from "@/lib/use-copy";
import { usePaging } from "@/lib/use-paging";
import type { SpuStd } from "@/lib/types";
import { ArchiveActions, ArchivedAt, archivedRowClass, ShowArchivedToggle } from "@/components/archive";
import { Badge } from "@/components/ui/badge";
import { Checkbox } from "@/components/ui/checkbox";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, Field } from "@/components/ui/drawer";
import { FilterSelect } from "@/components/ui/filter-select";
import { Input, Select } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Notice } from "@/components/ui/notice";
import { Pagination } from "@/components/ui/misc";
import { Toolbar } from "@/components/ui/toolbar";
import { useConfirm } from "@/components/ui/confirm-dialog";
import type { ProductsCopy } from "./copy";

/** 抽屉表单。规格组是**整份替换**的，所以直接持有数组，不做逐项 diff */
type Group = { name: string; options: string[]; optionCodes: string[] };
type Form = {
  stdNo?: string;
  categoryNo: string;
  title: string;
  subtitle: string;
  keywords: string;
  groups: Group[];
};

const EMPTY_GROUP = (): Group => ({ name: "", options: [""], optionCodes: [""] });
const EMPTY: Form = { categoryNo: "", title: "", subtitle: "", keywords: "", groups: [] };

export function SpuStdTab({ c, canEdit }: { c: ProductsCopy; canEdit: boolean }) {
  const qc = useQueryClient();
  const { confirm, dialog } = useConfirm();
  const { page, setPage, size, setSize } = usePaging();

  const [keyword, setKeyword] = useState("");
  const [categoryNo, setCategoryNo] = useState("");
  const [showArchived, setShowArchived] = useState(false);
  const [source, setSource] = useState("");
  const [form, setForm] = useState<Form | null>(null);
  /*
   * 勾选**只在当前这一页有效**，翻页即清空。
   *
   * 跨页累积看着更"强"，但它会造出一种很糟的状态：运营翻了六页、勾了几十条，
   * 而屏幕上只看得见最后一页 —— 点下去改了什么全凭记忆。那批导进来的标准品
   * 之所以是待审状态，就是因为要人**过目**；跨页累积恰好把过目这件事架空了。
   */
  const [picked, setPicked] = useState<string[]>([]);
  const clearPick = () => setPicked([]);

  const q = { keyword, categoryNo, source, showArchived, page, size };
  const list = useQuery({ queryKey: ["spu-std", q], queryFn: () => api.listSpuStd(q) });
  // 类目要能选：标准品的类目**必填**，形态由它派生
  const cats = useQuery({ queryKey: ["categories"], queryFn: () => api.listCategories() });

  const invalidate = () => qc.invalidateQueries({ queryKey: ["spu-std"] });

  const save = useMutation({
    mutationFn: () =>
      api.saveSpuStd({
        stdNo: form!.stdNo,
        categoryNo: form!.categoryNo,
        title: form!.title.trim(),
        subtitle: form!.subtitle.trim() || undefined,
        keywords: form!.keywords.trim() || undefined,
        specGroups: form!.groups.map((g) => ({
          name: g.name.trim(),
          options: g.options.map((o) => o.trim()),
          optionCodes: g.optionCodes.map((o) => o.trim()),
        })),
      }),
    onSuccess: () => { invalidate(); setForm(null); notify.success(c.stdToastSaved); },
  });
  const archive = useMutation({
    mutationFn: (no: string) => api.archiveSpuStd(no),
    onSuccess: () => { invalidate(); notify.success(c.stdToastArchived); },
  });
  const unarchive = useMutation({
    mutationFn: (no: string) => api.unarchiveSpuStd(no),
    onSuccess: () => { invalidate(); notify.success(c.stdToastUnarchived); },
  });
  const bulk = useMutation({
    mutationFn: (status: "ACTIVE" | "ARCHIVED") => api.bulkSpuStdStatus(picked, status),
    // 报**真正改动的条数**而不是勾选数：勾了 20 条其中 17 条本来就是启用的，
    // 说「已启用 20 条」是在骗人，而运营下一步就是按这个数去核对
    onSuccess: (r) => { invalidate(); clearPick(); notify.success(fill(c.stdToastBulk, { n: String(r.changed) })); },
  });

  const catOptions = (cats.data?.records ?? []).map((x) => ({ value: x.categoryNo, label: x.name }));

  const rows = list.data?.records ?? [];
  const pageNos = rows.map((t) => t.stdNo);
  const allPicked = pageNos.length > 0 && pageNos.every((no) => picked.includes(no));

  const columns: Column<SpuStd>[] = [
    {
      // 表头那个勾选框只管**本页**：它是「这一屏全选」，不是「全库全选」。
      // 全库全选正是这里刻意不做的那件事 —— 见 picked 的注释
      header: canEdit ? (
        <Checkbox aria-label={c.stdPickAll} checked={allPicked}
          onChange={(v) => setPicked(v ? pageNos : [])} />
      ) : "",
      width: "2.5rem",
      cell: (t) => canEdit ? (
        <Checkbox aria-label={t.title} checked={picked.includes(t.stdNo)}
          onChange={(v) => setPicked((p) =>
            v ? [...p, t.stdNo] : p.filter((x) => x !== t.stdNo))} />
      ) : null,
    },
    { header: c.stdColNo, cell: (t) => t.stdNo, numeric: true, align: "start" },
    { header: c.stdColTitle, cell: (t) => t.title },
    {
      // 出处：众包来的那批标题里混着品牌写法不一与错别字，运营得先知道自己在看哪一种
      header: c.stdColSource,
      cell: (t) => t.source === "OFF"
        ? <Badge tone="warning">{c.stdSourceOff}</Badge>
        : <Badge tone="muted">{c.stdSourceOps}</Badge>,
    },
    { header: c.stdColCategory, cell: (t) => t.categoryName ?? t.categoryNo },
    {
      header: c.stdColSpecs,
      className: "whitespace-normal",
      width: "22rem",
      // 编码与文案一起显示：只显示文案的话这一列看着就是普通自由文本，
      // 而编码才是这份标准品存在的理由
      cell: (t) => (
        <span className="flex flex-wrap gap-1">
          {t.specGroups.flatMap((g) =>
            g.options.map((o, i) => (
              <Badge key={`${g.name}-${o}`} tone="muted">
                <span className="tabular-nums">{g.optionCodes?.[i] ?? "—"}</span> · {o}
              </Badge>
            )))}
        </span>
      ),
    },
    {
      // 被引用次数：运营据此判断哪些标准品真的在用、哪些是录了没人碰的
      header: c.stdColRefCount,
      cell: (t) => <span className="tabular-nums">{t.refCount ?? 0}</span>,
      numeric: true,
    },
    { header: c.stdColArchivedAt, cell: (t) => <ArchivedAt at={t.archivedAt} /> },
    {
      header: c.colActions,
      cell: (t) => (
        <ArchiveActions
          archived={!!t.archivedAt}
          canWrite={canEdit}
          onArchive={async () => {
            const ok = await confirm({
              title: fill(c.stdConfirmArchiveTitle, { name: t.title }),
              // 归档**不影响已引用的商品** —— std_no 是溯源不是外键。
              // 不说这句的话，运营会以为归档会连带下架一批商品而不敢点
              desc: c.stdConfirmArchiveDesc,
              danger: true, confirmText: c.stdConfirmArchiveOk,
            });
            if (ok) archive.mutate(t.stdNo);
          }}
          onUnarchive={() => unarchive.mutate(t.stdNo)}
          actions={
            <Button size="sm" variant="outline"
              onClick={() => setForm({
                stdNo: t.stdNo,
                categoryNo: t.categoryNo,
                title: t.title,
                subtitle: t.subtitle ?? "",
                keywords: t.keywords ?? "",
                // 拷贝一份再编辑：直接改 query 缓存里的对象，取消后列表也已经变了
                groups: t.specGroups.map((g) => ({
                  name: g.name,
                  options: [...g.options],
                  optionCodes: [...(g.optionCodes ?? g.options.map(() => ""))],
                })),
              })}>
              {c.stdEdit}
            </Button>
          }
        />
      ),
    },
  ];

  const patchGroup = (gi: number, patch: Partial<Group>) =>
    setForm((f) => (f ? { ...f, groups: f.groups.map((g, i) => (i === gi ? { ...g, ...patch } : g)) } : f));

  const patchOption = (gi: number, oi: number, code: string | null, label: string | null) =>
    setForm((f) => {
      if (!f) return f;
      const g = f.groups[gi]!;
      return {
        ...f,
        groups: f.groups.map((x, i) => i !== gi ? x : {
          ...x,
          options: label === null ? x.options : x.options.map((o, k) => (k === oi ? label : o)),
          optionCodes: code === null ? x.optionCodes : x.optionCodes.map((o, k) => (k === oi ? code : o)),
        }),
      };
    });

  /** 存不下去的判据。**在前端也拦一道**：录一条标准品要填好几组规格，
   *  走到服务端才被拒等于让运营重填一遍 */
  const blocked = !form || !form.title.trim() || !form.categoryNo
    || form.groups.some((g) => !g.name.trim() || g.options.length === 0
      || g.options.some((o, i) => !o.trim() || !g.optionCodes[i]?.trim()));

  return (
    <>
      <Notice className="mb-3">{c.stdNotice}</Notice>

      <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder={c.stdSearch}>
        <FilterSelect aria-label={c.stdFilterCategory} value={categoryNo}
          onChange={(v) => { setCategoryNo(v); setPage(1); }}
          options={catOptions} allLabel={c.stdFilterCategoryAll} />
        <FilterSelect aria-label={c.stdFilterSource} value={source}
          onChange={(v) => { setSource(v); setPage(1); clearPick(); }}
          options={[{ value: "OPS", label: c.stdSourceOps }, { value: "OFF", label: c.stdSourceOff }]}
          allLabel={c.stdFilterSourceAll} />
        <ShowArchivedToggle checked={showArchived} onChange={(v) => { setShowArchived(v); setPage(1); clearPick(); }}
          label={c.stdShowArchived} />
        {canEdit && <Button size="sm" onClick={() => setForm({ ...EMPTY, groups: [EMPTY_GROUP()] })}>{c.stdNew}</Button>}
      </Toolbar>

      {picked.length > 0 && (
        <div className="mb-2 flex items-center gap-2 rounded-field border border-line bg-surface-2 px-3 py-2">
          <span className="text-sm text-fg-2">{fill(c.stdPickedN, { n: String(picked.length) })}</span>
          <Button size="sm" loading={bulk.isPending} onClick={() => bulk.mutate("ACTIVE")}>
            {c.stdBulkEnable}
          </Button>
          <Button size="sm" variant="outline" loading={bulk.isPending}
            onClick={async () => {
              const ok = await confirm({
                title: fill(c.stdBulkArchiveTitle, { n: String(picked.length) }),
                desc: c.stdConfirmArchiveDesc,
                danger: true, confirmText: c.stdConfirmArchiveOk,
              });
              if (ok) bulk.mutate("ARCHIVED");
            }}>
            {c.stdBulkArchive}
          </Button>
          <Button size="sm" variant="ghost" onClick={clearPick}>{c.stdPickClear}</Button>
        </div>
      )}

      <DataTable
        columns={columns} rows={list.data?.records} loading={list.isLoading}
        error={list.error} onRetry={() => list.refetch()}
        rowKey={(t) => t.stdNo}
        rowClassName={archivedRowClass}
        empty={c.stdEmpty}
      />
      <Pagination page={page} size={size} onSize={setSize} total={list.data?.total ?? 0}
        onPage={(p) => { setPage(p); clearPick(); }} />

      <Drawer
        open={!!form}
        onOpenChange={(o) => !o && setForm(null)}
        title={form?.stdNo ? c.stdDrawerEdit : c.stdDrawerNew}
        desc={form?.stdNo}
        width="w-[640px]"
        footer={form ? (
          <Button loading={save.isPending} disabled={blocked} onClick={() => save.mutate()}>
            {c.save}
          </Button>
        ) : null}
      >
        {form && (
          <div className="space-y-4">
            <div className="space-y-1">
              <Label htmlFor="std-title" required>{c.stdFieldTitle}</Label>
              <Input id="std-title" className="w-full" value={form.title} placeholder={c.stdTitlePh}
                onChange={(e) => setForm({ ...form, title: e.target.value })} />
            </div>

            <div className="space-y-1">
              <Label htmlFor="std-cat" required>{c.stdFieldCategory}</Label>
              <Select id="std-cat" className="w-full" value={form.categoryNo}
                onChange={(e) => setForm({ ...form, categoryNo: e.target.value })}>
                <option value="">{c.stdCategoryPh}</option>
                {catOptions.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
              </Select>
              {/* 类目决定形态（生鲜要截单、服务不发货），而商家取用后改不掉它 —— 说出来 */}
              <p className="txt-caption text-muted-foreground">{c.stdCategoryHint}</p>
            </div>

            <div className="space-y-1">
              <Label htmlFor="std-sub">{c.stdFieldSubtitle}</Label>
              <Input id="std-sub" className="w-full" value={form.subtitle}
                onChange={(e) => setForm({ ...form, subtitle: e.target.value })} />
            </div>

            <div className="space-y-1">
              <Label htmlFor="std-kw">{c.stdFieldKeywords}</Label>
              <Input id="std-kw" className="w-full" value={form.keywords} placeholder={c.stdKeywordsPh}
                onChange={(e) => setForm({ ...form, keywords: e.target.value })} />
              {/* 别名是搜得到的关键：商家嘴里的「洋芋」与标题「土豆」对不上时，
                  结果不是报错，是他以为标准库里没有 —— 然后自建一个 */}
              <p className="txt-caption text-muted-foreground">{c.stdKeywordsHint}</p>
            </div>

            <Field label={c.stdFieldSpecs}>
              <p className="mb-2 txt-caption text-muted-foreground">{c.stdSpecsHint}</p>
              <div className="space-y-3">
                {form.groups.map((g, gi) => (
                  <div key={gi} className="rounded-field border p-3 space-y-2">
                    <div className="flex items-center gap-2">
                      <Input className="w-40" value={g.name} placeholder={c.stdGroupNamePh}
                        onChange={(e) => patchGroup(gi, { name: e.target.value })} />
                      <Button size="sm" variant="ghost"
                        onClick={() => setForm({ ...form, groups: form.groups.filter((_, k) => k !== gi) })}>
                        {c.stdRemoveGroup}
                      </Button>
                    </div>
                    {g.options.map((o, oi) => (
                      <div key={oi} className="flex items-center gap-2">
                        <Input className="w-32 tabular-nums" value={g.optionCodes[oi] ?? ""}
                          placeholder={c.stdOptionCodePh}
                          onChange={(e) => patchOption(gi, oi, e.target.value, null)} />
                        <Input className="flex-1" value={o} placeholder={c.stdOptionLabelPh}
                          onChange={(e) => patchOption(gi, oi, null, e.target.value)} />
                        <Button size="sm" variant="ghost"
                          // 最后一行不给删：删空之后这一组没有任何输入框，看起来像坏了
                          disabled={g.options.length <= 1}
                          onClick={() => patchGroup(gi, {
                            options: g.options.filter((_, k) => k !== oi),
                            optionCodes: g.optionCodes.filter((_, k) => k !== oi),
                          })}>
                          {c.stdRemoveOption}
                        </Button>
                      </div>
                    ))}
                    <Button size="sm" variant="outline"
                      onClick={() => patchGroup(gi, {
                        options: [...g.options, ""], optionCodes: [...g.optionCodes, ""],
                      })}>
                      {c.stdAddOption}
                    </Button>
                  </div>
                ))}
              </div>
              <Button className="mt-2" size="sm" variant="outline"
                onClick={() => setForm({ ...form, groups: [...form.groups, EMPTY_GROUP()] })}>
                {c.stdAddGroup}
              </Button>
            </Field>
          </div>
        )}
      </Drawer>

      {dialog}
    </>
  );
}
