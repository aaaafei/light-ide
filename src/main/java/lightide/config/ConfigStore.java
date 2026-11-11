package lightide.config;

import lightide.UserException;
import lightide.json.Json;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ConfigStore {
    private final Path file;
    private AppConfig config;

    public ConfigStore() throws IOException, UserException {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.trim().isEmpty()) {
            appData = System.getProperty("user.home");
        }
        Path dir = Paths.get(appData, "light-ide");
        Files.createDirectories(dir);
        this.file = dir.resolve("config.json");
        this.config = load();
    }

    public synchronized AppConfig get() {
        return config;
    }

    public synchronized Path directory() {
        return file.getParent();
    }

    public synchronized Project findProject(String id) {
        for (Project project : config.projects) {
            if (project.id.equals(id)) {
                return project;
            }
        }
        return null;
    }

    public synchronized void addProject(Project project) throws IOException, UserException {
        String canonical = new File(project.root).getCanonicalPath();
        for (Project existing : config.projects) {
            if (new File(existing.root).getCanonicalPath().equalsIgnoreCase(canonical)) {
                throw new UserException("这个目录已经添加过了");
            }
        }
        project.root = canonical;
        config.projects.add(project);
        save();
    }

    public synchronized Map<String, Object> settingsView() {
        return config.settingsMap();
    }

    public synchronized List<Object> projectMaps() {
        List<Object> projects = new ArrayList<Object>();
        for (Project project : config.projects) {
            projects.add(project.toMap());
        }
        return projects;
    }

    public synchronized void removeProject(String id) throws IOException, UserException {
        Project found = null;
        for (Project project : config.projects) {
            if (project.id.equals(id)) {
                found = project;
                break;
            }
        }
        if (found == null) {
            throw new UserException("项目不存在");
        }
        config.projects.remove(found);
        save();
    }

    public synchronized RunConfig addConfig(String projectId, RunConfig runConfig) throws IOException, UserException {
        Project project = requireProject(projectId);
        project.configs.add(runConfig);
        save();
        return runConfig;
    }

    public synchronized RunConfig replaceConfig(String projectId, RunConfig runConfig) throws IOException, UserException {
        Project project = requireProject(projectId);
        for (int i = 0; i < project.configs.size(); i++) {
            if (project.configs.get(i).id.equals(runConfig.id)) {
                project.configs.set(i, runConfig);
                save();
                return runConfig;
            }
        }
        throw new UserException("运行配置不存在");
    }

    public synchronized void removeConfig(String projectId, String configId) throws IOException, UserException {
        Project project = requireProject(projectId);
        for (int i = 0; i < project.configs.size(); i++) {
            if (project.configs.get(i).id.equals(configId)) {
                project.configs.remove(i);
                save();
                return;
            }
        }
        throw new UserException("运行配置不存在");
    }

    public synchronized void updateSettings(int port, String javaHome, String mavenCmd, String gradleCmd, String catalinaHome)
            throws IOException, UserException {
        if (port < 1 || port > 65535) {
            throw new UserException("端口需要在 1 到 65535 之间");
        }
        config.port = port;
        config.javaHome = javaHome == null ? "" : javaHome.trim();
        config.mavenCmd = blank(mavenCmd) ? "mvn" : mavenCmd.trim();
        config.gradleCmd = blank(gradleCmd) ? "gradle" : gradleCmd.trim();
        config.catalinaHome = catalinaHome == null ? "" : catalinaHome.trim();
        save();
    }

    private Project requireProject(String projectId) throws UserException {
        Project project = findProject(projectId);
        if (project == null) {
            throw new UserException("项目不存在");
        }
        return project;
    }

    private AppConfig load() throws IOException, UserException {
        if (!Files.exists(file)) {
            AppConfig created = new AppConfig();
            String javaHome = System.getenv("JAVA_HOME");
            if (javaHome != null) {
                created.javaHome = javaHome;
            }
            String catalinaHome = System.getenv("CATALINA_HOME");
            if (catalinaHome != null) {
                created.catalinaHome = catalinaHome;
            }
            config = created;
            save();
            return created;
        }
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        try {
            return AppConfig.fromMap(Json.object(Json.parse(text)));
        } catch (RuntimeException e) {
            throw new UserException("无法解析 " + file + "：" + e.getMessage());
        }
    }

    private void save() throws IOException {
        Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        Files.write(tmp, Json.writePretty(config.toMap()).getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
