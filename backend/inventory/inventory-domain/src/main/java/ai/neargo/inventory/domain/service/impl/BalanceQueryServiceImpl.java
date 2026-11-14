package ai.neargo.inventory.domain.service.impl;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.domain.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.entity.InvItem;
import ai.neargo.inventory.domain.entity.InvLocation;
import ai.neargo.inventory.domain.entity.InvOwner;
import ai.neargo.inventory.domain.entity.InvStockBalance;
import ai.neargo.inventory.domain.mapper.InventoryMappers.BalanceMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.ItemMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.LocationMapper;
import ai.neargo.inventory.domain.service.BalanceQueryService;
import ai.neargo.inventory.domain.service.ServicePlatformService;
import ai.neargo.inventory.domain.support.InvEnums;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ConditionalOnInventory
@Service
public class BalanceQueryServiceImpl implements BalanceQueryService {

    private final BalanceMapper balanceMapper;
    private final ItemMapper itemMapper;
    private final LocationMapper locationMapper;
    private final ServicePlatformService platform;

    public BalanceQueryServiceImpl(BalanceMapper balanceMapper, ItemMapper itemMapper, LocationMapper locationMapper,
                                   ServicePlatformService platform) {
        this.balanceMapper = balanceMapper;
        this.itemMapper = itemMapper;
        this.locationMapper = locationMapper;
        this.platform = platform;
    }

    @Override
    public List<InvStockBalance> balances(String ownerId, String locationId, String itemId) {
        return balanceMapper.selectList(Wrappers.<InvStockBalance>lambdaQuery()
                .eq(InvStockBalance::getOwnerId, ownerId)
                .eq(locationId != null, InvStockBalance::getLocationId, locationId)
                .eq(itemId != null, InvStockBalance::getItemId, itemId)
                .orderByAsc(InvStockBalance::getLocationId, InvStockBalance::getItemId));
    }

    @Override
    public List<OwnerBalances> acrossOwners(String systemCode, List<OwnerKey> owners, Set<String> itemRefs) {
        List<OwnerBalances> out = new ArrayList<>();
        for (OwnerKey key : owners) {
            InvOwner owner;
            try {
                owner = platform.ownerByRef(systemCode, key.type(), key.ref());
            } catch (InventoryException ambiguous) {
                // 同号多本：这一项报给宿主，其余照常
                out.add(new OwnerBalances(key.type(), key.ref(), "owner_type_required", List.of()));
                continue;
            }
            if (owner == null) {
                out.add(new OwnerBalances(key.type(), key.ref(), InventoryError.NOT_FOUND.name(), List.of()));
            } else if (!InvEnums.WriteAuthority.SVC.equals(owner.getWriteAuthority())) {
                // 过渡期由宿主内嵌写的账，服务这边的数字不作数
                out.add(new OwnerBalances(owner.getExternalType(), key.ref(), InventoryError.NOT_WRITER.name(),
                        List.of()));
            } else {
                out.add(new OwnerBalances(owner.getExternalType(), key.ref(), null,
                        rows(owner.getOwnerId(), itemRefs)));
            }
        }
        return out;
    }

    private List<Row> rows(String ownerId, Set<String> itemRefs) {
        Map<String, String> itemRefById = new HashMap<>();
        itemMapper.selectList(Wrappers.<InvItem>lambdaQuery()
                        .eq(InvItem::getOwnerId, ownerId)
                        .in(!itemRefs.isEmpty(), InvItem::getExternalRef, itemRefs))
                .forEach(i -> itemRefById.put(i.getItemId(), i.getExternalRef()));
        if (itemRefById.isEmpty()) {
            return List.of();
        }
        Map<String, String> locationRefById = new HashMap<>();
        locationMapper.selectList(Wrappers.<InvLocation>lambdaQuery().eq(InvLocation::getOwnerId, ownerId))
                .forEach(l -> locationRefById.put(l.getLocationId(), l.getExternalRef()));
        return balanceMapper.selectList(Wrappers.<InvStockBalance>lambdaQuery()
                        .eq(InvStockBalance::getOwnerId, ownerId)
                        .in(InvStockBalance::getItemId, itemRefById.keySet())
                        .orderByAsc(InvStockBalance::getLocationId, InvStockBalance::getItemId))
                .stream()
                .map(b -> {
                    BigDecimal onHand = nz(b.getOnHandQuantity());
                    BigDecimal reserved = nz(b.getReservedQuantity());
                    BigDecimal unavailable = nz(b.getUnavailableQuantity());
                    return new Row(itemRefById.get(b.getItemId()), b.getItemId(), b.getLocationId(),
                            locationRefById.get(b.getLocationId()), onHand, reserved, unavailable,
                            onHand.subtract(reserved).subtract(unavailable));
                })
                .toList();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v.stripTrailingZeros();
    }
}
