package ai.neargo.shop.geo;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.geom.util.GeometryFixer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * 商家画的配送范围多边形（ADR-034）：解析、校验、含边界的「点在面内」精判、几何指纹。
 *
 * <h2>输入形状</h2>
 * {@code [[lngE6,latE6],[lngE6,latE6],...]}，整数 E6，顶点序 lng 在前 —— 与全库坐标口径一致。
 * 尾点与首点重复会被去掉；连续重复点会被合并。这是我们自己定的形状，不走通用 JSON 库：
 * shop-base 不该为了四个数字一组的数组把 Jackson 绑进来。
 *
 * <h2>为什么用 JTS 不手写射线法</h2>
 * 边界点、凹多边形、自交多边形这三类边界情况手写一次就要错一次；JTS 是 PostGIS/GeoTools 同源的实现。
 * {@link PreparedGeometry} 把同一多边形反复判点的开销压到最低 —— 查询侧正是这个模式。
 * 用 {@code covers} 不用 {@code contains}：<b>边界算在内</b>，站在配送边界上的消费者应当能买。
 *
 * <h2>自交</h2>
 * 商家手画偶有「蝴蝶结」。拒掉体验差，而 {@code buffer(0)} 能把它归一成合法的（多）多边形，
 * 判定仍有定义；归一后仍为空或零面积的才拒 —— 那是三点共线这类真退化。
 */
public final class GeoPolygon {

    private static final GeometryFactory GF = new GeometryFactory();
    private static final double E6 = 1_000_000d;

    private final List<int[]> vertices;        // 去重后的原始顶点 [lngE6, latE6]
    private final Geometry geometry;            // 归一后的 Polygon 或 MultiPolygon
    private final PreparedGeometry prepared;
    private final Envelope bbox;
    private final String normalizedJson;
    private final String fingerprint;

    private GeoPolygon(List<int[]> vertices, Geometry geometry) {
        this.vertices = List.copyOf(vertices);
        this.geometry = geometry;
        this.prepared = PreparedGeometryFactory.prepare(geometry);
        this.bbox = geometry.getEnvelopeInternal();
        this.normalizedJson = toJson(vertices);
        this.fingerprint = sha256Hex32(normalizedJson);
    }

    /**
     * 解析并校验。
     *
     * @param json        顶点 JSON，见类注释
     * @param maxVertices 顶点上限（配置 {@code shop.reach.polygon-max-vertices}）
     * @throws IllegalArgumentException 顶点不足三个 / 超上限 / 坐标越界 / JSON 坏 / 退化为零面积
     */
    public static GeoPolygon parse(String json, int maxVertices) {
        List<int[]> raw = parseVertices(json);
        List<int[]> pts = dedupe(raw);
        if (pts.size() < 3) {
            throw new IllegalArgumentException("多边形至少需要 3 个不重复的顶点，实际 " + pts.size());
        }
        if (pts.size() > maxVertices) {
            throw new IllegalArgumentException("多边形顶点数 " + pts.size() + " 超过上限 " + maxVertices);
        }
        for (int[] p : pts) {
            if (p[0] < -180 * E6 || p[0] > 180 * E6 || p[1] < -90 * E6 || p[1] > 90 * E6) {
                throw new IllegalArgumentException("顶点坐标越界：lngE6=" + p[0] + ", latE6=" + p[1]);
            }
        }
        Coordinate[] ring = new Coordinate[pts.size() + 1];
        for (int i = 0; i < pts.size(); i++) {
            ring[i] = new Coordinate(pts.get(i)[0] / E6, pts.get(i)[1] / E6);
        }
        ring[pts.size()] = ring[0];
        Geometry g;
        try {
            LinearRing shell = GF.createLinearRing(ring);
            Polygon polygon = GF.createPolygon(shell);
            /*
             * 自交用 GeometryFixer 不用 buffer(0)：buffer(0) 按环绕方向取舍，「蝴蝶结」只剩一个三角 ——
             * 等于静默砍掉商家画的一半范围，而他看到的是保存成功。GeometryFixer 把自交环在交点打结、
             * 保留全部封闭面（成 MultiPolygon），判定仍有定义、也不丢面积。
             */
            g = polygon.isValid() ? polygon : GeometryFixer.fix(polygon);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("多边形无法构成有效环：" + e.getMessage(), e);
        }
        if (g == null || g.isEmpty() || g.getArea() <= 0
                || !(g instanceof Polygon || g instanceof MultiPolygon)) {
            throw new IllegalArgumentException("多边形退化为零面积（顶点共线或全部重合）");
        }
        return new GeoPolygon(pts, g);
    }

