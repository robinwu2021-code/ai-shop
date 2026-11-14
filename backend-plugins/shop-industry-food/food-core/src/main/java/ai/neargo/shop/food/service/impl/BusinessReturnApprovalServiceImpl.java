package ai.neargo.shop.food.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.BusinessReturnApprovalContracts.ApprovalVO;
import ai.neargo.shop.food.dto.BusinessReturnApprovalContracts.ApproveReq;
import ai.neargo.shop.food.dto.BusinessReturnApprovalContracts.ReplacementReq;
import ai.neargo.shop.food.entity.FnbCheckCart;
import ai.neargo.shop.food.entity.FnbCoreOrderBinding;
import ai.neargo.shop.food.entity.FnbOrderBatch;
import ai.neargo.shop.food.entity.FnbReturn;
import ai.neargo.shop.food.entity.FnbReturnAmendment;
import ai.neargo.shop.food.mapper.FoodMappers.CheckCartMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CoreOrderBindingMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderBatchMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ReturnMapper;
import ai.neargo.shop.food.mapper.FoodReturnAmendmentMapper;
import ai.neargo.shop.food.policy.BusinessReturnDocuments;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.service.BusinessReturnApprovalService;
import ai.neargo.shop.food.service.CheckBusinessGuardService;
import ai.neargo.shop.spi.trade.IndustryAmendmentSourcePort;
import ai.neargo.shop.spi.trade.IndustryFulfillmentPort.Reference;
import ai.neargo.shop.spi.trade.IndustryOrderAmendmentPort;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.spi.trade.PricingSourceRef;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Approval stays pending until both kitchen disposition and the original unpaid debt owner confirm.
 */
public class BusinessReturnApprovalServiceImpl implements BusinessReturnApprovalService {
    private final FoodReturnAmendmentMapper intents;
    private final ReturnMapper returns;
    private final OrderBatchMapper batches;
    private final CoreOrderBindingMapper bindings;
    private final CheckCartMapper carts;
    private final IndustryAmendmentSourcePort sources;
    private final ObjectProvider<IndustryOrderAmendmentPort> owner;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    @Autowired private CheckBusinessGuardService checkGuard;

