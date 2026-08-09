package ai.neargo.shop.platform.dto;

/**
 * 行业主数据。
 *
 * @param industry           行业码，与 shared 的 {@code Industry} 联合类型同值
 * @param name               展示名
 * @param sort               排序
 * @param enabled            是否可选。停用只影响**新入驻**，存量商家不受影响
 * @param wechatMicroAllowed 微信能否以小微主体进件。**默认 false** —— 默认允许等于默认让商家撞墙
 * @param alipayMicroAllowed 支付宝同上。⚠️ 支付宝的行业限制尚未确认，故一律 false
 * @param pointsForced       该行业是否强制开启积分
 * @param remark             给运营看的说明：为什么是这个准入结论
 * @param merchantCount      该行业下的商家数，改准入前要知道影响面
 */
public record IndustryVO(
        String industry,
        String name,
        int sort,
        boolean enabled,
        boolean wechatMicroAllowed,
        boolean alipayMicroAllowed,
        boolean pointsForced,
        String remark,
        long merchantCount) {
}
