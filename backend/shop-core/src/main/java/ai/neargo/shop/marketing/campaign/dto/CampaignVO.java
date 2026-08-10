package ai.neargo.shop.marketing.campaign.dto;

import java.util.List;

/**
 * 营销活动（契约 {@code MarketingCampaign}）。
 *
 * @param type       COUPON / FULL_CUT / FLASH / BUY_GIFT —— 决定下面哪几个字段有意义
 * @param goodsNos   参与商品；**空 = 全店**
 * @param totalCount COUPON 的发放总量。{@code null} = 不限量
 * @param takenCount COUPON 已被领取数
 * @param usedCount  已核销/已使用次数，衡量效果
 */
public record CampaignVO(String campaignNo,
                         String merchantNo,
                         String type,
                         String name,
                         String status,
                         long startAt,
                         long endAt,
                         Long thresholdMinor,
                         Long discountMinor,
                         Long flashPriceMinor,
                         Integer buyN,
                         Integer giftM,
                         List<String> goodsNos,
                         Integer totalCount,
                         Integer takenCount,
                         int usedCount) {
}
