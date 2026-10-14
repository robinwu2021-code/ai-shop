package ai.neargo.shop.spi.user;

/**
 * user → merchant：这个手机号是不是某个 B 端账号的登录号。
 *
 * <p><b>只为一件事存在</b>：测试号固定验证码白名单那条「拒绝录入已存在账号的手机号」
 * 护栏，必须两个登录面都查。此前只查 {@code usr_identity} 是不够的 ——
 * B 端店主与子账号登录走的是 {@code mch_account.login_phone}
 * （见 {@code MerchantStaffServiceImpl.loginByPhone}），而它与 C 端<b>共用同一个
 * {@code OtpStore}</b>：白名单一旦命中，那个号的固定码在 B 端也照样能用。
 *
 * <p>所以漏掉这一面的后果不是「少查一张表」，而是<b>拿到那个权限码的人可以录入
 * 任意店主的手机号，然后用自己配的码直接登进他的店</b> —— 而白名单最关键的那条护栏
 * 看起来是生效的（C 端那边确实拦住了）。
 *
 * <p>只回一个布尔，不回是哪个账号：调用方要做的判断只有「能不能录」。
 * 多给一个字段就意味着运营端能靠这个接口<b>枚举</b>某个手机号是不是商家，
 * 而那是白名单页面不需要知道的事。
 */
public interface StaffLoginPhonePort {

    /**
     * 这个手机号有没有对应的 B 端账号（<b>含已停用的</b>）。
     *
     * <p>停用的也算「已存在」：停用是可逆的，运营随时能把它改回 ACTIVE，
     * 而白名单那一行会一直留着。只查 ACTIVE 等于留一条「先停用、再录白名单、
     * 再启用」的路 —— 三步都是合法操作，合起来是一次接管。
     */
    boolean isStaffLoginPhone(String phone);
}
