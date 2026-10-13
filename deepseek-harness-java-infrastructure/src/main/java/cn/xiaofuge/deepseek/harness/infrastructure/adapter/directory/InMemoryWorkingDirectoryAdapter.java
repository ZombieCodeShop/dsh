package cn.xiaofuge.deepseek.harness.infrastructure.adapter.directory;

import cn.xiaofuge.deepseek.harness.domain.session.directory.WorkingDirectoryPort;

import java.io.File;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于内存的会话工作目录适配器。
 *
 * <p>对应 TS 侧 working-directory seam 的默认实现：进程内维护
 * sessionId → cwd 映射，目录状态不持久化（冷恢复时由调用方按
 * 会话日志中的 SessionHeader.cwd 重新登记）。</p>
 *
 * <p>调用流程：领域端口 → 基础设施适配器 → 内存状态。</p>
 *
 * @author 小傅哥
 * @website bugstack.cn
 */
public class InMemoryWorkingDirectoryAdapter implements WorkingDirectoryPort {

    private final Map<String, String> cwdBySession = new ConcurrentHashMap<>();

    @Override
    public void register(String sessionId, String cwd) {
        if (isBlank(sessionId) || isBlank(cwd)) {
            return;
        }
        cwdBySession.put(sessionId, cwd.trim());
    }

    @Override
    public void update(String sessionId, String cwd) {
        register(sessionId, cwd);
    }

    @Override
    public String currentCwd(String sessionId) {
        if (isBlank(sessionId)) {
            return null;
        }
        return cwdBySession.get(sessionId);
    }

    @Override
    public String resolve(String sessionId, String path) {
        if (isBlank(path)) {
            return null;
        }
        String trimmed = path.trim();
        File candidate = new File(trimmed);
        if (candidate.isAbsolute()) {
            return Paths.get(trimmed).normalize().toString();
        }
        String base = currentCwd(sessionId);
        if (isBlank(base)) {
            base = System.getProperty("user.dir");
        }
        Objects.requireNonNull(base, "base cwd must not be null");
        return Paths.get(base, trimmed).normalize().toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
