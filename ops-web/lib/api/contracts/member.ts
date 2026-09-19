// 覆盖范围：运营侧的会员与人档（P8 · O1–O4）与新模型营销（O5–O7）。
//
// 与商家侧的差别只有一条：**跨商家**。字段与脱敏口径完全一样 ——
// 运营看得到「谁是谁家的会员」，但看不到完整手机号。
import type { MemberLevelPolicy, OpsEnrollment, OpsMember, OpsPerson, OpsPlatformActivity, OpsPlatformDraft, OpsPromoActivity, OpsPromoCoupon, Page, ReachStat }
  from "@/lib/types";

export interface MemberApi {
  /**
   * 跨商家会员名单。
   *
   * @param phoneTail **只接受恰好四位**。给前缀就等于把全平台会员库
   *                  变成一本可翻的通讯录，而运营的读权限比商家宽得多。
   *                  后四位会撞是有意的：看到几个候选再按别的线索确认。
   */
  listOpsMembers(q?: { entityNo?: string; phoneTail?: string; page?: number; size?: number })
    : Promise<Page<OpsMember>>;

  /** 人档：他是哪几家店的会员 —— 这正是人档存在的理由 */
  getOpsPerson(personNo: string): Promise<OpsPerson>;

  /**
   * 查看完整手机号（申诉处置）。
   *
   * **理由必填且不少于四个字**，后端每次都写审计。这是唯一能把后四位
   * 还原成真实号码的地方，谁看了谁的号必须留得下来。
   */
  revealMemberPhone(personNo: string, reason: string): Promise<{ phone: string }>;

  /** 触达量与退订率，**按退订率倒序** —— 发得多不是成绩，发到有人关掉才是问题 */
  listReachStats(days?: number): Promise<ReachStat[]>;
  /** 会员分层口径。看要 `member:member:read` */
  getLevelPolicy(): Promise<MemberLevelPolicy>;
  /** 改分层口径。要 `system:param:update` —— 一改，全平台所有商家的「沉睡」人数跟着变 */
  saveLevelPolicy(v: Pick<MemberLevelPolicy, "sleepDays" | "loyalD90Orders" | "regularD90Orders">): Promise<MemberLevelPolicy>;

  /** 全平台券（新模型）：归属、敞口、异常标记 */
  listOpsPromoCoupons(entityNo?: string): Promise<OpsPromoCoupon[]>;

  /** 全平台活动（新模型）：归属、受众、限量 */
  listOpsPromoActivities(entityNo?: string): Promise<OpsPromoActivity[]>;

  /**
   * 强制停止一个活动。**原因必填且商家可见** ——
   * 不给理由的话，商家看到的是「我的活动莫名其妙没了」。
   */
  stopOpsActivity(activityNo: string, reason: string): Promise<OpsPromoActivity>;

  /** 平台活动（s29）：全部，含草稿，带审核计数与预算占用 */
  listPlatformActivities(): Promise<OpsPlatformActivity[]>;
  /** 建 / 改平台活动。发布后只能改报名截止与预算 */
  savePlatformActivity(draft: OpsPlatformDraft): Promise<OpsPlatformActivity>;
  /** 一个平台活动的报名（s30）；status 空 = 全部 */
  listEnrollments(activityNo: string, status?: string): Promise<OpsEnrollment[]>;
  /** 通过 / 驳回。通过占预算，超了拒（40032）；驳回理由必填，商家原样看到 */
  reviewEnrollment(enrollmentNo: string, pass: boolean, reason?: string): Promise<OpsEnrollment>;
}
