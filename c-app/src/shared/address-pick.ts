import { isCompleteRegion, joinRegion, splitRegion } from "@shared/utils/region";

/**
 * 地址链路上的**一次性交接**。两处，同一个形状：
 *
 * <ul>
 *   <li>{@link pickedAddress} 地址簿 → 结算页：<b>这一单送到哪儿</b>
 *   <li>{@link pickedPlace} 选点页 → 地址簿：<b>刚选中的那个地点</b>（带坐标）
 * </ul>
 *
 * <p><b>「这一单送哪儿」此前是借「改默认地址」传的</b>：地址簿在 `picking` 模式下
 * 点一条就 `setDefaultAddress`，结算页读默认。省了一个接口，代价是
 * <b>把一单的选择写成了长期偏好</b> —— 给父母寄一次东西，从此每一单都预填父母家。
 * 这与「生效位置 ≠ 默认地址」是同一类错误，只是错在另一对概念上。
 *
 * <p><b>为什么不是 pinia store</b>：这是交接，不是状态。放进 store 就多出
 * 「谁负责清掉它」这个问题，而清不干净的表现是下一次进页面莫名其妙跳到上次选的那条 ——
 * 一个没人能复现的「灵异」缺陷。读即清，从形状上就没有这个问题。
 */
function oneShot<T>() {
  let pending: T | null = null;
  return {
    /** 交出去，随后 `navigateBack`。 */
    offer(value: T): void {
      pending = value;
    },
    /**
     * 接收方取。**读一次就没了** —— 它只对这一次返回有效。
     *
     * <p>返回 null 是常态：用户点系统返回、没选就退出来，接收方照旧维持原样。
     */
    take(): T | null {
      const value = pending;
      pending = null;
      return value;
    },
  };
}

/** 地址簿 → 结算页：选中的那条地址的 id。 */
export const pickedAddress = oneShot<string>();

/**
 * 选点页 → 地址簿：一个**带坐标**的地点。
 *
 * <p>坐标是这条链路的全部意义所在：没有它，商家自送半径、按坐标算可见性、
 * 骑手导航三条链路在这条地址上一律求值为空，而界面上完全看不出区别。
 */
export interface PlacePick {
  kind: "place";
  /** 地点主体名（「阳光里小区」）或标准地址，落到 `detail` 的初值 */
  name: string;
  /** 省市区整串，落到 `region` */
  region: string;
  province: string;
  city: string;
  district: string;
  latE6: number;
  lngE6: number;
}

/**
 * 「都搜不到，我自己打」。**必须是一个显式的选择，不能是「什么都没交回来」** ——
 * 后者与「用户点了系统返回」分不开，而那两种情况该做的事正好相反：
 * 一个要打开空白表单，一个要什么都不做。
 */
export interface ManualPick {
  kind: "manual";
}

export type PickedPlace = PlacePick | ManualPick;

export const pickedPlace = oneShot<PickedPlace>();

/**
 * 三种来源（搜索命中 / 地图选点 / 附近社区）收敛成同一个形状。
 *
 * <p><b>省市区要拆开存</b>：`region` 是展示用的一串，而 `province/city/district`
 * 是**能拿来算的**那份 —— 按省算运费、按区派单都读那三列。
 * 高德与微信给回来的都是「省市区 + 路名门牌」一整串，不拆的话那三列永远是 null，
 * 于是那些规则全在 null 上求值、一条都不命中，而页面上完全正常。
 */
export function placeFrom(p: { name?: string; address?: string; lat: number; lng: number }): PlacePick {
  const raw = (p.address ?? "").slice(0, 96);
  const parts = splitRegion(raw);
  return {
    kind: "place",
    // 优先用 POI 名（「阳光里小区」比「浙江省杭州市…文一西路 100 号」更像用户会写的地址主体）
    name: (p.name || parts.rest || raw).trim().slice(0, 60),
    // 拆不出省市区的（只有门牌的写法）保持原样，别把一整串塞进 region 又清空三列
    region: isCompleteRegion(parts) ? joinRegion(parts) : raw,
    province: parts.province,
    city: parts.city,
    district: parts.district,
    latE6: Math.round(p.lat * 1e6),
    lngE6: Math.round(p.lng * 1e6),
  };
}
