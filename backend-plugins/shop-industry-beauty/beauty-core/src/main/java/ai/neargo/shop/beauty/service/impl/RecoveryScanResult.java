package ai.neargo.shop.beauty.service.impl;

/** Counts of claimed originals classified by their persisted recovery outcome. */
public record RecoveryScanResult(int attempted, int completed, int pending, int failed) {
    public String detail() {
        return "attempted=%d completed=%d pending=%d failed=%d".formatted(attempted, completed, pending, failed);
    }
}
