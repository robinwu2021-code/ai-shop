package ai.neargo.shop.platform.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.platform.GeoService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** 腾讯位置服务 WebService `geocoder/v1`。换厂商只动这一个类 */
@Service
public class GeoServiceImpl implements GeoService {

    private final String tencentKey;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public GeoServiceImpl(@Value("${shop.geo.tencent-key:}") String tencentKey, ObjectMapper json) {
        this.tencentKey = tencentKey;
        this.json = json;
    }

    @Override
    public ReverseVO reverse(double lat, double lng) {
        if (tencentKey == null || tencentKey.isBlank()) {
            throw BizException.of(ErrorCode.GEO_UNAVAILABLE);
        }
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        try {
            URI uri = URI.create("https://apis.map.qq.com/ws/geocoder/v1/?location=" + lat + "," + lng
                    + "&get_poi=0&key=" + tencentKey);
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode root = json.readTree(resp.body());
            if (root.path("status").asInt(-1) != 0) {
                throw BizException.of(ErrorCode.GEO_UNAVAILABLE);
            }
            JsonNode result = root.path("result");
            String recommend = result.path("formatted_addresses").path("recommend").asText("");
            String address = result.path("address").asText("");
            return new ReverseVO(recommend.isBlank() ? address : recommend, address);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw BizException.of(ErrorCode.GEO_UNAVAILABLE);
        }
    }
}
