// 门店主页治理 mock（P-10.1）。审核队列刻意留一条机审命中敏感词的，
// 否则「人审要看到机器为什么标它」这条设计在页面上验不到。
import type { StoreAcquisition, StorePageAudit, StoreQrcode } from "@/lib/types";

export const storeAudits: StorePageAudit[] = [
  { auditNo: "SA9001", merchantNo: "M903", merchantName: "邻家便利", kind: "NOTICE", content: "本店今日到货时间调整为下午 4 点，取货请带取货码。", status: "PENDING", hits: [], submittedAt: "2026-08-05T23:10:00Z" },
  { auditNo: "SA9002", merchantNo: "M901", merchantName: "阿姨家的菜摊", kind: "NOTICE", content: "全网最低价！假一赔十，绝对正宗有机蔬菜。", status: "PENDING", hits: ["全网最低", "假一赔十", "绝对"], submittedAt: "2026-08-05T14:22:00Z" },
  { auditNo: "SA9003", merchantNo: "M902", merchantName: "老张水果店", kind: "BANNER", content: "https://cdn.example.com/store/M902/banner-0805.jpg", status: "PENDING", hits: ["图片含二维码"], submittedAt: "2026-08-04T09:05:00Z" },
  { auditNo: "SA9004", merchantNo: "M905", merchantName: "快修家电服务", kind: "NOTICE", content: "空调清洗预约请提前一天下单。", status: "PASSED", hits: [], submittedAt: "2026-08-02T02:00:00Z" },
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
