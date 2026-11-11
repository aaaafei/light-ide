package lightide;

import lightide.config.ConfigStore;
import lightide.http.ApiServer;
import lightide.supervisor.Supervisor;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        boolean openBrowser = true;
        for (String arg : args) {
            if ("--no-browser".equals(arg)) {
                openBrowser = false;
            }
        }
        ConfigStore store = new ConfigStore();
        Supervisor supervisor = new Supervisor(store);
        supervisor.recover();
        int port = store.get().port;
        if (port < 1 || port > 65535) {
            throw new UserException("配置里的端口无效: " + port);
        }
        final ApiServer server = new ApiServer(port, store, supervisor);
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    supervisor.shutdown();
                } catch (Exception e) {
                    System.err.println(e.getMessage());
                }
                server.stop();
            }
        }, "light-ide-shutdown"));
        URI uri = new URI("http://127.0.0.1:" + port + "/");
        System.out.println("light-ide " + uri);
        if (openBrowser) {
            open(uri);
        }
    }

    private static void open(URI uri) {
        if (!Desktop.isDesktopSupported()) {
            return;
        }
        try {
            Desktop.getDesktop().browse(uri);
        } catch (IOException e) {
            System.out.println("请手动打开 " + uri);
        } catch (UnsupportedOperationException e) {
            System.out.println("请手动打开 " + uri);
        }
    }
}
