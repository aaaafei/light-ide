package lightide.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import lightide.UserException;
import lightide.config.ConfigStore;
import lightide.config.Project;
import lightide.config.RunConfig;
import lightide.detect.Detector;
import lightide.json.Json;
import lightide.supervisor.Supervisor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

public final class ApiServer {
    private final int port;
    private final ConfigStore store;
    private final Supervisor supervisor;
    private HttpServer server;

    public ApiServer(int port, ConfigStore store, Supervisor supervisor) {
        this.port = port;
        this.store = store;
        this.supervisor = supervisor;
    }

    public void start() throws IOException {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        } catch (IOException e) {
            throw new IOException("端口 " + port + " 已被占用", e);
        }
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            route(exchange);
        } catch (UserException e) {
            sendError(exchange, HttpURLConnection.HTTP_BAD_REQUEST, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendError(exchange, HttpURLConnection.HTTP_INTERNAL_ERROR, "被中断");
        } catch (RuntimeException e) {
            sendError(exchange, HttpURLConnection.HTTP_INTERNAL_ERROR, message(e));
        } catch (IOException e) {
            if (!exchange.getResponseHeaders().isEmpty() && exchange.getResponseCode() >= 0) {
                exchange.close();
                return;
            }
            sendError(exchange, HttpURLConnection.HTTP_INTERNAL_ERROR, message(e));
        }
    }

    private void route(HttpExchange exchange) throws IOException, UserException, InterruptedException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path) || "/app.css".equals(path) || "/app.js".equals(path))) {
            sendFile(exchange, "/".equals(path) ? "/index.html" : path);
            return;
        }
        if ("GET".equals(method) && "/api/state".equals(path)) {
            sendJson(exchange, HttpURLConnection.HTTP_OK, state());
            return;
        }
        if ("PUT".equals(method) && "/api/settings".equals(path)) {
            updateSettings(exchange);
            return;
        }
        if ("POST".equals(method) && "/api/projects".equals(path)) {
            addProject(exchange);
            return;
        }
        if ("POST".equals(method) && "/api/runs".equals(path)) {
            startRun(exchange);
            return;
        }
        String[] parts = path.split("/");
        if (parts.length == 4 && "api".equals(parts[1]) && "projects".equals(parts[2]) && "DELETE".equals(method)) {
            deleteProject(exchange, id(parts[3]));
            return;
        }
        if (parts.length == 5 && "api".equals(parts[1]) && "projects".equals(parts[2]) && "configs".equals(parts[4]) && "POST".equals(method)) {
            addConfig(exchange, id(parts[3]));
            return;
        }
        if (parts.length == 6 && "api".equals(parts[1]) && "projects".equals(parts[2]) && "configs".equals(parts[4])) {
            String projectId = id(parts[3]);
            String configId = id(parts[5]);
            if ("PUT".equals(method)) {
                updateConfig(exchange, projectId, configId);
                return;
            }
            if ("DELETE".equals(method)) {
                deleteConfig(exchange, projectId, configId);
                return;
            }
        }
        if (parts.length == 5 && "api".equals(parts[1]) && "runs".equals(parts[2]) && "POST".equals(method)) {
            String runId = id(parts[3]);
            if ("stop".equals(parts[4])) {
                supervisor.stop(runId);
                sendJson(exchange, HttpURLConnection.HTTP_OK, state());
                return;
            }
            if ("restart".equals(parts[4])) {
                supervisor.restart(runId);
                sendJson(exchange, HttpURLConnection.HTTP_OK, state());
                return;
            }
        }
        if (parts.length == 5 && "api".equals(parts[1]) && "runs".equals(parts[2]) && "logs".equals(parts[4]) && "GET".equals(method)) {
            streamLogs(exchange, id(parts[3]));
            return;
        }
        sendError(exchange, HttpURLConnection.HTTP_NOT_FOUND, "没有这个地址");
    }

    private Map<String, Object> state() {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("listenPort", Integer.valueOf(port));
        body.put("settings", store.settingsView());
        body.put("projects", store.projectMaps());
        body.put("runs", supervisor.snapshot());
        return body;
    }

    private void updateSettings(HttpExchange exchange) throws IOException, UserException {
        Map<String, Object> map = bodyMap(exchange);
        int requested = Json.integer(map.get("port"), store.get().port);
        store.updateSettings(
                requested,
                Json.text(map.get("javaHome"), ""),
                Json.text(map.get("mavenCmd"), "mvn"),
                Json.text(map.get("gradleCmd"), "gradle"),
                Json.text(map.get("catalinaHome"), ""));
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("settings", store.get().settingsMap());
        body.put("restartRequired", Boolean.valueOf(requested != port));
        sendJson(exchange, HttpURLConnection.HTTP_OK, body);
    }

    private void addProject(HttpExchange exchange) throws IOException, UserException {
        Map<String, Object> map = bodyMap(exchange);
        String path = Json.text(map.get("path"), "").trim();
        if (path.isEmpty()) {
            throw new UserException("请填写项目目录");
        }
        File dir = new File(path);
        if (!dir.isDirectory()) {
            throw new UserException("目录不存在: " + path);
        }
        Project project = Detector.detect(dir);
        project.root = dir.getCanonicalPath();
        store.addProject(project);
        sendJson(exchange, HttpURLConnection.HTTP_OK, project.toMap());
    }

    private void deleteProject(HttpExchange exchange, String projectId) throws IOException, UserException {
        if (supervisor.projectBusy(projectId)) {
            throw new UserException("先停止这个项目里正在运行的配置");
        }
        store.removeProject(projectId);
        sendJson(exchange, HttpURLConnection.HTTP_OK, state());
    }

    private void addConfig(HttpExchange exchange, String projectId) throws IOException, UserException {
        Map<String, Object> map = bodyMap(exchange);
        String type = Json.text(map.get("type"), "");
        requireType(type);
        RunConfig config = new RunConfig();
        config.id = UUID.randomUUID().toString();
        config.type = type;
        config.name = Json.text(map.get("name"), defaultName(type));
        if (config.name.trim().isEmpty()) {
            config.name = defaultName(type);
        }
        store.addConfig(projectId, config);
        sendJson(exchange, HttpURLConnection.HTTP_OK, config.toMap());
    }

    private void updateConfig(HttpExchange exchange, String projectId, String configId) throws IOException, UserException {
        if (supervisor.busy(configId)) {
            throw new UserException("先停止这个配置，再修改");
        }
        RunConfig config = configFrom(bodyMap(exchange), configId);
        store.replaceConfig(projectId, config);
        sendJson(exchange, HttpURLConnection.HTTP_OK, config.toMap());
    }

    private void deleteConfig(HttpExchange exchange, String projectId, String configId) throws IOException, UserException {
        if (supervisor.busy(configId)) {
            throw new UserException("先停止这个配置，再删除");
        }
        store.removeConfig(projectId, configId);
        sendJson(exchange, HttpURLConnection.HTTP_OK, state());
    }

    private void startRun(HttpExchange exchange) throws IOException, UserException, InterruptedException {
        Map<String, Object> map = bodyMap(exchange);
        supervisor.start(Json.text(map.get("projectId"), ""), Json.text(map.get("configId"), ""));
        sendJson(exchange, HttpURLConnection.HTTP_OK, state());
    }

    private void streamLogs(HttpExchange exchange, String runId) throws IOException, UserException {
        Supervisor.Run run = supervisor.get(runId);
        if (run == null) {
            throw new UserException("运行实例不存在");
        }
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, 0);
        OutputStream out = exchange.getResponseBody();
        Supervisor.Subscription subscription = run.log.subscribe();
        try {
            sendEvent(out, "history", linesMap(subscription.history));
            while (true) {
                String[] line = run.log.take(subscription);
                if (line == null) {
                    Map<String, Object> end = new LinkedHashMap<String, Object>();
                    end.put("state", "exited");
                    end.put("exitCode", Integer.valueOf(subscription.exitCode));
                    sendEvent(out, "end", end);
                    break;
                }
                sendEvent(out, "line", lineMap(line));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            run.log.unsubscribe(subscription);
            exchange.close();
        }
    }

    private static Map<String, Object> linesMap(List<String[]> lines) {
        List<Object> items = new ArrayList<Object>();
        for (String[] line : lines) {
            items.add(lineMap(line));
        }
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("lines", items);
        return body;
    }

    private static Map<String, Object> lineMap(String[] line) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("stream", line[0]);
        map.put("text", line[1]);
        return map;
    }

    private static void sendEvent(OutputStream out, String event, Object data) throws IOException {
        String payload = "event: " + event + "\ndata: " + Json.write(data).trim() + "\n\n";
        out.write(payload.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private RunConfig configFrom(Map<String, Object> map, String configId) throws UserException {
        Map<String, Object> copy = new LinkedHashMap<String, Object>(map);
        copy.put("id", configId);
        RunConfig config = RunConfig.fromMap(copy);
        if (config.name.trim().isEmpty()) {
            throw new UserException("请填写名称");
        }
        requireType(config.type);
        if (config.debugPort < 1 || config.debugPort > 65535) {
            throw new UserException("调试端口需要在 1 到 65535 之间");
        }
        return config;
    }

    private static void requireType(String type) throws UserException {
        if (!"maven".equals(type) && !"gradle".equals(type) && !"tomcat".equals(type) && !"script".equals(type)) {
            throw new UserException("未知的启动类型");
        }
    }

    private static String defaultName(String type) {
        if ("maven".equals(type)) {
            return "Maven";
        }
        if ("gradle".equals(type)) {
            return "Gradle";
        }
        if ("tomcat".equals(type)) {
            return "Tomcat";
        }
        return "脚本";
    }

    private static String id(String raw) throws UserException {
        if (raw == null || !raw.matches("[0-9a-fA-F\\-]{8,80}")) {
            throw new UserException("无效的 id");
        }
        return raw;
    }

    private Map<String, Object> bodyMap(HttpExchange exchange) throws IOException, UserException {
        String text = body(exchange);
        if (text.trim().isEmpty()) {
            throw new UserException("请求体是空的");
        }
        try {
            return Json.object(Json.parse(text));
        } catch (RuntimeException e) {
            throw new UserException("请求不是合法的 JSON");
        }
    }

    private static String body(HttpExchange exchange) throws IOException, UserException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InputStream in = exchange.getRequestBody();
        byte[] buf = new byte[4096];
        int total = 0;
        int n;
        while ((n = in.read(buf)) >= 0) {
            total += n;
            if (total > 1024 * 1024) {
                throw new UserException("请求体过大");
            }
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private void sendFile(HttpExchange exchange, String path) throws IOException {
        String name = path.substring(1);
        byte[] bytes = resource("web/" + name);
        if (bytes == null) {
            sendError(exchange, HttpURLConnection.HTTP_NOT_FOUND, "页面不存在");
            return;
        }
        String type = "application/octet-stream";
        if (name.endsWith(".html")) {
            type = "text/html; charset=utf-8";
        } else if (name.endsWith(".css")) {
            type = "text/css; charset=utf-8";
        } else if (name.endsWith(".js")) {
            type = "text/javascript; charset=utf-8";
        }
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static byte[] resource(String name) throws IOException {
        InputStream in = ApiServer.class.getClassLoader().getResourceAsStream(name);
        if (in == null) {
            return null;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = Json.write(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void sendError(HttpExchange exchange, int status, String message) throws IOException {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("error", message == null || message.trim().isEmpty() ? "未知错误" : message);
        sendJson(exchange, status, body);
    }

    private static String message(Throwable error) {
        String text = error.getMessage();
        return text == null || text.trim().isEmpty() ? error.toString() : text;
    }
}
