package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.SettleService;
import ai.neargo.shop.pay.SplitGateway;
import ai.neargo.shop.pay.points.app.PointsPaidFactService;
import ai.neargo.shop.pay.points.model.AccountKey;
import ai.neargo.shop.pay.points.model.AgreementSnapshot;
import ai.neargo.shop.pay.points.model.Bucket;
import ai.neargo.shop.pay.points.model.EvidenceRef;
import ai.neargo.shop.pay.points.model.FundBucket;
import ai.neargo.shop.pay.points.model.FundMove;
import ai.neargo.shop.pay.points.model.HolderType;
import ai.neargo.shop.pay.points.model.PointsKeys;
import ai.neargo.shop.pay.points.model.PointsMove;
import ai.neargo.shop.pay.points.model.PositionMove;
import ai.neargo.shop.pay.points.model.RedeemRate;
import ai.neargo.shop.pay.points.model.ReserveMove;
import ai.neargo.shop.pay.points.model.Voucher;
import ai.neargo.shop.pay.points.port.PointsFundsGateway;
import ai.neargo.shop.pay.points.posting.PointsPostingService;
import ai.neargo.shop.pay.points.redeem.impl.RedeemServiceImpl.RedeemSnapshot;
import ai.neargo.shop.spi.points.PointsFactPort.FactKind;
import ai.neargo.shop.spi.points.PointsFactPort.LifecycleFact;
import ai.neargo.shop.spi.points.PointsFactPort.LineSnapshot;
import ai.neargo.shop.spi.points.PointsRedeemPort.Share;
import ai.neargo.shop.spi.points.PointsRedeemPort.Target;
import ai.neargo.shop.spi.points.PointsRedeemPort.Item;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.mapper.SettleMappers.BillMapper;
import ai.neargo.shop.spi.trade.SettleSourcePort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicit source fixtures test the actual settlement amount calculation, without any money gateway execution. */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:points_settlement_basis;MODE=MySQL;"
        + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@Import(PerOrderSettlementTest.GatewayFixture.class)
class PerOrderSettlementTest {
    @Autowired private SettleService settlement;
    @Autowired private BillMapper bills;
    @Autowired private LostSplitGateway gateway;
    @Autowired private FixtureFunds funds;
    @Autowired private PointsPostingService posting;
    @Autowired private PointsPaidFactService paidFacts;
    @Autowired @Qualifier("payDataSource") private DataSource payDataSource;

    @Test
    void merchant_is_not_charged_earn_fee() {
        String no="PF"+UUID.randomUUID().toString().replace("-", "").substring(0,20);
        var source=new SettleSourcePort.SettleSource("S:"+no,"M:"+no,"MERCHANT_OWNED",1000,0,0,null,1,
                "STORE:"+no,200,300,"TEST","MP",0,0,null);
        var input=new SettleService.SettleInput(source,MerchantQueryPort.MODE_SELF_OPERATED,"ACCOUNT", "NATURAL",
                MerchantQueryPort.FUNDS_AGGREGATED,"PLATFORM","CN");
        assertThat(settlement.generateForOrder("O:"+no,List.of(input))).isEqualTo(1);
        StlBill bill=DataScopeContext.executeWithoutScope(() -> bills.selectOne(Wrappers.<StlBill>lambdaQuery()
                .eq(StlBill::getSubOrderNo,source.subOrderNo())));
        assertThat(bill.getPointsFeeMinor()).isZero();
        assertThat(bill.getGrossMinor()).isEqualTo(1200);
        assertThat(bill.getNetMinor()).isEqualTo(bill.getGrossMinor()-bill.getCommissionMinor()
                -bill.getServiceFeeMinor());
        assertThat(new JdbcTemplate(payDataSource).queryForObject("SELECT COUNT(*) FROM stl_points_pool"
                + " WHERE ref_no=?",Long.class,bill.getSettleNo())).isZero();
    }
    @Test
    void cash_intent_commits_before_external_call_and_unknown_only_queries_original() {
        String no="CS"+UUID.randomUUID().toString().replace("-", "").substring(0,20);
        StlBill bill=new StlBill(); bill.setSettleNo(no); bill.setSubOrderNo("S:"+no); bill.setOrderNo("O:"+no); bill.setEntityNo("M");
        bill.setStoreNo("STORE"); bill.setPayMerchantNo("ORIGINAL-ACCOUNT"); bill.setCurrency("CNY");
        bill.setPayChannel("TEST"); bill.setFundsMode(MerchantQueryPort.FUNDS_DIRECT);
        bill.setStatus(StlBill.PENDING); bill.setGrossMinor(1000L); bill.setNetMinor(985L);
        bill.setCommissionMinor(10L); bill.setServiceFeeMinor(5L); bill.setSplitAmountMinor(15L);
        bill.setPointsCostMinor(0L); bill.setSubsidyMinor(0L); bill.setPointsFeeMinor(0L);
        DataScopeContext.executeWithoutScope(() -> bills.insert(bill));
        settlement.executeSplit(no);
        assertThat(settlement.reverseSplit(bill.getSubOrderNo())).isFalse();
        StlBill changed=DataScopeContext.executeWithoutScope(() -> bills.selectById(bill.getId()));
        changed.setPayMerchantNo("NEW-ACCOUNT");
        DataScopeContext.executeWithoutScope(() -> bills.updateById(changed));
        settlement.executeSplit(no);
        settlement.executeSplit(no);
        assertThat(gateway.sends.get("S:"+no)).isEqualTo(1);
        assertThat(gateway.queries.get("S:"+no)).isEqualTo(1);
        assertThat(DataScopeContext.executeWithoutScope(() -> bills.selectById(bill.getId())).getStatus())
                .isEqualTo(StlBill.SPLIT);
        assertThat(gateway.originals.get("S:"+no)).contains("ORIGINAL-ACCOUNT", "|15|");
    }

