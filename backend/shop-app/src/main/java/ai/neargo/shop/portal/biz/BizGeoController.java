package ai.neargo.shop.portal.biz;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.platform.EstateCacheService;
import ai.neargo.shop.platform.GeoService;
import ai.neargo.shop.platform.port.GeoRouter;
import ai.neargo.shop.spi.platform.GeoPort;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/**
 * 地理能力（高德 Web 服务代理，P2/G1）。三件事：坐标→地址、地址校验、输入提示。
 * 未开通（没配密钥）：reverse 给 10503 让端上藏按钮；geocode/tips 给空结果让端上退回自由输入。
 *
 * <p><b>带 {@code market} 的 tips / reverse</b>（TDD-双地图提供方与门店选点 AC3）：建店选点按门店地区走
 * {@link GeoRouter} 选出的提供方（大陆高德、台湾 / 阿联酋 Google）。该地区地图不可用时给<b>空结果而不是错误</b>——
 * 选点页据此退回手动输入；也<b>不换另一家</b>（理由见 GeoRouter）。不带 market 时与改动前完全一样。
 */
@Profile("api")
@RestController
public class BizGeoController {

    private final GeoService geoService;
    private final EstateCacheService estateCache;
    private final GeoRouter router;

    public BizGeoController(GeoService geoService, EstateCacheService estateCache, GeoRouter router) {
        this.geoService = geoService;
        this.estateCache = estateCache;
        this.router = router;
    }

    /**
     * @param sys    端上坐标系。不带 market 时：三端现在都是 gcj02（App 已切高德定位），wgs84 只为老版本 App 留着，
     *               不传按 gcj02。带 market 时：不传按该地区地图的坐标系
     * @param market 门店地区（CN / TW / AE）；带了就按地区选提供方，不可用给空结果（recommend/address 为空串、
     *               coordSys 为 null），不报 10503
     */
    @GetMapping("/biz/geo/reverse")
    public GeoService.ReverseVO reverse(@RequestParam double lat, @RequestParam double lng,
                                        @RequestParam(required = false) String sys,
                                        @RequestParam(required = false) String market) {
        if (market == null || market.isBlank()) {
            return geoService.reverse(lat, lng, sys == null ? GeoPort.GCJ02 : sys);
        }
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        int latE6 = (int) Math.round(lat * 1e6);
        int lngE6 = (int) Math.round(lng * 1e6);
        String coordSys = GeoRouter.coordSysOf(router.providerFor(market));
        Optional<GeoPort> port = router.portFor(market);
        if (port.isEmpty()) {
            return emptyReverse(latE6, lngE6);
        }
        int[] p = port.get().toGcj02(latE6, lngE6, sys == null ? coordSys : sys);
        return port.get().reverse(p[0], p[1])
                .map(r -> new GeoService.ReverseVO(r.recommend(), r.address(), r.adcode(), r.township(),
                        p[0], p[1], coordSys))
                .orElseGet(() -> emptyReverse(latE6, lngE6));
    }

    private static GeoService.ReverseVO emptyReverse(int latE6, int lngE6) {
        return new GeoService.ReverseVO("", "", null, null, latE6, lngE6, null);
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

    /**
     * 输入提示。每条带 {@code coordSys}（高德 gcj02 / Google wgs84），端上选中后连同坐标回传建店。
     *
     * @param market 门店地区；带了就按地区选提供方，该地区不可用给空表（不换另一家）；不带 = 改动前的高德行为
     */
    @GetMapping("/biz/geo/tips")
    public List<GeoPort.Tip> tips(@RequestParam String kw, @RequestParam(required = false) String city,
                                  @RequestParam(required = false) String market) {
        if (market == null || market.isBlank()) {
            return geoService.tips(kw, city);
        }
        String coordSys = GeoRouter.coordSysOf(router.providerFor(market));
        return router.portFor(market)
                .map(port -> port.tips(kw, city).stream()
                        .map(t -> t.coordSys() != null ? t : new GeoPort.Tip(t.name(), t.address(), t.adcode(),
                                t.latE6(), t.lngE6(), t.typecode(), coordSys))
                        .toList())
                .orElse(List.of());
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
