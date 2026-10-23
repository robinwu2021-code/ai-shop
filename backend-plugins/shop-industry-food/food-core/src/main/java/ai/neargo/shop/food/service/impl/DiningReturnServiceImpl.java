package ai.neargo.shop.food.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.DiningReturnApplyReq;
import ai.neargo.shop.food.dto.DiningReturnVO;
import ai.neargo.shop.food.entity.FnbCoreLineAllocation;
import ai.neargo.shop.food.entity.FnbCoreOrderBinding;
import ai.neargo.shop.food.entity.FnbOrderBatch;
import ai.neargo.shop.food.entity.FnbOrderLine;
import ai.neargo.shop.food.entity.FnbReturn;
import ai.neargo.shop.food.entity.FnbReturnItem;
import ai.neargo.shop.food.mapper.FoodMappers.CoreLineAllocationMapper;
import ai.neargo.shop.food.mapper.FoodMappers.CoreOrderBindingMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderBatchMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderLineMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ReturnItemMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ReturnMapper;
import ai.neargo.shop.food.policy.CartInputPolicy;
import ai.neargo.shop.food.service.CheckCartService;
import ai.neargo.shop.food.service.DiningReturnService;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.trade.IndustryAfterSalePort;
import ai.neargo.shop.spi.trade.PricingSourceRef;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Durable FOOD return intent calls the original core policy outside local transactions; no kitchen result is inferred. */
public class DiningReturnServiceImpl implements DiningReturnService {
    private final CheckCartService carts;
    private final OrderBatchMapper batches;
    private final CoreOrderBindingMapper bindings;
    private final OrderLineMapper lines;
    private final CoreLineAllocationMapper allocations;
    private final ReturnMapper returns;
    private final ReturnItemMapper items;
    private final Supplier<IndustryAfterSalePort> authority;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionTemplate write;

