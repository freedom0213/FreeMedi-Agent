package com.mediflow.agent.config;

import java.util.List;
import java.util.function.Consumer;

import com.mediflow.agent.agent.AgentTurnResult;
import com.mediflow.agent.agent.StreamingReplyService;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;

/** Uses DeepSeek's streaming endpoint only for patient-facing wording. */
public final class DeepSeekStreamingReplyService implements StreamingReplyService {
    private static final String SYSTEM_PROMPT = """
            你是 FreeMedi 医院导诊助手。请把系统已经完成的本轮处理结果，改写成普通患者容易看懂的中文。
            不要增加系统没有提供的医生、号源、诊断或预约结果，不要提及 JSON、工具、数据库、模型或内部状态。
            如果结果是等待补充或等待确认，请清楚说明下一步；如果预约尚未核验成功，不要说已经预约成功。
            只输出给患者看的文字，不要 Markdown 代码块。
            """;

    private final StreamingChatLanguageModel model;

    public DeepSeekStreamingReplyService(StreamingChatLanguageModel model) {
        this.model = model;
    }

    @Override
    public void stream(AgentTurnResult result, Consumer<String> onDelta,
                       Runnable onComplete, Consumer<Throwable> onError) {
        List<ChatMessage> messages = List.of(
                SystemMessage.from(SYSTEM_PROMPT),
                UserMessage.from("系统本轮结果：" + result.message()));
        try {
            model.generate(messages, new StreamingResponseHandler<>() {
                @Override
                public void onNext(String token) {
                    onDelta.accept(token);
                }

                @Override
                public void onComplete(dev.langchain4j.model.output.Response<dev.langchain4j.data.message.AiMessage> response) {
                    onComplete.run();
                }

                @Override
                public void onError(Throwable error) {
                    onError.accept(error);
                }
            });
        } catch (Throwable error) {
            onError.accept(error);
        }
    }
}
