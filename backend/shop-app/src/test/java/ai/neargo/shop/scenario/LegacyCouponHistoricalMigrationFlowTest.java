package ai.neargo.shop.scenario;
import static org.assertj.core.api.Assertions.*;
import ai.neargo.shop.payclient.EntitlementMigrationAppService;
import ai.neargo.shop.promotion.migration.LegacyCouponSourceFenceService;
import ai.neargo.shop.paybridge.LegacyCouponMigrationCommitCoordinator;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.UsageHistoryQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
/** Historical trade/payment closure is an explicit source fixture; the migration and restore writers are real. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"shop.services.internal-token=history-test","spring.datasource.url=jdbc:h2:mem:historical-migration-flow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "shop.core.entitlement.migration-watermark-key=historical-source-watermark-key-over-thirty-two-characters",
        "shop.core.entitlement.migration-commit.enabled=true","shop.pay.entitlement.cursor-key=historical-query-key-over-thirty-two-characters",
        "shop.pay.entitlement.migration-snapshot-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "shop.pay.entitlement.migration-snapshot-key-id=HISTORYTEST"})
@ActiveProfiles("test")
class LegacyCouponHistoricalMigrationFlowTest {
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired LegacyCouponSourceFenceService fences;
    @Autowired EntitlementMigrationAppService migration;
    @Autowired LegacyCouponMigrationCommitCoordinator source;
    @Autowired EntitlementQueryPort queries;
    @Autowired org.springframework.context.ApplicationContext application;
    @Test void pastConsumptionIsImportedOnceAndOnlyARealWholeRefundRestoresItsOriginalUnit() throws Exception {
        var f=LegacyCouponMigrationFixture.prepareHistory(jdbc,fences,migration);
        source.commit(f.scope(),f.planNo(),"historical source","C"+f.id());
        migration.importPlan(f.scope(),new ai.neargo.shop.pay.migration.dto.MigrationImportViews.ImportCommand(f.planNo(),"historical import"),"I"+f.id());
        var history=queries.history(f.ctx(),new UsageHistoryQuery("U"+f.id(),f.scope().entityNo(),null,null,20));
        assertThat(history.records()).hasSize(1);assertThat(history.records().getFirst().amountMinor()).isEqualTo(100);
        assertThat(history.records().getFirst().status()).isEqualTo("COMMITTED");
        assertThat(jdbc.queryForObject("SELECT remaining_units FROM ent_instance WHERE entity_no=?",Integer.class,f.scope().entityNo())).isZero();
        jdbc.update("UPDATE ord_sub_order SET status='REFUNDED',refunded_minor=500,refund_progress='PARTIAL' WHERE sub_order_no=?","OLD-SO"+f.id());
        jdbc.update("INSERT INTO ord_after_sale(after_sale_no,order_no,sub_order_no,user_no,entity_no,type,status,reason,refund_minor,refunded_at,tenant_no,created_at,updated_at) VALUES(?,?,?,?,?,'REFUND_ONLY','REFUNDED','source fixture',1000,?,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)","AS"+f.id(),"OLD-O"+f.id(),"OLD-SO"+f.id(),"U"+f.id(),f.scope().entityNo(),System.currentTimeMillis());
        Object journal=application.getBean("legacyCouponHistoricalReturnService");
        var decide=journal.getClass().getMethod("refunded",ai.neargo.shop.spi.entitlement.EntitlementContext.class,String.class);
        assertThat((java.util.List<?>)decide.invoke(journal,f.ctx(),"AS"+f.id())).isEmpty();
        jdbc.update("UPDATE ord_sub_order SET refunded_minor=1000,refund_progress='FULL' WHERE sub_order_no=?","OLD-SO"+f.id());
        var refundEvent=new ai.neargo.shop.event.SysOutbox();
        refundEvent.setPayload(new tools.jackson.databind.ObjectMapper().writeValueAsString(
                new ai.neargo.shop.spi.trade.OrderEvents.AfterSaleRefunded("AS"+f.id(),"OLD-SO"+f.id(),"U"+f.id(),1000L)));
        application.getBean(ai.neargo.shop.paybridge.LegacyCouponRefundCompletedConsumer.class).consume(refundEvent);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pmt_migration_return WHERE entity_no=?",Integer.class,f.scope().entityNo())).isEqualTo(1);
        var returns=(java.util.List<?>)decide.invoke(journal,f.ctx(),"AS"+f.id());assertThat(returns).hasSize(1);
        String no=(String)returns.getFirst().getClass().getMethod("returnNo").invoke(returns.getFirst());
        var locator=new ai.neargo.shop.svc.ConfigServiceLocator();
        locator.getTargets().put(ai.neargo.shop.svc.ServiceName.PLATFORM,"http://127.0.0.1:"+port);
        var api=new ai.neargo.shop.svc.InternalHttp(locator,"history-test").client(ai.neargo.shop.svc.ServiceName.PLATFORM,
                ai.neargo.shop.spi.entitlement.CoreEntitlementInternalApi.class,java.time.Duration.ofSeconds(5));
        var wrong=new ai.neargo.shop.svc.InternalHttp(locator,"wrong").client(ai.neargo.shop.svc.ServiceName.PLATFORM,
                ai.neargo.shop.spi.entitlement.CoreEntitlementInternalApi.class,java.time.Duration.ofSeconds(5));
        var request=new ai.neargo.shop.spi.entitlement.EntitlementCoreInternal.HistoricalReturnQuery(f.ctx(),no);
        assertThat(api.historicalReturnSource(request)).isEqualTo(returns.getFirst());
        assertThatThrownBy(()->wrong.historicalReturnSource(request)).isInstanceOf(ai.neargo.svc.client.ServiceCallException.class);
        var kind=ai.neargo.shop.trade.dto.EntitlementRecoveryWork.Kind.valueOf("HISTORICAL_COUPON_RETURN");
        // Simulate pay commit followed by a lost core acknowledgement.
        application.getBean(ai.neargo.shop.spi.entitlement.LegacyCouponHistoricalReturnPort.class).reverse(f.ctx(),f.scope().entityNo(),no);
        assertThat(jdbc.queryForObject("SELECT pay_state FROM pmt_migration_return WHERE return_no=?",String.class,no)).isEqualTo("PENDING");
        var recovered=application.getBean(ai.neargo.shop.paybridge.EntitlementRecoveryService.class).recover("MAIN",kind,0,20);
        assertThat(recovered.completed()).isEqualTo(1);
        var restoreEvent=new ai.neargo.shop.event.SysOutbox();
        restoreEvent.setPayload(new tools.jackson.databind.ObjectMapper().writeValueAsString(
                new ai.neargo.shop.spi.entitlement.LegacyCouponHistoryEvents.ReturnDecided("MAIN",f.scope().entityNo(),no)));
        var consumer=application.getBean(ai.neargo.shop.paybridge.LegacyCouponHistoricalReturnConsumer.class);
        consumer.consume(restoreEvent);consumer.consume(restoreEvent);
        var receipt=application.getBean(ai.neargo.shop.spi.entitlement.LegacyCouponHistoricalReturnPort.class).query(f.ctx(),f.scope().entityNo(),no);
        var acknowledgement=new ai.neargo.shop.spi.entitlement.EntitlementCoreInternal.HistoricalReturnAck(f.ctx(),receipt);
        assertThat(api.historicalReturnAck(acknowledgement)).isEqualTo(receipt);
        assertThatThrownBy(()->wrong.historicalReturnAck(acknowledgement)).isInstanceOf(ai.neargo.svc.client.ServiceCallException.class);
        assertThat(jdbc.queryForObject("SELECT pay_state FROM pmt_migration_return WHERE return_no=?",String.class,no)).isEqualTo("RESTORED");
        assertThat(jdbc.queryForObject("SELECT remaining_units FROM ent_instance WHERE entity_no=?",Integer.class,f.scope().entityNo())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_ledger WHERE biz_type='MIGRATION_RETURN' AND instance_no IN (SELECT instance_no FROM ent_instance WHERE entity_no=?)",Integer.class,f.scope().entityNo())).isEqualTo(1);
        assertThat(queries.history(f.ctx(),new UsageHistoryQuery("U"+f.id(),f.scope().entityNo(),null,null,20)).records().getFirst().status()).isEqualTo("REVERSED");
        assertThat(jdbc.queryForObject("SELECT reverted_at FROM pmt_apply WHERE apply_no=?",Long.class,"AP"+f.id())).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_grant",Integer.class)).isZero();
    }
    @Test void actualCancellationRouterKeepsFrozenLegacyHistoryAndRestoresThroughPay() {
        var f=LegacyCouponMigrationFixture.prepareHistory(jdbc,fences,migration);
        source.commit(f.scope(),f.planNo(),"old unpaid source","C"+f.id());
        migration.importPlan(f.scope(),new ai.neargo.shop.pay.migration.dto.MigrationImportViews.ImportCommand(f.planNo(),"history"),"I"+f.id());
        jdbc.update("UPDATE ord_order SET status='CANCELLED',paid_at=NULL WHERE order_no=?","OLD-O"+f.id());
        jdbc.update("UPDATE ord_sub_order SET status='CANCELLED' WHERE sub_order_no=?","OLD-SO"+f.id());
        var router=application.getBean(ai.neargo.shop.portal.port.CouponPortRouter.class);
        router.release("OLD-O"+f.id());router.release("OLD-O"+f.id());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pmt_migration_return WHERE entity_no=?",Integer.class,f.scope().entityNo())).isEqualTo(1);
        var recovered=application.getBean(ai.neargo.shop.paybridge.EntitlementRecoveryService.class).recover("MAIN",
                ai.neargo.shop.trade.dto.EntitlementRecoveryWork.Kind.HISTORICAL_COUPON_RETURN,0,100);
        assertThat(recovered.failed()).isZero();assertThat(recovered.completed()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT remaining_units FROM ent_instance WHERE entity_no=?",Integer.class,f.scope().entityNo())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reverted_at FROM pmt_apply WHERE apply_no=?",Long.class,"AP"+f.id())).isNull();
        assertThat(router.returnedTitleOf("OLD-O"+f.id())).isEqualTo("old");
    }
}
