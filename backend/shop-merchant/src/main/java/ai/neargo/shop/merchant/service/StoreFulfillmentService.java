package ai.neargo.shop.merchant.service;

import java.util.List;

/**
 * 门店送货方式（方案 v4 §四/§六）：每店每路一行的开关与配置。
 *
 * <p>与 {@link MerchantStoreService} 分开：那边是门面文案与经营范围（主体级），
 * 这边是履约能力（门店级）。揉在一个 Service 里，正是这次要拆掉的那张「一张卡」。
 */
public interface StoreFulfillmentService {

    /**
     * 门店履约全景。四路各返回一行（库里没有的路补 enabled=false 的虚行）——
     * 端上按固定四行渲染开关，不用自己补缺。
     *
     * @param storeNo 空 = 默认门店（与订单履约「空 → 默认门店」同一口径）
     */
    FulfillmentVO get(String merchantNo, String storeNo);

    /**
     * 全量保存。校验见实现：值域、门槛（自提要有地址）、至少一路、准入矩阵。
     * 「关一路」是 {@code enabled=0} 不是删行 —— 配置原地保留。
     */
    FulfillmentVO save(String merchantNo, String storeNo, List<ChannelCmd> channels);

    /**
     * 主体下全部门店的履约配置（含停用门店）—— 运营端商家详情「履约配置」只读视图。
     * 治理视角更不能看不见停用的店。
     */
    List<StoreFulfillmentVO> byMerchant(String merchantNo);

    /** @param templateNo 仅 EXPRESS 有意义；空 = 平台默认模板 */
    /**
     * @param pickupNos 仅 NEIGHBOR_PICKUP：这一路的取货点引用，<b>全量替换</b>；null = 不改
     */
    record ChannelCmd(String channel, boolean enabled, String templateNo, List<String> pickupNos,
                      /** P2：ALL / SUBSET；null = 不改。EXPRESS 不允许 SUBSET */
                      String scopeMode,
                      /** P2：SUBSET 时适用的范围项 area_no，全量替换；null = 不改 */
                      List<String> areaNos) {
    }

    /**
     * @param denied 准入矩阵不允许（按主体类型）。端上置灰＋原因，不隐藏
     * @param templateNo 仅 EXPRESS 非空
     */
    record ChannelVO(String channel, boolean enabled, boolean denied, String templateNo,
                     /** 仅 NEIGHBOR_PICKUP：已引用的取货点，含 PENDING 的自建点（让商家看到「审核中」） */
                     List<PickupRef> pickups,
                     /** 运营锁路（P2）：买家侧不可选，商家侧置灰不可改 */
                     boolean locked,
                     /** ALL / SUBSET（P2） */
                     String scopeMode,
                     /** SUBSET 时适用的范围项 area_no（P2） */
                     List<String> areaNos) {
    }

    /** 运营锁路/解锁（P2）。不存在的 channel 行会先建一行（enabled=0）再锁，锁态不依赖商家配过 */
    void setLocked(String storeNo, String channel, boolean locked);

    /** 取货点引用的展示面。status 来自 cmt_pickup_point：只有 ACTIVE 参与买家侧 */
    record PickupRef(String pickupNo, String name, String address, String type, String status) {
    }

    record FulfillmentVO(String storeNo, List<ChannelVO> channels) {
    }

    /** 运营端行：多带门店名与状态，矩阵表头要用 */
    record StoreFulfillmentVO(String storeNo, String storeName, String storeStatus,
                              List<ChannelVO> channels) {
    }
}
