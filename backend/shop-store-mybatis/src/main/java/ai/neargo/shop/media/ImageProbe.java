package ai.neargo.shop.media;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Set;

/**
 * 「这是一张图吗、它多大」——上传链路上的两个判据。
 *
 * <p><b>为什么单独一个类：这两个判据有两个调用方（B 端上传、C 端头像上传），
 * 而它们必须给出同一个答案。</b> 各自抄一份的话，magic number 那张表会漂 ——
 * 一端补了一种格式另一端没补，而症状是「同一张图在一个入口能传、换个入口说格式不对」，
 * 没人会想到去比两份常量表。
 *
 * <p>这里的两个方法原本是 {@code BizUploadController} 的 private static，
 * 搬过来时一个字节都没改（它们是纯函数，不读配置、不碰上下文）。
 */
public final class ImageProbe {

    /**
     * 允许的后缀。<b>白名单而不是黑名单</b> —— 黑名单要穷举所有危险后缀，
     * 而漏一个就是往可访问目录里放了一个可执行文件。
     */
    public static final Set<String> ALLOWED_EXT = Set.of("jpg", "jpeg", "png", "webp", "gif");

    private ImageProbe() {
    }

    /** 文件名的后缀，小写、不带点；取不到时返回空串。 */
    public static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 || dot == filename.length() - 1
                ? ""
                : filename.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 头几个字节是不是一张图，且与后缀说的是同一种。
     *
     * <p><b>两边都要判</b>：只判「是不是图」的话，{@code .png} 后缀配一个 JPEG 内容仍会通过 ——
     * 存下来 Content-Type 与真实字节不符，浏览器多半仍能显示，但缩略图/转码这类
     * 按 Content-Type 分发的下游会拿到一个它处理不了的东西，而且报错报在离这里很远的地方。
     *
     * <p>为什么必须有这一道：后缀白名单只认文件名，而文件名是客户端说了算的。
     * 实测一段纯文本改名 {@code x.png} 传进来，白名单放行、{@link #dimensionsOf} 读不出尺寸
     * 也不拦（它的职责是「多大」不是「是不是图」），于是任意字节以
     * {@code image/png} 落进<b>公开</b>目录并可公开取回。
     *
     * <p>不引解码库：magic number 就够，且<b>只读前 12 个字节</b>。
     */
    public static boolean looksLikeImage(InputStreamSource source, String ext) {
        byte[] h = new byte[12];
        try (InputStream in = source.open()) {
            if (in.readNBytes(h, 0, 12) < 12) {
                return false;
            }
        } catch (IOException e) {
            return false;
        }
        return switch (ext) {
            // FF D8 FF
            case "jpg", "jpeg" -> (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF;
            // 89 50 4E 47 0D 0A 1A 0A
            case "png" -> (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G'
                    && (h[4] & 0xFF) == 0x0D && (h[5] & 0xFF) == 0x0A
                    && (h[6] & 0xFF) == 0x1A && (h[7] & 0xFF) == 0x0A;
            // "GIF87a" / "GIF89a"
            case "gif" -> h[0] == 'G' && h[1] == 'I' && h[2] == 'F' && h[3] == '8'
                    && (h[4] == '7' || h[4] == '9') && h[5] == 'a';
            // "RIFF"????"WEBP"
            case "webp" -> h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                    && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P';
            default -> false;
        };
    }

    /**
     * 读宽高，取不到返回 {@code {0, 0}}。
     *
     * <p><b>只读文件头，不解码像素</b> —— {@code ImageIO.read} 会把整张图解成
     * BufferedImage，一个 5MB 的 JPEG 可能是 5000×5000，解出来上百 MB，
     * 几个人同时传就能把堆打满。
     *
     * <p>webp 没有内置 reader，取不到就返回 0 —— 记账表那两列本来就允许为空，
     * 运营端少显示一个尺寸，不值得为它引一个解码库。
     *
     * <p><b>读不出尺寸不该让上传失败</b>：这个方法答的是「多大」，
     * 「是不是图」在 {@link #looksLikeImage}。
     */
    public static int[] dimensionsOf(InputStreamSource source) {
        try (InputStream in = source.open();
             ImageInputStream iis = ImageIO.createImageInputStream(in)) {
            if (iis == null) {
                return new int[]{0, 0};
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                return new int[]{0, 0};
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis);
                return new int[]{reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            return new int[]{0, 0};
        }
    }

    /**
     * 每次调用给一条<b>新的</b>流。
     *
     * <p>不收 {@code InputStream} 而收这个：两个判据各要从头读一遍，
     * 而一条流读过就没法回到开头。收 {@code MultipartFile} 也不行 ——
     * 那会让这个类依赖 spring-web，而它住在存储模块里。
     */
    @FunctionalInterface
    public interface InputStreamSource {
        InputStream open() throws IOException;
    }
}
