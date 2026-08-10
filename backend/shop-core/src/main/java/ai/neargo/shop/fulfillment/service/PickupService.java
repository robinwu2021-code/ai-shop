package ai.neargo.shop.fulfillment.service;

import ai.neargo.shop.fulfillment.dto.PickingRowVO;
import ai.neargo.shop.fulfillment.dto.PickupOrderVO;
import ai.neargo.shop.fulfillment.dto.PickupOverviewVO;
import ai.neargo.shop.fulfillment.dto.VerifyResultVO;

import java.util.List;

/**
 * 自提点履约台（[API 清单 §3.5]）。**自提履约的必要条件** ——
 * 缺它货到了没人能核销，订单永远停在「备货中」，评价与结算也就没有数据来源。
 */
public interface PickupService {

    PickupOverviewVO overview(String pickupNo);

    /** 扫码/输码核销。失败返回具体原因而不是抛异常 —— 店主要看到「为什么」。 */
    VerifyResultVO verify(String verifyCode, boolean onBehalf);

    BatchResult verifyBatch(List<String> verifyCodes);

    /** 本点订单（字段已裁剪，见 {@link PickupOrderVO}）。 */
    List<PickupOrderVO> orders(String pickupNo, String status);

    /** 按码搜索（扫码失败时的兜底）。 */
    List<PickupOrderVO> searchByCode(String keyword);

    /** 分拣单：按商品聚合。 */
    List<PickingRowVO> picking(String pickupNo);

    /**
     * 标记到货（B-6.3）：一批自提单从「待履约」推进到「已到货、等买家来取」。
     *
     * <p><b>只认本自提点的单</b>，且对已到货/已核销的重复点击静默跳过 ——
     * 到货登记在自提点是高频且容易重复点的动作。
     *
     * @return 真正被推进的订单
     */
    List<PickupOrderVO> markArrived(String pickupNo, List<String> subOrderNos);

    /**
     * 短少 / 破损上报（B-6.5）。
     *
     * <p><b>只留痕并让买家看见，不退款、不改状态</b> —— 责任在供货方还是承接方尚未定
     * （矩阵 M4），自动退款等于默认平台兜底。买家看到时间线后可以自己走售后。
     *
     * <p>已核销的单不能报：货已交到买家手上，那时的短少是售后问题，责任认定路径不同。
     */
    PickupOrderVO reportShortage(String pickupNo, String subOrderNo, String kind,
                                 String skuNo, String note);

    record BatchResult(int successCount, List<VerifyResultVO> failed) {
    }
}