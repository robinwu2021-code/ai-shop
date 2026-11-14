package ai.neargo.shop.pay.storedvalue.service.impl;

import static ai.neargo.shop.pay.storedvalue.policy.StoredValueFacts.audit;
import static ai.neargo.shop.pay.storedvalue.policy.StoredValueFacts.conflict;
import static ai.neargo.shop.pay.storedvalue.policy.StoredValueFacts.digest;
import static ai.neargo.shop.pay.storedvalue.policy.StoredValueFacts.instant;
import static ai.neargo.shop.pay.storedvalue.policy.StoredValueFacts.no;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.dto.UseSnapshots.Prepared;
import ai.neargo.shop.pay.entitlement.entity.EntUsage;
import ai.neargo.shop.pay.entitlement.entity.EntUseIntent;
import ai.neargo.shop.pay.entitlement.event.PayEntitlementEventPublisher;
import ai.neargo.shop.pay.entitlement.service.EntitlementSourceFence;
import ai.neargo.shop.pay.mapper.entitlement.EntUsageMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntUseIntentMapper;
import ai.neargo.shop.pay.mapper.storedvalue.SvAccountMapper;
import ai.neargo.shop.pay.mapper.storedvalue.SvEntryMapper;
import ai.neargo.shop.pay.mapper.storedvalue.SvHoldAllocationMapper;
import ai.neargo.shop.pay.mapper.storedvalue.SvLotMapper;
import ai.neargo.shop.pay.mapper.storedvalue.SvPostingLineMapper;
import ai.neargo.shop.pay.mapper.storedvalue.SvVoucherMapper;
import ai.neargo.shop.pay.storedvalue.entity.SvAccount;
import ai.neargo.shop.pay.storedvalue.entity.SvEntry;
import ai.neargo.shop.pay.storedvalue.entity.SvHoldAllocation;
import ai.neargo.shop.pay.storedvalue.entity.SvLot;
import ai.neargo.shop.pay.storedvalue.entity.SvPostingLine;
import ai.neargo.shop.pay.storedvalue.entity.SvVoucher;
import ai.neargo.shop.pay.storedvalue.policy.StoredValueAllocationPolicy;
import ai.neargo.shop.pay.storedvalue.policy.StoredValuePostingPolicy;
import ai.neargo.shop.pay.storedvalue.policy.StoredValueUseFacts;
import ai.neargo.shop.pay.storedvalue.policy.StoredValueAccountScope;
import ai.neargo.shop.pay.storedvalue.service.StoredValueRedemptionPostingService;
import ai.neargo.shop.pay.storedvalue.service.StoredValueStoreProjector;
import ai.neargo.shop.pay.storedvalue.service.StoredValueStatementProjector;
import ai.neargo.shop.spi.entitlement.DecisionRef;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementEvents.EntitlementUsed;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.HoldView;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.State;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseCommand;
import ai.neargo.shop.spi.entitlement.SourceDecisionPort.SourceDecision;
import ai.neargo.shop.spi.entitlement.SourceRef;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Fixed lock order: fence -> intent -> issuer quota -> account -> lots. Every source/authorization lookup precedes this boundary. */
@Service
public class StoredValueRedemptionPostingServiceImpl implements StoredValueRedemptionPostingService {
    @org.springframework.beans.factory.annotation.Autowired
    private ai.neargo.shop.pay.storedvalue.issuer.StoredValueIssuerLedgerBridge issuerLedger;
    @org.springframework.beans.factory.annotation.Autowired
    private ai.neargo.shop.pay.entitlement.service.PayRightsDocumentNumbers documentNumbers;
    @org.springframework.beans.factory.annotation.Autowired private ai.neargo.shop.pay.storedvalue.issuer.StoredValueLotBasisService lotBasis;
    private final EntitlementSourceFence fences;
    private final EntUseIntentMapper intents;
    @org.springframework.beans.factory.annotation.Autowired
    private ai.neargo.shop.pay.storedvalue.issuer.StoredValueIssuerAccountService issuerAccounts;
    @org.springframework.beans.factory.annotation.Autowired
    private ai.neargo.shop.pay.storedvalue.issuer.StoredValueWalletExecutionGuard walletGuard;
    @org.springframework.beans.factory.annotation.Autowired
    private ai.neargo.shop.pay.storedvalue.clearing.StoredValueClearingService clearing;
    @org.springframework.beans.factory.annotation.Autowired private ai.neargo.shop.pay.storedvalue.issuer.StoredValueBrandLedgerService brandLedger;
    private final SvAccountMapper accounts;
    private final SvLotMapper lots;
    private final SvHoldAllocationMapper allocations;
    private final SvVoucherMapper vouchers;
    private final SvEntryMapper entries;
    private final SvPostingLineMapper journal;
    private final EntUsageMapper usages;
    private final StoredValueAllocationPolicy policy;
    private final StoredValueStoreProjector stores;
    private final PayEntitlementEventPublisher events;
    private final ObjectMapper json;
    private final StoredValueStatementProjector statements;
    public StoredValueRedemptionPostingServiceImpl(EntitlementSourceFence fences, EntUseIntentMapper intents,
            SvAccountMapper accounts, SvLotMapper lots, SvHoldAllocationMapper allocations, SvVoucherMapper vouchers,
            SvEntryMapper entries, SvPostingLineMapper journal, EntUsageMapper usages, StoredValueAllocationPolicy policy,
            StoredValueStoreProjector stores, PayEntitlementEventPublisher events, ObjectMapper json, StoredValueStatementProjector statements) {
        this.fences = fences; this.intents = intents; this.accounts = accounts; this.lots = lots;
        this.allocations = allocations; this.vouchers = vouchers; this.entries = entries; this.journal = journal;
        this.usages = usages; this.policy = policy; this.stores = stores; this.events = events; this.json = json; this.statements=statements;
    }
    @Override @Transactional("payTxManager")
    public HoldView prepare(EntitlementContext ctx, Prepared frozen) {
        var c = frozen.command();
        var fence = fences.lock(ctx, "USE", c.operationNo(), c.sourceRef(), c.userNo(), c.entityNo());
        if (fences.cancelled(fence)) return new HoldView(c.operationNo(), State.RELEASED, null, null, null);
        var claim = new EntUseIntent(); audit(claim, ctx, now());
        claim.setOperationNo(c.operationNo()); claim.setSourceKey(c.sourceRef().useIntentKey());
        claim.setActiveSourceKey(c.sourceRef().settledKey());
        claim.setSourceSnapshot(json.writeValueAsString(c.sourceRef())); claim.setUserNo(c.userNo());
        claim.setEntityNo(c.entityNo()); claim.setStoreNo(c.storeNo()); claim.setAssetKind("STORED_VALUE");
        claim.setAuthorizationRef(c.authorizationRef()); claim.setCommandSnapshot(json.writeValueAsString(frozen));
        claim.setPayloadHash(digest(c, json)); claim.setServiceDay(frozen.serviceDay());
        claim.setExpiresAt(LocalDateTime.ofInstant(frozen.authorization().expiresAt(), ZoneOffset.UTC));
        intents.claim(claim);
        var original = intents.lock(ctx.tenantNo(), c.operationNo());
        if (original == null || !claim.getPayloadHash().equals(original.getPayloadHash())) conflict();
        if (!"PREPARING".equals(original.getStatus())) return view(original);
        boolean brandWallet=walletGuard.before(ctx,c,frozen.source().walletProof(),false);
        if(!brandWallet)issuerLedger.beforeAccount(ctx,c.entityNo(),c.storeNo(),c.currency(),c.accountNo());
        var account = account(ctx,c,frozen.source().walletProof());
        if (!"ACTIVE".equals(account.getStatus()) || account.getVersion() != c.expectedAssetRevision()) conflict();
        var ownedLots = lots.lockLive(ctx.tenantNo(), account.getAccountNo());
        StoredValueUseFacts.conserve(account, ownedLots);
        var plan = policy.allocate(brandWallet?lotBasis.available(ctx.tenantNo(),ownedLots):StoredValueUseFacts.available(ownedLots, json), frozen.source());
        if (plan.amountMinor() != c.amountMinor() || plan.allocations().isEmpty()) conflict();
        var byNo = ownedLots.stream().collect(Collectors.toMap(SvLot::getLotNo, Function.identity()));
        long principal = 0, bonus = 0;
        for (var part : plan.allocations()) {
            var lot = byNo.get(part.lotNo());
            lot.setHeldPrincipalMinor(Math.addExact(lot.getHeldPrincipalMinor(), part.principalMinor()));
            lot.setHeldBonusMinor(Math.addExact(lot.getHeldBonusMinor(), part.bonusMinor()));
            lot.setUpdatedAt(now()); lot.setUpdatedBy(ctx.actorNo()); changed(lots.updateById(lot));
            principal = Math.addExact(principal, part.principalMinor()); bonus = Math.addExact(bonus, part.bonusMinor());
            var allocation = new SvHoldAllocation(); audit(allocation, ctx, now());
            allocation.setOperationNo(c.operationNo()); allocation.setLotNo(part.lotNo());
            allocation.setPrincipalMinor(part.principalMinor()); allocation.setBonusMinor(part.bonusMinor());
            allocation.setItemsAllocation(json.writeValueAsString(part.items())); changed(allocations.insert(allocation));
        }
        issuerLedger.held(ctx,account.getAccountNo(),c.operationNo());
        account.setHeldPrincipalMinor(Math.addExact(account.getHeldPrincipalMinor(), principal));
        account.setHeldBonusMinor(Math.addExact(account.getHeldBonusMinor(), bonus)); save(account, ctx);
        original.setStatus("HELD"); original.setUpdatedAt(now()); original.setUpdatedBy(ctx.actorNo());
        changed(intents.updateById(original)); return view(original);
    }
    @Override @Transactional("payTxManager")
    public HoldView finish(EntitlementContext ctx, String operation, SourceRef source, DecisionRef decision,
                           boolean commit, SourceDecision truth) {
        var known = intents.find(ctx.tenantNo(), operation);
        if (known == null) throw BizException.of(ErrorCode.NOT_FOUND);
        var fence = fences.lock(ctx, "USE", operation, source, known.getUserNo(), known.getEntityNo());
        if (commit && fences.cancelled(fence)) conflict();
        var row = intents.lock(ctx.tenantNo(), operation);
        var frozen = json.readValue(row.getCommandSnapshot(), Prepared.class); var c = frozen.command();
        if (!source.equals(c.sourceRef()) || !"STORED_VALUE".equals(row.getAssetKind())) conflict();
        if (!"HELD".equals(row.getStatus())) {
            if (!(commit ? "COMMITTED" : "RELEASED").equals(row.getStatus())
                    || !decision.decisionNo().equals(row.getSourceDecisionNo())) conflict();
            return view(row);
        }
        boolean brandWallet=walletGuard.before(ctx,c,frozen.source().walletProof(),true);
        if(!brandWallet)issuerLedger.beforeAccount(ctx,c.entityNo(),c.storeNo(),c.currency(),c.accountNo());
        var account = account(ctx,c,frozen.source().walletProof());
        var ownedLots = lots.lockLive(ctx.tenantNo(), account.getAccountNo());
        StoredValueUseFacts.conserve(account, ownedLots);
        Map<String, SvLot> byNo = ownedLots.stream().collect(Collectors.toMap(SvLot::getLotNo, Function.identity()));
        var parts = allocations.lockOperation(ctx.tenantNo(), operation);
        long principal = 0, bonus = 0;
        for (var part : parts) {
            var lot = byNo.get(part.getLotNo());
            if (lot == null || part.getPrincipalMinor() < 0 || part.getBonusMinor() < 0
                    || lot.getHeldPrincipalMinor() < part.getPrincipalMinor()
                    || lot.getHeldBonusMinor() < part.getBonusMinor()) conflict();
            principal = Math.addExact(principal, part.getPrincipalMinor());
            bonus = Math.addExact(bonus, part.getBonusMinor());
            lot.setHeldPrincipalMinor(lot.getHeldPrincipalMinor() - part.getPrincipalMinor());
            lot.setHeldBonusMinor(lot.getHeldBonusMinor() - part.getBonusMinor());
            if (commit) {
                lot.setPrincipalMinor(lot.getPrincipalMinor() - part.getPrincipalMinor());
                lot.setBonusMinor(lot.getBonusMinor() - part.getBonusMinor());
            }
            lot.setUpdatedAt(now()); lot.setUpdatedBy(ctx.actorNo()); changed(lots.updateById(lot));
        }
        if (parts.isEmpty() || Math.addExact(principal, bonus) != c.amountMinor()
                || account.getHeldPrincipalMinor() < principal || account.getHeldBonusMinor() < bonus) conflict();
        account.setHeldPrincipalMinor(account.getHeldPrincipalMinor() - principal);
        account.setHeldBonusMinor(account.getHeldBonusMinor() - bonus);
        if (commit) {
            account.setPrincipalMinor(account.getPrincipalMinor() - principal);
            account.setBonusMinor(account.getBonusMinor() - bonus);
            account.setAccountSeq(Math.addExact(account.getAccountSeq(), 1));
            var at = LocalDateTime.ofInstant(decision.occurredAt(), ZoneOffset.UTC);
            var voucher=post(ctx, account, frozen, principal, bonus, parts, at);
            row.setUsageNo(no("EU", List.of(ctx.tenantNo(), operation), json));
            row.setCommittedAt(at); row.setSettledSourceKey(source.settledKey());
            var usage = new EntUsage(); audit(usage, ctx, now());
            usage.setUsageNo(row.getUsageNo()); usage.setOperationNo(operation); usage.setUserNo(c.userNo());
            usage.setEntityNo(c.entityNo()); usage.setStoreNo(c.storeNo());
            usage.setStoreName(frozen.source().storeName()); usage.setAssetKind("STORED_VALUE");
            String title = frozen.source().items().getFirst().title();
            usage.setTitle(title.length() <= 100 ? title : title.substring(0, 100));
            usage.setAmountMinor(c.amountMinor()); usage.setCurrency(c.currency());
            usage.setItemsSnapshot(json.writeValueAsString(c.itemsSnapshot()));
            usage.setServiceStaffName(frozen.source().serviceStaffName()); usage.setSourceOrderNo(source.sourceNo());
            usage.setOperatorNo(ctx.actorNo()); usage.setStatus("COMMITTED"); usage.setOccurredAt(at);
            changed(usages.insert(usage));
            documentNumbers.bind(ai.neargo.shop.numbering.DocumentType.ENTITLEMENT_USAGE, ctx.tenantNo(), usage.getUsageNo());
            statements.redemption(ctx, account, usage, voucher);
            if(brandWallet)brandLedger.consumed(ctx,c.entityNo(),account.getAccountNo(),voucher.getVoucherNo());else issuerLedger.consumed(ctx,c.entityNo(),account.getAccountNo(),voucher.getVoucherNo());
            clearing.record(ctx,usage.getUsageNo(),voucher.getVoucherNo());
            stores.consume(ctx, c.userNo(), c.entityNo(), c.storeNo(), frozen.source().storeName(), at);
            events.publish(new EntitlementUsed(no("UE", List.of(ctx.tenantNo(), operation), json), row.getUsageNo(),
                    c.userNo(), c.entityNo(), c.storeNo(), "STORED_VALUE", c.accountNo(), c.amountMinor(), null,
                    decision.occurredAt().toEpochMilli()));
        } else {
            changed(intents.update(null, Wrappers.<EntUseIntent>lambdaUpdate()
                    .eq(EntUseIntent::getTenantNo, ctx.tenantNo()).eq(EntUseIntent::getId, row.getId())
                    .set(EntUseIntent::getActiveSourceKey, null)));
            row.setActiveSourceKey(null); fences.cancel(ctx, fence, truth);
        }
        save(account, ctx);
        row.setSourceDecisionNo(decision.decisionNo()); row.setStatus(commit ? "COMMITTED" : "RELEASED");
        row.setUpdatedAt(now()); row.setUpdatedBy(ctx.actorNo()); changed(intents.updateById(row)); return view(row);
    }
    private SvVoucher post(EntitlementContext ctx, SvAccount account, Prepared frozen, long principal, long bonus,
                       List<SvHoldAllocation> parts, LocalDateTime at) {
        var c = frozen.command();
        var voucher = new SvVoucher(); audit(voucher, ctx, now());
        voucher.setVoucherNo(no("SV", List.of(ctx.tenantNo(), "CONSUME", c.operationNo()), json));
        voucher.setAccountNo(account.getAccountNo()); voucher.setAccountSeq(account.getAccountSeq());
        voucher.setBizType("CONSUME"); voucher.setBizNo(c.operationNo());
        voucher.setPayloadHash(digest(List.of(c, frozen.source().orderSnapshotHash()), json));
        voucher.setPrincipalDelta(-principal); voucher.setBonusDelta(-bonus);
        voucher.setAfterPrincipalMinor(account.getPrincipalMinor()); voucher.setAfterBonusMinor(account.getBonusMinor());
        voucher.setOccurredAt(at); changed(vouchers.insert(voucher));
        documentNumbers.bind(ai.neargo.shop.numbering.DocumentType.SV_VOUCHER, ctx.tenantNo(), voucher.getVoucherNo());
        for (var part : parts) {
            var entry = new SvEntry(); audit(entry, ctx, now()); entry.setVoucherNo(voucher.getVoucherNo());
            entry.setLotNo(part.getLotNo()); entry.setPrincipalDelta(-part.getPrincipalMinor());
            entry.setBonusDelta(-part.getBonusMinor()); changed(entries.insert(entry));
        }
        int index = 0;
        for (var line : StoredValuePostingPolicy.plan(-principal, -bonus)) {
            var posting = new SvPostingLine(); audit(posting, ctx, now()); posting.setVoucherNo(voucher.getVoucherNo());
            posting.setLineNo(++index); posting.setPool(line.pool()); posting.setBook(line.book());
            posting.setSide(line.side()); posting.setAmountMinor(line.amountMinor()); changed(journal.insert(posting));
        }
            return voucher;
    }
    private SvAccount account(EntitlementContext ctx,UseCommand c,ai.neargo.shop.spi.storedvalue.StoredValueWallets.Proof proof){
        if(proof!=null){var selected=accounts.lockAccount(ctx.tenantNo(),c.accountNo());if(selected==null)conflict();walletGuard.account(ctx,c,proof);return selected;}
        StoredValueAccountScope.requireMigrated(accounts, ctx.tenantNo(), c.userNo(), c.entityNo(), c.currency());
        var selected=issuerAccounts.lookup(ctx.tenantNo(),c.entityNo(),c.storeNo(),c.currency(),c.userNo(),true);
        var account=selected.issuerScoped()?accounts.lockAccount(ctx.tenantNo(),selected.accountNo()):accounts.lockOwner(ctx.tenantNo(),c.userNo(),c.entityNo(),c.currency(),c.storeNo());
        if (account == null || !account.getAccountNo().equals(c.accountNo())) conflict();
        return account;
    }
    private void save(SvAccount account, EntitlementContext ctx) {
        if (account.getPrincipalMinor() < account.getHeldPrincipalMinor()
                || account.getBonusMinor() < account.getHeldBonusMinor()) conflict();
        account.setUpdatedAt(now()); account.setUpdatedBy(ctx.actorNo()); changed(accounts.updateById(account));
    }
    private HoldView view(EntUseIntent row) {
        return new HoldView(row.getOperationNo(), State.valueOf(row.getStatus()), row.getOperationNo(), row.getUsageNo(),
                instant(row.getCommittedAt() == null ? row.getCreatedAt() : row.getCommittedAt()), documentNumbers.find(ai.neargo.shop.numbering.DocumentType.ENTITLEMENT_USAGE, row.getTenantNo(), row.getUsageNo()));
    }
    private static void changed(int result) { if (result != 1) conflict(); }
    private static LocalDateTime now() {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(System.currentTimeMillis()), ZoneOffset.UTC);
    }
}
