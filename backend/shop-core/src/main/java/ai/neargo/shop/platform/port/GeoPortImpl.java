package ai.neargo.shop.platform.port;

import ai.neargo.shop.platform.GeoService;
import ai.neargo.shop.spi.platform.GeoPort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * {@link GeoPort} 的实现 —— 薄转发到 {@link GeoService}，厂商调用只有那一份。
 *
 * <p>为什么要单独一个类：{@code GeoService} 以前直接 {@code extends GeoPort}，
 * 于是 {@code GeoServiceImpl} 一个类同时是本域 Service 实现和跨域 Port 实现。
 * 「Service 兼任 Port」的代价是本域逻辑的改动会不知不觉改掉跨域契约的行为，
 * 而且两拨受众看到的能力范围被绑死成同一个 —— 给端上加一条就等于给别的域也加一条。
 */
@Component
public class GeoPortImpl implements GeoPort {

    private final GeoService geoService;

    public GeoPortImpl(GeoService geoService) {
        this.geoService = geoService;
    }

    @Override
    public boolean available() {
        return geoService.available();
    }

    @Override
    public int[] toGcj02(int latE6, int lngE6, String coordSys) {
        return geoService.toGcj02(latE6, lngE6, coordSys);
    }

    @Override
    public Optional<Reverse> reverse(int latE6, int lngE6) {
        return geoService.reverse(latE6, lngE6);
    }

    @Override
    public Optional<Geocode> geocode(String address, String city) {
        return geoService.geocode(address, city);
    }

    @Override
    public List<Tip> tips(String keyword, String city) {
        return geoService.tips(keyword, city);
    }

    @Override
    public List<Tip> around(String keyword, int latE6, int lngE6, int radiusM, String types) {
        return geoService.around(keyword, latE6, lngE6, radiusM, types);
    }
}
