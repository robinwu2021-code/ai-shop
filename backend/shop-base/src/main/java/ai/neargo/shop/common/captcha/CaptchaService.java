package ai.neargo.shop.common.captcha;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 图形验证码。
 *
 * <p><b>它保护的是「测试发送」那个接口</b>：那是一个能**指定任意收件人**的入口。
 * 运营账号一旦泄漏（或内部人误用），它就是一台群发机 —— 而且发出去的是
 * 带平台签名的正规短信，比垃圾短信更能骗到人。
 *
 * <p><b>为什么不用短信验证码来保护它</b>：那是循环依赖 ——
 * 保护「发短信」这个动作的手段，本身要先发一条短信。
 *
 * <p><b>为什么不引第三方库</b>：{@code java.awt} 画四个字符加两条干扰线够用了，
 * 而这个仓库在 globals.css 里已经表过态（为三个动画类手写 keyframes，
 * 注释写着「补上真实实现，而不是加一个依赖」）。
 *
 * <p><b>⚠️ 进程内存储</b>：与 {@link ai.neargo.shop.common.ratelimit.InMemoryRateLimiter}
 * 同样的过渡态。运营端 1–2 实例且有会话粘性时够用；多实例无粘性时
 * 表现为「验证码总是错」—— 那时换 Redis，只改这一个类。
 */
@Component
public class CaptchaService {

    /** 2 分钟。够看图、够输入，短到捡到一个也用不上 */
    private static final Duration TTL = Duration.ofMinutes(2);

    /**
     * 去掉了 0/O、1/I/l 这些**看图时分不清**的字符。
     * 留着它们的代价不是不安全，是正常用户反复输错 —— 然后他会觉得是系统坏了。
     */
    private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

    private static final int WIDTH = 120;
    private static final int HEIGHT = 40;
    private static final int LENGTH = 4;
    private static final int MAX_PENDING = 10_000;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> pending = new ConcurrentHashMap<>();

    private record Entry(String code, Instant expireAt) {
    }

    /** @param imageBase64 不带 {@code data:} 前缀，由端上自己拼 */
    public record Challenge(String captchaId, String imageBase64) {
    }

    public Challenge issue() {
        sweep();
        if (pending.size() > MAX_PENDING) {
            // 未消费的挑战会堆积（用户开了页面不提交）。设上限，超了整表清一次
            pending.clear();
        }
        String code = randomCode();
        String id = UUID.randomUUID().toString().replace("-", "");
        pending.put(id, new Entry(code, Instant.now().plus(TTL)));
        return new Challenge(id, Base64.getEncoder().encodeToString(render(code)));
    }

    /**
     * 校验并**消费**。
     *
     * <p><b>无论对错都删掉</b>：留着的话，一次挑战可以被暴力猜 32^4 次 ——
     * 而验证码的全部安全性就建立在「猜的次数有限」上。
     *
     * @throws BizException 验证码错误或已过期
     */
    public void verifyAndConsume(String captchaId, String input) {
        if (captchaId == null || input == null) {
            throw BizException.of(ErrorCode.CAPTCHA_INVALID);
        }
        Entry e = pending.remove(captchaId);
        if (e == null || Instant.now().isAfter(e.expireAt())
                || !e.code().equalsIgnoreCase(input.trim())) {
            throw BizException.of(ErrorCode.CAPTCHA_INVALID);
        }
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private byte[] render(String code) {
        BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0xF5, 0xF5, 0xF5));
            g.fillRect(0, 0, WIDTH, HEIGHT);

            // 干扰线。**不加噪点**：噪点对 OCR 几乎无效，却明显更难认
            g.setStroke(new BasicStroke(1.2f));
            for (int i = 0; i < 3; i++) {
                g.setColor(new Color(random.nextInt(160) + 60, random.nextInt(160) + 60,
                        random.nextInt(160) + 60, 120));
                g.drawLine(random.nextInt(WIDTH), random.nextInt(HEIGHT),
                        random.nextInt(WIDTH), random.nextInt(HEIGHT));
            }

            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
            for (int i = 0; i < code.length(); i++) {
                g.setColor(new Color(random.nextInt(90), random.nextInt(90), random.nextInt(90)));
                // 每个字符各自轻微旋转：整体旋转等于没转
                double angle = (random.nextDouble() - 0.5) * 0.5;
                g.rotate(angle, 20 + i * 25.0, HEIGHT / 2.0);
                g.drawString(String.valueOf(code.charAt(i)), 12 + i * 25, 30);
                g.rotate(-angle, 20 + i * 25.0, HEIGHT / 2.0);
            }
        } finally {
            g.dispose();
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("captcha render failed", e);
        }
    }

    private void sweep() {
        Instant now = Instant.now();
        pending.entrySet().removeIf(e -> now.isAfter(e.getValue().expireAt()));
    }
}
