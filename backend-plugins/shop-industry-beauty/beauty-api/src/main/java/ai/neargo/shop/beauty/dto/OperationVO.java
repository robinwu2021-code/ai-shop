package ai.neargo.shop.beauty.dto;

/** Original operation projection; resource evidence and identity proofs stay on trusted server links. */
public record OperationVO(String operationNo, String appointmentNo, String state, String action,
        String recoveryAction, String workOrderNo, String failureCode) {
    public OperationVO(String operationNo, String appointmentNo, String state, String action, String recoveryAction) {
        this(operationNo, appointmentNo, state, action, recoveryAction, null, null);
    }
}
