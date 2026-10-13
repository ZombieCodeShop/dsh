package cn.xiaofuge.deepseek.harness.domain.agent.service.subagent;

import java.util.concurrent.CompletableFuture;

/**
 * SubagentActivation 表示一次受管子代理执行。
 *
 * <p>对应 TS 侧 {@code SubagentActivation}：dispose 只处置这一次激活，
 * 绝不影响后续 resume；拆解失败通过 dispose 的异常 CompletableFuture
 * 上报，不覆盖已捕获的执行结果。</p>
 *
 * @author 小傅哥
 * @website bugstack.cn
 */
public interface SubagentActivation {

    /** 子级稳定标识（本地子会话 id 或外部后端分配 id）。 */
    String childId();

    /** 已接受的任务短标签。 */
    String label();

    /** 使用的后端提供方名称。 */
    String provider();

    /** 交付策略："parent" | "caller"。 */
    String delivery();

    /** 是否可继续（结果交付后子会话保留，可恢复执行）。 */
    boolean continuable();

    /**
     * 执行结果：拆解结算、通知发送完成后完成；捕获失败以异常完成。
     */
    CompletableFuture<SubagentProvider.SubagentResult> result();

    /**
     * 停止并释放本次激活及其派生后代；拆解失败时以异常完成。
     */
    CompletableFuture<Void> dispose();
}
