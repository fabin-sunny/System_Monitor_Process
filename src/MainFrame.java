import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.File;
import javax.swing.*;
import java.lang.management.ManagementFactory;
import com.sun.management.OperatingSystemMXBean;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.json.JSONObject;


public class MainFrame {

    public class GlobalConfig {
        public static String globalIP = "172.16.213.31";
    }

    private JLabel cpuLabel, memoryLabel, userLabel, diskLabel, ipLabel;
    //system tray application to display system statistics
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
        listenForCommands();
    }

    //function to monitor CPU,Memory and Disk usages every 1 second 
    private void startMonitoring() {
        OperatingSystemMXBean osBean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
        String username = System.getProperty("user.name");
        userLabel.setText("User: " + username);

        String ipAddress = getIpAddress();
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
        });
        timer.start();
        Timer processTimer = new Timer(3000, e -> sendProcessesToServer(System.getProperty("user.name")));
        processTimer.start();
    }
    //function to get the CPU usage 
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

    //function to send statistics as JSON file to server
    private void saveToDatabase(String username, String ipAddress, double cpuUsage, double memoryUsed, double memoryTotal, double diskUsed, double diskTotal) {
        try {
            URL url = new URL("http://"+GlobalConfig.globalIP+":9090/api/system-stats/update");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
    
            // JSON payload with "status": "active"
            String jsonInput = String.format(
                "{\"user\":\"%s\",\"ipAddress\":\"%s\",\"cpuUsage\":%.2f,\"memoryUsed\":%.2f,\"memoryTotal\":%.2f,\"diskUsed\":%.2f,\"diskTotal\":%.2f,\"status\":\"active\"}",
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
    
    //function to send system active processes to server 
    private void sendProcessesToServer(String username) {
        List<ProcessInfo> processes = getRunningProcesses();
        
        // Skip sending if no processes are running
        if (processes.isEmpty()) {
            System.out.println("No running processes to send.");
            return;
        }
    
        try {
            URL url = new URL("http://"+GlobalConfig.globalIP+":9090/api/processes/update");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
    
            // Build JSON request
            StringBuilder jsonProcesses = new StringBuilder();
            jsonProcesses.append("{\"user\":\"").append(username).append("\",");
            jsonProcesses.append("\"processes\":[");
    
            for (int i = 0; i < processes.size(); i++) {
                ProcessInfo process = processes.get(i);
                jsonProcesses.append(String.format(
                    "{\"processName\":\"%s\",\"cpuUsage\":%.2f,\"memoryUsage\":%.2f}",
                    process.getProcessName(), process.getCpuUsage(), process.getMemoryUsage()
                ));
                if (i < processes.size() - 1) {
                    jsonProcesses.append(",");
                }
            }
    
            jsonProcesses.append("]}");
    
            // Debugging - Print JSON before sending
            System.out.println("Sending JSON " );
    
            // Send request
            OutputStream os = conn.getOutputStream();
            os.write(jsonProcesses.toString().getBytes());
            os.flush();
            os.close();
    
            // Read response
            int responseCode = conn.getResponseCode();
            System.out.println("Process Data Response Code: " + responseCode);
            conn.disconnect();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    //function to get the active processes of the system
    private List<ProcessInfo> getRunningProcesses() {
        List<ProcessInfo> processes = new ArrayList<>();
        try {
            ProcessBuilder processBuilder;
            String os = System.getProperty("os.name").toLowerCase();
    
            if (os.contains("win")) {
                processBuilder = new ProcessBuilder("wmic", "process", "get", "Name,WorkingSetSize,KernelModeTime,UserModeTime", "/format:csv");
            } else {
                processBuilder = new ProcessBuilder("ps", "-eo", "comm,rss,cputime");
            }
    
            Process process = processBuilder.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
    
            String line;
            boolean isFirstLine = true;
    
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
    
                if (isFirstLine) { 
                    isFirstLine = false;
                    continue;
                }
    
                String processName;
                long memoryUsageBytes;
                double memoryUsageMB;
                long totalCpuTime = 0;
                double cpuUsagePercentage = 0.0;
    
                if (os.contains("win")) {
                    String[] data = line.split(",");
                    if (data.length >= 5) {
                        try {
                            processName = data[2].trim();
                            memoryUsageBytes = Long.parseLong(data[4].trim());
                            memoryUsageMB = memoryUsageBytes / (1024.0 * 1024.0);
    
                            long kernelTime = Long.parseLong(data[3].trim());
                            long userTime = Long.parseLong(data[4].trim());
                            totalCpuTime = kernelTime + userTime;
                            cpuUsagePercentage = (totalCpuTime / 1_000_000.0) / 100.0; 
                        } catch (NumberFormatException e) {
                            System.out.println("Skipping invalid row (NumberFormatException): " + line);
                            continue;
                        }
                    } else {
                        System.out.println("Skipping invalid row (Incorrect Column Count): " + line);
                        continue;
                    }
                } else {
                    String[] data = line.split("\\s+");
                    if (data.length >= 3) {
                        try {
                            processName = data[0].trim();
                            memoryUsageBytes = Long.parseLong(data[1].trim()) * 1024;
                            memoryUsageMB = memoryUsageBytes / (1024.0 * 1024.0);
    
                            String[] timeParts = data[2].split(":");
                            if (timeParts.length == 3) {
                                totalCpuTime = (Integer.parseInt(timeParts[0]) * 3600 +
                                                Integer.parseInt(timeParts[1]) * 60 +
                                                Integer.parseInt(timeParts[2])) * 1_000_000L;
                            }
                            cpuUsagePercentage = (totalCpuTime / 1_000_000.0) / 100.0;
                        } catch (NumberFormatException e) {
                            System.out.println("Skipping invalid row (NumberFormatException): " + line);
                            continue;
                        }
                    } else {
                        System.out.println("Skipping invalid row (Incorrect Column Count): " + line);
                        continue;
                    }
                }
    
                processes.add(new ProcessInfo(processName, cpuUsagePercentage, memoryUsageMB));
            }
            reader.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return processes;
    }
    
    //function to get the IP address of the system
    private String getIpAddress() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            e.printStackTrace();
            return "Unknown";
        }
    }

class ProcessInfo {
    private String processName;
    private double cpuUsage;
    private double memoryUsage;

    public ProcessInfo(String processName, double cpuUsage, double memoryUsage) {
        this.processName = processName;
        this.cpuUsage = cpuUsage;
        this.memoryUsage = memoryUsage;
    }

    public String getProcessName() { return processName; }
    public double getCpuUsage() { return cpuUsage; }
    public double getMemoryUsage() { return memoryUsage; }
}

//function to listen for commands from the server
private void listenForCommands() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                String systemName = System.getProperty("user.name"); // Gets system username
                String requestUrl = "http://"+GlobalConfig.globalIP+":9090/api/command?system=" + systemName.replace(" ", "%20");

                System.out.println("Requesting command from: " + requestUrl); // Debugging output

                URL url = new URL(requestUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/json");

                if (conn.getResponseCode() == 200) {
                    // Read full response instead of only the first line
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();
                    String command = response.toString().trim();

                    System.out.println("Received Command: " + command); //  Debugging output

                    if (command != null && !command.isEmpty()) {
                        System.out.println("Executing Command: " + command);
                        String output = executeCommand(command);
                        sendCommandOutputToServer(systemName, command, output);
                    }
                } else {
                    System.out.println("Failed to fetch command. Response Code: " + conn.getResponseCode());
                }
                conn.disconnect();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }, 0, 1, TimeUnit.SECONDS);
    }

    //function to execute the command
    private String executeCommand(String command) {
        StringBuilder output = new StringBuilder();
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                System.getProperty("os.name").toLowerCase().contains("win") ? 
                new String[]{"cmd.exe", "/c", command} : 
                new String[]{"sh", "-c", command}
            );
    
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
    
            //separate thread to read output asynchronously
            Executors.newSingleThreadExecutor().submit(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                } catch (Exception ignored) {}
            });
    
            if (!process.waitFor(10, TimeUnit.SECONDS)) { 
                process.destroy();
                output.append("Error: Command execution timed out");
            }
    
        } catch (Exception e) {
            output.append("Error: ").append(e.getMessage());
        }
        return output.toString().trim();
    }
    
    //function to send comamnd output to the server as JSON file
    private void sendCommandOutputToServer(String systemId, String command, String output) {
        try {
            URL url = new URL("http://"+GlobalConfig.globalIP+":9090/api/command/output");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            //  Construct JSON properly
            JSONObject json = new JSONObject();
            json.put("system", systemId);
            json.put("command", command);
            json.put("output", output);

            // Debugging output
            System.out.println("Sending JSON: " + json.toString());

            // Send JSON request
            try (OutputStream os = conn.getOutputStream();
                 OutputStreamWriter writer = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {
                writer.write(json.toString());
                writer.flush();
            }

            // Read response
            int responseCode = conn.getResponseCode();
            System.out.println("Output Response Code: " + responseCode);
            conn.disconnect();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        MainFrame myFrame = new MainFrame();
        myFrame.init();
    }

}