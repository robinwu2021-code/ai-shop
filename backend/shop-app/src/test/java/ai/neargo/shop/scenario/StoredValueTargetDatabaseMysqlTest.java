package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import ai.neargo.shop.member.dto.MemberCodeScope;
import ai.neargo.shop.member.service.MemberCodeResolveService;
import ai.neargo.shop.pay.storedvalue.service.StoredValuePlanService;
import ai.neargo.shop.pay.storedvalue.service.StoredValuePlanRechargeService;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.spi.storedvalue.StoredValuePlans;
import ai.neargo.shop.spi.storedvalue.StoredValuePlanRecharges;
import ai.neargo.shop.paybridge.EntitlementServiceUseCoordinator;
import ai.neargo.shop.paybridge.EntitlementRecoveryService;
import ai.neargo.shop.trade.dto.EntitlementRecoveryWork.Kind;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementUseFactsPort;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort;
import ai.neargo.shop.spi.storedvalue.StoredValueUseQuotePort;
import ai.neargo.shop.trade.dto.AuthorizationCommand;
import ai.neargo.shop.trade.dto.ServiceOrderCreate;
import ai.neargo.shop.trade.dto.ServiceOrderSnapshot;
import ai.neargo.shop.trade.dto.ServiceUseSubmit;
import ai.neargo.shop.trade.service.EntitlementAuthorizationService;
import ai.neargo.shop.trade.service.EntitlementServiceOrderService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import ai.neargo.shop.common.config.ModuleDataSources;
import javax.sql.DataSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Qualifier;

@SpringBootTest(properties = {
    "shop.pay.entitlement.use.enabled=true", "shop.member-code.enabled=true",
    "shop.core.entitlement.authorization.enabled=true", "shop.pay.stored-value.enabled=true",
    "shop.pay.entitlement.cursor-key=stored-value-test-cursor-key-at-least-thirty-two-characters",
    "spring.datasource.url=${MM_SOURCE_MYSQL_URL}",
    "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
    "spring.datasource.username=${MM_SOURCE_MYSQL_USER}",
    "spring.datasource.password=${MM_SOURCE_MYSQL_PASSWORD}",
    "spring.sql.init.mode=never", "shop.seed.enabled=false",
    "shop.modules.member-marketing.datasource=rights",
    "shop.datasources.rights.url=${MM_TARGET_MYSQL_URL}",
    "shop.datasources.rights.username=${MM_TEST_MYSQL_USER}",
    "shop.datasources.rights.password=${MM_TEST_MYSQL_PASSWORD}"
})
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "MM_SOURCE_MYSQL_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/mm_source_test_[a-z0-9_]+.*")
@EnabledIfEnvironmentVariable(named = "MM_TARGET_MYSQL_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/mm_target_test_[a-z0-9_]+.*")
class StoredValueTargetDatabaseMysqlTest {
    @Autowired EntitlementServiceOrderService services;
    @Autowired EntitlementAuthorizationService consent;
    @Autowired StoredValueUseQuotePort quotes;
    @Autowired EntitlementUsePort uses;
    @Autowired EntitlementUseFactsPort facts;
    @Autowired StoredValuePlanService plans;
    @Autowired StoredValuePlanRechargeService recharges;
    @Autowired EntitlementServiceUseCoordinator coordinator;
    @Autowired EntitlementRecoveryService recovery;
    @Autowired MemberCodeResolveService members;
    @Autowired JdbcTemplate jdbc;
    @Autowired @Qualifier(ModuleDataSources.MEMBER_MARKETING_DATA_SOURCE) DataSource memberData;
    private JdbcTemplate target;
    private String user, actor, entity, store, sku, reference;
    private EntitlementContext ctx;
    private String id() { return UUID.randomUUID().toString().replace("-", ""); }
    @BeforeEach void sourceAndOwedCredit() {
        target = new JdbcTemplate(memberData);
        user = id(); actor = id(); entity = id(); store = id(); sku = id();
        ctx = new EntitlementContext("MAIN", actor, id(), "a".repeat(64));
        for (var no : List.of(user, actor)) jdbc.update("INSERT INTO usr_account(user_no,status,tenant_no,created_at,"
                + "updated_at) VALUES (?,'NORMAL','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", no);
        jdbc.update("INSERT INTO mch_entity(entity_no,owner_user_no,name,status,market,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,'beauty','ACTIVE','CN','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", entity, actor);
        jdbc.update("INSERT INTO mch_store(store_no,entity_no,name,status,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,'beauty store','ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", store, entity);
        String goods = id();
        jdbc.update("INSERT INTO prd_goods(goods_no,entity_no,store_no,title,type,on_sale,audit_status,fulfillments,"
                + "tenant_no,created_at,updated_at) VALUES (?,?,?,'Care','SERVICE',1,'APPROVED','[\"STORE_VERIFY\"]',"
                + "'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", goods, entity, store);
        jdbc.update("INSERT INTO prd_sku(sku_no,goods_no,entity_no,market,price,stock,locked_stock,tenant_no,created_at,"
                + "updated_at) VALUES (?,?,?,'CN',1000,0,0,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", sku, goods, entity);
        var code = members.issue("MAIN", user);
        reference = members.resolve(new MemberCodeScope("MAIN", entity, store, actor), code.memberCode()).customerRef();
        var plan = plans.create(new CatalogScope("MAIN", entity, actor, false),
                new StoredValuePlans.Create(store, "线下充值", "CNY", 1000, 100), id());
        var charge = new StoredValuePlanRecharges.Create(user, store, "测试门店", plan.planNo(), plan.versionNo(),
                "d".repeat(64));
        var paid = recharges.confirmOffline(ctx, entity, charge);
        assertThat(paid.status().name()).isEqualTo("CREDITED");
        assertThat(recharges.confirmOffline(ctx, entity, charge).rechargeNo()).isEqualTo(paid.rechargeNo());
    }
    private ServiceOrderSnapshot authorized(String key) {

        var order = services.createMonetary(ctx, entity,
                new ServiceOrderCreate(reference, store, List.of(new ServiceOrderCreate.Line(sku, 1))), key);
        assertThat(order.fact().items().getFirst().amountMinor()).isEqualTo(1000);
        var quote = quotes.quote(ctx, order.fact().source()); assertThat(quote.redeemable()).isTrue();
        services.bindMoneyPreview(ctx, entity, store, order.serviceOrderNo(), quote);
        var auth = consent.confirm("MAIN", user, order.serviceOrderNo(),
                new AuthorizationCommand(0, quote.quoteRef(), quote.snapshotHash(), id()));
        return services.stage(ctx, entity, store, new ServiceUseSubmit(order.serviceOrderNo(), quote.quoteRef(),
                auth.authorizationRef(), quote.assetRevision()));
    }

