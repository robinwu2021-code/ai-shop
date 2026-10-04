package ai.neargo.shop.community;

import ai.neargo.shop.community.entity.GeoPlace;
import ai.neargo.shop.community.mapper.CommunityMappers.GeoPlaceMapper;
import ai.neargo.shop.community.service.PlaceResolver;
import ai.neargo.shop.community.support.MapBreaker;
import ai.neargo.shop.spi.platform.GeoPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L1.5 内存热缓存（TDD-虹选鲜果运营落地 §1）：挡住 {@code geo_place} 的读洪峰。
 *
 * <p>断言落在**读了几次库**上 —— 这层的全部价值就是「热点格子第二次别再读 DB」。
 * 两条守两件最容易回退的事：①新鲜命中第二次走内存；②<b>陈旧的不进缓存</b>
 * （进了就会把"该回头核一次"的格子钉死在旧名字上）。
 */
class PlaceResolverHotCacheTest {

    private static PlaceResolver resolverWith(GeoPlaceMapper mapper) {
        GeoPort port = mock(GeoPort.class);
        MapBreaker breaker = mock(MapBreaker.class);
        when(port.available()).thenReturn(true);
        when(breaker.isOpen()).thenReturn(false);
        // 陈旧分支会后台 askMap，给个空结果别让后台线程 NPE
        when(port.reverse(any(Integer.class), any(Integer.class))).thenReturn(Optional.empty());
        return new PlaceResolver(mapper, port, breaker, 8, 30, 10_000, 300_000);
    }

    private static GeoPlace place(LocalDateTime verifiedAt) {
        GeoPlace p = new GeoPlace();
        p.setId(1L);
        p.setName("XX大厦");
        p.setKind("AOI");
        p.setAddress("深圳市福田区XX路1号");
        p.setHitCount(5);
        p.setVerifiedAt(verifiedAt);
        return p;
    }

    @Test
    @DisplayName("★★★ 同一格子第二次 resolve 走内存，不再读 geo_place")
    void secondResolveSameCellSkipsDbRead() {
        GeoPlaceMapper mapper = mock(GeoPlaceMapper.class);
        when(mapper.selectOne(any())).thenReturn(place(LocalDateTime.now()));   // 新鲜
        PlaceResolver resolver = resolverWith(mapper);

        int lat = 22_540_000, lng = 114_050_000;
        var first = resolver.resolve(lat, lng);
        var second = resolver.resolve(lat, lng);

        assertThat(first).isPresent();
        assertThat(second).map(PlaceResolver.Place::name).hasValue("XX大厦");
        verify(mapper, times(1)).selectOne(any());   // 第二次没读库
    }

    @Test
    @DisplayName("★★ 陈旧结果不进内存：下一次仍要读库（否则把该回头核的格子钉死）")
    void staleResultIsNotCached() {
        GeoPlaceMapper mapper = mock(GeoPlaceMapper.class);
        // verifiedAt 在 freshDays(30) 之外 → 陈旧
        when(mapper.selectOne(any())).thenReturn(place(LocalDateTime.now().minusDays(40)));
        PlaceResolver resolver = resolverWith(mapper);

        int lat = 22_540_000, lng = 114_050_000;
        resolver.resolve(lat, lng);
        resolver.resolve(lat, lng);

        verify(mapper, times(2)).selectOne(any());   // 陈旧没缓存，两次都读
    }
}
