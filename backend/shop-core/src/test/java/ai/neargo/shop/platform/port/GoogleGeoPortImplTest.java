package ai.neargo.shop.platform.port;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.spi.platform.GeoPort;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * TDD-双地图提供方与门店选点 AC4：Google 实现走 Places API (New) Text Search 与 Geocoding API，
 * 全部用罐头 JSON，<b>不连网</b>。断言落在两处：发出去的请求长什么样（计费档位由 FieldMask 决定、
 * 密钥只在请求里），以及回来的 JSON 怎么映射到 {@link GeoPort} 的记录。
 */
class GoogleGeoPortImplTest {

    private static final String KEY = "test-google-server-key";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<GoogleGeoPortImpl.Call> calls = new ArrayList<>();

    private GoogleGeoPortImpl port(String key, String response) {
        return new GoogleGeoPortImpl(key, JSON, call -> {
            calls.add(call);
            return response;
        }, null);
    }

    private static final String PLACES = """
            {"places":[
              {"id":"p1","displayName":{"text":"台北101","languageCode":"zh-TW"},
               "formattedAddress":"110台灣台北市信義區信義路五段7號","location":{"latitude":25.0339639,"longitude":121.5644722}},
              {"id":"p2","displayName":{"text":"沒有座標的地點"},"formattedAddress":"某處"},
              {"id":"p3","displayName":{"text":"信義區公所"},"formattedAddress":"110台北市信義區市府路1號",
               "location":{"latitude":25.0330,"longitude":121.5654}}
            ]}""";

    private static final String REVERSE = """
            {"status":"OK","results":[
              {"formatted_address":"110台灣台北市信義區信義路五段7號","types":["point_of_interest","establishment"],
               "address_components":[
                 {"long_name":"台北101","short_name":"台北101","types":["point_of_interest","establishment"]},
                 {"long_name":"7","short_name":"7","types":["street_number"]},
                 {"long_name":"信義路五段","short_name":"信義路五段","types":["route"]},
                 {"long_name":"信義區","short_name":"信義區","types":["administrative_area_level_2","political"]},
                 {"long_name":"台北市","short_name":"台北市","types":["administrative_area_level_1","political"]},
                 {"long_name":"台灣","short_name":"TW","types":["country","political"]}]},
              {"formatted_address":"110台灣台北市信義區","types":["administrative_area_level_2"],"address_components":[]}
            ]}""";

    @Test
    @DisplayName("没配服务端密钥：available=false，任何查询都不发请求、回空")
    void withoutKeyNothingIsCalled() {
        var p = port("  ", PLACES);
        assertThat(p.available()).isFalse();
        assertThat(p.tips("台北101", null)).isEmpty();
        assertThat(p.reverse(25_033964, 121_564472)).isEmpty();
        assertThat(p.geocode("台北101", null)).isEmpty();
        assertThat(p.around("便利商店", 25_033964, 121_564472, 500, null)).isEmpty();
        assertThat(calls).isEmpty();
    }

    @Test
    @DisplayName("tips：POST searchText，密钥只在 X-Goog-Api-Key，FieldMask 只要四个字段，语言 zh-TW；没坐标的条目丢掉")
    void tipsUseTextSearchAndMapCoordinatesToE6() {
        var p = port(KEY, PLACES);
        List<GeoPort.Tip> tips = p.tips("台北101", null);

        assertThat(calls).hasSize(1);
        var call = calls.getFirst();
        assertThat(call.method()).isEqualTo("POST");
        assertThat(call.uri().toString()).isEqualTo("https://places.googleapis.com/v1/places:searchText");
        assertThat(call.headers()).containsEntry("X-Goog-Api-Key", KEY)
                .containsEntry("X-Goog-FieldMask",
                        "places.id,places.displayName,places.formattedAddress,places.location");
        JsonNode body = JSON.readTree(call.body());
        assertThat(body.path("textQuery").asString()).isEqualTo("台北101");
        assertThat(body.path("languageCode").asString()).isEqualTo("zh-TW");
        assertThat(call.uri().toString()).doesNotContain(KEY);
        assertThat(call.body()).doesNotContain(KEY);

        assertThat(tips).hasSize(2);
        var first = tips.getFirst();
        assertThat(first.name()).isEqualTo("台北101");
        assertThat(first.address()).isEqualTo("110台灣台北市信義區信義路五段7號");
        assertThat(first.latE6()).isEqualTo(25_033964);
        assertThat(first.lngE6()).isEqualTo(121_564472);
        assertThat(first.adcode()).isNull();
        assertThat(first.coordSys()).isEqualTo(GeoPort.WGS84);
        assertThat(tips.get(1).name()).isEqualTo("信義區公所");
    }

    @Test
    @DisplayName("tips：关键词太短不发请求（与高德同口径）；城市并进查询词；地区版带 regionCode")
    void shortKeywordCityAndRegion() {
        var p = port(KEY, PLACES);
        assertThat(p.tips(" 台 ", null)).isEmpty();
        assertThat(calls).isEmpty();

        p.forRegion("TW").tips("鼎泰豐", "台北市");
        JsonNode body = JSON.readTree(calls.getFirst().body());
        assertThat(body.path("textQuery").asString()).isEqualTo("鼎泰豐 台北市");
        assertThat(body.path("regionCode").asString()).isEqualTo("TW");
    }

