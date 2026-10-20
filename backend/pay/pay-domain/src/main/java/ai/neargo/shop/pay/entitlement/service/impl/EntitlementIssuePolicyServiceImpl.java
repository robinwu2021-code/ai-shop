package ai.neargo.shop.pay.entitlement.service.impl;

import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.IssuePolicyViews.PolicyView;
import ai.neargo.shop.pay.entitlement.dto.IssuePolicyViews.SavePolicy;
import ai.neargo.shop.pay.entitlement.entity.EntIssuePolicy;
import ai.neargo.shop.pay.entitlement.entity.EntTemplate;
import ai.neargo.shop.pay.entitlement.policy.PayEntitlementFeatureGate;
import ai.neargo.shop.pay.entitlement.service.EntitlementCommandDeduplicator;
import ai.neargo.shop.pay.entitlement.service.EntitlementIssuePolicyService;
import ai.neargo.shop.pay.mapper.entitlement.EntIssuePolicyMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntIssueRecipientMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntTemplateMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Template-wide capacity survives versions; terminal grants retain their capacity even when new issuance is off. */
@Service
public class EntitlementIssuePolicyServiceImpl implements EntitlementIssuePolicyService {
    private final EntTemplateMapper templates;
    private final EntIssuePolicyMapper policies;
    private final EntIssueRecipientMapper recipients;
    private final EntitlementCommandDeduplicator commands;
    private final PayEntitlementFeatureGate features;
    public EntitlementIssuePolicyServiceImpl(EntTemplateMapper templates, EntIssuePolicyMapper policies,
            EntIssueRecipientMapper recipients, EntitlementCommandDeduplicator commands, PayEntitlementFeatureGate features) {
        this.templates = templates; this.policies = policies; this.recipients = recipients;
        this.commands = commands; this.features = features;
    }
    @Override @Transactional("payTxManager")
    public PolicyView save(CatalogScope scope, String no, SavePolicy command, String key) {
        id(no);
        if (command == null || command.expectedRevision() < 0 || command.perUserLimit() < 1
                || (command.limitCount() != null && command.limitCount() < 0)
                || (command.maxExposureMinor() != null && command.maxExposureMinor() < 0)) bad();
        owned(scope, no, false);
        return commands.execute(scope, "ISSUE_POLICY", key, List.of(no, command), no, PolicyView.class, () -> {
            if (command.enabled()) features.requireCatalog();
            owned(scope, no, true);
            var row = policies.lock(scope.tenantNo(), no);
            if (row == null) {
                if (command.expectedRevision() != 0) conflict();
                row = new EntIssuePolicy(); audit(row, scope);
                row.setTemplateNo(no); row.setHeldCount(0L); row.setIssuedCount(0L);
                row.setHeldExposureMinor(0L); row.setUsedExposureMinor(0L);
                row.setPerUserLimit(command.perUserLimit()); row.setEnabled(0);
                changed(policies.insert(row));
            }
            if (row.getVersion() != command.expectedRevision()
                    || recipients.findExceeding(scope.tenantNo(), no, command.perUserLimit()) != null) conflict();
            row.setLimitCount(command.limitCount()); row.setMaxExposureMinor(command.maxExposureMinor());
            row.setPerUserLimit(command.perUserLimit()); row.setEnabled(command.enabled() ? 1 : 0);
            row.setUpdatedAt(now()); row.setUpdatedBy(scope.actorNo()); changed(policies.configure(row));
            return get(scope, no);
        });
    }
    @Override public PolicyView get(CatalogScope scope, String no) {
        id(no); owned(scope, no, false);
        var p = policies.selectOne(Wrappers.<EntIssuePolicy>lambdaQuery()
                .eq(EntIssuePolicy::getTenantNo, scope.tenantNo()).eq(EntIssuePolicy::getTemplateNo, no));
        if (p == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return new PolicyView(no, p.getLimitCount(), p.getHeldCount(), p.getIssuedCount(), p.getMaxExposureMinor(),
                p.getHeldExposureMinor(), p.getUsedExposureMinor(), p.getPerUserLimit(), p.getEnabled() == 1, p.getVersion());
    }
    @Override @Transactional(value = "payTxManager", propagation = Propagation.MANDATORY)
    public void reserve(CatalogScope scope, String no, String user, int quantity, long amount) {
        inputs(no, user, quantity, amount); features.requireIssuance();
        var head = owned(scope, no, true);
        if (!"ACTIVE".equals(head.getStatus()) || head.getCurrentVersionNo() == null || head.getArchivedAt() != null) conflict();
        var row = policies.lock(scope.tenantNo(), no);
        if (row == null) conflict();
        recipients.ensure(scope.tenantNo(), no, user, scope.actorNo(), now());
        changed(policies.reserve(scope.tenantNo(), no, quantity, amount, scope.actorNo(), now()));
        changed(recipients.reserve(scope.tenantNo(), no, user, quantity, row.getPerUserLimit(), scope.actorNo(), now()));
    }
    @Override @Transactional(value = "payTxManager", propagation = Propagation.MANDATORY)
    public void settle(CatalogScope scope, String no, String user, int quantity, long amount, boolean issue) {
        inputs(no, user, quantity, amount); owned(scope, no, true);
        if (policies.lock(scope.tenantNo(), no) == null) conflict();
        changed(policies.settle(scope.tenantNo(), no, quantity, amount, issue ? quantity : 0,
                issue ? amount : 0, scope.actorNo(), now()));
        changed(recipients.settle(scope.tenantNo(), no, user, quantity, issue ? quantity : 0, scope.actorNo(), now()));
    }
    private EntTemplate owned(CatalogScope scope, String no, boolean lock) {
        var head = lock ? templates.lockOwned(scope.tenantNo(), scope.entityNo(), no)
                : templates.findOwned(scope.tenantNo(), scope.entityNo(), no);
        if (head == null) throw BizException.of(ErrorCode.NOT_FOUND);
        if ("PLATFORM".equals(head.getFunder()) && !scope.platformActor()) throw BizException.of(ErrorCode.FORBIDDEN);
        return head;
    }
    private static void inputs(String no, String user, int quantity, long amount) {
        id(no); id(user); if (quantity <= 0 || amount < 0) bad();
    }
    private static void id(String s) {
        if (s == null || s.isBlank() || s.length() > 64 || !s.equals(s.strip())) bad();
    }
    private static void audit(BaseEntity row, CatalogScope scope) {
        row.setTenantNo(scope.tenantNo()); row.setVersion(0L); row.setDeleted(0);
        row.setCreatedBy(scope.actorNo()); row.setUpdatedBy(scope.actorNo()); row.setCreatedAt(now()); row.setUpdatedAt(now());
    }
    private static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS); }
    private static void changed(int n) { if (n != 1) conflict(); }
    private static void bad() { throw BizException.of(ErrorCode.BAD_REQUEST); }
    private static void conflict() { throw BizException.of(ErrorCode.CONFLICT); }
}
