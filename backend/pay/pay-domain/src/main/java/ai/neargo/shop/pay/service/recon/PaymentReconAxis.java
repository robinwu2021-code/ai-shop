package ai.neargo.shop.pay.service.recon;

import ai.neargo.shop.pay.service.ReconService;
import org.springframework.stereotype.Component;

/**
 * 收款轴：用户付了钱而我方没收到回调。
 *
 * <p><b>这个类不含任何业务逻辑</b> —— 它把已经在跑的 {@link ReconService#scan}
 * 与 {@link ReconService#coverage} 包成一条轴，好让其余三条轴有地方可放。
 *
 * <p>刻意做成纯委托：这一步（工单 6a）的约定是<b>行为一行不变</b>。
 * 把逻辑顺手搬进来的话，行为变化与结构变化就分不开了 ——
 * 出问题时不知道该回滚哪一个。真正的搬迁等三条新轴都在了、
 * 形状稳定下来之后再做，那时它是一次纯粹的移动。
 */
@Component
public class PaymentReconAxis implements ReconAxis {

    /** 与 {@code stl_recon_diff.axis} 的默认值一致 —— 存量差异行都属于这条轴 */
    public static final String CODE = "PAYMENT";

    private final ReconService reconService;

    public PaymentReconAxis(ReconService reconService) {
        this.reconService = reconService;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public ScanOutcome scan(long now) {
        ReconService.ScanResult r = reconService.scan(now);
        /*
         * 字段对位：`closed`（通道说没有这笔、可以安全关单）算作 resolved ——
         * 它和 `repaired` 一样是**当场收口**，差别只在收口的方向。
         * 而 `deferred`（查询本身失败）单独留着：把它算进任何一边，
         * 都会让「今天有多少条判不了」这个数消失，而那正是要盯的。
         */
        return new ScanOutcome(r.scanned(), r.repaired() + r.closed(), 0, r.deferred());
    }

    @Override
    public Coverage coverage() {
        ReconService.Coverage c = reconService.coverage();
        return new Coverage(c.channelBillConnected(), c.note());
    }
}
