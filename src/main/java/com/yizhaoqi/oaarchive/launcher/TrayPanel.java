package com.yizhaoqi.oaarchive.launcher;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

/**
 * 托盘弹出面板的内容（仿 CC Switch 风格）：标题栏、状态卡片、功能列表、退出。
 * 全部用 Swing 自绘并显式指定中文字体。系统原生托盘菜单在精简运行时里显示中文会变成方块，所以不用它。
 */
final class TrayPanel {

    static final int WIDTH = 300;
    static final Color BG = new Color(0xF3, 0xF4, 0xF6);
    static final Color CARD = Color.WHITE;
    static final Color TEXT = new Color(0x1F, 0x2A, 0x30);
    static final Color MUTED = new Color(0x7A, 0x84, 0x8B);
    static final Color HOVER = new Color(0xE6, 0xEA, 0xED);
    static final Color ACCENT = new Color(0x2F, 0x6F, 0x7E);
    static final Color DANGER = new Color(0xC0, 0x39, 0x2B);
    static final Color LINE = new Color(0xE1, 0xE5, 0xE8);

    /** 状态数据 */
    record Stats(long ready, long review, long ledger, boolean running) {
    }

    /** 面板上的操作 */
    interface Actions {
        void openPage(String hashPath);

        void openArchiveDir();

        void openLogDir();

        void exit();
    }

    private final Actions actions;
    private final Runnable close;
    private final JLabel readyValue = value("0");
    private final JLabel reviewValue = value("0");
    private final JLabel ledgerValue = value("0");
    private final JLabel statusLine = text("后台运行中", 12, MUTED);
    private final Badge reviewBadge = new Badge();

    TrayPanel(Actions actions, Runnable close) {
        this.actions = actions;
        this.close = close;
    }

    static Font font(int size, boolean bold) {
        String family = pickFamily();
        return new Font(family, bold ? Font.BOLD : Font.PLAIN, size);
    }

    private static final String[] PREFERRED_FONTS = {"Microsoft YaHei UI", "Microsoft YaHei", "微软雅黑",
            "PingFang SC", "Noto Sans CJK SC", "SimHei"};
    private static String cachedFamily;

    /** 按优先级选系统里存在的中文字体 */
    private static String pickFamily() {
        if (cachedFamily == null) {
            Set<String> names = Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
            cachedFamily = Font.DIALOG;
            for (String f : PREFERRED_FONTS) {
                if (names.contains(f)) {
                    cachedFamily = f;
                    break;
                }
            }
        }
        return cachedFamily;
    }

    void update(Stats s) {
        readyValue.setText(String.valueOf(s.ready()));
        reviewValue.setText(String.valueOf(s.review()));
        ledgerValue.setText(String.valueOf(s.ledger()));
        reviewValue.setForeground(s.review() > 0 ? new Color(0xB0, 0x7D, 0x12) : TEXT);
        reviewBadge.setCount(s.review());
        statusLine.setText(s.running() ? "● 正在识别文件…" : "● 后台运行中 · 离线 OCR 可用");
        statusLine.setForeground(s.running() ? ACCENT : new Color(0x2F, 0x8F, 0x5B));
    }

    JComponent build() {
        JPanel root = new RoundedPanel(BG, 14);
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(BorderFactory.createEmptyBorder(14, 12, 10, 12));
        root.add(header());
        root.add(Box.createVerticalStrut(12));
        root.add(statsCard());
        root.add(Box.createVerticalStrut(10));
        root.add(row(Icon.WINDOW, "打开主界面", null, () -> actions.openPage("#/import")));
        root.add(row(Icon.IMPORT, "导入识别", null, () -> actions.openPage("#/import")));
        root.add(row(Icon.CHECK, "待确认", reviewBadge, () -> actions.openPage("#/review")));
        root.add(row(Icon.TABLE, "文件目录台账", null, () -> actions.openPage("#/ledger")));
        root.add(separator());
        root.add(row(Icon.FOLDER, "打开归档目录", null, actions::openArchiveDir));
        root.add(row(Icon.LOG, "打开日志目录", null, actions::openLogDir));
        root.add(separator());
        root.add(row(Icon.POWER, "退出", null, actions::exit, DANGER));
        root.setPreferredSize(new Dimension(WIDTH, root.getPreferredSize().height));
        return root;
    }

