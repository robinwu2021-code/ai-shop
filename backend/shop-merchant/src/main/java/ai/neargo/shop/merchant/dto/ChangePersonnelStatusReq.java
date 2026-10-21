package ai.neargo.shop.merchant.dto;

import java.util.Set;

/** Destructive transitions require an explicit reason and no unresolved resource work. */
public record ChangePersonnelStatusReq(Long expectedRevision, String status, String reason) {
    public ChangePersonnelStatusReq {
        PersonnelInputs.revision(expectedRevision);
        PersonnelInputs.require(status != null && Set.of("ACTIVE", "SUSPENDED", "LEFT").contains(status));
        PersonnelInputs.text(reason, 500, false);
    }
}
