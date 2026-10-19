package ai.neargo.shop.pay.svc;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.client.PayEntitlementRequests.ListTemplates;
import ai.neargo.shop.pay.client.PayEntitlementRequests.GetTemplate;
import ai.neargo.shop.pay.client.PayEntitlementRequests.CreateTemplate;
import ai.neargo.shop.pay.client.PayEntitlementRequests.DraftTemplate;
import ai.neargo.shop.pay.client.PayEntitlementRequests.PublishTemplate;
import ai.neargo.shop.pay.client.PayEntitlementRequests.StatusTemplate;
import ai.neargo.shop.pay.client.PayEntitlementRequests.ConfigureIssue;
import ai.neargo.shop.pay.client.PayEntitlementRequests.CatalogVersion;
import ai.neargo.shop.pay.client.PayEntitlementRequests.ValidateCatalogReference;
import ai.neargo.shop.pay.client.PayInternalPaths;
import ai.neargo.shop.pay.entitlement.service.EntitlementManagementService;
import ai.neargo.shop.spi.entitlement.EntitlementCatalogPort;
import ai.neargo.shop.svc.InternalHttp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Trusted narrowed scope from main application; fail closed on missing shared secret. */
@RestController
public class InternalEntitlementEndpoint {
    private final EntitlementManagementService management;
    private final EntitlementCatalogPort catalog;
    private final byte[] token;
    public InternalEntitlementEndpoint(EntitlementManagementService management, EntitlementCatalogPort catalog,
            @Value("${shop.services.internal-token:}") String token) {
        this.management = management;
        this.catalog = catalog;
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }
    @PostMapping(PayInternalPaths.ENT_LIST)
    public ResponseEntity<?> list(@RequestBody ListTemplates r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(given, () -> management.list(r.scope(), r.kind(), r.status(), r.page(), r.size()));
    }
    @PostMapping(PayInternalPaths.ENT_GET)
    public ResponseEntity<?> get(@RequestBody GetTemplate r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(given, () -> management.get(r.scope(), r.templateNo()));
    }
    @PostMapping(PayInternalPaths.ENT_CREATE)
    public ResponseEntity<?> create(@RequestBody CreateTemplate r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(given, () -> management.create(r.scope(), r.definition(), r.requestKey()));
    }
    @PostMapping(PayInternalPaths.ENT_DRAFT)
    public ResponseEntity<?> draft(@RequestBody DraftTemplate r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(given, () -> management.draft(r.scope(), r.templateNo(), r.expectedRevision(),
                r.definition(), r.requestKey()));
    }
    @PostMapping(PayInternalPaths.ENT_PUBLISH)
    public ResponseEntity<?> publish(@RequestBody PublishTemplate r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(given, () -> management.publish(r.scope(), r.templateNo(), r.expectedRevision(), r.requestKey()));
    }
    @PostMapping(PayInternalPaths.ENT_STATUS)
    public ResponseEntity<?> status(@RequestBody StatusTemplate r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(given, () -> management.status(r.scope(), r.templateNo(), r.expectedRevision(), r.status(),
                r.requestKey()));
    }
    @PostMapping(PayInternalPaths.ENT_POLICY)
    public ResponseEntity<?> policy(@RequestBody ConfigureIssue r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(given, () -> management.policy(r.scope(), r.templateNo(), r.command(), r.requestKey()));
    }
    @PostMapping(PayInternalPaths.ENT_VERSION)
    public ResponseEntity<?> version(@RequestBody CatalogVersion r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(given, () -> catalog.getPublishedVersion(r.context().tenantNo(), r.entityNo(), r.versionNo()));
    }
    @PostMapping(PayInternalPaths.ENT_REFERENCE)
    public ResponseEntity<?> reference(@RequestBody ValidateCatalogReference r,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return invoke(given, () -> {
            if (r.query() == null || !r.context().tenantNo().equals(r.query().tenantNo())) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            return catalog.validateReference(r.query());
        });
    }
    private ResponseEntity<?> invoke(String given, Supplier<?> operation) {
        if (token.length == 0 || given == null
                || !MessageDigest.isEqual(token, given.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(401).build();
        }
        try {
            return ResponseEntity.ok(operation.get());
        } catch (BizException failure) {
            int status = switch (failure.errorCode()) {
                case BAD_REQUEST -> 400;
                case FORBIDDEN -> 403;
                case NOT_FOUND -> 404;
                case CONFLICT -> 409;
                case ENTITLEMENT_FEATURE_UNAVAILABLE -> 503;
                default -> 500;
            };
            return ResponseEntity.status(status).build();
        }
    }
}
