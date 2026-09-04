"use client";

// 社区与网点（矩阵 P-2.1 社区网格 / P-2.2 自提点）。
//
// 三个 tab 对应三种**对象**，不是三种操作：社区、自提点、临时点风控队列。
// 矩阵里的 2.1.2 开城开关、2.1.3 围栏、2.2.2 启停迁移、2.2.4 费率都是**行上的字段与动作**，
// 拆成独立菜单会让运营在两个页面之间来回找同一个自提点（见 TDD-ops-履约与获客 §3.1）。
import { Suspense, useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { fill, useCopy } from "@/lib/use-copy";
import { COMMUNITIES_COPY } from "./copy";
import { usePaging } from "@/lib/use-paging";
import { usePageTab, useNavTabs } from "@/lib/use-page-tab";
import { NEIGHBOR_RISK_ACCEPT_COUNT } from "@/lib/constants";
import { fmtTime } from "@/lib/utils";
import { useSearchParams } from "next/navigation";
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
import { FenceDialog } from "./fence-dialog";
import { BuildingDialog } from "./building-dialog";
import { TabHeader } from "@/components/ui/tab-header";
import { ApplyTab } from "./apply-tab";
import { RegionTab } from "./region-tab";
import { HealthTab } from "./health-tab";
import { DistributionTab } from "./distribution-tab";
import { DuplicatesPanel } from "./duplicates-panel";
import { Toolbar } from "@/components/ui/toolbar";
import { useConfirm } from "@/components/ui/confirm-dialog";

type Copy = (typeof COMMUNITIES_COPY)["zh"];
const TAB_KEYS = ["grid", "pickups", "neighbor", "applies", "regions", "health", "distribution"] as const;

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
  const sp = useSearchParams();
  const qc = useQueryClient();
  const allow = useCan();
  const { confirm, dialog } = useConfirm();

  const [tab, setTab] = usePageTab(tabs, () => { setPage(1); setKeyword(""); setShowArchived(false); });

  const { page, setPage, size, setSize } = usePaging();
  const [keyword, setKeyword] = useState("");
  /*
   * 开城筛选**认 URL 上的 `opened`**（位置分布那一屏的「去看未开城的聚落 ›」跳过来）。
   *
   * 不认的话那个链接会跳到这一屏、却一条也不筛 —— 运营看到的是完整列表，
   * 而他刚点的是「未开城的那 1 个」：一个跳过来什么也没发生的链接，
   * 比没有链接更糟，它让人以为自己看到的就是筛过的结果。
   */
  const [opened, setOpened] = useState(sp.get("opened") ?? "");
  /*
   * ⚠️ **光有 useState 的初值不够。**
   *
   * 从「位置分布」点「去看未开城的聚落 ›」过来是**同一条路由**的导航，
   * 组件不会重新挂载，初值那一行根本不会再跑 —— 浏览器上验到的就是这个：
   * 页面跳过去了，筛选框还写着「全部开城状态」，四条聚落一条不少。
   * 一个跳过来什么也没发生的链接比没有链接更糟：它让人以为自己看到的就是筛过的结果。
   */
  const spOpened = sp.get("opened");
  useEffect(() => {
    if (spOpened != null) {
      setOpened(spOpened);
      setPage(1);
    }
    // setPage 来自 usePaging，恒等；只跟着 URL 变
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [spOpened]);
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

  // 自建点裁决（P1）。通过走普通确认；驳回要理由 —— 它原样回给商家
  const decidePickupMut = useMutation({
    mutationFn: (v: { pickupNo: string; pass: boolean; reason?: string }) =>
      api.decidePickup(v.pickupNo, v.pass, v.reason),
    onSuccess: (_, v) => { invalidate(); notify.success(v.pass ? cp.toastPickupApproved : cp.toastPickupRejected); },
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

  const fenceMut = useMutation({
    mutationFn: (v: { communityNo: string; radiusM: number }) =>
      api.setCommunityFence(v.communityNo, v.radiusM),
    onSuccess: (x, v) => {
      invalidate();
      qc.invalidateQueries({ queryKey: ["fence-impact"] });
      setFenceOf(null);
      notify.success(fill(cp.toastFenceSaved, { name: x.name, n: v.radiusM }));
    },
  });

  const buildingMut = useMutation({
    mutationFn: (draft: { name: string; address?: string; parentNo: string }) => api.createBuilding(draft),
    onSuccess: (x, draft) => {
      invalidate();
      setBuildingOpen(false);
      notify.success(fill(cp.toastBuildingCreated, {
        name: x.name,
        parent: communities.data?.records.find((r) => r.communityNo === draft.parentNo)?.name ?? draft.parentNo,
      }));
    },
  });

  // ── 社区网格 ──────────────────────────────────────────────────────────
  // 正在编辑归属的社区。null = 抽屉关着
  const [regionOf, setRegionOf] = useState<Community | null>(null);
  /** 正在调围栏的聚落。null = 对话框关着 */
  const [fenceOf, setFenceOf] = useState<Community | null>(null);
  const [buildingOpen, setBuildingOpen] = useState(false);

  const communityColumns: Column<Community>[] = [
    { header: cp.colCommunityNo, cell: (c) => c.communityNo, numeric: true, align: "start" },
    {
      header: cp.colCommunity,
      // 楼栋要能一眼看出挂在谁下面：平铺的话「阳光里」和「阳光里 3 幢」并排两行，
      // 分不出后者在前者里面，而「框了小区就盖住里面每栋楼」正是靠这层归属成立的
      cell: (c) => (
        <span>
          {c.name}
          {c.parentNo && (
            <span className="ml-2 text-xs text-muted-foreground">
              ← {communities.data?.records.find((r) => r.communityNo === c.parentNo)?.name ?? c.parentNo}
            </span>
          )}
        </span>
      ),
    },
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
    {
      // 半径此前是只读的一个数：改它的接口一直都在，界面上却没有出口，
      // 要调只能找人直接改库。点进去还能先看影响，再决定改不改。
      header: cp.colRadius,
      numeric: true,
      cell: (c) =>
        canEditCommunity ? (
          <button type="button" className="focus-ring text-primary underline-offset-2 hover:underline tabular-nums"
                  onClick={() => setFenceOf(c)}>
            {c.fenceRadius} m
          </button>
        ) : (
          <span className="tabular-nums">{c.fenceRadius} m</span>
        ),
    },
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
            className="focus-ring rounded-field px-1 tabular-nums transition-colors hover:bg-accent disabled:cursor-default disabled:hover:bg-transparent"
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
            p.status === "PENDING" ? (
              <span className="flex items-center gap-1">
                <Button
                  size="sm"
                  disabled={!canEditPickup}
                  onClick={() => confirm({
                    title: cp.pickupApproveTitle.replace("{name}", p.name),
                    desc: p.latE6 == null ? cp.pickupNoCoords : `${p.address}`,
                    action: () => decidePickupMut.mutateAsync({ pickupNo: p.pickupNo, pass: true }),
                  })}
                >{cp.btnPickupApprove}</Button>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={!canEditPickup}
                  onClick={() => confirm({
                    title: cp.pickupRejectTitle.replace("{name}", p.name),
                    desc: cp.pickupRejectDesc,
                    danger: true,
                    requireReason: true,
                    action: (reason) => decidePickupMut.mutateAsync({ pickupNo: p.pickupNo, pass: false, reason }),
                  })}
                >{cp.btnPickupReject}</Button>
              </span>
            ) : p.status === "REJECTED" ? (
              <span className="txt-caption text-muted-foreground">{p.rejectReason}</span>
            ) : p.status === "ACTIVE" ? (
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
      {/* 坐标健康度：只读，判 community:community:read（与社区网格同一码） */}
      {tab === "health" && <HealthTab enabled={tab === "health"} />}
      {tab === "distribution" && <DistributionTab enabled={tab === "distribution"} />}

      {tab === "regions" && !allow("community:region:update") && (
        <ReadOnlyNotice what={cp.readOnlyRegionWhat} perm="community:region:update" note={cp.readOnlyRegionNote} className="mb-3" />
      )}
      {/*
        疑似重复：放在社区 tab 的顶部，而不是再开一个 tab。
        它是「这一屏的例外情况」，不是一类新对象 —— 单开 tab 的话，
        运营只有想起来才会去点，而这件事的性质是「有就得处理」。
      */}
      {tab === "grid" && canEditCommunity && (
        <div className="mb-3 flex justify-end">
          <Button size="sm" variant="outline" onClick={() => setBuildingOpen(true)}>{cp.buildingNew}</Button>
        </div>
      )}

      {tab === "grid" && <DuplicatesPanel c={cp} canMerge={canEditCommunity} />}

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

      {tab !== "neighbor" && tab !== "applies" && tab !== "health" && tab !== "distribution" && (
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
      {/*
        health / distribution 自成一屏，**没有分页**。
        照旧渲染的话底下会挂一条「共 0 条 · 1/1」—— 那是在说这一屏没有数据，
        而它上面明明有一张表；读的人只能在两句互相矛盾的话里挑一句信。
      */}
      {tab !== "applies" && tab !== "health" && tab !== "distribution" && (
        <Pagination page={page} size={size} onSize={setSize} total={activeList.data?.total ?? 0} onPage={setPage} />
      )}

      {dialog}

      <RegionPicker
        c={cp}
        community={regionOf}
        canWrite={canEditCommunity}
        onClose={() => setRegionOf(null)}
      />

      {fenceOf && (
        <FenceDialog
          c={cp}
          community={fenceOf}
          saving={fenceMut.isPending}
          onSave={(radiusM) => fenceMut.mutate({ communityNo: fenceOf.communityNo, radiusM })}
          onClose={() => setFenceOf(null)}
        />
      )}

      {buildingOpen && (
        <BuildingDialog
          c={cp}
          // **只列顶层聚落**：归属只做两层，楼底下不再挂楼。
          // 让已经是楼栋的出现在下拉里，运营挑了才被后端拒 —— 那是把规则藏到报错里。
          candidates={(communities.data?.records ?? []).filter((r) => !r.parentNo)}
          saving={buildingMut.isPending}
          onSave={(draft) => buildingMut.mutate(draft)}
          onClose={() => setBuildingOpen(false)}
        />
      )}
    </div>
  );
}
