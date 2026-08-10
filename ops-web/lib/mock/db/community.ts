// 社区与网点 mock（P-2.1 / P-2.2）。
// 自提点刻意覆盖 STORE/NEIGHBOR 两类与三种状态，且留了一个「30 天承接 4 次」的
// 临时点 —— 职业化风控（P-2.2.5）没有样本数据就永远验不到。
import type { Community, PickupPoint } from "@/lib/types";

export const communities: Community[] = [
  { communityNo: "C001", name: "锦绣花园", city: "杭州", grid: "西湖-北", opened: true, fenceRadius: 800, pickupCount: 3, createdAt: "2026-03-02T01:00:00Z" },
  { communityNo: "C002", name: "阳光里", city: "杭州", grid: "西湖-北", opened: true, fenceRadius: 600, pickupCount: 2, createdAt: "2026-03-18T01:00:00Z" },
  { communityNo: "C003", name: "梧桐苑", city: "杭州", grid: "拱墅-东", opened: true, fenceRadius: 1000, pickupCount: 2, createdAt: "2026-04-06T01:00:00Z" },
  { communityNo: "C004", name: "云栖里", city: "杭州", grid: "西湖-南", opened: false, fenceRadius: 700, pickupCount: 0, createdAt: "2026-07-20T01:00:00Z" },
];

export const pickups: PickupPoint[] = [
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
