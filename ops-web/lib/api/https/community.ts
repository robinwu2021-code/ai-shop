// 覆盖范围：社区网格与自提点（P-2.1 / P-2.2）。
import { client } from "../http-client";
import type { CommunityApi } from "../contracts/community";

export const communityHttp: CommunityApi = {
  listCommunities: (q) => client.get("/ops/communities", q),
  setCommunityOpen: (no, opened) => client.post(`/ops/communities/${no}/open`, { opened }),
  setCommunityFence: (no, fenceRadius) => client.post(`/ops/communities/${no}/fence`, { fenceRadius }),
  setCommunityRegion: (no, regionCode) => client.post(`/ops/communities/${no}/region`, { regionCode }),
  listRegions: (parent, enabledOnly) => client.get("/ops/regions", { parent, enabledOnly }),
  regionPath: (code) => client.get("/ops/regions/path", { code }),
  archiveCommunity: (no) => client.post(`/ops/communities/${no}/archive`),
  unarchiveCommunity: (no) => client.post(`/ops/communities/${no}/unarchive`),

  listPickups: (q) => client.get("/ops/pickups", q),
  createPickup: (draft) => client.post("/ops/pickups", draft),
  setPickupStatus: (no, status) => client.post(`/ops/pickups/${no}/status`, { status }),
  setPickupServiceFee: (no, serviceFeeRate) => client.post(`/ops/pickups/${no}/service-fee`, { serviceFeeRate }),
  listRiskyNeighborPickups: (q) => client.get("/ops/pickups/risky", q),
  archivePickup: (no) => client.post(`/ops/pickups/${no}/archive`),
  unarchivePickup: (no) => client.post(`/ops/pickups/${no}/unarchive`),
};
