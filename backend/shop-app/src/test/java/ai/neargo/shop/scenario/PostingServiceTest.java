package ai.neargo.shop.scenario;

import ai.neargo.shop.pay.points.model.AccountKey;
import ai.neargo.shop.pay.points.model.Bucket;
import ai.neargo.shop.pay.points.model.EvidenceRef;
import ai.neargo.shop.pay.points.model.FundBucket;
import ai.neargo.shop.pay.points.model.FundMove;
import ai.neargo.shop.pay.points.model.HolderType;
import ai.neargo.shop.pay.points.model.PointsMove;
import ai.neargo.shop.pay.points.model.RedeemRate;
import ai.neargo.shop.pay.points.model.PositionMove;
import ai.neargo.shop.pay.points.model.ReserveMove;
import ai.neargo.shop.pay.points.model.Voucher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Uses the application's real pay mapper and transaction manager, never an in-memory posting substitute. */
@SpringBootTest
@ActiveProfiles("test")
class PostingServiceTest {
    @Autowired private ApplicationContext context;

    @Test
    void replay_conflict_and_stable_lock_order() {
        String type = fixture();
        Voucher first = topup(type, "FUND:" + type, "E:" + type, 100);
        Object posted = post(first);
        Object replayed = post(first);
        assertThat(read(posted, "replayed")).isEqualTo(false);
        assertThat(read(replayed, "replayed")).isEqualTo(true);
        assertThat(jdbc().queryForObject("SELECT u_minor FROM pts_fund_pool WHERE type_code=?", Long.class, type))
                .isEqualTo(100L);
        assertThatThrownBy(() -> post(topup(type, first.bizRef(), "E:" + type, 90)))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM pts_voucher WHERE type_code=?", Long.class, type))
                .isEqualTo(1L);
    }

    @Test
    void failed_account_condition_rolls_back_voucher_and_funds() {
        String type = fixture();
        Voucher invalid = new Voucher("GRANT:" + type, type, "CNY", new RedeemRate(1, 1),
                List.of(move(type, HolderType.ISSUE, Bucket.ISSUABLE, -100),
                        move(type, HolderType.USER, Bucket.PENDING, 100)),
                List.of(new FundMove(FundBucket.U, -100), new FundMove(FundBucket.C, 100)),
                List.of(), 0, List.of(), "{}");
        assertThatThrownBy(() -> post(invalid)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM pts_voucher WHERE type_code=?", Long.class, type))
                .isZero();
        assertThat(jdbc().queryForObject("SELECT c_minor FROM pts_fund_pool WHERE type_code=?", Long.class, type))
                .isZero();
    }

    @Test
    void position_limit_condition_rolls_back_the_new_head() {
        String type = fixture();
        String position = "P:" + type;
        Voucher invalid = new Voucher("POS:" + type, type, "CNY", new RedeemRate(1, 1),
                List.of(), List.of(), List.of(new PositionMove(PositionMove.Kind.REDEEM, position,
                -1, "NEW", "HELD", 100, 101, "{}")), 0, List.of(), "{}");
        assertThatThrownBy(() -> post(invalid)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM pts_redeem_position WHERE position_no=?",
                Long.class, position)).isZero();
    }

    @Test
    void reserve_cannot_exist_without_its_redeem_position() {
        String type = fixture();
        post(new Voucher("B:" + type, type, "CNY", new RedeemRate(1, 1), List.of(),
                List.of(new FundMove(FundBucket.B, 100)), List.of(), 0,
                List.of(new EvidenceRef("E:" + type, "CNY", 100)), "{}"));
        Voucher reserve = new Voucher("RES:" + type, type, "CNY", new RedeemRate(1, 1),
                List.of(), List.of(), List.of(), 100, List.of(), "{}",
                List.of(new ReserveMove("R:" + type, "NO-POSITION", -1, 100, 100, 0, 0)));
        assertThatThrownBy(() -> post(reserve)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc().queryForObject("SELECT reserved_b_minor FROM pts_fund_pool WHERE type_code=?",
                Long.class, type)).isZero();
    }

    private String fixture() {
        // Presence of the production bean is checked before any fixture SQL, so missing implementation is a test failure.
        assertThat(context.containsBean("pointsPostingService")).as("real posting service is required").isTrue();
        String type = "PT" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        jdbc().update("INSERT INTO pts_point_type(type_code,name,market,currency,rate_points,rate_minor,status)"
                + " VALUES(?,?,'CN','CNY',1,1,'ACTIVE')", type, type);
        jdbc().update("INSERT INTO pts_fund_pool(type_code,currency) VALUES(?,'CNY')", type);
        jdbc().update("INSERT INTO pts_fund_evidence(evidence_no,currency,external_minor,verified) VALUES(?,'CNY',100,1)",
                "E:" + type);
        return type;
    }

    private Voucher topup(String type, String ref, String evidence, long amount) {
        return new Voucher(ref, type, "CNY", new RedeemRate(1, 1),
                List.of(move(type, HolderType.MINT, Bucket.ISSUED, -amount),
                        move(type, HolderType.ISSUE, Bucket.ISSUABLE, amount)),
                List.of(new FundMove(FundBucket.U, amount)), List.of(), 0,
                List.of(new EvidenceRef(evidence, "CNY", amount)), "{\"funded\":100}");
    }

    private PointsMove move(String type, HolderType holder, Bucket bucket, long delta) {
        return new PointsMove(new AccountKey(type, holder, holder == HolderType.USER ? "U" : "PLATFORM", bucket), delta);
    }

    private JdbcTemplate jdbc() { return new JdbcTemplate(context.getBean("payDataSource", DataSource.class)); }
    private Object post(Voucher voucher) {
        try { return context.getBean("pointsPostingService").getClass().getMethod("post", Voucher.class)
                .invoke(context.getBean("pointsPostingService"), voucher); }
        catch (InvocationTargetException e) { throw (RuntimeException) e.getCause(); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private Object read(Object value, String property) {
        try { return value.getClass().getMethod(property).invoke(value); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
}
