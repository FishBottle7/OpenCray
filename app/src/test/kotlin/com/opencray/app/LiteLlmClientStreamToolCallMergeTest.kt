package com.opencray.app

import com.opencray.app.facade.llm.mergeOpenAiStreamToolCalls
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class LiteLlmClientStreamToolCallMergeTest {
  private val client = OpenAiCompatibleLiteLlmProviderClient()

  private fun mergedMessage(deltaToolCallsJson: String): JSONObject {
    val message = JSONObject()
    client.mergeOpenAiStreamToolCalls(message, JSONArray(deltaToolCallsJson))
    return message
  }

  private fun firstFunction(message: JSONObject): JSONObject =
    message.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function")

  @Test
  fun repeatedFullNameChunksDoNotDuplicateToolName() {
    val message = mergedMessage(
      """
      [
        {"index":0,"id":"call_1","type":"function","function":{"name":"get_weather","arguments":"{\"city\":"}},
        {"index":0,"function":{"name":"get_weather","arguments":"\"Paris\"}"}},
        {"index":0,"function":{"name":"get_weather"}}
      ]
      """.trimIndent(),
    )
    assertEquals("get_weather", firstFunction(message).getString("name"))
    assertEquals("{\"city\":\"Paris\"}", firstFunction(message).getString("arguments"))
  }

  @Test
  fun differingLaterNameOverwritesEarlierName() {
    val message = mergedMessage(
      """
      [
        {"index":0,"function":{"name":"get_weather"}},
        {"index":0,"function":{"name":"get_forecast"}}
      ]
      """.trimIndent(),
    )
    assertEquals("get_forecast", firstFunction(message).getString("name"))
  }

  @Test
  fun blankNameDeltaDoesNotClearExistingName() {
    val message = mergedMessage(
      """
      [
        {"index":0,"function":{"name":"get_weather"}},
        {"index":0,"function":{"name":""}},
        {"index":0,"function":{}},
        {"index":0,"function":{"name":null}}
      ]
      """.trimIndent(),
    )
    assertEquals("get_weather", firstFunction(message).getString("name"))
  }

  @Test
  fun canonicalSingleNameDeltaStillYieldsPlainAssignment() {
    val message = mergedMessage(
      """
      [
        {"index":0,"id":"call_1","type":"function","function":{"name":"TodoWrite","arguments":"{\"todos\":[]}"}}
      ]
      """.trimIndent(),
    )
    val function = firstFunction(message)
    assertEquals("TodoWrite", function.getString("name"))
    assertEquals("{\"todos\":[]}", function.getString("arguments"))
    val toolCall = message.getJSONArray("tool_calls").getJSONObject(0)
    assertEquals("call_1", toolCall.getString("id"))
    assertEquals("function", toolCall.getString("type"))
  }
}
