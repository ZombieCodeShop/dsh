package cn.xiaofuge.deepseek.harness.domain.agent.service.subagent;

import cn.xiaofuge.deepseek.harness.domain.agent.model.entity.AgentOptions;
import cn.xiaofuge.deepseek.harness.domain.agent.service.subagent.SubagentProvider.SubagentCapabilities;
import cn.xiaofuge.deepseek.harness.domain.agent.service.subagent.SubagentProvider.SubagentResult;
import cn.xiaofuge.deepseek.harness.domain.agent.service.subagent.SubagentProvider.SubagentStartRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SubagentRegistry 统一 activation 生命周期的领域测评。
 *
 * <p>覆盖：准入校验（provider 解析、能力 fail-loud、委派深度上限）、
 * 工作目录解析（相对/继承/进程回退）、childId 分配与保留、目录登记
 * （mode/depth/谱系）、dispose 级联后代、交付策略默认值。</p>
 */
class SubagentRegistryActivationTest {

    // ── 测试替身：可控能力的记录型 Provider ─────────────────────

    private static final class FakeProvider implements SubagentProvider {
        final String name;
        final SubagentCapabilities capabilities;
        final List<SubagentActivationSpec> admittedSpecs = new CopyOnWriteArrayList<>();
        final AtomicInteger disposedCount = new AtomicInteger();

        FakeProvider(String name, SubagentCapabilities capabilities) {
            this.name = name;
            this.capabilities = capabilities;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public SubagentCapabilities capabilities() {
            return capabilities;
        }

        @Override
        public CompletableFuture<SubagentActivation> startActivation(SubagentActivationSpec spec) {
            admittedSpecs.add(spec);
            String childId = spec.childId();
            SubagentResult result = new SubagentProvider.SubagentResult("completed",
                    List.of(new cn.xiaofuge.deepseek.harness.domain.model.valobj.TextBlock("ok")),
                    "run-1");
            return CompletableFuture.completedFuture(new StubActivation(childId, spec, result, disposedCount));
        }
    }

    private static final class StubActivation implements SubagentActivation {
        private final String childId;
        private final SubagentActivationSpec spec;
        private final SubagentResult result;
        private final AtomicInteger disposedCount;

        StubActivation(String childId, SubagentActivationSpec spec,
                       SubagentResult result, AtomicInteger disposedCount) {
            this.childId = childId;
            this.spec = spec;
            this.result = result;
            this.disposedCount = disposedCount;
        }

        @Override
        public String childId() {
            return childId;
        }
        @Override
        public String label() {
            return spec.label();
        }

        @Override
        public String provider() {
            return spec.provider();
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
            return CompletableFuture.completedFuture(result);
        }

        @Override
        public CompletableFuture<Void> dispose() {
            disposedCount.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }
    }

    private static SubagentStartRequest request(String parentSessionId, String parentCwd,
                                                String requestCwd, int depth) {
        return new SubagentStartRequest("do task", (AgentOptions) null,
                parentSessionId, parentCwd, requestCwd, depth, List.of(), List.of());
    }

    // ── 准入校验 ────────────────────────────────────────────────

    @Test
    void rejectsUnknownProviderWithFailedFuture() {
        SubagentRegistry registry = new SubagentRegistry();
        SubagentActivationSpec spec = new SubagentActivationSpec(
                "missing", null, null, request("p", "/tmp", null, 0), null,
                SubagentActivationSpec.DELIVERY_PARENT, false);

        CompletionException e = assertThrows(CompletionException.class, () ->
                registry.startActivation(spec).join());
        assertTrue(e.getCause().getMessage().contains("missing"));
    }

    @Test
    void rejectsContinuableWhenProviderDoesNotDeclareIt() {
        SubagentRegistry registry = new SubagentRegistry();
        registry.register(new FakeProvider("spawn",
                new SubagentProvider.SubagentCapabilities(false, false, true)));

        SubagentActivationSpec spec = new SubagentActivationSpec(
                "spawn", null, null, request("p", "/tmp", null, 0), null,
                SubagentActivationSpec.DELIVERY_PARENT, true);

        CompletionException e = assertThrows(CompletionException.class, () ->
                registry.startActivation(spec).join());
        assertTrue(e.getCause().getMessage().contains("continuable"));
    }

    @Test
    void acceptsContinuableWhenProviderDeclaresIt() {
        SubagentRegistry registry = new SubagentRegistry();
        FakeProvider provider = new FakeProvider("spawn",
                new SubagentProvider.SubagentCapabilities(true, false, true));
        registry.register(provider);

        SubagentActivationSpec spec = new SubagentActivationSpec(
                "spawn", "resume-able", null, request("p", "/tmp", null, 0), null,
                SubagentActivationSpec.DELIVERY_PARENT, true);

        SubagentActivation activation = registry.startActivation(spec).join();
        assertTrue(activation.continuable());
        assertEquals(1, provider.admittedSpecs.size());
    }

