package com.baseball.presentation.desktop;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileLock;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Baseball Insight 제어판 - 서버 시작/종료/재시작, 브라우저 열기.
 */
public final class ControlPanelApp {

    private static final Color BG_TOP = new Color(0x0c, 0x12, 0x1c);
    private static final Color BG_BOTTOM = new Color(0x15, 0x1f, 0x2e);
    private static final Color CARD = new Color(0x1a, 0x24, 0x36);
    private static final Color CARD_BORDER = new Color(0x2d, 0x3a, 0x4f);
    private static final Color TEXT = new Color(0xf1, 0xf5, 0xf9);
    private static final Color TEXT_MUTED = new Color(0x94, 0xa3, 0xb8);
    /** 상태·포인트(실행 중) */
    private static final Color ACCENT_CYAN = new Color(0x22, 0xd3, 0xee);
    private static final String TITLE = "Baseball Insight - 제어판";
    private static final int SERVER_PORT = 8080;
    private static final String BASE_URL = "http://localhost:" + SERVER_PORT;
    /** 서버가 뜨는 CMD 창 제목 (재시작 시 이 제목으로 프로세스 종료) */
    private static final String SERVER_WINDOW_TITLE = "Baseball League Server";
    private static final Color WARNING = new Color(250, 204, 21);

    private enum BtnStyle {
        START(new Color(0x10, 0xb9, 0x81), new Color(0x05, 0x96, 0x69), Color.WHITE),
        RESTART(new Color(0xf5, 0x9e, 0x0b), new Color(0xd9, 0x77, 0x06), new Color(0x1c, 0x14, 0x0a)),
        STOP(new Color(0xef, 0x44, 0x44), new Color(0xdc, 0x26, 0x26), Color.WHITE),
        NEUTRAL(new Color(0x2a, 0x36, 0x4a), new Color(0x3d, 0x4d, 0x66), TEXT);

        final Color bg;
        final Color hover;
        final Color fg;

        BtnStyle(Color bg, Color hover, Color fg) {
            this.bg = bg;
            this.hover = hover;
            this.fg = fg;
        }
    }

    private Process serverProcess; // CMD 창으로 띄울 때는 사용 안 함 (null)
    private JFrame frame;
    private JLabel statusLabel;
    private JLabel restartHintLabel; // "재시작 필요" 안내
    private JButton startBtn;
    private JButton restartBtn;
    private JButton stopBtn;
    /** 서버 기동 직후 ~ HTTP 응답 확인 전 */
    private volatile boolean serverStartPending;
    private TrayIcon trayIcon;
    private static final int SERVER_CHECK_INTERVAL_MS = 800;
    private static final int SERVER_CHECK_MAX_ATTEMPTS = 40; // 약 30초
    private volatile boolean restartNeeded; // JAR가 새로 빌드되어 재시작 권장
    private Path serverStartedJarPath;   // 서버 시작 시 사용한 JAR
    private long serverStartedJarMtime; // 서버 시작 시 JAR 수정 시각
    private File lockFile; // 단일 인스턴스용
    private java.io.RandomAccessFile lockRaf;
    private FileLock instanceLock;

