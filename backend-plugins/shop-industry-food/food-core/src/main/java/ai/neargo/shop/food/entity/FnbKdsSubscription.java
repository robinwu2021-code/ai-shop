package ai.neargo.shop.food.entity;

/** 厨显订阅：哪台厨显看哪些工位（{@code stations} 为 JSON 数组），每台一行（TDD-餐饮域迁移新库 §2.8 D-5）。 */
public record FnbKdsSubscription(String entityNo, String storeNo, String deviceNo, String stations, long version) {}
