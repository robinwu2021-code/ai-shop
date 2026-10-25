package ai.neargo.shop.scenario;

import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.numbering.DocumentType;
import ai.neargo.shop.numbering.store.DocumentNumberCoordinator;
import org.springframework.beans.factory.annotation.Qualifier;
import ai.neargo.shop.pay.mapper.SettleMappers.BillMapper;
import ai.neargo.shop.pay.points.model.AccountKey;
import ai.neargo.shop.pay.points.model.Bucket;
import ai.neargo.shop.pay.points.model.EvidenceRef;
import ai.neargo.shop.pay.points.model.FundBucket;
import ai.neargo.shop.pay.points.model.FundMove;
import ai.neargo.shop.pay.points.model.HolderType;
import ai.neargo.shop.pay.points.model.PointsMove;
import ai.neargo.shop.pay.points.model.RedeemRate;
import ai.neargo.shop.pay.points.model.Voucher;
import ai.neargo.shop.pay.points.posting.PointsPostingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/** A wrong mapper factory or transaction qualifier must change the actual committed balances. */
@SpringBootTest
@ActiveProfiles("test")
class PointsPayTransactionTest {
    @Autowired private ApplicationContext context;
    @Autowired private PointsPostingService posting;
    @Autowired private BillMapper bills;
    @Autowired @Qualifier("payDocumentNumberCoordinator") private DocumentNumberCoordinator numbers;

    @Test
    void settlement_and_points_roll_back_together() {
        String type = fundedUser();
        withReservedHold(() -> tx("payTxManager").executeWithoutResult(status -> {
            StlBill bill = new StlBill();
            bill.setSettleNo("PTX-" + type);
            bill.setSubOrderNo("S-" + type);
            bill.setOrderNo("O-" + type);
            bill.setEntityNo("M");
            bill.setGrossMinor(100L);
            bill.setNetMinor(100L);
            bill.setStatus(StlBill.PENDING);
            bills.insert(bill);
            posting.post(hold(type));
            assertThat(balance(type, "held_points")).isEqualTo(100L);
            status.setRollbackOnly();
        }));
        assertThat(balance(type, "available_points")).isEqualTo(100L);
        assertThat(balance(type, "held_points")).isZero();
        assertThat(pay().queryForObject("SELECT COUNT(*) FROM stl_bill WHERE settle_no=?", Long.class, "PTX-" + type))
                .isZero();
        assertThat(pay().queryForObject("SELECT COUNT(*) FROM pts_voucher WHERE biz_ref=?", Long.class, "H:" + type))
                .isZero();
    }

    @Test
    void platform_rollback_does_not_rollback_pay_hold() {
        String type = fundedUser();
        JdbcTemplate platform = new JdbcTemplate(context.getBean("dataSource", DataSource.class));
        withReservedHold(() -> tx("transactionManager").executeWithoutResult(status -> {
            platform.update("INSERT INTO ord_order(order_no,user_no,status,pay_amount,created_at,updated_at)"
                    + " VALUES(?,?,'PAID',100,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", "O-" + type, "U");
            posting.post(hold(type));
            status.setRollbackOnly();
        }));
        assertThat(platform.queryForObject("SELECT COUNT(*) FROM ord_order WHERE order_no=?", Long.class, "O-" + type))
                .isZero();
        assertThat(balance(type, "held_points")).isEqualTo(100L);
        assertThat(balance(type, "available_points")).isZero();
    }

    private void withReservedHold(Runnable action) {
        numbers.withReservation(Map.of(DocumentType.POINTS_VOUCHER, 1), () -> {
            action.run();
            return null;
        });
    }

    private String fundedUser() {
        String type = "TX" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        pay().update("INSERT INTO pts_point_type(type_code,name,market,currency,rate_points,rate_minor,status)"
                + " VALUES(?,?,'CN','CNY',1,1,'ACTIVE')", type, type);
        pay().update("INSERT INTO pts_fund_pool(type_code,currency) VALUES(?,'CNY')", type);
        pay().update("INSERT INTO pts_fund_evidence(evidence_no,currency,external_minor,verified)"
                + " VALUES(?,'CNY',100,1)", "E:" + type);
        posting.post(new Voucher("F:" + type, type, "CNY", new RedeemRate(1, 1),
                List.of(move(type, HolderType.MINT, "PLATFORM", Bucket.ISSUED, -100),
                        move(type, HolderType.ISSUE, "PLATFORM", Bucket.ISSUABLE, 100)),
                List.of(new FundMove(FundBucket.U, 100)), List.of(), 0,
                List.of(new EvidenceRef("E:" + type, "CNY", 100)), "{}"));
        posting.post(new Voucher("G:" + type, type, "CNY", new RedeemRate(1, 1),
                List.of(move(type, HolderType.ISSUE, "PLATFORM", Bucket.ISSUABLE, -100),
                        move(type, HolderType.USER, "U", Bucket.AVAILABLE, 100)),
                List.of(new FundMove(FundBucket.U, -100), new FundMove(FundBucket.C, 100)),
                List.of(), 0, List.of(), "{}"));
        return type;
    }
    private Voucher hold(String type) {
        return new Voucher("H:" + type, type, "CNY", new RedeemRate(1, 1),
                List.of(move(type, HolderType.USER, "U", Bucket.AVAILABLE, -100),
                        move(type, HolderType.USER, "U", Bucket.HELD, 100)),
                List.of(), List.of(), 0, List.of(), "{}");
    }
    private PointsMove move(String type, HolderType holder, String no, Bucket bucket, long points) {
        return new PointsMove(new AccountKey(type, holder, no, bucket), points);
    }
    private JdbcTemplate pay() { return new JdbcTemplate(context.getBean("payDataSource", DataSource.class)); }
    private TransactionTemplate tx(String name) {
        return new TransactionTemplate(context.getBean(name, PlatformTransactionManager.class));
    }
    private long balance(String type, String column) {
        return pay().queryForObject("SELECT " + column + " FROM pts_account WHERE type_code=? AND holder_type='USER'",
                Long.class, type);
    }
}
