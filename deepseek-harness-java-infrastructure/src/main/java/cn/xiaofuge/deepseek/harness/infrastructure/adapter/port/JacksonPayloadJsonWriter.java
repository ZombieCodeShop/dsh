package cn.xiaofuge.deepseek.harness.infrastructure.adapter.port;

import cn.xiaofuge.deepseek.harness.domain.agent.contract.outbound.PayloadJsonWriter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 基于 Jackson 的 {@link PayloadJsonWriter} 实现。
 */
public class JacksonPayloadJsonWriter implements PayloadJsonWriter {

    private final ObjectMapper objectMapper;

    public JacksonPayloadJsonWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String write(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize payload: " + e.getMessage(), e);
        }
    }
}
