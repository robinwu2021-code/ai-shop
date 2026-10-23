package ai.neargo.shop.food.dto;

/** Completed receipts keep the exact canonical working schedule even after later edits. */
public record MenuScheduleResultVO(MenuVO menu, MenuScheduleVO schedule) { }
