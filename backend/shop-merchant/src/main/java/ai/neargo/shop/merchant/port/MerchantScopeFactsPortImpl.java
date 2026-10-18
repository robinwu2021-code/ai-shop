package ai.neargo.shop.merchant.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper;
import ai.neargo.shop.spi.user.MerchantScopeFactsPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Merchant ownership is checked inside the owner domain, without leaking merchant entities to core or pay. */
@Component
public class MerchantScopeFactsPortImpl implements MerchantScopeFactsPort {
    private static final int BATCH_SIZE = 200;
    private final MchEntityMapper entities;
    private final MchStoreMapper stores;

    public MerchantScopeFactsPortImpl(MchEntityMapper entities, MchStoreMapper stores) {
        this.entities = entities;
        this.stores = stores;
    }

    @Override
    public boolean entityActive(String tenantNo, String entityNo) {
        return DataScopeContext.executeWithoutScope(() -> entities.selectCount(
                Wrappers.<MchEntity>lambdaQuery().eq(MchEntity::getTenantNo, tenantNo)
                        .eq(MchEntity::getEntityNo, entityNo).eq(MchEntity::getStatus, MchEntity.ACTIVE)
                        .isNull(MchEntity::getArchivedAt))) == 1;
    }

    @Override
    public boolean storesOwned(String tenantNo, String entityNo, Set<String> storeNos) {
        if (storeNos == null || storeNos.isEmpty()) {
            return false;
        }
        List<String> ids = new ArrayList<>(storeNos);
        for (int from = 0; from < ids.size(); from += BATCH_SIZE) {
            var batch = ids.subList(from, Math.min(from + BATCH_SIZE, ids.size()));
            long count = DataScopeContext.executeWithoutScope(() -> stores.selectCount(
                    Wrappers.<MchStore>lambdaQuery().eq(MchStore::getTenantNo, tenantNo)
                            .eq(MchStore::getEntityNo, entityNo).in(MchStore::getStoreNo, batch)
                            .in(MchStore::getStatus, MchStore.ACTIVE, MchStore.READONLY)));
            if (count != batch.size()) {
                return false;
            }
        }
        return true;
    }
}
