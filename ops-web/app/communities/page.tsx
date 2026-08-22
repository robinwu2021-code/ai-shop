"use client";

// 社区与网点（矩阵 P-2.1 社区网格 / P-2.2 自提点）。
//
// 三个 tab 对应三种**对象**，不是三种操作：社区、自提点、临时点风控队列。
// 矩阵里的 2.1.2 开城开关、2.1.3 围栏、2.2.2 启停迁移、2.2.4 费率都是**行上的字段与动作**，
// 拆成独立菜单会让运营在两个页面之间来回找同一个自提点（见 TDD-ops-履约与获客 §3.1）。
import { Suspense, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { fill, useCopy } from "@/lib/use-copy";
import { COMMUNITIES_COPY } from "./copy";
import { usePaging } from "@/lib/use-paging";
import { usePageTab, useNavTabs } from "@/lib/use-page-tab";
import { NEIGHBOR_RISK_ACCEPT_COUNT } from "@/lib/constants";
import { fmtTime } from "@/lib/utils";
import { useCan } from "@/lib/use-can";
import { notify } from "@/lib/notify";
import type { Community, PickupPoint, PickupStatus } from "@/lib/types";
import { PickupStatusBadge, PickupPointTypeBadge, usePickupStatusMap, usePickupPointTypeMap } from "@/components/status";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { ArchiveActions, ShowArchivedToggle, archiveConfirm, archivedRowClass, unarchiveConfirm } from "@/components/archive";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { FilterSelect } from "@/components/ui/filter-select";
import { Input } from "@/components/ui/input";
import { Notice } from "@/components/ui/notice";
import { Pagination } from "@/components/ui/misc";
import { Switch } from "@/components/ui/switch";
import { RegionPicker } from "./region-picker";
import { TabHeader } from "@/components/ui/tab-header";
import { ApplyTab } from "./apply-tab";
import { RegionTab } from "./region-tab";
import { Toolbar } from "@/components/ui/toolbar";
import { useConfirm } from "@/components/ui/confirm-dialog";

type Copy = (typeof COMMUNITIES_COPY)["zh"];
const TAB_KEYS = ["grid", "pickups", "neighbor", "applies", "regions"] as const;

const OPEN_OPTIONS = (c: Copy) => [
  { value: "1", label: c.openedYes },
  { value: "0", label: c.openedNo },
];

/** 费率以万分比存（P-2.2.4），展示成百分比 —— 运营说的是"1.5%"不是"150 个万分点"。 */
const fmtRate = (bp: number) => `${(bp / 100).toFixed(2)}%`;

export default function CommunitiesPage() {
  return <Suspense fallback={null}><CommunitiesInner /></Suspense>;
}

function CommunitiesInner() {
  const cp = useCopy(COMMUNITIES_COPY);
  const tabs = useNavTabs("/communities", TAB_KEYS);
  const openOptions = OPEN_OPTIONS(cp);
  const qc = useQueryClient();
  const allow = useCan();
  const { confirm, dialog } = useConfirm();

  const [tab, setTab] = usePageTab(tabs, () => { setPage(1); setKeyword(""); setShowArchived(false); });

  const { page, setPage, size, setSize } = usePaging();
  const [keyword, setKeyword] = useState("");
  const [opened, setOpened] = useState("");
  const [type, setType] = useState("");
  const [status, setStatus] = useState("");
  const [showArchived, setShowArchived] = useState(false);
  const [feeEditing, setFeeEditing] = useState<{ pickupNo: string; value: string } | null>(null);

  const canEditCommunity = allow("community:community:update");
  const canEditPickup = allow("community:pickup:update");

  const typeMap = usePickupPointTypeMap();
  const statusMap = usePickupStatusMap();

  const communityQ = { keyword, opened, showArchived, page, size };
  const communities = useQuery({
    queryKey: ["communities", communityQ],
    queryFn: () => api.listCommunities(communityQ),
    enabled: tab === "grid",
  });

  const pickupQ = { keyword, type, status, showArchived, page, size };
  const pickups = useQuery({
    queryKey: ["pickups", pickupQ],
    queryFn: () => api.listPickups(pickupQ),
    enabled: tab === "pickups",
  });

  const riskyQ = { page, size };
  const risky = useQuery({
    queryKey: ["pickups", "risky", riskyQ],
    queryFn: () => api.listRiskyNeighborPickups(riskyQ),
    enabled: tab === "neighbor",
  });

  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ["communities"] });
    qc.invalidateQueries({ queryKey: ["pickups"] });
  };

  const openMut = useMutation({
    mutationFn: (v: { communityNo: string; opened: boolean }) => api.setCommunityOpen(v.communityNo, v.opened),
    onSuccess: (x) => { invalidate(); notify.success(fill(x.opened ? cp.toastOpened : cp.toastClosed, { name: x.name })); },
  });

  const pickupStatusMut = useMutation({
    mutationFn: (v: { pickupNo: string; status: PickupStatus }) => api.setPickupStatus(v.pickupNo, v.status),
    onSuccess: () => { invalidate(); notify.success(cp.toastPickupStatus); },
  });

  const feeMut = useMutation({
    mutationFn: (v: { pickupNo: string; rate: number }) => api.setPickupServiceFee(v.pickupNo, v.rate),
    onSuccess: () => { invalidate(); setFeeEditing(null); notify.success(cp.toastFeeSaved); },
  });

  const archiveCommunityMut = useMutation({
    mutationFn: (v: { no: string; restore: boolean }) =>
      v.restore ? api.unarchiveCommunity(v.no) : api.archiveCommunity(v.no),
    onSuccess: invalidate,
  });
  const archivePickupMut = useMutation({
    mutationFn: (v: { no: string; restore: boolean }) =>
      v.restore ? api.unarchivePickup(v.no) : api.archivePickup(v.no),
    onSuccess: invalidate,
  });

  // ── 社区网格 ──────────────────────────────────────────────────────────
  // 正在编辑归属的社区。null = 抽屉关着
  const [regionOf, setRegionOf] = useState<Community | null>(null);

  const communityColumns: Column<Community>[] = [
    { header: cp.colCommunityNo, cell: (c) => c.communityNo, numeric: true, align: "start" },
    { header: cp.colCommunity, cell: (c) => c.name },
    { header: cp.colCityGrid, cell: (c) => `${c.city} · ${c.grid}` },
    {
      // 未归属显示成灰字而不是空白：空白读起来像「这一列没数据」，
      // 而它其实是一个待办 —— 没挂区划的社区，按区覆盖时谁也命中不了它
      header: cp.colRegion,
      cell: (c) =>
        c.regionPath ? (
          <span className="txt-caption">{c.regionPath}</span>
        ) : (
          <span className="txt-caption text-muted-foreground">{cp.regionUnset}</span>
        ),
    },
    {
      header: cp.colOpened,
      cell: (c) => (
        <Switch
          checked={c.opened}
          disabled={!canEditCommunity || !!c.archivedAt}
          aria-label={fill(cp.ariaOpenSwitch, { name: c.name })}
          onChange={(v) => openMut.mutate({ communityNo: c.communityNo, opened: v })}
        />
      ),
    },
    { header: cp.colRadius, cell: (c) => `${c.fenceRadius} m`, numeric: true },
    { header: cp.colPickupCount, cell: (c) => c.pickupCount, numeric: true },
    { header: cp.colCreatedAt, cell: (c) => fmtTime(c.createdAt) },
    {
      header: cp.colActions,
      cell: (c) => (
        <>
        <Button size="sm" variant="outline" className="mr-2" onClick={() => setRegionOf(c)}>
          {cp.regionConfigure}
        </Button>
        <ArchiveActions
          archived={!!c.archivedAt}
          canWrite={canEditCommunity}
          // 还挂着自提点的社区不许归档：归档后这些点会从列表消失，但货还会到
          canArchive={c.pickupCount === 0}
          archiveHint={cp.archiveHintCommunity}
          onArchive={async () => {
            await confirm(archiveConfirm(cp.entityCommunity, c.name, c.communityNo, () => archiveCommunityMut.mutateAsync({ no: c.communityNo, restore: false })));
          }}
          onUnarchive={async () => {
            await confirm(unarchiveConfirm(cp.entityCommunity, c.name, () => archiveCommunityMut.mutateAsync({ no: c.communityNo, restore: true })));
          }}
        />
        </>
      ),
    },
  ];

  // ── 自提点 ────────────────────────────────────────────────────────────
  const pickupColumns: Column<PickupPoint>[] = [
    { header: cp.colPickupNo, cell: (p) => p.pickupNo, numeric: true, align: "start" },
    { header: cp.colName, cell: (p) => p.name },
    { header: cp.colType, cell: (p) => <PickupPointTypeBadge value={p.type} /> },
    { header: cp.colCommunity, cell: (p) => p.communityName },
    { header: cp.colCarrier, cell: (p) => p.merchantName ?? "—" },
    { header: cp.colTimes, cell: (p) => `${p.arriveTime} / ${p.openHours}` },
    {
      header: cp.colFeeRate,
      numeric: true,
      cell: (p) =>
        p.type === "NEIGHBOR" ? (
          // 零报酬是规则不是"还没配"，所以写清楚而不是留空
          <span className="text-muted-foreground">{cp.zeroFee}</span>
        ) : feeEditing?.pickupNo === p.pickupNo ? (
          <span className="flex items-center justify-end gap-1">
            <Input
              className="w-20"
              value={feeEditing.value}
              aria-label={cp.ariaFeeRate}
              onChange={(e) => setFeeEditing({ pickupNo: p.pickupNo, value: e.target.value })}
            />
            <Button size="sm" onClick={() => feeMut.mutate({ pickupNo: p.pickupNo, rate: Number(feeEditing.value) })}>{cp.save}</Button>
            <Button size="sm" variant="ghost" onClick={() => setFeeEditing(null)}>{cp.cancel}</Button>
          </span>
        ) : (
          <button
            type="button"
            disabled={!canEditPickup}
            className="rounded-field px-1 tabular-nums transition-colors hover:bg-accent disabled:cursor-default disabled:hover:bg-transparent"
            onClick={() => setFeeEditing({ pickupNo: p.pickupNo, value: String(p.serviceFeeRate) })}
          >
            {fmtRate(p.serviceFeeRate)}
          </button>
        ),
    },
    { header: cp.colStatus, cell: (p) => <PickupStatusBadge value={p.status} /> },
    {
      header: cp.colActions,
      cell: (p) => (
        <ArchiveActions
          archived={!!p.archivedAt}
          canWrite={canEditPickup}
          onArchive={async () => {
            await confirm(archiveConfirm(cp.entityPickup, p.name, p.pickupNo, () => archivePickupMut.mutateAsync({ no: p.pickupNo, restore: false })));
          }}
          onUnarchive={async () => {
            await confirm(unarchiveConfirm(cp.entityPickup, p.name, () => archivePickupMut.mutateAsync({ no: p.pickupNo, restore: true })));
          }}
          actions={
            // 只出**当前状态允许**的那一个迁移（合法迁移表见 lib/types/community.ts）
            p.status === "ACTIVE" ? (
              <Button size="sm" variant="outline" onClick={() => pickupStatusMut.mutate({ pickupNo: p.pickupNo, status: "SUSPENDED" })}>{cp.btnSuspend}</Button>
            ) : p.status === "SUSPENDED" ? (
              <Button size="sm" variant="outline" onClick={() => pickupStatusMut.mutate({ pickupNo: p.pickupNo, status: "ACTIVE" })}>{cp.btnActivate}</Button>
            ) : (
              <Button size="sm" variant="outline" onClick={() => pickupStatusMut.mutate({ pickupNo: p.pickupNo, status: "SUSPENDED" })}>{cp.btnFinishMigrate}</Button>
            )
          }
        />
      ),
    },
  ];

  // ── 临时点风控 ────────────────────────────────────────────────────────
  const riskyColumns: Column<PickupPoint>[] = [
    { header: cp.colPickupNo, cell: (p) => p.pickupNo, numeric: true, align: "start" },
    { header: cp.colName, cell: (p) => p.name },
    { header: cp.colCommunity, cell: (p) => p.communityName },
    {
      header: cp.col30dAccept,
      numeric: true,
      cell: (p) => (
        <span className="inline-flex items-center gap-2">
          {p.acceptCount30d}
          <Badge tone="warning">{cp.suspectPro}</Badge>
        </span>
      ),
    },
    { header: cp.colFiledAt, cell: (p) => fmtTime(p.createdAt) },
    {
      header: cp.colActions,
      cell: (p) =>
        canEditPickup ? (
          <Button size="sm" variant="outline" onClick={() => pickupStatusMut.mutate({ pickupNo: p.pickupNo, status: "SUSPENDED" })}>
            {cp.btnPauseIntake}
          </Button>
        ) : <span className="text-muted-foreground">-</span>,
    },
  ];

  const activeList =
    tab === "grid" ? communities : tab === "pickups" ? pickups : risky;

  return (
    <div>
      <TabHeader tabs={tabs} value={tab} onChange={setTab} />

      {/* 提报审核自带筛选与分页，与其余三个 tab 的工具条无关 —— 所以整块拆出去 */}
      {tab === "applies" && <ApplyTab c={cp} canDecide={canEditCommunity} />}

      {tab === "applies" && !canEditCommunity && (
        <ReadOnlyNotice what={cp.readOnlyApplyWhat} perm="community:community:update" note={cp.readOnlyApplyNote} className="mb-3" />
      )}

      {/*
        区划补录：与提报审核同构 —— 自带筛选，与其余 tab 的工具条无关。
        裁决按 community:region:update 判，而不是复用 community:community:update ——
        通过一条会让这个村对「全平台商家」可见，与改一个社区不是一回事。
      */}
      {tab === "regions" && <RegionTab c={cp} canDecide={allow("community:region:update")} />}

      {tab === "regions" && !allow("community:region:update") && (
        <ReadOnlyNotice what={cp.readOnlyRegionWhat} perm="community:region:update" note={cp.readOnlyRegionNote} className="mb-3" />
      )}
      {tab === "grid" && !canEditCommunity && (
        <ReadOnlyNotice what={cp.readOnlyCommunityWhat} perm="community:community:update" note={cp.readOnlyCommunityNote} className="mb-3" />
      )}
      {tab !== "grid" && !canEditPickup && (
        <ReadOnlyNotice what={cp.readOnlyPickupWhat} perm="community:pickup:update" note={cp.readOnlyPickupNote} className="mb-3" />
      )}

      {tab === "neighbor" && (
        <Notice className="mb-3">
          {fill(cp.neighborNotice, { n: NEIGHBOR_RISK_ACCEPT_COUNT })}
        </Notice>
      )}

      {tab !== "neighbor" && tab !== "applies" && (
        <Toolbar
          search={keyword}
          onSearch={(v) => { setKeyword(v); setPage(1); }}
          searchPlaceholder={tab === "grid" ? cp.searchGrid : cp.searchPickup}
        >
          {tab === "grid" && (
            <FilterSelect aria-label={cp.filterOpened} value={opened} onChange={(v) => { setOpened(v); setPage(1); }} options={openOptions} allLabel={cp.filterOpenedAll} />
          )}
          {tab === "pickups" && (
            <>
              <FilterSelect aria-label={cp.filterType} value={type} onChange={(v) => { setType(v); setPage(1); }} options={typeMap} allLabel={cp.filterTypeAll} />
              <FilterSelect aria-label={cp.filterStatus} value={status} onChange={(v) => { setStatus(v); setPage(1); }} options={statusMap} allLabel={cp.filterStatusAll} />
            </>
          )}
          <ShowArchivedToggle checked={showArchived} onChange={(v) => { setShowArchived(v); setPage(1); }} />
        </Toolbar>
      )}

      {tab === "grid" && (
        <DataTable
          columns={communityColumns}
          rows={communities.data?.records}
          loading={communities.isLoading}
          error={communities.error}
          onRetry={() => communities.refetch()}
          rowKey={(c) => c.communityNo}
          rowClassName={archivedRowClass}
          empty={cp.emptyGrid}
        />
      )}
      {tab === "pickups" && (
        <DataTable
          columns={pickupColumns}
          rows={pickups.data?.records}
          loading={pickups.isLoading}
          error={pickups.error}
          onRetry={() => pickups.refetch()}
          rowKey={(p) => p.pickupNo}
          rowClassName={archivedRowClass}
          empty={cp.emptyPickup}
        />
      )}
      {tab === "neighbor" && (
        <DataTable
          columns={riskyColumns}
          rows={risky.data?.records}
          loading={risky.isLoading}
          error={risky.error}
          onRetry={() => risky.refetch()}
          rowKey={(p) => p.pickupNo}
          empty={fill(cp.emptyNeighbor, { n: NEIGHBOR_RISK_ACCEPT_COUNT })}
        />
      )}

      {/* 提报 tab 自带分页 —— 这里再摆一个的话，一屏两个分页条，点哪个都不对 */}
      {tab !== "applies" && (
        <Pagination page={page} size={size} onSize={setSize} total={activeList.data?.total ?? 0} onPage={setPage} />
      )}

      {dialog}

      <RegionPicker
        c={cp}
        community={regionOf}
        canWrite={canEditCommunity}
        onClose={() => setRegionOf(null)}
      />
    </div>
  );
}
