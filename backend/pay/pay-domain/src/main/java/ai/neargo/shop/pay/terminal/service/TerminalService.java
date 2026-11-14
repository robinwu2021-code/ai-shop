package ai.neargo.shop.pay.terminal.service;

import ai.neargo.shop.pay.terminal.entity.PayTerminal;
import java.util.List;
import java.util.Optional;

/**
 * POS 终端的登记与生命周期（pay-guard 开发计划 M6.3；代码清单 §4.2 {@code TerminalService}）：登记 → 发一次性激活码 → 终端激活
 * （生成设备密钥，只此一次下发）→ 心跳 → 挂失（吊销密钥，下一次请求即 401）。
 *
 * <p><b>设备密钥的两人控制</b>：生成人记发激活码的运营（{@code updated_by}），启用人记终端本身（{@code TERMINAL:<sn>}）——
 * 一个人在后台、一台设备在门店，两样凑齐才生效；密钥模块照常强制「启用人 ≠ 录入人」。
 */
public interface TerminalService {

    /** 激活结果：设备密钥明文只在这一次响应里，服务端不能再次展示（丢了就挂失重登记） */
    record Activated(String terminalSn, Long storeId, String deviceSecret, String keySerial) {

        @Override
        public String toString() {
            return "Activated[terminalSn=" + terminalSn + ", storeId=" + storeId + ", keySerial=" + keySerial + "]";
        }
    }

    PayTerminal register(String terminalSn, Long storeId, Long payMerchantId, String model, long dailyRefundLimitMinor,
                                String operatorNo);

    /** 一次性激活码：10 分钟、单次、库里只存 SHA-256。返回明文（只此一次） */
    String issueActivationCode(String terminalSn, String operatorNo);

    /**
     * 终端用激活码换设备密钥（守卫不验签的唯一接口）。激活码不对、过期、已用、终端不是待激活 —— 一律同一个 401，
     * 不告诉对方差在哪。
     */
    Activated activate(String code, String deviceFingerprint, String appVersion);

    void heartbeat(Long terminalId, String appVersion, String readerKit);

    /** 挂失：终端置 LOST、设备密钥所有可用版本吊销 —— 下一次签名请求即 401。重新启用要重新登记、重新激活 */
    void reportLost(String terminalSn, String operatorNo, String reason);

    Optional<PayTerminal> find(String terminalSn);

    PayTerminal require(String terminalSn);

    /** @param storeId 空 = 全部门店（运营） */
    List<PayTerminal> list(Long storeId);
}
