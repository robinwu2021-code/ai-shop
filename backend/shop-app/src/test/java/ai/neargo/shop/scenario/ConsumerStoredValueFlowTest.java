package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.storedvalue.service.StoredValuePlanRechargeService;
import ai.neargo.shop.pay.storedvalue.service.StoredValuePlanService;
import ai.neargo.shop.payclient.StoredValueConsumerQueryAppService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.storedvalue.StoredValuePlanRecharges;
import ai.neargo.shop.spi.storedvalue.StoredValuePlans.Create;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Real application wiring and transactional facts, without external collection or message delivery. */
@SpringBootTest(properties = {"shop.pay.stored-value.enabled=true",
        "shop.pay.entitlement.cursor-key=consumer-query-key-at-least-thirty-two-characters",
        "spring.datasource.url=jdbc:h2:mem:consumer-stored-value-flow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class ConsumerStoredValueFlowTest {
    @Autowired StoredValuePlanService plans;
    @Autowired StoredValuePlanRechargeService recharges;
    @Autowired StoredValueConsumerQueryAppService reads;

    @Test void consumerReadsActualCreditsWithScopedCursorAndShortNumberFocus() {
        String entity=id(),store=id(),user=id();
        var plan=plans.create(new CatalogScope("MAIN",entity,"OWNER",false),
                new Create(store,"到账记录","CNY",10000,3000),id());
        var command=new StoredValuePlanRecharges.Create(user,store,"测试门店",plan.planNo(),
                plan.versionNo(),"a".repeat(64));
        var first=recharges.confirmOffline(context("OWNER"),entity,command);
        var second=recharges.confirmOffline(context("OWNER"),entity,command);
        var consumer=context(user);
        assertThat(reads.store(consumer,store).storeName()).isEqualTo("测试门店");
        var page=reads.records(consumer,store,"ALL",null,null,null,null,1);
        assertThat(page.records()).hasSize(1);
        assertThat(page.records().getFirst().signedAmountMinor()).isEqualTo(13000);
        assertThat(page.nextCursor()).isNotBlank();
        var next=reads.records(consumer,store,"ALL",null,null,null,page.nextCursor(),1);
        assertThat(next.records()).hasSize(1);
        assertThat(next.records().getFirst().documentNo()).isNotEqualTo(page.records().getFirst().documentNo());
        assertThat(next.hasMore()).isFalse();
        assertThat(reads.records(consumer,store,"RECHARGE",null,null,first.documentNo(),null,1)
                .records().getFirst().documentNo()).isEqualTo(first.documentNo());
        assertThat(reads.records(consumer,store,"REDEMPTION",null,null,null,null,20).records()).isEmpty();
        assertThatThrownBy(()->reads.records(context(id()),store,"ALL",null,null,null,null,20))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(()->reads.records(consumer,store,"RECHARGE",null,null,null,page.nextCursor(),1))
                .isInstanceOf(BizException.class);
        assertThat(second.documentNo()).isNotEqualTo(first.documentNo());
    }
    private static EntitlementContext context(String actor) {
        return new EntitlementContext("MAIN",actor,id(),"f".repeat(64));
    }
    private static String id() { return UUID.randomUUID().toString().replace("-",""); }
}
