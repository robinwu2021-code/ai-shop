package ai.neargo.shop.marketing.visit;

import ai.neargo.shop.common.PageData;

/**
 * 门店访问埋点与获客漏斗。
 *
 * <p>漏斗四段「扫码 → 进店 → 注册 → 首单」里，<b>后三段本来就有数据</b>
 * （归因逐条留痕 + 首单回填），只有第一段没有采集 —— 本服务补的就是它，
 * 并把四段合成一处口径。
 *
 * <p><b>口径只此一份</b>：运营端获客看板与平台看板的 funnel 都走这里。
 * 两处各写一份 group by 是「同一个指标两个数」的开始，而两个数都会看起来是对的。
 */
public interface StoreVisitService {

    /**
     * 记一次扫码落地。<b>不要求登录</b>：{@code userNo} 为空就是匿名访客，
     * 而那正是漏斗第一层要测的东西。
     *
     * <p><b>本方法不抛异常</b>。它挂在扫码后的第一屏上，
     * 一次埋点失败绝不能变成「扫码进不去店」—— 失败只记 WARN。
     */
    void record(Visit visit);

    /**
     * 获客漏斗，按主体聚合。
     *
     * @param from 起（毫秒，含）。<b>必填</b> —— 不给区间就是「有史以来」，
     *             那个数只会越来越大且不能用于判断趋势
     * @param to   止（毫秒，含）
     */
    PageData<AcquisitionRow> acquisition(long from, long to, String keyword, long page, long size);

    /** 平台级四段合计（给看板 funnel 用，与上面同一套口径）。 */
    Funnel platformFunnel(long from, long to);

    /**
     * @param userNo   登录用户；<b>为空 = 匿名访客</b>
     * @param deviceId 端上生成并持久化；匿名时它是唯一能按人去重的抓手
     */
    record Visit(String entityNo, String storeCode, String storeNo,
                 String userNo, String deviceId, String ip, String uaHash) {
    }

    /**
     * @param scan       扫码次数（PV）
     * @param scanUv     扫码人数（UV，按 userNo 回落 deviceId 去重）
     * @param enter      进店人数：归因到本店的去重用户数
     * @param register   <b>首次归因人数</b>（{@code decision=CREATED}）。
     *                   <b>不等于「平台新注册」</b> —— 一个老用户第一次扫这家店的码也会计入。
     *                   口径写在这里，免得看板上的数被读成别的意思
     * @param firstOrder 其中已产生首单的人数
     * @param convRate   firstOrder / scanUv；scanUv 为 0 时给 0
     */
    /**
     * @param storeNo   <b>一行一门店</b>（S1）。历史数据没有门店号，已并入该主体的默认店；
     *                  主体连默认店都没有时这里退回主体号本身
     * @param storeName 门店名；<b>null = 查不到</b>，端上显示门店号，别拿主体名冒充店名
     */
    record AcquisitionRow(String merchantNo, String merchantName,
                          String storeNo, String storeName,
                          long scan, long scanUv, long enter, long register,
                          long firstOrder, double convRate) {
    }

    record Funnel(long scan, long scanUv, long enter, long register, long firstOrder) {
    }
}
