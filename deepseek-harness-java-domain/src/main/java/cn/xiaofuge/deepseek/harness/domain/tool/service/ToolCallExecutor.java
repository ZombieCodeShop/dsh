package cn.xiaofuge.deepseek.harness.domain.tool.service;

import cn.xiaofuge.deepseek.harness.domain.model.entity.*;
import cn.xiaofuge.deepseek.harness.domain.model.valobj.ContentBlock;
import cn.xiaofuge.deepseek.harness.domain.model.valobj.ToolCallBlock;
import cn.xiaofuge.deepseek.harness.domain.hooks.model.valobj.HookOutput;
import cn.xiaofuge.deepseek.harness.domain.session.event.model.entity.SessionLog;
import cn.xiaofuge.deepseek.harness.domain.session.event.service.SessionEventFactory;
import cn.xiaofuge.deepseek.harness.domain.tool.adapter.ToolRegistry;
import cn.xiaofuge.deepseek.harness.domain.tool.model.valobj.ToolErrorCodes;
import cn.xiaofuge.deepseek.harness.domain.model.valobj.ToolExecutionMode;
import cn.xiaofuge.deepseek.harness.domain.hooks.IHookService;
import cn.xiaofuge.deepseek.harness.domain.hooks.model.valobj.HookDialect;
import cn.xiaofuge.deepseek.harness.domain.hooks.model.valobj.HookPoint;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// Domain 层不引入外部日志，由调用方负责记录
// 注：为满足运行期可观测性需求，此处破例引入 slf4j 记录工具执行的关键步骤

/**
 * ToolCallExecutor 表示ToolCallExecutor。
 */
public final class ToolCallExecutor {

    @FunctionalInterface
    public interface ToolLifecycleListener {
        void onToolEvent(ToolLifecycleEvent event);
    }

    public record ToolLifecycleEvent(
            String phase,
            String callId,
            String toolName,
            String args,
            String result,
            String status,
            Long durationMs
    ) {}

    private static final Logger log = LoggerFactory.getLogger(ToolCallExecutor.class);
    // Domain 层不持有日志器

    /** 单个工具调用的默认执行超时（毫秒）：300s。超时后记为 TOOL_TIMEOUT 失败，不再无限阻塞回合。 */
    public static final long DEFAULT_TOOL_TIMEOUT_MS = 300_000L;

