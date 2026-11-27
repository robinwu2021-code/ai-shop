package ai.neargo.shop.pay.storedvalue.service;

import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.audit;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.changed;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.conflict;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.digest;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.instant;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.now;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.utc;
import static ai.neargo.shop.pay.storedvalue.policy.StoredValueFacts.no;

import ai.neargo.shop.numbering.DocumentType;
import ai.neargo.shop.pay.entitlement.dto.UseSnapshots.Prepared;
import ai.neargo.shop.pay.entitlement.entity.EntUsage;
import ai.neargo.shop.pay.entitlement.service.PayRightsDocumentNumbers;
import ai.neargo.shop.pay.mapper.entitlement.EntUsageMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntUseIntentMapper;
import ai.neargo.shop.pay.mapper.storedvalue.SvAccountMapper;
import ai.neargo.shop.pay.mapper.storedvalue.SvEntryMapper;
import ai.neargo.shop.pay.mapper.storedvalue.SvLotMapper;
import ai.neargo.shop.pay.mapper.storedvalue.SvPostingLineMapper;
import ai.neargo.shop.pay.mapper.storedvalue.SvVoucherMapper;
import ai.neargo.shop.pay.storedvalue.clearing.StoredValueClearingService;
import ai.neargo.shop.pay.storedvalue.entity.SvAccount;
import ai.neargo.shop.pay.storedvalue.entity.SvEntry;
import ai.neargo.shop.pay.storedvalue.entity.SvLot;
import ai.neargo.shop.pay.storedvalue.entity.SvPostingLine;
import ai.neargo.shop.pay.storedvalue.entity.SvVoucher;
import ai.neargo.shop.pay.storedvalue.issuer.StoredValueIssuerLedgerBridge;
import ai.neargo.shop.pay.storedvalue.policy.StoredValuePostingPolicy;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.AssetKind;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.OrderBenefitReverse;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.State;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseReceipt;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** The original intent serializes all refunds, including replay after a lost response. */
@Component
@RequiredArgsConstructor
public class StoredValueWholeRefundWriter {
    private final EntUseIntentMapper intents;
    private final EntUsageMapper usages;
    private final SvAccountMapper accounts;
    private final SvLotMapper lots;
    private final SvVoucherMapper vouchers;
    private final SvEntryMapper entries;
    private final SvPostingLineMapper journal;
    private final PayRightsDocumentNumbers documentNumbers;
    private final StoredValueStatementProjector statements;
    private final StoredValueIssuerLedgerBridge issuerLedger;
    private final StoredValueClearingService clearing;
    private final ObjectMapper json;

    @Transactional("memberMarketingTxManager")
    public UseReceipt apply(EntitlementContext ctx, OrderBenefitReverse c, String originalOperation) {
        var intent = intents.lock(ctx.tenantNo(), originalOperation);
        if (intent == null || !"COMMITTED".equals(intent.getStatus()) || !"STORED_VALUE".equals(intent.getAssetKind())
                || c.assetKind() != AssetKind.STORED_VALUE || !c.usageNo().equals(intent.getUsageNo())) conflict();

        String hash = digest(json.writeValueAsString(c));
        var usage = usages.selectOne(Wrappers.<EntUsage>lambdaQuery().eq(EntUsage::getTenantNo, ctx.tenantNo())
                .eq(EntUsage::getUsageNo, c.usageNo()).last("FOR UPDATE"));
        if (usage != null && "REVERSED".equals(usage.getStatus())) {
            var prior = voucher(ctx, "REFUND", c.reverseNo());
            if (prior == null || !hash.equals(prior.getPayloadHash())) conflict();
            return receipt(prior);
        }
        var original = voucher(ctx, "CONSUME", originalOperation);
        var command = json.readValue(intent.getCommandSnapshot(), Prepared.class).command();
        if (usage == null || !"COMMITTED".equals(usage.getStatus()) || !"STORED_VALUE".equals(usage.getAssetKind())
                || !originalOperation.equals(usage.getOperationNo()) || !c.amountMinor().equals(usage.getAmountMinor())
                || original == null || !command.accountNo().equals(original.getAccountNo())) conflict();
        long principal = Math.negateExact(original.getPrincipalDelta());
        long bonus = Math.negateExact(original.getBonusDelta());
        if (principal < 0 || bonus < 0 || Math.addExact(principal, bonus) != c.amountMinor()) conflict();

        issuerLedger.beforeRefund(ctx, original, usage);
        var account = accounts.lockAccount(ctx.tenantNo(), original.getAccountNo());
        if (account == null || !usage.getCurrency().equals(account.getCurrency())) conflict();
        account.setPrincipalMinor(Math.addExact(account.getPrincipalMinor(), principal));
        account.setBonusMinor(Math.addExact(account.getBonusMinor(), bonus));
        account.setAccountSeq(Math.incrementExact(account.getAccountSeq()));
        account.setUpdatedAt(now());
        account.setUpdatedBy(ctx.actorNo());
        changed(accounts.updateById(account));

        var refund = postVoucher(ctx, c, original, account, hash, principal, bonus);
        restoreLots(ctx, original, refund);
        int lineNo = 0;
        for (var line : StoredValuePostingPolicy.plan(principal, bonus)) {
            var posting = new SvPostingLine();
            audit(posting, ctx);
            posting.setVoucherNo(refund.getVoucherNo());
            posting.setLineNo(++lineNo);
            posting.setPool(line.pool());
            posting.setBook(line.book());
            posting.setSide(line.side());
            posting.setAmountMinor(line.amountMinor());
            changed(journal.insert(posting));
        }
        statements.refund(ctx, account, usage, refund, original);
        clearing.reverse(ctx, usage.getUsageNo(), c.decisionRef().decisionNo(), refund.getOccurredAt());
        usage.setStatus("REVERSED");
        usage.setUpdatedAt(now());
        usage.setUpdatedBy(ctx.actorNo());
        changed(usages.updateById(usage));
        return receipt(refund);
    }

