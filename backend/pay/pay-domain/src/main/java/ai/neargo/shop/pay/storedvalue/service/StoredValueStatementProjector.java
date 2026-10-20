package ai.neargo.shop.pay.storedvalue.service;

import static ai.neargo.shop.pay.storedvalue.policy.StoredValueFacts.audit;

import ai.neargo.shop.pay.entitlement.entity.EntUsage;
import ai.neargo.shop.pay.mapper.storedvalue.SvStatementMapper;
import ai.neargo.shop.pay.storedvalue.entity.SvAccount;
import ai.neargo.shop.pay.storedvalue.entity.SvRecharge;
import ai.neargo.shop.pay.storedvalue.entity.SvStatement;
import ai.neargo.shop.pay.storedvalue.entity.SvVoucher;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.storedvalue.StoredValuePlanRecharges.Funding;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** A committed financial voucher and its user-facing read projection share the same transaction. */
@Component
@Transactional(transactionManager="payTxManager",propagation=Propagation.MANDATORY)
public class StoredValueStatementProjector {
    private final SvStatementMapper statements;
    private final ObjectMapper json;
    public StoredValueStatementProjector(SvStatementMapper statements,ObjectMapper json){this.statements=statements;this.json=json;}
    public void recharge(EntitlementContext ctx,SvAccount a,SvRecharge r,SvVoucher v){
        var row=base(ctx,a,v);row.setType("RECHARGE");row.setStoreName(r.getStoreName());row.setTitle("储值到账");
        row.setPaidAmountMinor(r.getPrincipalMinor());row.setBonusAmountMinor(r.getBonusMinor());
        row.setPlanNo(r.getPlanNo());row.setPlanVersionNo(r.getPlanVersionNo());
        if("PLAN".equals(r.getSourceKind()))row.setTitle(json.readValue(r.getPermitSnapshot(),Funding.class).planName());
        statements.insert(row);
    }
    public void redemption(EntitlementContext ctx,SvAccount a,EntUsage u,SvVoucher v){
        var row=base(ctx,a,v);row.setType("REDEMPTION");row.setStoreName(u.getStoreName());row.setTitle(u.getTitle());
        statements.insert(row);
    }
    private static SvStatement base(EntitlementContext ctx,SvAccount a,SvVoucher v){
        var row=new SvStatement();audit(row,ctx,v.getOccurredAt());
        row.setRecordNo(v.getVoucherNo());row.setVoucherNo(v.getVoucherNo());row.setAccountNo(a.getAccountNo());
        row.setAccountSeq(v.getAccountSeq());row.setUserNo(a.getUserNo());row.setEntityNo(a.getEntityNo());
        row.setStoreNo(a.getStoreNo());row.setCurrency(a.getCurrency());row.setBusinessNo(v.getBizNo());
        row.setSignedAmountMinor(Math.addExact(v.getPrincipalDelta(),v.getBonusDelta()));
        row.setBalanceAfterMinor(Math.addExact(v.getAfterPrincipalMinor(),v.getAfterBonusMinor()));row.setOccurredAt(v.getOccurredAt());
        return row;
    }
}
