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
     * 这个模板号此刻能不能用（存在且没归档）—— <b>不回落默认</b>。
     * 商品指定的模板被归档时要回落到<b>门店</b>模板（AC8），而 {@link #template} 会直接回落平台默认。
     */
    default boolean active(String templateNo) {
        return false;
    }

    /** 平台在用的模板（非归档），给商家在商品上选（AC7） */
    default List<Template> activeTemplates() {
        return List.of();
    }

    /**
     * 一家门店这一单的运费：行各自带着解析好的模板（商品 ＞ 门店 ＞ 默认），按 {@link #merge} 合并。
     *
     * @return 模板一个都没有时为空（与 {@link #quote} 同口径：调用方按 0）
     */
    default Optional<Quote> quoteMerged(List<FreightLine> lines, String receiverAddress) {
        return Optional.empty();
    }

    /**
     * 一行货的运费输入。
     *
     * @param templateNo 已解析好的模板号（商品 ＞ 门店 ＞ 默认）；空 = 平台默认
     */
    record FreightLine(String templateNo, int weighedGram, int unweighedUnits, long goodsAmountMinor) {
    }

    /**
     * 合并的一份：同一个模板下的全部货。
     *
     * @param weightGram 计费重（没填重量的件已按首重折进来）
     * @param hit        收货地址命中的地区规则；没命中为空
     */
    record Part(Template template, int weightGram, long goodsAmountMinor, Rule hit) {
    }

    /**
     * ★ 多模板合并（淘宝式，ADR-031 §2.5）—— 计价规则只此一处，端上 {@code freight.ts} 逐条照抄、用例对拍。
     *
     * <ol>
     *   <li>任一份命中「不配送」→ 整店拒；</li>
     *   <li>各份按自己模板的门槛判满额包邮，包邮的那份 0 元、不参与下面的比较；</li>
     *   <li>其余份里<b>首费最高</b>的那个模板计首重 + 续重，其他份的<b>全部重量只按各自续重</b>计；</li>
     *   <li>地区加收取参与计费那几份里最高的一笔，一个包裹只加一次。</li>
     * </ol>
     * 只有一份时与 {@link #fee} + 加收逐字相同 —— 改造前的单模板行为不变。
     */
    static Quote merge(List<Part> parts) {
        for (Part p : parts) {
            if (p.hit() != null && ACTION_REJECT.equals(p.hit().action())) {
                return new Quote(p.template().templateNo(), 0L, true, p.hit().region(), false);
            }
        }
        List<Part> charged = parts.stream()
                .filter(p -> !(p.template().freeThreshold() > 0
                        && p.goodsAmountMinor() >= p.template().freeThreshold()))
                .toList();
        if (charged.isEmpty()) {
            Part first = parts.get(0);
            return new Quote(first.template().templateNo(), 0L, false,
                    first.hit() == null ? null : first.hit().region(), true);
        }
        // 首费最高者计首重；同额取先出现的（与活动「同额取先」同一个取法，结果确定）
        Part primary = charged.get(0);
        for (Part p : charged) {
            if (p.template().firstFee() > primary.template().firstFee()) {
                primary = p;
            }
        }
        Template t = primary.template();
        long fee = fee(primary.weightGram(), t.firstWeightGram(), t.firstFee(), t.addWeightGram(), t.addFee());
        long surcharge = 0L;
        String region = primary.hit() == null ? null : primary.hit().region();
        for (Part p : charged) {
            if (p != primary) {
                Template o = p.template();
                if (o.addWeightGram() > 0 && p.weightGram() > 0) {
                    fee += (p.weightGram() + o.addWeightGram() - 1L) / o.addWeightGram() * o.addFee();
                }
            }
            if (p.hit() != null && p.hit().surcharge() > surcharge) {
                surcharge = p.hit().surcharge();
                region = p.hit().region();
            }
        }
        return new Quote(t.templateNo(), fee + Math.max(0L, surcharge), false, region, false);
    }

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
