package ai.neargo.shop.user.service;

import ai.neargo.shop.user.dto.CommunityVO;

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

    /** 自提点详情（C-CM-02）：地址、营业时间、到货时间。 */
    CommunityVO.PickupVO pickupDetail(String pickupNo);
}
