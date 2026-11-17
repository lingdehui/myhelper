package com.example.myhelper.interaction.desktop;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Swing 调试窗口。展示状态和全部窗口操作都归 EDT。 */
public final class DesktopWindow {
    /** 默认气泡数，更多任务可临时展开。 */
    private static final int BUBBLE_LIMIT = 3;
    /** 结束后保留气泡的时间，毫秒。 */
    private static final long FINISHED_VISIBLE_MILLIS = 8_000;
    /** 相邻窗口的间隔。 */
    private static final int GAP = 6;
    /** 默认离工作区边缘的距离。 */
    private static final int EDGE = 12;
    /** Windows 隐藏任务栏时系统不预留底部空间，初始停靠额外抬高。 */
    private static final int AUTO_HIDE_BOTTOM = 48;
    /** 气泡之间留出桌面间隔。 */
    private static final int BUBBLE_GAP = 5;
    /** 每一排头顶气泡的数量上限。 */
    private static final int BUBBLE_COLUMNS = 4;
    /** 展开预览的尺寸，随工作区压缩。 */
    private static final int PREVIEW_WIDTH = 286;
    private static final int PREVIEW_HEIGHT = 138;
    /** 详情窗口的默认宽度。 */
    private static final int DETAILS_WIDTH = 490;

    /** 玩偶窗口，同时拥有浮层与对话框。 */
    private final JFrame mascot = new JFrame("MyHelper");
    /** 短期行为只修改玩偶姿势、位置和本地道具。 */
    private final DesktopBehaviorView behavior = new DesktopBehaviorView(mascot, this::moveMascot);
    /** 无焦点的悬停预览。 */
    private final JWindow preview = new JWindow(mascot);
    /** 对话和本次会话的任务列表。 */
    private final JDialog conversation = new JDialog(mascot, "MyHelper 对话", false);
    /** 固定显示选中的任务树与时间线。 */
    private final JDialog details = new JDialog(mascot, "MyHelper 任务详情", false);
    /** 采用纯文本的预览。 */
    private final JTextArea previewText = DesktopPanels.text(4);
    /** 对话内容组件。 */
    private final DesktopPanels.Conversation chat;
    /** 任务详情组件。 */
    private final DesktopPanels.Details detail;
    /** 打开详情时请求相应的归档。 */
    private final Consumer<String> inspect;
    /** 退出请求由应用处理，窗口不调用 System.exit。 */
    private final Runnable exit;
    /** 任务保持首次发现的相对顺序。 */
    private final Map<String, TaskView> tasks = new LinkedHashMap<>();
    /** 完成气泡的剩余可见时间按任务单独计算。 */
    private final Map<String, Long> finished = new HashMap<>();
    /** 已创建的气泡按钮按任务标识索引。 */
    private final Map<String, DesktopBubble> buttons = new LinkedHashMap<>();
    /** 折叠切换气泡，在当前屏幕无法一并放下时也用于提示滚动。 */
    private DesktopBubble overflow;
    /** 当前气泡布局的任务标识。 */
    private List<String> bubbleIds = List.of();
    /** 当前布局中的折叠数。 */
    private int hiddenCount;
    /** 鼠标指向的任务标识。 */
    private String hovered;
    /** 用户点击所选的固定详情。 */
    private String selected;
    /** 是否展开额外任务。 */
    private boolean expanded;
    /** 展开后当前可见的首项，随工作区与活动任务数量限制。 */
    private int scrollOffset;
    /** 处理气泡收起和预览离开，随窗口一起停止。 */
    private final Timer timer = new Timer(180, this::updateTransientWindows);

    public DesktopWindow(Consumer<String> submit, Consumer<String> cancel, Consumer<String> inspect, Runnable exit) {
        this.inspect = Objects.requireNonNull(inspect);
        this.exit = Objects.requireNonNull(exit);
        chat = new DesktopPanels.Conversation(submit, this::showDetails, exit, this::closeConversation);
        detail = new DesktopPanels.Details(cancel, this::hideDetails);
        prepareWindows();
    }

