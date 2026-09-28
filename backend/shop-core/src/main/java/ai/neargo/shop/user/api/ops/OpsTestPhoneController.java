package ai.neargo.shop.user.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.user.service.OtpTestPhoneService;
import ai.neargo.shop.user.service.OtpTestPhoneService.TestPhoneVO;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 运营端 · 测试号固定验证码白名单（P-17.1 系统设置）。
 *
 * <p>白名单里的号请求验证码时不发真实短信，码恒为这一条配的值。唯一用途是苹果审核 ——
 * 审核员在美国，收不到中国短信，而 App 的登录是手机号 + 验证码。
 *
 * <p><b>读与写分成两个码</b>，而且读那个也不并进 {@code system:param:read}：
 * 这一页把固定验证码<b>明文显示</b>出来，能看这一页就等于知道那几个号的登录码。
 * 两个码都不配给任何角色，只有超管的通配能到（见 {@code Perms.SYSTEM_TESTPHONE_UPDATE}）。
 *
 * <p><b>它在 user 域不在 platform</b>，尽管菜单挂在「平台管理 · 系统设置」下面：
 * 表是 {@code usr_otp_test_phone}，读它的是 {@code AuthServiceImpl.sendOtp}，
 * 而最关键那条护栏要查 {@code usr_identity}。放 platform 要开两条跨域依赖，
 * 换来的只是表名前缀好看一点。<b>代码跟着数据走，菜单位置是另一回事</b>
 * （同 {@code OpsBannedWordController} 的口径）。
 *
 * <p>四条护栏与审计都在 {@code OtpTestPhoneServiceImpl} 里，不在这一层 ——
 * 直接打接口的人绕不过 Service，绕得过 Controller 的参数校验。
 */
@Profile("ops")
@RestController
public class OpsTestPhoneController {

    private final OtpTestPhoneService service;

    public OpsTestPhoneController(OtpTestPhoneService service) {
        this.service = service;
    }

    @PreAuthorize("@perm.can('" + Perms.SYSTEM_TESTPHONE_READ + "')")
    @GetMapping("/ops/test-phones")
    public List<TestPhoneVO> list() {
        return service.list();
    }

    /** 录一条或改一条（按手机号认，不按 id） */
    @PreAuthorize("@perm.can('" + Perms.SYSTEM_TESTPHONE_UPDATE + "')")
    @PostMapping("/ops/test-phones")
    public List<TestPhoneVO> save(@RequestBody TestPhoneReq req) {
        return service.save(req.phone(), req.code(), req.remark());
    }

    /** 开 / 关。<b>即刻生效</b> —— 出事要能当场关掉，不用等一次部署 */
    @PreAuthorize("@perm.can('" + Perms.SYSTEM_TESTPHONE_UPDATE + "')")
    @PostMapping("/ops/test-phones/{id}/enabled")
    public List<TestPhoneVO> setEnabled(@PathVariable Long id, @RequestBody EnabledReq req) {
        return service.setEnabled(id, Boolean.TRUE.equals(req.enabled()));
    }

    /*
     * 删走 POST 不走 DELETE：**这个后端里一个 @DeleteMapping 都没有**，
     * 前端的 http 客户端也只有 get / post / put 三个动词。
     *
     * ⚠️ 注释在 `@PreAuthorize` **上面**，不能夹在它与 `@PostMapping` 之间：
     * 端点扫描器按「判权注解紧邻 mapping」认，夹一段注释进去它就认不出判权，
     * 于是这条端点被算成「裸奔」——生成器当场拒跑，而症状与真的忘了判权一模一样。
     */
    @PreAuthorize("@perm.can('" + Perms.SYSTEM_TESTPHONE_UPDATE + "')")
    @PostMapping("/ops/test-phones/{id}/remove")
    public List<TestPhoneVO> remove(@PathVariable Long id) {
        return service.remove(id);
    }

    /**
     * @param phone  11 位大陆手机号
     * @param code   固定验证码，至少 6 位
     * @param remark 这一条为什么存在。不写清楚，半年后没人敢删也没人敢留
     */
    public record TestPhoneReq(String phone, String code, String remark) {
    }

    /** @param enabled true = 启用 */
    public record EnabledReq(Boolean enabled) {
    }
}
