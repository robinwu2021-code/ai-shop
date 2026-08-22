package ai.neargo.shop.community.service;

import ai.neargo.shop.community.dto.CommunityVO;
import ai.neargo.shop.community.dto.RegionOptionVO;

import java.util.List;

/** 社区与自提点（[API 清单 §2.2]）。游客可访问 —— 选社区发生在登录之前。 */
public interface CommunityService {

    /**
     * 附近社区（含其下自提点）。
     *
     * @param latE6 纬度 ×1e6，可空（未授权定位时按名称序返回）
     * @param lngE6 经度 ×1e6，可空
     */
    List<CommunityVO> nearby(Integer latE6, Integer lngE6);

    /** 社区详情（含其下常驻自提点）。 */
    CommunityVO detail(String communityNo);

    /**
     * 全部社区。B 端选覆盖范围用（ADR-009）—— 商家选的是「我送得到哪些小区」，
     * 那是他自己知道的经营半径，与他此刻站在哪儿无关，所以不按定位排序。
     */
    List<CommunityVO> all();

    /**
     * 全部已开通社区，可按行政区划筛。
     *
     * @param regionCode 区划码前缀。国标码本身是层级前缀（省 2 / 市 4 / 区县 6 / 街道 9），
     *                   所以传「3301」能捞出整个杭州市，传「330106」只捞西湖区 ——
     *                   不用先查一遍子区划再 IN 一大串
     */
    List<CommunityVO> all(String regionCode);

    /**
     * <b>有已开通社区的</b>区域清单，按「市 → 区」两级聚合。
     *
     * <p>为什么不直接给区划全表：库里有 2978 个区县、41352 个街道，
     * 让用户在里面挑一个，十有八九挑到一个**一家店都没有**的区 ——
     * 那不是「选区域」，那是抽奖。这里只列真的有货可买的地方，并带上社区数。
     */
    List<RegionOptionVO> openRegions();

    /** 自提点详情（C-CM-02）：地址、营业时间、到货时间。 */
    CommunityVO.PickupVO pickupDetail(String pickupNo);

    /**
     * 门店可引用的取货点候选（P1）：在这些社区里、ACTIVE、常驻的 STORE/PLATFORM 点，
     * 再并上本店自己建的点（含 PENDING/REJECTED，商家才知道自己建的点去哪了）。
     * 不给距离：门店本身没有坐标可算；本店的排最前，其余按社区归组、同组按名字。
     */
    List<PickupCandidate> pickupCandidates(java.util.Collection<String> communityNos, String ownerStoreNo);

    /**
     * 商家自建自提点（P1）：落 STORE 型、owner 为本店、<b>PENDING</b> 待运营核实。
     * 坐标必填 —— 没坐标的点买家用定位永远找不到；社区不传时按坐标就近归到已开通社区。
     */
    PickupCandidate selfBuildPickup(SelfBuildCmd cmd);

    record PickupCandidate(String pickupNo, String name, String address, String type, String status,
                           String communityNo, String communityName, String ownerStoreNo,
                           String rejectReason) {
    }

    record SelfBuildCmd(String storeNo, String name, String address, Integer latE6, Integer lngE6,
                        String openHours, String communityNo) {
    }
}
