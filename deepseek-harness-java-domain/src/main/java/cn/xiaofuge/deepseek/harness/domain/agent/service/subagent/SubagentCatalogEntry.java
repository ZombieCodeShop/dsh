package cn.xiaofuge.deepseek.harness.domain.agent.service.subagent;

/**
 * SubagentCatalogEntry 表示子代理目录中的一个创建事实条目。
 *
 * <p>对应 TS 侧 subagentCatalog projection：按父会话记录子级成员关系。
 * 历史子会话 descriptor 不可用时以 {@code mode=unknown} 保留——可发现，
 * 但不授予继续执行能力。</p>
 *
 * @author 小傅哥
 * @website bugstack.cn
 */
public record SubagentCatalogEntry(
        /** 子级标识。 */
        String childId,
        /** 父会话标识；top-level 激活为 null。 */
        String parentSessionId,
        /** 任务短标签。 */
        String label,
        /** 使用的后端提供方名称。 */
        String provider,
        /** 目录模式："local" | "external" | "unknown"。 */
        String mode,
        /** 交付策略："parent" | "caller"。 */
        String delivery,
        /** 委派深度：父级深度 + 1。 */
        int delegationDepth,
        /** 创建时间（epoch 毫秒）。 */
        long createdAtMillis
) {

    /** 本地（进程内）子会话。 */
    public static final String MODE_LOCAL = "local";
    /** 外部后端子会话（独立进程）。 */
    public static final String MODE_EXTERNAL = "external";
    /** 历史条目：descriptor 不可用，仅保留可发现性。 */
    public static final String MODE_UNKNOWN = "unknown";
}
