package cn.xiaofuge.deepseek.harness.domain.tool.fs;

import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext;
import cn.xiaofuge.deepseek.harness.domain.tool.adapter.JsonSchemaTool;
import cn.xiaofuge.deepseek.harness.domain.tool.adapter.ToolJsonCodec;
import cn.xiaofuge.deepseek.harness.domain.tool.fs.adapter.port.IFsPort;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 文件搜索工具。
 *
 * <p>调用流程：Agent 发起 tool call → ToolRegistry 查找工具 → Guard 校验 → ToolExecutor 执行 → 结果回填。</p>
 * @author 小傅哥
 * @website bugstack.cn
 */
public class FsSearchTool extends JsonSchemaTool<FsSearchTool.Input> {

    private static final String NAME = "fs_search";
    private static final String DESCRIPTION =
            "Search for files matching a glob pattern within a directory. " +
            "Returns a list of matching file paths. " +
            "Common patterns: *.java, *.ts, *.json, **/*.py";

    private static final int DEFAULT_MAX = 100;

    private static final String PARAMETERS_JSON = """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["directory", "pattern"],
              "properties": {
                "directory": {
                  "type": "string",
                  "description": "Root directory to search in. Defaults to the agent's working directory."
                },
                "pattern": {
                  "type": "string",
                  "description": "Glob pattern to match files. Examples: *.java, **/*.json, *.ts"
                },
                "max_results": {
                  "type": "integer",
                  "description": "Maximum number of results. Default 100."
                }
              }
            }
            """;

    private final IFsPort fsService;

    public FsSearchTool(IFsPort fsService, ToolJsonCodec codec) {
        super(Input.class, codec, PARAMETERS_JSON);
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
                        "matches", Map.of(
                                "type", "array",
                                "items", Map.of("type", "string")
                        ),
                        "count", Map.of("type", "integer")
                )
        );
    }

    @Override
    protected CompletableFuture<ToolExecutionResult> run(Input input, ToolRunContext ctx) {
        List<String> matches = fsService.search(input.directory(), input.pattern(), input.max_results());

        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(matches.size()).append(" files matching '")
          .append(input.pattern()).append("' in ").append(input.directory()).append(":\n");
        for (String match : matches) {
            sb.append("  ").append(match).append("\n");
        }
        return ok(sb.toString());
    }

    @Override
    public boolean isConcurrencySafe(Object args) { return true; }

    /** 入参模型：directory/pattern 必填；max_results 缺省 100。 */
    record Input(String directory, String pattern, Integer max_results) {
        Input {
            directory = directory == null ? null : directory.trim();
            pattern = pattern == null ? null : pattern.trim();
            if (directory == null || directory.isEmpty()) {
                throw new IllegalArgumentException("directory is required");
            }
            if (pattern == null || pattern.isEmpty()) {
                throw new IllegalArgumentException("pattern is required");
            }
            max_results = max_results == null || max_results < 1 ? DEFAULT_MAX : max_results;
        }
    }
}