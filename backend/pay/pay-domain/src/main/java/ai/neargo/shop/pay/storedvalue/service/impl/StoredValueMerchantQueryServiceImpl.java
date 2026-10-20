package ai.neargo.shop.pay.storedvalue.service.impl;

import static ai.neargo.shop.pay.storedvalue.policy.StoredValueFacts.digest;
import static ai.neargo.shop.pay.storedvalue.policy.StoredValueFacts.instant;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.CursorPage;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.mapper.storedvalue.SvAccountMapper;
import ai.neargo.shop.pay.mapper.storedvalue.SvMerchantAccountMapper;
import ai.neargo.shop.pay.mapper.storedvalue.SvStatementMapper;
import ai.neargo.shop.pay.storedvalue.entity.SvAccount;
import ai.neargo.shop.pay.storedvalue.entity.SvStatement;
import ai.neargo.shop.pay.storedvalue.service.StoredValueCursorCodec;
import ai.neargo.shop.pay.storedvalue.service.StoredValueMerchantQueryService;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.storedvalue.StoredValueMerchantViews.Account;
import ai.neargo.shop.spi.storedvalue.StoredValueMerchantViews.Record;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class StoredValueMerchantQueryServiceImpl implements StoredValueMerchantQueryService {
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.beans.factory.annotation.Qualifier("payDocumentNumbers")
    private ai.neargo.shop.numbering.DocumentNumberService documentNumbers;
    @org.springframework.beans.factory.annotation.Autowired
    private ai.neargo.shop.pay.mapper.entitlement.EntUsageMapper usages;
    private final SvAccountMapper accounts;
    private final SvMerchantAccountMapper directory;
    private final SvStatementMapper statements;
    private final StoredValueCursorCodec cursors;
    private final ObjectMapper json;
    public StoredValueMerchantQueryServiceImpl(SvAccountMapper accounts,SvMerchantAccountMapper directory,
            SvStatementMapper statements,StoredValueCursorCodec cursors,ObjectMapper json){
        this.accounts=accounts;this.directory=directory;this.statements=statements;this.cursors=cursors;this.json=json;
    }
    @Override public CursorPage<Account> directory(CatalogScope scope,String store,List<String> users,String keywordHash,String cursor,int size){
        read(scope,store,size);ContractChecks.require(users==null||users.size()<=200);
        String filters=digest(List.of(scope.entityNo(),store,keywordHash==null?"":keywordHash,users==null?List.of():users.stream().sorted().toList()),json);
        var after=cursors.decode(cursor,"SV_CUSTOMERS_RECHARGE",scope.tenantNo(),scope.actorNo(),filters);
        if(users!=null&&users.isEmpty())return new CursorPage<>(List.of(),null);
        var rows=directory.directory(scope.tenantNo(),scope.entityNo(),store,users,
                after==null?null:local(Instant.ofEpochMilli(after.occurredAtMillis())),after==null?null:after.sortKey(),size+1);
        boolean more=rows.size()>size;var shown=rows.subList(0,Math.min(size,rows.size()));String next=null;
        if(more){var last=shown.getLast();next=cursors.encode("SV_CUSTOMERS_RECHARGE",scope.tenantNo(),scope.actorNo(),filters,
                instant(last.getLastRechargeAt()).toEpochMilli(),last.getAccountNo());}
        return new CursorPage<>(shown.stream().map(a->view(scope,a,instant(a.getLastActivityAt()),instant(a.getLastRechargeAt()))).toList(),next);
    }
    @Override public Account account(CatalogScope scope,String store,String ref){
        read(scope,store,1);String no=accountNo(scope,store,ref);
        var a=accounts.selectOne(Wrappers.<SvAccount>lambdaQuery().eq(SvAccount::getTenantNo,scope.tenantNo())
                .eq(SvAccount::getEntityNo,scope.entityNo()).eq(SvAccount::getStoreNo,store).eq(SvAccount::getAccountNo,no));
        if(a==null)throw BizException.of(ErrorCode.NOT_FOUND);
        return view(scope,a,null,null);
    }
    @Override public CursorPage<Record> records(CatalogScope scope,String store,String type,Instant from,Instant to,String ref,String cursor,int size){
        read(scope,store,size);ContractChecks.require(List.of("ALL","RECHARGE","REDEMPTION").contains(type));
        ContractChecks.require(from==null||to==null||from.isBefore(to));
        String account=ref==null?null:accountNo(scope,store,ref);
        String filters=digest(List.of(scope.entityNo(),store,type,from==null?"":from.toString(),to==null?"":to.toString(),account==null?"":account),json);
        var after=cursors.decode(cursor,"SV_RECORDS",scope.tenantNo(),scope.actorNo(),filters);
        var q=Wrappers.<SvStatement>lambdaQuery().eq(SvStatement::getTenantNo,scope.tenantNo())
                .eq(SvStatement::getEntityNo,scope.entityNo()).eq(SvStatement::getStoreNo,store)
                .eq(account!=null,SvStatement::getAccountNo,account).eq(!"ALL".equals(type),SvStatement::getType,type)
                .ge(from!=null,SvStatement::getOccurredAt,from==null?null:local(from))
                .lt(to!=null,SvStatement::getOccurredAt,to==null?null:local(to));
        if(after!=null){var at=local(Instant.ofEpochMilli(after.occurredAtMillis()));q.and(x->x.lt(SvStatement::getOccurredAt,at)
                .or(y->y.eq(SvStatement::getOccurredAt,at).lt(SvStatement::getRecordNo,after.sortKey())));}
        var rows=statements.selectList(q.orderByDesc(SvStatement::getOccurredAt,SvStatement::getRecordNo).last("LIMIT "+(size+1)));
        boolean more=rows.size()>size;var shown=rows.subList(0,Math.min(size,rows.size()));String next=null;
        if(more){var last=shown.getLast();next=cursors.encode("SV_RECORDS",scope.tenantNo(),scope.actorNo(),filters,
                instant(last.getOccurredAt()).toEpochMilli(),last.getRecordNo());}
        return new CursorPage<>(numbered(shown),next);
    }
    @Override public Record record(CatalogScope scope,String store,String no){
        read(scope,store,1);ContractChecks.id(no);
        var q=Wrappers.<SvStatement>lambdaQuery().eq(SvStatement::getTenantNo,scope.tenantNo())
                .eq(SvStatement::getEntityNo,scope.entityNo()).eq(SvStatement::getStoreNo,store);
        var type=no.matches("PR[0-9]{14,}") ? ai.neargo.shop.numbering.DocumentType.SV_RECHARGE
                : no.matches("EU[0-9]{14,}") ? ai.neargo.shop.numbering.DocumentType.ENTITLEMENT_USAGE
                : no.matches("SV[0-9]{14,}") ? ai.neargo.shop.numbering.DocumentType.SV_VOUCHER : null;
        if(type==null) q.eq(SvStatement::getRecordNo,no);
        else {
            var aliasType=type;
            var source=documentNumbers.resolve(scope.tenantNo(),type,no)
                    .filter(x -> "PAY".equals(x.sourceSystem()) && aliasType.name().equals(x.resourceType()))
                    .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
            if(type==ai.neargo.shop.numbering.DocumentType.SV_VOUCHER) q.eq(SvStatement::getRecordNo,source.sourceNo());
            else if(type==ai.neargo.shop.numbering.DocumentType.SV_RECHARGE)
                q.eq(SvStatement::getType,"RECHARGE").eq(SvStatement::getBusinessNo,source.sourceNo());
            else {
                var usage=usages.selectOne(Wrappers.<ai.neargo.shop.pay.entitlement.entity.EntUsage>lambdaQuery()
                        .eq(ai.neargo.shop.pay.entitlement.entity.EntUsage::getTenantNo,scope.tenantNo())
                        .eq(ai.neargo.shop.pay.entitlement.entity.EntUsage::getEntityNo,scope.entityNo())
                        .eq(ai.neargo.shop.pay.entitlement.entity.EntUsage::getStoreNo,store)
                        .eq(ai.neargo.shop.pay.entitlement.entity.EntUsage::getAssetKind,"STORED_VALUE")
                        .eq(ai.neargo.shop.pay.entitlement.entity.EntUsage::getUsageNo,source.sourceNo()));
                if(usage==null) throw BizException.of(ErrorCode.NOT_FOUND);
                q.eq(SvStatement::getType,"REDEMPTION").eq(SvStatement::getBusinessNo,usage.getOperationNo());
            }
        }
        var r=statements.selectOne(q);
        if(r==null)throw BizException.of(ErrorCode.NOT_FOUND);return numbered(List.of(r)).getFirst();
    }
    private Account view(CatalogScope s,SvAccount a,Instant last,Instant lastRecharge){
        String ref=cursors.encode("SV_ACCOUNT_REF",s.tenantNo(),s.actorNo(),digest(List.of(s.entityNo(),a.getStoreNo()),json),
                Math.max(1,instant(a.getCreatedAt()).toEpochMilli()),a.getAccountNo());
        long balance=Math.addExact(a.getPrincipalMinor(),a.getBonusMinor());long held=Math.addExact(a.getHeldPrincipalMinor(),a.getHeldBonusMinor());
        if(held>balance)throw BizException.of(ErrorCode.CONFLICT);
        return new Account(ref,a.getUserNo(),a.getCurrency(),balance,held,"ACTIVE".equals(a.getStatus())?balance-held:0,last,a.customerIdentity(),lastRecharge);
    }
    private String accountNo(CatalogScope s,String store,String ref){
        ContractChecks.require(ref!=null&&!ref.isBlank()&&ref.length()<=512);return cursors.decode(ref,"SV_ACCOUNT_REF",s.tenantNo(),s.actorNo(),digest(List.of(s.entityNo(),store),json)).sortKey();
    }
    /** Only the authorized returned page is enriched; none of these lookups creates a historical alias. */
    private List<Record> numbered(List<SvStatement> rows) {
        if(rows.isEmpty()) return List.of();
        var rechargeSources=new java.util.ArrayList<ai.neargo.shop.numbering.DocumentSource>();
        var voucherSources=new java.util.ArrayList<ai.neargo.shop.numbering.DocumentSource>();
        var operations=rows.stream().filter(r -> "REDEMPTION".equals(r.getType()))
                .map(SvStatement::getBusinessNo).distinct().toList();
        var usageByOperation=new java.util.HashMap<String,String>();
        if(!operations.isEmpty()) usages.selectList(Wrappers.<ai.neargo.shop.pay.entitlement.entity.EntUsage>lambdaQuery()
                .eq(ai.neargo.shop.pay.entitlement.entity.EntUsage::getTenantNo,rows.getFirst().getTenantNo())
                .in(ai.neargo.shop.pay.entitlement.entity.EntUsage::getOperationNo,operations))
                .forEach(u -> usageByOperation.put(u.getOperationNo(),u.getUsageNo()));
        var usageSources=new java.util.ArrayList<ai.neargo.shop.numbering.DocumentSource>();
        for(var row:rows) {
            voucherSources.add(source(row,ai.neargo.shop.numbering.DocumentType.SV_VOUCHER,row.getVoucherNo()));
            if("RECHARGE".equals(row.getType())) rechargeSources.add(source(row,ai.neargo.shop.numbering.DocumentType.SV_RECHARGE,row.getBusinessNo()));
            else if(usageByOperation.containsKey(row.getBusinessNo()))
                usageSources.add(source(row,ai.neargo.shop.numbering.DocumentType.ENTITLEMENT_USAGE,usageByOperation.get(row.getBusinessNo())));
        }
        var recharges=documentNumbers.findAll(ai.neargo.shop.numbering.DocumentType.SV_RECHARGE,rechargeSources);
        var usageNumbers=documentNumbers.findAll(ai.neargo.shop.numbering.DocumentType.ENTITLEMENT_USAGE,usageSources);
        var vouchers=documentNumbers.findAll(ai.neargo.shop.numbering.DocumentType.SV_VOUCHER,voucherSources);
        return rows.stream().map(row -> {
            String documentNo="RECHARGE".equals(row.getType())
                    ? recharges.get(source(row,ai.neargo.shop.numbering.DocumentType.SV_RECHARGE,row.getBusinessNo()))
                    : usageByOperation.containsKey(row.getBusinessNo())
                        ? usageNumbers.get(source(row,ai.neargo.shop.numbering.DocumentType.ENTITLEMENT_USAGE,usageByOperation.get(row.getBusinessNo()))) : null;
            return new Record(row.getRecordNo(),row.getUserNo(),row.getType(),row.getTitle(),row.getStoreNo(),row.getStoreName(),
                    row.getCurrency(),row.getSignedAmountMinor(),row.getBalanceAfterMinor(),instant(row.getOccurredAt()),row.getBusinessNo(),
                    row.getPlanNo(),row.getPlanVersionNo(),row.getPaidAmountMinor(),row.getBonusAmountMinor(),row.customerIdentity(),documentNo,
                    vouchers.get(source(row,ai.neargo.shop.numbering.DocumentType.SV_VOUCHER,row.getVoucherNo())));
        }).toList();
    }
    private static ai.neargo.shop.numbering.DocumentSource source(SvStatement row,ai.neargo.shop.numbering.DocumentType type,String no) {
        return new ai.neargo.shop.numbering.DocumentSource(row.getTenantNo(),"PAY",type.name(),no);
    }
    private static LocalDateTime local(Instant at){return LocalDateTime.ofInstant(at,ZoneOffset.UTC);}
    private static void read(CatalogScope s,String store,int size){
        ContractChecks.id(store);ContractChecks.require(size>=1&&size<=100);if(s.platformActor())throw BizException.of(ErrorCode.FORBIDDEN);
    }
}
