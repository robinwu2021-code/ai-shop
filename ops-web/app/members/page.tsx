"use client";

// 会员与人档（P8 · O1–O7）。
//
// **这一页的定位是「跨商家」**，而不是「更强的商家会员页」：
// 商家看自己那一份，运营要回答的是「这个人是谁家的会员」「谁家的券会失控」。
//
// ⚠️ 两条贯穿全页的规矩：
//   1. **手机号只有后四位**。「跨商家可见」与「脱敏」是并列的两句，不是前者的例外。
//      要完整号得走「查看完整手机号」：单独权限码、必填理由、每次写审计。
//   2. **敞口两个 tab 看的是风险不是清单**。券与活动本身在商家端管，
//      运营在这里只看「谁家的没设预算、不限量、快用完」—— 商家看不出这些，
//      他只看得到自己那一张。
import { Suspense, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { fill, useCopy } from "@/lib/use-copy";
import { MEMBERS_COPY } from "./copy";
import { usePageTab, useNavTabs } from "@/lib/use-page-tab";
import { usePaging } from "@/lib/use-paging";
import { useCan } from "@/lib/use-can";
import { notify } from "@/lib/notify";
import { fmtTime, money } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Input } from "@/components/ui/input";
import { Notice } from "@/components/ui/notice";
import { Pagination } from "@/components/ui/misc";
import { TabHeader } from "@/components/ui/tab-header";
import { Toolbar } from "@/components/ui/toolbar";
import type { OpsMember, OpsPromoActivity, OpsPromoCoupon, ReachStat } from "@/lib/types";

type Copy = (typeof MEMBERS_COPY)["zh"];

/*
 * 敞口那两个 tab **不在这里** —— 它们挂在营销页（权限码是 marketing:*）。
 * nav 里的深链指向 /members?tab=coupons 只是入口位置，页面在 /marketing。
 * 混在一页会让看会员的人顺带拿到营销的入口。
 */
const TAB_KEYS = ["members", "persons", "reach"] as const;

export default function MembersPage() {
  return <Suspense fallback={null}><MembersInner /></Suspense>;
}

