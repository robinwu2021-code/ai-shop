package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.CheckCollaborationContracts.CheckVersion;
import ai.neargo.shop.food.dto.CheckCollaborationContracts.FreezeReq;
import ai.neargo.shop.food.dto.CheckCollaborationContracts.FreezeVO;
import ai.neargo.shop.food.dto.CheckCollaborationContracts.LineMoveReq;
import ai.neargo.shop.food.dto.CheckCollaborationContracts.LineMoveVO;
import ai.neargo.shop.food.dto.CheckCollaborationContracts.MoveReq;
import ai.neargo.shop.food.dto.CheckCollaborationContracts.MoveVO;
import ai.neargo.shop.food.dto.CheckCollaborationContracts.ReceivableBatch;
import ai.neargo.shop.food.dto.CheckCollaborationContracts.ReceivablePreview;
import ai.neargo.shop.food.dto.OperationVO;
import ai.neargo.shop.food.mapper.FoodBusinessOperationsMapper;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.service.CheckBusinessGuardService;
import ai.neargo.shop.food.service.CheckCollaborationService;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.trade.IndustryOrderPort;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Allocation;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Result;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Atomic current-service membership; source batches, core bindings and seats remain historical
 * facts.
 */
public class CheckCollaborationServiceImpl implements CheckCollaborationService {
    private final ThreadLocal<List<ReceivableBatch>> returnPreview = new ThreadLocal<>();
    private final TransactionTemplate tx;
    private Supplier<IndustryOrderPort> orderSources;

    @Autowired(required = false)
    public void setOrderSources(ObjectProvider<IndustryOrderPort> provider) {
        orderSources = provider::getIfAvailable;
    }

    private final BusinessCommandJournal journal;
    private final FoodBusinessOperationsMapper mapper;
    private final CheckBusinessGuardService guard;
    private final IdempotencyService idem;
    private final ObjectMapper json;

    public CheckCollaborationServiceImpl(
            FoodBusinessOperationsMapper mapper,
            CheckBusinessGuardService guard,
            IdempotencyService idem,
            ObjectMapper json,
            PlatformTransactionManager manager) {
        this.tx = new TransactionTemplate(manager);
        this.mapper = mapper;
        journal = new BusinessCommandJournal(mapper, json);
        this.guard = guard;
        this.idem = idem;
        this.json = json;
    }

    @Override
    public String sourceHash(String e, String store, String check) {
        var before = coreSources(e, store, check);
        String hash = economicHash(before);
        String result = tx.execute(t -> localSourceHash(e, store, check, hash));
        if (!hash.equals(economicHash(coreSources(e, store, check)))) conflict();
        return result;
    }

    @Override
    public ReceivablePreview previewReceivable(String e, String store, String check) {
        var sources = coreSources(e, store, check);
        returnPreview.set(null);
        String hash =
                tx.execute(
                        t -> {
                            var owned = mapper.checkCollaborationSelectOrderLine(e, store, check);
                            var rows = new ArrayList<ReceivableBatch>();
                            sources.forEach(
                                    (batch, result) -> {
                                        var refs =
                                                owned.stream()
                                                        .filter(
                                                                row ->
                                                                        batch.equals(
                                                                                row.text(
                                                                                        "batch_no")))
                                                        .map(row -> row.text("source_line_ref"))
                                                        .collect(Collectors.toSet());
                                        long original =
                                                result.allocations().stream()
                                                        .filter(
                                                                line ->
                                                                        refs.contains(
                                                                                line
                                                                                        .sourceLineNo()))
                                                        .mapToLong(Allocation::amountMinor)
                                                        .reduce(0, Math::addExact);
                                        long effective =
                                                result.actualAllocations().stream()
                                                        .filter(
                                                                line ->
                                                                        refs.contains(
                                                                                line
                                                                                        .sourceLineNo()))
                                                        .mapToLong(Allocation::amountMinor)
                                                        .reduce(0, Math::addExact);
                                        rows.add(
                                                new ReceivableBatch(
                                                        batch,
                                                        result.orderNo(),
                                                        result.subOrderNo(),
                                                        result.currency(),
                                                        original,
                                                        effective,
                                                        Math.subtractExact(effective, original),
                                                        result.amendmentSourceHash()));
                                    });
                            returnPreview.set(rows);
                            return localSourceHash(e, store, check, economicHash(sources));
                        });
        var rows = returnPreview.get();
        returnPreview.remove();
        if (!economicHash(sources).equals(economicHash(coreSources(e, store, check)))) conflict();
        return new ReceivablePreview(check, rows, hash, false);
    }

