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
  }

  @Test
  fun classifiesFileAndNetworkToolsConsistently() {
    assertEquals(PolicyToolClass.WRITE_FILE, classifier.classifyPolicyToolClass("Write"))
    assertEquals(PolicyToolClass.WRITE_FILE, classifier.classifyPolicyToolClass("Edit"))
    assertEquals(PolicyToolClass.MOVE_FILE, classifier.classifyPolicyToolClass("workspace_move_file"))
    assertEquals(PolicyToolClass.DELETE_FILE, classifier.classifyPolicyToolClass("workspace_delete_file"))
    assertEquals(PolicyToolClass.NETWORK_ACCESS, classifier.classifyPolicyToolClass("WebFetch"))
    assertEquals(PolicyToolClass.READ_FILE, classifier.classifyPolicyToolClass("Read"))
    assertEquals("write_file", classifier.classifyCapabilityKind("Write"))
    assertEquals("move_file", classifier.classifyCapabilityKind("workspace_move_file"))
    assertEquals("delete_file", classifier.classifyCapabilityKind("workspace_delete_file"))
    assertEquals("network_access", classifier.classifyCapabilityKind("WebFetch"))
    assertEquals("read_file", classifier.classifyCapabilityKind("Read"))
  }
}
