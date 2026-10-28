package ai.neargo.shop.pay.api.guard.terminal;

import ai.neargo.secret.api.KeyVault;
import ai.neargo.shop.pay.access.PayCaller;
import ai.neargo.shop.pay.openapp.SignatureCanonicalizer;
import ai.neargo.shop.pay.terminal.entity.PayTerminal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * POS 终端鉴权（pay-guard TDD §2.2 C；开发计划 M6.2）：终端存在且 {@code ACTIVE} → 时间窗 → 设备密钥验签 → nonce → 调用方圈到
 * 终端绑定的那一家门店。待签串同开放 API（{@link SignatureCanonicalizer}），第 4 行的 AppId 换成终端号。
 *
 * <p>设备密钥不出密钥模块：只交版本引用去算 HMAC（{@link KeyVault#mac}），常量时间比较。挂失吊销后它不在可验签的版本里，
 * 下一次请求即失败。{@link PayCaller} 只在这里（守卫）构造 —— 与开放 API、收银台、内部调用并列的合法构造点。
 */
public interface TerminalAuthService {

    record SignedRequest(String terminalSn, String timestamp, String nonce, String signature, String method,
                         String path, Map<String, List<String>> query, String bodySha256Hex) {
    }

    /** @param failure 只进日志与请求记录，不回给终端 */
    record Result(PayTerminal terminal, Integer keyVersion, String failure, boolean replayed, boolean expired) {

        public static Result ok(PayTerminal t, int version) {
            return new Result(t, version, null, false, false);
        }

        public static Result failed(String why) {
            return new Result(null, null, why, false, false);
        }

        public boolean ok() {
            return terminal != null;
        }
    }

    Result authenticate(SignedRequest r);

    /** 终端的调用方：门店圈到绑定的那一家；收银员取自 {@code X-Operator}（登录后才有） */
    PayCaller callerFor(String terminalSn, String operatorNo);

    /** 响应签名（P2）：用设备密钥当前版本；签不了为空（终端拿不到签名就不认这次「成功」） */
    Optional<String> sign(String terminalSn, byte[] data);
}
