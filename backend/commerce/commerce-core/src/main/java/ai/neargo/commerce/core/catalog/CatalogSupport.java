package ai.neargo.commerce.core.catalog;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.core.spi.CommerceUnitOfWork;

import java.time.*;
import java.util.*;
import java.util.function.Function;

/** Shared transaction/authorization/audit boundary; no host model or external side effect. */
public final class CatalogSupport {
    public final CatalogRepository repository;
    public final BusinessEndReadinessPort readiness;
    public final Clock clock;
    public final MoneyPolicyPort moneyPolicies;
    private final CommerceUnitOfWork uow;
    private final CatalogAuthorizationPort auth;
    private final CommerceEventPersistencePort events;
    private final CommerceIdGenerator ids;

    public CatalogSupport(
            CatalogRepository r,
            CommerceUnitOfWork u,
            CatalogAuthorizationPort a,
            BusinessEndReadinessPort readiness,
            CommerceEventPersistencePort e,
            Clock c,
            CommerceIdGenerator ids) {
        this(r, u, a, readiness, e, c, ids, MoneyPolicyPort.LEGACY);
    }

    public CatalogSupport(CatalogRepository r, CommerceUnitOfWork u, CatalogAuthorizationPort a,
            BusinessEndReadinessPort readiness, CommerceEventPersistencePort e, Clock c,
            CommerceIdGenerator ids, MoneyPolicyPort moneyPolicies) {
        this.moneyPolicies = Objects.requireNonNull(moneyPolicies);
        repository = Objects.requireNonNull(r);
        uow = Objects.requireNonNull(u);
        auth = Objects.requireNonNull(a);
        this.readiness = Objects.requireNonNull(readiness);
        events = Objects.requireNonNull(e);
        clock = Objects.requireNonNull(c);
        this.ids = Objects.requireNonNull(ids);
    }

    public static void check(boolean condition, String message) {
        if (!condition) throw new CatalogException(CatalogErrorCode.INVALID_ARGUMENT, message);
    }

    public static String text(String value, int max, String field) {
        check(
                value != null && !value.isBlank() && value.length() <= max,
                field + " required/max " + max);
        return value;
    }

    public static String code(String value, int max) {
        text(value, max, "code");
        check(value.matches("[A-Za-z0-9_.:-]+"), "invalid code");
        return value;
    }

    public static void expected(long expected, long actual) {
        if (expected != actual)
            throw new CatalogException(
                    CatalogErrorCode.VERSION_CONFLICT,
                    "stale version: expected " + expected + ", current " + actual);
    }

    public static void fail(CatalogErrorCode code, String message) {
        throw new CatalogException(code, message);
    }

    public static <T> T require(Map<String, T> map, String key) {
        var v = map.get(key);
        if (v == null)
            throw new CatalogException(
                    CatalogErrorCode.NOT_FOUND, "unknown tenant-scoped reference: " + key);
        return v;
    }

    public static void unique(List<String> codes) {
        check(codes.size() == new HashSet<>(codes).size(), "duplicate codes");
        codes.forEach(c -> code(c, 64));
    }

    public void authorize(ActorContext actor, String capability) {
        if (actor == null
                || actor.subjectKind() != SubjectKind.OPERATOR
                || actor.tenantNo() == null
                || actor.tenantNo().isBlank()
                || actor.principalNo() == null
                || actor.principalNo().isBlank()
                || !auth.allowed(actor, "commerce:catalog:" + capability))
            throw new CatalogException(
                    CatalogErrorCode.FORBIDDEN,
                    "platform catalog capability required: " + capability);
    }

    private void authorizeWrite(ActorContext a, String capability) {
        if (capability != null) {
            authorize(a, capability);
            return;
        }
        try {
            authorize(a, "edit");
        } catch (CatalogException denied) {
            if (denied.code() != CatalogErrorCode.FORBIDDEN) throw denied;
            authorize(a, "publish");
        }
    }

    public String nextId(String namespace) {
        return code(ids.nextBusinessNo(namespace), 64);
    }

    public CatalogState read(ActorContext a) {
        authorize(a, "read");
        // Host callers may use READ_COMMITTED. Current locking reads keep all nine tables
        // in one catalogue generation instead of relying on the caller's isolation level.
        return uow.required(() -> repository.load(a.tenantNo(), CatalogLockMode.SHARED));
    }

