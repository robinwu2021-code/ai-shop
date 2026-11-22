package ai.neargo.shop.invoice.gateway;

/**
 * 供应商明确拒绝或不可达。{@link #code} 是供应商原文代码，原样带给用户便于对着供应商文档排查。
 *
 * <p>TDD-发票-供应商能力定义完善 D4：另带<b>分类</b> {@link Kind}，领域按类处置而不是认厂商码 ——
 * 账号问题暂停该主体、查无当空结果、不可达下轮重试。{@link #detail} 是厂商原文说明（如汎宇的中文 MESSAGE），可空。
 */
public class ProviderException extends RuntimeException {

    public enum Kind {
        /** 账号认证失败、账号停用：重试多少次都一样，暂停该主体等核验 */
        ACCOUNT,
        /** 服务商没有这笔数据（查无、无配号可用） */
        NOT_FOUND,
        /** 这家供应商不支持（期别窗口外、报文种类不收） */
        UNSUPPORTED,
        /** 服务商明确拒绝（业务规则、格式） */
        REJECTED,
        /** 不可达、超时、服务商系统忙：稍后重试 */
        UNAVAILABLE,
        /** 回包看不懂 */
        UNEXPECTED
    }

    private final Kind kind;
    private final String code;
    private final String detail;

    /** 旧写法：未分类（{@link Kind#UNEXPECTED}）。 */
    public ProviderException(String code, Throwable cause) {
        this(Kind.UNEXPECTED, code, null, cause);
    }

    public ProviderException(Kind kind, String code, String detail, Throwable cause) {
        super("invoice provider rejected: " + code + " (" + kind + ")", cause);
        this.kind = kind == null ? Kind.UNEXPECTED : kind;
        this.code = code;
        this.detail = detail;
    }

    public static ProviderException of(Kind kind, String code) {
        return new ProviderException(kind, code, null, null);
    }

    public Kind kind() {
        return kind;
    }

    public String code() {
        return code;
    }

    public String detail() {
        return detail;
    }
}
