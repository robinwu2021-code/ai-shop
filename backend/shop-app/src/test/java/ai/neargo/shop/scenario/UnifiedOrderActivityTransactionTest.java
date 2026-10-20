package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.promotion.dto.ActivityActor;
import ai.neargo.shop.promotion.dto.UnifiedActivityDefinition;
import ai.neargo.shop.promotion.dto.UnifiedActivityViews.SaveDraft;
import ai.neargo.shop.promotion.service.ActivityCapabilityEvidence;
import ai.neargo.shop.promotion.service.ActivityMarketingSummaryService;
import ai.neargo.shop.promotion.service.ActivityPricingService;
import ai.neargo.shop.promotion.service.ActivityVersionService;
import ai.neargo.shop.promotion.service.EntitlementScopeValidationService;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import ai.neargo.shop.spi.marketing.CampaignPort;
import ai.neargo.shop.spi.promotion.ActivityCapability;
import ai.neargo.shop.trade.service.OrderService;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real order facts, accounting mappers, projector and transaction boundaries; only admission is a fixture. */
@SpringBootTest(properties={"shop.promotion.activity.enabled=true","shop.promotion.presets.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:unified-order-activity-tx;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "shop.inventory.datasource.url=jdbc:h2:mem:unified-order-activity-inv;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
@Import(UnifiedOrderActivityTransactionTest.Readiness.class)
class UnifiedOrderActivityTransactionTest {
    @Autowired ActivityVersionService versions;
    @Autowired ActivityPricingService pricing;
    @Autowired ActivityMarketingSummaryService summary;
    @Autowired OrderService orders;
    @Autowired JdbcTemplate jdbc;
    @Autowired @Qualifier("transactionManager") PlatformTransactionManager manager;
    @MockitoBean EntitlementScopeValidationService admission;

    @Test void commitReplayAndActualOrderCancellationKeepOneLedgerAndReturnCapacityOnce() {
        var fixture=activity();
        String order=order(fixture,"U-TX");
        var discount=pricing.autoDiscount("U-TX",basket(fixture));
        assertThat(discount.total()).isEqualTo(100);
        tx().executeWithoutResult(status->pricing.commit("U-TX",order,discount));
        tx().executeWithoutResult(status->pricing.commit("U-TX",order,discount));
        assertUsage(fixture,1,100,1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pmt_apply WHERE promo_no=?",Integer.class,fixture.activity())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pmt_effect_projection WHERE activity_no=?",Integer.class,fixture.activity())).isEqualTo(1);
        var before=summary.summarize(fixture.actor(),null,Instant.now().minusSeconds(120),Instant.now().plusSeconds(120));
        assertThat(before.metrics()).hasSize(1);
        assertThat(before.metrics().getFirst().amountMinor()).isEqualTo(100);
        orders.cancelByPlatform(order,"未付款取消","OP-TX");
        tx().executeWithoutResult(status->pricing.release(order));
        assertUsage(fixture,0,0,0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pmt_apply WHERE promo_no=? AND reverted_at IS NOT NULL",Integer.class,fixture.activity())).isEqualTo(1);
        assertThat(summary.summarize(fixture.actor(),null,Instant.now().minusSeconds(120),Instant.now().plusSeconds(120)).metrics()).isEmpty();
    }

    @Test void personalLimitFailureRollsBackEarlierGlobalBudgetReservationAndWritesNoPartialFacts() {
        var fixture=activity();
        String first=order(fixture,"U-TX");
        var discount=pricing.autoDiscount("U-TX",basket(fixture));
        tx().executeWithoutResult(status->pricing.commit("U-TX",first,discount));
        String second=order(fixture,"U-TX");
        assertThatThrownBy(()->tx().executeWithoutResult(status->pricing.commit("U-TX",second,discount)))
                .isInstanceOf(BizException.class);
        assertUsage(fixture,1,100,1);
        assertThat(jdbc.queryForObject("SELECT quota_held+budget_held_minor FROM pmt_activity_budget WHERE activity_no=?",Long.class,fixture.activity())).isZero();
        assertThat(jdbc.queryForObject("SELECT held_count FROM pmt_activity_participant WHERE activity_no=? AND user_no='U-TX'",Integer.class,fixture.activity())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pmt_apply WHERE order_no=?",Integer.class,second)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pmt_effect_projection WHERE activity_no=?",Integer.class,fixture.activity())).isEqualTo(1);
    }

    private Fixture activity() {
        jdbc.update("UPDATE pmt_preset_template SET enabled=1 WHERE tenant_no='MAIN' AND template_code='AP009'");
        var actor=new ActivityActor("MAIN","M-TX-"+UUID.randomUUID(),"OP-TX",null);
        String store="S-TX-"+UUID.randomUUID();
        var definition=new UnifiedActivityDefinition("满额优惠","ORDER","AP009",1,new RedemptionScope("STORES",List.of(store)),null,
                new UnifiedActivityDefinition.Schedule("ONE_OFF",Instant.now().minusSeconds(30),Instant.now().plusSeconds(3600),"UTC",List.of(),null,null),
                new UnifiedActivityDefinition.Rule(1,new UnifiedActivityDefinition.Trigger("AMOUNT",1000L,null),
                        List.of(new UnifiedActivityDefinition.Benefit("CUT",100L,null,null,null)),List.of()),10L,1000L,1,"CNY");
        var draft=versions.saveDraft(actor,new SaveDraft(null,null,definition),key());
        versions.publish(actor,draft.activityNo(),draft.revision(),key());
        return new Fixture(actor,store,draft.activityNo());
    }
    private String order(Fixture f,String user) {
        String order="O-TX-"+UUID.randomUUID(); String sub="SO-TX-"+UUID.randomUUID();
        jdbc.update("INSERT INTO ord_order(order_no,user_no,status,goods_amount,discount_amount,pay_amount,created_at,updated_at) VALUES (?,?,'WAIT_PAY',1000,100,900,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",order,user);
        jdbc.update("INSERT INTO ord_sub_order(sub_order_no,order_no,user_no,entity_no,store_no,status,fulfillment,goods_amount,discount_amount,pay_amount,created_at,updated_at) VALUES (?,?,?,?,?,'WAIT_PAY','STORE_PICKUP',1000,100,900,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",sub,order,user,f.actor().entityNo(),f.store());
        jdbc.update("INSERT INTO ord_item(sub_order_no,order_no,goods_no,sku_no,price,qty,amount,created_at,updated_at) VALUES (?,?,'G-TX','SKU-TX',1000,1,1000,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",sub,order);
        return order;
    }
    private List<CampaignPort.MerchantAmount> basket(Fixture f) {
        return List.of(new CampaignPort.MerchantAmount(f.actor().entityNo(),1000,1,f.store()));
    }
    private void assertUsage(Fixture f,long quota,long amount,int personal) {
        assertThat(jdbc.queryForObject("SELECT quota_used FROM pmt_activity_budget WHERE activity_no=?",Long.class,f.activity())).isEqualTo(quota);
        assertThat(jdbc.queryForObject("SELECT budget_used_minor FROM pmt_activity_budget WHERE activity_no=?",Long.class,f.activity())).isEqualTo(amount);
        assertThat(jdbc.queryForObject("SELECT used_count FROM pmt_activity_participant WHERE activity_no=? AND user_no='U-TX'",Integer.class,f.activity())).isEqualTo(personal);
    }
    private TransactionTemplate tx() { return new TransactionTemplate(manager); }
    private String key() { return "K"+UUID.randomUUID().toString().replace("-",""); }
    private record Fixture(ActivityActor actor,String store,String activity) { }
    @TestConfiguration static class Readiness {
        @Bean ActivityCapabilityEvidence transactionFixtureEvidence() { return actor->EnumSet.allOf(ActivityCapability.class); }
    }
}
