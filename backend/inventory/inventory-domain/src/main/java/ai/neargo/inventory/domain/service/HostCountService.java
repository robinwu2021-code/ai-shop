package ai.neargo.inventory.domain.service;

import java.util.List;

/**
 * 宿主盘点（TDD §2.10，M5 T5.1）：开盘冻结 → 扫码 / 录数 → 提交 → 复核 → 过账（或作废）。
 *
 * <p><b>开盘即冻结</b>：范围内的物料从开盘到过账 / 作废之间不许进出；范围内有活跃预留时拒绝开盘 ——
 * 否则冻结会卡住已付款订单的发货。<b>SN 实盘</b>：可多次、多人扫，同一 SN 重扫不重计；
 * 未知 SN、已出库的 SN 只列为异常，不自动当盘盈。<b>复核</b>：批准绑定内容版本，驳回后再改，旧批准失效。
 */
public interface HostCountService {

    /** @param itemIds 盘点范围；空 = 这个库位上有余额的全部物料 */
    String open(Caller caller, String locationId, List<String> itemIds);

    /** 扫 SN；返回每一件的判定（重扫的照样返回，但不重计）。 */
    List<Scan> scan(Caller caller, String countNo, List<String> serialNos);

    /** 数量物料录实盘数（覆盖式）。 */
    void count(Caller caller, String countNo, String itemId, int countedQty);

    void submit(Caller caller, String countNo);

    void reject(Caller caller, String countNo, String note);

    /** @throws ai.neargo.inventory.api.InventoryException {@code FORBIDDEN} 档案要求复核人不同于提交人而相同 */
    void approve(Caller caller, String countNo, String note);

    /** 过账：盘盈 / 盘亏各生成一张单，解冻。档案要求复核时，只有批准过、且批准后没再改的能过账。 */
    Posted post(Caller caller, String countNo);

    /** 作废：立即解冻。 */
    void cancel(Caller caller, String countNo);

    View view(String ownerId, String countNo);

    record Scan(String serialNo, String verdict) {
    }

    record Posted(String gainInboundNo, String lossOutboundNo) {
    }

    record View(String countNo, String locationId, String status, int contentVersion, Integer approvedVersion,
                String submittedBy, String reviewer, List<Line> lines, List<Scan> exceptions,
                List<String> missingSerials) {
    }

    record Line(String itemId, String trackingMode, int bookQty, Integer countedQty, Integer diffQty) {
    }
}
