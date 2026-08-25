"use client";

// 「配这一类的规格」这个抽屉本身 —— **两个 tab 共用同一份**。
//
// 为什么抽出来：配规格的动手场景在**类目树**上（那里有父子关系、有同级类目
// 配了什么可以照着抄），而「类目 × 规格」那张表是巡检视角（横向比 31 行、
// 把缺口顶到眼前）。两种视角都有用，但**动手不该被迫切换视角** ——
// 从前点一下「未配」就跳去另一个 tab，类目树的上下文全丢了。
//
// 抽屉自己拉数据（list + dims），所以放哪个 tab 里都能独立工作。
import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { fill } from "@/lib/use-copy";
import type { CategorySpec, CategorySpecBinding, SpecDim } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Drawer } from "@/components/ui/drawer";
import { FilterSelect } from "@/components/ui/filter-select";
import type { ProductsCopy } from "./copy";

/** 抽屉里的编辑态：一个类目的绑定是一组**有序**的东西，所以用数组不用 Set */
export type Editing = {
  category: CategorySpec;
  bindings: CategorySpecBinding[];
};

/**
 * 把一行的现状翻成可编辑的绑定。
 *
 * <p>换名（label_override）靠**与规格库原值比对**反推：`/ops/category-specs`
 * 下发的 label 已经是换名后的，与 `/ops/spec-dims` 里的原 label 不同就说明换过。
 * 只回填真的换过的 —— 否则保存时会把一堆等于原名的「换名」写进去。
 */
export function toEditing(r: CategorySpec, all: SpecDim[]): Editing {
  return {
    category: r,
    bindings: r.dims.map((d) => ({
      dimNo: d.dimNo,
      usageType: d.usage,
      primary: d.primary,
      required: false,
      valueNos: d.values.map((v) => v.valueNo),
      labels: Object.fromEntries(
        d.values
          .map((v) => {
            const src = all.find((x) => x.dimNo === d.dimNo)
              ?.values.find((x) => x.valueNo === v.valueNo);
            return src && src.label !== v.label ? [v.valueNo, v.label] : null;
          })
          .filter(Boolean) as [string, string][],
      ),
    })),
  };
}

/**
 * 规格配置抽屉。`categoryNo` 为空 = 关着。
 *
 * <p>拿 categoryNo 而不是拿整行数据：调用方（类目树）手上只有类目号，
 * 让它先去拼一份 CategorySpec 只会让两处的数据形状对不齐。
 */
