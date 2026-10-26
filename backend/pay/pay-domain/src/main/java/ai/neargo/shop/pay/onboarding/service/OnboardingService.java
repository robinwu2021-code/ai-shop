package ai.neargo.shop.pay.onboarding.service;

import java.util.List;

/**
 * 进件（TDD-支付服务-I4进件与收款商户 S7；ADR-041）：某门店向某渠道申请收款能力。资料与每次提交是版本；
 * 通过即产出收款商户 {@code pay_merchant} 并绑上门店。
 * <ul>
 *   <li>R04：每次提交一版，外部单号 = 进件单号-V{版本}，每版唯一；</li>
 *   <li>R06：同门店同渠道在途（SUBMITTED / CHANNEL_REVIEW / NEED_SIGN）只有一张，靠 {@code inflight_key} 唯一键兜底并发；</li>
 *   <li>R23：驳回原因留在那一版上，重提后进件单的当前原因清空。</li>
 * </ul>
 * 回填来的 {@code LEGACY-*} 进件单由旧进件流程推进（双写镜像），这里不提交、不回查、不轮询。
 */
public interface OnboardingService {

    /** 建草稿进件单，返回进件单号 */
    String open(Long storeId, String payChannel, String initiatorType, String initiatorNo);

    /** 提交（首提或驳回后重提）：另起一版、占在途键、调渠道 */
    void submit(String onboardingNo, Materials materials, String operatorNo);

    /** 回查渠道：通过 → 产出收款商户并绑门店；驳回 → 原因落在当前版；仍在审 → 不动 */
    void refresh(String onboardingNo);

    /** 轮询在途的进件单（只限支付自己的） */
    PollResult poll(int limit);

    /**
     * 一版资料。结算账号明文只在提交那一刻交给渠道，<b>不落库</b>（密钥模块就绪前只存掩码，见 TDD 偏差）。
     */
    record Materials(String legalForm, String contactName, String contactPhone, List<String> licenses,
                     String settleAccountType, String settleAccount) {
    }

    record PollResult(int scanned, int settled, int failed) {
    }
}
