package cn.xiaofuge.deepseek.harness.domain.tool.adapter;

/**
 * 工具侧的 JSON 能力端口：写结果、解 schema、把模型给的参数转成类型化入参。
 * <p>
 * 存在这个端口是为了让工具实现不依赖具体 JSON 库（domain.agent 禁止 Jackson，
 * 见 AgentRunArchitectureTest），转换/解析能力由基础设施注入。
 */
public interface ToolJsonCodec {

    /** 把领域对象写成 JSON 文本（工具结果回填给模型用）。 */
    String write(Object payload);

    /** 解析 JSON 文本，对象得到 Map、数组得到 List。失败抛 IllegalArgumentException。 */
    Object parse(String json);

    /** 把已解析的参数转成类型化对象；形状不合法时抛 IllegalArgumentException。 */
    <T> T convert(Object args, Class<T> type);
}