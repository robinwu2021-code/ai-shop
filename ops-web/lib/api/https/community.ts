// 覆盖范围：社区网格与自提点（P-2.1 / P-2.2）。
import { client } from "../http-client";
import type { CommunityApi } from "../contracts/community";

export const communityHttp: CommunityApi = {
  coverageHealth: () => client.get("/ops/coverage/health"),
  coverageDistribution: () => client.get("/ops/coverage/distribution"),
  listCommunities: (q) => client.get("/ops/communities", q),
  setCommunityOpen: (no, opened) => client.post(`/ops/communities/${no}/open`, { opened }),
  setCommunityFence: (no, fenceRadius) => client.post(`/ops/communities/${no}/fence`, { fenceRadius }),
  fenceImpact: (no, radiusM) => client.get(`/ops/communities/${no}/fence-impact`,
    radiusM == null ? undefined : { radiusM }),
  createBuilding: (draft) => client.post("/ops/communities/buildings", draft),
  setCommunityRegion: (no, regionCode) => client.post(`/ops/communities/${no}/region`, { regionCode }),
  listCommunityApplies: (q) => client.get("/ops/communities/applies", q),
  decideCommunityApply: (applyNo, pass, opts) =>
    client.post(`/ops/communities/applies/${applyNo}/decide`,
      { pass, regionCode: opts?.regionCode, reason: opts?.reason }),
  listRegions: (parent, enabledOnly) => client.get("/ops/regions", { parent, enabledOnly }),
  regionPath: (code) => client.get("/ops/regions/path", { code }),
  resolveRegion: (q) => client.get("/ops/regions/resolve", q),
  communitiesNear: (latE6, lngE6, radiusM) => client.get("/ops/communities/near", { latE6, lngE6, radiusM }),
  duplicateCommunities: (limit) => client.get("/ops/communities/duplicates", { limit }),
  mergeCommunities: (fromNo, intoNo) => client.post("/ops/communities/merge", { fromNo, intoNo }),
  createRegion: (parent, name) => client.post("/ops/regions", { parent, name }),
  toggleRegion: (code, enabled) => client.post(`/ops/regions/${code}/toggle`, { enabled }),
  renameRegion: (code, name) => client.post(`/ops/regions/${code}/rename`, { name }),
  archiveCommunity: (no) => client.post(`/ops/communities/${no}/archive`),
  unarchiveCommunity: (no) => client.post(`/ops/communities/${no}/unarchive`),

  listPickups: (q) => client.get("/ops/pickups", q),
  createPickup: (draft) => client.post("/ops/pickups", draft),
  setPickupStatus: (no, status) => client.post(`/ops/pickups/${no}/status`, { status }),
  decidePickup: (no, pass, reason) => client.post(`/ops/pickups/${no}/decide`, { pass, reason }),
  setPickupServiceFee: (no, serviceFeeRate) => client.post(`/ops/pickups/${no}/service-fee`, { serviceFeeRate }),
  listRiskyNeighborPickups: (q) => client.get("/ops/pickups/risky", q),
  archivePickup: (no) => client.post(`/ops/pickups/${no}/archive`),
  unarchivePickup: (no) => client.post(`/ops/pickups/${no}/unarchive`),
};
