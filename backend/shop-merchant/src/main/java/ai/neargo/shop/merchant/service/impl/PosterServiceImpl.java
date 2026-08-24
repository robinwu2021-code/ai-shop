package ai.neargo.shop.merchant.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.merchant.service.PosterService;
import ai.neargo.shop.merchant.service.StoreCodeService;
import ai.neargo.shop.spi.product.GoodsQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 分享海报的 Java2D 实现。
 *
 * <p><b>字体必须从 classpath 加载，不能信任服务器装没装中文字体</b>：生产是精简 Linux 镜像，
 * JVM 找不到 CJK 字体时 {@code Graphics2D} 画中文会是一串方块——本机能跑、上线一片豆腐块，
 * 且没有任何异常或日志能提示这件事。字体文件（Noto Sans SC，SIL OFL 许可，与 site/ 那份同源）
 * 打进了 {@code resources/fonts/}，启动时加载一次、常驻内存复用。
 */
@Service
public class PosterServiceImpl implements PosterService {

    private static final Logger LOG = Logger.getLogger(PosterServiceImpl.class.getName());

    private static final int W = 750;
    private static final int H = 1200;
    private static final int COVER_H = 600;
    private static final int PAD = 48;

    private static final Color BRAND = new Color(0xE1, 0x25, 0x1B);
    private static final Color BRAND_TINT = new Color(0xFD, 0xEC, 0xEA);
    private static final Color INK = new Color(0x17, 0x18, 0x1A);
    private static final Color SUB = new Color(0x63, 0x67, 0x6E);
    private static final Color SURFACE = Color.WHITE;
    private static final Color LINE = new Color(0xE4, 0xE5, 0xE8);

    private final Font titleFont;
    private final Font bodyFont;

    private final MchEntityMapper merchantMapper;
    private final GoodsQueryPort goodsQueryPort;
    private final StoreCodeService storeCodeService;

    public PosterServiceImpl(MchEntityMapper merchantMapper, GoodsQueryPort goodsQueryPort,
                             StoreCodeService storeCodeService) {
        this.merchantMapper = merchantMapper;
        this.goodsQueryPort = goodsQueryPort;
        this.storeCodeService = storeCodeService;
        this.titleFont = loadFont("/fonts/NotoSansSC-Bold.otf");
        this.bodyFont = loadFont("/fonts/NotoSansSC-Regular.otf");
    }

