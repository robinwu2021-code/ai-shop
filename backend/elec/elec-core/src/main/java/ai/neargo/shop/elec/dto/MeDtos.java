package ai.neargo.shop.elec.dto;

/**
 * {@code GET /elec/me}：一个账号在买家与供应商两面的<b>身份与角标</b>。
 *
 * <p>它是「两面数据各走各的前缀」这条规则的唯一例外，所以<b>只给状态与计数，不给任何一面的内容</b> ——
 * 想看询价走 {@code /elec/c/**}，想看求购与库存走 {@code /elec/b/**}。
 */
public final class MeDtos {

    private MeDtos() {
    }

    /**
     * @param phoneBound 绑没绑手机号。询价与成为供应商都要先绑（没绑那两个接口回 90001）
     * @param supplier   不是供应商为 null —— 端上据此显示「成为供应商」还是「供应商工作台」
     */
    public record MeView(String userNo, boolean phoneBound, SupplierBrief supplier, Badges badges) {
    }

    /**
     * 供应商身份的摘要。完整档案走 {@code GET /elec/b/supplier}。
     *
     * @param status ACTIVE / SUSPENDED。暂停只关供应商面，买家面照常
     */
    public record SupplierBrief(String supplierNo, String companyName, String status, String maskCode) {
    }

    /**
     * 两面的角标。买家那一个人人都有；不是供应商、或供应商被暂停时，供应商那两个恒为 0 —— 他进不去那两个列表，给数字只会让他点进去碰壁。
     *
     * @param rfqNewOffers    买家：还在询价中、有他没看过的报价的单子数（打开详情即算看过；供应商改价不算新）
     * @param dispatchPending 派给我、还没回话的求购（SENT + VIEWED）
     * @param stockExpiring   7 天内到期的在售库存行数（与工作台「快到期」同一个口径）
     */
    public record Badges(int rfqNewOffers, int dispatchPending, int stockExpiring) {
    }
}
