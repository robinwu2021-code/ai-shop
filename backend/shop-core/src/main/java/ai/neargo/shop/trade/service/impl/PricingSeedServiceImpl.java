package ai.neargo.shop.trade.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSeedValidator;
import ai.neargo.shop.common.pricing.PricingSeedValidator.SeedPlan;
import ai.neargo.shop.common.pricing.PricingSeedValidator.TemplateDraft;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.trade.dto.PricingConfigCommands.Context;
import ai.neargo.shop.trade.entity.OrdPricingTemplate;
import ai.neargo.shop.trade.mapper.TradeMappers.PricingTemplateMapper;
import ai.neargo.shop.trade.service.PricingSeedService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Seeds create drafts only. Permanent provenance outlives the short-lived idempotency receipt. */
@Service
public class PricingSeedServiceImpl implements PricingSeedService {
    private final PricingTemplateMapper templates;
    private final IdempotencyService idempotency;
    private final TransactionTemplate transactions;
    private final PricingSeedValidator validator = new PricingSeedValidator();

    public PricingSeedServiceImpl(PricingTemplateMapper templates, IdempotencyService idempotency,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this.templates = templates;
        this.idempotency = idempotency;
        this.transactions = new TransactionTemplate(manager);
    }

    @Override
    public ImportReport importDrafts(Context c, String source, String registryVersion, ImportMode mode) {
        if (c == null || !c.platformOperator()) throw BizException.of(ErrorCode.FORBIDDEN);
        if (mode == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        final SeedPlan plan;
        try {
            plan = validator.validate(source, registryVersion);
        } catch (IllegalArgumentException error) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (mode == ImportMode.DRY_RUN) return report(plan, mode, List.of());
        return DataScopeContext.executeWithoutScope(() -> {
            for (int attempt = 0; ; attempt++) {
                try {
                    return importValidated(c, plan);
                } catch (PessimisticLockingFailureException transientLockFailure) {
                    // Only a fully rolled-back, standalone attempt can safely start a new transaction.
                    if (attempt >= 2 || TransactionSynchronizationManager.isActualTransactionActive()) {
                        throw transientLockFailure;
                    }
                }
            }
        });
    }

    private ImportReport importValidated(Context c, SeedPlan plan) {
        var existing = existing(c, plan);
        if (existing != null) return existing;
        try {
            return transactions.execute(status -> idempotency.execute("PRICING_SEED:" + plan.seedVersion(),
                    "PRICING_SEED:" + c.tenantNo(), c.actorNo(), plan.seedHash(), ImportReport.class, () -> {
                        var completed = existing(c, plan);
                        if (completed != null) return completed;
                        var refs = new ArrayList<TemplateRef>();
                        for (var draft : plan.templates()) refs.add(insert(c, plan, draft));
                        return report(plan, ImportMode.DRAFT, refs);
                    }));
        } catch (BizException error) {
            // Another authorized operator may have committed between preflight and claiming the receipt.
            var completed = existing(c, plan);
            if (completed != null) return completed;
            throw error;
        }
    }

    private TemplateRef insert(Context c, SeedPlan plan, TemplateDraft draft) {
        var row = new OrdPricingTemplate();
        row.setTenantNo(c.tenantNo());
        row.setCreatedAt(LocalDateTime.now());
        row.setUpdatedAt(LocalDateTime.now());
        row.setCreatedBy(c.actorNo());
        row.setUpdatedBy(c.actorNo());
        row.setVersion(0L);
        row.setDeleted(0);
        row.setTemplateNo(BizKey.next("PT"));
        row.setIndustryCode(draft.industryCode());
        row.setTemplateCode(templateCode(plan, draft));
        row.setName(draft.sourceTemplateNo());
        row.setStatus("ACTIVE");
        row.setDraftContent(draft.definition());
        row.setDraftSchemaVersion(2);
        row.setDraftVersion(0L);
        row.setSeedVersion(plan.seedVersion());
        row.setSeedHash(plan.seedHash());
        row.setSeedSnapshot(plan.provenanceSnapshot());
        templates.insert(row);
        return new TemplateRef(draft.sourceTemplateNo(), row.getTemplateNo(), draft.industryCode());
    }

    private ImportReport existing(Context c, SeedPlan plan) {
        var rows = templates.selectList(new QueryWrapper<OrdPricingTemplate>()
                .eq("tenant_no", c.tenantNo()).eq("seed_version", plan.seedVersion()));
        if (rows.isEmpty()) return null;
        if (rows.size() != plan.templates().size()) throw BizException.of(ErrorCode.CONFLICT);
        var refs = new ArrayList<TemplateRef>();
        for (var draft : plan.templates()) {
            var matches = rows.stream().filter(row -> draft.industryCode().equals(row.getIndustryCode())
                    && templateCode(plan, draft).equals(row.getTemplateCode())).toList();
            if (matches.size() != 1) throw BizException.of(ErrorCode.CONFLICT);
            var row = matches.getFirst();
            if (!Objects.equals(row.getSeedHash(), plan.seedHash())
                    || !Objects.equals(row.getSeedSnapshot(), plan.provenanceSnapshot())) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            refs.add(new TemplateRef(draft.sourceTemplateNo(), row.getTemplateNo(), draft.industryCode()));
        }
        return report(plan, ImportMode.DRAFT, refs);
    }

    private static ImportReport report(SeedPlan plan, ImportMode mode, List<TemplateRef> refs) {
        return new ImportReport(mode, plan.seedVersion(), plan.seedHash(), plan.ruleCount(),
                plan.templates().size(), plan.taxDraftCount(), refs);
    }

    private static String templateCode(SeedPlan plan, TemplateDraft draft) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                    (plan.seedVersion() + "\n" + draft.sourceTemplateNo()).getBytes(StandardCharsets.UTF_8));
            return "SEED_" + HexFormat.of().formatHex(hash).substring(0, 24);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
