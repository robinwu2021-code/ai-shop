package ai.neargo.shop.portal.biz;

import ai.neargo.shop.platform.GeoService;
import ai.neargo.shop.spi.platform.GeoPort;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 地理能力（高德 Web 服务代理，P2/G1）。三件事：坐标→地址、地址校验、输入提示。
 * 未开通（没配密钥）：reverse 给 10503 让端上藏按钮；geocode/tips 给空结果让端上退回自由输入。
 */
@Profile("api")
@RestController
public class BizGeoController {

    private final GeoService geoService;

    public BizGeoController(GeoService geoService) {
        this.geoService = geoService;
    }

    /** @param sys 端上坐标系。三端现在都是 gcj02（App 已切高德定位）；wgs84 只为老版本 App 留着，不传按 gcj02 */
    @GetMapping("/biz/geo/reverse")
    public GeoService.ReverseVO reverse(@RequestParam double lat, @RequestParam double lng,
                                        @RequestParam(required = false) String sys) {
        return geoService.reverse(lat, lng, sys == null ? GeoPort.GCJ02 : sys);
    }

    /** 地址校验与标准化。available=false 时端上不拦、按自由文本保存 */
    @PostMapping("/biz/geo/geocode")
    public GeocodeVO geocode(@RequestBody GeocodeReq req) {
        if (!geoService.available()) {
            return new GeocodeVO(false, false, "", "", null, null, "");
        }
        return geoService.geocode(req.address(), req.city())
                .map(g -> new GeocodeVO(true, g.ok(), g.level(), g.formatted(),
                        g.ok() ? g.latE6() : null, g.ok() ? g.lngE6() : null, g.adcode()))
                .orElse(new GeocodeVO(true, false, "", "", null, null, ""));
    }

    @GetMapping("/biz/geo/tips")
    public List<GeoPort.Tip> tips(@RequestParam String kw, @RequestParam(required = false) String city) {
        return geoService.tips(kw, city);
    }

    public record GeocodeReq(String address, String city) {
    }

    /** available=false 表示功能未开通（与「解析不到」区分开：前者不拦，后者要提示） */
    public record GeocodeVO(boolean available, boolean ok, String level, String formatted,
                            Integer latE6, Integer lngE6, String adcode) {
    }
}
