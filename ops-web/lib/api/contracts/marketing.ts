// 覆盖范围：优惠券（P-7.1）、活动（P-7.2）、内容位（P-7.3）、会员卡（P-7.4）。
import type { MerchantCampaign, PlatformSlot, ContentSlot, Coupon, CouponIssue, CouponStatus, IssueTarget, MemberCard, MemberCardStatus, Page } from "@/lib/types";
import type { CampaignQ, CouponQ, PageQ, SlotQ } from "../query";

export interface MarketingApi {
  listCoupons(q?: CouponQ): Promise<Page<Coupon>>;
  /** 状态推进（草稿→启用⇄暂停→结束），非法迁移抛错。 */
  /**
   * 改券状态（暂停 / 恢复 / 结束）。**理由必填** —— 停别人的券要说得出为什么，
   * 后端把它写进审计，空理由返回 10400。
   *
   * 此前这里没有 reason 参数，于是**运营在真后端下点「暂停」必然失败**；
   * mock 没有这条校验，所以演示一切正常。
   */
  setCouponStatus(couponNo: string, status: CouponStatus, reason: string): Promise<Coupon>;
  /** 调预算（P-7.1.3）。**不得小于已发放金额** —— 否则账面直接超支。 */
  setCouponBudget(couponNo: string, budget: number): Promise<Coupon>;
  /**
   * 发券（P-7.1.2）。超预算直接拒绝，不是"先发了再说"。
   * ⚠️ 客服也持有该权限（补偿券），所以校验必须在服务端。
   */
  issueCoupon(v: { couponNo: string; target: IssueTarget; targetDesc: string; count: number }): Promise<CouponIssue>;
  listCouponIssues(q?: PageQ): Promise<Page<CouponIssue>>;
  archiveCoupon(couponNo: string): Promise<Coupon>;
  unarchiveCoupon(couponNo: string): Promise<Coupon>;

  /**
   * **商家自建的店铺活动**（平台治理视角）。
   *
   * 不是平台投放场次 —— 那个对象后端还没有，见 `PlatformSlot` 的说明。
   */
  listCampaigns(q?: CampaignQ): Promise<Page<MerchantCampaign>>;
  /**
   * 停用 / 启用商家活动（矩阵 §2.3）。**理由必填** —— 停别人的活动要说得出为什么，
   * 后端把它写进审计。这是平台对商家活动的**全部**能力：看得见、能停，
   * 不能建也不能改内容，那是商家自己的经营决定。
   */
  toggleCampaign(campaignNo: string, running: boolean, reason: string): Promise<MerchantCampaign>;
  /**
   * 保存平台投放场次（P-7.2）。结束必须晚于开始；同一位置的秒杀场次不可重叠。
   *
   * ⚠️ **后端尚未实现这个对象**，只有 mock 能跑通。
   */
  saveCampaign(v: Pick<PlatformSlot, "campaignNo" | "name" | "type" | "position" | "startAt" | "endAt">): Promise<PlatformSlot>;
  archiveCampaign(campaignNo: string): Promise<MerchantCampaign>;
  unarchiveCampaign(campaignNo: string): Promise<MerchantCampaign>;

  listContentSlots(q?: SlotQ): Promise<Page<ContentSlot>>;
  /** 上下线开关（P-7.3.5）。 */
  setSlotEnabled(slotNo: string, enabled: boolean): Promise<ContentSlot>;
  /** 定时上下线：下线必须晚于上线。 */
  setSlotSchedule(slotNo: string, onlineAt: string, offlineAt: string): Promise<ContentSlot>;
  archiveSlot(slotNo: string): Promise<ContentSlot>;
  unarchiveSlot(slotNo: string): Promise<ContentSlot>;

  // ── 会员卡与权益（P-7.4）──────────────────────────────────────

  listMemberCards(q?: PageQ & { status?: string }): Promise<Page<MemberCard>>;

  /**
   * 保存会员卡。
   *
   * ⚠️ **已有持卡人的卡，权益与月费都改不了** —— 卖出去的是承诺，不是配置。
   * 要调整就新建一张卡、把旧卡停售，让在售的那张始终与用户当初买的一致。
   *
   * 其余校验：至少一项权益（没有权益的会员卡就是纯收费）；权益类型不重复；
   * 折扣不得低于 `MIN_MEMBER_DISCOUNT`；赠券必须绑一张**已启用**的券模板
   * （绑草稿券的话，用户开卡当天就领不到）。
   */
  saveMemberCard(
    v: Omit<MemberCard, "createdAt" | "updatedAt" | "updatedBy" | "holderCount" | "status" | "cardNo"> & { cardNo?: string },
  ): Promise<MemberCard>;

  /** 状态推进（草稿→启用⇄暂停→停售），非法迁移抛错。停售是终态。 */
  setMemberCardStatus(cardNo: string, status: MemberCardStatus): Promise<MemberCard>;

  /** 归档。**有持卡人的卡归档不了** —— 权益还要继续兑现。 */
  archiveMemberCard(cardNo: string): Promise<MemberCard>;
  unarchiveMemberCard(cardNo: string): Promise<MemberCard>;
}
