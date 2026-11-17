package com.example.myhelper.interaction.desktop;

import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.Icon;

/** 透明玩偶图标及与图像对齐的桌面命中区域。 */
final class DesktopMascot implements Icon {
    /** 玩偶窗口在常规缩放下的逻辑高度。 */
    private static final int HEIGHT = 150;
    /** 排除轮廓外的弱半透明像素，保留主体的抗锯齿边缘。 */
    private static final int MIN_ALPHA = 32;
    /** 有真实 Alpha 通道的角色与手包。 */
    private static final BufferedImage IMAGE = readImage(Frame.BASE);
    /** 保持图像宽高比时的逻辑宽度。 */
    private static final int WIDTH = Math.round(HEIGHT * (float) IMAGE.getWidth() / IMAGE.getHeight());
    /** 图像像素到窗口逻辑坐标的变换。 */
    private static final AffineTransform ICON_SCALE = AffineTransform.getScaleInstance(
            (double) WIDTH / IMAGE.getWidth(), (double) HEIGHT / IMAGE.getHeight());
    /** 按窗口逻辑像素计算轮廓，保留手包柄内和身体周围的透明空白。 */
    private static final Area OUTLINE = opaquePixels(IMAGE);
    /** 只包含手包包身和可见包柄的对话命中区域。 */
    private static final Shape BAG = bagShape();
    /** 所有卡面及窗口轮廓只在加载时解码和计算。 */
    private static final Map<Frame, Sprite> SPRITES = loadSprites();

    /** 当前会话只在 Swing 事件线程切换帧。 */
    private Sprite current = SPRITES.get(Frame.BASE);

    static Shape outline() {
        return OUTLINE;
    }

    static Shape bag() {
        return BAG;
    }

    boolean select(Frame frame) {
        Sprite next = SPRITES.get(frame);
        if (next == current) {
            return false;
        }
        current = next;
        return true;
    }

    Shape currentOutline() {
        return current.outline();
    }

    @Override
    public int getIconWidth() {
        return WIDTH;
    }

    @Override
    public int getIconHeight() {
        return HEIGHT;
    }

    @Override
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            drawImage(current.image(), g, x, y);
        } finally {
            g.dispose();
        }
    }

    private static void drawImage(BufferedImage image, Graphics2D g, int x, int y) {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(image, x, y, WIDTH, HEIGHT, null);
    }

    private static Map<Frame, Sprite> loadSprites() {
        EnumMap<Frame, Sprite> sprites = new EnumMap<>(Frame.class);
        sprites.put(Frame.BASE, new Sprite(IMAGE, OUTLINE));
        for (Frame frame : Frame.values()) {
            if (frame == Frame.BASE) {
                continue;
            }
            BufferedImage image = readImage(frame);
            if (image.getWidth() != IMAGE.getWidth() || image.getHeight() != IMAGE.getHeight()) {
                throw new IllegalStateException("桌面动作的画布尺寸与站立姿势不同 " + frame.file);
            }
            Area mask = opaquePixels(image);
            sprites.put(frame, new Sprite(image, OUTLINE.equals(mask) ? OUTLINE : mask));
        }
        return Map.copyOf(sprites);
    }

    private static BufferedImage readImage(Frame frame) {
        String resource = "/desktop/" + frame.file;
        try (var input = DesktopMascot.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("缺少桌面玩偶资源 " + resource);
            }
            BufferedImage image = ImageIO.read(input);
            if (image == null || !image.getColorModel().hasAlpha()) {
                throw new IllegalStateException("桌面玩偶资源需要透明 PNG " + resource);
            }
            return image;
        } catch (IOException failure) {
            throw new IllegalStateException("无法读取桌面玩偶资源 " + resource, failure);
        }
    }

    private static Area opaquePixels(BufferedImage image) {
        // Windows 用整数窗口像素生成裁切区。先采样再合并相邻区域，避免小数条带在原生裁切时出现孔洞。
        BufferedImage logical = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = logical.createGraphics();
        try {
            drawImage(image, g, 0, 0);
        } finally {
            g.dispose();
        }
        Path2D path = new Path2D.Double();
        for (int y = 0; y < HEIGHT; y++) {
            int start = -1;
            for (int x = 0; x < WIDTH; x++) {
                boolean visible = (logical.getRGB(x, y) >>> 24) >= MIN_ALPHA;
                if (visible && start < 0) {
                    start = x;
                } else if (!visible && start >= 0) {
                    path.append(new Rectangle2D.Double(start, y, x - start, 1), false);
                    start = -1;
                }
            }
            if (start >= 0) {
                path.append(new Rectangle2D.Double(start, y, WIDTH - start, 1), false);
            }
        }
        return new Area(path);
    }

    enum Frame {
        BASE("rem-mascot.png"),
        BLINK("rem-blink.png"),
        ALERT("rem-alert.png"),
        THINK("rem-think.png"),
        HAPPY("rem-happy.png");

        /** 基础立绘和各动作使用同一画布及坐标。 */
        private final String file;

        Frame(String file) {
            this.file = file;
        }
    }

    private record Sprite(
            /** 透明原图，用于按当前设备 DPI 绘制。 */
            BufferedImage image,
            /** 与图像逻辑像素一致的原生窗口形状。 */
            Shape outline) { }

    private static Shape bagShape() {
        // 手包轮廓使用 rem-mascot.png 的 259×450 像素坐标。
        Path2D path = new Path2D.Double();
        path.moveTo(191, 283);
        path.curveTo(202, 279, 229, 280, 239, 282);
        path.curveTo(246, 284, 246, 291, 251, 301);
        path.curveTo(256, 315, 259, 328, 251, 334);
        path.curveTo(239, 342, 199, 342, 187, 335);
        path.curveTo(177, 331, 176, 326, 180, 314);
        path.curveTo(182, 302, 184, 289, 191, 283);
        path.closePath();

        path.moveTo(187, 284);
        path.curveTo(187, 273, 194, 259, 200, 255);
        path.lineTo(205, 259);
        path.curveTo(200, 267, 196, 277, 196, 284);
        path.closePath();

        path.moveTo(221, 253);
        path.curveTo(232, 257, 238, 271, 240, 283);
        path.lineTo(231, 283);
        path.curveTo(230, 274, 226, 265, 217, 259);
        path.closePath();

        Area bag = new Area(ICON_SCALE.createTransformedShape(path));
        bag.intersect(new Area(OUTLINE));
        return bag;
    }
}
