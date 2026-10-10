package ai.neargo.shop.promotion.service;

import ai.neargo.shop.promotion.dto.ActivityVOs.ActivityDraft;
import ai.neargo.shop.promotion.dto.ActivityVOs.ActivityVO;
import ai.neargo.shop.promotion.dto.ActivityVOs.ConflictVO;

import java.util.List;

/**
 * 商家活动（P5，新模型 {@code pmt_activity}）。
 *
 * <p><b>建活动时堵住的两件事</b>，都是「上线之后没人能补救」的：
 * <ol>
 *   <li><b>长期活动必须有限量或预算</b>：没有结束时间又没有上限 = 永久敞口。
 *       商家建的时候想的是「一直有这个优惠」，而不是「无论花多少」。</li>
 *   <li><b>改单价与送商品必须有限量</b>：这两种的单次成本由商品决定，
 *       不设上限时敞口随销量走 —— 卖得越好亏得越多，而那正是最难叫停的时刻。</li>
 * </ol>
 */
public interface ActivityService {

    List<ActivityVO> list(String entityNo, boolean includeEnded);

    ActivityVO detail(String entityNo, String activityNo);

    ActivityVO save(String entityNo, ActivityDraft draft, String operatorNo);

    /**
     * 启停。<b>只允许 {@code RUNNING} ⇄ {@code PAUSED}，以及置 {@code ENDED}</b> ——
     * 已结束的不能复活：时段已过、限量已用，打开只会立刻又结束一次，
     * 而 {@code ended_reason} 会被覆盖成新的，商家再也查不到当初为什么停。
     */
    ActivityVO setStatus(String entityNo, String activityNo, String status);

    /**
     * 这些商品已经在哪些活动里 —— <b>建活动时的冲突提示</b>。
     *
     * <p>不阻止（同类取最优是既定口径），但要在保存前说出来：
     * 商家建第二个特价活动时，多半是忘了第一个还在跑。
     */
    List<ConflictVO> conflicts(String entityNo, List<String> goodsNos);
}
