package ai.neargo.shop.pay.svc;

import ai.neargo.shop.pay.client.PayEntitlementRequests;
import ai.neargo.shop.pay.client.PayEntitlementResponses;
import ai.neargo.shop.pay.client.PayInternalPaths;
import ai.neargo.shop.pay.entitlement.service.EntitlementCodeService;
import ai.neargo.shop.pay.entitlement.service.EntitlementIssueBatchService;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort;
import ai.neargo.shop.spi.entitlement.EntitlementUseFactsPort;
import ai.neargo.shop.spi.entitlement.PaymentAttemptFactsPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Pay owns every operation here. Public sessions are narrowed by main-app facades before service authentication. */
@RestController
public class InternalEntitlementRuntimeEndpoint {
    private final EntitlementGrantPort grants;
    private final EntitlementUsePort uses;
    private final EntitlementQueryPort queries;
    private final EntitlementUseFactsPort facts;
    private final PaymentAttemptFactsPort attempts;
    private final EntitlementCodeService codes;
    private final EntitlementIssueBatchService batches;
    private final String token;
    public InternalEntitlementRuntimeEndpoint(EntitlementGrantPort grants, EntitlementUsePort uses,
            EntitlementQueryPort queries, EntitlementUseFactsPort facts, PaymentAttemptFactsPort attempts,
            EntitlementCodeService codes, EntitlementIssueBatchService batches,
            @Value("${shop.services.internal-token:}") String token) {
        this.grants = grants; this.uses = uses; this.queries = queries; this.facts = facts; this.attempts = attempts;
        this.codes = codes; this.batches = batches; this.token = token;
    }
    @PostMapping(PayInternalPaths.ENT_GRANT_PREPARE)
    public ResponseEntity<?> entGrantPrepare(@RequestBody PayEntitlementRequests.GrantPrepare r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> grants.prepare(r.context(), r.command()));
    }
    @PostMapping(PayInternalPaths.ENT_GRANT_COMMIT)
    public ResponseEntity<?> entGrantCommit(@RequestBody PayEntitlementRequests.GrantCommit r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> grants.commit(r.context(), r.command()));
    }
    @PostMapping(PayInternalPaths.ENT_GRANT_ABORT)
    public ResponseEntity<?> entGrantAbort(@RequestBody PayEntitlementRequests.GrantAbort r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> grants.abort(r.context(), r.command()));
    }
    @PostMapping(PayInternalPaths.ENT_GRANT_QUERY)
    public ResponseEntity<?> entGrantQuery(@RequestBody PayEntitlementRequests.GrantQuery r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> grants.query(r.context(),
                r.entityNo(), r.grantNo()));
    }
    @PostMapping(PayInternalPaths.ENT_USE_QUOTE)
    public ResponseEntity<?> entQuote(@RequestBody PayEntitlementRequests.UseQuote r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> uses.quote(r.context(), r.command()));
    }
    @PostMapping(PayInternalPaths.ENT_USE_PREVIEW)
    public ResponseEntity<?> entPreview(@RequestBody PayEntitlementRequests.UsePreview r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> uses.preview(r.context(), r.query()));
    }
    @PostMapping(PayInternalPaths.ENT_USE_PREPARE)
    public ResponseEntity<?> entUsePrepare(@RequestBody PayEntitlementRequests.UsePrepare r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> uses.prepare(r.context(), r.command()));
    }
    @PostMapping(PayInternalPaths.ENT_USE_COMMIT)
    public ResponseEntity<?> entUseCommit(@RequestBody PayEntitlementRequests.UseCommit r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> uses.commit(r.context(), r.command()));
    }
    @PostMapping(PayInternalPaths.ENT_USE_RELEASE)
    public ResponseEntity<?> entUseRelease(@RequestBody PayEntitlementRequests.UseRelease r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> uses.release(r.context(), r.command()));
    }
    @PostMapping(PayInternalPaths.ENT_USE_REVERSE)
    public ResponseEntity<?> entReverse(@RequestBody PayEntitlementRequests.UseReverse r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> uses.reverse(r.context(), r.command()));
    }
    @PostMapping(PayInternalPaths.ENT_USE_REVERSE_QUERY)
    public ResponseEntity<?> entReverseQuery(@RequestBody PayEntitlementRequests.ReverseQuery r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> uses.queryReverse(r.context(),
                r.entityNo(), r.reverseNo()));
    }
    @PostMapping(PayInternalPaths.ENT_USE_QUERY)
    public ResponseEntity<?> entUseQuery(@RequestBody PayEntitlementRequests.UseQuery r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> uses.query(r.context(), r.entityNo(),
                r.operationNo()));
    }
    @PostMapping(PayInternalPaths.ENT_QUERY_MINE)
    public ResponseEntity<?> entMine(@RequestBody PayEntitlementRequests.Mine r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> queries.mine(r.context(), r.query()));
    }
    @PostMapping(PayInternalPaths.ENT_QUERY_DETAIL)
    public ResponseEntity<?> entDetail(@RequestBody PayEntitlementRequests.Detail r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> queries.detail(r.context(), r.query()));
    }
    @PostMapping(PayInternalPaths.ENT_QUERY_USAGE)
    public ResponseEntity<?> entUsage(@RequestBody PayEntitlementRequests.Usage r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> queries.usage(r.context(), r.query()));
    }
    @PostMapping(PayInternalPaths.ENT_QUERY_ALLOWANCES)
    public ResponseEntity<?> entAllowances(@RequestBody PayEntitlementRequests.Allowances r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> queries.allowances(r.context(), r.query()));
    }
    @PostMapping(PayInternalPaths.ENT_QUERY_ACTIVATION)
    public ResponseEntity<?> entActivation(@RequestBody PayEntitlementRequests.Activation r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> queries.activationQuote(r.context(), r.query()));
    }
    @PostMapping(PayInternalPaths.ENT_USE_FACTS)
    public ResponseEntity<?> entUseFacts(@RequestBody PayEntitlementRequests.UseFacts r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> facts.query(r.context(),
                r.entityNo(), r.operationNo()));
    }
    @PostMapping(PayInternalPaths.ENT_PAYMENT_ATTEMPTS)
    public ResponseEntity<?> entPaymentAttempts(@RequestBody PayEntitlementRequests.PaymentAttempts r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> new PayEntitlementResponses.PaymentAttemptView(
                r.context().tenantNo(), r.orderNo(), attempts.hasAnyAttempt(r.context().tenantNo(), r.orderNo())));
    }
    @PostMapping(PayInternalPaths.ENT_BATCH_CREATE)
    public ResponseEntity<?> entBatchCreate(@RequestBody PayEntitlementRequests.BatchCreate r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> batches.create(r.scope(),
                r.command(), r.requestKey()));
    }
    @PostMapping(PayInternalPaths.ENT_BATCH_GET)
    public ResponseEntity<?> entBatchGet(@RequestBody PayEntitlementRequests.BatchGet r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> batches.get(r.scope(), r.batchNo()));
    }
    @PostMapping(PayInternalPaths.ENT_BATCH_RESUME)
    public ResponseEntity<?> entBatchResume(@RequestBody PayEntitlementRequests.BatchResume r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> batches.resume(r.scope(), r.batchNo(), r.limit()));
    }
    @PostMapping(PayInternalPaths.ENT_CODE_ISSUE)
    public ResponseEntity<?> entCodeIssue(@RequestBody PayEntitlementRequests.CodeIssue r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> codes.issue(r.context(), r.userNo(),
                r.instanceNo(), r.request(), r.requestKey()));
    }
    @PostMapping(PayInternalPaths.ENT_CODE_RESOLVE)
    public ResponseEntity<?> entCodeResolve(@RequestBody PayEntitlementRequests.CodeResolve r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> codes.resolve(r.context(),
                r.entityNo(), r.storeNo(), r.token(), r.operationNo()));
    }
    @PostMapping(PayInternalPaths.ENT_CODE_INVALIDATE)
    public ResponseEntity<?> entCodeInvalidate(@RequestBody PayEntitlementRequests.CodeInvalidate r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invoke(token, given, () -> new PayEntitlementResponses.CodeInvalidationView(
                r.userNo(), r.instanceNo(), codes.invalidate(r.context(), r.userNo(), r.instanceNo())));
    }
}
