package ai.neargo.shop.merchant.dto;

import java.util.Set;

/** Only physical resources are writable here; personnel owns creation and status of canonical STAFF identities. */
public record SaveResourceReq(String resourceNo, String type, String personnelNo, String storeNo, String name,
        Integer capacity, String status, Long expectedRevision) {
    public SaveResourceReq {
        PersonnelInputs.text(resourceNo, 32, true);
        PersonnelInputs.text(storeNo, 32, true);
        PersonnelInputs.text(name, 64, false);
        PersonnelInputs.require(type != null && Set.of("ROOM", "SEAT", "DEVICE").contains(type));
        PersonnelInputs.require(personnelNo == null && capacity != null && capacity >= 1 && capacity <= 1000);
        PersonnelInputs.require(status != null && Set.of("ACTIVE", "SUSPENDED").contains(status));
        PersonnelInputs.revision(expectedRevision);
        PersonnelInputs.require(resourceNo != null || expectedRevision == 0 && "ACTIVE".equals(status));
    }
}
