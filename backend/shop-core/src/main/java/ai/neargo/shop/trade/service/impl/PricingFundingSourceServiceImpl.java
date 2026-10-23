package ai.neargo.shop.trade.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.entitlement.DecisionRef.DecisionType;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.SourceDecisionPort.SourceDecision;
import ai.neargo.shop.spi.entitlement.SourceDecisionPort.State;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Credentials;
import ai.neargo.shop.trade.assembler.PricingQuoteAssembler;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Context;
import ai.neargo.shop.trade.entity.OrdPriceQuote;
import ai.neargo.shop.trade.entity.OrdPricingFundingSource;
import ai.neargo.shop.trade.mapper.TradeMappers.PriceQuoteMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.PricingFundingSourceMapper;
import ai.neargo.shop.trade.policy.PricingFundingSourcePolicy;
import ai.neargo.shop.trade.policy.PricingFundingBasisPolicy;
import ai.neargo.shop.trade.service.EntitlementSourceDecisionService;
import ai.neargo.shop.trade.service.PricingFundingSourceService;
import ai.neargo.shop.trade.service.PricingPreparationService;
import ai.neargo.shop.trade.service.PricingQuoteService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.NoSuchElementException;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Short local transactions only. No calls into PAY, no asset authorization and no inferred cancellation. */
@Service
public class PricingFundingSourceServiceImpl implements PricingFundingSourceService {
    private final PriceQuoteMapper quotes;
    private final PricingFundingSourceMapper sources;
    private final PricingQuoteService quoteService;
    private final PricingPreparationService preparations;
    private final EntitlementSourceDecisionService decisions;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final PricingQuoteAssembler assembler = new PricingQuoteAssembler();

    @Autowired
    public PricingFundingSourceServiceImpl(PriceQuoteMapper quotes, PricingFundingSourceMapper sources,
            PricingQuoteService quoteService, PricingPreparationService preparations,
            EntitlementSourceDecisionService decisions,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this(quotes, sources, quoteService, preparations, decisions, manager, Clock.systemUTC());
    }

