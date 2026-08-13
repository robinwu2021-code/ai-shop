// 覆盖范围：优惠券（P-7.1）、活动（P-7.2）、内容位（P-7.3）、会员卡（P-7.4）。
import type { MerchantCampaign, PlatformSlot, ContentSlot, Coupon, CouponIssue, CouponSaveReq, CouponStatus, IssueTarget, MemberCard, MemberCardStatus, Page } from "@/lib/types";
import type { CampaignQ, CouponQ, PageQ, SlotQ } from "../query";

export interface MarketingApi {
  listCoupons(q?: CouponQ): Promise<Page<Coupon>>;
  /**
   * 建券 / 改券（TDD-营销预算前置）。**只建平台券**——商家自己的店铺券走活动
   * 同步，这里不管。三条硬校验都在服务端：折扣券必须填封顶（取消 0=不封顶）、
   * 发行量必须 >0、预算非零时必须 ≥ 敞口（`totalCount × 单张最大优惠`）。
   *
   * `couponNo` 为空 = 新建。已发放张数 >0 时，编辑不能把发行量改到低于它。
   */
  saveCoupon(v: CouponSaveReq): Promise<Coupon>;
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
  /**
   * 主动发券（P-7.1.2）。客服的补偿券走同一条，**操作人由后端从会话取并留痕**。
   *
   * ⚠️ 目前**只有 `SINGLE_USER` 能真发**，其余三种后端返回 10501「还没做完」：
   * 「定向说明」是自由文本，给不出社区号也给不出 userNo，后端无从知道发给谁。
   * 按名字模糊匹配去猜收券人，猜错就是把钱发给了别人。
   */
  issueCoupon(v: { couponNo: string; target: IssueTarget; targetDesc: string; userNo?: string; count: number }): Promise<CouponIssue>;
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
  /*
   * 这里曾有 saveCampaign（保存平台投放场次 P-7.2）。**2026-08-12 删除**：
   *
   * · 后端没有这个对象，也不打算补 —— 平台场次是平台**自己出资、跨商家**的活动，
   *   与 mkt_campaign（店铺级、商家出资）不是一回事，混表会让分账重撞一次
   *   MktCoupon.funder 踩过的墙。真做它要新表 + 审批状态机 + 算价接入 + 分账，
   *   是一个完整业务域（见 TDD-ops-平台场次），不是补一个端点。
   * · 而且**页面上从来没有调用方** —— 契约、mock、http 三层都写着，
   *   零个消费方。这正是本仓库反复出现的「有能力没有消费方」。
   *
   * 一期不做就把它删掉，而不是留在契约里等人以为能用。
   */
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
