package ai.neargo.shop.settle.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.settle.PointsService;
import ai.neargo.shop.settle.dto.PointsVOs.PointsOverviewVO;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端 · 积分资金看板。
 *
 * <p><b>补的是一个「服务早已实现、无人调用」的缺口</b>：
 * {@code PointsService.overview} 一直在那里，而运营端<b>一个积分接口都没有</b> ——
 * 池子对不对得上，只能连数据库看。
 *
 * <p><b>只读，不给写侧。</b>积分池的钱是靠流水推出来的，不是靠人改的 ——
 * 开一个「手工调整余额」的入口，等于允许在没有业务事件的情况下改账，
 * 而那之后恒等式失衡就再也说不清是哪一笔了。要调整就补一笔有类型的流水。
 */
@Profile("ops")
@RestController
@Validated
public class OpsPointsController {

    private final PointsService pointsService;

    public OpsPointsController(PointsService pointsService) {
        this.pointsService = pointsService;
    }

    /**
     * 积分资金总览：流通中的积分、池子余额、本期兑付，以及<b>按通道分的账本</b>。
     *
     * <p>前三个数摆在一起是刻意的 —— 恒等式「流通积分 == 池子里的钱」
     * 分开看的话，失衡要等到有人主动比对才会发现。
     *
     * <p>按通道分账本同样不能省：账面是一个池子，<b>钱实际分散在两个通道账户</b>。
     * 只看总数的话，一个溢一个空时总数仍然是平的。
     *
     * <p>用 {@code FINANCE_SETTLE_READ}：这是一张资金表，读它的是财务，
     * 不是做营销的人。
     */
    @GetMapping("/ops/points/overview")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_READ + "')")
    public PointsOverviewVO overview(@RequestParam(defaultValue = "CN") String market) {
        return pointsService.overview(market);
    }
}
