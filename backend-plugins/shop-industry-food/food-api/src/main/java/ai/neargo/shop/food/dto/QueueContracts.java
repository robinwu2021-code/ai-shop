package ai.neargo.shop.food.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Reception identities do not identify pickup orders; no contact secret is returned. */
public final class QueueContracts {
    private QueueContracts() { }
    public record IssueReq(@NotNull @Min(1) @Max(1000) Integer partySize,@Size(max=64) String contactRef) {
        @JsonAnySetter public void unknown(String name,Object value) { throw new IllegalArgumentException(name); }
    }
    public record Query(@NotNull LocalDate businessDate,String status,Integer page,Integer size) { }
    public record ChangeReq(@NotNull @Min(0) Long version,@Size(max=255) String reason) {
        @JsonAnySetter public void unknown(String name,Object value) { throw new IllegalArgumentException(name); }
    }
    public record SeatQueueReq(@NotNull @Min(0) Long version,@NotNull @Valid OpenCheckReq check) {
        @JsonAnySetter public void unknown(String name,Object value) { throw new IllegalArgumentException(name); }
    }
    public record QueueVO(String ticketNo,LocalDate businessDate,int partySize,String status,
            LocalDateTime calledAt,String checkNo,long version,String notificationStatus) { }
}
