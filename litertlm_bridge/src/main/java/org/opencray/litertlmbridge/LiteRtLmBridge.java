package org.opencray.litertlmbridge;

import com.google.ai.edge.litertlm.Backend;
import com.google.ai.edge.litertlm.Content;
import com.google.ai.edge.litertlm.Contents;
import com.google.ai.edge.litertlm.Conversation;
import com.google.ai.edge.litertlm.ConversationConfig;
import com.google.ai.edge.litertlm.Engine;
import com.google.ai.edge.litertlm.EngineConfig;
import com.google.ai.edge.litertlm.Message;
import com.google.ai.edge.litertlm.OpenApiTool;
import com.google.ai.edge.litertlm.Role;
import com.google.ai.edge.litertlm.SamplerConfig;
import com.google.ai.edge.litertlm.ToolCall;
import com.google.ai.edge.litertlm.ToolKt;
import com.google.ai.edge.litertlm.ToolProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

public final class LiteRtLmBridge {
  private LiteRtLmBridge() {}

  public static EngineHandle createEngineHandle(
      String modelPath,
      String backend,
      Integer maxNumTokens) {
    return new LiteRtLmEngineHandle(modelPath, backend, maxNumTokens);
  }

  public interface EngineHandle extends AutoCloseable {
    Response generate(Request request);

    void prewarm(Request request);

    void cancelActiveGeneration();
  }

  public interface ToolExecutor {
    String execute(String toolName, Map<String, Object> arguments);
  }

  private interface ToolExecutorResolver {
    ToolExecutor resolve();
  }

  public static final class Request {
    private final String prompt;
    private final String systemInstruction;
    private final List<MessagePayload> initialMessages;
    private final List<ToolDefinition> tools;
    private final int topK;
    private final double topP;
    private final double temperature;
    private final boolean thinkingEnabled;
    private final boolean automaticToolExecutionEnabled;
    private final ToolExecutor toolExecutor;

    public Request(
        String prompt,
        String systemInstruction,
        List<MessagePayload> initialMessages,
        List<ToolDefinition> tools,
        int topK,
        double topP,
        double temperature,
        boolean thinkingEnabled,
        boolean automaticToolExecutionEnabled,
        ToolExecutor toolExecutor) {
      this.prompt = prompt == null ? "" : prompt;
      this.systemInstruction = systemInstruction;
      this.initialMessages = immutableList(initialMessages);
      this.tools = immutableList(tools);
      this.topK = topK;
      this.topP = topP;
      this.temperature = temperature;
      this.thinkingEnabled = thinkingEnabled;
      this.automaticToolExecutionEnabled = automaticToolExecutionEnabled;
      this.toolExecutor = toolExecutor;
    }

    public String getPrompt() {
      return prompt;
    }

    public String getSystemInstruction() {
      return systemInstruction;
    }

    public List<MessagePayload> getInitialMessages() {
      return initialMessages;
    }

    public List<ToolDefinition> getTools() {
      return tools;
    }

    public int getTopK() {
      return topK;
    }

    public double getTopP() {
      return topP;
    }

    public double getTemperature() {
      return temperature;
    }

    public boolean isThinkingEnabled() {
      return thinkingEnabled;
    }

    public boolean isAutomaticToolExecutionEnabled() {
      return automaticToolExecutionEnabled;
    }

    public ToolExecutor getToolExecutor() {
      return toolExecutor;
    }
  }

  public static final class MessagePayload {
    private final String role;
    private final String content;
    private final List<ToolCallPayload> toolCalls;
    private final ToolResultPayload toolResult;

    public MessagePayload(
        String role,
        String content,
        List<ToolCallPayload> toolCalls,
        ToolResultPayload toolResult) {
      this.role = role == null ? "" : role;
      this.content = content;
      this.toolCalls = immutableList(toolCalls);
      this.toolResult = toolResult;
    }

    public String getRole() {
      return role;
    }

    public String getContent() {
      return content;
    }

    public List<ToolCallPayload> getToolCalls() {
      return toolCalls;
    }

    public ToolResultPayload getToolResult() {
      return toolResult;
    }
  }

  public static final class ToolDefinition {
    private final String name;
    private final String description;
    private final String schemaJson;

    public ToolDefinition(String name, String description, String schemaJson) {
      this.name = name == null ? "" : name;
      this.description = description == null ? "" : description;
      this.schemaJson = schemaJson == null ? "{}" : schemaJson;
    }

    public String getName() {
      return name;
    }

    public String getDescription() {
      return description;
    }

    public String getSchemaJson() {
      return schemaJson;
    }
  }

  public static final class ToolCallPayload {
    private final String name;
    private final Map<String, Object> arguments;

