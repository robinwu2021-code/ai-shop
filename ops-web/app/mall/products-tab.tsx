"use client";

// 商城商品与 SKU：价格、组件、周期、退款政策；单个 SKU 版本的发布与停售。
//
// 金额输入是**主单位文本**（699 / 699.50），保存前经 parseMajor 转成 minor 整数字符串 ——
// 不乘 100、不过浮点。已发布版本只读；停售只影响以后的报价，不改已落单的快照。
import * as React from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { useLocaleStore } from "@/lib/stores/locale";
import { fill } from "@/lib/use-copy";
import { parseMajor, toMajorInput } from "@/lib/commerce-money";
import type {
  MallComponentKind, MallProductKind, MallUnusedRefundPolicy, MallProductView, MallSkuComponent, MallSkuDefinitionView, MallSkuVersionView,
} from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { CheckboxField } from "@/components/ui/checkbox";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, DrawerSection, Field, FieldGrid } from "@/components/ui/drawer";
import { HelpNote } from "@/components/ui/help-note";
import { Input, Select } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Notice } from "@/components/ui/notice";
import { SectionHeader } from "@/components/ui/section-header";
import { StatusBadge } from "@/components/ui/status-badge";
import { Textarea } from "@/components/ui/textarea";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { Money, categoryPath, categoryTree, newOpNo, saleMap, showErr, versionMap, type MallCopy } from "./shared";

const KINDS: MallProductKind[] = ["PLAN", "END_ACCESS", "HARDWARE", "BUNDLE"];
const CURRENCIES = ["TWD", "CNY"];

interface ProductForm {
  creating: boolean; productNo: string; categoryCode: string; kind: MallProductKind; name: string; description: string;
  allowedBusinessTypes: string[]; sortOrder: string; expectedVersion: number; reason: string;
}

interface CompRow {
  kind: MallComponentKind; planVersionNo: string; entitlementCode: string; hardwareSkuNo: string; periodDays: string;
  unitCount: string; allocationWeight: string; gift: boolean; activationPolicy: string;
}

interface SkuForm {
  productNo: string; skuNo: string; skuVersionNo: string | null; newSku: boolean; title: string; currency: string;
  listPrice: string; price: string; allowedBusinessTypes: string[]; unusedDigital: MallUnusedRefundPolicy;
  components: CompRow[]; maxPerOrder: string; expectedVersion: number; reason: string;
}

const blankComp = (): CompRow => ({ kind: "PLAN", planVersionNo: "", entitlementCode: "", hardwareSkuNo: "", periodDays: "365",
  unitCount: "1", allocationWeight: "1", gift: false, activationPolicy: "IMMEDIATE" });

function useInvalidate() {
  const qc = useQueryClient();
  return () => ["mall-catalog", "mall-products"].forEach((k) => qc.invalidateQueries({ queryKey: [k] }));
}

