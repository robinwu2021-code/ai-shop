package ai.neargo.shop.platform.port;

import ai.neargo.shop.spi.platform.GeoPort;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link GeoPort} 的 Google 实现：Places API (New) Text Search + Geocoding API，<b>服务端代理</b>
 * （TDD-双地图提供方与门店选点 AC4）。调用口径移植自 sharehub {@code ops-web/lib/gmaps.ts}，从浏览器搬到了服务端，
 * 于是服务端 key（{@code shop.geo.google-key}）不出现在任何响应里。
 *
 * <p><b>为什么不是 Spring bean</b>：现有十几处按类型注入 {@code GeoPort} 的地方（社区、地址校验、建店资料）
 * 要的都是高德。再注册一个同类型 bean，按类型注入、{@code ObjectProvider#getIfAvailable}、测试里的
 * {@code @MockitoBean GeoPort} 都会面对两个候选 —— 有的报错，有的静默换人。所以它只由
 * {@link GeoRouter} 持有，只有「按地区选提供方」的那几条路径能拿到它。
 *
 * <p><b>为什么用 searchText 不用 autocomplete</b>（同 gmaps.ts）：searchText 一次就带回坐标；
 * autocomplete 只给 placeId，每条还要再调一次详情（N 倍计费）。FieldMask 只要四个字段 —— 它直接决定计费档位。
 *
 * <p><b>坐标是 wgs84</b>，{@link #toGcj02} 原样返回：Google 底图就是 wgs84，转成 gcj02 反而画偏；
 * 门店随坐标记 {@code coord_sys}。
 *
 * <p>任何失败（网络、配额、key 被拒、JSON 坏）都回空：地图是增强，不挡建店。错误不打日志 ——
 * 异常信息里带着带 key 的 URL。
 */
public class GoogleGeoPortImpl implements GeoPort {

    static final String SEARCH_TEXT = "https://places.googleapis.com/v1/places:searchText";
    static final String GEOCODE = "https://maps.googleapis.com/maps/api/geocode/json";
    static final String FIELD_MASK = "places.id,places.displayName,places.formattedAddress,places.location";
    static final String LANGUAGE = "zh-TW";
    private static final int MAX_TIPS = 10;
    /** Places 的 locationBias 圆半径上限 */
    private static final int MAX_BIAS_RADIUS_M = 50_000;
    /** 地理编码到这些精度才算「找得到门」（与高德 OK_LEVELS 同义） */
    private static final List<String> OK_LOCATION_TYPES = List.of("ROOFTOP", "RANGE_INTERPOLATED");
    /** 逆地理时这些类型的组件算「建筑 / 兴趣点」，对应高德的 POI 档 */
    private static final List<String> POI_TYPES = List.of("point_of_interest", "establishment", "premise");

    /** 一次 HTTP 往返，返回响应体。抛错 = 失败，调用方一律按「没有结果」处理。测试换成罐头 JSON */
    @FunctionalInterface
    public interface Transport {
        String exchange(Call call) throws Exception;
    }

    /** 一次请求。<b>headers / uri 里可能带 key</b>，不要打印 */
    public record Call(String method, URI uri, Map<String, String> headers, String body) {
    }

    private final String key;
    private final ObjectMapper json;
    private final Transport transport;
    /** CLDR 地区码（TW / AE），偏置搜索结果；null = 不偏置 */
    private final String regionCode;

    public GoogleGeoPortImpl(String key, ObjectMapper json) {
        this(key, json, httpTransport(), null);
    }

    GoogleGeoPortImpl(String key, ObjectMapper json, Transport transport, String regionCode) {
        this.key = key == null ? "" : key.trim();
        this.json = json;
        this.transport = transport;
        this.regionCode = regionCode == null || regionCode.isBlank() ? null
                : regionCode.trim().toUpperCase(Locale.ROOT);
    }

    /** 同一把 key、同一条通道，搜索偏置到某个地区（不偏置的话「Marina」会先命中别的国家） */
    public GoogleGeoPortImpl forRegion(String market) {
        return new GoogleGeoPortImpl(key, json, transport, market);
    }

    @Override
    public boolean available() {
        return !key.isBlank();
    }

    @Override
    public int[] toGcj02(int latE6, int lngE6, String coordSys) {
        return new int[]{latE6, lngE6};
    }

    @Override
    public Optional<Reverse> reverse(int latE6, int lngE6) {
        if (!available()) {
            return Optional.empty();
        }
        JsonNode r0 = firstGeocodeResult("latlng=" + degrees(latE6) + "," + degrees(lngE6));
        if (r0 == null) {
            return Optional.empty();
        }
        String formatted = r0.path("formatted_address").asString("");
        JsonNode components = r0.path("address_components");
        String poi = componentOf(components, POI_TYPES);
        String recommend = poi != null ? poi : formatted;
        String kind = poi != null ? Reverse.KIND_POI : Reverse.KIND_STREET;
        String city = componentOf(components, List.of("locality"));
        if (city == null) {
            city = componentOf(components, List.of("administrative_area_level_1"));
        }
        // adcode / township 是大陆行政区口径，Google 没有对应物，留 null
        return Optional.of(new Reverse(recommend, formatted, null, null, city, latE6, lngE6, kind));
    }

    @Override
    public Optional<Geocode> geocode(String address, String city) {
        if (!available() || address == null || address.isBlank()) {
            return Optional.empty();
        }
        String q = address.trim() + (city == null || city.isBlank() ? "" : " " + city.trim());
        JsonNode root = get("address=" + enc(q) + (regionCode == null ? "" : "&region=" + enc(regionCode)));
        if (root == null) {
            return Optional.empty();
        }
        JsonNode results = root.path("results");
        if (!results.isArray() || results.isEmpty()) {
            return Optional.of(new Geocode(false, "", "", 0, 0, null));
        }
        JsonNode g = results.get(0);
        JsonNode geometry = g.path("geometry");
        String level = geometry.path("location_type").asString("");
        JsonNode loc = geometry.path("location");
        boolean hasLoc = loc.path("lat").isNumber() && loc.path("lng").isNumber();
        return Optional.of(new Geocode(hasLoc && OK_LOCATION_TYPES.contains(level), level,
                g.path("formatted_address").asString(""),
                hasLoc ? e6(loc.path("lat").asDouble()) : 0, hasLoc ? e6(loc.path("lng").asDouble()) : 0, null));
    }

    @Override
    public List<Tip> tips(String keyword, String city) {
        String kw = keyword == null ? "" : keyword.trim();
        if (!available() || kw.length() < 2) {
            return List.of();
        }
        ObjectNode body = searchBody(kw + (city == null || city.isBlank() ? "" : " " + city.trim()));
        return search(body);
    }

    /** {@code types} 是高德 POI 类目码，Google 没有对应物，忽略；Places 只有圆形「偏置」，没有圆形「限定」 */
    @Override
    public List<Tip> around(String keyword, int latE6, int lngE6, int radiusM, String types) {
        String kw = keyword == null ? "" : keyword.trim();
        if (!available() || kw.isEmpty()) {
            return List.of();
        }
        ObjectNode body = searchBody(kw);
        ObjectNode circle = body.putObject("locationBias").putObject("circle");
        circle.putObject("center").put("latitude", latE6 / 1e6).put("longitude", lngE6 / 1e6);
        circle.put("radius", (double) Math.min(MAX_BIAS_RADIUS_M, Math.max(1, radiusM)));
        return search(body);
    }

    // ---------------------------------------------------------------- 底层

    private ObjectNode searchBody(String textQuery) {
        ObjectNode body = json.createObjectNode();
        body.put("textQuery", textQuery);
        body.put("languageCode", LANGUAGE);
        body.put("maxResultCount", MAX_TIPS);
        if (regionCode != null) {
            body.put("regionCode", regionCode);
        }
        return body;
    }

    private List<Tip> search(ObjectNode body) {
        JsonNode root;
        try {
            String resp = transport.exchange(new Call("POST", URI.create(SEARCH_TEXT), Map.of(
                    "Content-Type", "application/json",
                    "X-Goog-Api-Key", key,
                    "X-Goog-FieldMask", FIELD_MASK), json.writeValueAsString(body)));
            root = json.readTree(resp);
        } catch (Exception e) {
            return List.of();
        }
        List<Tip> out = new ArrayList<>();
        for (JsonNode p : root.path("places")) {
            JsonNode loc = p.path("location");
            String name = p.path("displayName").path("text").asString("");
            // 没坐标的条目对选点没用
            if (name.isBlank() || !loc.path("latitude").isNumber() || !loc.path("longitude").isNumber()) {
                continue;
            }
            out.add(new Tip(name, p.path("formattedAddress").asString(""), null,
                    e6(loc.path("latitude").asDouble()), e6(loc.path("longitude").asDouble()), null, WGS84));
            if (out.size() >= MAX_TIPS) {
                break;
            }
        }
        return out;
    }

    /** Geocoding 的第一条（最具体的那条）；失败或 status 非 OK 返回 null */
    private JsonNode firstGeocodeResult(String query) {
        JsonNode root = get(query);
        if (root == null) {
            return null;
        }
        JsonNode results = root.path("results");
        return results.isArray() && !results.isEmpty() ? results.get(0) : null;
    }

    /** status 为 OK 或 ZERO_RESULTS 时返回 JSON 根，其余（含网络错误）返回 null */
    private JsonNode get(String query) {
        try {
            URI uri = URI.create(GEOCODE + "?" + query + "&language=" + LANGUAGE + "&key=" + enc(key));
            JsonNode root = json.readTree(transport.exchange(new Call("GET", uri, Map.of(), null)));
            String status = root.path("status").asString("");
            return "OK".equals(status) || "ZERO_RESULTS".equals(status) ? root : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String componentOf(JsonNode components, List<String> types) {
        for (JsonNode c : components) {
            for (JsonNode t : c.path("types")) {
                if (types.contains(t.asString(""))) {
                    String name = c.path("long_name").asString("");
                    if (!name.isBlank()) {
                        return name;
                    }
                }
            }
        }
        return null;
    }

    private static String degrees(int e6) {
        return String.format(Locale.ROOT, "%.6f", e6 / 1e6);
    }

    private static int e6(double degrees) {
        return (int) Math.round(degrees * 1e6);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static Transport httpTransport() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        return call -> {
            HttpRequest.Builder b = HttpRequest.newBuilder(call.uri()).timeout(Duration.ofSeconds(5));
            call.headers().forEach(b::header);
            b.method(call.method(), call.body() == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(call.body(), StandardCharsets.UTF_8));
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return resp.body();
        };
    }
}
