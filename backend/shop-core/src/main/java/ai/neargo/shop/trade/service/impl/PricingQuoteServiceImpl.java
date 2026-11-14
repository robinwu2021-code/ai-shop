package ai.neargo.shop.trade.service.impl;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.HashMap;
import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.trade.entity.OrdStorePricingConfig;
import ai.neargo.shop.trade.entity.OrdStorePricingRevision;
import ai.neargo.shop.trade.service.PricingQuoteService;
import java.util.function.Supplier;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingPortion;
import ai.neargo.shop.trade.assembler.PricingQuoteAssembler;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Context;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Kind;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.QuoteVO;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Receipt;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.SaveReq;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Amounts;
import ai.neargo.shop.trade.entity.OrdPriceQuote;
import ai.neargo.shop.trade.entity.OrdPriceQuoteLine;
import ai.neargo.shop.trade.entity.OrdPriceQuoteAllocation;
import ai.neargo.shop.trade.mapper.TradeMappers.PriceQuoteMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.PriceQuoteLineMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.PriceQuoteAllocationMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.StorePricingConfigMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.StorePricingRevisionMapper;
import ai.neargo.shop.idem.IdempotencyService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Three tables and the idempotency receipt commit together; there are no monetary UPDATE or cleanup methods. */
@Service
public class PricingQuoteServiceImpl implements PricingQuoteService {
    private final PriceQuoteMapper quotes;
    private final PriceQuoteLineMapper lines;
    private final PriceQuoteAllocationMapper allocations;
    private final StorePricingConfigMapper configs;
    private final StorePricingRevisionMapper revisions;
    private final IdempotencyService idempotency;
    private final PricingQuoteAssembler assembler;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Autowired
    public PricingQuoteServiceImpl(PriceQuoteMapper quotes, PriceQuoteLineMapper lines,
            PriceQuoteAllocationMapper allocations, StorePricingConfigMapper configs,
            StorePricingRevisionMapper revisions, IdempotencyService idempotency, PricingQuoteAssembler assembler,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this(quotes, lines, allocations, configs, revisions, idempotency, assembler, manager, Clock.systemUTC());
    }

    public PricingQuoteServiceImpl(PriceQuoteMapper quotes, PriceQuoteLineMapper lines,
            PriceQuoteAllocationMapper allocations, StorePricingConfigMapper configs,
            StorePricingRevisionMapper revisions, IdempotencyService idempotency, PricingQuoteAssembler assembler,
            PlatformTransactionManager manager, Clock clock) {
        this.quotes = quotes;
        this.lines = lines;
        this.allocations = allocations;
        this.configs = configs;
        this.revisions = revisions;
        this.idempotency = idempotency;
        this.assembler = assembler;
        this.transactions = new TransactionTemplate(manager);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public QuoteVO save(Context context, SaveReq request, String key) {
        return authorized(context, () -> {
            if (key == null || key.isBlank() || key.length() > 128) throw bad();
            String hash = validated(() -> assembler.commandHash(request));
            String endpoint = "QUOTE:" + assembler.scopeHash(context);
            String actor = context.actorNo();
            for (int attempt = 0; ; attempt++) {
                var completed = idempotency.findCompleted(key, endpoint, actor, hash, Receipt.class);
                if (completed.isPresent()) return get(context, completed.get().quoteNo());
                try {
                    Receipt result = transactions.execute(status -> idempotency.execute(key, endpoint, actor,
                            hash, Receipt.class, () -> persist(context, request)));
                    return get(context, result.quoteNo());
                } catch (PessimisticLockingFailureException locked) {
                    if (TransactionSynchronizationManager.isActualTransactionActive() || attempt >= 2) throw locked;
                } catch (BizException failure) {
                    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                        var receipt = idempotency.findCompleted(key, endpoint, actor, hash, Receipt.class);
                        if (receipt.isPresent()) return get(context, receipt.get().quoteNo());
                    }
                    throw failure;
                }
            }
        });
    }

