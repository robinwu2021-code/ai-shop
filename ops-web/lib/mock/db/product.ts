// 商品与类目 mock（P-3）。刻意覆盖：三级树、五种模板、缺市场价的待审商品、
// 缺资质的商家商品、超卖的预售商品 —— 每条规则都要有能验到它的样本。
import type { GoodsAudit, Category, Sku, SpecTemplate, SpuStd, Topic } from "@/lib/types";

// 一期收敛后的类目树（V22 / TDD-一期主数据收敛）。**与真库逐条对齐** ——
// 编号或资质文案与 V22 不一致的话，症状是「mock 上跑得通、连真库就找不到类目」，
// 而两边各自自洽，谁也不报错。
export const categories: Category[] = [
  // 与迁移逐条对齐（V22 → V190）。**两级封顶**，编号或门槛码与真库不一致的话，
  // 症状是「mock 上跑得通、连真库就找不到类目」，而两边各自自洽，谁也不报错。
  // 默认停用的那几条（服饰鞋帽整棵、三个服务）在这里也是 archivedAt —— 运营一键开。
  { categoryNo: "CAT100", name: "食品生鲜", level: 1, template: "FRESH", qualifications: [], i18n: { zh: "食品生鲜", en: "Fresh Food" }, skuCount: 0 },
  { categoryNo: "CAT110", name: "蔬菜", parentNo: "CAT100", level: 2, template: "FRESH", qualifications: ["营业执照（食用农产品）"], requiredCode: "FRESH_VEG", i18n: { zh: "蔬菜", en: "Vegetables" }, skuCount: 3 },
  { categoryNo: "CAT120", name: "水果", parentNo: "CAT100", level: 2, template: "FRESH", qualifications: ["营业执照（食用农产品）"], requiredCode: "FRESH_FRUIT", i18n: { zh: "水果", en: "Fruits" }, skuCount: 1 },
  { categoryNo: "CAT130", name: "预包装食品", parentNo: "CAT100", level: 2, template: "STANDARD", qualifications: ["仅销售预包装食品备案"], requiredCode: "PACKAGED_FOOD", i18n: { zh: "预包装食品", en: "Packaged Food" }, skuCount: 0 },
  { categoryNo: "CAT140", name: "熟食卤味", parentNo: "CAT100", level: 2, template: "STANDARD", qualifications: ["食品经营许可证"], requiredCode: "FOOD", i18n: { zh: "熟食卤味", en: "Deli" }, skuCount: 0 },
  { categoryNo: "CAT150", name: "酒类", parentNo: "CAT100", level: 2, template: "STANDARD", qualifications: ["食品经营许可证（含酒类）"], requiredCode: "ALCOHOL", i18n: { zh: "酒类", en: "Alcohol" }, skuCount: 0 },
  { categoryNo: "CAT160", name: "茶叶", parentNo: "CAT100", level: 2, template: "STANDARD", qualifications: ["仅销售预包装食品备案"], requiredCode: "PACKAGED_FOOD", i18n: { zh: "茶叶", en: "Tea" }, skuCount: 0 },
  { categoryNo: "CAT170", name: "肉禽蛋", parentNo: "CAT100", level: 2, template: "FRESH", qualifications: ["食品经营许可证"], requiredCode: "FRESH_MEAT", i18n: { zh: "肉禽蛋", en: "Meat & Eggs" }, skuCount: 0 },
  { categoryNo: "CAT180", name: "乳制品", parentNo: "CAT100", level: 2, template: "FRESH", qualifications: ["食品经营许可证"], requiredCode: "FRESH_DAIRY", i18n: { zh: "乳制品", en: "Dairy" }, skuCount: 0 },
  { categoryNo: "CAT190", name: "水产海鲜", parentNo: "CAT100", level: 2, template: "FRESH", qualifications: ["食品经营许可证"], requiredCode: "FRESH_AQUATIC", i18n: { zh: "水产海鲜", en: "Seafood" }, skuCount: 0 },
  { categoryNo: "CAT200", name: "日用百货", level: 1, template: "STANDARD", qualifications: [], i18n: { zh: "日用百货", en: "Household" }, skuCount: 0 },
  { categoryNo: "CAT210", name: "纸品清洁", parentNo: "CAT200", level: 2, template: "STANDARD", qualifications: [], i18n: { zh: "纸品清洁", en: "Paper & Cleaning" }, skuCount: 1 },
  { categoryNo: "CAT220", name: "家居用品", parentNo: "CAT200", level: 2, template: "STANDARD", qualifications: [], i18n: { zh: "家居用品", en: "Home" }, skuCount: 0 },
  { categoryNo: "CAT230", name: "个护化妆", parentNo: "CAT200", level: 2, template: "STANDARD", qualifications: [], i18n: { zh: "个护化妆", en: "Personal Care" }, skuCount: 0 },
  { categoryNo: "CAT250", name: "母婴用品", parentNo: "CAT200", level: 2, template: "STANDARD", qualifications: [], i18n: { zh: "母婴用品", en: "Baby Care" }, skuCount: 0 },
  { categoryNo: "CAT260", name: "宠物用品", parentNo: "CAT200", level: 2, template: "STANDARD", qualifications: [], i18n: { zh: "宠物用品", en: "Pet Supplies" }, skuCount: 0 },
  { categoryNo: "CAT270", name: "宠物食品", parentNo: "CAT200", level: 2, template: "STANDARD", qualifications: ["饲料和饲料添加剂经营备案"], requiredCode: "PET_FOOD", i18n: { zh: "宠物食品", en: "Pet Food" }, skuCount: 0 },
  { categoryNo: "CAT280", name: "文具玩具", parentNo: "CAT200", level: 2, template: "STANDARD", qualifications: [], i18n: { zh: "文具玩具", en: "Stationery & Toys" }, skuCount: 0 },
  { categoryNo: "CAT290", name: "厨房用具", parentNo: "CAT200", level: 2, template: "STANDARD", qualifications: [], i18n: { zh: "厨房用具", en: "Kitchenware" }, skuCount: 0 },
  { categoryNo: "CAT300", name: "生活服务", level: 1, template: "SERVICE", qualifications: [], i18n: { zh: "生活服务", en: "Services" }, skuCount: 0 },
  { categoryNo: "CAT310", name: "家政保洁", parentNo: "CAT300", level: 2, template: "SERVICE", qualifications: [], requiredCode: "HOUSEKEEPING", i18n: { zh: "家政保洁", en: "Housekeeping" }, skuCount: 1 },
  { categoryNo: "CAT330", name: "洗衣洗鞋", parentNo: "CAT300", level: 2, template: "SERVICE", qualifications: [], i18n: { zh: "洗衣洗鞋", en: "Laundry" }, skuCount: 0 },
  { categoryNo: "CAT340", name: "美容美发", parentNo: "CAT300", level: 2, template: "SERVICE", qualifications: [], i18n: { zh: "美容美发", en: "Beauty & Hair" }, skuCount: 0 },
  { categoryNo: "CAT350", name: "宠物洗护", parentNo: "CAT300", level: 2, template: "SERVICE", qualifications: [], i18n: { zh: "宠物洗护", en: "Pet Grooming" }, skuCount: 0 },
  { categoryNo: "CAT360", name: "跑腿代办", parentNo: "CAT300", level: 2, template: "SERVICE", qualifications: [], i18n: { zh: "跑腿代办", en: "Errands" }, skuCount: 0 },
  { categoryNo: "CAT370", name: "洗车养护", parentNo: "CAT300", level: 2, template: "SERVICE", qualifications: [], i18n: { zh: "洗车养护", en: "Car Wash" }, skuCount: 0, archivedAt: "2026-08-22T00:00:00Z" },
  { categoryNo: "CAT380", name: "开锁换锁", parentNo: "CAT300", level: 2, template: "SERVICE", qualifications: [], i18n: { zh: "开锁换锁", en: "Locksmith" }, skuCount: 0, archivedAt: "2026-08-22T00:00:00Z" },
  { categoryNo: "CAT390", name: "家电清洗", parentNo: "CAT300", level: 2, template: "SERVICE", qualifications: [], i18n: { zh: "家电清洗", en: "Appliance Clean" }, skuCount: 0, archivedAt: "2026-08-22T00:00:00Z" },
  { categoryNo: "CAT600", name: "电子产品", level: 1, template: "STANDARD", qualifications: [], i18n: { zh: "电子产品", en: "Electronics" }, skuCount: 0 },
  { categoryNo: "CAT610", name: "手机数码", parentNo: "CAT600", level: 2, template: "STANDARD", qualifications: [], i18n: { zh: "手机数码", en: "Phones & Digital" }, skuCount: 0 },
  { categoryNo: "CAT620", name: "家用电器", parentNo: "CAT600", level: 2, template: "STANDARD", qualifications: [], i18n: { zh: "家用电器", en: "Home Appliances" }, skuCount: 0 },
  { categoryNo: "CAT630", name: "配件耗材", parentNo: "CAT600", level: 2, template: "STANDARD", qualifications: [], i18n: { zh: "配件耗材", en: "Accessories" }, skuCount: 0 },
  { categoryNo: "CAT700", name: "食品饮料", level: 1, template: "STANDARD", qualifications: [], i18n: { zh: "食品饮料", en: "Food & Drinks" }, skuCount: 0 },
  { categoryNo: "CAT710", name: "粮油调味", parentNo: "CAT700", level: 2, template: "STANDARD", qualifications: ["仅销售预包装食品备案"], requiredCode: "PACKAGED_FOOD", i18n: { zh: "粮油调味", en: "Grain & Seasoning" }, skuCount: 0 },
  { categoryNo: "CAT720", name: "休闲零食", parentNo: "CAT700", level: 2, template: "STANDARD", qualifications: ["仅销售预包装食品备案"], requiredCode: "PACKAGED_FOOD", i18n: { zh: "休闲零食", en: "Snacks" }, skuCount: 0 },
  { categoryNo: "CAT730", name: "饮料冲调", parentNo: "CAT700", level: 2, template: "STANDARD", qualifications: ["仅销售预包装食品备案"], requiredCode: "PACKAGED_FOOD", i18n: { zh: "饮料冲调", en: "Drinks" }, skuCount: 0 },
  { categoryNo: "CAT740", name: "烘焙面点", parentNo: "CAT700", level: 2, template: "STANDARD", qualifications: ["食品经营许可证"], requiredCode: "FOOD", i18n: { zh: "烘焙面点", en: "Bakery" }, skuCount: 0 },
  { categoryNo: "CAT750", name: "婴幼儿食品", parentNo: "CAT700", level: 2, template: "STANDARD", qualifications: ["婴幼儿配方乳粉销售备案"], requiredCode: "INFANT_FORMULA", i18n: { zh: "婴幼儿食品", en: "Baby Food" }, skuCount: 0 },
  { categoryNo: "CAT800", name: "鲜花绿植", level: 1, template: "STANDARD", qualifications: [], i18n: { zh: "鲜花绿植", en: "Flowers & Plants" }, skuCount: 0 },
  { categoryNo: "CAT810", name: "鲜花", parentNo: "CAT800", level: 2, template: "STANDARD", qualifications: [], i18n: { zh: "鲜花", en: "Fresh Flowers" }, skuCount: 0 },
  { categoryNo: "CAT820", name: "绿植盆栽", parentNo: "CAT800", level: 2, template: "STANDARD", qualifications: [], i18n: { zh: "绿植盆栽", en: "Potted Plants" }, skuCount: 0 },
  // 备着、默认停用：退换率高、尺码复杂，与「楼下拿了就走」的心智不合
  { categoryNo: "CAT900", name: "服饰鞋帽", level: 1, template: "STANDARD", qualifications: [], i18n: { zh: "服饰鞋帽", en: "Apparel" }, skuCount: 0, archivedAt: "2026-08-22T00:00:00Z" },
  { categoryNo: "CAT910", name: "内衣袜子", parentNo: "CAT900", level: 2, template: "STANDARD", qualifications: [], i18n: { zh: "内衣袜子", en: "Underwear" }, skuCount: 0, archivedAt: "2026-08-22T00:00:00Z" },
  { categoryNo: "CAT920", name: "鞋类拖鞋", parentNo: "CAT900", level: 2, template: "STANDARD", qualifications: [], i18n: { zh: "鞋类拖鞋", en: "Shoes" }, skuCount: 0, archivedAt: "2026-08-22T00:00:00Z" },
  { categoryNo: "CAT930", name: "家纺床品", parentNo: "CAT900", level: 2, template: "STANDARD", qualifications: [], i18n: { zh: "家纺床品", en: "Home Textile" }, skuCount: 0, archivedAt: "2026-08-22T00:00:00Z" },
  // 卡券与虚拟商品：有类目、没链路（后端无 CARD/VIRTUAL 分支），V176 起停用
  { categoryNo: "CAT400", name: "卡券", level: 1, template: "VOUCHER", qualifications: [], i18n: { zh: "卡券", en: "Vouchers" }, skuCount: 0, archivedAt: "2026-08-22T00:00:00Z" },
  { categoryNo: "CAT500", name: "虚拟商品", level: 1, template: "VIRTUAL", qualifications: [], i18n: { zh: "虚拟商品", en: "Virtual" }, skuCount: 0, archivedAt: "2026-08-22T00:00:00Z" },
];

