package ai.neargo.shop.print.api;

/** Stable domain error key, translated at the application boundary. */
public final class PrintFailure extends RuntimeException {
    private final String key;

    public PrintFailure(String key) {
        super(key);
        this.key = key;
    }

    public String key() {
        return key;
    }
}
