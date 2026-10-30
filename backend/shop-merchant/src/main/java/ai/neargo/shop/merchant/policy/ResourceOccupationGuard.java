package ai.neargo.shop.merchant.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.dto.ResourceCommandScopeRow;
import ai.neargo.shop.merchant.entity.MchResourceBucket;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ResourceOccupationMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Shared capacity and maintenance fence; only owner-local current evidence, never remote IO or owner locks. */
@Component
public final class ResourceOccupationGuard {
    private final ResourceOccupationSchemaVerifier schema;
    private final ResourceOccupationMapper rows;

    public ResourceOccupationGuard(ResourceOccupationSchemaVerifier schema, ResourceOccupationMapper rows) {
        this.schema = schema;
        this.rows = rows;
    }

    /** Inspect once per operation under its existing roots; callers cannot cache across owner operations. */
    public boolean inspect(ResourceCommandScopeRow scope) {
        var installation = requireCommon(scope);
        if (installation == ResourceOccupationSchemaVerifier.Installation.INSTALLED) {
            if (evidence(() -> rows.countInvalid(scope)) != 0) { fail(); }
            return true;
        }
        return false;
    }

    /** New binaries require the forward common contract before issuing any SQL using its columns. */
    public ResourceOccupationSchemaVerifier.Installation requireCommon(ResourceCommandScopeRow scope) {
        var installation = schema.installation();
        if (installation == ResourceOccupationSchemaVerifier.Installation.ABSENT) {
            throw BizException.of(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE);
        }
        if (installation == ResourceOccupationSchemaVerifier.Installation.CORRUPT) { fail(); }
        if (installation == ResourceOccupationSchemaVerifier.Installation.UNSUPPORTED
                && evidence(() -> rows.countCommonArtifacts(scope)) != 0) { fail(); }
        return installation;
    }

    /** Pure old identity reads still work before V607; actual usage never becomes an exclusive blocker. */
    public long currentUsed(ResourceCommandScopeRow scope, String resource, LocalDateTime at) {
        if (schema.installation() == ResourceOccupationSchemaVerifier.Installation.ABSENT) { return 0; }
        if (!inspect(scope)) { return 0; }
        var start = at.withSecond(0).withNano(0).minusMinutes(at.getMinute() % 5);
        return evidence(() -> Math.addExact(Math.addExact(evidence(() -> rows.projected(scope, resource, start, "OCCUPIED")),
                evidence(() -> rows.projected(scope, resource, start, "CLEANING"))), evidence(() -> rows.missing(scope, resource, start, start.plusMinutes(5)))));
    }

    /** Includes unprojected OPEN/PAUSED and unconfirmed cleaning after expected times; may exceed capacity. */
    public long used(ResourceCommandScopeRow scope, String resource, LocalDateTime start,
            MchResourceBucket bucket, long held, long confirmed) {
        return used(scope, resource, start, bucket, held, confirmed, inspect(scope));
    }

    public long used(ResourceCommandScopeRow scope, String resource, LocalDateTime start,
            MchResourceBucket bucket, long held, long confirmed, boolean nativeAvailable) {
        if (held < 0 || confirmed < 0 || bucket.getOccupiedQty() == null || bucket.getCleaningQty() == null
                || bucket.getOccupiedQty() < 0 || bucket.getCleaningQty() < 0) { fail(); }
        long occupied = nativeAvailable ? evidence(() -> rows.projected(scope, resource, start, "OCCUPIED")) : 0;
        long cleaning = nativeAvailable ? evidence(() -> rows.projected(scope, resource, start, "CLEANING")) : 0;
        if (occupied != bucket.getOccupiedQty() || cleaning != bucket.getCleaningQty()) { fail(); }
        long overlay = nativeAvailable ? evidence(() -> rows.missing(scope, resource, start, start.plusMinutes(5))) : 0;
        if (occupied < 0 || cleaning < 0 || overlay < 0) { fail(); }
        return evidence(() -> Math.addExact(Math.addExact(Math.addExact(held, confirmed), Math.addExact(occupied, cleaning)), overlay));
    }

    /** Valid ended/cleaned permanent history is not work; damaged linkage always fences maintenance. */
    public void requireNoWork(ResourceCommandScopeRow scope, String resource) {
        if (inspect(scope) && evidence(() -> rows.countWork(scope, resource, LocalDateTime.now(ZoneOffset.UTC))) != 0) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    /** The advisory wire has an int bound; exceeding it is unavailable, never a truncated free quantity. */
    public int reportedUsed(long used) {
        return evidence(() -> Math.toIntExact(used));
    }

    private static <T> T evidence(Supplier<T> operation) {
        try { return operation.get(); }
        catch (BizException failure) { throw failure; }
        catch (RuntimeException failure) {
            var unavailable = BizException.of(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE);
            unavailable.initCause(failure);
            throw unavailable;
        }
    }

    private static void fail() { throw BizException.of(ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE); }
}
