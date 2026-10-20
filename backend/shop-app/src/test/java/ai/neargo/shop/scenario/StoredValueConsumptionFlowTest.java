package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.member.dto.MemberCodeScope;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.paybridge.EntitlementMarketingEffectSubscriber;
import ai.neargo.shop.paybridge.ActivityBenefitCoordinator;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.IssuePolicyViews.SavePolicy;
import ai.neargo.shop.pay.entitlement.service.EntitlementCatalogService;
import ai.neargo.shop.pay.entitlement.service.EntitlementIssuePolicyService;
import ai.neargo.shop.promotion.dto.ActivityActor;
import ai.neargo.shop.promotion.dto.BenefitClaimCommand;
import ai.neargo.shop.promotion.dto.UnifiedActivityDefinition;
import ai.neargo.shop.promotion.dto.UnifiedActivityViews.SaveDraft;
import ai.neargo.shop.promotion.service.ActivityVersionService;
import ai.neargo.shop.promotion.service.ActivityMarketingSummaryService;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementMarketingFactsPort;
import java.time.Instant;
import ai.neargo.shop.member.service.MemberCodeResolveService;
import ai.neargo.shop.pay.storedvalue.service.StoredValueCreditService;
import ai.neargo.shop.pay.storedvalue.service.StoredValueQueryService;
import ai.neargo.shop.paybridge.EntitlementServiceUseCoordinator;
import ai.neargo.shop.spi.storedvalue.StoredValuePort;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementUseFactsPort;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort;
import ai.neargo.shop.spi.storedvalue.StoredValueUseQuotePort;
import ai.neargo.shop.support.StoredValueCreditFixture;
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
import tools.jackson.databind.ObjectMapper;

