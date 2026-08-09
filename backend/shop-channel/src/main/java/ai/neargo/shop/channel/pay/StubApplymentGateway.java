package ai.neargo.shop.channel.pay;

import ai.neargo.shop.spi.pay.PayApplymentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * 开发期的假进件网关：提交即记账，查询按规则给结果。
 *
 * <p><b>只在 {@code shop.pay.stub=true} 时装配</b>，与 {@link StubPayGateway} 同一个开关。
 * 默认装配的话，真实现没接上时进件会「成功」而通道根本没开户，
 * 商家第一笔订单才发现收不了钱。
 *
 * <p><b>它不是恒成功的</b>：主体名里带「驳回」两个字就返回 REJECTED。
 * 假网关恒成功的话，驳回分支（拒因回显、补资料重提）在开发期永远走不到，
 * 而那正是进件里最容易写错的一段 —— 真实进件的驳回率远高于订单失败率。
 */
@Component
@ConditionalOnProperty(name = "shop.pay.stub", havingValue = "true")
public class StubApplymentGateway implements PayApplymentGateway {

    private static final Logger log = LoggerFactory.getLogger(StubApplymentGateway.class);

    /** 申请单号 → 提交时的主体名，查询时据此决定给什么结果 */
    private final Map<String, String> submitted = new ConcurrentHashMap<>();

    @Override
    public String payChannel() {
        return "STUB";
    }

    @Override
    public String submit(SubmitCommand cmd) {
        String applyNo = "STUB-APPLY-" + cmd.entityNo();
        submitted.put(applyNo, cmd.entityName() == null ? "" : cmd.entityName());
        // **不打印结算账号** —— 明文账号只在这一次调用里存在，落日志等于落库
        log.info("[stub] 进件提交 entity={} legalForm={} settleType={} apply={}",
                cmd.entityNo(), cmd.legalForm(), cmd.settleAccountType(), applyNo);
        return applyNo;
    }

    @Override
    public ApplymentResult query(String channelApplyNo) {
        String name = submitted.get(channelApplyNo);
        if (name == null) {
            // 查一个不存在的申请单：如实说不知道，不要编一个「审核中」
            return new ApplymentResult("APPLYING", null, null);
        }
        if (name.contains("驳回")) {
            return new ApplymentResult("REJECTED", null, "结算账户与主体名称不一致（stub 模拟）");
        }
        return new ApplymentResult("ACTIVE", "SUB" + Math.abs(channelApplyNo.hashCode()), null);
    }
}
