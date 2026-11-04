package ai.neargo.shop.pay.service.impl;

import ai.neargo.shop.pay.service.PlatformMoneyHandoffService;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

import java.util.*;

import javax.sql.DataSource;

/** Pending handoff is committed in the same ledger row transition as actual cash success. */
@Service
@ConditionalOnProperty(
        prefix = "commerce",
        name = {"enabled", "mall.payment.enabled"},
        havingValue = "true")
public class PlatformMoneyHandoffServiceImpl implements PlatformMoneyHandoffService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public PlatformMoneyHandoffServiceImpl(
            @Qualifier("payDataSource") DataSource ds,
            @Qualifier("payTxManager") PlatformTransactionManager manager) {
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(manager);
    }

    private static void outside() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Cash handoff must run outside caller transactions");
    }

    public List<Handoff> pending(int limit) {
        return pending(null, limit);
    }

    public List<Handoff> pending(String afterPaymentNo, int limit) {
        outside();
        if (limit < 1 || limit > 500)
            throw new IllegalArgumentException("bounded handoff batch required");
        return tx.execute(
                s ->
                        jdbc.query(
                                "SELECT payment_no,direction,tenant_no,order_no FROM stl_payment"
                                    + " WHERE pay_scene='PLATFORM_MALL' AND status='SUCCESS' AND"
                                    + " platform_handoff_state='PENDING' AND payment_no>? ORDER BY"
                                    + " payment_no LIMIT ? FOR SHARE",
                                (r, n) ->
                                        new Handoff(
                                                r.getString(1),
                                                r.getString(2),
                                                r.getString(3),
                                                r.getString(4)),
                                afterPaymentNo == null ? "" : afterPaymentNo,
                                limit));
    }

    public void acknowledge(String paymentNo) {
        outside();
        tx.executeWithoutResult(
                s ->
                        jdbc.update(
                                "UPDATE stl_payment SET"
                                    + " platform_handoff_state='DONE',version=version+1 WHERE"
                                    + " payment_no=? AND pay_scene='PLATFORM_MALL' AND"
                                    + " status='SUCCESS' AND platform_handoff_state='PENDING'",
                                paymentNo));
    }
}
