package ai.neargo.shop.platform.port;

import ai.neargo.shop.spi.platform.GeoPort;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/**
 * 按门店地区选地图提供方（TDD-双地图提供方与门店选点 AC1–AC3）：缺省大陆高德，台湾 / 阿联酋 Google，
 * 由 {@code shop.geo.provider-by-market} 覆盖。
 *
 * <p><b>不跨提供方兜底</b>：高德在阿联酋没有底图数据（sharehub 2026-09-26 实测：迪拜瓦片 179 字节纯色，且不报错），
 * Google 在大陆网络下不可达。互相兜底只会得到「看着能用、实际空白」的地图，所以某地区的提供方不可用时
 * 直接给空，端上退回手动输入。
 *
 * <p><b>现有调用方不经过这里</b>：按类型注入的 {@link GeoPort} 仍然只有高德一个 bean，社区 / 地址校验行为不变。
 * Google 实现只由本类持有，理由见 {@link GoogleGeoPortImpl}。
 */
@Component
public class GeoRouter {

    public static final String AMAP = "AMAP";
    public static final String GOOGLE = "GOOGLE";
    static final String DEFAULT_PROVIDER_BY_MARKET = "CN:AMAP,TW:GOOGLE,AE:GOOGLE";

    /**
     * 端上画地图要的东西。
     *
     * @param jsKey      浏览器端 JS key（限定包名 / 域名，本来就要下发到端上），与服务端 key 分开
     * @param jsSecurity 高德 JS 安全密钥；Google 为 null
     * @param coordSys   这家地图的坐标系：高德 gcj02，Google wgs84
     */
    public record ClientMap(String provider, String jsKey, String jsSecurity, String coordSys) {
    }

    private final GeoPort amap;
    private final GoogleGeoPortImpl google;
    private final Map<String, String> providerByMarket;
    private final String amapJsKey;
    private final String amapJsSecurity;
    private final String googleJsKey;

    @Autowired
    public GeoRouter(GeoPort amap, ObjectMapper json,
                     @Value("${shop.geo.google-key:}") String googleKey,
                     @Value("${shop.geo.provider-by-market:" + DEFAULT_PROVIDER_BY_MARKET + "}")
                     String providerByMarket,
                     @Value("${shop.geo.amap-js-key:}") String amapJsKey,
                     @Value("${shop.geo.amap-js-security:}") String amapJsSecurity,
                     @Value("${shop.geo.google-js-key:}") String googleJsKey) {
        this(amap, new GoogleGeoPortImpl(googleKey, json), providerByMarket, amapJsKey, amapJsSecurity, googleJsKey);
    }

    GeoRouter(GeoPort amap, GoogleGeoPortImpl google, String providerByMarket,
              String amapJsKey, String amapJsSecurity, String googleJsKey) {
        this.amap = amap;
        this.google = google;
        this.providerByMarket = parse(providerByMarket);
        this.amapJsKey = blankToNull(amapJsKey);
        this.amapJsSecurity = blankToNull(amapJsSecurity);
        this.googleJsKey = blankToNull(googleJsKey);
    }

    /** 该地区配的提供方（{@link #AMAP} / {@link #GOOGLE}），不管密钥配没配；没配这个地区为 null */
    public String providerFor(String market) {
        return market == null ? null : providerByMarket.get(market.trim().toUpperCase(Locale.ROOT));
    }

    /** 该地区提供方的服务端实现，只在它 {@code available()} 时给；不可用为空，<b>不换另一家</b> */
    public Optional<GeoPort> portFor(String market) {
        String provider = providerFor(market);
        if (AMAP.equals(provider)) {
            return amap.available() ? Optional.of(amap) : Optional.empty();
        }
        if (GOOGLE.equals(provider)) {
            return google.available() ? Optional.of(google.forRegion(market.trim())) : Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * 端上地图选点能不能开（AC2）：服务端搜索可用<b>且</b>这家的端上 JS key 配了。少一样都不开 ——
     * 只有搜索没有底图、或只有底图搜不到，都是半个功能。
     */
    public Optional<ClientMap> clientMapFor(String market) {
        if (portFor(market).isEmpty()) {
            return Optional.empty();
        }
        String provider = providerFor(market);
        String jsKey = AMAP.equals(provider) ? amapJsKey : googleJsKey;
        if (jsKey == null) {
            return Optional.empty();
        }
        return Optional.of(new ClientMap(provider, jsKey, AMAP.equals(provider) ? amapJsSecurity : null,
                coordSysOf(provider)));
    }

    /** 提供方的坐标系：高德 gcj02，Google wgs84；不认识为 null */
    public static String coordSysOf(String provider) {
        if (AMAP.equals(provider)) {
            return GeoPort.GCJ02;
        }
        if (GOOGLE.equals(provider)) {
            return GeoPort.WGS84;
        }
        return null;
    }

    /** {@code "CN:AMAP,TW:GOOGLE"} → map。写坏的条目与不认识的提供方忽略（地图是增强，配置错不该起不来） */
    private static Map<String, String> parse(String spec) {
        Map<String, String> out = new HashMap<>();
        if (spec == null) {
            return out;
        }
        for (String entry : spec.split(",")) {
            String[] kv = entry.split(":");
            if (kv.length != 2) {
                continue;
            }
            String market = kv[0].trim().toUpperCase(Locale.ROOT);
            String provider = kv[1].trim().toUpperCase(Locale.ROOT);
            if (!market.isEmpty() && (AMAP.equals(provider) || GOOGLE.equals(provider))) {
                out.put(market, provider);
            }
        }
        return Map.copyOf(out);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
