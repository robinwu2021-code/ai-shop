package ai.neargo.shop.fulfillment.dto;

import java.util.List;

/**
 * 自提点视角的订单 —— **越权防线④（字段级裁剪）的落点**（TDD-backend §5.2）。
 *
 * <p>自提点承接方必然会看到**别家商家**的货到自己点上核销，这是业务上躲不掉的。
 * 但他只需要「认出人、找到货、扫码核销」，因此这个 VO 里：
 * <ul>
 *   <li>✅ 有：核销码、买家昵称、手机号后四位、商品名与件数</li>
 *   <li>❌ 没有：<b>任何金额字段、完整手机号、地址、商家结算信息</b></li>
 * </ul>
 *
 * <p><b>为什么是「另一个类」而不是给订单 VO 加条件序列化</b>：
 * `@JsonIgnore` 之类的条件隐藏依赖运行时判断，加字段时很容易忘记补条件，
 * 而漏掉的后果是静默泄漏 —— 没有报错、没有告警。字段在类型里根本不存在，就不可能泄漏。
 * 由 {@code M4FulfillmentFlowTest} 逐字段断言。
 */
public record PickupOrderVO(String subOrderNo,
                            String verifyCode,
                            /** 只给昵称：承接方需要的是「认出这个人」，不是知道他是谁 */
                            String buyerNickname,
                            /** 后四位，用于当面核对 */
                            String buyerPhoneTail,
                            String merchantName,
                            String status,
                            String pickupNo,
                            List<Item> items) {

    /** 分拣与交付需要的最小信息：什么货、什么规格、几件。 */
    public record Item(String goodsNo, String title, String spec, int qty) {
    }
}
