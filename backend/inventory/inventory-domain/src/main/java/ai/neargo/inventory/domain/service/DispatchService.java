package ai.neargo.inventory.domain.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 出库池（TDD §2.9，M4 T4.2 / T4.3 / T4.7）：交接凭证，不是库存。
 *
 * <p>只有待签收（AVAILABLE）能调整、撤回、签收；签收即交接，之后库存不再跟踪。
 * 成套的从属记录（柜内宝）随主记录（柜）走，单独操作从属记录被拒绝。
 */
public interface DispatchService {

    /** 多值过滤，供宿主套用登录人的数据范围（例：只看自己负责的几个商家）。空 = 不按它过滤。 */
    List<View> query(String ownerId, Filter filter);

    /**
     * 调整收货方（只待签收）：原因必填，留前后快照，从属记录随动（AC-07）。
     *
     * @param targets 新的目标集合，按用途的收货方规则整体校验
     */
    View redirect(Caller caller, String dispatchId, Map<String, String> targets, String reason);

    /**
     * 撤回（只待签收）：池记录标已撤回，同事务生成纠正入库、开新在库周期、恢复余额（AC-08）。
     *
     * @return 纠正入库单号
     */
    String revoke(Caller caller, String dispatchId, String reason);

    /** 签收即领取：整批全成全败；两个签收批次抢同一条，只一方成功（AC-09）。幂等键即批次号的来源。 */
    Claim claim(Caller caller, List<String> dispatchIds, String businessRef);

    /** 按幂等键查签收结果：签收请求成功但回包丢了时用。 */
    Claim claimByRequest(Caller caller, String requestId);

    /** 受控释放：只有原领取系统能释放，记录回到待签收。 */
    void release(Caller caller, String claimNo);

    /** 在途：待签收的数量按收货方顶层目标汇总。 */
    List<InTransit> inTransit(String ownerId);

    record Filter(List<String> statuses, List<String> rootTargetRefs, String targetType, List<String> targetRefs,
                  String outboundNo, String serialNo, String itemId) {
    }

    record View(String dispatchId, String outboundNo, String itemId, String serialNo, BigDecimal qty,
                String locationId, String status, String parentDispatchId, String rootTargetType,
                String rootTargetRef, Map<String, TargetView> targets, int resourceVersion) {
    }

    record TargetView(String ref, String nameSnapshot) {
    }

    record Claim(String claimNo, List<String> dispatchIds) {
    }

    record InTransit(String rootTargetType, String rootTargetRef, String itemId, BigDecimal qty) {
    }
}
