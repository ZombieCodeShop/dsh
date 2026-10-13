package cn.xiaofuge.deepseek.harness.domain.agent.run;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.junit.jupiter.api.Test;

class AgentRunArchitectureTest {

    @Test
    void domainMustNotDependOnInfrastructure() {
        var classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("cn.xiaofuge.deepseek.harness");

        ArchRuleDefinition.noClasses()
                .that().resideInAPackage("cn.xiaofuge.deepseek.harness.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "cn.xiaofuge.deepseek.harness.infrastructure..")
                .check(classes);
    }

    // 项目约定：domain 允许使用 Spring（@Service 标记、@Value 配置注入等），
    // 用于容器托管与配置管理；不再强制 no-spring。

    @Test
    void agentDomainMustNotDependOnJackson() {
        var classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("cn.xiaofuge.deepseek.harness.domain.agent");

        ArchRuleDefinition.noClasses()
                .should().dependOnClassesThat().resideInAnyPackage("com.fasterxml..")
                .check(classes);
    }
}
