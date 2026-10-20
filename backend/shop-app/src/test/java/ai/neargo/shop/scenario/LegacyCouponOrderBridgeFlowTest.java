package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import ai.neargo.auth.store.SubjectKind;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.spi.marketing.CampaignPort;
import ai.neargo.shop.spi.marketing.CouponPort;
import ai.neargo.shop.trade.service.LegacyCouponOrderPricingService;
import ai.neargo.shop.trade.service.OrderService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Real SKU/store pricing; campaign selection is a fixture. No entitlement amount is accepted from the client. */
@SpringBootTest(properties={"shop.core.entitlement.legacy-coupon.enabled=true","shop.pay.entitlement.use.enabled=true","shop.pay.entitlement.legacy-coupon.enabled=true","spring.datasource.url=jdbc:h2:mem:legacy-order-bridge;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class LegacyCouponOrderBridgeFlowTest {
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean ai.neargo.shop.spi.entitlement.LegacyCouponUsePort uses;
    @Autowired ai.neargo.shop.paybridge.LegacyCouponCheckoutDecisionConsumer decisionConsumer;
    @Autowired ai.neargo.shop.event.SysOutboxMapper outboxes;
    @Autowired OrderService orders;
    @Autowired ai.neargo.shop.spi.entitlement.EntitlementQueryPort entitlementQueries;
    @Autowired ai.neargo.shop.portal.mp.MpMyCouponController myWallet;
    @Autowired ai.neargo.shop.portal.mp.MpCouponCenterController couponCenter;
    @Autowired tools.jackson.databind.ObjectMapper json;
    @Autowired ai.neargo.shop.pay.entitlement.config.PayEntitlementProperties payProperties;
    @MockitoBean ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort owners;
    @Autowired ai.neargo.shop.trade.service.LegacyCouponBoundOrderService boundOrders;
    @Autowired ai.neargo.shop.trade.service.LegacyCouponCheckoutJournal journal;
    @MockitoBean ai.neargo.shop.spi.entitlement.LegacyCouponIdentityPort identities;
    @Autowired LegacyCouponOrderPricingService pricing;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.context.ApplicationContext application;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean CouponPort coupons;
    @MockitoBean CampaignPort campaigns;
    private String user;
    @BeforeEach void login() {
        payProperties.getEntitlement().getUse().setEnabled(true);
        payProperties.getEntitlement().getLegacyCoupon().setEnabled(true);
        user="U"+UUID.randomUUID();
        jdbc.update("INSERT INTO usr_account(user_no,status,tenant_no,created_at,updated_at) VALUES(?,'NORMAL','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",user);
        var login=new LoginUser(Realm.CONSUMER,SubjectKind.USR,user,"Buyer",List.of(),List.of(),null,null);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login,null,List.of()));
        when(campaigns.autoDiscount(anyList(),any())).thenReturn(CampaignPort.Discount.none());
    }
    @AfterEach void clear() {SecurityContextHolder.clearContext();}
    private OrderService.CreateOrderCommand command(int quantity) {
        return new OrderService.CreateOrderCommand(List.of(new OrderService.CreateOrderCommand.Item("G0001","SK0001",quantity)),
                "STORE_PICKUP","PP0001",null,"OLD-"+user,0L,null,null,null,null,null);
    }
    @Test void actualSkuAndPostCampaignStoreAmountsAreReadWithoutTouchingCouponsOrInventory() {
        var one=pricing.snapshot(user,command(1));
        assertThat(one.currency()).isEqualTo("CNY");
        assertThat(one.groups()).hasSize(1);
        assertThat(one.groups().getFirst().merchantNo()).isEqualTo("M0001");
        assertThat(one.groups().getFirst().storeNo()).isEqualTo("ST-M0001");
        assertThat(one.groups().getFirst().goodsAmountMinor()).isEqualTo(4980);
        assertThat(pricing.snapshot(user,command(1))).isEqualTo(one);
        var two=pricing.snapshot(user,command(2));
        assertThat(two.groups().getFirst().goodsAmountMinor()).isEqualTo(9960);
        assertThat(two.commandHash()).isNotEqualTo(one.commandHash());
        verifyNoInteractions(coupons);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_order WHERE user_no=?",Integer.class,user)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_legacy_coupon_checkout WHERE user_no=?",Integer.class,user)).isZero();
    }
    @Test void originalCoreOrderCreatesAgainstTheHeldAllocationAndWritesItsDecisionAtomically() {
        var cmd=command(1);var priced=pricing.snapshot(user,cmd);
        String id=UUID.randomUUID().toString();
        var ctx=new ai.neargo.shop.spi.entitlement.EntitlementContext("MAIN",user,"REQ"+id,"a".repeat(64));
        var origin=new ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceIdentity(
                ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceSystem.PMT,
                ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceKind.INSTANCE,cmd.couponNo());
        when(identities.ownedOrigins(ctx,user,cmd.couponNo())).thenReturn(List.of(
                new ai.neargo.shop.spi.entitlement.LegacyCouponIdentityPort.Origin(origin,"T1","M0001","MERCHANT",
                        "COMMIT_DECIDED","B1","b".repeat(64),true,false,true)));
        var fact=journal.stage(ctx,new ai.neargo.shop.trade.service.LegacyCouponCheckoutJournal.Stage(
                ai.neargo.shop.spi.entitlement.LegacyCouponCheckoutSourcePort.IntentKind.CHECKOUT,"LC"+id,"LO"+id,user,
                "M0001",origin,"EI"+id,"B1","b".repeat(64),priced.currency(),priced.groups(),priced.commandHash()));
        var held=new ai.neargo.shop.spi.entitlement.LegacyCouponUsePort.Operation(fact.checkoutNo(),fact.operationNo(),
                fact.targetInstanceNo(),fact.entityNo(),fact.currency(),fact.economicHash(),
                ai.neargo.shop.spi.entitlement.LegacyCouponUsePort.State.HELD,100L,List.of(
                new ai.neargo.shop.spi.entitlement.LegacyCouponUsePort.Allocation("ST-M0001","M0001","ST-M0001",100)));
        var binding=new ai.neargo.shop.trade.service.LegacyCouponBoundOrderService.Binding(ctx,fact.checkoutNo(),priced.commandHash(),held);
        String key="BOUND"+id;
        var order=boundOrders.createBound(user,cmd,key,null,binding);
        assertThat(order.amount().discountMinor()).isEqualTo(100);
        assertThat(journal.decision(ctx,fact.checkoutNo()).orElseThrow().orderNo()).isEqualTo(order.payOrderNo());
        assertThat(boundOrders.createBound(user,cmd,key,null,binding).payOrderNo()).isEqualTo(order.payOrderNo());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_order WHERE user_no=?",Integer.class,user)).isEqualTo(1);
        verify(coupons,never()).allocate(any(),any(),any());verify(coupons,never()).markUsed(any(),any(),any(),any());
    }

    private String migratedAsset(OrderService.CreateOrderCommand cmd) {
        return LegacyCouponFixtureSupport.asset(jdbc,json,identities,owners,user,cmd);
    }
    @Test void publicOrderServiceEntryUsesRealPayAndReplaysWithoutRepricingAfterClosingNewUse() {
        var cmd=command(1);String asset=migratedAsset(cmd),key="ENTRY"+UUID.randomUUID();
        var order=orders.create(cmd,key);
        assertThat(order.amount().discountMinor()).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT remaining_units FROM ent_instance WHERE instance_no=?",Integer.class,asset)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reserved_units FROM ent_instance WHERE instance_no=?",Integer.class,asset)).isZero();
        payProperties.getEntitlement().getUse().setEnabled(false);payProperties.getEntitlement().getLegacyCoupon().setEnabled(false);
        jdbc.update("UPDATE prd_sku SET price=price+99 WHERE sku_no='SK0001'");
        try {assertThat(orders.create(cmd,key).payOrderNo()).isEqualTo(order.payOrderNo());}
        finally {jdbc.update("UPDATE prd_sku SET price=price-99 WHERE sku_no='SK0001'");}
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_ledger WHERE instance_no=?",Integer.class,asset)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_order WHERE user_no=?",Integer.class,user)).isEqualTo(1);
        verify(coupons,never()).allocate(any(),any(),any());verify(coupons,never()).markUsed(any(),any(),any(),any());
    }
    @Test void originalCreateRejectionProducesDurableAbortAndReleasesTheRealPayHold() {
        var base=command(1);
        var bad=new OrderService.CreateOrderCommand(base.items(),base.fulfillment(),base.pickupNo(),null,base.couponNo(),0L,null,null,"INVALID",null,null);
        String asset=migratedAsset(bad);
        assertThatThrownBy(()->orders.create(bad,"FAIL"+UUID.randomUUID())).isInstanceOf(ai.neargo.shop.common.BizException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM ord_legacy_coupon_checkout WHERE user_no=?",String.class,user)).isEqualTo("ABORTED");
        assertThat(jdbc.queryForObject("SELECT status FROM ent_legacy_coupon_operation WHERE instance_no=?",String.class,asset)).isEqualTo("RELEASED");
        assertThat(jdbc.queryForObject("SELECT remaining_units FROM ent_instance WHERE instance_no=?",Integer.class,asset)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT reserved_units FROM ent_instance WHERE instance_no=?",Integer.class,asset)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_order WHERE user_no=?",Integer.class,user)).isZero();
    }

    @Test void committedCoreDecisionSurvivesLostPayResponseAndActualOutboxCompletesTheSameOperation() {
        var cmd=command(1);String asset=migratedAsset(cmd),key="LOST"+UUID.randomUUID();
        doThrow(ai.neargo.shop.common.BizException.of(ai.neargo.shop.common.ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE))
                .when(uses).commit(any(),any());
        assertThatThrownBy(()->orders.create(cmd,key)).isInstanceOf(ai.neargo.shop.common.BizException.class);
        String checkout=jdbc.queryForObject("SELECT checkout_no FROM ord_legacy_coupon_checkout WHERE user_no=?",String.class,user);
        assertThat(jdbc.queryForObject("SELECT status FROM ord_legacy_coupon_checkout WHERE checkout_no=?",String.class,checkout)).isEqualTo("COMMITTED");
        assertThat(jdbc.queryForObject("SELECT status FROM ent_legacy_coupon_operation WHERE instance_no=?",String.class,asset)).isEqualTo("HELD");
        payProperties.getEntitlement().getUse().setEnabled(false);payProperties.getEntitlement().getLegacyCoupon().setEnabled(false);
        doCallRealMethod().when(uses).commit(any(),any());
        String event=jdbc.queryForObject("SELECT event_no FROM sys_outbox WHERE aggregate_id=? AND event_type='LEGACY_COUPON_CHECKOUT_DECIDED'",String.class,checkout);
        var original=outboxes.findByEventNo(event);
        decisionConsumer.consume(original);decisionConsumer.consume(original);
        assertThat(jdbc.queryForObject("SELECT status FROM ent_legacy_coupon_operation WHERE instance_no=?",String.class,asset)).isEqualTo("COMMITTED");
        assertThat(jdbc.queryForObject("SELECT remaining_units FROM ent_instance WHERE instance_no=?",Integer.class,asset)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reserved_units FROM ent_instance WHERE instance_no=?",Integer.class,asset)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_ledger WHERE instance_no=?",Integer.class,asset)).isEqualTo(3);
        assertThat(orders.create(cmd,key).payOrderNo()).isNotBlank();
    }
    @Test void failureAfterWritingTheDecisionStillRollsBackTheOrderAndReleasesItsHold() {
        var cmd=command(1);String asset=migratedAsset(cmd);
        jdbc.execute("ALTER TABLE sys_outbox ADD CONSTRAINT reject_legacy_order_created CHECK (event_type<>'ORDER_CREATED' OR payload NOT LIKE '%"+user+"%')");
        try {assertThatThrownBy(()->orders.create(cmd,"ROLLBACK"+UUID.randomUUID())).isInstanceOf(RuntimeException.class);}
        finally {jdbc.execute("ALTER TABLE sys_outbox DROP CONSTRAINT reject_legacy_order_created");}
        assertThat(jdbc.queryForObject("SELECT status FROM ord_legacy_coupon_checkout WHERE user_no=?",String.class,user)).isEqualTo("ABORTED");
        assertThat(jdbc.queryForObject("SELECT status FROM ent_legacy_coupon_operation WHERE instance_no=?",String.class,asset)).isEqualTo("RELEASED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_order WHERE user_no=?",Integer.class,user)).isZero();
        assertThat(jdbc.queryForObject("SELECT remaining_units FROM ent_instance WHERE instance_no=?",Integer.class,asset)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT reserved_units FROM ent_instance WHERE instance_no=?",Integer.class,asset)).isZero();
    }

    @Test void originalCancelWritesARealReversalAndOutboxReturnsTheOriginalCouponOnlyOnce() {
        var cmd=command(1);String asset=migratedAsset(cmd);
        var order=orders.create(cmd,"CANCEL"+UUID.randomUUID());
        String checkout=jdbc.queryForObject("SELECT checkout_no FROM ord_legacy_coupon_checkout WHERE user_no=?",String.class,user);
        payProperties.getEntitlement().getUse().setEnabled(false);payProperties.getEntitlement().getLegacyCoupon().setEnabled(false);
        orders.cancel(order.payOrderNo(),"changed plans");
        var ctx=new ai.neargo.shop.spi.entitlement.EntitlementContext("MAIN",user,"CANCEL","a".repeat(64));
        var reversal=journal.reversal(ctx,checkout).orElseThrow();
        assertThat(reversal.reason()).isEqualTo(ai.neargo.shop.spi.entitlement.LegacyCouponCheckoutSourcePort.ReverseReason.CANCELLED);
        assertThat(reversal.discountMinor()).isEqualTo(100);
        String event=jdbc.queryForObject("SELECT event_no FROM sys_outbox WHERE aggregate_id=? AND event_type='LEGACY_COUPON_CHECKOUT_DECIDED' ORDER BY id DESC LIMIT 1",String.class,checkout);
        decisionConsumer.consume(outboxes.findByEventNo(event));decisionConsumer.consume(outboxes.findByEventNo(event));
        assertThat(jdbc.queryForObject("SELECT status FROM ent_legacy_coupon_operation WHERE instance_no=?",String.class,asset)).isEqualTo("REVERSED");
        assertThat(jdbc.queryForObject("SELECT remaining_units FROM ent_instance WHERE instance_no=?",Integer.class,asset)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT reserved_units FROM ent_instance WHERE instance_no=?",Integer.class,asset)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_ledger WHERE instance_no=? AND biz_type='LEGACY_REVERSE'",Integer.class,asset)).isEqualTo(1);
    }

    @Test void refundSourceRejectsPartialLedgerEvenIfStatusWasMarkedRefundedThenRestoresOnlyTheOriginalUnit() {
        // Explicit already-settled core refund fixtures; this verifies source authority, not the external refund gateway.
        var cmd=command(1);String asset=migratedAsset(cmd);
        var order=orders.create(cmd,"REFUND"+UUID.randomUUID());
        String sub=order.subOrders().getFirst().orderNo(),afterSale="AS"+UUID.randomUUID();
        jdbc.update("UPDATE ord_order SET status='PAID' WHERE order_no=?",order.payOrderNo());
        jdbc.update("UPDATE ord_sub_order SET status='REFUNDED',refund_progress='PARTIAL',refunded_minor=pay_amount-1 WHERE sub_order_no=?",sub);
        jdbc.update("INSERT INTO ord_after_sale(after_sale_no,sub_order_no,order_no,user_no,entity_no,type,status,reason,refund_minor,refunded_at,tenant_no,created_at,updated_at) "
                +"VALUES(?,?,?,?,'M0001','REFUND_ONLY','REFUNDED','fixture',1,?,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",afterSale,sub,order.payOrderNo(),user,System.currentTimeMillis());
        var ctx=new ai.neargo.shop.spi.entitlement.EntitlementContext("MAIN","REFUND_RECOVERY","REFUND","a".repeat(64));
        assertThat(journal.returnRefundedOrder(ctx,afterSale)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT remaining_units FROM ent_instance WHERE instance_no=?",Integer.class,asset)).isEqualTo(1);
        jdbc.update("UPDATE ord_sub_order SET refund_progress='FULL',refunded_minor=pay_amount WHERE sub_order_no=?",sub);
        var reversed=journal.returnRefundedOrder(ctx,afterSale).orElseThrow();
        assertThat(reversed.reason()).isEqualTo(ai.neargo.shop.spi.entitlement.LegacyCouponCheckoutSourcePort.ReverseReason.FULL_REFUND);
        assertThat(reversed.sourceRef()).isEqualTo(afterSale);
        assertThat(reversed.discountMinor()).isEqualTo(100);
        assertThat(journal.returnRefundedOrder(ctx,afterSale)).contains(reversed);
        String checkout=jdbc.queryForObject("SELECT checkout_no FROM ord_legacy_coupon_checkout WHERE user_no=?",String.class,user);
        String event=jdbc.queryForObject("SELECT event_no FROM sys_outbox WHERE aggregate_id=? AND event_type='LEGACY_COUPON_CHECKOUT_DECIDED' ORDER BY id DESC LIMIT 1",String.class,checkout);
        decisionConsumer.consume(outboxes.findByEventNo(event));decisionConsumer.consume(outboxes.findByEventNo(event));
        assertThat(jdbc.queryForObject("SELECT remaining_units FROM ent_instance WHERE instance_no=?",Integer.class,asset)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_ledger WHERE instance_no=? AND biz_type='LEGACY_REVERSE'",Integer.class,asset)).isEqualTo(1);
    }

    @Test void actualOrderPreviewReadsThePayOwnerWithoutWritingACheckoutOrReservingAUnit() {
        var cmd=command(1);String asset=migratedAsset(cmd);
        var preview=orders.preview(cmd);
        assertThat(preview.amount().discountMinor()).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_legacy_coupon_checkout WHERE user_no=?",Integer.class,user)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_legacy_coupon_operation WHERE instance_no=?",Integer.class,asset)).isZero();
        assertThat(jdbc.queryForObject("SELECT reserved_units FROM ent_instance WHERE instance_no=?",Integer.class,asset)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_ledger WHERE instance_no=?",Integer.class,asset)).isEqualTo(1);
    }

    @Test void historicalUsageRepairIsBoundedAndNeverRepostsLedgerOrHistoricalNotifications() {
        var cmd=command(1);String asset=migratedAsset(cmd);
        var first=orders.create(cmd,"REPAIR-FIRST"+UUID.randomUUID());
        orders.create(cmd,"REPAIR-SECOND"+UUID.randomUUID());
        int ledgers=jdbc.queryForObject("SELECT COUNT(*) FROM ent_ledger WHERE instance_no=?",Integer.class,asset);
        int notifications=jdbc.queryForObject("SELECT COUNT(*) FROM pay_ent_outbox WHERE event_type='LEGACY_COUPON_USAGE_CHANGED' AND payload LIKE ?",Integer.class,"%"+user+"%");
        jdbc.update("DELETE FROM ent_usage WHERE user_no=?",user);
        payProperties.getEntitlement().getUse().setEnabled(false);payProperties.getEntitlement().getLegacyCoupon().setEnabled(false);
        var ctx=new ai.neargo.shop.spi.entitlement.EntitlementContext("MAIN","LEGACY_USAGE_REPAIR","REPAIR","a".repeat(64));
        var repair=application.getBean(ai.neargo.shop.pay.migration.service.LegacyCouponUsageRepairService.class);
        String operation=jdbc.queryForObject("SELECT operation_no FROM ent_legacy_coupon_operation WHERE source_order_no=?",String.class,first.payOrderNo());
        jdbc.update("UPDATE ent_ledger SET biz_type='BROKEN' WHERE instance_no=? AND biz_key=?",asset,"LEGACY_COMMIT:"+operation);
        clearInvocations(uses,owners);
        var one=repair.repair(ctx,0,1);
        assertThat(one.scanned()).isEqualTo(1);assertThat(one.repaired()).isZero();assertThat(one.failed()).isEqualTo(1);
        var two=repair.repair(ctx,one.nextId(),1);
        assertThat(two.repaired()).isEqualTo(1);assertThat(two.failed()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_usage WHERE user_no=?",Integer.class,user)).isEqualTo(1);
        jdbc.update("UPDATE ent_ledger SET biz_type='LEGACY_COMMIT' WHERE instance_no=? AND biz_key=?",asset,"LEGACY_COMMIT:"+operation);
        assertThat(repair.repair(ctx,0,1).repaired()).isEqualTo(1);
        assertThat(repair.repair(ctx,0,1).scanned()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_usage WHERE user_no=? AND operator_no=?",Integer.class,user,user)).isEqualTo(2);
        verifyNoInteractions(uses,owners);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_ledger WHERE instance_no=?",Integer.class,asset)).isEqualTo(ledgers);
        assertThat(jdbc.queryForObject("SELECT remaining_units FROM ent_instance WHERE instance_no=?",Integer.class,asset)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_ent_outbox WHERE event_type='LEGACY_COUPON_USAGE_CHANGED' AND payload LIKE ?",Integer.class,"%"+user+"%")).isEqualTo(notifications);
    }
    @Test void existingWalletEndpointReadsActualPayUnitsAfterAnOrderWithoutChangingFrozenLegacyRows() {
        var cmd=command(1);String asset=migratedAsset(cmd),template="T"+UUID.randomUUID();
        long from=jdbc.queryForObject("SELECT effective_from FROM ent_instance WHERE instance_no=?",java.time.LocalDateTime.class,asset)
                .toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
        long until=jdbc.queryForObject("SELECT effective_until FROM ent_instance WHERE instance_no=?",java.time.LocalDateTime.class,asset)
                .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()-1;
        jdbc.update("INSERT INTO pmt_coupon(coupon_no,entity_no,title,benefit_mode,benefit_value,min_amount_minor,times_total,issue_mode,start_at,end_at,migration_state,migration_batch_no,migration_watermark,migration_frozen_revision,tenant_no,created_at,updated_at) "
                +"VALUES(?,'M0001','Old coupon','CASH',100,0,2,'TARGETED',?,?,'COMMIT_DECIDED','B1',?,0,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",template,from,until,"b".repeat(64));
        jdbc.update("INSERT INTO pmt_user_coupon(user_coupon_no,coupon_no,user_no,entity_no,received_at,expire_at,tenant_no,created_at,updated_at) "
                +"VALUES(?,?,?,'M0001',?,?,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",cmd.couponNo(),template,user,from,until);
        var order=orders.create(cmd,"WALLET"+UUID.randomUUID());
        var detail=orders.detail(order.subOrders().getFirst().orderNo(),null);
        assertThat(detail.discountLines()).anySatisfy(line->{
            assertThat(line.kind()).isEqualTo("COUPON");assertThat(line.name()).isEqualTo("Old coupon");assertThat(line.amountMinor()).isEqualTo(100);
        });
        var queryContext=new ai.neargo.shop.spi.entitlement.EntitlementContext("MAIN",user,"USAGE-QUERY","a".repeat(64));
        var consumption=entitlementQueries.detail(queryContext,new ai.neargo.shop.spi.entitlement.EntitlementQueryPort.DetailQuery(user,"M0001",asset,null)).recentUsage();
        assertThat(consumption).hasSize(1);
        assertThat(consumption.getFirst().amountMinor()).isEqualTo(100);
        assertThat(consumption.getFirst().units()).isEqualTo(1);
        var usageDetail=entitlementQueries.usage(queryContext,new ai.neargo.shop.spi.entitlement.EntitlementQueryPort.UsageQuery(user,"M0001",consumption.getFirst().usageNo()));
        assertThat(usageDetail.sourceOrderNo()).isEqualTo(order.payOrderNo());
        assertThat(usageDetail.remainingUnits()).isEqualTo(1);
        assertThat(usageDetail.items()).isEmpty();
        assertThatThrownBy(()->entitlementQueries.usage(queryContext,new ai.neargo.shop.spi.entitlement.EntitlementQueryPort.UsageQuery("OTHER","M0001",consumption.getFirst().usageNo())))
                .isInstanceOf(ai.neargo.shop.common.BizException.class);
        var wallet=myWallet.mine().stream().filter(c->c.userCouponNo().equals(cmd.couponNo())).findFirst().orElseThrow();
        assertThat(wallet.timesTotal()).isEqualTo(2);assertThat(wallet.timesUsed()).isEqualTo(1);assertThat(wallet.remaining()).isEqualTo(1);
        assertThat(wallet.redeemCode()).isNull();assertThat(wallet.usableNow()).isTrue();
        assertThat(jdbc.queryForObject("SELECT times_used FROM pmt_user_coupon WHERE user_coupon_no=?",Integer.class,cmd.couponNo())).isZero();
        var second=orders.create(cmd,"WALLET-SECOND"+UUID.randomUUID());
        var spent=myWallet.mine().stream().filter(c->c.userCouponNo().equals(cmd.couponNo())).findFirst().orElseThrow();
        assertThat(spent.remaining()).isZero();assertThat(spent.timesUsed()).isEqualTo(2);assertThat(spent.status()).isEqualTo("USED");
        var best=couponCenter.best(new ai.neargo.shop.portal.mp.MpCouponCenterController.BestReq(List.of(
                new ai.neargo.shop.portal.mp.MpCouponCenterController.BestReq.Item("G0001","SK0001",1))));
        assertThat(best.bestUserCouponNo()).isNull();assertThat(best.discountMinor()).isZero();
        orders.cancel(second.payOrderNo(),"return second coupon use");
        var pendingReturn=orders.detail(second.subOrders().getFirst().orderNo(),null).returned();
        assertThat(pendingReturn==null || pendingReturn.couponTitle()==null).isTrue();
        String checkout=jdbc.queryForObject("SELECT checkout_no FROM ord_legacy_coupon_checkout WHERE order_no=?",String.class,second.payOrderNo());
        String event=jdbc.queryForObject("SELECT event_no FROM sys_outbox WHERE aggregate_id=? AND event_type='LEGACY_COUPON_CHECKOUT_DECIDED' ORDER BY id DESC LIMIT 1",String.class,checkout);
        decisionConsumer.consume(outboxes.findByEventNo(event));
        assertThat(orders.detail(second.subOrders().getFirst().orderNo(),null).returned().couponTitle()).isEqualTo("Old coupon");
        payProperties.getEntitlement().getUse().setEnabled(false);payProperties.getEntitlement().getLegacyCoupon().setEnabled(false);
        var paused=myWallet.mine().stream().filter(c->c.userCouponNo().equals(cmd.couponNo())).findFirst().orElseThrow();
        assertThat(paused.remaining()).isEqualTo(1);assertThat(paused.usableNow()).isFalse();
        var history=entitlementQueries.detail(queryContext,new ai.neargo.shop.spi.entitlement.EntitlementQueryPort.DetailQuery(user,"M0001",asset,null)).recentUsage();
        assertThat(history).hasSize(2);
        assertThat(entitlementQueries.history(queryContext,new ai.neargo.shop.spi.entitlement.EntitlementQueryPort.UsageHistoryQuery(user,"M0001",asset,null,20)).records())
                .containsExactlyElementsOf(history);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_ent_outbox WHERE event_type='LEGACY_COUPON_USAGE_CHANGED' AND payload LIKE ?",Integer.class,"%"+user+"%")).isEqualTo(3);
        assertThat(history).extracting(ai.neargo.shop.spi.entitlement.EntitlementQueryPort.UsageItem::status)
                .containsExactlyInAnyOrder("COMMITTED","REVERSED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_usage WHERE user_no=?",Integer.class,user)).isEqualTo(2);
    }

}