    public ToolCallPayload(String name, Map<String, Object> arguments) {
      this.name = name == null ? "" : name;
      this.arguments = immutableMap(arguments);
    }

    public String getName() {
      return name;
    }

    public Map<String, Object> getArguments() {
      return arguments;
    }
  }

  public static final class ToolResultPayload {
    private final String toolName;
    private final Object response;

    public ToolResultPayload(String toolName, Object response) {
      this.toolName = toolName == null ? "" : toolName;
      this.response = response;
    }

    public String getToolName() {
      return toolName;
    }

    public Object getResponse() {
      return response;
    }
  }

  public static final class Response {
    private final String text;
    private final Map<String, String> channels;
    private final List<ToolCallPayload> toolCalls;

    public Response(
        String text,
        Map<String, String> channels,
        List<ToolCallPayload> toolCalls) {
      this.text = text == null ? "" : text;
      this.channels = immutableStringMap(channels);
      this.toolCalls = immutableList(toolCalls);
    }

    public String getText() {
      return text;
    }

    public Map<String, String> getChannels() {
      return channels;
    }

    public List<ToolCallPayload> getToolCalls() {
      return toolCalls;
    }
  }

  private static final class LiteRtLmEngineHandle implements EngineHandle {
    private final Engine engine;
    private final ToolExecutorResolver toolExecutorResolver;
    private volatile Conversation activeConversation;
    private volatile ToolExecutor activeToolExecutor;
    private volatile ToolProviderCacheKey cachedToolProviderKey = ToolProviderCacheKey.empty();
    private volatile List<ToolProvider> cachedToolProviders = Collections.emptyList();

    private LiteRtLmEngineHandle(
        String modelPath,
        String backend,
        Integer maxNumTokens) {
      // Current LiteRT Gemma bundles still require CPU for the audio backend even when
      // text generation runs on GPU. Reusing the requested accelerator for audio makes
      // engine creation fail with a backend constraint mismatch.
      String generationBackend = generationBackendName(backend);
      String imageBackend = imageBackendName(backend);
      String audioBackend = audioBackendName(backend);
      this.engine =
          new Engine(
              new EngineConfig(
                  modelPath,
                  backendFromName(generationBackend),
                  backendFromName(imageBackend),
                  backendFromName(audioBackend),
                  maxNumTokens,
                  parentDirectory(modelPath)));
      this.toolExecutorResolver =
          new ToolExecutorResolver() {
            @Override
            public ToolExecutor resolve() {
              return activeToolExecutor;
            }
          };
      this.engine.initialize();
    }

    @Override
    public Response generate(Request request) {
      Conversation conversation =
          engine.createConversation(conversationConfigFrom(request));
      activeConversation = conversation;
      try {
        Message response =
            conversation.sendMessage(request.getPrompt(), Collections.emptyMap());
        return responseFromMessage(response);
      } finally {
        activeToolExecutor = null;
        activeConversation = null;
        conversation.close();
      }
    }

    @Override
    public void prewarm(Request request) {
      Conversation conversation =
          engine.createConversation(conversationConfigFrom(request));
      activeConversation = conversation;
      try {
        // Creating and closing a conversation eagerly hides the first-turn engine setup cost
        // without mutating user-visible session state.
      } finally {
        activeToolExecutor = null;
        activeConversation = null;
        conversation.close();
      }
    }

    @Override
    public void cancelActiveGeneration() {
      Conversation conversation = activeConversation;
      if (conversation != null) {
        conversation.cancelProcess();
      }
    }

    @Override
    public void close() {
      cancelActiveGeneration();
      activeConversation = null;
      engine.close();
    }

    private ConversationConfig conversationConfigFrom(Request request) {
      activeToolExecutor =
          request.isAutomaticToolExecutionEnabled() ? request.getToolExecutor() : null;
      Contents systemInstruction = contentsFromText(request.getSystemInstruction());
      List<Message> initialMessages = toLiteRtMessages(request.getInitialMessages());
      List<ToolProvider> tools = toolProvidersFor(request.getTools());
      SamplerConfig samplerConfig =
          new SamplerConfig(
              request.getTopK(),
              request.getTopP(),
              request.getTemperature(),
              0);
      if (request.isThinkingEnabled()) {
        return new ConversationConfig(
            systemInstruction,
            initialMessages,
            tools,
            samplerConfig,
            false);
      }
      return new ConversationConfig(
          systemInstruction,
          initialMessages,
          tools,
          samplerConfig,
          false,
          Collections.emptyList());
    }

