package lightide.launch;

import java.io.File;
import java.util.Collections;
import java.util.Map;

public final class LaunchSpec {
    public final String type;
    public final String command;
    public final File directory;
    public final Map<String, String> env;
    public final boolean debug;
    public final int debugPort;
    public final File warSource;
    public final File warDest;
    public final String catalinaHome;
    public final String catalinaBase;

    public LaunchSpec(String type, String command, File directory, Map<String, String> env, boolean debug, int debugPort,
                      File warSource, File warDest, String catalinaHome, String catalinaBase) {
        this.type = type;
        this.command = command;
        this.directory = directory;
        this.env = Collections.unmodifiableMap(env);
        this.debug = debug;
        this.debugPort = debugPort;
        this.warSource = warSource;
        this.warDest = warDest;
        this.catalinaHome = catalinaHome;
        this.catalinaBase = catalinaBase;
    }
}
