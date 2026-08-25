package ai.neargo.shop.product.service;

import java.util.Set;

/**
 * 支付方式可用性的<b>唯一判定入口</b>。
 *
 * <p><b>禁止别处重复实现这串判定。</b> 不这样做的话，商品详情页说「支持货到付款」、
 * 结算页说不支持，是必然会发生的形态 —— 两处各判一遍，改一处忘一处。
 *
 * <p><b>它与积分的合成规则相反，这是最容易混的地方</b>：
 * <ul>
 *   <li>积分发多少 —— <b>取一个值</b>（类目 → 兜底，命中即停）：它是数值规则，
 *       多层各给一个数，只能选一个</li>
 *   <li>支付方式能不能用 —— <b>取交集</b>（四层全放行才可用）：它是能力与许可，
 *       任何一层说不行就是不行</li>
 * </ul>
 */
public interface PayModeService {

    /**
     * 这件商品在这家店支持哪些支付方式。<b>四层取交集</b>：
     *
     * <pre>
     *   ① 平台 × 类目   prd_category_pay_mode   —— 没有行即放行
     *   ② 主体资质      mch_qualification       —— 短期主力，按 expire_at 现算
     *   ③ 门店          mch_store               —— 默认关，商家自己开
     *   ④ 商品          prd_goods.pay_modes     —— 商家愿不愿意
     * </pre>
     *
     * <p><b>永远至少返回 {@code ONLINE}</b>：线上支付不受这四层约束，
     * 否则配错一处就会出现「这件商品谁也买不了」，而那比多开一种支付方式糟得多。
     *
     * @param storeNo 可空。空表示按主体判（单店场景两者恒等）
     */
    Set<String> availablePayModes(String goodsNo, String storeNo);
}
