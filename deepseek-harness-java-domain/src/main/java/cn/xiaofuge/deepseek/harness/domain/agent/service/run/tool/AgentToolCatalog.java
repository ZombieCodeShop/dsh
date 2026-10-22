package cn.xiaofuge.deepseek.harness.domain.agent.service.run.tool;

import cn.xiaofuge.deepseek.harness.domain.agent.contract.outbound.UserQuestionGateway;
import cn.xiaofuge.deepseek.harness.domain.tool.adapter.ToolJsonCodec;
import cn.xiaofuge.deepseek.harness.domain.agent.model.entity.AgentOptions;
import cn.xiaofuge.deepseek.harness.domain.agent.service.ask.AskUserTool;
import cn.xiaofuge.deepseek.harness.domain.agent.service.subagent.SubagentRegistry;
import cn.xiaofuge.deepseek.harness.domain.agent.service.subagent.SubagentTool;
import cn.xiaofuge.deepseek.harness.domain.goal.IGoalCommandService;
import cn.xiaofuge.deepseek.harness.domain.goal.IGoalQueryService;
import cn.xiaofuge.deepseek.harness.domain.jobs.IJobRegistryService;
import cn.xiaofuge.deepseek.harness.domain.lsp.ILspService;
import cn.xiaofuge.deepseek.harness.domain.plan.ExitPlanModeTool;
import cn.xiaofuge.deepseek.harness.domain.plan.model.valobj.PlanModeState;
import cn.xiaofuge.deepseek.harness.domain.plan.service.PlanModeService;
import cn.xiaofuge.deepseek.harness.domain.schedule.IScheduleService;
import cn.xiaofuge.deepseek.harness.domain.session.event.model.entity.SessionLog;
import cn.xiaofuge.deepseek.harness.domain.session.IConversationQueryService;
import cn.xiaofuge.deepseek.harness.domain.todo.service.TodoWriteService;
import cn.xiaofuge.deepseek.harness.domain.todo.service.TodoWriteTool;
import cn.xiaofuge.deepseek.harness.domain.tool.adapter.ToolRegistry;
import cn.xiaofuge.deepseek.harness.domain.tool.fs.CwdResolvingFsPort;
import cn.xiaofuge.deepseek.harness.domain.tool.fs.FsEditTool;
import cn.xiaofuge.deepseek.harness.domain.tool.fs.FsGrepTool;
import cn.xiaofuge.deepseek.harness.domain.tool.fs.FsReadImageTool;
import cn.xiaofuge.deepseek.harness.domain.tool.fs.FsReadTool;
import cn.xiaofuge.deepseek.harness.domain.tool.fs.FsSearchTool;
import cn.xiaofuge.deepseek.harness.domain.tool.fs.FsWriteTool;
import cn.xiaofuge.deepseek.harness.domain.tool.fs.StrReplaceEditorTool;
import cn.xiaofuge.deepseek.harness.domain.tool.fs.adapter.port.IFsPort;
import cn.xiaofuge.deepseek.harness.domain.tool.goal.GoalTools;
import cn.xiaofuge.deepseek.harness.domain.tool.jobs.JobKillTool;
import cn.xiaofuge.deepseek.harness.domain.tool.jobs.JobListTool;
import cn.xiaofuge.deepseek.harness.domain.tool.jobs.JobOutputTool;
import cn.xiaofuge.deepseek.harness.domain.tool.jobs.JobRunTool;
import cn.xiaofuge.deepseek.harness.domain.tool.lsp.LspTool;
import cn.xiaofuge.deepseek.harness.domain.tool.schedule.ScheduleTools;
import cn.xiaofuge.deepseek.harness.domain.tool.session.SessionSearchTool;
import cn.xiaofuge.deepseek.harness.domain.tool.shell.IShellExecutor;
import cn.xiaofuge.deepseek.harness.domain.tool.shell.ShellExecuteTool;
import cn.xiaofuge.deepseek.harness.domain.tool.plugin.PluginStatusTool;
import cn.xiaofuge.deepseek.harness.domain.tool.terminal.TerminalTools;
import cn.xiaofuge.deepseek.harness.domain.tool.web.WebFetchTool;
import cn.xiaofuge.deepseek.harness.domain.tool.web.WebSearchTool;
import cn.xiaofuge.deepseek.harness.domain.tool.web.adapter.port.IWebPort;
import cn.xiaofuge.deepseek.harness.domain.skill.ISkillService;
import cn.xiaofuge.deepseek.harness.domain.tool.skill.SkillTool;
import cn.xiaofuge.deepseek.harness.domain.plugin.IPluginRuntimeService;
import cn.xiaofuge.deepseek.harness.domain.plugin.registry.adapter.repository.IHarnessPluginRepository;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class AgentToolCatalog {

    private final IShellExecutor shellExecutor;
    private final IFsPort fsPort;
    private final IShellExecutor unrestrictedShellExecutor;
    private final IFsPort unrestrictedFsPort;
    private final IWebPort webPort;
    private final PlanModeService planModeService;
    private final TodoWriteService todoWriteService;
    private final SubagentRegistry subagentRegistry;
    private final ISkillService skillService;
    private final IJobRegistryService jobRegistryService;
    private final IGoalQueryService goalQueryService;
    private final IGoalCommandService goalCommandService;
    private final IScheduleService scheduleService;
    private final ILspService lspService;
    private final IConversationQueryService conversationQueryService;
    private final cn.xiaofuge.deepseek.harness.domain.terminal.adapter.port.ITerminalPort terminalPort;
    private final IHarnessPluginRepository pluginRepository;
    private final IPluginRuntimeService pluginRuntimeService;
    private final ToolJsonCodec toolJsonCodec;

    public AgentToolCatalog(
            IShellExecutor shellExecutor,
            IFsPort fsPort,
            IShellExecutor unrestrictedShellExecutor,
            IFsPort unrestrictedFsPort,
            IWebPort webPort,
            PlanModeService planModeService,
            TodoWriteService todoWriteService,
            SubagentRegistry subagentRegistry,
            ISkillService skillService,
            IJobRegistryService jobRegistryService,
            IGoalQueryService goalQueryService,
            IGoalCommandService goalCommandService,
            IScheduleService scheduleService,
            ILspService lspService,
            IConversationQueryService conversationQueryService,
            cn.xiaofuge.deepseek.harness.domain.terminal.adapter.port.ITerminalPort terminalPort,
            IHarnessPluginRepository pluginRepository,
            IPluginRuntimeService pluginRuntimeService,
            ToolJsonCodec toolJsonCodec
    ) {
        this.shellExecutor = shellExecutor;
        this.fsPort = fsPort;
        this.unrestrictedShellExecutor = unrestrictedShellExecutor;
        this.unrestrictedFsPort = unrestrictedFsPort;
        this.webPort = webPort;
        this.planModeService = planModeService;
        this.todoWriteService = todoWriteService;
        this.subagentRegistry = subagentRegistry;
        this.skillService = skillService;
        this.jobRegistryService = jobRegistryService;
        this.goalQueryService = goalQueryService;
        this.goalCommandService = goalCommandService;
        this.scheduleService = scheduleService;
        this.lspService = lspService;
        this.conversationQueryService = conversationQueryService;
        this.terminalPort = terminalPort;
        this.pluginRepository = pluginRepository;
        this.pluginRuntimeService = pluginRuntimeService;
        this.toolJsonCodec = toolJsonCodec;
    }

    public ToolRegistry createRegistry(AgentToolContext context, ToolRegistry sharedRegistry) {
        InMemoryToolRegistry localRegistry = new InMemoryToolRegistry();
        AtomicReference<PlanModeState> planState =
                new AtomicReference<>(new PlanModeState.Off());
        java.util.function.Supplier<AgentOptions> optionsSupplier = context.optionsSupplier();
        IShellExecutor effectiveShellExecutor = unrestrictedShellExecutor == null || optionsSupplier == null
                ? shellExecutor
                : new ApprovalAwareShellExecutor(shellExecutor, unrestrictedShellExecutor, optionsSupplier);
        IFsPort effectiveFsPort = unrestrictedFsPort == null || optionsSupplier == null
                ? fsPort
                : new ApprovalAwareFsPort(fsPort, unrestrictedFsPort, optionsSupplier);

        // cwd 感知：fsPort 是全局单例（baseDir=进程目录），相对路径必须解析到 Agent
        // 自己的工作目录，否则 fs_write 写出的文件与前端按会话 cwd 找的路径对不上；
        // cwdRef 支持热更新，Agent 实例复用且换工程目录时由 AgentRunFactory.updateCwd 刷新
        IFsPort agentFsPort = new CwdResolvingFsPort(effectiveFsPort, context.cwdRef() != null
                ? context.cwdRef()
                : new java.util.concurrent.atomic.AtomicReference<>(context.cwd()));
        // 写文件类工具自行把相对路径解析到 cwd（绝对路径透传），并按绝对路径登记产物
        java.util.function.Supplier<String> cwdSupplier = context.cwdRef() != null
                ? context.cwdRef()::get
                : () -> context.cwd();

        localRegistry.register(new TodoWriteTool(context.session(), false, todoWriteService));
        localRegistry.register(new AskUserTool(
                context.userQuestions(), context.agentId(), toolJsonCodec));
        localRegistry.register(new ExitPlanModeTool(context.session(), planState, planModeService));
        localRegistry.register(new ShellExecuteTool(
                effectiveShellExecutor,
                context.cwd()));
        localRegistry.register(new FsReadTool(agentFsPort, toolJsonCodec));
        localRegistry.register(new FsWriteTool(
                agentFsPort, cwdSupplier, toolJsonCodec));
        localRegistry.register(new FsSearchTool(agentFsPort, toolJsonCodec));
        localRegistry.register(new StrReplaceEditorTool(
                agentFsPort, cwdSupplier));
        localRegistry.register(new FsGrepTool(agentFsPort));
        localRegistry.register(new FsReadImageTool(agentFsPort));
        localRegistry.register(new FsEditTool(
                agentFsPort, cwdSupplier));
        localRegistry.register(new PluginStatusTool(pluginRepository, pluginRuntimeService));
        localRegistry.register(new WebSearchTool(webPort));
        localRegistry.register(new WebFetchTool(webPort));
        if (lspService != null) {
            localRegistry.register(new LspTool(lspService, agentFsPort));
        }
        if (terminalPort != null) {
            localRegistry.register(new TerminalTools.Open(terminalPort));
            localRegistry.register(new TerminalTools.Send(terminalPort));
            localRegistry.register(new TerminalTools.Read(terminalPort));
            localRegistry.register(new TerminalTools.Close(terminalPort));
            localRegistry.register(new TerminalTools.ListSessions(terminalPort));
        }
        if (jobRegistryService != null) {
            localRegistry.register(new JobRunTool(jobRegistryService, shellExecutor, context.sessionId(), context.cwd()));
            localRegistry.register(new JobListTool(jobRegistryService, context.sessionId()));
            localRegistry.register(new JobOutputTool(jobRegistryService, context.sessionId()));
            localRegistry.register(new JobKillTool(jobRegistryService, context.sessionId()));
        }
        if (context.session() != null) {
            if (goalQueryService != null) {
                localRegistry.register(new GoalTools.Get(goalQueryService, context.sessionId()));
            }
            if (goalCommandService != null) {
                localRegistry.register(new GoalTools.Create(goalCommandService, context.sessionId()));
                localRegistry.register(new GoalTools.Update(goalCommandService, context.sessionId()));
            }
            if (scheduleService != null) {
                localRegistry.register(new ScheduleTools.Create(scheduleService, context.sessionId()));
                localRegistry.register(new ScheduleTools.ListAll(scheduleService, context.sessionId()));
                localRegistry.register(new ScheduleTools.Delete(scheduleService, context.sessionId()));
            }
        }
        if (conversationQueryService != null) {
            localRegistry.register(new SessionSearchTool(conversationQueryService));
        }
        if (skillService != null) {
            localRegistry.register(new SkillTool(skillService, context.cwd()));
        }

        if (subagentRegistry != null && !subagentRegistry.listProviderNames().isEmpty()) {
            String providerName = subagentRegistry.listProviderNames().get(0);
            // 父级上下文：会话身份（谱系事实）、当前有效目录（子级继承基线）、
            // 委派深度（top-level 工具入口为 0，注册表准入阶段再校验上限）。
            java.util.function.Supplier<String> parentCwdSupplier = context.cwdRef() != null
                    ? context.cwdRef()::get
                    : () -> context.cwd();
            localRegistry.register(new SubagentTool(
                    subagentRegistry,
                    providerName,
                    "subagent",
                    context.optionsSupplier(),
                    context.agentId() == null ? null : () -> context.agentId(),
                    parentCwdSupplier,
                    0,
                    toolJsonCodec));
        }

        return new CompositeToolRegistry(localRegistry, sharedRegistry);
    }

    public record AgentToolContext(
            String agentId,
            String sessionId,
            String cwd,
            SessionLog session,
            UserQuestionGateway userQuestions,
            Supplier<AgentOptions> optionsSupplier,
            java.util.concurrent.atomic.AtomicReference<String> cwdRef
    ) {
        public AgentToolContext(
                String agentId,
                String sessionId,
                String cwd,
                SessionLog session,
                UserQuestionGateway userQuestions,
                Supplier<AgentOptions> optionsSupplier
        ) {
            this(agentId, sessionId, cwd, session, userQuestions, optionsSupplier, null);
        }

        public AgentToolContext(
                String agentId,
                String sessionId,
                String cwd,
                SessionLog session,
                UserQuestionGateway userQuestions
        ) {
            this(agentId, sessionId, cwd, session, userQuestions, () -> null, null);
        }
    }
}
