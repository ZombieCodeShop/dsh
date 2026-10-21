package cn.xiaofuge.deepseek.harness.domain.agent.service.run.tool;

import cn.xiaofuge.deepseek.harness.domain.skill.ISkillService;
import cn.xiaofuge.deepseek.harness.domain.skill.model.entity.SkillDefinition;
import cn.xiaofuge.deepseek.harness.domain.skill.model.entity.SkillSummary;
import cn.xiaofuge.deepseek.harness.domain.skill.model.valobj.SkillInvocationPolicy;
import cn.xiaofuge.deepseek.harness.domain.skill.model.valobj.SkillSource;
import cn.xiaofuge.deepseek.harness.domain.tool.service.ToolArgumentsParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class AgentToolCatalogTest {

    @Test
    void exposesSkillToolWhenSkillServiceIsConfigured() {
        AgentToolCatalog catalog = new AgentToolCatalog(
                null, null, null, null, null, null, null, null, skillService(),
                null, null, null, null, null, null, null, null, null, null, jsonParser());

        var registry = catalog.createRegistry(
                new AgentToolCatalog.AgentToolContext("agent", "session", null, null, null),
                new InMemoryToolRegistry());

        assertTrue(registry.lookup("skill").isPresent());
        assertTrue(registry.lookup("plugin_status").isPresent());
    }

    private static ToolArgumentsParser jsonParser() {
        return arguments -> {
            try {
                return new ObjectMapper().readValue(arguments, Map.class);
            } catch (Exception e) {
                throw new IllegalArgumentException(e);
            }
        };
    }

    private ISkillService skillService() {
        return new ISkillService() {
            @Override
            public List<SkillSummary> list(String cwd) {
                return List.of(new SkillSummary(
                        "example", "Example", Optional.empty(),
                        SkillInvocationPolicy.both(), SkillSource.RUNTIME, "test", Optional.empty()));
            }

            @Override
            public Optional<SkillDefinition> get(String name, String cwd) {
                return Optional.empty();
            }
        };
    }
}
