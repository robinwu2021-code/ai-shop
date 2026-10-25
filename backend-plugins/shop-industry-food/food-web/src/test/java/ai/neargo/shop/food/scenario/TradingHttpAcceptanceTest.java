package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.neargo.shop.auth.*;
import ai.neargo.shop.auth.store.MemoryTokenStore;
import ai.neargo.shop.common.*;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.api.biz.*;
import ai.neargo.shop.food.dto.*;
import ai.neargo.shop.food.service.*;
import ai.neargo.shop.spi.trade.PricingCheckoutPort.QuoteVO;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.web.servlet.*;
import org.springframework.web.context.WebApplicationContext;

/** MockMvc real token filters, method authorization, controller and JSON contracts; owners are mocks, no DB. */
class TradingHttpAcceptanceTest {
    private static final String HASH="a".repeat(64);
    private final WebApplicationContextRunner runner=new WebApplicationContextRunner()
            .withUserConfiguration(TradingHost.class)
            .withPropertyValues("shop.food.enabled=true","spring.profiles.active=api,ops");
    private static MockMvc mvc(WebApplicationContext c) { return OperationsHttpAcceptanceTest.mvc(c); }
    private static String token(WebApplicationContext c,String actor) { return OperationsHttpAcceptanceTest.biz(c,actor); }
    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder command(WebApplicationContext c,String path,String body,String key) {
        return post(path).header("Authorization",token(c,"staff")).header("Idempotency-Key",key).contentType("application/json").content(body);
    }

    @Test void aaCreateReadConfirmCancelCarryOriginalHashesVersionAndActor() {
        runner.run(c -> {
            var owner=c.getBean(BusinessShareService.class);
            var definition=new BusinessShareContracts.CreateReq(7L,"BY_PERSON",List.of(new BusinessShareContracts.Person("P1",null)),null,"split responsibility");
            when(owner.create("E","A","staff","C1",definition,"aa-create")).thenReturn(plan("DRAFT_BUSINESS_ONLY",1,definition));
            when(owner.get("E","A","C1","AA1")).thenReturn(plan("DRAFT_BUSINESS_ONLY",1,definition));
            when(owner.confirm("E","A","staff","C1","AA1",new BusinessShareContracts.ConfirmReq(1L,HASH,HASH),"aa-confirm")).thenReturn(plan("CONFIRMED_BUSINESS_ONLY",2,definition));
            when(owner.cancel("E","A","staff","C1","AA1",new BusinessShareContracts.CancelReq(2L,"withdraw"),"aa-cancel")).thenReturn(plan("CANCELLED_BUSINESS_ONLY",3,definition));
            String base="/biz/x/food/checks/C1/business-share-plans";
            mvc(c).perform(command(c,base,"{\"checkVersion\":7,\"mode\":\"BY_PERSON\",\"people\":[{\"personNo\":\"P1\"}],\"reason\":\"split responsibility\"}","aa-create"))
                    .andExpect(jsonPath("$.data.planNo").value("AA1")).andExpect(jsonPath("$.data.financialAuthorityPending").value(true)).andExpect(jsonPath("$.data.settlementEligible").value(false));
            mvc(c).perform(get(base+"/AA1").header("Authorization",token(c,"staff")))
                    .andExpect(jsonPath("$.data.originalAmountMinor").value(101)).andExpect(jsonPath("$.data.sourceHash").value(HASH));
            mvc(c).perform(command(c,base+"/AA1:confirm","{\"version\":1,\"sourceHash\":\""+HASH+"\",\"planHash\":\""+HASH+"\"}","aa-confirm"))
                    .andExpect(jsonPath("$.data.status").value("CONFIRMED_BUSINESS_ONLY")).andExpect(jsonPath("$.data.version").value(2));
            mvc(c).perform(command(c,base+"/AA1:cancel","{\"version\":2,\"reason\":\"withdraw\"}","aa-cancel"))
                    .andExpect(jsonPath("$.data.status").value("CANCELLED_BUSINESS_ONLY")).andExpect(jsonPath("$.data.version").value(3));
            verify(owner).create("E","A","staff","C1",definition,"aa-create");
            verify(owner).confirm("E","A","staff","C1","AA1",new BusinessShareContracts.ConfirmReq(1L,HASH,HASH),"aa-confirm");
            verify(owner).cancel("E","A","staff","C1","AA1",new BusinessShareContracts.CancelReq(2L,"withdraw"),"aa-cancel");
        });
    }

