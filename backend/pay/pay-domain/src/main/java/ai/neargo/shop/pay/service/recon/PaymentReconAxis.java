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
        /*
         * **这条轴只报「发现了什么」，不报「修了多少」**（2026-09-01 改）。
         *
         * 处置（补回支付成功链路 / 关单）搬到了主应用侧
         * （{@code shop-app/paybridge/PaymentReconReconciler}）——
         * 那是订单域的动作，而按「pay 只解决 pay 的核心问题」，
         * 支付域不该反向调订单域。
         *
         * 于是 {@code resolved} 恒为 0，这不是退化而是<b>更诚实</b>：
         * 一条对账轴声称自己「解决了 N 条」，而实际解决动作在另一个进程里，
         * 那个数迟早与事实对不上。处置数由 recon-scan 任务自己报。
         */
        var findings = reconService.checkStalePayments(now);
        int deferred = (int) findings.stream()
                .filter(ReconService.Finding::queryFailed)
                .count();
        return new ScanOutcome(findings.size(), 0, 0, deferred);
    }


    @Override
    public Coverage coverage() {
        ReconService.Coverage c = reconService.coverage();
        return new Coverage(c.channelBillConnected(), c.note());
    }
}
