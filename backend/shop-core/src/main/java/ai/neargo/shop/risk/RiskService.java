package ai.neargo.shop.risk;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.risk.dto.BlacklistVO;
import ai.neargo.shop.risk.dto.RiskEventVO;
import ai.neargo.shop.risk.dto.RiskRuleVO;

import java.util.List;

/**
 * 风控（P-16.2）。**只做「运营看得见 + 能配规则 + 能拉黑」这一层。**
 *
 * <p>这一版不在下单/支付链路上做实时拦截（TDD-运营端风控域 §二 D3）：
 * 一个还没有样本、没有误杀率数据的模型，不该直接握住交易主干的生杀权。
 * {@code autoBlock} 存的是运营的处置意愿，接拦截点时读它。
 */
public interface RiskService {

    /**
     * 风险事件列表（{@code GET /ops/risk-events}）。
     *
     * @param keyword 命中单号 / 主体 / 信号短语
     */
    PageData<RiskEventVO> events(String type, String status, String keyword, long page, long size);

    /**
     * 事件处置。**确认与排除都必须写结论** —— 下次同一主体再命中时，
     * 得知道上次为什么放过。
     */
    RiskEventVO decide(String eventNo, boolean confirmed, String verdict, String operatorNo);

    PageData<BlacklistVO> blacklists(String subjectType, boolean activeOnly, String keyword,
                                     long page, long size);

    /** 拉黑。{@code until} 是 ISO-8601 串，必填且必须在未来。 */
    BlacklistVO addBlacklist(String subjectType, String subject, String reason, String until,
                             String operatorNo);

    /**
     * 被拉黑者提交申诉（{@code POST /mp/risk/appeal}）。
     *
     * <p><b>契约之外、本域自建。</b> 没有它，运营端的「解禁申诉裁决」永远等不到
     * {@code appealStatus=PENDING} —— 那是一条结构上不可达的端点。
     */
    BlacklistVO submitAppeal(String userNo, String reason);

    /** 申诉裁决。接受 = 解除拉黑，**记录保留**（留痕不是删除）。 */
    BlacklistVO decideAppeal(String blackNo, boolean accept, String verdict, String operatorNo);

    /** 拦截规则。**读时自愈**：三条缺哪条补哪条（迁移里的 INSERT 进不了测试库）。 */
    List<RiskRuleVO> rules();

    RiskRuleVO saveRule(String type, int threshold, boolean autoBlock, String operatorNo);
}