    private static Font loadFont(String classpath) {
        try (InputStream in = PosterServiceImpl.class.getResourceAsStream(classpath)) {
            if (in == null) {
                throw new IllegalStateException("字体资源缺失：" + classpath);
            }
            return Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (FontFormatException | IOException e) {
            // 字体加载失败不该让整个应用起不来（海报是锦上添花的功能），
            // 但必须响亮地报出来——否则症状会拖到「商家反馈海报字是方块」才被发现
            throw new IllegalStateException("海报字体加载失败：" + classpath, e);
        }
    }

    @Override
    public byte[] render(String merchantNo, String goodsNo) {
        MchEntity m = DataScopeContext.executeWithoutScope(() ->
                merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                        .eq(MchEntity::getEntityNo, merchantNo).last("limit 1")));
        if (m == null) {
            return null;
        }
        GoodsQueryPort.SkuSnapshot sku = (goodsNo == null || goodsNo.isBlank())
                ? null
                : goodsQueryPort.snapshotOfGoods(goodsNo).orElse(null);

        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            g.setColor(SURFACE);
            g.fillRect(0, 0, W, H);

            drawCover(g, sku != null ? sku.cover() : null, m.getName());
            drawCard(g, m, sku);
        } finally {
            g.dispose();
        }

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("海报编码失败", e);
        }
    }

    /** 上半部：商品封面图（center-crop 铺满）；没有封面（店铺海报/下载失败）时用品牌色块兜底 */
    private void drawCover(Graphics2D g, String coverUrl, String fallbackText) {
        BufferedImage cover = coverUrl == null || coverUrl.isBlank() ? null : downloadImage(coverUrl);
        if (cover == null) {
            g.setColor(BRAND_TINT);
            g.fillRect(0, 0, W, COVER_H);
            g.setFont(titleFont.deriveFont(56f));
            g.setColor(BRAND);
            drawCentered(g, fallbackText, W / 2f, COVER_H / 2f);
            return;
        }
        // center-crop：源图宽高比与画布不同时，裁掉多出来的那一维，不拉伸变形
        double scale = Math.max((double) W / cover.getWidth(), (double) COVER_H / cover.getHeight());
        int sw = (int) Math.round(cover.getWidth() * scale);
        int sh = (int) Math.round(cover.getHeight() * scale);
        int sx = (W - sw) / 2;
        int sy = (COVER_H - sh) / 2;
        g.drawImage(cover, sx, sy, sw, sh, null);
    }

    /** 下半部白卡：店名 + 标题/价格（单品）或一句招牌语（整店）+ 小程序码 */
    private void drawCard(Graphics2D g, MchEntity m, GoodsQueryPort.SkuSnapshot sku) {
        int y = COVER_H + PAD;

        g.setFont(bodyFont.deriveFont(24f));
        g.setColor(SUB);
        g.drawString(clip(g, m.getName(), W - PAD * 2), PAD, y + 24);
        y += 56;

        if (sku != null) {
            g.setFont(titleFont.deriveFont(40f));
            g.setColor(INK);
            List<String> lines = wrap(g, sku.title(), W - PAD * 2, 2);
            for (String line : lines) {
                g.drawString(line, PAD, y + 40);
                y += 56;
            }
            g.setFont(titleFont.deriveFont(44f));
            g.setColor(BRAND);
            g.drawString(String.format("¥%.2f", sku.price() / 100.0), PAD, y + 44);
            y += 80;
        } else {
            g.setFont(titleFont.deriveFont(40f));
            g.setColor(INK);
            g.drawString(clip(g, "街坊邻居下单，楼下自提", W - PAD * 2), PAD, y + 40);
            y += 76;
        }

        g.setFont(bodyFont.deriveFont(24f));
        g.setColor(SUB);
        g.drawString("长按识别小程序码，进店逛逛", PAD, y + 24);

        drawQr(g, m.getEntityNo());
    }

    /** 小程序码贴右下角。通道未开、或这个商家还没生成过码时，画一个说明性占位块而不是留白——
     *  空白区域容易被当成渲染坏了，写一句「稍后再来」至少说清楚这不是 bug */
    private void drawQr(Graphics2D g, String merchantNo) {
        int size = 220;
        int x = W - PAD - size;
        int qy = H - PAD - size;
        String b64 = safeAcode(merchantNo);
        if (b64 == null) {
            g.setColor(new Color(0xF3, 0xF4, 0xF6));
            g.fill(new RoundRectangle2D.Float(x, qy, size, size, 16, 16));
            g.setFont(bodyFont.deriveFont(22f));
            g.setColor(SUB);
            drawCentered(g, "小程序码", x + size / 2f, qy + size / 2f - 12);
            drawCentered(g, "生成中", x + size / 2f, qy + size / 2f + 16);
            return;
        }
        try {
            BufferedImage qr = ImageIO.read(new java.io.ByteArrayInputStream(Base64.getDecoder().decode(b64)));
            if (qr != null) {
                g.setColor(LINE);
                g.drawRect(x - 1, qy - 1, size + 1, size + 1);
                g.drawImage(qr, x, qy, size, size, null);
            }
        } catch (IOException | IllegalArgumentException e) {
            LOG.log(Level.WARNING, "海报小程序码解码失败：" + merchantNo, e);
        }
    }

    private String safeAcode(String merchantNo) {
        try {
            return storeCodeService.acodeBase64(merchantNo);
        } catch (RuntimeException e) {
            // 码生成失败不该让整张海报也生成不出来——降级成占位块，海报其余部分照常
            LOG.log(Level.WARNING, "海报取小程序码失败：" + merchantNo, e);
            return null;
        }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    /** 下载商品封面图。**失败静默返回 null**——图裂了/超时不该让整个海报接口跟着报错 */
    private BufferedImage downloadImage(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                return null;
            }
            return ImageIO.read(new java.io.ByteArrayInputStream(resp.body()));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "海报封面图下载失败：" + url, e);
            return null;
        }
    }

    // ---------------------------------------------------------------- 文字排版

    private static void drawCentered(Graphics2D g, String text, float cx, float cy) {
        var fm = g.getFontMetrics();
        int w = fm.stringWidth(text);
        g.drawString(text, cx - w / 2f, cy + fm.getAscent() / 2f - fm.getDescent() / 2f);
    }

    /** 超宽就截断加省略号，不换行——单行场景（店名、招牌语） */
    private static String clip(Graphics2D g, String text, int maxWidth) {
        var fm = g.getFontMetrics();
        if (fm.stringWidth(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "…";
        int lo = 0, hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (fm.stringWidth(text.substring(0, mid) + ellipsis) <= maxWidth) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return text.substring(0, lo) + ellipsis;
    }

    /** 按像素宽度换行，最多 maxLines 行，超出的最后一行截断加省略号（商品标题场景） */
    private static List<String> wrap(Graphics2D g, String text, int maxWidth, int maxLines) {
        var fm = g.getFontMetrics();
        List<String> lines = new ArrayList<>();
        int i = 0;
        while (i < text.length() && lines.size() < maxLines) {
            int lo = i + 1, hi = text.length();
            while (lo < hi) {
                int mid = (lo + hi + 1) / 2;
                if (fm.stringWidth(text.substring(i, mid)) <= maxWidth) {
                    lo = mid;
                } else {
                    hi = mid - 1;
                }
            }
            boolean isLast = lines.size() == maxLines - 1;
            if (isLast && lo < text.length()) {
                lines.add(clip(g, text.substring(i), maxWidth));
                return lines;
            }
            lines.add(text.substring(i, lo));
            i = lo;
        }
        return lines;
    }
}
