package lightide.config;

import lightide.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AppConfig {
    public int port = 4717;
    public String javaHome = "";
    public String mavenCmd = "mvn";
    public String gradleCmd = "gradle";
    public String catalinaHome = "";
    public List<Project> projects = new ArrayList<Project>();

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("port", Integer.valueOf(port));
        map.put("javaHome", javaHome);
        map.put("mavenCmd", mavenCmd);
        map.put("gradleCmd", gradleCmd);
        map.put("catalinaHome", catalinaHome);
        List<Object> items = new ArrayList<Object>();
        for (Project project : projects) {
            items.add(project.toMap());
        }
        map.put("projects", items);
        return map;
    }

    public Map<String, Object> settingsMap() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("port", Integer.valueOf(port));
        map.put("javaHome", javaHome);
        map.put("mavenCmd", mavenCmd);
        map.put("gradleCmd", gradleCmd);
        map.put("catalinaHome", catalinaHome);
        return map;
    }

    public static AppConfig fromMap(Map<String, Object> map) {
        AppConfig config = new AppConfig();
        config.port = Json.integer(map.get("port"), 4717);
        config.javaHome = Json.text(map.get("javaHome"), "");
        config.mavenCmd = Json.text(map.get("mavenCmd"), "mvn");
        config.gradleCmd = Json.text(map.get("gradleCmd"), "gradle");
        config.catalinaHome = Json.text(map.get("catalinaHome"), "");
        config.projects = new ArrayList<Project>();
        Object raw = map.get("projects");
        if (raw != null) {
            for (Object item : Json.array(raw)) {
                config.projects.add(Project.fromMap(Json.object(item)));
            }
        }
        return config;
    }
}
