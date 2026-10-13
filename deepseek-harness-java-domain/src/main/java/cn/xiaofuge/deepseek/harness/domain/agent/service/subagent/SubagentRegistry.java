package cn.xiaofuge.deepseek.harness.domain.agent.service.subagent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SubagentRegistry 表示子代理后端注册表与统一激活管理器。
 *
 * <p>对应 TS 侧 {@code ctx.subagents}：按名称路由后端，并在准入阶段
 * 统一完成——能力校验（continuable 依赖后端声明）、委派深度上限、
 * 工作目录解析（request.cwd 相对路径按父级当前有效目录解析）、
 * childId 分配与目录（catalog）登记。本地与外部后端共用同一套
 * activation 生命周期；{@link #dispose(String)} 会级联停止其派生后代。</p>
 *
 * <p>调用流程：调用方构建 spec → 注册表准入 → Provider 执行 →
 * 结果结算 → dispose 释放；目录条目保留创建事实供发现与审计。</p>
 *
 * @author 小傅哥
 * @website bugstack.cn
 */
public class SubagentRegistry {

    /** 最大委派深度：超过即拒绝（fail loud）。 */
    public static final int MAX_DELEGATION_DEPTH = 3;

    private final Map<String, SubagentProvider> providers = new ConcurrentHashMap<>();
    private final Map<String, SubagentActivation> liveActivations = new ConcurrentHashMap<>();
    private final List<SubagentCatalogEntry> catalog = new CopyOnWriteArrayList<>();
    private final AtomicInteger runIdCounter = new AtomicInteger(0);

    // ── Provider registration ───────────────────────────────────

    /**
     * 注册目标后端。
     * @param provider 子代理后端。
     */
    public void register(SubagentProvider provider) {
        providers.put(provider.name(), provider);
    }

    /**
     * 移除指定后端。
     * @param name 后端名称。
     */
    public void unregister(String name) {
        providers.remove(name);
    }

    /**
     * 解析后端。
     */
    public SubagentProvider resolve(String name) {
        SubagentProvider p = providers.get(name);
        if (p == null) {
            throw new IllegalArgumentException("no subagent provider registered for: " + name);
        }
        return p;
    }

    /**
     * 返回已注册后端名称列表。
     */
    public List<String> listProviderNames() {
        return List.copyOf(providers.keySet());
    }

    /**
     * 返回策略树中的下一个节点。
     */
    public String nextRunId() {
        return "run-" + runIdCounter.incrementAndGet();
    }

    // ── Unified activation lifecycle ────────────────────────────

    /**
     * 统一激活入口：准入校验 → 目录解析 → 委派后端 → 登记目录。
     *
     * @param spec 激活描述。
     * @return 受管激活句柄。
     */
    public CompletableFuture<SubagentActivation> startActivation(SubagentActivationSpec spec) {
        SubagentProvider provider;
        try {
            provider = resolve(spec.provider());
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(e);
        }

        // 能力校验：continuable 依赖后端声明，绝不静默降级。
        if (spec.continuable() && !provider.capabilities().continuable()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "provider '" + spec.provider() + "' does not support continuable subagents"));
        }

        // 委派深度上限。
        int depth = Math.max(0, spec.request().delegationDepth());
        if (depth >= MAX_DELEGATION_DEPTH) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "delegation depth limit exceeded: " + depth + " >= " + MAX_DELEGATION_DEPTH));
        }

        // 准入阶段解析子级工作目录：显式 requestCwd 相对父级解析；缺省继承父级。
        String parentCwd = spec.request().parentCwd();
        String requestCwd = spec.request().requestCwd();
        String resolvedCwd;
        if (requestCwd != null && !requestCwd.isBlank()) {
            resolvedCwd = resolveAgainst(parentCwd, requestCwd.trim());
        } else {
            resolvedCwd = (parentCwd == null || parentCwd.isBlank())
                    ? System.getProperty("user.dir")
                    : parentCwd;
        }

        // childId：预留的保留；否则分配。
        String childId = (spec.childId() != null && !spec.childId().isBlank())
                ? spec.childId()
                : "child-" + UUID.randomUUID();

        SubagentActivationSpec admitted = new SubagentActivationSpec(
                spec.provider(),
                spec.label(),
                childId,
                spec.request(),
                resolvedCwd,
                spec.delivery(),
                spec.continuable()
        );

        return provider.startActivation(admitted).thenApply(activation -> {
            SubagentActivation managed = new ManagedActivation(admitted, activation, depth + 1);
            SubagentProvider.SubagentCapabilities caps = provider.capabilities();
            catalog.add(new SubagentCatalogEntry(
                    childId,
                    admitted.request().parentSessionId(),
                    admitted.label(),
                    admitted.provider(),
                    caps.localAgent() ? SubagentCatalogEntry.MODE_LOCAL : SubagentCatalogEntry.MODE_EXTERNAL,
                    admitted.delivery(),
                    depth + 1,
                    System.currentTimeMillis()
            ));
            liveActivations.put(childId, managed);
            return managed;
        });
    }

    /**
     * 以旧的 start(request) 语义发起一次性激活（caller 交付）。
     * @param request 请求。
     * @return 执行结果。
     */
    public CompletableFuture<SubagentProvider.SubagentResult> start(SubagentProvider.SubagentStartRequest request) {
        return startActivation(new SubagentActivationSpec(
                resolveProviderNameForRequest(request),
                null,
                null,
                request,
                null,
                SubagentActivationSpec.DELIVERY_CALLER,
                false
        )).thenCompose(SubagentActivation::result);
    }

    // start(request) 没有 provider 字段，沿用旧约定：由调用方先 resolve；
    // 此处取第一个已注册后端，与 SubagentTool 的默认行为一致。
    private String resolveProviderNameForRequest(SubagentProvider.SubagentStartRequest request) {
        List<String> names = listProviderNames();
        if (names.isEmpty()) {
            throw new IllegalArgumentException("no subagent provider registered");
        }
        return names.get(0);
    }

    // ── Catalog & lifecycle queries ─────────────────────────────

    /**
     * 返回目录条目快照（创建事实，含历史条目；mode=unknown 表示
     * descriptor 不可用，仅保留可发现性）。
     */
    public List<SubagentCatalogEntry> listCatalog() {
        return Collections.unmodifiableList(new ArrayList<>(catalog));
    }

    /**
     * 返回指定父会话的直接子级目录条目。
     * @param parentSessionId 父会话标识。
     */
    public List<SubagentCatalogEntry> listCatalog(String parentSessionId) {
        List<SubagentCatalogEntry> result = new ArrayList<>();
        for (SubagentCatalogEntry entry : catalog) {
            if (parentSessionId == null ? entry.parentSessionId() == null : parentSessionId.equals(entry.parentSessionId())) {
                result.add(entry);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 查询存活的激活。
     * @param childId 子级标识。
     */
    public SubagentActivation findActivation(String childId) {
        return liveActivations.get(childId);
    }

    /**
     * 停止并释放指定激活及其派生后代。
     * @param childId 子级标识。
     * @return 全部拆解完成后完成；任一拆解失败以异常完成。
     */
    public CompletableFuture<Void> dispose(String childId) {
        List<CompletableFuture<Void>> all = new ArrayList<>();
        collectDescendants(childId, all);
        SubagentActivation self = liveActivations.remove(childId);
        if (self != null) {
            all.add(self.dispose());
        }
        return CompletableFuture.allOf(all.toArray(new CompletableFuture[0]));
    }

    /**
     * 停止并释放全部存活激活（进程关闭/会话根释放时调用）。
     */
    public CompletableFuture<Void> disposeAll() {
        List<CompletableFuture<Void>> all = new ArrayList<>();
        for (String childId : List.copyOf(liveActivations.keySet())) {
            all.add(dispose(childId));
        }
        return CompletableFuture.allOf(all.toArray(new CompletableFuture[0]));
    }

    private void collectDescendants(String ancestorId, List<CompletableFuture<Void>> acc) {
        for (String childId : List.copyOf(liveActivations.keySet())) {
            SubagentActivation activation = liveActivations.get(childId);
            if (activation == null) {
                continue;
            }
            String parent = findParentSessionId(childId);
            if (ancestorId.equals(parent) && liveActivations.remove(childId) != null) {
                acc.add(activation.dispose());
                collectDescendants(childId, acc);
            }
        }
    }

    private String findParentSessionId(String childId) {
        for (SubagentCatalogEntry entry : catalog) {
            if (childId.equals(entry.childId())) {
                return entry.parentSessionId();
            }
        }
        return null;
    }

    private String resolveAgainst(String baseCwd, String relativeOrAbsolute) {
        if (new java.io.File(relativeOrAbsolute).isAbsolute()) {
            return java.nio.file.Paths.get(relativeOrAbsolute).normalize().toString();
        }
        String base = (baseCwd == null || baseCwd.isBlank())
                ? System.getProperty("user.dir") : baseCwd;
        return java.nio.file.Paths.get(base, relativeOrAbsolute).normalize().toString();
    }

    // ── Managed wrapper ─────────────────────────────────────────

    /**
     * 包装后端返回的 activation：登记/注销存活表，并把子级目录
     * 登记进工作目录端口（本地子会话）；dispose 级联后代。
     */
    private final class ManagedActivation implements SubagentActivation {

        private final SubagentActivationSpec spec;
        private final SubagentActivation delegate;
        private final int depth;

        private ManagedActivation(SubagentActivationSpec spec, SubagentActivation delegate, int depth) {
            this.spec = spec;
            this.delegate = delegate;
            this.depth = depth;
        }

        @Override
        public String childId() {
            return delegate.childId();
        }

        @Override
        public String label() {
            return delegate.label();
        }

        @Override
        public String provider() {
            return delegate.provider();
        }

        @Override
        public String delivery() {
            return delegate.delivery();
        }

        @Override
        public boolean continuable() {
            return delegate.continuable();
        }

        @Override
        public CompletableFuture<SubagentProvider.SubagentResult> result() {
            return delegate.result();
        }

        @Override
        public CompletableFuture<Void> dispose() {
            return delegate.dispose().whenComplete((v, e) -> liveActivations.remove(spec.childId()));
        }
    }
}