    private JComponent header() {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setOpaque(false);
        left.add(new LogoMark());
        JPanel titles = new JPanel(new GridLayout(2, 1, 0, 0));
        titles.setOpaque(false);
        titles.add(text(BrandIcon.FULL_NAME, 14, TEXT, true));
        titles.add(statusLine);
        left.add(titles);
        p.add(left, BorderLayout.WEST);
        JLabel gear = new JLabel(new IconGlyph(Icon.GEAR, MUTED));
        gear.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        gear.setToolTipText("系统设置");
        gear.addMouseListener(click(() -> actions.openPage("#/system")));
        p.add(gear, BorderLayout.EAST);
        fixHeight(p, 40);
        return p;
    }

    private JComponent statsCard() {
        JPanel card = new RoundedPanel(CARD, 10);
        card.setLayout(new GridLayout(1, 3));
        card.setBorder(BorderFactory.createEmptyBorder(10, 6, 10, 6));
        card.add(stat(readyValue, "待归档"));
        card.add(stat(reviewValue, "待确认"));
        card.add(stat(ledgerValue, "台账"));
        fixHeight(card, 64);
        return card;
    }

    private static JComponent stat(JLabel value, String label) {
        JPanel p = new JPanel(new GridLayout(2, 1));
        p.setOpaque(false);
        value.setHorizontalAlignment(SwingConstants.CENTER);
        p.add(value);
        JLabel l = text(label, 12, MUTED);
        l.setHorizontalAlignment(SwingConstants.CENTER);
        p.add(l);
        return p;
    }

    private JComponent row(Icon icon, String label, JComponent trailing, Runnable action) {
        return row(icon, label, trailing, action, TEXT);
    }

