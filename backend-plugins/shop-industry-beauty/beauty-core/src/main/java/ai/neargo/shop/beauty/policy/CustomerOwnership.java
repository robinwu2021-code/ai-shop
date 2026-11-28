package ai.neargo.shop.beauty.policy;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.beauty.entity.BtyAppointment;
import ai.neargo.shop.beauty.entity.BtyGuestClaimReceipt;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

/** Receipt ownership is established by an earlier explicit verification, never a live guess by guest number. */
public final class CustomerOwnership {
    private final BeautyMappers.GuestClaimReceiptMapper receipts;
    public CustomerOwnership(BeautyMappers.GuestClaimReceiptMapper receipts) { this.receipts = receipts; }
    public boolean owns(BtyAppointment root, String user) {
        if (root == null || !"MAIN".equals(root.getTenantNo()) || !Integer.valueOf(0).equals(root.getDeleted())) {
            return false;
        }
        if (root.getCustomerRef() != null) { return user.equals(root.getCustomerRef()); }
        if (root.getGuestRef() == null) { return false; }
        return DataScopeContext.executeWithoutScope(() -> receipts.selectCount(
                Wrappers.<BtyGuestClaimReceipt>lambdaQuery().eq(BtyGuestClaimReceipt::getTenantNo, "MAIN")
                        .eq(BtyGuestClaimReceipt::getEntityNo, root.getEntityNo())
                        .eq(BtyGuestClaimReceipt::getStoreNo, root.getStoreNo())
                        .eq(BtyGuestClaimReceipt::getGuestNo, root.getGuestRef())
                        .eq(BtyGuestClaimReceipt::getUserNo, user).eq(BtyGuestClaimReceipt::getDeleted, 0))) > 0;
    }
    /** 拼进查询的条件与参数（{@code {0}}、{@code {1}}… 占位，MyBatis-Plus 按参数绑定）。 */
    public record Predicate(String sql, Object[] params) { }

    /**
     * 「我的预约」的归属条件，用在分页查询与计数里、LIMIT 之前：本人的预约，或本人认领过的访客的预约。
     * 认领回执在行业库、预约在交易库（TDD-美业域迁移新库 B-5d），先在行业库查出本人认领的「主体 + 门店 + 访客」，
     * 再按值拼进交易库的查询；原来是交易库查询里 EXISTS 行业表，分库后会静默查不到。
     */
    public Predicate appointmentPredicate(String user) {
        var claimed = DataScopeContext.executeWithoutScope(() -> receipts.selectList(
                Wrappers.<BtyGuestClaimReceipt>lambdaQuery().eq(BtyGuestClaimReceipt::getTenantNo, "MAIN")
                        .eq(BtyGuestClaimReceipt::getUserNo, user).eq(BtyGuestClaimReceipt::getDeleted, 0)));
        var sql = new StringBuilder("(bty_appointment.customer_ref={0}");
        var params = new java.util.ArrayList<Object>(java.util.List.of(user));
        if (!claimed.isEmpty()) {
            sql.append(" OR (bty_appointment.customer_ref IS NULL AND (");
            for (int i = 0; i < claimed.size(); i++) {
                var receipt = claimed.get(i);
                int at = params.size();
                if (i > 0) sql.append(" OR ");
                sql.append("(bty_appointment.entity_no={").append(at).append("} AND bty_appointment.store_no={")
                        .append(at + 1).append("} AND bty_appointment.guest_ref={").append(at + 2).append("})");
                params.add(receipt.getEntityNo()); params.add(receipt.getStoreNo()); params.add(receipt.getGuestNo());
            }
            sql.append("))");
        }
        return new Predicate(sql.append(")").toString(), params.toArray());
    }
}