    private void prepareWindows() {
        mascot.setUndecorated(true);
        mascot.setType(Window.Type.UTILITY);
        mascot.setAlwaysOnTop(true);
        mascot.setAutoRequestFocus(false);
        mascot.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        mascot.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                exit.run();
            }
        });
        DesktopMascot icon = new DesktopMascot();
        JButton avatar = new JButton(icon) {
            @Override
            protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                behavior.paintPose(graphics);
            }
        };
        BufferedImage iconImage = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D iconGraphics = iconImage.createGraphics();
        icon.paintIcon(avatar, iconGraphics, 0, 0);
        iconGraphics.dispose();
        mascot.setIconImage(iconImage);
        avatar.setOpaque(false);
        avatar.setContentAreaFilled(false);
        avatar.setBorderPainted(false);
        avatar.setBounds(0, 0, icon.getIconWidth(), icon.getIconHeight());
        avatar.setMargin(new Insets(0, 0, 0, 0));
        avatar.setFocusable(false);
        avatar.getAccessibleContext().setAccessibleName("MyHelper 小人，可拖动");
        behavior.attachAvatar(avatar, icon);
        MascotMotion motion = new MascotMotion();
        avatar.addMouseListener(motion);
        avatar.addMouseMotionListener(motion);
        JPopupMenu menu = new JPopupMenu();
        JMenuItem open = new JMenuItem("对话");
        open.addActionListener(this::openConversation);
        JMenuItem quit = new JMenuItem("退出 MyHelper");
        quit.addActionListener(this::quit);
        menu.add(open);
        menu.add(quit);
        avatar.setComponentPopupMenu(menu);
        DesktopBubble.prepareTransparent(mascot);
        JLayeredPane avatarSurface = new JLayeredPane();
        avatarSurface.setPreferredSize(new Dimension(icon.getIconWidth(), icon.getIconHeight()));
        avatarSurface.add(avatar, JLayeredPane.DEFAULT_LAYER);
        avatarSurface.add(new BagButton(DesktopMascot.bag()), JLayeredPane.PALETTE_LAYER);
        mascot.setContentPane(avatarSurface);
        mascot.pack();
        mascot.setShape(DesktopMascot.outline());

        DesktopBubble.prepareTransparent(preview);
        preview.setFocusableWindowState(false);
        preview.setAutoRequestFocus(false);
        previewText.setFocusable(false);
        previewText.setOpaque(false);
        previewText.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        previewText.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event) && hovered != null) {
                    showDetails(hovered);
                }
            }
        });
        previewText.setMargin(new Insets(27, 43, 19, 43));
        JPanel previewSurface = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics graphics) {
                DesktopBubble.paintGlass(graphics, getWidth(), getHeight(), true);
            }
        };
        previewSurface.setOpaque(false);
        previewSurface.add(previewText, BorderLayout.CENTER);
        preview.setContentPane(previewSurface);

        conversation.setUndecorated(true);
        conversation.setContentPane(chat);
        conversation.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        details.setUndecorated(true);
        details.setContentPane(detail);
        details.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);

        bindKeys(mascot.getRootPane());
        bindKeys(conversation.getRootPane());
        bindKeys(details.getRootPane());
    }

    /** 打开玩偶，初始停靠在主屏工作区右下。 */
    public void show() {
        GraphicsConfiguration screen = mascot.getGraphicsConfiguration();
        Rectangle work = workArea(screen);
        int bottom = EDGE;
        if (System.getProperty("os.name", "").startsWith("Windows")
                && work.y + work.height == screen.getBounds().y + screen.getBounds().height) {
            bottom = Math.max(EDGE, Math.min(AUTO_HIDE_BOTTOM, work.height - mascot.getHeight()));
        }
        Rectangle anchor = clamped(work, work.x + work.width - mascot.getWidth() - EDGE,
                work.y + work.height - mascot.getHeight() - bottom, mascot.getWidth(), mascot.getHeight());
        mascot.setLocation(anchor.getLocation());
        mascot.setVisible(true);
        layoutBubbles();
        timer.start();
        behavior.startAnimation();
    }

    /** 关闭所有浮层及其计时器。 */
    public void close() {
        timer.stop();
        behavior.close();
        preview.dispose();
        mascot.dispose();
    }

    DesktopBehaviorView behaviorView() {
        return behavior;
    }

    private void moveMascot(Point point) {
        mascot.setLocation(point);
        updateBubbles(System.currentTimeMillis());
        layoutBubbles();
        if (conversation.isVisible()) {
            placeConversation();
        }
    }

    /** 合并不可变快照，以任务身份更新气泡、对话和详情。 */
    public void render(List<TaskView> next, List<SubmissionView> submissions, String notice) {
        long now = System.currentTimeMillis();
        List<String> present = new ArrayList<>();
        String newlyAccepted = null;
        for (TaskView task : next) {
            present.add(task.taskId());
            TaskView before = tasks.put(task.taskId(), task);
            if (task.input() != null && (before == null || before.input() == null)) {
                newlyAccepted = task.taskId();
            }
            if (task.active()) {
                finished.remove(task.taskId());
            } else {
                finished.putIfAbsent(task.taskId(), now + FINISHED_VISIBLE_MILLIS);
            }
        }
        tasks.keySet().retainAll(present);
        finished.keySet().retainAll(tasks.keySet());
        updateBubbles(now);
        chat.render(List.copyOf(tasks.values()), submissions, notice, newlyAccepted);
        if (details.isVisible()) {
            detail.render(tasks.get(selected), notice);
        }
        if (preview.isVisible()) {
            renderPreview(tasks.get(hovered));
        }
    }

    private void updateTransientWindows(ActionEvent event) {
        if (hovered != null && !hasHover()) {
            hidePreview();
        }
        updateBubbles(System.currentTimeMillis());
    }

    private void updateBubbles(long now) {
        List<String> visible = new ArrayList<>();
        for (TaskView task : tasks.values()) {
            if (finished.getOrDefault(task.taskId(), Long.MAX_VALUE) > now) {
                visible.add(task.taskId());
            }
        }
        int total = visible.size();
        if (total <= BUBBLE_LIMIT) {
            expanded = false;
        }
        int available = bubbleRows() * bubbleColumns(workArea(mascot.getGraphicsConfiguration()));
        boolean needsOverflow = total > Math.min(BUBBLE_LIMIT, available);
        int shown = Math.min(total, expanded ? available - 1 : Math.min(BUBBLE_LIMIT, available));
        if (!expanded && needsOverflow) {
            shown = Math.min(shown, available - 1);
        }
        shown = Math.max(0, shown);
        scrollOffset = expanded ? Math.clamp(scrollOffset, 0, total - shown) : 0;
        List<String> shownIds = visible.subList(scrollOffset, scrollOffset + shown);
        int more = expanded ? (total > shown ? -2 : -1) : total - shown;
        // 光标位于当前气泡或预览内时保留位置，内容仍更新。
        if (hovered == null && (!bubbleIds.equals(shownIds) || hiddenCount != more)) {
            rebuildBubbles(shownIds, more);
        }
        for (String id : bubbleIds) {
            TaskView task = tasks.get(id);
            DesktopBubble button = buttons.get(id);
            button.setEnabled(task != null);
            if (task != null) {
                button.update(task.title(), task.phase(), task.active());
            }
        }
    }

    private void rebuildBubbles(List<String> taskIds, int more) {
        for (String id : bubbleIds) {
            if (!taskIds.contains(id)) {
                buttons.remove(id).surface().dispose();
            }
        }
        bubbleIds = List.copyOf(taskIds);
        hiddenCount = more;
        for (String id : bubbleIds) {
            if (!buttons.containsKey(id)) {
                DesktopBubble button = newBubble();
                button.addActionListener(new OpenTask(id));
                button.addMouseListener(new HoverTask(id));
                buttons.put(id, button);
            }
        }
        if (more == 0 && overflow != null) {
            overflow.surface().dispose();
            overflow = null;
        } else if (more != 0) {
            if (overflow == null) {
                overflow = newBubble();
                overflow.addActionListener(this::toggleExpanded);
            }
            String label = switch (more) {
                case -2 -> "收起  ·  滚动看更多";
                case -1 -> "收起";
                default -> "其余 " + more + " 项";
            };
            overflow.update(label, null, false);
            overflow.setCaption(more > 0 ? "+" + more : "−");
        }
        layoutBubbles();
    }

    private DesktopBubble newBubble() {
        DesktopBubble bubble = new DesktopBubble(mascot);
        bubble.addMouseWheelListener(this::scrollBubbles);
        bindKeys(bubble.surface().getRootPane());
        return bubble;
    }

    private void scrollBubbles(MouseWheelEvent event) {
        if (expanded) {
            hidePreview();
            scrollOffset = Math.max(0, scrollOffset + event.getWheelRotation());
            updateBubbles(System.currentTimeMillis());
        }
    }

    private void toggleExpanded(ActionEvent event) {
        hidePreview();
        expanded = !expanded;
        updateBubbles(System.currentTimeMillis());
    }

    private int bubbleRows() {
        Rectangle work = workArea(mascot.getGraphicsConfiguration());
        Rectangle anchor = mascot.getBounds();
        int above = anchor.y - work.y - GAP;
        int below = work.y + work.height - anchor.y - anchor.height - GAP;
        return Math.clamp((Math.max(above, below) + BUBBLE_GAP) / (DesktopBubble.HEIGHT + 12), 1, 3);
    }

    private int bubbleColumns(Rectangle work) {
        int margin = Math.min(EDGE, work.width / 4);
        int count = (work.width - margin * 2 + BUBBLE_GAP) / (DesktopBubble.WIDTH + BUBBLE_GAP);
        return Math.clamp(count, 1, BUBBLE_COLUMNS);
    }

    private void layoutBubbles() {
        if (!mascot.isVisible()) {
            return;
        }
        Rectangle work = workArea(mascot.getGraphicsConfiguration());
        int columns = bubbleColumns(work);
        Rectangle cluster = bubbleCluster();
        List<DesktopBubble> visible = new ArrayList<>();
        for (String id : bubbleIds) {
            visible.add(buttons.get(id));
        }
        if (overflow != null) {
            visible.add(overflow);
        }
        for (int start = 0; start < visible.size(); start += columns) {
            int count = Math.min(columns, visible.size() - start);
            int width = bubbleRowWidth(count);
            int y = cluster.y + start / columns * (DesktopBubble.HEIGHT + 12);
            for (int column = 0; column < count; column++) {
                int x = cluster.x + (cluster.width - width) / 2 + column * (DesktopBubble.WIDTH + BUBBLE_GAP);
                int arch = Math.abs(2 * column - count + 1) * 2;
                visible.get(start + column).place(clamped(work, x, y + arch, DesktopBubble.WIDTH, DesktopBubble.HEIGHT));
            }
        }
        if (hovered != null) {
            placePreview();
        }
    }

    private int bubbleRowWidth(int count) {
        return Math.max(0, count * (DesktopBubble.WIDTH + BUBBLE_GAP) - BUBBLE_GAP);
    }

    private Rectangle bubbleCluster() {
        Rectangle work = workArea(mascot.getGraphicsConfiguration());
        Rectangle anchor = mascot.getBounds();
        int above = anchor.y - work.y - GAP;
        int below = work.y + work.height - anchor.y - anchor.height - GAP;
        int count = bubbleIds.size() + (overflow == null ? 0 : 1);
        int columns = bubbleColumns(work);
        int rows = Math.ceilDiv(count, columns);
        int height = rows * (DesktopBubble.HEIGHT + 12);
        int margin = Math.min(EDGE, work.width / 4);
        int width = Math.min(bubbleRowWidth(Math.min(count, columns)), work.width - 2 * margin);
        int left = Math.clamp(anchor.x + anchor.width / 2 - width / 2,
                work.x + margin, work.x + work.width - width - margin);
        boolean openAbove = above >= height || above >= below;
        int y = openAbove ? anchor.y - GAP - height : anchor.y + anchor.height + GAP;
        return clamped(work, left, y, width, height);
    }

    private void placePreview() {
        JButton button = buttons.get(hovered);
        if (button == null || !button.isShowing()) {
            return;
        }
        Rectangle anchor = buttons.get(hovered).surface().getBounds();
        Rectangle cluster = bubbleCluster();
        Rectangle work = workArea(mascot.getGraphicsConfiguration());
        int width = Math.min(PREVIEW_WIDTH, work.width);
        int height = Math.min(PREVIEW_HEIGHT, work.height);
        int x = anchor.x + anchor.width / 2 - width / 2;
        int y = cluster.y - height - 2;
        if (y < work.y) {
            x = cluster.x - width - 2;
            if (x < work.x) {
                x = cluster.x + cluster.width + 2;
            }
            y = anchor.y - 12;
        }
        DesktopBubble.placeOval(preview, clamped(work, x, y, width, height));
    }

    private boolean hasHover() {
        PointerInfo pointer = MouseInfo.getPointerInfo();
        if (pointer == null) {
            return false;
        }
        Point position = pointer.getLocation();
        DesktopBubble bubble = buttons.get(hovered);
        if (DesktopBubble.containsOnScreen(preview, position)) {
            return true;
        }
        return bubble != null && bubble.containsOnScreen(position);
    }

    private void renderPreview(TaskView task) {
        if (task == null) {
            hidePreview();
            return;
        }
        StringBuilder text = new StringBuilder(DesktopPanels.compact(task.title(), 16))
                .append("\n").append(DesktopPanels.compact(task.phase(), 16));
        int count = 0;
        for (Step step : task.steps()) {
            if (step.parentId() != null && activeStep(step.status())) {
                text.append("\n").append(DesktopPanels.compact(step.title(), 9)).append(" · ")
                        .append(DesktopPanels.compact(step.status(), 7));
                if (++count == 2) {
                    break;
                }
            }
        }
        if (!task.events().isEmpty()) {
            Event latest = task.events().getLast();
            text.append("\n").append(DesktopPanels.compact(latest.source() + " · " + latest.text(), 17));
        }
        DesktopPanels.updateText(previewText, text.toString(), false);
    }

    private boolean activeStep(String status) {
        return status.contains("进行中") || status.contains("等待") || status.contains("补充");
    }

    private void hidePreview() {
        preview.setVisible(false);
        hovered = null;
    }

    private void showDetails(String id) {
        TaskView task = tasks.get(id);
        if (task == null) {
            return;
        }
        hidePreview();
        selected = id;
        detail.render(task, chat.notice());
        if (!details.isVisible()) {
            placeDetails();
            details.setVisible(true);
        }
        details.toFront();
        inspect.accept(id);
    }

    private void hideDetails() {
        details.setVisible(false);
    }

    private void toggleConversation(ActionEvent event) {
        if (conversation.isVisible()) {
            closeConversation();
        } else {
            openConversation(event);
        }
    }

    private void closeConversation() {
        conversation.setVisible(false);
    }

    private void openConversation(ActionEvent event) {
        hidePreview();
        if (!conversation.isVisible()) {
            placeConversation();
            conversation.setVisible(true);
        }
        conversation.toFront();
        chat.focusInput();
    }

    private void quit(ActionEvent event) {
        exit.run();
    }

    private void placeConversation() {
        Rectangle work = workArea(mascot.getGraphicsConfiguration());
        Rectangle bag = DesktopMascot.bag().getBounds();
        bag.translate(mascot.getX(), mascot.getY());
        int width = 420;
        int height = 492;
        int x = bag.x + bag.width + GAP;
        if (x + width > work.x + work.width) {
            x = mascot.getX() - width - GAP;
        }
        Rectangle bounds = clamped(work, x, bag.y + bag.height - height, width, height);
        conversation.setBounds(bounds);
        roundCorners(conversation, bounds, 34);
    }

    private void placeDetails() {
        Rectangle work = workArea(mascot.getGraphicsConfiguration());
        Rectangle anchor = mascot.getBounds();
        int width = DETAILS_WIDTH;
        int height = 472;
        int x = anchor.x - width - GAP;
        if (x < work.x) {
            x = anchor.x + anchor.width + GAP;
        }
        int y = anchor.y + anchor.height - height;
        if (!bubbleIds.isEmpty() || overflow != null) {
            Rectangle rail = bubbleCluster();
            if (rail.y - work.y >= height + GAP) {
                x = rail.x + rail.width - width;
                y = rail.y - height - GAP;
            } else if (work.y + work.height - rail.y - rail.height >= height + GAP) {
                x = rail.x + rail.width - width;
                y = rail.y + rail.height + GAP;
            }
        }
        Rectangle bounds = clamped(work, x, y, width, height);
        details.setBounds(bounds);
        roundCorners(details, bounds, 30);
    }

    private static void roundCorners(Window window, Rectangle bounds, int radius) {
        GraphicsDevice device = window.getGraphicsConfiguration().getDevice();
        if (device.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSPARENT)) {
            window.setShape(new RoundRectangle2D.Double(0, 0, bounds.width, bounds.height, radius, radius));
        }
    }

    static Rectangle clamped(Rectangle work, int x, int y, int width, int height) {
        width = Math.min(width, work.width);
        height = Math.min(height, work.height);
        x = Math.clamp(x, work.x, work.x + work.width - width);
        y = Math.clamp(y, work.y, work.y + work.height - height);
        return new Rectangle(x, y, width, height);
    }

    static Rectangle workArea(GraphicsConfiguration configuration) {
        Rectangle screen = configuration.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        return new Rectangle(screen.x + insets.left, screen.y + insets.top,
                screen.width - insets.left - insets.right, screen.height - insets.top - insets.bottom);
    }

    private static GraphicsConfiguration screenAt(Point point, GraphicsConfiguration fallback) {
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            GraphicsConfiguration configuration = device.getDefaultConfiguration();
            if (configuration.getBounds().contains(point) && DesktopBubble.supported(device)) {
                return configuration;
            }
        }
        return fallback;
    }

    private void bindKeys(JRootPane root) {
        InputMap keys = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "dismissOverlay");
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_L, KeyEvent.CTRL_DOWN_MASK), "showConversation");
        root.getActionMap().put("dismissOverlay", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                hidePreview();
                hideDetails();
                closeConversation();
            }
        });
        root.getActionMap().put("showConversation", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                openConversation(event);
            }
        });
    }

    /** 仅命中精灵图上手提包的像素。可用 Tab 聚焦，按空格或 Enter 开关。 */
    private final class BagButton extends JButton {
        /** 图标坐标中的手提包实际轮廓。 */
        private final Shape outline;

        private BagButton(Shape outline) {
            this.outline = outline;
            setBounds(outline.getBounds());
            setName("chatBag");
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            getAccessibleContext().setAccessibleName("对话手提包");
            // 只接收按下和松开，避免按住手包时自动走动。手包本身不拖动角色。
            addMouseListener(new MascotMotion());
            addActionListener(DesktopWindow.this::toggleConversation);
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "toggleBag");
            getActionMap().put("toggleBag", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent event) {
                    toggleConversation(event);
                }
            });
        }

        @Override
        public boolean contains(int x, int y) {
            return outline != null && outline.contains(x + getX(), y + getY());
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            if (hasFocus()) {
                Graphics2D g = (Graphics2D) graphics.create();
                try {
                    g.translate(-getX(), -getY());
                    g.setColor(new Color(104, 177, 249));
                    g.draw(outline);
                } finally {
                    g.dispose();
                }
            }
        }
    }

    private final class MascotMotion extends MouseAdapter {
        /** 按下时的桌面位置，用于判断拖动阈值。 */
        private Point press;
        /** 按下时相对玩偶左上角的偏移。 */
        private Point offset;

        @Override
        public void mousePressed(MouseEvent event) {
            if (SwingUtilities.isLeftMouseButton(event)) {
                press = event.getLocationOnScreen();
                offset = event.getPoint();
                behavior.pointerPressed();
            }
        }

        @Override
        public void mouseDragged(MouseEvent event) {
            if (press == null || press.distance(event.getLocationOnScreen()) < 4) {
                return;
            }
            behavior.dragStarted();
            // 拖动已接管本次按压，立即解除按钮的按下样式。
            JButton source = (JButton) event.getSource();
            source.getModel().setArmed(false);
            source.getModel().setPressed(false);
            hidePreview();
            Point pointer = event.getLocationOnScreen();
            Rectangle work = workArea(screenAt(pointer, mascot.getGraphicsConfiguration()));
            Rectangle bound = clamped(work, pointer.x - offset.x, pointer.y - offset.y,
                    mascot.getWidth(), mascot.getHeight());
            moveMascot(bound.getLocation());
        }

        @Override
        public void mouseReleased(MouseEvent event) {
            if (SwingUtilities.isLeftMouseButton(event)) {
                press = null;
                behavior.pointerReleased();
            }
        }
    }

    private final class OpenTask extends AbstractAction {
        /** 对应的主任务身份。 */
        private final String id;

        private OpenTask(String id) {
            this.id = id;
        }

        @Override
        public void actionPerformed(ActionEvent event) {
            showDetails(id);
        }
    }

    private final class HoverTask extends MouseAdapter {
        /** 对应的主任务身份。 */
        private final String id;

        private HoverTask(String id) {
            this.id = id;
        }

        @Override
        public void mouseEntered(MouseEvent event) {
            hovered = id;
            renderPreview(tasks.get(id));
            if (hovered != null) {
                placePreview();
                preview.setVisible(true);
            }
        }
    }

    public record TaskView(String taskId, String title, String input, String phase, String outcome,
                           boolean active, boolean cancellable, boolean cancelling,
                           List<Step> steps, List<Event> events) {
        public TaskView {
            steps = List.copyOf(steps);
            events = List.copyOf(events);
        }
    }

    public record Step(String id, String parentId, String title, String status) { }

    public record Event(String stepId, Instant at, String source, String text) { }

    public record SubmissionView(String input, String status, String taskId) { }
}
