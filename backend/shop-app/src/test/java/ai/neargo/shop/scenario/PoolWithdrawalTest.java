package ai.neargo.shop.scenario;

import ai.neargo.shop.spi.pay.FundsPath;
import ai.neargo.shop.pay.points.model.AccountKey;
import ai.neargo.shop.pay.points.model.Bucket;
import ai.neargo.shop.pay.points.model.EvidenceRef;
import ai.neargo.shop.pay.points.model.FundBucket;
import ai.neargo.shop.pay.points.model.FundMove;
import ai.neargo.shop.pay.points.model.HolderType;
import ai.neargo.shop.pay.points.model.PointsKeys;
import ai.neargo.shop.pay.points.model.PointsMove;
import ai.neargo.shop.pay.points.model.PoolRequest;
import ai.neargo.shop.pay.points.model.PositionMove;
import ai.neargo.shop.pay.points.model.RedeemRate;
import ai.neargo.shop.pay.points.model.ReserveMove;
import ai.neargo.shop.pay.points.model.Voucher;
import ai.neargo.shop.pay.points.pool.PoolRequestService;
import ai.neargo.shop.pay.points.pool.PoolWithdrawalExecutionService;
import ai.neargo.shop.pay.points.pool.PoolWithdrawalService;
import ai.neargo.shop.pay.points.pool.impl.PoolRequestServiceImpl;
import ai.neargo.shop.pay.points.pool.impl.PoolServiceImpl;
import ai.neargo.shop.pay.points.port.PointsFundsGateway;
import ai.neargo.shop.pay.points.posting.PointsPostingService;
import ai.neargo.shop.pay.points.store.impl.MybatisPointsConfigurationStore;
import ai.neargo.shop.pay.points.store.impl.MybatisPointsReadStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Isolated platform cash simulator and the actual approval/posting/transaction code. */
@SpringJUnitConfig(PoolWithdrawalTest.Config.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties={
        "spring.datasource.url=${points.jdbc.url:jdbc:h2:mem:points_withdraw;MODE=MySQL;DATABASE_TO_LOWER=TRUE}",
        "spring.datasource.username=${points.jdbc.user:sa}","spring.datasource.password=${points.jdbc.password:}",
        "spring.datasource.driver-class-name=${points.jdbc.driver:org.h2.Driver}"
})
class PoolWithdrawalTest {
    @Autowired private PoolWithdrawalExecutionService execution;
    @Autowired private PoolRequestService requests;
    @Autowired private PointsPostingService posting;
    @Autowired private TestFunds funds;
    @Autowired @Qualifier("payDataSource") private DataSource dataSource;
    @BeforeAll void schema() {
        PointsPostingDatabaseTest.initializeSchema(dataSource);
    }
    @Test void approved_u_withdrawal_locks_budget_and_unknown_never_repays() {
        String type=fixture();
        PoolRequest request=request(type,PoolRequest.Action.WITHDRAW_U,80);
        requests.submit(request);
        requests.approve(request.requestNo(),"checker");
        requests.approve(request.requestNo(),"checker");
        assertThat(value(type,"u_minor")).isEqualTo(100);
        assertThat(value(type,"locked_u_minor")).isEqualTo(80);
        assertThatThrownBy(() -> posting.post(new Voucher("COMPETING:"+type,type,"CNY",new RedeemRate(1,1),
                List.of(move(type,HolderType.ISSUE,"PLATFORM",Bucket.ISSUABLE,-50),
                        move(type,HolderType.USER,"U",Bucket.PENDING,50)),
                List.of(new FundMove(FundBucket.U,-50),new FundMove(FundBucket.C,50)),List.of(),0,List.of(),"{}")))
                .hasMessage("POINTS_POOL_OR_RESERVE_INSUFFICIENT");
        String ticket=PointsKeys.position("W",type,request.requestNo());
        assertThat(execute(ticket).status()).isEqualTo(PointsFundsGateway.FundsStatus.UNKNOWN);
        assertThat(value(type,"locked_u_minor")).isEqualTo(80);
        assertThat(value(type,"u_minor")).isEqualTo(100);
        assertThat(execute(ticket).status()).isEqualTo(PointsFundsGateway.FundsStatus.SUCCEEDED);
        assertThat(execute(ticket).replayed()).isTrue();
        assertThat(value(type,"locked_u_minor")).isZero();
        assertThat(value(type,"u_minor")).isEqualTo(20);
        assertThat(jdbc().queryForObject("SELECT recirculatable_points FROM pts_account WHERE type_code=?"
                + " AND holder_type='RECEIVE'",Long.class,type)).isEqualTo(80);
        assertThat(funds.sends.get(ticket)).isEqualTo(1);
        assertThat(funds.queries.get(ticket)).isEqualTo(1);
    }
    @Test void refund_reserved_b_cannot_be_withdrawn() {
        String type=fixture();
        posting.post(new Voucher("HOLD:"+type,type,"CNY",new RedeemRate(1,1),List.of(
                move(type,HolderType.ISSUE,"PLATFORM",Bucket.ISSUABLE,-100),
                move(type,HolderType.USER,"U",Bucket.AVAILABLE,40),move(type,HolderType.USER,"U",Bucket.HELD,60)),
                List.of(new FundMove(FundBucket.U,-100),new FundMove(FundBucket.C,100)),
                List.of(new PositionMove(PositionMove.Kind.REDEEM,"R:"+type,-1,"NEW","HELD",60,0,"{}")),
                60,List.of(),"{}",List.of(new ReserveMove("R:"+type,"R:"+type,-1,60,60,0,0))));
        PoolRequest request=request(type,PoolRequest.Action.WITHDRAW_B,50);
        requests.submit(request);
        assertThatThrownBy(() -> requests.approve(request.requestNo(),"checker"))
                .hasMessage("POINTS_WITHDRAW_AVAILABLE_INSUFFICIENT");
        assertThat(value(type,"b_minor")).isEqualTo(100);
        assertThat(value(type,"reserved_b_minor")).isEqualTo(60);
        assertThat(value(type,"locked_b_minor")).isZero();
        var allowed=request(type,PoolRequest.Action.WITHDRAW_B,40);
        requests.submit(allowed); requests.approve(allowed.requestNo(),"checker");
        assertThat(value(type,"locked_b_minor")).isEqualTo(40);
        assertThatThrownBy(() -> posting.post(new Voucher("HOLD_EXTRA:"+type,type,"CNY",new RedeemRate(1,1),
                List.of(move(type,HolderType.USER,"U",Bucket.AVAILABLE,-1),
                        move(type,HolderType.USER,"U",Bucket.HELD,1)),List.of(),
                List.of(new PositionMove(PositionMove.Kind.REDEEM,"R2:"+type,-1,"NEW","HELD",1,0,"{}")),
                1,List.of(),"{}",List.of(new ReserveMove("R2:"+type,"R2:"+type,-1,1,1,0,0)))))
                .hasMessage("POINTS_POOL_OR_RESERVE_INSUFFICIENT");
    }
    @Test void definitive_rejection_releases_lock_without_debiting_money() {
        String type=fixture();
        PoolRequest request=request(type,PoolRequest.Action.WITHDRAW_B,80);
        requests.submit(request); requests.approve(request.requestNo(),"checker");
        String ticket=PointsKeys.position("W",type,request.requestNo());
        funds.reject.add(ticket);
        execute(ticket); execute(ticket);
        assertThat(value(type,"b_minor")).isEqualTo(100);
        assertThat(value(type,"locked_b_minor")).isZero();
        assertThat(funds.sends.get(ticket)).isEqualTo(1);
        assertThat(funds.queries.getOrDefault(ticket,0)).isZero();
    }
    @Test void unsupported_transfer_does_not_approve_or_lock_money() {
        String type=fixture();
        var request=request(type,PoolRequest.Action.WITHDRAW_U,80);
        funds.unsupported.add(request.requestNo());
        requests.submit(request);
        assertThatThrownBy(() -> requests.approve(request.requestNo(),"checker"))
                .hasMessage("POINTS_WITHDRAW_TRANSFER_UNSUPPORTED");
        assertThat(value(type,"locked_u_minor")).isZero();
        assertThat(jdbc().queryForObject("SELECT state FROM pts_pool_request WHERE request_no=?",String.class,
                request.requestNo())).isEqualTo("SUBMITTED");
    }
    @Test void foreign_receipt_keeps_original_budget_reserved_until_valid_query() {
        String type=fixture();
        var request=request(type,PoolRequest.Action.WITHDRAW_U,80);
        requests.submit(request); requests.approve(request.requestNo(),"checker");
        String ticket=PointsKeys.position("W",type,request.requestNo());
        execute(ticket);
        funds.foreign.add(ticket);
        assertThat(execute(ticket).status()).isEqualTo(PointsFundsGateway.FundsStatus.UNKNOWN);
        assertThat(value(type,"u_minor")).isEqualTo(100);
        assertThat(value(type,"locked_u_minor")).isEqualTo(80);
        funds.foreign.remove(ticket);
        assertThat(execute(ticket).status()).isEqualTo(PointsFundsGateway.FundsStatus.SUCCEEDED);
        assertThat(value(type,"locked_u_minor")).isZero();
        assertThat(funds.sends.get(ticket)).isEqualTo(1);
        assertThat(funds.queries.get(ticket)).isEqualTo(2);
    }

