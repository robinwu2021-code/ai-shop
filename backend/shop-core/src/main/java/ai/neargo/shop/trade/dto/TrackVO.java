package ai.neargo.shop.trade.dto;

import java.util.List;

/**
 * 免登录看件视图（TDD-收件人物流触达与分享裂变 §3、§8 决策 A「收窄视图」）。
 *
 * <p><b>这是一份刻意删过的订单详情</b>：发给收件人的看件链接会被转发、截图，
 * 所以这里只放收件人**该看、也只该看**的东西 —— 货到哪了、几件、哪家店发的、送到哪。
 * 匿名视图不包含买家身份、价格、优惠、积分或支付信息。
 * 仅验证过收件手机号的视图可以附带下单人昵称，不返回其手机号或账号号。
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
        OrderVO.Trace trace,
        /** 仅核验收件手机号后提供；匿名视图为 null。 */
        String buyerNickname, String receiverSalutation) {

    public TrackVO(String subOrderNo, String status, String fulfillment, String storeName,
                   String receiverName, String receiverPhoneMasked, String receiverAddress,
                   String expressCompany, String expressNo, List<Item> items, OrderVO.Trace trace,
                   String buyerNickname) {
        this(subOrderNo, status, fulfillment, storeName, receiverName, receiverPhoneMasked,
                receiverAddress, expressCompany, expressNo, items, trace, buyerNickname, null);
    }

    /** 核验收件手机号后追加下单人昵称，保持匿名视图默认不披露身份。 */
    public TrackVO withBuyerNickname(String nickname) {
        return new TrackVO(subOrderNo, status, fulfillment, storeName, receiverName, receiverPhoneMasked,
                receiverAddress, expressCompany, expressNo, items, trace, nickname, receiverSalutation);
    }

    /** 商品摘要：名字、规格、图、件数 —— **不含价格**。 */
    public record Item(String title, String cover, String spec, int qty) {
    }
}
