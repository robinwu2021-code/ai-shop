package ai.neargo.shop.food.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.CreateMenuReq;
import ai.neargo.shop.food.dto.CreateMenuResultVO;
import ai.neargo.shop.food.dto.ListMenusQuery;
import ai.neargo.shop.food.dto.MenuConfigResultVO;
import ai.neargo.shop.food.dto.MenuResultVO;
import ai.neargo.shop.food.dto.MenuVO;
import ai.neargo.shop.food.dto.UpdateMenuReq;
import ai.neargo.shop.food.dto.ReplaceMenuScheduleReq;
import ai.neargo.shop.food.dto.MenuScheduleResultVO;

/** Restaurant headers reuse store bindings; publication and sellability remain separate accepted workflows. */
public interface MenuService {
    CreateMenuResultVO createMenu(String entity, String store, String actor, CreateMenuReq req, String key);
    MenuResultVO updateMenu(String entity, String store, String menu, String actor, UpdateMenuReq req, String key);
    /** Replaces only the explicit schedule section, preserving composition and immutable history. */
    MenuScheduleResultVO replaceSchedule(String entity, String store, String menu, String actor,
            ReplaceMenuScheduleReq req, String key);
    MenuConfigResultVO getMenu(String entity, String store, String menu);
    PageData<MenuVO> listMenus(String entity, String store, ListMenusQuery query);
}
