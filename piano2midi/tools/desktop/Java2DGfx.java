import com.hft.piano2midi.core.sheet.Gfx;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.GeneralPath;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;

/** Java2D implementation of the engraver's drawing surface (desktop previews / tests). */
public final class Java2DGfx implements Gfx {
    private final Graphics2D g;
    private final Font music;
    private GeneralPath path;

    public Java2DGfx(Graphics2D g, Font music) {
        this.g = g;
        this.music = music;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    }

    @Override
    public void setColor(int argb) {
        g.setColor(new Color(argb, true));
    }

    @Override
    public void line(float x1, float y1, float x2, float y2, float width) {
        g.setStroke(new BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
        g.draw(new Line2D.Float(x1, y1, x2, y2));
    }

    @Override
    public void rect(float x, float y, float w, float h) {
        g.fill(new Rectangle2D.Float(x, y, w, h));
    }

    @Override
    public void glyph(char c, float x, float y, float size) {
        g.setFont(music.deriveFont(size));
        g.drawString(String.valueOf(c), x, y);
    }

    @Override
    public void text(String s, float x, float y, float size, boolean bold, int align) {
        g.setFont(new Font(Font.SANS_SERIF, bold ? Font.BOLD : Font.PLAIN, 1).deriveFont(size));
        float w = g.getFontMetrics().stringWidth(s);
        if (align == CENTER) x -= w / 2;
        else if (align == RIGHT) x -= w;
        g.drawString(s, x, y);
    }

    @Override
    public void beginPath() {
        path = new GeneralPath();
    }

    @Override
    public void moveTo(float x, float y) {
        path.moveTo(x, y);
    }

    @Override
    public void lineTo(float x, float y) {
        path.lineTo(x, y);
    }

    @Override
    public void cubicTo(float x1, float y1, float x2, float y2, float x3, float y3) {
        path.curveTo(x1, y1, x2, y2, x3, y3);
    }

    @Override
    public void fillPath() {
        path.closePath();
        g.fill(path);
    }
}