    private List<ToolProvider> toolProvidersFor(List<ToolDefinition> definitions) {
      ToolProviderCacheKey nextKey = ToolProviderCacheKey.from(definitions);
      ToolProviderCacheKey currentKey = cachedToolProviderKey;
      if (nextKey.equals(currentKey)) {
        return cachedToolProviders;
      }
      List<ToolProvider> providers = toToolProviders(definitions, toolExecutorResolver);
      cachedToolProviders = Collections.unmodifiableList(new ArrayList<>(providers));
      cachedToolProviderKey = nextKey;
      return cachedToolProviders;
    }
  }

  private static final class BridgeOpenApiTool implements OpenApiTool {
    private final ToolDefinition definition;
    private final ToolExecutorResolver executorResolver;

    private BridgeOpenApiTool(ToolDefinition definition, ToolExecutorResolver executorResolver) {
      this.definition = definition;
      this.executorResolver = executorResolver;
    }

    @Override
    public String getToolDescriptionJsonString() {
      return "{\"name\":\""
          + escapeJson(definition.getName())
          + "\",\"description\":\""
          + escapeJson(definition.getDescription())
          + "\",\"parameters\":"
          + definition.getSchemaJson()
          + "}";
    }

    @Override
    public String execute(String parametersJsonString) {
      ToolExecutor executor = executorResolver == null ? null : executorResolver.resolve();
      if (executor == null) {
        throw new UnsupportedOperationException(
            "Automatic LiteRT-LM tool execution is disabled in OpenCray.");
      }
      return executor.execute(definition.getName(), parseToolArguments(parametersJsonString));
    }
  }

  private static String generationBackendName(String backend) {
    return normalizedBackendName(backend);
  }

  private static String imageBackendName(String backend) {
    return normalizedBackendName(backend);
  }

  private static String audioBackendName(String backend) {
    return "cpu";
  }

  private static String normalizedBackendName(String backend) {
    return "cpu".equalsIgnoreCase(backend) ? "cpu" : "gpu";
  }

  private static Backend backendFromName(String backend) {
    return "cpu".equalsIgnoreCase(backend) ? new Backend.CPU() : new Backend.GPU();
  }

  static Response responseFromMessage(Message response) {
    return new Response(
        textFromContents(response == null ? null : response.getContents()),
        response == null ? Collections.emptyMap() : response.getChannels(),
        response == null
            ? Collections.emptyList()
            : toBridgeToolCalls(response.getToolCalls()));
  }

  private static String parentDirectory(String modelPath) {
    int separatorIndex = modelPath == null ? -1 : Math.max(modelPath.lastIndexOf('/'), modelPath.lastIndexOf('\\'));
    if (separatorIndex <= 0) {
      return null;
    }
    return modelPath.substring(0, separatorIndex);
  }

  private static List<Message> toLiteRtMessages(List<MessagePayload> payloads) {
    List<Message> messages = new ArrayList<>();
    for (MessagePayload payload : immutableList(payloads)) {
      messages.add(toLiteRtMessage(payload));
    }
    return messages;
  }

  private static Message toLiteRtMessage(MessagePayload payload) {
    Role role = roleFor(payload.getRole());
    if (role == Role.TOOL) {
      ToolResultPayload toolResult = payload.getToolResult();
      return new Message(
          Role.TOOL,
          Contents.Companion.of(
              new Content.ToolResponse(
                  toolResult == null ? "" : toolResult.getToolName(),
                  toolResult == null ? null : toolResult.getResponse())),
          Collections.emptyList(),
          Collections.emptyMap());
    }
    return new Message(
        role,
        contentsFromText(payload.getContent()),
        toLiteRtToolCalls(payload.getToolCalls()),
        Collections.emptyMap());
  }

  private static Role roleFor(String rawRole) {
    if ("system".equalsIgnoreCase(rawRole)) {
      return Role.SYSTEM;
    }
    if ("assistant".equalsIgnoreCase(rawRole) || "model".equalsIgnoreCase(rawRole)) {
      return Role.MODEL;
    }
    if ("tool".equalsIgnoreCase(rawRole)) {
      return Role.TOOL;
    }
    return Role.USER;
  }

  private static Contents contentsFromText(String text) {
    String normalized = text == null ? "" : text.trim();
    if (normalized.isEmpty()) {
      return Contents.Companion.of(Collections.emptyList());
    }
    return Contents.Companion.of(normalized);
  }

  private static String textFromContents(Contents contents) {
    if (contents == null) {
      return "";
    }
    StringBuilder builder = new StringBuilder();
    for (Content content : immutableList(contents.getContents())) {
      String text = textFromContent(content);
      if (text == null || text.trim().isEmpty()) {
        continue;
      }
      if (builder.length() > 0) {
        builder.append('\n');
      }
      builder.append(text);
    }
    return builder.toString();
  }

