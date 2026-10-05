package ai.neargo.shop.product;

import ai.neargo.shop.product.api.biz.BizGoodsController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * parse-text 端点的**组装逻辑**（TDD-商品快速录入 §5 · AC6/AC9）。
 *
 * <p>规则本身在 {@link GoodsTextRuleParserTest}；这里只验 controller 把规则结果
 * 组装成 VO 的那几步：快递→fulfillment、区域空列表→null（不是空串）、置信度。
 * 不走 HTTP/权限——组装逻辑与鉴权无关，单元测更快更准。
 */
class BizGoodsParseTextTest {

    private static BizGoodsController controller() {
        // parseText 一个依赖都不碰（只调静态规则 parser），全 mock 即可
        return new BizGoodsController(
                mock(ai.neargo.shop.product.service.MerchantGoodsService.class),
                mock(ai.neargo.shop.product.service.CategoryService.class),
                mock(ai.neargo.shop.spi.product.GoodsVisionPort.class),
                mock(ai.neargo.shop.product.service.SpuStdService.class),
                mock(ai.neargo.shop.product.service.SpecLibraryService.class));
    }

    @Test
    @DisplayName("★★★ AC9 用户给的例子：快递→EXPRESS、区域只回文本、价抽对")
    void assemblesSampleIntoVo() {
        var vo = controller().parseText(new BizGoodsController.ParseTextReq(
                "净重4.5斤装10元\n圆通快递，新疆西藏海南不发货", null));
        assertThat(vo.pricesMinor()).containsExactly(1000L);
        assertThat(vo.fulfillment()).containsExactly("EXPRESS");
        assertThat(vo.carriers()).containsExactly("圆通");
        // 不发货区域**只回文本**——不自动改运费模板（AC9）
        assertThat(vo.excludeRegionText()).isEqualTo("新疆 西藏 海南");
        assertThat(vo.confidence()).isEqualTo(1d);
        // P1 规则层不做语义归类
        assertThat(vo.specs()).isEmpty();
        assertThat(vo.params()).isEmpty();
    }

    @Test
    @DisplayName("★★★ AC9 没有不发货区域时回 null，不是空串")
    void noRegionYieldsNullNotBlank() {
        // 端上判「有没有不发货区域」只看一个条件；空串会让它误以为有、弹出一个空跳转
        var vo = controller().parseText(new BizGoodsController.ParseTextReq("圆通快递 9.9元", null));
        assertThat(vo.excludeRegionText()).isNull();
    }

    @Test
    @DisplayName("没有快递词 → fulfillment 为空，不硬塞 EXPRESS")
    void noCarrierNoFulfillment() {
        var vo = controller().parseText(new BizGoodsController.ParseTextReq("净重4.5斤 10元", null));
        assertThat(vo.fulfillment()).isEmpty();
        assertThat(vo.pricesMinor()).containsExactly(1000L);
    }

    @Test
    @DisplayName("AC6 整段都没认出来 → confidence=0（端上据此提示没识别）")
    void nothingRecognizedYieldsZeroConfidence() {
        var vo = controller().parseText(new BizGoodsController.ParseTextReq("今天天气不错", null));
        assertThat(vo.confidence()).isEqualTo(0d);
        assertThat(vo.pricesMinor()).isEmpty();
    }
}