    @Test
    void cash_payout_and_points_confirm_once() {
        String no="CP"+UUID.randomUUID().toString().replace("-", "").substring(0,20);
        String type="T:"+no;
        String sub="S:"+no;
        JdbcTemplate jdbc=new JdbcTemplate(payDataSource);
        jdbc.update("INSERT INTO pts_point_type(type_code,market,currency,rate_points,rate_minor,status)"
                + " VALUES(?,'CN','CNY',1,1,'ACTIVE')",type);
        jdbc.update("INSERT INTO pts_fund_pool(type_code,currency) VALUES(?,'CNY')",type);
        posting.recordEvidence(new EvidenceRef("E:"+no,"CNY",200));
        posting.post(new Voucher("F:"+no,type,"CNY",new RedeemRate(1,1),List.of(
                move(type,HolderType.MINT,"PLATFORM",Bucket.ISSUED,-100),
                move(type,HolderType.ISSUE,"PLATFORM",Bucket.ISSUABLE,100)),List.of(
                new FundMove(FundBucket.U,100),new FundMove(FundBucket.B,100)),List.of(),0,
                List.of(new EvidenceRef("E:"+no,"CNY",200)),"{}"));
        posting.post(new Voucher("G:"+no,type,"CNY",new RedeemRate(1,1),List.of(
                move(type,HolderType.ISSUE,"PLATFORM",Bucket.ISSUABLE,-100),
                move(type,HolderType.USER,"U",Bucket.AVAILABLE,70),
                move(type,HolderType.USER,"U",Bucket.HELD,30)),List.of(
                new FundMove(FundBucket.U,-100),new FundMove(FundBucket.C,100)),List.of(),0,List.of(),"{}"));
        String position=PointsKeys.position("R",type,sub);
        var context=new PointsFundsGateway.FundsContext(PointsFundsGateway.FundsPath.DIRECT,"TEST","M","STORE",
                "A1","ORIGINAL-ACCOUNT","TRADE",sub,970);
        var agreement=new AgreementSnapshot("A:"+no,type,"M","STORE","OWNER","v1","HASH","TEST TERMS",
                "PER_ORDER","DIRECT","TEST","A1","ACTIVE");
        var source=new RedeemSnapshot(sub,"U","O:"+no,new Share(sub,"M","STORE",30,30),
                new Target(sub,"M","STORE",1000,"DIRECT","TEST","A1","ORIGINAL-ACCOUNT","TRADE",
                        List.of(new Item("I",1000))),agreement,context,false);
        posting.post(new Voucher("H:"+no,type,"CNY",new RedeemRate(1,1),List.of(),List.of(),
                List.of(new PositionMove(PositionMove.Kind.REDEEM,position,-1,"NEW","HELD",30,0,
                        new ObjectMapper().writeValueAsString(source))),30,List.of(),"{}",
                List.of(new ReserveMove(position,position,-1,30,30,0,0))));
        paidFacts.onPaid(new LifecycleFact("PAID:"+no,"HASH",sub,"U",type,1,FactKind.PAID,970,0,
                List.of(new LineSnapshot("I",1,970,0)),Instant.parse("2026-10-14T00:00:00Z")));
        StlBill bill=new StlBill(); bill.setSettleNo(no); bill.setSubOrderNo(sub); bill.setOrderNo("O:"+no);
        bill.setEntityNo("M"); bill.setStoreNo("STORE"); bill.setPayMerchantNo("ORIGINAL-ACCOUNT");
        bill.setCurrency("CNY"); bill.setPayChannel("TEST"); bill.setFundsMode(MerchantQueryPort.FUNDS_DIRECT);
        bill.setStatus(StlBill.PENDING); bill.setGrossMinor(1000L); bill.setNetMinor(985L);
        bill.setCommissionMinor(10L); bill.setServiceFeeMinor(5L); bill.setSplitAmountMinor(15L);
        bill.setPointsCostMinor(0L); bill.setSubsidyMinor(30L); bill.setPointsFeeMinor(0L);
        DataScopeContext.executeWithoutScope(() -> bills.insert(bill));
        settlement.executeSplit(no);
        assertThat(gateway.sends.getOrDefault(sub,0)).isZero();
        assertThat(jdbc.queryForObject("SELECT d_minor FROM pts_fund_pool WHERE type_code=?",Long.class,type))
                .isEqualTo(30);
        settlement.executeSplit(no);
        assertThat(jdbc.queryForObject("SELECT d_minor FROM pts_fund_pool WHERE type_code=?",Long.class,type)).isZero();
        assertThat(DataScopeContext.executeWithoutScope(() -> bills.selectById(bill.getId())).getSubsidyAt())
                .isNotNull();
        settlement.executeSplit(no);
        settlement.executeSplit(no);
        assertThat(gateway.sends.get(sub)).isEqualTo(1);
        assertThat(gateway.queries.get(sub)).isEqualTo(1);
        assertThat(funds.sends.get(sub)).isEqualTo(1);
        assertThat(funds.queries.get(sub)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM pts_redeem_position WHERE position_no=?",String.class,
                position)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pts_fund_evidence WHERE evidence_no LIKE ?"
                + " AND used_voucher_no IS NOT NULL",Long.class,"TEST-POINTS:%")).isEqualTo(1);
    }
    private PointsMove move(String type,HolderType holder,String no,Bucket bucket,long amount) {
        return new PointsMove(new AccountKey(type,holder,no,bucket),amount);
    }

    @TestConfiguration
    static class GatewayFixture {
        @Bean @Primary FixtureFunds testPointsGateway() { return new FixtureFunds(); }
        @Bean @Primary LostSplitGateway testSplitGateway(@Qualifier("payDataSource") DataSource dataSource) {
            return new LostSplitGateway(new JdbcTemplate(dataSource));
        }
    }
    /** Explicit accepted-command simulator; acceptance is never an arrival confirmation. */
    static class LostSplitGateway implements SplitGateway {
        private final JdbcTemplate jdbc;
        final Map<String,Integer> sends=new ConcurrentHashMap<>();
        final Map<String,Integer> queries=new ConcurrentHashMap<>();
        final Map<String,String> originals=new ConcurrentHashMap<>();
        LostSplitGateway(JdbcTemplate jdbc) { this.jdbc=jdbc; }
        @Override public Result split(String sub,String recipient,long amount,String request) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stl_split_intent WHERE request_no=?",
                    Long.class,request)).isEqualTo(1);
            sends.merge(sub,1,Integer::sum);
            originals.put(sub,recipient+"|"+amount+"|"+request);
            throw new IllegalStateException("TEST_ACCEPTED_REPLY_LOST");
        }
        @Override public Result querySplit(String sub,String recipient,long amount,String request) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(recipient+"|"+amount+"|"+request).isEqualTo(originals.get(sub));
            queries.merge(sub,1,Integer::sum);
            return Result.ok("TEST-ACCEPTED:"+request);
        }
        @Override public Result reverse(String sub,String recipient,long amount,String request) {
            throw new AssertionError("unexpected reverse");
        }
        @Override public Result subsidy(String sub,String recipient,long amount,String request) {
            throw new AssertionError("points must use the new funds gateway");
        }
        @Override public Result subsidyReturn(String sub,String recipient,long amount,String request) {
            throw new AssertionError("unexpected subsidy return");
        }
    }
    /** No bank connection: simulates a committed subsidy whose first response was lost. */
    static class FixtureFunds implements PointsFundsGateway {
        final Map<String,Integer> sends=new ConcurrentHashMap<>();
        final Map<String,Integer> queries=new ConcurrentHashMap<>();
        final Map<String,FundsCommand> originals=new ConcurrentHashMap<>();
        @Override public FundsCapabilities capabilities(FundsContext context) {
            return new FundsCapabilities(true,true,true,false);
        }
        @Override public FundsResult submit(FundsCommand command) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(command.purpose()).isEqualTo(FundsPurpose.SUBSIDY);
            sends.merge(command.context().outTradeNo(),1,Integer::sum);
            originals.put(command.ticketNo(),command);
            return new FundsResult(command.ticketNo(),FundsStatus.UNKNOWN,null,command.amountMinor(),"TEST_REPLY_LOST");
        }
        @Override public FundsResult query(FundsCommand command) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(command).isEqualTo(originals.get(command.ticketNo()));
            queries.merge(command.context().outTradeNo(),1,Integer::sum);
            return new FundsResult(command.ticketNo(),FundsStatus.SUCCEEDED,"TEST-POINTS:"+command.ticketNo(),
                    command.amountMinor(),null);
        }
    }
}
