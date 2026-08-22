"use client";

// 平台类目（P-3.1）—— **已接真后端** `/ops/categories/**`。
//
// 这一页此前是「左树 + 右详情」：一屏只看得到十几行，改一个类目要点三下
// （选中 → 读详情 → 找按钮），而最高频的动作其实只有一个 —— **这一类这期做不做**。
//
// 现在是**一张带层级的表**：一级行 + 缩进的二级行，列对齐（形态 / 门槛 / 商品数 /
// 顺序 / 状态）。此前拆成一级一张卡，同一列的值在卡与卡之间对不齐，
// 想回答「哪些类目还没设门槛」得挨张卡去扫；表格一列扫到底就够了。
// 开关是立即生效的状态切换（Switch 的语义），不是待提交的表单勾选。
//
// 「停用」用的就是归档那套（`status=ARCHIVED`）：**不物理删** ——
// 已经归到这个类目下的商品还在，C 端历史链接也还指着它，删掉之后那些入口进来是 404，
// 而它本来只需要「这一类我们这期不做」。
import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { fill } from "@/lib/use-copy";
import type { Category } from "@/lib/types";
import { ShowArchivedToggle } from "@/components/archive";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, DrawerSection, Field } from "@/components/ui/drawer";
import { FilterSelect } from "@/components/ui/filter-select";
import { Input } from "@/components/ui/input";
import { Notice } from "@/components/ui/notice";
import { Switch } from "@/components/ui/switch";
import { Toolbar } from "@/components/ui/toolbar";
import { useConfirm } from "@/components/ui/confirm-dialog";
import type { ProductsCopy } from "./copy";

type Form = {
  categoryNo?: string;
  name: string;
  i18nEn: string;
  parentNo: string;
  template: string;
  requiredCode: string;
};

const EMPTY: Form = { name: "", i18nEn: "", parentNo: "", template: "STANDARD", requiredCode: "" };

const TEMPLATES = ["STANDARD", "FRESH", "SERVICE", "VOUCHER", "VIRTUAL"] as const;

