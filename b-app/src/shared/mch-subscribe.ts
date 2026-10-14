// 商家在小程序里顺带攒订阅消息额度（TDD-微信订阅消息优先 AC8）。
//
// 厂商通道没报备，App 退到后台就收不到推送（通知 TDD §13.3②）；而商家端已整包并进小程序，
// 店主的商家账号与 C 端同一个 user_no —— 在小程序里点「允许」攒下的额度，后端发「新订单 / 售后 / 评价」时直接用得上。
//
// 一次性订阅：一次「允许」只够发一条。所以挂在**常用操作**上（发货、核销、处理售后、回评价），
// 用户勾了「总是保持以上选择」之后不再弹框、每点一次静默攒一条。
// App / H5 里 requestSubscribe 直接返回空，这里什么都不发生。

import { api } from "@/api";
import { requestSubscribe, SUBSCRIBE_TMPL } from "@shared/ports/push";

/**
 * 这一次会话里三个全被拒过就不再问 —— 没勾「总是保持」的拒绝每次都会再弹，
 * 挂在每一次发货上就成了骚扰。下次打开小程序再问。
 */
let declined = false;

/**
 * **必须在点击回调里、任何 await 之前同步调用** —— 隔一次 await 微信就不认这是用户点击，框弹不出来。
 * 不等它：授权框与业务操作各走各的。
 */
export function askMchSubscribe(): void {
  if (declined) return;
  void requestSubscribe([SUBSCRIBE_TMPL.mchNewOrder, SUBSCRIBE_TMPL.mchAfterSale, SUBSCRIBE_TMPL.mchReview])
    .then((r) => {
      if (r.accepted.length) void api.mSubscribeReport(r.accepted, true).catch(() => undefined);
      if (r.rejected.length) void api.mSubscribeReport(r.rejected, false).catch(() => undefined);
      if (!r.accepted.length && r.rejected.length) declined = true;
    });
}
