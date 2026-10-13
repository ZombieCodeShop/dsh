package cn.xiaofuge.deepseek.harness.domain.agent.service.subagent;

import cn.xiaofuge.deepseek.harness.domain.agent.model.entity.AgentOptions;
import cn.xiaofuge.deepseek.harness.domain.model.valobj.ContentBlock;
import cn.xiaofuge.deepseek.harness.domain.model.valobj.ToolSchema;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * SubagentProvider 表示创建子智能体的领域端口。
 *
 * <p>对应 TS 侧统一 activation API：所有后端（进程内与进程外）都实现
 * {@link #startActivation(SubagentActivationSpec)}；旧的
 * {@link #start(SubagentStartRequest)} 保留为一次性 caller 交付的便捷入口，
 * 由默认方法委托到 activation 路径。</p>
 */
public interface SubagentProvider {

    /**
     * 处理名称。
     * @return 领域处理结果。
     */
    String name();

    /**
     * 启动一次受管激活：能力校验已由注册表在准入阶段完成，
     * 实现方按 spec 执行并在完成结算后交付结果。
     *
     * @param spec 激活描述（含已解析的子级工作目录与交付策略）。
     * @return 受管激活句柄。
     */
    CompletableFuture<SubagentActivation> startActivation(SubagentActivationSpec spec);

    /**
     * 一次性启动（caller 交付）：等价于以 delivery=caller 发起激活并等待结果。
     * 兼容旧调用方；新代码应优先使用 {@link #startActivation(SubagentActivationSpec)}。
     *
     * @param request 请求。
     * @return 领域处理结果。
     */
    default CompletableFuture<SubagentResult> start(SubagentStartRequest request) {
        return startActivation(new SubagentActivationSpec(
                name(),
                null,
                null,
                request,
                request.parentCwd(),
                SubagentActivationSpec.DELIVERY_CALLER,
                false
        )).thenCompose(SubagentActivation::result);
    }

    /**
     * 后端启动期能力标记。注册表在准入阶段校验请求选项，
     * 依赖后端不具备的能力会被显式拒绝（fail loud，绝不静默降级）。
     */
    SubagentCapabilities capabilities();

    // ── Types ───────────────────────────────────────────────────

    /**
     * SubagentStartRequest 定义子代理启动请求。
     *
     * <p>目录语义：{@code parentCwd} 是父级当前有效目录；
     * {@code requestCwd} 是调用方显式指定的子级初始目录（相对路径按
     * parentCwd 解析）。准入阶段由注册表解析出 spec.resolvedCwd。</p>
     */
    record SubagentStartRequest(
            String prompt,
            AgentOptions options,
            String parentSessionId,
            String parentCwd,
            String requestCwd,
            int delegationDepth,
            List<ToolSchema> enabledTools,
            List<String> allowedToolNames
    ) {}

    /**
     * SubagentCapabilities 定义后端启动期能力标记。
     */
    record SubagentCapabilities(
            boolean continuable,
            boolean structuredOutput,
            boolean localAgent
    ) {}

    /**
     * SubagentResult 定义子代理执行结果。
     */
    record SubagentResult(
            String stopReason,      // "completed" | "aborted" | "error" | "max-tokens"
            List<ContentBlock> output,
            String runId
    ) {}
}
