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
    record ChannelCmd(String channel, boolean enabled, String templateNo) {
    }

    /**
     * @param denied 准入矩阵不允许（按主体类型）。端上置灰＋原因，不隐藏
     * @param templateNo 仅 EXPRESS 非空
     */
    record ChannelVO(String channel, boolean enabled, boolean denied, String templateNo) {
    }

    record FulfillmentVO(String storeNo, List<ChannelVO> channels) {
    }

    /** 运营端行：多带门店名与状态，矩阵表头要用 */
    record StoreFulfillmentVO(String storeNo, String storeName, String storeStatus,
                              List<ChannelVO> channels) {
    }
}