    private SvVoucher voucher(EntitlementContext ctx, String type, String bizNo) {
        return vouchers.selectOne(Wrappers.<SvVoucher>lambdaQuery().eq(SvVoucher::getTenantNo, ctx.tenantNo())
                .eq(SvVoucher::getBizType, type).eq(SvVoucher::getBizNo, bizNo).last("FOR UPDATE"));
    }

    private SvVoucher postVoucher(EntitlementContext ctx, OrderBenefitReverse c, SvVoucher original,
            SvAccount account, String hash, long principal, long bonus) {
        var refund = new SvVoucher();
        audit(refund, ctx);
        refund.setVoucherNo(no("SV", List.of(ctx.tenantNo(), "REFUND", c.reverseNo()), json));
        refund.setAccountNo(account.getAccountNo());
        refund.setAccountSeq(account.getAccountSeq());
        refund.setIssuerNo(original.getIssuerNo());
        refund.setBizType("REFUND");
        refund.setBizNo(c.reverseNo());
        refund.setPayloadHash(hash);
        refund.setOriginVoucherNo(original.getVoucherNo());
        refund.setUsageNo(c.usageNo());
        refund.setSourceDecisionNo(c.decisionRef().decisionNo());
        refund.setAfterSaleNo(c.sourceRef().sourceNo());
        refund.setPrincipalDelta(principal);
        refund.setBonusDelta(bonus);
        refund.setAfterPrincipalMinor(account.getPrincipalMinor());
        refund.setAfterBonusMinor(account.getBonusMinor());
        refund.setOccurredAt(utc(c.decisionRef().occurredAt()));
        changed(vouchers.insert(refund));
        documentNumbers.bind(DocumentType.SV_VOUCHER, ctx.tenantNo(), refund.getVoucherNo());
        return refund;
    }

    private void restoreLots(EntitlementContext ctx, SvVoucher original, SvVoucher refund) {
        var debits = entries.selectList(Wrappers.<SvEntry>lambdaQuery().eq(SvEntry::getTenantNo, ctx.tenantNo())
                .eq(SvEntry::getVoucherNo, original.getVoucherNo()).orderByAsc(SvEntry::getLotNo));
        long principal = 0, bonus = 0;
        for (var debit : debits) {
            var lot = lots.selectOne(Wrappers.<SvLot>lambdaQuery().eq(SvLot::getTenantNo, ctx.tenantNo())
                    .eq(SvLot::getAccountNo, original.getAccountNo()).eq(SvLot::getLotNo, debit.getLotNo())
                    .last("FOR UPDATE"));
            long p = Math.negateExact(debit.getPrincipalDelta());
            long b = Math.negateExact(debit.getBonusDelta());
            if (lot == null || p < 0 || b < 0 || (p == 0 && b == 0)) conflict();
            lot.setPrincipalMinor(Math.addExact(lot.getPrincipalMinor(), p));
            lot.setBonusMinor(Math.addExact(lot.getBonusMinor(), b));
            lot.setUpdatedAt(now());
            lot.setUpdatedBy(ctx.actorNo());
            changed(lots.updateById(lot));

            var entry = new SvEntry();
            audit(entry, ctx);
            entry.setVoucherNo(refund.getVoucherNo());
            entry.setLotNo(lot.getLotNo());
            entry.setPrincipalDelta(p);
            entry.setBonusDelta(b);
            changed(entries.insert(entry));
            principal = Math.addExact(principal, p);
            bonus = Math.addExact(bonus, b);
        }
        if (principal != refund.getPrincipalDelta() || bonus != refund.getBonusDelta()) conflict();
    }

    public static UseReceipt receipt(SvVoucher row) {
        return new UseReceipt(row.getBizNo(), State.COMMITTED, row.getUsageNo(), instant(row.getOccurredAt()));
    }
}
