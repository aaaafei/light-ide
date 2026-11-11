package lightide.config;

import lightide.json.Json;

import java.util.LinkedHashMap;
import java.util.Map;

public final class RunConfig {
    public String id;
    public String name;
    public String type;
    public String goals = "";
    public String args = "";
    public String script = "";
    public String cwd = "";
    public String catalinaBase = "";
    public String warPath = "";
    public Map<String, String> env = new LinkedHashMap<String, String>();
    public boolean debug;
    public int debugPort = 5005;

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("id", id);
        map.put("name", name);
        map.put("type", type);
        map.put("goals", goals);
        map.put("args", args);
        map.put("script", script);
        map.put("cwd", cwd);
        map.put("catalinaBase", catalinaBase);
        map.put("warPath", warPath);
        map.put("env", new LinkedHashMap<String, String>(env));
        map.put("debug", Boolean.valueOf(debug));
        map.put("debugPort", Integer.valueOf(debugPort));
        return map;
    }

    public static RunConfig fromMap(Map<String, Object> map) {
        RunConfig config = new RunConfig();
        config.id = Json.text(map.get("id"), "");
        config.name = Json.text(map.get("name"), "");
        config.type = Json.text(map.get("type"), "");
        config.goals = Json.text(map.get("goals"), "");
        config.args = Json.text(map.get("args"), "");
        config.script = Json.text(map.get("script"), "");
        config.cwd = Json.text(map.get("cwd"), "");
        config.catalinaBase = Json.text(map.get("catalinaBase"), "");
        config.warPath = Json.text(map.get("warPath"), "");
        config.env = new LinkedHashMap<String, String>();
        Object rawEnv = map.get("env");
        if (rawEnv instanceof Map) {
            Map<?, ?> env = (Map<?, ?>) rawEnv;
            for (Map.Entry<?, ?> entry : env.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    config.env.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            }
        }
        Object debug = map.get("debug");
        config.debug = debug instanceof Boolean && ((Boolean) debug).booleanValue();
        config.debugPort = Json.integer(map.get("debugPort"), 5005);
        return config;
    }
}
