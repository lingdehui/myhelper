package com.example.myhelper.interaction.desktop;

import com.example.myhelper.interaction.desktop.behavior.BehaviorPlan.Prop;
import com.example.myhelper.interaction.desktop.behavior.BehaviorPlan.Style;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JWindow;

/** 使用本地矢量部件组合的会话道具，只在实际图形区域响应命中。 */
final class DesktopProp {
    /** 所有道具图形使用相同的逻辑画布宽度。 */
    static final int WIDTH = 44;
    /** 所有道具图形使用相同的逻辑画布高度。 */
    static final int HEIGHT = 40;
    /** 描边用的通用深色。 */
    private static final Color OUTLINE = new Color(81, 66, 115);
    /** 关注中的道具描边，轮廓和点击区保持不变。 */
    private static final Color EMPHASIS = new Color(84, 44, 165);

    /** 道具外观同时用于本地绘制和下一次空闲输入。 */
    private final Prop appearance;
    /** 用于裁形的实际图形，手柄上的孔也保留透明。 */
    private final Shape silhouette;
    /** 边框外的桌面空间不归道具窗口。 */
    private final JWindow window;
    /** 高亮只改变显示，不移动已摆放道具。 */
    private boolean highlighted;

    DesktopProp(JFrame owner, GraphicsConfiguration screen, long id, Prop appearance, Rectangle bounds) {
        this.appearance = appearance;
        silhouette = outline(appearance.style());
        window = new JWindow(owner, screen);
        try {
            window.setName("desktopProp-" + id);
            window.setFocusableWindowState(false);
            window.setAutoRequestFocus(false);
            window.setAlwaysOnTop(true);
            DesktopBubble.prepareTransparent(window);
            window.setContentPane(new Artwork());
            window.setBounds(bounds);
            window.setShape(silhouette);
            window.setVisible(true);
        } catch (RuntimeException failure) {
            window.dispose();
            throw failure;
        }
    }

    Prop appearance() {
        return appearance;
    }

    Rectangle bounds() {
        return window.getBounds();
    }

    void highlight(boolean next) {
        if (highlighted != next) {
            highlighted = next;
            window.getContentPane().repaint();
        }
    }

    void close() {
        window.dispose();
    }

    static Shape outline(Style style) {
        if (style == Style.CUP) {
            Area shape = new Area(body(style));
            Area handle = new Area(new Ellipse2D.Double(26, 13, 15, 16));
            handle.subtract(new Area(new Ellipse2D.Double(29, 16, 8, 10)));
            shape.add(handle);
            return shape;
        }
        return body(style);
    }

    private static Shape body(Style style) {
        return switch (style) {
            case CUP -> new RoundRectangle2D.Double(3, 8, 28, 27, 10, 10);
            case BOOK -> new RoundRectangle2D.Double(5, 4, 33, 32, 6, 6);
            case STAR -> star();
        };
    }

    private static Shape star() {
        Path2D path = new Path2D.Double();
        path.moveTo(22, 1);
        for (int point = 1; point < 10; point++) {
            double angle = point * Math.PI / 5 - Math.PI / 2;
            int radius = point % 2 == 0 ? 19 : 9;
            path.lineTo(22 + radius * Math.cos(angle), 20 + radius * Math.sin(angle));
        }
        path.closePath();
        return path;
    }

    private void paintPattern(Graphics2D graphics) {
        Shape previous = graphics.getClip();
        graphics.clip(body(appearance.style()));
        graphics.setColor(new Color(255, 255, 255, 135));
        switch (appearance.pattern()) {
            case PLAIN -> { }
            case DOTS -> {
                for (int y = 10; y < HEIGHT; y += 8) {
                    for (int x = 9; x < WIDTH; x += 9) {
                        graphics.fillOval(x, y, 3, 3);
                    }
                }
            }
            case STRIPES -> {
                graphics.setStroke(new BasicStroke(1.5f));
                for (int x = -HEIGHT; x < WIDTH; x += 8) {
                    graphics.drawLine(x, HEIGHT, x + HEIGHT, 0);
                }
            }
        }
        graphics.setClip(previous);
    }

    private final class Artwork extends JComponent {
        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color fill = switch (appearance.color()) {
                    case LAVENDER -> new Color(196, 175, 240);
                    case PINK -> new Color(250, 180, 204);
                    case BLUE -> new Color(158, 210, 248);
                };
                g.setColor(fill);
                g.fill(silhouette);
                paintPattern(g);
                g.setColor(highlighted ? EMPHASIS : OUTLINE);
                // 原生窗裁掉外半边描线，这里描线的内半边仍清晰可见。
                g.setStroke(new BasicStroke(highlighted ? 4f : 2.4f));
                g.draw(silhouette);
                if (appearance.style() == Style.BOOK) {
                    g.setStroke(new BasicStroke(1.3f));
                    g.drawLine(12, 5, 12, 35);
                }
            } finally {
                g.dispose();
            }
        }
    }
}
