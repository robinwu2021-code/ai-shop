package ai.neargo.shop.trade.service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 快递代下单（TDD-快递100商家寄件 · ADR-028）。
 *
 * <p>商家叫快递 → 平台代下单 → 快递员取件后回调 → 运单号回填（走原发货链路，微信上报跟着走）
 * → 运费记到商家欠款。
 *
 * <p>商家侧四个方法都带 {@code merchantNo / storeNo}：归属由 BizContext 给，不信请求参数。
 */
public interface ExpressPickupService {

    /** 各家报价，按价升序。查不到价的那家不出现（AC1） */
    List<QuoteVO> quotes(String merchantNo, String storeNo, String subOrderNo, BigDecimal weightKg);

    /** 代下单（AC2） */
    PickupVO create(String merchantNo, String storeNo, String subOrderNo, String carrier, BigDecimal weightKg);

    /** 这一单最近一张取件单；没叫过为 null */
    PickupVO latest(String merchantNo, String storeNo, String subOrderNo);

    /** 取件前取消（AC6） */
    PickupVO cancel(String merchantNo, String storeNo, String subOrderNo);

    /**
     * 通道回调（AC3–AC5）。
     *
     * @return false = 验签不过或认不出这一单，调用方回失败让通道重推；true = 已处理（含重复推送）
     */
    boolean onCallback(String taskId, String sign, String param);

    record QuoteVO(String carrier, String carrierName, long priceMinor, long listPriceMinor) {
    }

    /**
     * @param weightG        商家申报重量（克）
     * @param chargedWeightG 快递员称重后的计费重量（克），取件前为空
     * @param freightMinor   平台实付运费（分），取件前为空；取件后记到商家欠款
     */
    record PickupVO(String pickupNo, String carrier, String carrierName, String status, String trackingNo,
                    int weightG, Integer chargedWeightG, Long freightMinor, String courierName,
                    String courierMobile, String failReason, long createdAt) {
    }
}
