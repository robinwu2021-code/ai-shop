package ai.neargo.shop.trade.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.common.pricing.TaxSettingDefinition;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import ai.neargo.shop.spi.platform.SettingPort;
import ai.neargo.shop.spi.platform.SettingPort.VersionedValue;
import ai.neargo.shop.spi.trade.PricingTaxSourceChanged;
import ai.neargo.shop.trade.dto.PricingConfigCommands.Context;
import ai.neargo.shop.trade.dto.PricingTaxSettingCommands.ResetReq;
import ai.neargo.shop.trade.dto.PricingTaxSettingCommands.SaveReq;
import ai.neargo.shop.trade.dto.PricingTaxSettingCommands.SettingVO;
import ai.neargo.shop.trade.dto.PricingTaxSettingCommands.Snapshot;
import ai.neargo.shop.trade.dto.PricingTaxSettingCommands.Source;
import ai.neargo.shop.trade.service.PricingTaxSettingService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Merchant-owned tax settings use the existing platform store, not another merchant or tax account
 * ledger.
 */
@Service
public class PricingTaxSettingServiceImpl implements PricingTaxSettingService {
    private static final Logger LOG = LoggerFactory.getLogger(PricingTaxSettingServiceImpl.class);
    private static final String DEFAULT_KEY = "pricing.tax.default";
    private final SettingPort settings;
    private final AuditLogPort audit;
    private final IdempotencyService idempotency;
    private final TransactionTemplate transactions;
    private final TransactionTemplate auditTransactions;
    private final ObjectMapper json;
    private OutboxEventBus sourceEvents;

    @Autowired
    public void sourceEvents(OutboxEventBus events) {
        sourceEvents = events;
    }

    public PricingTaxSettingServiceImpl(
            SettingPort settings,
            AuditLogPort audit,
            IdempotencyService idempotency,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            ObjectMapper json) {
        this.settings = settings;
        this.audit = audit;
        this.idempotency = idempotency;
        this.transactions = new TransactionTemplate(manager);
        this.auditTransactions = new TransactionTemplate(manager);
        this.auditTransactions.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.json = json;
    }

    @Override
    public SettingVO getPlatform(Context context) {
        authorize(context, true);
        var value = read(DEFAULT_KEY);
        return new SettingVO(
                value.map(VersionedValue::version).orElse(0L),
                value.isPresent(),
                platformSnapshot(value));
    }

    @Override
    public SettingVO getMerchant(Context context) {
        authorize(context, false);
        return merchantView(context, read(merchantKey(context)));
    }

    @Override
    public Snapshot requireEffective(Context context) {
        var result = getMerchant(context).effective();
        if (result == null) throw blocked();
        return result;
    }

    @Override
    public SettingVO savePlatform(Context context, SaveReq request, String key) {
        authorize(context, true);
        var value = custom(request);
        return write(
                context,
                "SAVE_PLATFORM",
                request,
                key,
                () -> change(context, DEFAULT_KEY, request.expectedVersion(), value, true));
    }

    @Override
    public SettingVO saveMerchant(Context context, SaveReq request, String key) {
        authorize(context, false);
        var value = custom(request);
        return write(
                context,
                "SAVE_MERCHANT",
                request,
                key,
                () ->
                        change(
                                context,
                                merchantKey(context),
                                request.expectedVersion(),
                                value,
                                false));
    }

    @Override
    public SettingVO resetMerchant(Context context, ResetReq request, String key) {
        authorize(context, false);
        if (request == null) throw bad();
        return write(
                context,
                "RESET_MERCHANT",
                request,
                key,
                () ->
                        change(
                                context,
                                merchantKey(context),
                                request.expectedVersion(),
                                new TaxSettingDefinition(false, null, null),
                                false));
    }

