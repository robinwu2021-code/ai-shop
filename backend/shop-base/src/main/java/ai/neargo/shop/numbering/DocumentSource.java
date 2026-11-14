package ai.neargo.shop.numbering;

/** Explicit source namespace, including legacy resource namespaces that may share identifiers. */
public record DocumentSource(String tenantNo, String sourceSystem, String resourceType, String sourceNo) {
    public DocumentSource {
        text(tenantNo,64); text(sourceSystem,32); text(resourceType,64); text(sourceNo,128);
    }
    private static void text(String value,int max) {
        if(value==null || value.isBlank() || value.length()>max || !value.equals(value.trim()))
            throw new IllegalArgumentException("Invalid document source");
    }
}
