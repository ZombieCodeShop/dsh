package cn.xiaofuge.deepseek.harness.domain.agent.service.subagent;

import cn.xiaofuge.deepseek.harness.domain.agent.contract.inbound.AgentRunLifecycle;
import cn.xiaofuge.deepseek.harness.domain.agent.model.entity.AgentOptions;
import cn.xiaofuge.deepseek.harness.domain.agent.service.run.AgentRun;
import cn.xiaofuge.deepseek.harness.domain.session.event.model.entity.SessionEvent;
import cn.xiaofuge.deepseek.harness.domain.session.event.model.entity.SessionLog;

import java.util.List;
import java.util.function.Supplier;
/**
 * ForkInProcessProvider 表示进程内复制子智能体的后端。
 *
 * <p>对应 TS 侧 base fork 委派默认 continuable：子级继承父级当前工作
 * 目录与模型选项；若提供父级会话（通过 {@link #withParentSession}），
 * fork 会以父级已完成轮次前缀作为子会话的起始事实基础。</p>
 *
 * @author 小傅哥
 * @website bugstack.cn
 */
public class ForkInProcessProvider extends AbstractInProcessSubagentProvider {

    /**
     * 初始化进程内 Fork 子智能体提供者。
     * <p>流程：继承当前 Agent 上下文 → 派生独立会话 → 回传执行结果。</p>
     */
    public ForkInProcessProvider(
            String providerName,
            SubagentRegistry registry,
            AgentRunLifecycle agentFactory,
            Supplier<AgentOptions> defaultOptionsSupplier
    ) {
        super(providerName, registry, agentFactory, defaultOptionsSupplier);
    }

    @Override
    protected AgentRun prepareChild(SubagentActivationSpec spec, AgentOptions childOptions) {
        // fork 语义：子级继承父级当前工作目录（resolvedCwd 缺省即父级目录）
        // 与模型选项基线，拥有独立会话；父级已完成轮次前缀可通过
        // completedTurnPrefix 提取，供调用方做种子回放。
        return agentFactory.create(spec.childId(), childOptions, spec.resolvedCwd());
    }

    /**
     * 处理completedTurnPrefix轮次。
     * <p>流程：校验入参 → 执行领域动作 → 返回处理结果。</p>
     * @param parentSession parentSession会话。
     * @return 领域处理结果。
     */
    public static List<SessionEvent> completedTurnPrefix(SessionLog parentSession) {
        List<SessionEvent> events = parentSession.events();
        int lastEndIdx = -1;
        for (int i = events.size() - 1; i >= 0; i--) {
            if (events.get(i) instanceof SessionEvent.TurnEnd) {
                lastEndIdx = i;
                break;
            }
        }
        if (lastEndIdx < 0) return List.of();
        return List.copyOf(events.subList(0, lastEndIdx + 1));
    }
}
