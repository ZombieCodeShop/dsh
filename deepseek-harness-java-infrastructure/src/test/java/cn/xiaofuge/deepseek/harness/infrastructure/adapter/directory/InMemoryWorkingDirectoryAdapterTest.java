package cn.xiaofuge.deepseek.harness.infrastructure.adapter.directory;

import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * InMemoryWorkingDirectoryAdapter（working-directory seam 默认实现）测评。
 *
 * <p>覆盖：登记/热更新/查询、绝对路径透传归一化、相对路径按会话当前
 * 目录解析、未登记会话回退进程目录、空白输入安全忽略。</p>
 */
class InMemoryWorkingDirectoryAdapterTest {

    @Test
    void registerAndQueryCurrentCwd() {
        InMemoryWorkingDirectoryAdapter port = new InMemoryWorkingDirectoryAdapter();
        port.register("s1", "/tmp/proj-a");

        assertEquals("/tmp/proj-a", port.currentCwd("s1"));
        assertNull(port.currentCwd("s2"));
        assertNull(port.currentCwd(null));
    }

    @Test
    void updateHotSwapsCwdForReusedSession() {
        InMemoryWorkingDirectoryAdapter port = new InMemoryWorkingDirectoryAdapter();
        port.register("s1", "/tmp/proj-a");
        port.update("s1", "/tmp/proj-b");

        assertEquals("/tmp/proj-b", port.currentCwd("s1"));
    }

    @Test
    void blankRegisterAndUpdateAreIgnored() {
        InMemoryWorkingDirectoryAdapter port = new InMemoryWorkingDirectoryAdapter();
        port.register("s1", "/tmp/proj-a");
        port.update("s1", "  ");
        port.register(null, "/tmp/other");
        port.register("s3", null);

        assertEquals("/tmp/proj-a", port.currentCwd("s1"));
        assertNull(port.currentCwd("s3"));
    }

    @Test
    void absolutePathPassesThroughNormalized() {
        InMemoryWorkingDirectoryAdapter port = new InMemoryWorkingDirectoryAdapter();
        port.register("s1", "/tmp/proj-a");

        assertEquals("/tmp/other",
                port.resolve("s1", "/tmp/proj-a/../other"));
    }

    @Test
    void relativePathResolvesAgainstRegisteredCwd() {
        InMemoryWorkingDirectoryAdapter port = new InMemoryWorkingDirectoryAdapter();
        port.register("s1", "/tmp/proj-a");

        assertEquals("/tmp/proj-a/src/main",
                port.resolve("s1", "src/main"));
    }

    @Test
    void relativePathAfterUpdateFollowsNewCwd() {
        InMemoryWorkingDirectoryAdapter port = new InMemoryWorkingDirectoryAdapter();
        port.register("s1", "/tmp/proj-a");
        port.update("s1", "/tmp/proj-b");

        assertEquals("/tmp/proj-b/docs",
                port.resolve("s1", "docs"));
    }

    @Test
    void unregisteredSessionResolvesAgainstProcessDirectory() {
        InMemoryWorkingDirectoryAdapter port = new InMemoryWorkingDirectoryAdapter();

        String expected = Paths.get(System.getProperty("user.dir"), "notes.md")
                .normalize().toString();
        assertEquals(expected, port.resolve("unknown", "notes.md"));
        assertEquals(expected, port.resolve(null, "notes.md"));
    }

    @Test
    void blankPathReturnsNull() {
        InMemoryWorkingDirectoryAdapter port = new InMemoryWorkingDirectoryAdapter();
        port.register("s1", "/tmp/proj-a");

        assertNull(port.resolve("s1", null));
        assertNull(port.resolve("s1", "   "));
    }
}