    @Test void aaRejectsMissingHashForgedActorForeignStoreAndRoleBeforeOwner() {
        runner.run(c -> {
            String path="/biz/x/food/checks/C1/business-share-plans/AA1:confirm";
            mvc(c).perform(command(c,path,"{\"version\":1,\"sourceHash\":\"bad\",\"planHash\":\"bad\"}","key"))
                    .andExpect(jsonPath("$.code").value(10400));
            mvc(c).perform(command(c,path,"{\"version\":1,\"actorNo\":\"forged\",\"sourceHash\":\""+HASH+"\",\"planHash\":\""+HASH+"\"}","key"))
                    .andExpect(jsonPath("$.code").value(10400));
            mvc(c).perform(get("/biz/x/food/checks/C1/business-share-plans/AA1").header("Authorization",token(c,"staff")).header("X-Store-No","B"))
                    .andExpect(jsonPath("$.code").value(10403));
            mvc(c).perform(post(path).header("Authorization",token(c,"cashier")).header("Idempotency-Key","key").contentType("application/json")
                            .content("{\"version\":1,\"sourceHash\":\""+HASH+"\",\"planHash\":\""+HASH+"\"}"))
                    .andExpect(jsonPath("$.code").value(70006));
            verifyNoInteractions(c.getBean(BusinessShareService.class));
        });
    }

    @Test void staffQuotePlaceAndPendingBatchCancelPreserveBuyerOperatorAndOriginalVersion() {
        runner.run(c -> {
            var pricing=c.getBean(DiningPricingService.class); var orders=c.getBean(DiningOrderService.class);
            when(pricing.staffQuote("E","A","staff",new DiningQuoteReq("CART1",7,null),"quote-key"))
                    .thenReturn(new QuoteVO("Q1","READY","2026-10-25T12:00:00Z",7,HASH,"CNY",List.of(),null,HASH));
            var place=new CreateStaffDiningOrderReq("BUYER","CART1",7,"Q1",HASH,"POSTPAY");
            when(orders.staffPlace("E","A","staff",place,"place-key")).thenReturn(batch("UNKNOWN",8));
            when(orders.staffCancel("E","A","staff","B1",new CancelDiningOrderReq(8L,"withdraw pending"),"cancel-key")).thenReturn(batch("CANCEL_PENDING",9));
            mvc(c).perform(command(c,"/biz/x/food/stores/A/quotes","{\"cartNo\":\"CART1\",\"version\":7}","quote-key"))
                    .andExpect(jsonPath("$.data.quoteNo").value("Q1")).andExpect(jsonPath("$.data.confirmationDigest").value(HASH));
            mvc(c).perform(command(c,"/biz/x/food/stores/A/batches",staffOrderBody(),"place-key"))
                    .andExpect(jsonPath("$.data.status").value("UNKNOWN")).andExpect(jsonPath("$.data.orderNo").doesNotExist()).andExpect(jsonPath("$.data.version").value(8));
            mvc(c).perform(command(c,"/biz/x/food/batches/B1:cancel","{\"version\":8,\"reason\":\"withdraw pending\"}","cancel-key"))
                    .andExpect(jsonPath("$.data.status").value("CANCEL_PENDING")).andExpect(jsonPath("$.data.version").value(9));
            verify(pricing).staffQuote("E","A","staff",new DiningQuoteReq("CART1",7,null),"quote-key");
            verify(orders).staffPlace("E","A","staff",place,"place-key");
            verify(orders).staffCancel("E","A","staff","B1",new CancelDiningOrderReq(8L,"withdraw pending"),"cancel-key");
        });
    }