    private Map<String, Result> coreSources(String e, String store, String check) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Business source owner calls must not hold FOOD locks");
        if (orderSources == null) return Map.of();
        var owner = orderSources.get();
        if (owner == null) throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
        var result = new TreeMap<String, Result>();
        for (var row : mapper.currentCoreBindings(e, store, check)) {
            var actual =
                    owner.getForStore(
                            new Scope("MAIN", e, store), "SYSTEM", row.text("payment_root_no"));
            if (!row.text("store_order_no").equals(actual.subOrderNo())) conflict();
            result.put(row.text("batch_no"), actual);
        }
        return result;
    }

    private String economicHash(Map<String, Result> sources) {
        var rows = new TreeMap<String, Object>();
        sources.forEach(
                (batch, result) ->
                        rows.put(
                                batch,
                                Arrays.asList(
                                        result.orderNo(),
                                        result.subOrderNo(),
                                        result.actualReceivableMinor(),
                                        result.actualAllocations(),
                                        result.amendmentSourceHash())));
        return FoodCommandFingerprint.hash(json.writeValueAsString(rows));
    }

    private String localSourceHash(String e, String store, String check, String coreHash) {
        long version = version(e, store, check);
        var source = mapper.checkCollaborationSelectOrderLine(e, store, check);
        return FoodCommandFingerprint.hash(
                "CHECK_SERVICE_SOURCE_V1",
                e,
                store,
                check,
                Long.toString(version),
                json.writeValueAsString(source),
                json.writeValueAsString(mapper.checkFormEvidence(e, store, check)),
                Objects.toString(coreHash, ""));
    }

    @Override
    public String currentLineOwner(String e, String store, String line) {
        var rows = mapper.checkCollaborationSelectOrderLine2(e, store, line);
        if (rows.size() != 1 || rows.getFirst().text("current_check_no") == null)
            throw BizException.of(ErrorCode.CONFLICT);
        return (String) rows.getFirst().text("current_check_no");
    }

    @Override
    public OperationVO releaseService(
            String e,
            String store,
            String actor,
            String check,
            long expected,
            String reason,
            String key) {
        validate(e, store, actor, check, key);
        if (reason == null || reason.isBlank()) bad();
        return tx.execute(
                t ->
                        journal.execute(
                                key,
                                FoodCommandFingerprint.scope("RELEASE_CHECK_SERVICE", e, store),
                                actor,
                                FoodCommandFingerprint.hash(check, Long.toString(expected), reason),
                                OperationVO.class,
                                () -> {
                                    guard.assertPartyMutable(e, store, check);
                                    if (version(e, store, check) != expected) conflict();
                                    var lines =
                                            mapper.checkCollaborationSelectOrderLine3(
                                                    e, store, check);
                                    if (mapper.checkCollaborationCountOrderBatch(e, store, check)
                                            > 0) conflict();
                                    for (var row : lines) {
                                        String line = (String) row.get("food_line_no");
                                        if (mapper.checkCollaborationCountKitchenTask(e, line) > 0)
                                            conflict();
                                        if (mapper.checkCollaborationCountReturnItem(e, line) > 0)
                                            conflict();
                                    }
                                    if (mapper.checkCollaborationCountDiningEntitlement(e, check)
                                            > 0) conflict();
                                    var tables =
                                            mapper.checkCollaborationSelectTable(e, store, check);
                                    if (mapper.checkCollaborationCountTableOccupancy(e, check) > 0)
                                        conflict();
                                    mapper.checkCollaborationUpdateTableOccupancy(actor, e, check);
                                    for (var row : tables)
                                        mapper.checkCollaborationUpdateTable(
                                                actor, e, store, row.text("table_no"));
                                    mapper.checkCollaborationUpdateGuestSession(
                                            actor, e, store, check);
                                    mapper.checkCollaborationUpdateCheckCart(
                                            actor, e, store, check);
                                    // Service release is independent of receivable/payment
                                    // completion; status and closed_at stay untouched.
                                    mapper.checkCollaborationUpdateCheck(actor, e, store, check);
                                    guard.invalidateShares(e, store, check, actor, reason);
                                    String op = BizKey.next("FCOP");
                                    audit(
                                            e,
                                            store,
                                            actor,
                                            op,
                                            "CHECK_SERVICE_RELEASE",
                                            check,
                                            reason,
                                            key,
                                            "SERVICE_RELEASED");
                                    return new OperationVO(op, "SUCCEEDED", expected + 1, check);
                                }));
    }

    @Override
    public MoveVO moveBatches(String entity, String store, String actor, MoveReq req, String key) {
        validate(entity, store, actor, key);
        if (req == null
                || req.source() == null
                || req.target() == null
                || req.source().checkNo().equals(req.target().checkNo())
                || req.batchNos() == null
                || req.batchNos().isEmpty()
                || req.batchNos().size() > 100
                || new HashSet<>(req.batchNos()).size() != req.batchNos().size()
                || req.reason() == null
                || req.reason().isBlank()) bad();
        return tx.execute(
                t ->
                        journal.execute(
                                key,
                                FoodCommandFingerprint.scope("MOVE_CHECK_BATCHES", entity, store),
                                actor,
                                FoodCommandFingerprint.hash(json.writeValueAsString(req)),
                                MoveVO.class,
                                () -> {
                                    var ordered =
                                            List.of(req.source(), req.target()).stream()
                                                    .sorted(
                                                            Comparator.comparing(
                                                                    CheckVersion::checkNo))
                                                    .toList();
                                    for (var c : ordered) {
                                        guard.assertPartyMutable(entity, store, c.checkNo());
                                        if (version(entity, store, c.checkNo()) != c.version())
                                            conflict();
                                    }
                                    String op = BizKey.next("FCOP");
                                    var batches = req.batchNos().stream().sorted().toList();
                                    for (var batch : batches) {
                                        var origin =
                                                mapper.checkCollaborationSelectOrderBatch(
                                                        entity, store, batch);
                                        if (origin.size() != 1
                                                || !"ACCEPTED"
                                                        .equals(origin.getFirst().text("status")))
                                            conflict();
                                        if (mapper.checkCollaborationCountCheckLineMembership(
                                                        entity, batch)
                                                > 0) conflict();
                                        var memberships =
                                                mapper.checkCollaborationSelectCheckBatchMembership(
                                                        entity, batch);
                                        if (memberships.size() > 1) conflict();
                                        String owner =
                                                (String)
                                                        (memberships.isEmpty()
                                                                ? origin.getFirst().text("check_no")
                                                                : memberships
                                                                        .getFirst()
                                                                        .text("check_no"));
                                        if (!req.source().checkNo().equals(owner)) conflict();
                                        if (mapper.checkCollaborationCountReturn(
                                                        entity, store, batch)
                                                > 0) conflict();
                                        mapper.checkCollaborationUpdateCheckBatchMembership(
                                                actor, entity, batch);
                                        mapper.checkCollaborationInsertCheckBatchMembership(
                                                entity,
                                                BizKey.next("FCBM"),
                                                req.target().checkNo(),
                                                batch,
                                                op,
                                                actor,
                                                actor);
                                    }
                                    for (var c : ordered) {
                                        guard.invalidateShares(
                                                entity, store, c.checkNo(), actor, req.reason());
                                        mapper.checkCollaborationUpdateCheck2(
                                                actor, entity, store, c.checkNo(), c.version());
                                        // Draft lines refer to their historical seat/check. Requote
                                        // on a new cart rather than moving identities.
                                        mapper.checkCollaborationUpdateCheckCart2(
                                                actor, entity, store, c.checkNo());
                                    }
                                    var result =
                                            new MoveVO(
                                                    op,
                                                    req.source().checkNo(),
                                                    req.target().checkNo(),
                                                    batches,
                                                    req.source().version() + 1,
                                                    req.target().version() + 1,
                                                    "SUCCEEDED");
                                    audit(
                                            entity,
                                            store,
                                            actor,
                                            op,
                                            "CHECK_BATCH_MOVE",
                                            req.source().checkNo(),
                                            req.reason(),
                                            key,
                                            json.writeValueAsString(result));
                                    return result;
                                }));
    }

    @Override
    public LineMoveVO moveLines(String e, String store, String actor, LineMoveReq req, String key) {
        validate(e, store, actor, key);
        if (req == null
                || req.source() == null
                || req.target() == null
                || req.source().checkNo().equals(req.target().checkNo())
                || req.foodLineNos() == null
                || req.foodLineNos().isEmpty()
                || req.foodLineNos().size() > 100
                || new HashSet<>(req.foodLineNos()).size() != req.foodLineNos().size()
                || req.reason() == null
                || req.reason().isBlank()) bad();
        return tx.execute(
                t ->
                        journal.execute(
                                key,
                                FoodCommandFingerprint.scope("MOVE_CHECK_LINES", e, store),
                                actor,
                                FoodCommandFingerprint.hash(json.writeValueAsString(req)),
                                LineMoveVO.class,
                                () -> {
                                    var ordered =
                                            List.of(req.source(), req.target()).stream()
                                                    .sorted(
                                                            Comparator.comparing(
                                                                    CheckVersion::checkNo))
                                                    .toList();
                                    for (var c : ordered) {
                                        guard.assertPartyMutable(e, store, c.checkNo());
                                        if (version(e, store, c.checkNo()) != c.version())
                                            conflict();
                                    }
                                    String op = BizKey.next("FCOP");
                                    // Whole original lines (including their complete component
                                    // tree) preserve original quantities and allocations.
                                    for (String line :
                                            req.foodLineNos().stream().sorted().toList()) {
                                        var rows =
                                                mapper.checkCollaborationSelectOrderLine4(
                                                        e, store, line);
                                        if (rows.size() != 1) conflict();
                                        var original = rows.getFirst();
                                        var owners =
                                                mapper.checkCollaborationSelectCheckLineMembership(
                                                        e, line);
                                        if (owners.isEmpty())
                                            owners =
                                                    mapper
                                                            .checkCollaborationSelectCheckBatchMembership2(
                                                                    e, original.text("batch_no"));
                                        if (owners.size() > 1) conflict();
                                        String owner =
                                                (String)
                                                        (owners.isEmpty()
                                                                ? original.text("check_no")
                                                                : owners.getFirst()
                                                                        .text("check_no"));
                                        if (!req.source().checkNo().equals(owner)) conflict();
                                        if (mapper.checkCollaborationCountReturnItem2(e, line) > 0)
                                            conflict();
                                        if (mapper.checkCollaborationCountSettlementItem(
                                                        e, original.text("batch_no"))
                                                > 0) conflict();
                                        mapper.checkCollaborationUpdateCheckLineMembership(
                                                actor, e, line);
                                        mapper.checkCollaborationInsertCheckLineMembership(
                                                e,
                                                store,
                                                BizKey.next("FCLM"),
                                                req.target().checkNo(),
                                                original.text("batch_no"),
                                                line,
                                                line,
                                                original.text("seat_no"),
                                                original.decimal("qty"),
                                                original.text("unit"),
                                                op,
                                                actor,
                                                actor);
                                    }
                                    for (var c : ordered) {
                                        guard.invalidateShares(
                                                e, store, c.checkNo(), actor, req.reason());
                                        mapper.checkCollaborationUpdateCheck3(
                                                actor, e, store, c.checkNo(), c.version());
                                        mapper.checkCollaborationUpdateCheckCart3(
                                                actor, e, store, c.checkNo());
                                    }
                                    var result =
                                            new LineMoveVO(
                                                    op,
                                                    req.source().checkNo(),
                                                    req.target().checkNo(),
                                                    req.foodLineNos(),
                                                    "SUCCEEDED");
                                    audit(
                                            e,
                                            store,
                                            actor,
                                            op,
                                            "CHECK_LINE_MOVE",
                                            req.source().checkNo(),
                                            req.reason(),
                                            key,
                                            json.writeValueAsString(result));
                                    return result;
                                }));
    }

    @Override
    public FreezeVO freeze(
            String entity, String store, String actor, String check, FreezeReq req, String key) {
        validate(entity, store, actor, key);
        if (req == null) bad();
        String coreHash = economicHash(coreSources(entity, store, check));
        return tx.execute(
                t ->
                        journal.execute(
                                key,
                                FoodCommandFingerprint.scope("FREEZE_CHECK", entity, store),
                                actor,
                                FoodCommandFingerprint.hash(check, json.writeValueAsString(req)),
                                FreezeVO.class,
                                () -> {
                                    guard.assertMutable(entity, store, check);
                                    guard.assertNoPendingChanges(entity, store, check);
                                    if (version(entity, store, check) != req.checkVersion()
                                            || !localSourceHash(entity, store, check, coreHash)
                                                    .equals(req.sourceHash())) conflict();
                                    String no = BizKey.next("FCFR");
                                    mapper.checkCollaborationInsertCheckFreeze(
                                            entity,
                                            store,
                                            no,
                                            check,
                                            check,
                                            req.sourceHash(),
                                            req.reason(),
                                            actor,
                                            actor);
                                    audit(
                                            entity,
                                            store,
                                            actor,
                                            no,
                                            "CHECK_FREEZE",
                                            check,
                                            req.reason(),
                                            key,
                                            req.sourceHash());
                                    return new FreezeVO(no, check, req.sourceHash(), "ACTIVE", 0);
                                }));
    }

    @Override
    public FreezeVO release(
            String entity,
            String store,
            String actor,
            String no,
            long expected,
            String reason,
            String key) {
        validate(entity, store, actor, key);
        if (reason == null || reason.isBlank()) bad();
        return tx.execute(
                t ->
                        journal.execute(
                                key,
                                FoodCommandFingerprint.scope("RELEASE_CHECK_FREEZE", entity, store),
                                actor,
                                FoodCommandFingerprint.hash(no, Long.toString(expected), reason),
                                FreezeVO.class,
                                () -> {
                                    var initial =
                                            mapper.checkCollaborationSelectCheckFreeze(
                                                    entity, store, no);
                                    if (initial.size() != 1)
                                        throw BizException.of(ErrorCode.NOT_FOUND);
                                    String check = (String) initial.getFirst().text("check_no");
                                    version(entity, store, check);
                                    var rows =
                                            mapper.checkCollaborationSelectCheckFreeze2(
                                                    entity, store, no);
                                    var row = rows.getFirst();
                                    if (((Number) row.get("version")).longValue() != expected
                                            || !"ACTIVE".equals(row.text("status"))) conflict();
                                    if (mapper.checkCollaborationCountSettlementItem2(
                                                    entity, check, store)
                                            > 0) conflict();
                                    mapper.checkCollaborationUpdateCheckFreeze(actor, no, entity);
                                    audit(
                                            entity,
                                            store,
                                            actor,
                                            no,
                                            "CHECK_UNFREEZE",
                                            check,
                                            reason,
                                            key,
                                            (String) row.text("source_hash"));
                                    return new FreezeVO(
                                            no,
                                            check,
                                            (String) row.text("source_hash"),
                                            "RELEASED",
                                            expected + 1);
                                }));
    }

    private long version(String e, String s, String c) {
        var rows = mapper.checkCollaborationSelectCheck(e, s, c);
        if (rows.size() != 1) throw BizException.of(ErrorCode.NOT_FOUND);
        return ((Number) rows.getFirst().get("version")).longValue();
    }

    private void audit(
            String e,
            String s,
            String actor,
            String no,
            String action,
            String object,
            String reason,
            String key,
            String after) {
        mapper.checkCollaborationInsertActionLog(
                e,
                s,
                BizKey.next("FCAC"),
                object,
                action,
                actor,
                reason,
                FoodCommandFingerprint.hash(key),
                FoodCommandFingerprint.hash(after),
                actor,
                actor);
    }

    private static void validate(String... values) {
        for (var v : values) if (v == null || v.isBlank() || v.length() > 128) bad();
    }

    private static void bad() {
        throw BizException.of(ErrorCode.BAD_REQUEST);
    }

    private static void conflict() {
        throw BizException.of(ErrorCode.CONFLICT);
    }
}
