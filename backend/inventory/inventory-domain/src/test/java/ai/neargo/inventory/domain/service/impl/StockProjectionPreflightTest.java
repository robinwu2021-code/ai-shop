package ai.neargo.inventory.domain.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.domain.service.Caller;
import ai.neargo.inventory.domain.service.LocationService;
import ai.neargo.inventory.domain.entity.InvLocation;
import ai.neargo.inventory.domain.entity.InvItem;
import ai.neargo.inventory.domain.mapper.InventoryMappers.LocationMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.ItemMapper;
import ai.neargo.inventory.domain.entity.InvOwner;
import ai.neargo.inventory.domain.mapper.StockProjectionMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.OwnerMapper;
import ai.neargo.inventory.api.projection.StockProjectionContracts.Scope;
import ai.neargo.inventory.api.projection.StockProjectionContracts.Mapping;
import ai.neargo.inventory.api.projection.StockProjectionContracts.MappingResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class StockProjectionPreflightTest {
    private final StockProjectionMapper rows=mock(StockProjectionMapper.class);
    private final OwnerMapper owners=mock(OwnerMapper.class);
    private final LocationMapper locations=mock(LocationMapper.class);
    private final ItemMapper items=mock(ItemMapper.class);
    private final LocationService stockLocations=mock(LocationService.class);
    private final Caller caller=new Caller("SHOP","OWNER","ACTOR","KEY","TRACE");
    private final Scope scope=new Scope("E","S");
    private StockProjectionServiceImpl service() {
        var owner=new InvOwner();owner.setStatus("ACTIVE");owner.setExternalRef("E");
        owner.setSystemCode("SHOP");owner.setWriteAuthority("SVC");
        when(owners.selectOne(any())).thenReturn(owner);
        var location=new InvLocation();location.setLocationId("LOCATION");
        when(locations.selectOne(any())).thenReturn(location);
        when(stockLocations.resolveStockLocation("OWNER","LOCATION")).thenReturn("LOCATION");
        return new StockProjectionServiceImpl(rows,owners,new ObjectMapper(),locations,items,stockLocations,"SHOP");
    }
    @Test void sourceOnlyMirrorValidationNeverCreatesNativeInventoryOrMirrorRecords() {
        var service=service();
        assertThat(service.validateMappings(caller,scope,List.of(new Mapping("LOCAL","G",null,null,null))))
                .containsExactly(new MappingResult("LOCAL",true,null));
        verifyNoInteractions(rows);verifyNoInteractions(items);
    }
    @Test void eachMappingReportsOwnerLocationMissingItemAndUnitFailuresBeforeEnablement() {
        var service=service();
        var material=new InvItem();material.setBaseUom("KG");
        when(items.selectOne(any())).thenReturn(null,material,material);
        var results=service.validateMappings(caller,scope,List.of(
                new Mapping("A","KG","OTHER","LOCATION","TARGET"),
                new Mapping("B","KG","OWNER","OTHER","TARGET"),
                new Mapping("C","KG","OWNER","LOCATION","MISSING"),
                new Mapping("D","G","OWNER","LOCATION","TARGET"),
                new Mapping("E","KG","OWNER","LOCATION","TARGET")));
        assertThat(results).containsExactly(new MappingResult("A",false,"TARGET_OWNER_MISMATCH"),
                new MappingResult("B",false,"TARGET_LOCATION_MISMATCH"),
                new MappingResult("C",false,"TARGET_ITEM_NOT_FOUND"),
                new MappingResult("D",false,"TARGET_UNIT_MISMATCH"),new MappingResult("E",true,null));
        verifyNoInteractions(rows);
    }
    @Test void missingExistingOwnerOrLocationCannotBeCreatedByPreflight() {
        var service=service();when(locations.selectOne(any())).thenReturn(null);
        assertThatThrownBy(()->service.validateMappings(caller,scope,List.of())).isInstanceOf(InventoryException.class);
        verifyNoInteractions(rows);verify(owners,never()).insert(any(InvOwner.class));
    }
}
