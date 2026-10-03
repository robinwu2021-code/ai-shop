// 拨一个品类「记不记库存」的整条交互（原型 inv-managed-switch s03 / s04）。
//
// 库存设置页与经营类目页各有一个开关，走的是同一套三道判：
//   BLOCKED       有在途单据 → 列出单据、什么都没改
//   NEEDS_CONFIRM 还有库存   → 列出实存，确认键写「不记库存」，确认后带 confirm 再来一次
//   DONE          直接改好
// 两页各写一份的话，迟早一处会漏掉「确认之后又冒出在途单据」那一支。
import { api } from "@/api";
import { confirm } from "@ai-shop/ui/prompt";
import { describeBlockers, describeStocked } from "@/shared/inv-mode";
import type { InvModeChange } from "@shared/types";

type T = (key: string, params?: Record<string, unknown>) => unknown;

/** @return 改成了没有。被拦、取消、失败都是 `false` —— 调用方据此决定要不要重取 */
export async function toggleInvCategory(
  t: T,
  row: { categoryNo: string; name: string; managed: boolean },
): Promise<boolean> {
  const managed = !row.managed;
  try {
    let r = await api.mInvSetCategory(row.categoryNo, { managed });
    if (r.status === "NEEDS_CONFIRM") {
      const ok = await confirm({
        title: String(t("stockSettings.confirmTitle", { name: row.name })),
        hint: String(t("stockSettings.confirmStocked", { n: r.goods.length, list: describeStocked(t, r.goods) })),
        confirmText: String(t("stockSettings.confirmOk")),
      });
      if (!ok) return false;
      r = await api.mInvSetCategory(row.categoryNo, { managed, confirm: true });
    }
    // 确认之后又冒出在途单据（这几秒里有人开了进货单）：照样说清楚
    if (r.status === "BLOCKED") {
      await alertBlocked(t, r);
      return false;
    }
    return true;
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
    return false;
  }
}

async function alertBlocked(t: T, r: InvModeChange) {
  await confirm({
    title: String(t("stockSettings.blockedTitle")),
    hint: String(t("stockSettings.blockedHint", { list: describeBlockers(t, r.goods) })),
    alert: true,
  });
}
