package com.example.myhelper.application;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 单机应用的派发器所有权。锁证明旧本地进程已退出，不证明远端工具效果已结束。 */
public final class TaskProcessLock implements AutoCloseable {
    /** 标准 UUID 使用 ASCII 字符，其文本和 UTF-8 编码长度均为 36。 */
    private static final int OWNER_ID_LENGTH = 36;
    /** 正式应用锁保留到 JVM 退出；Spring 关闭时工作线程可能仍在收尾。 */
    private static final List<TaskProcessLock> PROCESS_LOCKS = new ArrayList<>();
    /** 同一安装目录跨启动保持的所有者编号。 */
    private final String ownerId;
    /** 被操作系统锁住的文件，运行期间不能替换或删除。 */
    private final FileChannel channel;
    /** 与旧进程互斥的实际锁。 */
    private final FileLock lock;

    private TaskProcessLock(String ownerId, FileChannel channel, FileLock lock) {
        this.ownerId = ownerId;
        this.channel = channel;
        this.lock = lock;
    }

    public static synchronized TaskProcessLock holdForProcess(Path directory) throws IOException {
        TaskProcessLock acquired = acquire(directory);
        PROCESS_LOCKS.add(acquired);
        return acquired;
    }

    /** 检查或嵌入宿主可显式管理生命周期；关闭前须确保全部本地派发器已停止。 */
    public static TaskProcessLock acquire(Path directory) throws IOException {
        Files.createDirectories(directory);
        FileChannel channel = FileChannel.open(directory.resolve("task-owner.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        FileLock lock = null;
        try {
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException occupied) {
                throw new IllegalStateException("本任务运行目录已有活动进程，不能接管其任务", occupied);
            }
            if (lock == null) {
                throw new IllegalStateException("本任务运行目录已被其他进程占用");
            }
            String owner;
            if (channel.size() == 0) {
                owner = UUID.randomUUID().toString();
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(owner);
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                channel.force(true);
            } else {
                if (channel.size() != OWNER_ID_LENGTH) {
                    throw new IllegalStateException("任务所有者文件损坏，须核对旧运行，不能重新生成身份");
                }
                ByteBuffer bytes = ByteBuffer.allocate(OWNER_ID_LENGTH);
                while (bytes.hasRemaining()) {
                    if (channel.read(bytes) < 0) {
                        throw new IOException("任务所有者文件读取不完整");
                    }
                }
                bytes.flip();
                owner = StandardCharsets.UTF_8.decode(bytes).toString();
                if (!UUID.fromString(owner).toString().equals(owner)) {
                    throw new IllegalStateException("任务所有者身份无效");
                }
            }
            return new TaskProcessLock(owner, channel, lock);
        } catch (RuntimeException | IOException failure) {
            if (lock != null) {
                lock.release();
            }
            channel.close();
            throw failure;
        }
    }

    public String ownerId() {
        if (!lock.isValid()) {
            throw new IllegalStateException("任务进程锁已失效");
        }
        return ownerId;
    }

    @Override
    public void close() throws IOException {
        synchronized (TaskProcessLock.class) {
            if (PROCESS_LOCKS.contains(this)) {
                throw new IllegalStateException("正式应用所有权保留到进程退出");
            }
        }
        lock.release();
        channel.close();
    }
}
