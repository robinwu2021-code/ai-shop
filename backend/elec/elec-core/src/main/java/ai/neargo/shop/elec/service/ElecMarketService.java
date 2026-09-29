package ai.neargo.shop.elec.service;

import java.util.Collection;

/**
 * 买家面库存投影（elc_part_market）的维护。<b>唯一一个同时读库存与写投影的地方</b> ——
 * 档位怎么分、参考价怎么算，都只在这里。
 */
public interface ElecMarketService {

    /** 按当前有效库存重算这些料号的投影；没货了就删掉那一行 */
    void refresh(Collection<String> partNos);

    /**
     * 重算已经过期的投影（有库存行到期了）。读路径上调，不需要定时任务。
     *
     * @return 重算了几个料号
     */
    int refreshStale();
}
