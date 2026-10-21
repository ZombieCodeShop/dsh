package cn.xiaofuge.deepseek.harness.domain.agent.service.run;

import cn.xiaofuge.deepseek.harness.domain.model.entity.Message;
import cn.xiaofuge.deepseek.harness.domain.model.entity.MessageSource;
import cn.xiaofuge.deepseek.harness.domain.model.valobj.ContentBlock;
import cn.xiaofuge.deepseek.harness.domain.model.valobj.TextBlock;
import cn.xiaofuge.deepseek.harness.domain.model.valobj.ToolCallBlock;
import cn.xiaofuge.deepseek.harness.domain.model.valobj.ToolResultBlock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 请求历史必须满足上游不变量：每个 tool_use 之后紧邻的消息里要有对应的 tool_result。
 * 否则整段历史被拒，且该错误会随历史一直复现（会话"中毒"）。
 */
class ReactLoopAgentToolPairingTest {

    @Test
    void orphanToolUseGetsSyntheticResult() {
        List<Message> repaired = ReactLoopAgent.repairToolPairing(List.of(
                user("查一下"),
                assistantWithCalls("call_01", "call_02")));

        assertEquals(3, repaired.size());
        assertEquals("tool", repaired.get(2).role());
        List<String> ids = resultIds(repaired.get(2));
        assertEquals(List.of("call_01", "call_02"), ids);
        assertTrue(((ToolResultBlock) repaired.get(2).content().get(0)).isError());
    }

    @Test
    void partialResultsAreCompletedInPlace() {
        List<Message> repaired = ReactLoopAgent.repairToolPairing(List.of(
                user("查一下"),
                assistantWithCalls("call_01", "call_02"),
                toolResult("call_01")));

        assertEquals(3, repaired.size());
        assertEquals(List.of("call_01", "call_02"), resultIds(repaired.get(2)));
    }

    @Test
    void consecutiveToolMessagesAreAcceptedWithoutChanges() {
        List<Message> history = List.of(
                user("查一下"),
                assistantWithCalls("call_01", "call_02"),
                toolResult("call_01"),
                toolResult("call_02"));

        List<Message> repaired = ReactLoopAgent.repairToolPairing(history);

        assertEquals(4, repaired.size());
        for (int i = 0; i < history.size(); i++) assertSame(history.get(i), repaired.get(i));
    }

    @Test
    void orphanToolResultWithoutToolUseIsDropped() {
        List<Message> repaired = ReactLoopAgent.repairToolPairing(List.of(
                user("查一下"),
                toolResult("call_missing")));

        assertEquals(1, repaired.size());
        assertEquals("user", repaired.get(0).role());
    }

    @Test
    void truncationSplitIsRepaired() {
        // 模拟按预算截断后：保留了 assistant(tool_use)，结果消息被切掉
        List<Message> truncated = new ArrayList<>(List.of(
                assistantWithCalls("call_01"),
                user("继续")));

        List<Message> repaired = ReactLoopAgent.repairToolPairing(truncated);

        assertEquals(3, repaired.size());
        assertEquals("tool", repaired.get(1).role());
        assertEquals(List.of("call_01"), resultIds(repaired.get(1)));
        assertEquals("user", repaired.get(2).role());
        assertFalse(resultIds(repaired.get(1)).isEmpty());
    }

    private static Message user(String text) {
        return Message.createUser(List.of(new TextBlock(text)), new MessageSource.UserSource());
    }

    private static Message assistantWithCalls(String... callIds) {
        List<ContentBlock> blocks = new ArrayList<>();
        blocks.add(new TextBlock("思考中"));
        for (String callId : callIds) {
            blocks.add(new ToolCallBlock(callId, "shell_execute", "{}"));
        }
        return Message.createAssistant(blocks,
                new MessageSource.ModelMessageSource("deepseek", "deepseek-chat"));
    }

    private static Message toolResult(String callId) {
        return Message.createToolResult(callId, List.of(new TextBlock("ok")), false);
    }

    private static List<String> resultIds(Message message) {
        List<String> ids = new ArrayList<>();
        for (ContentBlock block : message.content()) {
            if (block instanceof ToolResultBlock result) ids.add(result.toolCallId());
        }
        return ids;
    }
}