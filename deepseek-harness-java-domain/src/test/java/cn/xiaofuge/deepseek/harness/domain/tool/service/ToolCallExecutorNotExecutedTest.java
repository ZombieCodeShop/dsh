package cn.xiaofuge.deepseek.harness.domain.tool.service;

import cn.xiaofuge.deepseek.harness.domain.agent.service.run.tool.InMemoryToolRegistry;
import cn.xiaofuge.deepseek.harness.domain.model.entity.MessageSource;
import cn.xiaofuge.deepseek.harness.domain.model.valobj.ToolCallBlock;
import cn.xiaofuge.deepseek.harness.domain.session.event.model.entity.SessionEvent;
import cn.xiaofuge.deepseek.harness.domain.session.event.model.entity.SessionHeader;
import cn.xiaofuge.deepseek.harness.domain.session.event.model.entity.SessionLog;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 未执行的工具调用也必须成对落库，否则历史会被上游以 "tool_use without tool_result" 拒绝。
 */
class ToolCallExecutorNotExecutedTest {

    @Test
    void writesPairedCallAndResultForEachUnexecutedCall() {
        SessionLog session = new SessionLog("session-1",
                SessionHeader.create("session-1", Instant.now(), null, "agent-1"));
        ToolCallExecutor executor = new ToolCallExecutor(
                new InMemoryToolRegistry(), session, 10, ToolArgumentsParser.identity(), 1_000);

        executor.appendNotExecuted(1, 2,
                List.of(new ToolCallBlock("call_01", "shell_execute", "{}"),
                        new ToolCallBlock("call_02", "fs_read", "{}")),
                "Error: stream aborted before tool execution");

        List<SessionEvent> events = session.events();
        assertEquals(4, events.size(), "两个调用 = 两条 tool_call + 两条 tool_result");

        for (int i = 0; i < events.size(); i += 2) {
            SessionEvent.ToolCall call = assertInstanceOf(SessionEvent.ToolCall.class, events.get(i));
            SessionEvent.ToolResult result = assertInstanceOf(SessionEvent.ToolResult.class, events.get(i + 1));
            assertEquals(call.data().callId(), toolCallIdOf(result));
        }
        assertTrue(((SessionEvent.ToolResult) events.get(1)).data().error() != null,
                "合成结果应标记为失败");
    }

    @Test
    void malformedArgumentsFailThatCallButKeepEventPairing() {
        SessionLog session = new SessionLog("session-2",
                SessionHeader.create("session-2", Instant.now(), null, "agent-1"));
        ToolArgumentsParser brokenParser = arguments -> {
            throw new IllegalArgumentException("Unexpected end of JSON input");
        };
        ToolCallExecutor executor = new ToolCallExecutor(
                new InMemoryToolRegistry(), session, 10, brokenParser, 1_000);

        boolean concluded = executor.execute(1, 1,
                List.of(new ToolCallBlock("call_01", "fs_read", "{bad")),
                new java.util.concurrent.atomic.AtomicBoolean(false), null);

        assertFalse(concluded);
        List<SessionEvent> events = session.events();
        assertEquals(2, events.size(), "参数错误也要成对落库，且不得抛出回合异常");
        SessionEvent.ToolCall call = assertInstanceOf(SessionEvent.ToolCall.class, events.get(0));
        assertEquals("call_01", call.data().callId());
        SessionEvent.ToolResult result = assertInstanceOf(SessionEvent.ToolResult.class, events.get(1));
        assertEquals("call_01", toolCallIdOf(result));
        assertTrue(resultText(result).contains("invalid tool arguments"));
    }

    private static String resultText(SessionEvent.ToolResult result) {
        StringBuilder sb = new StringBuilder();
        for (var block : result.data().message().content()) {
            if (block instanceof cn.xiaofuge.deepseek.harness.domain.model.valobj.ToolResultBlock trb) {
                for (var inner : trb.content()) {
                    if (inner instanceof cn.xiaofuge.deepseek.harness.domain.model.valobj.TextBlock tb) {
                        sb.append(tb.text());
                    }
                }
            }
        }
        return sb.toString();
    }

    private static String toolCallIdOf(SessionEvent.ToolResult result) {
        return ((MessageSource.ToolMessageSource) result.data().message().source()).callId();
    }
}