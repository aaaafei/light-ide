package lightide.supervisor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class WinProcessTree {
    private WinProcessTree() {
    }

    public static long javaPid() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        int at = name.indexOf('@');
        return Long.parseLong(at < 0 ? name : name.substring(0, at));
    }

    public static String cmdChildren(long parentPid) throws IOException, InterruptedException {
        return joinPids(powershell(parentPid, null, true));
    }

    public static long waitForNewCmd(long parentPid, String beforeCsv) throws IOException, InterruptedException {
        String output = powershell(parentPid, beforeCsv, false);
        Long pid = firstLong(output);
        return pid == null ? -1L : pid.longValue();
    }

    public static boolean alive(long pid) throws IOException, InterruptedException {
        if (pid <= 0) {
            return false;
        }
        Process process = new ProcessBuilder("tasklist", "/FI", "PID eq " + pid, "/FO", "CSV", "/NH")
                .redirectErrorStream(true)
                .start();
        String output = read(process);
        process.waitFor();
        return output.contains("\"" + pid + "\"");
    }

    public static void killTree(long pid) throws IOException, InterruptedException {
        if (pid <= 0) {
            return;
        }
        Process process = new ProcessBuilder("taskkill", "/PID", Long.toString(pid), "/T", "/F")
                .redirectErrorStream(true)
                .start();
        String output = read(process);
        int code = process.waitFor();
        if (code == 0 || code == 128) {
            return;
        }
        String lower = output.toLowerCase(Locale.ROOT);
        if (lower.contains("not found") || output.contains("没有找到") || output.contains("没有此任务")) {
            return;
        }
        if (!alive(pid)) {
            return;
        }
        throw new IOException(output.trim().isEmpty() ? "taskkill 退出码 " + code : output.trim());
    }

    private static String numericList(String csv) {
        StringBuilder out = new StringBuilder();
        int written = 0;
        for (String part : csv == null ? new String[0] : csv.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            for (int i = 0; i < token.length(); i++) {
                char c = token.charAt(i);
                if (c < '0' || c > '9') {
                    token = "";
                    break;
                }
            }
            if (token.isEmpty()) {
                continue;
            }
            if (written++ > 0) {
                out.append(',');
            }
            out.append(token);
        }
        return out.toString();
    }

    private static String joinPids(String output) {
        StringBuilder csv = new StringBuilder();
        Long pid;
        String rest = output;
        while ((pid = firstLong(rest)) != null) {
            if (csv.length() > 0) {
                csv.append(',');
            }
            csv.append(pid.longValue());
            int at = rest.indexOf(String.valueOf(pid));
            rest = rest.substring(at + String.valueOf(pid).length());
        }
        return csv.toString();
    }

    private static Long firstLong(String text) {
        if (text == null) {
            return null;
        }
        int start = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                if (start < 0) {
                    start = i;
                }
            } else if (start >= 0) {
                return Long.valueOf(text.substring(start, i));
            }
        }
        if (start >= 0) {
            return Long.valueOf(text.substring(start));
        }
        return null;
    }

    private static String powershell(long parentPid, String beforeCsv, boolean list) throws IOException, InterruptedException {
        List<String> command = new ArrayList<String>();
        command.add("powershell.exe");
        command.add("-NoProfile");
        command.add("-NonInteractive");
        command.add("-File");
        command.add(script().getAbsolutePath());
        command.add("-ParentPid");
        command.add(Long.toString(parentPid));
        if (list) {
            command.add("-List");
        } else if (beforeCsv != null && !beforeCsv.trim().isEmpty()) {
            command.add("-Before");
            command.add(numericList(beforeCsv));
        }
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = read(process);
        int code = process.waitFor();
        if (code != 0 && list) {
            throw new IOException(output.trim().isEmpty() ? "powershell 退出码 " + code : output.trim());
        }
        return output;
    }

    private static File script() throws IOException {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.trim().isEmpty()) {
            appData = System.getProperty("java.io.tmpdir");
        }
        Path dir = Paths.get(appData, "light-ide");
        Files.createDirectories(dir);
        Path path = dir.resolve("pid.ps1");
        String script = ""
                + "param([Parameter(Mandatory=$true)][long]$ParentPid, [string]$Before = '', [switch]$List)\r\n"
                + "$ProgressPreference = 'SilentlyContinue'\r\n"
                + "$filter = \"ParentProcessId=$ParentPid and Name='cmd.exe'\"\r\n"
                + "function Read-Pids {\r\n"
                + "  @(Get-CimInstance Win32_Process -Filter $filter | ForEach-Object { [int64]$_.ProcessId })\r\n"
                + "}\r\n"
                + "if ($List) {\r\n"
                + "  (Read-Pids) -join ','\r\n"
                + "  exit 0\r\n"
                + "}\r\n"
                + "$before = @()\r\n"
                + "if ($Before) { $before = @($Before.Split(',') | ForEach-Object { [int64]$_ }) }\r\n"
                + "$deadline = (Get-Date).AddSeconds(5)\r\n"
                + "do {\r\n"
                + "  foreach ($id in (Read-Pids)) {\r\n"
                + "    if ($before -notcontains $id) { Write-Output $id; exit 0 }\r\n"
                + "  }\r\n"
                + "  Start-Sleep -Milliseconds 200\r\n"
                + "} while ((Get-Date) -lt $deadline)\r\n"
                + "exit 1\r\n";
        Files.write(path, script.getBytes(StandardCharsets.US_ASCII));
        return path.toFile();
    }

    private static String read(Process process) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InputStream in = process.getInputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) >= 0) {
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), Charset.defaultCharset());
    }
}
