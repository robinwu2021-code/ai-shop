package ai.neargo.shop.food.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.CreateModifierReq;
import ai.neargo.shop.food.dto.CreateModifierResultVO;
import ai.neargo.shop.food.dto.UpdateModifierReq;
import ai.neargo.shop.food.dto.ModifierMutationVO;
import ai.neargo.shop.food.dto.ModifierConfigVO;
import ai.neargo.shop.food.dto.ListModifiersQuery;

/** Keeps restaurant serving composition separate from original pricing, stock and publication. */
public interface ModifierService {
    CreateModifierResultVO createModifier(String entityNo, String storeNo, String actorNo,
            CreateModifierReq req, String idempotencyKey);
    ModifierMutationVO updateModifier(String entityNo, String storeNo, String modifierNo, String actorNo,
            UpdateModifierReq req, String idempotencyKey);
    PageData<ModifierConfigVO> listModifiers(String entityNo, String storeNo, ListModifiersQuery query);
}