    @Test
    @DisplayName("reverse：GET Geocoding，最具体的结果取名；大陆专属字段（adcode/township）留 null")
    void reverseMapsTheMostSpecificResult() {
        var p = port(KEY, REVERSE);
        GeoPort.Reverse r = p.reverse(25_033964, 121_564472).orElseThrow();

        var call = calls.getFirst();
        assertThat(call.method()).isEqualTo("GET");
        assertThat(call.uri().toString())
                .startsWith("https://maps.googleapis.com/maps/api/geocode/json?latlng=25.033964,121.564472")
                .contains("language=zh-TW");
        assertThat(r.recommend()).isEqualTo("台北101");
        assertThat(r.kind()).isEqualTo(GeoPort.Reverse.KIND_POI);
        assertThat(r.address()).isEqualTo("110台灣台北市信義區信義路五段7號");
        assertThat(r.city()).as("没有 locality 时取一级行政区").isEqualTo("台北市");
        assertThat(r.adcode()).isNull();
        assertThat(r.township()).isNull();
        assertThat(List.of(r.latE6(), r.lngE6())).containsExactly(25_033964, 121_564472);
    }

    @Test
    @DisplayName("reverse：没有兴趣点时退回街道门牌，kind=STREET")
    void reverseFallsBackToStreet() {
        var p = port(KEY, """
                {"status":"OK","results":[{"formatted_address":"Sheikh Zayed Rd, Dubai","types":["route"],
                 "address_components":[{"long_name":"Sheikh Zayed Road","types":["route"]},
                 {"long_name":"Dubai","types":["locality","political"]}]}]}""");
        var r = p.reverse(25_197000, 55_274000).orElseThrow();
        assertThat(r.kind()).isEqualTo(GeoPort.Reverse.KIND_STREET);
        assertThat(r.recommend()).isEqualTo("Sheikh Zayed Rd, Dubai");
        assertThat(r.city()).isEqualTo("Dubai");
    }

    @Test
    @DisplayName("失败一律回空：HTTP 抛错（错误里带着 URL 和密钥）、status 非 OK、JSON 坏掉")
    void failuresAreEmptyAndNeverThrow() {
        var throwing = new GoogleGeoPortImpl(KEY, JSON, call -> {
            throw new IllegalStateException("boom " + call.uri());
        }, null);
        assertThat(throwing.tips("台北101", null)).isEmpty();
        assertThat(throwing.reverse(25_033964, 121_564472)).isEmpty();
        assertThat(throwing.geocode("台北101", null)).isEmpty();

        assertThat(port(KEY, "{\"status\":\"REQUEST_DENIED\",\"error_message\":\"bad key\"}")
                .reverse(25_033964, 121_564472)).isEmpty();
        assertThat(port(KEY, "not json").tips("台北101", null)).isEmpty();
        assertThat(port(KEY, "{\"error\":{\"code\":403}}").tips("台北101", null)).isEmpty();
    }

    @Test
    @DisplayName("toGcj02 原样返回：Google 是 wgs84，坐标系随门店记录，不做转换")
    void toGcj02IsIdentity() {
        var p = port(KEY, PLACES);
        assertThat(p.toGcj02(25_033964, 121_564472, GeoPort.WGS84)).containsExactly(25_033964, 121_564472);
        assertThat(calls).isEmpty();
    }

    @Test
    @DisplayName("geocode 用 Geocoding；ROOFTOP 才算找得到门")
    void geocodeUsesGeocoding() {
        var p = port(KEY, """
                {"status":"OK","results":[{"formatted_address":"110台北市信義區市府路1號",
                 "geometry":{"location":{"lat":25.0375,"lng":121.5637},"location_type":"ROOFTOP"}}]}""");
        var g = p.geocode("市府路1號", "台北市").orElseThrow();
        assertThat(g.ok()).isTrue();
        assertThat(g.formatted()).isEqualTo("110台北市信義區市府路1號");
        assertThat(List.of(g.latE6(), g.lngE6())).containsExactly(25_037500, 121_563700);
        assertThat(g.adcode()).isNull();
        assertThat(calls.getFirst().uri().toString()).contains("address=");
    }

    @Test
    @DisplayName("around 用 Text Search + 圆形偏置")
    void aroundUsesLocationBias() {
        var p = port(KEY, PLACES);
        assertThat(p.around("便利商店", 25_033964, 121_564472, 800, "120300")).hasSize(2);
        JsonNode circle = JSON.readTree(calls.getFirst().body()).path("locationBias").path("circle");
        assertThat(circle.path("radius").asDouble()).isEqualTo(800.0);
        assertThat(circle.path("center").path("latitude").asDouble()).isEqualTo(25.033964);
    }
}
