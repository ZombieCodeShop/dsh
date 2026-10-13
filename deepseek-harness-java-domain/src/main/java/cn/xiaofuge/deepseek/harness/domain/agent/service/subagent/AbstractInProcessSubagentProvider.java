package cn.xiaofuge.deepseek.harness.domain.agent.service.subagent;

import cn.xiaofuge.deepseek.harness.domain.agent.contract.inbound.AgentRunLifecycle;
import cn.xiaofuge.deepseek.harness.domain.agent.model.entity.AgentOptions;
import cn.xiaofuge.deepseek.harness.domain.agent.model.valobj.AgentCancelCause;
import cn.xiaofuge.deepseek.harness.domain.agent.model.valobj.InboxTarget;
import cn.xiaofuge.deepseek.harness.domain.agent.service.run.AgentRun;
import cn.xiaofuge.deepseek.harness.domain.model.entity.Message;
import cn.xiaofuge.deepseek.harness.domain.model.entity.MessageSource;
import cn.xiaofuge.deepseek.harness.domain.model.valobj.ContentBlock;
import cn.xiaofuge.deepseek.harness.domain.model.valobj.TextBlock;
import cn.xiaofuge.deepseek.harness.domain.session.event.model.entity.SessionEvent;
import cn.xiaofuge.deepseek.harness.domain.session.event.model.valobj.TurnEndReason;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * AbstractInProcessSubagentProvider 表示进程内子代理后端的公共骨架。
 *
 * <p>对应 TS 侧统一 activation API 中本地后端的公共路径：按 spec 创建
 * （或预留恢复）子代理 → 交付提示词 → 等待排空 → 捕获输出与停止原因 →
 * 按 continuable 决定保留或释放子代理。子类只需声明名称与能力，
 * 并可通过 {@link #prepareChild} 定制子代理创建方式（spawn 新会话 /
 * fork 继承上下文）。</p>
 *
 * <p>拆解语义：dispose 只作用于本次激活的子代理，绝不影响后续 resume；
 * 已自然结束且不可继续的激活，dispose 是幂等 no-op。</p>
 *
 * @author 小傅哥
 * @website bugstack.cn
 */
public abstract class AbstractInProcessSubagentProvider implements SubagentProvider {

    protected final String providerName;
    protected final SubagentRegistry registry;
    protected final AgentRunLifecycle agentFactory;
    protected final Supplier<AgentOptions> defaultOptionsSupplier;

    /**
     * 初始化进程内子代理提供者。
     * @param providerName 后端名称。
     * @param registry 注册表。
     * @param agentFactory 子代理创建入口。
     * @param defaultOptionsSupplier 默认模型选项（request.options 缺省时使用）。
     */
    protected AbstractInProcessSubagentProvider(
            String providerName,
            SubagentRegistry registry,
            AgentRunLifecycle agentFactory,
            Supplier<AgentOptions> defaultOptionsSupplier
    ) {
        this.providerName = Objects.requireNonNull(providerName, "providerName");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.agentFactory = Objects.requireNonNull(agentFactory, "agentFactory");
        this.defaultOptionsSupplier = Objects.requireNonNull(defaultOptionsSupplier, "defaultOptionsSupplier");
    }

    @Override
    public String name() {
        return providerName;
    }

    @Override
    public SubagentCapabilities capabilities() {
        return new SubagentCapabilities(true, true, true);
    }

    @Override
    public CompletableFuture<SubagentActivation> startActivation(SubagentActivationSpec spec) {
        return CompletableFuture.supplyAsync(() -> {
            AgentOptions childOptions = spec.request().options() != null
                    ? spec.request().options()
                    : Objects.requireNonNull(defaultOptionsSupplier.get(), "defaultOptionsSupplier returned null");

            AgentRun childAgent = prepareChild(spec, childOptions);

            AtomicBoolean settled = new AtomicBoolean(false);
            CompletableFuture<SubagentResult> resultFuture = runChild(childAgent, spec)
                    .whenComplete((r, e) -> {
                        if (!spec.continuable()) {
                            disposeQuietly(childAgent);
                        }
                        settled.set(true);
                    });

            return new InProcessActivation(spec, childAgent, resultFuture, settled);
        });
    }

    /**
     * 创建子代理运行实例。spawn 后端创建全新会话；fork 后端可继承父级上下文。
     * @param spec 已准入的激活描述（childId 与 resolvedCwd 已分配）。
     * @param childOptions 子代理选项。
     * @return 子代理运行实例。
     */
    protected abstract AgentRun prepareChild(SubagentActivationSpec spec, AgentOptions childOptions);

    private CompletableFuture<SubagentResult> runChild(AgentRun childAgent, SubagentActivationSpec spec) {
        List<ContentBlock> promptBlocks = List.of(new TextBlock(
                spec.request().prompt() != null && !spec.request().prompt().isEmpty()
                        ? spec.request().prompt()
                        : "(empty task)"));
        Message userMessage = Message.createUser(promptBlocks, new MessageSource.UserSource());
        childAgent.send(userMessage, InboxTarget.NEXT_TURN, true);

        return childAgent.whenIdle().thenApply(v -> {
            List<ContentBlock> output = collectLastAssistantOutput(childAgent);
            String stopReason = determineStopReason(childAgent);
            return new SubagentResult(stopReason, output, registry.nextRunId());
        });
    }

    private void disposeQuietly(AgentRun childAgent) {
        try {
            childAgent.cancel(new AgentCancelCause.ParentCancel("activation disposed"), false);
        } catch (Exception ignored) {
            // 已空闲的子代理无需取消
        }
        try {
            childAgent.dispose();
        } catch (Exception ignored) {
            // dispose 失败不覆盖已捕获结果
        }
    }

    /**
     * 收集子会话最后一条助手文本输出。
     */
    protected List<ContentBlock> collectLastAssistantOutput(AgentRun agent) {
        var events = agent.session().events();
        TextBlock lastText = null;
        for (var event : events) {
            if (event instanceof SessionEvent.AssistantMessage am) {
                var msg = am.data().message();
                if (msg != null && msg.content() != null) {
                    for (var block : msg.content()) {
                        if (block instanceof TextBlock tb) {
                            lastText = tb;
                        }
                    }
                }
            }
        }
        return lastText != null ? List.of(lastText) : List.of();
    }

    /**
     * 从最后一个轮次结束事件推导停止原因。
     */
    protected String determineStopReason(AgentRun agent) {
        var events = agent.session().events();
        TurnEndReason lastReason = null;
        for (var event : events) {
            if (event instanceof SessionEvent.TurnEnd te) {
                lastReason = te.reason();
            }
        }
        return mapStopReason(lastReason);
    }

    static String mapStopReason(TurnEndReason reason) {
        if (reason == null) return "error";
        if (reason instanceof TurnEndReason.Completed) return "completed";
        if (reason instanceof TurnEndReason.MaxTokens) return "max-tokens";
        if (reason instanceof TurnEndReason.Aborted) return "aborted";
        if (reason instanceof TurnEndReason.Blocked) return "refusal";
        return "error"; // error, interrupted
    }

    // ── Activation 句柄 ─────────────────────────────────────────

    private final class InProcessActivation implements SubagentActivation {

        private final SubagentActivationSpec spec;
        private final AgentRun childAgent;
        private final CompletableFuture<SubagentResult> result;
        private final AtomicBoolean settled;

        private InProcessActivation(
                SubagentActivationSpec spec,
                AgentRun childAgent,
                CompletableFuture<SubagentResult> result,
                AtomicBoolean settled
        ) {
            this.spec = spec;
            this.childAgent = childAgent;
            this.result = result;
            this.settled = settled;
        }

        @Override
        public String childId() {
            return spec.childId();
        }

        @Override
        public String label() {
            return spec.label();
        }

        @Override
        public String provider() {
            return providerName;
        }

        @Override
        public String delivery() {
            return spec.delivery();
        }

        @Override
        public boolean continuable() {
            return spec.continuable();
        }

        @Override
        public CompletableFuture<SubagentResult> result() {
            return result;
        }

        @Override
        public CompletableFuture<Void> dispose() {
            if (settled.get()) {
                // 不可继续的激活已在结果结算时释放；可继续的激活在
                // 结果交付后由所有者显式 dispose；重复 dispose 幂等。
                if (!spec.continuable()) {
                    return CompletableFuture.completedFuture(null);
                }
            }
            return CompletableFuture.runAsync(() -> {
                try {
                    childAgent.cancel(new AgentCancelCause.ParentCancel("activation disposed"), false);
                } catch (Exception ignored) {
                    // 忽略取消异常
                }
                childAgent.dispose();
            });
        }
    }
}