    @Test
    void rejectsDelegationDepthAtLimit() {
        SubagentRegistry registry = new SubagentRegistry();
        registry.register(new FakeProvider("spawn",
                new SubagentProvider.SubagentCapabilities(false, false, true)));

        SubagentActivationSpec atLimit = new SubagentActivationSpec(
                "spawn", null, null, request("p", "/tmp", null, SubagentRegistry.MAX_DELEGATION_DEPTH), null,
                SubagentActivationSpec.DELIVERY_PARENT, false);
        CompletionException e = assertThrows(CompletionException.class, () ->
                registry.startActivation(atLimit).join());
        assertTrue(e.getCause().getMessage().contains("delegation depth limit"));

        // 深度上限 -1 可通过。
        SubagentActivationSpec belowLimit = new SubagentActivationSpec(
                "spawn", null, null, request("p", "/tmp", null, SubagentRegistry.MAX_DELEGATION_DEPTH - 1), null,
                SubagentActivationSpec.DELIVERY_PARENT, false);
        assertNotNull(registry.startActivation(belowLimit).join());
    }

    @Test
    void rejectsInvalidDeliveryStrategy() {
        assertThrows(IllegalArgumentException.class, () -> new SubagentActivationSpec(
                "spawn", null, null, request("p", "/tmp", null, 0), null, "broadcast", false));
    }

    @Test
    void deliveryDefaultsToCallerWhenNull() {
        SubagentActivationSpec spec = new SubagentActivationSpec(
                "spawn", null, null, request("p", "/tmp", null, 0), null, null, false);
        assertEquals(SubagentActivationSpec.DELIVERY_CALLER, spec.delivery());
    }

    // ── 工作目录解析 ────────────────────────────────────────────

    @Test
    void resolvesRelativeRequestCwdAgainstParentCwd() {
        SubagentRegistry registry = new SubagentRegistry();
        FakeProvider provider = new FakeProvider("spawn",
                new SubagentProvider.SubagentCapabilities(false, false, true));
        registry.register(provider);

        SubagentActivationSpec spec = new SubagentActivationSpec(
                "spawn", null, null, request("p", "/tmp/proj", "packages/app", 0), null,
                SubagentActivationSpec.DELIVERY_PARENT, false);

        registry.startActivation(spec).join();
        assertEquals("/tmp/proj/packages/app", provider.admittedSpecs.get(0).resolvedCwd());
    }

    @Test
    void absoluteRequestCwdPassesThroughNormalized() {
        SubagentRegistry registry = new SubagentRegistry();
        FakeProvider provider = new FakeProvider("spawn",
                new SubagentProvider.SubagentCapabilities(false, false, true));
        registry.register(provider);

        SubagentActivationSpec spec = new SubagentActivationSpec(
                "spawn", null, null, request("p", "/tmp/proj", "/tmp/other/../other", 0), null,
                SubagentActivationSpec.DELIVERY_PARENT, false);

        registry.startActivation(spec).join();
        assertEquals("/tmp/other", provider.admittedSpecs.get(0).resolvedCwd());
    }

    @Test
    void inheritsParentCwdWhenRequestCwdMissing() {
        SubagentRegistry registry = new SubagentRegistry();
        FakeProvider provider = new FakeProvider("spawn",
                new SubagentProvider.SubagentCapabilities(false, false, true));
        registry.register(provider);

        SubagentActivationSpec spec = new SubagentActivationSpec(
                "spawn", null, null, request("p", "/tmp/proj", "  ", 0), null,
                SubagentActivationSpec.DELIVERY_PARENT, false);

        registry.startActivation(spec).join();
        assertEquals("/tmp/proj", provider.admittedSpecs.get(0).resolvedCwd());
    }

    @Test
    void fallsBackToProcessDirectoryWhenNoParentCwd() {
        SubagentRegistry registry = new SubagentRegistry();
        FakeProvider provider = new FakeProvider("spawn",
                new SubagentProvider.SubagentCapabilities(false, false, true));
        registry.register(provider);

        SubagentActivationSpec spec = new SubagentActivationSpec(
                "spawn", null, null, request("p", null, null, 0), null,
                SubagentActivationSpec.DELIVERY_PARENT, false);

        registry.startActivation(spec).join();
        assertEquals(System.getProperty("user.dir"), provider.admittedSpecs.get(0).resolvedCwd());
    }

    // ── childId 与目录登记 ──────────────────────────────────────

