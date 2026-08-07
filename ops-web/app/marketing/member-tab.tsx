"use client";

// 会员卡与权益（矩阵 P-7.4）。
//
// 这一域只有一句话：**卖出去的是承诺，不是配置**。
// 界面因此把「有人持卡」做成了显性的锁：有持卡人的卡，权益与月费的输入框直接禁用，
// 旁边写清"要调整请新建一张卡并把这张停售"——而不是让人改到一半才被服务端拒。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { fill } from "@/lib/use-copy";
import { fmtTime, money } from "@/lib/utils";
import { MINOR_UNIT, MIN_MEMBER_DISCOUNT } from "@/lib/constants";
import { MEMBER_CARD_TRANSITIONS } from "@/lib/types";
import type { Benefit, BenefitKind, MemberCard, MemberCardStatus } from "@/lib/types";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, DrawerSection, Field, FieldGrid } from "@/components/ui/drawer";
import { FilterSelect } from "@/components/ui/filter-select";
import { Pagination } from "@/components/ui/misc";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { Toolbar } from "@/components/ui/toolbar";
import { Notice } from "@/components/ui/notice";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import type { MarketingCopy } from "./copy";

const useCardStatusMap = (c: MarketingCopy): StatusMap<MemberCardStatus> => ({
  DRAFT: { label: c.mcDraft, tone: "muted" },
  ACTIVE: { label: c.mcActive, tone: "success" },
  PAUSED: { label: c.mcPaused, tone: "warning" },
  ENDED: { label: c.mcEnded, tone: "muted" },
});

interface Form {
  cardNo?: string;
  name: string;
  level: string;
  priceMonthly: string;
  benefits: Benefit[];
}

const toForm = (m: MemberCard): Form => ({
  cardNo: m.cardNo, name: m.name, level: String(m.level),
  priceMonthly: String(m.priceMonthly / MINOR_UNIT),
  benefits: m.benefits.map((b) => ({ ...b })),
});

