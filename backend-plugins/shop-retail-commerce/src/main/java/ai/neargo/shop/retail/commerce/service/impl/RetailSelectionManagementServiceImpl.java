package ai.neargo.shop.retail.commerce.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.retail.commerce.dto.RetailSelection;
import ai.neargo.shop.retail.commerce.dto.RetailSelectionContracts;
import ai.neargo.shop.retail.commerce.dto.RetailSelectionManagementContracts.PublishRequest;
import ai.neargo.shop.retail.commerce.dto.RetailSelectionManagementContracts.SaveRequest;
import ai.neargo.shop.retail.commerce.dto.RetailSelectionManagementContracts.View;
import ai.neargo.shop.retail.commerce.mapper.RetailSelectionMapper;
import ai.neargo.shop.retail.commerce.service.RetailSelectionManagementService;
import ai.neargo.shop.retail.commerce.service.RetailSelectionService;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.List;
import java.util.function.Supplier;

/**
 * Durable draft/version and idempotency semantics; original-owner reads finish before atomic local
 * publication.
 */
public final class RetailSelectionManagementServiceImpl
        implements RetailSelectionManagementService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate write;
    private final ObjectMapper json;
    private final RetailSelectionMapper repository;
    private final RetailSelectionService publication;

    public RetailSelectionManagementServiceImpl(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            ObjectMapper json,
            RetailSelectionMapper repository,
            RetailSelectionService publication) {
        this.jdbc = jdbc;
        this.write = new TransactionTemplate(manager);
        this.json = json;
        this.repository = repository;
        this.publication = publication;
    }

    @Override
    public List<View> list(String entity, String store, int offset, int size) {
        identities(entity, store, null);
        require(offset >= 0 && size >= 1 && size <= 100);
        return jdbc
                .query(
                        "SELECT selection_no FROM rtl_channel_selection_draft WHERE entity_no=? AND"
                                + " store_no=? ORDER BY selection_no LIMIT ? OFFSET ?",
                        (rs, i) -> rs.getString(1),
                        entity,
                        store,
                        size,
                        offset)
                .stream()
                .map(n -> detail(entity, store, n))
                .toList();
    }

    @Override
    public View detail(String entity, String store, String no) {
        identities(entity, store, no);
        var rows =
                jdbc.query(
                        "SELECT version,payload_json FROM rtl_channel_selection_draft WHERE"
                                + " entity_no=? AND store_no=? AND selection_no=?",
                        (rs, i) ->
                                new View(
                                        entity,
                                        store,
                                        no,
                                        rs.getLong(1),
                                        json.readValue(
                                                rs.getString(2),
                                                RetailSelectionContracts.Publish.class),
                                        repository.current(entity, store, no)),
                        entity,
                        store,
                        no);
        if (rows.isEmpty()) throw BizException.of(ErrorCode.NOT_FOUND);
        return rows.getFirst();
    }

    @Override
    public View save(
            String entity, String store, String no, String actor, SaveRequest req, String key) {
        identities(entity, store, no);
        identity(actor);
        require(req != null && req.expectedVersion() != null && req.expectedVersion() >= 0);
        var replay = replay(entity, store, no, actor, "SAVE", key, req);
        if (replay != null) return replay;
        identity(req.connectionNo());
        identity(req.connectionRevisionNo());
        if (req.expectedPublishedRevision() != null) identity(req.expectedPublishedRevision());
        require(req.picks() != null && !req.picks().isEmpty() && req.picks().size() <= 100);
        var goods = new HashSet<String>();
        var skus = new HashSet<String>();
        for (var pick : req.picks()) {
            require(pick != null);
            identity(pick.goodsNo());
            require(goods.add(pick.goodsNo()) && pick.skuNos() != null && !pick.skuNos().isEmpty());
            for (var sku : pick.skuNos()) {
                identity(sku);
                require(skus.add(sku));
            }
        }
        require(skus.size() <= 100);
        var command =
                new RetailSelectionContracts.Publish(
                        entity,
                        store,
                        no,
                        req.expectedPublishedRevision(),
                        req.connectionNo(),
                        req.connectionRevisionNo(),
                        req.picks());
        return execute(
                entity,
                store,
                no,
                actor,
                "SAVE",
                key,
                req,
                () -> {
                    if (req.expectedVersion() == 0) {
                        require(
                                repository.current(entity, store, no) == null
                                        && req.expectedPublishedRevision() == null);
                        jdbc.update(
                                "INSERT INTO"
                                    + " rtl_channel_selection_draft(entity_no,store_no,selection_no,version,"
                                    + "payload_json) VALUES(?,?,?,?,?)",
                                entity,
                                store,
                                no,
                                1,
                                json.writeValueAsString(command));
                    } else if (jdbc.update(
                                    "UPDATE rtl_channel_selection_draft SET"
                                        + " version=version+1,payload_json=? WHERE entity_no=? AND"
                                        + " store_no=? AND selection_no=? AND version=?",
                                    json.writeValueAsString(command),
                                    entity,
                                    store,
                                    no,
                                    req.expectedVersion())
                            != 1) throw BizException.of(ErrorCode.CONFLICT);
                    return detail(entity, store, no);
                });
    }

    @Override
    public View publish(
            String entity, String store, String no, String actor, PublishRequest req, String key) {
        identities(entity, store, no);
        identity(actor);
        require(req != null && req.expectedVersion() != null && req.expectedVersion() > 0);
        var replay = replay(entity, store, no, actor, "PUBLISH", key, req);
        if (replay != null) return replay;
        var before = detail(entity, store, no);
        if (before.version() != req.expectedVersion()) throw BizException.of(ErrorCode.CONFLICT);
        // All remote authorization and product reads precede the short write transaction.
        var prepared = publication.prepareWithEvidence(before.draft());
        return execute(
                entity,
                store,
                no,
                actor,
                "PUBLISH",
                key,
                req,
                () -> {
                    var after = detail(entity, store, no);
                    if (!before.equals(after)) throw BizException.of(ErrorCode.CONFLICT);
                    if (jdbc.update(
                                    "UPDATE rtl_channel_selection_draft SET version=version+1 WHERE"
                                            + " entity_no=? AND store_no=? AND selection_no=? AND"
                                            + " version=?",
                                    entity,
                                    store,
                                    no,
                                    before.version())
                            != 1) throw BizException.of(ErrorCode.CONFLICT);
                    publication.assertFresh(prepared);
                    repository.publish(prepared.selection(), before.draft().expectedRevision());
                    return detail(entity, store, no);
                });
    }

    private View replay(
            String e, String s, String no, String actor, String action, String key, Object req) {
        key(key);
        String requestHash = PricingSnapshotHash.of(List.of(no, req));
        var values =
                jdbc.query(
                        "SELECT actor_no,request_hash,result_json FROM"
                            + " rtl_channel_selection_command WHERE entity_no=? AND store_no=? AND"
                            + " action_key=? AND key_hash=?",
                        (rs, i) -> {
                            if (!actor.equals(rs.getString(1))
                                    || !requestHash.equals(rs.getString(2))
                                    || rs.getString(3) == null)
                                throw BizException.of(ErrorCode.CONFLICT);
                            return json.readValue(rs.getString(3), View.class);
                        },
                        e,
                        s,
                        action,
                        PricingSnapshotHash.of(key));
        return values.isEmpty() ? null : values.getFirst();
    }

    private View execute(
            String e,
            String s,
            String no,
            String actor,
            String action,
            String key,
            Object req,
            Supplier<View> change) {
        try {
            return write.execute(
                    tx -> {
                        var prior = replay(e, s, no, actor, action, key, req);
                        if (prior != null) return prior;
                        jdbc.update(
                                "INSERT INTO"
                                    + " rtl_channel_selection_command(entity_no,store_no,action_key,key_hash,"
                                    + "actor_no,request_hash,result_json) VALUES(?,?,?,?,?,?,NULL)",
                                e,
                                s,
                                action,
                                PricingSnapshotHash.of(key),
                                actor,
                                PricingSnapshotHash.of(List.of(no, req)));
                        var result = change.get();
                        jdbc.update(
                                "UPDATE rtl_channel_selection_command SET result_json=? WHERE"
                                    + " entity_no=? AND store_no=? AND action_key=? AND key_hash=?",
                                json.writeValueAsString(result),
                                e,
                                s,
                                action,
                                PricingSnapshotHash.of(key));
                        return result;
                    });
        } catch (DuplicateKeyException race) {
            var winner = replay(e, s, no, actor, action, key, req);
            if (winner != null) return winner;
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private static void identities(String e, String s, String no) {
        identity(e);
        identity(s);
        if (no != null) identity(no);
    }

    private static void identity(String v) {
        try {
            RetailSelection.key(v);
        } catch (IllegalArgumentException invalid) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    private static void key(String key) {
        require(
                key != null
                        && !key.isBlank()
                        && key.length() <= 128
                        && key.equals(key.strip())
                        && key.codePoints().noneMatch(Character::isISOControl));
    }

    private static void require(boolean ok) {
        if (!ok) throw BizException.of(ErrorCode.BAD_REQUEST);
    }
}