/** Recharge receipt/marketing is proved separately. This exercises real priced services, C09 and pay money usage. */
@SpringBootTest(properties = {"shop.pay.entitlement.catalog.enabled=true","shop.pay.entitlement.issue.enabled=true",
        "shop.promotion.activity.enabled=true","shop.promotion.presets.enabled=true","shop.member-code.enabled=true", "shop.core.entitlement.authorization.enabled=true",
        "shop.pay.entitlement.cursor-key=stored-value-test-cursor-key-at-least-thirty-two-characters",
        "shop.pay.stored-value.enabled=true", "shop.pay.entitlement.use.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:sv-consume;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class StoredValueConsumptionFlowTest {
    @Autowired EntitlementServiceOrderService services;
    @Autowired EntitlementCatalogService catalog;
    @Autowired EntitlementIssuePolicyService policies;
    @Autowired ActivityVersionService activities;
    @Autowired ActivityBenefitCoordinator claims;
    @Autowired EntitlementMarketingEffectSubscriber marketing;
    @Autowired EntitlementMarketingFactsPort marketingFacts;
    @Autowired ActivityMarketingSummaryService summary;
    @Autowired EntitlementAuthorizationService consent;
    @Autowired StoredValueUseQuotePort quotes;
    @Autowired EntitlementUsePort uses;
    @Autowired EntitlementUseFactsPort facts;
    @Autowired StoredValueCreditService credits;
    @Autowired ai.neargo.shop.pay.entitlement.service.EntitlementReconciliationService reconciliation;
    @Autowired StoredValueQueryService queries;
    @Autowired EntitlementServiceUseCoordinator coordinator;
    @Autowired MemberCodeResolveService members;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    private String user, actor, entity, store, sku, reference;
    private EntitlementContext ctx;
    private String id() { return UUID.randomUUID().toString().replace("-", ""); }
    @BeforeEach void sourceAndOwedCredit() {
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
        credits.credit(ctx, StoredValueCreditFixture.received(jdbc, json, user, entity, store, 1000, 100));
    }
    @Test void moneyReconciliationFindsIndependentAccountLotAndJournalDamageWithoutAdjustments() {
        var ops=new ai.neargo.shop.pay.entitlement.dto.CatalogScope("MAIN",entity,actor,true);
        var check=new ai.neargo.shop.pay.entitlement.dto.ReconciliationViews.Check("MONEY","CNY",
                java.time.LocalDate.of(2026,10,19),"Asia/Shanghai","",20);
        var clean=reconciliation.check(ops,check,id());
        assertThat(clean.checkedCount()).isEqualTo(1);
        assertThat(clean.diffCount()).isZero();
        jdbc.update("UPDATE sv_lot SET bonus_minor=99 WHERE account_no IN "
                + "(SELECT account_no FROM sv_account WHERE entity_no=?)",entity);
        var badLot=reconciliation.check(ops,check,id());
        assertThat(badLot.diffCount()).isEqualTo(1);
        jdbc.update("UPDATE sv_lot SET bonus_minor=100 WHERE account_no IN "
                + "(SELECT account_no FROM sv_account WHERE entity_no=?)",entity);
        jdbc.update("UPDATE sv_posting_line SET amount_minor=amount_minor+1 WHERE side='DEBIT' AND voucher_no IN "
                + "(SELECT voucher_no FROM sv_voucher WHERE account_no IN "
                + "(SELECT account_no FROM sv_account WHERE entity_no=?))",entity);
        String key=id();
        var damaged=reconciliation.check(ops,check,key);
        assertThat(damaged.diffCount()).isEqualTo(1);
        assertThat(reconciliation.check(ops,check,key)).isEqualTo(damaged);
        assertThat(reconciliation.differences(ops,damaged.reconNo(),1,20).records()).hasSize(1);
        assertThat(reconciliation.list(ops,check.bizDate(),1,20).total()).isEqualTo(3);
        assertThat(balance()).isEqualTo(1100);
        var otherCurrency=new ai.neargo.shop.pay.entitlement.dto.ReconciliationViews.Check("MONEY","USD",
                check.bizDate(),check.marketZone(),"",20);
        assertThat(reconciliation.check(ops,otherCurrency,id()).checkedCount()).isZero();
        assertThatThrownBy(() -> reconciliation.check(ops,otherCurrency,key)).isInstanceOf(ai.neargo.shop.common.BizException.class);
        assertThatThrownBy(() -> reconciliation.differences(
                new ai.neargo.shop.pay.entitlement.dto.CatalogScope("MAIN",id(),actor,true),damaged.reconNo(),1,20))
                .isInstanceOf(ai.neargo.shop.common.BizException.class);
    }
    @Test void offsettingLotOrVoucherErrorsStillProduceDifferenceWhenAccountTotalsMatch() {
        credits.credit(new EntitlementContext("MAIN",actor,id(),"b".repeat(64)),
                StoredValueCreditFixture.received(jdbc,json,user,entity,store,1000,100));
        var ops=new ai.neargo.shop.pay.entitlement.dto.CatalogScope("MAIN",entity,actor,true);
        var check=new ai.neargo.shop.pay.entitlement.dto.ReconciliationViews.Check("MONEY","CNY",
                java.time.LocalDate.of(2026,10,19),"UTC","",20);
        assertThat(reconciliation.check(ops,check,id()).diffCount()).isZero();
        var lots=jdbc.queryForList("SELECT l.lot_no FROM sv_lot l JOIN sv_account a ON a.account_no=l.account_no "
                + "AND a.tenant_no=l.tenant_no WHERE a.entity_no=? ORDER BY l.lot_no",String.class,entity);
        jdbc.update("UPDATE sv_lot SET bonus_minor=bonus_minor+1 WHERE lot_no=?",lots.get(0));
        jdbc.update("UPDATE sv_lot SET bonus_minor=bonus_minor-1 WHERE lot_no=?",lots.get(1));
        assertThat(reconciliation.check(ops,check,id()).diffCount()).isEqualTo(1);
        jdbc.update("UPDATE sv_lot SET bonus_minor=bonus_minor-1 WHERE lot_no=?",lots.get(0));
        jdbc.update("UPDATE sv_lot SET bonus_minor=bonus_minor+1 WHERE lot_no=?",lots.get(1));
        var vouchers=jdbc.queryForList("SELECT v.voucher_no FROM sv_voucher v JOIN sv_account a "
                + "ON a.account_no=v.account_no AND a.tenant_no=v.tenant_no WHERE a.entity_no=? "
                + "ORDER BY v.voucher_no",String.class,entity);
        jdbc.update("UPDATE sv_posting_line SET amount_minor=amount_minor+1 "
                + "WHERE voucher_no=? AND pool='BONUS'",vouchers.get(0));
        jdbc.update("UPDATE sv_posting_line SET amount_minor=amount_minor-1 "
                + "WHERE voucher_no=? AND pool='BONUS'",vouchers.get(1));
        assertThat(reconciliation.check(ops,check,id()).diffCount()).isEqualTo(1);
        assertThat(balance()).isEqualTo(2200);
    }
    @Test void realActivityServiceVoucherIssueAndUseReportUnitsWithoutDebitingStoredValue() {
        jdbc.update("UPDATE mch_entity SET industry='LIFE_SERVICE' WHERE entity_no=?",entity);
        jdbc.update("UPDATE pmt_preset_template SET enabled=1 WHERE tenant_no='MAIN' AND template_code='AP004'");
        var scope=new CatalogScope("MAIN",entity,actor,false);
        var storeScope=new EntitlementDefinition.RedemptionScope("STORES",List.of(store));
        var definition=new EntitlementDefinition("Care voucher","STORE_EXCHANGE","MERCHANT","CNY","SERVICE_UNITS",
                null,null,null,null,null,"COUNT",3,null,
                new EntitlementDefinition.Validity("RELATIVE",null,null,30,"GRANTED","ROLLING_DAYS","UTC"),
                storeScope,List.of("ACTIVITY"));
        var template=catalog.create(scope,definition,id());
        var version=catalog.publish(scope,template.templateNo(),template.revision(),id());
        policies.save(scope,template.templateNo(),new SavePolicy(0,10L,0L,2,true),id());
        Instant now=Instant.now();
        var activityDefinition=new UnifiedActivityDefinition("Care receive","ENTITLEMENT_GRANT","AP004",1,
                storeScope,null,new UnifiedActivityDefinition.Schedule("ONE_OFF",now.minusSeconds(60),
                        now.plusSeconds(3600),"UTC",List.of(),null,null),
                new UnifiedActivityDefinition.Rule(1,new UnifiedActivityDefinition.Trigger("NONE",null,null),
                        List.of(new UnifiedActivityDefinition.Benefit("ENTITLEMENT",null,version.versionNo(),null,1)),
                        List.of()),10L,1000L,1,"CNY");
        var owner=new ActivityActor("MAIN",entity,actor,null);
        var draft=activities.saveDraft(owner,new SaveDraft(null,null,activityDefinition),id());
        var published=activities.publish(owner,draft.activityNo(),draft.revision(),id());
        var customer=new EntitlementContext("MAIN",user,id(),"b".repeat(64));
        var claimed=claims.claim(customer,new BenefitClaimCommand(version.versionNo(),draft.activityNo(),
                published.versionNo(),entity,store),id());
        assertThat(claimed.status()).isEqualTo("COMMITTED");
        var grant=claimed.grants().getFirst(); var instance=grant.instanceNos().getFirst();
        var order=services.create(ctx,entity,new ServiceOrderCreate(reference,store,
                List.of(new ServiceOrderCreate.Line(sku,1))),id());
        var preview=coordinator.preview(ctx,entity,store,order.serviceOrderNo(),instance);
        var authorization=consent.confirm("MAIN",user,order.serviceOrderNo(),new AuthorizationCommand(0,
                preview.previewRef(),preview.snapshotHash(),id()));
        var used=coordinator.submit(ctx,entity,store,new ServiceUseSubmit(order.serviceOrderNo(),preview.previewRef(),
                authorization.authorizationRef(),preview.assetRevision()));
        assertThat(used.status()).isEqualTo(EntitlementUsePort.State.COMMITTED);
        var facts=marketingFacts.usage(ctx,entity,used.usageNo(),"",100);
        assertThat(facts.records()).hasSize(1);
        assertThat(facts.records().getFirst().activityNo()).isEqualTo(draft.activityNo());
        assertThat(facts.records().getFirst().units()).isEqualTo(1L);
        var events=jdbc.query("SELECT event_no,event_type,aggregate_no,payload FROM pay_ent_outbox "
                + "WHERE aggregate_no IN (?,?)",(rs,n) -> {
                    var event=new SysOutbox(); event.setEventNo(rs.getString("event_no"));
                    event.setEventType(rs.getString("event_type")); event.setAggregateId(rs.getString("aggregate_no"));
                    event.setAggregateType("ENTITLEMENT_GRANTED".equals(event.getEventType())
                            ?"ENTITLEMENT_GRANT":"ENTITLEMENT_USAGE");
                    event.setPayload(rs.getString("payload")); return event;
                },grant.grantNo(),used.usageNo());
        for(var event:events) { marketing.consume(event); marketing.consume(event); }
        var totals=summary.summarize(owner,store,now.minusSeconds(10),Instant.now().plusSeconds(10));
        assertThat(totals.metrics()).hasSize(2);
        assertThat(totals.metrics()).allSatisfy(metric -> {
            assertThat(metric.amountMinor()).isNull(); assertThat(metric.currency()).isNull();
            assertThat(metric.units()).isEqualTo(1L); assertThat(metric.eventCount()).isEqualTo(1);
        });
        assertThat(totals.metrics()).extracting(ActivityMarketingSummaryService.Metric::effectKind)
                .containsExactlyInAnyOrder("COUPON_ISSUED","RIGHTS_USED");
        assertThat(balance()).isEqualTo(1100);
    }

    private void assertMoneyReconciles() {
        var scope=new ai.neargo.shop.pay.entitlement.dto.CatalogScope("MAIN",entity,actor,true);
        var check=new ai.neargo.shop.pay.entitlement.dto.ReconciliationViews.Check("MONEY","CNY",
                java.time.LocalDate.of(2026,10,19),"UTC","",20);
        assertThat(reconciliation.check(scope,check,id()).diffCount()).isZero();
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
    @Test void fullServiceAmountIsHeldThenDebitedOnceWithOneUsageAndBalancedJournal() {
        var order = authorized("consume");
        var held = uses.prepare(order.context(), order.command());
        assertThat(held.status()).isEqualTo(EntitlementUsePort.State.HELD);
        assertThat(balance()).isEqualTo(1100);
        assertThat(held()).isEqualTo(1000);
        assertMoneyReconciles();
        var actual = facts.query(ctx, entity, order.operationNo());
        assertThat(actual.command().assetKind()).isEqualTo(EntitlementUsePort.AssetKind.STORED_VALUE);
        assertThat(actual.command().amountMinor()).isEqualTo(1000); assertThat(actual.rule()).isNull();
        var decided = services.accept(ctx, entity, actual);
        var command = new EntitlementUsePort.UseDecision(order.operationNo(), order.fact().source(), decided.decision());
        var used = uses.commit(ctx, command);
        assertThat(used.status()).isEqualTo(EntitlementUsePort.State.COMMITTED);
        assertThat(uses.commit(ctx, command)).isEqualTo(used);
        assertThat(balance()).isEqualTo(100); assertThat(held()).isZero();
        assertMoneyReconciles();
        assertThat(jdbc.queryForObject("SELECT account_seq FROM sv_account WHERE user_no=?", Long.class, user))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_usage WHERE user_no=?", Integer.class, user)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_voucher WHERE biz_no=?", Integer.class,
                order.operationNo())).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_order WHERE order_no=?", Integer.class,
                order.serviceOrderNo())).isZero();
        assertThat(jdbc.queryForObject("SELECT SUM(CASE WHEN p.side='DEBIT' THEN p.amount_minor ELSE -p.amount_minor END) "
                + "FROM sv_posting_line p JOIN sv_voucher v ON v.tenant_no=p.tenant_no AND v.voucher_no=p.voucher_no "
                + "WHERE v.account_no=(SELECT account_no FROM sv_account WHERE user_no=?)",
                Long.class, user)).isZero();
    }
    @Test void actualCancellationReturnsOriginalLotHoldsWithoutRefundOrDebitAndFencesLatePrepare() {
        var order = authorized("cancel");
        uses.prepare(order.context(), order.command());
        var cancelled = services.cancel(ctx, entity, store, order.serviceOrderNo(), "before consumption");
        var result = uses.release(ctx, new EntitlementUsePort.CancelUse(order.operationNo(), order.fact().source(),
                cancelled.decision()));
        assertThat(result.status()).isEqualTo(EntitlementUsePort.State.RELEASED);
        assertThat(uses.prepare(order.context(), order.command()).status()).isEqualTo(EntitlementUsePort.State.RELEASED);
        assertThat(balance()).isEqualTo(1100); assertThat(held()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_usage WHERE user_no=?", Integer.class, user)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_voucher WHERE biz_no=?", Integer.class,
                order.operationNo())).isZero();
    }
    @Test void failedUsedEventRollsBackTheWholeDebitAndTheOriginalCommittedSourceCanRetry() {
        var order = authorized("outbox"); uses.prepare(order.context(), order.command());
        var decided = services.accept(ctx, entity, facts.query(ctx, entity, order.operationNo()));
        var command = new EntitlementUsePort.UseDecision(order.operationNo(), order.fact().source(), decided.decision());
        jdbc.execute("ALTER TABLE pay_ent_outbox ADD CONSTRAINT reject_sv_use CHECK (event_type<>'ENTITLEMENT_USED' "
                + "OR CAST(payload AS VARCHAR) NOT LIKE '%" + user + "%')");
        try { assertThatThrownBy(() -> uses.commit(ctx, command)).isInstanceOf(RuntimeException.class); }
        finally { jdbc.execute("ALTER TABLE pay_ent_outbox DROP CONSTRAINT reject_sv_use"); }
        assertThat(balance()).isEqualTo(1100); assertThat(held()).isEqualTo(1000);
        assertThat(uses.query(ctx, entity, order.operationNo()).status()).isEqualTo(EntitlementUsePort.State.HELD);
        assertThat(uses.commit(ctx, command).status()).isEqualTo(EntitlementUsePort.State.COMMITTED);
        assertThat(balance()).isEqualTo(100);
    }
    @Test void coordinatorCommitsAndQueriesExposeOnlyTheOwnersCommittedUsage() {
        var order = authorized("coordinated");
        assertThat(queries.consumptions(ctx, new StoredValuePort.ConsumptionQuery(user, entity, store, null, 20))
                .records()).isEmpty();
        var result = coordinator.submit(ctx, entity, store, new ServiceUseSubmit(order.serviceOrderNo(),
                order.preview().ref(), order.command().authorizationRef(), order.preview().assetRevision()));
        assertThat(result.status()).isEqualTo(EntitlementUsePort.State.COMMITTED);
        var history = queries.consumptions(ctx, new StoredValuePort.ConsumptionQuery(user, entity, store, null, 20));
        assertThat(history.records()).hasSize(1);
        assertThat(history.records().getFirst().usageNo()).isEqualTo(result.usageNo());
        assertThat(history.records().getFirst().amountMinor()).isEqualTo(1000);
        assertThat(queries.consumptions(ctx, new StoredValuePort.ConsumptionQuery(actor, entity, store, null, 20))
                .records()).isEmpty();
        assertThat(queries.stores(ctx, new StoredValuePort.StoreQuery(user, null, 20)).records())
                .anySatisfy(s -> { assertThat(s.storeNo()).isEqualTo(store);
                    assertThat(s.lastActivityType()).isEqualTo(StoredValuePort.StoreActivityType.CONSUMPTION); });
        var balance = queries.balance(ctx, new StoredValuePort.BalanceQuery(user, entity, "CNY", store));
        assertThat(balance.balanceMinor()).isEqualTo(100);
        assertThat(balance.heldMinor()).isZero();
        assertThat(balance.availableMinor()).isEqualTo(100);
    }
    @Test void directoryKeepsStableTiesAndCursorsCannotChangeMemberOrQueryKind() {
        String holder = id();
        var at = java.time.LocalDateTime.of(2026, 10, 1, 10, 0);
        for (String no : List.of("S1", "S2", "S3", "HIDDEN")) {
            jdbc.update("INSERT INTO sv_user_store_index(tenant_no,user_no,entity_no,store_no,store_name,"
                    + "has_recharge,last_recharge_at,last_activity_at,last_activity_type,store_status,created_at,"
                    + "updated_at,created_by,updated_by) VALUES ('MAIN',?,?,?,'History',?,?,?,'RECHARGE',"
                    + "'UNVERIFIED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'TEST','TEST')", holder, entity, no,
                    "HIDDEN".equals(no) ? 0 : 1, at, "S3".equals(no) ? at.minusDays(1) : at);
        }
        var first = queries.stores(ctx, new StoredValuePort.StoreQuery(holder, null, 1));
        assertThat(first.records()).extracting(StoredValuePort.StoreItem::storeNo).containsExactly("S1");
        var second = queries.stores(ctx, new StoredValuePort.StoreQuery(holder, first.nextCursor(), 1));
        assertThat(second.records()).extracting(StoredValuePort.StoreItem::storeNo).containsExactly("S2");
        assertThat(queries.stores(ctx, new StoredValuePort.StoreQuery(holder, first.nextCursor(), 1)))
                .isEqualTo(second);
        var last = queries.stores(ctx, new StoredValuePort.StoreQuery(holder, second.nextCursor(), 1));
        assertThat(last.records()).extracting(StoredValuePort.StoreItem::storeNo).containsExactly("S3");
        assertThat(last.nextCursor()).isNull();
        assertThatThrownBy(() -> queries.stores(ctx, new StoredValuePort.StoreQuery(user, first.nextCursor(), 1)))
                .isInstanceOf(ai.neargo.shop.common.BizException.class);
        assertThatThrownBy(() -> queries.consumptions(ctx, new StoredValuePort.ConsumptionQuery(
                holder, entity, "S1", first.nextCursor(), 1)))
                .isInstanceOf(ai.neargo.shop.common.BizException.class);
    }
    @Test void merchantHistoryWithoutStorePagesAcrossStoresAndBindsCursorToOwnerAndFilter() {
        String prefix = id();
        var at = java.time.LocalDateTime.of(2026, 10, 1, 10, 0);
        for (int i = 1; i <= 6; i++) {
            jdbc.update("INSERT INTO ent_usage(tenant_no,usage_no,operation_no,user_no,entity_no,store_no,"
                    + "store_name,asset_kind,title,amount_minor,currency,items_snapshot,operator_no,status,"
                    + "occurred_at,created_at,updated_at,created_by,updated_by) "
                    + "VALUES('MAIN',?,?,?,?,?, 'Store','STORED_VALUE','Care',100,'CNY','[]','TEST',?,?,"
                    + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'TEST','TEST')", prefix + i, id(),
                    i == 4 ? actor : user, i == 5 ? id() : entity, i == 2 ? "SECOND" : store,
                    i == 6 ? "REVERSED" : "COMMITTED", at);
        }
        var first = queries.consumptions(ctx, new StoredValuePort.ConsumptionQuery(user, entity, null, null, 1));
        assertThat(first.records()).extracting(r -> r.usageNo()).containsExactly(prefix + 3);
        var second = queries.consumptions(ctx,
                new StoredValuePort.ConsumptionQuery(user, entity, null, first.nextCursor(), 1));
        assertThat(second.records()).extracting(r -> r.usageNo()).containsExactly(prefix + 2);
        var last = queries.consumptions(ctx,
                new StoredValuePort.ConsumptionQuery(user, entity, null, second.nextCursor(), 1));
        assertThat(last.records()).extracting(r -> r.usageNo()).containsExactly(prefix + 1);
        assertThat(last.nextCursor()).isNull();
        assertThat(queries.consumptions(ctx, new StoredValuePort.ConsumptionQuery(user, entity, store, null, 20))
                .records()).extracting(r -> r.usageNo()).containsExactly(prefix + 3, prefix + 1);
        assertThatThrownBy(() -> queries.consumptions(ctx,
                new StoredValuePort.ConsumptionQuery(user, entity, store, first.nextCursor(), 1)))
                .isInstanceOf(ai.neargo.shop.common.BizException.class);
        assertThatThrownBy(() -> queries.consumptions(ctx,
                new StoredValuePort.ConsumptionQuery(actor, entity, null, first.nextCursor(), 1)))
                .isInstanceOf(ai.neargo.shop.common.BizException.class);
        assertThatThrownBy(() -> queries.consumptions(ctx,
                new StoredValuePort.ConsumptionQuery(user, id(), null, first.nextCursor(), 1)))
                .isInstanceOf(ai.neargo.shop.common.BizException.class);
    }
    private long balance() { return jdbc.queryForObject("SELECT principal_minor+bonus_minor FROM sv_account "
            + "WHERE user_no=?", Long.class, user); }
    private long held() { return jdbc.queryForObject("SELECT held_principal_minor+held_bonus_minor FROM sv_account "
            + "WHERE user_no=?", Long.class, user); }
}
