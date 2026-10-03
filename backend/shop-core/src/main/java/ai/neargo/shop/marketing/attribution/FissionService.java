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

    /**
     * C 端的「邀请有礼」：当前在跑的活动 + 这个人自己邀到了几个
     * （TDD-C 端裂变与商家招募 §3.1）。
     *
     * <p><b>没有在跑的活动时返回 empty</b> —— 端上据此整条入口不显示。
     * 不返回一个「活动为空」的壳：那会让页面出现一个点进去说「暂无活动」的入口，
     * 而那比没有入口更糟。
     *
     * <p>这条是<b>买家侧此前完全没有的那一半</b>：后端从配置、归因、注册落台账到
     * 首单回填全都通了，而买家没有任何地方看得到「分享能得券」——
     * 于是线上 {@code mkt_fission_invite} 一行都没有。
     */
    java.util.Optional<MyFissionVO> myFission(String userNo);

    /**
     * @param inviterCount   邀请人得几张
     * @param inviteeCount   被邀请人得几张
     * @param couponTitle    奖励券的名字。**取自券模板** —— 页面要说得出「得的是什么」，
     *                       只说「得 1 张券」等于没说
     * @param faceMinor      券面值（分）；折扣券为 0
     * @param thresholdMinor 使用门槛（分）；0 = 无门槛
     * @param myInvited      我邀到的人数
     * @param myConverted    其中完成首单的人数 —— 奖励是按首单发的，两个数要并列摆出来，
     *                       否则用户会问「我邀了 3 个怎么只得 1 张」
     */
    record MyFissionVO(String fissionNo, String name, int inviterCount, int inviteeCount,
                       String couponTitle, long faceMinor, long thresholdMinor,
                       int myInvited, int myConverted) {
    }
}
