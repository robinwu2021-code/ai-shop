package ai.neargo.shop.pay.storedvalue.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.CursorPage;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.numbering.DocumentType;
import ai.neargo.shop.pay.entitlement.entity.EntUsage;
import ai.neargo.shop.pay.entitlement.service.PayRightsDocumentNumbers;
import ai.neargo.shop.pay.mapper.entitlement.EntUsageMapper;
import ai.neargo.shop.pay.mapper.storedvalue.SvAccountMapper;
import ai.neargo.shop.pay.mapper.storedvalue.SvConsumerStatementMapper;
import ai.neargo.shop.pay.mapper.storedvalue.SvStatementMapper;
import ai.neargo.shop.pay.mapper.storedvalue.SvUserStoreIndexMapper;
import ai.neargo.shop.pay.storedvalue.entity.SvAccount;
import ai.neargo.shop.pay.storedvalue.entity.SvStatement;
import ai.neargo.shop.pay.storedvalue.service.StoredValueConsumerQueryService;
import ai.neargo.shop.pay.storedvalue.service.StoredValueCursorCodec;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.storedvalue.StoredValueConsumerViews.NotificationFact;
import ai.neargo.shop.spi.storedvalue.StoredValueConsumerViews.Record;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.StoreActivityType;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.StoreItem;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.digest;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.instant;

