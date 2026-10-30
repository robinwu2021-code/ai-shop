package ai.neargo.shop.beauty.dto;

import java.util.List;

/** A durable coordination request, without claiming a public approval workflow exists. */
public record ReceptionExceptionVO(String requestNo, String appointmentNo, String state,
        List<String> affectedTaskNos, String operationNo) {
    public ReceptionExceptionVO { affectedTaskNos = List.copyOf(affectedTaskNos); }
}
