// 订阅消息授权。**一次授权只够一条**（TDD-元器件-前端独立与通知矩阵 §2.2），所以每个
// 可能产生结果的动作上单独要一次：提交询价 → 「有报价了」；提交报价 → 「被选中了」；
// 打开求购 → 「有新求购」。
//
// 三件事**同一个模板**（mp 公共模板 2319「报价更新通知」）：主系统按场景 ELEC_QUOTED 发，
// 四个位置是「单号 · 料号概述 · 结果 · 提示语」（InternalElecEndpoint 的注释写了为什么不分三个 ——
// 多一个模板就多一次授权，供应商多半不会点第二次）。模板号必须与主系统的 WX_TPL_ELEC_QUOTED 同值。
//
// **授权完要上报**（同意与拒绝都报）：后端按上报的记录扣额度，不报就一条也发不出 ——
// 而端上看起来一切正常（弹窗出了、用户点了允许）。
import { requestSubscribe } from "@shared/ports/push";
import { STORAGE } from "@shared/utils/constants";
import { api } from "@/api";

const TPL = import.meta.env.VITE_WX_TPL_ELEC_QUOTED || "";

export type SubscribeKind = "quoted" | "dispatch" | "picked";

/** 必须在点击回调里**同步**调起（微信 2.8.2 起的限制），失败不拦主流程 */
export async function askSubscribe(_kind: SubscribeKind): Promise<void> {
  if (!TPL) return;
  try {
    const r = await requestSubscribe([TPL]);
    // 没登录时不报：401 会触发全局的「去登录」，与调用方自己的登录跳转撞车。
    // 这种情况很少（小程序打开即静默登录），丢的只是这一次额度
    if (!uni.getStorageSync(STORAGE.token)) return;
    if (r.accepted.length) await api.subscribeReport(r.accepted, true);
    if (r.rejected.length) await api.subscribeReport(r.rejected, false);
  } catch {
    // 授权与上报都只是加速通道：站内信是必达的那一份
  }
}