/** Reads only durable money facts, with member scope applied before alias lookup and pagination. */
@Service
public class StoredValueConsumerQueryServiceImpl implements StoredValueConsumerQueryService {
    private final SvUserStoreIndexMapper stores;
    private final SvConsumerStatementMapper statements;
    private final SvStatementMapper allStatements;
    private final SvAccountMapper accounts;
    private final EntUsageMapper usages;
    private final PayRightsDocumentNumbers numbers;
    private final StoredValueCursorCodec cursors;
    private final ObjectMapper json;
    public StoredValueConsumerQueryServiceImpl(SvUserStoreIndexMapper stores, SvConsumerStatementMapper statements,
            SvStatementMapper allStatements, SvAccountMapper accounts, EntUsageMapper usages,
            PayRightsDocumentNumbers numbers, StoredValueCursorCodec cursors, ObjectMapper json) {
        this.stores=stores; this.statements=statements; this.allStatements=allStatements; this.accounts=accounts;
        this.usages=usages; this.numbers=numbers; this.cursors=cursors; this.json=json;
    }
    @Override public StoreItem store(EntitlementContext ctx, String storeNo) {
        ContractChecks.id(storeNo);
        var row=stores.consumerStore(ctx.tenantNo(),ctx.actorNo(),storeNo);
        if(row==null) throw BizException.of(ErrorCode.NOT_FOUND);
        return new StoreItem(row.getStoreNo(),row.getEntityNo(),row.getStoreName(),row.getStoreStatus(),
                StoreActivityType.valueOf(row.getLastActivityType()),instant(row.getLastActivityAt()));
    }
    @Override public CursorPage<Record> records(EntitlementContext ctx, String storeNo, String type, Instant from,
            Instant to, String focus, String cursor, int size) {
        store(ctx,storeNo);
        ContractChecks.require(type!=null && List.of("ALL","RECHARGE","REDEMPTION").contains(type));
        ContractChecks.require(size>=1 && size<=50 && (focus==null || cursor==null));
        ContractChecks.require(from==null || to==null || from.isBefore(to));
        String filters=digest(json.writeValueAsString(List.of(storeNo,type,from==null?"":from.toString(),
                to==null?"":to.toString())));
        var after=cursors.decode(cursor,"SV_CONSUMER_RECORDS",ctx.tenantNo(),ctx.actorNo(),filters);
        LocalDateTime at=after==null?null:local(Instant.ofEpochMilli(after.occurredAtMillis()));
        String key=after==null?null:after.sortKey();
        if(focus!=null) {
            var row=focus(ctx,storeNo,focus);
            if(!"ALL".equals(type) && !type.equals(row.getType())
                    || from!=null && instant(row.getOccurredAt()).isBefore(from)
                    || to!=null && !instant(row.getOccurredAt()).isBefore(to)) throw BizException.of(ErrorCode.NOT_FOUND);
            at=row.getOccurredAt(); key=row.getRecordNo();
        }
        var rows=statements.page(ctx.tenantNo(),ctx.actorNo(),storeNo,type,local(from),local(to),at,key,
                focus!=null,size+1);
        boolean more=rows.size()>size;
        var shown=rows.subList(0,Math.min(size,rows.size()));
        String next=null;
        if(more) {
            var last=shown.getLast();
            next=cursors.encode("SV_CONSUMER_RECORDS",ctx.tenantNo(),ctx.actorNo(),filters,
                    instant(last.getOccurredAt()).toEpochMilli(),last.getRecordNo());
        }
        return new CursorPage<>(numbered(ctx,shown),next);
    }
    private SvStatement focus(EntitlementContext ctx,String store,String documentNo) {
        if(!documentNo.matches("(PR|EU)[0-9]{14,}")) throw BizException.of(ErrorCode.NOT_FOUND);
        boolean recharge=documentNo.startsWith("PR");
        String no=numbers.resolve(recharge?DocumentType.SV_RECHARGE:DocumentType.ENTITLEMENT_USAGE,
                ctx.tenantNo(),documentNo);
        if(documentNo.equals(no)) throw BizException.of(ErrorCode.NOT_FOUND);
        if(!recharge) {
            var usage=usage(ctx,no);
            if(!store.equals(usage.getStoreNo())) throw BizException.of(ErrorCode.NOT_FOUND);
            no=usage.getOperationNo();
        }
        var row=statements.focus(ctx.tenantNo(),ctx.actorNo(),store,recharge?"RECHARGE":"REDEMPTION",no);
        if(row==null) throw BizException.of(ErrorCode.NOT_FOUND);
        return row;
    }
    private List<Record> numbered(EntitlementContext ctx,List<SvStatement> rows) {
        if(rows.isEmpty()) return List.of();
        var operations=rows.stream().filter(r->"REDEMPTION".equals(r.getType()))
                .map(SvStatement::getBusinessNo).distinct().toList();
        var usageByOperation=new HashMap<String,String>();
        if(!operations.isEmpty()) usages.selectList(Wrappers.<EntUsage>lambdaQuery()
                .eq(EntUsage::getTenantNo,ctx.tenantNo()).eq(EntUsage::getAssetKind,"STORED_VALUE")
                .eq(EntUsage::getStatus,"COMMITTED").in(EntUsage::getOperationNo,operations))
                .forEach(u->usageByOperation.put(u.getOperationNo(),u.getUsageNo()));
        var rechargeNumbers=numbers.findAll(DocumentType.SV_RECHARGE,ctx.tenantNo(),rows.stream()
                .filter(r->"RECHARGE".equals(r.getType())).map(SvStatement::getBusinessNo).toList());
        var usageNumbers=numbers.findAll(DocumentType.ENTITLEMENT_USAGE,ctx.tenantNo(),usageByOperation.values());
        return rows.stream().map(r->new Record(r.getType(),r.getTitle(),r.getCurrency(),r.getSignedAmountMinor(),
                instant(r.getOccurredAt()),"RECHARGE".equals(r.getType())?rechargeNumbers.get(r.getBusinessNo())
                :usageNumbers.get(usageByOperation.get(r.getBusinessNo())))).toList();
    }
    @Override public NotificationFact notificationFact(EntitlementContext ctx,String usageNo) {
        ContractChecks.id(usageNo);
        var u=usage(ctx,usageNo);
        var s=allStatements.selectOne(Wrappers.<SvStatement>lambdaQuery().eq(SvStatement::getTenantNo,ctx.tenantNo())
                .eq(SvStatement::getStoreNo,u.getStoreNo()).eq(SvStatement::getEntityNo,u.getEntityNo())
                .eq(SvStatement::getType,"REDEMPTION")
                .eq(SvStatement::getBusinessNo,u.getOperationNo()));
        if(s==null || !s.getCurrency().equals(u.getCurrency()) || s.getSignedAmountMinor()>=0
                || Math.negateExact(s.getSignedAmountMinor())!=u.getAmountMinor())
            throw BizException.of(ErrorCode.NOT_FOUND);
        var a=accounts.selectOne(Wrappers.<SvAccount>lambdaQuery().eq(SvAccount::getTenantNo,ctx.tenantNo())
                .eq(SvAccount::getAccountNo,s.getAccountNo()).eq(SvAccount::getStoreNo,s.getStoreNo())
                .eq(SvAccount::getEntityNo,s.getEntityNo()));
        String user=a==null?null:a.getUserNo();
        return new NotificationFact(u.getUsageNo(),user,s.getStoreNo(),s.getStoreName(),s.getTitle(),s.getCurrency(),
                Math.negateExact(s.getSignedAmountMinor()),instant(s.getOccurredAt()),
                numbers.find(DocumentType.ENTITLEMENT_USAGE,ctx.tenantNo(),u.getUsageNo()));
    }
    private EntUsage usage(EntitlementContext ctx,String usageNo) {
        var u=usages.selectOne(Wrappers.<EntUsage>lambdaQuery().eq(EntUsage::getTenantNo,ctx.tenantNo())
                .eq(EntUsage::getUsageNo,usageNo).eq(EntUsage::getAssetKind,"STORED_VALUE")
                .eq(EntUsage::getStatus,"COMMITTED"));
        if(u==null) throw BizException.of(ErrorCode.NOT_FOUND);
        return u;
    }
    private static LocalDateTime local(Instant at) { return at==null?null:LocalDateTime.ofInstant(at,ZoneOffset.UTC); }
}
