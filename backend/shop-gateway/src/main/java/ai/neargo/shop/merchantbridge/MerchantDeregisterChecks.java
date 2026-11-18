package ai.neargo.shop.merchantbridge;

import ai.neargo.shop.merchant.service.MerchantAccountService.Blocker;
import ai.neargo.shop.merchant.service.MerchantStaffService;
import ai.neargo.shop.spi.trade.OpenOrderPort;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * What stops an owner from deleting their account (TDD-商户App统一登录与账号 endpoint 7, D14 option B).
 *
 * <p>Stores themselves are not a blocker: deletion freezes them read-only. What must be settled first is
 * money and goods in flight — open store orders, bills not yet paid out, paid plan periods still running —
 * plus the buyer-side check the mini-program always had.
 */
@Component
public class MerchantDeregisterChecks {

    public static final String OPEN_ORDERS = "OPEN_ORDERS";
    public static final String PENDING_PAYOUT = "PENDING_PAYOUT";
    public static final String PAID_PLAN_ACTIVE = "PAID_PLAN_ACTIVE";
    public static final String BUYER_OPEN_ORDERS = "BUYER_OPEN_ORDERS";

    private final MerchantStaffService staff;
    private final OpenOrderPort openOrders;
    private final NamedParameterJdbcTemplate platform;
    private final ObjectProvider<DataSource> payDataSource;

    public MerchantDeregisterChecks(MerchantStaffService staff, OpenOrderPort openOrders,
                                    NamedParameterJdbcTemplate platform,
                                    @Qualifier("payDataSource") ObjectProvider<DataSource> payDataSource) {
        this.staff = staff;
        this.openOrders = openOrders;
        this.platform = platform;
        this.payDataSource = payDataSource;
    }

    public List<Blocker> blockers(String userNo) {
        List<Blocker> out = new ArrayList<>();
        List<String> stores = staff.ownedStoreNos(userNo);
        List<String> entities = staff.ownedEntityNos(userNo);

        List<String> busy = new ArrayList<>();
        long open = 0;
        for (String store : stores) {
            long n = openOrders.countOpenByStore(store) + openOrders.countOpenAfterSalesByStore(store);
            if (n > 0) {
                busy.add(store);
                open += n;
            }
        }
        if (open > 0) out.add(new Blocker(OPEN_ORDERS, (int) open, busy));

        long pending = pendingPayouts(entities);
        if (pending > 0) out.add(new Blocker(PENDING_PAYOUT, (int) pending, List.of()));

        List<String> paid = paidPlanStores(stores);
        if (!paid.isEmpty()) out.add(new Blocker(PAID_PLAN_ACTIVE, paid.size(), paid));

        if (openOrders.hasOpenOrders(userNo)) out.add(new Blocker(BUYER_OPEN_ORDERS, 1, List.of()));
        return Collections.unmodifiableList(out);
    }

    /** Bills of the owned entities that have not been split to the merchant yet. */
    private long pendingPayouts(List<String> entities) {
        DataSource ds = payDataSource.getIfAvailable();
        if (entities.isEmpty() || ds == null) return 0;
        Long n = new NamedParameterJdbcTemplate(ds).queryForObject(
                "SELECT COUNT(*) FROM stl_bill WHERE deleted = 0 AND entity_no IN (:e)"
                        // Everything but the settled end states: self-operated bills awaiting payment
                        // (PENDING_RECON / CONFIRMED), splits not yet confirmed, retries and manual cases.
                        + " AND status NOT IN ('PAID','SPLIT_CONFIRMED','REVERSED','OFFLINE_SETTLED')",
                new MapSqlParameterSource("e", entities), Long.class);
        return n == null ? 0 : n;
    }

    private volatile Boolean commerceInstalled;

    /**
     * The commerce schema is migrated by its own runner and is absent where the plan module is not
     * deployed; such a deployment sells no plans. Any other SQL failure propagates.
     */
    private boolean commerceInstalled() {
        Boolean known = commerceInstalled;
        if (known != null) return known;
        boolean found = Boolean.TRUE.equals(platform.getJdbcTemplate().execute(
                (org.springframework.jdbc.core.ConnectionCallback<Boolean>) c -> {
                    var meta = c.getMetaData();
                    for (String name : new String[] {"mch_subscription_period", "MCH_SUBSCRIPTION_PERIOD"}) {
                        try (var rs = meta.getTables(c.getCatalog(), null, name, new String[] {"TABLE"})) {
                            if (rs.next()) return true;
                        }
                    }
                    return false;
                }));
        commerceInstalled = found;
        return found;
    }

    /** Owned stores with a cash-paid plan period that has not ended. */
    private List<String> paidPlanStores(List<String> stores) {
        if (stores.isEmpty() || !commerceInstalled()) return List.of();
        // NORMAL and FROZEN periods are still owed time; CONVERTED / REFUNDED / REVOKED are settled.
        return platform.queryForList(
                "SELECT DISTINCT store_no FROM mch_subscription_period WHERE deleted = 0 AND store_no IN (:s)"
                        + " AND state IN ('NORMAL','FROZEN') AND cash_basis_minor > 0 AND effective_end_at > :now",
                new MapSqlParameterSource("s", stores).addValue("now", System.currentTimeMillis()), String.class);
    }
}
