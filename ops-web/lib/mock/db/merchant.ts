// 商家域 mock 数据（P-11.1）。
// 覆盖三种主体分层与全部审核状态，让列表页的筛选、徽标、空态在无后端时都能验到。
import type { MerchantApply, Merchant } from "@/lib/types";

// 时间用固定字符串而非 Date.now()：mock 数据每次刷新都变的话，截图对不上、测试也不稳。
export const merchants: Merchant[] = [
  {
    merchantNo: "M901", name: "阿姨家的菜摊", tier: "PERSONAL", status: "ACTIVE",
    communityNos: ["C001"],
    contactName: "王秀兰", contactPhone: "138****2011",
    categoryCodes: ["FRESH_VEG"], qualifications: ["食品经营许可证"], verified: false, breachCount: 0,
    settleAccountReady: false, createdAt: "2026-07-28T02:10:00Z", asPickupPoint: true,
  },
  {
    merchantNo: "M902", name: "老张水果店", tier: "INDIVIDUAL", status: "ACTIVE",
    communityNos: ["C001"],
    contactName: "张建国", contactPhone: "139****7788",
    categoryCodes: ["FRESH_FRUIT"], qualifications: ["食品经营许可证"], verified: false, breachCount: 0,
    settleAccountReady: true, createdAt: "2026-07-30T06:20:00Z",
  },
  {
    merchantNo: "M903", name: "邻家便利", tier: "COMPANY", status: "ACTIVE",
    communityNos: ["C002"],
    contactName: "李慧", contactPhone: "137****3355",
    categoryCodes: ["DAILY", "FRESH_VEG"], qualifications: ["食品经营许可证"], verified: true, breachCount: 0,
    settleAccountReady: true, createdAt: "2026-06-12T01:00:00Z",
  },
  {
    merchantNo: "M904", name: "社区鲜奶站", tier: "INDIVIDUAL", status: "ACTIVE",
    communityNos: ["C002"],
    contactName: "赵明", contactPhone: "136****9021",
    categoryCodes: ["FRESH_DAIRY"], qualifications: [], verified: false, breachCount: 0,
    settleAccountReady: false, createdAt: "2026-07-15T08:45:00Z",
    auditRemark: "食品经营许可证照片不清晰，请补交",
  },
  {
    merchantNo: "M905", name: "快修家电服务", tier: "COMPANY", status: "ACTIVE",
    communityNos: ["C003"],
    contactName: "陈伟", contactPhone: "135****4412",
    categoryCodes: ["SERVICE_REPAIR"], qualifications: ["家电维修资质"], verified: true, breachCount: 1,
    settleAccountReady: true, createdAt: "2026-05-20T03:30:00Z",
  },
  {
    merchantNo: "M906", name: "夜市烧烤（停业整改）", tier: "PERSONAL", status: "SUSPENDED",
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
  { code: "FRESH_VEG", name: "蔬菜", requiredQualification: "食品经营许可证" },
  { code: "FRESH_FRUIT", name: "水果", requiredQualification: "食品经营许可证" },
  { code: "FRESH_DAIRY", name: "乳制品", requiredQualification: "食品经营许可证" },
  { code: "FOOD", name: "熟食加工", requiredQualification: "食品经营许可证" },
  { code: "DAILY", name: "日用百货" },
  { code: "SERVICE_REPAIR", name: "维修服务", requiredQualification: "家电维修资质" },
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
    applyNo: "A901", name: "阿姨家的菜摊", subject: "MICRO",
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
