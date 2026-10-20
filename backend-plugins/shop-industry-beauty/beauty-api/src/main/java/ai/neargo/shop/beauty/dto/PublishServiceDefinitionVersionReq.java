package ai.neargo.shop.beauty.dto;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** P1 fixed-step execution definition; duration must also match the product owner's authoritative fact. */
public record PublishServiceDefinitionVersionReq(String skuNo, Long expectedRevision, Duration duration,
        List<Requirement> requirements) {
    public PublishServiceDefinitionVersionReq {
        if (!text(skuNo, 32) || expectedRevision == null || expectedRevision < 0 || duration == null
                || requirements == null || requirements.isEmpty() || requirements.size() > 100) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        Set<String> identities = new HashSet<>();
        for (Requirement row : requirements) {
            if (row == null || (long) row.offsetMin() + row.durationMin() > duration.minutes()
                    || !identities.add(row.sequenceNo() + ":" + row.roleCode() + ":" + row.resourceType())) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
        }
        if (requirements.stream().noneMatch(row -> row.resourceType().equals("STAFF"))) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        requirements = requirements.stream().sorted(Comparator.comparing(Requirement::sequenceNo)
                .thenComparing(Requirement::roleCode).thenComparing(Requirement::resourceType)).toList();
    }

    private static boolean text(String value, int length) {
        return value != null && !value.isBlank() && value.length() <= length;
    }

    /** Buffers are execution rules; minutes is a consistency assertion against product durationMin. */
    public record Duration(Integer minutes, Integer bufferBeforeMin, Integer bufferAfterMin) {
        public Duration {
            if (minutes == null || minutes < 1 || minutes > 1440 || bufferBeforeMin == null || bufferBeforeMin < 0
                    || bufferAfterMin == null || bufferAfterMin < 0 || bufferBeforeMin > 1440
                            || bufferAfterMin > 1440) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
        }
    }

    /** STAFF skills reference the same product, while physical resources cannot impersonate skill owners. */
    public record Requirement(String roleCode, String resourceType, String skillGoodsNo, Integer qty,
            Integer sequenceNo, Integer offsetMin, Integer durationMin, String selectionRule) {
        public Requirement {
            if (!text(roleCode, 32) || !Set.of("STAFF", "ROOM", "SEAT", "DEVICE").contains(resourceType == null
                    ? "" : resourceType) || qty == null || qty < 1 || qty > 100 || sequenceNo == null || sequenceNo != 0
                    || offsetMin == null || offsetMin < 0 || durationMin == null || durationMin < 1
                    || !"ANY_QUALIFIED".equals(selectionRule)
                    || ("STAFF".equals(resourceType) ? !text(skillGoodsNo, 32) : skillGoodsNo != null)) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
        }
    }
}
