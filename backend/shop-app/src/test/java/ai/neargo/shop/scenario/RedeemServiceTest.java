package ai.neargo.shop.scenario;

import ai.neargo.shop.pay.points.agreement.AgreementService;
import ai.neargo.shop.pay.points.earn.EarnService;
import ai.neargo.shop.pay.points.model.AccountKey;
import ai.neargo.shop.pay.points.model.Bucket;
import ai.neargo.shop.pay.points.model.HolderType;
import ai.neargo.shop.pay.points.model.PointsMove;
import ai.neargo.shop.pay.points.model.PointsKeys;
import ai.neargo.shop.pay.points.model.PositionMove;
import ai.neargo.shop.pay.points.model.PoolRequest;
import ai.neargo.shop.pay.points.model.RedeemRate;
import ai.neargo.shop.pay.points.model.Voucher;
import ai.neargo.shop.pay.points.pool.PoolRequestService;
import ai.neargo.shop.pay.points.port.PointsFundsGateway;
import ai.neargo.shop.pay.points.posting.PointsPostingService;
import ai.neargo.shop.pay.points.redeem.RedeemService;
import ai.neargo.shop.pay.points.store.PointsReadStore;
import ai.neargo.shop.pay.points.type.PointTypeService;
import ai.neargo.shop.spi.points.PointsEarnPort;
import ai.neargo.shop.spi.points.PointsRedeemPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties={"shop.points.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:points_redeem;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
@Import(RedeemServiceTest.VerifiedCapabilities.class)
class RedeemServiceTest {
    @Autowired private ApplicationContext context;
    @Autowired private PointTypeService types;
    @Autowired private PoolRequestService requests;
    @Autowired private EarnService earning;
    @Autowired private PointsPostingService posting;
    @Autowired private AgreementService agreements;
    @Autowired private PointsReadStore read;

    @Test
    void termination_does_not_break_hold() throws Exception {
        assertThat(context.getBeanNamesForType(RedeemService.class))
                .as("atomic redemption service required").isNotEmpty();
        String type=fixture(true);
        try {
            RedeemService redeem=context.getBean(RedeemService.class);
            var held=redeem.hold(command(type));
            assertThat(held.points()).isEqualTo(100);
            assertThat(read.pool(type).reservedBMinor()).isEqualTo(100);
            agreements.terminate(type,"ST"+type,"OWNER");
            assertThat(redeem.hold(command(type))).isEqualTo(held);
            redeem.release(new PointsRedeemPort.ReleaseCommand("O:"+type,"CANCEL:"+type,2));
            assertThat(read.account(type,HolderType.USER,"U").points(Bucket.AVAILABLE)).isEqualTo(100);
            assertThat(read.pool(type).reservedBMinor()).isZero();
        } finally { db("payDataSource").update("UPDATE pts_point_type SET active_market=NULL WHERE type_code=?",type); }
    }

    @Test
    void reserve_shortage_is_atomic() throws Exception {
        String type=fixture(false);
        try {
            var outcome=context.getBean(RedeemService.class).hold(command(type));
            assertThat(outcome.points()).isZero();
            assertThat(outcome.reason()).isEqualTo("POINTS_B_RESERVE_INSUFFICIENT");
            assertThat(read.account(type,HolderType.USER,"U").points(Bucket.AVAILABLE)).isEqualTo(100);
            assertThat(read.account(type,HolderType.USER,"U").points(Bucket.HELD)).isZero();
        } finally { db("payDataSource").update("UPDATE pts_point_type SET active_market=NULL WHERE type_code=?",type); }
    }

    @Test
    void quote_is_advisory_and_hold_rechecks_acceptance() throws Exception {
        String type=fixture(true);
        try {
            RedeemService redeem=context.getBean(RedeemService.class);
            var c=command(type);
            assertThat(redeem.quote(new PointsRedeemPort.RedeemQuery(c.market(),c.userNo(),c.wantPoints(),
                    c.payMode(),c.clientType(),c.targets())).points()).isEqualTo(100);
            agreements.terminate(type,"ST"+type,"OWNER");
            var outcome=redeem.hold(c);
            assertThat(outcome.reason()).isEqualTo("POINTS_STORE_NOT_ACCEPTING");
            assertThat(outcome.points()).isZero();
            assertThat(read.account(type,HolderType.USER,"U").points(Bucket.HELD)).isZero();
            assertThat(read.pool(type).reservedBMinor()).isZero();
        } finally { db("payDataSource").update("UPDATE pts_point_type SET active_market=NULL WHERE type_code=?",type); }
    }

    @Test
    void immutable_hold_rejects_changed_amount_and_release_replays() throws Exception {
        String type=fixture(true);
        try {
            RedeemService redeem=context.getBean(RedeemService.class);
            var c=command(type);
            redeem.hold(c);
            assertThatThrownBy(() -> redeem.hold(new PointsRedeemPort.HoldCommand(c.orderNo(),c.market(),c.userNo(),
                    99,c.payMode(),c.clientType(),c.targets())))
                    .hasMessage("POINTS_IDEMPOTENCY_CONFLICT");
            var release=new PointsRedeemPort.ReleaseCommand(c.orderNo(),"C1",2);
            assertThat(redeem.release(release)).isEqualTo(redeem.release(release));
            assertThat(read.pool(type).reservedBMinor()).isZero();
            assertThat(read.account(type,HolderType.USER,"U").points(Bucket.AVAILABLE)).isEqualTo(100);
        } finally { db("payDataSource").update("UPDATE pts_point_type SET active_market=NULL WHERE type_code=?",type); }
    }

    @Test
    void paid_vs_release_has_one_outcome() throws Exception {
        String type=fixture(true);
        try (var threads=Executors.newFixedThreadPool(2)) {
            RedeemService redeem=context.getBean(RedeemService.class);
            var c=command(type);
            redeem.hold(c);
            String pos=PointsKeys.position("R",type,"S:"+type);
            var head=read.position(PositionMove.Kind.REDEEM.table(),pos);
            CountDownLatch start=new CountDownLatch(1);
            var paid=threads.submit(() -> {
                start.await();
                try {
                    posting.post(new Voucher("PAID:"+type,type,"CNY",new RedeemRate(1,1),List.of(
                            new PointsMove(new AccountKey(type,HolderType.USER,"U",Bucket.HELD),-100),
                            new PointsMove(new AccountKey(type,HolderType.STORE,"ST"+type,Bucket.PENDING),100)),
                            List.of(),List.of(new PositionMove(PositionMove.Kind.REDEEM,pos,
                                    ((Number)head.get("version")).longValue(),"HELD","PAID",100,0,
                                    (String)head.get("payload_json"))),0,List.of(),"{}"));
                    return true;
                } catch (IllegalArgumentException | IllegalStateException expected) { return false; }
            });
            var released=threads.submit(() -> {
                start.await();
                try { redeem.release(new PointsRedeemPort.ReleaseCommand(c.orderNo(),"C",2)); return true; }
                catch (IllegalArgumentException expected) { return false; }
            });
            start.countDown();
            boolean paidWon=paid.get(10,TimeUnit.SECONDS);
            boolean releasedWon=released.get(10,TimeUnit.SECONDS);
            assertThat(paidWon ^ releasedWon).as("one terminal transition wins").isTrue();
            assertThat(read.account(type,HolderType.USER,"U").points(Bucket.HELD)).isZero();
            assertThat(read.account(type,HolderType.USER,"U").points(Bucket.AVAILABLE)).isEqualTo(paidWon?0:100);
            assertThat(read.account(type,HolderType.STORE,"ST"+type).points(Bucket.PENDING)).isEqualTo(paidWon?100:0);
            assertThat(read.pool(type).reservedBMinor()).isEqualTo(paidWon?100:0);
            assertThat(read.pool(type).c()).isEqualTo(100);
        } finally { db("payDataSource").update("UPDATE pts_point_type SET active_market=NULL WHERE type_code=?",type); }
    }

    @Test
    void draining_type_allows_existing_balance_redemption_but_no_new_grants() throws Exception {
        String type=fixture(true);
        try {
            types.drain(type,"ops");
            RedeemService redeem=context.getBean(RedeemService.class);
            var c=command(type);
            assertThat(redeem.quote(new PointsRedeemPort.RedeemQuery(c.market(),c.userNo(),c.wantPoints(),
                    c.payMode(),c.clientType(),c.targets())).points()).isEqualTo(100);
            assertThat(redeem.hold(c).points()).isEqualTo(100);
            var skipped=earning.grant(new PointsEarnPort.GrantCommand("NEW:"+type,"NEW-S:"+type,"U",type,
                    "STORE","TEST","MP",1,List.of(new PointsEarnPort.EarnLine("I","G","C",1,100,0,
                    new PointsEarnPort.EarnRule(PointsEarnPort.RuleMode.FIXED,100)))));
            assertThat(skipped.status()).isEqualTo(PointsEarnPort.GrantStatus.SKIPPED);
            assertThat(skipped.reason()).isEqualTo("POINTS_TYPE_NOT_ACTIVE");
            assertThat(read.pool(type).reservedBMinor()).isEqualTo(100);
        } finally { db("payDataSource").update("UPDATE pts_point_type SET active_market=NULL WHERE type_code=?",type); }
    }

    @Test
    void redemption_projects_store_and_order_dimensions() throws Exception {
        String type=fixture(true);
        try {
            context.getBean(RedeemService.class).hold(command(type));
            assertThat(db("payDataSource").queryForObject("SELECT store_no FROM pts_redeem_position"
                    + " WHERE type_code=? AND sub_order_no=?",String.class,type,"S:"+type)).isEqualTo("ST"+type);
            assertThat(db("payDataSource").queryForObject("SELECT order_no FROM pts_redeem_position"
                    + " WHERE type_code=? AND sub_order_no=?",String.class,type,"S:"+type)).isEqualTo("O:"+type);
        } finally { db("payDataSource").update("UPDATE pts_point_type SET active_market=NULL WHERE type_code=?",type); }
    }

    private String fixture(boolean reserve) throws Exception {
        String type="RD"+UUID.randomUUID().toString().replace("-", "").substring(0,20);
        types.create(new PointTypeService.CreateType(type,"CN",new RedeemRate(1,1),10000,365),"ops");
        types.activate(type,"ops");
        fund(type,"U",PoolRequest.Action.TOPUP_U);
        if (reserve) { fund(type,"B",PoolRequest.Action.TOPUP_B); }
        String sub="E:"+type;
        earning.grant(new PointsEarnPort.GrantCommand("EV:"+type,sub,"U",type,"STORE","TEST","MP",1,
                List.of(new PointsEarnPort.EarnLine("I","G","C",1,100,0,
                        new PointsEarnPort.EarnRule(PointsEarnPort.RuleMode.FIXED,100)))));
        posting.post(new Voucher("ACT:"+type,type,"CNY",new RedeemRate(1,1),
                List.of(new PointsMove(new AccountKey(type,HolderType.USER,"U",Bucket.PENDING),-100),
                        new PointsMove(new AccountKey(type,HolderType.USER,"U",Bucket.AVAILABLE),100)),
                List.of(),List.of(),0,List.of(),"{}"));
        db("dataSource").update("INSERT INTO mch_entity(entity_no,name,owner_user_no,status,created_at,updated_at)"
                + " VALUES(?, 'Merchant','OWNER','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)","M"+type);
        db("dataSource").update("INSERT INTO mch_store(entity_no,store_no,name,status,created_at,updated_at)"
                + " VALUES(?,?,'Store','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)","M"+type,"ST"+type);
        String text="TEST acceptance terms";
        agreements.publishTerms(type,"v1",text,"ops");
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
        agreements.sign(new AgreementService.SignCommand(type,"M"+type,"ST"+type,"v1",hash,"PER_ORDER",
                new PointsFundsGateway.FundsContext(PointsFundsGateway.FundsPath.DIRECT,"TEST","M"+type,
                        "ST"+type,"A1",null,null,null,0)),"OWNER");
        return type;
    }
    private void fund(String type,String kind,PoolRequest.Action action) {
        String receipt=kind+":"+type;
        db("payDataSource").update("INSERT INTO pts_fund_evidence(evidence_no,currency,external_minor,verified)"
                + " VALUES(?,'CNY',100,1)",receipt);
        requests.submit(new PoolRequest(receipt,type,action,100,"maker",receipt)); requests.approve(receipt,"checker");
    }
    private PointsRedeemPort.HoldCommand command(String type) {
        return new PointsRedeemPort.HoldCommand("O:"+type,"CN","U",100,"DIRECT","MP",
                List.of(new PointsRedeemPort.Target("S:"+type,"M"+type,"ST"+type,100,"DIRECT","TEST","A1",null,null,
                        List.of(new PointsRedeemPort.Item("I",100)))));
    }
    private JdbcTemplate db(String name) { return new JdbcTemplate(context.getBean(name,DataSource.class)); }

    @TestConfiguration
    static class VerifiedCapabilities {
        @Bean @Primary PointsFundsGateway testPointsFundsGateway() {
            return new PointsFundsGateway() {
                @Override public FundsCapabilities capabilities(FundsContext c) {
                    return new FundsCapabilities(true,true,true,false);
                }
                @Override public FundsResult submit(FundsCommand c) {
                    throw new AssertionError("Hold must not execute money");
                }
                @Override public FundsResult query(FundsCommand c) {
                    throw new AssertionError("Hold must not query money");
                }
            };
        }
    }
}
