package ai.neargo.shop.food.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.ReplaceOptionGroupItemsReq;
import ai.neargo.shop.food.dto.OptionGroupMutationVO;
import ai.neargo.shop.food.dto.CreateOptionGroupReq;
import ai.neargo.shop.food.dto.CreateOptionGroupResultVO;
import ai.neargo.shop.food.dto.ListOptionGroupsQuery;
import ai.neargo.shop.food.dto.OptionGroupConfigVO;
import ai.neargo.shop.food.dto.OptionGroupResultVO;

/** Manages reusable entity-owned selection definitions, without conferring sale or inventory authority. */
public interface OptionGroupService {
    /** Replaces the editable definition atomically after remote authority checks outside the local transaction. */
    OptionGroupMutationVO replaceOptionGroupItems(String entityNo, String storeNo, String groupNo,
            String actorNo, ReplaceOptionGroupItemsReq req, String idempotencyKey);

    /** Creates one draft with durable audit, configuration event and idempotent receipt in the same transaction. */
    CreateOptionGroupResultVO createOptionGroup(String entityNo, String storeNo, String actorNo,
            CreateOptionGroupReq req, String idempotencyKey);

    /** Reads an entity-scoped working head with real ordered children, not its published execution revision. */
    OptionGroupResultVO getOptionGroup(String entityNo, String storeNo, String configNo);

    /** Applies ownership before SQL count and pagination, then batch-loads the page's children. */
    PageData<OptionGroupConfigVO> listOptionGroups(String entityNo, String storeNo, ListOptionGroupsQuery query);
}