    public <T> T write(
            ActorContext a,
            String capability,
            String reason,
            String entityKind,
            String aggregate,
            String eventType,
            String operationNo,
            Function<CatalogState, T> action) {
        authorizeWrite(a, capability);
        text(reason, 1024, "reason");
        return uow.required(
                () -> {
                    authorizeWrite(a, capability);
                    var before = repository.load(a.tenantNo(), CatalogLockMode.EXCLUSIVE);
                    var after = before.copy();
                    var result = action.apply(after);
                    var writeContext = new CatalogWriteContext(a.principalNo(), clock.instant());
                    repository.save(a.tenantNo(), before, after, writeContext);
                    long beforeVersion = version(before, entityKind, aggregate),
                            afterVersion = version(after, entityKind, aggregate);
                    var refs =
                            result instanceof PublicationResult publication
                                    ? publication.publishedVersionNos()
                                    : List.<String>of();
                    var payload =
                            "{\"actor\":"
                                    + json(a.principalNo())
                                    + ",\"subjectKind\":\"OPERATOR\",\"reason\":"
                                    + json(reason)
                                    + ",\"operationNo\":"
                                    + json(operationNo)
                                    + ",\"beforeVersion\":"
                                    + beforeVersion
                                    + ",\"afterVersion\":"
                                    + afterVersion
                                    + ",\"publishedVersionNos\":["
                                    + String.join(
                                            ",", refs.stream().map(CatalogSupport::json).toList())
                                    + "]}";
                    var auditNo =
                            result instanceof PublicationResult publication
                                    ? publication.publicationNo()
                                    : nextId("catalog-audit");
                    events.append(
                            new CommerceEvent(
                                    auditNo,
                                    a.tenantNo(),
                                    "CATALOG_" + entityKind,
                                    aggregate,
                                    afterVersion,
                                    eventType,
                                    1,
                                    payload,
                                    writeContext.occurredAt()));
                    return result;
                });
    }

    private static long version(CatalogState s, String kind, String key) {
        return switch (kind) {
            case "TYPE" -> s.types.containsKey(key) ? s.types.get(key).version() : 0;
            case "END" -> s.ends.containsKey(key) ? s.ends.get(key).version() : 0;
            case "PLAN" -> s.plans.containsKey(key) ? s.plans.get(key).version() : 0;
            case "PLAN_VERSION" ->
                    s.planVersions.containsKey(key) ? s.planVersions.get(key).version() : 0;
            case "SKU" -> s.skus.containsKey(key) ? s.skus.get(key).version() : 0;
            case "SKU_VERSION" ->
                    s.skuVersions.containsKey(key) ? s.skuVersions.get(key).version() : 0;
            case "CATEGORY" ->
                    s.categories.containsKey(key) ? s.categories.get(key).version() : 0;
            case "PRODUCT" -> s.products.containsKey(key) ? s.products.get(key).version() : 0;
            default -> throw new IllegalArgumentException("unknown catalog aggregate kind");
        };
    }

    public static String json(String s) {
        if (s == null) return "null";
        var out = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 32) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }

    public void endValid(String tenant, String type, CatalogState.End end, boolean paid) {
        var e = end.draft();
        check(
                e.enabled()
                        && e.scope() == EndScope.STORE
                        && e.releaseStatus() == ReleaseStatus.VERIFIED
                        && e.allowedBusinessTypes().contains(type),
                "end not available for type");
        check(!paid || e.saleEnabled(), "end not sale enabled");
        text(e.clientId(), 128, "clientId");
        text(e.routePolicyRef(), 128, "routePolicyRef");
        text(e.acceptanceRef(), 128, "acceptanceRef");
        check(readiness.verified(tenant, e), "unverified trusted end references");
    }

    /**
     * Fresh validation is shared by industry and independent SKU publication without package
     * cycles.
     */
    public void planEndsValid(
            String tenant, CatalogState state, PlanDraft plan, Set<String> permanentFreeCodes) {
        for (String code : plan.entitlementCodes()) {
            var end =
                    state.ends.values().stream()
                            .filter(e -> e.draft().entitlementCode().equals(code))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new CatalogException(
                                                    CatalogErrorCode.NOT_FOUND,
                                                    "unknown Plan end target"));
            endValid(
                    tenant,
                    plan.typeCode(),
                    end,
                    plan.tier() != PlanTier.FREE && !permanentFreeCodes.contains(code));
        }
    }

    public Set<String> currentFreeCodes(CatalogState state, String type) {
        var free =
                state.plans.values().stream()
                        .filter(p -> p.typeCode().equals(type) && p.tier() == PlanTier.FREE)
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new CatalogException(
                                                CatalogErrorCode.NOT_FOUND,
                                                "unknown FREE definition"));
        var version = require(state.planVersions, free.currentVersionNo());
        check(version.status().equals("PUBLISHED"), "current FREE must be published");
        return Set.copyOf(version.draft().entitlementCodes());
    }
}
