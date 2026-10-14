package ai.neargo.shop.merchant.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.merchant.service.DebtService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台端 · 商家欠款。
 *
 * <p>欠款是 Z4 追偿的第二层：退款追不回来时先记在账上，从后续货款里自动抵扣。
 * 这个页面回答两件事：<b>谁还欠着</b>、以及<b>要不要动他的保证金去抵</b>。
 *
 * <p><b>没有「改欠款金额」这个动作</b>，是有意的：欠款只由退款追偿产生、
 * 由货款或保证金抵扣减少。给一个直接改数的口子，等于允许在没有业务事件的情况下改账，
 * 而之后「这家到底欠多少」就再也说不清了。要免掉就走核销（需审批），那是另一条路。
 */
@Profile("ops")
@RestController
@Validated
public class OpsDebtController {

    private final DebtService debtService;

    public OpsDebtController(DebtService debtService) {
        this.debtService = debtService;
    }

    /** 某商家的欠款余额与流水 */
    @GetMapping("/ops/debts/{entityNo}")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_READ + "')")
    public DebtVO debt(@PathVariable String entityNo) {
        return new DebtVO(entityNo, debtService.balanceOf(entityNo), debtService.txns(entityNo));
    }

    /**
     * 用保证金抵掉一部分欠款。<b>人工动作，不自动</b>。
     *
     * <p>[ADR-022 §3.3]：扣划保证金必须人工 —— 动的是商家的<b>本金</b>，
     * 而未经同意从保证金扣款的合规边界还没定（法务待确认）。
     * 所以它不在追偿的自动链路上，是运营在后台按的一个动作。
     *
     * <p>用 {@code FINANCE_PAYOUT_EXECUTE} 而不是只读那档：
     * 这是一次真的动钱，与「看看谁欠着」不是同一类权限。
     */
    @PostMapping("/ops/debts/{entityNo}/deposit-offset")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_PAYOUT_EXECUTE + "')")
    public DebtVO offsetByDeposit(@PathVariable String entityNo, @RequestBody OffsetReq req) {
        String operator = SecurityUtils.currentUserNo();
        debtService.offsetByDeposit(entityNo, req == null ? 0 : req.amountMinor(),
                operator, req == null ? null : req.reason(),
                req == null ? null : req.requestNo());
        return new DebtVO(entityNo, debtService.balanceOf(entityNo), debtService.txns(entityNo));
    }

    /**
     * @param amountMinor 想抵多少（分）。实际抵扣<b>两头都会封顶</b> ——
     *                    不超过欠款余额，也不超过保证金<b>可用</b>额
     *                    （冻结中的那部分正被别的争议占着）
     */
    /**
     * @param requestNo <b>必填</b>的幂等键，页面上一次点击生成一个。
     *                  这个动作不是自然幂等的：它算的是
     *                  {@code min(欠款, 请求额, 保证金可用)} —— 点第二次时三个数都变小了，
     *                  于是它会接着扣，而每一次单看都「算得对」
     */
    public record OffsetReq(long amountMinor, String reason, String requestNo) {
    }

    public record DebtVO(String entityNo, long balanceMinor, List<DebtService.TxnVO> txns) {
    }
}