    @Test void staffTradingRejectsPrepayForgedActorMissingVersionForeignStoreAndRole() {
        runner.run(c -> {
            mvc(c).perform(command(c,"/biz/x/food/stores/A/batches",staffOrderBody().replace("POSTPAY","PREPAY"),"key"))
                    .andExpect(jsonPath("$.code").value(10400));
            mvc(c).perform(command(c,"/biz/x/food/stores/A/batches",staffOrderBody().replace("{","{\"actorNo\":\"forged\", "),"key"))
                    .andExpect(jsonPath("$.code").value(10400));
            mvc(c).perform(command(c,"/biz/x/food/batches/B1:cancel","{\"reason\":\"withdraw\"}","key"))
                    .andExpect(jsonPath("$.code").value(10400));
            mvc(c).perform(command(c,"/biz/x/food/stores/B/quotes","{\"cartNo\":\"CART1\",\"version\":7}","key"))
                    .andExpect(jsonPath("$.code").value(10403));
            mvc(c).perform(post("/biz/x/food/stores/A/batches").header("Authorization",token(c,"cashier")).header("Idempotency-Key","key").contentType("application/json").content(staffOrderBody()))
                    .andExpect(jsonPath("$.code").value(70006));
            verifyNoInteractions(c.getBean(DiningOrderService.class),c.getBean(DiningPricingService.class));
        });
    }

    @Test void businessReturnApprovalAndReplacementCarryOriginalVersionAndPendingOwnerResult() {
        runner.run(c -> {
            var owner=c.getBean(BusinessReturnApprovalService.class);
            var approve=new BusinessReturnApprovalContracts.ApproveReq(3L,"replace","RC1");
            var link=new BusinessReturnApprovalContracts.ReplacementReq(4L,"NEW-B1","original replacement");
            when(owner.approve("E","A","staff","R1",approve,"approve-key"))
                    .thenReturn(new BusinessReturnApprovalContracts.ApprovalVO("AP1","R1",4,"WAIT_KITCHEN",true,false,null,null,null));
            when(owner.linkReplacement("E","A","staff","R1",link,"link-key"))
                    .thenReturn(new BusinessReturnApprovalContracts.ApprovalVO("AP1","R1",5,"CONFIRMED",false,true,amendmentReceipt(),"NEW-B1","NEW-O1"));
            mvc(c).perform(command(c,"/biz/x/food/business-returns/R1:approve","{\"version\":3,\"reason\":\"replace\",\"replacementCartNo\":\"RC1\"}","approve-key"))
                    .andExpect(jsonPath("$.data.status").value("WAIT_KITCHEN")).andExpect(jsonPath("$.data.originalPayableChanged").value(false));
            mvc(c).perform(command(c,"/biz/x/food/business-returns/R1:link-replacement","{\"version\":4,\"replacementBatchNo\":\"NEW-B1\",\"reason\":\"original replacement\"}","link-key"))
                    .andExpect(jsonPath("$.data.replacementBatchNo").value("NEW-B1")).andExpect(jsonPath("$.data.replacementOrderNo").value("NEW-O1"));
            verify(owner).approve("E","A","staff","R1",approve,"approve-key"); verify(owner).linkReplacement("E","A","staff","R1",link,"link-key");
        });
    }

    @Test void businessReturnApprovalRejectsMissingKeyWrongRoleAndForeignStoreBeforeOwner() {
        runner.run(c -> {
            String path="/biz/x/food/business-returns/R1:approve", body="{\"version\":3,\"reason\":\"replace\"}";
            mvc(c).perform(post(path).header("Authorization",token(c,"staff")).contentType("application/json").content(body)).andExpect(jsonPath("$.code").value(10400));
            mvc(c).perform(command(c,path,body,"key").header("X-Store-No","B")).andExpect(jsonPath("$.code").value(10403));
            mvc(c).perform(post(path).header("Authorization",token(c,"cashier")).header("Idempotency-Key","key").contentType("application/json").content(body)).andExpect(jsonPath("$.code").value(70006));
            verifyNoInteractions(c.getBean(BusinessReturnApprovalService.class));
        });
    }

