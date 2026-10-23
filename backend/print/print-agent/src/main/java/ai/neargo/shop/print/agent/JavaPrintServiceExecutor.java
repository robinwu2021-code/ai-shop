package ai.neargo.shop.print.agent;

import ai.neargo.shop.print.agent.AgentModels.*;
import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintProtocolDtos.*;

import java.awt.*;
import java.awt.print.*;
import java.io.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import javax.print.*;
import javax.print.attribute.*;
import javax.print.attribute.standard.JobName;

public final class JavaPrintServiceExecutor implements DesktopPrintExecutor {
    private final AgentJson json = new AgentJson();
    private final Font bundled;
    private final String fontHash;

    public JavaPrintServiceExecutor() {
        try (var in = getClass().getResourceAsStream("/print/fonts/NotoSansSC-Regular.ttf")) {
            if (in == null) throw new IllegalStateException("AGENT_FONT_MISSING");
            byte[] bytes = in.readAllBytes();
            fontHash = PrintHashes.sha256(bytes);
            bundled = Font.createFont(Font.TRUETYPE_FONT, new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("AGENT_FONT_UNAVAILABLE", e);
        }
    }

    private float pt(BigDecimal mm) {
        return mm.floatValue() * 72f / 25.4f;
    }

    private String queue(LocalDispatch d) {
        return json.tree(d.connection().canonicalJson()).path("config").path("queueName").asText();
    }

    private QueueObservation observation(
            ObservedPrintStatus status, String id, boolean terminated, boolean partial) {
        return new QueueObservation(
                status,
                id,
                terminated,
                partial,
                PrintEvidenceLevel.NONE,
                json.freeze(Map.of("queueTerminated", terminated, "partialOutput", partial)));
    }

    public QueueObservation submit(LocalDispatch d) {
        if (!d.leaseUntil().isAfter(Instant.now())
                || !d.executeBefore().isAfter(Instant.now())
                || !fontHash.equals(d.layout().fontHash()))
            return observation(ObservedPrintStatus.NOT_ACCEPTED, null, true, false);
        var service =
                Arrays.stream(PrintServiceLookup.lookupPrintServices(null, null))
                        .filter(p -> p.getName().equals(queue(d)))
                        .findFirst();
        if (service.isEmpty())
            return observation(ObservedPrintStatus.NOT_ACCEPTED, null, true, false);
        try {
            var job = PrinterJob.getPrinterJob();
            job.setPrintService(service.get());
            var book = new Book();
            for (var page : d.layout().pages()) {
                var paper = new Paper();
                paper.setSize(pt(page.widthMm()), pt(page.heightMm()));
                paper.setImageableArea(0, 0, paper.getWidth(), paper.getHeight());
                var format = new PageFormat();
                format.setPaper(paper);
                book.append(
                        (graphics, pf, index) -> {
                            var g = (Graphics2D) graphics.create();
                            try {
                                g.setColor(Color.BLACK);
                                for (var drawing : page.drawings()) {
                                    if (drawing instanceof PrintDrawing.TextRun t) {
                                        if (!fontHash.equals(t.font().sha256()))
                                            throw new PrinterException("AGENT_FONT_HASH_MISMATCH");
                                        g.setFont(bundled.deriveFont(t.pointSize().floatValue()));
                                        float x = pt(t.xMm()),
                                                y = pt(t.yMm()) + t.pointSize().floatValue();
                                        int n = 0;
                                        for (int cp : t.text().codePoints().toArray()) {
                                            g.drawString(new String(Character.toChars(cp)), x, y);
                                            x += pt(t.advancesMm().get(n++));
                                        }
                                    } else if (drawing instanceof PrintDrawing.RuleLine l) {
                                        g.setStroke(new BasicStroke(pt(l.strokeMm())));
                                        g.draw(
                                                new java.awt.geom.Line2D.Float(
                                                        pt(l.x1Mm()),
                                                        pt(l.y1Mm()),
                                                        pt(l.x2Mm()),
                                                        pt(l.y2Mm())));
                                    } else throw new PrinterException("AGENT_DRAWING_UNSUPPORTED");
                                }
                                return Printable.PAGE_EXISTS;
                            } finally {
                                g.dispose();
                            }
                        },
                        format);
            }
            job.setPageable(book);
            var attributes = new HashPrintRequestAttributeSet();
            attributes.add(new JobName("AI_SHOP:" + d.dispatchKey(), Locale.ROOT));
            if (!d.leaseUntil().isAfter(Instant.now()))
                return observation(ObservedPrintStatus.NOT_ACCEPTED, null, true, false);
            job.print(attributes);
            return observation(ObservedPrintStatus.ACCEPTED, null, false, false);
        } catch (Exception e) {
            return observation(ObservedPrintStatus.UNKNOWN, null, false, true);
        }
    }

    /**
     * Java Print Service does not prove physical output. A disappeared queue item remains UNKNOWN.
     */
    public QueueObservation query(LocalDispatch d) {
        return observation(ObservedPrintStatus.UNKNOWN, d.systemJobId(), false, false);
    }

    /**
     * No queue-wide cancellation: without a trustworthy exact spool ID, require manual
     * verification.
     */
    public QueueObservation cancel(LocalDispatch d) {
        return observation(ObservedPrintStatus.UNKNOWN, d.systemJobId(), false, false);
    }
}
