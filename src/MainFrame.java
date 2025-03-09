import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.File;
import java.sql.*;
import javax.swing.*;
import java.lang.management.ManagementFactory;
import com.sun.management.OperatingSystemMXBean;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

public class MainFrame {
    private JLabel cpuLabel, memoryLabel, userLabel, diskLabel, ipLabel;
    private static final String DB_URL = "jdbc:mysql://localhost:3306/system_monitor";
    private static final String DB_USER = "root";
    private static final String DB_PASSWORD = "password";

    public void init() {
        JFrame frame = new JFrame("System Monitor");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(400, 350);
        frame.setLocationRelativeTo(null);
        frame.setVisible(false);

        cpuLabel = new JLabel("CPU Usage: N/A", SwingConstants.CENTER);
        memoryLabel = new JLabel("Memory Usage: N/A", SwingConstants.CENTER);
        userLabel = new JLabel("User: N/A", SwingConstants.CENTER);
        diskLabel = new JLabel("Disk Usage: N/A", SwingConstants.CENTER);
        ipLabel = new JLabel("IP Address: N/A", SwingConstants.CENTER);

        JPanel panel = new JPanel(new GridLayout(5, 1));
        panel.add(userLabel);
        panel.add(ipLabel);
        panel.add(cpuLabel);
        panel.add(memoryLabel);
        panel.add(diskLabel);
        frame.add(panel);

        if (SystemTray.isSupported()) {
            frame.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
            SystemTray systemTray = SystemTray.getSystemTray();
            Image iconImage = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = (Graphics2D) iconImage.getGraphics();
            g.setColor(Color.RED);
            g.fillOval(0, 0, 16, 16);
            g.dispose();
            TrayIcon trayIcon = new TrayIcon(iconImage);
            PopupMenu popupMenu = new PopupMenu();

            MenuItem show = new MenuItem("Show");
            show.addActionListener(e -> frame.setVisible(true));

            MenuItem exit = new MenuItem("Exit");
            exit.addActionListener(e -> System.exit(0));

            popupMenu.add(show);
            popupMenu.add(exit);

            trayIcon.setPopupMenu(popupMenu);

            try {
                systemTray.add(trayIcon);
            } catch (AWTException e1) {
                e1.printStackTrace();
            }
        }

        startMonitoring();
    }

    private void startMonitoring() {
        OperatingSystemMXBean osBean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
        String username = System.getProperty("user.name"); // Get the logged-in user
        userLabel.setText("User: " + username);

        String ipAddress = getIpAddress(); // Get the system's IP address
        ipLabel.setText("IP Address: " + ipAddress);

        Timer timer = new Timer(1000, (ActionEvent e) -> {
            double cpuLoad = getCpuUsage();
            cpuLabel.setText(String.format("CPU Usage: %.2f%%", cpuLoad));

            long totalMemory = osBean.getTotalMemorySize();
            long freeMemory = osBean.getFreeMemorySize();
            long usedMemory = totalMemory - freeMemory;
            memoryLabel.setText(String.format("Memory Usage: %.2f GB / %.2f GB", usedMemory / 1e9, totalMemory / 1e9));

            File root = new File("/");
            long totalDiskSpace = root.getTotalSpace();
            long freeDiskSpace = root.getFreeSpace();
            long usedDiskSpace = totalDiskSpace - freeDiskSpace;
            diskLabel.setText(String.format("Disk Usage: %.2f GB / %.2f GB", usedDiskSpace / 1e9, totalDiskSpace / 1e9));

            saveToDatabase(username, ipAddress, cpuLoad, usedMemory / 1e9, totalMemory / 1e9, usedDiskSpace / 1e9, totalDiskSpace / 1e9);
            saveProcessesToDatabase(username);
        });
        timer.start();
        Timer processTimer = new Timer(3000, e -> saveProcessesToDatabase(System.getProperty("user.name")));
        processTimer.start();
    }

    private double getCpuUsage() {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            ProcessBuilder processBuilder;
            if (os.contains("win")) {
                processBuilder = new ProcessBuilder("wmic", "cpu", "get", "LoadPercentage");
            } else {
                processBuilder = new ProcessBuilder("sh", "-c", "top -bn1 | grep 'Cpu(s)' | awk '{print 100 - $8}'");
            }
            Process process = processBuilder.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.matches("\\d+")) {
                    return Double.parseDouble(line);
                }
            }
            reader.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return -1;
    }

    private void saveToDatabase(String username, String ipAddress, double cpuUsage, double memoryUsed, double memoryTotal, double diskUsed, double diskTotal) {
        try {
        URL url = new URL("http://192.168.1.7:9090/api/system-stats/update");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        String jsonInput = String.format(
            "{\"user\":\"%s\",\"ipAddress\":\"%s\",\"cpuUsage\":%.2f,\"memoryUsed\":%.2f,\"memoryTotal\":%.2f,\"diskUsed\":%.2f,\"diskTotal\":%.2f}",
            username, ipAddress, cpuUsage, memoryUsed, memoryTotal, diskUsed, diskTotal
        );

        OutputStream os = conn.getOutputStream();
        os.write(jsonInput.getBytes());
        os.flush();
        os.close();

        int responseCode = conn.getResponseCode();
        System.out.println("Response Code: " + responseCode);
        conn.disconnect();
    } catch (Exception e) {
        e.printStackTrace();
    }
    }

    private void saveProcessesToDatabase(String username) {
        List<String> processes = getRunningProcesses();
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            conn.setAutoCommit(false); // Start transaction
            

            // Clear previous process entries for the user
            try (PreparedStatement deleteStmt = conn.prepareStatement("TRUNCATE TABLE user_processes")) {
                //deleteStmt.setString(1, username);
                deleteStmt.executeUpdate();
            }
    
            // Insert updated process list
            try (PreparedStatement insertStmt = conn.prepareStatement(
                    "INSERT INTO user_processes (user, process_name) VALUES (?, ?)")) {
                for (String process : processes) {
                    insertStmt.setString(1, username);
                    insertStmt.setString(2, process);
                    insertStmt.addBatch();
                }
                insertStmt.executeBatch(); // Execute batch insert
            }
    
            conn.commit(); // Commit transaction
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
    

    private List<String> getRunningProcesses() {
        List<String> processes = new ArrayList<>();
        try {
            Process process = new ProcessBuilder("tasklist").start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                processes.add(line.split("\\s+")[0]); 
            }
            reader.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return processes;
    }
    private String getIpAddress() {
    try {
        return InetAddress.getLocalHost().getHostAddress();
    } catch (UnknownHostException e) {
        e.printStackTrace();
        return "Unknown";
    }
}


    public static void main(String[] args) {
        MainFrame myFrame = new MainFrame();
        myFrame.init();
    }

}
