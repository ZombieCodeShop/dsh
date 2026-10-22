package cn.xiaofuge.deepseek.harness.domain.tool;

import cn.xiaofuge.deepseek.harness.domain.agent.contract.outbound.UserQuestionGateway;
import cn.xiaofuge.deepseek.harness.domain.agent.service.ask.AskUserTool;
import cn.xiaofuge.deepseek.harness.domain.agent.service.subagent.SubagentRegistry;
import cn.xiaofuge.deepseek.harness.domain.agent.service.subagent.SubagentTool;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolDefinition;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult;
import cn.xiaofuge.deepseek.harness.domain.model.valobj.TextBlock;
import cn.xiaofuge.deepseek.harness.domain.tool.adapter.ToolJsonCodec;
import cn.xiaofuge.deepseek.harness.domain.tool.fs.FsReadTool;
import cn.xiaofuge.deepseek.harness.domain.tool.fs.adapter.port.IFsPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 已迁移工具的共享契约：名字非空、schema 是可序列化的 JSON 对象、
 * 非对象参数一律返回 INVALID_ARGS（而不是抛异常）。
 *
 * <p>每迁移一个工具就把它加进 {@link #pilotTools()}。</p>
 */
class ToolContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final ToolJsonCodec CODEC = new ToolJsonCodec() {
        @Override
        public String write(Object payload) {
            try {
                return MAPPER.writeValueAsString(payload);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public Object parse(String json) {
            try {
                return MAPPER.readValue(json, Object.class);
            } catch (Exception e) {
                throw new IllegalArgumentException(e);
            }
        }

        @Override
        public <T> T convert(Object args, Class<T> type) {
            return MAPPER.convertValue(args, type);
        }
    };

    static Stream<ToolDefinition> pilotTools() {
        return Stream.of(
                new FsReadTool(new StubFsPort(), CODEC),
                new AskUserTool(new NoopGateway(), "agent-1", CODEC),
                new SubagentTool(new SubagentRegistry(), "spawn-in-process", "subagent", CODEC));
    }

    @ParameterizedTest
    @MethodSource("pilotTools")
    void toolsHonourSharedContract(ToolDefinition tool) throws Exception {
        assertFalse(tool.name() == null || tool.name().isBlank(), "name must not be blank");
        assertInstanceOf(Map.class, tool.parameters());

        JsonNode schema = MAPPER.readTree(MAPPER.writeValueAsString(tool.parameters()));
        assertEquals("object", schema.path("type").asText(), "参数 schema 必须是 JSON object");

        ToolExecutionResult result = tool.execute("not-a-map", null).join();
        assertTrue(result.isError());
        assertEquals("INVALID_ARGS",
                ((ToolExecutionResult.Failure) result).error().info().code());
    }

    @Test
    void fsReadAppliesDefaultOffsetAndLimit() {
        StubFsPort port = new StubFsPort();
        FsReadTool tool = new FsReadTool(port, CODEC);

        ToolExecutionResult result = tool.execute(Map.of("path", " a.txt "), null).join();

        assertFalse(result.isError());
        assertEquals("a.txt", port.lastPath.get());
        assertEquals(1, port.lastOffset.get());
        assertEquals(2000, port.lastLimit.get());
        assertEquals("content", ((TextBlock) result.content().get(0)).text());
    }

    @Test
    void fsReadMissingPathFailsWithInvalidArgs() {
        FsReadTool tool = new FsReadTool(new StubFsPort(), CODEC);

        ToolExecutionResult result = tool.execute(Map.of("offset", 5), null).join();

        assertTrue(result.isError());
        assertEquals("INVALID_ARGS",
                ((ToolExecutionResult.Failure) result).error().info().code());
    }

    private static final class StubFsPort implements IFsPort {
        final AtomicReference<String> lastPath = new AtomicReference<>();
        final AtomicInteger lastOffset = new AtomicInteger();
        final AtomicInteger lastLimit = new AtomicInteger();

        @Override
        public String read(String path, int offset, int limit) {
            lastPath.set(path);
            lastOffset.set(offset);
            lastLimit.set(limit);
            return "content";
        }

        @Override
        public void write(String path, String content) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> search(String directory, String pattern, int maxResults) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean exists(String path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<DirEntry> listDir(String directory) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> grep(String directory, String pattern, String include, boolean isRegex, int maxResults) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] readBytes(String path) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class NoopGateway implements UserQuestionGateway {
        @Override
        public UserAnswer ask(UserQuestionRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }
}