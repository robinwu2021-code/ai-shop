package ai.neargo.commerce.api.mall;

/** Stable domain failures; no payment or inventory outcome is inferred from these errors. */
public final class MallException extends RuntimeException {
    private final String code;

    public MallException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
