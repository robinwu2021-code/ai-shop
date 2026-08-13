// 商家域 mock 数据（P-11.1）。
// 覆盖三种主体分层与全部审核状态，让列表页的筛选、徽标、空态在无后端时都能验到。
import type { MerchantApply, Merchant, MerchantStaffRow } from "@/lib/types";
import type { AdmissionPolicy, DepositTxn, StoreMode } from "@/lib/types";

// 时间用固定字符串而非 Date.now()：mock 数据每次刷新都变的话，截图对不上、测试也不稳。
export const merchants: Merchant[] = [
  {
    merchantNo: "M901", legalForm: "NATURAL_PERSON", name: "阿姨家的菜摊", tier: "SMALL", status: "ACTIVE",
    communityNos: ["C001"],
    contactName: "王秀兰", contactPhone: "138****2011",
    categoryCodes: ["FRESH_VEG"], qualifications: ["食品经营许可证"], verified: false, breachCount: 0,
    settleAccountReady: false, createdAt: "2026-07-28T02:10:00Z", asPickupPoint: true,
  },
  {
    merchantNo: "M902", name: "老张水果店", tier: "SMALL", status: "ACTIVE",
    communityNos: ["C001"],
    contactName: "张建国", contactPhone: "139****7788",
    categoryCodes: ["FRESH_FRUIT"], qualifications: ["食品经营许可证"], verified: false, breachCount: 0,
    settleAccountReady: true, createdAt: "2026-07-30T06:20:00Z",
  },
  {
    merchantNo: "M903", name: "邻家便利", tier: "MEDIUM", status: "ACTIVE",
    communityNos: ["C002"],
    contactName: "李慧", contactPhone: "137****3355",
    categoryCodes: ["DAILY", "FRESH_VEG"], qualifications: ["食品经营许可证"], verified: true, breachCount: 0,
    settleAccountReady: true, createdAt: "2026-06-12T01:00:00Z",
  },
  {
    merchantNo: "M904", name: "社区鲜奶站", tier: "SMALL", status: "ACTIVE",
    communityNos: ["C002"],
    contactName: "赵明", contactPhone: "136****9021",
    categoryCodes: ["FRESH_DAIRY"], qualifications: [], verified: false, breachCount: 0,
    settleAccountReady: false, createdAt: "2026-07-15T08:45:00Z",
    auditRemark: "食品经营许可证照片不清晰，请补交",
  },
  {
    merchantNo: "M905", name: "快修家电服务", tier: "MEDIUM", status: "ACTIVE",
    communityNos: ["C003"],
    contactName: "陈伟", contactPhone: "135****4412",
    categoryCodes: ["SERVICE_REPAIR"], qualifications: ["家电维修资质"], verified: true, breachCount: 1,
    settleAccountReady: true, createdAt: "2026-05-20T03:30:00Z",
  },
  {
    merchantNo: "M906", name: "夜市烧烤（停业整改）", tier: "SMALL", status: "SUSPENDED",
    communityNos: ["C003"],
    contactName: "刘洋", contactPhone: "133****6677",
    categoryCodes: ["FOOD"], qualifications: [], verified: false, breachCount: 3,
    settleAccountReady: true, createdAt: "2026-04-02T11:00:00Z",
    auditRemark: "连续 3 次履约毁约，暂停经营",
  },
];

/**
 * 类目授权码目录（P-11.1.3）。
 *
 * 与类目树是**多对一**：叶菜、根茎菜都归 `FRESH_VEG`。按码授权而不是按类目节点授权 ——
 * 类目树会重构，而"能不能卖菜"这件事不会。
 */
export const authCodes: import("@/lib/types").AuthCode[] = [
  // 一期口径（V22）：果蔬走**初级农产品**，执照就够，不需要食品经营许可证。
  // 乳制品 / 熟食加工 / 维修 已停用（执照覆盖不到），故不在可授权列表里 ——
  // 这个列表服务的是「给商家授权时的可选项」，本来就只该给启用的
  { code: "FRESH_VEG", name: "蔬菜", requiredQualification: "营业执照（食用农产品）" },
  { code: "FRESH_FRUIT", name: "水果", requiredQualification: "营业执照（食用农产品）" },
  { code: "PACKAGED_FOOD", name: "预包装食品", requiredQualification: "仅销售预包装食品备案" },
  { code: "DAILY", name: "日用百货" },
  { code: "HOUSEKEEPING", name: "家政服务" },
];

