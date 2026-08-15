package ai.neargo.shop.marketing.attribution;

import java.util.List;

/**
 * 裂变活动维护（P-9.2.1 / 9.2.2）。
 *
 * <p>这一层只管**活动本身**（建、改、启停）。发奖与新客判定挂在台账
 * {@code mkt_fission_invite} 上，由 C 端注册/首单链路触发 —— 那是另一条链路，
 * 不在运营端的写操作里。<b>这个边界要说清楚</b>：运营端能配活动、看效果，
 * 但不能手工给某个人补发奖励（那会绕开幂等键 {@code uk_fission_invitee}）。
 */
public interface FissionService {

    List<CampaignVO> list(boolean enabledOnly);

    /** 新建或修改。{@code fissionNo} 为空 = 新建。 */
    CampaignVO save(SaveCommand cmd, String operatorNo);

    /**
     * 启停。<b>启用时校验券模板存在且可用</b> ——
     * 指向一个停用券的活动会在发奖那一刻才失败，而那时用户已经被邀请来了。
     */
    CampaignVO setEnabled(String fissionNo, boolean enabled, String operatorNo);

    /**
     * @param invitedCount   台账聚合：累计邀请人数
     * @param convertedCount 其中完成首单的人数 —— 与 invitedCount 并列才看得出活动有没有用
     */
    record CampaignVO(String fissionNo, String name, String rewardType, String couponNo,
                      int inviterCount, int inviteeCount, boolean enabled,
                      int invitedCount, int convertedCount, String createdAt) {
    }

    record SaveCommand(String fissionNo, String name, String couponNo,
                       Integer inviterCount, Integer inviteeCount) {
    }
}
