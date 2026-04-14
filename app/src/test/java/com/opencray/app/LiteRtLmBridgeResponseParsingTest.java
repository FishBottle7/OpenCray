package com.opencray.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.ai.edge.litertlm.Content;
import com.google.ai.edge.litertlm.Contents;
import com.google.ai.edge.litertlm.Message;
import com.google.ai.edge.litertlm.OpenApiTool;
import com.google.ai.edge.litertlm.Role;
import com.google.ai.edge.litertlm.ToolCall;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.opencray.litertlmbridge.LiteRtLmBridge;

public final class LiteRtLmBridgeResponseParsingTest {
  @Test
  public void responseFromMessageExtractsTextContentsAndPreservesMetadata() throws Exception {
    Map<String, String> channels = new LinkedHashMap<>();
    channels.put("thinking", "considering");
    Map<String, Object> arguments = new LinkedHashMap<>();
    arguments.put("query", "cat");
    Message message =
        new Message(
            Role.MODEL,
            Contents.Companion.of(
                Arrays.asList((Content) new Content.Text("Hello"), new Content.Text("World"))),
            Collections.singletonList(new ToolCall("lookup", arguments)),
            channels);

    LiteRtLmBridge.Response response = invokeResponseFromMessage(message);

    assertEquals("Hello\nWorld", response.getText());
    assertEquals("considering", response.getChannels().get("thinking"));
    assertEquals(1, response.getToolCalls().size());
    assertEquals("lookup", response.getToolCalls().get(0).getName());
    assertEquals("cat", response.getToolCalls().get(0).getArguments().get("query"));
  }

  @Test
  public void responseFromMessageReturnsEmptyTextWhenContentsAreEmpty() throws Exception {
    Message message =
        new Message(
            Role.MODEL,
            Contents.Companion.of(Collections.<Content>emptyList()),
            Collections.emptyList(),
            Collections.emptyMap());

    LiteRtLmBridge.Response response = invokeResponseFromMessage(message);

    assertTrue(response.getText().isEmpty());
  }

  @Test
  public void responseFromMessageStringifiesToolResponseContents() throws Exception {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("status", "ok");
    Message message =
        new Message(
            Role.TOOL,
            Contents.Companion.of(new Content.ToolResponse("lookup", payload)),
            Collections.emptyList(),
            Collections.emptyMap());

    LiteRtLmBridge.Response response = invokeResponseFromMessage(message);

    assertTrue(response.getText().contains("status"));
    assertTrue(response.getText().contains("ok"));
  }

  @Test
  public void bridgeOpenApiToolParsesJsonArgumentsAndDelegatesToExecutor() throws Exception {
    LiteRtLmBridge.ToolDefinition definition =
        new LiteRtLmBridge.ToolDefinition("lookup", "Search docs", "{\"type\":\"object\"}");
    Map<String, Object>[] capturedArguments = new Map[1];
    String[] capturedToolName = new String[1];
    OpenApiTool tool =
        newBridgeOpenApiTool(
            definition,
            (toolName, arguments) -> {
              capturedToolName[0] = toolName;
              capturedArguments[0] = arguments;
              return "executed";
            });

    String result =
        tool.execute(
            "{\"query\":\"cats\",\"options\":{\"fresh\":true},\"ids\":[1,2],\"empty\":null}");

    assertEquals("executed", result);
    assertEquals("lookup", capturedToolName[0]);
    assertEquals("cats", capturedArguments[0].get("query"));
    assertEquals(Boolean.TRUE, ((Map<?, ?>) capturedArguments[0].get("options")).get("fresh"));
    assertEquals(2, ((List<?>) capturedArguments[0].get("ids")).size());
    assertEquals(2, ((Number) ((List<?>) capturedArguments[0].get("ids")).get(1)).intValue());
    assertTrue(capturedArguments[0].containsKey("empty"));
    assertNull(capturedArguments[0].get("empty"));
  }

  @Test
  public void bridgeOpenApiToolThrowsWhenAutomaticExecutionIsDisabled() throws Exception {
    LiteRtLmBridge.ToolDefinition definition =
        new LiteRtLmBridge.ToolDefinition("lookup", "Search docs", "{\"type\":\"object\"}");
    OpenApiTool tool = newBridgeOpenApiTool(definition, null);

    try {
      tool.execute("{\"query\":\"cats\"}");
      fail("Expected automatic LiteRT tool execution to be rejected without an executor.");
    } catch (UnsupportedOperationException expected) {
      assertTrue(expected.getMessage().contains("disabled"));
    }
  }

  @Test
  public void backendSelectionKeepsAudioOnCpuForGpuRequests() throws Exception {
    assertEquals("gpu", invokeBridgeStringMethod("generationBackendName", "gpu"));
    assertEquals("gpu", invokeBridgeStringMethod("imageBackendName", "gpu"));
    assertEquals("cpu", invokeBridgeStringMethod("audioBackendName", "gpu"));
  }

  @Test
  public void backendSelectionKeepsAllCpuBackendsForCpuRequests() throws Exception {
    assertEquals("cpu", invokeBridgeStringMethod("generationBackendName", "cpu"));
    assertEquals("cpu", invokeBridgeStringMethod("imageBackendName", "cpu"));
    assertEquals("cpu", invokeBridgeStringMethod("audioBackendName", "cpu"));
  }

  private static LiteRtLmBridge.Response invokeResponseFromMessage(Message message)
      throws Exception {
    Method method =
        LiteRtLmBridge.class.getDeclaredMethod("responseFromMessage", Message.class);
    method.setAccessible(true);
    return (LiteRtLmBridge.Response) method.invoke(null, message);
  }

  private static OpenApiTool newBridgeOpenApiTool(
      LiteRtLmBridge.ToolDefinition definition, LiteRtLmBridge.ToolExecutor executor)
      throws Exception {
    Class<?> type = Class.forName("org.opencray.litertlmbridge.LiteRtLmBridge$BridgeOpenApiTool");
    Constructor<?> constructor =
        type.getDeclaredConstructor(LiteRtLmBridge.ToolDefinition.class, LiteRtLmBridge.ToolExecutor.class);
    constructor.setAccessible(true);
    return (OpenApiTool) constructor.newInstance(definition, executor);
  }

  private static String invokeBridgeStringMethod(String methodName, String backend)
      throws Exception {
    Method method = LiteRtLmBridge.class.getDeclaredMethod(methodName, String.class);
    method.setAccessible(true);
    return (String) method.invoke(null, backend);
  }
}
