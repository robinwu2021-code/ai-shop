package ai.neargo.shop.pay.svc;

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
    @MockitoBean StoredValueRechargeService recharges;
    @MockitoBean StoredValueCreditService credits;
    @MockitoBean StoredValueRechargeFactsPort rechargeFacts;
    @MockitoBean EntitlementGrantPort grants;
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
        var api = api("tk");
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

}
