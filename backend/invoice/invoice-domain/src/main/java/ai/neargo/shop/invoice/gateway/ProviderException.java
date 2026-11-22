package ai.neargo.shop.invoice.gateway;

/** 供应商明确拒绝或不可达。{@link #code} 是供应商原文代码，原样带给用户便于对着供应商文档排查。 */
public class ProviderException extends RuntimeException {

    private final String code;

    public ProviderException(String code, Throwable cause) {
        super("invoice provider rejected: " + code, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
