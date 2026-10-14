package ai.neargo.shop.trade.dto;

import java.util.List;

/**
 * 免登录看件视图（TDD-收件人物流触达与分享裂变 §3、§8 决策 A「收窄视图」）。
 *
 * <p><b>这是一份刻意删过的订单详情</b>：发给收件人的看件链接会被转发、截图，
 * 所以这里只放收件人**该看、也只该看**的东西 —— 货到哪了、几件、哪家店发的、送到哪。
 * 价格、买家是谁、优惠花了多少、积分、支付信息，一律不进这个 VO：
 * 不是前端不显示，是后端根本不下发（前端藏得住、接口抓包藏不住）。
 *
 * <p>与 {@link OrderVO} 的关系：复用它的 {@link OrderVO.Trace}（物流轨迹的契约端上已有渲染），
 * 其余字段全部收窄。手机号**掩码**下发（{@code 138****8000}）—— 收件人自己认得出，
 * 转发出去的人认不全。
 */
public record TrackVO(
        String subOrderNo,
        /** 契约抽象状态（{@code PAID/FULFILLING/SHIPPED/...}），与订单详情同一口径 */
        String status,
        String fulfillment,
        /** 发货门店名。空=未解析到（停业/删店），端上退化成「商家」 */
        String storeName,
        String receiverName,
        /** 掩码后的收件号，非明文 */
        String receiverPhoneMasked,
        String receiverAddress,
        String expressCompany,
        String expressNo,
        List<Item> items,
        OrderVO.Trace trace) {

    /** 商品摘要：名字、规格、图、件数 —— **不含价格**。 */
    public record Item(String title, String cover, String spec, int qty) {
    }
}
