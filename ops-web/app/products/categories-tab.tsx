"use client";

// 平台类目（P-3.1）—— **已接真后端** `/ops/categories/**`。
//
// 这一页此前是「左树 + 右详情」：一屏只看得到十几行，改一个类目要点三下
// （选中 → 读详情 → 找按钮），而最高频的动作其实只有一个 —— **这一类这期做不做**。
//
// 现在按一级分组铺成卡片网格，每个二级一行：名字、门槛、商品数、一个开关。
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
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
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

  const q = { keyword, template, showArchived };
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

  const rows = cats.data ?? [];
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
        {canEdit && <Button onClick={() => setForm({ ...EMPTY })}>{c.catNew}</Button>}
      </Toolbar>

      <p className="mb-3 txt-caption text-muted-foreground">
        {fill(c.catStat, { a: stat.tops, b: stat.subs, c: stat.off })}
      </p>

      {cats.isLoading ? (
        <div className="py-8 text-center text-muted-foreground">{c.loading}</div>
      ) : !tops.length ? (
        <div className="py-8 text-center text-muted-foreground">{c.emptyTree}</div>
      ) : (
        <div className="grid gap-3 lg:grid-cols-2 2xl:grid-cols-3">
          {tops.map((top) => (
            <Card key={top.categoryNo} className={off(top) ? "opacity-55" : undefined}>
              <CardHeader className="flex-row items-center gap-2 pb-2">
                <CardTitle className="flex min-w-0 items-center gap-2">
                  <span className="truncate">{top.name}</span>
                  <Badge tone="muted">{codeLabel(c, top.template)}</Badge>
                </CardTitle>
                {/* 计数放标题旁：扫一眼就知道这一组有多少二级，不用去数行 */}
                <span className="txt-caption text-muted-foreground">
                  {fill(c.catSubCount, { n: childrenOf(top.categoryNo).length })}
                </span>
                <span className="flex-1" />
                {/* 「加二级」收进卡头：放底部会为一个低频动作白占一整行 */}
                {canEdit && (
                  <Button size="sm" variant="ghost" onClick={() => openNew(top.categoryNo, top.template)}>
                    {c.catAddChild}
                  </Button>
                )}
                {canEdit && (
                  <Switch checked={!off(top)} onChange={(on) => void toggleTop(top, on)} />
                )}
              </CardHeader>
              <CardContent className="pt-0">
                <ul className="divide-y divide-[var(--border)]">
                  {childrenOf(top.categoryNo).map((sub, i, list) => (
                    <li
                      key={sub.categoryNo}
                      className={`group flex items-center gap-2 py-1.5 ${off(sub) ? "opacity-55" : ""}`}
                    >
                      <button
                        type="button"
                        className="min-w-0 flex-1 truncate text-left txt-body hover:underline"
                        onClick={() => canEdit && setForm({
                          categoryNo: sub.categoryNo, name: sub.name,
                          i18nEn: sub.i18n.en ?? "", parentNo: sub.parentNo ?? "",
                          template: sub.template, requiredCode: sub.requiredCode ?? "",
                        })}
                      >
                        {sub.name}
                      </button>
                      {/*
                        门槛只用一个小徽章表示「要证」，具体哪张证放 title 里 ——
                        平铺码名会与类目名重复（「蔬菜 · 蔬菜」），一行里挤两遍同一个词。
                        发不出来的码要红：那种类目谁都上不了架，而报错说不清原因。
                      */}
                      {/*
                        上下移**只在悬停时出现**：顺序是低频动作，常驻两个箭头会把
                        每一行都变成三个可点区域，而行本身（点名字改类目）才是主操作。
                      */}
                      {canEdit && !off(sub) && (
                        <span className="flex shrink-0 opacity-0 transition-opacity group-hover:opacity-100">
                          <Button
                            size="sm" variant="ghost"
                            disabled={!neighbour(list, i, -1) || move.isPending}
                            onClick={() => {
                              const b = neighbour(list, i, -1);
                              if (b) move.mutate({ a: sub, b });
                            }}
                          >
                            {c.catMoveUp}
                          </Button>
                          <Button
                            size="sm" variant="ghost"
                            disabled={!neighbour(list, i, 1) || move.isPending}
                            onClick={() => {
                              const b = neighbour(list, i, 1);
                              if (b) move.mutate({ a: sub, b });
                            }}
                          >
                            {c.catMoveDown}
                          </Button>
                        </span>
                      )}
                      {/* 固定列宽：不给宽度的话，同一列的徽章在两张卡片里会各停在不同位置 */}
                      <span className="w-14 shrink-0 text-right">
                        {sub.requiredCode && (
                          <span title={`${codeName(sub.requiredCode)}（${sub.requiredCode}）${(sub.qualifications ?? []).join("、")}`}>
                            <Badge tone={codeBroken(sub.requiredCode) ? "danger" : "info"}>
                              {codeBroken(sub.requiredCode) ? c.catGateBroken : c.catGateNeed}
                            </Badge>
                          </span>
                        )}
                      </span>
                      {/* 0 件不显示数字：一列的「0」会把真正有货的那几行淹掉 */}
                      <span className="w-10 shrink-0 text-right txt-caption text-muted-foreground">
                        {sub.skuCount || ""}
                      </span>
                      {canEdit && (
                        <Switch checked={!off(sub)} onChange={(on) => toggle.mutate({ row: sub, on })} />
                      )}
                    </li>
                  ))}
                  {!childrenOf(top.categoryNo).length && (
                    <li className="py-1.5 txt-caption text-muted-foreground">{c.catNoChild}</li>
                  )}
                </ul>
              </CardContent>
            </Card>
          ))}
        </div>
      )}

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
      {dialog}
    </>
  );
}

/** 形态 → 文案。集中一处，避免每个用到的地方各拼一次 */
function codeLabel(c: ProductsCopy, template: string) {
  const map: Record<string, string> = {
    STANDARD: c.tplStandard, FRESH: c.tplFresh, SERVICE: c.tplService,
    VOUCHER: c.tplVoucher, VIRTUAL: c.tplVirtual,
  };
  return map[template] ?? template;
}