    public DiningReturnServiceImpl(CheckCartService carts, OrderBatchMapper batches, CoreOrderBindingMapper bindings,
            OrderLineMapper lines, CoreLineAllocationMapper allocations, ReturnMapper returns, ReturnItemMapper items,
            Supplier<IndustryAfterSalePort> authority, ObjectMapper json, Clock clock, PlatformTransactionManager manager) {
        this.carts = carts; this.batches = batches; this.bindings = bindings; this.lines = lines;
        this.allocations = allocations; this.returns = returns; this.items = items; this.authority = authority;
        this.json = json; this.clock = clock; write = new TransactionTemplate(manager);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    private record SourceItem(String foodLineNo, String sourceLineRef, String componentRef,
                              String allocationNo, BigDecimal quantity) { }
    private record Frozen(int schema, IndustryAfterSalePort.Apply command, List<SourceItem> items) { }

    @Override public DiningReturnVO customerApply(String store, CartAccess access, DiningReturnApplyReq request,
                                                 String key) {
        outside(); buyer(access); CartInputPolicy.key(store); CartInputPolicy.replayKey(key);
        DiningReturnApplyReq normalized = normalize(request);
        FnbOrderBatch batch = authorizedBatch(access, normalized.batchNo());
        if (!store.equals(batch.getStoreNo())) throw forbidden();
        String replayKey = PricingSnapshotHash.of(List.of("FOOD_RETURN_KEY_V1", key));
        String hash = PricingSnapshotHash.of(List.of("FOOD_RETURN_REQUEST_V1", access.buyerNo(), store, normalized));
        FnbReturn row;
        try {
            row = sql(() -> write.execute(tx -> prepare(batch, normalized, replayKey, hash, access.buyerNo())));
        } catch (DuplicateKeyException concurrent) {
            row = sql(() -> byKey(batch.getEntityNo(), store, replayKey));
            if (row == null) throw conflict();
            replay(row, hash, access.buyerNo(), normalized.batchNo());
        }
        if ("CANCEL_PENDING".equals(row.getStatus())) return refresh(row, true);
        Frozen frozen = frozen(row);
        try {
            IndustryAfterSalePort.Lookup known = core().queryApply(query(frozen));
            if (known == null) throw unsupported();
            if (known.found()) return merge(row, known.result(), false);
            // Re-read the durable cancellation intention immediately before dispatch; an already in-flight call
            // can still arrive later, so CANCEL_PENDING recovery continues querying the original stable key.
            if ("CANCEL_PENDING".equals(reload(row).getStatus())) return refresh(reload(row), true);
            return merge(row, core().apply(frozen.command()), false);
        } catch (BizException rejected) {
            // Preserve original policy/ownership rejections; retaining intent never turns rejection into acceptance.
            unknown(row);
            throw rejected;
        } catch (RuntimeException uncertain) {
            unknown(row);
            return view(reload(row), null);
        }
    }

    @Override public DiningReturnVO customerGet(CartAccess access, String number) {
        outside(); buyer(access); FnbReturn row = authorizedReturn(access, number);
        return refresh(row, "CANCEL_PENDING".equals(row.getStatus()));
    }
    @Override public DiningReturnVO customerCancel(CartAccess access, String number) {
        outside(); buyer(access); FnbReturn row = authorizedReturn(access, number);
        if (row.getCoreCaseRef() == null) {
            sql(() -> write.execute(tx -> {
                returns.update(null, Wrappers.<FnbReturn>lambdaUpdate()
                        .eq(FnbReturn::getEntityNo, row.getEntityNo()).eq(FnbReturn::getReturnNo, row.getReturnNo())
                        .isNull(FnbReturn::getCoreCaseRef).set(FnbReturn::getStatus, "CANCEL_PENDING")
                        .set(FnbReturn::getFinancialStatus, "UNKNOWN").set(FnbReturn::getUpdatedBy, access.buyerNo()));
                return null;
            }));
            return refresh(reload(row), true);
        }
        Frozen frozen = frozen(row);
        IndustryAfterSalePort.Result current = core().get(reference(row, frozen));
        validate(row, frozen, current);
        if ("CLOSED".equals(current.status())) return merge(row, current, true);
        return merge(row, core().cancel(reference(row, frozen)), true);
    }
    @Override public DiningReturnVO staffGet(String entity, String store, String actor, String number) {
        outside(); CartInputPolicy.key(entity); CartInputPolicy.key(store); CartInputPolicy.key(actor);
        CartInputPolicy.key(number);
        FnbReturn row = sql(() -> returns.selectOne(Wrappers.<FnbReturn>lambdaQuery()
                .eq(FnbReturn::getTenantNo, "MAIN").eq(FnbReturn::getEntityNo, entity)
                .eq(FnbReturn::getStoreNo, store).eq(FnbReturn::getReturnNo, number)));
        if (row == null) throw missing();
        return view(row, null);
    }

    @Override public DiningReturnVO recoverOriginal(String entity, String store, String number) {
        outside(); CartInputPolicy.key(entity); CartInputPolicy.key(store); CartInputPolicy.key(number);
        FnbReturn original = sql(() -> returns.selectOne(Wrappers.<FnbReturn>lambdaQuery()
                .eq(FnbReturn::getTenantNo, "MAIN").eq(FnbReturn::getEntityNo, entity)
                .eq(FnbReturn::getStoreNo, store).eq(FnbReturn::getReturnNo, number)));
        if (original == null || original.getRequestSnapshot() == null) throw missing();
        frozen(original); // Persisted buyer/binding/key are verified without creating a current security principal.
        return refresh(original, "CANCEL_PENDING".equals(original.getStatus()));
    }

    private FnbReturn prepare(FnbOrderBatch original, DiningReturnApplyReq req, String key, String hash, String actor) {
        FnbOrderBatch batch = batches.selectOne(Wrappers.<FnbOrderBatch>lambdaQuery()
                .eq(FnbOrderBatch::getTenantNo, "MAIN").eq(FnbOrderBatch::getEntityNo, original.getEntityNo())
                .eq(FnbOrderBatch::getStoreNo, original.getStoreNo()).eq(FnbOrderBatch::getBatchNo, original.getBatchNo())
                .eq(FnbOrderBatch::getBuyerNo, actor).last("FOR UPDATE"));
        if (batch == null) throw missing();
        FnbReturn prior = byKey(batch.getEntityNo(), batch.getStoreNo(), key);
        if (prior != null) { replay(prior, hash, actor, batch.getBatchNo()); return prior; }
        FnbCoreOrderBinding binding = bindings.selectOne(Wrappers.<FnbCoreOrderBinding>lambdaQuery()
                .eq(FnbCoreOrderBinding::getTenantNo, "MAIN").eq(FnbCoreOrderBinding::getEntityNo, batch.getEntityNo())
                .eq(FnbCoreOrderBinding::getBatchNo, batch.getBatchNo()).eq(FnbCoreOrderBinding::getStatus, "BOUND"));
        if (binding == null || !MaterialCatalogFacts.validKey(binding.getPaymentRootNo(), 32)
                || !MaterialCatalogFacts.validKey(binding.getStoreOrderNo(), 32)
                || !MaterialCatalogFacts.validKey(batch.getCartNo(), 32) || batch.getCartVersion() == null
                || batch.getCartVersion() < 0 || !"CONSUMER".equals(batch.getActorRealm())) {
            throw unsupported();
        }
        List<FnbOrderLine> originalLines = lines.selectList(Wrappers.<FnbOrderLine>lambdaQuery()
                .eq(FnbOrderLine::getTenantNo, "MAIN").eq(FnbOrderLine::getEntityNo, batch.getEntityNo())
                .eq(FnbOrderLine::getBatchNo, batch.getBatchNo()).orderByAsc(FnbOrderLine::getSourceLineRef));
        if (originalLines.isEmpty() || originalLines.size() < req.lines().size()
                || originalLines.stream().map(FnbOrderLine::getSourceLineRef).distinct().count() != originalLines.size()
                || !originalLines.stream().map(FnbOrderLine::getSourceLineRef).collect(Collectors.toSet())
                .containsAll(req.lines().stream().map(DiningReturnApplyReq.Line::sourceLineRef).toList())) {
            throw unsupported();
        }
        Map<String, BigDecimal> requested = req.lines().stream().collect(Collectors.toMap(
                DiningReturnApplyReq.Line::sourceLineRef, DiningReturnApplyReq.Line::quantity));
        List<SourceItem> originals = new ArrayList<>();
        List<IndustryAfterSalePort.LineQty> full = new ArrayList<>();
        for (FnbOrderLine line : originalLines) {
            BigDecimal wanted = requested.get(line.getSourceLineRef());
            if (wanted == null) continue;
            if (line.getQty() == null || wanted.compareTo(line.getQty()) > 0) throw BizException.of(ErrorCode.BAD_REQUEST);
            int whole;
            try { whole = wanted.intValueExact(); }
            catch (ArithmeticException fractional) { throw unsupported(); }
            List<FnbCoreLineAllocation> originalAllocation = allocations.selectList(
                    Wrappers.<FnbCoreLineAllocation>lambdaQuery().eq(FnbCoreLineAllocation::getTenantNo, "MAIN")
                    .eq(FnbCoreLineAllocation::getEntityNo, batch.getEntityNo())
                    .eq(FnbCoreLineAllocation::getFoodLineNo, line.getFoodLineNo()));
            if (whole <= 0 || originalAllocation.size() != 1) throw unsupported();
            FnbCoreLineAllocation allocation = originalAllocation.getFirst();
            if (allocation.getOrderItemId() == null || allocation.getOrderItemId() <= 0 || allocation.getQty() == null
                    || allocation.getQty().compareTo(wanted) < 0 || allocation.getQty().compareTo(line.getQty()) != 0
                    || allocation.getCoreSnapshotHash() == null
                    || !allocation.getCoreSnapshotHash().matches("[0-9a-f]{64}")
                    || allocation.getSourceComponentRef() == null
                    || !allocation.getSourceComponentRef().matches("[0-9a-f]{64}")
                    || !allocation.getSourceComponentRef().equals(PricingSnapshotHash.of(List.of("MAIN", line.getSkuNo())))) {
                throw unsupported();
            }
            originals.add(new SourceItem(line.getFoodLineNo(), line.getSourceLineRef(),
                    allocation.getSourceComponentRef(), allocation.getAllocationNo(), wanted));
            full.add(new IndustryAfterSalePort.LineQty(line.getSourceLineRef(), allocation.getSourceComponentRef(), whole));
        }
        FnbReturn row = new FnbReturn(); stamp(row, actor); row.setEntityNo(batch.getEntityNo());
        row.setReturnNo(BizKey.next("FRET")); row.setStoreNo(batch.getStoreNo()); row.setCheckNo(batch.getCheckNo());
        row.setBatchNo(batch.getBatchNo()); row.setBuyerNo(actor); row.setRequestKey(key); row.setRequestHash(hash);
        row.setReason(req.reason()); row.setStatus("REQUESTED"); row.setFinancialStatus("PENDING");
        row.setKitchenStatus("PENDING");
        var identity = new IndustryAfterSalePort.Identity("MAIN", batch.getEntityNo(), batch.getStoreNo(), actor, actor);
        var source = new PricingSourceRef("FOOD", "CART", batch.getCartNo(), batch.getCartVersion(), null, null);
        var command = new IndustryAfterSalePort.Apply(identity, new IndustryAfterSalePort.Binding(source,
                binding.getPaymentRootNo(), binding.getStoreOrderNo()), row.getReturnNo(), req.type(), req.reason(),
                req.reasonCode(), req.images(), full);
        row.setRequestSnapshot(json.writeValueAsString(new Frozen(1, command, List.copyOf(originals))));
        returns.insert(row);
        for (SourceItem originalItem : originals) {
            FnbReturnItem item = new FnbReturnItem(); stamp(item, actor); item.setEntityNo(row.getEntityNo());
            item.setReturnItemNo(BizKey.next("FRTI")); item.setReturnNo(row.getReturnNo());
            item.setFoodLineNo(originalItem.foodLineNo()); item.setQty(originalItem.quantity());
            // KEEP is a request to perform no kitchen mutation; it is not a HANDLED acknowledgement.
            item.setKitchenDisposition("KEEP"); item.setCoreLineAllocationRef(originalItem.allocationNo()); items.insert(item);
        }
        return row;
    }

    private DiningReturnVO refresh(FnbReturn row, boolean cancel) {
        Frozen frozen = frozen(row);
        try {
            IndustryAfterSalePort.Result result;
            if (row.getCoreCaseRef() == null) {
                var lookup = core().queryApply(query(frozen));
                if (lookup == null) throw unsupported();
                if (!lookup.found()) return view(row, null);
                result = lookup.result();
            } else result = core().get(reference(row, frozen));
            validate(row, frozen, result);
            if (cancel && "CLOSED".equals(result.status())) return merge(row, result, true);
            if (cancel) {
                // No cancellation completion is inferred from an absent original apply.
                try {
                    return merge(row, core().cancel(new IndustryAfterSalePort.Reference(frozen.command().identity(),
                            frozen.command().binding(), result.afterSaleNo())), true);
                } catch (BizException rejected) {
                    merge(row, result, true);
                    throw rejected;
                }
            }
            return merge(row, result, false);
        } catch (BizException definite) { throw definite; }
        catch (RuntimeException uncertain) { unknown(row); return view(reload(row), null); }
    }
    private DiningReturnVO merge(FnbReturn original, IndustryAfterSalePort.Result result, boolean clearCancel) {
        Frozen frozen = frozen(original); validate(original, frozen, result);
        FnbReturn updated = sql(() -> write.execute(tx -> {
            FnbReturn row = returns.selectOne(Wrappers.<FnbReturn>lambdaQuery()
                    .eq(FnbReturn::getEntityNo, original.getEntityNo()).eq(FnbReturn::getReturnNo, original.getReturnNo())
                    .last("FOR UPDATE"));
            if (row == null || row.getCoreCaseRef() != null && !row.getCoreCaseRef().equals(result.afterSaleNo())) throw conflict();
            // A delayed apply response cannot resurrect an original case already confirmed terminal.
            if (List.of("CLOSED", "REFUNDED").contains(row.getStatus()) && !row.getStatus().equals(result.status())) {
                return row;
            }
            row.setCoreCaseRef(result.afterSaleNo());
            if (clearCancel || !"CANCEL_PENDING".equals(row.getStatus())) row.setStatus(result.status());
            row.setFinancialStatus(financial(result)); row.setUpdatedBy(row.getBuyerNo()); returns.updateById(row);
            return row;
        }));
        return view(updated, updated.getStatus().equals(result.status()) ? result : null);
    }
    private String financial(IndustryAfterSalePort.Result result) {
        var p = result.progress();
        if ("REFUNDED".equals(result.status()) || p != null && "SUCCEEDED".equals(p.cashStatus())) return "COMPLETED";
        if (p != null && ("UNKNOWN".equals(p.cashStatus()) || "UNKNOWN".equals(p.status()))) return "UNKNOWN";
        if (p != null && ("FAILED".equals(p.cashStatus()) || "FAILED".equals(p.status()))) return "FAILED";
        if (List.of("CLOSED", "REJECTED").contains(result.status()) && p == null) return "NONE";
        return "PENDING";
    }
    private void unknown(FnbReturn original) {
        sql(() -> write.execute(tx -> {
            returns.update(null, Wrappers.<FnbReturn>lambdaUpdate().eq(FnbReturn::getEntityNo, original.getEntityNo())
                    .eq(FnbReturn::getReturnNo, original.getReturnNo()).isNull(FnbReturn::getCoreCaseRef)
                    .ne(FnbReturn::getStatus, "CANCEL_PENDING").set(FnbReturn::getStatus, "UNKNOWN")
                    .set(FnbReturn::getFinancialStatus, "UNKNOWN").set(FnbReturn::getUpdatedBy, original.getBuyerNo()));
            return null;
        }));
    }
    private FnbOrderBatch authorizedBatch(CartAccess access, String batchNo) {
        CartInputPolicy.key(batchNo);
        FnbOrderBatch batch = sql(() -> batches.selectOne(Wrappers.<FnbOrderBatch>lambdaQuery()
                .eq(FnbOrderBatch::getTenantNo, "MAIN").eq(FnbOrderBatch::getBatchNo, batchNo)
                .eq(FnbOrderBatch::getBuyerNo, access.buyerNo())));
        if (batch == null) throw missing();
        var cart = carts.customerGetCart(batch.getCartNo(), access).cart();
        // Customer cart views intentionally redact buyerNo; the original cart authority already verified login.
        if (cart == null || !batch.getStoreNo().equals(cart.storeNo()) || !"CLOSED".equals(cart.status())) {
            throw forbidden();
        }
        return batch;
    }
    private FnbReturn authorizedReturn(CartAccess access, String number) {
        CartInputPolicy.key(number);
        FnbReturn row = sql(() -> returns.selectOne(Wrappers.<FnbReturn>lambdaQuery()
                .eq(FnbReturn::getTenantNo, "MAIN").eq(FnbReturn::getReturnNo, number)
                .eq(FnbReturn::getBuyerNo, access.buyerNo())));
        if (row == null || row.getBatchNo() == null) throw missing();
        var batch = authorizedBatch(access, row.getBatchNo());
        if (!row.getEntityNo().equals(batch.getEntityNo()) || !row.getStoreNo().equals(batch.getStoreNo())) throw forbidden();
        var originalSource = frozen(row).command().binding().source();
        if (!batch.getCartNo().equals(originalSource.sourceNo())
                || batch.getCartVersion() == null || batch.getCartVersion() != originalSource.sourceVersion()) throw conflict();
        return row;
    }
    private Frozen frozen(FnbReturn row) {
        if (row.getRequestSnapshot() == null) throw unsupported();
        Frozen frozen = json.readValue(row.getRequestSnapshot(), Frozen.class);
        if (frozen == null || frozen.schema() != 1 || frozen.command() == null || frozen.items() == null
                || frozen.items().isEmpty()) throw unsupported();
        var command = frozen.command(); var identity = command.identity();
        if (identity == null || !"MAIN".equals(identity.tenantNo()) || !row.getEntityNo().equals(identity.entityNo())
                || !row.getStoreNo().equals(identity.storeNo()) || !row.getBuyerNo().equals(identity.buyerNo())
                || !row.getBuyerNo().equals(identity.actorNo()) || !row.getReturnNo().equals(command.requestKey())
                || command.binding() == null || command.binding().source() == null
                || !"FOOD".equals(command.binding().source().sourceSystem())
                || !"CART".equals(command.binding().source().sourceType())
                || command.binding().source().scopeNo() != null || command.binding().source().scopeVersion() != null
                || !row.getReason().equals(command.reason()) || command.lines().size() != frozen.items().size()) throw conflict();
        Map<String, IndustryAfterSalePort.LineQty> commanded = new HashMap<>();
        for (var line : command.lines()) {
            if (line.componentRef() == null || !line.componentRef().matches("[0-9a-f]{64}") || line.qty() <= 0
                    || commanded.put(line.sourceLineNo(), line) != null) throw conflict();
        }
        for (var item : frozen.items()) {
            if (item.quantity() == null || !MaterialCatalogFacts.validKey(item.foodLineNo(), 32)
                    || !MaterialCatalogFacts.validKey(item.allocationNo(), 32)
                    || commanded.get(item.sourceLineRef()) == null
                    || !Objects.equals(commanded.get(item.sourceLineRef()).componentRef(), item.componentRef())
                    || BigDecimal.valueOf(commanded.get(item.sourceLineRef()).qty()).compareTo(item.quantity()) != 0) {
                throw conflict();
            }
        }
        return frozen;
    }
    private void validate(FnbReturn row, Frozen frozen, IndustryAfterSalePort.Result result) {
        if (result == null || !MaterialCatalogFacts.validKey(result.afterSaleNo(), 64)
                || !frozen.command().binding().orderNo().equals(result.orderNo())
                || !frozen.command().binding().subOrderNo().equals(result.subOrderNo())
                || row.getCoreCaseRef() != null && !row.getCoreCaseRef().equals(result.afterSaleNo())
                || result.status() == null || result.status().isBlank() || result.status().length() > 24
                || result.refundMinor() < 0 || result.currency() == null || result.currency().isBlank()) throw unsupported();
    }
    private IndustryAfterSalePort.QueryApply query(Frozen frozen) {
        return new IndustryAfterSalePort.QueryApply(frozen.command().identity(), frozen.command().binding(),
                frozen.command().requestKey());
    }
    private IndustryAfterSalePort.Reference reference(FnbReturn row, Frozen frozen) {
        return new IndustryAfterSalePort.Reference(frozen.command().identity(), frozen.command().binding(), row.getCoreCaseRef());
    }
    private DiningReturnVO view(FnbReturn row, IndustryAfterSalePort.Result core) {
        Frozen frozen = frozen(row);
        Map<String, SourceItem> original = frozen.items().stream().collect(Collectors.toMap(SourceItem::foodLineNo,
                item -> item));
        var detail = sql(() -> items.selectList(Wrappers.<FnbReturnItem>lambdaQuery()
                .eq(FnbReturnItem::getTenantNo, "MAIN").eq(FnbReturnItem::getEntityNo, row.getEntityNo())
                .eq(FnbReturnItem::getReturnNo, row.getReturnNo()).orderByAsc(FnbReturnItem::getReturnItemNo)));
        if (detail.size() != original.size()) throw conflict();
        List<DiningReturnVO.Line> output = detail.stream().map(item -> {
            SourceItem source = original.get(item.getFoodLineNo());
            if (source == null || item.getQty() == null || item.getQty().compareTo(source.quantity()) != 0
                    || !Objects.equals(item.getCoreLineAllocationRef(), source.allocationNo())) throw conflict();
            return new DiningReturnVO.Line(source.sourceLineRef(), item.getQty(), item.getKitchenDisposition());
        }).toList();
        return new DiningReturnVO(row.getReturnNo(), row.getBatchNo(), row.getStoreNo(), row.getStatus(),
                row.getFinancialStatus(), row.getKitchenStatus(), row.getCoreCaseRef(), row.getReason(), output, core);
    }
    private DiningReturnApplyReq normalize(DiningReturnApplyReq r) {
        if (r == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        CartInputPolicy.key(r.batchNo());
        if (!List.of("REFUND_ONLY", "RETURN_REFUND").contains(r.type() == null ? "" : r.type())
                || r.reason() == null || r.reason().isBlank() || !r.reason().equals(r.reason().strip())
                || r.reason().length() > 255 || r.reason().codePoints().anyMatch(Character::isISOControl)
                || r.reasonCode() != null && !MaterialCatalogFacts.validKey(r.reasonCode(), 32)
                || r.images().size() > 9 || r.images().stream().anyMatch(image -> image == null || image.isBlank()
                || image.length() > 2048) || r.lines().isEmpty() || r.lines().size() > 256) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        Map<String, DiningReturnApplyReq.Line> unique = new HashMap<>();
        for (var line : r.lines()) {
            if (line == null || !MaterialCatalogFacts.validKey(line.sourceLineRef(), 64) || line.quantity() == null
                    || line.quantity().signum() <= 0) throw BizException.of(ErrorCode.BAD_REQUEST);
            try { line.quantity().intValueExact(); } catch (ArithmeticException unsupported) { throw unsupported(); }
            if (unique.put(line.sourceLineRef(), new DiningReturnApplyReq.Line(line.sourceLineRef(),
                    line.quantity().stripTrailingZeros())) != null) throw conflict();
        }
        return new DiningReturnApplyReq(r.batchNo(), r.type(), r.reason(), r.reasonCode(), r.images(),
                unique.values().stream().sorted(Comparator.comparing(DiningReturnApplyReq.Line::sourceLineRef)).toList());
    }
    private FnbReturn byKey(String entity, String store, String key) {
        return returns.selectOne(Wrappers.<FnbReturn>lambdaQuery().eq(FnbReturn::getTenantNo, "MAIN")
                .eq(FnbReturn::getEntityNo, entity).eq(FnbReturn::getStoreNo, store).eq(FnbReturn::getRequestKey, key));
    }
    private FnbReturn reload(FnbReturn original) {
        return sql(() -> returns.selectOne(Wrappers.<FnbReturn>lambdaQuery()
                .eq(FnbReturn::getEntityNo, original.getEntityNo()).eq(FnbReturn::getReturnNo, original.getReturnNo())));
    }
    private void stamp(BaseEntity row, String actor) {
        row.setTenantNo("MAIN"); row.setVersion(0L); row.setDeleted(0); row.setCreatedBy(actor); row.setUpdatedBy(actor);
        row.setCreatedAt(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)); row.setUpdatedAt(row.getCreatedAt());
    }
    private void replay(FnbReturn row, String hash, String buyer, String batch) {
        if (!hash.equals(row.getRequestHash()) || !buyer.equals(row.getBuyerNo()) || !batch.equals(row.getBatchNo())) {
            throw conflict();
        }
    }
    private IndustryAfterSalePort core() { var port = authority.get(); if (port == null) throw unsupported(); return port; }
    private static void buyer(CartAccess access) {
        if (access == null || !MaterialCatalogFacts.validKey(access.buyerNo(), 32) || access.sessionNo() != null
                || access.accessToken() != null) throw forbidden();
    }
    private static void outside() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw unsupported();
    }
    private static <T> T sql(Supplier<T> action) { return DataScopeContext.executeWithoutScope(action); }
    private static BizException missing() { return BizException.of(ErrorCode.NOT_FOUND); }
    private static BizException conflict() { return BizException.of(ErrorCode.CONFLICT); }
    private static BizException forbidden() { return BizException.of(ErrorCode.FORBIDDEN); }
    private static BizException unsupported() { return BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED); }
}
