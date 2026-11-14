package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.DiningBatchSummaryVO;
import ai.neargo.shop.food.dto.DiningBatchSummaryVO.OriginalBindingVO;
import ai.neargo.shop.food.dto.ListDiningBatchesQuery;
import ai.neargo.shop.food.mapper.DiningBatchQueryMapper;
import ai.neargo.shop.food.service.DiningBatchQueryService;
import ai.neargo.shop.spi.user.StoreCardQueryPort;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** A separate repeatable-read transaction keeps count, source page and both bounded detail sets consistent. */
public class DiningBatchQueryServiceImpl implements DiningBatchQueryService {
    private final DiningBatchQueryMapper batches;
    private final StoreCardQueryPort stores;
    private final TransactionTemplate read;

    public DiningBatchQueryServiceImpl(DiningBatchQueryMapper batches,StoreCardQueryPort stores,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this.batches=batches; this.stores=stores; read=new TransactionTemplate(manager);
        read.setReadOnly(true); read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    @Override public PageData<DiningBatchSummaryVO> list(String entity,String store,String actor,
            ListDiningBatchesQuery input) {
        key(entity); key(store); key(actor);
        var card=stores.cards(List.of(store)).get(store);
        if(card==null || !entity.equals(card.entityNo())) throw BizException.of(ErrorCode.FORBIDDEN);
        var query=input==null?new ListDiningBatchesQuery(null,null,null,null,null,null,null):input;
        int page=query.page()==null?1:query.page(),size=query.size()==null?20:query.size();
        if(page<1 || page>10000 || size<1 || size>100) bad();
        if(query.checkNo()!=null) key(query.checkNo());
        if(query.originalCheckNo()!=null) key(query.originalCheckNo());
        if(query.status()!=null && !Set.of("PREPARED","PLACING","UNKNOWN","BOUND","ACCEPTED","CANCEL_PENDING",
                "CANCELLED","FAILED").contains(query.status())) bad();
        if(query.orderSource()!=null && !Set.of("POS","WAITER","KIOSK","QR","H5","APP")
                .contains(query.orderSource())) bad();
        if(query.businessDate()!=null && (query.businessDate().getYear()<1000 || query.businessDate().getYear()>9999))
            bad();
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Batch listing requires its own repeatable-read snapshot");
        return read.execute(tx -> {
            long total=batches.count(entity,store,query);
            var rows=batches.page(entity,store,query,(long)(page-1)*size,size);
            if(rows.size()>size || total<rows.size()) throw BizException.of(ErrorCode.CONFLICT);
            if(rows.isEmpty()) return PageData.of(List.of(),total,page,size);
            var numbers=rows.stream().map(DiningBatchQueryMapper.SourceRow::batchNo).toList();
            var memberships=batches.currentChecks(entity,store,numbers);
            var bindings=batches.bindings(entity,store,numbers);
            if(memberships.size()>10000 || bindings.size()>10000) throw BizException.of(ErrorCode.CONFLICT);
            var current=memberships.stream().collect(Collectors.groupingBy(DiningBatchQueryMapper.MembershipRow::batchNo,
                    Collectors.mapping(DiningBatchQueryMapper.MembershipRow::checkNo,Collectors.toList())));
            var original=bindings.stream().collect(Collectors.groupingBy(DiningBatchQueryMapper.BindingRow::batchNo,
                    Collectors.mapping(k -> new OriginalBindingVO(k.bindingNo(),k.paymentRootNo(),k.storeOrderNo(),
                            k.bindingStatus()),Collectors.toList())));
            var records=rows.stream().map(row -> new DiningBatchSummaryVO(row.batchNo(),row.storeNo(),row.originalCheckNo(),
                    current.getOrDefault(row.batchNo(),List.of()).stream().distinct().sorted().toList(),
                    row.membershipAuthoritative(),row.status(),row.orderSource(),row.businessDate(),row.fulfillment(),
                    row.payMode(),row.quoteNo(),row.quoteHash(),instant(row.createdAt()),instant(row.acceptedAt()),
                    instant(row.scheduledAt()),row.version(),original.getOrDefault(row.batchNo(),List.of()))).toList();
            return PageData.of(records,total,page,size);
        });
    }
    private static Instant instant(LocalDateTime value) { return value==null?null:value.toInstant(ZoneOffset.UTC); }
    private static void key(String value) {
        if(value==null || value.isBlank() || value.length()>32 || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) bad();
    }
    private static void bad() { throw BizException.of(ErrorCode.BAD_REQUEST); }
}
