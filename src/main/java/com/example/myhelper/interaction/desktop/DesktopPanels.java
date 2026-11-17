package com.example.myhelper.interaction.desktop;

import javax.swing.*;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import com.example.myhelper.interaction.desktop.DesktopWindow.Event;
import com.example.myhelper.interaction.desktop.DesktopWindow.Step;
import com.example.myhelper.interaction.desktop.DesktopWindow.SubmissionView;
import com.example.myhelper.interaction.desktop.DesktopWindow.TaskView;

/** 对话与任务详情的 Swing 组件。 */
final class DesktopPanels {
    /** 主文字。 */
    static final Color INK = new Color(38, 29, 53);
    /** 内容块边框。 */
    static final Color LINE = new Color(229, 222, 241);
    /** 窗口内底色。 */
    private static final Color SURFACE = new Color(249, 246, 255);
    /** 内容块底色。 */
    private static final Color PAPER = new Color(255, 253, 255);
    /** 次要文字。 */
    private static final Color MUTED = new Color(101, 89, 119);
    /** 提交动作和强调文字。 */
    private static final Color ACCENT = new Color(104, 67, 178);
    /** 次要动作和选中条目。 */
    private static final Color LAVENDER = new Color(239, 231, 255);
    /** 错误和取消动作的正文。 */
    private static final Color DANGER = new Color(130, 44, 87);
    /** 错误和取消动作的底色。 */
    private static final Color ROSE = new Color(253, 235, 244);
    /** Windows 使用系统中文 UI 字体，其他图形环境使用逻辑字体。 */
    private static final Font BODY = uiFont();
    /** 用户可见时间按本机时区显示。 */
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private DesktopPanels() { }

    private static Font uiFont() {
        String family = "Microsoft YaHei UI";
        Font windows = new Font(family, Font.PLAIN, 13);
        return windows.getFamily().equalsIgnoreCase(family) ? windows : new Font(Font.SANS_SERIF, Font.PLAIN, 13);
    }

    static JButton button(String text) {
        return new ActionButton(text, Tone.SECONDARY);
    }

    static JTextArea text(int rows) {
        JTextArea area = new JTextArea(rows, 24);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(BODY);
        area.setForeground(INK);
        area.setBackground(PAPER);
        area.setCaretColor(ACCENT);
        area.setSelectionColor(LAVENDER);
        area.setSelectedTextColor(INK);
        area.setMargin(new Insets(5, 5, 5, 5));
        area.setEditable(false);
        return area;
    }

    static JScrollPane scroll(Component component) {
        JScrollPane pane = new JScrollPane(component);
        pane.setBorder(null);
        pane.setBackground(PAPER);
        pane.getViewport().setBackground(PAPER);
        pane.getVerticalScrollBar().setBackground(PAPER);
        pane.getHorizontalScrollBar().setBackground(PAPER);
        pane.getVerticalScrollBar().setUI(new SlimScrollBar());
        pane.getHorizontalScrollBar().setUI(new SlimScrollBar());
        return pane;
    }

