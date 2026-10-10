package ai.neargo.shop.scenario;

import ai.neargo.shop.platform.EstateCacheService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 小区清单缓存（V206）：**第二次进同一片不该再问地图**。
 *
 * <p>小区这一级 {@code sys_region} 里没有，只能问高德，而真机上一次周边搜索要一两秒。
 * 这组用例守三件事：写进去读得回来、上一级能一次拿到各片的条数（列表要据此预告
 * 「12 个小区 / 暂无小区」）、以及**空结果也算抓过** —— 不然农村那些真的没有小区的片
 * 每次进都会重抓一遍，而每次都抓回零条。
 */
@SpringBootTest
@ActiveProfiles("test")
class EstateCacheFlowTest {

    private static final String STREET = "440309005";
    private static final String VILLAGE = "440309005001";

    @Autowired
    private EstateCacheService cache;

    @Test
    @DisplayName("★★ 写进去读得回来，且标记为已抓过（cached=true, stale=false）")
    void putThenGet() {
        cache.put(VILLAGE, STREET, List.of(
                new EstateCacheService.Estate("福安雅园A区", "福前路 8 号", 22715480, 114044000, "B0FF001"),
                new EstateCacheService.Estate("福安雅园B区", "福前路 10 号", 22715500, 114044200, "B0FF002")));

        EstateCacheService.Estates got = cache.get(VILLAGE);
        assertThat(got.cached()).isTrue();
        assertThat(got.stale()).isFalse();
        assertThat(got.items()).extracting(EstateCacheService.Estate::name)
                .containsExactly("福安雅园A区", "福安雅园B区");
    }

    @Test
    @DisplayName("★★ 没抓过的片 cached=false —— 与「抓过但是空的」是两回事")
    void neverFetchedIsNotEmptyFetch() {
        assertThat(cache.get("440309005999").cached()).isFalse();
    }

    @Test
    @DisplayName("★★ 空结果也算抓过：农村真的没有小区的片，不该每次进都重抓一遍")
    void emptyResultStillCounts() {
        cache.put("140821107200", "140821107", List.of());

        EstateCacheService.Estates got = cache.get("140821107200");
        assertThat(got.cached()).isTrue();
        assertThat(got.items()).isEmpty();
    }

    @Test
    @DisplayName("★ 上一级一次拿到各片条数 —— 列表要在每一行上预告，不能一行一次查")
    void countsByParent() {
        cache.put("440309005002", STREET, List.of(
                new EstateCacheService.Estate("茜坑新村", "茜坑路 1 号", 22707388, 114037000, "B0FF003")));

        assertThat(cache.counts(STREET))
                .containsEntry("440309005002", 1);
    }

    @Test
    @DisplayName("★ 同一片再抓一次是覆盖，不是追加 —— 地图上没了的小区不该永远留在列表里")
    void putOverwrites() {
        cache.put("440309005003", STREET, List.of(
                new EstateCacheService.Estate("旧名花园", "某路 1 号", 22700000, 114000000, "B0FF004")));
        cache.put("440309005003", STREET, List.of(
                new EstateCacheService.Estate("新名花园", "某路 1 号", 22700000, 114000000, "B0FF005")));

        assertThat(cache.get("440309005003").items())
                .extracting(EstateCacheService.Estate::name).containsExactly("新名花园");
    }

    @Test
    @DisplayName("★★ 已开通社区的缓存键（C + 聚落号）能写进去 —— 真机实测撞过 VARCHAR(16) 截断")
    void communityScopedKeyFits() {
        // 真实撞过的长度：C + 23 位聚落号 = 24 字符，此前的列宽 16 装不下
        String scope = "C202608240005390003971";
        cache.put(scope, STREET, List.of(
                new EstateCacheService.Estate("福安雅园A区", "福前路 8 号", 22715480, 114044000, "B0FF001")));

        assertThat(cache.get(scope).cached()).isTrue();
    }

    @Test
    @DisplayName("★ 没坐标的条目落库前就被滤掉 —— 买家用定位永远落不进去，而双方都查不出来")
    void dropsItemsWithoutCoords() {
        cache.put("440309005004", STREET, List.of(
                new EstateCacheService.Estate("有坐标花园", "路 1 号", 22700000, 114000000, "B1"),
                new EstateCacheService.Estate("没坐标花园", "路 2 号", null, null, "B2")));

        assertThat(cache.get("440309005004").items())
                .extracting(EstateCacheService.Estate::name).containsExactly("有坐标花园");
    }

    // ---------------------------------------------------------------- resolve()：读穿透

    @Test
    @DisplayName("★★ 缓存新鲜时 resolve() 直接返回，不碰地图 —— 这是「本地优先」的核心断言")
    void resolveReturnsFreshCacheWithoutTouchingMap() {
        cache.put("440309005005", STREET, List.of(
                new EstateCacheService.Estate("已缓存花园", "路 1 号", 22700000, 114000000, "B1")));

        EstateCacheService.Estates got = cache.resolve("440309005005", STREET, null, null, null, null, false);
        assertThat(got.cached()).isTrue();
        assertThat(got.stale()).isFalse();
        assertThat(got.items()).extracting(EstateCacheService.Estate::name).containsExactly("已缓存花园");
    }

    @Test
    @DisplayName("★ 缓存缺失且给不出圆心（没坐标也没地址）：原样返回空缓存，不报错")
    void resolveWithoutAnyCenterStaysEmpty() {
        EstateCacheService.Estates got = cache.resolve("440309005006", STREET, null, null, null, null, false);
        assertThat(got.cached()).isFalse();
        assertThat(got.items()).isEmpty();
    }

    @Test
    @DisplayName("★ 缓存缺失但给了坐标：走一次地图（测试环境没配 key，问回空表也要落一条「已抓过」的缓存）")
    void resolveWithCoordsFetchesAndCaches() {
        EstateCacheService.Estates got = cache.resolve("440309005007", STREET, 22700000, 114000000, null, null, false);
        // 没配 AMAP key 时 GeoService.available()=false，around() 返回空 —— 断言的是流程走通、
        // 且**这次查询本身被记成「抓过」**（cached=true），不是重点在结果内容
        assertThat(got.cached()).isTrue();
        assertThat(cache.get("440309005007").cached()).isTrue();
    }

    @Test
    @DisplayName("★★ 农村片（rural=true）走的是「村」这套词，不是城区的「住宅小区」——流程要能走通、不报错")
    void resolveRuralUsesVillageKeywordsAndCaches() {
        // 「牛杜镇」下 19 个村委会都有坐标（真机实测），农村这条路径正是从这样的坐标进来的
        EstateCacheService.Estates got = cache.resolve("140821107200", "140821107", 35340000, 110990000, null, null, true);
        assertThat(got.cached()).isTrue();
        assertThat(cache.get("140821107200").cached()).isTrue();
    }
}
