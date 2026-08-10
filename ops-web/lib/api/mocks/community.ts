// 覆盖范围：社区网格与自提点（P-2.1 / P-2.2）。写操作真改 db，规则在此强制。
import * as db from "@/lib/mock/db";
import { NEIGHBOR_RISK_ACCEPT_COUNT } from "@/lib/constants";
import { PICKUP_TRANSITIONS, type Community, type PickupPoint } from "@/lib/types";
import type { CommunityApi } from "../contracts/community";
import { fail, notFound } from "@/lib/biz-error";
import { wait } from "./_wait";

function findCommunity(no: string): Community {
  const c = db.communities.find((x) => x.communityNo === no);
  if (!c) notFound("社区", "Community", no);
  return c;
}
function findPickup(no: string): PickupPoint {
  const p = db.pickups.find((x) => x.pickupNo === no);
  if (!p) notFound("自提点", "Pickup point", no);
  return p;
}

export const communityMock: CommunityApi = {
  listCommunities: (q = {}) =>
    wait(
      db.paginate(db.communities, q.page, q.size, (c) =>
        db.liveHit(c, q.showArchived) &&
        db.eqHit(q.communityNo, c.communityNo) &&
        db.eqHit(q.city, c.city) &&
        // opened 从下拉来，是字符串 "1"/"0"，不是 boolean
        (!q.opened || (q.opened === "1") === c.opened) &&
        db.kwHit(q.keyword, c.communityNo, c.name, c.grid, c.city),
      ),
    ),

  setCommunityOpen: async (communityNo, opened) => {
    const c = findCommunity(communityNo);
    c.opened = opened;
    return wait(c, 400);
  },

  setCommunityFence: async (communityNo, fenceRadius) => {
    const c = findCommunity(communityNo);
    // 围栏半径为 0 等于"谁都不覆盖"，是配置事故而非合法值
    if (!(fenceRadius > 0)) fail("覆盖半径必须大于 0 米", "The coverage radius must be greater than 0 m");
    c.fenceRadius = fenceRadius;
    return wait(c, 400);
  },

  archiveCommunity: async (no) => wait(db.archiveRow(db.communities, "communityNo", no), 400),
  unarchiveCommunity: async (no) => wait(db.unarchiveRow(db.communities, "communityNo", no), 400),

  listPickups: (q = {}) =>
    wait(
      db.paginate(db.pickups, q.page, q.size, (p) =>
        db.liveHit(p, q.showArchived) &&
        db.scopeHit(q, p) &&
        db.eqHit(q.type, p.type) &&
        db.eqHit(q.status, p.status) &&
        db.kwHit(q.keyword, p.pickupNo, p.name, p.address, p.merchantName),
      ),
    ),

  createPickup: async (draft) => {
    if (!draft.communityNo || !draft.name?.trim() || !draft.address?.trim()) {
      fail("社区、名称、地址都必填", "Community, name and address are all required");
    }
    if (!db.communities.some((c) => c.communityNo === draft.communityNo)) {
      // 挂在不存在的社区上，这个点对谁都不可见，而列表看着是正常的
      fail("社区不存在", "No such community");
    }
    // owner_ref 是多态的：STORE 门店号 / NEIGHBOR 用户号 / PLATFORM 空
    if ((draft.type === "STORE" || draft.type === "NEIGHBOR") && !draft.ownerRef?.trim()) {
      fail("这类自提点必须指定承接方", "This pickup type requires an owner");
    }
    // ADR-005 §4：给了报酬，承接的邻居就变成团长
    if (draft.type === "NEIGHBOR" && (draft.serviceFeeRate || draft.serviceFeePerItemMinor)) {
      fail("邻里自提点为零报酬", "Neighbour pickup points are unpaid");
    }
    const created: PickupPoint = {
      pickupNo: `P${String(db.pickups.length + 900).padStart(3, "0")}`,
      name: draft.name.trim(),
      type: draft.type,
      status: "ACTIVE",
      communityNo: draft.communityNo,
      communityName:
        db.communities.find((c) => c.communityNo === draft.communityNo)?.name ?? draft.communityNo,
      storeNo: draft.type === "STORE" ? draft.ownerRef : undefined,
      address: draft.address.trim(),
      openHours: draft.openHours ?? "",
      arriveTime: draft.arrivalDesc ?? "",
      serviceFeeRate: draft.serviceFeeRate ?? 0,
      feeMode: "NONE",
      serviceFeePerItemMinor: draft.serviceFeePerItemMinor ?? 0,
      acceptCount30d: 0,
      createdAt: new Date().toISOString(),
    };
    db.pickups.unshift(created);
    return wait(created, 400);
  },

  setPickupStatus: async (pickupNo, status) => {
    const p = findPickup(pickupNo);
    db.assertTransition(PICKUP_TRANSITIONS, p.status, status, "自提点", "Pickup point");
    p.status = status;
    return wait(p, 400);
  },

  setPickupServiceFee: async (pickupNo, serviceFeeRate) => {
    const p = findPickup(pickupNo);
    // ADR-005 §4：临时自提点零报酬。放行的话，"给邻居发钱"会变成产品事实，
    // 再想收回来就是改规则而不是改配置了。
    if (p.type === "NEIGHBOR") fail("邻里自提点为零报酬，不可配置履约服务费", "Neighbour pickup points are unpaid — no service fee can be set for them");
    if (serviceFeeRate < 0) fail("费率不能为负", "The rate cannot be negative");
    p.serviceFeeRate = serviceFeeRate;
    return wait(p, 400);
  },

  listRiskyNeighborPickups: (q = {}) =>
    wait(
      db.paginate(db.pickups, q.page, q.size, (p) =>
        p.type === "NEIGHBOR" &&
        db.liveHit(p, q.showArchived) &&
        db.scopeHit(q, p) &&
        // 阈值走常量：ADR-005 F6 只是建议值，风控要调时改一处
        p.acceptCount30d >= NEIGHBOR_RISK_ACCEPT_COUNT,
      ),
    ),

  archivePickup: async (no) => wait(db.archiveRow(db.pickups, "pickupNo", no), 400),
  unarchivePickup: async (no) => wait(db.unarchiveRow(db.pickups, "pickupNo", no), 400),
};
