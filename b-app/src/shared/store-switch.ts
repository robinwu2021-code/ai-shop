/**
 * 切店的整套过程：幕布升起 → 真的切完 → 打勾 → 落地。两个切店入口（选择门店、门店管理）共用。
 *
 * 店主两次反馈「切店感知不到」：转圈 + 带勾 toast 都是屏幕中间一个小灰块，
 * 一秒不到就没了；toast 还只放得下 7 个字，「已切换至 虹选粮油·深圳测试店」被裁成「已切换至 虹选粮」——
 * 最该看清的店名恰恰看不见。现在换成整屏主色幕布 + 大字店名（见 biz-switch-curtain）。
 */
import { ref } from "vue";
import { useMerchantStore } from "@/stores/merchant";

export type CurtainPhase = "" | "going" | "done" | "leaving";

/** 幕布至少停这么久再打勾：切店接口快的时候一闪而过，等于没有 */
const MIN_GOING_MS = 600;
/** 打勾后停多久：够读完店名 */
const DONE_MS = 900;
/** 淡出时长，与 biz-switch-curtain 的 transition 对齐 */
const LEAVE_MS = 250;

const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

export function useStoreSwitch() {
  const merchant = useMerchantStore();
  const phase = ref<CurtainPhase>("");
  const name = ref("");

  /**
   * @param land 切完要去别的页时传：**幕布还盖着时就跳**，落地页直接是新店那一屏；
   *             不传 = 留在本页，幕布淡出后露出的就是已切换的本页
   * @returns 是否真的切了（正在切时再点返回 false）
   */
  async function run(storeNo: string, storeName: string, land?: () => void): Promise<boolean> {
    if (phase.value) return false;
    name.value = storeName;
    phase.value = "going";
    const started = Date.now();
    try {
      await merchant.pickStore(storeNo);
    } catch (e) {
      // 切失败：收起幕布，错误照旧交给调用方/全局提示 —— 不能停在一块打着勾的幕布上
      phase.value = "";
      throw e;
    }
    const rest = MIN_GOING_MS - (Date.now() - started);
    if (rest > 0) await sleep(rest);
    phase.value = "done";
    await sleep(DONE_MS);
    if (land) {
      land();
      return true;
    }
    phase.value = "leaving";
    await sleep(LEAVE_MS);
    phase.value = "";
    return true;
  }

  return { phase, name, run };
}