    private Receipt persist(Context context, SaveReq request) {
        var revision = revisions.selectOne(new QueryWrapper<OrdStorePricingRevision>()
                .eq("tenant_no", context.tenantNo()).eq("entity_no", context.entityNo())
                .eq("store_no", context.storeNo()).eq("revision_no", request.storeRevisionNo()).eq("deleted", 0));
        if (revision == null || !request.templateRevisionNo().equals(revision.getTemplateRevisionNo())
                || !request.engineSpecVersion().equals(revision.getEngineSpecVersion())
                || !request.industry().name().equals(revision.getIndustryCode())
                || !Integer.valueOf(3).equals(revision.getSchemaVersion())) throw conflict();
        var config = configs.selectOne(new QueryWrapper<OrdStorePricingConfig>()
                .eq("tenant_no", context.tenantNo()).eq("entity_no", context.entityNo())
                .eq("store_no", context.storeNo()).eq("config_no", revision.getConfigNo())
                .eq("active_revision_no", revision.getRevisionNo()).eq("industry_code", request.industry().name())
                .eq("status", "ACTIVE").eq("deleted", 0).last("FOR UPDATE"));
        if (config == null) throw conflict();
        var prepared = validated(() -> assembler.prepare(context, request, revision.getEffectiveSnapshot()));
        var now = now();
        var row = new OrdPriceQuote();
        audit(row, context, now);
        row.setQuoteNo(BizKey.next("PQ"));
        row.setEntityNo(context.entityNo());
        row.setStoreNo(context.storeNo());
        row.setSubjectNo(context.subjectNo());
        row.setIndustryCode(request.industry().name());
        row.setSourceSystem(request.source().sourceSystem());
        row.setSourceType(request.source().sourceType());
        row.setSourceNo(request.source().sourceNo());
        row.setSourceVersion(request.source().sourceVersion());
        row.setRootScopeNo(request.source().scopeNo());
        row.setScopeVersion(request.source().scopeVersion());
        row.setChannel(request.channel());
        row.setQuotePhase(request.phase().name());
        row.setStoreRevisionNo(request.storeRevisionNo());
        row.setTemplateRevisionNo(request.templateRevisionNo());
        row.setCurrency(request.facts().currency());
        row.setEngineSpecVersion(request.engineSpecVersion());
        row.setRequestHash(prepared.requestHash());
        row.setFactsHash(prepared.factsHash());
        row.setEconomicsHash(prepared.economicsHash());
        row.setFactsSnapshot(prepared.factsSnapshot());
        row.setConfigSnapshot(prepared.configSnapshot());
        row.setPlanSnapshot(prepared.planSnapshot());
        row.setResultSnapshot(prepared.resultSnapshot());
        row.setSchemaVersion(1);
        amounts(row, prepared.amounts());
        row.setStoredPlannedMinor(request.intent().funding().storedPlannedMinor());
        row.setOtherPlannedMinor(request.intent().funding().otherPlannedMinor());
        row.setRemainingDueMinor(prepared.remainingDueMinor());
        row.setCashDueMinor(request.intent().funding().cashDueMinor());
        row.setCashTenderMinor(request.intent().funding().cashTenderMinor());
        row.setCashChangeMinor(prepared.cashChangeMinor());
        row.setStatus("READY");
        row.setExpiresAt(now.plusSeconds(request.lifetimeSeconds()));
        quotes.insert(row);
        var lineNos = new HashMap<PricingPortion, String>();
        int sort = 0;
        for (var input : request.lines()) {
            var source = input.source();
            var line = new OrdPriceQuoteLine();
            audit(line, context, now);
            line.setQuoteLineNo(BizKey.next("QL"));
            line.setQuoteNo(row.getQuoteNo());
            var portion = PricingPortion.from(source);
            lineNos.put(portion, line.getQuoteLineNo());
            line.setPricingUnitRef(assembler.unitRef(portion));
            line.setSourceLineNo(source.sourceLineNo());
            line.setParentSourceLineNo(input.parentSourceLineNo());
            line.setComponentRef(source.componentRef());
            line.setInvoiceLineRef(source.taxInvoiceLineRef());
            line.setGoodsNo(source.goodsNo());
            line.setSkuNo(source.skuNo());
            line.setLineKind(input.kind().name());
            line.setQuantityStart(source.quantityStart());
            line.setQuantityEnd(source.quantityEnd());
            line.setQuantity(source.quantity());
            line.setUnitPriceMinor(source.unitPrice().minor());
            amounts(line, input.amounts());
            line.setInvoiceBaseMinor(input.invoiceBaseMinor());
            line.setTaxClass(input.taxClass().name());
            line.setEligibilitySnapshot(assembler.canonical(input.eligibility()));
            line.setSourceSnapshot(assembler.canonical(input));
            line.setSortOrder(sort++);
            lines.insert(line);
        }
        for (var input : request.allocations()) {
            var allocation = new OrdPriceQuoteAllocation();
            audit(allocation, context, now);
            allocation.setAllocationNo(BizKey.next("QA"));
            allocation.setQuoteNo(row.getQuoteNo());
            allocation.setQuoteLineNo(lineNos.get(input.portion()));
            allocation.setAllocationKind(input.kind().name());
            allocation.setStage(input.kind() == Kind.STORED_PLAN || input.kind() == Kind.OTHER_PLAN
                    || input.kind() == Kind.CASH_PLAN ? "FUNDING_PLAN"
                    : input.kind() == Kind.POINTS_CONSIDERATION ? "CONSIDERATION" : "BUSINESS_PRE_TAX");
            allocation.setStepOrder(input.stepOrder());
            allocation.setBenefitRef(input.benefitRef());
            allocation.setOwner(input.owner());
            allocation.setOwnerQuoteRef(input.ownerQuoteRef());
            allocation.setApplicationRef(input.applicationRef());
            allocation.setRuleNo(input.ruleNo());
            allocation.setRuleVersion(input.ruleVersion());
            allocation.setBasisMinor(input.basisMinor());
            allocation.setAmountMinor(input.amountMinor());
            allocation.setPayableRateBp(input.payableRateBp());
            allocation.setQuantityStart(input.portion().start());
            allocation.setQuantityEnd(input.portion().end());
            allocation.setAlgorithmVersion(input.algorithmVersion());
            allocation.setEvidenceSnapshot(assembler.canonical(input));
            allocations.insert(allocation);
        }
        return new Receipt(row.getQuoteNo());
    }

