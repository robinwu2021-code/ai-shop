package ai.neargo.shop.inventory.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.inventory.entity.InvOwner;
import ai.neargo.shop.inventory.mapper.FoodStockProjectionMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.OwnerMapper;
import ai.neargo.shop.inventory.service.InventoryAclService;
import ai.neargo.shop.spi.inventory.InventoryFoodStockProjectionPort.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class FoodStockProjectionPreflightTest {
    private final FoodStockProjectionMapper rows=mock(FoodStockProjectionMapper.class);
    private final OwnerMapper owners=mock(OwnerMapper.class);
    private final InventoryAclService acl=mock(InventoryAclService.class);
    private final Scope scope=new Scope("E","S");
    private FoodStockProjectionServiceImpl service() {
        when(acl.findOwnerId("E")).thenReturn("OWNER");
        when(acl.stockLocationOfExistingStore("E","S")).thenReturn("LOCATION");
        var owner=new InvOwner();owner.setStatus("ACTIVE");
        when(owners.selectOne(any())).thenReturn(owner);
        return new FoodStockProjectionServiceImpl(rows,owners,new ObjectMapper(),acl);
    }
    @Test void sourceOnlyMirrorValidationNeverCreatesNativeInventoryOrMirrorRecords() {
        var service=service();
        assertThat(service.validateMappings(scope,List.of(new Mapping("LOCAL","G",null,null,null))))
                .containsExactly(new MappingResult("LOCAL",true,null));
        verifyNoInteractions(rows);verify(acl,never()).findMaterials(any(),any());
    }
    @Test void eachMappingReportsOwnerLocationMissingItemAndUnitFailuresBeforeEnablement() {
        var service=service();
        when(acl.findMaterials("E",List.of("MISSING"))).thenReturn(List.of());
        when(acl.findMaterials("E",List.of("TARGET"))).thenReturn(List.of(
                new InventoryAclService.MaterialIdentity("TARGET","material","KG",1,"OWN")));
        var results=service.validateMappings(scope,List.of(
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
        var service=service();when(acl.stockLocationOfExistingStore("E","S")).thenReturn(null);
        assertThatThrownBy(()->service.validateMappings(scope,List.of())).isInstanceOf(BizException.class);
        verifyNoInteractions(rows);verify(owners,never()).insert(any(InvOwner.class));
    }
}
