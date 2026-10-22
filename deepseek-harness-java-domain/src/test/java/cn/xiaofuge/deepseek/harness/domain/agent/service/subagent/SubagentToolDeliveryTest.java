package cn.xiaofuge.deepseek.harness.domain.agent.service.subagent;

import cn.xiaofuge.deepseek.harness.domain.agent.model.entity.AgentOptions;
import cn.xiaofuge.deepseek.harness.domain.agent.service.subagent.SubagentProvider.SubagentCapabilities;
import cn.xiaofuge.deepseek.harness.domain.agent.service.subagent.SubagentProvider.SubagentResult;
import cn.xiaofuge.deepseek.harness.domain.agent.service.subagent.SubagentProvider.SubagentStartRequest;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext;
import cn.xiaofuge.deepseek.harness.domain.model.valobj.TextBlock;
import cn.xiaofuge.deepseek.harness.domain.tool.adapter.ToolJsonCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SubagentTool 统一激活交付路径的领域测评。
 *
 * <p>覆盖：delivery=parent 发起、父级会话身份/目录/委派深度真实传递
 * （此前硬编码 null 的回归防护）、可选 cwd 参数透传为 requestCwd、
 * prompt 校验、error/aborted 停止原因到工具失败的映射、激活异常兜底。</p>
 */
class SubagentToolDeliveryTest {

