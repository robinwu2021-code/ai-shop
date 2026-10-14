package ai.neargo.shop.scenario;

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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real posting and pay transactions prove R is an unpaid claim, while returned customer rights are backed by B. */
@SpringJUnitConfig(PointsPostingDatabaseTest.Config.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties={
        "spring.datasource.url=${points.jdbc.url:jdbc:h2:mem:points_refund_backing;MODE=MySQL;DATABASE_TO_LOWER=TRUE}",
        "spring.datasource.username=${points.jdbc.user:sa}","spring.datasource.password=${points.jdbc.password:}",
        "spring.datasource.driver-class-name=${points.jdbc.driver:org.h2.Driver}"
})
class RefundBackingTest {
    @Autowired private PointsPostingService posting;
    @Autowired @Qualifier("payDataSource") private DataSource dataSource;
    @BeforeAll void schema() { PointsPostingDatabaseTest.initializeSchema(dataSource); }
    @Test void failed_recovery_returns_funded_points_and_never_counts_r_as_cash() {
        String type="RB"+UUID.randomUUID().toString().replace("-", "").substring(0,20);
        jdbc().update("INSERT INTO pts_point_type(type_code,market,currency,rate_points,rate_minor,status)"
                + " VALUES(?,'CN','CNY',1,1,'DRAINING')",type);
        jdbc().update("INSERT INTO pts_fund_pool(type_code,currency) VALUES(?,'CNY')",type);
        posting.recordEvidence(new EvidenceRef("B:"+type,"CNY",100));
        posting.post(new Voucher("B:"+type,type,"CNY",new RedeemRate(1,1),List.of(),
                List.of(new FundMove(FundBucket.B,100)),List.of(),0,
                List.of(new EvidenceRef("B:"+type,"CNY",100)),"{}"));
        // Existing paid redemptions have retained receive rights; this fixture starts after that external payout.
        posting.post(new Voucher("PAID_FIXTURE:"+type,type,"CNY",new RedeemRate(1,1),List.of(
                move(type,HolderType.MINT,Bucket.ISSUED,-100),
                move(type,HolderType.RECEIVE,Bucket.REFUND_RESERVED,100)),List.of(),List.of(),0,List.of(),"{}"));
        Object receivable=receivable("R:"+type,-1,"NEW","OPEN",100,0);
        Voucher refund=voucher("REFUND:"+type,type,List.of(
                move(type,HolderType.RECEIVE,Bucket.REFUND_RESERVED,-100),
                move(type,HolderType.USER,Bucket.AVAILABLE,100)),List.of(
                new FundMove(FundBucket.B,-100),new FundMove(FundBucket.C,100)),List.of(),receivable);
        posting.post(refund); posting.post(refund);
        assertThat(value(type,"b_minor")).isZero();
        assertThat(value(type,"c_minor")).isEqualTo(100);
        assertThat(jdbc().queryForObject("SELECT original_minor-recovered_minor FROM pts_receivable"
                + " WHERE receivable_no=?",Long.class,"R:"+type)).isEqualTo(100);
        assertThatThrownBy(() -> posting.post(voucher("RECOVER_UNVERIFIED:"+type,type,List.of(),
                List.of(new FundMove(FundBucket.B,100)),List.of(new EvidenceRef("UNVERIFIED","CNY",100)),
                receivable("R:"+type,1,"OPEN","CLOSED",100,100))))
                .hasMessage("POINTS_EVIDENCE_UNVERIFIED_OR_REUSED");
        assertThat(value(type,"b_minor")).isZero();
        posting.recordEvidence(new EvidenceRef("RECOVERY:"+type,"CNY",100));
        Voucher recovery=voucher("RECOVER:"+type,type,List.of(),List.of(new FundMove(FundBucket.B,100)),
                List.of(new EvidenceRef("RECOVERY:"+type,"CNY",100)),
                receivable("R:"+type,1,"OPEN","CLOSED",100,100));
        posting.post(recovery); posting.post(recovery);
        assertThat(value(type,"b_minor")).isEqualTo(100);
        assertThat(jdbc().queryForObject("SELECT recovered_minor FROM pts_receivable WHERE receivable_no=?",
                Long.class,"R:"+type)).isEqualTo(100);
    }
    private Object receivable(String no,long version,String from,String next,long original,long recovered) {
        try {
            var type=Class.forName("ai.neargo.shop.pay.points.model.ReceivableMove");
            return type.getConstructor(String.class,String.class,long.class,String.class,String.class,long.class,
                    long.class,long.class,String.class).newInstance(no,"STORE",version,from,next,original,
                    recovered,0,"{}");
        } catch (ReflectiveOperationException e) { throw new AssertionError("Receivable posting move missing",e); }
    }
    private Voucher voucher(String biz,String type,List<PointsMove> points,List<FundMove> funds,
                            List<EvidenceRef> evidence,Object receivable) {
        try {
            return Voucher.class.getConstructor(String.class,String.class,String.class,RedeemRate.class,List.class,
                    List.class,List.class,long.class,List.class,String.class,List.class,List.class,List.class)
                    .newInstance(biz,type,"CNY",new RedeemRate(1,1),points,funds,List.of(),0,evidence,"{}",List.of(),
                            List.of(),List.of(receivable));
        } catch (ReflectiveOperationException e) { throw new AssertionError("Receivable posting contract missing",e); }
    }
    private PointsMove move(String type,HolderType holder,Bucket bucket,long amount) {
        return new PointsMove(new AccountKey(type,holder,holder==HolderType.USER?"U":"PLATFORM",bucket),amount);
    }
    private long value(String type,String column) {
        return jdbc().queryForObject("SELECT "+column+" FROM pts_fund_pool WHERE type_code=?",Long.class,type);
    }
    private JdbcTemplate jdbc() { return new JdbcTemplate(dataSource); }
}
