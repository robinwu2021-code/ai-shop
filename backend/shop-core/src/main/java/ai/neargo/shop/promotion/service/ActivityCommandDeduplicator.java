package ai.neargo.shop.promotion.service;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.promotion.dto.ActivityActor;
import ai.neargo.shop.promotion.entity.PmtCommandDedup;
import ai.neargo.shop.promotion.mapper.PmtCommandDedupMapper;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Command replay is scoped to actor and owner and joins the activity transaction. */
@Component
public class ActivityCommandDeduplicator {
    private final PmtCommandDedupMapper commands;
    private final ObjectMapper json;
    public ActivityCommandDeduplicator(PmtCommandDedupMapper commands, ObjectMapper json) {
        this.commands = commands;
        this.json = json;
    }

    @Transactional(transactionManager = "transactionManager", propagation = Propagation.MANDATORY)
    public <T> T execute(ActivityActor actor, String operation, String key, Object payload,
                         String resourceNo, Class<T> type, Supplier<T> action) {
        try { ContractChecks.id(key); ContractChecks.id(resourceNo); }
        catch (IllegalArgumentException invalid) { throw BizException.of(ErrorCode.BAD_REQUEST); }
        String digest = hash(json.writeValueAsString(Arrays.asList(actor.entityNo(), actor.actorNo(), payload)));
        var now = LocalDateTime.now(ZoneOffset.UTC);
        var row = new PmtCommandDedup();
        row.setTenantNo(actor.tenantNo()); row.setOperationKind(operation); row.setRequestKey(key);
        row.setResourceNo(resourceNo); row.setPayloadHash(digest);
        row.setCreatedAt(now); row.setUpdatedAt(now); row.setCreatedBy(actor.actorNo());
        row.setUpdatedBy(actor.actorNo());
        commands.claim(row);
        var original = commands.lock(actor.tenantNo(), operation, key);
        if (original == null || !digest.equals(original.getPayloadHash())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        if ("COMPLETED".equals(original.getResultStatus())) {
            return json.readValue(original.getResponseSnapshot(), type);
        }
        if (!"PENDING".equals(original.getResultStatus())) throw BizException.of(ErrorCode.CONFLICT);
        T response = action.get();
        if (commands.complete(original.getId(), actor.tenantNo(), json.writeValueAsString(response)) != 1) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return response;
    }

    /** Read an already committed reply before remote preflight; mutable owner facts cannot invalidate a retry. */
    public <T> Optional<T> replay(ActivityActor actor, String operation, String key, Object payload, Class<T> type) {
        try { ContractChecks.id(key); }
        catch (IllegalArgumentException invalid) { throw BizException.of(ErrorCode.BAD_REQUEST); }
        var row = commands.selectOne(Wrappers.<PmtCommandDedup>lambdaQuery()
                .eq(PmtCommandDedup::getTenantNo, actor.tenantNo())
                .eq(PmtCommandDedup::getOperationKind, operation).eq(PmtCommandDedup::getRequestKey, key));
        if (row == null) return Optional.empty();
        String digest = hash(json.writeValueAsString(Arrays.asList(actor.entityNo(), actor.actorNo(), payload)));
        if (!digest.equals(row.getPayloadHash())) throw BizException.of(ErrorCode.CONFLICT);
        return "COMPLETED".equals(row.getResultStatus())
                ? Optional.of(json.readValue(row.getResponseSnapshot(), type)) : Optional.empty();
    }

    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
