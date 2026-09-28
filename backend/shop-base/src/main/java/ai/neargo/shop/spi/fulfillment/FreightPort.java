package ai.neargo.shop.spi.fulfillment;

import java.util.List;
import java.util.Optional;

/**
 * 运费模板算价（TDD-快递100商家寄件 §8）。模板属于履约域，下单（交易域）经这里算快递运费。
 *
 * <p><b>计价公式只此一处</b>（{@link #fee}），端上的预估（packages/shared）按同一公式，两边用例对拍。
 */
public interface FreightPort {

    /** 模板命中「不配送」时的动作，与 {@code FulFreightTemplate.ACTION_REJECT} 同值 */
    String ACTION_REJECT = "REJECT";

    /**
     * 算一组货（同一商家）的快递运费。
     *
     * @param templateNo       模板号；空 = 平台默认模板
     * @param weighedGram      填了重量的那些件的总重（克）
     * @param unweighedUnits   没填重量的件数 —— 每件按首重计（§8 澄清：按首重算并提醒商家补）
     * @param goodsAmountMinor 这组货的商品金额（分），判满额免邮
     * @param receiverAddress  收货地址整条（含省份），按模板的地区名匹配加收 / 不配送
     * @return 模板一个都没有时为空 —— 调用方按 0 处理（与改造前一致），不编一个数
     */
    Optional<Quote> quote(String templateNo, int weighedGram, int unweighedUnits, long goodsAmountMinor,
                          String receiverAddress);

    /** 模板本身（给商家看「本店适用的运费模板」）；空 = 平台默认模板 */
    Optional<Template> template(String templateNo);

    /**
     * @param rejected      命中了「不配送」的地区 —— 下单要拒
     * @param matchedRegion 命中的地区名（加收或不配送），没命中为空
     * @param free          满额免邮
     */
    record Quote(String templateNo, long feeMinor, boolean rejected, String matchedRegion, boolean free) {
    }

    record Rule(String region, String action, long surcharge) {
    }

    record Template(String templateNo, String name, int firstWeightGram, long firstFee, int addWeightGram,
                    long addFee, long freeThreshold, List<Rule> rules) {
    }

    /**
     * 计价公式：{@code 重 ≤ 首重 → 首重费；否则 首重费 + ⌈(重 − 首重) / 续重单位⌉ × 续重费}。
     * 不含地区加收与满免 —— 那两样在 {@link #quote} 里叠。
     */
    static long fee(int weightGram, int firstWeightGram, long firstFee, int addWeightGram, long addFee) {
        if (weightGram <= firstWeightGram || addWeightGram <= 0) {
            return firstFee;
        }
        long units = (weightGram - firstWeightGram + addWeightGram - 1L) / addWeightGram;
        return firstFee + units * addFee;
    }
}
