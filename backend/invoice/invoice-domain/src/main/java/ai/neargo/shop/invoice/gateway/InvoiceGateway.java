package ai.neargo.shop.invoice.gateway;

import java.util.Optional;

/**
 * 开票供应商 SPI 的根接口（TDD-发票模块 §19）：台湾加值中心 / Turnkey、阿联酋 Peppol 接入点服务商 …
 * 实现放 invoice-channel，每家一个包，继承所属对接形态的抽象类（{@code channel.base}）。
 *
 * <p>只做协议：把税制生成好的报文送出去，回一句收没收到。不判业务、不改发票状态 —— 状态由
 * {@code InvoiceTransmissionServiceImpl} 按回执推进，同一个报文失败后原样补传。
 *
 * <p>上传之外的动作按能力拆成接口，厂商只实现自己有的：{@link AccountCapability} 核验账号、
 * {@link RangeCapability} 服务商配号段、{@link QueryCapability} 查单。领域经 {@link InvoiceGateways} 按能力取用。
 */
public interface InvoiceGateway {

    /** 与 {@code ivc_regime_profile.gateway_code} 对应。 */
    String code();

    /** 服务哪些税制（地区）、编号与上报方式、要不要商家账号。 */
    ProviderProfile profile();

    GatewayReceipt send(GatewayRequest request);

    /**
     * 不出网：按本供应商的映射规则检查这份报文能否送出（TDD-发票-供应商能力定义完善 D2）。
     * 能送出返回空，否则返回原因。领域在开立 / 折让 / 注销的事务提交前调用，不通过就回滚 —— 号码不占用。
     * 默认不检查（测试桩、文件交换类）。
     */
    default Optional<String> preflight(GatewayRequest request) {
        return Optional.empty();
    }

    /**
     * @param refNo   业务号（发票号 / 折让号），通道可拿来做对方系统里的订单号
     * @param account 主体在服务商那边的账号；只有 {@link ProviderProfile#requiresAccount()} 的供应商会收到非空值
     */
    record GatewayRequest(String regimeCode, String messageType, String refNo, String payloadJson,
            ProviderAccount account) {}

    /**
     * 通道回执。失败分三种，上传任务据此决定下一步（TDD-发票模块 §12）：
     * <ul>
     *   <li>{@link Outcome#RETRYABLE}：对方系统忙 / 网络不通，退避后原样重发</li>
     *   <li>{@link Outcome#ACCOUNT_PROBLEM}：账号认证失败 —— 重发多少次都一样，暂停该主体的上传，等商家核验账号</li>
     *   <li>{@link Outcome#FINAL}：报文被拒（格式、业务规则）—— 不自动重发，标为待人工处理</li>
     * </ul>
     *
     * @param error 失败原因（通道原文，截断到 512 字），成功为空
     */
    record GatewayReceipt(Outcome outcome, String gatewayRef, String error) {

        public enum Outcome { ACCEPTED, RETRYABLE, ACCOUNT_PROBLEM, FINAL }

        public boolean accepted() {
            return outcome == Outcome.ACCEPTED;
        }

        public static GatewayReceipt accepted(String gatewayRef) {
            return new GatewayReceipt(Outcome.ACCEPTED, gatewayRef, null);
        }

        public static GatewayReceipt retryable(String error) {
            return new GatewayReceipt(Outcome.RETRYABLE, null, error);
        }

        public static GatewayReceipt accountProblem(String error) {
            return new GatewayReceipt(Outcome.ACCOUNT_PROBLEM, null, error);
        }

        public static GatewayReceipt rejected(String error) {
            return new GatewayReceipt(Outcome.FINAL, null, error);
        }
    }

    /**
     * 主体在供应商那边的账号，各家统一成「代号 + 密钥标识 + 密钥」三元：
     *
     * <table>
     *   <tr><th>供应商</th><th>accountCode</th><th>keyId</th><th>secret</th></tr>
     *   <tr><td>鲸跃</td><td>officeid</td><td>posno</td><td>posid</td></tr>
     *   <tr><td>绿界 / 蓝新（加密接口）</td><td>MerchantID</td><td>HashKey</td><td>HashIV</td></tr>
     *   <tr><td>签名接口 / Peppol 接入点</td><td>商户号或参与方标识</td><td>API Key / Client ID</td><td>签名密钥 / Client Secret</td></tr>
     * </table>
     *
     * {@code accountCode} 存业务表；{@code keyId} / {@code secret} 只存在 secret 模块，用时现解。
     */
    record ProviderAccount(String accountCode, String keyId, String secret) {
        @Override
        public String toString() {
            return "ProviderAccount[" + accountCode + "]";
        }
    }
}
