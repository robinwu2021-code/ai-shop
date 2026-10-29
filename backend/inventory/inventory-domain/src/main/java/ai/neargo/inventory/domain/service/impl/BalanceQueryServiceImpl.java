package ai.neargo.inventory.domain.service.impl;

import ai.neargo.inventory.domain.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.entity.InvStockBalance;
import ai.neargo.inventory.domain.mapper.InventoryMappers.BalanceMapper;
import ai.neargo.inventory.domain.service.BalanceQueryService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.util.List;

@ConditionalOnInventory
@Service
public class BalanceQueryServiceImpl implements BalanceQueryService {

    private final BalanceMapper balanceMapper;

    public BalanceQueryServiceImpl(BalanceMapper balanceMapper) {
        this.balanceMapper = balanceMapper;
    }

    @Override
    public List<InvStockBalance> balances(String ownerId, String locationId, String itemId) {
        return balanceMapper.selectList(Wrappers.<InvStockBalance>lambdaQuery()
                .eq(InvStockBalance::getOwnerId, ownerId)
                .eq(locationId != null, InvStockBalance::getLocationId, locationId)
                .eq(itemId != null, InvStockBalance::getItemId, itemId)
                .orderByAsc(InvStockBalance::getLocationId, InvStockBalance::getItemId));
    }
}