/** 违规记录（P-11.1.4）。样本里的两条正对应 M905 与 M906 的 breachCount。 */
export const violations: import("@/lib/types").Violation[] = [
  {
    violationNo: "VL901", merchantNo: "M906", merchantName: "夜市烧烤（停业整改）",
    type: "BREACH", action: "SUSPEND",
    detail: "连续 3 次成团后不发货，工单 #2026-0712 / #2026-0718 / #2026-0725",
    operator: "ops01", at: "2026-07-26T03:00:00Z",
  },
  {
    violationNo: "VL902", merchantNo: "M905", merchantName: "快修家电服务",
    type: "BREACH", action: "WARN",
    detail: "上门服务爽约一次，用户投诉 #2026-0630",
    operator: "ops01", at: "2026-06-30T09:20:00Z",
  },
];

/**
 * 入驻申请单（mock）。
 *
 * **有意留一条服务范围为空的**（A903）：那正是运营必须在通过时补上的情形，
 * 恒有值的假数据会把这段界面藏起来，而它是当前链路上最隐蔽的一个断点 ——
 * 没人补的话，商家通过审核、上完架，却对谁都不可见。
 */
export const applies: MerchantApply[] = [
  {
    applyNo: "A901", name: "阿姨家的菜摊", subject: "NATURAL_PERSON",
    contactName: "王秀兰", contactPhone: "138****2011",
    category: "生鲜", desc: "小区门口卖了十年菜", industry: "FRESH",
    serviceScope: "COMMUNITY", communityNos: ["C001"],
    licenses: [], asPickupPoint: true, status: "PENDING",
    createdAt: Date.parse("2026-07-28T02:10:00Z"),
  },
  {
    applyNo: "A902", name: "老张水果店", subject: "INDIVIDUAL",
    contactName: "张建国", contactPhone: "139****7788",
    category: "水果", desc: "连锁两家店", industry: "RETAIL",
    serviceScope: "COMMUNITY", communityNos: ["C001", "C002"],
    licenses: ["https://cdn/license-902.jpg"], asPickupPoint: false, status: "REVIEWING",
    createdAt: Date.parse("2026-07-30T06:20:00Z"),
  },
  {
    applyNo: "A903", name: "巷口烘焙", subject: "INDIVIDUAL",
    contactName: "李梅", contactPhone: "137****3355",
    category: "烘焙", desc: "现烤面包", industry: "BAKERY",
    // ← 服务范围空着：通过时运营必须补，否则这家店对谁都不可见
    licenses: ["https://cdn/license-903.jpg"], asPickupPoint: false, status: "PENDING",
    createdAt: Date.parse("2026-08-01T01:00:00Z"),
  },
];


// ── 弱主体准入（后端 mch_admission_policy / mch_deposit）────────

/**
 * 三档准入策略。**S1/S2 三项全 0 是有意的** ——
 * 一个默认就生效的准入闸门，比没有闸门更危险。只有自然人一档带限制。
 */
export const admissionPolicies: AdmissionPolicy[] = [
  { legalForm: "ENTERPRISE", requiredDepositMinor: 0, singleOrderLimitMinor: 0,
    dailyAmountLimitMinor: 0, banQualifiedCategory: 0, enabled: 1,
    remark: "S1：出事能追到有偿付能力的主体，不设限" },
  { legalForm: "INDIVIDUAL", requiredDepositMinor: 0, singleOrderLimitMinor: 0,
    dailyAmountLimitMinor: 0, banQualifiedCategory: 0, enabled: 1,
    remark: "S2：能追到人、赔付能力弱；先不设限，观察后再调" },
  { legalForm: "NATURAL_PERSON", requiredDepositMinor: 200_000, singleOrderLimitMinor: 50_000,
    dailyAmountLimitMinor: 500_000, banQualifiedCategory: 1, enabled: 1,
    remark: "S3：几乎追不到人，平台是唯一被追的一方；三样同时生效" },
];

