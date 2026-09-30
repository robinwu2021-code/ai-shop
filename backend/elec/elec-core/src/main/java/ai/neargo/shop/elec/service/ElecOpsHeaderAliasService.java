package ai.neargo.shop.elec.service;

import ai.neargo.shop.elec.dto.OpsDtos.HeaderAliasReq;
import ai.neargo.shop.elec.dto.OpsDtos.HeaderAliasRow;
import ai.neargo.shop.elec.dto.OpsDtos.HeaderAliasUpdate;

import java.util.List;

/** 运营端 · 库存表的表头别名 */
public interface ElecOpsHeaderAliasService {

    /** @param scope GLOBAL 全局（种子 + 运营加的）/ LEARNED 各家学到的（按写法聚合） */
    List<HeaderAliasRow> list(String scope, String keyword, int page, int size);

    HeaderAliasRow createGlobal(String actor, HeaderAliasReq req);

    HeaderAliasRow update(String actor, long id, HeaderAliasUpdate req);
}
