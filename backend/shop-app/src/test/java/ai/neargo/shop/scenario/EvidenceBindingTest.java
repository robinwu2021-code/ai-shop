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

/** Cash collected for an unfinished refund cannot be taken by an unrelated budget topup. */
@SpringJUnitConfig(PointsPostingDatabaseTest.Config.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties={
        "spring.datasource.url=${points.jdbc.url:jdbc:h2:mem:points_evidence_binding;MODE=MySQL;DATABASE_TO_LOWER=TRUE}",
        "spring.datasource.username=${points.jdbc.user:sa}","spring.datasource.password=${points.jdbc.password:}",
        "spring.datasource.driver-class-name=${points.jdbc.driver:org.h2.Driver}"
})
class EvidenceBindingTest {
    @Autowired private PointsPostingService posting;
    @Autowired @Qualifier("payDataSource") private DataSource dataSource;
    @BeforeAll void schema() { PointsPostingDatabaseTest.initializeSchema(dataSource); }
    @Test void collection_evidence_cannot_be_used_by_an_unrelated_topup() {
        String type="EB"+UUID.randomUUID().toString().replace("-", "").substring(0,20);
        JdbcTemplate jdbc=new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO pts_point_type(type_code,market,currency,rate_points,rate_minor,status)"
                + " VALUES(?,'CN','CNY',1,1,'ACTIVE')",type);
        jdbc.update("INSERT INTO pts_fund_pool(type_code,currency) VALUES(?,'CNY')",type);
        String evidence="COLLECT:"+type;
        EvidenceRef bound=bound(evidence,"REFUND-CLAWBACK","PREP:"+type);
        posting.recordEvidence(bound);
        assertThatThrownBy(() -> posting.post(topup("WRONG:"+type,type,new EvidenceRef(evidence,"CNY",100))))
                .hasMessage("POINTS_EVIDENCE_UNVERIFIED_OR_REUSED");
        assertThat(jdbc.queryForObject("SELECT u_minor FROM pts_fund_pool WHERE type_code=?",Long.class,type))
                .isZero();
        Voucher proper=topup("CONFIRM:"+type,type,bound);
        posting.post(proper); posting.post(proper);
        assertThat(jdbc.queryForObject("SELECT u_minor FROM pts_fund_pool WHERE type_code=?",Long.class,type))
                .isEqualTo(100);
    }
    private EvidenceRef bound(String no,String purpose,String source) {
        try {
            return EvidenceRef.class.getConstructor(String.class,String.class,long.class,String.class,String.class)
                    .newInstance(no,"CNY",100,purpose,source);
        } catch (ReflectiveOperationException e) { throw new AssertionError("Evidence binding missing",e); }
    }
    private Voucher topup(String biz,String type,EvidenceRef evidence) {
        return new Voucher(biz,type,"CNY",new RedeemRate(1,1),List.of(
                new PointsMove(new AccountKey(type,HolderType.MINT,"PLATFORM",Bucket.ISSUED),-100),
                new PointsMove(new AccountKey(type,HolderType.ISSUE,"PLATFORM",Bucket.ISSUABLE),100)),
                List.of(new FundMove(FundBucket.U,100)),List.of(),0,List.of(evidence),"{}");
    }
}
