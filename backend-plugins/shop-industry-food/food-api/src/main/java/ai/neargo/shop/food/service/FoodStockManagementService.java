package ai.neargo.shop.food.service;

/** 餐饮本地库存管理：物料档案与库存余额两组能力由同一个实现提供。 */
public interface FoodStockManagementService extends FoodStockItemService, FoodStockBalanceService {
}
