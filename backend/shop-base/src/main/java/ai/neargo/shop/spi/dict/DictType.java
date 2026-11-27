package ai.neargo.shop.spi.dict;

/**
 * 字典类型，对应基础能力库 {@code sys_dict_item.dict_type}。
 *
 * <p>只列已经有调用方走 {@link DictionaryPort} 的类型；设计里的其余类型（主体类型、市场、区划、计量单位…）
 * 收敛时再加，不预先占位 —— 没有来源的类型查出来恒为空，会被误当成“配置为空”。
 */
public enum DictType {
    /** 经营类型与子行业：一棵树，行业行即经营类型，子行业挂在下面。旧表 {@code sys_store_industry}、{@code sys_business_type} */
    BUSINESS_TYPE,
    /** 进件行业：通道准入口径（能否小微、是否强制积分）。旧表 {@code sys_industry}；与经营类型不是同一概念 */
    ONBOARD_INDUSTRY,
    /** 支付渠道：运营维护、可开关的渠道。旧表 {@code sys_pay_channel}；代码里的非渠道码见 {@code PayChannels} */
    PAY_CHANNEL
}
