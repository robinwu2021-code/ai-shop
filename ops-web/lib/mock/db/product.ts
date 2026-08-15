// 商品与类目 mock（P-3）。刻意覆盖：三级树、五种模板、缺市场价的待审商品、
// 缺资质的商家商品、超卖的预售商品 —— 每条规则都要有能验到它的样本。
import type { GoodsAudit, Category, Sku, SpecTemplate } from "@/lib/types";

// 一期收敛后的类目树（V22 / TDD-一期主数据收敛）。**与真库逐条对齐** ——
// 编号或资质文案与 V22 不一致的话，症状是「mock 上跑得通、连真库就找不到类目」，
// 而两边各自自洽，谁也不报错。
export const categories: Category[] = [
  { categoryNo: "CAT100", name: "食品生鲜", level: 1, template: "FRESH", qualifications: [], i18n: { zh: "食品生鲜", en: "Fresh Food" }, skuCount: 0 },
  { categoryNo: "CAT110", name: "蔬菜", parentNo: "CAT100", level: 2, template: "FRESH", qualifications: [], i18n: { zh: "蔬菜", en: "Vegetables" }, skuCount: 0 },
  { categoryNo: "CAT111", name: "叶菜", parentNo: "CAT110", level: 3, template: "FRESH", qualifications: ["营业执照（食用农产品）"], requiredCode: "FRESH_VEG", i18n: { zh: "叶菜", en: "Leafy Greens" }, skuCount: 2 },
  { categoryNo: "CAT112", name: "根茎菜", parentNo: "CAT110", level: 3, template: "FRESH", qualifications: ["营业执照（食用农产品）"], requiredCode: "FRESH_VEG", i18n: { zh: "根茎菜" }, skuCount: 1 },
  { categoryNo: "CAT120", name: "水果", parentNo: "CAT100", level: 2, template: "FRESH", qualifications: [], i18n: { zh: "水果", en: "Fruits" }, skuCount: 0 },
  { categoryNo: "CAT121", name: "浆果", parentNo: "CAT120", level: 3, template: "FRESH", qualifications: ["营业执照（食用农产品）"], requiredCode: "FRESH_FRUIT", i18n: { zh: "浆果", en: "Berries" }, skuCount: 1 },
  { categoryNo: "CAT122", name: "常温水果", parentNo: "CAT120", level: 3, template: "FRESH", qualifications: ["营业执照（食用农产品）"], requiredCode: "FRESH_FRUIT", i18n: { zh: "常温水果", en: "Fruits (Ambient)" }, skuCount: 0 },
  { categoryNo: "CAT130", name: "预包装食品", parentNo: "CAT100", level: 2, template: "STANDARD", qualifications: [], i18n: { zh: "预包装食品", en: "Packaged Food" }, skuCount: 0 },
  { categoryNo: "CAT131", name: "粮油调味", parentNo: "CAT130", level: 3, template: "STANDARD", qualifications: ["仅销售预包装食品备案"], requiredCode: "PACKAGED_FOOD", i18n: { zh: "粮油调味", en: "Grain & Oil" }, skuCount: 0 },
  { categoryNo: "CAT132", name: "休闲零食", parentNo: "CAT130", level: 3, template: "STANDARD", qualifications: ["仅销售预包装食品备案"], requiredCode: "PACKAGED_FOOD", i18n: { zh: "休闲零食", en: "Snacks" }, skuCount: 0 },
  { categoryNo: "CAT133", name: "茶叶", parentNo: "CAT130", level: 3, template: "STANDARD", qualifications: ["仅销售预包装食品备案"], requiredCode: "PACKAGED_FOOD", i18n: { zh: "茶叶", en: "Tea" }, skuCount: 0 },
  { categoryNo: "CAT200", name: "日用百货", level: 1, template: "STANDARD", qualifications: [], i18n: { zh: "日用百货", en: "Household" }, skuCount: 0 },
  { categoryNo: "CAT210", name: "纸品清洁", parentNo: "CAT200", level: 2, template: "STANDARD", qualifications: [], i18n: { zh: "纸品清洁" }, skuCount: 1 },
  { categoryNo: "CAT220", name: "家居用品", parentNo: "CAT200", level: 2, template: "STANDARD", qualifications: [], i18n: { zh: "家居用品", en: "Home" }, skuCount: 0 },
  { categoryNo: "CAT230", name: "个护化妆", parentNo: "CAT200", level: 2, template: "STANDARD", qualifications: [], i18n: { zh: "个护化妆", en: "Personal Care" }, skuCount: 0 },
  // 一级不挂 requiredCode（V22 修的 D2）：挂在这里会让家政被一张它不需要的维修资质卡住
  { categoryNo: "CAT300", name: "生活服务", level: 1, template: "SERVICE", qualifications: [], i18n: { zh: "生活服务", en: "Services" }, skuCount: 0 },
  { categoryNo: "CAT310", name: "家政保洁", parentNo: "CAT300", level: 2, template: "SERVICE", qualifications: [], requiredCode: "HOUSEKEEPING", i18n: { zh: "家政保洁", en: "Housekeeping" }, skuCount: 1 },
  // 一期停用：平台执照无预付卡相关项。留着不删 —— 切平台模式后在后台恢复即可
  { categoryNo: "CAT400", name: "卡券", level: 1, template: "VOUCHER", qualifications: [], i18n: { zh: "卡券" }, skuCount: 0, archivedAt: "2026-08-11T00:00:00Z" },
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
    // 一级类目上不该挂商品：V22 把这条改指到了二级的家政保洁，mock 跟着改
    merchantNo: "M905", merchantName: "快修家电服务", categoryNo: "CAT310", categoryName: "家政保洁",
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

/**
 * 平台规格模板（P-3.4 / E27）。样本刻意覆盖三种情形：
 * 按品类预置的（FRESH 的「重量」）、不限品类的（「颜色」）、已归档的（旧的一套重量档）——
 * 少了最后一条，「归档 / 恢复」那段界面在开发期永远走不到。
 *
 * ⚠️ `code` 每条都有值，且**跨模板不复用**：B-4.5 的整条理由都在这上面 ——
 * 三家店各写「5 斤」「五斤」「2.5kg」，只有 code 能把它们认成同一个规格。
 */
export const specTemplates: SpecTemplate[] = [
  {
    templateNo: "SPT901", scope: "PLATFORM", categoryType: "FRESH", name: "重量",
    options: [
      { code: "W_500G", label: "500g" },
      { code: "W_1KG", label: "1kg" },
      { code: "W_2500G", label: "2.5kg（5 斤）" },
    ],
    createdAt: "2026-07-01T02:00:00Z",
  },
  {
    templateNo: "SPT902", scope: "PLATFORM", categoryType: "FRESH", name: "分拣等级",
    options: [
      { code: "GRADE_A", label: "特级" },
      { code: "GRADE_B", label: "一级" },
    ],
    createdAt: "2026-07-03T02:00:00Z",
  },
  {
    // 不限品类：日用、卡券都用得上，categoryType 留空
    templateNo: "SPT903", scope: "PLATFORM", name: "颜色",
    options: [
      { code: "COLOR_RED", label: "红" },
      { code: "COLOR_BLUE", label: "蓝" },
      { code: "COLOR_BLACK", label: "黑" },
    ],
    createdAt: "2026-07-05T02:00:00Z",
  },
  {
    // 已归档：商家侧不再下发，但历史商品还靠它解释 optionCode
    templateNo: "SPT904", scope: "PLATFORM", categoryType: "STANDARD", name: "旧规格档（已停用）",
    options: [{ code: "LEGACY_S", label: "小份" }, { code: "LEGACY_L", label: "大份" }],
    createdAt: "2026-06-01T02:00:00Z",
    archivedAt: "2026-08-01T02:00:00Z",
  },
];

/** 待审商品（mock）。**留一条已驳回的** —— 否则「拒因回显」那段界面走不到 */
export const goodsAudits: GoodsAudit[] = [
  { goodsNo: "G9001", title: "现磨豆浆 500ml", subtitle: "当天现磨", type: "FRESH", merchant: { merchantNo: "M901", name: "阿姨家的菜摊" }, status: "PENDING" },
  { goodsNo: "G9002", title: "手工辣椒酱", subtitle: "小罐装", type: "NORMAL", merchant: { merchantNo: "M902", name: "老张水果店" }, status: "PENDING" },
  { goodsNo: "G9003", title: "代客充值", type: "VIRTUAL", merchant: { merchantNo: "M903", name: "邻家便利" }, status: "REJECTED" },
];
