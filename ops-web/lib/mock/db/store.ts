// 门店主页治理 mock（P-10.1）。审核队列刻意留一条机审命中敏感词的，
// 否则「人审要看到机器为什么标它」这条设计在页面上验不到。
import type { StoreAcquisition, StorePageAudit, StoreQrcode } from "@/lib/types";

export const storeAudits: StorePageAudit[] = [
  { auditNo: "SA9001", merchantNo: "M903", merchantName: "邻家便利", kind: "NOTICE", content: "本店今日到货时间调整为下午 4 点，取货请带取货码。", status: "PENDING", hits: [], submittedAt: "2026-08-05T23:10:00Z" },
  { auditNo: "SA9002", merchantNo: "M901", merchantName: "阿姨家的菜摊", kind: "NOTICE", content: "全网最低价！假一赔十，绝对正宗有机蔬菜。", status: "PENDING", hits: ["全网最低", "假一赔十", "绝对"], submittedAt: "2026-08-05T14:22:00Z" },
  { auditNo: "SA9003", merchantNo: "M902", merchantName: "老张水果店", kind: "BANNER", content: "https://cdn.example.com/store/M902/banner-0805.jpg", status: "PENDING", hits: ["图片含二维码"], submittedAt: "2026-08-04T09:05:00Z" },
  { auditNo: "SA9004", merchantNo: "M905", merchantName: "快修家电服务", kind: "NOTICE", content: "空调清洗预约请提前一天下单。", status: "PASSED", hits: [], submittedAt: "2026-08-02T02:00:00Z" },
  // 覆盖项待审（ADR-013 阶段三）：content 是机器串，display 是运营真正读的东西
  { auditNo: "SA9006", merchantNo: "M901", merchantName: "阿姨家的菜摊", kind: "SERVICE_AREA", content: "DISTRICT:330106", display: "浙江省 / 杭州市 / 西湖区", status: "PENDING", hits: [], submittedAt: "2026-08-06T01:15:00Z" },
  { auditNo: "SA9005", merchantNo: "M906", merchantName: "夜市烧烤", kind: "BANNER", content: "https://cdn.example.com/store/M906/banner-0730.jpg", status: "REJECTED", hits: ["含联系方式"], submittedAt: "2026-07-30T11:40:00Z", reason: "店招图里印了店主微信号，属于站外引流，请去掉后重新提交" },
];

export const storeQrcodes: StoreQrcode[] = [
  { merchantNo: "M903", merchantName: "邻家便利", communityName: "阳光里", code: "shop_M903_c2", size: "10x10cm", printed: 200, scanCount: 1842 },
  { merchantNo: "M902", merchantName: "老张水果店", communityName: "锦绣花园", code: "shop_M902_c1", size: "10x10cm", printed: 150, scanCount: 1130 },
  { merchantNo: "M901", merchantName: "阿姨家的菜摊", communityName: "锦绣花园", code: "shop_M901_c1", size: "6x6cm", printed: 80, scanCount: 402 },
  { merchantNo: "M905", merchantName: "快修家电服务", communityName: "梧桐苑", code: "shop_M905_c3", size: "10x10cm", printed: 120, scanCount: 836 },
];

export const storeAcquisition: StoreAcquisition[] = [
  { merchantNo: "M903", merchantName: "邻家便利", scan: 1842, enter: 1310, register: 540, firstOrder: 312, convRate: 0.169 },
  { merchantNo: "M902", merchantName: "老张水果店", scan: 1130, enter: 806, register: 302, firstOrder: 161, convRate: 0.142 },
  { merchantNo: "M905", merchantName: "快修家电服务", scan: 836, enter: 512, register: 188, firstOrder: 96, convRate: 0.115 },
  { merchantNo: "M901", merchantName: "阿姨家的菜摊", scan: 402, enter: 232, register: 150, firstOrder: 71, convRate: 0.177 },
];

/**
 * 主页模板（P-10.1.1）。
 *
 * 三份样本分别对应三种「运营真的会遇到」的状态：
 * 默认模板（停不掉）、有店在用的模板（停用要拦）、没人用的模板（可以停）。
 */
