package com.example.myhelper.application;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import static org.junit.jupiter.api.Assertions.*;

/** 使用真实操作系统文件锁和子 JVM 验证进程所有权，不需要数据库。 */
public class ProcessOwnershipTest {
    @Test
    void sameDirectoryIsExclusiveAndOwnerSurvivesReopen() throws Exception {
        Path directory = Files.createTempDirectory(Path.of("checks/build"), "ownership-");
        String owner;
        try (TaskProcessLock first = TaskProcessLock.acquire(directory)) {
            owner = first.ownerId();
            assertThrows(IllegalStateException.class, new Executable() {
                @Override public void execute() throws Throwable { TaskProcessLock.acquire(directory); }
            });
        }
        try (TaskProcessLock reopened = TaskProcessLock.acquire(directory)) {
            assertEquals(owner, reopened.ownerId());
        }
    }

    @Test
    void anotherProcessCannotTakeOwnershipUntilOldProcessExits() throws Exception {
        Path directory = Files.createTempDirectory(Path.of("checks/build"), "ownership-process-");
        String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                LockHolder.class.getName(), directory.toAbsolutePath().toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try {
            String owner = child.inputReader().readLine();
            assertNotNull(owner, "子 JVM 应在持锁后报告身份");
            assertEquals(36, owner.length());
            assertThrows(IllegalStateException.class, new Executable() {
                @Override public void execute() throws Throwable { TaskProcessLock.acquire(directory); }
            });
            child.destroyForcibly();
            assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            try (TaskProcessLock recovered = TaskProcessLock.acquire(directory)) {
                assertEquals(owner, recovered.ownerId());
            }
        } finally {
            child.destroyForcibly();
            child.waitFor(10, TimeUnit.SECONDS);
        }
    }

    /** 检查专用子进程，不是生产启动入口。 */
    public static final class LockHolder {
        public static void main(String[] arguments) throws Exception {
            TaskProcessLock lock = TaskProcessLock.holdForProcess(Path.of(arguments[0]));
            System.out.println(lock.ownerId());
            System.out.flush();
            System.in.read();
        }
    }
}