    @Test void productionAdjustAndReversePreserveDecimalQuantityOriginalVersionAndUnknownOutcome() {
        runner.run(c -> {
            var owner=c.getBean(ProductionConsumptionService.class);
            var adjustment=new ProductionContracts.AdjustmentReq(4L,"MATERIAL_SUPPLEMENT",new BigDecimal("0.125"),"extra preparation");
            var reverse=new ProductionContracts.ReverseReq(5L,"wrong task");
            when(owner.adjust("E","A","staff","T1",adjustment,"adjust-key")).thenReturn(consumption("CON1","EV1","UNKNOWN",5));
            when(owner.reverse("E","A","staff","EV1",reverse,"reverse-key")).thenReturn(consumption("REV1","EV1","REVERSAL_PENDING",6));
            mvc(c).perform(command(c,"/biz/x/food/production-tasks/T1:adjust","{\"version\":4,\"kind\":\"MATERIAL_SUPPLEMENT\",\"qty\":0.125,\"reason\":\"extra preparation\"}","adjust-key"))
                    .andExpect(jsonPath("$.data.consumptionNo").value("CON1")).andExpect(jsonPath("$.data.status").value("UNKNOWN")).andExpect(jsonPath("$.data.totalCostMinor").doesNotExist());
            mvc(c).perform(command(c,"/biz/x/food/production-events/EV1:reverse","{\"version\":5,\"reason\":\"wrong task\"}","reverse-key"))
                    .andExpect(jsonPath("$.data.sourceEventNo").value("EV1")).andExpect(jsonPath("$.data.status").value("REVERSAL_PENDING"));
            verify(owner).adjust("E","A","staff","T1",adjustment,"adjust-key"); verify(owner).reverse("E","A","staff","EV1",reverse,"reverse-key");
        });
    }

    @Test void productionRejectsMissingKeyMalformedQuantityRoleAndStoreBeforeOwner() {
        runner.run(c -> {
            String path="/biz/x/food/production-tasks/T1:adjust",body="{\"version\":4,\"kind\":\"MATERIAL_SUPPLEMENT\",\"qty\":0.125,\"reason\":\"extra\"}";
            mvc(c).perform(post(path).header("Authorization",token(c,"staff")).contentType("application/json").content(body)).andExpect(jsonPath("$.code").value(10400));
            mvc(c).perform(command(c,path,body.replace("0.125","\"invalid\""),"key")).andExpect(jsonPath("$.code").value(10400));
            mvc(c).perform(command(c,path,body,"key").header("X-Store-No","B")).andExpect(jsonPath("$.code").value(10403));
            mvc(c).perform(post(path).header("Authorization",token(c,"cashier")).header("Idempotency-Key","key").contentType("application/json").content(body)).andExpect(jsonPath("$.code").value(70006));
            verifyNoInteractions(c.getBean(ProductionConsumptionService.class));
        });
    }