    private JComponent row(Icon icon, String label, JComponent trailing, Runnable action, Color color) {
        JPanel p = new RoundedPanel(null, 8);
        p.setLayout(new BorderLayout(10, 0));
        p.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
        p.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        JLabel ic = new JLabel(new IconGlyph(icon, color == DANGER ? DANGER : ACCENT));
        p.add(ic, BorderLayout.WEST);
        p.add(text(label, 13, color), BorderLayout.CENTER);
        if (trailing != null) {
            p.add(trailing, BorderLayout.EAST);
        }
        p.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                ((RoundedPanel) p).setFill(HOVER);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                ((RoundedPanel) p).setFill(null);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                ((RoundedPanel) p).setFill(null);
                close.run();
                new Thread(action, "tray-action").start();
            }
        });
        fixHeight(p, 36);
        return p;
    }

    private static JComponent separator() {
        JPanel p = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(LINE);
                g.fillRect(6, getHeight() / 2, getWidth() - 12, 1);
            }
        };
        p.setOpaque(false);
        fixHeight(p, 11);
        return p;
    }

    private static MouseAdapter click(Runnable r) {
        return new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent e) {
                new Thread(r, "tray-action").start();
            }
        };
    }

    private static void fixHeight(JComponent c, int h) {
        c.setPreferredSize(new Dimension(WIDTH - 24, h));
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
        c.setAlignmentX(0f);
    }

    private static JLabel value(String s) {
        JLabel l = new JLabel(s);
        l.setFont(font(20, true));
        l.setForeground(TEXT);
        return l;
    }

    static JLabel text(String s, int size, Color c) {
        return text(s, size, c, false);
    }

    static JLabel text(String s, int size, Color c, boolean bold) {
        JLabel l = new JLabel(s);
        l.setFont(font(size, bold));
        l.setForeground(c);
        return l;
    }

    /** 带圆角背景的面板；fill 为 null 时透明 */
    static class RoundedPanel extends JPanel {
        private Color fill;
        private final int arc;

        RoundedPanel(Color fill, int arc) {
            this.fill = fill;
            this.arc = arc;
            setOpaque(false);
        }

        void setFill(Color c) {
            this.fill = c;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (fill != null) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(fill);
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), arc, arc));
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    /** 待确认数量徽标 */
    static class Badge extends JComponent {
        private long count;

        Badge() {
            setPreferredSize(new Dimension(30, 20));
        }

        void setCount(long c) {
            count = c;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (count <= 0) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            String s = count > 99 ? "99+" : String.valueOf(count);
            g2.setFont(font(11, true));
            int w = Math.max(20, g2.getFontMetrics().stringWidth(s) + 12);
            int y = (getHeight() - 18) / 2;
            g2.setColor(new Color(0xF2, 0xB1, 0x34));
            g2.fillRoundRect(getWidth() - w, y, w, 18, 18, 18);
            g2.setColor(Color.WHITE);
            g2.drawString(s, getWidth() - w + (w - g2.getFontMetrics().stringWidth(s)) / 2, y + 13);
            g2.dispose();
        }
    }

    /** 标题栏左侧的 RGL 标志，与托盘图标一致 */
    static class LogoMark extends JComponent {
        LogoMark() {
            setPreferredSize(new Dimension(34, 34));
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.drawImage(TrayManager.createIcon(34), 0, 0, null);
        }
    }

    enum Icon { WINDOW, IMPORT, CHECK, TABLE, FOLDER, LOG, GEAR, POWER }

    /** 用线条画的小图标，避免依赖图片和 emoji 字体 */
    static class IconGlyph implements javax.swing.Icon {
        private final Icon kind;
        private final Color color;

        IconGlyph(Icon kind, Color color) {
            this.kind = kind;
            this.color = color;
        }

        @Override
        public int getIconWidth() {
            return 18;
        }

        @Override
        public int getIconHeight() {
            return 18;
        }

        @Override
        public void paintIcon(java.awt.Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.translate(x, y);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            switch (kind) {
                case WINDOW -> {
                    g2.drawRoundRect(2, 3, 14, 12, 3, 3);
                    g2.drawLine(2, 6, 16, 6);
                }
                case IMPORT -> {
                    g2.drawLine(9, 2, 9, 11);
                    g2.drawLine(5, 7, 9, 11);
                    g2.drawLine(13, 7, 9, 11);
                    g2.drawLine(3, 15, 15, 15);
                }
                case CHECK -> {
                    g2.drawOval(2, 2, 14, 14);
                    g2.drawLine(6, 9, 8, 12);
                    g2.drawLine(8, 12, 12, 6);
                }
                case TABLE -> {
                    g2.drawRoundRect(2, 3, 14, 12, 2, 2);
                    g2.drawLine(2, 7, 16, 7);
                    g2.drawLine(2, 11, 16, 11);
                    g2.drawLine(7, 3, 7, 15);
                }
                case FOLDER -> {
                    g2.drawLine(2, 5, 7, 5);
                    g2.drawLine(7, 5, 9, 7);
                    g2.drawRoundRect(2, 7, 14, 8, 2, 2);
                    g2.drawLine(2, 5, 2, 8);
                }
                case LOG -> {
                    g2.drawRoundRect(4, 2, 10, 14, 2, 2);
                    g2.drawLine(7, 6, 11, 6);
                    g2.drawLine(7, 9, 11, 9);
                    g2.drawLine(7, 12, 10, 12);
                }
                case GEAR -> {
                    g2.drawOval(6, 6, 6, 6);
                    for (int i = 0; i < 8; i++) {
                        double a = Math.PI / 4 * i;
                        g2.drawLine((int) Math.round(9 + 5 * Math.cos(a)), (int) Math.round(9 + 5 * Math.sin(a)),
                                (int) Math.round(9 + 7.5 * Math.cos(a)), (int) Math.round(9 + 7.5 * Math.sin(a)));
                    }
                }
                case POWER -> {
                    g2.drawArc(3, 4, 12, 12, 120, 300);
                    g2.drawLine(9, 2, 9, 9);
                }
            }
            g2.dispose();
        }
    }
}