export function CategoriesTab({ c, canEdit }: { c: ProductsCopy; canEdit: boolean }) {
  const qc = useQueryClient();
  const { confirm, dialog } = useConfirm();
  const [keyword, setKeyword] = useState("");
  const [template, setTemplate] = useState("");
  /*
   * 默认**不含停用**：本地与线上都会堆积历史归档（E2E 残留、下线的旧类目），
   * 默认带上的话首屏是一片灰卡片，真正在用的那几类反而要往下翻。
   * 要开哪一类时勾一下「含已停用」—— 那是一次明确的动作。
   */
  const [showArchived, setShowArchived] = useState(false);
  const [form, setForm] = useState<Form | null>(null);
  /**
   * 买家侧预览。**这一页的产出全是「要到别处才看得见效果」的东西** ——
   * 顺序改了、开关关了，运营在这张管理表上看到的仍是管理表，
   * 只能去 C 端反复刷新才知道对不对。
   *
   * <p>它渲染的是**同一份数据**（这一页已经拉到的类目），不是去抓 C 端页面 ——
   * 所以它证明的是「配置长这样」，不是「C 端此刻长这样」。这条差别写在面板里，
   * 免得有人拿它当线上验收。
   */
  const [preview, setPreview] = useState(false);
  /** 预览语言：切到 EN 才看得出缺译回落成中文 */
  const [previewLang, setPreviewLang] = useState<"zh" | "en">("zh");
  const [previewTop, setPreviewTop] = useState("");

  /*
   * **关键词不进请求**，在前端过滤。
   *
   * 后端按关键词过滤会把父级一起筛掉：搜「茶叶」命中的是二级 CAT160，
   * 而它的父级「食品生鲜」名字不含「茶」→ 不在结果里 → 分组渲染时
   * 那一条命中项**根本无处可挂，整个页面看着像没搜到**。
   * 类目总量只有几十条，一次全量在前端过滤更简单也更准。
   */
  const q = { template, showArchived };
  const cats = useQuery({ queryKey: ["categories", q], queryFn: () => api.listCategories(q) });
  // 门槛码只列启用中的：挂一个停用码，那个类目就永远拒绝所有人
  const authCodes = useQuery({ queryKey: ["auth-code-dict"], queryFn: () => api.listAuthCodeDict() });

  const invalidate = () => qc.invalidateQueries({ queryKey: ["categories"] });

  /**
   * 开关。**一级会连带问一句要不要带上它的二级** ——
   * 「开了一级、底下全关」是个空壳：商家在选择器里点进去空空如也，
   * 而运营以为自己把这一类放出去了。反过来关一级也一样：
   * 二级还开着的话，它们在别处（搜索、专题）仍会露出来。
   *
   * <p>后端对「还挂着子类目或在售商品」的类目**拒绝归档**（C 端类目树会断枝）。
   * 那条拒绝要原样说给运营听 —— 通用的「操作失败」会让他反复点同一个开关。
   */
  const toggle = useMutation({
    mutationFn: async (v: { row: Category; on: boolean; withChildren?: Category[] }) => {
      const one = (no: string) => (v.on ? api.unarchiveCategory(no) : api.archiveCategory(no));
      // 顺序有讲究：开时先开父、再开子；关时先关子、再关父 ——
      // 反过来会撞上后端「有子类目不能归档」那道校验
      if (v.on) {
        await one(v.row.categoryNo);
        for (const x of v.withChildren ?? []) await one(x.categoryNo);
      } else {
        for (const x of v.withChildren ?? []) await one(x.categoryNo);
        await one(v.row.categoryNo);
      }
    },
    onSuccess: (_, v) => { invalidate(); notify.success(v.on ? c.catEnabled : c.catDisabled); },
  });

  /**
   * 能不能停用 —— **与后端 `archive` 的两道判据一字不差**：
   * 下面还有商品、下面还有未归档的子类目。
   *
   * <p>端上先判是因为这两件事列表上都看得见（商品数、子级开关）。
   * 让人点一个注定被拒的开关，是最差的一种拒绝 —— 而批量关一棵树时更糟：
   * 前几个二级关掉了、剩下的连同一级一起报错，界面停在关了一半的状态。
   *
   * @return 不能停用的原因（给 tooltip 用）；能停用时为 `null`
   */
  function blockedReason(x: Category): string | null {
    if (x.skuCount > 0) return fill(c.catOffBlockedGoods, { n: x.skuCount });
    const kids = childrenOf(x.categoryNo).filter((k) => !off(k));
    const stuck = kids.find((k) => k.skuCount > 0);
    if (stuck) return fill(c.catOffBlockedChild, { name: stuck.name, n: stuck.skuCount });
    return null;
  }

  /** 一级的开关：先问清楚要不要连带子级，再发请求 */
  async function toggleTop(top: Category, on: boolean) {
    const kids = childrenOf(top.categoryNo);
    const affected = kids.filter((k) => off(k) === on);   // 开时挑关着的，关时挑开着的
    if (!affected.length) {
      toggle.mutate({ row: top, on });
      return;
    }
    const ok = await confirm({
      title: fill(on ? c.catEnableTopTitle : c.catDisableTopTitle, { name: top.name }),
      desc: fill(on ? c.catEnableTopDesc : c.catDisableTopDesc, { n: affected.length }),
      confirmText: on ? c.catEnableTopOk : c.catDisableTopOk,
      danger: !on,
    });
    if (ok) toggle.mutate({ row: top, on, withChildren: affected });
  }

  /**
   * 上下移：**交换相邻两个的 `sort`**，不是给自己 ±1。
   *
   * <p>±1 会撞上已有的值（种子是 10/20/30，但运营手填过的可能是 1、2），
   * 撞了之后同序的两条谁在前取决于数据库返回顺序 —— 那是「点了没反应」
   * 与「点一下跳两格」这类怪象的来源。交换是唯一稳定的做法。
   */
  const move = useMutation({
    mutationFn: async (v: { a: Category; b: Category }) => {
      const put = (x: Category, sort: number) =>
        api.saveCategory({
          categoryNo: x.categoryNo, name: x.name, i18nEn: x.i18n.en,
          parentNo: x.parentNo, template: x.template,
          qualifications: x.qualifications, requiredCode: x.requiredCode, sort,
        } as Parameters<typeof api.saveCategory>[0]);
      await put(v.a, v.b.sort ?? 0);
      await put(v.b, v.a.sort ?? 0);
    },
    onSuccess: () => invalidate(),
  });

  /** 同级里的邻居；停用的不参与换位 —— 它们本来就沉在底部，换过去看不出变化 */
  function neighbour(list: Category[], i: number, dir: -1 | 1) {
    const j = i + dir;
    return j >= 0 && j < list.length && !off(list[j]!) ? list[j] : undefined;
  }

  const save = useMutation({
    mutationFn: () =>
      api.saveCategory({
        categoryNo: form!.categoryNo,
        name: form!.name.trim(),
        i18nEn: form!.i18nEn.trim() || undefined,
        parentNo: form!.parentNo || undefined,
        template: form!.template,
        qualifications: [],
        requiredCode: form!.requiredCode || undefined,
      } as Parameters<typeof api.saveCategory>[0]),
    onSuccess: () => { invalidate(); setForm(null); notify.success(c.catSaved); },
  });

  const all = cats.data ?? [];
  /** 命中：自己名字含关键词，或它的父级命中（父级命中时整组保留） */
  const rows = useMemo(() => {
    const kw = keyword.trim().toLowerCase();
    if (!kw) return all;
    const hit = (x: Category) =>
      x.name.toLowerCase().includes(kw) || (x.i18n.en ?? "").toLowerCase().includes(kw)
      || x.categoryNo.toLowerCase().includes(kw);
    const hitTops = new Set(all.filter((x) => x.level === 1 && hit(x)).map((x) => x.categoryNo));
    const keep = all.filter((x) => hit(x) || (x.parentNo && hitTops.has(x.parentNo)));
    // 命中的二级要把父级带回来，否则它没有可挂的分组
    const parents = new Set(keep.map((x) => x.parentNo).filter(Boolean) as string[]);
    return all.filter((x) => keep.includes(x) || parents.has(x.categoryNo));
  }, [all, keyword]);
  const off = (x: Category) => !!x.archivedAt;
  /**
   * 停用的沉底、其余按 `sort` —— **顺序是这一页的产出之一**：
   * C 端类目栏就按它排，所以看到的顺序必须与买家看到的一致，
   * 否则运营调完顺序在这里看不出变化，只能去 C 端反复刷新验证。
   */
  const byLive = (a: Category, b: Category) =>
    Number(off(a)) - Number(off(b)) || (a.sort ?? 0) - (b.sort ?? 0);
  const tops = useMemo(() => rows.filter((x) => x.level === 1).sort(byLive), [rows]);
  const childrenOf = (no: string) => rows.filter((x) => x.parentNo === no).sort(byLive);

  /** 门槛码 → 展示名。手输的码不该出现在这里，所以查不到就原样显示，一眼看得出不对 */
  const codeName = (code?: string) =>
    code ? (authCodes.data ?? []).find((a) => a.code === code)?.name ?? code : "";
  /** 这个码发不出来（停用或不存在）—— 挂着它的类目谁都上不了架 */
  const codeBroken = (code?: string) =>
    !!code && !(authCodes.data ?? []).some((a) => a.code === code && a.enabled);

  const stat = {
    tops: tops.length,
    subs: rows.filter((x) => x.level === 2).length,
    off: rows.filter(off).length,
  };

  /**
   * 拍平成「一级 + 它的二级」一条条行 —— 层级靠行样式与缩进表达，不再拆成卡片。
   * 一级下面没有二级时仍然出现：那正是需要被看到的状态（买家点进去是空的）。
   */
  const flat = useMemo(() => {
    const out: Category[] = [];
    for (const t of tops) {
      out.push(t);
      out.push(...childrenOf(t.categoryNo));
    }
    return out;
  }, [tops, rows]);

  /** 同级邻居（排序用）：一级看 tops，二级看它父级下的兄弟 */
  const siblings = (r: Category) =>
    r.level === 1 ? tops : childrenOf(r.parentNo ?? "");

  const columns: Column<Category>[] = [
    {
      header: c.catColName,
      cell: (r) => (
        <span className={r.level === 2 ? "pl-5" : ""}>
          <button
            type="button"
            className="text-left hover:underline"
            onClick={() => canEdit && setForm({
              categoryNo: r.categoryNo, name: r.name, i18nEn: r.i18n.en ?? "",
              parentNo: r.parentNo ?? "", template: r.template,
              requiredCode: r.requiredCode ?? "",
            })}
          >
            {r.name}
          </button>
          {/* 缺英文名会在 C 端英文界面静默回落中文 —— 这一页看不见就永远没人补 */}
          {!r.i18n.en && (
            <span title={c.catI18nMissingHint} className="ml-2">
              <Badge tone="muted">{c.catI18nMissing}</Badge>
            </span>
          )}
        </span>
      ),
      className: "whitespace-normal",
      width: "16rem",
    },
    { header: c.catColTemplate, cell: (r) => codeLabel(c, r.template) },
    {
      header: c.fieldRequiredCode,
      cell: (r) => {
        if (!r.requiredCode)
          return <span className="text-muted-foreground">{c.catNoGateShort}</span>;
        const label = codeName(r.requiredCode);
        const title = `${label}（${r.requiredCode}）${(r.qualifications ?? []).join("、")}`;
        // 发不出来的码要红，**而且从不缩写**：那种类目谁都上不了架，报错又说不清原因
        if (codeBroken(r.requiredCode))
          return (
            <span title={title}><Badge tone="danger">{c.catGateBroken}</Badge></span>
          );
        // 码名与类目同名（蔬菜→蔬菜、酒类→酒类）时只留一个点：整列把类目名再读一遍，
        // 真正要在这一列找的「哪几个没门槛、哪个坏了」反而被淹掉。全名留在 title 里。
        // 用实心点而不是「·」：无门槛那格是「—」，两个细长灰符号并排根本分不出，
        // 而它们的意思正好相反
        if (label === r.name)
          return <span title={title} className="text-info" aria-label={title}>●</span>;
        return (
          <span title={title}><Badge tone="info">{label}</Badge></span>
        );
      },
    },
    // 0 不显示：一列的「0」会把真正有货的那几行淹掉
    { header: c.catColGoods, cell: (r) => r.skuCount || "", numeric: true },
    {
      header: c.catColOrder,
      cell: (r) => {
        if (!canEdit || off(r)) return null;
        const list = siblings(r);
        const i = list.findIndex((x) => x.categoryNo === r.categoryNo);
        return (
          <span className="flex">
            <Button size="sm" variant="ghost"
              disabled={!neighbour(list, i, -1) || move.isPending}
              onClick={() => { const b = neighbour(list, i, -1); if (b) move.mutate({ a: r, b }); }}
            >{c.catMoveUp}</Button>
            <Button size="sm" variant="ghost"
              disabled={!neighbour(list, i, 1) || move.isPending}
              onClick={() => { const b = neighbour(list, i, 1); if (b) move.mutate({ a: r, b }); }}
            >{c.catMoveDown}</Button>
          </span>
        );
      },
    },
    {
      header: c.catColStatus,
      // 停用被后端拒的两种情况在这里就置灰，别让人点一个注定失败的开关
      cell: (r) => (
        <span title={off(r) ? undefined : blockedReason(r) ?? undefined}>
          <Switch
            checked={!off(r)}
            disabled={!canEdit || (!off(r) && !!blockedReason(r))}
            onChange={(on) => (r.level === 1 ? void toggleTop(r, on) : toggle.mutate({ row: r, on }))}
          />
        </span>
      ),
    },
    {
      header: c.colActions,
      cell: (r) =>
        canEdit && r.level === 1 ? (
          <Button size="sm" variant="ghost" onClick={() => openNew(r.categoryNo, r.template)}>
            {c.catAddChild}
          </Button>
        ) : null,
    },
  ];

  /** 预览只看启用中的 —— 停用的不出现，这正是运营要确认的那件事 */
  const liveTops = tops.filter((x) => !off(x));
  const liveChildren = childrenOf(previewTop || liveTops[0]?.categoryNo || "").filter((x) => !off(x));

  function openNew(parentNo: string, tpl: string) {
    setForm({ ...EMPTY, parentNo, template: tpl });
  }

  return (
    <>
      <Notice className="mb-3">{c.catTreeNotice}</Notice>

      <Toolbar search={keyword} onSearch={setKeyword} searchPlaceholder={c.catSearchPh}>
        <FilterSelect
          value={template}
          onChange={setTemplate}
          options={[
            { value: "", label: c.catAllTemplates },
            ...TEMPLATES.map((t) => ({ value: t, label: c[`tpl${t[0]}${t.slice(1).toLowerCase()}` as keyof ProductsCopy] as string })),
          ]}
        />
        <ShowArchivedToggle checked={showArchived} onChange={setShowArchived} label={c.catShowOff} />
        <Button variant="outline" onClick={() => setPreview(true)}>{c.catPreview}</Button>
        {canEdit && <Button onClick={() => setForm({ ...EMPTY })}>{c.catNew}</Button>}
      </Toolbar>

      <p className="mb-3 txt-caption text-muted-foreground">
        {fill(c.catStat, { a: stat.tops, b: stat.subs, c: stat.off })}
      </p>

      {/*
        这一页故意比别的表紧一档：它是**配置表**，一屏五十来行、要来回比对同一列，
        而不是「读几条记录」。走既有的 [data-density] 令牌而不是写死行高 ——
        写死的话密度切换在这一页会失效，而且与 --ctl-h 错开、行里的按钮会顶出格。
      */}
      <div data-density="dense">
      <DataTable
        rows={flat}
        columns={columns}
        rowKey={(r) => r.categoryNo}
        loading={cats.isLoading}
        error={cats.error}
        onRetry={() => cats.refetch()}
        empty={c.emptyTree}
        // 一级行加底色、二级行降一档字重：层级要靠**行本身**表达，
        // 只靠缩进的话滚到中间就分不清自己在哪一组
        rowClassName={(r) =>
          [r.level === 1 ? "bg-secondary/40 font-medium" : "", off(r) ? "opacity-55" : ""]
            .filter(Boolean).join(" ")
        }
      />
      </div>

      {/*
        编辑抽屉。「形态」与「门槛码」是这里最要紧的两个字段：
        形态决定商品长什么样（生鲜要截单、服务不发货），门槛码决定谁能卖。
        二级的形态继承父级，所以是只读的。
      */}
      <Drawer
        open={!!form}
        onOpenChange={(o) => !o && setForm(null)}
        title={form?.categoryNo ? c.catEdit : c.catNew}
      >
        {form && (
          <DrawerSection first title={c.catFormBasic}>
            <Field label={c.fieldCatName}>
              <Input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
            </Field>
            <Field label={c.fieldCatNameEn}>
              <Input value={form.i18nEn} onChange={(e) => setForm({ ...form, i18nEn: e.target.value })} />
            </Field>
            <Field label={c.fieldParent}>
              <FilterSelect
                value={form.parentNo}
                onChange={(v) => {
                  const parent = rows.find((x) => x.categoryNo === v);
                  setForm({ ...form, parentNo: v, template: parent?.template ?? form.template });
                }}
                options={[
                  { value: "", label: c.catParentNone },
                  ...tops.map((x) => ({ value: x.categoryNo, label: x.name })),
                ]}
              />
            </Field>
            <Field label={c.fieldTemplate}>
              {form.parentNo ? (
                <div className="txt-body text-muted-foreground">
                  {codeLabel(c, form.template)}
                  <span className="ml-2 txt-caption">{c.catTemplateInherited}</span>
                </div>
              ) : (
                <FilterSelect
                  value={form.template}
                  onChange={(v) => setForm({ ...form, template: v })}
                  options={TEMPLATES.map((t) => ({ value: t, label: codeLabel(c, t) }))}
                />
              )}
            </Field>
            {/* 门槛码只能从字典里挑、不能手输 —— 输错一个字母就是一个永不命中的门槛，且不报错 */}
            <Field label={c.fieldRequiredCode}>
              <FilterSelect
                value={form.requiredCode}
                onChange={(v) => setForm({ ...form, requiredCode: v })}
                options={[
                  { value: "", label: c.catNoGate },
                  ...(authCodes.data ?? [])
                    .filter((a) => a.enabled)
                    .map((a) => ({ value: a.code, label: `${a.name}（${a.code}）` })),
                ]}
              />
            </Field>
            <p className="mt-1 txt-caption text-muted-foreground">{c.catGateHint}</p>
            <Button
              className="mt-4"
              disabled={!form.name.trim() || save.isPending}
              onClick={() => save.mutate()}
            >
              {c.catSave}
            </Button>
          </DrawerSection>
        )}
      </Drawer>
      {/*
        预览：只渲染**启用中的**、按 sort 排 —— 与买家看到的规则一致。
        停用的不出现，这正是运营要确认的那件事。
      */}
      <Drawer open={preview} onOpenChange={setPreview} title={c.catPreview} desc={c.catPreviewDesc}>
        <DrawerSection first title={c.catPreviewLang}>
          <div className="flex gap-2">
            {(["zh", "en"] as const).map((lg) => (
              <Button
                key={lg}
                size="sm"
                variant={previewLang === lg ? "default" : "outline"}
                onClick={() => setPreviewLang(lg)}
              >
                {lg === "zh" ? c.catPreviewZh : c.catPreviewEn}
              </Button>
            ))}
          </div>
        </DrawerSection>
        <DrawerSection title={c.catPreviewPane}>
          {/* 窄容器：类目栏在手机上就是这个宽度，按桌面宽度预览会看不出换行与截断 */}
          <div className="mx-auto w-[320px] rounded-card border border-[var(--border)] bg-card p-3">
            <div className="flex flex-wrap gap-2">
              {liveTops.map((t) => (
                <button
                  key={t.categoryNo}
                  type="button"
                  onClick={() => setPreviewTop(t.categoryNo)}
                  className={`rounded-field px-2.5 py-1 txt-caption ${
                    (previewTop || liveTops[0]?.categoryNo) === t.categoryNo
                      ? "bg-primary text-primary-foreground"
                      : "bg-secondary"
                  }`}
                >
                  {shown(t, previewLang)}
                </button>
              ))}
            </div>
            <ul className="mt-3 space-y-1">
              {liveChildren.map((x) => (
                <li key={x.categoryNo} className="txt-body">
                  {shown(x, previewLang)}
                  {previewLang === "en" && !x.i18n.en && (
                    <span className="ml-2 txt-caption text-[var(--warning)]">{c.catPreviewFallback}</span>
                  )}
                </li>
              ))}
              {!liveChildren.length && (
                <li className="txt-caption text-muted-foreground">{c.catPreviewEmptyGroup}</li>
              )}
            </ul>
          </div>
        </DrawerSection>
      </Drawer>

      {dialog}
    </>
  );
}

/**
 * 按预览语言取名字。**缺译回落中文**（R9）—— 预览要如实展示这个回落，
 * 否则运营会以为英文界面上是空的或是英文。
 */
function shown(x: Category, lang: "zh" | "en") {
  return lang === "en" ? x.i18n.en || x.i18n.zh : x.name;
}

/** 形态 → 文案。集中一处，避免每个用到的地方各拼一次 */
function codeLabel(c: ProductsCopy, template: string) {
  const map: Record<string, string> = {
    STANDARD: c.tplStandard, FRESH: c.tplFresh, SERVICE: c.tplService,
    VOUCHER: c.tplVoucher, VIRTUAL: c.tplVirtual,
  };
  return map[template] ?? template;
}
