package ai.neargo.shop.merchant.dto;

/** A business person may have no login; initial store employment makes the new record explicitly scoped. */
public record CreatePersonnelReq(String displayName, String avatarRef, AssignPersonnelReq assignment) {
    public CreatePersonnelReq {
        PersonnelInputs.text(displayName, 64, false);
        PersonnelInputs.text(avatarRef, 64, true);
        PersonnelInputs.require(assignment != null && assignment.expectedRevision() == 0
                && assignment.assignmentNo() == null);
    }
}
