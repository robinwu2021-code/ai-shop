package ai.neargo.shop.portal.ops.pay;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.settle.SettleService;
import ai.neargo.shop.settle.SettleService.SplitLogVO;
import ai.neargo.shop.settle.dto.SettleBillVO;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final ai.neargo.shop.settle.SettleBatchService batchService;

    public OpsSettleController(SettleService settleService,
                               ai.neargo.shop.settle.SettleBatchService batchService) {
        this.settleService = settleService;
        this.batchService = batchService;
    }

    /**
     * 账期批次列表。<b>「这家的钱卡在哪一批」的入口。</b>
     *
     * <p>资源段用<b>复数</b>（/ops 的约定）。
     *
     * @param status 空 = 全部；{@code BLOCKED} 就是待处置队列
     */
    @GetMapping("/ops/settle-batches")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_READ + "')")
    public java.util.List<ai.neargo.shop.settle.SettleBatchService.BatchVO> batches(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String entityNo) {
        return batchService.opsBatches(status, entityNo);
    }

    /**
     * 人工放行一批。<b>必须写原因</b> —— 事后要能回答「当时凭什么放的」。
     *
     * <p>与超时自动放行（{@code decided_by = SYSTEM_TIMEOUT}）分开统计：
     * 那个数持续大于零，说明挂起时限比运营的处置能力短，要调的是时限不是任务。
     */
    @PostMapping("/ops/settle-batches/{batchNo}/release")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_EXECUTE + "')")
    public ai.neargo.shop.settle.SettleBatchService.BatchVO releaseBatch(
            @PathVariable String batchNo, @RequestBody DecideReq req) {
        return batchService.release(batchNo, ai.neargo.shop.auth.SecurityUtils.currentUserNo(),
                req == null ? null : req.remark());
    }

    /** 继续挂起。同样必须写原因 */
    @PostMapping("/ops/settle-batches/{batchNo}/hold")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_EXECUTE + "')")
    public ai.neargo.shop.settle.SettleBatchService.BatchVO holdBatch(
            @PathVariable String batchNo, @RequestBody DecideReq req) {
        return batchService.hold(batchNo, ai.neargo.shop.auth.SecurityUtils.currentUserNo(),
                req == null ? null : req.remark());
    }

    /** @param remark 放行或继续挂起的原因，<b>必填</b> */
    public record DecideReq(String remark) {
    }

    /**
     * 结算单列表。<b>两条轨道都在这里</b>——运营要回答的是「这家店的钱到哪一步了」，
     * 而那不该因为经营模式不同就分成两个入口去查。
     */
    @GetMapping("/ops/settlements")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_READ + "')")
    public ai.neargo.shop.common.PageData<SettleBillVO> settlements(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String merchantNo,
            @RequestParam(required = false) String businessMode,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        // 运营端列表页按 {records,total} 渲染 —— 返回裸数组的话它取不到 records，
        // 页面显示「暂无数据」，而接口是 200、控制台一条错误都没有
        return ai.neargo.shop.common.PageData.ofAll(
                settleService.opsBills(status, merchantNo, businessMode), page, size);
    }

    /**
     * 分账指令流水。<b>失败的也给</b>——出问题时要看的恰恰是它们；
     * 只给成功的等于把「为什么这单没分成」的答案藏起来。
     */
    @GetMapping("/ops/split-records")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_READ + "')")
    public ai.neargo.shop.common.PageData<SplitLogVO> splitRecords(
            @RequestParam(required = false) String settleNo,
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return ai.neargo.shop.common.PageData.ofAll(
                settleService.opsSplitLogs(settleNo, action), page, size);
    }
}
