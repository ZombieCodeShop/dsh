package cn.xiaofuge.deepseek.harness.domain.agent.service.ask;

import cn.xiaofuge.deepseek.harness.domain.agent.contract.outbound.UserQuestionGateway;
import cn.xiaofuge.deepseek.harness.domain.model.valobj.TextBlock;
import cn.xiaofuge.deepseek.harness.domain.model.valobj.ToolSchema;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolDefinition;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext;
import cn.xiaofuge.deepseek.harness.domain.tool.adapter.ToolJsonCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The {@code ask_user_question} tool.
 * <p>
 * Mirrors TS {@code dsh-tool-ask-user}. Pauses the agent loop until the
 * user responds, then feeds the answer back as a tool result.
 *
 * <p>调用流程：模型输出 → 工具调用 → 结果回填 → 下一步模型调用；每一步写入会话事件。</p>
 * <p>示例：模型请求执行 fs_read 后，工具结果作为观察值进入下一轮模型输入。</p>
 * @author 小傅哥
 * @website bugstack.cn
 */
public class AskUserTool implements ToolDefinition {

    private static final String NAME = "ask_user_question";

    private static final String DESCRIPTION =
            "Ask the user a concise question when you need confirmation, a choice, " +
            "or missing information before proceeding. Send one or more questions, " +
            "each with a stable id that will be echoed in the answer.";

    /**
     * 参数 schema 以 JSON 维护：与 provider / MCP 的 schema 词汇一致，且装配期解析一次。
     */
    private static final String PARAMETERS_JSON = """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["questions"],
              "properties": {
                "questions": {
                  "type": "array",
                  "description": "Questions to ask the user before continuing.",
                  "items": {
                    "type": "object",
                    "additionalProperties": true,
                    "required": ["id", "question"],
                    "properties": {
                      "id": {
                        "type": "string",
                        "description": "Stable id for this question; echoed in the answer."
                      },
                      "question": {
                        "type": "string",
                        "description": "The specific question to ask the user."
                      },
                      "header": {
                        "type": "string",
                        "description": "Optional short heading for the question."
                      },
                      "options": {
                        "type": "array",
                        "description": "Optional choices to show the user.",
                        "items": {
                          "type": "object",
                          "additionalProperties": true,
                          "properties": {
                            "label": {
                              "type": "string",
                              "description": "Short user-facing option label."
                            },
                            "description": {
                              "type": "string",
                              "description": "One sentence explaining the tradeoff or impact."
                            }
                          }
                        }
                      },
                      "multi_select": {
                        "type": "boolean",
                        "description": "Whether the user may select more than one option."
                      }
                    }
                  }
                }
              }
            }
            """;

    private final UserQuestionGateway userQuestions;
    private final String agentId;
    private final ToolJsonCodec codec;
    private final Map<String, Object> parameters;

    /**
     * 询问 用户 工具。
     * 流程：应用领域规则 → 更新领域对象 → 返回结果。
     * @param userQuestions 用户问答网关。
     * @param agentId Agent ID。
     * @param codec 工具侧 JSON 能力端口（写结果 / 解 schema / 转类型化入参）。
     * @return 方法执行结果。
     */
    public AskUserTool(
            UserQuestionGateway userQuestions,
            String agentId,
            ToolJsonCodec codec
    ) {
        this.userQuestions = userQuestions;
        this.agentId = agentId;
        this.codec = codec;
        this.parameters = parseSchema(codec);
    }

    @Override public String name() { return NAME; }
    @Override public String description() { return DESCRIPTION; }

    /**
     * 返回 output Schema。
     * 流程：应用领域规则 → 更新领域对象 → 返回结果。
     * @return 方法执行结果。
     */

    /**
     * 返回 output Schema。
     * 流程：应用领域规则 → 更新领域对象 → 返回结果。
     * @return 方法执行结果。
     */

    @Override
    public Map<String, Object> parameters() {
        return parameters;
    }

