package ai.neargo.shop.food.entity;

/** 门店设备（行业库 {@code ind_store_device}，TDD-餐饮域迁移新库 §2.8 D-5）；只取屏类设备用得到的列。 */
public record IndStoreDevice(
        String deviceNo,
        String entityNo,
        String storeNo,
        String deviceType,
        String displayName,
        String serialNo,
        String status,
        long version) {}
