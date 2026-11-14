package ai.neargo.shop.food.service;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.FoodStockContracts.*;
public interface FoodStockItemService {
    ItemVO createItem(String entity, String store, String actor, CreateItem input, String key);
    ItemVO updateItem(String entity, String store, String item, String actor, UpdateItem input, String key);
    ItemVO getItem(String entity, String store, String item);
    PageData<ItemVO> listItems(String entity, String store, int page, int size, String keyword, String kind, String status);
}
