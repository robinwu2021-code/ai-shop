// 买家 / 供应商两面。**同一个账号**，只是看的东西不同：买家看询价，供应商看求购与库存。
//
// 记住上次停在哪一面：供应商进来多半是回工作台处理求购，每次都先落到买家首页再点一下是白费；
// 只存在这台手机上（它是一个顺手的偏好，不是数据）。
import { ROUTES } from "./routes";

export type ElecRole = "buyer" | "supplier";

const KEY = "she_elec_role";

export function lastRole(): ElecRole {
  try {
    return uni.getStorageSync(KEY) === "supplier" ? "supplier" : "buyer";
  } catch {
    return "buyer";
  }
}

export function rememberRole(r: ElecRole): void {
  try {
    uni.setStorageSync(KEY, r);
  } catch {
    // 存不上就每次从买家首页进
  }
}

/**
 * 切到另一面。目标是**左上角的返回永远回到进来之前那一页**（并进虹选时那是「我的」）：
 *   - 去供应商：从买家首页压栈打开工作台
 *   - 回买家：栈里有首页就退回去；没有（上次停在供应商那面、入口直接落到了工作台）才原地换成首页
 * 于是两面来回切，栈最多比进来时深一层，不会越叠越深。
 */
export function switchRole(to: ElecRole, isSupplier: boolean): void {
  if (to === "buyer") {
    rememberRole("buyer");
    const pages = getCurrentPages() as { route?: string }[];
    const at = pages.map((p) => `/${p.route ?? ""}`).lastIndexOf(ROUTES.home);
    if (at >= 0) uni.navigateBack({ delta: pages.length - 1 - at });
    else uni.redirectTo({ url: ROUTES.home });
    return;
  }
  // 还不是供应商：去「成为供应商」，成了之后那一页自己换成工作台
  if (!isSupplier) {
    uni.navigateTo({ url: ROUTES.supplierJoin });
    return;
  }
  rememberRole("supplier");
  uni.navigateTo({ url: ROUTES.supplier });
}
