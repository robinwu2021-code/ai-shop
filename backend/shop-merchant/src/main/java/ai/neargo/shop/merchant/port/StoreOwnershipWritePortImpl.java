package ai.neargo.shop.merchant.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.config.ModuleDataSources;
import ai.neargo.shop.common.config.PlatformJdbcConfiguration;
import ai.neargo.shop.spi.user.StoreOwnershipWritePort;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Business writers share these identity locks with exclusive claim freezes. */
@Component
public class StoreOwnershipWritePortImpl implements StoreOwnershipWritePort {
    private final JdbcTemplate jdbc;
    private final JdbcTemplate platformJdbc;
    private final TransactionTemplate platformLocks;
    private final String shareLock;

    public StoreOwnershipWritePortImpl(JdbcTemplate jdbc) {
        this(jdbc, jdbc, new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    @Autowired
    public StoreOwnershipWritePortImpl(JdbcTemplate jdbc,
            @Qualifier(PlatformJdbcConfiguration.PLATFORM_JDBC_TEMPLATE) JdbcTemplate platformJdbc,
            @Qualifier(ModuleDataSources.PLATFORM_TX_MANAGER) PlatformTransactionManager platformManager) {
        this.jdbc = jdbc;
        this.platformJdbc = platformJdbc;
        this.platformLocks = new TransactionTemplate(platformManager);
        this.shareLock = platformJdbc.execute((ConnectionCallback<String>) connection ->
                "H2".equals(connection.getMetaData().getDatabaseProductName()) ? " FOR UPDATE" : " FOR SHARE");
    }

    @Override
    public <T> T withStoreLock(String tenant, String entity, String store, Supplier<T> write) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("The ownership lock must enclose the full trade transaction");
        }
        return platformLocks.execute(status -> {
            lockStore(platformJdbc, tenant, entity, store);
            return write.get();
        });
    }

    @Override public void lockEntityAndCheck(String tenant,String entity){lockEntity(jdbc,tenant,entity,shareLock);}
    @Override public void lockEntityExclusiveAndCheck(String tenant,String entity){
        lockEntity(jdbc,tenant,entity," FOR UPDATE");
    }
    private void lockEntity(JdbcTemplate source, String tenant,String entity,String lock) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Store ownership check requires the business write transaction");
        var rows=source.queryForList("SELECT ownership_change_no FROM mch_entity WHERE tenant_no=? AND entity_no=? AND deleted=0"+lock,tenant,entity);
        if(rows.size()!=1||rows.getFirst().get("ownership_change_no")!=null)throw BizException.of(ErrorCode.CONFLICT);
    }

    @Override
    public void lockAndCheck(String tenant, String entity, String store) {
        lockStore(jdbc, tenant, entity, store);
    }

    private void lockStore(JdbcTemplate source, String tenant, String entity, String store) {
        lockEntity(source, tenant, entity, shareLock);
        var rows=source.queryForList("SELECT entity_no,ownership_change_no FROM mch_store WHERE tenant_no=? AND store_no=? AND deleted=0"+shareLock,tenant,store);
        if (rows.size()!=1 || !Objects.equals(entity,rows.getFirst().get("entity_no"))
                || rows.getFirst().get("ownership_change_no")!=null)
            throw BizException.of(ErrorCode.CONFLICT);
    }
}
