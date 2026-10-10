package ai.neargo.shop.portal.biz;

import ai.neargo.shop.platform.EstateCacheService;
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
    private final EstateCacheService estateCache;

    public BizGeoController(GeoService geoService, EstateCacheService estateCache) {
        this.geoService = geoService;
        this.estateCache = estateCache;
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

    /**
     * 一片（街道 / 村·社区）里的小区。**服务端读穿透**：缓存新鲜直接给；缺失或过期，
     * 且给得出圆心，就现问地图、写回缓存、再返回。
     *
     * <p>App 不再自己调原生 SDK 拼结果、也不用把结果传回来写缓存 —— 那条路径（V206）
     * 出过一次真事故：端上写的缓存键和真实使用的键对不上，导致缓存一直没生效，
     * 而失败又被两边的静默 catch 一起吞掉，谁都看不出来。现在读写都在服务端一侧，
     * App 只在「打开地图选点」这种真正要看地图 UI 的时候才碰原生 SDK。
     *
     * @param latE6 圆心（可选，已知坐标时端上直接给，省一次地理编码）
     * @param lngE6 同上
     * @param addressPath 圆心的地址描述（如「浙江省 / 杭州市 / 西湖区 / 北山街道 / 宝石社区」），
     *                    没给坐标时用它地理编码
     * @param city  高德 city 偏好（可选）
     * @param rural 这一片是村委会还是社区/居委会。城乡搜法不一样（见 EstateCacheService#resolve）
     */
    @GetMapping("/biz/geo/estates")
    public EstateCacheService.Estates estates(@RequestParam String regionCode,
                                              @RequestParam(required = false) String parentCode,
                                              @RequestParam(required = false) Integer latE6,
                                              @RequestParam(required = false) Integer lngE6,
                                              @RequestParam(required = false) String addressPath,
                                              @RequestParam(required = false) String city,
                                              @RequestParam(required = false, defaultValue = "false") boolean rural) {
        return estateCache.resolve(regionCode, parentCode, latE6, lngE6, addressPath, city, rural);
    }

    /**
     * 上一级下辖各片的小区条数。列表要在每一行上预告「12 个小区 / 暂无小区」——
     * 没有这个预告，每行的 › 都是一句没有依据的承诺，点进去才知道是不是空的。
     */
    @GetMapping("/biz/geo/estates/counts")
    public java.util.Map<String, Integer> estateCounts(@RequestParam String parentCode) {
        return estateCache.counts(parentCode);
    }

    public record GeocodeReq(String address, String city) {
    }

    /** available=false 表示功能未开通（与「解析不到」区分开：前者不拦，后者要提示） */
    public record GeocodeVO(boolean available, boolean ok, String level, String formatted,
                            Integer latE6, Integer lngE6, String adcode) {
    }
}
