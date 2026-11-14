package ai.neargo.shop.merchant.dto;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Parent revision protects employment, positions and store-scoped SKU qualifications as one command. */
public record AssignPersonnelReq(Long expectedRevision, String assignmentNo, String storeNo,
        OffsetDateTime validFrom, OffsetDateTime validTo, Boolean publicVisible, String levelCode,
        Integer sortOrder, List<String> positions, List<Skill> skills) {
    private static final Set<String> POSITIONS = Set.of("TECHNICIAN", "ASSISTANT", "CONSULTANT", "WAITER",
            "CHEF", "CASHIER", "RECEPTIONIST");
    public AssignPersonnelReq {
        PersonnelInputs.revision(expectedRevision);
        PersonnelInputs.text(assignmentNo, 32, true);
        PersonnelInputs.text(storeNo, 32, false);
        PersonnelInputs.text(levelCode, 32, true);
        validFrom = PersonnelInputs.utc(validFrom, false);
        validTo = PersonnelInputs.utc(validTo, true);
        PersonnelInputs.require(validTo == null || validTo.isAfter(validFrom));
        PersonnelInputs.require(publicVisible != null && sortOrder != null && sortOrder >= 0
                && positions != null && !positions.isEmpty() && positions.size() <= 7
                && positions.stream().allMatch(position -> position != null && POSITIONS.contains(position))
                && Set.copyOf(positions).size() == positions.size()
                && skills != null && skills.size() <= 100 && skills.stream().noneMatch(s -> s == null));
        PersonnelInputs.require(skills.stream().map(Skill::skuNo).distinct().count() == skills.size());
        positions = positions.stream().sorted().toList();
        skills = skills.stream().sorted(Comparator.comparing(Skill::skuNo)).toList();
    }
    public record Skill(String goodsNo, String skuNo, String levelCode) {
        public Skill {
            PersonnelInputs.text(goodsNo, 32, false);
            PersonnelInputs.text(skuNo, 32, false);
            PersonnelInputs.text(levelCode, 32, true);
        }
    }
}
