// 覆盖范围：社区网格与自提点（P-2.1 / P-2.2）。写操作真改 db，规则在此强制。
import * as db from "@/lib/mock/db";
import { NEIGHBOR_RISK_ACCEPT_COUNT } from "@/lib/constants";
import { PICKUP_TRANSITIONS, type Community, type PickupPoint } from "@/lib/types";
import type { CommunityApi } from "../contracts/community";
import { fail, notFound } from "@/lib/biz-error";

/**
 * 从省到自身。查不到时返回**已经走到的部分**，不抛也不返空 ——
 * 区划每年调整，存量里会有撤并的旧码；抛异常会让一个社区弄挂整个列表页。
 */
function pathOf(code: string) {
  const chain: import("@/lib/types").Region[] = [];
  let cur: string | undefined = code;
  for (let i = 0; i < 8 && cur; i++) {
    const row = db.regions.find((r) => r.regionCode === cur);
    if (!row) break;
    chain.unshift(row);
    cur = row.parentCode;
  }
  return chain;
}
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

  listCommunityApplies: (q = {}) =>
    wait(db.paginate(db.communityApplies, q.page, q.size,
      (a) => (q.status && q.status !== "ALL" ? a.status === q.status : true))),

  decideCommunityApply: async (applyNo, pass, opts) => {
    const a = db.communityApplies.find((x) => x.applyNo === applyNo);
    if (!a) notFound("提报单", "Community request", applyNo);
    // 裁完就是终态：再裁一次意味着同一条提报有两个结论，而通过那次已经建了社区
    if (a.status !== "PENDING") fail("这条提报已经裁决过了", "This request was already decided");
    if (!pass && !opts?.reason?.trim()) {
      // 理由原样回给商家 —— 不写的话他不知道该改什么，只会原样再提一次
      fail("驳回必须写原因", "A reason is required to reject");
    }
    if (pass) {
      const code = opts?.regionCode?.trim() || a.regionCode;
      const communityNo = `C${900 + db.communities.length}`;
      db.communities.unshift({
        communityNo, name: a.name, city: "杭州", grid: "", opened: true,
        fenceRadius: 1000, pickupCount: 0, createdAt: new Date().toISOString(),
        regionCode: code,
        regionPath: code ? db.regions.find((r) => r.regionCode === code)?.name : undefined,
      });
      a.communityNo = communityNo;
      a.regionCode = code;
      a.status = "APPROVED";
    } else {
      a.status = "REJECTED";
      a.reason = opts?.reason?.trim();
    }
    return wait(a, 400);
  },

  setCommunityRegion: async (communityNo, regionCode) => {
    const c = findCommunity(communityNo);
    const code = regionCode?.trim();
    if (!code) {
      // 清空是允许的：挂错了要能改回来
      delete c.regionCode;
      delete c.regionPath;
      return wait(c, 400);
    }
    /*
     * 挂之前先确认这个码存在。挂到不存在的码上不会报错，只会让这个社区在
     * 任何「按区覆盖」里都出不来 —— 而运营看着界面上明明填着值。
     */
    const path = pathOf(code);
    if (!path.length) notFound("区划", "Region", code);
    c.regionCode = code;
    c.regionPath = path.map((r) => r.name).join(" / ");
    return wait(c, 400);
  },

  listRegions: async (parent, enabledOnly) =>
    wait(db.regions.filter((r) =>
      (parent ? r.parentCode === parent : !r.parentCode) && (!enabledOnly || r.enabled))),

  regionPath: async (code) => wait(pathOf(code)),

  /**
   * 区划维护。mock 直接改 db.regions —— 「停用后还能开回来」这条
   * 必须在 mock 上走得通，否则运营端唯一能演的是把树越停越少。
   */
  createRegion: async (parent, name) => {
    const p = db.regions.find((r) => r.regionCode === parent);
    if (!p) fail("父级区划不存在", "Parent region does not exist");
    const dup = db.regions.find((r) => r.parentCode === parent && r.name === name);
    if (dup) return wait(dup, 300);
    const n = db.regions.filter((r) => r.regionCode.startsWith(`${parent}X`)).length + 1;
    const row = {
      regionCode: `${parent}X${String(n).padStart(2, "0")}`,
      parentCode: parent,
      level: p!.level === "DISTRICT" ? "STREET" : p!.level === "CITY" ? "DISTRICT" : "CITY",
      name, enabled: true, hasChild: false,
    } as (typeof db.regions)[number];
    db.regions.push(row);
    p!.hasChild = true;
    return wait(row, 300);
  },

  toggleRegion: async (code, enabled) => {
    const r = db.regions.find((x) => x.regionCode === code);
    if (!r) fail("区划不存在", "Region does not exist");
    r!.enabled = enabled;
    return wait(r!, 300);
  },

  renameRegion: async (code, name) => {
    const r = db.regions.find((x) => x.regionCode === code);
    if (!r) fail("区划不存在", "Region does not exist");
    r!.name = name;
    return wait(r!, 300);
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

  decidePickup: async (pickupNo, pass, reason) => {
    const p = findPickup(pickupNo);
    if (p.status !== "PENDING") fail("已经裁过了", "Already decided");
    if (!pass && !reason?.trim()) fail("驳回要写理由", "A reason is required to reject");
    p.status = pass ? "ACTIVE" : "REJECTED";
    p.rejectReason = pass ? null : reason!.trim();
    return wait(p, 400);
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