    /**
     * 工具执行工作线程池（daemon）：部分工具实现（如 shell_execute）会在调用线程上
     * 同步阻塞直到自身超时，若直接在驱动线程派发，任何后续超时等待都无从生效。
     * 超时启用时派发动作被投递到本池，驱动线程仅负责限时等待结果。
     */
    private static final ExecutorService TOOL_WORKER = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "tool-worker");
        t.setDaemon(true);
        return t;
    });

    private final ToolRegistry registry;
    private final SessionLog session;
    private final int maxParallel;
    private final ToolArgumentsParser argumentsParser;
    /** 单个工具调用最大执行时长（毫秒），0 表示不设超时。 */
    private final long toolTimeoutMs;
    private volatile IHookService hookService;
    private volatile RuntimeApprovalGate approvalGate = RuntimeApprovalGate.allowAll();
    private volatile Consumer<ToolLifecycleEvent> lifecycleListener;
    // 本回合写文件类工具登记的产物（绝对路径），由响应收集节点按回合取走
    private final Set<String> turnArtifacts = ConcurrentHashMap.newKeySet();

    /**
     * @param registry    工具注册表
     * @param session     会话日志，用于持久化事件追加
     * @param maxParallel 每个步骤中可并行的最大在途调用数
     */
    public ToolCallExecutor(ToolRegistry registry, SessionLog session, int maxParallel) {
        this(registry, session, maxParallel, ToolArgumentsParser.identity(), DEFAULT_TOOL_TIMEOUT_MS);
    }

    public ToolCallExecutor(
            ToolRegistry registry,
            SessionLog session,
            int maxParallel,
            ToolArgumentsParser argumentsParser
    ) {
        this(registry, session, maxParallel, argumentsParser, DEFAULT_TOOL_TIMEOUT_MS);
    }

    /**
     * 创建带参数解析器和工具超时的完整工具执行器。
     * <p>toolTimeoutMs 为单个工具调用的最大执行时长（毫秒），<=0 表示不启用超时。
     */
    public ToolCallExecutor(
            ToolRegistry registry,
            SessionLog session,
            int maxParallel,
            ToolArgumentsParser argumentsParser,
            long toolTimeoutMs
    ) {
        this.registry = Objects.requireNonNull(registry);
        this.session = Objects.requireNonNull(session);
        this.argumentsParser = Objects.requireNonNull(argumentsParser);
        if (maxParallel < 1) throw new IllegalArgumentException("maxParallel must be >= 1");
        this.maxParallel = maxParallel;
        this.toolTimeoutMs = toolTimeoutMs;
    }

    /**
     * 注入 PRE/POST 工具 Hook 服务。
     * <p>PRE Hook 可阻断工具调用；POST Hook 只观察执行结果，不改变已产生的工具输出。
     */
    public void setHookService(IHookService hookService) {
        this.hookService = hookService;
    }

    /**
     * 注入运行期审批门禁。每次派发工具前都会调用 {@link RuntimeApprovalGate#check}，
     * 被 DENY 的调用记录合成失败结果（不执行工具），保持会话回放一致。
     */
    public void setApprovalGate(RuntimeApprovalGate approvalGate) {
        this.approvalGate = approvalGate == null ? RuntimeApprovalGate.allowAll() : approvalGate;
    }

    public void setLifecycleListener(Consumer<ToolLifecycleEvent> listener) {
        this.lifecycleListener = listener;
    }

    /**
     * 取走本回合累计的文件产物路径（绝对路径，去重）。
     * 由响应收集节点在回合结束后调用一次。
     */
    public List<String> drainArtifacts() {
        if (turnArtifacts.isEmpty()) return List.of();
        List<String> copy = new ArrayList<>(turnArtifacts);
        turnArtifacts.clear();
        return copy;
    }

    /**
     * 运行期审批判定结果：携带裁决详情（而非布尔），以区分 deny 与 cancel。
     */
    private record ApprovalOutcome(boolean denied, boolean cancelled) {}

    /**
     * 检查当前工具调用是否被运行期审批门禁拒绝。
     * <p>数据示例：shell_execute 未获得审批时返回 denied=true；fs_read 通常直接放行。
     */
    private ApprovalOutcome checkApproval(String toolName, Object args) {
        // 阻塞等待裁决前先发出显式挂起信号：远程协作层据此识别"审批等待"，
        // 而不是把长时间阻塞误判为普通工具执行（step_break 的 status=requires_approval）。
        if (approvalGate.requiresApproval(toolName)) {
            log.info("[Tool] 工具 {} 需运行期审批，已发出 requires_approval 信号，等待人工裁决...", toolName);
            notifyLifecycle(new ToolLifecycleEvent(
                    "call", null, toolName,
                    args == null ? null : String.valueOf(args),
                    null, "requires_approval", null));
        }
        RuntimeApprovalGate.Decision decision = approvalGate.check(toolName, args);
        return new ApprovalOutcome(decision == RuntimeApprovalGate.Decision.DENY, false);
    }

    /**
     * 执行 PRE_TOOL_USE Hook，判断是否阻断本次工具调用。
     * <p>Hook 服务未注入时直接放行；Hook 返回 BLOCK 或 DENY 都视为阻断。
     * <p>数据示例：PRE Hook 对 `rm -rf /` 返回 DENY，则本次调用不会进入真实工具实现。
     */
    private boolean shouldBlock(String toolName) {
        if (hookService == null) return false;
        var outcome = hookService.runHooks(
                HookDialect.CLAUDE_CODE, HookPoint.PRE_TOOL_USE, toolName,
                "{\"tool\":\"" + toolName + "\"}", Map.of());
        return outcome.decision()
                .map(d -> d == HookOutput.HookDecision.BLOCK
                       || d == HookOutput.HookDecision.DENY)
                .orElse(false);
    }

    /**
     * 在工具提交结果后触发 POST_TOOL_USE Hook。
     * <p>该阶段用于审计、告警和后续处理；不改变已写入会话日志的工具结果。
     */
    private void afterTool(String toolName) {
        if (hookService == null) return;
        hookService.runHooks(
                HookDialect.CLAUDE_CODE, HookPoint.POST_TOOL_USE, toolName,
                "{\"tool\":\"" + toolName + "\"}", Map.of());
    }

    /**
     * 按当前并发模式调度一个助手步骤中的工具调用。
     *
     * @param turn         current turn number
     * @param step         current step number
     * @param toolCalls    assistant calls in model order
     * @param abortSignal  shared abort flag
     * @param acceptContext accepts committed result context for the next step boundary
     * @return whether any committed result concluded the turn
     */
    public boolean execute(
            long turn,
            long step,
            List<ToolCallBlock> toolCalls,
            AtomicBoolean abortSignal,
            java.util.function.Consumer<Message> acceptContext
    ) {
        if (toolCalls.isEmpty()) return false;
        log.info("[Tool] Turn#{} Step#{} 调度 {} 个工具调用：{}",
                turn, step, toolCalls.size(),
                toolCalls.stream().map(ToolCallBlock::name).toList());

        // 规划调用：单个参数解析失败只让该调用失败，不打断整个回合
        List<PlannedCall> planned = new ArrayList<>();
        for (ToolCallBlock block : toolCalls) {
            try {
                planned.add(new PlannedCall(block, argumentsParser.parse(block.arguments()), null));
            } catch (RuntimeException e) {
                planned.add(new PlannedCall(block, Map.of(),
                        "Error: invalid tool arguments: " + e.getMessage()));
            }
        }

        int next = 0;
        boolean concluded = false;
        while (next < planned.size()) {
            PlannedCall first = planned.get(next);
            ToolExecutionMode mode = first.parseError() != null
                    ? ToolExecutionMode.EXCLUSIVE
                    : registry.executionMode(
                            ToolExecutionInput.root(first.block.id(), first.block.name(), first.execArgs));
            List<PlannedCall> group = mode == ToolExecutionMode.PARALLEL
                    ? planned.subList(next, planned.size())
                    : List.of(first);
            GroupOutcome outcome = runGroup(turn, step, group, mode, abortSignal, acceptContext);
            next += outcome.consumed;
            concluded |= outcome.concluded;
            if (outcome.aborted) {
                for (int i = next; i < planned.size(); i++) {
                    appendSkippedToolCall(turn, step, planned.get(i).block);
                }
                return concluded;
            }
        }
        return concluded;
    }

    // ── 分组执行器 ────────────────────────────────────────────────

    /**
     * 等待单个工具 Future 完成；超过 toolTimeoutMs 未完成时以 TOOL_TIMEOUT 失败收场。
     * <p>超时后 Future 里的工作线程仍在跑（无法安全强杀），但其结果会被丢弃，
     * 槽位记为合成失败结果，保证 tool_call/tool_result 成对落库，回合不再被挂死工具阻塞。
     */
    private ToolExecutionResult awaitWithTimeout(
            CompletableFuture<ToolExecutionResult> future,
            long turn, long step, String toolName
    ) {
        if (toolTimeoutMs <= 0) {
            return future.join();
        }
        try {
            return future.get(toolTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            log.warn("[Tool] Turn#{} Step#{} 工具 {} 执行超过 {}ms，判定超时（TOOL_TIMEOUT）",
                    turn, step, toolName, toolTimeoutMs);
            notifyLifecycle(new ToolLifecycleEvent(
                    "result", null, toolName, null, null, "timeout", toolTimeoutMs));
            return ToolExecutionResult.fail(
                    "Tool \"" + toolName + "\" timed out after " + toolTimeoutMs
                            + "ms and was terminated",
                    ToolErrorCodes.TOOL_TIMEOUT);
        } catch (java.util.concurrent.ExecutionException ee) {
            Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
            return ToolExecutionResult.fail(cause.getMessage(), "TOOL_ERROR");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return ToolExecutionResult.fail("tool wait interrupted", "TOOL_ERROR");
        }
    }

    /** 判断一个工具调用是否已因超时而失败（其后续结果将被丢弃）。 */
    private boolean isTimeoutFailure(Slot slot) {
        return slot != null && slot.result instanceof ToolExecutionResult.Failure f
                && f.error() != null && f.error().info() != null
                && ToolErrorCodes.TOOL_TIMEOUT.equals(f.error().info().code());
    }

    /**
     * 按并行/串行模式执行一组工具调用，并保持结果按调用顺序提交。
     * <p>执行流程：解析参数 -> 记录 tool_call 事件 -> 审批检查 -> PRE Hook -> 启动执行 ->
     * 等待完成 -> POST Hook -> 记录 tool_result 事件。取消时未派发的调用标记为 ABORTED。
     * <p>数据示例：模型调用 read_file(a.txt) 和 read_file(b.txt)，PARALLEL 模式下两者并发执行；
     * 结果仍按 a、b 的调用顺序回填，保证会话回放稳定。
     */
    private GroupOutcome runGroup(
            long turn,
            long step,
            List<PlannedCall> group,
            ToolExecutionMode mode,
            AtomicBoolean abortSignal,
            java.util.function.Consumer<Message> acceptContext
    ) {
        int groupSize = group.size();
        Slot[] slots = new Slot[groupSize];
        long[] callSeqs = new long[groupSize];
        Arrays.fill(callSeqs, -1);
        int nextToStart = 0;
        int committed = 0;
        int started = 0;
        boolean aborted = abortSignal.get();
        boolean concluded = false;
        boolean schedulerFailure = false;

        // 记录在途 Future
        Map<Integer, CompletableFuture<Integer>> inFlight = new ConcurrentHashMap<>();
        // 已定论槽位（超时判负后，底层 future 迟到的回调不得覆盖结果）
        Set<Integer> settled = ConcurrentHashMap.newKeySet();
        // 每个在途调用的超时截止时间（nanoTime），仅超时启用时记录
        Map<Integer, Long> deadlines = new ConcurrentHashMap<>();
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0, toolTimeoutMs));

        try {
            // 填充线程池
            while (!aborted && nextToStart < groupSize && inFlight.size() < maxParallel) {
                // 有序提交后重新读取后续调用模式
                if (nextToStart > 0 && mode == ToolExecutionMode.PARALLEL) {
                    PlannedCall nextCall = group.get(nextToStart);
                    if (nextCall.parseError() != null) break;
                    ToolExecutionMode recheck = registry.executionMode(
                            ToolExecutionInput.root(nextCall.block.id(), nextCall.block.name(), nextCall.execArgs));
                    if (recheck != ToolExecutionMode.PARALLEL) break;
                }

                int index = nextToStart;
                PlannedCall call = group.get(index);

                // 参数无法解析：不派发，但必须成对落库，把错误回给模型重试
                if (call.parseError() != null) {
                    log.warn("[Tool] Turn#{} Step#{} 工具 {} 参数解析失败，跳过执行",
                            turn, step, call.block.name());
                    callSeqs[index] = appendToolCall(turn, step, call.block);
                    started++;
                    slots[index] = new Slot(null,
                            ToolExecutionResult.fail(call.parseError(), ToolErrorCodes.INVALID_ARGS), false);
                    nextToStart++;
                    continue;
                }

                // PRE_TOOL_USE Hook：被拦截时跳过工具
                if (shouldBlock(call.block.name())) {
                    log.warn("[Tool] Turn#{} Step#{} 工具 {} 被 PreToolUse Hook 拦截，跳过执行",
                            turn, step, call.block.name());
                    callSeqs[index] = appendToolCall(turn, step, call.block);
                    started++;
                    slots[index] = new Slot(null, ToolExecutionResult.fail(
                            "Blocked by PreToolUse hook", "HOOK_BLOCKED"), false);
                    nextToStart++;
                    continue;
                }

                // 运行期审批门禁：被 DENY 时不执行工具，记录合成失败结果
                if (checkApproval(call.block.name(), call.execArgs).denied()) {
                    log.warn("[Tool] Turn#{} Step#{} 工具 {} 需审批但未获批准（DENY），跳过执行",
                            turn, step, call.block.name());
                    callSeqs[index] = appendToolCall(turn, step, call.block);
                    started++;
                    slots[index] = new Slot(null, ToolExecutionResult.fail(
                            "Tool \"" + call.block.name()
                                    + "\" requires approval and was not approved for this session",
                            "APPROVAL_REQUIRED"), false);
                    nextToStart++;
                    continue;
                }

                // 追加 tool/call 事件
                callSeqs[index] = appendToolCall(turn, step, call.block);
                started++;

                // 准备并派发调用
                log.debug("[Tool] Turn#{} Step#{} 派发工具 {} ...", turn, step, call.block.name());
                ToolExecution exec = ToolExecution.from(
                        ToolExecutionInput.root(call.block.id(), call.block.name(), call.execArgs));
                ToolRunContext ctx = new ToolRunContext(exec);

                try {
                    var defOpt = registry.lookup(call.block.name());
                    if (defOpt.isEmpty()) {
                        log.warn("[Tool] Turn#{} Step#{} 未知工具 {}，记录失败", turn, step, call.block.name());
                        slots[index] = new Slot(ctx, ToolExecutionResult.fail(
                                "unknown tool \"" + call.block.name() + "\"",
                                ToolErrorCodes.UNKNOWN_TOOL), false);
                    } else {
                        // 派发：超时启用时投递到工作线程池，避免同步阻塞型工具
                        // 在驱动线程上跑满自身超时、令本执行器的超时等待形同虚设
                        CompletableFuture<ToolExecutionResult> future = toolTimeoutMs > 0
                                ? CompletableFuture.supplyAsync(
                                        () -> defOpt.get().execute(call.execArgs, ctx).join(), TOOL_WORKER)
                                : defOpt.get().execute(call.execArgs, ctx);
                        int finalIndex = index;
                        CompletableFuture<Integer> tracked = future.handle((result, error) -> {
                            if (settled.add(finalIndex)) {
                                if (error != null) {
                                    slots[finalIndex] = new Slot(ctx,
                                            ToolExecutionResult.fail(error.getMessage(), "TOOL_ERROR"), false);
                                } else {
                                    afterTool(call.block.name());
                                    boolean needsPost = true;
                                    slots[finalIndex] = new Slot(ctx, result, needsPost);
                                }
                            }
                            return finalIndex;
                        });
                        inFlight.put(index, tracked);
                        tracked.whenComplete((ignored, throwable) -> inFlight.remove(finalIndex));
                        if (toolTimeoutMs > 0) {
                            deadlines.put(index, System.nanoTime() + timeoutNanos);
                        }
                        // 非并行模式下，等待该调用完成后再继续（带超时保护）
                        if (mode != ToolExecutionMode.PARALLEL) {
                            ToolExecutionResult awaited = awaitWithTimeout(
                                    future, turn, step, call.block.name());
                            if (isTimeoutFailure(new Slot(ctx, awaited, false))) {
                                // 超时：直接把失败结果写入槽位（settled 防止底层回调覆盖），
                                // 从等待队列移除，使回合不再被挂死工具阻塞
                                settled.add(finalIndex);
                                slots[finalIndex] = new Slot(ctx, awaited, false);
                                inFlight.remove(finalIndex);
                            }
                        }
                    }
                } catch (Exception e) {
                    slots[index] = new Slot(ctx,
                            ToolExecutionResult.fail(e.getMessage(), "TOOL_ERROR"), false);
                }

                nextToStart++;
                if (abortSignal.get()) aborted = true;

                // 按顺序提交已就绪槽位
                committed = commitReady(turn, step, group, slots, callSeqs, committed, acceptContext);
            }

            // 等待全部在途调用完成（带超时保护，避免挂死工具无限阻塞回合）
            while (!inFlight.isEmpty()) {
                CompletableFuture<Void> all = CompletableFuture.allOf(
                        inFlight.values().toArray(new CompletableFuture[0]));
                if (timeoutNanos <= 0) {
                    all.join();
                } else {
                    // 计算当前在途调用中最近的截止时间，等待到该时刻
                    long nearestDeadline = deadlines.values().stream()
                            .min(Long::compare)
                            .orElse(System.nanoTime() + timeoutNanos);
                    long waitNanos = nearestDeadline - System.nanoTime();
                    try {
                        if (waitNanos > 0) {
                            all.get(waitNanos, TimeUnit.NANOSECONDS);
                        }
                    } catch (java.util.concurrent.TimeoutException te) {
                        // 到期仍未完成的调用全部判定超时
                    } catch (java.util.concurrent.ExecutionException ee) {
                        // 单个调用异常已由各自回调处理，此处忽略聚合异常
                    }
                    // 判定所有已到期且未完成的调用为 TOOL_TIMEOUT
                    long now = System.nanoTime();
                    for (Map.Entry<Integer, Long> entry : deadlines.entrySet()) {
                        int idx = entry.getKey();
                        if (entry.getValue() <= now && inFlight.containsKey(idx)) {
                            String toolName = group.get(idx).block.name();
                            log.warn("[Tool] Turn#{} Step#{} 工具 {} 执行超过 {}ms，判定超时（TOOL_TIMEOUT）",
                                    turn, step, toolName, toolTimeoutMs);
                            notifyLifecycle(new ToolLifecycleEvent(
                                    "result", null, toolName, null, null, "timeout", toolTimeoutMs));
                            if (settled.add(idx)) {
                                slots[idx] = new Slot(null, ToolExecutionResult.fail(
                                        "Tool \"" + toolName + "\" timed out after " + toolTimeoutMs
                                                + "ms and was terminated",
                                        ToolErrorCodes.TOOL_TIMEOUT), false);
                            }
                            deadlines.remove(idx);
                            CompletableFuture<Integer> f = inFlight.remove(idx);
                            if (f != null) f.cancel(false);
                        }
                    }
                }
                committed = commitReady(turn, step, group, slots, callSeqs, committed, acceptContext);
                if (abortSignal.get()) aborted = true;

                // 尝试继续填充
                while (!aborted && nextToStart < groupSize && inFlight.size() < maxParallel) {
                    if (nextToStart > 0 && mode == ToolExecutionMode.PARALLEL) {
                        PlannedCall nextCall = group.get(nextToStart);
                        ToolExecutionMode recheck = registry.executionMode(
                                ToolExecutionInput.root(nextCall.block.id(), nextCall.block.name(), nextCall.execArgs));
                        if (recheck != ToolExecutionMode.PARALLEL) break;
                    }
                    int index = nextToStart;
                    PlannedCall call = group.get(index);
                    callSeqs[index] = appendToolCall(turn, step, call.block);
                    started++;
                    // 运行期审批门禁（补流路径）
                    if (checkApproval(call.block.name(), call.execArgs).denied()) {
                        slots[index] = new Slot(null, ToolExecutionResult.fail(
                                "Tool \"" + call.block.name()
                                        + "\" requires approval and was not approved for this session",
                                "APPROVAL_REQUIRED"), false);
                        nextToStart++;
                        committed = commitReady(turn, step, group, slots, callSeqs, committed, acceptContext);
                        continue;
                    }
                    ToolExecution exec = ToolExecution.from(
                            ToolExecutionInput.root(call.block.id(), call.block.name(), call.execArgs));
                    ToolRunContext ctx = new ToolRunContext(exec);
                    try {
                        var defOpt = registry.lookup(call.block.name());
                        if (defOpt.isEmpty()) {
                            slots[index] = new Slot(ctx, ToolExecutionResult.fail(
                                    "unknown tool", ToolErrorCodes.UNKNOWN_TOOL), false);
                        } else {
                            // 派发（补流路径）：与主派发路径一致，超时启用时走工作线程池
                            CompletableFuture<ToolExecutionResult> future = toolTimeoutMs > 0
                                    ? CompletableFuture.supplyAsync(
                                            () -> defOpt.get().execute(call.execArgs, ctx).join(), TOOL_WORKER)
                                    : defOpt.get().execute(call.execArgs, ctx);
                            int finalIndex = index;
                            future.whenComplete((result, error) -> {
                                if (settled.add(finalIndex)) {
                                    if (error != null) {
                                        slots[finalIndex] = new Slot(ctx,
                                                ToolExecutionResult.fail(error.getMessage(), "TOOL_ERROR"), false);
                                    } else {
                                        slots[finalIndex] = new Slot(ctx, result, true);
                                    }
                                }
                                inFlight.remove(finalIndex);
                            });
                            if (toolTimeoutMs > 0) {
                                deadlines.put(index, System.nanoTime() + timeoutNanos);
                            }
                            inFlight.put(index, future.thenApply(v -> finalIndex));
                        }
                    } catch (Exception e) {
                        slots[index] = new Slot(ctx,
                                ToolExecutionResult.fail(e.getMessage(), "TOOL_ERROR"), false);
                    }
                    nextToStart++;
                    committed = commitReady(turn, step, group, slots, callSeqs, committed, acceptContext);
                }
            }

        } catch (Exception e) {
            schedulerFailure = true;
            // 清空在途调用（超时启用时限制等待，避免清理阶段被挂死工具二次阻塞）
            for (var f : inFlight.values()) {
                try {
                    if (toolTimeoutMs > 0) {
                        f.get(toolTimeoutMs, TimeUnit.MILLISECONDS);
                    } else {
                        f.join();
                    }
                } catch (Exception ignored) {}
            }
        }

        if (aborted) {
            for (int i = started; i < groupSize; i++) {
                appendSkippedToolCall(turn, step, group.get(i).block);
            }
            return new GroupOutcome(groupSize, true, concluded);
        }

        // 提交剩余已完成槽位
        while (committed < groupSize && slots[committed] != null) {
            committed = commitOne(turn, step, group.get(committed), slots[committed],
                    callSeqs[committed], committed, acceptContext);
        }

        return new GroupOutcome(committed, false, concluded);
    }

    // ── 提交辅助方法 ──────────────────────────────────────────────

    /**
     * 按槽位顺序提交所有已完成且相邻的工具结果。
     * <p>结果必须按派发顺序进入会话日志，避免并行执行导致 tool_call/tool_result 交叉乱序。
     */
    private int commitReady(
            long turn, long step, List<PlannedCall> group,
            Slot[] slots, long[] callSeqs,
            int committed,
            java.util.function.Consumer<Message> acceptContext
    ) {
        while (committed < group.size() && slots[committed] != null) {
            committed = commitOne(turn, step, group.get(committed), slots[committed],
                    callSeqs[committed], committed, acceptContext);
        }
        return committed;
    }

    /**
     * 完成一个工具调用的最终结果处理。
     * <p>执行流程：必要时执行 finalizeContent -> 合并工具执行期间产生的延迟上下文 ->
     * 写入 tool_result 事件 -> 交给 acceptContext 接收额外上下文。
     * <p>数据示例：文件读取工具返回大文件摘要，finalizeContent 可把原始内容替换为摘要，同时保留成功状态。
     */
    private int commitOne(
            long turn, long step, PlannedCall call, Slot slot,
            long callSeq, int index,
            java.util.function.Consumer<Message> acceptContext
    ) {
        ToolExecutionResult result = slot.result;
        // 按需执行 finalizeContent
        if (slot.needsPost && slot.ctx != null) {
            var defOpt = registry.lookup(call.block.name());
            if (defOpt.isPresent()) {
                var finalized = defOpt.get().finalizeContent(slot.ctx.execution(), result);
                if (finalized.isPresent()) {
                    // 替换内容块
                    if (result instanceof ToolExecutionResult.Success s) {
                        result = new ToolExecutionResult.Success(s.value(), finalized.get(),
                                s.meta(), s.additionalContexts(), s.concludesTurn());
                    } else if (result instanceof ToolExecutionResult.Failure f) {
                        result = new ToolExecutionResult.Failure(f.error(), finalized.get(),
                                f.meta(), f.additionalContexts());
                    }
                }
            }
        }

        // 合并延迟上下文
        List<Message> deferred = slot.ctx == null
                ? List.of()
                : slot.ctx.drainDeferredContexts();
        if (!deferred.isEmpty()) {
            if (result instanceof ToolExecutionResult.Success s) {
                var combined = new ArrayList<>(s.additionalContexts());
                combined.addAll(deferred);
                result = new ToolExecutionResult.Success(s.value(), s.content(),
                        s.meta(), combined, s.concludesTurn());
            } else if (result instanceof ToolExecutionResult.Failure f) {
                var combined = new ArrayList<>(f.additionalContexts());
                combined.addAll(deferred);
                result = new ToolExecutionResult.Failure(f.error(), f.content(),
                        f.meta(), combined);
            }
        }

        // 聚合写文件类工具登记的产物路径
        if (slot.ctx != null) {
            turnArtifacts.addAll(slot.ctx.drainArtifacts());
        }

        // 追加 tool/result 事件
        appendToolResult(turn, step, call.block, result, callSeq);
        if (result.isError()) {
            String errDetail = result instanceof ToolExecutionResult.Failure f ? f.error().message() : "unknown";
            log.warn("[Tool] Turn#{} Step#{} 工具 {} 执行失败：{}（callId={}）",
                    turn, step, call.block.name(), errDetail, call.block.id());
        } else {
            log.info("[Tool] Turn#{} Step#{} 工具 {} 执行成功（callId={}）",
                    turn, step, call.block.name(), call.block.id());
        }

        // 接收额外上下文
        for (Message ctx : result.additionalContexts()) {
            acceptContext.accept(ctx);
        }

        return index + 1;
    }

    // ── 持久化事件追加 ────────────────────────────────────────────

    /**
     * 将一次工具调用写入会话事件日志，并返回该调用的序列号。
     * <p>序列号用于稍后将 tool_result 精确关联到 tool_call。
     */
    private long appendToolCall(long turn, long step, ToolCallBlock block) {
        var event = SessionEventFactory.toolCall(turn, step, block.id(), block.name(), block.arguments());
        session.append(event);
        notifyLifecycle(new ToolLifecycleEvent(
                "call", block.id(), block.name(), block.arguments(), null, "running", null));
        return event.seq();
    }

    /**
     * 将工具结果写入会话事件日志。
     * <p>失败结果会保留错误码；成功结果可携带内容、元数据和额外上下文，供下一轮 LLM 观察。
     */
    private void appendToolResult(
            long turn, long step, ToolCallBlock block,
            ToolExecutionResult result, long callSeq
    ) {
        // 构造结果内容块
        List<ContentBlock> content = result.content();
        boolean isError = result.isError();

        // 构造工具结果消息
        Message message = Message.createToolResult(block.id(), content, isError);

        // 存在错误信息时提取错误详情
        Map<String, Object> errorInfo = null;
        if (result instanceof ToolExecutionResult.Failure f && f.error().info() != null) {
            errorInfo = Map.of(
                    "name", f.error().info().name(),
                    "code", f.error().info().code()
            );
        }

        // 存在元数据时提取 meta
        Map<String, Object> meta = null;
        if (result instanceof ToolExecutionResult.Success s) {
            meta = s.meta();
        } else if (result instanceof ToolExecutionResult.Failure f) {
            meta = f.meta();
        }

        var event = SessionEventFactory.toolResult(turn, step, message, errorInfo, meta);
        session.append(event);
        notifyLifecycle(new ToolLifecycleEvent(
                "result", block.id(), block.name(), block.arguments(),
                flattenToolResult(content), isError ? "error" : "success", null));
    }

    /**
     * 为未执行的工具调用写入成对的 tool_call / tool_result 事件。
     * <p>用于回合在中途结束（取消、模型报错、步骤异常）却已经落库了含 tool_use 的
     * assistant 消息的场景：上游要求每个 tool_use 后紧跟对应的 tool_result，否则整段历史被拒。
     */
    public void appendNotExecuted(long turn, long step, List<ToolCallBlock> toolCalls, String reason) {
        if (toolCalls == null || toolCalls.isEmpty()) return;
        for (ToolCallBlock block : toolCalls) {
            if (block == null || block.id() == null || block.id().isBlank()) continue;
            long callSeq = appendToolCall(turn, step, block);
            appendToolResult(turn, step, block,
                    ToolExecutionResult.fail(reason, ToolErrorCodes.ABORTED_BEFORE_DISPATCH), callSeq);
        }
    }

    /**
     * 为被取消或未派发的工具调用写入合成失败结果。
     * <p>这保证 tool_call 和 tool_result 在事件日志中成对出现，会话重建时不会出现悬挂调用。
     */
    private void appendSkippedToolCall(long turn, long step, ToolCallBlock block) {
        appendNotExecuted(turn, step, List.of(block), "Error: tool call aborted before dispatch");
    }

    private String flattenToolResult(List<ContentBlock> blocks) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof cn.xiaofuge.deepseek.harness.domain.model.valobj.TextBlock text) {
                if (!sb.isEmpty()) sb.append("\n");
                sb.append(text.text());
            } else if (block instanceof cn.xiaofuge.deepseek.harness.domain.model.valobj.ToolResultBlock result) {
                String nested = flattenToolResult(result.content());
                if (!nested.isEmpty()) {
                    if (!sb.isEmpty()) sb.append("\n");
                    sb.append(nested);
                }
            }
        }
        return sb.length() > 8192 ? sb.substring(0, 8192) + "...[truncated]" : sb.toString();
    }

    private void notifyLifecycle(ToolLifecycleEvent event) {
        Consumer<ToolLifecycleEvent> listener = this.lifecycleListener;
        if (listener == null) return;
        try {
            listener.accept(event);
        } catch (RuntimeException ignored) {
        }
    }

    // ── 工具方法 ──────────────────────────────────────────────────

    // ── 内部类型 ──────────────────────────────────────────────────

    /**
     * 一次已解析参数、准备派发的工具调用。
     */
    private record PlannedCall(ToolCallBlock block, Object execArgs, String parseError) {}

    /**
     * 并行调度器中的一个结果槽位。
     */
    private record Slot(ToolRunContext ctx, ToolExecutionResult result, boolean needsPost) {}

    /**
     * 一组工具调用的收尾状态。
     */
    private record GroupOutcome(int consumed, boolean aborted, boolean concluded) {}
}
