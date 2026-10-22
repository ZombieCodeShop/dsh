package cn.xiaofuge.deepseek.harness.domain.tool.fs;

import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext;
import cn.xiaofuge.deepseek.harness.domain.tool.adapter.JsonSchemaTool;
import cn.xiaofuge.deepseek.harness.domain.tool.adapter.ToolJsonCodec;
import cn.xiaofuge.deepseek.harness.domain.tool.fs.adapter.port.IFsPort;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 文件读取工具。
 *
 * <p>调用流程：Agent 发起 tool call → ToolRegistry 查找工具 → Guard 校验 → ToolExecutor 执行 → 结果回填。</p>
 * @author 小傅哥
 * @website bugstack.cn
 */
public class FsReadTool extends JsonSchemaTool<FsReadTool.ReadInput> {

    private static final String NAME = "fs_read";
    private static final String DESCRIPTION =
            "Read the contents of a text file. Supports line offset and limit " +
            "for reading large files in chunks. Output is truncated to 2000 lines " +
            "or 50KB, whichever is hit first.";

    private static final int DEFAULT_LIMIT = 2000;

    private static final String PARAMETERS_JSON = """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["path"],
              "properties": {
                "path": {
                  "type": "string",
                  "description": "Path to the file to read. Relative paths resolve against the agent's working directory."
                },
                "offset": {
                  "type": "integer",
                  "description": "Line number to start reading from (1-indexed). Default 1."
                },
                "limit": {
                  "type": "integer",
                  "description": "Maximum number of lines to read. Default 2000."
                }
              }
            }
            """;

    private final IFsPort fsService;

    public FsReadTool(IFsPort fsService, ToolJsonCodec codec) {
        super(ReadInput.class, codec, PARAMETERS_JSON);
        this.fsService = fsService;
    }

    @Override public String name() { return NAME; }
    @Override public String description() { return DESCRIPTION; }

    @Override
    public Map<String, Object> outputSchema() {
        return Map.of(
                "type", "object",
                "additionalProperties", true,
                "properties", Map.of(
                        "content", Map.of("type", "string"),
                        "lines_read", Map.of("type", "integer"),
                        "total_lines", Map.of("type", "integer")
                )
        );
    }

    @Override
    protected CompletableFuture<ToolExecutionResult> run(ReadInput input, ToolRunContext ctx) {
        return ok(fsService.read(input.path(), input.offset(), input.limit()));
    }

    @Override
    public boolean isConcurrencySafe(Object args) { return true; }

    /** 入参模型：path 必填；offset/limit 缺省为 1 / 2000。 */
    record ReadInput(String path, Integer offset, Integer limit) {
        ReadInput {
            path = path == null ? null : path.trim();
            if (path == null || path.isEmpty()) {
                throw new IllegalArgumentException("path is required");
            }
            offset = offset == null || offset < 1 ? 1 : offset;
            limit = limit == null || limit < 1 ? DEFAULT_LIMIT : limit;
        }
    }
}