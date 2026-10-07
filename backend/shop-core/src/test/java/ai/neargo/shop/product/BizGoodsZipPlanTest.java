package ai.neargo.shop.product;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.product.api.biz.BizGoodsController;
import ai.neargo.shop.spi.product.GoodsVisionPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * zip-plan 端点的**装配**（TDD-商品压缩包导入 AC11/AC12）：清单交给 port、port 的结果过 ZipPlanning、
 * port 不给就全按规则。逐文件校验本身在 {@link ZipPlanningTest}。
 */
class BizGoodsZipPlanTest {

    private final GoodsVisionPort vision = mock(GoodsVisionPort.class);

    private BizGoodsController controller() {
        return new BizGoodsController(
                mock(ai.neargo.shop.product.service.MerchantGoodsService.class),
                mock(ai.neargo.shop.product.service.CategoryService.class),
                vision,
                mock(ai.neargo.shop.product.service.SpuStdService.class),
                mock(ai.neargo.shop.product.service.SpecLibraryService.class),
                mock(ai.neargo.shop.product.service.GoodsRevisionService.class));
    }

    private static BizGoodsController.ZipPlanReq req(List<BizGoodsController.ZipFileReq> files) {
        return new BizGoodsController.ZipPlanReq("脆柿子", null, files, null, List.of(
                new GoodsVisionPort.ZipPick("长图/1.png", "MAIN", 1, false)));
    }

    @Test
    @DisplayName("★★★ 模型的分法被采用：长图进详情")
    void usesPortMapping() {
        when(vision.mapZip(eq("脆柿子"), any(), anyList(), any())).thenReturn(List.of(
                new GoodsVisionPort.ZipPick("长图/1.png", "DETAIL", 1, false)));
        var vo = controller().zipPlan(req(List.of(new BizGoodsController.ZipFileReq("长图/1.png", 750, 2400))));
        assertThat(vo.source()).isEqualTo("LLM");
        assertThat(vo.items()).extracting(BizGoodsController.ZipPlanVO.Item::target).containsExactly("DETAIL");
    }

    @Test
    @DisplayName("★★★ port 不给（未启用 / 超时）→ 按端上带来的规则，导入照样能用")
    void portNullFallsBackToRuleHint() {
        var vo = controller().zipPlan(req(List.of(new BizGoodsController.ZipFileReq("长图/1.png", 750, 2400))));
        assertThat(vo.source()).isEqualTo("RULE");
        assertThat(vo.items()).extracting(BizGoodsController.ZipPlanVO.Item::target).containsExactly("MAIN");
    }

    @Test
    @DisplayName("空清单不调模型")
    void emptyDoesNotCallPort() {
        var vo = controller().zipPlan(req(List.of()));
        assertThat(vo.items()).isEmpty();
        verify(vision, never()).mapZip(any(), any(), anyList(), any());
    }

    @Test
    @DisplayName("超过 200 个文件拒掉 —— 那不是一件商品的图")
    void tooManyFilesRejected() {
        var files = IntStream.range(0, 201)
                .mapToObj(i -> new BizGoodsController.ZipFileReq(i + ".jpg", 800, 800)).toList();
        assertThatThrownBy(() -> controller().zipPlan(req(files))).isInstanceOf(BizException.class);
        verify(vision, never()).mapZip(any(), any(), anyList(), any());
    }
}
