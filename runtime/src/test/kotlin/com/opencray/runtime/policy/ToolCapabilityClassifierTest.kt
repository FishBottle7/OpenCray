package com.opencray.runtime.policy

import com.opencray.policy.PolicyToolClass
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolCapabilityClassifierTest {
  private val classifier = ToolCapabilityClassifier()

  @Test
  fun classifiesExecutionToolsConsistently() {
    assertEquals(PolicyToolClass.EXECUTE_COMMAND, classifier.classifyPolicyToolClass("Bash"))
    assertEquals(PolicyToolClass.EXECUTE_COMMAND, classifier.classifyPolicyToolClass("command_exec"))
    assertEquals(PolicyToolClass.EXECUTE_COMMAND, classifier.classifyPolicyToolClass("python_exec"))
    assertEquals(PolicyToolClass.EXECUTE_COMMAND, classifier.classifyPolicyToolClass("ProcessStart"))
    assertEquals(PolicyToolClass.EXECUTE_COMMAND, classifier.classifyPolicyToolClass("ProcessTerminate"))
    assertEquals("execute_command", classifier.classifyCapabilityKind("Bash"))
    assertEquals("execute_command", classifier.classifyCapabilityKind("ProcessStart"))
    assertEquals("process_control", classifier.classifyCapabilityKind("ProcessTerminate"))
    assertEquals("delegate_task", classifier.classifyCapabilityKind("Task"))
  }

  @Test
  fun classifiesFileAndNetworkToolsConsistently() {
    assertEquals(PolicyToolClass.WRITE_FILE, classifier.classifyPolicyToolClass("Write"))
    assertEquals(PolicyToolClass.WRITE_FILE, classifier.classifyPolicyToolClass("Edit"))
    assertEquals(PolicyToolClass.WRITE_FILE, classifier.classifyPolicyToolClass("import_chat_attachment"))
    assertEquals(PolicyToolClass.MOVE_FILE, classifier.classifyPolicyToolClass("workspace_move_file"))
    assertEquals(PolicyToolClass.DELETE_FILE, classifier.classifyPolicyToolClass("workspace_delete_file"))
    assertEquals(PolicyToolClass.NETWORK_ACCESS, classifier.classifyPolicyToolClass("WebFetch"))
    assertEquals(PolicyToolClass.NETWORK_ACCESS, classifier.classifyPolicyToolClass("GenerateImage"))
    assertEquals(PolicyToolClass.NETWORK_ACCESS, classifier.classifyPolicyToolClass("SynthesizeSpeech"))
    assertEquals(PolicyToolClass.NETWORK_ACCESS, classifier.classifyPolicyToolClass("sandbox_preview_open"))
    assertEquals(PolicyToolClass.READ_FILE, classifier.classifyPolicyToolClass("Read"))
    assertEquals(PolicyToolClass.READ_FILE, classifier.classifyPolicyToolClass("view_workspace_image"))
    assertEquals(PolicyToolClass.READ_FILE, classifier.classifyPolicyToolClass("view_workspace_pdf"))
    assertEquals(PolicyToolClass.READ_FILE, classifier.classifyPolicyToolClass("Task"))
    assertEquals("write_file", classifier.classifyCapabilityKind("Write"))
    assertEquals("write_file", classifier.classifyCapabilityKind("import_chat_attachment"))
    assertEquals("move_file", classifier.classifyCapabilityKind("workspace_move_file"))
    assertEquals("delete_file", classifier.classifyCapabilityKind("workspace_delete_file"))
    assertEquals("network_access", classifier.classifyCapabilityKind("WebFetch"))
    assertEquals("network_access", classifier.classifyCapabilityKind("GenerateImage"))
    assertEquals("network_access", classifier.classifyCapabilityKind("SynthesizeSpeech"))
    assertEquals("network_access", classifier.classifyCapabilityKind("sandbox_preview_open"))
    assertEquals("read_file", classifier.classifyCapabilityKind("Read"))
    assertEquals("read_file", classifier.classifyCapabilityKind("view_workspace_image"))
    assertEquals("read_file", classifier.classifyCapabilityKind("view_workspace_pdf"))
    assertEquals("todo_management", classifier.classifyCapabilityKind("TodoWrite"))
  }
}