function MembersInner() {
  const c = useCopy(MEMBERS_COPY);
  const tabs = useNavTabs("/members", TAB_KEYS);
  const { page, size, setPage, setSize } = usePaging();
  const [tab, setTab] = usePageTab(tabs, () => { setPage(1); setTail(""); setPersonNo(""); });

  const [tail, setTail] = useState("");
  const [personNo, setPersonNo] = useState("");
  const qc = useQueryClient();
  const can = useCan();
  const canReveal = can("member:phone:reveal");
  const canStop = can("marketing:campaign:update");

  /*
   * 只在**恰好四位**时才查。三位就发请求的话，后端会拒（400），
   * 而运营看到的是一个红色报错 —— 他会以为是系统坏了，而不是「还没输完」。
   */
  const members = useQuery({
    queryKey: ["ops-members", tail, page, size],
    queryFn: () => api.listOpsMembers({ phoneTail: tail.length === 4 ? tail : undefined, page, size }),
    enabled: tab === "members",
  });

  const person = useQuery({
    queryKey: ["ops-person", personNo],
    queryFn: () => api.getOpsPerson(personNo),
    enabled: tab === "persons" && !!personNo,
  });

  const reach = useQuery({
    queryKey: ["ops-reach-stats"],
    queryFn: () => api.listReachStats(30),
    enabled: tab === "reach",
  });

  const coupons = useQuery({
    queryKey: ["ops-promo-coupons"],
    queryFn: () => api.listOpsPromoCoupons(),
    enabled: tab === "coupons",
  });

  const activities = useQuery({
    queryKey: ["ops-promo-activities"],
    queryFn: () => api.listOpsPromoActivities(),
    enabled: tab === "activities",
  });

  const reveal = useMutation({
    mutationFn: ({ no, reason }: { no: string; reason: string }) =>
      api.revealMemberPhone(no, reason),
    onSuccess: (r) => notify.success(fill(c.revealDone, { phone: r.phone })),
    onError: (e: Error) => notify.error(e.message),
  });

  const stop = useMutation({
    mutationFn: ({ no, reason }: { no: string; reason: string }) =>
      api.stopOpsActivity(no, reason),
    onSuccess: () => {
      notify.success(c.stopDone);
      void qc.invalidateQueries({ queryKey: ["ops-promo-activities"] });
    },
    onError: (e: Error) => notify.error(e.message),
  });

  const flagBadges = (flags: string[]) => (
    <div className="flex flex-wrap gap-1">
      {flags.map((f) => (
        <Badge key={f} tone="danger">
          {(c as unknown as Record<string, string>)["flag_" + f] ?? f}
        </Badge>
      ))}
    </div>
  );

  const memberCols: Column<OpsMember>[] = [
    { header: c.colMember,
      cell: (m) => (
        <button className="text-left underline" onClick={() => { setPersonNo(m.personNo); setTab("persons"); }}>
          ···{m.phoneTail ?? "----"}
          {m.status === "LEAD" && <Badge className="ml-2">{c.lead}</Badge>}
        </button>
      ) },
    { header: c.colEntity, cell: (m) => m.entityName },
    { header: c.colLevel, cell: (m) => m.level ?? "—" },
    { header: c.colOrders, cell: (m) => m.orderCount },
    { header: c.colSpent, cell: (m) => money(m.totalSpentMinor) },
    { header: c.colReach,
      cell: (m) => (m.reachOptOut ? <Badge tone="muted">{c.reachOff}</Badge> : c.reachOn) },
    { header: c.colJoined, cell: (m) => fmtTime(m.joinedAt) },
  ];

  const reachCols: Column<ReachStat>[] = [
    { header: c.colEntity, cell: (r) => r.entityName },
    { header: c.colSent, cell: (r) => r.sent },
    { header: c.colMembers, cell: (r) => r.members },
    { header: c.colOptOut, cell: (r) => r.optOut },
    { header: c.colOptOutRate,
      // 退订率高的要显眼：这一列是这条线唯一的健康指标
      cell: (r) => (r.optOutRate >= 10
        ? <Badge tone="danger">{r.optOutRate}%</Badge>
        : <span>{r.optOutRate}%</span>) },
  ];

  const couponCols: Column<OpsPromoCoupon>[] = [
    { header: c.colTitle, cell: (x) => x.title },
    { header: c.colEntity, cell: (x) => x.entityName },
    { header: c.colIssued,
      cell: (x) => `${x.receivedCount} / ${x.totalCount ?? c.unlimited}` },
    { header: c.colBudget,
      cell: (x) => (x.budgetMinor ? money(x.budgetMinor) : c.none) },
    { header: c.colExposure,
      cell: (x) => (x.maxExposureMinor == null ? c.unlimited : money(x.maxExposureMinor)) },
    { header: c.colFlags, cell: (x) => flagBadges(x.flags) },
  ];

  const activityCols: Column<OpsPromoActivity>[] = [
    { header: c.colActivity, cell: (x) => x.name },
    { header: c.colEntity, cell: (x) => x.entityName },
    { header: c.colSchedule, cell: (x) => x.scheduleType },
    { header: c.colQuota,
      cell: (x) => (x.quota == null ? c.unlimited : `${x.quotaUsed} / ${x.quota}`) },
    { header: c.colAudience,
      // 0 条受众 = 对所有人生效。**这一列必须显示** ——
      // 「给所有人」与「没设置」在库里长得一样，但含义差很远
      cell: (x) => (x.audienceCount === 0
        ? c.audienceAll : fill(c.audienceN, { n: x.audienceCount })) },
    { header: c.colFlags, cell: (x) => flagBadges(x.flags) },
    { header: "", cell: (x) => (canStop && x.status === "RUNNING" ? (
      <Button
        size="sm"
        variant="destructive"
        onClick={() => {
          const reason = window.prompt(c.stopReason) ?? "";
          if (reason.trim().length >= 4) stop.mutate({ no: x.activityNo, reason });
        }}
      >{c.stop}</Button>
    ) : null) },
  ];

  return (
    <div className="space-y-4">
      <TabHeader tabs={tabs} value={tab} onChange={setTab} />

      {tab === "members" && (
        <Card>
          <CardHeader><CardTitle>{c.tabMembers}</CardTitle></CardHeader>
          <CardContent className="space-y-3">
            {/*
              用 Toolbar 自带的 search 槽而不是塞一个裸 <Input>：
              放进 Toolbar 的筛选控件要能出现在筛选回显里，否则用户以为没筛，
              然后对着少掉的数据找半天（design-tokens 那条守卫守的就是这个）。
            */}
            <Toolbar
              search={tail}
              onSearch={(v) => { setTail(v.replace(/\D/g, "").slice(0, 4)); setPage(1); }}
              searchPlaceholder={c.searchTail}
            ></Toolbar>
            <Notice>{c.searchTailHint}</Notice>
            <DataTable columns={memberCols} rows={members.data?.records ?? []} rowKey={(m) => m.memberNo} />
            <Pagination page={page} size={size} onSize={setSize} total={members.data?.total ?? 0} onPage={setPage} />
          </CardContent>
        </Card>
      )}

      {tab === "persons" && (
        <Card>
          <CardHeader><CardTitle>{fill(c.personTitle, { no: personNo || "—" })}</CardTitle></CardHeader>
          <CardContent className="space-y-3">
            <Notice>{c.personHint}</Notice>
            {/* 这不是筛选而是「输入一个人档号」，所以不放进 Toolbar */}
            <Input
              className="w-64"
              placeholder="PS-…"
              value={personNo}
              onChange={(e) => setPersonNo(e.target.value.trim())}
            />

            {person.data && (
              <div className="space-y-3">
                <div className="text-sm">
                  ···{person.data.phoneTail ?? "----"}
                  {" · "}
                  {person.data.userNo ?? c.noAccount}
                </div>
                <DataTable
                  columns={memberCols}
                  rows={person.data.memberships}
                  rowKey={(m) => m.memberNo}
                />
                {canReveal && (
                  <div className="space-y-2">
                    <Notice tone="warning">{c.revealHint}</Notice>
                    <Button
                      variant="outline"
                      onClick={() => {
                        const reason = window.prompt(c.revealReason) ?? "";
                        if (reason.trim().length >= 4) {
                          reveal.mutate({ no: person.data!.personNo, reason });
                        }
                      }}
                    >{c.reveal}</Button>
                  </div>
                )}
              </div>
            )}
          </CardContent>
        </Card>
      )}

      {tab === "reach" && (
        <Card>
          <CardHeader><CardTitle>{c.tabReach}</CardTitle></CardHeader>
          <CardContent className="space-y-3">
            <Notice>{c.reachHint}</Notice>
            <DataTable columns={reachCols} rows={reach.data ?? []} rowKey={(r) => r.entityNo} />
          </CardContent>
        </Card>
      )}

      {tab === "coupons" && (
        <Card>
          <CardHeader><CardTitle>{c.tabCoupons}</CardTitle></CardHeader>
          <CardContent className="space-y-3">
            <Notice>{c.couponHint}</Notice>
            <DataTable columns={couponCols} rows={coupons.data ?? []} rowKey={(x) => x.couponNo} />
          </CardContent>
        </Card>
      )}

      {tab === "activities" && (
        <Card>
          <CardHeader><CardTitle>{c.tabActivities}</CardTitle></CardHeader>
          <CardContent className="space-y-3">
            <Notice>{c.activityHint}</Notice>
            <DataTable columns={activityCols} rows={activities.data ?? []} rowKey={(x) => x.activityNo} />
          </CardContent>
        </Card>
      )}
    </div>
  );
}
