package ai.neargo.shop.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 门店送货方式：每店每路一行（方案 v4，取代 {@code mch_entity.fulfillment_reach} 单选）。
 *
 * <p><b>挂门店不挂主体</b>：履约的邻居们全在门店维度——子单 {@code store_no}、
 * 每店货架 {@code prd_store_goods}、自送费率 {@code mch_store.delivery_*}、
 * 自提点 {@code owner_ref=store_no}。唯独开关吊在主体上才是不协调的那个。
 *
 * <p>本表走物理删除（照 {@code MchServiceArea} 先例——逻辑删＋业务唯一键的
 * revive bug 已修过四次）。「关掉一路」是 {@code enabled=0} 不是删行：
 * 配置（模板号、取货点引用）原地保留，再打开原样回来。
 */
@Getter
@Setter
@TableName("mch_fulfillment_channel")
public class MchFulfillmentChannel extends BaseEntity {

    private String storeNo;

    /** 冗余自门店：鉴权与主体级汇总（可见性并集）不用连表 */
    private String entityNo;

    /**
     * {@link ai.neargo.shop.common.Fulfillments} 值域的商家可配子集：
     * STORE_PICKUP / NEIGHBOR_PICKUP / MERCHANT_DELIVERY / EXPRESS。
     * STORE_VERIFY / APPOINTMENT 是服务类商品属性，写入即拒。
     */
    private String channel;

    private Boolean enabled;

    /** ALL 继承整个经营范围 / SUBSET 收窄（P2）。EXPRESS 恒为 ALL */
    private String scopeMode;

    /** JSON，按 channel 各取所需：EXPRESS 存 {"templateNo":"…"}。自送费率在 mch_store，不在这里 */
    private String config;

    public static final String SCOPE_ALL = "ALL";
    public static final String SCOPE_SUBSET = "SUBSET";
}
