package ai.neargo.shop.elec.service;

import ai.neargo.shop.elec.dto.RfqDtos.CloseReq;
import ai.neargo.shop.elec.dto.RfqDtos.OpsRfqView;
import ai.neargo.shop.elec.dto.RfqDtos.QuoteReq;
import ai.neargo.shop.elec.dto.RfqDtos.RfqReq;
import ai.neargo.shop.elec.dto.RfqDtos.RfqView;

import java.util.List;

/**
 * 询价。第一步<b>以平台为准</b>：买家提交 → 企业微信群收到 → 平台报价 → 微信通知买家 → 买家接受 → 平台线下成交。
 *
 * <pre>
 * SUBMITTED ──报价──► QUOTED ──接受──► ACCEPTED ──成交──► CLOSED
 *     └────────关单（无货等）────────────────────────────► CLOSED
 * QUOTED 过了有效期 → 对外显示 EXPIRED（不落库）
 * </pre>
 */
public interface ElecRfqService {

    RfqView submit(String userNo, RfqReq req);

    List<RfqView> mine(String userNo, int page, int size);

    RfqView detail(String userNo, String rfqNo);

    /** 买家接受平台报价（整单）。只在 QUOTED 且未过期时可以 */
    RfqView accept(String userNo, String rfqNo);

    /** 买家选中某一行的某一条供应商报价 */
    RfqView acceptOffer(String userNo, String rfqNo, int lineNo, String offerNo);

    // ── 运营端 ──

    /** @param status SUBMITTED / QUOTED / ACCEPTED / CLOSED；空 = 全部 */
    List<OpsRfqView> opsList(String status, int page, int size);

    /** 带每行「库里谁有货」 */
    OpsRfqView opsDetail(String rfqNo);

    /** 录入（或改）报价，写完通知买家。SUBMITTED 与 QUOTED（改价）可以 */
    OpsRfqView quote(String staffNo, String rfqNo, QuoteReq req);

    /** 关单。原因是「暂无货源」时也通知买家 */
    OpsRfqView close(String staffNo, String rfqNo, CloseReq req);
}
