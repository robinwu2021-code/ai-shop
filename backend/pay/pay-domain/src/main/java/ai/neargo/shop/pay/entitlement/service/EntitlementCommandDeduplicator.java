package ai.neargo.shop.pay.entitlement.service;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.entity.PayEntCommandDedup;
import ai.neargo.shop.pay.mapper.entitlement.PayEntCommandDedupMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Stable command replies join the owner's pay transaction; no PENDING command may commit on its own. */
@Component
public class EntitlementCommandDeduplicator {
    private final PayEntCommandDedupMapper commands;
    private final ObjectMapper json;

    public EntitlementCommandDeduplicator(PayEntCommandDedupMapper commands, ObjectMapper json) {
        this.commands = commands;
        this.json = json;
    }

    /**
     * Replay uses tenant + operation + opaque key; identity and the complete logical payload are fingerprinted.
     * The caller includes target IDs in payload, but not newly generated IDs that differ on each retry.
     */
    @Transactional(transactionManager = "payTxManager", propagation = Propagation.MANDATORY)
    public <T> T execute(CatalogScope scope, String operation, String key, Object payload, String resourceNo,
                          Class<T> responseType, Supplier<T> work) {
        if (!id(operation) || !id(key) || !id(resourceNo) || scope == null || payload == null
                || responseType == null || work == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        String hash = hash(json.writeValueAsString(List.of(scope.entityNo(), scope.actorNo(),
                scope.platformActor(), payload)));
        var now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        var row = new PayEntCommandDedup();
        row.setTenantNo(scope.tenantNo());
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setCreatedBy(scope.actorNo());
        row.setUpdatedBy(scope.actorNo());
        row.setVersion(0L);
        row.setDeleted(0);
        row.setOperationKind(operation);
        row.setRequestKey(key);
        row.setPayloadHash(hash);
        row.setResourceNo(resourceNo);
        row.setResultStatus("PENDING");
        commands.claim(row);
        var original = commands.lock(scope.tenantNo(), operation, key);
        if (original == null || !hash.equals(original.getPayloadHash())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        if ("COMPLETED".equals(original.getResultStatus())) {
            return json.readValue(original.getResponseSnapshot(), responseType);
        }
        if (!"PENDING".equals(original.getResultStatus())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        T response = work.get();
        int changed = commands.complete(original.getId(), scope.tenantNo(), json.writeValueAsString(response),
                scope.actorNo(), LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS));
        if (changed != 1) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return response;
    }

    /** Read a committed reply before external source lookups; transactional execute still serializes the write. */
    public <T> Optional<T> replay(CatalogScope scope, String operation, String key, Object payload, Class<T> type) {
        if (!id(operation) || !id(key) || scope == null || payload == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        var row = commands.find(scope.tenantNo(), operation, key);
        if (row == null) return Optional.empty();
        String actual = hash(json.writeValueAsString(List.of(scope.entityNo(), scope.actorNo(),
                scope.platformActor(), payload)));
        if (!actual.equals(row.getPayloadHash()) || !"COMPLETED".equals(row.getResultStatus())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return Optional.of(json.readValue(row.getResponseSnapshot(), type));
    }

    private static boolean id(String value) {
        return value != null && !value.isBlank() && value.length() <= 64 && value.equals(value.strip());
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", e);
        }
    }
}
