package cn.xiaofuge.deepseek.harness.infrastructure.adapter.subagent;

import cn.xiaofuge.deepseek.harness.domain.agent.service.subagent.SubagentActivation;
import cn.xiaofuge.deepseek.harness.domain.agent.service.subagent.SubagentActivationSpec;
import cn.xiaofuge.deepseek.harness.domain.agent.service.subagent.SubagentProvider;
import cn.xiaofuge.deepseek.harness.domain.model.valobj.ContentBlock;
import cn.xiaofuge.deepseek.harness.domain.model.valobj.TextBlock;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Base for out-of-process subagent providers: spawns a child process, sends the
 * prompt via stdin, and reads the assistant output from stdout.
 *
 * <p>对应 TS 侧统一 activation API 的外部后端路径：实现 {@link #startActivation}，
 * 进程句柄由 activation 持有；dispose 只终止这一次激活的子进程，
 * 已自然退出的激活 dispose 为幂等 no-op。旧的
 * {@link SubagentProvider#start} caller 交付路径由接口默认方法委托。</p>
 *
 * <p>Subclasses define the command, arguments, and wire protocol encoding.</p>
 *
 * @author 小傅哥
 * @website bugstack.cn
 */
public abstract class OutOfProcessSubagentProvider implements SubagentProvider {

    protected volatile String command;
    protected final List<String> args;
    protected final String cwd;
    protected final Map<String, String> env;
    protected final long timeoutMs;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "subagent-proc");
        t.setDaemon(true);
        return t;
    });

    /**
     * 初始化进程外子智能体提供者。
     * <p>流程：准备命令和工作目录 → 启动子进程 → 转换输出。</p>
     */
    protected OutOfProcessSubagentProvider(String command, List<String> args, String cwd,
                                           Map<String, String> env, long timeoutMs) {
        this.command = command;
        this.args = List.copyOf(args);
        this.cwd = cwd;
        this.env = env == null ? Map.of() : Map.copyOf(env);
        this.timeoutMs = timeoutMs;
    }

    /** 运行期更新 CLI 命令（扩展管理热更新；立即对下一次 spawn 生效）。 */
    public void setCommand(String command) {
        if (command != null && !command.isBlank()) {
            this.command = command.trim();
        }
    }

    @Override
    public SubagentCapabilities capabilities() {
        return new SubagentCapabilities(false, false, false);
    }

    @Override
    public CompletableFuture<SubagentActivation> startActivation(SubagentActivationSpec spec) {
        CompletableFuture<SubagentActivation> ready = new CompletableFuture<>();
        CompletableFuture<SubagentResult> result = new CompletableFuture<>();
        AtomicReference<Process> processRef = new AtomicReference<>();

        executor.execute(() -> {
            try {
                Process process = spawn(spec);
                processRef.set(process);
                ready.complete(new ProcessActivation(spec, result, processRef));
                result.complete(runChild(spec, process));
            } catch (Exception e) {
                result.complete(new SubagentResult("error",
                        List.of(new TextBlock("subagent execution failed: " + e.getMessage())),
                        "exception"));
                if (!ready.isDone()) {
                    ready.completeExceptionally(e);
                }
            }
        });

        return ready;
    }

    private Process spawn(SubagentActivationSpec spec) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(command);
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        // 子进程目录：准入阶段解析出的子级工作目录优先，配置 cwd 兜底
        String dir = (spec.resolvedCwd() != null && !spec.resolvedCwd().isBlank())
                ? spec.resolvedCwd() : cwd;
        if (dir != null) pb.directory(new File(dir));
        pb.environment().putAll(env);
        pb.redirectErrorStream(false);
        return pb.start();
    }

    /**
     * 运行Child。
     */
    private SubagentResult runChild(SubagentActivationSpec spec, Process process) {
        try {
            String wireInput = encodePrompt(spec.request());
            process.getOutputStream().write(wireInput.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();

            byte[] stdout = process.getInputStream().readAllBytes();
            byte[] stderr = process.getErrorStream().readAllBytes();

            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new SubagentResult("error",
                        List.of(new TextBlock("subagent timed out after " + timeoutMs + "ms")),
                        "timeout");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                String errText = new String(stderr, StandardCharsets.UTF_8).trim();
                return new SubagentResult("error",
                        List.of(new TextBlock("subagent exited with code " + exitCode + ": " + errText)),
                        "exit-" + exitCode);
            }

            String output = new String(stdout, StandardCharsets.UTF_8).trim();
            List<ContentBlock> blocks = decodeOutput(output);
            return new SubagentResult("completed", blocks, "ok");
        } catch (Exception e) {
            return new SubagentResult("error",
                    List.of(new TextBlock("subagent execution failed: " + e.getMessage())),
                    "exception");
        }
    }

    /** Encode the prompt into the wire format expected by the child process. */
    protected abstract String encodePrompt(SubagentStartRequest request);

    /** Decode the child's stdout into content blocks. */
    protected List<ContentBlock> decodeOutput(String output) {
        return List.of(new TextBlock(output));
    }

    // ── Activation 句柄 ─────────────────────────────────────────

    private final class ProcessActivation implements SubagentActivation {

        private final SubagentActivationSpec spec;
        private final CompletableFuture<SubagentResult> result;
        private final AtomicReference<Process> processRef;

        private ProcessActivation(SubagentActivationSpec spec,
                                  CompletableFuture<SubagentResult> result,
                                  AtomicReference<Process> processRef) {
            this.spec = spec;
            this.result = result;
            this.processRef = processRef;
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
            return OutOfProcessSubagentProvider.this.name();
        }

        @Override
        public String delivery() {
            return spec.delivery();
        }

        @Override
        public boolean continuable() {
            return false;
        }

        @Override
        public CompletableFuture<SubagentResult> result() {
            return result;
        }

        @Override
        public CompletableFuture<Void> dispose() {
            return CompletableFuture.runAsync(() -> {
                Process process = processRef.get();
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                }
                // 拆解失败不覆盖已捕获结果：仅终止进程，结果 future 保持原状
            }, executor);
        }
    }
}
