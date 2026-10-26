package ai.neargo.shop.commerce.policy;

/** 纯并发策略；持久层必须锁scope并按同一事务保存，不能靠worker lease解除远端阻断。 */
public record CatalogPublicationBarrier(
        long generation, long lastConfirmedGeneration, String activeOperationNo) {
    public enum Result {
        SUBMITTED,
        UNKNOWN,
        SUCCEEDED,
        PARTIAL,
        FAILED,
        CANCELLED
    }

    public CatalogPublicationBarrier {
        if (generation < 0
                || lastConfirmedGeneration < 0
                || lastConfirmedGeneration > generation
                || (activeOperationNo != null && activeOperationNo.isBlank())) {
            throw new IllegalArgumentException("Invalid publication state");
        }
    }

    public static CatalogPublicationBarrier idle() {
        return new CatalogPublicationBarrier(0, 0, null);
    }

    public CatalogPublicationBarrier begin(String operationNo) {
        if (operationNo == null || operationNo.isBlank())
            throw new IllegalArgumentException("Missing operation");
        if (activeOperationNo != null)
            throw new IllegalStateException("Remote publication unresolved");
        return new CatalogPublicationBarrier(
                Math.addExact(generation, 1), lastConfirmedGeneration, operationNo);
    }

    public CatalogPublicationBarrier observe(
            String operationNo,
            long observedGeneration,
            Result result,
            boolean allRemoteWritesTerminal) {
        if (activeOperationNo == null
                || !activeOperationNo.equals(operationNo)
                || generation != observedGeneration) {
            throw new IllegalStateException("Stale publication receipt");
        }
        if (result == null) throw new IllegalArgumentException("Missing result");
        boolean terminal =
                allRemoteWritesTerminal && result != Result.SUBMITTED && result != Result.UNKNOWN;
        long confirmed =
                terminal && result == Result.SUCCEEDED ? generation : lastConfirmedGeneration;
        return new CatalogPublicationBarrier(
                generation, confirmed, terminal ? null : activeOperationNo);
    }
}
