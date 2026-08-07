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

    record BatchResult(int successCount, List<VerifyResultVO> failed) {
    }
}
