package ai.neargo.shop.beauty.policy;

/** Exact successor identity shared by startup validation, original recovery and predecessor verifiers. */
public final class EntitlementUseSchemaContract {
    public static final String SCRIPT = "V17__beauty_entitlement_use_binding.sql";
    public static final String MIGRATION = "db/industry/beauty/" + SCRIPT;
    public static final String SHA256 = "160e1f308b08b78dc6fbac8cd6efa9756de3ff622afed6beb1fd52fae45efe5f";
    public static final int CHECKSUM = -257312052;
    private EntitlementUseSchemaContract() {}
}
