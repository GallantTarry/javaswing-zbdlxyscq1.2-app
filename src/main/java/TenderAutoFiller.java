import com.formdev.flatlaf.FlatDarkLaf;
import org.apache.poi.xwpf.usermodel.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.*;
import java.awt.image.FilteredImageSource;
import java.awt.image.ImageFilter;
import java.awt.image.ImageProducer;
import java.awt.image.RGBImageFilter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class TenderAutoFiller extends JFrame {

    private JList<File> fileList;
    private DefaultListModel<File> listModel;
    private JTextArea logArea;
    private JButton btnProcess, btnClear;
    private File desktopOutputDir;
    private Image backgroundImage;

    // 托盘相关组件
    private SystemTray systemTray;
    private TrayIcon trayIcon;

    public TenderAutoFiller() {
        initDesktopFolder();
        loadBackgroundImage();
        initUI();
        loadAppIcon();
        initSystemTray(); // 初始化系统托盘
    }

    private void initDesktopFolder() {
        File desktop = new File(System.getProperty("user.home"), "Desktop");
        desktopOutputDir = new File(desktop, "招标响应结果_已响应");
    }

    private void loadBackgroundImage() {
        try {
            URL bgUrl = TenderAutoFiller.class.getResource("/texture2.png");
            if (bgUrl != null) {
                backgroundImage = new ImageIcon(bgUrl).getImage();
            }
        } catch (Exception e) {
            System.err.println("未找到 texture2.png 背景图，将使用纯色背景。");
        }
    }

    private void initUI() {
        setTitle("智能招标文档响应生成器");
        setSize(850, 600);
        // 如果想让点击关闭按钮(X)也最小化到托盘，可以把下面的 EXIT_ON_CLOSE 改为 HIDE_ON_CLOSE
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        JPanel contentPane = new JPanel(new BorderLayout(12, 12)) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (backgroundImage != null) {
                    g.drawImage(backgroundImage, 0, 0, getWidth(), getHeight(), this);
                }
            }
        };
        contentPane.setBorder(new EmptyBorder(15, 15, 15, 15));
        setContentPane(contentPane);

        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 0));
        topPanel.setOpaque(false);

        btnProcess = new JButton("一键批量生成响应件");
        btnClear = new JButton("清空列表");
        btnProcess.setEnabled(false);

        btnProcess.setFont(new Font("Microsoft YaHei", Font.BOLD, 14));
        btnClear.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));

        btnProcess.setBackground(new Color(60, 100, 200));
        btnProcess.setForeground(Color.WHITE);

        topPanel.add(btnProcess);
        topPanel.add(btnClear);

        Color semiTransparentDark = new Color(30, 32, 36, 210);

        listModel = new DefaultListModel<>();
        fileList = new JList<>(listModel);
        fileList.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        fileList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        fileList.setBackground(semiTransparentDark);
        fileList.setForeground(new Color(220, 220, 220));

        fileList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof File) {
                    label.setText(((File) value).getName() + " [" + ((File) value).getParentFile().getName() + "]");
                }
                label.setOpaque(isSelected);
                return label;
            }
        });

        JScrollPane listScroll = new JScrollPane(fileList);
        listScroll.setBorder(BorderFactory.createTitledBorder("待处理队列（拖拽文件夹至此）"));
        listScroll.setPreferredSize(new Dimension(360, 0));
        makeScrollPaneTransparent(listScroll);

        enableDragAndDrop();

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 13));
        logArea.setBackground(semiTransparentDark);
        logArea.setForeground(new Color(150, 210, 150));

        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("实时处理日志"));
        makeScrollPaneTransparent(logScroll);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, logScroll);
        splitPane.setDividerLocation(360);
        splitPane.setOpaque(false);
        splitPane.setBackground(new Color(0,0,0,0));

        add(topPanel, BorderLayout.NORTH);
        add(splitPane, BorderLayout.CENTER);

        btnProcess.addActionListener(e -> processFilesAsync());
        btnClear.addActionListener(e -> {
            listModel.clear();
            btnProcess.setEnabled(false);
            logArea.append("队列已清空。\n");
        });

        logArea.append("【系统提示】请将包含招标文件的文件夹拖入左侧窗口。\n");
        logArea.append("【少侠提醒】下方为处理日志。\n\n");
    }

    // --- 新增：系统托盘初始化 ---
    private void initSystemTray() {
        if (!SystemTray.isSupported()) {
            System.err.println("当前系统不支持系统托盘");
            return;
        }

        systemTray = SystemTray.getSystemTray();

        // 1. 创建右键弹出菜单
        PopupMenu popupMenu = new PopupMenu();
        MenuItem showItem = new MenuItem("OPEN APPLICATION");
        MenuItem exitItem = new MenuItem("EXIT");

        showItem.addActionListener(e -> restoreFromTray());
        exitItem.addActionListener(e -> System.exit(0));

        popupMenu.add(showItem);
        popupMenu.addSeparator();
        popupMenu.add(exitItem);

        // 2. 获取托盘图标 (复用 logo.png)
        Image trayImage = null;
        try {
            URL iconURL = getClass().getResource("/logo.png");
            if (iconURL != null) {
                trayImage = new ImageIcon(iconURL).getImage();
            } else {
                // 如果没图，创建一个临时的透明图防止崩溃
                trayImage = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        trayIcon = new TrayIcon(trayImage, "智能招标文档响应生成器", popupMenu);
        trayIcon.setImageAutoSize(true);
        // 双击或单击托盘图标恢复窗口
        trayIcon.addActionListener(e -> restoreFromTray());

        // 3. 监听窗口状态，拦截最小化事件
        addWindowStateListener(e -> {
            // 如果窗口状态变为最小化 (ICONIFIED)
            if (e.getNewState() == Frame.ICONIFIED) {
                try {
                    systemTray.add(trayIcon); // 添加图标到托盘
                    setVisible(false);        // 隐藏主窗口
                } catch (AWTException ex) {
                    System.err.println("无法添加到系统托盘");
                }
            }
        });
    }

    // --- 新增：从托盘恢复窗口 ---
    private void restoreFromTray() {
        systemTray.remove(trayIcon); // 从托盘移除图标
        setVisible(true);            // 显示主窗口
        setExtendedState(JFrame.NORMAL); // 恢复窗口正常状态
        toFront();                   // 窗口置顶
    }

    private void makeScrollPaneTransparent(JScrollPane scrollPane) {
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);
    }

    private void loadAppIcon() {
        try {
            URL iconURL = getClass().getResource("/logo.png");
            if (iconURL != null) {
                ImageIcon icon = new ImageIcon(iconURL);
                setIconImage(icon.getImage());
            }
        } catch (Exception e) {
            System.err.println("加载 logo.png 时发生异常: " + e.getMessage());
        }
    }

    private void enableDragAndDrop() {
        new DropTarget(fileList, DnDConstants.ACTION_COPY, new DropTargetAdapter() {
            @Override
            public void drop(DropTargetDropEvent dtde) {
                try {
                    if (dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        dtde.acceptDrop(DnDConstants.ACTION_COPY);
                        @SuppressWarnings("unchecked")
                        List<File> droppedFiles = (List<File>) dtde.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);

                        int addedCount = 0;
                        for (File file : droppedFiles) {
                            addedCount += parseDroppedItem(file);
                        }

                        if (addedCount > 0) {
                            logArea.append("▶ 成功解析：新增了 " + addedCount + " 个有效招标文件。\n");
                            btnProcess.setEnabled(true);
                        }
                    } else {
                        dtde.rejectDrop();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    private int parseDroppedItem(File file) {
        int count = 0;
        if (file.isDirectory()) {
            File[] subFiles = file.listFiles();
            if (subFiles != null) {
                for (File sub : subFiles) {
                    count += parseDroppedItem(sub);
                }
            }
        } else {
            String name = file.getName();
            if (name.contains("招标") && name.endsWith(".docx") && !name.startsWith("~$")) {
                if (!listModel.contains(file)) {
                    listModel.addElement(file);
                    count++;
                }
            }
        }
        return count;
    }

    private void processFilesAsync() {
        btnProcess.setEnabled(false);
        btnClear.setEnabled(false);
        fileList.setEnabled(false);

        if (!desktopOutputDir.exists()) {
            desktopOutputDir.mkdirs();
        }

        List<File> tasks = new ArrayList<>();
        for (int i = 0; i < listModel.getSize(); i++) {
            tasks.add(listModel.getElementAt(i));
        }

        SwingWorker<Void, String> worker = new SwingWorker<Void, String>() {
            @Override
            protected Void doInBackground() throws Exception {
                publish("🚀 开始批量生成，目标：" + desktopOutputDir.getAbsolutePath() + "\n");

                for (File file : tasks) {
                    publish("分析中: " + file.getName());
                    try {
                        executeWordGridRepair(file);
                        publish("  ↳  ✅ 已响应并存入输出目录。");
                    } catch (Exception ex) {
                        publish("  ↳  ❌ 错误: " + ex.getMessage());
                    }
                }
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                for (String msg : chunks) {
                    logArea.append(msg + "\n");
                }
                logArea.setCaretPosition(logArea.getDocument().getLength());
            }

            @Override
            protected void done() {
                logArea.append("\n🎉 所有任务处理完毕！\n");
                btnProcess.setEnabled(true);
                btnClear.setEnabled(true);
                fileList.setEnabled(true);

                try {
                    Desktop.getDesktop().open(desktopOutputDir);
                } catch (Exception ignored) {}
            }
        };
        worker.execute();
    }

    private void executeWordGridRepair(File file) throws Exception {
        try (FileInputStream fis = new FileInputStream(file);
             XWPFDocument doc = new XWPFDocument(fis)) {

            for (XWPFTable table : doc.getTables()) {
                if (table.getRows().isEmpty()) continue;

                XWPFTableRow headerRow = table.getRow(0);
                int targetGridCol = -1;
                int currentHeaderCol = 0;
                boolean skipThisTable = false;

                for (XWPFTableCell cell : headerRow.getTableCells()) {
                    String text = cell.getText().replaceAll("\\s+", "");
                    int span = 1;
                    if (cell.getCTTc().getTcPr() != null && cell.getCTTc().getTcPr().getGridSpan() != null) {
                        span = cell.getCTTc().getTcPr().getGridSpan().getVal().intValue();
                    }

                    if (text.contains("标准参数值")) {
                        targetGridCol = currentHeaderCol;
                    }
                    if (text.contains("投标人保证值") || text.contains("投标人响应值")) {
                        skipThisTable = true;
                        break;
                    }
                    currentHeaderCol += span;
                }

                if (targetGridCol != -1 && !skipThisTable) {
                    XWPFTableCell newHeaderCell = headerRow.addNewTableCell();
                    newHeaderCell.setText("投标人响应值");

                    for (int j = 1; j < table.getRows().size(); j++) {
                        XWPFTableRow row = table.getRow(j);
                        if (row.getTableCells().size() <= 2) {
                            row.addNewTableCell().setText("");
                            continue;
                        }

                        String stdValue = "";
                        int currentRowCol = 0;

                        for (XWPFTableCell cell : row.getTableCells()) {
                            int span = 1;
                            if (cell.getCTTc().getTcPr() != null && cell.getCTTc().getTcPr().getGridSpan() != null) {
                                span = cell.getCTTc().getTcPr().getGridSpan().getVal().intValue();
                            }
                            if (targetGridCol >= currentRowCol && targetGridCol < currentRowCol + span) {
                                stdValue = cell.getText().trim();
                                break;
                            }
                            currentRowCol += span;
                        }

                        XWPFTableCell newCell = row.addNewTableCell();
                        newCell.setText(stdValue);
                    }
                }
            }

            File outFile = new File(desktopOutputDir, file.getName().replace(".docx", "_已响应.docx"));
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                doc.write(fos);
            }
        }
    }

    public static void main(String[] args) {
        showSplashScreen();

        try {
            UIManager.put("Button.arc", 12);
            UIManager.put("Component.arc", 12);
            UIManager.put("TextComponent.arc", 12);
            UIManager.put("ScrollBar.thumbArc", 12);
            FlatDarkLaf.setup();
        } catch (Exception e) {
            System.err.println("FlatLaf 皮肤装配失败。");
        }

        SwingUtilities.invokeLater(() -> {
            new TenderAutoFiller().setVisible(true);
        });
    }

    private static void showSplashScreen() {
        JWindow splash = new JWindow();
        splash.setBackground(new Color(0, 0, 0, 0));

        try {
            URL gifUrl = TenderAutoFiller.class.getResource("/logo.gif");
            if (gifUrl != null) {
                Image rawImage = new ImageIcon(gifUrl).getImage();

                ImageFilter filter = new RGBImageFilter() {
                    @Override
                    public int filterRGB(int x, int y, int rgb) {
                        if ((rgb | 0xFF000000) == 0xFFFFFFFF) {
                            return 0x00FFFFFF & rgb;
                        }
                        return rgb;
                    }
                };

                ImageProducer ip = new FilteredImageSource(rawImage.getSource(), filter);
                Image transparentImage = Toolkit.getDefaultToolkit().createImage(ip);

                JLabel splashLabel = new JLabel(new ImageIcon(transparentImage));
                splash.getContentPane().add(splashLabel, BorderLayout.CENTER);
                splash.pack();
                splash.setLocationRelativeTo(null);
                splash.setVisible(true);

                Thread.sleep(2500);
            }
        } catch (Exception e) {
            System.err.println("闪图加载或抠图处理失败。");
        } finally {
            splash.dispose();
        }
    }
}