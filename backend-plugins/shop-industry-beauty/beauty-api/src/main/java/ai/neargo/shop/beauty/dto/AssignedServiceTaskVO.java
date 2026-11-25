package ai.neargo.shop.beauty.dto;

import java.time.LocalDateTime;

/** Minimal technician projection. Planned time is persisted UTC. */
public record AssignedServiceTaskVO(String taskNo, String workOrderNo, String skuNo, String status,
        LocalDateTime plannedStartAt) { }
