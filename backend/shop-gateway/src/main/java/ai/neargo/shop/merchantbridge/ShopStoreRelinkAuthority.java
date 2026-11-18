package ai.neargo.shop.merchantbridge;

import ai.neargo.commerce.api.subscription.StoreRelinkFacade;
import ai.neargo.commerce.core.subscription.StoreRelinkService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class ShopStoreRelinkAuthority implements StoreRelinkService.Authority {
    private final JdbcTemplate jdbc;
    public ShopStoreRelinkAuthority(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    @Override public void verify(StoreRelinkFacade.Command c) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Claim transaction required");
        var rows=jdbc.queryForList("SELECT * FROM mch_store_entity_claim WHERE tenant_no=? AND claim_no=? AND state='PAY_PREPARED' FOR UPDATE",c.store().tenantNo(),c.changeNo());
        if(rows.size()!=1) throw BizException.of(ErrorCode.FORBIDDEN);
        var row=rows.getFirst();
        if(!Objects.equals(c.store().storeNo(),row.get("store_no")) || !Objects.equals(c.expectedEntityNo(),row.get("source_entity_no"))
                || !Objects.equals(c.targetEntityNo(),row.get("target_entity_no"))) throw BizException.of(ErrorCode.FORBIDDEN);
        var store=jdbc.queryForMap("SELECT entity_no,ownership_change_no FROM mch_store WHERE tenant_no=? AND store_no=? AND deleted=0 FOR UPDATE",c.store().tenantNo(),c.store().storeNo());
        if(!Objects.equals(c.expectedEntityNo(),store.get("entity_no")) || !Objects.equals(c.changeNo(),store.get("ownership_change_no"))) throw BizException.of(ErrorCode.CONFLICT);
    }
}
