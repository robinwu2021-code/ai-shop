// 社区与网点 mock（P-2.1 / P-2.2）。
// 自提点刻意覆盖 STORE/NEIGHBOR 两类与三种状态，且留了一个「30 天承接 4 次」的
// 临时点 —— 职业化风控（P-2.2.5）没有样本数据就永远验不到。
import type { Community, CommunityApply, PickupPoint, Region } from "@/lib/types";

/**
 * 商家提报的新社区（ADR-013 阶段三）。
 *
 * 三条样本对应运营真的会遇到的三种判断：**平台确实没有**（该批）、
 * **已有社区的另一个叫法**（该驳，而这正是地址字段存在的理由）、
 * 以及一条已经批过的（通过之后长什么样，不留样本就永远没人验）。
 */
export const communityApplies: CommunityApply[] = [
  { applyNo: "CA9001", merchantNo: "M901", merchantName: "阿姨家的菜摊", name: "钱塘湾花苑", address: "杭州市西湖区文一西路 1218 号", regionCode: "330106003", regionPath: "浙江省 / 杭州市 / 西湖区 / 西溪街道", note: "我的摊子就在小区南门口，这边住户一直问能不能送", status: "PENDING", submittedAt: 1786100000000, kind: "ESTATE", located: true, latE6: 30279000, lngE6: 120131000 },
  // 词典关联的村，**故意没带定位**：演「通过前要先补坐标」那个警示 ——
  // 没坐标的聚落买家用定位永远找不到，这个状态藏起来它就永远不会被处理
  { applyNo: "CA9002", merchantNo: "M903", merchantName: "邻家便利", name: "米市新村", address: "杭州市拱墅区米市巷街道", regionCode: "330105001", regionPath: "浙江省 / 杭州市 / 拱墅区 / 米市巷街道", note: "", status: "PENDING", submittedAt: 1786050000000, kind: "VILLAGE", originCode: "330105001219", located: false, fallbackLatE6: 30316000, fallbackLngE6: 120152000 },
  { applyNo: "CA9003", merchantNo: "M902", merchantName: "老张水果店", name: "翡翠城", address: "杭州市拱墅区莫干山路 300 号", status: "APPROVED", communityNo: "C005", submittedAt: 1785900000000 },
];

export const communities: Community[] = [
  // **有意留两条未归属的**（C003/C004）：那正是运营要去补的情形，
  // 全都填好的假数据会把「未归属」这个状态藏起来，而它是 ADR-013 里
  // 最容易被忽略的一格 —— 没挂区划的社区，按区覆盖时谁也命中不了它
  { communityNo: "C001", name: "锦绣花园", city: "杭州", grid: "西湖-北", opened: true, fenceRadius: 800, pickupCount: 3, createdAt: "2026-03-02T01:00:00Z", regionCode: "330106002", regionPath: "浙江省 / 杭州市 / 西湖区 / 北山街道" },
  { communityNo: "C002", name: "阳光里", city: "杭州", grid: "西湖-北", opened: true, fenceRadius: 600, pickupCount: 2, createdAt: "2026-03-18T01:00:00Z", regionCode: "330106003", regionPath: "浙江省 / 杭州市 / 西湖区 / 西溪街道" },
  { communityNo: "C003", name: "梧桐苑", city: "杭州", grid: "拱墅-东", opened: true, fenceRadius: 1000, pickupCount: 2, createdAt: "2026-04-06T01:00:00Z" },
  { communityNo: "C004", name: "云栖里", city: "杭州", grid: "西湖-南", opened: false, fenceRadius: 700, pickupCount: 0, createdAt: "2026-07-20T01:00:00Z" },
];

/**
 * 行政区划（mock）。真库有 44703 行（V31 灌入），这里只留**一条完整链路**：
 * 浙江省 → 杭州市 → 西湖区/拱墅区 → 几个街道。
 *
 * 够用的判据是「能不能把逐级选择走完」——四级都在、且叶子层的 hasChild 是 false，
 * 就能验到「选到街道为止，不该再让人往下点」。堆更多省市只是让 mock 更像真的，
 * 验不到任何新东西。
 */