    public BusinessReturnApprovalServiceImpl(
            FoodReturnAmendmentMapper intents,
            ReturnMapper returns,
            OrderBatchMapper batches,
            CoreOrderBindingMapper bindings,
            CheckCartMapper carts,
            IndustryAmendmentSourcePort sources,
            ObjectProvider<IndustryOrderAmendmentPort> owner,
            ObjectMapper json,
            PlatformTransactionManager manager) {
        this.intents = intents;
        this.returns = returns;
        this.batches = batches;
        this.bindings = bindings;
        this.carts = carts;
        this.sources = sources;
        this.owner = owner;
        this.json = json;
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public ApprovalVO approve(
            String e, String s, String actor, String number, ApproveReq req, String key) {
        outside();
        keys(e, s, actor, number);
        if (req == null
                || req.version() == null
                || req.version() < 0
                || req.reason() == null
                || req.reason().isBlank()
                || req.reason().length() > 255
                || key == null
                || key.isBlank()
                || key.length() > 128) throw bad();
        String operation = FoodCommandFingerprint.hash("return-approval-key:v1", e, s, key),
                hash =
                        FoodCommandFingerprint.hash(
                                "return-approval:v1", actor, number, json.writeValueAsString(req));
        var previous = read(() -> intent(e, s, number, false));
        if (previous != null) {
            if (!operation.equals(previous.getOperationKey())
                    || !hash.equals(previous.getRequestHash())) throw conflict();
            return execute(previous);
        }
        var origin = read(() -> head(e, s, number, false));
        var row =
                write(
                        () -> {
                            if (checkGuard != null)
                                checkGuard.lockBatchChecks(e, s, origin.getBatchNo());
                            var current = head(e, s, number, true);
                            var frozen = BusinessReturnDocuments.read(current, json);
                            var prior = intent(e, s, number, false);
                            if (prior != null) {
                                if (!operation.equals(prior.getOperationKey())
                                        || !hash.equals(prior.getRequestHash())) throw conflict();
                                return prior;
                            }
                            if (!req.version().equals(current.getVersion())
                                    || !"BUSINESS_REQUESTED".equals(current.getStatus()))
                                throw conflict();
                            var batch = batch(e, s, current.getBatchNo(), true);
                            if (req.replacementCartNo() != null) {
                                var cart =
                                        carts.selectOne(
                                                Wrappers.<FnbCheckCart>lambdaQuery()
                                                        .eq(FnbCheckCart::getEntityNo, e)
                                                        .eq(FnbCheckCart::getStoreNo, s)
                                                        .eq(
                                                                FnbCheckCart::getCartNo,
                                                                req.replacementCartNo()));
                                if (cart == null
                                        || !Objects.equals(batch.getCheckNo(), cart.getCheckNo())
                                        || req.replacementCartNo().equals(batch.getCartNo()))
                                    throw conflict();
                            }
                            var created = new FnbReturnAmendment();
                            created.setEntityNo(e);
                            created.setStoreNo(s);
                            created.setApprovalNo(BizKey.next("FAPR"));
                            created.setReturnNo(number);
                            created.setActorNo(actor);
                            created.setOperationKey(operation);
                            created.setRequestHash(hash);
                            created.setSourceHash(frozen.sourceHash());
                            created.setRequestSnapshot(json.writeValueAsString(req));
                            created.setReplacementCartNo(req.replacementCartNo());
                            created.setStatus(
                                    "HANDLED".equals(current.getKitchenStatus())
                                            ? "PREPARED"
                                            : "WAIT_KITCHEN");
                            stamp(created, actor);
                            if (intents.insert(created) != 1) throw conflict();
                            current.setStatus("APPROVAL_PENDING");
                            current.setApprovalRef(created.getApprovalNo());
                            current.setUpdatedBy(actor);
                            if (returns.updateById(current) != 1) throw conflict();
                            return created;
                        });
        return execute(row);
    }

    @Override
    public ApprovalVO get(String e, String s, String actor, String number) {
        outside();
        keys(e, s, actor, number);
        var row = read(() -> intent(e, s, number, true));
        return execute(row);
    }

    private ApprovalVO execute(FnbReturnAmendment initial) {
        String e = initial.getEntityNo(), s = initial.getStoreNo(), number = initial.getReturnNo();
        if ("CONFIRMED".equals(initial.getStatus())) return view(initial);
        var row = read(() -> head(e, s, number, false));
        if (!"HANDLED".equals(row.getKitchenStatus())) return view(initial);
        try {
            if (checkGuard != null)
                write(
                        () -> {
                            checkGuard.lockBatchChecks(e, s, row.getBatchNo());
                            checkGuard.invalidateBatchShares(
                                    e,
                                    s,
                                    row.getBatchNo(),
                                    initial.getActorNo(),
                                    "RETURN_APPROVAL");
                            return null;
                        });
            var batch = read(() -> batch(e, s, row.getBatchNo(), false));
            var binding =
                    read(
                            () ->
                                    bindings.selectOne(
                                            Wrappers.<FnbCoreOrderBinding>lambdaQuery()
                                                    .eq(FnbCoreOrderBinding::getEntityNo, e)
                                                    .eq(
                                                            FnbCoreOrderBinding::getBatchNo,
                                                            row.getBatchNo())
                                                    .eq(FnbCoreOrderBinding::getStatus, "BOUND")));
            if (binding == null) throw conflict();
            var ref =
                    new Reference(
                            new Scope("MAIN", e, s),
                            new PricingSourceRef(
                                    "FOOD",
                                    "CART",
                                    batch.getCartNo(),
                                    batch.getCartVersion(),
                                    null,
                                    null),
                            binding.getPaymentRootNo(),
                            binding.getStoreOrderNo());
            var provider = provider();
            String ownerKey = "RETURN_APPROVAL_V1:" + initial.getApprovalNo();
            var known = provider.query(ref, initial.getActorNo(), ownerKey);
            IndustryOrderAmendmentPort.Command command;
            if (initial.getCommandSnapshot() == null) {
                var evidence = sources.get(ref, number);
                var req = json.readValue(initial.getRequestSnapshot(), ApproveReq.class);
                command =
                        new IndustryOrderAmendmentPort.Command(
                                ref,
                                initial.getActorNo(),
                                known.subOrderVersion(),
                                number,
                                evidence.sourceHash(),
                                evidence.lines(),
                                req.reason(),
                                initial.getReplacementCartNo());
                final var frozen = command;
                write(
                        () -> {
                            var current = intent(e, s, number, true);
                            if (current.getCommandSnapshot() == null) {
                                current.setCommandSnapshot(json.writeValueAsString(frozen));
                                current.setStatus("PREPARED");
                                current.setUpdatedBy(initial.getActorNo());
                                if (intents.updateById(current) != 1) throw conflict();
                            }
                            return null;
                        });
                command =
                        read(
                                () ->
                                        json.readValue(
                                                intent(e, s, number, true).getCommandSnapshot(),
                                                IndustryOrderAmendmentPort.Command.class));
            } else
                command =
                        json.readValue(
                                initial.getCommandSnapshot(),
                                IndustryOrderAmendmentPort.Command.class);
            var receipt =
                    "NOT_STARTED".equals(known.status())
                            ? provider.amend(command, ownerKey)
                            : known;
            if (receipt == null || !ref.equals(receipt.reference())) throw conflict();
            final var confirmed = receipt;
            var completed =
                    write(
                            () -> {
                                var current = intent(e, s, number, true);
                                current.setReceiptSnapshot(json.writeValueAsString(confirmed));
                                current.setStatus(confirmed.pending() ? "UNKNOWN" : "CONFIRMED");
                                current.setUpdatedBy(initial.getActorNo());
                                if (intents.updateById(current) != 1) throw conflict();
                                var original = head(e, s, number, true);
                                original.setStatus(
                                        confirmed.pending() ? "APPROVAL_UNKNOWN" : "APPROVED");
                                original.setUpdatedBy(initial.getActorNo());
                                if (returns.updateById(original) != 1) throw conflict();
                                if (checkGuard != null)
                                    checkGuard.invalidateBatchShares(
                                            e,
                                            s,
                                            row.getBatchNo(),
                                            initial.getActorNo(),
                                            "RETURN_CONFIRMED");
                                return current;
                            });
            return view(completed);
        } catch (RuntimeException unknown) {
            write(
                    () -> {
                        var current = intent(e, s, number, true);
                        if (!"CONFIRMED".equals(current.getStatus())) {
                            current.setStatus("UNKNOWN");
                            current.setUpdatedBy(initial.getActorNo());
                            intents.updateById(current);
                        }
                        return null;
                    });
            throw unknown;
        }
    }

    @Override
    public ApprovalVO linkReplacement(
            String e, String s, String actor, String number, ReplacementReq req, String key) {
        outside();
        keys(e, s, actor, number);
        if (req == null
                || req.version() == null
                || req.reason() == null
                || req.reason().isBlank()
                || req.reason().length() > 255
                || key == null
                || key.isBlank()
                || key.length() > 128) throw bad();
        keys(req.replacementBatchNo());
        String operation = FoodCommandFingerprint.hash("return-replace-key:v1", e, s, key),
                hash =
                        FoodCommandFingerprint.hash(
                                "return-replace:v1", actor, number, json.writeValueAsString(req));
        return write(
                () -> {
                    var intent = intent(e, s, number, true);
                    if (intent.getReplacementKey() != null) {
                        if (!operation.equals(intent.getReplacementKey())
                                || !hash.equals(intent.getReplacementHash())) throw conflict();
                        return view(intent);
                    }
                    if (!"CONFIRMED".equals(intent.getStatus())
                            || !req.version().equals(intent.getVersion())) throw conflict();
                    var original = head(e, s, number, true);
                    var old = batch(e, s, original.getBatchNo(), true);
                    var replacement = batch(e, s, req.replacementBatchNo(), true);
                    if (old.getBatchNo().equals(replacement.getBatchNo())
                            || !Objects.equals(old.getCheckNo(), replacement.getCheckNo())
                            || !"ACCEPTED".equals(replacement.getStatus())
                            || replacement.getAcceptedAt() == null
                            || intent.getReplacementCartNo() != null
                                    && !intent.getReplacementCartNo()
                                            .equals(replacement.getCartNo())) throw conflict();
                    var binding =
                            bindings.selectOne(
                                    Wrappers.<FnbCoreOrderBinding>lambdaQuery()
                                            .eq(FnbCoreOrderBinding::getEntityNo, e)
                                            .eq(
                                                    FnbCoreOrderBinding::getBatchNo,
                                                    replacement.getBatchNo())
                                            .eq(FnbCoreOrderBinding::getStatus, "BOUND"));
                    if (binding == null || binding.getPaymentRootNo() == null) throw conflict();
                    intent.setReplacementBatchNo(replacement.getBatchNo());
                    intent.setReplacementOrderNo(binding.getPaymentRootNo());
                    intent.setReplacementKey(operation);
                    intent.setReplacementHash(hash);
                    intent.setUpdatedBy(actor);
                    if (intents.updateById(intent) != 1) throw conflict();
                    return view(intent);
                });
    }

    @Override
    public int recover(int limit) {
        outside();
        if (limit < 1 || limit > 200) throw bad();
        var rows =
                read(
                        () ->
                                intents.selectList(
                                        Wrappers.<FnbReturnAmendment>lambdaQuery()
                                                .in(
                                                        FnbReturnAmendment::getStatus,
                                                        List.of(
                                                                "WAIT_KITCHEN",
                                                                "PREPARED",
                                                                "UNKNOWN"))
                                                .orderByAsc(FnbReturnAmendment::getUpdatedAt)
                                                .last("LIMIT " + limit)));
        int done = 0;
        for (var row : rows)
            try {
                if (!execute(row).pending()) done++;
            } catch (RuntimeException unknown) {
                LoggerFactory.getLogger(getClass())
                        .warn(
                                "Business return amendment recovery remains pending: {}",
                                row.getApprovalNo(),
                                unknown);
            }
        return done;
    }

    private ApprovalVO view(FnbReturnAmendment row) {
        var receipt =
                row.getReceiptSnapshot() == null
                        ? null
                        : json.readValue(
                                row.getReceiptSnapshot(), IndustryOrderAmendmentPort.Receipt.class);
        boolean confirmed =
                "CONFIRMED".equals(row.getStatus())
                        && receipt != null
                        && !receipt.pending()
                        && "CONFIRMED".equals(receipt.status());
        return new ApprovalVO(
                row.getApprovalNo(),
                row.getReturnNo(),
                row.getVersion(),
                row.getStatus(),
                !confirmed,
                confirmed,
                receipt,
                row.getReplacementBatchNo(),
                row.getReplacementOrderNo());
    }

    private FnbReturnAmendment intent(String e, String s, String n, boolean required) {
        var row =
                intents.selectOne(
                        Wrappers.<FnbReturnAmendment>lambdaQuery()
                                .eq(FnbReturnAmendment::getEntityNo, e)
                                .eq(FnbReturnAmendment::getStoreNo, s)
                                .eq(FnbReturnAmendment::getReturnNo, n)
                                .last(required ? "FOR UPDATE" : ""));
        if (row == null && required) throw missing();
        return row;
    }

    private FnbReturn head(String e, String s, String n, boolean lock) {
        var row =
                returns.selectOne(
                        Wrappers.<FnbReturn>lambdaQuery()
                                .eq(FnbReturn::getEntityNo, e)
                                .eq(FnbReturn::getStoreNo, s)
                                .eq(FnbReturn::getReturnNo, n)
                                .last(lock ? "FOR UPDATE" : ""));
        if (row == null) throw missing();
        return row;
    }

    private FnbOrderBatch batch(String e, String s, String n, boolean lock) {
        var row =
                batches.selectOne(
                        Wrappers.<FnbOrderBatch>lambdaQuery()
                                .eq(FnbOrderBatch::getEntityNo, e)
                                .eq(FnbOrderBatch::getStoreNo, s)
                                .eq(FnbOrderBatch::getBatchNo, n)
                                .last(lock ? "FOR UPDATE" : ""));
        if (row == null) throw missing();
        return row;
    }

    private IndustryOrderAmendmentPort provider() {
        var ps = owner.orderedStream().limit(2).toList();
        if (ps.size() != 1) throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
        return ps.getFirst();
    }

    private static void stamp(FnbReturnAmendment row, String actor) {
        row.setCreatedBy(actor);
        row.setUpdatedBy(actor);
        row.setCreatedAt(LocalDateTime.now());
        row.setUpdatedAt(row.getCreatedAt());
        row.setVersion(0L);
    }

    private static void keys(String... keys) {
        for (var key : keys) if (key == null || key.isBlank() || key.length() > 32) throw bad();
    }

    private static void outside() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Return approval cannot join a caller transaction");
    }

    private static <T> T read(Supplier<T> work) {
        return DataScopeContext.executeWithoutScope(work);
    }

    private <T> T write(Supplier<T> work) {
        return read(() -> tx.execute(s -> work.get()));
    }

    private static BizException bad() {
        return BizException.of(ErrorCode.BAD_REQUEST);
    }

    private static BizException conflict() {
        return BizException.of(ErrorCode.CONFLICT);
    }

    private static BizException missing() {
        return BizException.of(ErrorCode.NOT_FOUND);
    }
}