export const skus: Sku[] = [
  {
    skuNo: "SKU1001", title: { zh: "本地小番茄 500g", en: "Cherry Tomato 500g" },
    merchantNo: "M903", merchantName: "邻家便利", categoryNo: "CAT110", categoryName: "蔬菜",
    status: "ON_SALE", prices: { CN: 890, SG: 180 }, stock: 120,
    presaleQuota: 0, soldCount: 0, createdAt: "2026-07-20T02:00:00Z",
  },
  {
    // 待审 + 缺 SG 价：用来验 B6「每个市场都要有价格才能通过」
    skuNo: "SKU1002", title: { zh: "现摘菠菜 400g", en: "Spinach 400g" },
    merchantNo: "M901", merchantName: "阿姨家的菜摊", categoryNo: "CAT110", categoryName: "蔬菜",
    status: "PENDING", prices: { CN: 520 }, stock: 60,
    presaleQuota: 200, soldCount: 0, cutoffAt: "2026-08-07T10:00:00Z", arriveAt: "2026-08-08T00:00:00Z",
    createdAt: "2026-08-05T06:00:00Z",
  },
  {
    // 待审 + 缺 en/ar 文案：验「zh 齐全即可，但缺译要看得见」
    skuNo: "SKU1003", title: { zh: "沙地红薯 2kg" },
    merchantNo: "M901", merchantName: "阿姨家的菜摊", categoryNo: "CAT110", categoryName: "蔬菜",
    status: "PENDING", prices: { CN: 1580, SG: 320 }, stock: 40,
    presaleQuota: 100, soldCount: 0, cutoffAt: "2026-08-07T10:00:00Z", arriveAt: "2026-08-08T00:00:00Z",
    createdAt: "2026-08-05T07:00:00Z",
  },
  {
    // 待审 + 商家未持有该类目资质（M906 只申请了 FOOD）：验类目资质校验
    skuNo: "SKU1004", title: { zh: "冰糖心苹果 5 斤", en: "Apple 2.5kg" },
    merchantNo: "M906", merchantName: "夜市烧烤", categoryNo: "CAT120", categoryName: "水果",
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
    merchantNo: "M902", merchantName: "老张水果店", categoryNo: "CAT120", categoryName: "水果",
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
    merchantNo: "M901", merchantName: "阿姨家的菜摊", categoryNo: "CAT110", categoryName: "蔬菜",
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

/**
 * 平台标准品（TDD-标准品库）。**编号与真库 V166 的种子逐条对齐** ——
 * 不对齐的症状是「mock 上跑得通、连真库就找不到标准品」，而两边各自自洽，谁也不报错。
 *
 * <p>规格里的 `optionCodes` 一个都不能少：没有 code 的标准品与商家手输没有区别，
 * 它唯一的作用是让人**以为**规格统一了。
 */
/**
 * 主题分类（陈列，批 E）。**两条常设的**（没有档期）——
 * 空表的后果不是「少点数据」，是运营端页面看不出是没配还是坏了。
 */
export const topics: Topic[] = [
  { topicNo: "TP0001", title: "早餐必备", subtitle: "7 点前送到楼下", sort: 10, status: "ACTIVE", goodsCount: 0 },
  { topicNo: "TP0002", title: "本地时令", subtitle: "当季当地，今天到货", sort: 20, status: "ACTIVE", goodsCount: 0 },
];

/** 专题 → 商品编号，顺序即展示顺序。与真库的 `prd_topic_goods` 同一份语义 */
export const topicGoods: Record<string, string[]> = {
  TP0001: [],
  TP0002: [],
};

export const spuStds: SpuStd[] = [
  { stdNo: "STD1001", categoryNo: "CAT110", categoryName: "蔬菜", title: "本地菠菜", subtitle: "当季叶菜", keywords: "菠菜 波斯菜 叶菜", status: "ACTIVE", refCount: 3,
    specGroups: [{ name: "重量", options: ["500g", "1斤", "2斤"], optionCodes: ["W500G", "W1JIN", "W2JIN"] }] },
  { stdNo: "STD1011", categoryNo: "CAT110", categoryName: "蔬菜", title: "土豆", subtitle: "根茎菜", keywords: "土豆 马铃薯 洋芋", status: "ACTIVE", refCount: 5,
    specGroups: [{ name: "重量", options: ["1斤", "2斤", "5斤"], optionCodes: ["W1JIN", "W2JIN", "W5JIN"] }] },
  { stdNo: "STD2001", categoryNo: "CAT210", categoryName: "纸品清洁", title: "抽纸", subtitle: "家用抽取式面巾纸", keywords: "抽纸 面巾纸 纸巾", status: "ACTIVE", refCount: 2,
    specGroups: [{ name: "规格", options: ["3包", "6包", "12包"], optionCodes: ["B3", "B6", "B12"] }] },
  { stdNo: "STD2004", categoryNo: "CAT210", categoryName: "纸品清洁", title: "洗衣液", subtitle: "衣物清洁", keywords: "洗衣液 洗涤剂", status: "ACTIVE", refCount: 0,
    specGroups: [{ name: "规格", options: ["1L", "2L", "3L"], optionCodes: ["V1L", "V2L", "V3L"] }] },
];
