// 覆盖范围：券 / 活动 / 内容位（P-7）。
import { client } from "../http-client";
import type { MarketingApi } from "../contracts/marketing";

export const marketingHttp: MarketingApi = {
  listCoupons: (q) => client.get("/ops/coupons", q),
  setCouponStatus: (no, status, reason) =>
    client.post(`/ops/coupons/${no}/status`, { status, reason }),
  setCouponBudget: (no, budget) => client.post(`/ops/coupons/${no}/budget`, { budget }),
  issueCoupon: (v) =>
    client.post(`/ops/coupons/${v.couponNo}/issue`, {
      target: v.target, targetDesc: v.targetDesc, userNo: v.userNo, count: v.count,
    }),
  listCouponIssues: (q) => client.get("/ops/coupon-issues", q),
  archiveCoupon: (no) => client.post(`/ops/coupons/${no}/archive`),
  unarchiveCoupon: (no) => client.post(`/ops/coupons/${no}/unarchive`),

  listCampaigns: (q) => client.get("/ops/campaigns", q),
  toggleCampaign: (no, running, reason) =>
    client.post(`/ops/campaigns/${no}/toggle`, { running, reason }),
  archiveCampaign: (no) => client.post(`/ops/campaigns/${no}/archive`),
  unarchiveCampaign: (no) => client.post(`/ops/campaigns/${no}/unarchive`),

  listContentSlots: (q) => client.get("/ops/content-slots", q),
  setSlotEnabled: (no, enabled) => client.post(`/ops/content-slots/${no}/enabled`, { enabled }),
  setSlotSchedule: (no, onlineAt, offlineAt) => client.post(`/ops/content-slots/${no}/schedule`, { onlineAt, offlineAt }),
  archiveSlot: (no) => client.post(`/ops/content-slots/${no}/archive`),
  unarchiveSlot: (no) => client.post(`/ops/content-slots/${no}/unarchive`),
  listMemberCards: (q) => client.get("/ops/marketing/member-cards", q),
  saveMemberCard: (v) => client.post("/ops/marketing/member-cards", v),
  setMemberCardStatus: (cardNo, status) => client.post(`/ops/marketing/member-cards/${cardNo}/status`, { status }),
  archiveMemberCard: (cardNo) => client.post(`/ops/marketing/member-cards/${cardNo}/archive`),
  unarchiveMemberCard: (cardNo) => client.post(`/ops/marketing/member-cards/${cardNo}/unarchive`),
};
