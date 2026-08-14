package com.example.myhelper.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Docker 容器自动启动器
 * 在应用启动前检查并自动启动 Neo4j 等必要的 Docker 容器
 */
@Component
public class DockerInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DockerInitializer.class);

    @Override
    public void run(String... args) throws Exception {
        // 检查并启动 Neo4j
        ensureContainerRunning("neo4j", "docker compose up -d neo4j");

        // 检查并启动 Home Assistant
        ensureContainerRunning("homeassistant", "docker compose up -d homeassistant");
        
        log.info("✅ 所有必要的 Docker 服务已就绪");
    }

    /**
     * 检查容器是否在运行，如果没有则启动它
     *
     * @param containerName 容器名称
     * @param startCommand  启动命令
     */
    private void ensureContainerRunning(String containerName, String startCommand) {
        try {
            // 检查容器是否在运行
            ProcessBuilder pb = new ProcessBuilder("docker", "inspect", "-f", "{{.State.Running}}", containerName);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String status = new String(p.getInputStream().readAllBytes()).trim();

            if ("true".equalsIgnoreCase(status)) {
                log.info("✅ {} 已在运行", containerName);
                return;
            }

            // 如果容器存在但已停止，直接启动
            if (!status.isEmpty()) {
                log.info("📦 {} 已停止，正在启动...", containerName);
                Runtime.getRuntime().exec("docker start " + containerName);
                waitForContainer(containerName, 3000);
                log.info("✅ {} 启动成功", containerName);
                return;
            }

            // 容器不存在，用 docker compose 创建并启动
            log.info("📦 {} 未创建，正在通过 docker compose 启动...", containerName);
            Process composeProcess = Runtime.getRuntime().exec(startCommand);
            composeProcess.waitFor();
            waitForContainer(containerName, 5000);
            log.info("✅ {} 创建并启动成功", containerName);

        } catch (Exception e) {
            log.error("⚠️ 无法启动 {}: {}", containerName, e.getMessage());
            log.error("   请确保 Docker Desktop 正在运行，并手动执行: docker compose up -d");
        }
    }

    /**
     * 等待容器就绪
     */
    private void waitForContainer(String containerName, int maxWaitMs) throws Exception {
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            ProcessBuilder pb = new ProcessBuilder("docker", "inspect", "-f", "{{.State.Running}}", containerName);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String status = new String(p.getInputStream().readAllBytes()).trim();
            if ("true".equalsIgnoreCase(status)) {
                Thread.sleep(500);
                return;
            }
            Thread.sleep(500);
        }
        throw new RuntimeException("等待 " + containerName + " 启动超时");
    }
}
