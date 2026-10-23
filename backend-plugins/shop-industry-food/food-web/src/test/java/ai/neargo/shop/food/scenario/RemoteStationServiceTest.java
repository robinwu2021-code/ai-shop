package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.food.dto.StationContracts.CreateStationReq;
import ai.neargo.shop.food.dto.StationContracts.GetStationVO;
import ai.neargo.shop.food.dto.StationContracts.StationMutationVO;
import ai.neargo.shop.food.dto.StationContracts.StationVO;
import ai.neargo.shop.food.dto.StationContracts.UpdateStationReq;
import ai.neargo.shop.food.foodclient.RemoteStationService;
import ai.neargo.shop.food.spi.StationInternalApi;
import ai.neargo.shop.food.spi.MenuChannelBindingInternalApi;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RemoteStationServiceTest {
    @Test void commandsCarryTrustedStoreActorAndCompareMutationVersion() {
        var api=mock(StationInternalApi.class); var service=new RemoteStationService(api);
        var create=new CreateStationReq("Kitchen","HOT");
        when(api.createStation(any())).thenReturn(new StationMutationVO("STA",0,"DRAFT"));
        assertThat(service.createStation("E","A","actor",create,"key").configNo()).isEqualTo("STA");
        verify(api).createStation(new StationInternalApi.CreateCommand("E","A","actor","key",create));
        var update=new UpdateStationReq(0L,"Kitchen","HOT",true);
        when(api.updateStation(any())).thenReturn(new StationMutationVO("STA",2,"DRAFT"));
        assertThatThrownBy(() -> service.updateStation("E","A","STA","actor",update,"next"))
                .isInstanceOf(IllegalStateException.class);
        verify(api).updateStation(new StationInternalApi.UpdateCommand("E","A","STA","actor","next",update));
        when(api.getStation(any())).thenReturn(new GetStationVO(
                new StationVO("STA",0,"DRAFT","B","Kitchen","HOT",true,null)));
        assertThatThrownBy(() -> service.getStation("E","A","STA")).isInstanceOf(IllegalStateException.class);
        verify(api).getStation(new StationInternalApi.GetQuery("E","A","STA")); verifyNoMoreInteractions(api);
    }
    @Test void wireVersionsRejectFractionalStringNullAndMissingValues() {
        var json=new ObjectMapper();
        for(String token:List.of("0.5","\"0\"","null","-1")) {
            assertThatThrownBy(() -> json.readValue("{\"configNo\":\"STA\",\"version\":"+token
                    +",\"status\":\"DRAFT\"}",StationMutationVO.class)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> json.readValue("{\"version\":"+token
                    +",\"name\":\"Kitchen\",\"stationType\":\"HOT\",\"enabled\":true}",UpdateStationReq.class))
                    .isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(() -> json.readValue("{\"configNo\":\"STA\",\"status\":\"DRAFT\"}",
                StationMutationVO.class)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> json.readValue("{\"entityNo\":12,\"storeNo\":\"A\",\"configNo\":\"STA\"}",
                StationInternalApi.GetQuery.class)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> json.readValue("{\"entityNo\":12,\"storeNo\":\"A\",\"bindingNo\":\"BIND\"}",
                MenuChannelBindingInternalApi.GetQuery.class)).isInstanceOf(RuntimeException.class);

    }
}
