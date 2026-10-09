package ai.neargo.shop.logistics.wxbind;

import ai.neargo.shop.logistics.domain.WaybillStatus;
import ai.neargo.shop.logistics.entity.LgsWaybill;

/**
 * 什么时候去微信换 waybill_token（TDD-物流模块 §2.4.3，AC2 / AC3）。
 *
 * <p><b>前置条件不满足就不调</b>：发货信息没上传微信，微信不认识这笔交易；快递还没揽收，
 * 微信那边一定查不到这个运单（9300559）—— 两种情况盲调都只是在耗微信的调用次数（9300513）。
 * 两个条件先后不定（上传事件与揽收推送谁先到都可能），所以两边各判一次。
 */
public final class WxBindPolicy {

    private WxBindPolicy() {
    }

    /** @param onShip 配置 {@code wx-bind.trigger=on-ship}：不等揽收 */
    public static boolean ready(LgsWaybill w, boolean onShip) {
        if (w == null || !LgsWaybill.PROFILE_WX.equals(w.getProfile())
                || !LgsWaybill.BIND_WAITING.equals(w.getBindState())
                || w.getWxUploadedAt() == null
                || (w.getDisplayToken() != null && !w.getDisplayToken().isBlank())) {
            return false;
        }
        String s = w.getStatus();
        if (WaybillStatus.CANCELLED.equals(s)) {
            return false;
        }
        return onShip || !WaybillStatus.CREATED.equals(s);
    }
}
