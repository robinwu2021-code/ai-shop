package ai.neargo.shop.commerce.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.commerce.entity.CimRequestJournal;
import ai.neargo.shop.commerce.mapper.CommerceMappers.RequestJournalMapper;
import ai.neargo.shop.commerce.policy.ChannelIdentity;
import ai.neargo.shop.commerce.policy.CommerceValidation;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/** journal与本域事实同事务；重放冻结响应，不按当前实体重算。没有网络调用。 */
public final class CommerceCommands {
    private final RequestJournalMapper journals;
    private final ObjectMapper json;
    private final TransactionTemplate write;
    private final TransactionTemplate read;

    public CommerceCommands(
            RequestJournalMapper journals, PlatformTransactionManager manager, ObjectMapper json) {
        this.journals = journals;
        this.json = json;
        write = new TransactionTemplate(manager);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        read = new TransactionTemplate(manager);
        read.setReadOnly(true);
    }

    public <T> T query(Supplier<T> action) {
        return DataScopeContext.executeWithoutScope(() -> read.execute(tx -> action.get()));
    }

    /** 内部回执落库使用短写事务，不持有事务跨网络。 */
    public <T> T atomic(Supplier<T> action) {
        return DataScopeContext.executeWithoutScope(() -> write.execute(tx -> action.get()));
    }

    public <T> Optional<T> replay(
            String entity,
            String store,
            String actor,
            String endpoint,
            String key,
            Object request,
            Class<T> type) {
        CommerceValidation.text(entity, 32);
        CommerceValidation.text(actor, 32);
        CommerceValidation.text(key, 128);
        if (store != null) CommerceValidation.text(store, 32);
        String kind = store == null ? "ENTITY" : "STORE";
        String scope =
                ChannelIdentity.hash(
                        ChannelIdentity.part(entity)
                                + ChannelIdentity.part(store == null ? "" : store));
        return replayScoped(kind, scope, actor, endpoint, key, request, type);
    }

    /**
     * Platform infrastructure has no invented merchant; the existing nullable journal owner stays
     * null.
     */
    public <T> Optional<T> replayPlatform(
            String actor, String endpoint, String key, Object request, Class<T> type) {
        CommerceValidation.text(actor, 32);
        CommerceValidation.text(key, 128);
        return replayScoped(
                "PLATFORM",
                ChannelIdentity.hash("PLATFORM:MAIN"),
                actor,
                endpoint,
                key,
                request,
                type);
    }

    private <T> Optional<T> replayScoped(
            String kind,
            String scope,
            String actor,
            String endpoint,
            String key,
            Object request,
            Class<T> type) {
        String hash = ChannelIdentity.hash(json.writeValueAsString(request));
        return query(
                () -> {
                    var row =
                            journals.selectOne(
                                    Wrappers.<CimRequestJournal>query()
                                            .eq("tenant_no", "MAIN")
                                            .eq("scope_kind", kind)
                                            .eq("scope_key", scope)
                                            .eq("endpoint_key", endpoint)
                                            .eq("key_hash", ChannelIdentity.hash(key)));
                    if (row == null) return Optional.empty();
                    if (!actor.equals(row.getActorNo())
                            || !hash.equals(row.getRequestHash())
                            || !"SUCCEEDED".equals(row.getStatus()))
                        throw BizException.of(ErrorCode.CONFLICT);
                    return Optional.of(json.readValue(row.getResultSnapshot(), type));
                });
    }

    public <T> T execute(
            String entity,
            String store,
            String actor,
            String endpoint,
            String key,
            Object request,
            Class<T> type,
            Supplier<T> action,
            Function<T, String> ref) {
        CommerceValidation.text(entity, 32);
        CommerceValidation.text(actor, 32);
        CommerceValidation.text(key, 128);
        String kind = store == null ? "ENTITY" : "STORE";
        if (store != null) CommerceValidation.text(store, 32);
        String scope =
                ChannelIdentity.hash(
                        ChannelIdentity.part(entity)
                                + ChannelIdentity.part(store == null ? "" : store));
        return executeScoped(entity, kind, scope, actor, endpoint, key, request, type, action, ref);
    }

    /**
     * Original platform scope is distinct from ENTITY/STORE and shares the same committed replay
     * semantics.
     */
    public <T> T executePlatform(
            String actor,
            String endpoint,
            String key,
            Object request,
            Class<T> type,
            Supplier<T> action,
            Function<T, String> ref) {
        CommerceValidation.text(actor, 32);
        CommerceValidation.text(key, 128);
        return executeScoped(
                null,
                "PLATFORM",
                ChannelIdentity.hash("PLATFORM:MAIN"),
                actor,
                endpoint,
                key,
                request,
                type,
                action,
                ref);
    }

    private <T> T executeScoped(
            String entity,
            String kind,
            String scope,
            String actor,
            String endpoint,
            String key,
            Object request,
            Class<T> type,
            Supplier<T> action,
            Function<T, String> ref) {
        String keyHash = ChannelIdentity.hash(key);
        String requestHash = ChannelIdentity.hash(json.writeValueAsString(request));
        try {
            return DataScopeContext.executeWithoutScope(
                    () ->
                            write.execute(
                                    tx -> {
                                        var candidate = new CimRequestJournal();
                                        candidate.setRequestNo(BizKey.next("CIRQ"));
                                        candidate.setEntityNo(entity);
                                        candidate.setEndpointKey(endpoint);
                                        candidate.setKeyHash(keyHash);
                                        candidate.setActorNo(actor);
                                        candidate.setRequestHash(requestHash);
                                        candidate.setScopeKind(kind);
                                        candidate.setScopeKey(scope);
                                        journals.insertOrKeep(candidate);
                                        var journal = journals.lock(kind, scope, endpoint, keyHash);
                                        if (journal == null
                                                || !actor.equals(journal.getActorNo())
                                                || !requestHash.equals(journal.getRequestHash()))
                                            throw BizException.of(ErrorCode.CONFLICT);
                                        if ("SUCCEEDED".equals(journal.getStatus()))
                                            return json.readValue(
                                                    journal.getResultSnapshot(), type);
                                        if (!candidate
                                                .getRequestNo()
                                                .equals(journal.getRequestNo()))
                                            throw BizException.of(ErrorCode.CONFLICT);
                                        T result = action.get();
                                        int changed =
                                                journals.update(
                                                        Wrappers.<CimRequestJournal>update()
                                                                .eq("id", journal.getId())
                                                                .eq("status", "PENDING")
                                                                .set("status", "SUCCEEDED")
                                                                .set(
                                                                        "result_ref",
                                                                        ref.apply(result))
                                                                .set(
                                                                        "result_snapshot",
                                                                        json.writeValueAsString(
                                                                                result))
                                                                .set("updated_by", actor)
                                                                .set(
                                                                        "updated_at",
                                                                        LocalDateTime.now(
                                                                                ZoneOffset.UTC)));
                                        if (changed != 1) throw BizException.of(ErrorCode.CONFLICT);
                                        return result;
                                    }));
        } catch (DuplicateKeyException duplicate) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }
}
