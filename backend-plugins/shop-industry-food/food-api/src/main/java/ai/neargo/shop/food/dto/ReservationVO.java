package ai.neargo.shop.food.dto;

import java.time.Instant;

/** No contact/buyer credentials, and a deposit reference does not assert payment success. */
public record ReservationVO(String reservationNo,int partySize,Instant startAt,Instant endAt,String status,
        String depositRef,String checkNo,long version) { }
