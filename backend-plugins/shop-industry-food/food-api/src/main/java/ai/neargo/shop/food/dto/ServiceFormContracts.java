package ai.neargo.shop.food.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class ServiceFormContracts {
    private ServiceFormContracts() {}

    public enum Kind {
        NORMAL,
        BUFFET,
        BANQUET,
        CORPORATE
    }

    public record OrganizationReq(
            @NotBlank String name, @NotBlank String contactRef, @NotBlank String externalRef) {}

    public record OrganizationVO(
            String organizationNo,
            String name,
            String contactRef,
            String externalRef,
            String status) {}

    public record BookReq(
            @NotNull Kind kind,
            String reservationNo,
            String slotNo,
            String menuNo,
            String menuRevisionNo,
            @Min(1) int guaranteedPersons,
            @NotNull Instant startsAt,
            @NotNull Instant endsAt,
            String organizationNo,
            String contactRef,
            @Min(1) @Max(100) int maxEntriesPerPerson,
            @NotBlank String reason) {}

    public record Guest(
            @NotBlank @Size(max = 64) String guestRef,
            String seatNo,
            @NotBlank String personClass) {}

    public record EnterReq(
            @Min(0) long version,
            @NotBlank String checkNo,
            @Min(0) long checkVersion,
            @NotEmpty @Size(max = 1000) List<@Valid Guest> guests,
            @NotBlank String reason) {
        public EnterReq {
            if (guests != null) guests = List.copyOf(guests);
        }
    }

    public record ReviseReq(
            @Min(0) long version,
            @Min(1) int guaranteedPersons,
            String menuNo,
            String menuRevisionNo,
            @NotBlank String reason) {}

    public record VersionReq(@Min(0) long version, @NotBlank String reason) {}

    public record BatchReq(
            @Min(0) long version, @NotBlank String batchNo, @NotBlank String reason) {}

    public record FormVO(
            String formNo,
            Kind kind,
            String status,
            String reservationNo,
            String slotNo,
            String menuNo,
            String menuRevisionNo,
            int guaranteedPersons,
            Integer actualPersons,
            String checkNo,
            Instant startsAt,
            Instant endsAt,
            String organizationNo,
            String contactRef,
            int maxEntriesPerPerson,
            String sourceTransition,
            long version) {}

    public record AttendeeVO(
            String attendeeNo,
            String guestRef,
            String seatNo,
            String personClass,
            String status,
            int entryCount,
            long version) {}
}
