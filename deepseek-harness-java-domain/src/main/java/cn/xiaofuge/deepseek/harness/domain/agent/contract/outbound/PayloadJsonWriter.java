package cn.xiaofuge.deepseek.harness.domain.agent.contract.outbound;

/**
 * 把领域载荷序列化成 JSON 文本，供工具结果回填给模型阅读。
 * <p>
 * 存在这个端口的原因是一条架构约束：{@code domain.agent} 不允许依赖 Jackson
 * （由 AgentRunArchitectureTest#agentDomainMustNotDependOnJackson 强制），
 * 而工具结果最终必须是文本内容块，序列化能力只能由基础设施注入。
 */
public interface PayloadJsonWriter {

    /**
     * @param payload 待序列化的领域对象，永不为 null
     * @return JSON 文本
     * @throws RuntimeException 序列化失败时抛出，调用方按工具执行失败收口，不得让异常打断回合
     */
    String write(Object payload);
}