    private static final ToolJsonCodec CODEC = new ToolJsonCodec() {
        private final ObjectMapper mapper = new ObjectMapper();

        @Override
        public String write(Object payload) {
            try {
                return mapper.writeValueAsString(payload);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public Object parse(String json) {
            try {
                return mapper.readValue(json, Object.class);
            } catch (Exception e) {
                throw new IllegalArgumentException(e);
            }
        }

        @Override
        public <T> T convert(Object args, Class<T> type) {
            return mapper.convertValue(args, type);
        }
    };

    /** 记录收到的 spec，可编程返回结果。 */
    private static final class RecordingProvider implements SubagentProvider {
        final String name;
        final SubagentCapabilities capabilities;
        final List<SubagentActivationSpec> specs = new CopyOnWriteArrayList<>();
        volatile SubagentResult nextResult = new SubagentResult("completed",
                List.of(new TextBlock("subagent answer")), "run-1");
        volatile RuntimeException nextFailure;

        RecordingProvider(String name, SubagentCapabilities capabilities) {
            this.name = name;
            this.capabilities = capabilities;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public SubagentCapabilities capabilities() {
            return capabilities;
        }

        @Override
        public CompletableFuture<SubagentActivation> startActivation(SubagentActivationSpec spec) {
            specs.add(spec);
            if (nextFailure != null) {
                return CompletableFuture.failedFuture(nextFailure);
            }
            SubagentResult result = nextResult;
            return CompletableFuture.completedFuture(new SubagentActivation() {
                @Override public String childId() { return "child-test"; }
                @Override public String label() { return spec.label(); }
                @Override public String provider() { return spec.provider(); }
                @Override public String delivery() { return spec.delivery(); }
                @Override public boolean continuable() { return spec.continuable(); }
                @Override public CompletableFuture<SubagentResult> result() {
                    return CompletableFuture.completedFuture(result);
                }
                @Override public CompletableFuture<Void> dispose() {
                    return CompletableFuture.completedFuture(null);
                }
            });
        }
    }

    private static ToolRunContext runContext() {
        return new ToolRunContext(null);
    }

    // ── 交付路径 ────────────────────────────────────────────────

    @Test
    void executesWithParentDeliveryAndReturnsSubagentOutput() {
        SubagentRegistry registry = new SubagentRegistry();
        RecordingProvider provider = new RecordingProvider("spawn",
                new SubagentProvider.SubagentCapabilities(false, false, true));
        registry.register(provider);

        SubagentTool tool = new SubagentTool(registry, "spawn", "subagent", CODEC);
        ToolExecutionResult result = tool.execute(
                Map.of("prompt", "refactor module X"), runContext()).join();

        assertFalse(result.isError());
        ToolExecutionResult.Success success = assertInstanceOf(ToolExecutionResult.Success.class, result);
        assertEquals(1, success.content().size());
        assertInstanceOf(TextBlock.class, success.content().get(0));
        assertEquals("subagent answer", ((TextBlock) success.content().get(0)).text());

        // 统一激活：delivery=parent，resolvedCwd 留给注册表准入解析。
        assertEquals(1, provider.specs.size());
        assertEquals(SubagentActivationSpec.DELIVERY_PARENT, provider.specs.get(0).delivery());
    }

    @Test
    void propagatesParentSessionIdentityCwdAndDepth() {
        SubagentRegistry registry = new SubagentRegistry();
        RecordingProvider provider = new RecordingProvider("spawn",
                new SubagentProvider.SubagentCapabilities(false, false, true));
        registry.register(provider);

        SubagentTool tool = new SubagentTool(
                registry, "spawn", "subagent",
                () -> null,                      // parentOptions
                () -> "session-parent-7",        // parentSessionId
                () -> "/tmp/proj",               // parentCwd
                2,                               // delegationDepth
                CODEC
        );
        tool.execute(Map.of("prompt", "task"), runContext()).join();

        SubagentProvider.SubagentStartRequest request = provider.specs.get(0).request();
        assertEquals("session-parent-7", request.parentSessionId());
        assertEquals("/tmp/proj", request.parentCwd());
        assertEquals(2, request.delegationDepth());
    }

    @Test
    void cwdArgumentIsPassedAsRequestCwdForAdmissionResolution() {
        SubagentRegistry registry = new SubagentRegistry();
        RecordingProvider provider = new RecordingProvider("spawn",
                new SubagentProvider.SubagentCapabilities(false, false, true));
        registry.register(provider);

        SubagentTool tool = new SubagentTool(registry, "spawn", "subagent", CODEC);
        tool.execute(Map.of("prompt", "task", "cwd", "packages/app"), runContext()).join();

        assertEquals("packages/app", provider.specs.get(0).request().requestCwd());
    }

    @Test
    void registryAdmissionResolvesChildCwdForToolPath() {
        // 端到端（进程内）：Tool → Registry 准入 → Provider 收到已解析目录。
        SubagentRegistry registry = new SubagentRegistry();
        RecordingProvider provider = new RecordingProvider("spawn",
                new SubagentProvider.SubagentCapabilities(false, false, true));
        registry.register(provider);

        SubagentTool tool = new SubagentTool(
                registry, "spawn", "subagent",
                () -> null, () -> "session-1", () -> "/tmp/proj", 0, CODEC);
        tool.execute(Map.of("prompt", "task", "cwd", "src/main"), runContext()).join();

        assertEquals("/tmp/proj/src/main", provider.specs.get(0).resolvedCwd());
    }

    // ── 参数与错误映射 ──────────────────────────────────────────

    @Test
    void missingPromptFailsWithMissingPromptCode() {
        SubagentRegistry registry = new SubagentRegistry();
        registry.register(new RecordingProvider("spawn",
                new SubagentProvider.SubagentCapabilities(false, false, true)));

        SubagentTool tool = new SubagentTool(registry, "spawn", "subagent", CODEC);
        ToolExecutionResult result = tool.execute(Map.of(), runContext()).join();

        assertTrue(result.isError());
        ToolExecutionResult.Failure failure = assertInstanceOf(ToolExecutionResult.Failure.class, result);
        assertEquals("MISSING_PROMPT", failure.error().info().code());
    }

    @Test
    void nonMapArgsFailWithBadArgsCode() {
        SubagentRegistry registry = new SubagentRegistry();
        registry.register(new RecordingProvider("spawn",
                new SubagentProvider.SubagentCapabilities(false, false, true)));

        SubagentTool tool = new SubagentTool(registry, "spawn", "subagent", CODEC);
        ToolExecutionResult result = tool.execute("not-a-map", runContext()).join();

        assertTrue(result.isError());
        ToolExecutionResult.Failure failure = assertInstanceOf(ToolExecutionResult.Failure.class, result);
        assertEquals("INVALID_ARGS", failure.error().info().code());
    }

    @Test
    void errorStopReasonMapsToToolFailure() {
        SubagentRegistry registry = new SubagentRegistry();
        RecordingProvider provider = new RecordingProvider("spawn",
                new SubagentProvider.SubagentCapabilities(false, false, true));
        provider.nextResult = new SubagentProvider.SubagentResult("error", List.of(), "run-9");
        registry.register(provider);

        SubagentTool tool = new SubagentTool(registry, "spawn", "subagent", CODEC);
        ToolExecutionResult result = tool.execute(Map.of("prompt", "t"), runContext()).join();

        assertTrue(result.isError());
        ToolExecutionResult.Failure failure = assertInstanceOf(ToolExecutionResult.Failure.class, result);
        assertEquals("SUBAGENT_ERROR", failure.error().info().code());
    }

    @Test
    void abortedStopReasonMapsToToolFailure() {
        SubagentRegistry registry = new SubagentRegistry();
        RecordingProvider provider = new RecordingProvider("spawn",
                new SubagentProvider.SubagentCapabilities(false, false, true));
        provider.nextResult = new SubagentProvider.SubagentResult("aborted", List.of(), "run-9");
        registry.register(provider);

        SubagentTool tool = new SubagentTool(registry, "spawn", "subagent", CODEC);
        ToolExecutionResult result = tool.execute(Map.of("prompt", "t"), runContext()).join();

        assertTrue(result.isError());
        ToolExecutionResult.Failure failure = assertInstanceOf(ToolExecutionResult.Failure.class, result);
        assertEquals("SUBAGENT_ABORTED", failure.error().info().code());
    }

    @Test
    void emptyOutputIsReplacedWithStopReasonNotice() {
        SubagentRegistry registry = new SubagentRegistry();
        RecordingProvider provider = new RecordingProvider("spawn",
                new SubagentProvider.SubagentCapabilities(false, false, true));
        provider.nextResult = new SubagentProvider.SubagentResult("max-tokens", List.of(), "run-2");
        registry.register(provider);

        SubagentTool tool = new SubagentTool(registry, "spawn", "subagent", CODEC);
        ToolExecutionResult result = tool.execute(Map.of("prompt", "t"), runContext()).join();

        // max-tokens 不属于 error/aborted，走成功路径但内容为提示文本。
        assertFalse(result.isError());
        ToolExecutionResult.Success success = assertInstanceOf(ToolExecutionResult.Success.class, result);
        String text = ((TextBlock) success.content().get(0)).text();
        assertTrue(text.contains("max-tokens"));
    }

    @Test
    void activationFailureIsWrappedAsSubagentError() {
        SubagentRegistry registry = new SubagentRegistry();
        RecordingProvider provider = new RecordingProvider("spawn",
                new SubagentProvider.SubagentCapabilities(false, false, true));
        provider.nextFailure = new IllegalStateException("backend unavailable");
        registry.register(provider);

        SubagentTool tool = new SubagentTool(registry, "spawn", "subagent", CODEC);
        ToolExecutionResult result = tool.execute(Map.of("prompt", "t"), runContext()).join();

        assertTrue(result.isError());
        ToolExecutionResult.Failure failure = assertInstanceOf(ToolExecutionResult.Failure.class, result);
        assertEquals("SUBAGENT_ERROR", failure.error().info().code());
        assertTrue(failure.error().message().contains("backend unavailable"));
    }

    @Test
    void unknownProviderActivationFailsGracefully() {
        SubagentRegistry registry = new SubagentRegistry();
        SubagentTool tool = new SubagentTool(registry, "spawn", "subagent", CODEC);

        ToolExecutionResult result = tool.execute(Map.of("prompt", "t"), runContext()).join();

        assertTrue(result.isError());
        ToolExecutionResult.Failure failure = assertInstanceOf(ToolExecutionResult.Failure.class, result);
        assertEquals("SUBAGENT_ERROR", failure.error().info().code());
    }
}