export const depositTxns: Record<string, DepositTxn[]> = {
  M901: [
    { txnNo: "DP-1", txnType: "PAY", amountMinor: 200_000, balanceAfterMinor: 200_000,
      reason: "入驻缴纳保证金", operator: "admin", createdAt: "2026-07-01T02:00:00Z" },
    { txnNo: "DP-2", txnType: "FREEZE", amountMinor: 50_000, balanceAfterMinor: 200_000,
      reason: "食品变质投诉，理赔冻结", operator: "admin", createdAt: "2026-08-02T06:00:00Z" },
  ],
};

/**
 * 商家的员工与门店授权（**运营端只读**）。
 *
 * 这份种子刻意造出三种最常被问到的形态：
 * 老板（roles 为空但权限最大）、一人两店两角色、以及**授权为空的人** ——
 * 最后那种正是「他登录进去什么都看不到」的成因，而它在商家自己的界面上
 * 看起来与正常人没有区别。
 */
export const merchantStaff: Record<string, MerchantStaffRow[]> = {
  M901: [
    { mchAccountNo: "MA-901-1", displayName: "张老板", loginPhone: "13800008000",
      isOwner: true, status: "ACTIVE", roles: [] },
    { mchAccountNo: "MA-901-2", displayName: "小王", loginPhone: "13900002222",
      isOwner: false, status: "ACTIVE", roles: [
        { storeNo: "ST001", storeName: "张记粮油·文三路店", role: "MANAGER" },
        { storeNo: "ST002", storeName: "张记粮油·古荡店", role: "CLERK" },
      ] },
    { mchAccountNo: "MA-901-3", displayName: null, loginPhone: "13777771010",
      isOwner: false, status: "ACTIVE", roles: [] },
    { mchAccountNo: "MA-901-4", displayName: "老李", loginPhone: "13777771020",
      isOwner: false, status: "DISABLED", roles: [
        { storeNo: "ST001", storeName: "张记粮油·文三路店", role: "COURIER" },
      ] },
  ],
};

/** 门店经营模式。**payMerchantNo 为空的店不能切第三方** —— 钱没处去。 */
export const storeModes: StoreMode[] = [
  { storeNo: "ST001", storeName: "张记粮油·文三路店", merchantNo: "M901",
    businessMode: "SELF_OPERATED", payMerchantNo: null },
  { storeNo: "ST002", storeName: "张记粮油·古荡店", merchantNo: "M901",
    businessMode: "THIRD_PARTY", payMerchantNo: "PM_M901_DEFAULT" },
];

/**
 * 已登记的资质（后端 `mch_qualification`）。**上架的两个闸门读的就是这张表。**
 *
 * 真库里它此前是空的 —— 入驻收的执照停在申请单，审核通过时没人转存，
 * 于是「资质过期」「类目授权」两个闸门都写好了、都从不触发（V79 已接上）。
 *
 * qualName 要与 `authCodes[].requiredQualification` **同一套字面量** ——
 * 类目授权是按证件名做字符串比对的，名字对不上等于这张证不存在，而两边都不报错。
 */
export const qualifications: import("@/lib/types").Qualification[] = [
  { qualNo: "Q1", entityNo: "M901", qualType: "FOOD_PERMIT", qualName: "食品经营许可证",
    qualNumber: "JY13301060000001", imageUrl: "https://picsum.photos/seed/q1/400/260",
    expireAt: Date.UTC(2027, 5, 30), status: "VALID" },
  { qualNo: "Q2", entityNo: "M901", qualType: "BUSINESS_LICENSE", qualName: "营业执照",
    qualNumber: "91330106MA2XXXXX01", imageUrl: "https://picsum.photos/seed/q2/400/260",
    // 长期有效：**与「已过期」是两回事**，扫描任务不碰它
    expireAt: null, status: "VALID" },
];
