// 底部菜单的四个落点：找料 · 询价 · 供货 · 我的。
//
// **买家与供应商分开**：询价是买家的事、供货是供应商的事，各占一格；同一个账号两样都能做。
// 此前是买家首页与供应商工作台顶上一条「我要买 / 我是供应商」切换条 —— 两面挤在一处，
// 又没有放账号与历史记录的地方。
//
// **不用原生 tabBar**：测试期元器件并进虹选好店当分包，分包页当不了 tab 页；
// 组件库的 sh-tabbar 走 switchTab 与宿主的菜单配置，同样用不了。
// 于是自己画一条，切换用 redirectTo（原地换页）：
//   - 左上角的返回永远回到进来之前那一页（并进虹选时是虹选的「我的」）
//   - 来回切多少次，栈都不会变深
import type { IconName } from "@shared/design/icons";
import { ROUTES } from "./routes";

export type ElecTab = "find" | "rfq" | "supply" | "me";

export interface ElecTabDef {
  key: ElecTab;
  label: string;
  icon: IconName;
  iconOn: IconName;
  route: string;
}

export const ELEC_TABS: readonly ElecTabDef[] = [
  { key: "find", label: "找料", icon: "search", iconOn: "search", route: ROUTES.home },
  { key: "rfq", label: "询价", icon: "grid", iconOn: "gridFilled", route: ROUTES.rfqs },
  { key: "supply", label: "供货", icon: "store", iconOn: "storeFilled", route: ROUTES.supplier },
  { key: "me", label: "我的", icon: "user", iconOn: "userFilled", route: ROUTES.me },
];

const KEY = "she_elec_tab";

/** 上次停在哪一格。只用来判断「供应商进来直接落到供货」—— 一个顺手的偏好，不是数据 */
export function lastTab(): ElecTab {
  try {
    const v = uni.getStorageSync(KEY) as unknown;
    return ELEC_TABS.some((t) => t.key === v) ? (v as ElecTab) : "find";
  } catch {
    return "find";
  }
}

export function rememberTab(t: ElecTab): void {
  try {
    uni.setStorageSync(KEY, t);
  } catch {
    // 存不上就每次从找料进
  }
}

export function switchTab(to: ElecTab): void {
  const def = ELEC_TABS.find((t) => t.key === to);
  if (!def) return;
  rememberTab(to);
  uni.redirectTo({ url: def.route });
}
