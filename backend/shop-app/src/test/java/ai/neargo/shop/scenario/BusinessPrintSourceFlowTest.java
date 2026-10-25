package ai.neargo.shop.scenario;

import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.print.api.*;
import ai.neargo.shop.printbridge.BusinessPrintReceiptConsumer;
import ai.neargo.shop.trade.order.printing.BusinessPrintFactService;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.storedvalue.service.StoredValuePlanRechargeService;
import ai.neargo.shop.pay.storedvalue.service.StoredValuePlanService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.storedvalue.StoredValuePlanRecharges;
import ai.neargo.shop.spi.storedvalue.StoredValuePlans.Create;
import ai.neargo.shop.support.BasicRefundFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.context.WebApplicationContext;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Real business owners and committed immutable outboxes; no printer or channel delivery. */
@SpringBootTest(properties={"shop.print.enabled=true","shop.pay.stored-value.enabled=true","shop.refund.closure.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:business-print-source-flow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
@Import(StrictRefundCallbackFlowTest.ExternalChannel.class)
class BusinessPrintSourceFlowTest {
    @org.junit.jupiter.api.io.TempDir static java.nio.file.Path artifacts;
    @org.springframework.test.context.DynamicPropertySource
    static void storage(org.springframework.test.context.DynamicPropertyRegistry properties) {
        properties.add("shop.print.artifacts.root",()->artifacts.toString());
        properties.add("shop.print.artifacts.signing-key",()->"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }
    @Autowired PrintQueryService printQueries;
    @Autowired PrintSimulationFacade simulationFacade;
    @Autowired ai.neargo.shop.printing.center.simulation.SimulationRunner simulations;
    @Autowired PrintExportFacade exportFacade;
    @Autowired ai.neargo.shop.printing.center.export.PrintExportWorker exports;
    @Autowired ai.neargo.shop.printing.center.template.PrintPreviewFacts previews;
    @Autowired ai.neargo.shop.printbridge.PrintActorFactory actorFactory;
    @org.junit.jupiter.api.AfterEach void clearIdentity() {
        ai.neargo.shop.auth.BizContext.clear(); org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired StoredValuePlanService plans;
    @Autowired StoredValuePlanRechargeService recharges;
    @Autowired BusinessPrintReceiptConsumer consumer;
    @Autowired BusinessPrintFactService facts;
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;

    @Test void rechargeKeepsTheFirstPostingBalanceAfterLaterCreditAndReplay() throws Exception {
        String entity=id(),store=id(),user=id();
        var plan=plans.create(new CatalogScope("MAIN",entity,"OWNER",false),new Create(store,"真实充值","CNY",10000,3000),id());
        var command=new StoredValuePlanRecharges.Create(user,store,"测试门店",plan.planNo(),plan.versionNo(),"a".repeat(64));
        var first=recharges.confirmOffline(ctx(),entity,command);
        // A distinct original-operation key creates a real second credit on the same account.
        var later=recharges.confirmOffline(ctx(),entity,command);
        assertThat(later.rechargeNo()).isNotEqualTo(first.rechargeNo());
        assertThat(jdbc.queryForObject("select principal_minor+bonus_minor from sv_account where tenant_no='MAIN' and account_no=(select account_no from sv_lot where tenant_no='MAIN' and recharge_no=?)",Long.class,first.rechargeNo())).isEqualTo(26000);
        var row=jdbc.queryForObject("select event_no,payload from pay_ent_outbox where aggregate_no=? and event_type='STORED_VALUE_RECEIPT_CONFIRMED'",
                (rs,n)-> { var r=new SysOutbox(); r.setEventNo(rs.getString(1)); r.setPayload(rs.getString(2));
                    r.setAggregateType("STORED_VALUE_RECHARGE"); r.setAggregateId(first.rechargeNo());
                    r.setEventType("STORED_VALUE_RECEIPT_CONFIRMED"); return r; },first.rechargeNo());
        // The owner emits three different immutable facts for one aggregate. Keep legacy ids unchanged.
        var ownerJson=new tools.jackson.databind.ObjectMapper();
        String receivedEvent=ai.neargo.shop.pay.storedvalue.policy.StoredValueFacts.no("SR",java.util.List.of("MAIN",first.rechargeNo()),ownerJson);
        String creditedEvent=ai.neargo.shop.pay.storedvalue.policy.StoredValueFacts.no("SC",java.util.List.of("MAIN",first.rechargeNo()),ownerJson);
        String receiptEvent=ai.neargo.shop.pay.storedvalue.policy.StoredValueFacts.no("SP",
                java.util.List.of("STORED_VALUE_RECEIPT_CONFIRMED","MAIN",first.rechargeNo()),ownerJson);
        assertThat(row.getEventNo()).isEqualTo(receiptEvent).isNotEqualTo(receivedEvent).isNotEqualTo(creditedEvent);
        assertThat(jdbc.queryForObject("select event_no from pay_ent_outbox where aggregate_no=? and event_type='STORED_VALUE_RECHARGE_RECEIVED'",String.class,first.rechargeNo())).isEqualTo(receivedEvent);
        assertThat(jdbc.queryForObject("select event_no from pay_ent_outbox where aggregate_no=? and event_type='STORED_VALUE_CREDITED'",String.class,first.rechargeNo())).isEqualTo(creditedEvent);
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        assertThatThrownBy(()->tx.execute(status->{ consumer.consume(row); throw new IllegalStateException("rollback receipt bridge"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from biz_print_fact where source_no=?",Long.class,first.rechargeNo())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from sys_outbox where event_type='BUSINESS_PRINT_FACT_READY' and aggregate_id=?",Long.class,first.rechargeNo())).isZero();
        consumer.consume(row);
        var refs=facts.referencesByOrder(new PrintScope("MAIN",entity,store),first.rechargeNo());
        assertThat(refs).hasSize(1);
        var frozen=facts.snapshot(refs.getFirst()).immutablePayload();
        // Compare structured money too; source JSON object order is not contractual.
        var json=new tools.jackson.databind.ObjectMapper();
        assertThat(json.readTree(frozen.canonicalJson()).at("/fields/balanceAfter/minor").asText()).isEqualTo("13000");
        consumer.consume(row);
        assertThat(facts.snapshot(refs.getFirst()).immutablePayload()).isEqualTo(frozen);
        assertThat(facts.referencesByOrder(new PrintScope("OTHER",entity,store),first.rechargeNo())).isEmpty();
        assertThat(facts.referencesByOrder(new PrintScope("MAIN",entity,id()),first.rechargeNo())).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from biz_print_fact where source_no=?",Long.class,first.rechargeNo())).isEqualTo(1);
        receiptSoftwareFlow(refs.getFirst());
    }

    @Test void refundHasNoReceiptBeforeSuccessAndFreezesOnlyAfterOwnerClosure() throws Exception {
        try(var f=new BasicRefundFixture(context)) {
            var purchase=f.purchase("13207072991"); f.prepare(purchase);
            String no=f.apply(purchase,"REFUND_ONLY"); f.approve(purchase,no);
            assertThat(jdbc.queryForObject("select count(*) from biz_print_fact where source_no=?",Long.class,no)).isZero();
            f.confirmCash(no);
            // Owner callback may leave resource obligations pending; explicit recovery closes them.
            context.getBean(ai.neargo.shop.trade.order.refund.service.BasicRefundExecutionService.class).recover("RF-"+no);
            var scope=jdbc.queryForObject("select tenant_no,entity_no,store_no from ord_sub_order where sub_order_no=?",
                    (rs,n)->new PrintScope(rs.getString(1),rs.getString(2),rs.getString(3)),purchase.subNo());
            var refs=facts.referencesByOrder(scope,no); assertThat(refs).hasSize(1);
            var ref=refs.getFirst();
            assertThat(facts.resolveReference(new PrintSourceReference("REFUND",purchase.payNo(),ref.factNo(),ref.bizRevision(),ref.scene()),scope)).isEqualTo(ref);
            assertThat(facts.resolveReference(new PrintSourceReference("REFUND",purchase.subNo(),ref.factNo(),ref.bizRevision(),ref.scene()),scope)).isEqualTo(ref);
            assertThatThrownBy(()->facts.resolveReference(new PrintSourceReference("REFUND","unrelated-order",ref.factNo(),ref.bizRevision(),ref.scene()),scope)).isInstanceOf(PrintFailure.class);
            assertThatThrownBy(()->facts.resolveReference(new PrintSourceReference("REFUND",purchase.payNo(),ref.factNo(),ref.bizRevision(),ref.scene()),new PrintScope(scope.tenantNo(),scope.entityNo(),id()))).isInstanceOf(PrintFailure.class);
            assertThatThrownBy(()->facts.resolveReference(new PrintSourceReference("REFUND",purchase.payNo(),ref.factNo(),"wrong-revision",ref.scene()),scope)).isInstanceOf(PrintFailure.class);
            var before=facts.snapshot(refs.getFirst());
            assertThat(before.printability()).isEqualTo(Printability.READY);
            jdbc.update("update ord_item set title='later mutable name' where sub_order_no=?",purchase.subNo());
            f.confirmCash(no);
            assertThat(facts.snapshot(refs.getFirst())).isEqualTo(before);
            assertThat(before.immutablePayload().canonicalJson()).doesNotContain("later mutable name");
            receiptSoftwareFlow(refs.getFirst());
        }
    }
    private PrintActor employee(PrintFactRef ref, boolean owner, String key) {
        var user=ai.neargo.shop.auth.LoginUser.merchant(owner?"SOURCE-OWNER":"SOURCE-ORDER-EMPLOYEE","source employee");
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(user,"",java.util.List.of()));
        var permissions=java.util.Set.of(ai.neargo.shop.auth.BizPerms.ORDER_VIEW,"biz:print:read","biz:print:simulate","biz:print:export");
        ai.neargo.shop.auth.BizContext.set(new ai.neargo.shop.auth.BizContext(ref.entityNo(),java.util.Set.of(),java.util.Set.of(),
                java.util.Set.of(ref.storeNo()),ref.storeNo(),owner,java.util.Map.of(ref.storeNo(),java.util.Set.of("SOURCE-EMPLOYEE")),
                java.util.Map.of(ref.storeNo(),permissions)));
        return actorFactory.merchant(key);
    }
    private void receiptSoftwareFlow(PrintFactRef ref) throws Exception {
        var scope=new PrintScope(ref.tenantNo(),ref.entityNo(),ref.storeNo());
        var sourceNo=jdbc.queryForObject("select source_no from biz_print_fact where print_fact_no=?",String.class,ref.factNo());
        var source=new PrintSourceReference(ref.sourceType(),sourceNo,ref.factNo(),ref.bizRevision(),ref.scene());
        var command=new SimulationCommand(scope,ref.scene(),new SimulationSource(SimulationSourceType.FACT,null,ref,null),
                "STANDARD_58",SimulationFixtureCode.SUCCESS,new TemplateSelection(TemplateSelectionMode.SCENE_DEFAULT,"STANDARD_58",null),
                new PrintOutputOptions(false,PdfOutputProfile.ORIGINAL_ROLL));
        var restricted=employee(ref,false,id());
        assertThat(restricted.dataScopes()).contains("SOURCE_ORDER_READ").doesNotContain("SOURCE_RECHARGE_READ","SOURCE_REFUND_READ");
        assertThatThrownBy(()->simulationFacade.create(command,restricted)).isInstanceOf(PrintFailure.class);
        assertThatThrownBy(()->previews.get(ref.scene(),source,restricted)).isInstanceOf(PrintFailure.class);
        PrintActor sourceReader=null;
        if ("RECHARGE".equals(ref.sourceType())) {
            var walletPermissions=java.util.Set.of(ai.neargo.shop.auth.BizPerms.SV_RECHARGE_READ,ai.neargo.shop.auth.BizPerms.SV_ACCOUNT_READ,
                    "biz:print:read","biz:print:simulate","biz:print:export");
            ai.neargo.shop.auth.BizContext.set(new ai.neargo.shop.auth.BizContext(ref.entityNo(),java.util.Set.of(),java.util.Set.of(),
                    java.util.Set.of(ref.storeNo()),ref.storeNo(),false,java.util.Map.of(ref.storeNo(),java.util.Set.of("WALLET-EMPLOYEE")),
                    java.util.Map.of(ref.storeNo(),walletPermissions)));
            var wallet=actorFactory.merchant(id());
            assertThat(wallet.dataScopes()).contains("SOURCE_RECHARGE_READ").doesNotContain("SOURCE_ORDER_READ");
            assertThat(previews.get(ref.scene(),source,wallet).ref()).isEqualTo(ref);
            assertThat(printQueries.getOrderSummary(sourceNo,new ai.neargo.shop.print.api.dto.PrintJobDtos.GetOrderSummaryQuery(ref.storeNo()),wallet).sourceFacts()).contains(ref);
            assertThat(printQueries.getOrderSummary(sourceNo,new ai.neargo.shop.print.api.dto.PrintJobDtos.GetOrderSummaryQuery(ref.storeNo()),restricted).sourceFacts()).doesNotContain(ref);
            sourceReader=wallet;
        }
        var owner=employee(ref,true,id());
        var operation=simulationFacade.create(command,sourceReader==null?owner:sourceReader);
        var budget=new PrintWorkerBudget(20,java.time.Duration.ofSeconds(30),"business-source-test");
        assertThat(simulations.runNext(budget).succeeded()).isEqualTo(1);
        var view=simulationFacade.get(operation.targetNo(),owner); assertThat(view.status()).isEqualTo("PASSED");
        var export=new ExportCommand(scope,new ExportSource(ExportSourceType.SIMULATION,operation.targetNo(),view.revision()),PdfOutputProfile.ORIGINAL_ROLL);
        var exportOp=exportFacade.create(export,employee(ref,true,id()));
        assertThat(exports.generateNext(budget).succeeded()).isEqualTo(1);
        assertThat(exportFacade.get(exportOp.targetNo(),owner).status()).isEqualTo("READY");
        assertThatThrownBy(()->exportFacade.get(exportOp.targetNo(),restricted)).isInstanceOf(PrintFailure.class);
        assertThatThrownBy(()->exportFacade.download(exportOp.targetNo(),restricted)).isInstanceOf(PrintFailure.class);
        assertThatThrownBy(()->exportFacade.create(export,employee(ref,false,id()))).isInstanceOf(PrintFailure.class);
        assertThatThrownBy(()->simulationFacade.get(operation.targetNo(),restricted)).isInstanceOf(PrintFailure.class);
        var grant=exportFacade.download(exportOp.targetNo(),employee(ref,true,id()));
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(grant.url()).secure(true))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200); assertThat(response.getContentType()).isEqualTo("application/pdf");
        try(var pdf=org.apache.pdfbox.Loader.loadPDF(response.getContentAsByteArray())) {
            String text=new org.apache.pdfbox.text.PDFTextStripper().getText(pdf);
            // Narrow receipts may wrap a valid 64-character business reference over several lines.
            assertThat(text.replaceAll("\\s+", "")).contains(sourceNo);
        }
        assertThat(jdbc.queryForObject("select count(*) from prn_job where document_key=?",Long.class,ref.factNo()+":"+ref.scene())).isZero();
    }
    private static EntitlementContext ctx() { return new EntitlementContext("MAIN","OWNER",id(),"f".repeat(64)); }
    private static String id() { return UUID.randomUUID().toString().replace("-",""); }
}