    public PricingFundingSourceServiceImpl(PriceQuoteMapper quotes, PricingFundingSourceMapper sources,
            PricingQuoteService quoteService, PricingPreparationService preparations,
            EntitlementSourceDecisionService decisions, PlatformTransactionManager manager, Clock clock) {
        this.quotes = quotes;
        this.sources = sources;
        this.quoteService = quoteService;
        this.preparations = preparations;
        this.decisions = decisions;
        this.transactions = new TransactionTemplate(manager);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public View stage(Context context, Credentials credentials, String quoteNo, String expectedPreparationHash) {
        validateContext(context);
        outsideTransaction();
        credentials(context, credentials);
        if (expectedPreparationHash == null || !expectedPreparationHash.matches("[0-9a-f]{64}")) throw bad();
        var quote = withoutScope(() -> quote(context, quoteNo, false));
        var prepared = preparations.confirmCustomer(validated(() -> PricingFundingSourcePolicy.source(quote)),
                credentials, expectedPreparationHash);
        if (!expectedPreparationHash.equals(prepared.preparationHash())
                || !now().toInstant(ZoneOffset.UTC).isBefore(prepared.expiresAt())) throw conflict();
        var basis = validated(() -> PricingFundingSourcePolicy.verify(context, quote, prepared));
        return withoutScope(() -> transactions.execute(tx -> {
            // Serialize duplicate stage requests on the actual retained quote, not an absent journal row.
            var locked = quote(context, quoteNo, true);
            if (!quote.getEconomicsHash().equals(locked.getEconomicsHash())) throw conflict();
            var existing = sources.selectOne(scope(context).eq("quote_no", quoteNo));
            if (existing != null) {
                if (!existing.getPreparationHash().equals(expectedPreparationHash)) throw conflict();
                return view(existing);
            }
            var at = now();
            if (!"READY".equals(locked.getStatus()) || !at.isBefore(locked.getExpiresAt())
                    || !at.toInstant(ZoneOffset.UTC).isBefore(prepared.expiresAt())) throw conflict();
            var row = new OrdPricingFundingSource();
            row.setFundingSourceNo(BizKey.next(BizKey.PRICING_FUNDING_SOURCE));
            row.setTenantNo(context.tenantNo()); row.setEntityNo(context.entityNo());
            row.setStoreNo(context.storeNo()); row.setUserNo(context.subjectNo()); row.setQuoteNo(quoteNo);
            row.setCurrency(locked.getCurrency()); row.setAmountMinor(locked.getReceivableMinor());
            row.setEconomicsHash(locked.getEconomicsHash()); row.setPreparationHash(expectedPreparationHash);
            var expiry = LocalDateTime.ofInstant(prepared.expiresAt().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
            row.setExpiresAt(expiry.isBefore(locked.getExpiresAt()) ? expiry : locked.getExpiresAt());
            row.setBasisSnapshot(assembler.canonical(basis));
            row.setSnapshotHash(digest(row)); row.setStatus("PENDING");
            row.setVersion(0L); row.setDeleted(0); row.setCreatedAt(at); row.setUpdatedAt(at);
            row.setCreatedBy(context.actorNo()); row.setUpdatedBy(context.actorNo());
            quoteService.retain(context, quoteNo, row.getFundingSourceNo());
            sources.insert(row);
            return view(row);
        }));
    }

    @Override
    public BasisView readBasis(Context context, Credentials credentials, String sourceNo) {
        validateContext(context);
        outsideTransaction();
        credentials(context, credentials);
        var row = withoutScope(() -> head(context, sourceNo, false));
        usable(row);
        if (row.getBasisSnapshot() == null) throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
        var frozen = validated(() -> PricingFundingBasisPolicy.read(row));
        var quote = withoutScope(() -> quote(context, row.getQuoteNo(), false));
        if (!"READY".equals(quote.getStatus()) || !now().isBefore(quote.getExpiresAt())
                || !row.getFundingSourceNo().equals(quote.getRetainedRef())) throw conflict();
        var prepared = preparations.confirmCustomer(validated(() -> PricingFundingSourcePolicy.source(quote)),
                credentials, row.getPreparationHash());
        if (!row.getPreparationHash().equals(prepared.preparationHash())
                || !now().toInstant(ZoneOffset.UTC).isBefore(prepared.expiresAt())) throw conflict();
        var current = validated(() -> PricingFundingSourcePolicy.verify(context, quote, prepared));
        if (!assembler.canonical(frozen).equals(assembler.canonical(current))) throw conflict();
        var after = withoutScope(() -> head(context, sourceNo, false));
        usable(after);
        if (!row.getSnapshotHash().equals(after.getSnapshotHash())) throw conflict();
        return new BasisView(view(after), frozen);
    }

    @Override
    public BasisView confirmBasis(Context context, Credentials credentials, String sourceNo,
            String expectedSnapshotHash) {
        if (expectedSnapshotHash == null || !expectedSnapshotHash.matches("[0-9a-f]{64}")) throw bad();
        var current = readBasis(context, credentials, sourceNo);
        if (!expectedSnapshotHash.equals(current.source().snapshotHash())) throw conflict();
        return current;
    }

    private void usable(OrdPricingFundingSource row) {
        if (!"PENDING".equals(row.getStatus()) || !now().isBefore(row.getExpiresAt())) throw conflict();
    }
    private void credentials(Context context, Credentials credentials) {
        if (credentials == null || credentials.sessionNo() != null
                || !context.subjectNo().equals(credentials.buyerNo())) throw bad();
    }
    private void outsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw conflict();
    }

    @Override
    public View get(Context context, String sourceNo) {
        validateContext(context);
        return withoutScope(() -> view(head(context, sourceNo, false)));
    }

    @Override
    public View cancel(Context context, String sourceNo, String reason) {
        validateContext(context);
        if (reason == null || reason.isBlank() || !reason.equals(reason.strip()) || reason.length() > 128) throw bad();
        return withoutScope(() -> transactions.execute(tx -> {
            var row = head(context, sourceNo, true);
            if ("CANCELLED".equals(row.getStatus())) {
                if (!reason.equals(row.getCancelReason())) throw conflict();
                return view(row);
            }
            if (!"PENDING".equals(row.getStatus())) throw conflict();
            var at = now();
            decisions.record(context.tenantNo(), ref(row), "USE", DecisionType.USE_CANCEL, null, null,
                    row.getUserNo(), row.getEntityNo(), row.getStoreNo(), row.getCurrency(), row.getAmountMinor(),
                    row.getSnapshotHash(), at.toInstant(ZoneOffset.UTC));
            var update = new UpdateWrapper<OrdPricingFundingSource>()
                    .eq("tenant_no", context.tenantNo()).eq("funding_source_no", sourceNo).eq("deleted", 0)
                    .eq("version", row.getVersion()).eq("status", "PENDING")
                    .set("status", "CANCELLED").set("cancel_reason", reason).set("version", row.getVersion() + 1)
                    .set("updated_at", at).set("updated_by", context.actorNo());
            if (sources.update(null, update) != 1) throw conflict();
            row.setStatus("CANCELLED"); row.setCancelReason(reason);
            return view(row);
        }));
    }

    @Override
    public SourceDecision pendingDecision(EntitlementContext context, SourceRef source) {
        if (!"CORE".equals(source.sourceSystem()) || !SOURCE_TYPE.equals(source.sourceType())
                || source.sourceRevision() != 1 || source.sourceLineNo() != null
                || source.unitIndex() != null || source.entitlementIndex() != null) return unknown(source);
        var row = withoutScope(() -> sources.selectOne(new QueryWrapper<OrdPricingFundingSource>()
                .eq("tenant_no", context.tenantNo()).eq("funding_source_no", source.sourceNo()).eq("deleted", 0)));
        if (row == null) return unknown(source);
        integrity(row);
        // Terminal truth belongs to the original decision reader. A missing terminal record is corruption.
        if (!"PENDING".equals(row.getStatus())) throw conflict();
        return new SourceDecision(source, State.PENDING, null, row.getUserNo(), row.getEntityNo(), row.getStoreNo(),
                row.getCurrency(), row.getAmountMinor(), row.getSnapshotHash());
    }

    private OrdPriceQuote quote(Context context, String quoteNo, boolean lock) {
        validated(() -> { PricingQuoteAssembler.identity(quoteNo, 32); return true; });
        var query = new QueryWrapper<OrdPriceQuote>().eq("tenant_no", context.tenantNo())
                .eq("entity_no", context.entityNo()).eq("store_no", context.storeNo())
                .eq("subject_no", context.subjectNo()).eq("quote_no", quoteNo).eq("deleted", 0);
        if (lock) query.last("FOR UPDATE");
        var row = quotes.selectOne(query);
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return row;
    }

    private QueryWrapper<OrdPricingFundingSource> scope(Context context) {
        return new QueryWrapper<OrdPricingFundingSource>().eq("tenant_no", context.tenantNo())
                .eq("entity_no", context.entityNo()).eq("store_no", context.storeNo())
                .eq("user_no", context.subjectNo()).eq("deleted", 0);
    }
    private OrdPricingFundingSource head(Context context, String no, boolean lock) {
        validated(() -> { PricingQuoteAssembler.identity(no, 32); return true; });
        var query = scope(context).eq("funding_source_no", no);
        if (lock) query.last("FOR UPDATE");
        var row = sources.selectOne(query);
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        integrity(row);
        return row;
    }
    private View view(OrdPricingFundingSource row) {
        integrity(row);
        return new View(ref(row), row.getQuoteNo(), row.getStatus(), row.getExpiresAt().toInstant(ZoneOffset.UTC),
                row.getSnapshotHash());
    }
    private SourceRef ref(OrdPricingFundingSource row) {
        return new SourceRef("CORE", SOURCE_TYPE, row.getFundingSourceNo(), null, null, null, 1);
    }
    private void integrity(OrdPricingFundingSource row) {
        if (!Objects.equals(row.getSnapshotHash(), digest(row))) throw conflict();
    }
    private String digest(OrdPricingFundingSource row) {
        var terms = new ArrayList<Object>(List.of("PRICING_FUNDING_JOURNAL_V1", ref(row), row.getTenantNo(),
                row.getEntityNo(), row.getStoreNo(), row.getUserNo(), row.getQuoteNo(), row.getCurrency(),
                row.getAmountMinor(), row.getEconomicsHash(), row.getPreparationHash(),
                row.getExpiresAt().toInstant(ZoneOffset.UTC).toString()));
        if (row.getBasisSnapshot() != null) {
            terms.set(0, "PRICING_FUNDING_JOURNAL_V2");
            terms.add(row.getBasisSnapshot());
        }
        String value = assembler.canonical(terms);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private void validateContext(Context context) {
        validated(() -> {
            assembler.context(context);
            if (!"MAIN".equals(context.tenantNo()) || context.subjectNo() == null) throw new IllegalArgumentException();
            return true;
        });
    }
    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }
    private static SourceDecision unknown(SourceRef source) {
        return new SourceDecision(source, State.UNKNOWN, null, null, null, null, null, null, null);
    }
    private <T> T validated(Supplier<T> action) {
        try { return action.get(); }
        catch (IllegalArgumentException | ArithmeticException | NullPointerException | NoSuchElementException e) {
            throw bad();
        }
    }
    private <T> T withoutScope(Supplier<T> action) { return DataScopeContext.executeWithoutScope(action); }
    private static BizException bad() { return BizException.of(ErrorCode.BAD_REQUEST); }
    private static BizException conflict() { return BizException.of(ErrorCode.CONFLICT); }
}
