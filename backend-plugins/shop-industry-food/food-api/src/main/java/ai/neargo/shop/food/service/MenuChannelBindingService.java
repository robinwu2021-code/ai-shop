package ai.neargo.shop.food.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.CreateMenuChannelBindingReq;
import ai.neargo.shop.food.dto.MenuChannelBindingVO;
import ai.neargo.shop.food.dto.UpdateMenuChannelBindingReq;

/** Planning references remain drafts until an actual account authority authorizes their activation. */
public interface MenuChannelBindingService {
    MenuChannelBindingVO createBinding(String entity,String store,String actor,
            CreateMenuChannelBindingReq req,String key);
    MenuChannelBindingVO updateBinding(String entity,String store,String number,String actor,
            UpdateMenuChannelBindingReq req,String key);
    MenuChannelBindingVO getBinding(String entity,String store,String number);
    PageData<MenuChannelBindingVO> listBindings(String entity,String store,Integer page,Integer size,
            String provider,String menuNo);
}
