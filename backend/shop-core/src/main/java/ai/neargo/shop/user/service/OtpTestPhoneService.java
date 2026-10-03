package ai.neargo.shop.user.service;

import java.util.List;
import java.util.Optional;

/**
 * 测试号固定验证码白名单（TDD-测试号固定验证码）。
 *
 * <p>白名单里的号请求验证码时<b>不发真实短信</b>，码恒为这一条配的值。
 * 唯一的用途是苹果审核：审核员在美国，收不到中国短信，而登录是手机号 + 验证码。
 *
 * <h2>风险模型：这里的每一行都是一把钥匙</h2>
 *
 * 配置版（{@code shop.auth.otp.fixed}）的口子只有能上服务器的人能开。改成运营端可录之后，
 * <b>任何拿到那个权限码的人都能给任意手机号发一个自己知道的验证码</b> —— 那等于一键登进别人的店。
 * 所以护栏落在这一层，而不是在 Controller 的参数校验里：
 *
 * <ol>
 *   <li><b>拒绝录入已存在账号的手机号</b> —— 最关键的一条。演示账号的用法是
 *       「先录白名单 → 再注册」，录的时候那个号不存在；而要拿别人的店，那个号一定已经存在。
 *       这条直接掐掉「登进已有账号」这个用法，拿到权限码也没用。</li>
 *   <li>启用中的条目有上限 —— 防止它长成一个通用后门。</li>
 *   <li>码长下限 6 位（与 {@code PWD_MIN_LEN} 同档），挡住「1234」。</li>
 *   <li>增 / 改 / 删 / 启停都写审计，{@code critical=true}。</li>
 * </ol>
 *
 * <p><b>为什么在 user 域而不是 platform</b>：读它的是 {@code AuthServiceImpl.sendOtp}，
 * 而第 1 条护栏要查 {@code usr_identity}。放 platform 的话这两头都得开 SPI Port，
 * 换来的只是表名前缀好看一点。菜单挂在「系统设置」下是另一回事 ——
 * 代码跟着数据走（同 {@code OpsBannedWordController} 的口径）。
 */
public interface OtpTestPhoneService {

    /**
     * 这个号的固定验证码；不在白名单、或那一条停用了则 {@link Optional#empty()}。
     *
     * <p><b>每次发码都会调它</b>，所以实现里带整表缓存 —— 逐次查库等于给每一次
     * 「获取验证码」都加一趟往返。写口改完当场失效，见 {@link #invalidate()}。
     */
    Optional<String> fixedCodeFor(String phone);

    /** 整张表（含停用的）。运营端列表页要看得见「停用」那些，否则没法解释为什么不生效 */
    List<TestPhoneVO> list();

    /**
     * 录一条，或改一条已有的（按手机号认，不按 id）。
     *
     * <p>按手机号认是有意的：这张表的唯一键就是手机号，让「再录一次同一个号」
     * 变成改而不是撞唯一键报 500。
     */
    List<TestPhoneVO> save(String phone, String code, String remark);

    /** 开 / 关。<b>即刻生效</b>，不等缓存过期、不等重启 —— 出事要能当场关掉 */
    List<TestPhoneVO> setEnabled(Long id, boolean enabled);

    /** 删。<b>物理删</b>，理由见 {@code OtpTestPhoneMapper} */
    List<TestPhoneVO> remove(Long id);

    /** 改完白名单叫它，同一实例下一次发码即新名单 */
    void invalidate();

    /**
     * @param code 固定验证码。<b>明文返回给运营端</b>：不给看的话，
     *             运营就没法把它填进 App Store Connect 的审核资料里 ——
     *             而这张表的全部意义就是那份资料。
     */
    record TestPhoneVO(Long id, String phone, String code, boolean enabled, String remark) {
    }
}