    @Test
    void reservedChildIdIsPreservedAndCatalogRecordsLineage() {
        SubagentRegistry registry = new SubagentRegistry();
        FakeProvider provider = new FakeProvider("spawn",
                new SubagentProvider.SubagentCapabilities(false, false, true));
        registry.register(provider);

        SubagentActivationSpec spec = new SubagentActivationSpec(
                "spawn", "fix bug", "child-fixed-1", request("session-9", "/tmp", null, 1), null,
                SubagentActivationSpec.DELIVERY_PARENT, false);

        SubagentActivation activation = registry.startActivation(spec).join();
        assertEquals("child-fixed-1", activation.childId());
        assertEquals("spawn", activation.provider());
        assertEquals(SubagentActivationSpec.DELIVERY_PARENT, activation.delivery());

        List<SubagentCatalogEntry> entries = registry.listCatalog("session-9");
        assertEquals(1, entries.size());
        SubagentCatalogEntry entry = entries.get(0);
        assertEquals("child-fixed-1", entry.childId());
        assertEquals("fix bug", entry.label());
        assertEquals(SubagentCatalogEntry.MODE_LOCAL, entry.mode());
        assertEquals(2, entry.delegationDepth()); // 父级深度 1 + 1
        assertTrue(entry.createdAtMillis() > 0);
        assertEquals(activation, registry.findActivation("child-fixed-1"));
    }

    @Test
    void generatesChildIdAndExternalModeWhenReservedMissing() {
        SubagentRegistry registry = new SubagentRegistry();
        FakeProvider provider = new FakeProvider("codex",
                new SubagentProvider.SubagentCapabilities(false, false, false));
        registry.register(provider);

        SubagentActivationSpec spec = new SubagentActivationSpec(
                "codex", null, null, request("session-1", "/tmp", null, 0), null,
                SubagentActivationSpec.DELIVERY_CALLER, false);

        SubagentActivation activation = registry.startActivation(spec).join();
        assertNotNull(activation.childId());
        assertTrue(activation.childId().startsWith("child-"));

        assertEquals(SubagentCatalogEntry.MODE_EXTERNAL,
                registry.listCatalog("session-1").get(0).mode());
    }

    @Test
    void startLegacyApiUsesFirstProviderAndCallerDelivery() {
        SubagentRegistry registry = new SubagentRegistry();
        FakeProvider provider = new FakeProvider("spawn",
                new SubagentProvider.SubagentCapabilities(false, false, true));
        registry.register(provider);

        SubagentProvider.SubagentResult result = registry.start(request("p", "/tmp", null, 0)).join();
        assertEquals("completed", result.stopReason());
        assertEquals(SubagentActivationSpec.DELIVERY_CALLER, provider.admittedSpecs.get(0).delivery());
    }

    // ── dispose 级联 ────────────────────────────────────────────

    @Test
    void disposeCascadesToDescendants() {
        SubagentRegistry registry = new SubagentRegistry();
        FakeProvider provider = new FakeProvider("spawn",
                new SubagentProvider.SubagentCapabilities(false, false, true));
        registry.register(provider);

        // 根激活：parentSessionId = root（顶级）；子激活：parentSessionId = 根的 childId。
        SubagentActivation root = registry.startActivation(new SubagentActivationSpec(
                "spawn", null, "A", request("root", "/tmp", null, 0), null,
                SubagentActivationSpec.DELIVERY_PARENT, false)).join();
        registry.startActivation(new SubagentActivationSpec(
                "spawn", null, "B", request("A", "/tmp", null, 1), null,
                SubagentActivationSpec.DELIVERY_PARENT, false)).join();

        assertNotNull(registry.findActivation("A"));
        assertNotNull(registry.findActivation("B"));

        registry.dispose("A").join();

        assertNull(registry.findActivation("A"));
        assertNull(registry.findActivation("B"));
        // 目录条目保留创建事实（历史可发现）。
        assertEquals(2, registry.listCatalog().size());
    }

    @Test
    void disposeAllClearsEveryLiveActivation() {
        SubagentRegistry registry = new SubagentRegistry();
        FakeProvider provider = new FakeProvider("spawn",
                new SubagentProvider.SubagentCapabilities(false, false, true));
        registry.register(provider);

        registry.startActivation(new SubagentActivationSpec(
                "spawn", null, "A", request("root", "/tmp", null, 0), null,
                SubagentActivationSpec.DELIVERY_PARENT, false)).join();
        registry.startActivation(new SubagentActivationSpec(
                "spawn", null, "B", request("root", "/tmp", null, 0), null,
                SubagentActivationSpec.DELIVERY_PARENT, false)).join();

        registry.disposeAll().join();

        assertNull(registry.findActivation("A"));
        assertNull(registry.findActivation("B"));
    }

    @Test
    void disposeUnknownChildCompletesNormally() {
        SubagentRegistry registry = new SubagentRegistry();
        registry.dispose("nope").join(); // 不抛异常即通过
    }
}
