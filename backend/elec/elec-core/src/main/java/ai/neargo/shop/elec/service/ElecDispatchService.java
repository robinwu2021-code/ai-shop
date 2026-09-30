package ai.neargo.shop.elec.service;

import ai.neargo.shop.elec.dto.RfqDtos.DeclineReq;
import ai.neargo.shop.elec.dto.RfqDtos.DispatchView;
import ai.neargo.shop.elec.dto.RfqDtos.Offer;
import ai.neargo.shop.elec.dto.RfqDtos.OpsOffer;
import ai.neargo.shop.elec.dto.RfqDtos.OpsQuoteRow;
import ai.neargo.shop.elec.dto.RfqDtos.SupplierQuoteReq;
import ai.neargo.shop.elec.entity.ElcRfq;
import ai.neargo.shop.elec.entity.ElcRfqLine;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 买家发的求购 → 派给有货的供应商 → 供应商报价 → 买家看到（匿名、已加价）。
 *
 * <p>与「平台报价」并存，不是替代：供应商没人响应时，运营照样可以自己报一条兜底。
 *
 * <p><b>匿名在两个方向上都成立</b>：供应商看不到买家是谁（派单只给料号、数量、要求），
 * 买家看不到是谁报的（只有这一行内的代号 A/B/C）。
 */
public interface ElecDispatchService {

    /**
     * 按料号把求购派给<b>库里有这个料号</b>的供应商，并通知他们。
     *
     * <p>在询价提交之后调，<b>不在同一个事务里</b>：派单失败不该让询价回滚 ——
     * 询价已经落库了，运营在企业微信群里照样看得到，大不了手工指派。
     *
     * @return 派了几家（去重后）
     */
    int dispatch(ElcRfq rfq, List<ElcRfqLine> lines);

    /** 运营手工指派：库里没有这个料号、但运营知道谁有 */
    int dispatchTo(String rfqNo, int lineNo, List<String> supplierNos, String staffNo);

    /** 供应商的待报价/已报价列表。@param status 空 = 全部 */
    List<DispatchView> mine(String userNo, String status, int page, int size);

    DispatchView detail(String userNo, String dispatchNo);

    /** 供应商报价（同一条派单再报就是改价） */
    DispatchView quote(String userNo, String dispatchNo, SupplierQuoteReq req);

    /** 供应商拒绝。**拒绝也算响应** —— 不回才伤响应率 */
    DispatchView decline(String userNo, String dispatchNo, DeclineReq req);

    /**
     * 买家能看到的报价：按询价行号分组，已加价、已匿名、按价升序。
     *
     * @param platformQuoted 这些行平台自己报过价（那条排在最前，代号写「平台」）
     * @param platformAccepted 买家接受了平台的整单报价 —— 平台那几条标成已选
     */
    Map<Integer, List<Offer>> offersOf(String rfqNo, List<ElcRfqLine> lines, boolean platformAccepted);

    /**
     * 买家接受某一条报价：锁价、通知供应商、其余同行报价置为未选中（NOT_CHOSEN）。
     * <b>一行只能成交一家</b>：这一行已经选过的回 90011。
     */
    void acceptOffer(String rfqNo, int lineNo, String offerNo);

    /** 这张单里已经选中了供应商报价的行号。平台整单接受前用它判冲突 */
    java.util.Set<Integer> chosenLines(String rfqNo);

    // ── 运营端（真名、原价）────────────────────────────────────────────────

    /** 一张询价单的全部派单及结果，按询价行号分组。与 {@link #offersOf} 是同一件事的平台面 */
    Map<Integer, List<OpsOffer>> opsOffersOf(String rfqNo);

    /** 一批询价单各自有几家回了话（报价或拒绝，去重） */
    Map<String, Integer> respondedCounts(Collection<String> rfqNos);

    /**
     * 报价记录。
     *
     * @param status ACTIVE / EXPIRED / WITHDRAWN / ACCEPTED；空 = 全部
     */
    List<OpsQuoteRow> opsQuotes(String supplierNo, String status, int page, int size);
}
