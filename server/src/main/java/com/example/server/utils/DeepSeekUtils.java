package com.example.server.utils;

import com.example.server.dto.AgentState;
import com.example.server.dto.AnalysisResult;
import com.example.server.dto.VideoChunk;
import com.example.server.dto.VideoContext;
import com.example.server.dto.VideoRetrievalIntent;
import com.example.server.service.AgentTelemetry;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
public class DeepSeekUtils {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekUtils.class);

    private final ModelProvider primaryProvider;
    private final ModelProvider fallbackProvider;
    private final ObjectMapper objectMapper;
    private final AgentTelemetry telemetry;

    public DeepSeekUtils(@Value("${ai.chat.primary.api-key}") String primaryApiKey,
                         @Value("${ai.chat.primary.base-url:https://api.deepseek.com}") String primaryBaseUrl,
                         @Value("${ai.chat.primary.model:deepseek-v4-flash}") String primaryModel,
                         @Value("${ai.chat.primary.input-price-per-million:0}") double primaryInputPrice,
                         @Value("${ai.chat.primary.output-price-per-million:0}") double primaryOutputPrice,
                         @Value("${ai.chat.fallback.api-key}") String fallbackApiKey,
                         @Value("${ai.chat.fallback.base-url:https://api.siliconflow.cn/v1}") String fallbackBaseUrl,
                         @Value("${ai.chat.fallback.model:deepseek-ai/DeepSeek-V4-Flash}") String fallbackModel,
                         @Value("${ai.chat.fallback.input-price-per-million:0}") double fallbackInputPrice,
                         @Value("${ai.chat.fallback.output-price-per-million:0}") double fallbackOutputPrice,
                         @Value("${ai.chat.timeout-seconds:90}") long timeoutSeconds,
                         AgentTelemetry telemetry,
                         ObjectMapper objectMapper) {
        if (timeoutSeconds < 1) throw new IllegalArgumentException("AI request timeout must be positive");
        Duration timeout = Duration.ofSeconds(timeoutSeconds);
        this.primaryProvider = provider(
                "deepseek-official",
                primaryApiKey,
                primaryBaseUrl,
                primaryModel,
                primaryInputPrice,
                primaryOutputPrice,
                timeout);
        this.fallbackProvider = provider(
                "siliconflow",
                fallbackApiKey,
                fallbackBaseUrl,
                fallbackModel,
                fallbackInputPrice,
                fallbackOutputPrice,
                timeout);
        this.objectMapper = objectMapper;
        this.telemetry = telemetry;
    }

    public AgentState.AgentPlan plan(VideoContext context) {
        try {
            String prompt = """
                    你是 Video Agent 的 Planner。理解用户目标，并拆成 1 到 5 个可执行任务。
                    任务必须能够仅依靠 VideoContext 中的 ASR、OCR 和时间戳证据完成。
                    任务按执行顺序排列，每项只描述一个可验证的分析动作。
                    只返回 JSON：
                    {
                      "understoodGoal": "对用户目标的明确理解",
                      "tasks": ["任务1", "任务2", "任务3"]
                    }
                    VideoContext:
                    """ + objectMapper.writeValueAsString(context);
            return structuredChat("PLANNER", prompt, AgentState.AgentPlan.class);
        } catch (Exception e) {
            throw new IllegalStateException("Agent 任务规划失败", e);
        }
    }

    public AgentState.AgentPlan replan(VideoContext context,
                                       AgentState.AgentPlan currentPlan,
                                       AgentState.CriticResult critique) {
        try {
            String prompt = """
                    你是 Video Agent 的 Planner。Critic 发现当前计划遗漏了用户要求，请修订计划。
                    保留仍然有效的任务，只补充或调整遗漏部分，最终保持 1 到 5 个有序、可验证的任务。
                    任务必须能够仅依靠 VideoContext 中的 ASR、OCR 和时间戳证据完成。
                    只返回 JSON：
                    {
                      "understoodGoal": "修订后对用户目标的明确理解",
                      "tasks": ["任务1", "任务2", "任务3"]
                    }
                    CurrentPlan:
                    """ + objectMapper.writeValueAsString(currentPlan) + """

                    Critic:
                    """ + objectMapper.writeValueAsString(critique) + """

                    VideoContext:
                    """ + objectMapper.writeValueAsString(context);
            return structuredChat("REPLANNER", prompt, AgentState.AgentPlan.class);
        } catch (Exception e) {
            throw new IllegalStateException("Agent 任务重规划失败", e);
        }
    }

    public AgentState.AgentPlan repairPlan(VideoContext context,
                                           AgentState.AgentPlan invalidPlan) {
        try {
            String prompt = """
                    你是 Video Agent 的 Planner。上一份计划 JSON 可以解析，但业务结构不完整。
                    请补全目标理解，并输出 1 到 5 个非空、按顺序执行、可由当前 VideoContext 验证的任务。
                    只返回 JSON：
                    {
                      "understoodGoal": "对用户目标的明确理解",
                      "tasks": ["任务1", "任务2"]
                    }
                    InvalidPlan:
                    """ + objectMapper.writeValueAsString(invalidPlan) + """

                    VideoContext:
                    """ + objectMapper.writeValueAsString(context);
            return structuredChat("PLANNER_REPAIR", prompt, AgentState.AgentPlan.class);
        } catch (Exception e) {
            throw new IllegalStateException("Agent 任务计划修复失败", e);
        }
    }

    public VideoRetrievalIntent planRetrieval(String goal) {
        try {
            String prompt = """
                    你是 Video Agent 的检索规划器。把用户目标改写成适合检索长视频证据的查询。
                    semanticQuery 用于检索语音、摘要和上下文语义。
                    keywords 保留人物、概念、事件和专有名词。
                    visualKeywords 只保留可能出现在字幕、PPT、代码或画面文字中的词；没有则返回空数组。
                    不回答用户问题，只返回 JSON：
                    {
                      "semanticQuery": "完整、明确的检索语句",
                      "keywords": ["关键词"],
                      "visualKeywords": ["画面文字关键词"]
                    }
                    用户目标：
                    """ + goal;
            return structuredChat("RETRIEVAL_PLANNER", prompt, VideoRetrievalIntent.class);
        } catch (Exception e) {
            throw new IllegalStateException("视频检索目标拆解失败", e);
        }
    }

    public VideoChunk.ChunkSummary summarizeChunk(List<VideoContext.VideoSegment> segments) {
        try {
            String prompt = """
                    压缩以下五分钟视频片段，保留人物、事件、观点、结论以及重要 OCR 信息。
                    只返回 JSON：
                    {
                      "segmentSummary": "不超过 200 字的片段摘要",
                      "keywords": ["关键词1", "关键词2", "关键词3"]
                    }
                    原始片段：
                    """ + objectMapper.writeValueAsString(segments);
            return parseJson(chat("CHUNK_SUMMARY", prompt), VideoChunk.ChunkSummary.class);
        } catch (Exception e) {
            throw new IllegalStateException("视频片段摘要失败", e);
        }
    }

    public AnalysisResult execute(VideoContext context,
                                  AgentState.AgentPlan plan,
                                  AgentState.CriticResult previousCritique) {
        try {
            String prompt = """
                    你是 Video Agent 的 Executor。按照计划分析 VideoContext 并生成结构化产物。
                    逐项执行 Plan 中的任务，最终产物必须覆盖全部任务。
                    conclusions 中的每条结论都必须至少绑定一条真实证据。
                    evidence.claim 必须原样复制它所支持的 conclusion，timestampMs 必须落在原始片段内，source 只能是 ASR、OCR 或 ASR+OCR。
                    不得使用视频上下文之外的事实。
                    如果存在 Critic 反馈，只修正被指出的问题，并保留已经核验通过的结论和证据。

                    只返回 JSON：
                    {
                      "title": "产物标题",
                      "conclusions": ["结论"],
                      "evidence": [
                        {"timestampMs": 120000, "source": "ASR", "content": "原始证据内容", "claim": "结论"}
                      ],
                      "suggestions": ["建议"]
                    }

                    Plan:
                    """ + objectMapper.writeValueAsString(plan) + """

                    PreviousCritique:
                    """ + objectMapper.writeValueAsString(previousCritique) + """

                    VideoContext:
                    """ + objectMapper.writeValueAsString(context);
            return structuredChat("EXECUTOR", prompt, AnalysisResult.class);
        } catch (Exception e) {
            throw new IllegalStateException("Agent 执行失败", e);
        }
    }

    public AgentState.CriticResult critique(VideoContext context,
                                            AgentState.AgentPlan plan,
                                            AnalysisResult result) {
        try {
            String prompt = """
                    你是 Video Agent 的 Critic，只负责检查，不负责改写产物。
                    检查标准：
                    1. 是否覆盖用户目标和 Planner 的全部任务；
                    2. conclusions 中的每条结论是否都有 evidence.claim 的明确绑定；
                    3. 每条绑定证据的时间戳、来源和原文是否能在 VideoContext 中核验；
                    4. 是否存在上下文不支持的结论；
                    5. title、conclusions、evidence、suggestions 是否完整。

                    只有全部满足时 passed 才能为 true。
                    feedback 只填写能够基于当前 VideoContext 直接重写的修改动作。
                    missingRequirements 填写未覆盖的用户目标或 Planner 任务。
                    unsupportedClaims 填写当前 VideoContext 无法支持、需要重新检索证据的结论。
                    requiredTimestamps 只填写需要定向加载原始证据的时间戳；无需补充证据时返回空数组。
                    只返回 JSON：
                    {
                      "passed": false,
                      "feedback": ["具体修改建议"],
                      "missingRequirements": ["遗漏要求"],
                      "unsupportedClaims": ["无证据结论"],
                      "requiredTimestamps": [120000]
                    }

                    Plan:
                    """ + objectMapper.writeValueAsString(plan) + """

                    Draft:
                    """ + objectMapper.writeValueAsString(result) + """

                    VideoContext:
                    """ + objectMapper.writeValueAsString(context);
            return structuredChat("CRITIC", prompt, AgentState.CriticResult.class);
        } catch (Exception e) {
            throw new IllegalStateException("Critic 校验失败", e);
        }
    }

    private <T> T parseJson(String response, Class<T> type) throws Exception {
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("模型返回空响应");
        }
        String json = response
                .replace("```json", "")
                .replace("```", "")
                .trim();
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalStateException("模型未返回 JSON 对象");
        json = json.substring(start, end + 1);
        return objectMapper.readValue(json, type);
    }

    private <T> T structuredChat(String stage, String prompt, Class<T> type) throws Exception {
        String response = chat(stage, prompt);
        try {
            return parseJson(response, type);
        } catch (Exception e) {
            ensureNotInterrupted();
            telemetry.incrementCurrent("structuredOutputRetries", 1);
            return parseJson(chat(stage, prompt + "\n请严格返回合法 JSON，不要添加解释或代码块。"), type);
        }
    }

    private String chat(String stage, String prompt) {
        ensureNotInterrupted();
        long chatStarted = System.nanoTime();
        try {
            return call(primaryProvider, stage, prompt);
        } catch (RuntimeException primaryError) {
            ensureNotInterrupted();
            telemetry.incrementCurrent("modelProviderFallbacks", 1);
            log.warn("model_provider_fallback stage={} from={} to={}",
                    stage, primaryProvider.name(), fallbackProvider.name(), primaryError);
            try {
                return call(fallbackProvider, stage, prompt);
            } catch (RuntimeException fallbackError) {
                fallbackError.addSuppressed(primaryError);
                telemetry.failCurrentStage(stage, chatStarted);
                throw new IllegalStateException("主备模型调用均失败", fallbackError);
            }
        }
    }

    private String call(ModelProvider provider, String stage, String prompt) {
        ensureNotInterrupted();
        long started = System.nanoTime();
        try {
            telemetry.incrementCurrent("modelCalls." + provider.name(), 1);
            String response = provider.model().chat(prompt);
            ensureNotInterrupted();
            if (response == null || response.isBlank()) {
                throw new IllegalStateException("模型返回空响应");
            }
            telemetry.modelCall(stage, prompt, response,
                    provider.inputPricePerMillion(),
                    provider.outputPricePerMillion(),
                    started);
            return response;
        } catch (RuntimeException e) {
            telemetry.incrementCurrent("modelCallFailures", 1);
            telemetry.incrementCurrent("modelFailures." + provider.name(), 1);
            throw e;
        }
    }

    private void ensureNotInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new java.util.concurrent.CancellationException("model call interrupted");
        }
    }

    private ModelProvider provider(String name,
                                   String apiKey,
                                   String baseUrl,
                                   String modelName,
                                   double inputPricePerMillion,
                                   double outputPricePerMillion,
                                   Duration timeout) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException(name + " API key is required");
        }
        ChatModel model = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(timeout)
                // Provider failover is managed here; nested SDK retries would delay the fallback.
                .maxRetries(0)
                .build();
        return new ModelProvider(
                name, model, inputPricePerMillion, outputPricePerMillion);
    }

    private record ModelProvider(
            String name,
            ChatModel model,
            double inputPricePerMillion,
            double outputPricePerMillion) {
    }
}