  private static String textFromContent(Content content) {
    if (content instanceof Content.Text) {
      return ((Content.Text) content).getText();
    }
    if (content instanceof Content.ToolResponse) {
      Object response = ((Content.ToolResponse) content).getResponse();
      return response == null ? "" : String.valueOf(response);
    }
    return content == null ? "" : content.toString();
  }

  private static List<ToolProvider> toToolProviders(
      List<ToolDefinition> definitions,
      ToolExecutorResolver executorResolver) {
    List<ToolProvider> providers = new ArrayList<>();
    for (ToolDefinition definition : immutableList(definitions)) {
      providers.add(ToolKt.tool(new BridgeOpenApiTool(definition, executorResolver)));
    }
    return providers;
  }

  private static List<ToolCall> toLiteRtToolCalls(List<ToolCallPayload> payloads) {
    List<ToolCall> toolCalls = new ArrayList<>();
    for (ToolCallPayload payload : immutableList(payloads)) {
      toolCalls.add(new ToolCall(payload.getName(), immutableMap(payload.getArguments())));
    }
    return toolCalls;
  }

  private static List<ToolCallPayload> toBridgeToolCalls(List<ToolCall> toolCalls) {
    List<ToolCallPayload> payloads = new ArrayList<>();
    for (ToolCall toolCall : immutableList(toolCalls)) {
      payloads.add(new ToolCallPayload(toolCall.getName(), immutableMap(toolCall.getArguments())));
    }
    return payloads;
  }

  private static <T> List<T> immutableList(List<T> items) {
    if (items == null || items.isEmpty()) {
      return Collections.emptyList();
    }
    return Collections.unmodifiableList(new ArrayList<>(items));
  }

  private static Map<String, Object> immutableMap(Map<String, Object> items) {
    if (items == null || items.isEmpty()) {
      return Collections.emptyMap();
    }
    return Collections.unmodifiableMap(new LinkedHashMap<>(items));
  }

  private static Map<String, String> immutableStringMap(Map<String, String> items) {
    if (items == null || items.isEmpty()) {
      return Collections.emptyMap();
    }
    return Collections.unmodifiableMap(new LinkedHashMap<>(items));
  }

  private static Map<String, Object> parseToolArguments(String parametersJsonString) {
    String normalized = parametersJsonString == null ? "" : parametersJsonString.trim();
    if (normalized.isEmpty()) {
      return Collections.emptyMap();
    }
    Object parsed;
    try {
      parsed = new JSONTokener(normalized).nextValue();
    } catch (Throwable error) {
      return Collections.emptyMap();
    }
    if (!(parsed instanceof JSONObject)) {
      return Collections.emptyMap();
    }
    return immutableMap(jsonObjectToMap((JSONObject) parsed));
  }

  private static Map<String, Object> jsonObjectToMap(JSONObject object) {
    Map<String, Object> values = new LinkedHashMap<>();
    for (java.util.Iterator<String> iterator = object.keys(); iterator.hasNext(); ) {
      String key = iterator.next();
      values.put(key, toJavaValue(object.opt(key)));
    }
    return values;
  }

  private static List<Object> jsonArrayToList(JSONArray array) {
    List<Object> values = new ArrayList<>();
    for (int index = 0; index < array.length(); index += 1) {
      values.add(toJavaValue(array.opt(index)));
    }
    return values;
  }

  private static Object toJavaValue(Object value) {
    if (value == null || value == JSONObject.NULL) {
      return null;
    }
    if (value instanceof JSONObject) {
      return jsonObjectToMap((JSONObject) value);
    }
    if (value instanceof JSONArray) {
      return jsonArrayToList((JSONArray) value);
    }
    return value;
  }

  private static String escapeJson(String value) {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"");
  }

  private static final class ToolProviderCacheKey {
    private static final ToolProviderCacheKey EMPTY = new ToolProviderCacheKey(Collections.<String>emptyList());

    private final List<String> fingerprints;

    private ToolProviderCacheKey(List<String> fingerprints) {
      this.fingerprints = fingerprints;
    }

    static ToolProviderCacheKey empty() {
      return EMPTY;
    }

    static ToolProviderCacheKey from(List<ToolDefinition> definitions) {
      List<String> fingerprints = new ArrayList<>();
      for (ToolDefinition definition : immutableList(definitions)) {
        fingerprints.add(
            definition.getName()
                + "\u0000"
                + definition.getDescription()
                + "\u0000"
                + definition.getSchemaJson());
      }
      return new ToolProviderCacheKey(Collections.unmodifiableList(fingerprints));
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof ToolProviderCacheKey)) {
        return false;
      }
      ToolProviderCacheKey that = (ToolProviderCacheKey) other;
      return fingerprints.equals(that.fingerprints);
    }

    @Override
    public int hashCode() {
      return fingerprints.hashCode();
    }
  }
}
