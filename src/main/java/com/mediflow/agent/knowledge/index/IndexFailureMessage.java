package com.mediflow.agent.knowledge.index;

/** Only user-facing diagnostics may cross the admin response boundary. */
public final class IndexFailureMessage {
    private IndexFailureMessage() {}

    public static String display(String error) {
        if (error != null) {
            if (error.startsWith("无法访问 Qdrant") || error.startsWith("连接 Qdrant")) {
                return "连接 Qdrant 失败，请检查向量库服务。";
            }
            if (error.startsWith("向量维度与集合不一致") || error.startsWith("embedding 返回")
                    || error.startsWith("向量维度不一致") || error.startsWith("Qdrant 集合") && error.contains("维")) {
                return "向量维度不一致，请核对模型和集合的维度配置。";
            }
            if (error.startsWith("拒绝写入其它模型产生的向量") || error.startsWith("向量来自不同的模型")) {
                return "向量来自不同的模型，请核对索引配置。";
            }
        }
        return "向量生成或写入失败，请查看索引任务日志。";
    }
}