    @Test void originalInterfacesPostReleaseConsumeAndRefundOnlyInTheTargetLedger() {
        var cancelled = authorized("release");
        assertThat(uses.prepare(cancelled.context(), cancelled.command()).status()).isEqualTo(EntitlementUsePort.State.HELD);
        var cancellation = services.cancel(ctx, entity, store, cancelled.serviceOrderNo(), "cancel");
        assertThat(uses.release(ctx, new EntitlementUsePort.CancelUse(cancelled.operationNo(),
                cancelled.fact().source(), cancellation.decision())).status()).isEqualTo(EntitlementUsePort.State.RELEASED);
        assertThat(amount("held_principal_minor+held_bonus_minor")).isZero();

        var order = authorized("consume");
        uses.prepare(order.context(), order.command());
        var decision = services.accept(ctx, entity, facts.query(ctx, entity, order.operationNo())).decision();
        var consume = new EntitlementUsePort.UseDecision(order.operationNo(), order.fact().source(), decision);
        long mapped = target.queryForObject("SELECT COUNT(*) FROM pay_document_number", Long.class);
        // Only this disposable target receives the injected journal failure.
        target.execute("CREATE TRIGGER reject_mm_posting BEFORE INSERT ON sv_posting_line FOR EACH ROW "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected posting failure'");
        try {
            assertThatThrownBy(() -> uses.commit(ctx, consume)).hasStackTraceContaining("injected posting failure");
        } finally {
            target.execute("DROP TRIGGER reject_mm_posting");
        }
        assertThat(amount("principal_minor+bonus_minor")).isEqualTo(1100);
        assertThat(amount("held_principal_minor+held_bonus_minor")).isEqualTo(1000);
        assertThat(uses.query(ctx, entity, order.operationNo()).status()).isEqualTo(EntitlementUsePort.State.HELD);
        assertThat(target.queryForObject("SELECT COUNT(*) FROM sv_voucher WHERE biz_no=?", Long.class,
                order.operationNo())).isZero();
        assertThat(target.queryForObject("SELECT COUNT(*) FROM pay_document_number", Long.class)).isEqualTo(mapped);
        assertThat(target.queryForObject("SELECT COUNT(*) FROM ent_usage WHERE user_no=?", Long.class, user)).isZero();
        var committed = uses.commit(ctx, consume);
        assertThat(committed.status()).isEqualTo(EntitlementUsePort.State.COMMITTED);
        services.acknowledge(ctx, entity, uses.query(ctx, entity, order.operationNo()));
        assertThat(amount("principal_minor+bonus_minor")).isEqualTo(100);
        var refund = services.decideRefund(ctx, entity, store, order.serviceOrderNo(),
                facts.query(ctx, entity, order.operationNo()));
        assertThat(refund.refundStatus()).isEqualTo("REFUND_PENDING");
        assertThat(jdbc.update("UPDATE ord_service_refund_recovery_owner SET scan_owner='TARGET' "
                + "WHERE tenant_no='MAIN' AND scan_owner='PAY'")).isOne();
        try {
            assertThat(recovery.recover("MAIN", Kind.SERVICE_REFUND, 0, 100).completed()).isPositive();
        } finally {
            jdbc.update("UPDATE ord_service_refund_recovery_owner SET scan_owner='PAY' "
                    + "WHERE tenant_no='MAIN' AND scan_owner='TARGET'");
        }
        assertThat(services.refundOrder(ctx, entity, store, order.serviceOrderNo()).refundStatus()).isEqualTo("REFUNDED");
        var refunded = coordinator.refund(ctx, entity, store, order.serviceOrderNo());
        assertThat(refunded.status()).isEqualTo("REFUNDED");
        assertThat(coordinator.refund(ctx, entity, store, order.serviceOrderNo())).isEqualTo(refunded);
        assertThat(amount("principal_minor+bonus_minor")).isEqualTo(1100);
        assertThat(amount("account_seq")).isEqualTo(3);
        assertThat(target.queryForObject("""
                SELECT COUNT(*) FROM sv_voucher v JOIN sv_account a ON a.account_no=v.account_no
                JOIN pay_document_number d ON d.source_no=v.voucher_no AND d.document_type='SV_VOUCHER'
                WHERE a.user_no=?
                """, Long.class, user)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_account WHERE user_no=?", Long.class, user)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_use_intent WHERE operation_no=?", Long.class,
                order.operationNo())).isZero();
    }

    private long amount(String columns) {
        return target.queryForObject("SELECT " + columns + " FROM sv_account WHERE user_no=?", Long.class, user);
    }
}
