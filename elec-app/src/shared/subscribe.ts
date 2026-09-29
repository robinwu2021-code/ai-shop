// 订阅消息授权。**一次授权只够一条**（TDD-元器件-前端独立与通知矩阵 §2.2），所以每个
// 可能产生结果的动作上单独要一次：提交询价 → 「有报价了」；提交报价 → 「被选中了」；
// 打开求购列表 → 「有新求购」。
//
// ⚠️ 模板号还没选（.env 里是空的）：空的时候 requestSubscribe 整批剔掉、不弹窗。
// 独立小程序的额度也还没有地方上报 —— 主系统的 /mp/message/subscribe 记的是「社区好物」
// 那个 appid 的额度。两件都是 TDD §L4 的待拍板，这里先把调用点放对。
import { requestSubscribe } from "@shared/ports/push";

const TPL = {
  quoted: import.meta.env.VITE_WX_TPL_ELEC_QUOTED || "",
  dispatch: import.meta.env.VITE_WX_TPL_ELEC_DISPATCH || "",
  picked: import.meta.env.VITE_WX_TPL_ELEC_PICKED || "",
};

/** 必须在点击回调里**同步**调起（微信 2.8.2 起的限制），失败不拦主流程 */
export function askSubscribe(kind: keyof typeof TPL): Promise<unknown> {
  const id = TPL[kind];
  return id ? requestSubscribe([id]).catch(() => undefined) : Promise.resolve();
}