    /** 点在面内（含边界）。先外接矩形预筛，再 JTS 精判 */
    public boolean covers(int latE6, int lngE6) {
        double x = lngE6 / E6;
        double y = latE6 / E6;
        if (!bbox.covers(x, y)) {
            return false;
        }
        Point p = GF.createPoint(new Coordinate(x, y));
        return prepared.covers(p);
    }

    /** 去重后的原始顶点数（商家画了几个点） */
    public int vertexCount() {
        return vertices.size();
    }

    /** 规范化顶点 JSON：去重、无空格、首尾不重复。库里存的就是它 */
    public String normalizedJson() {
        return normalizedJson;
    }

    /** 几何指纹：规范化 JSON 的 SHA-256 前 32 位十六进制。作 POLYGON 项的 ref_code —— 几何不变则项不变 */
    public String fingerprint() {
        return fingerprint;
    }

    /** 归一后的几何（Polygon 或 MultiPolygon） */
    public Geometry jts() {
        return geometry;
    }

    /** 外接矩形（x=lng, y=lat，单位度） */
    public Envelope bbox() {
        return bbox;
    }

    /** 去重后的顶点副本，[lngE6, latE6] */
    public List<int[]> vertices() {
        List<int[]> out = new ArrayList<>(vertices.size());
        for (int[] v : vertices) {
            out.add(new int[]{v[0], v[1]});
        }
        return out;
    }

    // ── 解析 ──────────────────────────────────────────────────────────────

    /** 严格解析 {@code [[a,b],[c,d],...]}：只接受整数、方括号、逗号与空白 */
    private static List<int[]> parseVertices(String json) {
        if (json == null) {
            throw new IllegalArgumentException("多边形顶点为空");
        }
        String s = json.strip();
        if (s.length() < 2 || s.charAt(0) != '[' || s.charAt(s.length() - 1) != ']') {
            throw new IllegalArgumentException("多边形顶点不是 JSON 数组");
        }
        List<int[]> out = new ArrayList<>();
        int i = 1;
        int n = s.length() - 1;
        while (i < n) {
            i = skipWs(s, i, n);
            if (i >= n) {
                break;
            }
            if (s.charAt(i) == ',') {
                i++;
                continue;
            }
            if (s.charAt(i) != '[') {
                throw new IllegalArgumentException("多边形顶点格式错误（位置 " + i + "）");
            }
            int close = s.indexOf(']', i);
            if (close < 0 || close > n) {
                throw new IllegalArgumentException("多边形顶点缺少闭合括号");
            }
            String[] pair = s.substring(i + 1, close).split(",");
            if (pair.length != 2) {
                throw new IllegalArgumentException("每个顶点必须是 [lngE6,latE6] 两个整数");
            }
            try {
                out.add(new int[]{Integer.parseInt(pair[0].strip()), Integer.parseInt(pair[1].strip())});
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("顶点坐标不是整数 E6：" + s.substring(i, close + 1), e);
            }
            i = close + 1;
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("多边形没有任何顶点");
        }
        return out;
    }

    private static int skipWs(String s, int i, int n) {
        while (i < n && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    /** 合并连续重复点；若尾点等于首点则去掉尾点 */
    private static List<int[]> dedupe(List<int[]> raw) {
        List<int[]> out = new ArrayList<>(raw.size());
        for (int[] p : raw) {
            if (out.isEmpty() || !same(out.get(out.size() - 1), p)) {
                out.add(p);
            }
        }
        while (out.size() > 1 && same(out.get(0), out.get(out.size() - 1))) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    private static boolean same(int[] a, int[] b) {
        return a[0] == b[0] && a[1] == b[1];
    }

    private static String toJson(List<int[]> pts) {
        StringBuilder sb = new StringBuilder(pts.size() * 24 + 2).append('[');
        for (int i = 0; i < pts.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('[').append(pts.get(i)[0]).append(',').append(pts.get(i)[1]).append(']');
        }
        return sb.append(']').toString();
    }

    private static String sha256Hex32(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                sb.append(Character.forDigit((d[i] >> 4) & 0xF, 16)).append(Character.forDigit(d[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
