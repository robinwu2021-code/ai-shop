package ai.neargo.shop.community.service;

import ai.neargo.shop.geo.ReachGeoProps;
import ai.neargo.shop.spi.reach.ConsumerProfile;
import ai.neargo.shop.spi.user.CommunityQueryPort;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 把请求里零散的「在哪儿」（小区号 / 区划码 / 坐标）推成可见性判定要的 {@link ConsumerProfile}（ADR-034）。
 *
 * <p>推导顺序，<b>确知即记、不臆造</b>：
 * <ol>
 *   <li>给了小区号：从聚落带出 regionCode（街道级 9 位或更细）、parentNo（楼栋→小区）、坐标兜底；</li>
 *   <li>没有区划码但有坐标：{@code CommunityService.resolve} 反解到区县（6 位，纯定位这一级唯一能给出的结论）；</li>
 *   <li>S2 token 只在坐标合法时算。</li>
 * </ol>
 * 请求里显式给的字段优先于推导值 —— 端上比我们更知道消费者选的是哪里。
 */
@Service
public class ConsumerProfileResolver {

    private final CommunityQueryPort communityQueryPort;
    private final CommunityService communityService;
    private final ReachGeoProps props;

    public ConsumerProfileResolver(CommunityQueryPort communityQueryPort, CommunityService communityService,
                                   ReachGeoProps props) {
        this.communityQueryPort = communityQueryPort;
        this.communityService = communityService;
        this.props = props;
    }

    public ConsumerProfile resolve(String communityNo, String regionCode, Integer latE6, Integer lngE6) {
        String region = blank(regionCode) ? null : regionCode.strip();
        String community = blank(communityNo) ? null : communityNo.strip();
        String parent = null;
        Integer lat = latE6;
        Integer lng = lngE6;

        if (community != null) {
            CommunityQueryPort.CommunityRef ref = communityQueryPort.communityRefs(List.of(community)).get(community);
            if (ref != null) {
                if (region == null) {
                    region = ref.regionCode();
                }
                parent = ref.parentNo();
                if (!ConsumerProfile.validCoords(lat, lng)) {
                    lat = ref.latE6();
                    lng = ref.lngE6();
                }
            }
        }
        if (region == null && ConsumerProfile.validCoords(lat, lng)) {
            CommunityService.LocationVO vo = communityService.resolve(lat, lng, true);
            if (vo != null && !blank(vo.regionCode())) {
                region = vo.regionCode();
            }
        }
        return ConsumerProfile.of(region, community, parent, lat, lng, props.getS2MinLevel(), props.getS2MaxLevel());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
