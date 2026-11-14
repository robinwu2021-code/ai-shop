package ai.neargo.shop.pay.storedvalue.service.impl;

import static ai.neargo.shop.pay.storedvalue.policy.StoredValueFacts.add;
import static ai.neargo.shop.pay.storedvalue.policy.StoredValueFacts.audit;
import static ai.neargo.shop.pay.storedvalue.policy.StoredValueFacts.conflict;
import static ai.neargo.shop.pay.storedvalue.policy.StoredValueFacts.digest;
import static ai.neargo.shop.pay.storedvalue.policy.StoredValueFacts.instant;
import static ai.neargo.shop.pay.storedvalue.policy.StoredValueFacts.no;
import static ai.neargo.shop.pay.storedvalue.policy.StoredValueFacts.requireReceived;


import ai.neargo.shop.pay.entitlement.event.PayEntitlementEventPublisher;
import ai.neargo.shop.pay.mapper.storedvalue.SvAccountMapper;
import ai.neargo.shop.pay.mapper.storedvalue.SvEntryMapper;
import ai.neargo.shop.pay.mapper.storedvalue.SvLotMapper;
import ai.neargo.shop.pay.mapper.storedvalue.SvPostingLineMapper;
import ai.neargo.shop.pay.mapper.storedvalue.SvRechargeMapper;
import ai.neargo.shop.pay.mapper.storedvalue.SvVoucherMapper;
import ai.neargo.shop.pay.storedvalue.entity.SvAccount;
import ai.neargo.shop.pay.storedvalue.policy.StoredValueAccountScope;
import ai.neargo.shop.pay.storedvalue.entity.SvEntry;
import ai.neargo.shop.pay.storedvalue.entity.SvLot;
import ai.neargo.shop.pay.storedvalue.entity.SvPostingLine;
import ai.neargo.shop.pay.storedvalue.entity.SvRecharge;
import ai.neargo.shop.pay.storedvalue.entity.SvVoucher;
import ai.neargo.shop.pay.storedvalue.policy.StoredValuePostingPolicy;
import ai.neargo.shop.pay.storedvalue.service.StoredValuePostingService;
import ai.neargo.shop.pay.storedvalue.service.StoredValueStoreProjector;
import ai.neargo.shop.pay.storedvalue.service.StoredValueStatementProjector;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementEvents.StoredValueCredited;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.CreditRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.RechargeView;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Issuer quota -> account -> receipt lock order. No Core/network calls under money locks. */
@Service
public class StoredValuePostingServiceImpl implements StoredValuePostingService {
    @org.springframework.beans.factory.annotation.Autowired
    private ai.neargo.shop.pay.storedvalue.issuer.StoredValueIssuerLedgerBridge issuerLedger;
    @org.springframework.beans.factory.annotation.Autowired
    private ai.neargo.shop.pay.entitlement.service.PayRightsDocumentNumbers documentNumbers;
    private final ai.neargo.shop.pay.mapper.storedvalue.SvCustomerIdentityMapper identities;
    @org.springframework.beans.factory.annotation.Autowired
    private ai.neargo.shop.pay.storedvalue.issuer.StoredValueIssuerAccountService issuerAccounts;
    private final SvAccountMapper accounts;
    private final SvRechargeMapper recharges;
    private final SvLotMapper lots;
    private final SvVoucherMapper vouchers;
    private final SvEntryMapper entries;
    private final SvPostingLineMapper journal;
    private final StoredValueStoreProjector stores;
    private final PayEntitlementEventPublisher events;
    private final ObjectMapper json;
    private final StoredValueStatementProjector statements;
    public StoredValuePostingServiceImpl(ai.neargo.shop.pay.mapper.storedvalue.SvCustomerIdentityMapper identities,SvAccountMapper accounts, SvRechargeMapper recharges, SvLotMapper lots,
            SvVoucherMapper vouchers, SvEntryMapper entries, SvPostingLineMapper journal,
            StoredValueStoreProjector stores, PayEntitlementEventPublisher events, ObjectMapper json, StoredValueStatementProjector statements) {
        this.identities=identities;this.accounts = accounts; this.recharges = recharges; this.lots = lots; this.vouchers = vouchers;
        this.entries = entries; this.journal = journal; this.stores = stores; this.events = events; this.json = json; this.statements=statements;
    }
    @Override @Transactional("payTxManager")
    public RechargeView creditReceived(EntitlementContext ctx, CreditRecharge c) {
        var original = recharges.find(ctx.tenantNo(), c.rechargeNo());
        requireReceived(original, c, json);
        issuerLedger.beforeOriginal(ctx,original.getEntityNo(),c.rechargeNo());
        var at = LocalDateTime.ofInstant(Instant.ofEpochMilli(System.currentTimeMillis()), ZoneOffset.UTC);
        var claim = new SvAccount(); audit(claim, ctx, at);
        claim.setAccountNo(no("SA", List.of(ctx.tenantNo(), original.customerIdentity(), original.getEntityNo(),
                original.getCurrency(), original.getStoreNo()), json));
        claim.setStoreNo(original.getStoreNo());
        claim.setUserNo(original.getUserNo()==null?identities.boundUser(ctx.tenantNo(),original.customerIdentity()):original.getUserNo());claim.setCustomerNo(original.customerIdentity()); claim.setEntityNo(original.getEntityNo());
        claim.setCurrency(original.getCurrency());
        var selected=issuerAccounts.credit(ctx,c.rechargeNo());
        SvAccount account;
        if(selected.issuerScoped())account=accounts.lockAccount(ctx.tenantNo(),selected.accountNo());
        else {
            StoredValueAccountScope.requireMigrated(accounts, ctx.tenantNo(), original.customerIdentity(), original.getEntityNo(), original.getCurrency());
            if (!"CREDITED".equals(original.getStatus())) accounts.ensure(claim);
            account=accounts.lockOwner(ctx.tenantNo(),original.customerIdentity(),original.getEntityNo(),original.getCurrency(),original.getStoreNo());
        }
        var recharge = recharges.lock(ctx.tenantNo(), c.rechargeNo());
        var permit = requireReceived(recharge, c, json);
        if (account == null || (!selected.issuerScoped() && !recharge.customerIdentity().equals(account.customerIdentity()) && !(recharge.getUserNo()!=null && recharge.getUserNo().equals(account.getUserNo())))
                || !("ISSUER_RELEASE".equals(recharge.getSourceKind())?json.readValue(recharge.getPermitSnapshot(),ai.neargo.shop.spi.storedvalue.StoredValueIssuerRecharges.Funding.class).settlementEntityNo():recharge.getEntityNo()).equals(account.getEntityNo())
                || !recharge.getCurrency().equals(account.getCurrency())) conflict();
        String payload = digest(List.of(c, recharge.getPayloadHash(), recharge.getReceiptDigest(),
                recharge.getCurrency(), recharge.getPrincipalMinor(), recharge.getBonusMinor()), json);
        if ("CREDITED".equals(recharge.getStatus())) {
            var voucher = vouchers.selectOne(Wrappers.<SvVoucher>lambdaQuery()
                    .eq(SvVoucher::getTenantNo, ctx.tenantNo())
                    .eq(SvVoucher::getBizType, "RECHARGE").eq(SvVoucher::getBizNo, c.rechargeNo()).last("FOR UPDATE"));
            var lot = lots.selectOne(Wrappers.<SvLot>lambdaQuery().eq(SvLot::getTenantNo, ctx.tenantNo())
                    .eq(SvLot::getRechargeNo, c.rechargeNo()).last("FOR UPDATE"));
            if (voucher == null || !voucher.getAccountNo().equals(account.getAccountNo())
                    || !payload.equals(voucher.getPayloadHash()) || lot == null
                    || !lot.getAccountNo().equals(account.getAccountNo())) conflict();
            return view(recharge);
        }
        long principal = recharge.getPrincipalMinor(), bonus = recharge.getBonusMinor();
        long total = add(principal, bonus);
        long afterPrincipal = add(account.getPrincipalMinor(), principal);
        long afterBonus = add(account.getBonusMinor(), bonus);
        add(afterPrincipal, afterBonus); // The unified balance is also a signed-long contract.
        long sequence = add(account.getAccountSeq(), 1);
        var lot = new SvLot(); audit(lot, ctx, at);
        lot.setLotNo(no("SL", List.of(ctx.tenantNo(), c.rechargeNo()), json)); lot.setAccountNo(account.getAccountNo());
        lot.setRechargeNo(c.rechargeNo()); lot.setActivityVersionNo(recharge.getActivityVersionNo());
        lot.setPlanVersionNo(recharge.getPlanVersionNo()); lot.setRechargeStoreNo(recharge.getStoreNo());
        lot.setPrincipalMinor(principal); lot.setBonusMinor(bonus); lot.setHeldPrincipalMinor(0L);
        lot.setHeldBonusMinor(0L); lot.setScopeSnapshot(json.writeValueAsString(permit.redemptionScope()));
        lot.setCreditedAt(at);
        if (lots.insert(lot) != 1) conflict();
        var voucher = new SvVoucher(); audit(voucher, ctx, at);
        voucher.setVoucherNo(no("SV", List.of(ctx.tenantNo(), c.rechargeNo()), json));
        voucher.setAccountNo(account.getAccountNo()); voucher.setAccountSeq(sequence); voucher.setBizType("RECHARGE");
        voucher.setBizNo(c.rechargeNo()); voucher.setPayloadHash(payload);
        voucher.setPrincipalDelta(principal); voucher.setBonusDelta(bonus);
        voucher.setAfterPrincipalMinor(afterPrincipal); voucher.setAfterBonusMinor(afterBonus);
        voucher.setOccurredAt(at);
        if (vouchers.insert(voucher) != 1) conflict();
        documentNumbers.bind(ai.neargo.shop.numbering.DocumentType.SV_VOUCHER, ctx.tenantNo(), voucher.getVoucherNo());
        statements.recharge(ctx, account, recharge, voucher);
        var entry = new SvEntry(); audit(entry, ctx, at); entry.setVoucherNo(voucher.getVoucherNo());
        entry.setLotNo(lot.getLotNo()); entry.setPrincipalDelta(principal); entry.setBonusDelta(bonus);
        if (entries.insert(entry) != 1) conflict();
        var lines = StoredValuePostingPolicy.plan(principal, bonus);
        int lineNo = 0;
        for (var line : lines) {
            var posting = new SvPostingLine(); audit(posting, ctx, at); posting.setVoucherNo(voucher.getVoucherNo());
            posting.setLineNo(++lineNo); posting.setPool(line.pool()); posting.setBook(line.book());
            posting.setSide(line.side()); posting.setAmountMinor(line.amountMinor());
            if (journal.insert(posting) != 1) conflict();
        }
        account.setPrincipalMinor(afterPrincipal); account.setBonusMinor(afterBonus); account.setAccountSeq(sequence);
        account.setUpdatedAt(at); account.setUpdatedBy(ctx.actorNo());
        if (accounts.updateById(account) != 1) conflict();
        recharge.setStatus("CREDITED"); recharge.setCreditedAt(at); recharge.setUpdatedAt(at);
        recharge.setUpdatedBy(ctx.actorNo());
        if (recharges.updateById(recharge) != 1) conflict();
        stores.credit(ctx, recharge, at);
        issuerLedger.credited(ctx,recharge.getEntityNo(),recharge.getRechargeNo(),account.getAccountNo(),lot.getLotNo(),voucher.getVoucherNo());
        events.publish(new StoredValueCredited(no("SC", List.of(ctx.tenantNo(), c.rechargeNo()), json), c.rechargeNo(),
                account.getAccountNo(), recharge.getUserNo(), recharge.getEntityNo(), recharge.getStoreNo(),
                principal, total, recharge.getCurrency(), instant(at).toEpochMilli(),recharge.customerIdentity()));
        // Distinct event preserves the canonical hashes of the existing v1 credit contract.
        if ("MAIN".equals(ctx.tenantNo()) && recharge.getStoreNo()!=null && !recharge.getStoreNo().isBlank())
            events.publish(new ai.neargo.shop.spi.entitlement.EntitlementEvents.StoredValueReceiptConfirmed(
                no("SP", List.of("STORED_VALUE_RECEIPT_CONFIRMED", ctx.tenantNo(), c.rechargeNo()), json), ctx.tenantNo(), recharge.getRechargeNo(),
                account.getAccountNo(), recharge.getEntityNo(), recharge.getStoreNo(), principal, bonus,
                Math.addExact(afterPrincipal, afterBonus), recharge.getCurrency(), instant(at).toEpochMilli()));
        return view(recharge);
    }
    private RechargeView view(SvRecharge row) {
        var r=ai.neargo.shop.pay.storedvalue.policy.StoredValueFacts.view(row);
        return new RechargeView(r.rechargeNo(),r.status(),r.userNo(),r.entityNo(),r.storeNo(),r.currency(),
                r.paidAmountMinor(),r.creditedTotalMinor(),r.receivedAt(),r.creditedAt(),r.revision(),r.customerNo(),
                documentNumbers.find(ai.neargo.shop.numbering.DocumentType.SV_RECHARGE,row.getTenantNo(),row.getRechargeNo()));
    }

}
