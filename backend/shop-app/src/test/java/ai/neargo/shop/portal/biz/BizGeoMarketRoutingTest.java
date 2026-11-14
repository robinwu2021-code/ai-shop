package ai.neargo.shop.portal.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.platform.EstateCacheService;
import ai.neargo.shop.platform.GeoService;
import ai.neargo.shop.platform.port.GeoRouter;
import ai.neargo.shop.spi.platform.GeoPort;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

/**
 * TDD-双地图提供方与门店选点 AC3：{@code /biz/geo/tips} 与 {@code /biz/geo/reverse} 带 {@code market} 时按地区走
 * 对应提供方（不可用 = 空结果，不报错）；不带时保持今天的高德行为。
 */
class BizGeoMarketRoutingTest {

    private final GeoService legacy = mock(GeoService.class);
    private final GeoRouter router = mock(GeoRouter.class);
    private final GeoPort google = mock(GeoPort.class);
    private final GeoPort amap = mock(GeoPort.class);
    private final BizGeoController controller =
            new BizGeoController(legacy, mock(EstateCacheService.class), router);

    private static GeoPort.Tip tip(String name, String coordSys) {
        return new GeoPort.Tip(name, "addr", null, 25_033964, 121_564472, null, coordSys);
    }

    @Test
    @DisplayName("tips 不带 market：走原来的高德服务")
    void tipsWithoutMarketKeepTheLegacyPath() {
        when(legacy.tips("小区", "杭州")).thenReturn(List.of(tip("西湖小区", GeoPort.GCJ02)));
        assertThat(controller.tips("小区", "杭州", null)).extracting(GeoPort.Tip::name).containsExactly("西湖小区");
        verify(router, never()).portFor(any());
    }

    @Test
    @DisplayName("tips 带 market：走该地区的提供方，条目带该家坐标系")
    void tipsWithMarketRouteToTheMarketsProvider() {
        when(router.providerFor("TW")).thenReturn(GeoRouter.GOOGLE);
        when(router.portFor("TW")).thenReturn(Optional.of(google));
        when(google.tips("台北101", null)).thenReturn(List.of(tip("台北101", null)));

        var tips = controller.tips("台北101", null, "TW");
        assertThat(tips).extracting(GeoPort.Tip::name).containsExactly("台北101");
        assertThat(tips.getFirst().coordSys()).isEqualTo(GeoPort.WGS84);
        verify(legacy, never()).tips(any(), any());
    }

    @Test
    @DisplayName("tips 带 market 但该地区地图不可用：空表，不退回高德")
    void tipsForAnUnavailableMarketAreEmpty() {
        when(router.providerFor("AE")).thenReturn(GeoRouter.GOOGLE);
        when(router.portFor("AE")).thenReturn(Optional.empty());
        assertThat(controller.tips("Marina", null, "AE")).isEmpty();
        verify(legacy, never()).tips(any(), any());
    }

    @Test
    @DisplayName("reverse 带 market=TW：Google 逆地理，坐标原样（wgs84），coordSys=wgs84")
    void reverseWithGoogleMarket() {
        when(router.providerFor("TW")).thenReturn(GeoRouter.GOOGLE);
        when(router.portFor("TW")).thenReturn(Optional.of(google));
        when(google.toGcj02(25_033964, 121_564472, GeoPort.WGS84)).thenReturn(new int[]{25_033964, 121_564472});
        when(google.reverse(25_033964, 121_564472)).thenReturn(Optional.of(new GeoPort.Reverse(
                "台北101", "110台灣台北市信義區信義路五段7號", null, null, "台北市", 25_033964, 121_564472,
                GeoPort.Reverse.KIND_POI)));

        var r = controller.reverse(25.033964, 121.564472, null, "TW");
        assertThat(r.recommend()).isEqualTo("台北101");
        assertThat(r.address()).isEqualTo("110台灣台北市信義區信義路五段7號");
        assertThat(r.adcode()).isNull();
        assertThat(List.of(r.latE6(), r.lngE6())).containsExactly(25_033964, 121_564472);
        assertThat(r.coordSys()).isEqualTo(GeoPort.WGS84);
    }

    @Test
    @DisplayName("reverse 带 market=CN：高德，按 sys 归一到 gcj02，coordSys=gcj02")
    void reverseWithAmapMarketNormalisesToGcj02() {
        when(router.providerFor("CN")).thenReturn(GeoRouter.AMAP);
        when(router.portFor("CN")).thenReturn(Optional.of(amap));
        when(amap.toGcj02(30_000000, 120_000000, GeoPort.WGS84)).thenReturn(new int[]{29_997000, 120_004000});
        when(amap.reverse(29_997000, 120_004000)).thenReturn(Optional.of(new GeoPort.Reverse(
                "西湖小区", "浙江省杭州市", "330106", "北山街道", "杭州市", 29_997000, 120_004000,
                GeoPort.Reverse.KIND_AOI)));

        var r = controller.reverse(30.0, 120.0, GeoPort.WGS84, "CN");
        assertThat(r.recommend()).isEqualTo("西湖小区");
        assertThat(r.adcode()).isEqualTo("330106");
        assertThat(List.of(r.latE6(), r.lngE6())).containsExactly(29_997000, 120_004000);
        assertThat(r.coordSys()).isEqualTo(GeoPort.GCJ02);
    }

    @Test
    @DisplayName("reverse 带 market 但不可用或查不到：空结果，不抛 10503")
    void reverseForAnUnavailableMarketIsEmpty() {
        when(router.providerFor("AE")).thenReturn(GeoRouter.GOOGLE);
        when(router.portFor("AE")).thenReturn(Optional.empty());
        var r = controller.reverse(25.2, 55.27, null, "AE");
        assertThat(r.recommend()).isEmpty();
        assertThat(r.address()).isEmpty();
        assertThat(r.coordSys()).isNull();

        when(router.providerFor("TW")).thenReturn(GeoRouter.GOOGLE);
        when(router.portFor("TW")).thenReturn(Optional.of(google));
        when(google.toGcj02(anyInt(), anyInt(), any())).thenReturn(new int[]{25_000000, 121_000000});
        when(google.reverse(anyInt(), anyInt())).thenReturn(Optional.empty());
        assertThat(controller.reverse(25.0, 121.0, null, "TW").recommend()).isEmpty();
        verify(legacy, never()).reverse(anyDouble(), anyDouble(), any());
    }

    @Test
    @DisplayName("reverse 带 market 坐标越界：10400")
    void reverseRejectsOutOfRangeCoordinates() {
        assertThatThrownBy(() -> controller.reverse(91, 0, null, "TW")).isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("reverse 不带 market：原来的高德服务（带 coordSys=gcj02）")
    void reverseWithoutMarketKeepsTheLegacyPath() {
        when(legacy.reverse(30.0, 120.0, GeoPort.GCJ02)).thenReturn(
                new GeoService.ReverseVO("西湖小区", "浙江省杭州市", "330106", "北山街道", 30_000000, 120_000000));
        var r = controller.reverse(30.0, 120.0, null, null);
        assertThat(r.recommend()).isEqualTo("西湖小区");
        assertThat(r.coordSys()).isEqualTo(GeoPort.GCJ02);
        verify(router, never()).portFor(any());
    }
}
