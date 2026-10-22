package ai.neargo.shop.food.dto;

import java.time.Instant;

/** Dining reception contract; monetary authority remains outside this resource. */
public record OccupancyVO(String occupancyNo, String tableNo, String checkNo, int seats, Instant startedAt) {}
