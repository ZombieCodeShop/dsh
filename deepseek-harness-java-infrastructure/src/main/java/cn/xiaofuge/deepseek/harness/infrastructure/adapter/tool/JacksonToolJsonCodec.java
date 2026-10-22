package cn.xiaofuge.deepseek.harness.infrastructure.adapter.tool;

import cn.xiaofuge.deepseek.harness.domain.tool.adapter.ToolJsonCodec;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 基于 Jackson 的 {@link ToolJsonCodec} 实现。
 */
public class JacksonToolJsonCodec implements ToolJsonCodec {

    private final ObjectMapper objectMapper;

    public JacksonToolJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String write(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize tool payload: " + e.getMessage(), e);
        }
    }

    @Override
    public Object parse(String json) {
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("cannot parse tool json: " + e.getMessage(), e);
        }
    }

    @Override
    public <T> T convert(Object args, Class<T> type) {
        try {
            return objectMapper.convertValue(args, type);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "invalid arguments for " + type.getSimpleName() + ": " + e.getMessage(), e);
        }
    }
}