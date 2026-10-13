package cn.xiaofuge.deepseek.harness.domain.agent.service.subagent;

/**
 * SubagentActivationSpec 表示一次子代理激活的完整描述。
 *
 * <p>对应 TS 侧 {@code SubagentActivationSpec}：所有后端（进程内与进程外）
 * 都通过统一 activation 请求启动，由注册表在准入阶段完成能力校验、
 * 工作目录解析与 childId 分配，Provider 只负责执行。</p>
 *
 * <p>交付策略（delivery）：parent 表示结果回传给父代理模型（SubagentTool
 * 路径）；caller 表示结果仅返回给调用方（workflow / code-mode 直接 await）。</p>
 *
 * @author 小傅哥
 * @website bugstack.cn
 */
public record SubagentActivationSpec(
        /** 使用的后端提供方名称。 */
        String provider,
        /** 任务短标签，保留在目录条目中。 */
        String label,
        /** 预留的子会话标识；null 时由注册表分配。 */
        String childId,
        /** 任务与可选能力请求。 */
        SubagentProvider.SubagentStartRequest request,
        /** 已解析的子级工作目录（准入阶段按 requestCwd 相对父级目录解析）。 */
        String resolvedCwd,
        /** 交付策略："parent" | "caller"。 */
        String delivery,
        /** 可继续：子会话在结果交付后保留，可再次恢复执行。 */
        boolean continuable
) {

    /** 交付策略：结果回传父代理模型。 */
    public static final String DELIVERY_PARENT = "parent";
    /** 交付策略：结果仅返回调用方。 */
    public static final String DELIVERY_CALLER = "caller";

    public SubagentActivationSpec {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider is required");
        }
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        if (delivery == null) {
            delivery = DELIVERY_CALLER;
        }
        if (!DELIVERY_PARENT.equals(delivery) && !DELIVERY_CALLER.equals(delivery)) {
            throw new IllegalArgumentException("delivery must be parent or caller: " + delivery);
        }
        if (label == null || label.isBlank()) {
            label = request.prompt() == null ? "subagent" : firstLine(request.prompt());
        }
    }

    private static String firstLine(String text) {
        String line = text.strip().split("\r?\n", 2)[0];
        return line.length() > 60 ? line.substring(0, 60) : line;
    }
}
