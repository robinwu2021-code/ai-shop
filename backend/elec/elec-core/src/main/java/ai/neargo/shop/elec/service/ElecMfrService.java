package ai.neargo.shop.elec.service;

import ai.neargo.shop.elec.dto.MfrDtos.AliasResult;
import ai.neargo.shop.elec.dto.MfrDtos.AliasRow;
import ai.neargo.shop.elec.dto.MfrDtos.MfrReq;
import ai.neargo.shop.elec.dto.MfrDtos.MfrRow;
import ai.neargo.shop.elec.dto.MfrDtos.UnknownMfrRow;

import java.util.List;

/**
 * 运营端 · 基础数据：厂牌与别名。
 *
 * <p>「种子给起点，运营端给增长的路」：种子里 60 家厂牌、一百多种写法；供应商写出种子没见过的写法时，
 * 那些库存落到「厂牌不明」，在 {@link #unknown} 里排出来，运营点一下补成别名，库存当场改认过去。
 */
public interface ElecMfrService {

    /** @param q 代码 / 英文名 / 中文名包含；空 = 全部 */
    List<MfrRow> list(String q);

    /**
     * 加厂牌。<b>同时把它自己的代码、英文名、中文名登成别名</b>（已被别家占用的写法跳过）——
     * 不登的话，新厂牌建好了也一行都认不出来。登上的别名会当场改认「厂牌不明」里的库存。
     */
    MfrRow create(String staffNo, MfrReq req);

    /** 改名（代码不能改）。新名字同样登成别名 */
    MfrRow rename(String staffNo, String mfrCode, MfrReq req);

    List<AliasRow> aliases(String mfrCode);

    /**
     * 加一种写法，并<b>当场改认</b>「厂牌不明」里写着这种写法的库存。
     * 同一写法已指向同一家 = 幂等（仍会再改认一次）；已指向别家 = 90014，不静默改指向。
     */
    AliasResult addAlias(String staffNo, String mfrCode, String alias);

    /** 认不出的厂牌写法，按在售库存行数倒序。@param limit 默认 50，最多 200 */
    List<UnknownMfrRow> unknown(int limit);
}