export function ProductsTab({ c, canEdit, canPublish }: { c: MallCopy; canEdit: boolean; canPublish: boolean }) {
  const products = useQuery({ queryKey: ["mall-products"], queryFn: () => api.mallProducts() });
  const catalog = useQuery({ queryKey: ["mall-catalog"], queryFn: () => api.mallCatalog() });
  const invalidate = useInvalidate();
  const { confirm, dialog } = useConfirm();
  const [pForm, setPForm] = React.useState<ProductForm | null>(null);
  const [sForm, setSForm] = React.useState<SkuForm | null>(null);
  const [viewNo, setViewNo] = React.useState<string | null>(null);

  const types = (catalog.data?.businessTypes ?? []).map((t) => t.draft!);
  const locale = useLocaleStore((st) => st.locale);
  const categories = React.useMemo(() => categoryTree(catalog.data?.categories ?? []), [catalog.data?.categories]);
  const skuDefs = catalog.data?.skuDefinitions ?? [];
  const skuVersions = catalog.data?.skuVersions ?? [];

  const saveProduct = useMutation({
    mutationFn: (f: ProductForm) => {
      const body = { categoryCode: f.categoryCode.trim(), kind: f.kind, name: f.name.trim(), description: f.description.trim() || null,
        allowedBusinessTypes: f.allowedBusinessTypes, sortOrder: Number(f.sortOrder) || 0, expectedVersion: f.expectedVersion, reason: f.reason.trim() };
      return f.creating ? api.createMallProduct({ ...body, productNo: f.productNo.trim() }) : api.updateMallProduct(f.productNo, body);
    },
    onSuccess: () => { invalidate(); setPForm(null); notify.success(c.toastSaved); },
  });

  const toComp = (r: CompRow): MallSkuComponent => ({
    kind: r.kind, planVersionNo: r.kind === "PLAN" ? r.planVersionNo : null, entitlementCode: r.kind === "END_PACKAGE" ? r.entitlementCode : null,
    hardwareSkuNo: r.kind === "HARDWARE" ? r.hardwareSkuNo.trim() : null, periodDays: r.kind === "HARDWARE" ? null : Number(r.periodDays),
    unitCount: Number(r.unitCount), allocationWeight: Number(r.allocationWeight), gift: r.gift, activationPolicy: r.activationPolicy,
  });

  const saveSku = useMutation({
    mutationFn: (f: SkuForm) => api.saveMallSkuVersion(f.productNo, {
      skuNo: f.skuNo.trim(), skuVersionNo: f.skuVersionNo, title: f.title.trim(),
      listPrice: { currency: f.currency, minor: parseMajor(f.listPrice, f.currency)! },
      price: { currency: f.currency, minor: parseMajor(f.price, f.currency)! },
      allowedBusinessTypes: f.allowedBusinessTypes, refundPolicy: { unusedDigital: f.unusedDigital, usedDigital: "MANUAL_ONLY" },
      components: f.components.map(toComp), maxPerOrder: Number(f.maxPerOrder), expectedVersion: f.expectedVersion, reason: f.reason.trim(),
    }),
    onSuccess: (r) => { invalidate(); setSForm(null); notify.success(fill(c.toastSkuSaved, { v: r.draftVersionNo ?? "—" })); },
  });

  const openSku = (p: MallProductView, def?: MallSkuDefinitionView, from?: MallSkuVersionView) => {
    const d = from?.draft;
    const editing = from?.status === "DRAFT";
    let policy: SkuForm["unusedDigital"] = "MANUAL_ONLY";
    try { if (JSON.parse(d?.refundPolicySnapshot ?? "{}").unusedDigital === "FULL_ORIGINAL_ALLOCATION") policy = "FULL_ORIGINAL_ALLOCATION"; } catch { /* 快照不是 JSON 时按人工处理 */ }
    setSForm({
      productNo: p.draft!.productNo!, skuNo: def?.skuNo ?? "", skuVersionNo: editing ? d!.skuVersionNo! : null, newSku: !def,
      title: d?.title ?? "", currency: d?.price?.currency ?? "TWD", listPrice: d?.listPrice ? toMajorInput(d.listPrice) : "",
      price: d?.price ? toMajorInput(d.price) : "", allowedBusinessTypes: d?.allowedBusinessTypes ?? p.draft?.allowedBusinessTypes ?? [],
      unusedDigital: policy, maxPerOrder: String(d?.maxPerOrder ?? 1), expectedVersion: def?.version ?? 0, reason: "",
      components: (d?.components ?? []).map((x) => ({ kind: x.kind ?? "PLAN", planVersionNo: x.planVersionNo ?? "", entitlementCode: x.entitlementCode ?? "",
        hardwareSkuNo: x.hardwareSkuNo ?? "", periodDays: String(x.periodDays ?? ""), unitCount: String(x.unitCount ?? 1),
        allocationWeight: String(x.allocationWeight ?? 1), gift: !!x.gift, activationPolicy: x.activationPolicy ?? "IMMEDIATE" })),
    });
  };

  const publishSku = (v: MallSkuVersionView) => {
    const def = skuDefs.find((d) => d.skuNo === v.draft?.skuNo)!;
    return confirm({
      title: c.skuPublishTitle, desc: fill(c.skuPublishDesc, { v: v.draft?.skuVersionNo ?? "" }), requireReason: true,
      action: (reason) => api.publishMallSkuVersion(v.draft!.skuVersionNo!, { operationNo: newOpNo("PUB"), expectedVersion: def.version!,
        expectedSkuVersion: v.version!, reason }).then(() => { invalidate(); notify.success(c.toastSkuPublished); }),
    });
  };

  const stopSku = (def: MallSkuDefinitionView) => confirm({
    title: c.skuStopTitle, desc: c.skuStopDesc, danger: true, requireReason: true,
    action: (reason) => api.stopMallSkuSale(def.skuNo!, { operationNo: newOpNo("STOP"), expectedVersion: def.version!, reason })
      .then(() => { invalidate(); notify.success(c.toastStopped); }),
  });

  const productColumns: Column<MallProductView>[] = [
    { header: c.colProductNo, cell: (r) => <code className="txt-caption">{r.draft?.productNo}</code> },
    { header: c.colName, cell: (r) => r.draft?.name },
    { header: c.fieldCategory, cell: (r) => categoryPath(categories, r.draft?.categoryCode, locale) },
    { header: c.colKind, cell: (r) => r.draft?.kind },
    { header: c.colTypes, cell: (r) => (r.draft?.allowedBusinessTypes ?? []).join(" / ") },
    { header: c.colSale, cell: (r) => <StatusBadge map={saleMap(c)} value={r.saleStatus ?? ""} /> },
    { header: c.colSkuCount, numeric: true, cell: (r) => skuDefs.filter((d) => d.productNo === r.draft?.productNo).length },
    { header: c.colActions, stickyEnd: "always", cell: (r) => (
      <span className="flex gap-2">
        {canEdit && <Button size="sm" variant="outline" onClick={() => setPForm({ creating: false, productNo: r.draft!.productNo!, categoryCode: r.draft?.categoryCode ?? "",
          kind: r.draft?.kind ?? "PLAN", name: r.draft?.name ?? "", description: r.draft?.description ?? "", allowedBusinessTypes: r.draft?.allowedBusinessTypes ?? [],
          sortOrder: String(r.draft?.sortOrder ?? 0), expectedVersion: r.version!, reason: "" })}>{c.btnEdit}</Button>}
        {canEdit && <Button size="sm" variant="outline" onClick={() => openSku(r)}>{c.btnNewSku}</Button>}
      </span>
    ) },
  ];

  /** 每个 SKU 一行：定义 + 它的所有版本 */
  const expand = (p: MallProductView) => {
    const defs = skuDefs.filter((d) => d.productNo === p.draft?.productNo);
    if (!defs.length) return <p className="txt-caption text-muted-foreground">{c.noSkus}</p>;
    return (
      <div className="space-y-3">
        {defs.map((def) => (
          <div key={def.skuNo} className="rounded-card border border-border p-3">
            <div className="mb-2 flex flex-wrap items-center gap-2">
              <code className="txt-caption">{def.skuNo}</code>
              <span className="txt-strong">{def.title}</span>
              <StatusBadge map={saleMap(c)} value={def.saleStatus ?? ""} />
              <span className="txt-caption text-muted-foreground">{fill(c.skuCurrent, { v: def.currentVersionNo ?? c.planNone })}</span>
              {canPublish && def.saleStatus === "ON_SALE" && <Button size="sm" variant="outline" onClick={() => stopSku(def)}>{c.btnStopSale}</Button>}
            </div>
            <ul className="space-y-1">
              {skuVersions.filter((v) => v.draft?.skuNo === def.skuNo).map((v) => (
                <li key={v.draft?.skuVersionNo} className="flex flex-wrap items-center gap-2">
                  <code className="txt-caption">{v.draft?.skuVersionNo}</code>
                  <StatusBadge map={versionMap(c)} value={v.status ?? ""} />
                  <Money m={v.draft?.price} />
                  <span className="txt-caption text-muted-foreground line-through"><Money m={v.draft?.listPrice} /></span>
                  <Button size="sm" variant="ghost" onClick={() => setViewNo(v.draft!.skuVersionNo!)}>{c.btnView}</Button>
                  {canEdit && <Button size="sm" variant="ghost" onClick={() => openSku(p, def, v)}>{v.status === "DRAFT" ? c.btnEditDraft : c.btnCopyToDraft}</Button>}
                  {canPublish && v.status === "DRAFT" && <Button size="sm" variant="outline" onClick={() => publishSku(v)}>{c.btnPublish}</Button>}
                </li>
              ))}
            </ul>
          </div>
        ))}
      </div>
    );
  };

  const planVersions = (catalog.data?.planVersions ?? []);
  const ends = catalog.data?.businessEnds ?? [];
  const priceOk = sForm && parseMajor(sForm.price, sForm.currency) !== null && parseMajor(sForm.listPrice, sForm.currency) !== null
    && BigInt(parseMajor(sForm.price, sForm.currency)!) <= BigInt(parseMajor(sForm.listPrice, sForm.currency)!);
  const compOk = sForm && sForm.components.length > 0 && sForm.components.every((r) =>
    (r.kind === "PLAN" ? !!r.planVersionNo && Number(r.periodDays) > 0
      : r.kind === "END_PACKAGE" ? !!r.entitlementCode && Number(r.periodDays) > 0 : !!r.hardwareSkuNo.trim())
    && Number(r.unitCount) >= 1 && Number(r.allocationWeight) >= 0);
  const skuValid = sForm && sForm.skuNo.trim() && sForm.title.trim() && priceOk && compOk && sForm.reason.trim()
    && Number(sForm.maxPerOrder) >= 1 && sForm.allowedBusinessTypes.length > 0;
  const pValid = pForm && pForm.productNo.trim() && pForm.name.trim() && pForm.categoryCode.trim() && pForm.reason.trim() && pForm.allowedBusinessTypes.length > 0;
  const setComp = (i: number, patch: Partial<CompRow>) =>
    setSForm((f) => f && { ...f, components: f.components.map((r, j) => (j === i ? { ...r, ...patch } : r)) });

  return (
    <div className="space-y-4">
      {!canEdit && <ReadOnlyNotice what={c.catalogEditWhat} perm="commerce:catalog:edit" note={c.catalogEditNote} />}
      <HelpNote>{c.productsHelp}</HelpNote>
      <SectionHeader title={c.secProducts} desc={c.secProductsDesc}
        summary={canEdit && <Button size="sm" onClick={() => setPForm({ creating: true, productNo: "", categoryCode: "", kind: "PLAN", name: "",
          description: "", allowedBusinessTypes: [], sortOrder: "0", expectedVersion: 0, reason: "" })}>{c.btnNewProduct}</Button>} />
      <DataTable columns={productColumns} rows={products.data} loading={products.isLoading || catalog.isLoading}
        error={showErr(products.error ?? catalog.error)} onRetry={() => { products.refetch(); catalog.refetch(); }}
        rowKey={(r) => r.draft?.productNo ?? ""} empty={c.productsEmpty} expandable={expand} />

      <Drawer open={!!pForm} onOpenChange={(o) => !o && setPForm(null)} title={pForm?.creating ? c.btnNewProduct : c.productEditTitle}
        footer={<Button disabled={!pValid} loading={saveProduct.isPending} onClick={() => pForm && saveProduct.mutate(pForm)}>{c.btnSave}</Button>}>
        {pForm && (
          <div className="space-y-3">
            <div className="space-y-1">
              <Label htmlFor="pd-no" required>{c.colProductNo}</Label>
              <Input id="pd-no" value={pForm.productNo} disabled={!pForm.creating} onChange={(e) => setPForm((f) => f && { ...f, productNo: e.target.value.toUpperCase() })} />
            </div>
            <div className="space-y-1">
              <Label htmlFor="pd-name" required>{c.colName}</Label>
              <Input id="pd-name" value={pForm.name} onChange={(e) => setPForm((f) => f && { ...f, name: e.target.value })} />
            </div>
            <div className="space-y-1">
              <Label htmlFor="pd-cat" required>{c.fieldCategory}</Label>
              {/* 只列启用中的分类；编辑时原分类已停用/不存在就提示重选（后端会拒 CATEGORY_UNAVAILABLE） */}
              <Select id="pd-cat" className="w-full" value={pForm.categoryCode}
                onChange={(e) => setPForm((f) => f && { ...f, categoryCode: e.target.value })}>
                <option value="">{c.categoryPick}</option>
                {categories.filter((x) => x.status === "ENABLED").map((x) => (
                  <option key={x.categoryCode} value={x.categoryCode}>{categoryPath(categories, x.categoryCode, locale)}</option>
                ))}
              </Select>
              {pForm.categoryCode && !categories.some((x) => x.categoryCode === pForm.categoryCode && x.status === "ENABLED") && (
                <p className="txt-caption text-destructive">{fill(c.categoryMissing, { c: pForm.categoryCode })}</p>
              )}
            </div>
            <div className="space-y-1">
              <Label htmlFor="pd-kind">{c.colKind}</Label>
              <Select id="pd-kind" className="w-full" value={pForm.kind} onChange={(e) => setPForm((f) => f && { ...f, kind: e.target.value as MallProductKind })}>
                {KINDS.map((k) => <option key={k} value={k}>{k}</option>)}
              </Select>
            </div>
            <div className="space-y-1">
              <Label required>{c.colTypes}</Label>
              <div className="flex flex-wrap gap-3">
                {types.map((t) => (
                  <CheckboxField key={t.typeCode} label={`${t.name} (${t.typeCode})`} checked={pForm.allowedBusinessTypes.includes(t.typeCode!)}
                    onChange={(v) => setPForm((f) => f && { ...f, allowedBusinessTypes: v === true ? [...f.allowedBusinessTypes, t.typeCode!] : f.allowedBusinessTypes.filter((x) => x !== t.typeCode) })} />
                ))}
              </div>
            </div>
            <div className="space-y-1">
              <Label htmlFor="pd-desc">{c.fieldDescription}</Label>
              <Textarea id="pd-desc" value={pForm.description} onChange={(v) => setPForm((f) => f && { ...f, description: v })} />
            </div>
            <div className="space-y-1">
              <Label htmlFor="pd-sort">{c.colSort}</Label>
              <Input id="pd-sort" inputMode="numeric" value={pForm.sortOrder} onChange={(e) => setPForm((f) => f && { ...f, sortOrder: e.target.value })} />
            </div>
            <div className="space-y-1">
              <Label htmlFor="pd-reason" required>{c.fieldReason}</Label>
              <Textarea id="pd-reason" value={pForm.reason} placeholder={c.reasonPh} onChange={(v) => setPForm((f) => f && { ...f, reason: v })} />
            </div>
          </div>
        )}
      </Drawer>

      <Drawer open={!!sForm} onOpenChange={(o) => !o && setSForm(null)} width="w-[640px]"
        title={sForm ? fill(sForm.skuVersionNo ? c.skuEditTitle : c.skuNewTitle, { p: sForm.productNo }) : ""}
        footer={<Button disabled={!skuValid} loading={saveSku.isPending} onClick={() => sForm && saveSku.mutate(sForm)}>{c.btnSaveDraft}</Button>}>
        {sForm && (
          <div className="space-y-3">
            <Notice tone="info">{c.skuDraftNotice}</Notice>
            <div className="space-y-1">
              <Label htmlFor="sk-no" required>{c.colSkuNo}</Label>
              <Input id="sk-no" value={sForm.skuNo} disabled={!sForm.newSku} onChange={(e) => setSForm((f) => f && { ...f, skuNo: e.target.value.toUpperCase() })} />
            </div>
            <div className="space-y-1">
              <Label htmlFor="sk-title" required>{c.fieldTitle}</Label>
              <Input id="sk-title" value={sForm.title} onChange={(e) => setSForm((f) => f && { ...f, title: e.target.value })} />
            </div>
            <div className="grid gap-3 md:grid-cols-3">
              <div className="space-y-1">
                <Label htmlFor="sk-cur">{c.fieldCurrency}</Label>
                <Select id="sk-cur" className="w-full" value={sForm.currency} onChange={(e) => setSForm((f) => f && { ...f, currency: e.target.value })}>
                  {CURRENCIES.map((x) => <option key={x} value={x}>{x}</option>)}
                </Select>
              </div>
              <div className="space-y-1">
                <Label htmlFor="sk-list" required>{c.fieldListPrice}</Label>
                <Input id="sk-list" inputMode="decimal" value={sForm.listPrice} invalid={!!sForm.listPrice && parseMajor(sForm.listPrice, sForm.currency) === null}
                  onChange={(e) => setSForm((f) => f && { ...f, listPrice: e.target.value })} />
              </div>
              <div className="space-y-1">
                <Label htmlFor="sk-price" required>{c.fieldPrice}</Label>
                <Input id="sk-price" inputMode="decimal" value={sForm.price} invalid={!!sForm.price && parseMajor(sForm.price, sForm.currency) === null}
                  onChange={(e) => setSForm((f) => f && { ...f, price: e.target.value })} />
              </div>
            </div>
            <p className="txt-caption text-muted-foreground">
              {priceOk ? fill(c.priceEcho, { minor: parseMajor(sForm.price, sForm.currency)!, cur: sForm.currency }) : c.priceHint}
            </p>
            <div className="space-y-1">
              <Label required>{c.colTypes}</Label>
              <div className="flex flex-wrap gap-3">
                {types.map((t) => (
                  <CheckboxField key={t.typeCode} label={`${t.name} (${t.typeCode})`} checked={sForm.allowedBusinessTypes.includes(t.typeCode!)}
                    onChange={(v) => setSForm((f) => f && { ...f, allowedBusinessTypes: v === true ? [...f.allowedBusinessTypes, t.typeCode!] : f.allowedBusinessTypes.filter((x) => x !== t.typeCode) })} />
                ))}
              </div>
            </div>
            <div className="grid gap-3 md:grid-cols-2">
              <div className="space-y-1">
                <Label htmlFor="sk-refund">{c.fieldRefundPolicy}</Label>
                <Select id="sk-refund" className="w-full" value={sForm.unusedDigital}
                  onChange={(e) => setSForm((f) => f && { ...f, unusedDigital: e.target.value as SkuForm["unusedDigital"] })}>
                  <option value="FULL_ORIGINAL_ALLOCATION">{c.refundFull}</option>
                  <option value="MANUAL_ONLY">{c.refundManual}</option>
                </Select>
                <p className="txt-caption text-muted-foreground">{c.refundUsedHint}</p>
              </div>
              <div className="space-y-1">
                <Label htmlFor="sk-max" required>{c.fieldMaxPerOrder}</Label>
                <Input id="sk-max" inputMode="numeric" value={sForm.maxPerOrder} onChange={(e) => setSForm((f) => f && { ...f, maxPerOrder: e.target.value })} />
              </div>
            </div>

            <DrawerSection title={c.secComponents} desc={c.secComponentsDesc}>
              <div className="space-y-3">
                {sForm.components.map((r, i) => (
                  <div key={i} className="space-y-2 rounded-card border border-border p-3">
                    <div className="flex items-center gap-2">
                      <Select aria-label={c.colKind} value={r.kind} onChange={(e) => setComp(i, { kind: e.target.value as MallComponentKind })}>
                        <option value="PLAN">{c.compPlan}</option>
                        <option value="END_PACKAGE">{c.compEnd}</option>
                        <option value="HARDWARE">{c.compHardware}</option>
                      </Select>
                      <Button size="sm" variant="ghost" onClick={() => setSForm((f) => f && { ...f, components: f.components.filter((_, j) => j !== i) })}>{c.btnRemove}</Button>
                    </div>
                    {r.kind === "PLAN" && (
                      <Select aria-label={c.compPlan} className="w-full" value={r.planVersionNo} onChange={(e) => setComp(i, { planVersionNo: e.target.value })}>
                        <option value="">{c.pickVersion}</option>
                        {planVersions.map((v) => <option key={v.draft?.planVersionNo} value={v.draft?.planVersionNo}>{v.draft?.planVersionNo} · {v.draft?.displayName} · {v.status === "PUBLISHED" ? c.verPublished : c.verDraft}</option>)}
                      </Select>
                    )}
                    {r.kind === "END_PACKAGE" && (
                      <Select aria-label={c.compEnd} className="w-full" value={r.entitlementCode} onChange={(e) => setComp(i, { entitlementCode: e.target.value })}>
                        <option value="">{c.pickEnd}</option>
                        {ends.map((e) => <option key={e.draft?.entitlementCode} value={e.draft?.entitlementCode}>{e.draft?.name} ({e.draft?.entitlementCode})</option>)}
                      </Select>
                    )}
                    {r.kind === "HARDWARE" && (
                      <Input aria-label={c.fieldHardwareSku} placeholder={c.fieldHardwareSku} value={r.hardwareSkuNo} onChange={(e) => setComp(i, { hardwareSkuNo: e.target.value })} />
                    )}
                    <div className="grid gap-2 md:grid-cols-3">
                      {r.kind !== "HARDWARE" && (
                        <Input aria-label={c.fieldPeriodDays} placeholder={c.fieldPeriodDays} inputMode="numeric" value={r.periodDays} onChange={(e) => setComp(i, { periodDays: e.target.value })} />
                      )}
                      <Input aria-label={c.fieldUnitCount} placeholder={c.fieldUnitCount} inputMode="numeric" value={r.unitCount} onChange={(e) => setComp(i, { unitCount: e.target.value })} />
                      <Input aria-label={c.fieldWeight} placeholder={c.fieldWeight} inputMode="numeric" value={r.allocationWeight} onChange={(e) => setComp(i, { allocationWeight: e.target.value })} />
                    </div>
                    <CheckboxField label={c.fieldGift} checked={r.gift} onChange={(v) => setComp(i, { gift: v === true })} />
                  </div>
                ))}
                <Button size="sm" variant="outline" onClick={() => setSForm((f) => f && { ...f, components: [...f.components, blankComp()] })}>{c.btnAddComponent}</Button>
              </div>
            </DrawerSection>
            <div className="space-y-1">
              <Label htmlFor="sk-reason" required>{c.fieldReason}</Label>
              <Textarea id="sk-reason" value={sForm.reason} placeholder={c.reasonPh} onChange={(v) => setSForm((f) => f && { ...f, reason: v })} />
            </div>
          </div>
        )}
      </Drawer>

      <SkuVersionDrawer c={c} versionNo={viewNo} onClose={() => setViewNo(null)} />
      {dialog}
    </div>
  );
}

