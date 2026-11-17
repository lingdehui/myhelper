package com.example.myhelper.interaction.desktop;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Window;
import java.awt.geom.Ellipse2D;

/** 独立的椭圆气泡，透明角与相邻气泡之间的空隙留给桌面。 */
final class DesktopBubble extends JButton {
    /** 气泡默认宽度。 */
    static final int WIDTH = 45;
    /** 气泡默认高度。 */
    static final int HEIGHT = 29;
    /** 玻璃气泡从亮蓝白过渡到透出壁纸的淡蓝紫。 */
    private static final Color GLASS_TOP = new Color(248, 253, 255, 237);
    private static final Color GLASS_BOTTOM = new Color(177, 191, 238, 183);
    private static final Color GLASS_FOCUS = new Color(181, 212, 255, 220);
    private static final Color RIM_TOP = new Color(255, 255, 255, 238);
    private static final Color RIM_BOTTOM = new Color(116, 145, 221, 218);
    private static final Color RUNNING = new Color(48, 78, 142);
    private static final Color FINISHED = new Color(83, 91, 113);
    /** 折叠切换的数量或收起符号。 */
    private String label;
    /** 须等真实运行收尾后才显示结束样式。 */
    private boolean active;
    /** 给辅助技术的当前阶段，文字预览由独立窗口展示。 */
    private String phase;
    /** 按各自椭圆外形裁剪的桌面窗口。 */
    private final JDialog surface;

    DesktopBubble(JFrame owner) {
        setOpaque(false);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setRolloverEnabled(true);
        putClientProperty("html.disable", true);
        surface = new JDialog(owner, "MyHelper 气泡", false);
        surface.setUndecorated(true);
        surface.setAutoRequestFocus(false);
        surface.setType(java.awt.Window.Type.UTILITY);
        prepareTransparent(surface);
        surface.setContentPane(this);
    }

    JDialog surface() {
        return surface;
    }

    void place(Rectangle bounds) {
        placeOval(surface, bounds);
        if (!surface.isVisible()) {
            surface.setVisible(true);
        }
    }

    void update(String title, String phase, boolean active) {
        if (!java.util.Objects.equals(phase, this.phase) || this.active != active || !title.equals(getText())) {
            setText(title);
            this.phase = phase;
            this.active = active;
            String name = phase == null ? title : title + " · " + phase;
            getAccessibleContext().setAccessibleName(name);
            repaint();
        }
    }

    void setCaption(String text) {
        label = text;
        repaint();
    }

    boolean containsOnScreen(Point position) {
        return containsOnScreen(surface, position);
    }

    /** 形状和逐像素透明分别决定点击区域和渐变显示。 */
    static boolean supported(GraphicsDevice device) {
        return device.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSPARENT)
                && device.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT);
    }

    static void prepareTransparent(Window window) {
        if (!supported(window.getGraphicsConfiguration().getDevice())) {
            throw new IllegalStateException("桌面界面需要当前显示设备支持透明窗和窗口裁形。");
        }
        window.setBackground(new Color(0, 0, 0, 0));
    }

    static void placeOval(Window window, Rectangle bounds) {
        Rectangle before = window.getBounds();
        if (!before.equals(bounds)) {
            window.setBounds(bounds);
        }
        if (before.width != bounds.width || before.height != bounds.height || window.getShape() == null) {
            window.setShape(new Ellipse2D.Double(0, 0, bounds.width, bounds.height));
        }
    }

    static boolean containsOnScreen(Window window, Point position) {
        Shape shape = window.getShape();
        return window.isVisible() && shape != null && shape.contains(position.x - window.getX(), position.y - window.getY());
    }

    static void paintGlass(Graphics graphics, int width, int height, boolean focus) {
        if (width < 3 || height < 3) {
            return;
        }
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            var outline = new Ellipse2D.Double(1, 1, width - 2, height - 2);
            Color bottom = focus ? GLASS_FOCUS : GLASS_BOTTOM;
            g.setPaint(new GradientPaint(0, 1, GLASS_TOP, 0, height - 1, bottom));
            g.fill(outline);
            g.setPaint(new GradientPaint(0, 1, RIM_TOP, 0, height - 1, RIM_BOTTOM));
            g.setStroke(new BasicStroke(focus ? 1.35f : 1f));
            g.draw(outline);
        } finally {
            g.dispose();
        }
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            int width = getWidth();
            int height = getHeight();
            boolean highlight = hasFocus() || getModel().isRollover() || getModel().isPressed();
            paintGlass(g, width, height, highlight);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            if (label != null) {
                g.setFont(getFont().deriveFont(Font.BOLD, 12f));
                drawText(g, label, (width - g.getFontMetrics().stringWidth(label)) / 2, height / 2, RUNNING);
            } else {
                paintDots(g, width / 2, height / 2);
            }
        } finally {
            g.dispose();
        }
    }

    private void drawText(Graphics2D g, String label, int x, int center, Color color) {
        var metrics = g.getFontMetrics();
        g.setColor(color);
        g.drawString(label, x, center + (metrics.getAscent() - metrics.getDescent()) / 2);
    }

    private void paintDots(Graphics2D g, int x, int y) {
        g.setColor(active ? RUNNING : FINISHED);
        for (int dot = -1; dot <= 1; dot++) {
            g.fillOval(x + dot * 6 - 2, y - 2, 4, 4);
        }
    }
}
