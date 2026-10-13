package cn.xiaofuge.deepseek.harness.domain.session.directory;

/**
 * WorkingDirectoryPort 表示会话工作目录的领域端口。
 *
 * <p>对应 TS 侧 working-directory capability seam：session / subagent /
 * system-prompt 的目录语义统一由此端口承载，避免各模块各自保存目录状态。</p>
 *
 * <p>约定：</p>
 * <ul>
 *   <li>会话创建或恢复时登记初始目录（{@link #register}）；</li>
 *   <li>Agent 实例复用且请求切换工程目录时热更新（{@link #update}）；</li>
 *   <li>相对路径以会话当前有效目录为基准解析（{@link #resolve}），
 *       绝对路径原样透传；目录未登记时相对路径按进程目录解析。</li>
 * </ul>
 *
 * @author 小傅哥
 * @website bugstack.cn
 */
public interface WorkingDirectoryPort {

    /**
     * 登记会话初始工作目录（会话创建或恢复时调用一次）。
     * @param sessionId 会话标识。
     * @param cwd 初始目录，null/空白忽略。
     */
    void register(String sessionId, String cwd);

    /**
     * 热更新会话工作目录（目录切换时调用）。
     * @param sessionId 会话标识。
     * @param cwd 新目录，null/空白忽略。
     */
    void update(String sessionId, String cwd);

    /**
     * 查询会话当前有效目录。
     * @param sessionId 会话标识。
     * @return 当前目录；未登记时返回 null。
     */
    String currentCwd(String sessionId);

    /**
     * 把路径解析为绝对路径：绝对路径原样返回；相对路径以会话当前
     * 有效目录为基准；会话未登记目录时按进程目录解析。
     * @param sessionId 会话标识，可为 null（按进程目录解析）。
     * @param path 待解析路径。
     * @return 绝对路径；path 为 null/空白时返回 null。
     */
    String resolve(String sessionId, String path);
}
