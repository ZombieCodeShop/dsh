package cn.xiaofuge.deepseek.harness.domain.tool.fs;

import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext;
import cn.xiaofuge.deepseek.harness.domain.tool.adapter.JsonSchemaTool;
import cn.xiaofuge.deepseek.harness.domain.tool.adapter.ToolJsonCodec;
import cn.xiaofuge.deepseek.harness.domain.tool.fs.adapter.port.IFsPort;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * 文件写入工具。
 *
 * <p>调用流程：Agent 发起 tool call → ToolRegistry 查找工具 → Guard 校验 → ToolExecutor 执行 → 结果回填。</p>
 * @author 小傅哥
 * @website bugstack.cn
 */
public class FsWriteTool extends JsonSchemaTool<FsWriteTool.Input> {

    private static final String NAME = "fs_write";
    private static final String DESCRIPTION =
            "Write content to a file. Creates the file if it doesn't exist, " +
            "overwrites if it does. Parent directories are created automatically. " +
            "Use this for creating new files, updating configurations, or saving output.";

    private static final String PARAMETERS_JSON = """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["path", "content"],
              "properties": {
                "path": {
                  "type": "string",
                  "description": "Path to the file to write. Relative paths resolve against the agent's working directory."
                },
                "content": {
                  "type": "string",
                  "description": "The content to write to the file."
                }
              }
            }
            """;

    private final IFsPort fsService;
    /** Agent 工作目录提供者：相对路径解析到 Agent cwd，产物按绝对路径登记 */
    private final Supplier<String> cwdSupplier;

    public FsWriteTool(IFsPort fsService, Supplier<String> cwdSupplier, ToolJsonCodec codec) {
        super(Input.class, codec, PARAMETERS_JSON);
        this.fsService = fsService;
        this.cwdSupplier = cwdSupplier != null ? cwdSupplier : () -> null;
    }

    @Override public String name() { return NAME; }
    @Override public String description() { return DESCRIPTION; }

    @Override
    public Map<String, Object> outputSchema() {
        return Map.of(
                "type", "object",
                "additionalProperties", true,
                "properties", Map.of(
                        "path", Map.of("type", "string"),
                        "bytes_written", Map.of("type", "integer")
                )
        );
    }

    @Override
    protected CompletableFuture<ToolExecutionResult> run(Input input, ToolRunContext ctx) {
        // 相对路径解析到 Agent cwd：与前端按会话工作目录找文件对齐，
        // 同时把解析后的绝对路径登记为产物，随 done 事件下发权威文件清单
        String effectivePath = input.path();
        java.nio.file.Path p = java.nio.file.Paths.get(effectivePath);
        String cwd = cwdSupplier.get();
        if (!p.isAbsolute() && cwd != null && !cwd.isBlank()) {
            effectivePath = java.nio.file.Paths.get(cwd).toAbsolutePath().normalize()
                    .resolve(p).normalize().toString();
        }

        fsService.write(effectivePath, input.content());

        if (ctx != null) {
            ctx.recordArtifact(effectivePath);
        }

        int bytes = input.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        return ok("Wrote " + bytes + " bytes to " + effectivePath);
    }

    @Override
    public boolean isConcurrencySafe(Object args) { return false; }

    /** 入参模型：path 必填；content 缺省为空串。 */
    record Input(String path, String content) {
        Input {
            path = path == null ? null : path.trim();
            if (path == null || path.isEmpty()) {
                throw new IllegalArgumentException("path is required");
            }
            content = content == null ? "" : content;
        }
    }
}