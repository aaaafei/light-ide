package lightide.config;

import lightide.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Project {
    public String id;
    public String name;
    public String root;
    public boolean webapp;
    public List<RunConfig> configs = new ArrayList<RunConfig>();

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("id", id);
        map.put("name", name);
        map.put("root", root);
        map.put("webapp", Boolean.valueOf(webapp));
        List<Object> items = new ArrayList<Object>();
        for (RunConfig config : configs) {
            items.add(config.toMap());
        }
        map.put("configs", items);
        return map;
    }

    public static Project fromMap(Map<String, Object> map) {
        Project project = new Project();
        project.id = Json.text(map.get("id"), "");
        project.name = Json.text(map.get("name"), "");
        project.root = Json.text(map.get("root"), "");
        Object webapp = map.get("webapp");
        project.webapp = webapp instanceof Boolean && ((Boolean) webapp).booleanValue();
        project.configs = new ArrayList<RunConfig>();
        Object raw = map.get("configs");
        if (raw != null) {
            for (Object item : Json.array(raw)) {
                project.configs.add(RunConfig.fromMap(Json.object(item)));
            }
        }
        return project;
    }

    public RunConfig findConfig(String configId) {
        for (RunConfig config : configs) {
            if (config.id.equals(configId)) {
                return config;
            }
        }
        return null;
    }
}
