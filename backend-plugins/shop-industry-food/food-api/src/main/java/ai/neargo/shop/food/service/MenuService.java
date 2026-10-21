package ai.neargo.shop.food.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.CreateMenuReq;
import ai.neargo.shop.food.dto.CreateMenuResultVO;
import ai.neargo.shop.food.dto.ListMenusQuery;
import ai.neargo.shop.food.dto.MenuConfigResultVO;
import ai.neargo.shop.food.dto.MenuResultVO;
import ai.neargo.shop.food.dto.MenuVO;
import ai.neargo.shop.food.dto.UpdateMenuReq;

/** Restaurant headers reuse store bindings; publication and sellability remain separate accepted workflows. */
public interface MenuService {
    CreateMenuResultVO createMenu(String entity, String store, String actor, CreateMenuReq req, String key);
    MenuResultVO updateMenu(String entity, String store, String menu, String actor, UpdateMenuReq req, String key);
    MenuConfigResultVO getMenu(String entity, String store, String menu);
    PageData<MenuVO> listMenus(String entity, String store, ListMenusQuery query);
}
