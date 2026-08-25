package ai.neargo.shop.merchant.dto;

/**
 * 一张<b>证照</b>（库里叫 {@code mch_entity}，对外不叫「主体」「实体」—— 老板不认识那两个词）。
 *
 * @param entityNo   证照号
 * @param name       执照上的名称。<b>待补证照时它是老板随手填的店名</b> ——
 *                   补齐执照、审核通过后被执照上的正式名称覆盖
 * @param status     {@code ACTIVE} 营业中 / {@code PENDING_LICENSE} 待补证照 /
 *                   {@code SUSPENDED}、{@code BANNED} 已停业。
 *                   <b>端上照它给下一步</b>：待补证照给「去补执照」，已停业给客服入口
 * @param verified   平台已认证。{@code PENDING_LICENSE} 恒为 false ——
 *                   「已认证」标是审核给的，不能自己开店就带上
 * @param legalForm  个体户 / 有限公司…… <b>待补证照时为空</b>，那时还不知道是哪种
 * @param storeCount 这张证照下<b>我能进</b>的门店数（含停用的 —— 看不见的话商家会以为店被删了）。
 *                   老板 = 这张证照的全部门店；店员 = 只数被授权到的那几家，
 *                   与门店列表本身同一口径，两个数对不上会让人以为「有几家店没显示出来」
 * @param isPrimary  默认证照。不带 {@code X-Store-No} 时解析到的就是它
 * @param canManage  我是不是这张证照的老板。<b>只有老板能改资料、交执照、挂收款号</b>；
 *                   被邀请去别人店里当店员的人拿不到这一位（而他压根不该在列表里看到这张证照）
 */
public record EntityVO(String entityNo, String name, String status, boolean verified,
                       String legalForm, int storeCount, boolean isPrimary, boolean canManage) {
}
