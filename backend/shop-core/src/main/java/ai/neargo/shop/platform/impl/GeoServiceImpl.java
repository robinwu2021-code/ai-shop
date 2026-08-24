package ai.neargo.shop.platform.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.platform.GeoService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 高德 Web 服务（restapi.amap.com v3）。换厂商只动这一个类。
 *
 * <p>高德的坐标串一律 <b>lng,lat</b>，与本仓库 latE6/lngE6 的习惯相反 —— 所有拼串与解析都在这里翻一次，
 * 外面不再出现「经纬倒了」这种错。
 * <p>不可用（没配密钥 / 网络抖 / 厂商报错）统一按「没有结果」处理：调用方跳过校验。
 * 只有端上的 {@link #reverse(double, double, String)} 会把不可用说出来（端上要据此藏按钮）。
 */
@Service
public class GeoServiceImpl implements GeoService {

    private static final String BASE = "https://restapi.amap.com/v3";
    /** 输入提示限住宅区/住宅小区/村庄级地名 —— 提报小区与取货点选址只关心这三类 */
    private static final String TIP_TYPES = "120300|120302|190108";
    /** 地理编码到这些级别才算「找得到门」 */
    private static final List<String> OK_LEVELS = List.of("门牌号", "兴趣点", "门址", "楼栋", "单元号", "住宅区", "村庄");

    private final String key;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public GeoServiceImpl(@Value("${shop.geo.amap-key:}") String key, ObjectMapper json) {
        this.key = key == null ? "" : key.trim();
        this.json = json;
    }

    @Override
    public boolean available() {
        return !key.isBlank();
    }

    @Override
    public int[] toGcj02(int latE6, int lngE6, String coordSys) {
        if (!available() || !WGS84.equalsIgnoreCase(coordSys)) {
            return new int[]{latE6, lngE6};
        }
        JsonNode r = call("/assistant/coordinate/convert?coordsys=gps&locations=" + lnglat(latE6, lngE6));
        if (r == null) {
            return new int[]{latE6, lngE6};
        }
        int[] p = parseLngLat(r.path("locations").asText(""));
        return p == null ? new int[]{latE6, lngE6} : p;
    }

    @Override
    public Optional<Reverse> reverse(int latE6, int lngE6) {
        if (!available()) {
            return Optional.empty();
        }
        JsonNode r = call("/geocode/regeo?extensions=all&radius=200&location=" + lnglat(latE6, lngE6));
        if (r == null) {
            return Optional.empty();
        }
        JsonNode rg = r.path("regeocode");
        JsonNode ac = rg.path("addressComponent");
        String formatted = rg.path("formatted_address").asText("");
        // 人话版：优先最近的小区/楼盘名 + 门牌，没有就用标准地址
        String recommend = "";
        JsonNode aois = rg.path("aois");
        if (aois.isArray() && !aois.isEmpty()) {
            recommend = aois.get(0).path("name").asText("");
        }
        if (recommend.isBlank()) {
            JsonNode sn = ac.path("streetNumber");
            String street = sn.path("street").asText("");
            String number = sn.path("number").asText("");
            recommend = (street + number).isBlank() ? formatted : street + number;
        }
        String city = ac.path("city").isArray() ? ac.path("province").asText("") : ac.path("city").asText("");
        return Optional.of(new Reverse(recommend, formatted, ac.path("adcode").asText(""),
                ac.path("township").asText(""), city, latE6, lngE6));
    }

    @Override
    public Optional<Geocode> geocode(String address, String city) {
        if (!available() || address == null || address.isBlank()) {
            return Optional.empty();
        }
        String q = "/geocode/geo?address=" + enc(address.trim()) + (city == null || city.isBlank() ? "" : "&city=" + enc(city));
        JsonNode r = call(q);
        if (r == null) {
            return Optional.empty();
        }
        JsonNode geos = r.path("geocodes");
        if (!geos.isArray() || geos.isEmpty()) {
            return Optional.of(new Geocode(false, "", "", 0, 0, ""));
        }
        JsonNode g = geos.get(0);
        String level = g.path("level").asText("");
        int[] p = parseLngLat(g.path("location").asText(""));
        boolean ok = p != null && OK_LEVELS.contains(level);
        return Optional.of(new Geocode(ok, level, g.path("formatted_address").asText(""),
                p == null ? 0 : p[0], p == null ? 0 : p[1], g.path("adcode").asText("")));
    }

    @Override
    public List<Tip> tips(String keyword, String city) {
        String kw = keyword == null ? "" : keyword.trim();
        if (!available() || kw.length() < 2) {
            return List.of();
        }
        String q = "/assistant/inputtips?type=" + enc(TIP_TYPES) + "&keywords=" + enc(kw)
                + (city == null || city.isBlank() ? "" : "&city=" + enc(city) + "&citylimit=true");
        JsonNode r = call(q);
        if (r == null) {
            return List.of();
        }
        List<Tip> out = new ArrayList<>();
        for (JsonNode t : r.path("tips")) {
            // 没坐标的提示（纯地名词条）对选址没用
            int[] p = parseLngLat(t.path("location").isTextual() ? t.path("location").asText("") : "");
            String name = t.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            out.add(new Tip(name, t.path("address").isTextual() ? t.path("address").asText("") : "",
                    t.path("adcode").asText(""), p == null ? null : p[0], p == null ? null : p[1],
                    t.path("typecode").asText("")));
            if (out.size() >= 10) {
                break;
            }
        }
        return out;
    }

    @Override
    public List<Tip> around(String keyword, int latE6, int lngE6, int radiusM, String types) {
        String kw = keyword == null ? "" : keyword.trim();
        if (!available() || kw.isEmpty()) {
            return List.of();
        }
        String q = "/place/around?keywords=" + enc(kw)
                + (types == null || types.isBlank() ? "" : "&types=" + enc(types))
                + "&location=" + lnglat(latE6, lngE6) + "&radius=" + Math.max(1, radiusM)
                + "&offset=25&extensions=base";
        JsonNode r = call(q);
        if (r == null) {
            return List.of();
        }
        List<Tip> out = new ArrayList<>();
        for (JsonNode p : r.path("pois")) {
            String name = p.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            int[] loc = parseLngLat(p.path("location").isTextual() ? p.path("location").asText("") : "");
            out.add(new Tip(name, p.path("address").isTextual() ? p.path("address").asText("") : "",
                    p.path("adcode").asText(""), loc == null ? null : loc[0], loc == null ? null : loc[1],
                    p.path("typecode").asText("")));
        }
        return out;
    }

    @Override
    public ReverseVO reverse(double lat, double lng, String coordSys) {
        if (!available()) {
            throw BizException.of(ErrorCode.GEO_UNAVAILABLE);
        }
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        int[] p = toGcj02((int) Math.round(lat * 1e6), (int) Math.round(lng * 1e6), coordSys);
        Reverse r = reverse(p[0], p[1]).orElseThrow(() -> BizException.of(ErrorCode.GEO_UNAVAILABLE));
        return new ReverseVO(r.recommend(), r.address(), r.adcode(), r.township(), p[0], p[1]);
    }

    // ---------------------------------------------------------------- 底层

    /** 成功返回 JSON 根；任何失败（网络/status!=1）返回 null，调用方按「没有结果」处理 */
    private JsonNode call(String pathAndQuery) {
        try {
            URI uri = URI.create(BASE + pathAndQuery + "&output=json&key=" + key);
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode root = json.readTree(resp.body());
            return "1".equals(root.path("status").asText("")) ? root : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String lnglat(int latE6, int lngE6) {
        return String.format(java.util.Locale.ROOT, "%.6f,%.6f", lngE6 / 1e6, latE6 / 1e6);
    }

    /** "lng,lat" → {latE6, lngE6}；解析不了返回 null */
    private static int[] parseLngLat(String s) {
        if (s == null || !s.contains(",")) {
            return null;
        }
        try {
            String[] parts = s.split(";")[0].split(",");
            double lng = Double.parseDouble(parts[0].trim());
            double lat = Double.parseDouble(parts[1].trim());
            return new int[]{(int) Math.round(lat * 1e6), (int) Math.round(lng * 1e6)};
        } catch (Exception e) {
            return null;
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