export const storeTemplates: import("@/lib/types").StoreTemplate[] = [
  {
    templateNo: "TPL901", name: "标准店铺", layout: "GRID",
    sections: [
      { key: "BANNER", enabled: true, required: true },
      { key: "NOTICE", enabled: true, required: false },
      { key: "HOT", enabled: true, required: false },
      { key: "CATEGORY", enabled: true, required: false },
      { key: "COUPON", enabled: false, required: false },
      { key: "GROUP", enabled: false, required: false },
    ],
    enabled: true, isDefault: true, usedByCount: 42,
    updatedAt: "2026-06-01T02:00:00Z", updatedBy: "ops01",
  },
  {
    templateNo: "TPL902", name: "生鲜到家", layout: "LIST",
    sections: [
      { key: "BANNER", enabled: true, required: true },
      { key: "NOTICE", enabled: false, required: false },
      { key: "HOT", enabled: true, required: false },
      { key: "CATEGORY", enabled: true, required: false },
      { key: "COUPON", enabled: true, required: false },
      { key: "GROUP", enabled: true, required: false },
    ],
    enabled: true, isDefault: false, usedByCount: 12,
    updatedAt: "2026-07-10T06:30:00Z", updatedBy: "ops01",
  },
  {
    templateNo: "TPL903", name: "服务门店（试用）", layout: "FEATURE",
    sections: [
      { key: "BANNER", enabled: true, required: true },
      { key: "NOTICE", enabled: true, required: false },
      { key: "HOT", enabled: false, required: false },
      { key: "CATEGORY", enabled: false, required: false },
      { key: "COUPON", enabled: false, required: false },
      { key: "GROUP", enabled: false, required: false },
    ],
    enabled: false, isDefault: false, usedByCount: 0,
    updatedAt: "2026-07-28T09:00:00Z", updatedBy: "ops01",
  },
];

/**
 * 门店档案（P-11.2.1）。
 *
 * ⚠️ `storeNo` 与 `name` **逐条对齐 `merchant.ts` 的 `storeModes` 与
 * `merchantStaff[].roles`** —— 那两处早就有 ST001/ST002，另起一套编号的话
 * 「门店档案」和「准入与保证金」会各说各的门店，而两边都自洽、谁也不报错。
 *
 * 门店名与主体名**故意不同**（张记粮油 vs 阿姨家的菜摊）：一个主体可以开几家
 * 挂别的招牌的店，列表里门店名与商家名是两列，共用一个名字就验不出这一点。
 *
 * 四条样本覆盖运营真的会遇到的四种状态：
 *   · ST001 默认店 + 自营 + 无专属收款号（走主体默认号）
 *   · ST002 第三方 + 有专属收款号
 *   · ST003 **平台强制下线** —— 「解除下线」这个动作唯一能验到的样本
 *   · ST004 商家自助停用 —— 用来验「READONLY 解不了」（那是商家自己关的）
 */
export const stores: import("@/lib/types").StoreGovern[] = [
  {
    storeNo: "ST001", name: "张记粮油·文三路店", address: "杭州市西湖区文三路 122 号",
    merchantNo: "M901", merchantName: "阿姨家的菜摊",
    isDefault: true, status: "ACTIVE", businessMode: "SELF_OPERATED",
    // null = 用主体默认收款号，**不是「没配」** —— 页面要显示成前者
    payMerchantNo: null,
    announcement: "每日 6 点到货，蔬菜当日售完不留隔夜。", openHours: "06:00-21:00",
    deliveryRadiusM: 2000, deliveryMinOrderMinor: 1500, deliveryFeeMinor: 300,
    deliveryFreeThresholdMinor: 4900,
  },
  {
    storeNo: "ST002", name: "张记粮油·古荡店", address: "杭州市西湖区古荡新村 3 幢",
    merchantNo: "M901", merchantName: "阿姨家的菜摊",
    isDefault: false, status: "ACTIVE", businessMode: "THIRD_PARTY",
    payMerchantNo: "PM_M901_DEFAULT",
    announcement: "", openHours: "07:00-20:30",
    deliveryRadiusM: 1500, deliveryMinOrderMinor: 2000, deliveryFeeMinor: 400,
    deliveryFreeThresholdMinor: 5900,
  },
  {
    storeNo: "ST003", name: "夜市烧烤·凤起路店", address: "杭州市下城区凤起路 88 号",
    merchantNo: "M906", merchantName: "夜市烧烤（停业整改）",
    isDefault: true, status: "SUSPENDED", businessMode: "THIRD_PARTY",
    payMerchantNo: "PM_M906_DEFAULT",
    announcement: "", openHours: "17:00-02:00",
    deliveryRadiusM: 3000, deliveryMinOrderMinor: 3000, deliveryFeeMinor: 500,
    deliveryFreeThresholdMinor: 9900,
  },
  {
    storeNo: "ST004", name: "邻家便利·阳光里店", address: "杭州市拱墅区阳光里 1 号商铺",
    merchantNo: "M903", merchantName: "邻家便利",
    isDefault: true, status: "READONLY", businessMode: "THIRD_PARTY",
    payMerchantNo: null,
    announcement: "店主外出，暂停接单三天。", openHours: "08:00-22:00",
    deliveryRadiusM: 1200, deliveryMinOrderMinor: 0, deliveryFeeMinor: 0,
    deliveryFreeThresholdMinor: 0,
  },
];
