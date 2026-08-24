package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.platform.EstateCacheService;
import ai.neargo.shop.platform.GeoService;
import ai.neargo.shop.platform.entity.SysRegion;
import ai.neargo.shop.platform.mapper.PlatformMappers.RegionMapper;
import ai.neargo.shop.spi.platform.GeoPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * 坐标按需补全（方案二）：查到就存，下次不用再查。
 *
 * <p>{@code EstateCacheFlowTest} 那组用例跑在没配高德 key 的测试环境下（{@code GeoService.available()}
 * 恒为 false），走不到「地理编码成功」这条分支 —— 这组用例专门 mock {@link GeoService}，
 * 把这条分支单独跑一遍，不跟主套件混在一起，免得改动了主套件里「测试环境没有地图能力」这条假设。
 */
@SpringBootTest
@ActiveProfiles("test")
class EstateCacheBackfillFlowTest {

    @Autowired
    private EstateCacheService cache;

    @Autowired
    private RegionMapper regionMapper;

    @MockitoBean
    private GeoService geoService;

    private void region(String code, String parent, Integer latE6, Integer lngE6) {
        DataScopeContext.executeWithoutScope(() -> {
            SysRegion exist = regionMapper.selectOne(Wrappers.<SysRegion>lambdaQuery()
                    .eq(SysRegion::getRegionCode, code).last("limit 1"));
            SysRegion row = exist == null ? new SysRegion() : exist;
            row.setRegionCode(code);
            row.setParentCode(parent);
            row.setLevel("VILLAGE");
            row.setName("坐标补全测试-" + code);
            row.setEnabled(true);
            row.setAuditStatus("APPROVED");
            row.setLatE6(latE6);
            row.setLngE6(lngE6);
            return exist == null ? regionMapper.insert(row) : regionMapper.updateById(row);
        });
    }

    private SysRegion reload(String code) {
        return DataScopeContext.executeWithoutScope(() -> regionMapper.selectOne(
                Wrappers.<SysRegion>lambdaQuery().eq(SysRegion::getRegionCode, code).last("limit 1")));
    }

    @Test
    @DisplayName("★★ 没坐标 → 现查 → 查到就存回 sys_region，下次不用再查")
    void backfillsMissingCoordsOnce() {
        String code = "440309005100";
        region(code, "440309005", null, null);
        when(geoService.available()).thenReturn(true);
        when(geoService.geocode(any(), any()))
                .thenReturn(Optional.of(new GeoPort.Geocode(true, "住宅区", "广东省深圳市龙华区福城街道", 22715480, 114044000, "440309")));
        when(geoService.around(any(), anyInt(), anyInt(), anyInt(), any()))
                .thenReturn(List.of());

        cache.resolve(code, "440309005", null, null, "广东省 / 深圳市 / 龙华区 / 福城街道 / 坐标补全测试", "深圳市", false);

        SysRegion after = reload(code);
        assertThat(after.getLatE6()).isEqualTo(22715480);
        assertThat(after.getLngE6()).isEqualTo(114044000);
        assertThat(after.getCoordsSource()).isEqualTo("AMAP");
        assertThat(after.getCoordsAt()).isNotNull();
    }

    @Test
    @DisplayName("★ 已经有坐标的不会被现查的结果覆盖 —— WHERE lat_e6 IS NULL 是那道闸")
    void doesNotOverwriteExistingCoords() {
        String code = "440309005101";
        region(code, "440309005", 20000000, 110000000);
        when(geoService.available()).thenReturn(true);
        when(geoService.geocode(any(), any()))
                .thenReturn(Optional.of(new GeoPort.Geocode(true, "住宅区", "别的地方", 99999999, 99999999, "440309")));

        // 已知坐标直接命中「有坐标就不查」那一支：geocode 根本不会被调用，遑论覆盖
        cache.resolve(code, "440309005", 20000000, 110000000, "任意地址", "深圳市", false);

        SysRegion after = reload(code);
        assertThat(after.getLatE6()).isEqualTo(20000000);
        assertThat(after.getLngE6()).isEqualTo(110000000);
    }

    @Test
    @DisplayName("★ 已开通社区的合成缓存键（C 开头）不当区划码写回 —— 写下去也匹配不到任何一行")
    void skipsBackfillForCommunityScopedKey() {
        when(geoService.available()).thenReturn(true);
        when(geoService.geocode(any(), any()))
                .thenReturn(Optional.of(new GeoPort.Geocode(true, "住宅区", "某小区", 22715480, 114044000, "440309")));
        when(geoService.around(any(), anyInt(), anyInt(), anyInt(), any()))
                .thenReturn(List.of());

        // 不该抛异常、不该写坏任何一行 —— 这条用例守的就是「跳过」本身
        cache.resolve("C202608240009990001", "440309005", null, null, "广东省 / 深圳市 / 某小区", "深圳市", false);
    }
}
