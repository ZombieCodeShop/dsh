package cn.xiaofuge.deepseek.harness.domain.agent.service.ask;

import cn.xiaofuge.deepseek.harness.domain.agent.contract.outbound.UserQuestionGateway;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult;
import cn.xiaofuge.deepseek.harness.domain.model.valobj.ContentBlock;
import cn.xiaofuge.deepseek.harness.domain.model.valobj.TextBlock;
import cn.xiaofuge.deepseek.harness.domain.tool.adapter.ToolJsonCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AskUserTool 把答案载荷交给 JSON 端口，参数由端口转成类型化模型。
 */
class AskUserToolTest {

    private static final String WRITER_OUTPUT = "{\"answers\":[{\"id\":\"q1\"}]}";

    private static class RecordingCodec implements ToolJsonCodec {
        private final ObjectMapper mapper = new ObjectMapper();
        final AtomicReference<Object> written = new AtomicReference<>();

        @Override
        public String write(Object payload) {
            written.set(payload);
            return WRITER_OUTPUT;
        }

        @Override
        public Object parse(String json) {
            try {
                return mapper.readValue(json, Object.class);
            } catch (Exception e) {
                throw new IllegalArgumentException(e);
            }
        }

        @Override
        public <T> T convert(Object args, Class<T> type) {
            return mapper.convertValue(args, type);
        }
    }

    private static final class FakeGateway implements UserQuestionGateway {
        final AtomicBoolean asked = new AtomicBoolean();
        final AtomicReference<UserQuestionRequest> lastRequest = new AtomicReference<>();
        final UserAnswer answer;

        FakeGateway(UserAnswer answer) {
            this.answer = answer;
        }

        @Override
        public UserAnswer ask(UserQuestionRequest request) {
            asked.set(true);
            lastRequest.set(request);
            return answer;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }

    private static Map<String, Object> oneQuestionArgs() {
        return Map.of("questions", List.of(Map.of("id", "q1", "question", "选一选")));
    }

    private static ToolExecutionResult run(AskUserTool tool, Map<String, Object> args) {
        return tool.execute(args, null).join();
    }

    @Test
    void handsAnswerPayloadToCodecAndUsesItsOutputAsToolContent() {
        UserQuestionGateway.UserAnswer answer = new UserQuestionGateway.UserAnswer(
                List.of(new UserQuestionGateway.Answer("q1", List.of("A"), null)));
        FakeGateway gateway = new FakeGateway(answer);
        RecordingCodec codec = new RecordingCodec();

        ToolExecutionResult result = run(
                new AskUserTool(gateway, "agent-1", codec), oneQuestionArgs());

        assertFalse(result.isError());
        assertSame(answer, codec.written.get(), "应把网关返回的答案对象原样交给端口，不做字段加工");
        List<ContentBlock> content = result.content();
        assertEquals(1, content.size());
        assertEquals(WRITER_OUTPUT, ((TextBlock) content.get(0)).text());
    }

    @Test
    void missingCodecFailsAtConstruction() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new AskUserTool(
                        new FakeGateway(new UserQuestionGateway.UserAnswer(List.of())), "agent-1", null));

        assertTrue(error.getMessage().contains("ask_user_question"));
    }

    @Test
    void emptyQuestionsStillFailsWithMissingParam() {
        FakeGateway gateway = new FakeGateway(new UserQuestionGateway.UserAnswer(List.of()));
        AskUserTool tool = new AskUserTool(gateway, "agent-1", new RecordingCodec());

        ToolExecutionResult result = run(tool, Map.of("questions", List.of()));

        assertTrue(result.isError());
        assertFalse(gateway.asked.get());
        assertEquals("MISSING_PARAM", ((ToolExecutionResult.Failure) result).error().info().code());
    }

    @Test
    void parametersAreParsedFromJsonWithNestedRequiredFields() {
        Map<String, Object> schema = new AskUserTool(
                new FakeGateway(new UserQuestionGateway.UserAnswer(List.of())),
                "agent-1", new RecordingCodec()).parameters();

        assertEquals("object", schema.get("type"));
        assertEquals(false, schema.get("additionalProperties"));
        assertEquals(List.of("questions"), schema.get("required"));
        assertEquals(List.of("id", "question"), nestedItemRequired(schema),
                "id/question 必须进 required，否则模型可能省略 id");
    }

    @SuppressWarnings("unchecked")
    private static Object nestedItemRequired(Map<String, Object> schema) {
        Map<String, Object> questions = (Map<String, Object>) ((Map<String, Object>) schema.get("properties"))
                .get("questions");
        return ((Map<String, Object>) questions.get("items")).get("required");
    }

    @Test
    void missingIdFallsBackToStableGeneratedId() {
        FakeGateway gateway = new FakeGateway(new UserQuestionGateway.UserAnswer(List.of()));
        AskUserTool tool = new AskUserTool(gateway, "agent-1", new RecordingCodec());

        ToolExecutionResult result = run(tool, Map.of("questions", List.of(Map.of("question", "选一选"))));

        assertFalse(result.isError());
        UserQuestionGateway.Question question = gateway.lastRequest.get().questions().get(0);
        assertEquals("q1", question.id(), "缺 id 时不能退化成字符串 \"null\"");
    }

    @Test
    void missingQuestionFailsWithInvalidArgs() {
        FakeGateway gateway = new FakeGateway(new UserQuestionGateway.UserAnswer(List.of()));
        AskUserTool tool = new AskUserTool(gateway, "agent-1", new RecordingCodec());

        ToolExecutionResult result = run(tool, Map.of("questions", List.of(Map.of("id", "q1"))));

        assertTrue(result.isError());
        assertFalse(gateway.asked.get());
        assertEquals("INVALID_ARGS", ((ToolExecutionResult.Failure) result).error().info().code());
        assertTrue(result.content().toString().contains("question is required"));
    }

    @Test
    void nonMapArgumentsFailWithInvalidArgsInsteadOfClassCast() {
        FakeGateway gateway = new FakeGateway(new UserQuestionGateway.UserAnswer(List.of()));
        AskUserTool tool = new AskUserTool(gateway, "agent-1", new RecordingCodec());

        ToolExecutionResult result = tool.execute("not-a-map", null).join();

        assertTrue(result.isError());
        assertEquals("INVALID_ARGS", ((ToolExecutionResult.Failure) result).error().info().code());
    }

    @Test
    void rejectsCodecThatDoesNotProduceJsonObject() {
        ToolJsonCodec broken = new RecordingCodec() {
            @Override
            public Object parse(String json) {
                return json;
            }
        };

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new AskUserTool(
                        new FakeGateway(new UserQuestionGateway.UserAnswer(List.of())), "agent-1", broken));

        assertTrue(error.getMessage().contains("ask_user_question"));
    }
}