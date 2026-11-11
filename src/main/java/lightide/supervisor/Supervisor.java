package lightide.supervisor;

import lightide.UserException;
import lightide.config.AppConfig;
import lightide.config.ConfigStore;
import lightide.config.Project;
import lightide.config.RunConfig;
import lightide.json.Json;
import lightide.launch.Commands;
import lightide.launch.LaunchSpec;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class Supervisor {
    private static final int LOG_LIMIT = 2000;
    private static final int EXITED_LIMIT = 20;

    private final ConfigStore store;
    private final List<Run> runs = new ArrayList<Run>();
    private final Path runsFile;
    private final Path batDir;

    public Supervisor(ConfigStore store) throws IOException {
        this.store = store;
        this.runsFile = store.directory().resolve("runs.json");
        this.batDir = store.directory().resolve("bats");
        Files.createDirectories(batDir);
    }

    public void recover() {
        if (!Files.exists(runsFile)) {
            return;
        }
        List<Run> loaded = new ArrayList<Run>();
        try {
            String text = new String(Files.readAllBytes(runsFile), StandardCharsets.UTF_8);
            if (text.startsWith("\uFEFF")) {
                text = text.substring(1);
            }
            Map<String, Object> root = Json.object(Json.parse(text));
            Object raw = root.get("runs");
            if (raw == null) {
                return;
            }
            for (Object item : Json.array(raw)) {
                Map<String, Object> map = Json.object(item);
                long pid = Json.longValue(map.get("pid"));
                boolean live;
                try {
                    live = WinProcessTree.alive(pid);
                } catch (IOException e) {
                    live = true;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!live) {
                    continue;
                }
                Run run = Run.orphan(map, pid);
                run.log.add("sys", "这是上次退出后仍在运行的进程，日志没有接上。");
                loaded.add(run);
            }
        } catch (RuntimeException e) {
            quarantine();
            System.err.println("runs.json 无法解析，已改名为 runs.json.broken：" + e.getMessage());
            return;
        } catch (IOException e) {
            System.err.println("无法读取 runs.json：" + e.getMessage());
            return;
        }
        synchronized (runs) {
            runs.addAll(loaded);
        }
        try {
            persist();
        } catch (IOException e) {
            System.err.println("无法更新 runs.json：" + e.getMessage());
        }
    }

    public Run start(String projectId, String configId) throws UserException, IOException, InterruptedException {
        Project project = store.findProject(projectId);
        if (project == null) {
            throw new UserException("项目不存在");
        }
        RunConfig config = project.findConfig(configId);
        if (config == null) {
            throw new UserException("运行配置不存在");
        }
        AppConfig settings = store.get();
        LaunchSpec spec = Commands.prepare(settings, project, config);
        copyWar(spec);

        String id = UUID.randomUUID().toString();
        File bat = writeBat(id, spec.command);
        Run run = Run.owned(id, project, config, spec, bat);
        synchronized (runs) {
            if (busyLocked(configId)) {
                delete(bat);
                throw new UserException("这个配置已经在运行");
            }
            runs.add(run);
            trimExited();
        }
        run.log.add("sys", spec.command);
        if ("gradle".equals(spec.type) && spec.debug) {
            run.log.add("sys", "Gradle --debug-jvm 会暂停进程，监听 5005。在 Cursor 里附加调试器后才会继续。");
            if (config.debugPort > 0 && config.debugPort != 5005) {
                run.log.add("sys", "已忽略配置的调试端口 " + config.debugPort + "，Gradle 固定使用 5005。");
            }
        }
        try {
            if (spec.warSource != null && spec.warDest != null
                    && !spec.warSource.getCanonicalPath().equals(spec.warDest.getCanonicalPath())) {
                run.log.add("sys", "已复制 war 到 " + spec.warDest.getAbsolutePath());
            }
        } catch (IOException e) {
            run.log.add("err", "无法确认 war 路径: " + e.getMessage());
        }

        long javaPid = WinProcessTree.javaPid();
        String before = WinProcessTree.cmdChildren(javaPid);
        Process process;
        try {
            process = launch(spec, bat);
        } catch (IOException e) {
            remove(run);
            delete(bat);
            throw e;
        }
        run.process = process;
        pump(process.getInputStream(), run, "out");
        pump(process.getErrorStream(), run, "err");
        long pid = WinProcessTree.waitForNewCmd(javaPid, before);
        run.pid = pid;
        if (process.isAlive() && pid <= 0) {
            process.destroy();
            remove(run);
            delete(bat);
            throw new UserException("没有拿到进程号，启动已取消");
        }
        if (pid > 0) {
            try {
                persist();
            } catch (IOException e) {
                run.log.add("err", "无法写入 runs.json: " + e.getMessage());
            }
        }
        waitForExit(run);
        return run;
    }

    public Run restart(String runId) throws UserException, IOException, InterruptedException {
        Run existing = require(runId);
        String projectId = existing.projectId;
        String configId = existing.configId;
        stop(existing);
        return start(projectId, configId);
    }

    public void stop(String runId) throws UserException, IOException, InterruptedException {
        stop(require(runId));
    }

    public boolean busy(String configId) {
        synchronized (runs) {
            return busyLocked(configId);
        }
    }

    private boolean busyLocked(String configId) {
        for (Run run : runs) {
            if (configId.equals(run.configId) && !"exited".equals(run.state)) {
                return true;
            }
        }
        return false;
    }

    public boolean projectBusy(String projectId) {
        synchronized (runs) {
            for (Run run : runs) {
                if (projectId.equals(run.projectId) && !"exited".equals(run.state)) {
                    return true;
                }
            }
        }
        return false;
    }

    public Run get(String runId) {
        synchronized (runs) {
            for (Run run : runs) {
                if (run.id.equals(runId)) {
                    return run;
                }
            }
        }
        return null;
    }

    public List<Map<String, Object>> snapshot() {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        synchronized (runs) {
            for (Run run : runs) {
                list.add(run.toMap());
            }
        }
        return list;
    }

    public void shutdown() {
        List<Run> owned = new ArrayList<Run>();
        synchronized (runs) {
            for (Run run : runs) {
                if (run.owned && !"exited".equals(run.state)) {
                    owned.add(run);
                }
            }
        }
        for (Run run : owned) {
            try {
                stop(run);
            } catch (Exception e) {
                System.err.println("停止 " + run.name + " 失败: " + e.getMessage());
            }
        }
    }

    private void stop(Run run) throws IOException, InterruptedException {
        synchronized (run) {
            if ("exited".equals(run.state) || run.stopping) {
                return;
            }
            run.stopping = true;
        }
        try {
            if ("tomcat".equals(run.type) && !run.catalinaHome.isEmpty()) {
                catalinaStop(run);
                waitUntilExit(run, 8000);
            }
            if (stillAlive(run)) {
                if (run.pid > 0) {
                    WinProcessTree.killTree(run.pid);
                } else if (run.process != null) {
                    run.process.destroy();
                }
                waitUntilExit(run, 5000);
            }
            int code = -1;
            if (run.process != null && !run.process.isAlive()) {
                code = run.process.exitValue();
            }
            finish(run, code);
        } finally {
            synchronized (run) {
                run.stopping = false;
            }
        }
    }

    private void catalinaStop(Run run) throws IOException, InterruptedException {
        String id = run.id + "-stop";
        File bat;
        try {
            bat = writeBat(id, Commands.quote(new File(run.catalinaHome, "bin/catalina.bat").getAbsolutePath()) + " stop");
        } catch (UserException e) {
            run.log.add("err", e.getMessage());
            return;
        }
        ProcessBuilder builder = new ProcessBuilder("cmd.exe", "/c", bat.getAbsolutePath());
        Map<String, String> env = builder.environment();
        env.put("CATALINA_HOME", run.catalinaHome);
        env.put("CATALINA_BASE", run.catalinaBase);
        if (!run.javaHome.isEmpty()) {
            env.put("JAVA_HOME", run.javaHome);
        }
        builder.redirectErrorStream(true);
        Process process = builder.start();
        drain(process.getInputStream());
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroy();
        }
        delete(bat);
    }

    private void waitForExit(final Run run) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    int code = run.process.waitFor();
                    finish(run, code);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "wait-" + run.id);
        thread.setDaemon(true);
        thread.start();
    }

    private void finish(Run run, int code) {
        synchronized (run) {
            if ("exited".equals(run.state)) {
                return;
            }
            run.state = "exited";
            run.exitCode = Integer.valueOf(code);
        }
        run.log.add("sys", "进程已退出，代码 " + code);
        run.log.end(code);
        delete(run.bat);
        try {
            persist();
        } catch (IOException e) {
            run.log.add("err", "无法更新 runs.json: " + e.getMessage());
        }
        synchronized (runs) {
            trimExited();
        }
    }

    private boolean stillAlive(Run run) throws IOException, InterruptedException {
        if (run.process != null) {
            return run.process.isAlive();
        }
        return WinProcessTree.alive(run.pid);
    }

    private void waitUntilExit(Run run, long timeoutMs) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!stillAlive(run)) {
                return;
            }
            Thread.sleep(200);
        }
    }

    private Process launch(LaunchSpec spec, File bat) throws IOException {
        ProcessBuilder builder = new ProcessBuilder("cmd.exe", "/c", bat.getAbsolutePath());
        builder.directory(spec.directory);
        Map<String, String> env = builder.environment();
        for (Map.Entry<String, String> entry : spec.env.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                env.put(entry.getKey(), entry.getValue());
            }
        }
        return builder.start();
    }

    private void copyWar(LaunchSpec spec) throws UserException, IOException {
        if (spec.warSource == null || spec.warDest == null) {
            return;
        }
        if (spec.warSource.getCanonicalPath().equals(spec.warDest.getCanonicalPath())) {
            return;
        }
        File parent = spec.warDest.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new UserException("无法创建 " + parent.getAbsolutePath());
        }
        Files.copy(spec.warSource.toPath(), spec.warDest.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private File writeBat(String id, String command) throws IOException {
        String content = "@echo off\r\ncall " + command + "\r\nexit /b %ERRORLEVEL%\r\n";
        Path path = batDir.resolve(id + ".bat");
        Files.write(path, content.getBytes(Charset.defaultCharset()));
        return path.toFile();
    }

    private void pump(final InputStream input, final Run run, final String stream) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                BufferedReader reader = new BufferedReader(new InputStreamReader(input, Charset.defaultCharset()));
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        run.log.add(stream, line);
                    }
                } catch (IOException e) {
                    run.log.add("err", "读取日志失败: " + e.getMessage());
                }
            }
        }, "log-" + stream + "-" + run.id);
        thread.setDaemon(true);
        thread.start();
    }

    private void drain(final InputStream input) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[1024];
                try {
                    while (input.read(buf) >= 0) {
                        // keep the stop command from blocking on a full pipe
                    }
                } catch (IOException ignored) {
                    // the stop process is going away
                }
            }
        }, "drain-stop");
        thread.setDaemon(true);
        thread.start();
    }

    private Run require(String runId) throws UserException {
        Run run = get(runId);
        if (run == null) {
            throw new UserException("运行实例不存在");
        }
        return run;
    }

    private void remove(Run run) {
        synchronized (runs) {
            runs.remove(run);
        }
    }

    private void trimExited() {
        int exited = 0;
        for (Run run : runs) {
            if ("exited".equals(run.state)) {
                exited++;
            }
        }
        Iterator<Run> iterator = runs.iterator();
        while (exited > EXITED_LIMIT && iterator.hasNext()) {
            Run run = iterator.next();
            if ("exited".equals(run.state)) {
                iterator.remove();
                exited--;
            }
        }
    }

    private void persist() throws IOException {
        List<Object> items = new ArrayList<Object>();
        synchronized (runs) {
            for (Run run : runs) {
                if (!"exited".equals(run.state) && run.pid > 0) {
                    items.add(run.persisted());
                }
            }
        }
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("runs", items);
        Path tmp = runsFile.resolveSibling("runs.json.tmp");
        Files.write(tmp, Json.writePretty(root).getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp, runsFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, runsFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void quarantine() {
        try {
            Path broken = runsFile.resolveSibling("runs.json.broken");
            Files.move(runsFile, broken, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("无法改名 runs.json：" + e.getMessage());
        }
    }

    private static void delete(File file) {
        if (file != null && file.exists() && !file.delete()) {
            file.deleteOnExit();
        }
    }

    public static final class Run {
        public final String id;
        public final String projectId;
        public final String configId;
        public final String name;
        public final String type;
        public final String command;
        public final boolean debug;
        public final int debugPort;
        final String catalinaHome;
        final String catalinaBase;
        final String javaHome;
        final boolean owned;
        public final Log log = new Log();
        volatile Process process;
        volatile File bat;
        volatile long pid;
        volatile String state;
        volatile Integer exitCode;
        volatile boolean stopping;

        private Run(String id, String projectId, String configId, String name, String type, String command,
                    boolean debug, int debugPort, String catalinaHome, String catalinaBase, String javaHome,
                    boolean owned) {
            this.id = id;
            this.projectId = projectId;
            this.configId = configId;
            this.name = name;
            this.type = type;
            this.command = command;
            this.debug = debug;
            this.debugPort = debugPort;
            this.catalinaHome = catalinaHome == null ? "" : catalinaHome;
            this.catalinaBase = catalinaBase == null ? "" : catalinaBase;
            this.javaHome = javaHome == null ? "" : javaHome;
            this.owned = owned;
            this.state = owned ? "running" : "orphan";
        }

        static Run owned(String id, Project project, RunConfig config, LaunchSpec spec, File bat) {
            Run run = new Run(id, project.id, config.id, project.name + " / " + config.name, spec.type, spec.command,
                    spec.debug, spec.debugPort, spec.catalinaHome, spec.catalinaBase,
                    spec.env.containsKey("JAVA_HOME") ? spec.env.get("JAVA_HOME") : "", true);
            run.bat = bat;
            return run;
        }

        static Run orphan(Map<String, Object> map, long pid) {
            Run run = new Run(
                    Json.text(map.get("id"), UUID.randomUUID().toString()),
                    Json.text(map.get("projectId"), ""),
                    Json.text(map.get("configId"), ""),
                    Json.text(map.get("name"), "残留进程"),
                    Json.text(map.get("type"), ""),
                    Json.text(map.get("command"), ""),
                    map.get("debug") instanceof Boolean && ((Boolean) map.get("debug")).booleanValue(),
                    Json.integer(map.get("debugPort"), 0),
                    Json.text(map.get("catalinaHome"), ""),
                    Json.text(map.get("catalinaBase"), ""),
                    Json.text(map.get("javaHome"), ""),
                    false);
            run.pid = pid;
            return run;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = baseMap();
            map.put("state", state);
            map.put("exitCode", exitCode);
            if (debug && debugPort > 0) {
                Map<String, Object> attach = new LinkedHashMap<String, Object>();
                attach.put("type", "java");
                attach.put("request", "attach");
                attach.put("hostName", "127.0.0.1");
                attach.put("port", Integer.valueOf(debugPort));
                map.put("attach", attach);
            }
            return map;
        }

        Map<String, Object> persisted() {
            return baseMap();
        }

        private Map<String, Object> baseMap() {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("id", id);
            map.put("projectId", projectId);
            map.put("configId", configId);
            map.put("name", name);
            map.put("type", type);
            map.put("command", command);
            map.put("pid", Long.valueOf(pid));
            map.put("debug", Boolean.valueOf(debug));
            map.put("debugPort", Integer.valueOf(debugPort));
            map.put("catalinaHome", catalinaHome);
            map.put("catalinaBase", catalinaBase);
            map.put("javaHome", javaHome);
            return map;
        }
    }

    public static final class Log {
        private final ArrayDeque<String[]> lines = new ArrayDeque<String[]>();
        private final List<Subscription> subscriptions = new ArrayList<Subscription>();
        private boolean ended;
        private int exitCode;

        public void add(String stream, String text) {
            synchronized (this) {
                if (ended) {
                    return;
                }
                lines.add(new String[] {stream, text});
                while (lines.size() > LOG_LIMIT) {
                    lines.removeFirst();
                }
                for (Subscription subscription : subscriptions) {
                    subscription.queue.add(new String[] {stream, text});
                }
                notifyAll();
            }
        }

        public Subscription subscribe() {
            synchronized (this) {
                List<String[]> history = new ArrayList<String[]>(lines);
                Subscription subscription = new Subscription(history);
                if (ended) {
                    subscription.ended = true;
                    subscription.exitCode = exitCode;
                } else {
                    subscriptions.add(subscription);
                }
                return subscription;
            }
        }

        public String[] take(Subscription subscription) throws InterruptedException {
            synchronized (this) {
                while (subscription.queue.isEmpty() && !subscription.ended) {
                    wait();
                }
                if (!subscription.queue.isEmpty()) {
                    return subscription.queue.removeFirst();
                }
                return null;
            }
        }

        public void unsubscribe(Subscription subscription) {
            synchronized (this) {
                subscriptions.remove(subscription);
            }
        }

        void end(int code) {
            synchronized (this) {
                if (ended) {
                    return;
                }
                ended = true;
                exitCode = code;
                for (Subscription subscription : subscriptions) {
                    subscription.ended = true;
                    subscription.exitCode = code;
                }
                notifyAll();
            }
        }
    }

    public static final class Subscription {
        public final List<String[]> history;
        final ArrayDeque<String[]> queue = new ArrayDeque<String[]>();
        volatile boolean ended;
        public volatile int exitCode;

        Subscription(List<String[]> history) {
            this.history = history;
        }
    }
}
