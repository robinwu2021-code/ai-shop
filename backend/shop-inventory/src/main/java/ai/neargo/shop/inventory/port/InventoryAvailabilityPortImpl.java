package ai.neargo.shop.inventory.port;

import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.inventory.entity.InvItem;
import ai.neargo.shop.inventory.entity.InvItemRef;
import ai.neargo.shop.inventory.entity.InvLocation;
import ai.neargo.shop.inventory.entity.InvOwner;
import ai.neargo.shop.inventory.entity.InvStockBalance;
import ai.neargo.shop.inventory.mapper.InventoryMappers.BalanceMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ItemMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.ItemRefMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.LocationMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.OwnerMapper;
import ai.neargo.shop.inventory.service.InventoryAclService;
import ai.neargo.shop.spi.inventory.InventoryAvailabilityPort;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * No projection writes, default locations, missing-row zeros or legacy integer sellable fallbacks.
 */
@Component
public class InventoryAvailabilityPortImpl implements InventoryAvailabilityPort {
    private final InventoryAclService acl;
    private final OwnerMapper owners;
    private final ItemRefMapper refs;
    private final ItemMapper items;
    private final BalanceMapper balances;
    private final LocationMapper locations;

    public InventoryAvailabilityPortImpl(
            InventoryAclService acl,
            OwnerMapper owners,
            ItemRefMapper refs,
            ItemMapper items,
            BalanceMapper balances,
            LocationMapper locations) {
        this.acl = acl;
        this.owners = owners;
        this.refs = refs;
        this.items = items;
        this.balances = balances;
        this.locations = locations;
    }

    @Override
    @Transactional(
            transactionManager = "invTransactionManager",
            readOnly = true,
            isolation = Isolation.REPEATABLE_READ)
    public Facts capture(Query q) {
        if (q == null || blank(q.entityNo()) || blank(q.storeNo()) || blank(q.skuNo()))
            throw new IllegalArgumentException("INVENTORY_AVAILABILITY_SCOPE_REQUIRED");
        String owner = acl.findOwnerId(q.entityNo());
        if (owner == null) return unknown();
        var originalOwner =
                owners.selectOne(
                        Wrappers.<InvOwner>query()
                                .eq("owner_id", owner)
                                .eq("external_ref", q.entityNo()));
        if (originalOwner == null || !"ACTIVE".equals(originalOwner.getStatus())) return unknown();
        String location = acl.stockLocationOfExistingStore(q.entityNo(), q.storeNo());
        if (location == null) return unknown();
        var loc =
                locations.selectOne(
                        Wrappers.<InvLocation>query()
                                .eq("owner_id", owner)
                                .eq("location_id", location));
        var ref =
                refs.selectOne(
                        Wrappers.<InvItemRef>query()
                                .eq("owner_id", owner)
                                .eq("ref_system", "AISHOP")
                                .eq("ref", q.skuNo()));
        if (loc == null || !"ACTIVE".equals(loc.getStatus()) || ref == null) return unknown();
        var item =
                items.selectOne(
                        Wrappers.<InvItem>query()
                                .eq("owner_id", owner)
                                .eq("item_id", ref.getItemId()));
        var balance =
                balances.selectOne(
                        Wrappers.<InvStockBalance>query()
                                .eq("owner_id", owner)
                                .eq("item_id", ref.getItemId())
                                .eq("location_id", location));
        if (item == null
                || !"ACTIVE".equals(item.getStatus())
                || item.getRetiredAt() != null
                || !Integer.valueOf(0).equals(item.getTrackBatch())
                || blank(item.getBaseUom())
                || balance == null
                || balance.getVersion() == null
                || balance.getOnHandQuantity() == null
                || balance.getReservedQuantity() == null) return unknown();
        Integer safety =
                balance.getSafetyStock() != null ? balance.getSafetyStock() : item.getSafetyStock();
        if (safety == null
                || safety < 0
                || balance.getOnHandQuantity().signum() < 0
                || balance.getReservedQuantity().signum() < 0) return unknown();
        var qty =
                balance.getOnHandQuantity()
                        .subtract(balance.getReservedQuantity())
                        .subtract(BigDecimal.valueOf(safety))
                        .max(BigDecimal.ZERO);
        // Include all original policy facts, including the actual stock-source location, in the
        // revision.
        var revision =
                PricingSnapshotHash.of(
                        java.util.List.of(
                                owner,
                                ref.getItemId(),
                                location,
                                item.getBaseUom(),
                                balance.getVersion(),
                                balance.getOnHandQuantity(),
                                balance.getReservedQuantity(),
                                safety,
                                Objects.toString(item.getUpdatedAt(), ""),
                                Objects.toString(loc.getUpdatedAt(), "")));
        var data =
                new Data(
                        q,
                        owner,
                        ref.getItemId(),
                        location,
                        item.getBaseUom(),
                        qty.stripTrailingZeros().toPlainString(),
                        revision);
        Instant now = Instant.now();
        return new Facts(
                Status.KNOWN,
                data,
                now,
                now.plusSeconds(30),
                "INVENTORY:" + PricingSnapshotHash.of(data));
    }

    private static Facts unknown() {
        return new Facts(Status.UNKNOWN, null, null, null, null);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