    @Test void collaborationMovesBindBothCheckVersionsOriginalBatchOrLinesAndActor() {
        runner.run(c -> {
            var owner=c.getBean(CheckCollaborationService.class);
            var source=new CheckCollaborationContracts.CheckVersion("C1",7); var target=new CheckCollaborationContracts.CheckVersion("C2",8);
            var batches=new CheckCollaborationContracts.MoveReq(source,target,List.of("B1"),"move responsibility");
            var lines=new CheckCollaborationContracts.LineMoveReq(source,target,List.of("L1"),"move responsibility");
            when(owner.moveBatches("E","A","staff",batches,"move-batch-key")).thenReturn(new CheckCollaborationContracts.MoveVO("M1","C1","C2",List.of("B1"),8,9,"SUCCEEDED"));
            when(owner.moveLines("E","A","staff",lines,"move-line-key")).thenReturn(new CheckCollaborationContracts.LineMoveVO("M2","C1","C2",List.of("L1"),"SUCCEEDED"));
            mvc(c).perform(command(c,"/biz/x/food/business-operations/checkCollaboration/moveBatches",moveBody("batchNos","B1"),"move-batch-key"))
                    .andExpect(jsonPath("$.data.sourceVersion").value(8)).andExpect(jsonPath("$.data.targetVersion").value(9)).andExpect(jsonPath("$.data.batchNos[0]").value("B1"));
            mvc(c).perform(command(c,"/biz/x/food/business-operations/checkCollaboration/moveLines",moveBody("foodLineNos","L1"),"move-line-key"))
                    .andExpect(jsonPath("$.data.sourceCheckNo").value("C1")).andExpect(jsonPath("$.data.targetCheckNo").value("C2")).andExpect(jsonPath("$.data.foodLineNos[0]").value("L1"));
            verify(owner).moveBatches("E","A","staff",batches,"move-batch-key"); verify(owner).moveLines("E","A","staff",lines,"move-line-key");
        });
    }

    @Test void collaborationRejectsEmptyLinesNegativeVersionQueryScopeAndRoleBeforeOwner() {
        runner.run(c -> {
            String base="/biz/x/food/business-operations/checkCollaboration/";
            mvc(c).perform(command(c,base+"moveLines",moveBody("foodLineNos","L1").replace("[\"L1\"]","[]"),"key")).andExpect(jsonPath("$.code").value(10400));
            mvc(c).perform(command(c,base+"moveBatches",moveBody("batchNos","B1").replace("\"version\":7","\"version\":-1"),"key")).andExpect(jsonPath("$.code").value(10400));
            mvc(c).perform(command(c,base+"moveBatches",moveBody("batchNos","B1"),"key").param("entityNo","FORGED")).andExpect(jsonPath("$.code").value(10400));
            mvc(c).perform(command(c,base+"moveLines",moveBody("foodLineNos","L1"),"key").header("X-Store-No","B")).andExpect(jsonPath("$.code").value(10403));
            mvc(c).perform(post(base+"moveBatches").header("Authorization",token(c,"cashier")).header("Idempotency-Key","key").contentType("application/json").content(moveBody("batchNos","B1"))).andExpect(jsonPath("$.code").value(70006));
            verifyNoInteractions(c.getBean(CheckCollaborationService.class));
        });
    }

    static String staffOrderBody() { return "{\"buyerNo\":\"BUYER\",\"cartNo\":\"CART1\",\"version\":7,\"quoteNo\":\"Q1\",\"confirmationDigest\":\""+HASH+"\",\"payMode\":\"POSTPAY\"}"; }
    static String moveBody(String field,String no) { return "{\"request\":{\"source\":{\"checkNo\":\"C1\",\"version\":7},\"target\":{\"checkNo\":\"C2\",\"version\":8},\""+field+"\":[\""+no+"\"],\"reason\":\"move responsibility\"}}"; }
    static DiningOrderBatchVO batch(String status,long version) { return new DiningOrderBatchVO("B1","A","C1","CART1",7,"Q1",status,"POSTPAY",LocalDate.of(2026,10,25),null,null,"UNKNOWN","CNY",101L,version); }
    static BusinessShareContracts.PlanVO plan(String status,long version,BusinessShareContracts.CreateReq definition) { return new BusinessShareContracts.PlanVO("AA1","C1",version,status,status,"BY_PERSON","CNY",101,HASH,HASH,true,false,true,null,new BusinessShareContracts.Division(List.of(new BusinessShareContracts.PersonTotal("P1",101)),List.of()),definition,"split responsibility","staff",null,null); }
    static ai.neargo.shop.spi.trade.IndustryOrderAmendmentPort.Receipt amendmentReceipt() {
        var scope=new ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope("MAIN","E","A");
        var source=new ai.neargo.shop.spi.trade.PricingSourceRef("FOOD","CART","ORIGINAL-CART",1,"C1",1L);
        var reference=new ai.neargo.shop.spi.trade.IndustryFulfillmentPort.Reference(scope,source,"O1","SO1");
        return new ai.neargo.shop.spi.trade.IndustryOrderAmendmentPort.Receipt("AMD1",reference,"CONFIRMED",false,"CNY",-101L,0L,4,List.of(),"RC1");
    }
    static ProductionContracts.ConsumptionVO consumption(String no,String event,String status,long version) { return new ProductionContracts.ConsumptionVO(no,event,"MATERIAL_SUPPLEMENT",status,HASH,null,LocalDate.of(2026,10,25),null,List.of(new ProductionContracts.LineVO(1,"ROOT","M1",new BigDecimal("0.125"),"KG","RECIPE1",null,null,null,null,"PENDING")),"THEORETICAL_ONLY",true,null,null,version); }

