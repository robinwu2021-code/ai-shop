package ai.neargo.inventory.domain.service;

import java.util.List;

/**
 * 宿主入库（TDD §2.8，M3 T3.1 / T3.2 / T3.4）：用途取自业态档案；要求放行的物料必须带放行凭证；
 * 逐件物料按 SN 开在库周期，并可同时登记组装关系（宝挂在柜下）。
 *
 * <p><b>草稿不占用 SN</b>，校验全部在过账时做、<b>一次列全</b>：缺凭证的件、已在仓的 SN、
 * 物料对不上的 SN 一起报，不让仓管改一件提交一次。过账是一个事务：任何一步失败，
 * 单据、SN、周期、余额、流水、事件全部回滚（AC-01）。
 */
public interface HostInboundService {

    /** 单据上 SN 的上限（按件计，含组装登记的下级件）。 */
    int MAX_SERIALS = 2000;

    String create(Caller caller, Draft draft);

    /** 往草稿里分批追加行：一柜几十块宝，一次请求放不下时用。 */
    void append(Caller caller, String inboundNo, List<Line> lines);

    void cancel(Caller caller, String inboundNo);

    void post(Caller caller, String inboundNo);

    /**
     * @param purpose        档案 inboundPurposes 里的用途；{@code CORRECTION} 只能由撤回动作生成，不能手建
     * @param basis          期初等要求依据的用途必填
     * @param attachmentRefs 要求附件的用途必填
     */
    record Draft(String locationId, String purpose, String sourceRef, String basis, List<String> attachmentRefs,
                 String remark, List<Line> lines) {
    }

    /**
     * 一行。数量物料给 {@code qty}（可带整行的放行凭证）；逐件物料给 {@code serials}，数量由件数决定。
     */
    record Line(String itemId, Integer qty, String gateRef, List<Serial> serials) {
    }

    /** @param parentSerialNo 组装关系：挂在哪个上级件下（同单里的件，或本库位已在仓的件） */
    record Serial(String serialNo, String gateRef, String parentSerialNo) {
    }
}