    @Override
    public QuoteVO get(Context context, String no) {
        return authorized(context, () -> view(head(context, no, false)));
    }

    @Override
    public QuoteVO retain(Context context, String no, String reference) {
        return authorized(context, () -> transactions.execute(status -> {
            validated(() -> { PricingQuoteAssembler.identity(reference, 64); return reference; });
            var row = head(context, no, true);
            if (row.getRetainedRef() != null) {
                if (!row.getRetainedRef().equals(reference)) throw conflict();
                return view(row);
            }
            ready(row);
            transition(context, row, new UpdateWrapper<OrdPriceQuote>().set("retained_ref", reference));
            return view(head(context, no, false));
        }));
    }

    @Override
    public QuoteVO supersede(Context context, String no, String replacementNo) {
        return authorized(context, () -> transactions.execute(status -> {
            // Global business-No order avoids A->B/B->A lock inversion.
            if (no == null || replacementNo == null || no.equals(replacementNo)) throw bad();
            var first = head(context, no.compareTo(replacementNo) < 0 ? no : replacementNo, true);
            var second = head(context, no.compareTo(replacementNo) < 0 ? replacementNo : no, true);
            var original = first.getQuoteNo().equals(no) ? first : second;
            var replacement = first.getQuoteNo().equals(no) ? second : first;
            if (replacementNo.equals(original.getSupersededBy())) return view(original);
            ready(original);
            ready(replacement);
            if (original.getRetainedRef() != null || replacement.getRetainedRef() != null
                    || !original.getSourceSystem().equals(replacement.getSourceSystem())
                    || !original.getSourceType().equals(replacement.getSourceType())
                    || !original.getSourceNo().equals(replacement.getSourceNo())
                    || !Objects.equals(original.getRootScopeNo(), replacement.getRootScopeNo())
                    || !original.getIndustryCode().equals(replacement.getIndustryCode())
                    || !original.getCurrency().equals(replacement.getCurrency())) throw conflict();
            transition(context, original, new UpdateWrapper<OrdPriceQuote>()
                    .set("status", "SUPERSEDED").set("superseded_by", replacementNo));
            return view(head(context, no, false));
        }));
    }

    private void transition(Context context, OrdPriceQuote row, UpdateWrapper<OrdPriceQuote> update) {
        scope(update, context);
        update.eq("quote_no", row.getQuoteNo()).eq("version", row.getVersion())
                .set("version", row.getVersion() + 1).set("updated_at", now()).set("updated_by", context.actorNo());
        if (quotes.update(null, update) != 1) throw conflict();
    }

