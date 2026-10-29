package ai.neargo.shop.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import ai.neargo.shop.inventory.port.InventoryAvailabilityPortImpl;
import ai.neargo.shop.spi.inventory.InventoryAvailabilityPort;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

class InventoryAvailabilityPortTest {
    final InventoryAclService acl = mock(InventoryAclService.class);
    final OwnerMapper owners = mock(OwnerMapper.class);
    final ItemRefMapper refs = mock(ItemRefMapper.class);
    final ItemMapper items = mock(ItemMapper.class);
    final BalanceMapper balances = mock(BalanceMapper.class);
    final LocationMapper locations = mock(LocationMapper.class);
    final InventoryAvailabilityPortImpl service =
            new InventoryAvailabilityPortImpl(acl, owners, refs, items, balances, locations);
    final InventoryAvailabilityPort.Query query =
            new InventoryAvailabilityPort.Query("E", "S", "SKU");

    InvStockBalance setup() {
        when(acl.findOwnerId("E")).thenReturn("OWNER");
        var owner = new InvOwner();
        owner.setOwnerId("OWNER");
        owner.setStatus("ACTIVE");
        when(owners.selectOne(any())).thenReturn(owner);
        when(acl.stockLocationOfExistingStore("E", "S")).thenReturn("LOCATION");
        var loc = new InvLocation();
        loc.setOwnerId("OWNER");
        loc.setLocationId("LOCATION");
        loc.setStatus("ACTIVE");
        when(locations.selectOne(any())).thenReturn(loc);
        var ref = new InvItemRef();
        ref.setItemId("ITEM");
        ref.setOwnerId("OWNER");
        ref.setRef("SKU");
        when(refs.selectOne(any())).thenReturn(ref);
        var item = new InvItem();
        item.setItemId("ITEM");
        item.setOwnerId("OWNER");
        item.setBaseUom("KG");
        item.setStatus("ACTIVE");
        item.setTrackBatch(0);
        item.setSafetyStock(1);
        when(items.selectOne(any())).thenReturn(item);
        var balance = new InvStockBalance();
        balance.setOnHandQuantity(new BigDecimal("9.750000"));
        balance.setReservedQuantity(new BigDecimal("2.125000"));
        balance.setVersion(7L);
        when(balances.selectOne(any())).thenReturn(balance);
        return balance;
    }

    @Test
    void returnsExactOwnerBalanceAndSafetyWithRealVersionWithoutIntegerTruncation() {
        setup();
        var facts = service.capture(query);
        assertThat(facts.status()).isEqualTo(InventoryAvailabilityPort.Status.KNOWN);
        assertThat(facts.data().availableQuantity()).isEqualTo("6.625");
        assertThat(facts.data().locationId()).isEqualTo("LOCATION");
        assertThat(facts.data().unit()).isEqualTo("KG");
        assertThat(facts.validUntil()).isEqualTo(facts.observedAt().plusSeconds(30));
        verify(acl, never()).ownerIdOf(anyString());
        verify(acl, never()).itemIdOf(anyString(), anyString());
        verify(acl, never()).locationIdOf(anyString(), anyString());
    }

    @Test
    void absenceOfOwnerLocationBalanceOrVersionRemainsUnknownRatherThanZero() {
        assertThat(service.capture(query).status())
                .isEqualTo(InventoryAvailabilityPort.Status.UNKNOWN);
        var balance = setup();
        when(balances.selectOne(any())).thenReturn(null);
        assertThat(service.capture(query).data()).isNull();
        when(balances.selectOne(any())).thenReturn(balance);
        balance.setVersion(null);
        assertThat(service.capture(query).status())
                .isEqualTo(InventoryAvailabilityPort.Status.UNKNOWN);
    }

    @Test
    void batchTrackedOrUnknownTrackingPolicyCannotAttestUnexpiredSellableTotal() {
        setup();
        var item = new InvItem();
        item.setStatus("ACTIVE");
        item.setBaseUom("KG");
        item.setSafetyStock(1);
        when(items.selectOne(any())).thenReturn(item);
        assertThat(service.capture(query).status())
                .isEqualTo(InventoryAvailabilityPort.Status.UNKNOWN);
        item.setTrackBatch(1);
        assertThat(service.capture(query).status())
                .isEqualTo(InventoryAvailabilityPort.Status.UNKNOWN);
        item.setTrackBatch(0);
        assertThat(service.capture(query).status())
                .isEqualTo(InventoryAvailabilityPort.Status.KNOWN);
    }

    @Test
    void knownZeroIsDistinctFromMissingEvidenceAndSourceChangesHaveDifferentRevisions() {
        var balance = setup();
        var before = service.capture(query);
        balance.setOnHandQuantity(new BigDecimal("2"));
        balance.setVersion(8L);
        var after = service.capture(query);
        assertThat(after.status()).isEqualTo(InventoryAvailabilityPort.Status.KNOWN);
        assertThat(after.data().availableQuantity()).isEqualTo("0");
        assertThat(after.data().revision()).isNotEqualTo(before.data().revision());
        assertThat(after.receiptRef()).isNotEqualTo(before.receiptRef());
    }
}
