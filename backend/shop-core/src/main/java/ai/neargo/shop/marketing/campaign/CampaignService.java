package ai.neargo.shop.marketing.campaign;

import ai.neargo.shop.marketing.campaign.dto.CampaignVO;

import java.util.List;

/** 商家营销活动（B-11.8）。活动是**店铺级**的，不跨店。 */
public interface CampaignService {

    List<CampaignVO> list(String merchantNo);

    /**
     * 新建或编辑。{@code campaignNo} 为空即新建。
     *
     * <p><b>类型创建后不可改</b> —— 改类型等于换一套优惠语义（满减变秒杀），
     * 而已发出去的券、已参与的订单都是按原语义算的。要换就新建。
     */
    CampaignVO save(String merchantNo, SaveCommand cmd);

    /**
     * 启停。<b>只允许 RUNNING ↔ PAUSED</b>。
     *
     * <p>已结束（ENDED）的活动不可复活：它的时段已经过去，复活之后
     * 「生效中但已过期」这种状态没人能解释，预算与时段的统计也全乱。要再跑就新建。
     */
    CampaignVO toggle(String merchantNo, String campaignNo, boolean running);

    /**
     * @param type       创建后不可改
     * @param goodsNos   空 = 全店
     * @param totalCount COUPON 的发放总量，null = 不限量
     */
    /**
     * @param storeNo 只对这家门店生效；<b>为空 = 全主体</b>。
     *                只有 {@code FULL_CUT} 接受它 —— 另外三种要么改商品页展示
     *                （顾客那时还没选自提点，页面价与下单价会打架），
     *                要么走券的核销链路（门店限定该在券侧做）
     */
    // ---------------------------------------------------------------- 平台侧（P-7.2）

    /** 平台活动列表。**跨商家**。{@code status} 为空给全部。 */
    List<CampaignVO> opsCampaigns(String status);

    /**
     * 平台停/启商家活动。
     *
     * <p>与商家自己的 {@link #toggle} 走同一个状态字段，但**不校验归属**——
     * 平台停的就是别人家的活动。这正是它存在的理由：
     * 商家把满减门槛写成 0（等于白送）而联系不上时，运营得能立刻按停。
     *
     * @param reason 必填，写进审计
     */
    CampaignVO opsToggle(String campaignNo, boolean running, String reason, String operatorNo);

    record SaveCommand(String campaignNo, String type, String name, long startAt, long endAt,
                       Long thresholdMinor, Long discountMinor, Long flashPriceMinor,
                       Integer buyN, Integer giftM, List<String> goodsNos, Integer totalCount,
                       String storeNo) {
    }
}
