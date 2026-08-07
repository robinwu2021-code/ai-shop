// 商品与类目 mock（P-3）。刻意覆盖：三级树、五种模板、缺市场价的待审商品、
// 缺资质的商家商品、超卖的预售商品 —— 每条规则都要有能验到它的样本。
import type { Category, Sku } from "@/lib/types";

export const categories: Category[] = [
  { categoryNo: "CAT100", name: "食品生鲜", level: 1, template: "FRESH", qualifications: [], i18n: { zh: "食品生鲜", en: "Fresh Food" }, skuCount: 0 },
  { categoryNo: "CAT110", name: "蔬菜", parentNo: "CAT100", level: 2, template: "FRESH", qualifications: [], i18n: { zh: "蔬菜", en: "Vegetables" }, skuCount: 0 },
  { categoryNo: "CAT111", name: "叶菜", parentNo: "CAT110", level: 3, template: "FRESH", qualifications: ["食品经营许可证"], requiredCode: "FRESH_VEG", i18n: { zh: "叶菜", en: "Leafy Greens" }, skuCount: 2 },
  { categoryNo: "CAT112", name: "根茎菜", parentNo: "CAT110", level: 3, template: "FRESH", qualifications: ["食品经营许可证"], requiredCode: "FRESH_VEG", i18n: { zh: "根茎菜" }, skuCount: 1 },
  { categoryNo: "CAT120", name: "水果", parentNo: "CAT100", level: 2, template: "FRESH", qualifications: [], i18n: { zh: "水果", en: "Fruits" }, skuCount: 0 },
  { categoryNo: "CAT121", name: "浆果", parentNo: "CAT120", level: 3, template: "FRESH", qualifications: ["食品经营许可证"], requiredCode: "FRESH_FRUIT", i18n: { zh: "浆果", en: "Berries" }, skuCount: 1 },
  { categoryNo: "CAT200", name: "日用百货", level: 1, template: "STANDARD", qualifications: [], i18n: { zh: "日用百货", en: "Household" }, skuCount: 0 },
  { categoryNo: "CAT210", name: "纸品清洁", parentNo: "CAT200", level: 2, template: "STANDARD", qualifications: [], i18n: { zh: "纸品清洁" }, skuCount: 1 },
  { categoryNo: "CAT300", name: "生活服务", level: 1, template: "SERVICE", qualifications: ["家电维修资质"], requiredCode: "SERVICE_REPAIR", i18n: { zh: "生活服务", en: "Services" }, skuCount: 1 },
  { categoryNo: "CAT400", name: "卡券", level: 1, template: "VOUCHER", qualifications: [], i18n: { zh: "卡券" }, skuCount: 0 },
];

export const skus: Sku[] = [
  {
    skuNo: "SKU1001", title: { zh: "本地小番茄 500g", en: "Cherry Tomato 500g" },
    merchantNo: "M903", merchantName: "邻家便利", categoryNo: "CAT111", categoryName: "叶菜",
    status: "ON_SALE", prices: { CN: 890, SG: 180 }, stock: 120,
    presaleQuota: 0, soldCount: 0, createdAt: "2026-07-20T02:00:00Z",
  },
  {
    // 待审 + 缺 SG 价：用来验 B6「每个市场都要有价格才能通过」
    skuNo: "SKU1002", title: { zh: "现摘菠菜 400g", en: "Spinach 400g" },
    merchantNo: "M901", merchantName: "阿姨家的菜摊", categoryNo: "CAT111", categoryName: "叶菜",
    status: "PENDING", prices: { CN: 520 }, stock: 60,
    presaleQuota: 200, soldCount: 0, cutoffAt: "2026-08-07T10:00:00Z", arriveAt: "2026-08-08T00:00:00Z",
    createdAt: "2026-08-05T06:00:00Z",
  },
  {
    // 待审 + 缺 en/ar 文案：验「zh 齐全即可，但缺译要看得见」
    skuNo: "SKU1003", title: { zh: "沙地红薯 2kg" },
    merchantNo: "M901", merchantName: "阿姨家的菜摊", categoryNo: "CAT112", categoryName: "根茎菜",
    status: "PENDING", prices: { CN: 1580, SG: 320 }, stock: 40,
    presaleQuota: 100, soldCount: 0, cutoffAt: "2026-08-07T10:00:00Z", arriveAt: "2026-08-08T00:00:00Z",
    createdAt: "2026-08-05T07:00:00Z",
  },
  {
    // 待审 + 商家未持有该类目资质（M906 只申请了 FOOD）：验类目资质校验
    skuNo: "SKU1004", title: { zh: "冰糖心苹果 5 斤", en: "Apple 2.5kg" },
    merchantNo: "M906", merchantName: "夜市烧烤", categoryNo: "CAT121", categoryName: "浆果",
    status: "PENDING", prices: { CN: 4580, SG: 920 }, stock: 30,
    presaleQuota: 0, soldCount: 0, createdAt: "2026-08-05T08:00:00Z",
  },
  {
    skuNo: "SKU1102", title: { zh: "抽纸 3 层 12 包", en: "Tissue 12 packs" },
    merchantNo: "M903", merchantName: "邻家便利", categoryNo: "CAT210", categoryName: "纸品清洁",
    status: "ON_SALE", prices: { CN: 2990, SG: 600 }, stock: 200,
    presaleQuota: 0, soldCount: 0, createdAt: "2026-07-01T02:00:00Z",
  },
  {
    // 预售超卖：已售 260 > 额度 200
    skuNo: "SKU2003", title: { zh: "阳光玫瑰 2 斤装", en: "Shine Muscat 1kg" },
    merchantNo: "M902", merchantName: "老张水果店", categoryNo: "CAT121", categoryName: "浆果",
    status: "ON_SALE", prices: { CN: 3980, SG: 800 }, stock: 0,
    presaleQuota: 200, soldCount: 260, cutoffAt: "2026-08-06T10:00:00Z", arriveAt: "2026-08-07T00:00:00Z",
    createdAt: "2026-08-01T02:00:00Z",
  },
  {
    skuNo: "SKU9001", title: { zh: "空调深度清洗（1 台）", en: "AC Deep Clean" },
    merchantNo: "M905", merchantName: "快修家电服务", categoryNo: "CAT300", categoryName: "生活服务",
    status: "ON_SALE", prices: { CN: 12800, SG: 2600 }, stock: 999,
    presaleQuota: 0, soldCount: 0, createdAt: "2026-06-10T02:00:00Z",
  },
  {
    skuNo: "SKU1005", title: { zh: "小油菜 500g（已驳回）", en: "Baby Bok Choy" },
    merchantNo: "M901", merchantName: "阿姨家的菜摊", categoryNo: "CAT111", categoryName: "叶菜",
    status: "REJECTED", prices: { CN: 480, SG: 100 }, stock: 0,
    presaleQuota: 0, soldCount: 0, createdAt: "2026-08-02T02:00:00Z",
    reason: "主图含其它平台水印，请换图后重新提交",
  },
];