    static String compact(String text, int limit) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String line = text.replace('\n', ' ').replace('\r', ' ').strip();
        int count = line.codePointCount(0, line.length());
        if (count <= limit) {
            return line;
        }
        return line.substring(0, line.offsetByCodePoints(0, limit - 1)) + "…";
    }

    static void updateText(JTextArea area, String text, boolean follow) {
        if (text.equals(area.getText())) {
            return;
        }
        int pos = area.getCaretPosition();
        boolean atEnd = pos == area.getDocument().getLength();
        area.setText(text);
        area.setCaretPosition(follow && atEnd ? text.length() : Math.min(pos, text.length()));
    }

    private static JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.putClientProperty("html.disable", true);
        label.setForeground(INK);
        label.setFont(BODY);
        return label;
    }

    private static JPanel content() {
        JPanel panel = new JPanel(new BorderLayout(0, 13));
        panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        panel.setBackground(SURFACE);
        return panel;
    }

    private static JLabel sectionLabel(String value) {
        JLabel label = label(value);
        label.setFont(BODY.deriveFont(Font.BOLD, 12f));
        label.setForeground(MUTED);
        return label;
    }

    private static JPanel section(String title, Component child) {
        JPanel section = new JPanel(new BorderLayout(0, 5));
        section.setOpaque(false);
        section.add(sectionLabel(title), BorderLayout.NORTH);
        section.add(child, BorderLayout.CENTER);
        return section;
    }

    private static JPanel card(Component component, int padding) {
        JPanel card = new PaperCard();
        card.setBorder(BorderFactory.createEmptyBorder(padding, padding, padding, padding));
        card.add(component, BorderLayout.CENTER);
        return card;
    }

    private enum Tone { SECONDARY, PRIMARY, DANGER }

    private static final class ActionButton extends JButton {
        /** 动作用途决定底色和文字颜色。 */
        private final Tone tone;

        private ActionButton(String text, Tone tone) {
            super(text);
            this.tone = tone;
            putClientProperty("html.disable", true);
            setFont(BODY.deriveFont(Font.BOLD, 12f));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(7, 11, 7, 11));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setForeground(switch (tone) {
                case PRIMARY -> Color.WHITE;
                case SECONDARY -> ACCENT;
                case DANGER -> DANGER;
            });
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = antialiased(graphics);
            try {
                Color fill = switch (tone) {
                    case PRIMARY -> ACCENT;
                    case SECONDARY -> LAVENDER;
                    case DANGER -> ROSE;
                };
                if (!isEnabled()) {
                    fill = SURFACE;
                } else if (getModel().isPressed() || getModel().isRollover()) {
                    fill = fill.darker();
                }
                g.setColor(fill);
                g.fillRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 16, 16);
                if (hasFocus()) {
                    g.setColor(tone == Tone.PRIMARY ? LAVENDER : ACCENT);
                    g.drawRoundRect(2, 2, getWidth() - 5, getHeight() - 5, 13, 13);
                }
            } finally {
                g.dispose();
            }
            super.paintComponent(graphics);
        }
    }

    private static final class PaperCard extends JPanel {
        private PaperCard() {
            super(new BorderLayout());
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = antialiased(graphics);
            try {
                g.setColor(PAPER);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 18, 18);
                g.setColor(Boolean.TRUE.equals(getClientProperty("inputFocused")) ? ACCENT : LINE);
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 18, 18);
            } finally {
                g.dispose();
            }
        }
    }

    private static Graphics2D antialiased(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        return g;
    }

    private static final class SlimScrollBar extends BasicScrollBarUI {
        @Override
        protected JButton createDecreaseButton(int direction) {
            return emptyButton();
        }

        @Override
        protected JButton createIncreaseButton(int direction) {
            return emptyButton();
        }

        private static JButton emptyButton() {
            JButton spacer = new JButton();
            spacer.setPreferredSize(new Dimension(0, 0));
            return spacer;
        }

        @Override
        public Dimension getPreferredSize(JComponent component) {
            return new Dimension(9, 9);
        }

        @Override
        protected void paintTrack(Graphics graphics, JComponent component, Rectangle bounds) { }

        @Override
        protected void paintThumb(Graphics graphics, JComponent component, Rectangle bounds) {
            if (bounds.isEmpty() || !scrollbar.isEnabled()) { return; }
            Graphics2D g = antialiased(graphics);
            try {
                g.setColor(isThumbRollover() ? ACCENT : new Color(200, 184, 222));
                g.fillRoundRect(bounds.x + 2, bounds.y + 2, bounds.width - 4, bounds.height - 4, 7, 7);
            } finally {
                g.dispose();
            }
        }
    }

    static final class Conversation extends JPanel {
        /** 列表每行的高度。 */
        private static final int ROW_HEIGHT = 31;
        /** 会话列表及当前选中的对话。 */
        private final JList<ConversationRow> list = new JList<>();
        /** 没有提交时隐藏会话列表。 */
        private final JPanel recentSection;
        /** 只为当前可见的会话行预留空间。 */
        private final JScrollPane recentScroll;
        /** 原输入和回复，按选择更新。 */
        private final JTextArea transcript = text(0);
        /** 提交新任务的文本框。 */
        private final JTextArea input = text(2);
        /** 当前缓冲或提交状态。 */
        private final JLabel status = label(" ");
        /** 打开列表中已接纳任务的详情。 */
        private final JButton openTask = button("任务详情");
        /** 提交原文的回调。 */
        private final Consumer<String> submit;
        /** 选中当前任务的详情回调。 */
        private final Consumer<String> inspect;
        /** 用于避免日志更新时重建未变化的列表。 */
        private List<ConversationRow> rows = List.of();
        /** 当前视图提示，详情与对话共用。 */
        private String notice;
        /** 当前已接纳任务，供列表选择后读取最新回复。 */
        private final Map<String, TaskView> tasks = new HashMap<>();

        Conversation(Consumer<String> submit, Consumer<String> inspect, Runnable exit, Runnable close) {
            super(new BorderLayout());
            this.submit = Objects.requireNonNull(submit);
            this.inspect = Objects.requireNonNull(inspect);
            JPanel panel = content();
            JPanel header = new JPanel(new BorderLayout(8, 0));
            header.setOpaque(false);
            JLabel title = label("当前会话");
            title.setFont(BODY.deriveFont(Font.BOLD, 20f));
            openTask.setEnabled(false);
            openTask.addActionListener(this::openSelected);
            JButton quit = new ActionButton("退出助手", Tone.DANGER);
            quit.addActionListener(new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent event) {
                    exit.run();
                }
            });
            JButton dismiss = button("收起对话");
            dismiss.addActionListener(new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent event) {
                    close.run();
                }
            });
            header.add(title, BorderLayout.CENTER);
            JPanel controls = new JPanel(new FlowLayout(FlowLayout.TRAILING, 5, 0));
            controls.setOpaque(false);
            controls.add(openTask);
            controls.add(quit);
            controls.add(dismiss);
            header.add(controls, BorderLayout.EAST);
            panel.add(header, BorderLayout.NORTH);

            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.setFixedCellHeight(ROW_HEIGHT);
            list.setBackground(PAPER);
            list.setCellRenderer(new ConversationRenderer());
            list.getAccessibleContext().setAccessibleName("最近任务");
            list.addListSelectionListener(this::select);
            list.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent event) {
                    if (event.getClickCount() == 2) {
                        openSelected(null);
                    }
                }
            });
            bindAction(list, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "showTask", this::openSelected);
            recentScroll = scroll(list);
            recentSection = section("最近任务", card(recentScroll, 5));
            recentSection.setVisible(false);
            JPanel body = new JPanel(new BorderLayout(0, 10));
            body.setOpaque(false);
            body.add(recentSection, BorderLayout.NORTH);
            transcript.getAccessibleContext().setAccessibleName("对话内容");
            body.add(section("对话内容", card(scroll(transcript), 8)), BorderLayout.CENTER);
            panel.add(body, BorderLayout.CENTER);

            JPanel composer = new JPanel(new BorderLayout(0, 5));
            composer.setBackground(PAPER);
            JPanel actions = new JPanel(new BorderLayout(10, 0));
            actions.setOpaque(false);
            JLabel shortcut = label("Ctrl + Enter");
            shortcut.setForeground(MUTED);
            shortcut.setFont(BODY.deriveFont(11f));
            input.setEditable(true);
            input.getAccessibleContext().setAccessibleName("新任务");
            JButton send = new ActionButton("发送新任务", Tone.PRIMARY);
            send.addActionListener(this::submit);
            bindAction(input, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK), "sendTask",
                    this::submit);
            composer.add(scroll(input), BorderLayout.CENTER);
            actions.add(shortcut, BorderLayout.WEST);
            actions.add(send, BorderLayout.EAST);
            composer.add(actions, BorderLayout.SOUTH);
            JPanel inputCard = card(composer, 8);
            input.addFocusListener(new FocusAdapter() {
                @Override
                public void focusGained(FocusEvent event) {
                    showInputFocus(true);
                }

                @Override
                public void focusLost(FocusEvent event) {
                    showInputFocus(false);
                }

                private void showInputFocus(boolean focused) {
                    inputCard.putClientProperty("inputFocused", focused);
                    inputCard.repaint();
                }
            });
            status.setFont(BODY.deriveFont(12f));
            status.setForeground(DANGER);
            status.setVisible(false);
            JPanel compose = section("新任务", inputCard);
            compose.add(status, BorderLayout.SOUTH);
            panel.add(compose, BorderLayout.SOUTH);
            add(panel);
        }

        void render(List<TaskView> next, List<SubmissionView> submissions, String notice, String accepted) {
            this.notice = notice;
            ConversationRow selected = list.getSelectedValue();
            tasks.clear();
            List<ConversationRow> refreshed = new ArrayList<>();
            for (SubmissionView submission : submissions) {
                refreshed.add(new ConversationRow(submission.taskId(), submission.input(), submission.status(), true));
            }
            for (TaskView task : next.reversed()) {
                tasks.put(task.taskId(), task);
                refreshed.add(new ConversationRow(task.taskId(), task.title(), task.phase(), false));
            }
            if (!refreshed.equals(rows)) {
                rows = List.copyOf(refreshed);
                recentScroll.setPreferredSize(new Dimension(280, ROW_HEIGHT * Math.min(3, rows.size())));
                recentSection.setVisible(!rows.isEmpty());
                list.setListData(rows.toArray(ConversationRow[]::new));
                int index = preferredRow(selected, accepted);
                list.setSelectedIndex(index);
                if (index >= 0) {
                    list.ensureIndexIsVisible(index);
                }
            }
            String hint = notice;
            if ((hint == null || hint.isBlank()) && !submissions.isEmpty()) {
                hint = submissions.getFirst().status();
            }
            status.setText(compact(hint, 42));
            status.setVisible(hint != null && !hint.isBlank());
            renderTranscript();
        }

        private int preferredRow(ConversationRow selected, String accepted) {
            int retained = -1;
            for (int i = 0; i < rows.size(); i++) {
                ConversationRow row = rows.get(i);
                if (accepted != null && !row.submission() && accepted.equals(row.taskId())) {
                    return i;
                }
                if (selected != null && sameRow(selected, row)) {
                    retained = i;
                }
            }
            return retained >= 0 || rows.isEmpty() ? retained : 0;
        }

        private boolean sameRow(ConversationRow left, ConversationRow right) {
            if (left.taskId() == null) {
                return right.taskId() == null && Objects.equals(left.input(), right.input());
            }
            return left.submission() == right.submission() && left.taskId().equals(right.taskId());
        }

        private void select(javax.swing.event.ListSelectionEvent event) {
            if (!event.getValueIsAdjusting()) {
                renderTranscript();
            }
        }

        private void renderTranscript() {
            ConversationRow row = list.getSelectedValue();
            openTask.setEnabled(row != null && row.taskId() != null && !row.submission());
            if (row == null) {
                updateText(transcript, "暂无会话", false);
                return;
            }
            TaskView task = row.taskId() == null ? null : tasks.get(row.taskId());
            String text;
            if (row.submission() || task == null) {
                text = "你\n" + row.input() + "\n\n" + row.phase();
            } else {
                String original = task.input() == null ? task.title() : task.input();
                String reply = task.outcome() == null ? task.phase() : task.outcome();
                text = "你\n" + original + "\n\nMyHelper\n" + reply;
            }
            updateText(transcript, text, false);
        }

        private void submit(ActionEvent event) {
            String original = input.getText();
            if (!original.isBlank()) {
                submit.accept(original);
                input.setText("");
            }
        }

        private void openSelected(ActionEvent event) {
            ConversationRow row = list.getSelectedValue();
            if (row != null && row.taskId() != null && !row.submission()) {
                inspect.accept(row.taskId());
            }
        }

        void focusInput() {
            SwingUtilities.invokeLater(input::requestFocusInWindow);
        }

        String notice() {
            return notice;
        }
    }

    private record ConversationRow(String taskId, String input, String phase, boolean submission) {
        @Override
        public String toString() {
            return compact(input, 15) + " · " + compact(phase, 13);
        }
    }

    private static final class ConversationRenderer extends JPanel implements ListCellRenderer<ConversationRow> {
        /** 任务的简短标题。 */
        private final JLabel title = label("");
        /** 与标题分列，便于快速对比同会话的状态。 */
        private final JLabel phase = label("");

        ConversationRenderer() {
            super(new BorderLayout(10, 0));
            phase.setFont(BODY.deriveFont(12f));
            setBorder(BorderFactory.createEmptyBorder(0, 9, 0, 9));
            add(title, BorderLayout.CENTER);
            add(phase, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends ConversationRow> list, ConversationRow row,
                                                      int index, boolean selected, boolean focused) {
            title.setText(compact(row.input(), 19));
            phase.setText(compact(row.phase(), 12));
            phase.setForeground(selected ? ACCENT : MUTED);
            setBackground(selected ? LAVENDER : PAPER);
            return this;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            // 渲染器不一定有原生窗口，绘制前用本行的实际边界布置标题和状态。
            doLayout();
            super.paintComponent(graphics);
        }
    }

    static final class Details extends JPanel {
        /** 当前任务及阶段。 */
        private final JLabel heading = label("");
        /** 当前任务阶段。 */
        private final JLabel status = label(" ");
        /** 实时数据完整性提示。 */
        private final JLabel warning = label("");
        /** 执行图树，根是查看整条时间线的入口。 */
        private final JTree tree = new JTree(new DefaultMutableTreeNode("全部过程"));
        /** 当前选中节点的时间线。 */
        private final JTextArea timeline = text(0);
        /** 请求编排器取消当前任务。 */
        private final JButton cancel = new ActionButton("取消任务", Tone.DANGER);
        /** 处理用户取消请求。 */
        private final Consumer<String> requestCancel;
        /** 当前任务及其最近一次执行图投影。 */
        private TaskView task;
        /** 保留图刷新前后的节点选择与展开。 */
        private final Map<String, DefaultMutableTreeNode> treeNodes = new HashMap<>();

        Details(Consumer<String> requestCancel, Runnable close) {
            super(new BorderLayout());
            this.requestCancel = Objects.requireNonNull(requestCancel);
            JPanel panel = content();
            JPanel header = new JPanel(new BorderLayout(0, 7));
            header.setOpaque(false);
            heading.setFont(BODY.deriveFont(Font.BOLD, 19f));
            cancel.addActionListener(this::cancel);
            JButton dismiss = button("收起详情");
            dismiss.addActionListener(new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent event) {
                    close.run();
                }
            });
            JPanel controls = new JPanel(new FlowLayout(FlowLayout.TRAILING, 5, 0));
            controls.setOpaque(false);
            controls.add(cancel);
            controls.add(dismiss);
            JPanel overview = new JPanel(new BorderLayout(8, 0));
            overview.setOpaque(false);
            status.setFont(BODY.deriveFont(Font.BOLD, 12f));
            status.setForeground(ACCENT);
            warning.setFont(BODY.deriveFont(12f));
            warning.setForeground(DANGER);
            overview.add(status, BorderLayout.CENTER);
            overview.add(controls, BorderLayout.EAST);
            header.add(heading, BorderLayout.NORTH);
            header.add(overview, BorderLayout.CENTER);
            header.add(warning, BorderLayout.SOUTH);
            panel.add(header, BorderLayout.NORTH);

            tree.setFont(BODY);
            tree.setBackground(PAPER);
            tree.setRowHeight(27);
            tree.putClientProperty("JTree.lineStyle", "None");
            tree.setCellRenderer(new ExecutionRenderer());
            tree.getAccessibleContext().setAccessibleName("执行树");
            tree.getSelectionModel().setSelectionMode(javax.swing.tree.TreeSelectionModel.SINGLE_TREE_SELECTION);
            tree.addTreeSelectionListener(this::filter);
            timeline.getAccessibleContext().setAccessibleName("过程记录");
            JPanel execution = section("执行树", card(scroll(tree), 7));
            JPanel records = section("过程记录", card(scroll(timeline), 7));
            execution.setMinimumSize(new Dimension(160, 96));
            records.setMinimumSize(new Dimension(160, 84));
            JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, execution, records);
            split.setResizeWeight(0.48);
            split.setBorder(null);
            split.setOpaque(false);
            split.setDividerSize(9);
            if (split.getUI() instanceof BasicSplitPaneUI ui) {
                ui.getDivider().setBackground(SURFACE);
                ui.getDivider().setBorder(BorderFactory.createEmptyBorder());
            }
            panel.add(split, BorderLayout.CENTER);
            add(panel);
        }

        void render(TaskView next, String notice) {
            TaskView before = task;
            task = next;
            boolean same = next != null && before != null && Objects.equals(before.taskId(), next.taskId());
            List<Step> steps = next == null ? List.of() : next.steps();
            if (!same || !before.steps().equals(steps)) {
                rebuildTree(steps, same);
            }
            String title = next == null ? "任务详情暂不可用" : compact(next.title(), 25);
            heading.setText(title);
            status.setText(next == null ? " " : compact(next.phase(), 23));
            warning.setText(compact(notice, 40));
            warning.setVisible(notice != null && !notice.isBlank());
            cancel.setEnabled(next != null && next.cancellable() && !next.cancelling());
            cancel.setText(next != null && next.cancelling() ? "正在收尾" : "取消任务");
            filter(null);
        }

        private void rebuildTree(List<Step> steps, boolean preserveSelection) {
            String choice = preserveSelection ? selectedStepId() : null;
            Set<String> expanded = new HashSet<>();
            if (preserveSelection) {
                for (Map.Entry<String, DefaultMutableTreeNode> item : treeNodes.entrySet()) {
                    if (tree.isExpanded(new TreePath(item.getValue().getPath()))) {
                        expanded.add(item.getKey());
                    }
                }
            }
            DefaultMutableTreeNode root = new DefaultMutableTreeNode("全部过程");
            Set<String> oldIds = new HashSet<>(treeNodes.keySet());
            treeNodes.clear();
            for (Step step : steps) {
                treeNodes.put(step.id(), new DefaultMutableTreeNode(new StepRow(step)));
            }
            for (Step step : steps) {
                DefaultMutableTreeNode node = treeNodes.get(step.id());
                DefaultMutableTreeNode parent = step.parentId() == null ? null : treeNodes.get(step.parentId());
                if (parent == null || node == parent || parent.isNodeAncestor(node)) {
                    root.add(node);
                } else {
                    parent.add(node);
                }
            }
            tree.setModel(new DefaultTreeModel(root));
            tree.expandPath(new TreePath(root));
            for (DefaultMutableTreeNode node : treeNodes.values()) {
                StepRow row = (StepRow) node.getUserObject();
                String id = row.step().id();
                if (expanded.contains(id) || ((!preserveSelection || !oldIds.contains(id)) && node.getLevel() == 1)) {
                    tree.expandPath(new TreePath(node.getPath()));
                }
            }
            DefaultMutableTreeNode selected = choice == null ? root : treeNodes.getOrDefault(choice, root);
            tree.setSelectionPath(new TreePath(selected.getPath()));
        }

        private String selectedStepId() {
            if (tree.getLastSelectedPathComponent() instanceof DefaultMutableTreeNode node
                    && node.getUserObject() instanceof StepRow row) {
                return row.step().id();
            }
            return null;
        }

        private void filter(javax.swing.event.TreeSelectionEvent event) {
            if (task == null) {
                updateText(timeline, "", false);
                return;
            }
            String selectedId = selectedStepId();
            DefaultMutableTreeNode node = treeNodes.get(selectedId);
            StringBuilder text = new StringBuilder();
            for (Event entry : task.events()) {
                if (selectedId != null && !isWithin(node, entry.stepId())) {
                    continue;
                }
                if (entry.at() != null) {
                    text.append(CLOCK.format(entry.at())).append("  ");
                }
                text.append(entry.source()).append(" · ").append(entry.text()).append('\n');
            }
            if (text.isEmpty()) {
                text.append("部分记录暂不可用");
            }
            if (selectedId == null && task.outcome() != null && !task.outcome().isBlank()) {
                text.append("\n").append(task.outcome());
            }
            updateText(timeline, text.toString(), event == null);
        }

        private boolean isWithin(DefaultMutableTreeNode selected, String entryId) {
            DefaultMutableTreeNode node = treeNodes.get(entryId);
            return selected != null && node != null && selected.isNodeDescendant(node);
        }

        private void cancel(ActionEvent event) {
            if (task != null && task.cancellable() && !task.cancelling()) {
                requestCancel.accept(task.taskId());
            }
        }
    }

    private record StepRow(Step step) {
        @Override
        public String toString() {
            return compact(step.title(), 35) + " · " + step.status();
        }
    }

    private static final class ExecutionRenderer extends javax.swing.tree.DefaultTreeCellRenderer {
        ExecutionRenderer() {
            putClientProperty("html.disable", true);
            setTextSelectionColor(ACCENT);
            setTextNonSelectionColor(INK);
            setBackgroundSelectionColor(LAVENDER);
            setBackgroundNonSelectionColor(PAPER);
            setBorderSelectionColor(ACCENT);
            setOpenIcon(null);
            setClosedIcon(null);
            setLeafIcon(null);
        }

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded,
                                                       boolean leaf, int row, boolean focused) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focused);
            setFont(row == 0 ? BODY.deriveFont(Font.BOLD) : BODY);
            return this;
        }
    }

    private static void bindAction(JComponent component, KeyStroke key, String name, Consumer<ActionEvent> action) {
        component.getInputMap().put(key, name);
        component.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                action.accept(event);
            }
        });
    }
}