    public static void main(String[] args) {
        try {
            System.setProperty("apple.laf.useScreenMenuBar", "true");
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) { }
            SwingUtilities.invokeLater(() -> {
                try {
                    ControlPanelApp app = new ControlPanelApp();
                    if (!app.trySingleInstanceLock()) {
                        JOptionPane.showMessageDialog(null,
                                "제어판이 이미 실행 중입니다.\n작업 표시줄에서 기존 창을 찾아 사용하세요.",
                                "이미 실행 중", JOptionPane.INFORMATION_MESSAGE);
                        return;
                    }
                    app.buildAndShow();
                } catch (Throwable t) {
                    String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getName();
                    JOptionPane.showMessageDialog(null,
                            "제어판 시작 중 오류:\n" + msg,
                            "오류", JOptionPane.ERROR_MESSAGE);
                    t.printStackTrace();
                }
            });
        } catch (Throwable t) {
            String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getName();
            JOptionPane.showMessageDialog(null,
                    "제어판 시작 중 오류:\n" + msg,
                    "오류", JOptionPane.ERROR_MESSAGE);
            t.printStackTrace();
        }
    }

    /** 한 번에 하나의 제어판만 실행되도록 락 파일 사용. true = 이 인스턴스가 실행 권한 획득 */
    private boolean trySingleInstanceLock() {
        try {
            String tmp = System.getProperty("java.io.tmpdir", "");
            if (tmp == null || tmp.isEmpty()) return true;
            lockFile = new File(tmp, "baseball-control-panel.lock");
            lockRaf = new java.io.RandomAccessFile(lockFile, "rw");
            instanceLock = lockRaf.getChannel().tryLock();
            if (instanceLock == null) {
                try { lockRaf.close(); } catch (IOException ignored) { }
                lockRaf = null;
                return false;
            }
            return true;
        } catch (Throwable e) {
            try { if (lockRaf != null) lockRaf.close(); } catch (IOException ignored) { }
            lockRaf = null;
            instanceLock = null;
            return true; // 락 실패 시에도 실행은 허용 (다른 PC/권한 이슈 대비)
        }
    }

    private void buildAndShow() {
        frame = new JFrame(TITLE);
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                int c = JOptionPane.showConfirmDialog(frame,
                        "제어판을 종료하면 서버는 계속 실행됩니다.\n종료하시겠습니까?",
                        "종료 확인", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
                if (c == JOptionPane.YES_OPTION) {
                    releaseInstanceLock();
                    if (trayIcon != null) {
                        SystemTray.getSystemTray().remove(trayIcon);
                    }
                    frame.dispose();
                    System.exit(0);
                }
            }
        });

        JPanel main = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (g instanceof Graphics2D g2) {
                    g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                    g2.setPaint(new GradientPaint(0, 0, BG_TOP, getWidth(), getHeight(), BG_BOTTOM, true));
                    g2.fillRect(0, 0, getWidth(), getHeight());
                } else {
                    g.setColor(BG_TOP);
                    g.fillRect(0, 0, getWidth(), getHeight());
                }
            }
        };
        main.setLayout(new BoxLayout(main, BoxLayout.Y_AXIS));
        main.setOpaque(false);
        main.setBorder(BorderFactory.createEmptyBorder(22, 28, 22, 28));

        JLabel titleLabel = new JLabel("⚾ Baseball Insight");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 22f));
        titleLabel.setForeground(TEXT);
        titleLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        main.add(titleLabel);
        main.add(Box.createVerticalStrut(4));

        JLabel subLabel = new JLabel("로컬 서버 제어");
        subLabel.setFont(subLabel.getFont().deriveFont(Font.PLAIN, 13f));
        subLabel.setForeground(TEXT_MUTED);
        subLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        main.add(subLabel);
        main.add(Box.createVerticalStrut(20));

        statusLabel = new JLabel("서버: 중지됨");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 14f));
        statusLabel.setForeground(TEXT_MUTED);
        statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        main.add(statusLabel);
        restartHintLabel = new JLabel(" ");
        restartHintLabel.setFont(restartHintLabel.getFont().deriveFont(Font.PLAIN, 12f));
        restartHintLabel.setForeground(WARNING);
        restartHintLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        main.add(restartHintLabel);
        main.add(Box.createVerticalStrut(16));

        startBtn = createStyledButton("서버 시작", BtnStyle.START, this::startServer);
        restartBtn = createStyledButton("서버 재시작", BtnStyle.RESTART, this::restartServer);
        stopBtn = createStyledButton("서버 종료", BtnStyle.STOP, this::stopServer);
        restartBtn.setEnabled(false);
        stopBtn.setEnabled(false);
        JButton browserBtn = createStyledButton("브라우저 열기", BtnStyle.NEUTRAL, this::openBrowser);

        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setOpaque(true);
        card.setBackground(CARD);
        card.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(CARD_BORDER, 1),
                BorderFactory.createEmptyBorder(18, 18, 18, 18)));
        card.setMaximumSize(new Dimension(Short.MAX_VALUE, Short.MAX_VALUE));

        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        row1.setOpaque(false);
        row1.add(startBtn);
        row1.add(stopBtn);
        row1.add(restartBtn);
        card.add(row1);
        card.add(Box.createVerticalStrut(10));
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        row2.setOpaque(false);
        row2.add(browserBtn);
        card.add(row2);

        main.add(card);

        frame.getContentPane().setBackground(BG_TOP);
        frame.getContentPane().add(main);
        frame.setResizable(false);
        frame.pack();
        frame.setSize(520, 320);
        frame.setLocationRelativeTo(null);

        setupTray();
        frame.setVisible(true);
        // 제어판을 나중에 열었을 때 이미 서버가 떠 있으면 버튼 상태 맞춤
        CompletableFuture.runAsync(() -> {
            if (isServerResponding()) {
                SwingUtilities.invokeLater(() -> {
                    serverStartPending = false;
                    setStatus("서버: 실행 중", ACCENT_CYAN);
                    startBtn.setEnabled(false);
                    restartBtn.setEnabled(true);
                    stopBtn.setEnabled(true);
                    startServerStatusChecker();
                });
            }
        });
    }

    private JButton createStyledButton(String text, BtnStyle style, Runnable action) {
        JButton b = new JButton(text);
        b.setFont(b.getFont().deriveFont(Font.BOLD, 12f));
        b.setBackground(style.bg);
        b.setForeground(style.fg);
        b.setFocusPainted(false);
        b.setBorderPainted(false);
        b.setOpaque(true);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setBorder(BorderFactory.createEmptyBorder(11, 18, 11, 18));
        b.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                if (!b.isEnabled()) {
                    return;
                }
                b.setBackground(style.hover);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                b.setBackground(b.isEnabled() ? style.bg : new Color(0x4b, 0x55, 0x63));
            }
        });
        b.addPropertyChangeListener("enabled", e -> {
            boolean en = b.isEnabled();
            b.setForeground(en ? style.fg : new Color(0x9c, 0xa8, 0xb3));
            b.setBackground(en ? style.bg : new Color(0x3a, 0x45, 0x58));
        });
        b.addActionListener(e -> action.run());
        return b;
    }

    private Path findServerJar() {
        Path target = Path.of("target");
        if (!Files.isDirectory(target)) {
            return null;
        }
        try (Stream<Path> list = Files.list(target)) {
            Optional<Path> jar = list
                    .filter(p -> p.getFileName().toString().startsWith("baseball-league-analysis-")
                            && p.getFileName().toString().endsWith(".jar")
                            && !p.getFileName().toString().contains("original"))
                    .findFirst();
            return jar.orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private void startServer() {
        if (serverStartPending && !isServerResponding()) {
            JOptionPane.showMessageDialog(frame, "서버가 아직 시작되는 중입니다. 잠시만 기다려 주세요.", "알림", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (isServerResponding()) {
            JOptionPane.showMessageDialog(frame, "서버가 이미 실행 중입니다.", "알림", JOptionPane.INFORMATION_MESSAGE);
            serverStartPending = false;
            startBtn.setEnabled(false);
            restartBtn.setEnabled(true);
            stopBtn.setEnabled(true);
            return;
        }
        Path projectDir = Path.of(System.getProperty("user.dir"));
        boolean useMaven = Files.exists(projectDir.resolve("pom.xml"));
        String workDirPath = projectDir.toAbsolutePath().toString();

        if (useMaven) {
            // 소스 기준 실행: target/classes 사용 → UI/리소스 변경 시 재시작만 하면 반영
            serverStartedJarPath = null;
            restartNeeded = false;
            updateRestartHintLabel();
            File batchFile = createMavenServerBatchFile(workDirPath);
            if (batchFile == null) {
                JOptionPane.showMessageDialog(frame, "Maven 서버 실행용 배치 파일을 만들 수 없습니다.", "오류", JOptionPane.ERROR_MESSAGE);
                return;
            }
            ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "start", "\"" + SERVER_WINDOW_TITLE + "\"", "cmd", "/k", "\"" + batchFile.getAbsolutePath() + "\"");
            startServerProcess(pb, null);
            return;
        }

        Path jar = findServerJar();
        if (jar == null) {
            JOptionPane.showMessageDialog(frame,
                    "실행 가능한 JAR이 없습니다.\n\n프로젝트 폴더에서 제어판을 실행했는지 확인하세요.\n(제어판은 pom.xml이 있는 폴더에서 띄우면 서버를 JAR 없이 실행합니다.)\n\nJAR만 쓰려면: mvn package",
                    "JAR 없음", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            serverStartedJarPath = jar;
            serverStartedJarMtime = Files.getLastModifiedTime(jar).toMillis();
        } catch (IOException ignored) { }
        restartNeeded = false;
        updateRestartHintLabel();
        String javaHome = System.getProperty("java.home");
        File binDir = new File(javaHome, "bin");
        File javaExe = new File(binDir, "java.exe");
        String javaCmd = javaExe.getAbsolutePath();
        File workDir = jar.getParent() != null && jar.getParent().getParent() != null
                ? jar.getParent().getParent().toFile()
                : new File(workDirPath);
        String jarPath = jar.toAbsolutePath().toString();

        ProcessBuilder pb;
        if (isWindows()) {
            File batchFile = createServerBatchFile(workDirPath, javaCmd, jarPath);
            if (batchFile == null) {
                JOptionPane.showMessageDialog(frame, "서버 실행용 배치 파일을 만들 수 없습니다.", "오류", JOptionPane.ERROR_MESSAGE);
                return;
            }
            pb = new ProcessBuilder("cmd", "/c", "start", "\"" + SERVER_WINDOW_TITLE + "\"", "cmd", "/k", "\"" + batchFile.getAbsolutePath() + "\"");
        } else {
            pb = new ProcessBuilder(javaCmd, "-jar", jarPath).directory(workDir);
        }
        startServerProcess(pb, workDir);
    }

    private void startServerProcess(ProcessBuilder pb, File workDir) {
        if (workDir != null) {
            pb.directory(workDir);
        }
        if (!isWindows()) {
            pb.redirectErrorStream(true);
        }
        try {
            serverProcess = pb.start();
            if (isWindows()) {
                serverProcess = null;
            }
            serverStartPending = true;
            setStatus("서버: 시작 중...", TEXT_MUTED);
            startBtn.setEnabled(false);
            restartBtn.setEnabled(false);
            stopBtn.setEnabled(false);

            if (!isWindows() && serverProcess != null) {
                serverProcess.onExit().thenRun(() -> SwingUtilities.invokeLater(() -> {
                    if (serverProcess != null && !serverProcess.isAlive()) {
                        serverProcess = null;
                        serverStartPending = false;
                        setStatus("서버: 중지됨", TEXT_MUTED);
                        startBtn.setEnabled(true);
                        restartBtn.setEnabled(false);
                        stopBtn.setEnabled(false);
                    }
                }));
            }

            CompletableFuture.runAsync(() -> {
                for (int i = 0; i < SERVER_CHECK_MAX_ATTEMPTS; i++) {
                    try {
                        Thread.sleep(SERVER_CHECK_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (!isWindows() && (serverProcess == null || !serverProcess.isAlive())) {
                        SwingUtilities.invokeLater(() -> {
                            serverStartPending = false;
                            setStatus("서버: 중지됨 (시작 실패)", TEXT_MUTED);
                            startBtn.setEnabled(true);
                            restartBtn.setEnabled(false);
                            stopBtn.setEnabled(false);
                        });
                        return;
                    }
                    if (isServerResponding()) {
                        SwingUtilities.invokeLater(() -> {
                            serverStartPending = false;
                            setStatus("서버: 실행 중", ACCENT_CYAN);
                            updateRestartHintLabel();
                            startBtn.setEnabled(false);
                            restartBtn.setEnabled(true);
                            stopBtn.setEnabled(true);
                            startServerStatusChecker();
                        });
                        return;
                    }
                }
                SwingUtilities.invokeLater(() -> {
                    serverStartPending = false;
                    setStatus("서버: 응답 없음 (CMD 창·포트 확인)", WARNING);
                    startBtn.setEnabled(true);
                    restartBtn.setEnabled(false);
                    stopBtn.setEnabled(false);
                });
            });
        } catch (IOException e) {
            JOptionPane.showMessageDialog(frame, "서버 시작 실패: " + e.getMessage(), "오류", JOptionPane.ERROR_MESSAGE);
            serverStartPending = false;
            startBtn.setEnabled(true);
            restartBtn.setEnabled(false);
            stopBtn.setEnabled(false);
        }
    }

    /** Maven으로 서버 실행 (target/classes 기준 → UI 변경이 재시작만으로 반영됨) */
    private File createMavenServerBatchFile(String workDirPath) {
        try {
            File tmp = new File(System.getProperty("java.io.tmpdir"));
            File batch = new File(tmp, "baseball-maven-server-" + System.currentTimeMillis() + ".bat");
            try (PrintWriter w = new PrintWriter(batch, StandardCharsets.UTF_8)) {
                w.println("@echo off");
                w.println("chcp 65001 >nul");
                w.println("cd /d \"" + workDirPath.replace("\"", "\"\"") + "\"");
                w.println("echo [Baseball Insight] 최신 코드 반영 후 서버 시작...");
                w.println("call mvn -q compile");
                w.println("if errorlevel 1 ( echo 컴파일 실패. pause & exit /b 1 )");
                w.println("call mvn spring-boot:run");
                w.println("if errorlevel 1 (");
                w.println("  echo.");
                w.println("  echo [서버가 비정상 종료되었습니다. 위 메시지를 확인하세요.]");
                w.println("  pause");
                w.println(")");
            }
            return batch;
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().startsWith("windows");
    }

    /** 서버를 CMD에서 실행할 배치 파일 생성 (경로 공백·한글 처리, 실패 시 pause로 창 유지) */
    private File createServerBatchFile(String workDirPath, String javaExePath, String jarPath) {
        try {
            File tmp = new File(System.getProperty("java.io.tmpdir"));
            File batch = new File(tmp, "baseball-league-server-" + System.currentTimeMillis() + ".bat");
            try (PrintWriter w = new PrintWriter(batch, StandardCharsets.UTF_8)) {
                w.println("@echo off");
                w.println("chcp 65001 >nul");
                w.println("cd /d \"" + workDirPath.replace("\"", "\"\"") + "\"");
                w.println("\"" + javaExePath.replace("\"", "\"\"") + "\" -jar \"" + jarPath.replace("\"", "\"\"") + "\"");
                w.println("if errorlevel 1 (");
                w.println("  echo.");
                w.println("  echo [서버가 비정상 종료되었습니다. 위 메시지를 확인하세요.]");
                w.println("  pause");
                w.println(")");
            }
            return batch;
        } catch (IOException e) {
            return null;
        }
    }

    private volatile boolean statusCheckerRunning;

    /** 서버가 CMD 창으로 떠 있을 때, 주기적으로 응답 확인해 종료되면 UI 복구. JAR 재빌드 시 재시작 필요 안내. */
    private void startServerStatusChecker() {
        if (statusCheckerRunning || !isWindows()) {
            return;
        }
        statusCheckerRunning = true;
        CompletableFuture.runAsync(() -> {
            try {
                while (statusCheckerRunning) {
                    Thread.sleep(5000);
                    if (!statusCheckerRunning) {
                        break;
                    }
                    if (!isServerResponding()) {
                        SwingUtilities.invokeLater(() -> {
                            serverStartPending = false;
                            setStatus("서버: 중지됨", TEXT_MUTED);
                            restartHintLabel.setText(" ");
                            startBtn.setEnabled(true);
                            restartBtn.setEnabled(false);
                            stopBtn.setEnabled(false);
                        });
                        statusCheckerRunning = false;
                        return;
                    }
                    // JAR가 새로 빌드되었으면 재시작 필요 표시
                    if (serverStartedJarPath != null && Files.exists(serverStartedJarPath)) {
                        try {
                            long current = Files.getLastModifiedTime(serverStartedJarPath).toMillis();
                            if (current > serverStartedJarMtime) {
                                restartNeeded = true;
                                SwingUtilities.invokeLater(this::updateRestartHintLabel);
                            }
                        } catch (IOException ignored) { }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                statusCheckerRunning = false;
            }
        });
    }

    private void updateRestartHintLabel() {
        if (restartHintLabel == null) return;
        if (restartNeeded && isServerResponding()) {
            if (statusLabel != null) {
                statusLabel.setText("서버: 실행 중 (재시작 필요)");
                statusLabel.setForeground(WARNING);
            }
            restartHintLabel.setText("⚠ 새로 빌드됨. 서버를 재시작하세요.");
        } else {
            if (statusLabel != null && WARNING.equals(statusLabel.getForeground())) {
                statusLabel.setForeground(ACCENT_CYAN);
                statusLabel.setText("서버: 실행 중");
            }
            restartHintLabel.setText(" ");
        }
    }

    private void setStatus(String text, Color color) {
        if (statusLabel != null) {
            statusLabel.setText(text);
            statusLabel.setForeground(color);
        }
    }

    private boolean isServerResponding() {
        try {
            URL url = new URL(BASE_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.connect();
            int code = conn.getResponseCode();
            conn.disconnect();
            return code >= 200 && code < 400;
        } catch (Exception e) {
            return false;
        }
    }

    private void releaseInstanceLock() {
        try {
            if (instanceLock != null) instanceLock.release();
            if (lockRaf != null) lockRaf.close();
        } catch (Exception ignored) { }
    }

    private void restartServer() {
        if (!isServerResponding()) {
            JOptionPane.showMessageDialog(frame, "서버가 실행 중이 아닙니다. [서버 시작]을 사용하세요.", "알림", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        killServerProcessesQuietly();
        serverStartPending = false;
        setStatus("서버: 중지됨", TEXT_MUTED);
        restartHintLabel.setText(" ");
        updateRestartHintLabel();
        startBtn.setEnabled(true);
        restartBtn.setEnabled(false);
        stopBtn.setEnabled(false);
        startServer();
    }

    private void stopServer() {
        if (!isServerResponding()) {
            JOptionPane.showMessageDialog(frame, "서버가 실행 중이 아닙니다.", "알림", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int c = JOptionPane.showConfirmDialog(frame,
                "포트 " + SERVER_PORT + "에서 실행 중인 서버를 종료할까요?",
                "서버 종료",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE);
        if (c != JOptionPane.YES_OPTION) {
            return;
        }
        killServerProcessesQuietly();
        serverStartPending = false;
        restartNeeded = false;
        updateRestartHintLabel();
        setStatus("서버: 중지됨", TEXT_MUTED);
        restartHintLabel.setText(" ");
        startBtn.setEnabled(true);
        restartBtn.setEnabled(false);
        stopBtn.setEnabled(false);
    }

    /**
     * Spring Boot(8080 LISTENING) 및 연관 프로세스 종료. Windows에서는 최대 ~2초 대기.
     * 재시작/종료 직전에 호출. (EDT에서 호출 시 잠깐 멈출 수 있음)
     */
    private void killServerProcessesQuietly() {
        statusCheckerRunning = false;
        restartNeeded = false;
        if (serverProcess != null && serverProcess.isAlive()) {
            serverProcess.destroyForcibly();
            try {
                serverProcess.waitFor();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            serverProcess = null;
        }
        if (isWindows()) {
            try {
                int killed = killWindowsProcessesListeningOnPort(SERVER_PORT);
                if (killed == 0) {
                    new ProcessBuilder("cmd", "/c",
                            "taskkill /FI \"WINDOWTITLE eq " + SERVER_WINDOW_TITLE + "*\" /F")
                            .redirectErrorStream(true)
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .start()
                            .waitFor();
                }
                Thread.sleep(2000);
            } catch (Exception ignored) { }
        }
    }

    /**
     * Windows netstat 출력에서 {@code :port} 로 LISTENING 중인 PID를 찾아 /T(자식 포함)로 종료.
     * @return 종료를 시도한 서로 다른 PID 수
     */
    private static int killWindowsProcessesListeningOnPort(int port) {
        Pattern portListening = Pattern.compile(":\\s*" + port + "\\s+\\S+\\s+LISTENING\\s+(\\d+)\\s*$");
        Charset cs = Charset.forName(System.getProperty("sun.stdout.encoding",
                System.getProperty("native.encoding", StandardCharsets.UTF_8.name())));
        Set<Integer> pids = new HashSet<>();
        try {
            Process netstat = new ProcessBuilder("cmd", "/c", "chcp 65001>nul & netstat -ano")
                    .redirectErrorStream(true)
                    .start();
            String out = new String(netstat.getInputStream().readAllBytes(), cs);
            netstat.waitFor();
            for (String line : out.split("\r?\n")) {
                Matcher m = portListening.matcher(line.trim());
                if (m.find()) {
                    pids.add(Integer.parseInt(m.group(1)));
                }
            }
        } catch (Exception ignored) {
            return 0;
        }
        int n = 0;
        for (int pid : pids) {
            try {
                new ProcessBuilder("taskkill", "/PID", String.valueOf(pid), "/T", "/F")
                        .redirectErrorStream(true)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start()
                        .waitFor();
                n++;
            } catch (Exception ignored) { }
        }
        return n;
    }

    private void openBrowser() {
        try {
            Desktop.getDesktop().browse(URI.create(BASE_URL));
        } catch (IOException e) {
            JOptionPane.showMessageDialog(frame, "브라우저 열기 실패: " + e.getMessage(), "오류", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void setupTray() {
        if (!SystemTray.isSupported()) {
            return;
        }
        Image image = frame.createImage(16, 16);
        Graphics2D g = (Graphics2D) image.getGraphics();
        g.setColor(ACCENT_CYAN);
        g.fillOval(0, 0, 16, 16);
        g.dispose();
        PopupMenu menu = new PopupMenu();
        MenuItem showItem = new MenuItem("제어판 열기");
        showItem.addActionListener(e -> showFromTray());
        MenuItem exitItem = new MenuItem("종료");
        exitItem.addActionListener(e -> {
            if (serverProcess != null && serverProcess.isAlive()) {
                serverProcess.destroyForcibly();
            }
            releaseInstanceLock();
            SystemTray.getSystemTray().remove(trayIcon);
            frame.dispose();
            System.exit(0);
        });
        menu.add(showItem);
        menu.addSeparator();
        menu.add(exitItem);
        trayIcon = new TrayIcon(image, TITLE, menu);
        trayIcon.setImageAutoSize(true);
        trayIcon.addActionListener(e -> showFromTray());
        try {
            SystemTray.getSystemTray().add(trayIcon);
        } catch (AWTException ignored) { }
    }

    private void showFromTray() {
        frame.setVisible(true);
        frame.toFront();
        frame.requestFocus();
    }
}
