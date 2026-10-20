package ai.neargo.shop.scenario;
import ai.neargo.shop.spi.points.*;
import ai.neargo.shop.spi.points.PointsOperationsPort.*;
import ai.neargo.shop.pay.points.model.*;
import ai.neargo.shop.pay.points.posting.PointsPostingService;
import ai.neargo.shop.pay.points.type.PointTypeService;
import ai.neargo.shop.pay.points.event.*;
import ai.neargo.shop.pay.points.store.PointsReadStore;
import ai.neargo.shop.pay.points.operation.PointsOperationKeys;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.util.*;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(properties= {
    "shop.points.enabled=true", "shop.points.default-participation-enabled=true", "shop.points.default-participation-since=1", "spring.datasource.url=jdbc:h2:mem:points_unified;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
}) @ActiveProfiles("test") @Import(RedeemServiceTest.VerifiedCapabilities.class) class PointsUnifiedOperationsTest {
    @Autowired ai.neargo.shop.pay.points.agreement.AgreementService agreements;
    @Autowired @Qualifier("dataSource") DataSource shopData;
    @Autowired PointsOperationsPort ops;
    @Autowired ai.neargo.shop.pay.points.admin.PointsInspectionService inspection;
    @Autowired PointTypeService types;
    @Autowired PointsPostingService posting;
    @Autowired PointsSourceFactHandler source;
    @Autowired PointsEventQueue queue;
    @Autowired PointsReadStore read;
    @Autowired @Qualifier("payDataSource") DataSource data;
    JdbcTemplate jdbc() {
        return new JdbcTemplate(data);
    }
    String create(long money) {
        String type="UO_"+UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        types.create(new PointTypeService.CreateType(type, "CN", new RedeemRate(1, 1), 10000, 365), "maker");
        types.activate(type, "maker");
        if(money>0) {
            var evidence=new EvidenceRef("TEST:"+type, "CNY", money);
            posting.recordEvidence(evidence);
            posting.post(new Voucher("FUND:"+type, type, "CNY", new RedeemRate(1, 1), List.of(new PointsMove(new AccountKey(type, HolderType.MINT, "PLATFORM", Bucket.ISSUED), -money), new PointsMove(new AccountKey(type, HolderType.ISSUE, "PLATFORM", Bucket.ISSUABLE), money)), List.of(new FundMove(FundBucket.U, money)), List.of(), 0, List.of(evidence), "{}"));
        }
        return type;
    }
    Context context(String type, String business, String operation) {
        return new Context("REQ:"+UUID.randomUUID(), new Operation(Source.TRADE, business, operation, type), "CNY");
    }
    PointsFactPort.LifecycleFact fact(String type, String sub, PointsFactPort.FactKind kind) {
        var payment=new PointsRefundPort.PaymentSnapshot("DIRECT", "TEST", "M", "S", "A", "SUB", "TRADE:"+sub, "PAY:"+sub, 10000);
        var f=new PointsFactPort.LifecycleFact("F:"+kind+":"+type+":"+sub, null, sub, "USER", type, 1, kind, 10000, 0, List.of(new PointsFactPort.LineSnapshot("I", 1, 10000, 0)), Instant.parse("2026-10-20T00:00:00Z"), payment);
        return new PointsFactPort.LifecycleFact(f.eventNo(), PointsLifecycleFacts.digest(f, new tools.jackson.databind.ObjectMapper()::writeValueAsString), f.subOrderNo(), f.userNo(), f.typeCode(), f.sourceVersion(), f.kind(), f.finalGoodsMinor(), f.refundedGoodsMinor(), f.lines(), f.occurredAt(), f.payment());
    }
    Earning earning(String type, String sub) {
        var f=fact(type, sub, PointsFactPort.FactKind.PAID);
        return new Earning(new PointsEarnPort.GrantCommand("E:"+type+":"+sub, sub, "USER", type, "S", "TEST", "ONLINE", 1, List.of(new PointsEarnPort.EarnLine("I", "G", "C", 1, 10000, 0, new PointsEarnPort.EarnRule(PointsEarnPort.RuleMode.RATIO, 100))), "M", "MP", f.payment()), f);
    }
    void process(String type) { processAt(type, System.currentTimeMillis()+1000); }
    void processAt(String type, long now) {
        for(int i=0; i<4; i++) {
            var lease=queue.claim(type, now);
            if(lease==null)return;
            try {
                if(lease.message().kind()==PointsEventQueue.Kind.SOURCE_FACT)source.apply(new tools.jackson.databind.ObjectMapper().readValue(lease.message().payload(), PointsFactPort.LifecycleFact.class));
                else if(lease.message().kind()==PointsEventQueue.Kind.EARN_GRANT)source.earn(new tools.jackson.databind.ObjectMapper().readValue(lease.message().payload(), PointsEarnPort.GrantCommand.class));
                else throw new AssertionError(lease.message().kind());
                queue.complete(lease);
            }
            catch(RuntimeException e) {
                queue.fail(lease, System.currentTimeMillis(), false, e.getMessage());
                throw e;
            }
        }
    }
    @Test void versionedOrderDeliveryUsesRealLedgerAndReplaysWithoutDoubleGrant() {
        String type=create(1000), sub="WIRE"+UUID.randomUUID().toString().replace("-", "").substring(0,20);
        var input=earning(type,sub);
        var json=new tools.jackson.databind.ObjectMapper();
        var paid=input.paidFact();
        var paidEvent=new PointsOrderEvent("O"+sub,sub,"M","S","USER",type,"CNY",1,paid.occurredAt(),new SourceFactsPort.Fact(1,paid,null,null,null,null));
        var event=new PointsOrderEvent("O"+sub,sub,"M","S","USER",type,"CNY",1,paid.occurredAt(),new SourceFactsPort.Fact(2,null,input.command(),null,null,null));
        var frozen=new ai.neargo.shop.trade.order.points.PointsOrderPlacementCoordinator.EarningSnapshot(3,1,input.command().lines(),List.of(),"ORDER_EARN_V3");
        var admission=new ai.neargo.shop.trade.order.points.OrderPointsBindingService.Admission("R"+sub,"O"+sub,sub,"USER","M","S",type,"CNY","ONLINE",0,true,false,
            new ai.neargo.shop.trade.order.points.OrderPointsBindingService.Snapshot(10000,0,0,1,1,json.writeValueAsString(frozen),"null",paid.payment(),List.of(new PointsRedeemPort.Item("I",10000))));
        var bindings=org.mockito.Mockito.mock(ai.neargo.shop.trade.order.points.OrderPointsBindingService.class);
        org.mockito.Mockito.when(bindings.find(sub)).thenReturn(new ai.neargo.shop.trade.order.points.OrderPointsBindingService.Binding("B"+sub,admission,ai.neargo.shop.trade.order.points.OrderPointsBindingService.Phase.BOUND,1,null));
        var sourcePort=org.mockito.Mockito.mock(OrderPointsSourcePort.class);
        org.mockito.Mockito.when(sourcePort.find("ORDER:PAID:"+sub)).thenReturn(Optional.of(paidEvent));
        var factory=new org.springframework.beans.factory.support.StaticListableBeanFactory();
        factory.addBean("facts",org.mockito.Mockito.mock(PointsFactPort.class));factory.addBean("source",sourcePort);factory.addBean("ops",ops);
        var delivery=new ai.neargo.shop.trade.order.points.impl.OrderPointsCoordinatorImpl(
            org.mockito.Mockito.mock(ai.neargo.shop.trade.order.points.PointsIntegrationPolicyService.class),bindings,
            org.mockito.Mockito.mock(ai.neargo.shop.trade.order.mapper.OrderPointsBindingMapper.class),factory.getBeanProvider(PointsRedeemPort.class),factory.getBeanProvider(PointsFactPort.class),factory.getBeanProvider(PointsObligationPort.class),factory.getBeanProvider(OrderPointsSourcePort.class));
        delivery.setUnifiedOperations(factory.getBeanProvider(PointsOperationsPort.class));
        delivery.onSourceEvent(event);process(type);delivery.onSourceEvent(event);process(type);
        assertThat(ops.balance(type,Holder.USER,"USER").pendingPoints()).isEqualTo(100);
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM pts_voucher WHERE type_code=? AND biz_ref LIKE 'GRANT:%'",Long.class,type)).isEqualTo(1);
        var voucher=jdbc().queryForObject("SELECT voucher_no FROM pts_voucher WHERE type_code=? AND biz_ref LIKE 'GRANT:%'",String.class,type);
        assertThat(inspection.earningOrder(type,voucher)).isEqualTo(sub);
        var operation=new Operation(Source.TRADE,sub,"ORDER_EARN_V3",type);
        assertThat(ops.status(operation,Action.EARN).domainState()).isEqualTo("PENDING");
        ops.acceptLifecycle(context(type,sub,"FINAL"),fact(type,sub,PointsFactPort.FactKind.FINALIZED));process(type);
        assertThat(ops.status(operation,Action.EARN).domainState()).isEqualTo("AVAILABLE");
        assertThat(ops.balance(type,Holder.USER,"USER").availablePoints()).isEqualTo(100);
    }
    @Test void missingAccountReadDoesNotCreateAndCurrencyMismatchDoesNotWrite() {
        String type=create(0);
        long count=jdbc().queryForObject("SELECT COUNT(*) FROM pts_account WHERE type_code=?", Long.class, type);
        var b=ops.balance(type, Holder.USER, "MISSING");
        assertThat(b.exists()).isFalse();
        assertThat(b.availablePoints()).isZero();
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM pts_account WHERE type_code=?", Long.class, type)).isEqualTo(count);
        var c=context(type, "SAME", "EARN");
        assertThatThrownBy(()->ops.submitEarning(new Context(c.requestId(), c.operation(), "USD"), earning(type, "SAME"))).hasMessage("POINTS_OPERATION_CURRENCY_MISMATCH");
    }
    @Test void twoTypesSameSuborderHaveDifferentVouchersAndReplayOnlyOnce() {
        String a=create(1000), b=create(1000);
        String sub="MULTI"+UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        var ca=context(a, sub, "EARN");
        var cb=context(b, sub, "EARN");
        var input=earning(a, sub);
        var accepted=ops.submitEarning(ca, input);
        assertThat(accepted.status()).isEqualTo(Status.ACCEPTED);
        assertThat(ops.balance(a, Holder.USER, "USER").pendingPoints()).isZero();
        assertThat(ops.submitEarning(new Context("OTHER:"+UUID.randomUUID(), ca.operation(), "CNY"), input)).isEqualTo(accepted);
        ops.submitEarning(cb, earning(b, sub));
        process(a);
        process(b);
        assertThat(ops.balance(a, Holder.USER, "USER").pendingPoints()).isEqualTo(100);
        assertThat(ops.balance(b, Holder.USER, "USER").pendingPoints()).isEqualTo(100);
        assertThat(ops.status(ca.operation(), Action.EARN).status()).isEqualTo(Status.SUCCEEDED);
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM pts_voucher WHERE biz_ref LIKE 'GRANT:V2:%' AND type_code IN (?,?)", Long.class, a, b)).isEqualTo(2);
    }
    @Test void budgetFailureIsRetryableWithoutSkippedVoucher() {
        String type=create(0);
        String sub="EMPTY"+UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        var c=context(type, sub, "EARN");
        ops.submitEarning(c, earning(type, sub));
        assertThatThrownBy(()->process(type)).hasMessage("POINTS_POOL_INSUFFICIENT");
        assertThat(ops.status(c.operation(), Action.EARN).status()).isEqualTo(Status.RETRYABLE);
        assertThat(ops.balance(type, Holder.USER, "USER").pendingPoints()).isZero();
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM pts_voucher WHERE type_code=? AND biz_ref LIKE 'GRANT:%'", Long.class, type)).isZero();
        var evidence=new EvidenceRef("TOPUP:"+type,"CNY",1000);posting.recordEvidence(evidence);
        posting.post(new Voucher("TOPUP:"+type,type,"CNY",new RedeemRate(1,1),List.of(
            new PointsMove(new AccountKey(type,HolderType.MINT,"PLATFORM",Bucket.ISSUED),-1000),
            new PointsMove(new AccountKey(type,HolderType.ISSUE,"PLATFORM",Bucket.ISSUABLE),1000)),
            List.of(new FundMove(FundBucket.U,1000)),List.of(),0,List.of(evidence),"{}"));
        ops.submitEarning(c,earning(type,sub));
        processAt(type,System.currentTimeMillis()+3_600_000);
        assertThat(ops.status(c.operation(),Action.EARN).status()).isEqualTo(Status.SUCCEEDED);
        assertThat(ops.balance(type,Holder.USER,"USER").pendingPoints()).isEqualTo(100);
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM pts_voucher WHERE type_code=? AND biz_ref LIKE 'GRANT:%'",Long.class,type)).isEqualTo(1);
    }
    @Test void changedBusinessIntentConflictsEvenWithNewRequest() {
        String type=create(1000);
        String sub="CONFLICT"+UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        var c=context(type, sub, "EARN");
        ops.submitEarning(c, earning(type, sub));
        var original=earning(type, sub);
        var cmd=original.command();
        var changed=new Earning(new PointsEarnPort.GrantCommand(cmd.eventNo(), sub, cmd.userNo(), type, cmd.storeNo(), cmd.payChannel(), cmd.payScene(), cmd.sourceVersion(), List.of(new PointsEarnPort.EarnLine("I", "G", "C", 1, 10000, 0, new PointsEarnPort.EarnRule(PointsEarnPort.RuleMode.RATIO, 200))), cmd.merchantNo(), cmd.clientType(), cmd.payment()), original.paidFact());
        assertThatThrownBy(()->ops.submitEarning(new Context("NEW:"+UUID.randomUUID(), c.operation(), "CNY"), changed)).hasMessage("POINTS_OPERATION_IDEMPOTENCY_CONFLICT");
    }
    @Test void existingLegacyGrantCannotBeIssuedAgain() {
        String type=create(1000), sub="LEGACY"+UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        var input=earning(type, sub);
        source.apply(input.paidFact());
        source.earn(input.command());
        assertThatThrownBy(()->ops.submitEarning(context(type, sub, "NEW"), input)).hasMessage("POINTS_LEGACY_OPERATION_CONFLICT");
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM pts_voucher WHERE type_code=? AND biz_ref LIKE 'GRANT:%'", Long.class, type)).isEqualTo(1);
    }
    @Test void acceptedGrantCompletesWhenTypeDrainsAndNewRequestsAreRejected() {
        String type=create(1000), sub="DRAIN"+UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        var c=context(type, sub, "EARN");
        var input=earning(type, sub);
        ops.submitEarning(c, input);
        types.drain(type, "maker");
        assertThat(ops.submitEarning(c, input).status()).isEqualTo(Status.ACCEPTED);
        process(type);
        assertThat(ops.balance(type, Holder.USER, "USER").pendingPoints()).isEqualTo(100);
        assertThatThrownBy(()->ops.submitEarning(context(type, "NEW", "EARN"), earning(type, "NEW"))).hasMessage("POINTS_TYPE_NOT_ACTIVE");
    }
    void makeAvailable(String type) {
        var sub="SEED"+type;
        var c=context(type, sub, "EARN");
        ops.submitEarning(c, earning(type, sub));
        process(type);
        ops.acceptLifecycle(context(type, sub, "FINAL"), fact(type, sub, PointsFactPort.FactKind.FINALIZED));
        process(type);
        assertThat(ops.balance(type, Holder.USER, "USER").availablePoints()).isEqualTo(100);
    }
    String setupStore(String type, String existing) {
        String store=existing==null?"ST"+type:existing;
        String merchant="M"+store;
        var db=new JdbcTemplate(shopData);
        if(existing==null) {
            db.update("INSERT INTO mch_entity(entity_no,name,owner_user_no,status,created_at,updated_at) VALUES(?,'test','OWNER','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", merchant);
            db.update("INSERT INTO mch_store(entity_no,store_no,name,status,created_at,updated_at) VALUES(?,?,'test','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", merchant, store);
        }
        var ev=new EvidenceRef("B:"+type, "CNY", 200);
        posting.recordEvidence(ev);
        posting.post(new Voucher("B:"+type, type, "CNY", new RedeemRate(1, 1), List.of(), List.of(new FundMove(FundBucket.B, 200)), List.of(), 0, List.of(ev), "{}"));
        String text="TEST";
        agreements.publishTerms(type, "V1", text, "OPS");
        agreements.sign(new ai.neargo.shop.pay.points.agreement.AgreementService.SignCommand(type, merchant, store, "V1", ai.neargo.shop.pay.points.policy.PointsDigests.sha256(text), "PER_ORDER", new ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsContext(ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsPath.DIRECT, "TEST", merchant, store, "A1", null, null, null, 0)), "OWNER");
        return store;
    }
    PointsRedeemPort.HoldCommand hold(String order, String sub, String store) {
        return new PointsRedeemPort.HoldCommand(order, "CN", "USER", 50, "DIRECT", "MP", List.of(new PointsRedeemPort.Target(sub, "M"+store, store, 1000, "DIRECT", "TEST", "A1", null, null, List.of(new PointsRedeemPort.Item("I", 1000)))));
    }
    @Test void typedHoldsShareBusinessNumbersButNotBalancesAndReleaseWhileDraining() {
        String a=create(1000), b=create(1000);
        makeAvailable(a);
        makeAvailable(b);
        String store=setupStore(a, null);
        setupStore(b, store);
        String order="ORDER"+UUID.randomUUID().toString().replace("-", "").substring(0, 20), sub="SUB"+order;
        var ca=context(a, order, "HOLD");
        var cb=context(b, order, "HOLD");
        var command=hold(order, sub, store);
        assertThat(ops.quote(b, "CNY", new PointsRedeemPort.RedeemQuery("CN", "USER", 50, "DIRECT", "MP", command.targets())).typeCode()).isEqualTo(b);
        var ha=ops.hold(ca, command);
        var hb=ops.hold(cb, command);
        assertThat(ha.points()).isEqualTo(50);
        assertThat(hb.points()).isEqualTo(50);
        assertThat(ha.holdNo()).isNotEqualTo(hb.holdNo());
        assertThat(ops.hold(new Context("RETRY:"+UUID.randomUUID(), ca.operation(), "CNY"), command)).isEqualTo(ha);
        assertThat(ops.balance(a, Holder.USER, "USER").heldPoints()).isEqualTo(50);
        types.drain(a, "maker");
        var release=new Context("REL:"+UUID.randomUUID(), ca.operation(), "CNY");
        assertThat(ops.release(release, new PointsRedeemPort.ReleaseCommand(order, "CANCEL", 1)).state()).isEqualTo("RELEASED");
        assertThat(ops.balance(a, Holder.USER, "USER").availablePoints()).isEqualTo(100);
        assertThat(ops.balance(b, Holder.USER, "USER").heldPoints()).isEqualTo(50);
    }
    @Test void freezeHasVersionAuditAndDoesNotMoveExistingHeldBalance() {
        String type=create(1000);
        makeAvailable(type);
        String store=setupStore(type, null), order="FREEZE"+UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        var holdContext=context(type, order, "HOLD");
        ops.hold(holdContext, hold(order, "SUB"+order, store));
        var balance=ops.balance(type, Holder.USER, "USER");
        var c=context(type, "USER", "FREEZE");
        var control=new AccountControl("USER", balance.version(), true, "OPS", "TEST");
        ops.controlAccount(c, control);
        ops.controlAccount(c, control);
        var frozen=ops.balance(type, Holder.USER, "USER");
        assertThat(frozen.frozen()).isTrue();
        assertThat(frozen.heldPoints()).isEqualTo(balance.heldPoints());
        assertThat(frozen.availablePoints()).isEqualTo(balance.availablePoints());
        ops.controlAccount(context(type, "USER", "UNFREEZE"), new AccountControl("USER", frozen.version(), false, "OPS", "TEST"));
        assertThat(ops.balance(type, Holder.USER, "USER").frozen()).isFalse();
    }
    @Test void paidHoldStatusMustReportConsumptionAndCannotBeReleased() {
        String type=create(1000);
        makeAvailable(type);
        String store=setupStore(type, null), order="PAID"+UUID.randomUUID().toString().replace("-", "").substring(0, 20), sub="SUB"+order;
        var c=context(type, order, "HOLD");
        ops.hold(c, hold(order, sub, store));
        var f=new PointsFactPort.LifecycleFact("HPAID:"+type, null, sub, "USER", type, 1, PointsFactPort.FactKind.PAID, 1000, 0, List.of(new PointsFactPort.LineSnapshot("I", 1, 1000, 0)), Instant.now());
        var paid=new PointsFactPort.LifecycleFact(f.eventNo(), PointsLifecycleFacts.digest(f, new tools.jackson.databind.ObjectMapper()::writeValueAsString), sub, "USER", type, 1, f.kind(), 1000, 0, f.lines(), f.occurredAt());
        source.apply(paid);
        assertThat(ops.status(c.operation(), Action.HOLD).domainState()).isEqualTo("CONSUMED");
        assertThatThrownBy(()->ops.release(new Context("REL:"+UUID.randomUUID(), c.operation(), "CNY"), new PointsRedeemPort.ReleaseCommand(order, "CANCEL", 1))).hasMessage("POINTS_HOLD_ALREADY_PAID");
    }
    @Test void refundPreparationAndConfirmationKeepTheOriginalPayEvidenceAndReplay() {
        String type=create(1000), sub="REF"+UUID.randomUUID().toString().substring(0, 16);
        var grant=context(type, sub, "EARN");
        var input=earning(type, sub);
        ops.submitEarning(grant, input);
        process(type);
        String afterSale="AS"+sub;
        var c=context(type, afterSale, "REFUND");
        var command=new PointsRefundPort.RefundCommand(afterSale, sub, "USER", type, "CNY", 1, 10000, 10000, List.of(new PointsRefundPort.RefundLine("I", 10000)), input.command().payment());
        var prepared=ops.prepareRefund(c, command);
        assertThat(ops.prepareRefund(c, command)).isEqualTo(prepared);
        var confirmation=new PointsRefundPort.RefundConfirmation(prepared.preparationNo(), prepared.inputHash(), "CASH:"+sub, null, null);
        var confirmContext=new Context("CONF:"+UUID.randomUUID(), c.operation(), "CNY");
        assertThatThrownBy(()->ops.confirmRefund(confirmContext, confirmation)).hasMessageContaining("POINTS_");
        ops.authorizeRefund(new Context("AUTH:"+UUID.randomUUID(), c.operation(), "CNY"), new RefundAuthorization(prepared.preparationNo(), prepared.inputHash(), prepared.revision()));
        assertThatThrownBy(()->ops.confirmRefund(confirmContext, confirmation)).hasMessage("POINTS_CASH_REFUND_NOT_CONFIRMED");
        jdbc().update("INSERT INTO stl_payment(payment_no,direction,after_sale_no,sub_order_no,order_no,user_no,currency,amount_minor,status,pay_channel,trade_no,succeeded_at,refund_verified_minor,refund_verified_at,refund_verified_provider_no,created_at,updated_at) VALUES(?,'REFUND',?,?,?,'USER','CNY',?,'SUCCESS','TEST',?,1,?,1,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", "CASH:"+sub, afterSale, sub, sub, prepared.netCashMinor(), "RECEIPT:"+sub, prepared.netCashMinor(), "VERIFIED:"+sub);
        var result=ops.confirmRefund(confirmContext, confirmation);
        assertThat(result.state()).isEqualTo("CONFIRMED");
        assertThat(result.reclaimedPoints()).isEqualTo(100);
        assertThat(ops.confirmRefund(confirmContext, confirmation)).isEqualTo(result);
        assertThat(ops.refundStatus(c, prepared.preparationNo(), prepared.inputHash()).state()).isEqualTo("CONFIRMED");
        assertThat(ops.balance(type, Holder.USER, "USER").heldPoints()).isZero();
        assertThat(ops.balance(type, Holder.USER, "USER").pendingPoints()).isZero();
    }
    @Test void aRequestIdCannotBeReusedForAnotherOperationAndBadProofNeverQueues() {
        String type=create(1000), sub="ID"+UUID.randomUUID().toString().substring(0, 16);
        var c=context(type, sub, "EARN");
        ops.submitEarning(c, earning(type, sub));
        var other=new Context(c.requestId(), new Operation(Source.TRADE, "OTHER", "EARN", type), "CNY");
        assertThatThrownBy(()->ops.submitEarning(other, earning(type, "OTHER"))).hasMessage("POINTS_OPERATION_IDEMPOTENCY_CONFLICT");
        var bad=earning(type, "BAD");
        var missing=new Earning(bad.command(), null);
        long count=jdbc().queryForObject("SELECT COUNT(*) FROM pts_outbox WHERE type_code=?", Long.class, type);
        assertThatThrownBy(()->ops.submitEarning(context(type, "BAD", "EARN"), missing)).hasMessage("POINTS_EARN_INPUT_INVALID");
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM pts_outbox WHERE type_code=?", Long.class, type)).isEqualTo(count);
    }
    @Autowired ai.neargo.shop.pay.points.pool.PointsSwitchService switches;
    @Test void disabledEarningSwitchRejectsNewPromisesButPreservesAcceptedReplay() {
        String type=create(1000), sub="SW"+UUID.randomUUID().toString().substring(0, 16);
        var c=context(type, sub, "EARN");
        var input=earning(type, sub);
        ops.submitEarning(c, input);
        switches.change(new ai.neargo.shop.pay.points.pool.PointsSwitchService.Command("OFF:"+type, ai.neargo.shop.pay.points.pool.PointsSwitchService.Scope.TYPE, type, type, false, System.currentTimeMillis(), "OPS", "TEST", ai.neargo.shop.pay.points.pool.PointsSwitchService.Flow.EARN));
        assertThatThrownBy(()->ops.submitEarning(context(type, "NEW", "EARN"), earning(type, "NEW"))).hasMessage("POINTS_DISABLED");
        assertThat(ops.submitEarning(c, input).status()).isEqualTo(Status.ACCEPTED);
        process(type);
        assertThat(ops.balance(type, Holder.USER, "USER").pendingPoints()).isEqualTo(100);
    }
    @Test void cancelledLifecycleReleasesChildrenAndStatusReflectsIt() {
        String type=create(1000);
        makeAvailable(type);
        String store=setupStore(type, null), order="CAN"+UUID.randomUUID().toString().substring(0, 16), sub="SUB"+order;
        var c=context(type, order, "HOLD");
        ops.hold(c, hold(order, sub, store));
        var cancelled=fact(type, sub, PointsFactPort.FactKind.CANCELLED);
        ops.acceptLifecycle(context(type, sub, "CANCEL"), cancelled);
        process(type);
        assertThat(ops.balance(type, Holder.USER, "USER").heldPoints()).isZero();
        assertThat(ops.status(c.operation(), Action.HOLD).domainState()).isEqualTo("RELEASED");
    }
    @Test void mixedCashEarningUsesTrustedDeductionAndPartialRefundsReturnOriginalPoints() {
        String type=create(1000);makeAvailable(type);
        String store=setupStore(type,null),order="MIX"+UUID.randomUUID().toString().substring(0,16),sub="SUB"+order;
        var hc=context(type,order,"HOLD");var h=hold(order,sub,store);ops.hold(hc,h);
        var payment=new PointsRefundPort.PaymentSnapshot("DIRECT","TEST","M"+store,store,"A1",null,"TRADE:"+sub,"PAY:"+sub,950);
        var raw=new PointsFactPort.LifecycleFact("PAID:"+sub,null,sub,"USER",type,1,PointsFactPort.FactKind.PAID,1000,0,List.of(new PointsFactPort.LineSnapshot("I",1,1000,0)),Instant.now(),payment);
        var paid=new PointsFactPort.LifecycleFact(raw.eventNo(),PointsLifecycleFacts.digest(raw,new tools.jackson.databind.ObjectMapper()::writeValueAsString),sub,"USER",type,1,raw.kind(),1000,0,raw.lines(),raw.occurredAt(),payment);
        ops.acceptLifecycle(context(type,sub,"PAID"),paid);process(type);
        assertThat(ops.status(hc.operation(),Action.HOLD).domainState()).isEqualTo("CONSUMED");
        var command=new PointsEarnPort.GrantCommand("E:"+sub,sub,"USER",type,store,"TEST","ONLINE",1,List.of(new PointsEarnPort.EarnLine("I","G","C",1,950,0,new PointsEarnPort.EarnRule(PointsEarnPort.RuleMode.RATIO,1000))),"M"+store,"MP",payment);
        var ec=context(type,sub,"EARN");ops.submitEarning(ec,new Earning(command,paid));process(type);
        assertThat(ops.balance(type,Holder.USER,"USER").pendingPoints()).isEqualTo(95);
        var bad=new PointsEarnPort.GrantCommand("BAD:"+sub,sub,"USER",type,store,"TEST","ONLINE",1,List.of(new PointsEarnPort.EarnLine("I","G","C",1,1000,0,new PointsEarnPort.EarnRule(PointsEarnPort.RuleMode.RATIO,1000))),"M"+store,"MP",payment);
        assertThatThrownBy(()->ops.submitEarning(context(type,sub,"BAD"),new Earning(bad,paid))).hasMessage("POINTS_EARN_SNAPSHOT_MISMATCH");
        for(int step=1;step<=2;step++) {
        String afterSale="AS"+step+sub;var rc=context(type,afterSale,"REFUND");
        var refund=new PointsRefundPort.RefundCommand(afterSale,sub,"USER",type,"CNY",step,475,step*500,List.of(new PointsRefundPort.RefundLine("I",step*500)),payment);
        var prepared=ops.prepareRefund(rc,refund);
        assertThat(prepared.netCashMinor()).isEqualTo(475);
        ops.authorizeRefund(new Context("AUTH:"+afterSale,rc.operation(),"CNY"),new RefundAuthorization(prepared.preparationNo(),prepared.inputHash(),prepared.revision()));
        var confirmation=new PointsRefundPort.RefundConfirmation(prepared.preparationNo(),prepared.inputHash(),"CASH:"+afterSale,null,null);
        var cc=new Context("CONF:"+afterSale,rc.operation(),"CNY");
        assertThatThrownBy(()->ops.confirmRefund(cc,confirmation)).hasMessage("POINTS_CASH_REFUND_NOT_CONFIRMED");
        jdbc().update("INSERT INTO stl_payment(payment_no,direction,after_sale_no,sub_order_no,order_no,user_no,currency,amount_minor,status,pay_channel,trade_no,succeeded_at,refund_verified_minor,refund_verified_at,refund_verified_provider_no,created_at,updated_at) VALUES(?,'REFUND',?,?,?,'USER','CNY',?,'SUCCESS','TEST',?,1,?,1,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)","CASH:"+afterSale,afterSale,sub,order,prepared.netCashMinor(),"RECEIPT:"+afterSale,prepared.netCashMinor(),"VERIFIED:"+afterSale);
        var outcome=ops.confirmRefund(cc,confirmation);
        assertThat(outcome.state()).isEqualTo("CONFIRMED");
        assertThat(outcome.returnedPoints()).isEqualTo(25);assertThat(outcome.reclaimedPoints()).isEqualTo(step==1?47:48);
        assertThat(ops.confirmRefund(cc,confirmation)).isEqualTo(outcome);
        }
        assertThat(ops.balance(type,Holder.USER,"USER").availablePoints()).isEqualTo(100);
        assertThat(ops.balance(type,Holder.USER,"USER").pendingPoints()).isZero();
    }

    @Autowired ai.neargo.shop.pay.points.redeem.RedeemService redeemService;
    @Test void defaultParticipationAllowsUnsignedStoreWithoutCreatingAgreement() {
        String type=create(1000);makeAvailable(type);String store=setupStore(type,null);
        jdbc().update("DELETE FROM pts_merchant_agreement WHERE type_code=?",type);
        {
            String order="UNSIGNED"+UUID.randomUUID().toString().substring(0,12);
            assertThat(ops.hold(context(type,order,"HOLD"),hold(order,"SUB"+order,store)).points()).isEqualTo(50);
            assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM pts_merchant_agreement WHERE type_code=?",Long.class,type)).isZero();
        }
    }

}
