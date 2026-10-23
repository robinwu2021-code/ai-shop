package ai.neargo.shop.trade.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.trade.IndustryOrderPort.Identity;
import ai.neargo.shop.trade.entity.OrdIndustryOrderSource;
import ai.neargo.shop.trade.mapper.TradeMappers.IndustryOrderSourceMapper;
import ai.neargo.shop.trade.order.entity.OrdCreateIntent;
import ai.neargo.shop.trade.order.mapper.CreateIntentMapper;
import ai.neargo.shop.trade.service.IndustryCheckoutService;
import ai.neargo.shop.trade.service.IndustryOrderService;
import ai.neargo.shop.trade.service.IndustryTransactionRecoveryService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Original persisted buyer consent supplies read coordinates; this is not a new consumer action or login. */
@Service
public class IndustryTransactionRecoveryServiceImpl implements IndustryTransactionRecoveryService {
    private static final long RETRY_MILLIS = 120_000L;
    private final IndustryOrderSourceMapper sources;
    private final CreateIntentMapper intents;
    private final IndustryOrderService orders;
    private final IndustryCheckoutService checkout;
    private final TransactionTemplate transactions;

    public IndustryTransactionRecoveryServiceImpl(IndustryOrderSourceMapper sources, CreateIntentMapper intents,
            IndustryOrderService orders, IndustryCheckoutService checkout,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this.sources=sources; this.intents=intents; this.orders=orders; this.checkout=checkout;
        this.transactions=new TransactionTemplate(manager);
    }

    @Override
    public Batch recoverDue(long now, int limit) {
        outside();
        int bounded=Math.max(0,Math.min(limit,200));
        if(bounded==0) return new Batch(0,0,0,0);
        var candidates=DataScopeContext.executeWithoutScope(() -> sources.selectList(
                Wrappers.<OrdIndustryOrderSource>lambdaQuery()
                        .and(q -> q.isNull(OrdIndustryOrderSource::getNextRecoveryAt).or()
                                .le(OrdIndustryOrderSource::getNextRecoveryAt,now))
                        .and(q -> q.eq(OrdIndustryOrderSource::getStatus,"PREPARED")
                                .or(b -> b.eq(OrdIndustryOrderSource::getStatus,"BOUND")
                                        .in(OrdIndustryOrderSource::getPaymentState,"OPENING","OPEN","DISPATCHING","READY","UNKNOWN","UNPAID","SUCCESS")
                                        .apply("EXISTS (SELECT 1 FROM ord_order o WHERE o.order_no=ord_industry_order_source.order_no"
                                                + " AND o.deleted=0 AND o.status IN ('WAIT_PAY','PAID'))")
                                        .apply("EXISTS (SELECT 1 FROM ord_sub_order s WHERE s.sub_order_no=ord_industry_order_source.sub_order_no"
                                                + " AND s.deleted=0 AND s.status NOT IN ('REFUNDED','CANCELLED'))")))
                        .orderByAsc(OrdIndustryOrderSource::getNextRecoveryAt,OrdIndustryOrderSource::getOrderNo)
                        .last("LIMIT "+bounded)));
        int claimed=0,complete=0,pending=0,failed=0;
        for(var candidate:candidates.stream().limit(bounded).toList()) {
            long lease=Math.addExact(now,RETRY_MILLIS);
            if(!claim(candidate,now,lease)) continue;
            claimed++;
            String code;
            long next=lease;
            try {
                outside();
                Identity identity=identity(candidate);
                boolean finished;
                if("PREPARED".equals(candidate.getStatus())) {
                    OrdCreateIntent intent=DataScopeContext.executeWithoutScope(() -> intents.selectOne(
                            Wrappers.<OrdCreateIntent>lambdaQuery().eq(OrdCreateIntent::getPayNo,candidate.getOrderNo())
                                    .eq(OrdCreateIntent::getTenantNo,identity.tenantNo())
                                    .eq(OrdCreateIntent::getUserNo,identity.buyerNo())));
                    if(intent==null || intent.getIdemKey()==null || intent.getIdemKey().isBlank()
                            || !Objects.equals(intent.getRequestHash(),candidate.getRequestHash()))
                        throw new IllegalStateException("INDUSTRY_RECOVERY_ORIGINAL_INTENT_REQUIRED");
                    // Query may only finish the original expired/cancelled PREPARED recovery protocol.
                    var result=orders.query(identity,intent.getIdemKey());
                    finished=result!=null && "RELEASED".equals(result.status());
                    code=finished?"PREPARED_RELEASED":"PREPARED_PENDING";
                } else {
                    if(candidate.getPaymentOutTradeNo()==null || candidate.getPaymentOutTradeNo().isBlank()) {
                        // Never create the missing reference or initiate a never-dispatched OPENING record.
                        code="PAYMENT_REFERENCE_REQUIRED"; finished=false;
                    } else {
                        var result=checkout.queryPayment(identity,candidate.getOrderNo());
                        finished=result!=null && result.readyToAccept();
                        code=finished?"PAYMENT_PROJECTED":"PAYMENT_PENDING";
                    }
                }
                if(finished) { next=Long.MAX_VALUE; complete++; } else pending++;
            } catch(RuntimeException failure) {
                code="RECOVERY_QUERY_FAILED";
                failed++;
            }
            finish(candidate,lease,next,code);
        }
        return new Batch(claimed,complete,pending,failed);
    }

    private boolean claim(OrdIndustryOrderSource row,long now,long lease) {
        return Boolean.TRUE.equals(transactions.execute(tx -> DataScopeContext.executeWithoutScope(() ->
                sources.update(null,Wrappers.<OrdIndustryOrderSource>lambdaUpdate()
                        .eq(OrdIndustryOrderSource::getId,row.getId()).eq(OrdIndustryOrderSource::getVersion,row.getVersion())
                        .and(q -> q.isNull(OrdIndustryOrderSource::getNextRecoveryAt).or()
                                .le(OrdIndustryOrderSource::getNextRecoveryAt,now))
                        .set(OrdIndustryOrderSource::getNextRecoveryAt,lease)
                        .set(OrdIndustryOrderSource::getLastRecoveryCode,"RECOVERY_CLAIMED")
                        .setSql("version=version+1"))==1)));
    }
    private void finish(OrdIndustryOrderSource row,long lease,long next,String code) {
        transactions.executeWithoutResult(tx -> DataScopeContext.executeWithoutScope(() ->
                sources.update(null,Wrappers.<OrdIndustryOrderSource>lambdaUpdate()
                        .eq(OrdIndustryOrderSource::getId,row.getId())
                        .eq(OrdIndustryOrderSource::getNextRecoveryAt,lease)
                        .set(OrdIndustryOrderSource::getNextRecoveryAt,next)
                        .set(OrdIndustryOrderSource::getLastRecoveryCode,code).setSql("version=version+1"))));
    }
    private static Identity identity(OrdIndustryOrderSource row) {
        if(!"MAIN".equals(row.getTenantNo()) || row.getEntityNo()==null || row.getStoreNo()==null
                || row.getBuyerNo()==null || !Objects.equals(row.getBuyerNo(),row.getActorNo()))
            throw new IllegalStateException("INDUSTRY_RECOVERY_ORIGINAL_CONSENT_REQUIRED");
        return new Identity(row.getTenantNo(),row.getEntityNo(),row.getStoreNo(),row.getBuyerNo(),row.getActorNo());
    }
    private static void outside() {
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("INDUSTRY_RECOVERY_OUTSIDE_TRANSACTION_REQUIRED");
    }
}
