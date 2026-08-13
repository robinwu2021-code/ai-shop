package ai.neargo.shop.spi.notify;

/**
 * 域 → channel：把一封邮件交给通道。
 *
 * <p><b>消费方是运营端的密码交付</b>（TDD-短信与邮件通道接入 §3.4）：
 * 新建运营账号时把一次性初始密码发给本人，以及「忘记密码」的重置链接。
 * 这两件事今天都靠管理员在界面上看到明文再转告 —— 而管理员本人
 * <b>不该知道另一个人的密码</b>。
 *
 * <p><b>纯文本，不做 HTML 模板</b>：密码与重置链接不需要排版，
 * 而 HTML 邮件会被更多网关判成垃圾邮件。等有了真正需要排版的场景再说。
 *
 * <p><b>失败必须抛</b>：{@link MailException} 会让 {@code createStaff} 整体回滚。
 * 吞掉的话会留下一个「已建号但没人知道密码」的账号 —— 比原来的问题更糟：
 * 它看起来是正常账号，只是永远没人能登进去。
 */
public interface MailPort {

    /**
     * @param to      收件人
     * @param subject 主题
     * @param body    正文（纯文本）。**含密码时实现方不得写进日志**
     * @throws MailException 认证失败、被拒收、网络失败
     */
    SendResult send(String to, String subject, String body);

    /**
     * 带**用途**与操作人的重载。
     *
     * <p><b>为什么 bizType 属于这个契约而不是记录层的私事</b>：发送记录要能把
     * 「验证码」「账号初始密码」「测试发送」分开 —— 混在一起时，看到发送量激增
     * 分不清是有人在刷还是有人在测。而只有调用方知道这一次是为了什么。
     *
     * <p>默认忽略这两个参数，直接走三参版：**桩与真实现都不必关心记录**，
     * 记录由装饰器统一做（见 {@code NotifyLoggingMailPort}）。
     *
     * @param bizType    见 {@code SysNotifyLog} 的 {@code BIZ_*}
     * @param operatorNo 手动触发时是操作人；系统自动发出的传 null
     */
    default SendResult send(String to, String subject, String body,
                            String bizType, String operatorNo) {
        return send(to, subject, body);
    }

    /** 邮件发送失败。 */
    class MailException extends RuntimeException {

        public MailException(String message, Throwable cause) {
            super(message, cause);
        }

        public MailException(String message) {
            super(message);
        }
    }
}
