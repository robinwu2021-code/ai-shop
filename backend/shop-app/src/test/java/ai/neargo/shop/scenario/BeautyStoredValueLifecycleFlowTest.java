package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import ai.neargo.shop.member.dto.MemberCodeScope;
import ai.neargo.shop.spi.entitlement.EntitlementMarketingFactsPort;
import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.promotion.service.ActivityMarketingSummaryService;
import ai.neargo.shop.member.service.MemberCodeResolveService;
import ai.neargo.shop.payclient.StoredValueRechargeAppService;
import ai.neargo.shop.paybridge.EntitlementServiceUseCoordinator;
import ai.neargo.shop.paybridge.StoredValueRechargeCoordinator;
import ai.neargo.shop.paybridge.EntitlementRecoveryService;
import ai.neargo.shop.trade.dto.EntitlementRecoveryWork.Kind;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort;
import ai.neargo.shop.spi.entitlement.EntitlementUseFactsPort;
import ai.neargo.shop.promotion.dto.ActivityActor;
import ai.neargo.shop.promotion.dto.RechargeIntentCreate;
import ai.neargo.shop.promotion.dto.UnifiedActivityDefinition;
import ai.neargo.shop.promotion.dto.UnifiedActivityViews.SaveDraft;
import ai.neargo.shop.promotion.service.ActivityVersionService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import ai.neargo.shop.spi.storedvalue.StoredValuePort;
import ai.neargo.shop.spi.storedvalue.StoredValueUseQuotePort;
import ai.neargo.shop.trade.dto.AuthorizationCommand;
import ai.neargo.shop.trade.dto.ServiceOrderCreate;
import ai.neargo.shop.trade.dto.ServiceUseSubmit;
import ai.neargo.shop.trade.service.EntitlementAuthorizationService;
import ai.neargo.shop.trade.service.EntitlementServiceOrderService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

