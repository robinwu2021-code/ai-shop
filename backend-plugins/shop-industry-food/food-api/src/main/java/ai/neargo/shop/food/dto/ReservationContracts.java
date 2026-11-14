package ai.neargo.shop.food.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** Local, exclusive FOOD table appointments; callers cannot assert buyer, external hold or payment authority. */
public final class ReservationContracts {
    private ReservationContracts() { }
    public record CreateReq(@NotNull @Min(1) Integer partySize, @NotNull Instant startAt,
            @NotNull Instant endAt, @NotBlank @Size(max=64) String contactRef,
            @NotNull @Size(min=1,max=100) List<@Valid TableVersionReq> tables) {
        public CreateReq { tables=tables==null?null:List.copyOf(tables); }
        @JsonAnySetter public void rejectUnknown(String name,Object value) {
            throw new IllegalArgumentException("Unknown reservation parameter");
        }
    }
    public record ChangeReq(@NotNull @Min(0) Long version, @NotNull @Valid CreateReq appointment,
            @NotBlank @Size(max=255) String reason) {
        @JsonAnySetter public void rejectUnknown(String name,Object value) {
            throw new IllegalArgumentException("Unknown reservation parameter");
        }
    }
    public record TransitionReq(@NotNull @Min(0) Long version,@NotBlank @Size(max=255) String reason) {
        @JsonAnySetter public void rejectUnknown(String name,Object value) {
            throw new IllegalArgumentException("Unknown reservation parameter");
        }
    }
    public record ArriveReq(@NotNull @Min(0) Long version,
            @NotNull @Size(min=1,max=100) List<@Valid TableVersionReq> tables) {
        public ArriveReq { tables=tables==null?null:List.copyOf(tables); }
        @JsonAnySetter public void rejectUnknown(String name,Object value) {
            throw new IllegalArgumentException("Unknown reservation parameter");
        }
    }
    public record DetailVO(ReservationVO reservation,List<String> tableNos) {
        public DetailVO { tableNos=List.copyOf(tableNos); }
    }
}
