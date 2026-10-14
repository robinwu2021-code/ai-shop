package ai.neargo.shop.product;

import ai.neargo.shop.product.service.GoodsTextRuleParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文字规则解析（TDD-商品快速录入 §5 · AC5/AC6）。
 *
 * <p>守的是「确定性字段规则一抓一个准，且 LLM 不参与也不丢」——
 * 所以这里完全不碰 LLM，只喂文字、断言规则抽出了什么。
 */
class GoodsTextRuleParserTest {

    /** 用户给的原始例子——方案就是照它来的，它必须整段过。 */
    private static final String SAMPLE = """
            规格：单果140g+
            净重4.5斤装10元
            圆通快递，新疆西藏海南不发货""";

    @Test
    @DisplayName("★★★ AC5 整段例子：价格、重量、快递、不发货区域都抽对")
    void parsesTheWholeSample() {
        var r = GoodsTextRuleParser.parse(SAMPLE);
        assertThat(r.pricesMinor()).containsExactly(1000L);            // 10 元 = 1000 分
        assertThat(r.weights()).contains("140g", "4.5斤");
        assertThat(r.carriers()).containsExactly("圆通");
        assertThat(r.excludeRegions()).containsExactly("新疆", "西藏", "海南");
    }

    @Test
    @DisplayName("★★★ AC5 价格必须带货币符号——140g 里的 140 不算价")
    void onlyCurrencyMarkedNumbersAreprice() {
        // 若把「带元/￥」这道去掉，140 和 4.5 都会被当成价——这正是消融要红的点
        assertThat(GoodsTextRuleParser.parse("单果140g，4.5斤").pricesMinor()).isEmpty();
        assertThat(GoodsTextRuleParser.parse("￥9.9 包邮").pricesMinor()).containsExactly(990L);
        assertThat(GoodsTextRuleParser.parse("10.1元").pricesMinor()).containsExactly(1010L); // 不丢分
    }

    @Test
    @DisplayName("★★★ AC5 不发货区域只在「不发货」前面的窗口里找——否则会误收")
    void regionOnlyNearNoShipKeyword() {
        // 没有「不发货」就一个地名都不收，哪怕文里写了省名
        assertThat(GoodsTextRuleParser.parse("新疆哈密瓜，全国包邮").excludeRegions()).isEmpty();
        // 「全国包邮，新疆请注意」没说不发，也不收
        assertThat(GoodsTextRuleParser.parse("全国包邮").excludeRegions()).isEmpty();
        // 命中关键词才收
        assertThat(GoodsTextRuleParser.parse("西藏青海不配送").excludeRegions())
                .containsExactly("西藏", "青海");
    }

    @Test
    @DisplayName("AC5 快递是固定词表，不泛匹配「XX快递」")
    void carriersAreFromFixedTable() {
        assertThat(GoodsTextRuleParser.parse("顺丰发货").carriers()).containsExactly("顺丰");
        // 「次日快递」不是承运商，不该被收
        assertThat(GoodsTextRuleParser.parse("次日快递送达").carriers()).isEmpty();
    }

    @Test
    @DisplayName("AC6 空/纯噪声文本：规则返回空结果，不抛异常")
    void blankTextYieldsEmpty() {
        var r = GoodsTextRuleParser.parse("   ");
        assertThat(r.pricesMinor()).isEmpty();
        assertThat(r.carriers()).isEmpty();
        assertThat(GoodsTextRuleParser.parse(null).weights()).isEmpty();
    }
}
