package ai.neargo.shop.merchant.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper;
import ai.neargo.shop.merchant.service.StoreOperationalReviewService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import ai.neargo.shop.spi.user.StoreOwnershipWritePort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class StoreOperationalReviewServiceImpl implements StoreOperationalReviewService {
    private final JdbcTemplate db;
    private final MchStoreMapper stores;
    private final StoreOwnershipWritePort ownership;
    private final AuditLogPort audit;
    public StoreOperationalReviewServiceImpl(JdbcTemplate db, MchStoreMapper stores,
            StoreOwnershipWritePort ownership, AuditLogPort audit) {
        this.db=db;this.stores=stores;this.ownership=ownership;this.audit=audit;
    }
    private MchStore scoped(String no) {
        var store=stores.selectOne(Wrappers.<MchStore>lambdaQuery().select(MchStore::getStoreNo,MchStore::getEntityNo).eq(MchStore::getTenantNo,"MAIN").eq(MchStore::getStoreNo,no));
        if(store==null) throw BizException.of(ErrorCode.NOT_FOUND);
        return store;
    }
    private ReviewVO view(String tenant,String no) {
        return db.query("SELECT store_no,status,operational_review,operational_source,operational_order_no,operational_decided_at,operational_submitted_at FROM mch_store WHERE tenant_no=? AND store_no=? AND deleted=0",
            (r,i)->new ReviewVO(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getObject(6,Long.class),r.getObject(7,Long.class)),tenant,no).getFirst();
    }
    @Override public ReviewVO read(String no) {scoped(no);return view("MAIN",no);}
    @Override @Transactional public ReviewVO submit(String no) {
        var store=scoped(no);ownership.lockAndCheck("MAIN",store.getEntityNo(),no);
        int changed=db.update("UPDATE mch_store SET status='PENDING_OPEN',operational_review='PENDING',operational_source=NULL,operational_order_no=NULL,operational_decided_at=NULL,operational_submitted_at=?,version=version+1,updated_at=NOW() WHERE tenant_no='MAIN' AND store_no=? AND deleted=0 AND ((status='ACTIVE' AND operational_review IS NULL) OR (status='PENDING_OPEN' AND operational_review='REJECTED'))",System.currentTimeMillis(),no);
        var result=view("MAIN",no);
        if(changed==0 && !("PENDING_OPEN".equals(result.status()) && "PENDING".equals(result.review())))
            throw BizException.of(ErrorCode.CONFLICT);
        if(changed==1) audit.record("STORE_OPERATIONAL_SUBMITTED",no,"提交门店经营开通审核",true);
        return result;
    }
    @Override @Transactional public ReviewVO decide(String no,boolean approved,String reason) {
        var store=scoped(no);ownership.lockAndCheck("MAIN",store.getEntityNo(),no);
        var state=approved?"APPROVED":"REJECTED";
        int changed=db.update("UPDATE mch_store SET status=?,operational_review=?,operational_source='MANUAL',operational_decided_at=?,version=version+1,updated_at=NOW() WHERE tenant_no='MAIN' AND store_no=? AND status='PENDING_OPEN' AND operational_review='PENDING' AND deleted=0",
            approved?"ACTIVE":"PENDING_OPEN",state,System.currentTimeMillis(),no);
        var result=view("MAIN",no);
        if(changed==0 && !(state.equals(result.review()) && "MANUAL".equals(result.source()))) throw BizException.of(ErrorCode.CONFLICT);
        if(changed==1) audit.record("STORE_OPERATIONAL_"+state,no,reason,true);
        return result;
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void activatePaid(String tenant,String entity,String no,String model,String order,long purchasedAt,long at) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Paid activation requires fulfillment transaction");
        // The frozen order already established authority. This exact conditional write never lifts
        // a manual rejection/suspension and cannot open siblings or a transferred store.
        int changed=db.update("UPDATE mch_store SET status='ACTIVE',operational_review='APPROVED',operational_source=?,operational_order_no=?,operational_decided_at=?,version=version+1,updated_at=NOW() WHERE tenant_no=? AND entity_no=? AND store_no=? AND status='PENDING_OPEN' AND operational_review='PENDING' AND operational_submitted_at<=? AND deleted=0",
            model,order,at,tenant,entity,no,purchasedAt);
        if(changed==1) audit.record("STORE_OPERATIONAL_APPROVED",no,"套餐履约开通："+model+":"+order,true);
    }
}
