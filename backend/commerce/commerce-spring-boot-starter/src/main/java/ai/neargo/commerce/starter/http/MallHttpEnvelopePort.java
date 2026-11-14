package ai.neargo.commerce.starter.http;

public interface MallHttpEnvelopePort {
    record Failure(int status, Object body) {}

    Object success(Object exactWireView);

    Failure failure(String commerceCode);
}
