package ai.neargo.shop.merchant.service;

/**
 * 门店收款方式开关：线下（当面）收款、货到付款。
 *
 * <p>这是支付方式四层判定（{@code PayModeServiceImpl}）里的第 ③ 层，<b>读路径早就在跑</b>，
 * 而写入口一直没有 —— 两列自 V244 起全仓库没有一处 setter，任何一家店都开不出线下收款，
 * 闸门却全绿（TDD-线下收款商家开关 §0）。
 *
 * <p>与 {@link StoreFulfillmentService} 分开：那边是「东西怎么交付」，这边是「钱怎么付」，
 * 两个轴正交（见 {@code PayModes} 的类注释）。
 */
public interface StorePaySettingService {

    /** 本店的两个开关与主体资质。别家门店 = NOT_FOUND */
    PaySettingVO get(String entityNo, String storeNo);

    /**
     * 改开关。<b>null = 不改</b>。
     *
     * <ul>
     *   <li>开线下收款要主体有有效营业执照，否则 {@code OFFLINE_PAY_NOT_QUALIFIED} ——
     *       与「店没开」是两种不同的下一步（去补证 vs 点一下开关），所以不合成一个「不支持」</li>
     *   <li>关线下收款<b>连带关货到付款</b>：货到付款就是「自送 + 线下付」，没有前者后者无从谈起</li>
     *   <li>线下收款没开而要开货到付款 = 400</li>
     * </ul>
     */
    PaySettingVO save(String entityNo, String storeNo, Boolean offlinePayEnabled, Boolean codEnabled);

    /**
     * @param qualified 主体有没有有效营业执照。端上据此在开关旁说明「先补证」，
     *                  而不是让商家点了开关再被拒
     */
    record PaySettingVO(String storeNo, boolean offlinePayEnabled, boolean codEnabled, boolean qualified) {
    }
}