export function MemberTab({ c, canEdit }: { c: MarketingCopy; canEdit: boolean }) {
  const qc = useQueryClient();
  const statusMap = useCardStatusMap(c);
  const [keyword, setKeyword] = useState("");
  const [status, setStatus] = useState("");
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);
  const [editing, setEditing] = useState<Form | null>(null);

  const benefitLabel: Record<BenefitKind, string> = {
    DISCOUNT: c.bkDiscount, FREE_SHIPPING: c.bkFreeShipping,
    COUPON_PACK: c.bkCouponPack, POINTS_BOOST: c.bkPointsBoost,
  };

  const q = { keyword, status, page, size };
  const list = useQuery({ queryKey: ["member-cards", q], queryFn: () => api.listMemberCards(q) });
  // 赠券权益要绑一张已启用的券：下拉里只给 ACTIVE 的，选不出草稿券
  const activeCoupons = useQuery({
    queryKey: ["coupons", "active"],
    queryFn: () => api.listCoupons({ status: "ACTIVE", size: 100 }),
  });

  // 正在编辑的这张卡当前的持卡人数 —— 它决定权益能不能改
  const holders = editing?.cardNo
    ? list.data?.records.find((m) => m.cardNo === editing.cardNo)?.holderCount ?? 0
    : 0;
  const locked = holders > 0;

  const save = useMutation({
    mutationFn: () =>
      api.saveMemberCard({
        cardNo: editing!.cardNo,
        name: editing!.name,
        level: Number(editing!.level),
        priceMonthly: Math.round(Number(editing!.priceMonthly) * MINOR_UNIT),
        benefits: editing!.benefits,
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["member-cards"] });
      setEditing(null);
      notify.success(c.toastCardSaved);
    },
  });

  const setStatusMut = useMutation({
    mutationFn: (v: { cardNo: string; status: MemberCardStatus }) => api.setMemberCardStatus(v.cardNo, v.status),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["member-cards"] }); notify.success(c.toastCardStatus); },
  });

  const setBenefit = (i: number, patch: Partial<Benefit>) =>
    setEditing((p) => p && { ...p, benefits: p.benefits.map((b, j) => (j === i ? { ...b, ...patch } : b)) });

  /**
   * 权益值的展示口径四类各不相同，所以不能统一渲染成一个数字。
   *
   * 折扣尤其要小心：中文说「95 折」，英文说的是**折掉多少**（5% off）——
   * 同一个 9500 在两种语言里是两个数。所以两个参数都传给文案表，各自的模板挑自己要的那个。
   */
  const benefitText = (b: Benefit) =>
    b.kind === "DISCOUNT" ? fill(c.bvDiscount, { zhe: b.value / 1000, off: (10000 - b.value) / 100 })
      : b.kind === "POINTS_BOOST" ? fill(c.bvPoints, { n: b.value / 10000 })
        : b.kind === "FREE_SHIPPING" ? fill(c.bvFreeShipping, { n: b.value })
          : fill(c.bvCouponPack, { n: b.value });

  const columns: Column<MemberCard>[] = [
    { header: c.colCardNo, cell: (m) => m.cardNo, numeric: true, align: "start" },
    { header: c.colCardName, cell: (m) => m.name },
    { header: c.colLevel, cell: (m) => m.level, numeric: true },
    { header: c.colPrice, cell: (m) => fill(c.perMonth, { amount: money(m.priceMonthly) }), numeric: true },
    {
      header: c.colBenefits,
      cell: (m) => (
        <div className="flex flex-wrap gap-1">
          {m.benefits.map((b) => (
            <Badge key={b.kind} tone="info">{benefitLabel[b.kind]} {benefitText(b)}</Badge>
          ))}
        </div>
      ),
    },
    {
      header: c.colHolders,
      // 持卡人数是"这张卡还能不能改"的唯一依据，不是灰数字
      cell: (m) => (m.holderCount > 0 ? <Badge tone="warning">{fill(c.holderCount, { n: m.holderCount })}</Badge> : <span className="text-muted-foreground">{fill(c.holderCount, { n: 0 })}</span>),
      numeric: true,
    },
    { header: c.colCardStatus, cell: (m) => <StatusBadge map={statusMap} value={m.status} /> },
    { header: c.colUpdatedAt, cell: (m) => `${fmtTime(m.updatedAt)} · ${m.updatedBy}` },
    {
      header: c.colActions,
      cell: (m) => (
        <div className="flex gap-2">
          <Button size="sm" variant="outline" onClick={() => setEditing(toForm(m))}>{c.actionEditCard}</Button>
          {/* 只出状态机允许的下一步，不出点了必报错的按钮 */}
          {canEdit && MEMBER_CARD_TRANSITIONS[m.status].map((to) => (
            <Button key={to} size="sm" variant="ghost"
              onClick={() => setStatusMut.mutate({ cardNo: m.cardNo, status: to })}>
              {statusMap[to].label}
            </Button>
          ))}
        </div>
      ),
    },
  ];

  return (
    <>
      <Notice className="mb-3">{fill(c.memberNotice, { zhe: MIN_MEMBER_DISCOUNT / 1000, off: (10000 - MIN_MEMBER_DISCOUNT) / 100 })}</Notice>
      <Toolbar
        search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder={c.searchCard}
        onAdd={() => setEditing({ name: "", level: "1", priceMonthly: "9", benefits: [] })}
        addLabel={c.actionNewCard} canAdd={canEdit}
      >
        <FilterSelect aria-label={c.filterCardStatus} value={status} onChange={(v) => { setStatus(v); setPage(1); }}
          options={statusMap} allLabel={c.filterCardStatusAll} />
      </Toolbar>
      <DataTable
        columns={columns} rows={list.data?.records} loading={list.isLoading}
        error={list.error} onRetry={() => list.refetch()}
        rowKey={(m) => m.cardNo}
        empty={c.emptyCard}
      />
      <Pagination page={page} size={size} onSize={setSize} total={list.data?.total ?? 0} onPage={setPage} />

      <Drawer
        open={!!editing}
        onOpenChange={(o) => !o && setEditing(null)}
        title={editing?.cardNo ? fill(c.editCardTitle, { name: editing.name }) : c.newCardTitle}
        width="w-[560px]"
        footer={canEdit ? <Button loading={save.isPending} onClick={() => save.mutate()}>{c.btnSaveCard}</Button> : null}
      >
        {editing && (
          <div>
            {locked && (
              <Notice className="mb-4" tone="warning">
                {fill(c.lockedNotice, { n: holders })}
              </Notice>
            )}

            <DrawerSection first title={c.secCardBasic}>
              <div className="mb-3 space-y-1">
                <Label htmlFor="mc-name" required>{c.colCardName}</Label>
                <Input id="mc-name" className="w-full" value={editing.name} disabled={!canEdit}
                  onChange={(e) => setEditing((p) => p && { ...p, name: e.target.value })} />
              </div>
              <FieldGrid>
                <div className="mb-3 space-y-1">
                  <Label htmlFor="mc-level" required>{c.colLevel}</Label>
                  <Input id="mc-level" className="w-full" value={editing.level} disabled={!canEdit}
                    onChange={(e) => setEditing((p) => p && { ...p, level: e.target.value })} />
                </div>
                <div className="mb-3 space-y-1">
                  <Label htmlFor="mc-price" required>{c.fieldPrice}</Label>
                  {/* 有人持卡就锁死：用户买的是当初那个价 */}
                  <Input id="mc-price" className="w-full" value={editing.priceMonthly} disabled={!canEdit || locked}
                    onChange={(e) => setEditing((p) => p && { ...p, priceMonthly: e.target.value })} />
                </div>
              </FieldGrid>
            </DrawerSection>

            <DrawerSection title={c.secBenefits}>
              <div className="space-y-2">
                {editing.benefits.map((b, i) => (
                  <div key={i} className="flex items-center gap-2">
                    <Select className="w-36" value={b.kind} disabled={!canEdit || locked} aria-label={c.fieldBenefitKind}
                      onChange={(e) => setBenefit(i, { kind: e.target.value as BenefitKind, couponNo: null })}>
                      {(Object.keys(benefitLabel) as BenefitKind[]).map((k) => (
                        <option key={k} value={k}>{benefitLabel[k]}</option>
                      ))}
                    </Select>
                    <Input className="w-24" value={String(b.value)} disabled={!canEdit || locked} aria-label={c.fieldBenefitValue}
                      onChange={(e) => setBenefit(i, { value: Number(e.target.value) })} />
                    {b.kind === "COUPON_PACK" && (
                      <Select className="flex-1" value={b.couponNo ?? ""} disabled={!canEdit || locked} aria-label={c.fieldCoupon}
                        onChange={(e) => setBenefit(i, { couponNo: e.target.value })}>
                        <option value="">{c.pickCoupon}</option>
                        {activeCoupons.data?.records.map((cp) => (
                          <option key={cp.couponNo} value={cp.couponNo}>{cp.name}</option>
                        ))}
                      </Select>
                    )}
                    <Button size="sm" variant="ghost" disabled={!canEdit || locked}
                      onClick={() => setEditing((p) => p && { ...p, benefits: p.benefits.filter((_, j) => j !== i) })}>
                      {c.actionRemoveBenefit}
                    </Button>
                  </div>
                ))}
              </div>
              <Button className="mt-2" size="sm" variant="outline" disabled={!canEdit || locked}
                onClick={() => setEditing((p) => p && { ...p, benefits: [...p.benefits, { kind: "FREE_SHIPPING", value: 1 }] })}>
                {c.btnAddBenefit}
              </Button>
              <p className="mt-3 txt-caption text-muted-foreground">{c.benefitsHint}</p>
            </DrawerSection>

            {editing.cardNo && (
              <DrawerSection title={c.secHolders}>
                <Field className="mb-0" label={c.colHolders}>{fill(c.holderCount, { n: holders })}</Field>
              </DrawerSection>
            )}
          </div>
        )}
      </Drawer>
    </>
  );
}
