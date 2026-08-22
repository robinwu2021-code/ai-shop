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

  const toggle = useMutation({
    mutationFn: (v: { categoryNo: string; on: boolean }) =>
      v.on ? api.unarchiveCategory(v.categoryNo) : api.archiveCategory(v.categoryNo),
    onSuccess: (_, v) => { invalidate(); notify.success(v.on ? c.catEnabled : c.catDisabled); },
  });

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
  /** 停用的沉底：它们是「以后可能开」的备选，不该挤在正在用的类目前面 */
  const byLive = (a: Category, b: Category) => Number(off(a)) - Number(off(b));
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
                  <Switch
                    checked={!off(top)}
                    onChange={(on) => toggle.mutate({ categoryNo: top.categoryNo, on })}
                  />
                )}
              </CardHeader>
              <CardContent className="pt-0">
                <ul className="divide-y divide-[var(--border)]">
                  {childrenOf(top.categoryNo).map((sub) => (
                    <li
                      key={sub.categoryNo}
                      className={`flex items-center gap-2 py-1.5 ${off(sub) ? "opacity-55" : ""}`}
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
                        <Switch
                          checked={!off(sub)}
                          onChange={(on) => toggle.mutate({ categoryNo: sub.categoryNo, on })}
                        />
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
