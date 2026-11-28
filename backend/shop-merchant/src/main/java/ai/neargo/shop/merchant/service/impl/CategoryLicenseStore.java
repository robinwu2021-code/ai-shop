package ai.neargo.shop.merchant.service.impl;

import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.config.PlatformJdbcConfiguration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 商家的类目授权码：一个码一行（{@code mch_category_license}）。原先是主体行上的一段 JSON（{@code mch_entity.category_codes}），
 * 新库拆成了行（TDD-平台基础库换表 第 2 步 A）。按 Q2，授权暂不随资质到期联动，{@code expires_at} 留空。
 *
 * <p>「当前有效」= {@link #ACTIVE} 且未删。改授权时撤掉的码不删行，标 {@link #REVOKED} 并记撤销时间与原因，留得住「什么时候被撤的」。
 */
@Component
public class CategoryLicenseStore {

    static final String ACTIVE = "ACTIVE";
    static final String REVOKED = "REVOKED";
    /** 授予来源：入驻审核通过与事后调整都是运营授予 */
    static final String SOURCE_OPS = "OPS";

    private final JdbcTemplate jdbc;

    public CategoryLicenseStore(@Qualifier(PlatformJdbcConfiguration.PLATFORM_JDBC_TEMPLATE) JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 主体当前有效的授权码，按授予先后 */
    public List<String> codes(String entityNo) {
        return jdbc.queryForList("SELECT license_code FROM mch_category_license WHERE entity_no=? AND status=? AND deleted=0"
                + " ORDER BY id", String.class, entityNo, ACTIVE);
    }

    /**
     * 把主体的授权码整体改成 {@code codes}：新增的授予，不在其中的撤销。随调用方的平台事务。
     *
     * @param reason 撤销时记进 revoke_reason
     */
    public void replace(String entityNo, List<String> codes, String reason) {
        Set<String> current = new LinkedHashSet<>(codes(entityNo));
        Set<String> target = new LinkedHashSet<>(codes);
        for (String code : current) {
            if (!target.contains(code)) {
                jdbc.update("UPDATE mch_category_license SET status=?, revoked_at=CURRENT_TIMESTAMP, revoke_reason=?,"
                                + " updated_at=CURRENT_TIMESTAMP WHERE entity_no=? AND license_code=? AND status=? AND deleted=0",
                        REVOKED, reason, entityNo, code, ACTIVE);
            }
        }
        for (String code : target) {
            if (!current.contains(code)) {
                jdbc.update("INSERT INTO mch_category_license(license_no, entity_no, license_code, status, source, granted_at,"
                                + " created_at, updated_at) VALUES(?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                        BizKey.next(BizKey.CATEGORY_LICENSE), entityNo, code, ACTIVE, SOURCE_OPS);
            }
        }
    }

    /** 持有某个授权码的在营主体数（主体未删） */
    public long countEntitiesWith(String code) {
        Long n = jdbc.queryForObject("SELECT COUNT(DISTINCT l.entity_no) FROM mch_category_license l"
                + " JOIN mch_entity e ON e.entity_no=l.entity_no AND e.deleted=0"
                + " WHERE l.license_code=? AND l.status=? AND l.deleted=0", Long.class, code, ACTIVE);
        return n == null ? 0 : n;
    }
}
