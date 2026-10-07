package ai.neargo.shop.product;

import ai.neargo.shop.product.api.biz.BizGoodsController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ai.neargo.shop.spi.product.GoodsVisionPort;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
                mock(ai.neargo.shop.product.service.SpecLibraryService.class),
                mock(ai.neargo.shop.product.service.GoodsRevisionService.class));
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
    @Test
    @DisplayName("★★★ 文字走 LLM：参数被归类、省名→限购地区码；价格仍以规则为准")
    void mergesLlmParamsAndRegions() {
        var vision = mock(GoodsVisionPort.class);
        // 控制器调的是带清单的那个（两参数）—— mock 单参数版的话这里拿到的是 null，测试会假失败
        when(vision.extractText(anyString(), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(new GoodsVisionPort.TextExtract(
                "", List.of(new GoodsVisionPort.ParamKV("单果重量", "140g+"),
                        new GoodsVisionPort.ParamKV("净重", "4.5斤")),
                10.0, List.of("EXPRESS"), "圆通",
                List.of("新疆维吾尔自治区", "西藏自治区", "海南省"), 0.9));
        var controller = new BizGoodsController(
                mock(ai.neargo.shop.product.service.MerchantGoodsService.class),
                mock(ai.neargo.shop.product.service.CategoryService.class),
                vision,
                mock(ai.neargo.shop.product.service.SpuStdService.class),
                mock(ai.neargo.shop.product.service.SpecLibraryService.class),
                mock(ai.neargo.shop.product.service.GoodsRevisionService.class));

        var vo = controller.parseText(new BizGoodsController.ParseTextReq(
                "规格：\n单果140g+\n净重4.5斤装10元\n圆通快递，新疆西藏海南不发货", null));

        // 参数来自 LLM：单果重量 / 净重 各一条（规则层不做归类）
        assertThat(vo.params()).extracting(BizGoodsController.GoodsTextParseVO.ParamDraft::name)
                .containsExactlyInAnyOrder("单果重量", "净重");
        assertThat(vo.params()).allMatch(p -> "llm".equals(p.source()));
        // 省 → 国标两位码（规则省名 ∪ LLM 省名，去重）：新疆 65 / 西藏 54 / 海南 46
        assertThat(vo.restrictedRegions()).containsExactlyInAnyOrder("65", "54", "46");
        // 价格仍以规则为准（真金白银不交给概率）
        assertThat(vo.pricesMinor()).containsExactly(1000L);
    }
    // ── 参数核对到品类的标准参数（TDD-商品快速录入-品类感知与逐项确认 §7 第 2 条）──

    /** 水果的标准参数清单（与 V378 绑定一致）。propsForCategory 的替身按它回 */
    private static List<ai.neargo.shop.product.dto.SpecTemplateVO> fruitProps() {
        return List.of(
                prop("SD_UNIT_WEIGHT", "单果重量"),
                prop("SD_NET_CONTENT", "净含量"),
                prop("SD_GROSS_WEIGHT", "毛重"),
                prop("SD_ORIGIN_DETAIL", "原产地"));
    }

    private static ai.neargo.shop.product.dto.SpecTemplateVO prop(String dimNo, String name) {
        return new ai.neargo.shop.product.dto.SpecTemplateVO(
                dimNo, "PLATFORM", null, "CAT120", name, List.of(), null, false, "TEXT");
    }

    private static BizGoodsController controllerWith(GoodsVisionPort vision,
                                                     ai.neargo.shop.product.service.SpecLibraryService lib) {
        return new BizGoodsController(
                mock(ai.neargo.shop.product.service.MerchantGoodsService.class),
                mock(ai.neargo.shop.product.service.CategoryService.class),
                vision,
                mock(ai.neargo.shop.product.service.SpuStdService.class),
                lib,
                mock(ai.neargo.shop.product.service.GoodsRevisionService.class));
    }

    @Test
    @DisplayName("★★★ 净重 → 净含量、单果重量 → 单果重量：落到品类的标准维度号，不再拿中文名当维度号")
    void paramsMapToCategoryStandardDims() {
        // 模型照旧自由起名、没给维度号 —— 走的是名称 / 别名那两步
        var vision = mock(GoodsVisionPort.class);
        when(vision.extractText(anyString(), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(new GoodsVisionPort.TextExtract("", List.of(
                        new GoodsVisionPort.ParamKV("单果重量", "140g+"),
                        new GoodsVisionPort.ParamKV("净重", "4.5斤")),
                        10.0, List.of("EXPRESS"), "圆通", List.of(), 0.9));
        var lib = mock(ai.neargo.shop.product.service.SpecLibraryService.class);
        when(lib.propsForCategory(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("CAT120")))
                .thenReturn(fruitProps());

        var vo = controllerWith(vision, lib).parseText(new BizGoodsController.ParseTextReq(
                "单果140g+ 净重4.5斤装10元", "CAT120"));

        // 生产草稿里此前是 {dimNo:"单果重量"} {dimNo:"净重"} —— 游离参数
        assertThat(vo.params()).extracting(BizGoodsController.GoodsTextParseVO.ParamDraft::dimNo)
                .containsExactly("SD_UNIT_WEIGHT", "SD_NET_CONTENT");
        // 展示名用**标准名称**，不是原文叫法：「净重」落成「净含量」
        assertThat(vo.params()).extracting(BizGoodsController.GoodsTextParseVO.ParamDraft::name)
                .containsExactly("单果重量", "净含量");
        assertThat(vo.params()).extracting(BizGoodsController.GoodsTextParseVO.ParamDraft::label)
                .containsExactly("140g+", "4.5斤");
    }

    @Test
    @DisplayName("★★★ 品类清单真的交给了模型 —— 映射是模型拿着清单做的，不只是事后核对")
    void hintsArePassedToModel() {
        var vision = mock(GoodsVisionPort.class);
        when(vision.extractText(anyString(), org.mockito.ArgumentMatchers.anyList())).thenReturn(null);
        var lib = mock(ai.neargo.shop.product.service.SpecLibraryService.class);
        when(lib.propsForCategory(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("CAT120")))
                .thenReturn(fruitProps());

        controllerWith(vision, lib).parseText(new BizGoodsController.ParseTextReq("单果140g+", "CAT120"));

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<GoodsVisionPort.ParamHint>> cap =
                org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(vision).extractText(anyString(), cap.capture());
        assertThat(cap.getValue()).extracting(GoodsVisionPort.ParamHint::dimNo)
                .contains("SD_UNIT_WEIGHT", "SD_NET_CONTENT", "SD_GROSS_WEIGHT");
    }

    @Test
    @DisplayName("模型给的维度号在清单里 → 直接用（数据驱动的主路）")
    void modelChosenDimNoIsUsed() {
        var vision = mock(GoodsVisionPort.class);
        when(vision.extractText(anyString(), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(new GoodsVisionPort.TextExtract("", List.of(
                        new GoodsVisionPort.ParamKV("带箱重量", "5斤", "SD_GROSS_WEIGHT")),
                        null, List.of(), "", List.of(), 0.9));
        var lib = mock(ai.neargo.shop.product.service.SpecLibraryService.class);
        when(lib.propsForCategory(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("CAT120")))
                .thenReturn(fruitProps());

        var vo = controllerWith(vision, lib).parseText(new BizGoodsController.ParseTextReq("带箱5斤", "CAT120"));
        assertThat(vo.params().get(0).dimNo()).isEqualTo("SD_GROSS_WEIGHT");
        assertThat(vo.params().get(0).name()).isEqualTo("毛重");
    }

    @Test
    @DisplayName("★★★ 模型编了一个清单外的维度号 → 不信，退回自由参数")
    void fabricatedDimNoIsRejected() {
        var vision = mock(GoodsVisionPort.class);
        when(vision.extractText(anyString(), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(new GoodsVisionPort.TextExtract("", List.of(
                        new GoodsVisionPort.ParamKV("甜度", "18度", "SD_SWEETNESS")),
                        null, List.of(), "", List.of(), 0.9));
        var lib = mock(ai.neargo.shop.product.service.SpecLibraryService.class);
        when(lib.propsForCategory(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("CAT120")))
                .thenReturn(fruitProps());

        var vo = controllerWith(vision, lib).parseText(new BizGoodsController.ParseTextReq("甜度18度", "CAT120"));
        // 不能落到一个不存在的维度上 —— 原样当自由参数，什么都不丢
        assertThat(vo.params().get(0).dimNo()).isEqualTo("甜度");
    }

    @Test
    @DisplayName("别名的目标不在本品类清单里 → 不落（「净重=净含量」，但这个品类没绑净含量）")
    void aliasNeedsTargetInCategory() {
        var vision = mock(GoodsVisionPort.class);
        when(vision.extractText(anyString(), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(new GoodsVisionPort.TextExtract("", List.of(
                        new GoodsVisionPort.ParamKV("净重", "500g")),
                        null, List.of(), "", List.of(), 0.9));
        var lib = mock(ai.neargo.shop.product.service.SpecLibraryService.class);
        when(lib.propsForCategory(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("CAT999")))
                .thenReturn(List.of(prop("SD_ORIGIN_DETAIL", "原产地")));

        var vo = controllerWith(vision, lib).parseText(new BizGoodsController.ParseTextReq("净重500g", "CAT999"));
        assertThat(vo.params().get(0).dimNo()).isEqualTo("净重");
    }

    @Test
    @DisplayName("没有类目 → 不给清单、退回自由参数（与此前一致）")
    void noCategoryNoHints() {
        var vision = mock(GoodsVisionPort.class);
        when(vision.extractText(anyString(), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(new GoodsVisionPort.TextExtract("", List.of(
                        new GoodsVisionPort.ParamKV("净重", "4.5斤")),
                        null, List.of(), "", List.of(), 0.9));
        var lib = mock(ai.neargo.shop.product.service.SpecLibraryService.class);

        var vo = controllerWith(vision, lib).parseText(new BizGoodsController.ParseTextReq("净重4.5斤", null));
        assertThat(vo.params().get(0).dimNo()).isEqualTo("净重");
        org.mockito.Mockito.verifyNoInteractions(lib);
    }

}
