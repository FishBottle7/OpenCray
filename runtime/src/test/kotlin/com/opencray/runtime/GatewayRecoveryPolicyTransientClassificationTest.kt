package com.opencray.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayRecoveryPolicyTransientClassificationTest {
  @Test
  fun inStreamProviderErrorPayloadsAreClassifiedAsNonTransient() {
    assertFalse("PROVIDER_FAILURE".isTransientGatewayFailureCode())
    assertFalse(null.isTransientGatewayFailureCode())
    assertFalse("HTTP_400".isTransientGatewayFailureCode())
    assertFalse("HTTP_401".isTransientGatewayFailureCode())
    assertFalse("PROVIDER_REQUEST_INVALID_TOOL_CALL_ID".isTransientGatewayFailureCode())
    assertFalse("PROVIDER_EMPTY_RESPONSE".isTransientGatewayFailureCode())
  }

  @Test
  fun userCancellationIsClassifiedAsNonTransientAndNeverRetried() {
    assertFalse("PROVIDER_REQUEST_CANCELLED".isTransientGatewayFailureCode())
    assertFalse("provider_request_cancelled".isTransientGatewayFailureCode())
    assertFalse(" PROVIDER_REQUEST_CANCELLED ".isTransientGatewayFailureCode())
  }

  @Test
  fun transportAndServerSideFailuresStayTransient() {
    assertTrue("PROVIDER_TRANSPORT_ERROR".isTransientGatewayFailureCode())
    assertTrue("PROVIDER_CLIENT_EXCEPTION".isTransientGatewayFailureCode())
    assertTrue("HTTP_500".isTransientGatewayFailureCode())
    assertTrue("http_502".isTransientGatewayFailureCode())
    assertTrue(" HTTP_503 ".isTransientGatewayFailureCode())
    assertTrue("HTTP_429".isTransientGatewayFailureCode())
    assertTrue("HTTP_408".isTransientGatewayFailureCode())
  }
}
