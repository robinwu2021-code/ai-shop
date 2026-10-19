package ai.neargo.shop.pay.points;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ai.neargo.shop.pay.mapper.points.PointsAvailabilityMapper;
import ai.neargo.shop.pay.points.store.impl.AcceptanceAvailabilityProjection;
import ai.neargo.shop.pay.points.model.AgreementSnapshot;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AvailabilityProjectionTest {
    @Test void moreThanTenThousandHistoricalAgreementsArePagedAndMerged() {
        var mapper=mock(PointsAvailabilityMapper.class);
        when(mapper.lockType("T")).thenReturn(Map.of("market","CN"));
        when(mapper.snapshot("T")).thenReturn(null);
        var json=new ObjectMapper();
        var agreement=new AgreementSnapshot("A","T","M","S","OWNER","V","H","terms",
                "PER_ORDER","DIRECT","TEST","1","ACTIVE");
        String payload=json.writeValueAsString(agreement);
        when(mapper.agreements(eq("T"),anyLong(),anyInt())).thenAnswer(call -> {
            long after=call.getArgument(1); int limit=call.getArgument(2);
            List<Map<String,Object>> page=new ArrayList<>();
            for(long id=after+1;id<=Math.min(10001,after+limit);id++)
                page.add(Map.of("id",id,"payload_json",payload,"state","ACTIVE",
                        "created_at",new java.sql.Timestamp(100),"updated_at",new java.sql.Timestamp(100)));
            return page;
        });
        var projection=new AcceptanceAvailabilityProjection(mapper);
        projection.rebuild("T");
        var captured=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(mapper).insert(eq("T"),eq(1L),captured.capture());
        var intervals=json.readValue(captured.getValue(),new tools.jackson.core.type.TypeReference<List<ai.neargo.shop.pay.points.policy.ExpiryPolicy.Interval>>(){});
        assertThat(intervals).hasSize(1);
        assertThat(intervals.getFirst().from()).isEqualTo(100);
        assertThat(intervals.getFirst().to()).isEqualTo(Long.MAX_VALUE);
        verify(mapper,times(21)).agreements(eq("T"),anyLong(),eq(500));
    }
    @Test void currentProjectionAvoidsHistoricalQueriesAndStaleWatermarkFailsClosed() {
        var mapper=mock(PointsAvailabilityMapper.class);
        when(mapper.lockType("T")).thenReturn(Map.of("market","CN"));
        when(mapper.snapshot("T")).thenReturn(null);
        var cold=new AcceptanceAvailabilityProjection(mapper);
        assertThatThrownBy(() -> cold.current("T")).hasMessage("POINTS_AVAILABILITY_PROJECTION_PENDING");
        verify(mapper,never()).agreements(anyString(),anyLong(),anyInt());
        when(mapper.snapshot("T")).thenReturn(Map.of("source_version",3L,"projected_version",3L,
                "payload_json","[{\"from\":100,\"to\":200}]"));
        var projection=new AcceptanceAvailabilityProjection(mapper);
        assertThat(projection.current("T")).hasSize(1);
        verify(mapper,never()).agreements(anyString(),anyLong(),anyInt());
        when(mapper.snapshot("T")).thenReturn(Map.of("source_version",4L,"projected_version",3L));
        assertThatThrownBy(() -> projection.current("T")).hasMessage("POINTS_AVAILABILITY_PROJECTION_PENDING");
    }
}