/** No activity-version or pay credit fixtures: the real merchant workflow produces every financial fact. */
@SpringBootTest(properties={"shop.member-code.enabled=true","shop.core.entitlement.authorization.enabled=true",
        "shop.promotion.activity.enabled=true","shop.promotion.presets.enabled=true",
        "shop.pay.stored-value.enabled=true","shop.pay.entitlement.use.enabled=true",
        "shop.pay.entitlement.cursor-key=beauty-lifecycle-key-at-least-thirty-two-characters",
        "spring.datasource.url=jdbc:h2:mem:beauty-sv-lifecycle;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class BeautyStoredValueLifecycleFlowTest {
    @Autowired ActivityVersionService activities;
    @Autowired EntitlementMarketingFactsPort marketingFacts;
    @Autowired ActivityMarketingSummaryService summary;
    @Autowired List<OutboxConsumer> consumers;
    @Autowired MemberCodeResolveService members;
    @Autowired StoredValueRechargeCoordinator recharge;
    @Autowired EntitlementRecoveryService recovery;
    @Autowired EntitlementUsePort payUses;
    @Autowired EntitlementUseFactsPort heldFacts;
    @Autowired StoredValueRechargeAppService queries;
    @Autowired EntitlementServiceOrderService orders;
    @Autowired EntitlementAuthorizationService consent;
    @Autowired EntitlementServiceUseCoordinator consume;
    @Autowired StoredValueUseQuotePort quotes;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    private String id() { return UUID.randomUUID().toString().replace("-",""); }

    protected JdbcTemplate payJdbc() { return jdbc; }

    @Test void publishedBeautyPresetReceivesCreditsConsumesAndKeepsZeroBalanceStoreWithClearRecord() {
        String user=id(),actor=id(),entity=id(),store=id(),goods=id(),sku=id();
        for(String no:List.of(user,actor)) jdbc.update("INSERT INTO usr_account(user_no,status,tenant_no,created_at,"
                + "updated_at) VALUES(?,'NORMAL','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",no);
        jdbc.update("INSERT INTO mch_entity(entity_no,owner_user_no,name,status,industry,market,tenant_no,created_at,"
                + "updated_at) VALUES(?,?,'beauty','ACTIVE','LIFE_SERVICE','CN','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                entity,actor);
        jdbc.update("INSERT INTO mch_store(store_no,entity_no,name,status,tenant_no,created_at,updated_at) "
                + "VALUES(?,?,'beauty store','ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",store,entity);
        jdbc.update("INSERT INTO prd_goods(goods_no,entity_no,store_no,title,type,on_sale,audit_status,fulfillments,"
                + "tenant_no,created_at,updated_at) VALUES(?,?,?,'Care','SERVICE',1,'APPROVED','[\"STORE_VERIFY\"]',"
                + "'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",goods,entity,store);
        jdbc.update("INSERT INTO prd_sku(sku_no,goods_no,entity_no,market,price,stock,locked_stock,tenant_no,created_at,"
                + "updated_at) VALUES(?,?,?,'CN',2200,0,0,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",sku,goods,entity);
        // Test-only operator opt-in; shipped presets remain disabled.
        jdbc.update("UPDATE pmt_preset_template SET enabled=1 WHERE tenant_no='MAIN' AND template_code='AP030'");
        Instant now=Instant.now();
        var storeScope=new RedemptionScope("STORES",List.of(store));
        var definition=new UnifiedActivityDefinition("Beauty top up","RECHARGE","AP030",1,storeScope,null,
                new UnifiedActivityDefinition.Schedule("ONE_OFF",now.minusSeconds(60),now.plusSeconds(3600),
                        "Asia/Shanghai",List.of(),null,null),
                new UnifiedActivityDefinition.Rule(1,new UnifiedActivityDefinition.Trigger("NONE",null,null),List.of(),
                        List.of(new UnifiedActivityDefinition.Tier("T1",1000,100,storeScope))),10L,10000L,2,"CNY");
        var owner=new ActivityActor("MAIN",entity,actor,null);
        var draft=activities.saveDraft(owner,new SaveDraft(null,null,definition),id());
        var version=activities.publish(owner,draft.activityNo(),draft.revision(),id());
        var code=members.issue("MAIN",user);
        var reference=members.resolve(new MemberCodeScope("MAIN",entity,store,actor),code.memberCode()).customerRef();
        var ctx=new EntitlementContext("MAIN",actor,id(),"a".repeat(64));
        String createKey=id();
        var request=new RechargeIntentCreate(reference,draft.activityNo(),version.versionNo(),"T1",store);
        var created=recharge.create(ctx,entity,request,createKey);
        var waiting=recovery.recover("MAIN",Kind.RECHARGE,0,20);
        assertThat(waiting.scanned()).isEqualTo(1);
        assertThat(waiting.deferred()).isEqualTo(1);
        assertThat(recharge.query(ctx,entity,created.rechargeNo()).status()).isEqualTo("WAIT_COLLECTION");
        var submitted=recharge.submit(ctx,entity,new StoredValuePort.SubmitRecharge(created.rechargeNo(),
                created.revision(),1000,StoredValuePort.CollectionMethod.CASH,id(),"c".repeat(64)));
        var received=recharge.receive(ctx,entity,new StoredValuePort.ReceiveRecharge(created.rechargeNo(),
                submitted.revision(),"c".repeat(64),"Merchant verified cash receipt"));
        assertThat(received.status()).isEqualTo("RECEIVED");
        var recovered=recovery.recover("MAIN",Kind.RECHARGE,0,20);
        assertThat(recovered.completed()).isEqualTo(1);
        assertThat(recovered.failed()).isZero();
        assertThat(recovery.recover("MAIN",Kind.RECHARGE,0,20).scanned()).isZero();
        var credited=recharge.query(ctx,entity,created.rechargeNo());
        assertThat(credited.status()).isEqualTo("CREDITED");
        assertThat(recharge.recover(ctx,entity,created.rechargeNo())).isEqualTo(credited);
        assertThat(recharge.create(ctx,entity,request,createKey).rechargeNo()).isEqualTo(created.rechargeNo());
        assertThat(jdbc.queryForObject("SELECT quota_used FROM pmt_activity_budget WHERE activity_no=?",Long.class,
                draft.activityNo())).isEqualTo(1);
        // A second real activity funds another lot: one later consumption must attribute both contributions.
        var secondDraft=activities.saveDraft(owner,new SaveDraft(null,null,definition),id());
        var secondVersion=activities.publish(owner,secondDraft.activityNo(),secondDraft.revision(),id());
        var secondContext=new EntitlementContext("MAIN",actor,id(),"b".repeat(64));
        var second=recharge.create(secondContext,entity,new RechargeIntentCreate(reference,secondDraft.activityNo(),
                secondVersion.versionNo(),"T1",store),id());
        var secondSubmitted=recharge.submit(secondContext,entity,new StoredValuePort.SubmitRecharge(second.rechargeNo(),
                second.revision(),1000,StoredValuePort.CollectionMethod.CASH,id(),"d".repeat(64)));
        recharge.receive(secondContext,entity,new StoredValuePort.ReceiveRecharge(second.rechargeNo(),
                secondSubmitted.revision(),"d".repeat(64),"second verified receipt"));
        assertThat(recovery.recover("MAIN",Kind.RECHARGE,0,20).completed()).isEqualTo(1);
        var service=orders.createMonetary(ctx,entity,new ServiceOrderCreate(reference,store,
                List.of(new ServiceOrderCreate.Line(sku,1))),id());
        var quote=quotes.quote(ctx,service.fact().source());
        orders.bindMoneyPreview(ctx,entity,store,service.serviceOrderNo(),quote);
        var authorization=consent.confirm("MAIN",user,service.serviceOrderNo(),
                new AuthorizationCommand(0,quote.quoteRef(),quote.snapshotHash(),id()));
        var use=new ServiceUseSubmit(service.serviceOrderNo(),quote.quoteRef(),authorization.authorizationRef(),
                quote.assetRevision());
        // Model process loss after durable source acceptance but before the pay commit/acknowledgement.
        var staged=orders.stage(ctx,entity,store,use);
        payUses.prepare(staged.context(),staged.command());
        orders.accept(ctx,entity,heldFacts.query(ctx,entity,staged.operationNo()));
        var completed=recovery.recover("MAIN",Kind.SERVICE_USE,0,20);
        assertThat(completed.completed()).isEqualTo(1);
        assertThat(recovery.recover("MAIN",Kind.SERVICE_USE,0,20).scanned()).isZero();
        var used=consume.query(ctx,entity,staged.operationNo());
        assertThat(used.status().name()).isEqualTo("COMMITTED");
        assertThat(consume.submit(ctx,entity,store,use)).isEqualTo(used);
        assertThat(queries.balance(ctx,new StoredValuePort.BalanceQuery(user,entity,"CNY",store)).balanceMinor()).isZero();
        assertThat(queries.stores(ctx,new StoredValuePort.StoreQuery(user,null,20)).records())
                .anyMatch(s -> s.storeNo().equals(store));
        var history=queries.consumptions(ctx,new StoredValuePort.ConsumptionQuery(user,entity,store,null,20));
        assertThat(history.records()).hasSize(1);
        assertThat(history.records().getFirst().amountMinor()).isEqualTo(2200);
        assertThat(history.records().getFirst().storeName()).isEqualTo("beauty store");
        assertThat(json.writeValueAsString(history)).doesNotContain("principal","bonus","authorizationRef","memberCode");
        var firstAttribution=marketingFacts.usage(ctx,entity,used.usageNo(),"",1);
        assertThat(firstAttribution.records()).hasSize(1);
        assertThat(firstAttribution.nextCursor()).isNotNull();
        var secondAttribution=marketingFacts.usage(ctx,entity,used.usageNo(),firstAttribution.nextCursor(),1);
        assertThat(secondAttribution.records()).hasSize(1);
        assertThat(secondAttribution.nextCursor()).isNull();
        assertThat(firstAttribution.records().getFirst().amountMinor()
                +secondAttribution.records().getFirst().amountMinor()).isEqualTo(2200);
        var committed=payJdbc().query("SELECT event_no,event_type,aggregate_no,payload FROM pay_ent_outbox "
                + "WHERE event_type IN ('STORED_VALUE_RECHARGE_RECEIVED','STORED_VALUE_CREDITED','ENTITLEMENT_USED') "
                + "ORDER BY event_no DESC",(rs,n) -> {
                    var event=new SysOutbox(); event.setEventNo(rs.getString("event_no"));
                    event.setEventType(rs.getString("event_type")); event.setAggregateId(rs.getString("aggregate_no"));
                    event.setAggregateType("ENTITLEMENT_USED".equals(event.getEventType())
                            ?"ENTITLEMENT_USAGE":"STORED_VALUE_RECHARGE");
                    event.setPayload(rs.getString("payload")); return event;
                });
        for(var event:committed) for(var consumer:consumers) if(consumer.supports(event.getEventType())) {
            consumer.consume(event); consumer.consume(event);
        }
        var totals=summary.summarize(owner,store,now.minusSeconds(10),Instant.now().plusSeconds(10));
        assertThat(totals.metrics()).extracting(ActivityMarketingSummaryService.Metric::effectKind)
                .contains("RECHARGE","CREDITED","CONSUMED");
        for(var metric:totals.metrics()) {
            if("RECHARGE".equals(metric.effectKind())) {
                assertThat(metric.amountMinor()).isEqualTo(2000L); assertThat(metric.eventCount()).isEqualTo(2);
            }
            if("CREDITED".equals(metric.effectKind())) {
                assertThat(metric.amountMinor()).isEqualTo(2200L); assertThat(metric.eventCount()).isEqualTo(2);
            }
            if("CONSUMED".equals(metric.effectKind())) {
                assertThat(metric.amountMinor()).isEqualTo(2200L); assertThat(metric.eventCount()).isEqualTo(1);
            }
        }
        assertThat(payJdbc().queryForObject("SELECT account_seq FROM sv_account WHERE user_no=?",Long.class,user))
                .isEqualTo(3);


    }
}
