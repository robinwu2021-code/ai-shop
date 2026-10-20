package ai.neargo.shop.pay.svc;
import ai.neargo.shop.spi.entitlement.EntitlementSaleReservationPort.ReserveSale;
import ai.neargo.shop.spi.entitlement.EntitlementSaleReservationPort.ReleaseSale;
import ai.neargo.shop.spi.entitlement.EntitlementSaleReservationPort.SaleView;
import ai.neargo.shop.pay.client.PaySaleRequests;
import ai.neargo.shop.pay.client.PayReconciliationRequests;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import ai.neargo.shop.pay.client.PayOwnerRequests;
import ai.neargo.shop.pay.client.PayCapabilityRequests;
import ai.neargo.shop.spi.entitlement.EntitlementCapabilityPort;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceIdentity;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceSystem;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceKind;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.OwnerRef;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.OwnerSystem;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.MigrationState;
import ai.neargo.shop.pay.client.PayMarketingRequests;
import ai.neargo.shop.spi.entitlement.EntitlementMarketingFactsPort;
import ai.neargo.shop.spi.entitlement.EntitlementMarketingFactsPort.UsageEffects;
import ai.neargo.shop.spi.entitlement.EntitlementMarketingFactsPort.Attribution;
import ai.neargo.shop.spi.storedvalue.StoredValueCapabilityPort;
import ai.neargo.shop.pay.client.PayLifecycleRequests;
import ai.neargo.shop.pay.entitlement.dto.LifecycleViews.FreezeCommand;
import ai.neargo.shop.pay.entitlement.dto.LifecycleViews.FreezeView;
import ai.neargo.shop.pay.entitlement.dto.LifecycleViews.ExpireResult;
import ai.neargo.shop.pay.entitlement.dto.LifecycleViews.ReminderPage;
import ai.neargo.shop.pay.entitlement.dto.LifecycleViews.ExpiryReminder;
import ai.neargo.shop.pay.entitlement.service.EntitlementLifecycleService;
import ai.neargo.shop.pay.client.PayExceptionRequests;
import ai.neargo.shop.pay.entitlement.dto.ExceptionViews.ActionView;
import ai.neargo.shop.pay.entitlement.dto.ReconciliationViews.Check;
import ai.neargo.shop.pay.entitlement.dto.ReconciliationViews.RunView;
import ai.neargo.shop.pay.entitlement.dto.ReconciliationViews.DiffView;
import ai.neargo.shop.pay.entitlement.service.EntitlementReconciliationService;
import ai.neargo.shop.spi.entitlement.EntitlementSaleReservationPort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.pay.storedvalue.service.StoredValueRechargeService;
import ai.neargo.shop.pay.storedvalue.service.StoredValueCreditService;
import ai.neargo.shop.pay.client.PayStoredValueRequests;
import ai.neargo.shop.spi.storedvalue.StoredValuePort;
import ai.neargo.shop.spi.storedvalue.StoredValueRechargeFactsPort;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort.ParticipationPermit;
import ai.neargo.shop.common.CursorPage;
import ai.neargo.shop.common.PageData;
import java.util.Set;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.pay.client.PayInternalPaths;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import ai.neargo.shop.pay.client.PayEntitlementRequests;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.OperatorEntityScope;
import ai.neargo.shop.pay.entitlement.dto.CodeViews;
import ai.neargo.shop.pay.entitlement.dto.IssueBatchViews;
import ai.neargo.shop.pay.entitlement.service.EntitlementCodeService;
import ai.neargo.shop.pay.entitlement.service.EntitlementIssueBatchService;
import ai.neargo.shop.spi.entitlement.DecisionRef;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort;
import ai.neargo.shop.spi.entitlement.EntitlementUseFactsPort;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort;
import ai.neargo.shop.spi.entitlement.PaymentAttemptFactsPort;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.svc.ConfigServiceLocator;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Actual client/server HTTP, with only owner business functions substituted to isolate transport assertions. */
@SpringBootTest(classes = PayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "shop.services.internal-token=tk")
@ActiveProfiles("svctest")
class EntitlementRuntimeInternalApiRoundTripTest {
    @LocalServerPort int port;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
    @MockitoBean StoredValueRechargeService recharges;
    @MockitoBean StoredValueCapabilityPort storedValueCapabilities;
    @MockitoBean EntitlementCapabilityPort entitlementCapabilities;
    @MockitoBean StoredValueCreditService credits;
    @MockitoBean ai.neargo.shop.pay.storedvalue.service.StoredValueQueryService storedValueQueries;
    @MockitoBean ai.neargo.shop.pay.storedvalue.service.StoredValueRedemptionService storedValueRedemptions;
    @MockitoBean StoredValueRechargeFactsPort rechargeFacts;
    @MockitoBean EntitlementGrantPort grants;
    @MockitoBean ai.neargo.shop.pay.entitlement.service.EntitlementExceptionService exceptions;
    @MockitoBean ai.neargo.shop.pay.entitlement.service.EntitlementExceptionRecoveryService exceptionRecovery;
    @MockitoBean ai.neargo.shop.pay.migration.service.EntitlementMigrationService migration;
    @MockitoBean ai.neargo.shop.pay.migration.service.EntitlementMigrationPlanService migrationPlans;
    @MockitoBean ai.neargo.shop.pay.migration.service.LegacyCouponMigrationImportService migrationImports;
    @MockitoBean EntitlementSaleReservationPort sales;
    @MockitoBean EntitlementReconciliationService reconciliation;
    @MockitoBean EntitlementLifecycleService lifecycle;
    @MockitoBean EntitlementMarketingFactsPort marketing;
    @MockitoBean EntitlementOwnerRegistryPort owners;
    @MockitoBean ai.neargo.shop.pay.migration.service.LegacyCouponUseService legacyCoupons;
    @MockitoBean ai.neargo.shop.pay.migration.service.LegacyCouponHistoricalReturnService historicalReturns;
    @MockitoBean ai.neargo.shop.pay.migration.service.LegacyCouponTemplateManagementService legacyTemplates;
    @MockitoBean ai.neargo.shop.pay.migration.service.LegacyCouponIssueService legacyIssues;
    @MockitoBean EntitlementUsePort uses;
    @MockitoBean EntitlementQueryPort queries;
    @MockitoBean EntitlementUseFactsPort facts;
    @MockitoBean PaymentAttemptFactsPort attempts;
    @MockitoBean EntitlementCodeService codes;
    @MockitoBean EntitlementIssueBatchService batches;
    private final EntitlementContext ctx = new EntitlementContext("MAIN", "U1", "RPC-1", "a".repeat(64));
    private final SourceRef source = new SourceRef("CORE", "ORDER_USE", "SO1", null, null, null, 1);
    private final Instant at = Instant.parse("2026-10-19T01:00:00Z");
    private final List<EntitlementUsePort.UseItem> items =
            List.of(new EntitlementUsePort.UseItem("L1", "G1", "Care", 1, 1000));
    private PayInternalApi api(String token) {
        var locator = new ConfigServiceLocator();
        locator.getTargets().put(ServiceName.PAY, "http://127.0.0.1:" + port);
        return new InternalHttp(locator, token).client(ServiceName.PAY, PayInternalApi.class, Duration.ofSeconds(5));
    }
    @Test void issuanceRoundTripsOriginalCommandsAndSourceIdentity() {
        var src = new SourceRef("PAY", "TARGETED", "B1", "1", null, null, 0);
        var c = new EntitlementGrantPort.GrantCommand("G1", src, null, "V1", "U1", "M1", 1, "a".repeat(64), null);
        var commit = new EntitlementGrantPort.GrantDecision("G1", src,
                new DecisionRef("GC", at, 0, DecisionRef.DecisionType.GRANT_COMMIT));
        var cancel = new EntitlementGrantPort.AbortGrant("G1", src,
                new DecisionRef("GA", at, 0, DecisionRef.DecisionType.GRANT_ABORT));
        var prepared = new EntitlementGrantPort.GrantView("G1", EntitlementGrantPort.State.PREPARED, List.of(), src);
        var issued = new EntitlementGrantPort.GrantView("G1", EntitlementGrantPort.State.COMMITTED, List.of("I1"), src);
        var aborted = new EntitlementGrantPort.GrantView("G1", EntitlementGrantPort.State.ABORTED, List.of(), src);
        when(grants.prepare(ctx, c)).thenReturn(prepared); when(grants.commit(ctx, commit)).thenReturn(issued);
        when(grants.abort(ctx, cancel)).thenReturn(aborted); when(grants.query(ctx, "M1", "G1")).thenReturn(issued);
        var api = api("tk");
        assertThat(api.entGrantPrepare(new PayEntitlementRequests.GrantPrepare(ctx, c))).isEqualTo(prepared);
        assertThat(api.entGrantCommit(new PayEntitlementRequests.GrantCommit(ctx, commit))).isEqualTo(issued);
        assertThat(api.entGrantAbort(new PayEntitlementRequests.GrantAbort(ctx, cancel))).isEqualTo(aborted);
        assertThat(api.entGrantQuery(new PayEntitlementRequests.GrantQuery(ctx, "M1", "G1"))).isEqualTo(issued);
    }
    private EntitlementUsePort.UseCommand command() {
        return new EntitlementUsePort.UseCommand("OP1", source, "U1", "M1", "S1", "CNY",
                EntitlementUsePort.AssetKind.STORE_EXCHANGE, "I1", null, null, 1, null, items, "A1",
                        null, 0, "P1", null);
    }
    @Test void quotesPreviewsAndAllUseTransitionsKeepFrozenFields() {
        var q = new EntitlementUsePort.QuoteCommand(source, "U1", "M1", "S1", "CNY", 1000, 0, items,
                List.of("I1"), "a".repeat(64));
        var quote = new EntitlementUsePort.QuoteView("Q1", source, "b".repeat(64), 100, "CNY",
                List.of(new EntitlementUsePort.CouponAllocation("I1", "L1", 100)), at.plusSeconds(60));
        var p = new EntitlementUsePort.PreviewQuery("U1", "M1", "S1", "CNY", "I1", null, 1, source);
        var preview = new EntitlementUsePort.PreviewView("P1", "I1", "Care", "U**", true,
                EntitlementUsePort.PreviewReason.OK, 1, 2, 0, "b".repeat(64), at.plusSeconds(60));
        var commit = new EntitlementUsePort.UseDecision("OP1", source,
                new DecisionRef("UC", at, 1, DecisionRef.DecisionType.USE_COMMIT));
        var cancel = new EntitlementUsePort.CancelUse("OP1", source,
                new DecisionRef("UA", at, 1, DecisionRef.DecisionType.USE_CANCEL));
        var held = new EntitlementUsePort.HoldView("OP1", EntitlementUsePort.State.HELD, "OP1", null, at);
        var receipt = new EntitlementUsePort.UseReceipt("OP1", EntitlementUsePort.State.COMMITTED, "US1", at);
        var released = new EntitlementUsePort.HoldView("OP1", EntitlementUsePort.State.RELEASED, "OP1", null, at);
        when(uses.quote(ctx, q)).thenReturn(quote); when(uses.preview(ctx, p)).thenReturn(preview);
        when(uses.prepare(ctx, command())).thenReturn(held); when(uses.commit(ctx, commit)).thenReturn(receipt);
        when(uses.release(ctx, cancel)).thenReturn(released); when(uses.query(ctx, "M1", "OP1")).thenReturn(held);
        var api = api("tk");
        assertThat(api.entQuote(new PayEntitlementRequests.UseQuote(ctx, q))).isEqualTo(quote);
        assertThat(api.entPreview(new PayEntitlementRequests.UsePreview(ctx, p))).isEqualTo(preview);
        assertThat(api.entUsePrepare(new PayEntitlementRequests.UsePrepare(ctx, command()))).isEqualTo(held);
        assertThat(api.entUseCommit(new PayEntitlementRequests.UseCommit(ctx, commit))).isEqualTo(receipt);
        assertThat(api.entUseRelease(new PayEntitlementRequests.UseRelease(ctx, cancel))).isEqualTo(released);
        assertThat(api.entUseQuery(new PayEntitlementRequests.UseQuery(ctx, "M1", "OP1"))).isEqualTo(held);
    }
    @Test void reverseUsesItsOwnQueryNamespace() {
        var refund = new SourceRef("CORE", "ORDER_REFUND", "AS1", "US1", null, null, 1);
        var c = new EntitlementUsePort.OrderBenefitReverse("US1", "RV1", refund,
                EntitlementUsePort.AssetKind.ORDER_COUPON, 100L,
                new DecisionRef("R1", at, 1, DecisionRef.DecisionType.ORDER_REVERSE));
        var receipt = new EntitlementUsePort.UseReceipt("RV1", EntitlementUsePort.State.COMMITTED, "US1", at);
        var view = new EntitlementUsePort.ReversalView("RV1", EntitlementUsePort.State.COMMITTED, "US1", at);
        when(uses.reverse(ctx, c)).thenReturn(receipt); when(uses.queryReverse(ctx, "M1", "RV1")).thenReturn(view);
        assertThat(api("tk").entReverse(new PayEntitlementRequests.UseReverse(ctx, c))).isEqualTo(receipt);
        assertThat(api("tk").entReverseQuery(new PayEntitlementRequests.ReverseQuery(ctx, "M1", "RV1")))
                .isEqualTo(view);
    }
    @Test void migrationImportReceiptIsTypedMetadataAndNotANewGrant() {
        var scope=new ai.neargo.shop.pay.entitlement.dto.CatalogScope("MAIN","M1","OP1",true);
        var receipt=new ai.neargo.shop.pay.migration.dto.MigrationImportViews.ImportView("MI1","MP1","M1","MD1","a".repeat(64),
                "IMPORTED",1,2,false,Instant.parse("2026-10-20T00:00:00Z"));
        when(migrationImports.importPlan(scope,"MP1","import plan","IK1")).thenReturn(receipt);
        var request=new ai.neargo.shop.pay.client.PayMigrationRequests.Import(scope,
                new ai.neargo.shop.pay.migration.dto.MigrationImportViews.ImportCommand("MP1","import plan"),"IK1");
        var get=new ai.neargo.shop.pay.client.PayMigrationRequests.PlanGet(scope,"MP1");
        when(migrationImports.get(scope,"MP1")).thenReturn(receipt);
        assertThat(api("tk").migrationImport(request)).isEqualTo(receipt);
        assertThat(api("tk").migrationImportGet(get)).isEqualTo(receipt);
        assertThatThrownBy(()->api("wrong").migrationImport(request)).isInstanceOf(ServiceCallException.class);
        assertThatThrownBy(()->api("wrong").migrationImportGet(get)).isInstanceOf(ServiceCallException.class);
    }
    @Test void fixedMigrationPlanMetadataRoundTripsWithoutRecipientsOrImportAuthority() {
        var scope=new ai.neargo.shop.pay.entitlement.dto.CatalogScope("MAIN","M1","OP1",true);
        var at=Instant.parse("2026-10-20T00:00:00Z");
        var plan=new ai.neargo.shop.pay.migration.dto.MigrationPlanViews.PlanView("MP1","MS1","M1","B1","PLANNED",
                "a".repeat(64),"b".repeat(64),3,1,2,at,false,0);
        when(migrationPlans.prepare(scope,"MS1","fixed plan","K1")).thenReturn(plan);
        var command=new ai.neargo.shop.pay.migration.dto.MigrationPlanViews.PlanCommand("MS1","fixed plan");
        var request=new ai.neargo.shop.pay.client.PayMigrationRequests.Plan(scope,command,"K1");
        var get=new ai.neargo.shop.pay.client.PayMigrationRequests.PlanGet(scope,"MP1");
        when(migrationPlans.get(scope,"MP1")).thenReturn(plan);when(migrationPlans.verify(scope,"MP1")).thenReturn(plan);
        var prepared=new ai.neargo.shop.spi.entitlement.LegacyCouponMigrationPlan(1,"MAIN","MP1","M1","B1","a".repeat(64),"b".repeat(64),
                List.of(new ai.neargo.shop.spi.entitlement.LegacyCouponMigrationPlan.Source("MS1","PMT","T1","B1",2,"c".repeat(64),"d".repeat(64))));
        when(migrationPlans.prepared(scope,"MP1")).thenReturn(prepared);
        assertThat(api("tk").migrationPlanPrepared(get)).isEqualTo(prepared);
        assertThatThrownBy(()->api("wrong").migrationPlanPrepared(get)).isInstanceOf(ServiceCallException.class);
        assertThat(api("tk").migrationPlan(request)).isEqualTo(plan);
        assertThat(api("tk").migrationPlanGet(get)).isEqualTo(plan);
        assertThat(api("tk").migrationPlanVerify(get)).isEqualTo(plan);
        assertThatThrownBy(()->api("wrong").migrationPlan(request)).isInstanceOf(ServiceCallException.class);
        assertThatThrownBy(()->api("wrong").migrationPlanGet(get)).isInstanceOf(ServiceCallException.class);
        assertThatThrownBy(()->api("wrong").migrationPlanVerify(get)).isInstanceOf(ServiceCallException.class);
    }
    @Test void queryDtoWhitelistKeepsUnitsDatesAndCursorMetadata() {
        var q = new EntitlementQueryPort.AssetQuery("U1", "M1", EntitlementUsePort.AssetKind.STORE_EXCHANGE,
                null, null, 20);
        var d = new EntitlementQueryPort.DetailQuery("U1", "M1", "I1", "S1");
        var u = new EntitlementQueryPort.UsageQuery("U1", "M1", "US1");
        var a = new EntitlementQueryPort.AllowanceQuery("U1", "M1", "I1", LocalDate.of(2026, 10, 19));
        var item = new EntitlementQueryPort.EntitlementItem("I1", "Care", EntitlementUsePort.AssetKind.STORE_EXCHANGE,
                EntitlementQueryPort.AssetState.ACTIVE, 3, null, at, at.plusSeconds(3600), "S1", "TARGETED");
        var page = new CursorPage<>(List.of(item), "NEXT");
        var detail = new EntitlementQueryPort.EntitlementDetail(item, "Use at store", List.of());
        var usage = new EntitlementQueryPort.UsageDetail(new EntitlementQueryPort.UsageItem("US1",
                EntitlementUsePort.AssetKind.STORE_EXCHANGE, "Care", null, "CNY", 1, "S1", "Care store", at,
                "COMMITTED"), items, null, "O1", 2);
        var allowance = new EntitlementQueryPort.AllowanceView("I1", a.serviceDate(), "Asia/Shanghai", 3, 2, 0, 0, 2);
        var activation = new EntitlementQueryPort.ActivationQuote("I1", "GRANTED", "ROLLING_DAYS", at,
                at.plusSeconds(3600), EntitlementQueryPort.ActivationAction.NONE);
        when(queries.mine(ctx, q)).thenReturn(page); when(queries.detail(ctx, d)).thenReturn(detail);
        when(queries.usage(ctx, u)).thenReturn(usage); when(queries.allowances(ctx, a)).thenReturn(allowance);
        when(queries.activationQuote(ctx, d)).thenReturn(activation);
        var historyQuery=new EntitlementQueryPort.UsageHistoryQuery("U1","M1","I1",null,20);
        var history=new CursorPage<EntitlementQueryPort.UsageItem>(List.of(usage.usage()),"signed-next");
        when(queries.history(ctx,historyQuery)).thenReturn(history);
        var api = api("tk");
        assertThat(api.entHistory(new PayEntitlementRequests.UsageHistory(ctx,historyQuery))).isEqualTo(history);
        assertThatThrownBy(()->api("wrong").entHistory(new PayEntitlementRequests.UsageHistory(ctx,historyQuery)))
                .isInstanceOf(ServiceCallException.class);
        assertThat(api.entMine(new PayEntitlementRequests.Mine(ctx, q))).isEqualTo(page);
        assertThat(api.entDetail(new PayEntitlementRequests.Detail(ctx, d))).isEqualTo(detail);
        assertThat(api.entUsage(new PayEntitlementRequests.Usage(ctx, u))).isEqualTo(usage);
        assertThat(api.entAllowances(new PayEntitlementRequests.Allowances(ctx, a))).isEqualTo(allowance);
        assertThat(api.entActivation(new PayEntitlementRequests.Activation(ctx, d))).isEqualTo(activation);
    }
    @Test void merchantRecordsPreserveExactStoreScopeDatesAndMoneyUnitFields() {
        var query = new EntitlementQueryPort.BizUsageQuery("M1", Set.of("S1"), null,
                EntitlementUsePort.AssetKind.PAID_CARD, at, at.plusSeconds(3600), 1, 10);
        var item = new EntitlementQueryPort.UsageItem("US1", EntitlementUsePort.AssetKind.PAID_CARD, "swimming",
                null, null, 1, "S1", "store", at, "COMMITTED");
        var page = new PageData<>(List.of(item), 1, 1, 10);
        when(queries.merchantUsage(ctx, query)).thenReturn(page);
        assertThat(api("tk").entMerchantUsage(new PayEntitlementRequests.MerchantUsage(ctx, query))).isEqualTo(page);
        var empty = new EntitlementQueryPort.BizUsageQuery("M1", Set.of(), null, null, null, null, 1, 10);
        var noRecords = PageData.<EntitlementQueryPort.UsageItem>empty(1, 10);
        when(queries.merchantUsage(ctx, empty)).thenReturn(noRecords);
        assertThat(api("tk").entMerchantUsage(new PayEntitlementRequests.MerchantUsage(ctx, empty))).isEqualTo(noRecords);
        var all = new EntitlementQueryPort.BizUsageQuery("M1", null, null, null, null, null, 1, 10);
        when(queries.merchantUsage(ctx, all)).thenReturn(page);
        assertThat(api("tk").entMerchantUsage(new PayEntitlementRequests.MerchantUsage(ctx, all))).isEqualTo(page);
    }
    @Test void batchAndCredentialRequestsRetainOwnerScopeAndOriginalKeys() {
        var scope = new CatalogScope("MAIN", "M1", "B1", false);
        var c = new IssueBatchViews.CreateBatch("V1", "SEG1");
        var batch = new IssueBatchViews.BatchView("B1", "PROCESSING", 2, 0, 0, 0, 2, List.of());
        when(batches.create(scope, c, "BK1")).thenReturn(batch); when(batches.get(scope, "B1")).thenReturn(batch);
        when(batches.resume(scope, "B1", 10)).thenReturn(batch);
        var request = new CodeViews.CodeRequest("STORE_EXCHANGE");
        var code = new CodeViews.CodeView("ec1_" + "a".repeat(43), at.plusSeconds(60), "I1");
        var resolved = new CodeViews.ResolvedCode("U1", "I1", code.expiresAt());
        when(codes.issue(ctx, "U1", "I1", request, "CK1")).thenReturn(code);
        when(codes.resolve(ctx, "M1", "S1", code.token(), "OP1")).thenReturn(resolved);
        when(codes.invalidate(ctx, "U1", "I1")).thenReturn(1);
        var api = api("tk");
        assertThat(api.entBatchCreate(new PayEntitlementRequests.BatchCreate(scope, c, "BK1"))).isEqualTo(batch);
        assertThat(api.entBatchGet(new PayEntitlementRequests.BatchGet(scope, "B1"))).isEqualTo(batch);
        assertThat(api.entBatchResume(new PayEntitlementRequests.BatchResume(scope, "B1", 10))).isEqualTo(batch);
        assertThat(api.entCodeIssue(new PayEntitlementRequests.CodeIssue(ctx, "U1", "I1", request, "CK1")))
                .isEqualTo(code);
        assertThat(api.entCodeResolve(new PayEntitlementRequests.CodeResolve(ctx, "M1", "S1", code.token(), "OP1")))
                .isEqualTo(resolved);
        assertThat(api.entCodeInvalidate(new PayEntitlementRequests.CodeInvalidate(ctx, "U1", "I1")).revokedCount())
                .isOne();
        assertThat(new PayEntitlementRequests.CodeResolve(ctx, "M1", "S1", code.token(), "OP1").toString())
                .doesNotContain(code.token());
    }
    @Test void internalOwnerFactsRoundTripWithoutExposingThemAsPublicDto() {
        var rule = new EntitlementDefinition("Care", "STORE_EXCHANGE", "MERCHANT", "CNY", "SERVICE_UNITS",
                null, null, null, null, null, "COUNT", 3, null,
                new EntitlementDefinition.Validity("RELATIVE", null, null, 30, "GRANTED", "ROLLING_DAYS", "UTC"),
                new EntitlementDefinition.RedemptionScope("ALL", List.of()), List.of("TARGETED"));
        var held = new EntitlementUsePort.HoldView("OP1", EntitlementUsePort.State.HELD, "OP1", null, at);
        var fact = new EntitlementUseFactsPort.UseFact(held, command(), rule, List.of(), "a".repeat(64));
        when(facts.query(ctx, "M1", "OP1")).thenReturn(fact); when(attempts.hasAnyAttempt("MAIN",
                "O1")).thenReturn(true);
        assertThat(api("tk").entUseFacts(new PayEntitlementRequests.UseFacts(ctx, "M1", "OP1"))).isEqualTo(fact);
        assertThat(api("tk").entPaymentAttempts(new PayEntitlementRequests.PaymentAttempts(ctx, "O1")))
                .satisfies(v -> {
                    assertThat(v.tenantNo()).isEqualTo("MAIN"); assertThat(v.orderNo()).isEqualTo("O1");
                    assertThat(v.hasAnyAttempt()).isTrue();
                });
    }
    @Test void unauthorizedAndFailedRequestsAreNeverRetriedOrConvertedIntoSuccess() {
        var q = new PayEntitlementRequests.UseQuery(ctx, "M1", "OP1");
        assertStatus(401, () -> api("wrong").entUseQuery(q));
        when(uses.query(ctx, "M1", "OP1")).thenThrow(BizException.of(ErrorCode.CONFLICT));
        assertStatus(409, () -> api("tk").entUseQuery(q));
        verify(uses, times(1)).query(ctx, "M1", "OP1");
        doThrow(BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE)).when(uses).query(ctx, "M1", "OP1");
        assertStatus(503, () -> api("tk").entUseQuery(q));
    }
    @Test void fractionalMoneyCannotBeCoercedIntoAnIntegerBeforeOwnerValidation() {
        var q = new EntitlementUsePort.QuoteCommand(source, "U1", "M1", "S1", "CNY", 1000, 0, items,
                List.of("I1"), "a".repeat(64));
        String body = new ObjectMapper().writeValueAsString(new PayEntitlementRequests.UseQuote(ctx, q))
                .replace("\"goodsAmountMinor\":1000", "\"goodsAmountMinor\":1000.5")
                .replace("\"amountMinor\":1000", "\"amountMinor\":1000.5");
        assertThat(body).contains("1000.5");
        var client = RestClient.create("http://127.0.0.1:" + port);
        assertThatThrownBy(() -> client.post().uri(PayInternalPaths.ENT_USE_QUOTE)
                .header(InternalHttp.TOKEN_HEADER, "tk").contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().toBodilessEntity()).isInstanceOfSatisfying(RestClientResponseException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
        verifyNoInteractions(uses);
    }
    private void assertStatus(int expected, ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ServiceCallException.class,
                e -> assertThat(e.statusCode()).isEqualTo(expected));
    }
    @Test void storedValueCommandsAndFactsHaveSharedAuthenticatedHttpContracts() {
        var src = new SourceRef("CORE", "RECHARGE", "RI1", null, null, null, 0);
        var permit = new ParticipationPermit("AR1", src, "A1", "AV1", "T1", "U1", "M1", "S1", "CNY",
                1000L, 100L, List.of(), new EntitlementDefinition.RedemptionScope("STORES", List.of("S1")),
                "a".repeat(64), at);
        var create = new StoredValuePort.CreateRecharge(src, permit);
        var submit = new StoredValuePort.SubmitRecharge("RC1", 0, 1000, StoredValuePort.CollectionMethod.TRANSFER,
                "PRIVATE-RECEIPT", "b".repeat(64));
        var receive = new StoredValuePort.ReceiveRecharge("RC1", 1, "b".repeat(64), "private human verification");
        var cancel = new StoredValuePort.CancelRecharge("RC1", 0, "unreceived");
        var paySource = new SourceRef("PAY", "RECHARGE", "RC1", null, null, null, 0);
        var decision = new DecisionRef("RD1", at, 0, DecisionRef.DecisionType.RECHARGE_RECEIVED);
        var credit = new StoredValuePort.CreditRecharge("RC1", paySource, decision);
        var view = new StoredValuePort.RechargeView("RC1", StoredValuePort.RechargeState.RECEIVED, "U1", "M1", "S1",
                "CNY", 1000L, 1100L, at, null, 2);
        when(recharges.create(ctx, "M1", create)).thenReturn(view);
        when(recharges.submit(ctx, "M1", submit)).thenReturn(view);
        when(recharges.receive(ctx, "M1", receive)).thenReturn(view);
        when(recharges.cancel(ctx, "M1", cancel)).thenReturn(view);
        when(credits.credit(ctx, credit)).thenReturn(view);
        var fact = new StoredValueRechargeFactsPort.RechargeFact("MAIN", view, src, permit, "U1", decision);
        when(rechargeFacts.query(ctx, "M1", "RC1")).thenReturn(fact);
        var api = api("tk");
        assertThat(api.svCreate(new PayStoredValueRequests.Create(ctx, "M1", create))).isEqualTo(view);
        assertThat(api.svSubmit(new PayStoredValueRequests.Submit(ctx, "M1", submit))).isEqualTo(view);
        assertThat(api.svReceive(new PayStoredValueRequests.Receive(ctx, "M1", receive))).isEqualTo(view);
        assertThat(api.svCancel(new PayStoredValueRequests.Cancel(ctx, "M1", cancel))).isEqualTo(view);
        assertThat(api.svCredit(new PayStoredValueRequests.Credit(ctx, credit))).isEqualTo(view);
        assertThat(api.svFacts(new PayStoredValueRequests.Query(ctx, "M1", "RC1"))).isEqualTo(fact);
        verify(recharges).receive(ctx, "M1", receive);
    }
    @Test void storedValueQueriesAndPricedServiceQuoteRoundTripThroughRealHttp() {
        var balanceQuery = new StoredValuePort.BalanceQuery("U1", "M1", "CNY", "S1");
        var balance = new StoredValuePort.BalanceView("ACC1", "CNY", 1200, 200, 1000, null);
        when(storedValueQueries.balance(ctx, balanceQuery)).thenReturn(balance);
        var storeQuery = new StoredValuePort.StoreQuery("U1", null, 20);
        var stores = new ai.neargo.shop.common.CursorPage<StoredValuePort.StoreItem>(List.of(
                new StoredValuePort.StoreItem("S1", "M1", "Care store", "UNVERIFIED",
                        StoredValuePort.StoreActivityType.CONSUMPTION, at)), null);
        when(storedValueQueries.stores(ctx, storeQuery)).thenReturn(stores);
        var usageQuery = new StoredValuePort.ConsumptionQuery("U1", "M1", "S1", null, 20);
        var usages = new ai.neargo.shop.common.CursorPage<EntitlementQueryPort.UsageItem>(List.of(
                new EntitlementQueryPort.UsageItem("USE1", EntitlementUsePort.AssetKind.STORED_VALUE,
                        "Care", 1000L, "CNY", null, "S1", "Care store", at, "COMMITTED")), null);
        when(storedValueQueries.consumptions(ctx, usageQuery)).thenReturn(usages);
        var src = new SourceRef("CORE", "SERVICE_USE", "SERVICE1", null, null, null, 0);
        var quote = new ai.neargo.shop.spi.storedvalue.StoredValueUseQuotePort.Quote("SQ1", src, "ACC1", "CNY",
                1000, 1000, true, ai.neargo.shop.spi.storedvalue.StoredValueUseQuotePort.Reason.OK,
                1, "a".repeat(64), at);
        when(storedValueRedemptions.quote(ctx, src)).thenReturn(quote);
        var api = api("tk");
        assertThat(api.svBalance(new PayStoredValueRequests.Balance(ctx, balanceQuery))).isEqualTo(balance);
        assertThat(api.svStores(new PayStoredValueRequests.Stores(ctx, storeQuery))).isEqualTo(stores);
        assertThat(api.svConsumptions(new PayStoredValueRequests.Consumptions(ctx, usageQuery))).isEqualTo(usages);
        var allStores = new StoredValuePort.ConsumptionQuery("U1", "M1", null, null, 20);
        when(storedValueQueries.consumptions(ctx, allStores)).thenReturn(usages);
        assertThat(api.svConsumptions(new PayStoredValueRequests.Consumptions(ctx, allStores))).isEqualTo(usages);
        assertThat(api.svUseQuote(new PayStoredValueRequests.UseQuote(ctx, src))).isEqualTo(quote);
        assertStatus(401, () -> api("wrong").svBalance(new PayStoredValueRequests.Balance(ctx, balanceQuery)));
    }
    @Test void storedValueMoneyCannotBeFractionalAndWrongServiceTokenNeverReachesOwner() {
        assertThatThrownBy(() -> api("wrong").svReceive(new PayStoredValueRequests.Receive(ctx, "M1",
                new StoredValuePort.ReceiveRecharge("RC1", 1, "b".repeat(64), null))))
                .isInstanceOf(ServiceCallException.class);
        verifyNoInteractions(recharges);
        var raw = "{\"context\":" + new ObjectMapper().writeValueAsString(ctx)
                + ",\"entityNo\":\"M1\",\"command\":{\"rechargeNo\":\"RC1\",\"expectedRevision\":0,"
                + "\"actualAmountMinor\":1000.5,\"collectionMethod\":\"CASH\",\"receiptRef\":\"R1\","
                + "\"receiptDigest\":\"" + "a".repeat(64) + "\"}}";
        assertThatThrownBy(() -> RestClient.create("http://127.0.0.1:" + port).post()
                .uri(PayInternalPaths.SV_SUBMIT).header(InternalHttp.TOKEN_HEADER, "tk")
                .contentType(MediaType.APPLICATION_JSON).body(raw).retrieve().toBodilessEntity())
                .isInstanceOfSatisfying(RestClientResponseException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
        verifyNoInteractions(recharges);
    }
    @Test void storedValueMissingOwnerReplyNeverMeansReceivedOrCancelled() {
        assertThatThrownBy(() -> api("tk").svCancel(new PayStoredValueRequests.Cancel(ctx, "M1",
                new StoredValuePort.CancelRecharge("RC1", 0, "cancel"))))
                .isInstanceOf(ServiceCallException.class);
        assertThatThrownBy(() -> api("tk").svFacts(new PayStoredValueRequests.Query(ctx, "M1", "RC1")))
                .isInstanceOf(ServiceCallException.class);
    }


    @Test void saleReservationRoundTripsAndRejectsUnauthenticatedWrites() {
        var src = new SourceRef("CORE", "PAID_CARD_ORDER", "O1", "L1", 1, 1, 0);
        var command = new ReserveSale(
                ReserveSale.number(src),
                src, "V1", "U1", "M1", "a".repeat(64));
        var view = new SaleView(
                command.reservationNo(),src,
                EntitlementSaleReservationPort.State.RESERVED,null);
        when(sales.reserve(ctx,command)).thenReturn(view);
        var request = new PaySaleRequests.Reserve(ctx,command);
        assertThat(api("tk").saleReserve(request)).isEqualTo(view);
        assertThatThrownBy(() -> api("wrong").saleReserve(request)).isInstanceOf(ServiceCallException.class);
        verify(sales,times(1)).reserve(ctx,command);
        when(sales.query(ctx,"M1",command.reservationNo())).thenReturn(view);
        assertThat(api("tk").saleQuery(new PaySaleRequests.Query(
                ctx,"M1",command.reservationNo()))).isEqualTo(view);
        var release = new ReleaseSale(command,
                new DecisionRef("C1",at,0,DecisionRef.DecisionType.GRANT_ABORT));
        var released = new SaleView(
                command.reservationNo(),src,
                EntitlementSaleReservationPort.State.RELEASED,null);
        when(sales.release(ctx,release)).thenReturn(released);
        assertThat(api("tk").saleRelease(new PaySaleRequests.Release(ctx,release)))
                .isEqualTo(released);
    }
    @Test void exceptionReadAndOriginalGrantRetryRoundTripWithoutChangingScope() {
        var scope=new CatalogScope("MAIN","M1","OP1",true);
        var evidence=new ai.neargo.shop.pay.entitlement.dto.ExceptionViews.Evidence("G1","PREPARED","PAID_CARD_ORDER","DELIVERY_FAILED");
        var view=new ai.neargo.shop.pay.entitlement.dto.ExceptionViews.ExceptionView("EX1","M1","GRANT_DELIVERY", "G1","OPEN",evidence,null,0);
        when(exceptions.get(scope,"EX1")).thenReturn(view);
        when(exceptions.list(scope,null,"OPEN",1,20)).thenReturn(new PageData<>(List.of(view),1,1,20));
        assertThat(api("tk").exceptionGet(new ai.neargo.shop.pay.client.PayExceptionRequests.GetException(scope,"EX1"))).isEqualTo(view);
        assertThat(api("tk").exceptionsList(new ai.neargo.shop.pay.client.PayExceptionRequests.ListExceptions(scope,null,"OPEN",1,20))
                .records()).containsExactly(view);
        var command=new ai.neargo.shop.pay.entitlement.dto.ExceptionViews.RecoveryCommand(0,"核实原单后恢复");
        when(exceptionRecovery.retryGrant(scope,"EX1",command,"K1")).thenReturn(view);
        var retry=new ai.neargo.shop.pay.client.PayExceptionRequests.RetryGrant(scope,"EX1",command,"K1");
        assertThat(api("tk").exceptionRetry(retry)).isEqualTo(view);
        assertThatThrownBy(() -> api("wrong").exceptionRetry(retry)).isInstanceOf(ServiceCallException.class);
        verify(exceptionRecovery,times(1)).retryGrant(scope,"EX1",command,"K1");
        when(exceptionRecovery.querySource(scope,"EX1",command,"QUERY1")).thenReturn(view);
        assertThat(api("tk").exceptionSource(new ai.neargo.shop.pay.client.PayExceptionRequests.RetryGrant(
                scope,"EX1",command,"QUERY1"))).isEqualTo(view);
        verify(exceptionRecovery).querySource(scope,"EX1",command,"QUERY1");
    }
    @Test void eventRecoveryAndGlobalDeadMetadataUseAuthenticatedTypedTransport() {
        var scope=new CatalogScope("MAIN","M1","OP1",true);
        var command=new ai.neargo.shop.pay.entitlement.dto.ExceptionViews.RecoveryCommand(0,"retry original event");
        var request=new ai.neargo.shop.pay.client.PayExceptionRequests.RetryGrant(scope,"EX-EVENT",command,"EK1");
        var view=new ai.neargo.shop.pay.entitlement.dto.ExceptionViews.ExceptionView("EX-EVENT","M1","OUTBOX_DELIVERY",
                "EV1","PROCESSING",new ai.neargo.shop.pay.entitlement.dto.ExceptionViews.Evidence(null,null,null,
                "BRIDGE_RETRIES_EXHAUSTED",null,new ai.neargo.shop.pay.entitlement.dto.ExceptionViews.DeliveryEvidence(
                        "EV1","ENTITLEMENT_GRANTED","PENDING",0)),"RETRY_EVENT",1);
        when(exceptionRecovery.retryEvent(scope,"EX-EVENT",command,"EK1")).thenReturn(view);
        assertThat(api("tk").exceptionRetryEvent(request)).isEqualTo(view);
        assertThatThrownBy(() -> api("wrong").exceptionRetryEvent(request)).isInstanceOf(ServiceCallException.class);
        verify(exceptionRecovery,times(1)).retryEvent(scope,"EX-EVENT",command,"EK1");
        var all=new OperatorEntityScope("MAIN","OP1",null);
        var result=new ai.neargo.shop.pay.entitlement.dto.ExceptionViews.DeadLetterPage(List.of(),null);
        when(exceptionRecovery.deadLetters(all,null,20)).thenReturn(result);
        assertThat(api("tk").exceptionDeadLetters(new ai.neargo.shop.pay.client.PayExceptionRequests.DeadLetters(all,null,20)))
                .isEqualTo(result);
        assertThatThrownBy(() -> api("wrong").exceptionDeadLetters(
                new ai.neargo.shop.pay.client.PayExceptionRequests.DeadLetters(all,null,20))).isInstanceOf(ServiceCallException.class);
        verify(exceptionRecovery,times(1)).deadLetters(all,null,20);
    }
    @Test void sourceEvidenceStagingUsesTypedScopedTokenProtectedTransport() {
        var scope=new CatalogScope("MAIN","M1","OP1",true);
        var query=new ai.neargo.shop.spi.entitlement.LegacyCouponFrozenSnapshotPort.Query(
                new ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceIdentity(
                        ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceSystem.PMT,
                        ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceKind.TEMPLATE,"T1"),
                "B1",2,"a".repeat(64));
        var command=new ai.neargo.shop.pay.migration.dto.MigrationStageViews.StageCommand(query,"offline rehearsal");
        var view=new ai.neargo.shop.pay.migration.dto.MigrationStageViews.StageView("MS1","PMT","T1","M1","B1",
                "STAGED",1,2,100,1,0,"FROZEN",false,java.time.LocalDateTime.of(2026,10,19,1,0),0);
        var checked=new ai.neargo.shop.pay.migration.dto.MigrationStageViews.Verification("MS1","SOURCE_MATCHED",false);
        var request=new ai.neargo.shop.pay.client.PayMigrationRequests.Stage(scope,command,"MK1");
        var get=new ai.neargo.shop.pay.client.PayMigrationRequests.Get(scope,"MS1");
        when(migration.stage(scope,command,"MK1")).thenReturn(view);
        when(migration.get(scope,"MS1")).thenReturn(view);
        when(migration.verify(scope,"MS1")).thenReturn(checked);
        var full=new ai.neargo.shop.pay.migration.dto.MigrationStageViews.ManifestStageView(view,2,true);
        var manifestRequest=new ai.neargo.shop.pay.client.PayMigrationRequests.Stage(scope,command,"MF1");
        when(migration.stageManifest(scope,command,"MF1")).thenReturn(full);
        var assessment=new ai.neargo.shop.pay.migration.service.LegacyCouponMigrationNormalizer.Report(
                "MS1","BLOCKED",false,null,List.of(),List.of(),List.of("MARKET_FACTS_UNAVAILABLE"));
        when(migration.assess(scope,"MS1")).thenReturn(assessment);
        assertThat(api("tk").migrationStageAssess(get)).isEqualTo(assessment);
        assertThatThrownBy(() -> api("wrong").migrationStageAssess(get)).isInstanceOf(ServiceCallException.class);
        verify(migration,times(1)).assess(scope,"MS1");
        assertThat(api("tk").migrationStageManifest(manifestRequest)).isEqualTo(full);
        assertThatThrownBy(() -> api("wrong").migrationStageManifest(manifestRequest)).isInstanceOf(ServiceCallException.class);
        verify(migration,times(1)).stageManifest(scope,command,"MF1");
        assertThat(api("tk").migrationStage(request)).isEqualTo(view);
        assertThat(api("tk").migrationStageGet(get)).isEqualTo(view);
        assertThat(api("tk").migrationStageVerify(get)).isEqualTo(checked);
        assertThatThrownBy(() -> api("wrong").migrationStage(request)).isInstanceOf(ServiceCallException.class);
        assertThatThrownBy(() -> api("wrong").migrationStageGet(get)).isInstanceOf(ServiceCallException.class);
        assertThatThrownBy(() -> api("wrong").migrationStageVerify(get)).isInstanceOf(ServiceCallException.class);
        verify(migration,times(1)).stage(scope,command,"MK1");
        verify(migration,times(1)).get(scope,"MS1");
        verify(migration,times(1)).verify(scope,"MS1");
    }

    @Test void canonicalAliasToolsRemainTypedScopedAndTokenProtected() {
        var scope=new CatalogScope("MAIN","M1","OP1",true);
        var pair=new ai.neargo.shop.pay.migration.dto.MigrationAliasViews.Pair("MS-P","MS-M");
        var command=new ai.neargo.shop.pay.migration.dto.MigrationAliasViews.Recognize(pair,0,0,"V232 review");
        var compared=new ai.neargo.shop.pay.migration.service.LegacyCouponAliasComparator.Comparison(
                "CANDIDATE_ALIAS",false,1,"c".repeat(64),List.of());
        var at=java.time.LocalDateTime.of(2026,10,20,1,0);
        var view=new ai.neargo.shop.pay.migration.dto.MigrationAliasViews.AliasView("MA1","M1","B1","MS-P","MS-M",
                "MS-P","a".repeat(64),"b".repeat(64),"c".repeat(64),1,at,"RECOGNIZED",false,at,0);
        when(migration.compareAlias(scope,pair)).thenReturn(compared);
        when(migration.recognizeAlias(scope,command,"AK1")).thenReturn(view);
        when(migration.getAlias(scope,"MA1")).thenReturn(view);
        var compare=new ai.neargo.shop.pay.client.PayMigrationRequests.AliasPair(scope,pair);
        var recognize=new ai.neargo.shop.pay.client.PayMigrationRequests.AliasRecognition(scope,command,"AK1");
        var get=new ai.neargo.shop.pay.client.PayMigrationRequests.AliasGet(scope,"MA1");
        assertThat(api("tk").migrationAliasCompare(compare)).isEqualTo(compared);
        assertThat(api("tk").migrationAliasRecognize(recognize)).isEqualTo(view);
        assertThat(api("tk").migrationAliasGet(get)).isEqualTo(view);
        assertThatThrownBy(() -> api("wrong").migrationAliasCompare(compare)).isInstanceOf(ServiceCallException.class);
        assertThatThrownBy(() -> api("wrong").migrationAliasRecognize(recognize)).isInstanceOf(ServiceCallException.class);
        assertThatThrownBy(() -> api("wrong").migrationAliasGet(get)).isInstanceOf(ServiceCallException.class);
        verify(migration,times(1)).compareAlias(scope,pair);
        verify(migration,times(1)).recognizeAlias(scope,command,"AK1");
        verify(migration,times(1)).getAlias(scope,"MA1");
    }

    @Test void legacyCouponFinancialProtocolIsTypedAndAuthenticatedForEveryOperation() {
        var ctx=new EntitlementContext("MAIN","U1","K1","a".repeat(64));
        var prepared=new ai.neargo.shop.spi.entitlement.LegacyCouponUsePort.Prepare("LC1","LO1","U1","M1");
        var completed=new ai.neargo.shop.spi.entitlement.LegacyCouponUsePort.Complete("LC1","LO1","M1","D1");
        var allocations=List.of(new ai.neargo.shop.spi.entitlement.LegacyCouponUsePort.Allocation("S1","M1","S1",100));
        var quote=new ai.neargo.shop.spi.entitlement.LegacyCouponUsePort.Quote("EI1","CNY",100,allocations,"c".repeat(64),Instant.now().plusSeconds(30));
        var held=new ai.neargo.shop.spi.entitlement.LegacyCouponUsePort.Operation("LC1","LO1","EI1","M1","CNY","c".repeat(64),
                ai.neargo.shop.spi.entitlement.LegacyCouponUsePort.State.HELD,100L,allocations);
        var committed=new ai.neargo.shop.spi.entitlement.LegacyCouponUsePort.Operation("LC1","LO1","EI1","M1","CNY","c".repeat(64),
                ai.neargo.shop.spi.entitlement.LegacyCouponUsePort.State.COMMITTED,100L,allocations);
        var released=new ai.neargo.shop.spi.entitlement.LegacyCouponUsePort.Operation("LC1","LO1","EI1","M1","CNY","c".repeat(64),
                ai.neargo.shop.spi.entitlement.LegacyCouponUsePort.State.RELEASED,100L,allocations);
        var reversed=new ai.neargo.shop.spi.entitlement.LegacyCouponUsePort.Operation("LC1","LO1","EI1","M1","CNY","c".repeat(64),
                ai.neargo.shop.spi.entitlement.LegacyCouponUsePort.State.REVERSED,100L,allocations);
        when(legacyCoupons.quote(ctx,prepared)).thenReturn(quote);when(legacyCoupons.prepare(ctx,prepared)).thenReturn(held);
        when(legacyCoupons.commit(ctx,completed)).thenReturn(committed);when(legacyCoupons.release(ctx,completed)).thenReturn(released);
        when(legacyCoupons.reverse(ctx,completed)).thenReturn(reversed);when(legacyCoupons.query(ctx,"M1","LO1")).thenReturn(committed);
        var prepare=new ai.neargo.shop.pay.client.PayLegacyCouponRequests.PrepareRequest(ctx,prepared);
        var complete=new ai.neargo.shop.pay.client.PayLegacyCouponRequests.CompleteRequest(ctx,completed);
        var query=new ai.neargo.shop.pay.client.PayLegacyCouponRequests.Query(ctx,"M1","LO1");
        assertThat(api("tk").legacyCouponQuote(prepare)).isEqualTo(quote);
        assertThat(api("tk").legacyCouponPrepare(prepare)).isEqualTo(held);
        assertThat(api("tk").legacyCouponCommit(complete)).isEqualTo(committed);
        assertThat(api("tk").legacyCouponRelease(complete)).isEqualTo(released);
        assertThat(api("tk").legacyCouponReverse(complete)).isEqualTo(reversed);
        assertThat(api("tk").legacyCouponQuery(query)).isEqualTo(committed);
        assertThatThrownBy(()->api("wrong").legacyCouponQuote(prepare)).isInstanceOf(ServiceCallException.class);
        assertThatThrownBy(()->api("wrong").legacyCouponPrepare(prepare)).isInstanceOf(ServiceCallException.class);
        assertThatThrownBy(()->api("wrong").legacyCouponCommit(complete)).isInstanceOf(ServiceCallException.class);
        assertThatThrownBy(()->api("wrong").legacyCouponRelease(complete)).isInstanceOf(ServiceCallException.class);
        assertThatThrownBy(()->api("wrong").legacyCouponReverse(complete)).isInstanceOf(ServiceCallException.class);
        assertThatThrownBy(()->api("wrong").legacyCouponQuery(query)).isInstanceOf(ServiceCallException.class);
        verify(legacyCoupons,times(1)).quote(ctx,prepared);verify(legacyCoupons,times(1)).prepare(ctx,prepared);
        verify(legacyCoupons,times(1)).commit(ctx,completed);verify(legacyCoupons,times(1)).release(ctx,completed);
        verify(legacyCoupons,times(1)).reverse(ctx,completed);verify(legacyCoupons,times(1)).query(ctx,"M1","LO1");
    }
    @Test void malformedExceptionRecoveryIsRejectedBeforeBusinessInvocation() {
        var client=RestClient.create("http://127.0.0.1:"+port);
        assertThatThrownBy(() -> client.post().uri(PayInternalPaths.EXCEPTIONS_RETRY)
                .header(InternalHttp.TOKEN_HEADER,"tk").contentType(MediaType.APPLICATION_JSON)
                .body("{\"scope\":null,\"exceptionNo\":\"EX1\",\"command\":null,\"requestKey\":\"K1\"}")
                .retrieve().toBodilessEntity()).isInstanceOfSatisfying(RestClientResponseException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
        verifyNoInteractions(exceptionRecovery);
    }
    @Test void reconciliationCommandsQueriesAndSafeDifferencesRoundTrip() {
        var scope=new CatalogScope("MAIN","M1","OP1",true);
        var check=new Check("MONEY","CNY",LocalDate.of(2026,10,19),"Asia/Shanghai","",20);
        var view=new RunView("RR1","M1","MONEY","COMPLETED",1,1,null,check.bizDate(),check.marketZone(),"CNY");
        var diff=new DiffView("RD1","RR1","M1","A1","MONEY_MISMATCH","OPEN");
        when(reconciliation.check(scope,check,"K1")).thenReturn(view);
        when(reconciliation.get(scope,"RR1")).thenReturn(view);
        when(reconciliation.list(scope,check.bizDate(),1,20)).thenReturn(new PageData<>(List.of(view),1,1,20));
        when(reconciliation.differences(scope,"RR1",1,20)).thenReturn(new PageData<>(List.of(diff),1,1,20));
        var request=new PayReconciliationRequests.Start(scope,check,"K1");
        assertThat(api("tk").reconciliationCheck(request)).isEqualTo(view);
        assertThat(api("tk").reconciliationGet(new PayReconciliationRequests.GetRun(scope,"RR1"))).isEqualTo(view);
        assertThat(api("tk").reconciliationsList(new PayReconciliationRequests.ListRuns(scope,check.bizDate(),1,20))
                .records()).containsExactly(view);
        assertThat(api("tk").reconciliationDiffs(new PayReconciliationRequests.Differences(scope,"RR1",1,20))
                .records()).containsExactly(diff);
        assertThatThrownBy(() -> api("wrong").reconciliationCheck(request)).isInstanceOf(ServiceCallException.class);
        verify(reconciliation,times(1)).check(scope,check,"K1");
    }
    @Test void exceptionHistoryRoundTripsOnlyAuditMetadata() {
        var scope=new CatalogScope("MAIN","M1","OP1",true);
        var time=java.time.LocalDateTime.of(2026,10,19,1,0);
        var action=new ActionView("EA1","EX1","QUERY_SOURCE","DONE","OP1","核实原单",time,time);
        when(exceptions.actions(scope,"EX1",1,20)).thenReturn(new PageData<>(List.of(action),1,1,20));
        assertThat(api("tk").exceptionActions(new PayExceptionRequests.Actions(scope,"EX1",1,20)).records())
                .containsExactly(action);
        assertThatThrownBy(() -> api("wrong").exceptionActions(new PayExceptionRequests.Actions(scope,"EX1",1,20)))
                .isInstanceOf(ServiceCallException.class);
        verify(exceptions,times(1)).actions(scope,"EX1",1,20);
    }
    @Test void lifecycleReadAndFreezeRoundTripOriginalVersionReasonAndKey() {
        var scope=new CatalogScope("MAIN","M1","OP1",true);
        var before=new FreezeView("I1","ACTIVE",4,3,1);
        var after=new FreezeView("I1","FROZEN",5,3,1);
        var command=new FreezeCommand(4,"FREEZE","核实异常");
        when(lifecycle.state(scope,"I1")).thenReturn(before);
        when(lifecycle.freeze(scope,"I1",command,"FK1")).thenReturn(after);
        assertThat(api("tk").entitlementLifecycleState(new PayLifecycleRequests.State(scope,"I1"))).isEqualTo(before);
        var request=new PayLifecycleRequests.Freeze(scope,"I1",command,"FK1");
        assertThat(api("tk").entitlementFreeze(request)).isEqualTo(after);
        assertThatThrownBy(() -> api("wrong").entitlementFreeze(request)).isInstanceOf(ServiceCallException.class);
        verify(lifecycle,times(1)).freeze(scope,"I1",command,"FK1");
    }
    @Test void storedValueReadinessRoundTripsTenantAndRequiresInternalToken() {
        var status=new StoredValueCapabilityPort.Status("MAIN",true,false);
        when(storedValueCapabilities.status("MAIN")).thenReturn(status);
        var request=new PayStoredValueRequests.Capabilities("MAIN");
        assertThat(api("tk").storedValueCapabilities(request)).isEqualTo(status);
        assertThatThrownBy(() -> api("wrong").storedValueCapabilities(request)).isInstanceOf(ServiceCallException.class);
        verify(storedValueCapabilities,times(1)).status("MAIN");
    }
    @Test void expiryUsesBoundedOwnerCommandAndRequiresInternalToken() {
        when(lifecycle.expireDue(ctx,100)).thenReturn(2);
        var command=new PayLifecycleRequests.Expire(ctx,100);
        assertThat(api("tk").entitlementExpire(command)).isEqualTo(new ExpireResult(2));
        assertThatThrownBy(() -> api("wrong").entitlementExpire(command)).isInstanceOf(ServiceCallException.class);
        verify(lifecycle,times(1)).expireDue(ctx,100);
        assertThatThrownBy(() -> new PayLifecycleRequests.Expire(ctx,101)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void marketingUsageFactsRoundTripSafeAttributionAndRequireToken() {
        var page=new UsageEffects("MAIN","EU1","U1","M1","S1","STORED_VALUE","A1","CNY",2200L,null,
                at.toEpochMilli(),List.of(new Attribution("ACT1",1100L,null)),"ACT1");
        when(marketing.usage(ctx,"M1","EU1","",1)).thenReturn(page);
        var request=new PayMarketingRequests.Usage(ctx,"M1","EU1","",1);
        assertThat(api("tk").marketingUsage(request)).isEqualTo(page);
        assertThatThrownBy(() -> api("wrong").marketingUsage(request)).isInstanceOf(ServiceCallException.class);
        verify(marketing,times(1)).usage(ctx,"M1","EU1","",1);
    }
    @Test void exactOwnerIdentityRoundTripsWithoutCrossSourceFallback() {
        var source=new SourceIdentity(SourceSystem.PMT,SourceKind.INSTANCE,"OLD1");
        var owner=new OwnerRef(source,null,OwnerSystem.CORE_LEGACY,MigrationState.PREPARING,"B1",null,0);
        when(owners.resolve(ctx,source)).thenReturn(owner);
        var request=new PayOwnerRequests.Resolve(ctx,source);
        assertThat(api("tk").entitlementOwnerResolve(request)).isEqualTo(owner);
        assertThatThrownBy(() -> api("wrong").entitlementOwnerResolve(request)).isInstanceOf(ServiceCallException.class);
        verify(owners,times(1)).resolve(ctx,source);
        var missing=new SourceIdentity(SourceSystem.MKT_LEGACY,SourceKind.INSTANCE,"OLD1");
        when(owners.resolve(ctx,missing)).thenThrow(BizException.of(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> api("tk").entitlementOwnerResolve(new PayOwnerRequests.Resolve(ctx,missing)))
                .isInstanceOfSatisfying(ServiceCallException.class,e -> assertThat(e.statusCode()).isEqualTo(404));
    }
    @Test void everyPayEntitlementAndStoredValueClientMethodHasALiveOwnerHandler() {
        int count=0;
        for(var method:PayInternalApi.class.getMethods()) {
            var post=method.getAnnotation(PostExchange.class); var get=method.getAnnotation(GetExchange.class);
            String path=post!=null?post.value():get!=null?get.value():"";
            if(!path.startsWith("/internal/pay/entitlement/") && !path.startsWith("/internal/pay/stored-value/")) continue;
            var verb=post!=null?RequestMethod.POST:RequestMethod.GET;
            boolean exists=mappings.getHandlerMethods().keySet().stream().anyMatch(info ->
                    info.getPathPatternsCondition()!=null && info.getPathPatternsCondition().getPatternValues().contains(path)
                    && info.getMethodsCondition().getMethods().contains(verb));
            assertThat(exists).as(verb+" "+path).isTrue(); count++;
        }
        assertThat(count).isGreaterThanOrEqualTo(30);
        System.out.println("Pay entitlement/stored-value owner handlers covered: "+count);
    }
    @Test void entitlementOwnerCapabilitiesRoundTripWithTransportAuthentication() {
        var request = new PayCapabilityRequests.Status("MAIN");
        var status = new EntitlementCapabilityPort.Status("MAIN", true, true, true, false);
        when(entitlementCapabilities.status("MAIN")).thenReturn(status);
        assertThat(api("tk").entitlementCapabilities(request)).isEqualTo(status);
        assertThatThrownBy(() -> api("wrong").entitlementCapabilities(request))
                .isInstanceOf(ServiceCallException.class);
        verify(entitlementCapabilities, times(1)).status("MAIN");
    }
    @Test void expiryReminderReadRoundTripsOnlyMinimalFactsAndRequiresInternalToken() {
        var request = new PayLifecycleRequests.Reminders(ctx, 12, 20);
        var page = new ReminderPage(List.of(new ExpiryReminder("I1", "U1", Instant.parse("2026-10-20T00:00:00Z"))), 99);
        when(lifecycle.expiringSoon(ctx, 12, 20)).thenReturn(page);
        assertThat(api("tk").entitlementExpiringSoon(request)).isEqualTo(page);
        assertThatThrownBy(() -> api("wrong").entitlementExpiringSoon(request))
                .isInstanceOf(ServiceCallException.class);
        verify(lifecycle, times(1)).expiringSoon(ctx, 12, 20);
    }
    @Test void scopedExceptionQueuePreservesTheExplicitAllowedSetAndRequiresToken() {
        var scope = new OperatorEntityScope("MAIN", "OP1", Set.of("M1", "M2"));
        var request = new PayExceptionRequests.ListAcross(scope, null, "OPEN", 1, 20);
        var result = new PageData<ai.neargo.shop.pay.entitlement.dto.ExceptionViews.ExceptionView>(List.of(), 0, 1, 20);
        when(exceptions.listAcross(scope, null, "OPEN", 1, 20)).thenReturn(result);
        assertThat(api("tk").exceptionsListAcross(request)).isEqualTo(result);
        assertThatThrownBy(() -> api("wrong").exceptionsListAcross(request)).isInstanceOf(ServiceCallException.class);
        verify(exceptions, times(1)).listAcross(scope, null, "OPEN", 1, 20);
    }
    @Test void legacyPreviewEstimateIsTypedAuthenticatedAndHasNoCheckoutCredential() {
        var origin=new SourceIdentity(SourceSystem.PMT,SourceKind.INSTANCE,"PU1");
        var command=new ai.neargo.shop.spi.entitlement.LegacyCouponUsePort.EstimateCommand("U1","M1",origin,"EI1","B1","b".repeat(64),"CNY",
                List.of(new ai.neargo.shop.spi.entitlement.LegacyCouponCheckoutSourcePort.MerchantGroup("S1","M1","S1",1000)));
        var result=new ai.neargo.shop.spi.entitlement.LegacyCouponUsePort.Estimate("EI1","CNY","Old coupon",100,
                List.of(new ai.neargo.shop.spi.entitlement.LegacyCouponUsePort.Allocation("S1","M1","S1",100)),Instant.now().plusSeconds(300));
        when(legacyCoupons.estimate(ctx,command)).thenReturn(result);
        var request=new ai.neargo.shop.pay.client.PayLegacyCouponRequests.EstimateRequest(ctx,command);
        assertThat(api("tk").legacyCouponEstimate(request)).isEqualTo(result);
        assertThatThrownBy(()->api("wrong").legacyCouponEstimate(request)).isInstanceOf(ServiceCallException.class);
        verify(legacyCoupons,times(1)).estimate(ctx,command);
    }

    @Test void legacyWalletSnapshotKeepsReservationsSeparateAndDoesNotNeedAnOpenUseGate() {
        var origin=new SourceIdentity(SourceSystem.PMT,SourceKind.INSTANCE,"PU1");
        var owned=new ai.neargo.shop.spi.entitlement.LegacyCouponUsePort.Owned("U1","M1",origin,"EI1","B1","b".repeat(64));
        var rule=new EntitlementDefinition("Old coupon","ORDER_COUPON","MERCHANT","CNY","CASH",100L,null,null,0L,null,"COUNT",2,null,
                new EntitlementDefinition.Validity("ABSOLUTE",at.minusSeconds(100),at.plusSeconds(300),null,"GRANTED","ROLLING_DAYS","UTC"),
                new EntitlementDefinition.RedemptionScope("ALL",List.of()),List.of("TARGETED"));
        var result=new ai.neargo.shop.spi.entitlement.LegacyCouponUsePort.Snapshot("EI1",rule,2,2,1,
                EntitlementQueryPort.AssetState.ACTIVE,at.minusSeconds(100),at.plusSeconds(300),null,false);
        when(legacyCoupons.snapshot(ctx,owned)).thenReturn(result);
        var request=new ai.neargo.shop.pay.client.PayLegacyCouponRequests.SnapshotRequest(ctx,owned);
        assertThat(api("tk").legacyCouponSnapshot(request)).isEqualTo(result);
        assertThatThrownBy(()->api("wrong").legacyCouponSnapshot(request)).isInstanceOf(ServiceCallException.class);
        verify(legacyCoupons,times(1)).snapshot(ctx,owned);
    }

    @Test void historicalReturnReceiptRoundTripsWithoutTreatingItAsACashRefund() {
        var receipt=new ai.neargo.shop.spi.entitlement.LegacyCouponHistoricalReturnPort.Receipt(
                "PR1","HR1","AP1","EU1","EI1","U1","M1","a".repeat(64),1,1,at);
        when(historicalReturns.reverse(ctx,"M1","HR1")).thenReturn(receipt);
        when(historicalReturns.query(ctx,"M1","HR1")).thenReturn(receipt);
        var request=new ai.neargo.shop.pay.client.PayHistoricalReturnRequests.Query(ctx,"M1","HR1");
        assertThat(api("tk").historicalReturnReverse(request)).isEqualTo(receipt);
        assertThat(api("tk").historicalReturnQuery(request)).isEqualTo(receipt);
        assertThatThrownBy(()->api("wrong").historicalReturnReverse(request)).isInstanceOf(ServiceCallException.class);
        assertThatThrownBy(()->api("wrong").historicalReturnQuery(request)).isInstanceOf(ServiceCallException.class);
    }
    @Test void legacyDefinitionOwnerSupportsReadSaveAndExplicitStatusWithAuthentication() {
        var scope=new CatalogScope("MAIN","M1","OP1",false);
        var reference=new ai.neargo.shop.pay.migration.dto.LegacyCouponTemplateViews.Reference(
                new SourceIdentity(SourceSystem.PMT,SourceKind.TEMPLATE,"OLD1"),"ET1","B1","a".repeat(64));
        var d=new EntitlementDefinition("Old coupon","ORDER_COUPON","MERCHANT","CNY","CASH",100L,null,null,0L,null,"COUNT",1,null,
                new EntitlementDefinition.Validity("ABSOLUTE",at.minusSeconds(100),at.plusSeconds(300),null,"GRANTED","ROLLING_DAYS","UTC"),
                new EntitlementDefinition.RedemptionScope("ALL",List.of()),List.of("CENTER","TARGETED"));
        var policy=new ai.neargo.shop.pay.entitlement.dto.IssuePolicyViews.PolicyView("ET1",10L,0,1,1000L,0,100,1,false,0);
        var snapshot=new ai.neargo.shop.pay.migration.dto.LegacyCouponTemplateViews.Snapshot(
                new ai.neargo.shop.pay.entitlement.dto.ManagedTemplateView("ET1","ACTIVE",0,"EV1",d,policy),d,1,100);
        var change=new ai.neargo.shop.pay.migration.dto.LegacyCouponTemplateViews.DefinitionChange(d,10L,1000L,1);
        when(legacyTemplates.get(scope,reference)).thenReturn(snapshot);
        when(legacyTemplates.status(scope,reference,"ACTIVE","K1")).thenReturn(snapshot);
        when(legacyTemplates.save(scope,reference,change,"K2")).thenReturn(snapshot);
        when(legacyTemplates.archive(scope,reference,true,"K3")).thenReturn(snapshot);
        var get=new ai.neargo.shop.pay.client.PayLegacyTemplateRequests.Get(scope,reference);
        var status=new ai.neargo.shop.pay.client.PayLegacyTemplateRequests.Status(scope,reference,"ACTIVE","K1");
        var save=new ai.neargo.shop.pay.client.PayLegacyTemplateRequests.Save(scope,reference,change,"K2");
        assertThat(api("tk").legacyTemplateGet(get)).isEqualTo(snapshot);
        assertThat(api("tk").legacyTemplateStatus(status)).isEqualTo(snapshot);
        assertThat(api("tk").legacyTemplateSave(save)).isEqualTo(snapshot);
        var archive=new ai.neargo.shop.pay.client.PayLegacyTemplateRequests.Archive(scope,reference,true,"K3");
        assertThat(api("tk").legacyTemplateArchive(archive)).isEqualTo(snapshot);
        assertThatThrownBy(()->api("wrong").legacyTemplateArchive(archive)).isInstanceOf(ServiceCallException.class);
        assertThatThrownBy(()->api("wrong").legacyTemplateGet(get)).isInstanceOf(ServiceCallException.class);
        assertThatThrownBy(()->api("wrong").legacyTemplateStatus(status)).isInstanceOf(ServiceCallException.class);
        assertThatThrownBy(()->api("wrong").legacyTemplateSave(save)).isInstanceOf(ServiceCallException.class);
    }
    @Test void legacyIssueOriginalBatchAndResultsRoundTripWithNoPublicRecipientInput() {
        var scope=new CatalogScope("MAIN","M1","OP1",false);
        var ref=new ai.neargo.shop.pay.migration.dto.LegacyCouponTemplateViews.Reference(
                new SourceIdentity(SourceSystem.PMT,SourceKind.TEMPLATE,"T1"),"ET1","MB1","a".repeat(64));
        var command=new ai.neargo.shop.pay.migration.dto.LegacyCouponIssueViews.Command(ref,"TARGETED","@ALL",List.of());
        var result=new ai.neargo.shop.pay.migration.dto.LegacyCouponIssueViews.View("LI1","LB1","M1","PMT","T1","ET1","EV1","MB1",
                "a".repeat(64),"OP1","TARGETED","@ALL",List.of(),"COMPLETED",0,0,0,0,java.util.Map.of(),0,at,List.of());
        when(legacyIssues.issue(scope,command,"K1")).thenReturn(result);when(legacyIssues.get(scope,"LI1")).thenReturn(result);
        when(legacyIssues.resume(scope,"LI1",100)).thenReturn(result);when(legacyIssues.list(scope,"PMT","T1")).thenReturn(List.of(result));
        var issue=new ai.neargo.shop.pay.client.PayLegacyIssueRequests.Issue(scope,command,"K1");
        var get=new ai.neargo.shop.pay.client.PayLegacyIssueRequests.Get(scope,"LI1");
        var resume=new ai.neargo.shop.pay.client.PayLegacyIssueRequests.Resume(scope,"LI1",100);
        var history=new ai.neargo.shop.pay.client.PayLegacyIssueRequests.History(scope,"PMT","T1");
        assertThat(api("tk").legacyIssue(issue)).isEqualTo(result);assertThat(api("tk").legacyIssueGet(get)).isEqualTo(result);
        assertThat(api("tk").legacyIssueResume(resume)).isEqualTo(result);assertThat(api("tk").legacyIssueList(history)).containsExactly(result);
        assertThatThrownBy(()->api("wrong").legacyIssue(issue)).isInstanceOf(ServiceCallException.class);
        assertThatThrownBy(()->api("wrong").legacyIssueGet(get)).isInstanceOf(ServiceCallException.class);
        assertThatThrownBy(()->api("wrong").legacyIssueResume(resume)).isInstanceOf(ServiceCallException.class);
        assertThatThrownBy(()->api("wrong").legacyIssueList(history)).isInstanceOf(ServiceCallException.class);
    }
}