/** 单个 SKU 版本的只读详情（GET /ops/biz-mall/sku-versions/{versionNo}） */
function SkuVersionDrawer({ c, versionNo, onClose }: { c: MallCopy; versionNo: string | null; onClose: () => void }) {
  const q = useQuery({ queryKey: ["mall-sku-version", versionNo], queryFn: () => api.mallSkuVersion(versionNo!), enabled: !!versionNo });
  const d = q.data?.draft;
  const compColumns: Column<MallSkuComponent>[] = [
    { header: c.colKind, cell: (x) => x.kind },
    { header: c.colRef, cell: (x) => <code className="txt-caption">{x.planVersionNo ?? x.entitlementCode ?? x.hardwareSkuNo}</code> },
    { header: c.fieldPeriodDays, numeric: true, cell: (x) => x.periodDays ?? "—" },
    { header: c.fieldUnitCount, numeric: true, cell: (x) => x.unitCount },
    { header: c.fieldWeight, numeric: true, cell: (x) => x.allocationWeight },
    { header: c.fieldGift, cell: (x) => x.gift ? <Badge tone="info">{c.fieldGift}</Badge> : "—" },
  ];
  return (
    <Drawer open={!!versionNo} onOpenChange={(o) => !o && onClose()} title={versionNo ?? ""} width="w-[560px]">
      {q.error ? <DataTable columns={[]} rows={undefined} error={showErr(q.error)} onRetry={() => q.refetch()} rowKey={() => ""} empty={c.productsEmpty} /> : d && (
        <>
          {q.data?.status === "PUBLISHED" && <Notice tone="info" className="mb-3">{c.publishedReadOnly}</Notice>}
          <FieldGrid>
            <Field label={c.fieldTitle}>{d.title}</Field>
            <Field label={c.colSale}><StatusBadge map={versionMap(c)} value={q.data?.status ?? ""} /></Field>
            <Field label={c.fieldPrice}><Money m={d.price} /></Field>
            <Field label={c.fieldListPrice}><Money m={d.listPrice} /></Field>
            <Field label={c.fieldMaxPerOrder}>{d.maxPerOrder}</Field>
            <Field label={c.colTypes}>{(d.allowedBusinessTypes ?? []).join(" / ")}</Field>
          </FieldGrid>
          <Field label={c.fieldRefundPolicy}><code className="txt-caption break-all">{d.refundPolicySnapshot}</code></Field>
          <DataTable columns={compColumns} rows={d.components} error={showErr(q.error)} onRetry={() => q.refetch()} rowKey={(x) => x.componentNo ?? String(x.kind)} empty={c.componentsEmpty} />
        </>
      )}
    </Drawer>
  );
}
