package cn.xiaofuge.deepseek.harness.domain.tool.adapter;

import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolDefinition;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext;
import cn.xiaofuge.deepseek.harness.domain.model.valobj.ContentBlock;
import cn.xiaofuge.deepseek.harness.domain.model.valobj.TextBlock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 内置工具的公共骨架：schema 以 JSON 文本声明、装配期解析一次；
 * 参数由 {@link ToolJsonCodec} 转成类型化入参；参数形状错误统一返回 INVALID_ARGS。
 * <p>
 * 子类只需声明 name/description、入参 record 与 {@link #run}。
 *
 * @param <I> 入参模型类型（应为 record）
 */
public abstract class JsonSchemaTool<I> implements ToolDefinition {

    private final Class<I> inputType;
    private final ToolJsonCodec codec;
    private final Map<String, Object> parameters;

    protected JsonSchemaTool(Class<I> inputType, ToolJsonCodec codec, String schemaJson) {
        this.inputType = inputType;
        this.codec = codec;
        Object parsed = codec == null ? null : codec.parse(schemaJson);
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IllegalStateException("no tool-json codec configured for tool " + getClass().getSimpleName());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) map;
        this.parameters = java.util.Collections.unmodifiableMap(typed);
    }

    /** 解析好的参数 schema（同一个不可变实例，不再逐步重建）。 */
    @Override
    public final Map<String, Object> parameters() {
        return parameters;
    }

    /** 统一入口：校验参数形状 → 转类型 → 交给子类；失败降级为工具结果。 */
    @Override
    public final CompletableFuture<ToolExecutionResult> execute(Object rawArgs, ToolRunContext ctx) {
        if (!(rawArgs instanceof Map<?, ?>)) {
            return fail("expected object arguments but got "
                    + (rawArgs == null ? "null" : rawArgs.getClass().getSimpleName()), "INVALID_ARGS");
        }
        I input;
        try {
            input = codec.convert(rawArgs, inputType);
        } catch (IllegalArgumentException e) {
            return fail("invalid tool arguments: " + e.getMessage(), "INVALID_ARGS");
        }
        try {
            return run(input, ctx);
        } catch (IllegalArgumentException e) {
            return fail("invalid tool arguments: " + e.getMessage(), "INVALID_ARGS");
        } catch (Exception e) {
            return fail(name() + " failed: " + e.getMessage(), "EXECUTION_ERROR");
        }
    }

    /** 工具业务逻辑；参数已完成类型转换。 */
    protected abstract CompletableFuture<ToolExecutionResult> run(I input, ToolRunContext ctx);

    // ── 结果工厂 ──────────────────────────────────────────────

    protected static CompletableFuture<ToolExecutionResult> ok(String text) {
        return CompletableFuture.completedFuture(
                ToolExecutionResult.ok(List.of(new TextBlock(text))));
    }

    protected static CompletableFuture<ToolExecutionResult> ok(List<ContentBlock> content) {
        return CompletableFuture.completedFuture(ToolExecutionResult.ok(content));
    }

    protected static CompletableFuture<ToolExecutionResult> fail(String message, String code) {
        return CompletableFuture.completedFuture(ToolExecutionResult.fail(message, code));
    }

    /** 子类读取字符串参数时的统一归一化。 */
    protected static String text(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}