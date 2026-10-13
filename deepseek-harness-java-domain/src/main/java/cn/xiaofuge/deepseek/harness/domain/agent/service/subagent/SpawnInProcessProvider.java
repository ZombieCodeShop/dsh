package cn.xiaofuge.deepseek.harness.domain.agent.service.subagent;

import cn.xiaofuge.deepseek.harness.domain.agent.contract.inbound.AgentRunLifecycle;
import cn.xiaofuge.deepseek.harness.domain.agent.model.entity.AgentOptions;
import cn.xiaofuge.deepseek.harness.domain.agent.service.run.AgentRun;
import java.util.function.Supplier;

/**
 * SpawnInProcessProvider 表示进程内创建全新会话子智能体的后端。
 *
 * <p>对应 TS 侧 {@code subagent-spawn-in-process}：子级拥有全新会话，
 * 继承父级的当前工作目录与谱系（parentSessionId / delegationDepth 由
 * spec 携带），不共享父级对话上下文。</p>
 *
 * <p>调用流程：统一 activation 准入 → 创建全新子代理 → 交付任务 →
 * 排空等待 → 捕获结果 → 按 continuable 保留或释放。</p>
 *
 * @author 小傅哥
 * @website bugstack.cn
 */
public class SpawnInProcessProvider extends AbstractInProcessSubagentProvider {

    /**
     * 初始化进程内新会话子智能体提供者。
     * <p>流程：接收任务提示 → 创建全新 Agent 会话 → 执行并回传结果。</p>
     */
    public SpawnInProcessProvider(
            String providerName,
            SubagentRegistry registry,
            AgentRunLifecycle agentFactory,
            Supplier<AgentOptions> defaultOptionsSupplier
    ) {
        super(providerName, registry, agentFactory, defaultOptionsSupplier);
    }

    @Override
    protected AgentRun prepareChild(SubagentActivationSpec spec, AgentOptions childOptions) {
        // resolvedCwd 已在注册表准入阶段解析（request.cwd 相对父级或继承父级）
        return agentFactory.create(spec.childId(), childOptions, spec.resolvedCwd());
    }
}
