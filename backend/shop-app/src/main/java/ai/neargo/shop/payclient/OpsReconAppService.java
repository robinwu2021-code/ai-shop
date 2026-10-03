package ai.neargo.shop.payclient;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.pay.service.ReconService;
import java.util.List;

/**
 * 平台端 · 对账差异处置的 app service。
 *
 * <p>两个写动作（处置、忽略）都要留痕：<b>钱的事必须能追到是谁在什么时候下的结论</b>。
 * 「忽略」尤其如此 —— 它认定这不是问题，而下个月再对账时没人记得为什么放过它。
 */
public interface OpsReconAppService {

    /** 差异列表。默认给待处置的 —— 这是个队列，历史是次要视图 */
    PageData<ReconService.ReconDiffVO> diffs(String status, long page, long size);

    /**
     * 四条对账轴的总览。<b>它会真的跑一轮扫描</b>而不是读缓存 ——
     * 对账页是「今天有没有对不上」的入口，读一份过期的结果比不读更坏。
     */
    List<ReconService.AxisReport> axes();

    /** 本列表覆盖到哪些差异。四条轴今天都只有 A 侧（我方自查） */
    ReconService.Coverage coverage();

    /** 已处置。结论必填，且原样留在单据上 */
    ReconService.ReconDiffVO resolve(String diffNo, String resolution);

    /** 忽略：认定不是问题。同样必须写理由 */
    ReconService.ReconDiffVO ignore(String diffNo, String resolution);
}
