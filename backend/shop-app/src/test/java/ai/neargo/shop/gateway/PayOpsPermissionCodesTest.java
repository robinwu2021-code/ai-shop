package ai.neargo.shop.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.gateway.core.function.ModuleOwnership;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * near-pay 运营端用到的每个权限码都能在 ai-shop 角色里授予（TDD-支付运营端经网关转发与权限下沉 §9）。
 *
 * <p>码表是 near-pay 登记文件的副本；near-pay 侧由 OpsV2PermissionCoverageTest 保证登记文件与接口注解一致。
 * 这里失败 = near-pay 加了新码，ai-shop 还缺常量或功能点。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("支付运营 · near-pay 权限码在 ai-shop 可授予")
public class PayOpsPermissionCodesTest {

    private static final String CODES = "/contracts/near-pay-ops-v2-permission-codes.txt";
    /** near-pay 接入 OpsKit 后审批人用的码（同步自 near-pay docs/api/opskit-approver-permission-codes.txt）。 */
    private static final String APPROVER_CODES = "/contracts/near-pay-opskit-approver-permission-codes.txt";

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("★★★ 每个码都是运营端常量")
    void everyCodeIsAnOperatorConstant() throws IOException {
        Set<String> constants = ModuleOwnership.codes(Realm.OPERATOR);
        assertThat(missing(nearPayCodes(), constants)).as("Perms.java 里缺的码").isEmpty();
    }

    @Test
    @DisplayName("★★★ 每个码都有功能点挂着（角色才勾得到）")
    void everyCodeHasAFunctionPoint() throws IOException {
        Set<String> points = new HashSet<>(jdbc.queryForList(
                "SELECT DISTINCT perm_code FROM sys_function_point WHERE perm_code LIKE 'pay:%'", String.class));
        assertThat(missing(nearPayCodes(), points)).as("没有功能点的码").isEmpty();
    }

    @Test
    @DisplayName("★★★ OpsKit 审批人用的码也可授予：是运营端常量、有功能点")
    void everyApproverCodeIsGrantable() throws IOException {
        Set<String> approver = read(APPROVER_CODES, 5);
        assertThat(missing(approver, ModuleOwnership.codes(Realm.OPERATOR))).as("Perms.java 里缺的审批人码").isEmpty();
        Set<String> points = new HashSet<>(jdbc.queryForList(
                "SELECT DISTINCT perm_code FROM sys_function_point WHERE perm_code LIKE 'pay:%'", String.class));
        assertThat(missing(approver, points)).as("没有功能点的审批人码").isEmpty();
    }

    @Test
    @DisplayName("新补的操作功能点授给超管、不授给其它岗位")
    void newActionPointsOnlyForSuperAdmin() {
        List<String> holders = jdbc.queryForList("""
                SELECT DISTINCT rp.role_code FROM sys_role_point rp
                WHERE rp.point_code IN ('ACT__PAY_PERMISSION_MANAGE', 'ACT__PAY_MERCHANT_REPAIR_APPROVE', 'ACT__PAY_FEE_PLAN_APPROVE')
                """, String.class);
        assertThat(holders).containsOnly("SUPER_ADMIN");
    }

    private static Set<String> missing(Set<String> wanted, Set<String> have) {
        Set<String> out = new TreeSet<>(wanted);
        out.removeAll(have);
        return out;
    }

    public static Set<String> nearPayCodes() throws IOException {
        return read(CODES, 50);
    }

    private static Set<String> read(String file, int atLeast) throws IOException {
        try (InputStream in = PayOpsPermissionCodesTest.class.getResourceAsStream(file)) {
            assertThat(in).as(file).isNotNull();
            Set<String> out = new TreeSet<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String code = line.strip();
                if (!code.isEmpty() && !code.startsWith("#")) {
                    out.add(code);
                }
            }
            assertThat(out).hasSizeGreaterThan(atLeast).allMatch(c -> c.startsWith("pay:"));
            return out;
        }
    }
}