    @Configuration(proxyBeanMethods=false)
    static class TradingHost extends OperationsHttpAcceptanceTest.Host {
        @Bean BusinessShareService shares() { return mock(BusinessShareService.class); }
        @Bean DiningOrderService orders() { return mock(DiningOrderService.class); }
        @Bean DiningPricingService pricing() { return mock(DiningPricingService.class); }
        @Bean BusinessReturnApprovalService approvals() { return mock(BusinessReturnApprovalService.class); }
        @Bean ProductionConsumptionService production() { return mock(ProductionConsumptionService.class); }
        @Bean CheckCollaborationService collaboration() { return mock(CheckCollaborationService.class); }
        @Bean BizBusinessShareController shareController(BusinessShareService s,StoreDirectoryPort d,FoodRequestScopeValidator v) { return new BizBusinessShareController(s,d,v); }
        @Bean BizOrderBatchController orderController(DiningOrderService s,StoreDirectoryPort d,FoodRequestScopeValidator v) { return new BizOrderBatchController(s,d,v); }
        @Bean BizDiningPricingController pricingController(DiningPricingService s,StoreDirectoryPort d,FoodRequestScopeValidator v) { return new BizDiningPricingController(s,d,v); }
        @Bean BizBusinessReturnApprovalController approvalController(BusinessReturnApprovalService s,StoreDirectoryPort d,FoodRequestScopeValidator v) { return new BizBusinessReturnApprovalController(s,d,v); }
        @Bean BizProductionConsumptionController productionController(ProductionConsumptionService s,StoreDirectoryPort d,FoodRequestScopeValidator v) { return new BizProductionConsumptionController(s,d,v); }
        @Bean BizBusinessOperationsController operationsController(CheckCollaborationService s,StoreDirectoryPort d,FoodRequestScopeValidator v) { return new BizBusinessOperationsController(mock(ServiceFormService.class),mock(FoodFeeSourceService.class),mock(QueueNotificationService.class),s,mock(ReservationPreorderService.class),mock(ServiceSlotService.class),mock(BusinessOperationsService.class),d,v); }
        @Override @Bean SecurityFilterChain chain(HttpSecurity http,MemoryTokenStore tokens,ObjectProvider<LiveIdentityResolver> identities) throws Exception {
            var context=new BizContextFilter((actor,requested) -> new BizContext("E",Set.of(),Set.of(),Set.of("A"),"A",false,Map.of(),Map.of("A",actor.equals("cashier") ? Set.of() : Set.of(BizPerms.VERIFY,BizPerms.ORDER_VIEW,BizPerms.AFTERSALE,BizPerms.STOCK))));
            return http.csrf(x -> x.disable()).authorizeHttpRequests(a -> a.anyRequest().authenticated())
                    .exceptionHandling(e -> e.authenticationEntryPoint((q,r,x) -> r.sendError(401)))
                    .addFilterBefore(new OperatorTokenAuthFilter(tokens,identities),UsernamePasswordAuthenticationFilter.class)
                    .addFilterBefore(new MerchantTokenAuthFilter(tokens),UsernamePasswordAuthenticationFilter.class)
                    .addFilterAfter(context,MerchantTokenAuthFilter.class).build();
        }
    }
}
