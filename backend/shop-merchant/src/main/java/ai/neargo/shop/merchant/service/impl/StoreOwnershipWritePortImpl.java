package ai.neargo.shop.merchant.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.user.StoreOwnershipWritePort;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Business writers share these identity locks with exclusive claim freezes. */
@Component
public class StoreOwnershipWritePortImpl implements StoreOwnershipWritePort {
    private final JdbcTemplate jdbc;
    private final String shareLock;
    public StoreOwnershipWritePortImpl(JdbcTemplate jdbc) {
        this.jdbc=jdbc;
        this.shareLock=jdbc.execute((ConnectionCallback<String>) connection ->
                "H2".equals(connection.getMetaData().getDatabaseProductName()) ? " FOR UPDATE" : " FOR SHARE");
    }

    @Override
    public void lockEntityAndCheck(String tenant, String entity) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Store ownership check requires the business write transaction");
        var rows=jdbc.queryForList("SELECT ownership_change_no FROM mch_entity WHERE tenant_no=? AND entity_no=? AND deleted=0"+shareLock,tenant,entity);
        if (rows.size()!=1 || rows.getFirst().get("ownership_change_no")!=null)
            throw BizException.of(ErrorCode.CONFLICT);
    }

    @Override
    public void lockAndCheck(String tenant, String entity, String store) {
        lockEntityAndCheck(tenant,entity);
        var rows=jdbc.queryForList("SELECT entity_no,ownership_change_no FROM mch_store WHERE tenant_no=? AND store_no=? AND deleted=0"+shareLock,tenant,store);
        if (rows.size()!=1 || !Objects.equals(entity,rows.getFirst().get("entity_no"))
                || rows.getFirst().get("ownership_change_no")!=null)
            throw BizException.of(ErrorCode.CONFLICT);
    }
}
