package ai.neargo.shop.merchant.service;

import ai.neargo.shop.merchant.dto.StoreProfileVO;

import java.util.List;

/**
 * 店铺资料（B-11.2）。商家自己维护的门面 + 经营范围。
 *
 * <p>它横跨两张表（门面在 {@code mch_store}，范围在 {@code mch_entity}
 * 与 {@code mch_entity_community}），对前端是一份资料。拆表的理由见 V30 迁移。
 */
public interface MerchantStoreService {

    /** 读；从没保存过时返回各字段为空的默认资料，<b>不是 null</b> —— 新店打开设置页应当看到空表单而不是报错。 */
    StoreProfileVO profile(String merchantNo);

    /**
     * 保存。
     *
     * <p><b>scope=COMMUNITY 且覆盖社区为空时拒绝保存</b>（ADR-009）。
     * 这条规则在入驻审核那边也有一份，两处必须一致 —— 否则商家可以入驻时配好范围，
     * 转头在店铺设置里把社区清空，然后货就对谁都不可见了，而这中间没有任何提示。
     */
    StoreProfileVO save(String merchantNo, SaveCommand cmd);

    /**
     * 覆盖社区全量替换。审核与店铺设置<b>共用这一处实现</b> ——
     * 「空覆盖 = 对谁都不可见」这条规则只有一份代码，才不会两条路径各写各的。
     */
    void syncCommunities(String merchantNo, List<String> communityNos);

    /**
     * 当前门店的配送规则。**没配过时返回默认值而不是空** ——
     * 端上拿到 null 会渲染出四个空输入框，店主以为功能坏了。
     */
    DeliveryRuleVO deliveryRule(String merchantNo, String storeNo);

    /** 保存配送规则。免配送费门槛低于起送价是无意义配置，直接拒。 */
    DeliveryRuleVO saveDeliveryRule(String merchantNo, String storeNo, DeliveryRuleVO rule);

    /**
     * @param radius             配送半径（米）
     * @param minOrderMinor      起送价（分），0 = 不设门槛
     * @param feeMinor           配送费（分），0 = 免费送
     * @param freeThresholdMinor 免配送费门槛（分），0 = 不免
     */
    record DeliveryRuleVO(int radius, long minOrderMinor, long feeMinor, long freeThresholdMinor) {
    }

    /**
     * @param serviceScope        @deprecated 旧三档。端上改用 fulfillmentReach + serviceAreas 之后
     *                            这两个字段留空即可 —— 传了也只在 areas 为空时兜底
     * @param fulfillmentReach    履约能力（ADR-013）。为空按 PICKUP
     * @param serviceAreas        地理覆盖项，**全量替换**：勾选面板上的就是最终结果
     */
    /**
     * @param latE6 门店坐标（gcj02，E6）。<b>null = 这次不改</b> —— 老版本端上不传这个字段，
     *              缺省当清空会把已标好的点抹掉，且保存那一下看不出来
     */
    record SaveCommand(String announcement, String openHours, String address,
                       List<String> featured, String serviceScope,
                       List<String> serviceCommunityNos, String serviceCityCode,
                       String fulfillmentReach, List<AreaCommand> serviceAreas,
                       Integer latE6, Integer lngE6) {

        /** 不带坐标的老形状（= 这次不改坐标） */
        public SaveCommand(String announcement, String openHours, String address,
                           List<String> featured, String serviceScope,
                           List<String> serviceCommunityNos, String serviceCityCode,
                           String fulfillmentReach, List<AreaCommand> serviceAreas) {
            this(announcement, openHours, address, featured, serviceScope, serviceCommunityNos,
                    serviceCityCode, fulfillmentReach, serviceAreas, null, null);
        }
    }

    /** 一条覆盖项的入参。名字由后端补，端上不用传 */
    record AreaCommand(String level, String refCode) {
    }
}
