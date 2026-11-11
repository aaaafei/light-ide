package lightide.detect;

import lightide.config.Project;
import lightide.config.RunConfig;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

public final class Detector {
    private Detector() {
    }

    public static Project detect(File dir) throws IOException {
        Project project = new Project();
        project.id = UUID.randomUUID().toString();
        project.name = dir.getName();
        project.root = dir.getAbsolutePath();
        project.webapp = new File(dir, "src/main/webapp").isDirectory() || new File(dir, "WEB-INF").isDirectory();

        File pom = new File(dir, "pom.xml");
        if (pom.isFile()) {
            boolean springBoot = readPrefix(pom).contains("spring-boot");
            RunConfig config = base("maven", springBoot ? "Spring Boot" : "Maven");
            config.goals = springBoot ? "spring-boot:run" : "";
            project.configs.add(config);
        }

        File gradle = new File(dir, "build.gradle");
        File gradleKts = new File(dir, "build.gradle.kts");
        if (gradle.isFile() || gradleKts.isFile()) {
            String text = "";
            if (gradle.isFile()) {
                text = text + readPrefix(gradle);
            }
            if (gradleKts.isFile()) {
                text = text + readPrefix(gradleKts);
            }
            boolean springBoot = text.contains("springframework.boot") || text.contains("spring-boot");
            RunConfig config = base("gradle", springBoot ? "Gradle Boot" : "Gradle");
            config.goals = springBoot ? "bootRun" : "";
            project.configs.add(config);
        }

        if (project.webapp) {
            project.configs.add(base("tomcat", "Tomcat"));
        }
        return project;
    }

    private static RunConfig base(String type, String name) {
        RunConfig config = new RunConfig();
        config.id = UUID.randomUUID().toString();
        config.type = type;
        config.name = name;
        return config;
    }

    private static String readPrefix(File file) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        int length = Math.min(bytes.length, 256 * 1024);
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }
}