    private static Map<String, Object> parseSchema(ToolJsonCodec codec) {
        if (codec == null) {
            throw new IllegalStateException("no tool-json codec configured for tool " + NAME);
        }
        Object parsed = codec.parse(PARAMETERS_JSON);
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IllegalStateException("parameter schema for tool " + NAME
                    + " must parse to a JSON object but got "
                    + (parsed == null ? "null" : parsed.getClass().getSimpleName()));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) map;
        return java.util.Collections.unmodifiableMap(typed);
    }

    /**
     * 执行工具调用。
     * 流程：应用领域规则 → 更新领域对象 → 返回结果。
     * @param args 启动参数。
     * @param ctx ctx参数。
     * @return 方法执行结果。
     */

    /**
     * 执行工具调用。
     * 流程：应用领域规则 → 更新领域对象 → 返回结果。
     * @param args 启动参数。
     * @param ctx ctx参数。
     * @return 方法执行结果。
     */

    @Override
    public Map<String, Object> outputSchema() { return Map.of(); }

    /**
     * 判断当前状态是否满足条件。
     * 流程：应用领域规则 → 更新领域对象 → 返回结果。
     * @param args 启动参数。
     * @return 布尔结果：满足条件为 true，否则 false。
     */

    /**
     * 判断当前状态是否满足条件。
     * 流程：应用领域规则 → 更新领域对象 → 返回结果。
     * @param args 启动参数。
     * @return 布尔结果：满足条件为 true，否则 false。
     */

    @Override
    public CompletableFuture<ToolExecutionResult> execute(Object args, ToolRunContext ctx) {
        if (!(args instanceof Map<?, ?> rawArgs)) {
            return completed(ToolExecutionResult.fail(
                    "expected object arguments but got "
                            + (args == null ? "null" : args.getClass().getSimpleName()),
                    "INVALID_ARGS"));
        }
        try {
            if (!userQuestions.isAvailable()) {
                return completed(ToolExecutionResult.fail(
                        "no user-questions channel is available", "NO_CHANNEL"));
            }
            AskUserInput input = codec.convert(castArgs(rawArgs), AskUserInput.class);
            if (input.questions().isEmpty()) {
                return completed(ToolExecutionResult.fail(
                        "questions parameter is required and must be non-empty", "MISSING_PARAM"));
            }

            UserQuestionGateway.UserAnswer answer = userQuestions.ask(
                    new UserQuestionGateway.UserQuestionRequest(input.toQuestions(), agentId));
            return completed(ToolExecutionResult.ok(
                    List.of(new TextBlock(codec.write(answer)))));
        } catch (IllegalArgumentException e) {
            return completed(ToolExecutionResult.fail(
                    "invalid tool arguments: " + e.getMessage(), "INVALID_ARGS"));
        } catch (Exception e) {
            return completed(ToolExecutionResult.fail(
                    "ask_user_question failed: " + e.getMessage(), "EXECUTION_ERROR"));
        }
    }

    private static CompletableFuture<ToolExecutionResult> completed(ToolExecutionResult result) {
        return CompletableFuture.completedFuture(result);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castArgs(Map<?, ?> args) {
        return (Map<String, Object>) args;
    }

    // ── 参数模型：由 codec 直接反序列化，字段名只在 record 组件里出现一次 ──

    private record AskUserInput(List<QuestionInput> questions) {
        AskUserInput {
            questions = questions == null ? List.of() : List.copyOf(questions);
        }

        List<UserQuestionGateway.Question> toQuestions() {
            List<UserQuestionGateway.Question> result = new ArrayList<>(questions.size());
            for (int i = 0; i < questions.size(); i++) {
                QuestionInput question = questions.get(i);
                List<UserQuestionGateway.QuestionOption> options = question.options() == null
                        ? null
                        : question.options().stream()
                                .map(o -> new UserQuestionGateway.QuestionOption(o.label(), o.description()))
                                .toList();
                String id = question.id() != null ? question.id() : "q" + (i + 1);
                result.add(new UserQuestionGateway.Question(
                        id, question.question(), question.header(), options, question.multi_select()));
            }
            return result;
        }
    }

    private record QuestionInput(String id, String question, String header,
                                 List<OptionInput> options, boolean multi_select) {
        QuestionInput {
            question = question == null ? null : question.trim();
            if (question == null || question.isEmpty()) {
                throw new IllegalArgumentException("question is required");
            }
            id = id == null || id.isBlank() ? null : id.trim();
            header = header == null || header.isBlank() ? null : header.trim();
            options = options == null ? null : List.copyOf(options);
        }
    }

    private record OptionInput(String label, String description) {
        OptionInput {
            label = label == null ? null : label.trim();
            if (label == null || label.isEmpty()) {
                throw new IllegalArgumentException("option label is required");
            }
            description = description == null || description.isBlank() ? null : description.trim();
        }
    }
    /**
     * 判断当前状态是否满足条件。
     * 流程：应用领域规则 → 更新领域对象 → 返回结果。
     * @param args 启动参数。
     * @return 布尔结果：满足条件为 true，否则 false。
     */


    @Override
    public boolean isConcurrencySafe(Object args) { return false; }

    /**
     * 转换为 Schema。
     * 流程：应用领域规则 → 更新领域对象 → 返回结果。
     * @return 转换后的目标对象。
     */
    public ToolSchema toSchema() {
        return new ToolSchema(NAME, DESCRIPTION, parameters());
    }
}
