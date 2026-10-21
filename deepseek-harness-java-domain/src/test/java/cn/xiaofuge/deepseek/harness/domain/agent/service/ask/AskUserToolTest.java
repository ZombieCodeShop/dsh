package cn.xiaofuge.deepseek.harness.domain.agent.service.ask;

import cn.xiaofuge.deepseek.harness.domain.agent.contract.outbound.PayloadJsonWriter;
import cn.xiaofuge.deepseek.harness.domain.agent.contract.outbound.UserQuestionGateway;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult;
import cn.xiaofuge.deepseek.harness.domain.model.valobj.ContentBlock;
import cn.xiaofuge.deepseek.harness.domain.model.valobj.TextBlock;
import cn.xiaofuge.deepseek.harness.domain.tool.service.ToolArgumentsParser;
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
 * AskUserTool 把答案载荷交给序列化端口，参数 schema 由 JSON 文本解析而来。
 */
class AskUserToolTest {

    private static final String WRITER_OUTPUT = "{\"answers\":[{\"id\":\"q1\"}]}";

    private static final ToolArgumentsParser JSON_PARSER = arguments -> {
        try {
            return new ObjectMapper().readValue(arguments, Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    };

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

    private static final class RecordingWriter implements PayloadJsonWriter {
        final AtomicReference<Object> received = new AtomicReference<>();

        @Override
        public String write(Object payload) {
            received.set(payload);
            return WRITER_OUTPUT;
        }
    }

    private static Map<String, Object> oneQuestionArgs() {
        return Map.of("questions", List.of(Map.of("id", "q1", "question", "选一选")));
    }

    private static ToolExecutionResult run(AskUserTool tool, Map<String, Object> args) {
        return tool.execute(args, null).join();
    }

    @Test
    void handsAnswerPayloadToWriterAndUsesItsOutputAsToolContent() {
        UserQuestionGateway.UserAnswer answer = new UserQuestionGateway.UserAnswer(
                List.of(new UserQuestionGateway.Answer("q1", List.of("A"), null)));
        FakeGateway gateway = new FakeGateway(answer);
        RecordingWriter writer = new RecordingWriter();

        ToolExecutionResult result = run(
                new AskUserTool(gateway, "agent-1", writer, JSON_PARSER), oneQuestionArgs());

        assertFalse(result.isError());
        assertSame(answer, writer.received.get(), "应把网关返回的答案对象原样交给端口，不做字段加工");
        List<ContentBlock> content = result.content();
        assertEquals(1, content.size());
        assertEquals(WRITER_OUTPUT, ((TextBlock) content.get(0)).text());
    }

    @Test
    void missingWriterFailsBeforeAskingTheUser() {
        FakeGateway gateway = new FakeGateway(new UserQuestionGateway.UserAnswer(List.of()));

        ToolExecutionResult result = run(
                new AskUserTool(gateway, "agent-1", null, JSON_PARSER), oneQuestionArgs());

        assertTrue(result.isError());
        assertFalse(gateway.asked.get(), "序列化能力缺失时不应先向用户提问");
        String code = ((ToolExecutionResult.Failure) result).error().info().code();
        assertEquals("NO_SERIALIZER", code);
    }

    @Test
    void emptyQuestionsStillFailsWithMissingParam() {
        FakeGateway gateway = new FakeGateway(new UserQuestionGateway.UserAnswer(List.of()));
        AskUserTool tool = new AskUserTool(gateway, "agent-1", new RecordingWriter(), JSON_PARSER);

        ToolExecutionResult result = run(tool, Map.of("questions", List.of()));

        assertTrue(result.isError());
        assertFalse(gateway.asked.get());
    }

    @SuppressWarnings("unchecked")
    @Test
    void parametersAreParsedFromJsonWithNestedRequiredFields() {
        Map<String, Object> schema = new AskUserTool(
                new FakeGateway(new UserQuestionGateway.UserAnswer(List.of())),
                "agent-1", new RecordingWriter(), JSON_PARSER).parameters();

        assertEquals("object", schema.get("type"));
        assertEquals(false, schema.get("additionalProperties"));
        assertEquals(List.of("questions"), schema.get("required"));

        Map<String, Object> questions = (Map<String, Object>) ((Map<String, Object>) schema.get("properties"))
                .get("questions");
        Map<String, Object> item = (Map<String, Object>) questions.get("items");
        assertEquals(List.of("id", "question"), item.get("required"),
                "id/question 必须进 required，否则模型可能省略 id");
    }

    @Test
    void rejectsParserThatDoesNotProduceJsonObject() {
        ToolArgumentsParser identity = ToolArgumentsParser.identity();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new AskUserTool(
                        new FakeGateway(new UserQuestionGateway.UserAnswer(List.of())),
                        "agent-1", new RecordingWriter(), identity));

        assertTrue(error.getMessage().contains("ask_user_question"));
    }

    @Test
    void missingIdFallsBackToStableGeneratedId() {
        FakeGateway gateway = new FakeGateway(new UserQuestionGateway.UserAnswer(List.of()));
        AskUserTool tool = new AskUserTool(gateway, "agent-1", new RecordingWriter(), JSON_PARSER);

        ToolExecutionResult result = run(tool, Map.of("questions", List.of(Map.of("question", "选一选"))));

        assertFalse(result.isError());
        UserQuestionGateway.Question question = gateway.lastRequest.get().questions().get(0);
        assertEquals("q1", question.id(), "缺 id 时不能退化成字符串 \"null\"");
    }

    @Test
    void missingQuestionFailsWithInvalidArgs() {
        FakeGateway gateway = new FakeGateway(new UserQuestionGateway.UserAnswer(List.of()));
        AskUserTool tool = new AskUserTool(gateway, "agent-1", new RecordingWriter(), JSON_PARSER);

        ToolExecutionResult result = run(tool, Map.of("questions", List.of(Map.of("id", "q1"))));

        assertTrue(result.isError());
        assertFalse(gateway.asked.get());
        assertEquals("INVALID_ARGS", ((ToolExecutionResult.Failure) result).error().info().code());
        assertTrue(result.content().toString().contains("questions[0].question"));
    }

    @Test
    void nonMapArgumentsFailWithInvalidArgsInsteadOfClassCast() {
        FakeGateway gateway = new FakeGateway(new UserQuestionGateway.UserAnswer(List.of()));
        AskUserTool tool = new AskUserTool(gateway, "agent-1", new RecordingWriter(), JSON_PARSER);

        ToolExecutionResult result = tool.execute("not-a-map", null).join();

        assertTrue(result.isError());
        assertEquals("INVALID_ARGS", ((ToolExecutionResult.Failure) result).error().info().code());
    }
}