    private SettingVO change(
            Context context,
            String key,
            long version,
            TaxSettingDefinition value,
            boolean platform) {
        if (version < 0 || version >= 9007199254740991L) throw bad();
        var original = read(key);
        if (version != original.map(VersionedValue::version).orElse(0L)) throw conflict();
        var updated =
                settings.compareAndSet(
                        key, original.orElse(null), value.canonicalJson(), context.actorNo());
        var result =
                platform
                        ? new SettingVO(
                                updated.version(), true, platformSnapshot(Optional.of(updated)))
                        : merchantView(context, Optional.of(updated));
        if (sourceEvents != null)
            sourceEvents.publish(
                    new PricingTaxSourceChanged(
                            context.tenantNo(),
                            platform ? null : context.entityNo(),
                            key,
                            updated.version(),
                            PricingSnapshotHash.of(updated.json()),
                            context.actorNo()));
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        auditCommitted(context, key, original, updated);
                    }
                });
        return result;
    }

    private void auditCommitted(
            Context context,
            String key,
            Optional<VersionedValue> original,
            VersionedValue updated) {
        try {
            // A deadlock in best-effort audit must never roll back a setting or its successful
            // receipt.
            auditTransactions.executeWithoutResult(
                    status ->
                            audit.record(
                                    "PRICING_TAX_SETTING_CHANGED",
                                    key,
                                    "actor=" + context.actorNo() + ";version=" + updated.version(),
                                    true,
                                    original.map(VersionedValue::json).orElse(null),
                                    updated.json()));
        } catch (RuntimeException unavailableAudit) {
            LOG.warn("Tax setting audit unavailable for {}", key);
        }
    }

    private SettingVO merchantView(Context context, Optional<VersionedValue> value) {
        long version = value.map(VersionedValue::version).orElse(0L);
        if (value.isPresent()) {
            var definition = definition(value.get());
            if (definition.custom()) {
                return new SettingVO(
                        version,
                        true,
                        snapshot(
                                Source.MERCHANT_CUSTOM,
                                context.entityNo(),
                                value.get(),
                                definition));
            }
        }
        return new SettingVO(version, false, platformSnapshot(read(DEFAULT_KEY)));
    }

    private Snapshot platformSnapshot(Optional<VersionedValue> value) {
        if (value.isEmpty()) return null;
        var definition = definition(value.get());
        if (!definition.custom()) throw blocked();
        return snapshot(Source.PLATFORM_DEFAULT, "PLATFORM", value.get(), definition);
    }

    private Snapshot snapshot(
            Source source, String owner, VersionedValue value, TaxSettingDefinition definition) {
        return new Snapshot(
                source,
                owner,
                value.version(),
                definition.rateBp(),
                definition.priceTaxMode(),
                definition.snapshotHash(source + ":" + owner, value.version()));
    }

    private Optional<VersionedValue> read(String key) {
        try {
            return settings.getVersioned(key);
        } catch (UnsupportedOperationException unsupported) {
            throw blocked();
        }
    }

    private TaxSettingDefinition definition(VersionedValue value) {
        try {
            return TaxSettingDefinition.parse(value.json());
        } catch (IllegalArgumentException invalid) {
            throw blocked();
        }
    }

    private TaxSettingDefinition custom(SaveReq request) {
        if (request == null) throw bad();
        try {
            return new TaxSettingDefinition(true, request.rateBp(), request.priceTaxMode());
        } catch (IllegalArgumentException invalid) {
            throw bad();
        }
    }

    private SettingVO write(
            Context context,
            String operation,
            Object request,
            String key,
            Supplier<SettingVO> action) {
        if (key == null || key.isBlank() || key.length() > 128) throw bad();
        String endpoint =
                "TAX_SETTING:"
                        + hash(
                                json.writeValueAsString(
                                        Arrays.asList(
                                                context.tenantNo(),
                                                context.platformOperator()
                                                        ? null
                                                        : context.entityNo(),
                                                operation)));
        String requestHash = hash(json.writeValueAsString(request));
        for (int attempt = 0; ; attempt++) {
            try {
                return transactions.execute(
                        status ->
                                idempotency.execute(
                                        key,
                                        endpoint,
                                        context.actorNo(),
                                        requestHash,
                                        SettingVO.class,
                                        action));
            } catch (PessimisticLockingFailureException lockFailure) {
                if (TransactionSynchronizationManager.isActualTransactionActive())
                    throw lockFailure;
                var receipt =
                        idempotency.findCompleted(
                                key, endpoint, context.actorNo(), requestHash, SettingVO.class);
                if (receipt.isPresent()) return receipt.get();
                if (attempt >= 2) throw conflict();
            } catch (BizException failure) {
                if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                    var receipt =
                            idempotency.findCompleted(
                                    key, endpoint, context.actorNo(), requestHash, SettingVO.class);
                    if (receipt.isPresent()) return receipt.get();
                }
                throw failure;
            }
        }
    }

    private void authorize(Context context, boolean platform) {
        if (context == null || context.platformOperator() != platform)
            throw BizException.of(ErrorCode.FORBIDDEN);
    }

    private String merchantKey(Context context) {
        return "pricing.tax.merchant." + context.entityNo();
    }

    private String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static BizException bad() {
        return BizException.of(ErrorCode.BAD_REQUEST);
    }

    private static BizException conflict() {
        return BizException.of(ErrorCode.CONFLICT);
    }

    private static BizException blocked() {
        return BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
    }
}
