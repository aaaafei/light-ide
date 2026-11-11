package lightide.launch;

import lightide.UserException;
import lightide.config.AppConfig;
import lightide.config.Project;
import lightide.config.RunConfig;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class Commands {
    private Commands() {
    }

    public static LaunchSpec prepare(AppConfig settings, Project project, RunConfig config) throws UserException {
        File directory = cwd(project, config);
        Map<String, String> env = new LinkedHashMap<String, String>();
        if (!blank(settings.javaHome)) {
            env.put("JAVA_HOME", settings.javaHome.trim());
        }
        env.putAll(config.env);

        if ("maven".equals(config.type)) {
            if (blank(config.goals)) {
                throw new UserException("Maven 配置缺少 goals");
            }
            String goals = config.goals.trim();
            if (config.debug && !goals.contains("spring-boot:run")) {
                throw new UserException("Maven 调试只注入到 spring-boot:run。请把 goals 写成 spring-boot:run，或关闭调试。");
            }
            int port = debugPort(config);
            String command = quote(commandOf(settings.mavenCmd, "mvn")) + " -B " + goals;
            command = appendArgs(command, config.args);
            String jvmArgs = springBootJvmArgs(goals, config.args, config.debug, port);
            if (jvmArgs != null) {
                command = command + " " + quote("-Dspring-boot.run.jvmArguments=" + jvmArgs);
            }
            return new LaunchSpec(config.type, command, directory, env, config.debug, config.debug ? port : 0,
                    null, null, "", "");
        }

        if ("gradle".equals(config.type)) {
            if (blank(config.goals)) {
                throw new UserException("Gradle 配置缺少任务");
            }
            String tasks = config.goals.trim();
            if (config.debug && !tasks.contains("bootRun")) {
                throw new UserException("Gradle 调试使用 bootRun --debug-jvm。请把任务写成 bootRun，或关闭调试。");
            }
            File wrapper = new File(project.root, "gradlew.bat");
            String gradle = wrapper.isFile() ? wrapper.getAbsolutePath() : commandOf(settings.gradleCmd, "gradle");
            String command = quote(gradle) + " --no-daemon --console=plain " + tasks;
            command = appendArgs(command, config.args);
            if (config.debug) {
                command = command + " --debug-jvm";
            }
            return new LaunchSpec(config.type, command, directory, env, config.debug, config.debug ? 5005 : 0,
                    null, null, "", "");
        }

        if ("tomcat".equals(config.type)) {
            if (blank(settings.catalinaHome)) {
                throw new UserException("请先在设置里填写 CATALINA_HOME");
            }
            File home = new File(settings.catalinaHome.trim());
            File bat = new File(home, "bin/catalina.bat");
            if (!bat.isFile()) {
                throw new UserException("找不到 " + bat.getAbsolutePath());
            }
            String basePath = blank(config.catalinaBase) ? home.getAbsolutePath() : config.catalinaBase.trim();
            File base = new File(basePath);
            if (!base.isDirectory()) {
                throw new UserException("CATALINA_BASE 不是目录: " + base.getAbsolutePath());
            }
            env.put("CATALINA_HOME", home.getAbsolutePath());
            env.put("CATALINA_BASE", base.getAbsolutePath());
            int port = debugPort(config);
            if (config.debug) {
                env.put("JPDA_TRANSPORT", "dt_socket");
                env.put("JPDA_ADDRESS", Integer.toString(port));
                env.put("JPDA_SUSPEND", "n");
            }
            File warSource = null;
            File warDest = null;
            if (!blank(config.warPath)) {
                warSource = new File(config.warPath.trim());
                if (!warSource.isFile()) {
                    throw new UserException("war 不存在: " + warSource.getAbsolutePath());
                }
                warDest = new File(base, "webapps/" + warSource.getName());
            }
            String command = quote(bat.getAbsolutePath()) + (config.debug ? " jpda run" : " run");
            return new LaunchSpec(config.type, command, home, env, config.debug, config.debug ? port : 0,
                    warSource, warDest, home.getAbsolutePath(), base.getAbsolutePath());
        }

        if ("script".equals(config.type)) {
            if (config.debug) {
                throw new UserException("自定义脚本不注入调试参数。请在脚本里自行添加 JDWP。");
            }
            if (blank(config.script)) {
                throw new UserException("请填写脚本路径");
            }
            File script = new File(config.script.trim());
            if (!script.isAbsolute()) {
                script = new File(directory, config.script.trim());
            }
            if (!script.isFile()) {
                throw new UserException("脚本不存在: " + script.getAbsolutePath());
            }
            String name = script.getName().toLowerCase(Locale.ROOT);
            String command;
            if (name.endsWith(".ps1")) {
                command = "powershell.exe -NoProfile -ExecutionPolicy Bypass -File " + quote(script.getAbsolutePath());
            } else {
                command = quote(script.getAbsolutePath());
            }
            command = appendArgs(command, config.args);
            return new LaunchSpec(config.type, command, directory, env, false, 0, null, null, "", "");
        }

        throw new UserException("未知的启动类型: " + config.type);
    }

    public static String quote(String value) throws UserException {
        if (value.indexOf('"') >= 0) {
            throw new UserException("路径里不能包含双引号: " + value);
        }
        // cmd 不会给带引号且没有扩展名的名字补 .cmd，call "mvn" 会直接失败。
        if (!needsQuote(value)) {
            return value;
        }
        return "\"" + value + "\"";
    }

    private static boolean needsQuote(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= ' ' || c == '&' || c == '|' || c == '<' || c == '>' || c == '^' || c == '(' || c == ')') {
                return true;
            }
        }
        return false;
    }

    private static String springBootJvmArgs(String goals, String args, boolean debug, int port) throws UserException {
        if (!goals.contains("spring-boot:run")) {
            if (debug) {
                throw new UserException("Maven 调试只注入到 spring-boot:run。请把 goals 写成 spring-boot:run，或关闭调试。");
            }
            return null;
        }
        boolean userJvmArgs = contains(goals, "spring-boot.run.jvmArguments") || contains(args, "spring-boot.run.jvmArguments");
        boolean userHeap = contains(goals, "-Xmx") || contains(args, "-Xmx");
        if (debug && userJvmArgs) {
            throw new UserException("调试模式会设置 spring-boot.run.jvmArguments。请去掉额外参数里的同名项，或关闭调试。");
        }
        StringBuilder jvm = new StringBuilder();
        if (!userJvmArgs && !userHeap) {
            // JDK 8 在大内存机器上会把最大堆开到数 GB，fork 出来的应用经常在启动阶段耗尽本机内存。
            jvm.append("-Xmx1024m");
        }
        if (debug) {
            if (jvm.length() > 0) {
                jvm.append(' ');
            }
            jvm.append(jdwp(port));
        }
        return jvm.length() == 0 ? null : jvm.toString();
    }

    private static boolean contains(String text, String needle) {
        return text != null && text.contains(needle);
    }

    private static String jdwp(int port) {
        return "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=" + port;
    }

    private static int debugPort(RunConfig config) throws UserException {
        int port = config.debugPort <= 0 ? 5005 : config.debugPort;
        if (port < 1 || port > 65535) {
            throw new UserException("调试端口需要在 1 到 65535 之间");
        }
        return port;
    }

    private static File cwd(Project project, RunConfig config) throws UserException {
        File dir = blank(config.cwd) ? new File(project.root) : new File(config.cwd.trim());
        if (!dir.isDirectory()) {
            throw new UserException("工作目录不存在: " + dir.getAbsolutePath());
        }
        return dir;
    }

    private static String commandOf(String configured, String fallback) {
        return blank(configured) ? fallback : configured.trim();
    }

    private static String appendArgs(String command, String args) {
        if (blank(args)) {
            return command;
        }
        return command + " " + args.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