export function CategorySpecDrawer({
  c, canEdit, categoryNo, onClose,
}: {
  c: ProductsCopy; canEdit: boolean; categoryNo: string | null; onClose: () => void;
}) {
  const qc = useQueryClient();
  const [editing, setEditing] = useState<Editing | null>(null);

  const list = useQuery({ queryKey: ["category-specs"], queryFn: () => api.listCategorySpecs() });
  // 抽屉里要能从全量维度里挑，所以两个分区都拉
  const dims = useQuery({ queryKey: ["spec-dims", "all"], queryFn: () => api.listSpecDims({}) });

  /*
   * categoryNo 变了就重新翻一份编辑态。**不能在 categoryNo 不变时重跑** ——
   * 那会把用户改了一半的绑定冲回服务端的样子，而他看不出发生了什么。
   */
  useEffect(() => {
    if (!categoryNo) { setEditing(null); return; }
    const row = (list.data ?? []).find((r) => r.categoryNo === categoryNo);
    if (!row) return;   // 数据还没到，下一次渲染再试
    setEditing(toEditing(row, dims.data ?? []));
    // dims 后到会让换名反推不准，所以它也进依赖；editing 自身不进，否则每次改都被冲掉
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [categoryNo, list.data, dims.data]);

  const save = useMutation({
    mutationFn: () => api.saveCategorySpecs(editing!.category.categoryNo, editing!.bindings),
    onSuccess: () => {
      onClose();
      qc.invalidateQueries({ queryKey: ["category-specs"] });
      qc.invalidateQueries({ queryKey: ["spec-dims"] });
      notify.success(c.save);
    },
  });

  return (
    <Drawer
      open={!!categoryNo} onOpenChange={(o) => !o && onClose()}
      title={editing ? fill(c.csEditTitle, { name: editing.category.categoryName }) : ""}
      desc={categoryNo ?? ""}
      width="w-[680px]"
      footer={editing && canEdit ? (
        <Button loading={save.isPending} onClick={() => save.mutate()}>{c.csEditOk}</Button>
      ) : null}
    >
      {editing && (
        <>
          {/*
            **只在一条都没配的时候出现。**已配好的类目打开抽屉先看到
            「照哪个类目配」是纯噪音 —— 他要改的是眼前这几个维度，
            而照抄会把它们整份冲掉。空类目才是需要它的那一刻。
          */}
          {!editing.bindings.length && (
            <CopyFrom
              c={c} rows={list.data ?? []} all={dims.data ?? []} self={editing.category.categoryNo}
              onPick={(bindings) => setEditing({ ...editing, bindings })}
            />
          )}
          <BindingEditor
            c={c}
            all={dims.data ?? []}
            editing={editing}
            onChange={(bindings) => setEditing({ ...editing, bindings })}
          />
        </>
      )}
    </Drawer>
  );
}

/**
 * 「照某个类目配」。
 *
 * <p>新类目往往和某个同级几乎一样（新增「豆制品」，配置和「蔬菜」差不多），
 * 而从零挑 4 个维度、每个维度再挑 3–5 个取值，是二十来次点击。
 * 照抄再改一两处，比从空白开始快得多，**也更不容易漏**：
 * 从零配的人想不起「保质期」这种不显眼但该有的维度。
 *
 * <p>只抄配置不抄换名之外的东西 —— 换名是类目专属的（500g 在蔬菜叫「约1斤」），
 * 抄过来照样合适的居多，不合适他改掉就是；而丢掉它等于让他重新敲一遍。
 */
function CopyFrom({ c, rows, all, self, onPick }: {
  c: ProductsCopy; rows: CategorySpec[]; all: SpecDim[]; self: string;
  onPick: (b: CategorySpecBinding[]) => void;
}) {
  const [from, setFrom] = useState("");
  const sources = rows.filter((r) => r.categoryNo !== self && r.dimCount > 0);
  if (!sources.length) return null;
  return (
    <div className="mb-5 flex items-center gap-2">
        <FilterSelect
          value={from} onChange={setFrom}
          options={[{ value: "", label: c.csCopyFromPh },
            ...sources.map((r) => ({
              value: r.categoryNo,
              label: `${r.parentName} · ${r.categoryName}（${r.dimCount}）`,
            }))]}
        />
      <Button size="sm" variant="secondary" disabled={!from}
        onClick={() => {
          const src = sources.find((r) => r.categoryNo === from);
          if (src) onPick(toEditing(src, all).bindings);
        }}>
        {c.csCopyDo}
      </Button>
    </div>
  );
}

/**
 * 绑定编辑器：左边是已选（有序，第一个是主维度），右边是可选。
 *
 * <p>「主维度」用<b>顺序</b>表达而不是一个单选钮：它就是「排第一的那个」——
 * 两种表示并存的话，用户会遇到「排在第二却标着主」的状态，而那时预填哪一个说不清。
 */
function BindingEditor({ c, all, editing, onChange }: {
  c: ProductsCopy; all: SpecDim[]; editing: Editing;
  onChange: (b: CategorySpecBinding[]) => void;
}) {
  const picked = editing.bindings;
  const pickedNos = new Set(picked.map((b) => b.dimNo));
  const rest = all.filter((d) => !pickedNos.has(d.dimNo) && d.status === "ACTIVE");
  const dimOf = (no: string) => all.find((d) => d.dimNo === no);

  /*
   * **销售规格与商品参数分开配置。**
   *
   * 它们的性质相反：销售规格进笛卡尔积生成 SKU，每一档要单独定价备库存；
   * 商品参数一项也不进，买家不用挑，只是看。混在一张列表里配的后果是
   * 「主维度」这个徽标会落到「产地」头上 —— 而参数根本没有主维度这回事，
   * 排序也不影响任何东西（SKU 的排列由销售规格决定）。
   *
   * usage 判据取绑定上的覆盖，没有才回落维度自身 —— 与后端 forCategory 同一条：
   * 同一个「口味」在熟食是 SALE、在预包装是 PROP。
   */
  const usageOf = (b: CategorySpecBinding) =>
    b.usageType || dimOf(b.dimNo)?.usageType || "SALE";
  const sale = picked.filter((b) => usageOf(b) !== "PROP");
  const props = picked.filter((b) => usageOf(b) === "PROP");

  /**
   * 在**销售规格这一段之内**挪位置。
   *
   * <p>主维度 = 销售规格里排第一的那个。参数不参与 ——
   * 上一版用的是整个 picked 的下标，于是参数排在最前面时，
   * 「主维度」会落到参数头上，而那个标记在建品页决定「自动带出哪一组」。
   */
  const move = (i: number, to: number) => {
    if (to < 0 || to >= sale.length) return;
    const next = [...sale];
    const [x] = next.splice(i, 1);
    next.splice(to, 0, x!);
    onChange([...next.map((b, k) => ({ ...b, primary: k === 0 })), ...props]);
  };

  return (
    <div className="space-y-5">
      {([
        { key: "sale", rows: sale, title: c.csSectionSale, hint: c.csSectionSaleHint, isProp: false },
        { key: "props", rows: props, title: c.csSectionProp, hint: c.csSectionPropHint, isProp: true },
      ] as const).map((sec) => (
      <div key={sec.key}>
        <div className="mb-1 txt-label text-muted-foreground">{sec.title}</div>
        <p className="mb-2 text-[12px] text-muted-foreground">{sec.hint}</p>
        <div className="space-y-2">
          {sec.rows.map((b, i) => {
            const d = dimOf(b.dimNo);
            if (!d) return null;
            return (
              <div key={b.dimNo} className="rounded-card border border-border p-3">
                <div className="flex items-center gap-2">
                  <span className="font-semibold">{d.name}</span>
                  {/* 主维度只属于销售规格 —— 参数没有「自动带出哪一组」这回事 */}
                  {!sec.isProp && i === 0 && <Badge tone="default">{c.csPrimary}</Badge>}
                  <Badge tone={d.universal ? "info" : "muted"}>
                    {d.universal ? c.csUniversal : c.csDedicated}
                  </Badge>
                  <span className="ml-auto flex gap-1">
                    {/* 排序也只属于销售规格：SKU 的排列由它决定，参数排前排后没有区别 */}
                    {!sec.isProp && <Button size="sm" variant="ghost" onClick={() => move(i, i - 1)}>↑</Button>}
                    {!sec.isProp && <Button size="sm" variant="ghost" onClick={() => move(i, i + 1)}>↓</Button>}
                    <Button size="sm" variant="ghost"
                      onClick={() => onChange(picked.filter((x) => x.dimNo !== b.dimNo)
                        .map((x, k) => ({ ...x, primary: k === 0 })))}>
                      {c.csUnbind}
                    </Button>
                  </span>
                </div>
                {/*
                  取值分成两排：**上排是已选的、按商家实际看到的顺序**，下排是还没选的。
                  从前两者混在一排靠高亮区分，于是「顺序」这件事既看不见也调不了 ——
                  而它一直在生效（保存时按数组下标写 sort，商家侧就按 sort 展示）。
                  一个默默生效、界面上却不存在的东西，比没有更难查。
                */}
                {/*
                  **不写「已选取值」这行标签。**蓝底 + ‹ › + × 本身就是「选中且可排序」，
                  再加一行字是把界面已经说清的事又说一遍 —— 而它在每个维度下重复一次，
                  五个维度就是五行噪音。
                */}
                <div className="mt-2 flex flex-wrap items-center gap-1.5">
                  {b.valueNos.map((vn, vi) => {
                    const v = d.values.find((x) => x.valueNo === vn);
                    if (!v) return null;
                    const shown = b.labels[vn] ?? v.label;
                    const setVals = (valueNos: string[]) =>
                      onChange(picked.map((x) => x.dimNo === b.dimNo ? { ...x, valueNos } : x));
                    const moveVal = (to: number) => {
                      if (to < 0 || to >= b.valueNos.length) return;
                      const next = [...b.valueNos];
                      const [x] = next.splice(vi, 1);
                      next.splice(to, 0, x!);
                      setVals(next);
                    };
                    return (
                      <span key={vn}
                        className="inline-flex items-center gap-0.5 rounded-chip
                                   bg-[var(--primary)] px-1 py-0.5 text-[12px] text-white">
                        <button type="button" title={c.csValMoveL} disabled={vi === 0}
                          onClick={() => moveVal(vi - 1)}
                          className="px-0.5 leading-none opacity-70 hover:opacity-100
                                     disabled:cursor-default disabled:opacity-25">‹</button>
                        <span className="px-0.5" title={c.csRenameHint}
                          onDoubleClick={() => {
                            // 双击换名：500g 在蔬菜下叫「约1斤」，归一量不变
                            const next = window.prompt(
                              fill(c.csRenamePrompt, { cat: editing.category.categoryName, label: v.label }),
                              shown,
                            );
                            if (next == null) return;
                            const labels = { ...b.labels };
                            if (next.trim() && next.trim() !== v.label) labels[v.valueNo] = next.trim();
                            else delete labels[v.valueNo];
                            onChange(picked.map((x) => x.dimNo === b.dimNo ? { ...x, labels } : x));
                          }}>
                          {shown}
                          {b.labels[vn] && <span className="opacity-70"> ← {v.label}</span>}
                        </span>
                        <button type="button" title={c.csValMoveR} disabled={vi === b.valueNos.length - 1}
                          onClick={() => moveVal(vi + 1)}
                          className="px-0.5 leading-none opacity-70 hover:opacity-100
                                     disabled:cursor-default disabled:opacity-25">›</button>
                        <button type="button" title={c.csValDrop}
                          onClick={() => setVals(b.valueNos.filter((x) => x !== vn))}
                          className="ml-0.5 px-0.5 leading-none opacity-70 hover:opacity-100">×</button>
                      </span>
                    );
                  })}
                  {!b.valueNos.length && (
                    <span className="text-[12px] text-muted-foreground">{c.csValsAllHint}</span>
                  )}
                </div>

                <div className="mt-2.5 txt-label text-muted-foreground">{c.csValsRest}</div>
                <div className="mt-1 flex flex-wrap gap-1.5">
                  {d.values.filter((v) => v.status === "ACTIVE" && !b.valueNos.includes(v.valueNo))
                    .map((v) => (
                      <button key={v.valueNo} type="button"
                        onClick={() => onChange(picked.map((x) => x.dimNo === b.dimNo
                          ? { ...x, valueNos: [...x.valueNos, v.valueNo] } : x))}
                        className="rounded-chip bg-muted px-2 py-0.5 text-[12px] text-muted-foreground
                                   hover:bg-border">
                        {v.label}
                      </button>
                    ))}
                </div>
              </div>
            );
          })}
          {!sec.rows.length && <p className="text-[13px] text-muted-foreground">{c.csNone}</p>}
        </div>
      </div>
      ))}

      {/*
        候选也分两段：**加销售规格与加参数是两件事**。
        一张混合列表里，运营点「产地」以为在配参数，实际上它会成为第 N 个销售维度
        （usageType 跟着维度自身走，但主维度与排序的语义已经错位）。
      */}
      {([
        { key: "sale", title: c.csPickSale, isProp: false },
        { key: "props", title: c.csPickProp, isProp: true },
      ] as const).map((sec) => {
        const items = rest.filter((d) => (d.usageType === "PROP") === sec.isProp);
        if (!items.length) return null;
        return (
      <div key={sec.key}>
        <div className="mb-2 txt-label text-muted-foreground">{sec.title}</div>
        <div className="flex flex-wrap gap-1.5">
          {items.map((d) => (
            <button key={d.dimNo} type="button"
              onClick={() => onChange([...picked, {
                dimNo: d.dimNo, usageType: d.usageType,
                // 主维度只在销售规格里成立，且只有这一段还空着时才是它
                primary: !sec.isProp && sale.length === 0,
                required: false, valueNos: [], labels: {},
              }])}
              className="inline-flex items-center gap-1.5 rounded-chip border border-border
                         px-2.5 py-1 text-[12px] hover:bg-muted">
              {d.name}
              <Badge tone={d.universal ? "info" : "muted"}>
                {d.universal ? c.csUniversal : c.csDedicated}
              </Badge>
            </button>
          ))}
        </div>
      </div>
        );
      })}
    </div>
  );
}
