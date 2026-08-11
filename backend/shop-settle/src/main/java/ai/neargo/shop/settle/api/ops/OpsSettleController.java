package ai.neargo.shop.settle.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.settle.SettleService;
import ai.neargo.shop.settle.SettleService.SplitLogVO;
import ai.neargo.shop.settle.dto.SettleBillVO;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端 · 结算单与分账流水（落地清单 P2-9）。
 *
 * <p>补的是运营侧一个完整的盲区：结算单与分账指令一直只存在于库里，
 * <b>运营端没有任何入口</b>。出问题时——某单没分成、某家钱没到——
 * 唯一的办法是让人去查库。
 *
 * <p>两个接口分工明确：结算单说的是「该给多少」，
 * 流水说的是「发了几条指令、成没成、失败在哪」。出问题时要看的是后者。
 *
 * <p>只读。分账的下发与回退有它们自己的触发路径（结算生成、售后退款），
 * 在这里放一个「立即分账」按钮，等于给运营一个绕过状态机的口子。
 */
@Profile("ops")
@RestController
@Validated
public class OpsSettleController {

    private final SettleService settleService;

    public OpsSettleController(SettleService settleService) {
        this.settleService = settleService;
    }

    /**
     * 结算单列表。<b>两条轨道都在这里</b>——运营要回答的是「这家店的钱到哪一步了」，
     * 而那不该因为经营模式不同就分成两个入口去查。
     */
    @GetMapping("/ops/settlements")
    @PreAuthorize("@perm.can('" + Perms.SETTLE_MANAGE + "')")
    public List<SettleBillVO> settlements(@RequestParam(required = false) String status,
                                          @RequestParam(required = false) String merchantNo,
                                          @RequestParam(required = false) String businessMode) {
        return settleService.opsBills(status, merchantNo, businessMode);
    }

    /**
     * 分账指令流水。<b>失败的也给</b>——出问题时要看的恰恰是它们；
     * 只给成功的等于把「为什么这单没分成」的答案藏起来。
     */
    @GetMapping("/ops/split-records")
    @PreAuthorize("@perm.can('" + Perms.SETTLE_MANAGE + "')")
    public List<SplitLogVO> splitRecords(@RequestParam(required = false) String settleNo,
                                         @RequestParam(required = false) String action) {
        return settleService.opsSplitLogs(settleNo, action);
    }
}
