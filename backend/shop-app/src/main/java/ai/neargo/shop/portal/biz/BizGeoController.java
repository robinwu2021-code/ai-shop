package ai.neargo.shop.portal.biz;

import ai.neargo.shop.platform.GeoService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 逆地理编码（P2）：店主站在店门口点「定位」，地址自动填上。
 * 未开通（没配密钥）时返回 10501，端上据此<b>藏掉按钮</b>——一个点了只会报错的按钮比没有更糟。
 */
@Profile("api")
@RestController
public class BizGeoController {

    private final GeoService geoService;

    public BizGeoController(GeoService geoService) {
        this.geoService = geoService;
    }

    @GetMapping("/biz/geo/reverse")
    public GeoService.ReverseVO reverse(@RequestParam double lat, @RequestParam double lng) {
        return geoService.reverse(lat, lng);
    }
}
