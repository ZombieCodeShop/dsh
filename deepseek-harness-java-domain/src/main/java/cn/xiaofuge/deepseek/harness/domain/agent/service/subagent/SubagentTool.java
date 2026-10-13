package cn.xiaofuge.deepseek.harness.domain.agent.service.subagent;

import cn.xiaofuge.deepseek.harness.domain.agent.model.entity.AgentOptions;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolDefinition;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext;
import cn.xiaofuge.deepseek.harness.domain.model.valobj.ContentBlock;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * SubagentTool 表示子智能体调用的领域工具定义。
 *
 * <p>对应 TS 侧 dsh-tool-subagent（parent 交付路径）：工具以
 * delivery=parent 发起统一激活——结果作为本工具结果回传父代理模型；
 * 子级继承父级会话身份、当前有效工作目录与委派深度，谱系事实进入
 * 子代理目录。可选 {@code cwd} 参数允许模型显式指定子级目录（相对
 * 路径按父级当前目录解析，由注册表准入阶段完成）。</p>
 *
 * @author 小傅哥
 * @website bugstack.cn
 */
public class SubagentTool implements ToolDefinition {

    private static final String DEFAULT_TOOL_NAME = "subagent";
    private static final String DEFAULT_PROVIDER = "spawn-in-process";

    private final String toolName;
    private final SubagentRegistry registry;
    private final String providerName;
    private final Supplier<AgentOptions> parentOptionsSupplier;
    private final Supplier<String> parentSessionIdSupplier;
    private final Supplier<String> parentCwdSupplier;
    private final int delegationDepth;

    /**
     * 初始化子智能体工具（默认 provider，无父级上下文）。
     */
    public SubagentTool(SubagentRegistry registry, String providerName) {
        this(registry, providerName, DEFAULT_TOOL_NAME);
    }

    /**
     * 初始化指定提供者名称的子智能体工具。
     */
    public SubagentTool(SubagentRegistry registry, String providerName, String toolName) {
        this(registry, providerName, toolName, (Supplier<AgentOptions>) null);
    }

    /**
     * 初始化从父 Agent 动态读取模型渠道的子智能体工具。
     */
    public SubagentTool(
            SubagentRegistry registry,
            String providerName,
            String toolName,
            Supplier<AgentOptions> parentOptionsSupplier
    ) {
        this(registry, providerName, toolName, parentOptionsSupplier, null, null, 0);
    }

    /**
     * 初始化携带完整父级上下文的子智能体工具。
     *
     * @param registry 子代理注册表（激活管理器）。
     * @param providerName 后端名称。
     * @param toolName 工具名。
     * @param parentOptionsSupplier 父级模型选项（缺省渠道继承）。
     * @param parentSessionIdSupplier 父级会话标识（谱系事实）。
     * @param parentCwdSupplier 父级当前有效目录（子级目录继承基线）。
     * @param delegationDepth 当前父级委派深度。
     */
    public SubagentTool(
            SubagentRegistry registry,
            String providerName,
            String toolName,
            Supplier<AgentOptions> parentOptionsSupplier,
            Supplier<String> parentSessionIdSupplier,
            Supplier<String> parentCwdSupplier,
            int delegationDepth
    ) {
        this.registry = registry;
        this.providerName = providerName == null || providerName.isBlank() ? DEFAULT_PROVIDER : providerName;
        this.toolName = toolName;
        this.parentOptionsSupplier = parentOptionsSupplier;
        this.parentSessionIdSupplier = parentSessionIdSupplier;
        this.parentCwdSupplier = parentCwdSupplier;
        this.delegationDepth = delegationDepth;
    }

    @Override
    public String name() {
        return toolName;
    }

    @Override
    public String description() {
        return """
                Delegate a task to a subagent (child agent). The subagent runs in its own \
                session with its own context and tools, inheriting the current working \
                directory and lineage. Use this for complex subtasks that benefit from \
                focused multi-step reasoning. The subagent's final answer is returned as \
                this tool's result.""";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> promptProp = new LinkedHashMap<>();
        promptProp.put("type", "string");
        promptProp.put("description", "The task description / prompt for the subagent.");
        properties.put("prompt", promptProp);

        Map<String, Object> cwdProp = new LinkedHashMap<>();
        cwdProp.put("type", "string");
        cwdProp.put("description",
                "Optional initial working directory for the subagent. Relative paths resolve "
                        + "against the current directory. Omitted to inherit the current directory.");
        properties.put("cwd", cwdProp);

        Map<String, Object> bgProp = new LinkedHashMap<>();
        bgProp.put("type", "boolean");
        bgProp.put("description", "If true, run the subagent in the background (default: false).");
        properties.put("run_in_background", bgProp);

        props.put("properties", properties);
        props.put("required", List.of("prompt"));

        return props;
    }

    /**
     * 执行工具调用：以 delivery=parent 发起统一激活并等待结果。
     */
    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<ToolExecutionResult> execute(Object args, ToolRunContext exec) {
        Map<String, Object> argMap;
        if (args instanceof Map) {
            argMap = (Map<String, Object>) args;
        } else {
            return CompletableFuture.completedFuture(
                    ToolExecutionResult.fail("Invalid args type", "BAD_ARGS")
            );
        }

        String prompt = argMap.get("prompt") instanceof String s ? s : "";
        if (prompt.isEmpty()) {
            return CompletableFuture.completedFuture(
                    ToolExecutionResult.fail("prompt is required", "MISSING_PROMPT")
            );
        }
        String requestCwd = argMap.get("cwd") instanceof String cwd && !cwd.isBlank() ? cwd.trim() : null;

        SubagentProvider.SubagentStartRequest request = new SubagentProvider.SubagentStartRequest(
                prompt,
                parentOptionsSupplier == null ? null : parentOptionsSupplier.get(),
                parentSessionIdSupplier == null ? null : parentSessionIdSupplier.get(),
                parentCwdSupplier == null ? null : parentCwdSupplier.get(),
                requestCwd,
                delegationDepth,
                List.of(),      // enabledTools (all)
                List.of()       // allowedToolNames (all)
        );

        SubagentActivationSpec spec = new SubagentActivationSpec(
                providerName,
                null,
                null,
                request,
                null,           // resolvedCwd 由注册表准入阶段解析
                SubagentActivationSpec.DELIVERY_PARENT,
                false
        );

        return registry.startActivation(spec)
                .thenCompose(SubagentActivation::result)
                .<ToolExecutionResult>thenApply(result -> {
                    List<ContentBlock> content;
                    if (result.output() != null && !result.output().isEmpty()) {
                        content = result.output();
                    } else {
                        content = List.of(new cn.xiaofuge.deepseek.harness.domain.model.valobj.TextBlock(
                                "[subagent ended with reason: " + result.stopReason() + " and no output]"));
                    }

                    if ("error".equals(result.stopReason()) || "aborted".equals(result.stopReason())) {
                        return ToolExecutionResult.fail(
                                "Subagent ended with reason: " + result.stopReason(),
                                "SUBAGENT_" + result.stopReason().toUpperCase()
                        );
                    }

                    return ToolExecutionResult.ok(content);
                }).exceptionally(e -> ToolExecutionResult.fail(
                        extractMessage(e),
                        "SUBAGENT_ERROR"
                ));
    }

    private static String extractMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return "Subagent execution failed: " + cause.getMessage();
    }

    @Override
    public boolean isConcurrencySafe(Object args) {
        // Subagent runs are exclusive — they create child agents
        return false;
    }

    @Override
    public long timeoutMs() {
        return 300_000; // 5 minutes default
    }
}
