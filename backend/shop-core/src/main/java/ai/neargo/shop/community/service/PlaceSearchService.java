package ai.neargo.shop.community.service;

import ai.neargo.shop.community.entity.CmtCommunity;
import ai.neargo.shop.community.entity.GeoPlace;
import ai.neargo.shop.community.mapper.CommunityMappers.CommunityMapper;
import ai.neargo.shop.community.mapper.CommunityMappers.GeoPlaceMapper;
import ai.neargo.shop.community.support.MapBreaker;
import ai.neargo.shop.platform.GeoService;
import ai.neargo.shop.spi.platform.GeoPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 「输个名字找地方」。**本地优先，地图是补充。**
 *
 * <p>为什么本地要在前面，而不是把地图结果排前面：本地那一条带着
 * {@code communityNo} —— 选中它才能直接绑到聚落、才有商品池。
 * 地图那一条只有名字与坐标，选中之后还要再解析一次。同名的两条里，
 * <b>留本地那条</b>。
 *
 * <p><b>地图不可用时这个接口照样有结果</b>（只是少）。这一条不是优化，
 * 是产品判断：端上今天的做法是整段搜索不渲染，而那等于告诉用户
 * 「这儿什么都没有」—— 一个空列表和一个不存在的搜索框，后者更让人无从下手。
 */
@Service
public class PlaceSearchService {

    /** 本地与地图各取多少条。合并后端上还会再截，这里只防「一次拉回几百条」 */
    private static final int PER_SOURCE = 10;
    /** 围着当前位置搜的半径。太大会把邻市的同名点搜进来 */
    private static final int AROUND_M = 5000;

    public static final String SOURCE_COMMUNITY = "COMMUNITY";
    public static final String SOURCE_PLACE_DB = "PLACE_DB";
    public static final String SOURCE_MAP = "MAP";

    /**
     * @param communityNo 本地聚落才有。**端上据此决定选中之后能不能直接绑**
     * @param source      这条从哪儿来的，端上可以据此排版，也用于排查
     */
    public record PlaceHitVO(String name, String address, Integer latE6, Integer lngE6,
                             String communityNo, String source) {
    }

    private final CommunityMapper communityMapper;
    private final GeoPlaceMapper placeMapper;
    private final GeoService geoService;
    private final MapBreaker breaker;

    public PlaceSearchService(CommunityMapper communityMapper, GeoPlaceMapper placeMapper,
                              GeoService geoService, MapBreaker breaker) {
        this.communityMapper = communityMapper;
        this.placeMapper = placeMapper;
        this.geoService = geoService;
        this.breaker = breaker;
    }

    public List<PlaceHitVO> search(String keyword, Integer latE6, Integer lngE6, String city) {
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) {
            return List.of();
        }

        List<PlaceHitVO> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        // ① 已开通聚落：唯一带 communityNo 的一档
        for (CmtCommunity c : communityMapper.selectList(Wrappers.<CmtCommunity>lambdaQuery()
                .eq(CmtCommunity::getStatus, "OPEN")
                .isNull(CmtCommunity::getArchivedAt)
                .isNotNull(CmtCommunity::getLatE6)
                .like(CmtCommunity::getName, kw)
                .last("limit " + PER_SOURCE))) {
            if (seen.add(c.getName())) {
                out.add(new PlaceHitVO(c.getName(), c.getAddress(), c.getLatE6(), c.getLngE6(),
                        c.getCommunityNo(), SOURCE_COMMUNITY));
            }
        }

        // ② 固定地址库：问过一次就记着的那些地方
        for (GeoPlace p : placeMapper.selectList(Wrappers.<GeoPlace>lambdaQuery()
                .like(GeoPlace::getName, kw)
                .last("limit " + PER_SOURCE))) {
            if (seen.add(p.getName())) {
                out.add(new PlaceHitVO(p.getName(), p.getAddress(), p.getLatE6(), p.getLngE6(),
                        null, SOURCE_PLACE_DB));
            }
        }

        // ③ 地图。不可用就到此为止 —— 上面两档已经有东西了
        if (!geoService.available() || breaker.isOpen()) {
            return out;
        }
        List<GeoPort.Tip> tips;
        try {
            tips = latE6 != null && lngE6 != null
                    ? geoService.around(kw, latE6, lngE6, AROUND_M, null)
                    : geoService.tips(kw, city);
            breaker.recordSuccess();
        } catch (RuntimeException e) {
            breaker.recordFailure();
            return out;
        }
        for (GeoPort.Tip t : tips) {
            if (t.latE6() == null || t.lngE6() == null) {
                // 没坐标的地点选了等于又得到一条没坐标的地址，这一页就白来了
                continue;
            }
            if (seen.add(t.name())) {
                out.add(new PlaceHitVO(t.name(), t.address(), t.latE6(), t.lngE6(), null, SOURCE_MAP));
            }
        }
        return out;
    }
}