export const regions: Region[] = [
  { regionCode: "33", level: "PROVINCE", name: "浙江省", enabled: true, hasChild: true },
  { regionCode: "3301", parentCode: "33", level: "CITY", name: "杭州市", enabled: true, hasChild: true },
  { regionCode: "330106", parentCode: "3301", level: "DISTRICT", name: "西湖区", enabled: true, hasChild: true },
  { regionCode: "330105", parentCode: "3301", level: "DISTRICT", name: "拱墅区", enabled: true, hasChild: true },
  { regionCode: "330106002", parentCode: "330106", level: "STREET", name: "北山街道", enabled: true, hasChild: false },
  { regionCode: "330106003", parentCode: "330106", level: "STREET", name: "西溪街道", enabled: true, hasChild: false },
  { regionCode: "330106004", parentCode: "330106", level: "STREET", name: "翠苑街道", enabled: true, hasChild: false },
  { regionCode: "330105001", parentCode: "330105", level: "STREET", name: "米市巷街道", enabled: true, hasChild: false },
  // 停用一条：运营端要看得见它才能开回来 —— 与行业、授权码同一条规矩
  { regionCode: "330105002", parentCode: "330105", level: "STREET", name: "湖墅街道", enabled: false, hasChild: false },
];



export const pickups: PickupPoint[] = [
  {
    // 商家自建（P1）：先审后用。有坐标——审核面上要看坐标有没有
    pickupNo: "P009", name: "阳光里东门驿站", type: "STORE", status: "PENDING",
    communityNo: "C002", communityName: "阳光里", storeNo: "ST-M902", merchantName: "老张水果店",
    address: "阳光里东门 2 号商铺", openHours: "08:00-20:00", arriveTime: "",
    serviceFeeRate: 0, feeMode: "NONE", serviceFeePerItemMinor: 0, acceptCount30d: 0, createdAt: "2026-08-22T06:00:00Z",
    latE6: 30_180_000, lngE6: 120_090_000,
  },
  {
    pickupNo: "P001", name: "邻家便利·锦绣店", type: "STORE", status: "ACTIVE",
    communityNo: "C001", communityName: "锦绣花园", storeNo: "ST-M903", merchantName: "邻家便利",
    address: "锦绣花园 3 幢商铺 102", openHours: "07:30-22:00", arriveTime: "16:00",
    serviceFeeRate: 150, feeMode: "NONE", serviceFeePerItemMinor: 0, acceptCount30d: 86, createdAt: "2026-03-05T02:00:00Z",
  },
  {
    pickupNo: "P002", name: "老张水果店", type: "STORE", status: "ACTIVE",
    communityNo: "C002", communityName: "阳光里", storeNo: "ST-M902", merchantName: "老张水果店",
    address: "阳光里 1 幢 105", openHours: "08:00-21:00", arriveTime: "15:30",
    serviceFeeRate: 120, feeMode: "NONE", serviceFeePerItemMinor: 0, acceptCount30d: 64, createdAt: "2026-04-01T02:00:00Z",
  },
  {
    pickupNo: "P003", name: "阿姨家的菜摊", type: "STORE", status: "SUSPENDED",
    communityNo: "C001", communityName: "锦绣花园", storeNo: "ST-M901", merchantName: "阿姨家的菜摊",
    address: "锦绣花园东门菜市 A12", openHours: "06:00-12:00", arriveTime: "05:30",
    serviceFeeRate: 100, feeMode: "NONE", serviceFeePerItemMinor: 0, acceptCount30d: 12, createdAt: "2026-05-11T02:00:00Z",
  },
  {
    pickupNo: "P004", name: "梧桐苑 12-3 王女士家", type: "NEIGHBOR", status: "ACTIVE",
    communityNo: "C003", communityName: "梧桐苑",
    address: "梧桐苑 12 幢 3 单元（付款后可见门牌）", openHours: "18:00-21:00", arriveTime: "17:30",
    serviceFeeRate: 0, feeMode: "NONE", serviceFeePerItemMinor: 0, acceptCount30d: 4, createdAt: "2026-07-02T02:00:00Z",
  },
  {
    pickupNo: "P005", name: "梧桐苑 5-1 李先生家", type: "NEIGHBOR", status: "ACTIVE",
    communityNo: "C003", communityName: "梧桐苑",
    address: "梧桐苑 5 幢 1 单元（付款后可见门牌）", openHours: "19:00-21:00", arriveTime: "18:30",
    serviceFeeRate: 0, feeMode: "NONE", serviceFeePerItemMinor: 0, acceptCount30d: 1, createdAt: "2026-07-26T02:00:00Z",
  },
  {
    pickupNo: "P006", name: "阳光里便民驿站（迁移中）", type: "STORE", status: "MIGRATING",
    communityNo: "C002", communityName: "阳光里", storeNo: "ST-M903", merchantName: "邻家便利",
    address: "阳光里 6 幢 101（原址拆迁）", openHours: "09:00-20:00", arriveTime: "16:30",
    serviceFeeRate: 130, feeMode: "NONE", serviceFeePerItemMinor: 0, acceptCount30d: 21, createdAt: "2026-02-14T02:00:00Z",
  },
];