    private OrdPriceQuote head(Context context, String no, boolean lock) {
        validated(() -> { PricingQuoteAssembler.identity(no, 32); return no; });
        var query = new QueryWrapper<OrdPriceQuote>().eq("tenant_no", context.tenantNo())
                .eq("entity_no", context.entityNo()).eq("store_no", context.storeNo())
                .eq("quote_no", no).eq("deleted", 0);
        if (context.subjectNo() == null) query.isNull("subject_no"); else query.eq("subject_no", context.subjectNo());
        if (lock) query.last("FOR UPDATE");
        var row = quotes.selectOne(query);
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return row;
    }

    private void scope(UpdateWrapper<OrdPriceQuote> update, Context context) {
        update.eq("tenant_no", context.tenantNo()).eq("entity_no", context.entityNo())
                .eq("store_no", context.storeNo()).eq("deleted", 0);
        if (context.subjectNo() == null) update.isNull("subject_no"); else update.eq("subject_no", context.subjectNo());
    }

    private void ready(OrdPriceQuote row) {
        if (!"READY".equals(row.getStatus()) || !now().isBefore(row.getExpiresAt())) throw conflict();
    }

    private QuoteVO view(OrdPriceQuote row) {
        String status = "READY".equals(row.getStatus()) && !now().isBefore(row.getExpiresAt())
                ? "EXPIRED" : row.getStatus();
        return new QuoteVO(row.getQuoteNo(), status, row.getExpiresAt().toInstant(ZoneOffset.UTC).toString(),
                row.getFactsHash(), row.getRequestHash(), row.getEconomicsHash(), row.getFactsSnapshot(),
                row.getConfigSnapshot(), row.getPlanSnapshot(), row.getResultSnapshot(), row.getRetainedRef());
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }

    private void audit(BaseEntity row, Context context, LocalDateTime now) {
        row.setTenantNo(context.tenantNo());
        row.setVersion(0L);
        row.setDeleted(0);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setCreatedBy(context.actorNo());
        row.setUpdatedBy(context.actorNo());
    }

    private <T> T authorized(Context context, Supplier<T> action) {
        validated(() -> { assembler.context(context); return context; });
        return DataScopeContext.executeWithoutScope(action);
    }

    private <T> T validated(Supplier<T> action) {
        try { return action.get(); }
        catch (IllegalArgumentException | ArithmeticException | NullPointerException invalid) { throw bad(); }
    }
    private static BizException bad() { return BizException.of(ErrorCode.BAD_REQUEST); }
    private static BizException conflict() { return BizException.of(ErrorCode.CONFLICT); }

    private void amounts(OrdPriceQuote row, Amounts amounts) {
        row.setGoodsMinor(amounts.goodsMinor());
        row.setPriceAdjustmentMinor(amounts.priceAdjustmentMinor());
        row.setActivityDiscountMinor(amounts.activityDiscountMinor());
        row.setCouponDiscountMinor(amounts.couponDiscountMinor());
        row.setManualDiscountMinor(amounts.manualDiscountMinor());
        row.setPointsDiscountMinor(amounts.pointsDiscountMinor());
        row.setWaiverMinor(amounts.waiverMinor());
        row.setFeeMinor(amounts.feeMinor());
        row.setNetMinor(amounts.netMinor());
        row.setTaxMinor(amounts.taxMinor());
        row.setReceivableMinor(amounts.receivableMinor());
        row.setConsiderationOffsetMinor(amounts.considerationOffsetMinor());
    }

    private void amounts(OrdPriceQuoteLine row, Amounts amounts) {
        row.setGoodsMinor(amounts.goodsMinor());
        row.setPriceAdjustmentMinor(amounts.priceAdjustmentMinor());
        row.setActivityDiscountMinor(amounts.activityDiscountMinor());
        row.setCouponDiscountMinor(amounts.couponDiscountMinor());
        row.setManualDiscountMinor(amounts.manualDiscountMinor());
        row.setPointsDiscountMinor(amounts.pointsDiscountMinor());
        row.setWaiverMinor(amounts.waiverMinor());
        row.setFeeMinor(amounts.feeMinor());
        row.setNetMinor(amounts.netMinor());
        row.setTaxMinor(amounts.taxMinor());
        row.setReceivableMinor(amounts.receivableMinor());
        row.setConsiderationOffsetMinor(amounts.considerationOffsetMinor());
    }
}
