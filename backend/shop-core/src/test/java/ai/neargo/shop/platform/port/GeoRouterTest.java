package ai.neargo.shop.platform.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.spi.platform.GeoPort;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

/**
 * TDD-双地图提供方与门店选点 AC1 / AC2：地区 → 提供方，提供方不可用时<b>不跨家兜底</b>。
 */
class GeoRouterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static GeoPort amap(boolean available) {
        GeoPort p = mock(GeoPort.class);
        when(p.available()).thenReturn(available);
        return p;
    }

    private static GoogleGeoPortImpl google(boolean available) {
        return new GoogleGeoPortImpl(available ? "server-key" : "", JSON, call -> "{}", null);
    }

    private static GeoRouter router(GeoPort amap, GoogleGeoPortImpl google, String byMarket,
                                    String amapJs, String amapSec, String googleJs) {
        return new GeoRouter(amap, google, byMarket, amapJs, amapSec, googleJs);
    }

    @Test
    @DisplayName("AC1 缺省 CN→AMAP，TW/AE→GOOGLE；大小写不敏感；没配的地区为 null")
    void defaultMappingIsCaseInsensitive() {
        var r = router(amap(true), google(true), GeoRouter.DEFAULT_PROVIDER_BY_MARKET, "", "", "");
        assertThat(r.providerFor("CN")).isEqualTo(GeoRouter.AMAP);
        assertThat(r.providerFor("tw")).isEqualTo(GeoRouter.GOOGLE);
        assertThat(r.providerFor(" ae ")).isEqualTo(GeoRouter.GOOGLE);
        assertThat(r.providerFor("JP")).isNull();
        assertThat(r.providerFor(null)).isNull();
    }

    @Test
    @DisplayName("AC1 配置可覆盖：台湾临时走高德；写坏的条目与不认识的提供方忽略")
    void mappingIsConfigurable() {
        var r = router(amap(true), google(true), " cn:amap , TW:Amap,AE,XX:BAIDU", "", "", "");
        assertThat(r.providerFor("TW")).isEqualTo(GeoRouter.AMAP);
        assertThat(r.providerFor("CN")).isEqualTo(GeoRouter.AMAP);
        assertThat(r.providerFor("AE")).isNull();
        assertThat(r.providerFor("XX")).isNull();
    }

    @Test
    @DisplayName("portFor 只给该地区自己的提供方，且只在 available 时给；不可用不兜底到另一家")
    void portForNeverFallsBack() {
        GeoPort amapOn = amap(true);
        var r = router(amapOn, google(false), GeoRouter.DEFAULT_PROVIDER_BY_MARKET, "", "", "");
        assertThat(r.portFor("CN")).containsSame(amapOn);
        assertThat(r.portFor("TW")).as("Google 没配密钥时台湾没有地图，不退回高德").isEmpty();

        var r2 = router(amap(false), google(true), GeoRouter.DEFAULT_PROVIDER_BY_MARKET, "", "", "");
        assertThat(r2.portFor("CN")).as("高德没配密钥时大陆没有地图，不退回 Google").isEmpty();
        assertThat(r2.portFor("TW")).get().isInstanceOf(GoogleGeoPortImpl.class)
                .matches(GeoPort::available);
    }

    @Test
    @DisplayName("AC2 端上地图：服务端可用且该家 JS key 配了才给；安全密钥只有高德有")
    void clientMapNeedsBothServerPortAndJsKey() {
        var r = router(amap(true), google(true), GeoRouter.DEFAULT_PROVIDER_BY_MARKET, "amap-js", "amap-sec",
                "google-js");
        var cn = r.clientMapFor("CN").orElseThrow();
        assertThat(cn.provider()).isEqualTo(GeoRouter.AMAP);
        assertThat(cn.jsKey()).isEqualTo("amap-js");
        assertThat(cn.jsSecurity()).isEqualTo("amap-sec");
        assertThat(cn.coordSys()).isEqualTo(GeoPort.GCJ02);
        var tw = r.clientMapFor("TW").orElseThrow();
        assertThat(tw.provider()).isEqualTo(GeoRouter.GOOGLE);
        assertThat(tw.jsKey()).isEqualTo("google-js");
        assertThat(tw.jsSecurity()).isNull();
        assertThat(tw.coordSys()).isEqualTo(GeoPort.WGS84);

        var noJs = router(amap(true), google(true), GeoRouter.DEFAULT_PROVIDER_BY_MARKET, " ", "amap-sec", "");
        assertThat(noJs.clientMapFor("CN")).as("没有端上 JS key").isEmpty();
        assertThat(noJs.clientMapFor("TW")).isEmpty();

        var noServer = router(amap(false), google(false), GeoRouter.DEFAULT_PROVIDER_BY_MARKET, "amap-js", "",
                "google-js");
        assertThat(noServer.clientMapFor("CN")).as("服务端搜索不可用").isEmpty();
        assertThat(noServer.clientMapFor("TW")).isEmpty();

        var blankSecurity = router(amap(true), google(true), GeoRouter.DEFAULT_PROVIDER_BY_MARKET, "amap-js", "  ",
                "");
        assertThat(blankSecurity.clientMapFor("CN").orElseThrow().jsSecurity()).isNull();
    }

    @Test
    @DisplayName("coordSys 由提供方决定：AMAP=gcj02，GOOGLE=wgs84")
    void coordSysOfProvider() {
        assertThat(GeoRouter.coordSysOf(GeoRouter.AMAP)).isEqualTo(GeoPort.GCJ02);
        assertThat(GeoRouter.coordSysOf(GeoRouter.GOOGLE)).isEqualTo(GeoPort.WGS84);
        assertThat(GeoRouter.coordSysOf(null)).isNull();
    }
}
