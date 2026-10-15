package ai.neargo.shop.scenario;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

/** 只在虚构测试库配置支付前的真实账户事实，测试后恢复原门店配置和原进件行。 */
final class VerifiedPaymentAccountFixture implements AutoCloseable {
    private static final String ACCOUNT = "M7-F07-TEST-MCH";
    private final JdbcTemplate jdbc;
    private final String storeNo;
    private final String oldStoreAccount;
    private final Map<String, Object> oldProfile;

    VerifiedPaymentAccountFixture(JdbcTemplate jdbc, String storeNo) {
        this.jdbc = jdbc;
        this.storeNo = storeNo;
        oldStoreAccount = jdbc.queryForObject("select pay_merchant_no from mch_store where store_no=?",
                String.class, storeNo);
        List<Map<String, Object>> prior = jdbc.queryForList("select * from mch_payment_merchant"
                + " where entity_no='M0001' and pay_channel='TEST' and store_no=?", storeNo);
        oldProfile = prior.isEmpty() ? null : prior.getFirst();
        if (oldProfile == null) {
            jdbc.update("insert into mch_payment_merchant(entity_no,pay_merchant_no,pay_channel,store_no,"
                    + "apply_status,fee_bearer,tenant_no,created_at,updated_at,version,deleted)"
                    + " values('M0001',?,'TEST',?,'ACTIVE','PLATFORM','MAIN',"
                    + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,0)", ACCOUNT, storeNo);
        } else {
            jdbc.update("update mch_payment_merchant set pay_merchant_no=?,apply_status='ACTIVE',"
                    + "fee_bearer='PLATFORM',deleted=0 where id=?", ACCOUNT, oldProfile.get("id"));
        }
        jdbc.update("update mch_store set pay_merchant_no=? where store_no=?", ACCOUNT, storeNo);
    }

    @Override
    public void close() {
        jdbc.update("update mch_store set pay_merchant_no=? where store_no=?", oldStoreAccount, storeNo);
        if (oldProfile == null) {
            jdbc.update("delete from mch_payment_merchant where pay_merchant_no=?", ACCOUNT);
        } else {
            jdbc.update("update mch_payment_merchant set pay_merchant_no=?,apply_status=?,fee_bearer=?,deleted=?"
                    + " where id=?", oldProfile.get("pay_merchant_no"), oldProfile.get("apply_status"),
                    oldProfile.get("fee_bearer"), oldProfile.get("deleted"), oldProfile.get("id"));
        }
    }
}