    private PoolRequest request(String type,PoolRequest.Action action,long amount) {
        String request="W:"+type+":"+amount;
        var target=new PointsFundsGateway.FundsContext(FundsPath.AGGREGATED,"TEST",
                "PLATFORM","PLATFORM","BANK-V1","PLATFORM-BANK",null,request,0);
        return new PoolRequest(request,type,action,amount,"maker",null,target);
    }
    private PoolWithdrawalService.Outcome execute(String ticket) { return execution.execute(ticket); }
    private String fixture() {
        String type="WD"+UUID.randomUUID().toString().replace("-", "").substring(0,20);
        jdbc().update("INSERT INTO pts_point_type(type_code,market,currency,rate_points,rate_minor,status)"
                + " VALUES(?,'CN','CNY',1,1,'DRAINING')",type);
        jdbc().update("INSERT INTO pts_fund_pool(type_code,currency) VALUES(?,'CNY')",type);
        posting.recordEvidence(new EvidenceRef("E:"+type,"CNY",200));
        posting.post(new Voucher("F:"+type,type,"CNY",new RedeemRate(1,1),List.of(
                move(type,HolderType.MINT,"PLATFORM",Bucket.ISSUED,-100),
                move(type,HolderType.ISSUE,"PLATFORM",Bucket.ISSUABLE,100)),List.of(
                new FundMove(FundBucket.U,100),new FundMove(FundBucket.B,100)),List.of(),0,
                List.of(new EvidenceRef("E:"+type,"CNY",200)),"{}"));
        return type;
    }
    private PointsMove move(String type,HolderType holder,String no,Bucket bucket,long points) {
        return new PointsMove(new AccountKey(type,holder,no,bucket),points);
    }
    private long value(String type,String column) {
        return jdbc().queryForObject("SELECT "+column+" FROM pts_fund_pool WHERE type_code=?",Long.class,type);
    }
    private JdbcTemplate jdbc() { return new JdbcTemplate(dataSource); }
    @Configuration
    @Import({PointsPostingDatabaseTest.Config.class,MybatisPointsReadStore.class,
            MybatisPointsConfigurationStore.class, ai.neargo.shop.pay.points.store.impl.AcceptanceAvailabilityProjection.class,PoolServiceImpl.class,PoolRequestServiceImpl.class})
    @ComponentScan(basePackages="ai.neargo.shop.pay.points.pool.withdrawal")
    static class Config { @Bean TestFunds testFunds() { return new TestFunds(); } }
    /** Bank evidence is simulated here only; never deployed as an enabled gateway. */
    static class TestFunds implements PointsFundsGateway {
        final Map<String,Integer> sends=new ConcurrentHashMap<>();
        final Map<String,Integer> queries=new ConcurrentHashMap<>();
        final Map<String,FundsCommand> originals=new ConcurrentHashMap<>();
        final Set<String> reject=ConcurrentHashMap.newKeySet();
        final Set<String> unsupported=ConcurrentHashMap.newKeySet();
        final Set<String> foreign=ConcurrentHashMap.newKeySet();
        @Override public FundsCapabilities capabilities(FundsContext target) {
            return new FundsCapabilities(false,!unsupported.contains(target.outTradeNo()),false,false);
        }
        @Override public FundsResult submit(FundsCommand command) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(command.purpose()).isEqualTo(FundsPurpose.TRANSFER);
            sends.merge(command.ticketNo(),1,Integer::sum);
            if (reject.contains(command.ticketNo())) {
                return new FundsResult(command.ticketNo(),FundsStatus.FAILED,null,command.amountMinor(),
                        "TEST_REJECTED");
            }
            originals.put(command.ticketNo(),command);
            return new FundsResult(command.ticketNo(),FundsStatus.UNKNOWN,null,command.amountMinor(),"TEST_REPLY_LOST");
        }
        @Override public FundsResult query(FundsCommand command) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(command).isEqualTo(originals.get(command.ticketNo()));
            queries.merge(command.ticketNo(),1,Integer::sum);
            return new FundsResult(foreign.contains(command.ticketNo())?"FOREIGN":command.ticketNo(),
                    FundsStatus.SUCCEEDED,"TEST-WITHDRAW:"+command.ticketNo(),
                    command.amountMinor(),null);
        }
    }
}
