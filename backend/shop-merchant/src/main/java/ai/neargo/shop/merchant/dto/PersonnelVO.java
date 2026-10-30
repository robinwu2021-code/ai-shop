package ai.neargo.shop.merchant.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** Administrative projection with business keys; no contact reference, physical ID or permission grant. */
public record PersonnelVO(String personnelNo, String displayName, String avatarRef, String status,
        long revision, String resourceNo, List<Assignment> assignments, List<Qualification> qualifications) {
    public PersonnelVO {
        assignments = List.copyOf(assignments);
        qualifications = List.copyOf(qualifications);
    }
    public record Assignment(String assignmentNo, String storeNo, OffsetDateTime validFrom, OffsetDateTime validTo,
            boolean publicVisible, String levelCode, int sortOrder, String status, List<String> positions) {
        public Assignment { positions = List.copyOf(positions); }
    }
    public record Qualification(String skillNo, String goodsNo, String skuNo, String levelCode,
            OffsetDateTime validFrom, OffsetDateTime validTo, String status) { }
}
