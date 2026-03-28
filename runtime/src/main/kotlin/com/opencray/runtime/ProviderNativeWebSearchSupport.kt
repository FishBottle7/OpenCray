package com.opencray.runtime

object ProviderNativeWebSearchSupport {
  const val APPROVAL_KIND: String = "provider_native_web_search"
  const val RESUME_TOOL_NAME: String = "ProviderNativeWebSearch"
  const val LLM_METADATA_RUN_APPROVED: String = "_host.nativeWebSearchRunApproved"
  const val LLM_METADATA_SESSION_APPROVED: String = "_host.nativeWebSearchSessionApproved"
  const val METADATA_APPROVAL_KIND: String = "approvalKind"
  const val METADATA_SUPPORTS_SESSION_APPROVAL: String = "supportsSessionApproval"
  const val RESULT_METADATA_PROVIDER_MANAGED: String = "providerManaged"
  const val RESULT_METADATA_OPERATION: String = "providerManagedOperation"
  const val RESULT_METADATA_STATUS: String = "providerManagedStatus"
}
