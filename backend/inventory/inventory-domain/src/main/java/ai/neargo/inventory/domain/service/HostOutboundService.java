package ai.neargo.inventory.domain.service;

import java.util.List;
import java.util.Map;

/**
 * 宿主出库（TDD §2.9，M3 T3.3 / T3.4）：处置类出库（报废、退供应商、领用…）。
 *
 * <p>用途与收货方规则取自业态档案：必填目标必须给、可选目标须挂在必填目标之下、目标必须是已同步且未停用的；
 * 目标名称在建单时快照。<b>扫上级件即带出下级件</b>（扫柜码带出柜内宝），展开结果逐件记在单据上。
 * 要生成出库池的交接出库（{@code handover = POOL}）在 M4，这里拒绝。
 */
public interface HostOutboundService {

    String create(Caller caller, Draft draft);

    void append(Caller caller, String outboundNo, List<Line> lines, List<String> serialNos);

    void cancel(Caller caller, String outboundNo);

    void post(Caller caller, String outboundNo);

    /**
     * @param targets   目标类型 → 目标编号，如 {@code PERSON → E1001}
     * @param lines     数量物料的行
     * @param serialNos 逐件物料：扫到的 SN；上级件自动带出它当前挂着的下级件
     */
    record Draft(String locationId, String purpose, String reasonCode, Map<String, String> targets, String remark,
                 List<Line> lines, List<String> serialNos) {
    }

    record Line(String itemId, Integer qty) {
    }
}
