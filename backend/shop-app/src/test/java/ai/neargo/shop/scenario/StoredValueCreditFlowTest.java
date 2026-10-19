package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.support.StoredValueCreditFixture;
import ai.neargo.shop.pay.storedvalue.service.StoredValueCreditService;
import ai.neargo.shop.spi.entitlement.DecisionRef;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.CreditRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.RechargeState;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

/** Authoritative pay-owned receipt fixtures exercise posting, not the pending public/manual receipt workflow. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sv-credit;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class StoredValueCreditFlowTest {
    @Autowired StoredValueCreditService credits;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    private final EntitlementContext ctx = new EntitlementContext("MAIN", "CREDIT_WORKER", "CREDIT", "a".repeat(64));
    private String id() { return UUID.randomUUID().toString(); }
    private CreditRecharge received(String user, String entity, String store, long principal, long bonus) {
        return StoredValueCreditFixture.received(jdbc, json, user, entity, store, principal, bonus);
    }
    @Test void concurrentOriginalReceiptCreditsOnceAndBalancesEachPoolWithoutNewFeatureGate() throws Exception {
        String user = id(), entity = id(), store = id();
        var c = received(user, entity, store, 10000, 2000);
        var requests = new ArrayList<Callable<String>>();
        for (int n = 0; n < 20; n++) requests.add(() -> credits.credit(ctx, c).status().name());
        try (var pool = Executors.newFixedThreadPool(6)) {
            for (var result : pool.invokeAll(requests)) assertThat(result.get()).isEqualTo("CREDITED");
        }
        var first = credits.credit(ctx, c);
        assertThat(credits.credit(ctx, c)).isEqualTo(first);
        assertThat(first.creditedTotalMinor()).isEqualTo(12000);
        assertThat(jdbc.queryForObject("SELECT principal_minor FROM sv_account WHERE user_no=?", Long.class, user))
                .isEqualTo(10000);
        assertThat(jdbc.queryForObject("SELECT bonus_minor FROM sv_account WHERE user_no=?", Long.class, user))
                .isEqualTo(2000);
        assertThat(jdbc.queryForObject("SELECT account_seq FROM sv_account WHERE user_no=?", Long.class, user)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_voucher WHERE biz_no=?", Integer.class, c.rechargeNo()))
                .isOne();
        assertThat(jdbc.queryForObject("SELECT principal_minor FROM sv_lot WHERE recharge_no=?", Long.class,
                c.rechargeNo())).isEqualTo(10000);
        assertThat(jdbc.queryForObject("SELECT SUM(e.principal_delta-v.principal_delta) FROM sv_entry e "
                + "JOIN sv_voucher v ON v.tenant_no=e.tenant_no AND v.voucher_no=e.voucher_no WHERE v.biz_no=?",
                Long.class, c.rechargeNo())).isZero();
        String voucher = jdbc.queryForObject("SELECT voucher_no FROM sv_voucher WHERE biz_no=?", String.class,
                c.rechargeNo());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_posting_line WHERE voucher_no=?", Integer.class,
                voucher)).isEqualTo(4);
        assertThat(jdbc.queryForList("SELECT pool,SUM(CASE WHEN side='DEBIT' THEN amount_minor ELSE -amount_minor END) "
                + "AS net FROM sv_posting_line WHERE voucher_no=? GROUP BY pool", voucher))
                .allSatisfy(row -> assertThat(((Number) row.get("net")).longValue()).isZero());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_user_store_index WHERE user_no=? AND has_recharge=1",
                Integer.class, user)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_ent_outbox WHERE aggregate_no=?", Integer.class,
                c.rechargeNo())).isOne();
        assertThat(json.writeValueAsString(first)).doesNotContain("principal", "bonus", "receiptRef", "receiptDigest");
    }
    @Test void failedCreditOutboxRollsBackAllPostingAndKeepsReceivedPromiseForRetry() {
        String user = id(), entity = id();
        var c = received(user, entity, id(), 5000, 0);
        jdbc.execute("ALTER TABLE pay_ent_outbox ADD CONSTRAINT reject_sv_credit CHECK (aggregate_no<>'"
                + c.rechargeNo() + "')");
        try { assertThatThrownBy(() -> credits.credit(ctx, c)).isInstanceOf(RuntimeException.class); }
        finally { jdbc.execute("ALTER TABLE pay_ent_outbox DROP CONSTRAINT reject_sv_credit"); }
        assertThat(jdbc.queryForObject("SELECT status FROM sv_recharge WHERE recharge_no=?", String.class,
                c.rechargeNo())).isEqualTo("RECEIVED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_account WHERE user_no=?", Integer.class, user)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_lot WHERE recharge_no=?", Integer.class,
                c.rechargeNo())).isZero();
        assertThat(credits.credit(ctx, c).status()).isEqualTo(RechargeState.CREDITED);
    }
    @Test void originalDecisionIsRequiredAndFrozenAccountAcceptsOwedCreditWithoutCrossingOwners() {
        String user = id(), entity = id(), store = id();
        var c = received(user, entity, store, 3000, 300);
        var forged = new DecisionRef("WRONG", c.decisionRef().occurredAt(), 0,
                DecisionRef.DecisionType.RECHARGE_RECEIVED);
        assertThatThrownBy(() -> credits.credit(ctx, new CreditRecharge(c.rechargeNo(), c.sourceRef(), forged)))
                .isInstanceOf(BizException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_account WHERE user_no=?", Integer.class, user)).isZero();
        assertThat(credits.credit(ctx, c).status()).isEqualTo(RechargeState.CREDITED);
        jdbc.update("UPDATE sv_account SET status='FROZEN' WHERE user_no=?", user);
        var second = received(user, entity, store, 4000, 400);
        credits.credit(ctx, second);
        assertThat(jdbc.queryForObject("SELECT account_seq FROM sv_account WHERE user_no=?", Long.class, user))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT principal_minor+bonus_minor FROM sv_account WHERE user_no=?",
                Long.class, user)).isEqualTo(7700);
        var anotherOwner = received(user, id(), id(), 1000, 0);
        credits.credit(ctx, anotherOwner);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_account WHERE user_no=?", Integer.class, user))
                .isEqualTo(2);
    }
    @Test void currenciesRemainSeparateAndAccumulatedAccountOverflowRollsBackOnlyTheNewCredit() {
        String user = id(), entity = id(), store = id();
        var local = received(user, entity, store, 1000, 0);
        var foreign = StoredValueCreditFixture.received(jdbc, json, user, entity, store, 2000, 0, "USD");
        credits.credit(ctx, local); credits.credit(ctx, foreign);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_account WHERE user_no=?", Integer.class, user))
                .isEqualTo(2);
        String overflowUser = id();
        var maximum = received(overflowUser, entity, store, Long.MAX_VALUE - 10, 0);
        credits.credit(ctx, maximum);
        var extra = received(overflowUser, entity, store, 20, 0);
        assertThatThrownBy(() -> credits.credit(ctx, extra)).isInstanceOf(BizException.class);
        assertThat(jdbc.queryForObject("SELECT principal_minor FROM sv_account WHERE user_no=?", Long.class,
                overflowUser)).isEqualTo(Long.MAX_VALUE - 10);
        assertThat(jdbc.queryForObject("SELECT status FROM sv_recharge WHERE recharge_no=?", String.class,
                extra.rechargeNo())).isEqualTo("RECEIVED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_lot WHERE recharge_no=?", Integer.class,
                extra.rechargeNo())).isZero();
    }

}
