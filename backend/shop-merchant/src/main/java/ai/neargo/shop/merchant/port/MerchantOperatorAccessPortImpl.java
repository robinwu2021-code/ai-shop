package ai.neargo.shop.merchant.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.merchant.mapper.MerchantMappers;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Current database facts stay owner-local; ambiguous identity and absent role definitions never grant access. */
@Component
public class MerchantOperatorAccessPortImpl implements MerchantOperatorAccessPort {
    private final MerchantMappers.OperatorAccessMapper mapper;
    public MerchantOperatorAccessPortImpl(MerchantMappers.OperatorAccessMapper mapper) { this.mapper = mapper; }
    @Override
    public boolean allowed(Query query) {
        if (query == null) { throw new IllegalArgumentException("explicit operator query required"); }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("operator facts require no caller transaction");
        }
        return DataScopeContext.executeWithoutScope(() -> {
            var rows = mapper.selectAccess(query);
            return rows.size() == 1 && rows.getFirst().allowed();
        });
    }
}
