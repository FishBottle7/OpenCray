package com.opencray.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteLlmGatewayTest {

  @Test
  fun runProviderRouteSelectionRespectsActiveProfileSwitchingWithoutRestart() {
    val primaryRoute = route(
      id = "route-openai-primary",
      providerId = "openai",
      model = "gpt-4o-mini",
      baseUrl = "https://openai.example",
    )
    val alternateRoute = route(
      id = "route-anthropic-primary",
      providerId = "anthropic",
      model = "claude-3-5-sonnet",
      baseUrl = "https://anthropic.example",
    )
    val routingStore = InMemoryLiteLlmRoutingSettingsStore(
      ProviderRouting(
        activeProfileId = "profile-openai",
        profiles = listOf(
          profile(
            id = "profile-openai",
            displayName = "OpenAI Primary",
            primaryRoute = primaryRoute,
          ),
          profile(
            id = "profile-anthropic",
            displayName = "Anthropic Primary",
            primaryRoute = alternateRoute,
          ),
        ),
      ),
    )
    val providerClient = RecordingProviderClient(
      resultsByRouteId = mapOf(
        primaryRoute.id to listOf(
          LiteLlmProviderResult.Success(
            outputText = "openai answer",
            completion = LiteLlmStructuredCompletion(
              finalText = "openai answer",
              rawText = "openai answer",
            ),
            finishReason = "stop",
            metadata = mapOf(
              "providerRequestId" to "provider-openai-1",
              "secretToken" to "redacted",
            ),
          ),
        ),
        alternateRoute.id to listOf(
          LiteLlmProviderResult.Success(
            outputText = "anthropic answer",
            finishReason = "end_turn",
            metadata = mapOf("providerRequestId" to "provider-anthropic-1"),
          ),
        ),
      ),
    )
    val fallbackEventLog = InMemoryLiteLlmFallbackEventLog()
    val logger = InMemoryLiteLlmGatewayLogger()
    val gateway = DefaultLiteLlmGateway(
      routingStore = routingStore,
      providerClient = providerClient,
      fallbackEventLog = fallbackEventLog,
      logger = logger,
      clock = IncrementingClock(start = 10_000L)::next,
    )

    val firstRequest = LiteLlmGatewayRequest(
      requestId = "request-primary",
      prompt = "Say hello from the primary profile.",
      systemPrompt = "Stay concise.",
      metadata = mapOf(
        "traceId" to "trace-primary",
        "apiKey" to "should-not-appear",
      ),
      authHeaders = mapOf("Authorization" to "Bearer should-not-appear"),
    )

    val firstResult = gateway.execute(firstRequest)

    assertEquals(1, providerClient.requests.size)
    assertEquals(primaryRoute.id, providerClient.requests[0].route.id)
    assertEquals(firstRequest.requestId, providerClient.requests[0].request.requestId)
    assertEquals("profile-openai", providerClient.requests[0].selection.profileId)
    assertEquals(0, providerClient.requests[0].selection.attemptIndex)
    assertNull(providerClient.requests[0].selection.fallbackTrigger)

    assertEquals(LiteLlmGatewayStatus.SUCCESS, firstResult.status)
    assertEquals(LiteLlmCompletionMode.PRIMARY, firstResult.completionMode)
    assertEquals("openai answer", firstResult.outputText)
    assertEquals("openai answer", firstResult.completion?.finalText)
    assertEquals("provider-openai-1", firstResult.metadata["providerRequestId"])
    assertEquals(primaryRoute.id, firstResult.selectedRoute?.routeId)
    assertEquals(primaryRoute.providerId, firstResult.selectedRoute?.providerId)
    assertEquals(primaryRoute.model, firstResult.selectedRoute?.model)
    assertEquals(1, firstResult.attempts.size)

    val firstAttempt = firstResult.attempts.single()
    assertEquals(firstResult.selectedRoute, firstAttempt.route)
    assertEquals(LiteLlmAttemptOutcome.SUCCESS, firstAttempt.outcome)
    assertEquals("stop", firstAttempt.finishReason)
    assertEquals("providerRequestId", firstAttempt.metadataKeys.single())
    assertTrue(fallbackEventLog.snapshot().isEmpty())

    routingStore.setActiveProfileId("profile-anthropic")

    val secondResult = gateway.execute(
      LiteLlmGatewayRequest(
        requestId = "request-secondary",
        prompt = "Say hello from the alternate profile.",
        metadata = mapOf("traceId" to "trace-secondary"),
      ),
    )

    assertEquals(2, providerClient.requests.size)
    assertEquals(alternateRoute.id, providerClient.requests[1].route.id)
    assertEquals("profile-anthropic", providerClient.requests[1].selection.profileId)
    assertEquals(0, providerClient.requests[1].selection.attemptIndex)
    assertNull(providerClient.requests[1].selection.fallbackTrigger)

    assertEquals(LiteLlmGatewayStatus.SUCCESS, secondResult.status)
    assertEquals(LiteLlmCompletionMode.PRIMARY, secondResult.completionMode)
    assertEquals("anthropic answer", secondResult.outputText)
    assertEquals("provider-anthropic-1", secondResult.metadata["providerRequestId"])
    assertEquals(alternateRoute.id, secondResult.selectedRoute?.routeId)
    assertEquals(alternateRoute.providerId, secondResult.selectedRoute?.providerId)
    assertEquals(alternateRoute.model, secondResult.selectedRoute?.model)
    assertEquals(secondResult.selectedRoute, secondResult.attempts.single().route)
    assertEquals(LiteLlmAttemptOutcome.SUCCESS, secondResult.attempts.single().outcome)
    assertEquals("end_turn", secondResult.attempts.single().finishReason)

    assertEquals(
      listOf(primaryRoute.id, alternateRoute.id),
      logger.requestSnapshot().map { it.route.routeId },
    )
    val requestLogs = logger.requestSnapshot()
    val responseLogs = logger.responseSnapshot()
    assertEquals(listOf("traceId"), requestLogs[0].metadataKeys)
    assertEquals(listOf("traceId"), requestLogs[1].metadataKeys)

    println(
      "TASK12 happy initial_active_profile=${providerClient.requests[0].selection.profileId} " +
        "initial_selected_provider=${firstResult.selectedRoute?.providerId} " +
        "initial_selected_route=${firstResult.selectedRoute?.routeId} " +
        "initial_selected_model=${firstResult.selectedRoute?.model} " +
        "switched_active_profile=${providerClient.requests[1].selection.profileId} " +
        "switched_selected_provider=${secondResult.selectedRoute?.providerId} " +
        "switched_selected_route=${secondResult.selectedRoute?.routeId} " +
        "switched_selected_model=${secondResult.selectedRoute?.model} " +
        "request_log_keys=${requestLogs[1].metadataKeys.joinToString(separator = ",")} " +
        "response_log_keys=${responseLogs[1].metadataKeys.joinToString(separator = ",")}",
    )
  }

  @Test
  fun runRateLimitFallbackAuditsExplicitOutcomeAndRedactsSecretMetadataInLogs() {
    val primaryRoute = route(
      id = "route-primary-429",
      providerId = "openai",
      model = "gpt-4o-mini",
      baseUrl = "https://openai.example",
    )
    val fallbackRoute = route(
      id = "route-fallback-success",
      providerId = "anthropic",
      model = "claude-3-5-sonnet",
      baseUrl = "https://anthropic.example",
    )
    val routingStore = InMemoryLiteLlmRoutingSettingsStore(
      ProviderRouting(
        activeProfileId = "profile-resilient",
        profiles = listOf(
          profile(
            id = "profile-resilient",
            displayName = "Resilient Profile",
            primaryRoute = primaryRoute,
            fallbackRoutes = listOf(fallbackRoute),
          ),
        ),
      ),
    )
    val providerClient = RecordingProviderClient(
      resultsByRouteId = mapOf(
        primaryRoute.id to listOf(
          LiteLlmProviderResult.RateLimited(
            retryAfterMs = 2_000L,
            errorMessage = "Primary provider is rate limited.",
            metadata = mapOf(
              "retryClass" to "burst",
              "apiKey" to "should-not-appear",
              "secretToken" to "should-not-appear",
            ),
          ),
        ),
        fallbackRoute.id to listOf(
          LiteLlmProviderResult.Success(
            outputText = "fallback answer",
            finishReason = "stop",
            metadata = mapOf(
              "providerRequestId" to "provider-fallback-1",
              "authToken" to "should-not-appear",
            ),
          ),
        ),
      ),
    )
    val fallbackEventLog = InMemoryLiteLlmFallbackEventLog()
    val logger = InMemoryLiteLlmGatewayLogger()
    val gateway = DefaultLiteLlmGateway(
      routingStore = routingStore,
      providerClient = providerClient,
      fallbackEventLog = fallbackEventLog,
      logger = logger,
      clock = IncrementingClock(start = 20_000L)::next,
    )

    val result = gateway.execute(
      LiteLlmGatewayRequest(
        requestId = "request-rate-limit",
        prompt = "Handle a rate limited provider.",
        metadata = mapOf(
          "traceId" to "trace-rate-limit",
          "sessionToken" to "should-not-appear",
          "secret" to "should-not-appear",
        ),
        authHeaders = mapOf("Authorization" to "Bearer should-not-appear"),
      ),
    )

    assertEquals(listOf(primaryRoute.id, fallbackRoute.id), providerClient.requests.map { it.route.id })
    assertEquals(FallbackTrigger.RATE_LIMIT_429, providerClient.requests[1].selection.fallbackTrigger)
    assertTrue(providerClient.requests[1].selection.isFallbackAttempt)
    assertEquals(1, providerClient.requests[1].selection.attemptIndex)

    assertEquals(LiteLlmGatewayStatus.SUCCESS, result.status)
    assertEquals(LiteLlmCompletionMode.FALLBACK, result.completionMode)
    assertEquals("fallback answer", result.outputText)
    assertEquals("provider-fallback-1", result.metadata["providerRequestId"])
    assertEquals(fallbackRoute.id, result.selectedRoute?.routeId)
    assertEquals(2, result.attempts.size)

    val primaryAttempt = result.attempts[0]
    assertEquals(primaryRoute.id, primaryAttempt.route.routeId)
    assertEquals(LiteLlmAttemptOutcome.RATE_LIMITED, primaryAttempt.outcome)
    assertEquals(FallbackAction.TRY_NEXT_ROUTE, primaryAttempt.fallbackAction)
    assertEquals("PROVIDER_RATE_LIMIT_429_FALLBACK_APPLIED", primaryAttempt.errorCode)
    assertEquals(listOf("retryAfterMs", "retryClass"), primaryAttempt.metadataKeys)

    val fallbackAttempt = result.attempts[1]
    assertEquals(fallbackRoute.id, fallbackAttempt.route.routeId)
    assertEquals(LiteLlmAttemptOutcome.SUCCESS, fallbackAttempt.outcome)
    assertEquals(result.selectedRoute, fallbackAttempt.route)
    assertEquals("stop", fallbackAttempt.finishReason)
    assertEquals(listOf("providerRequestId"), fallbackAttempt.metadataKeys)

    val fallbackEvent = fallbackEventLog.snapshot().single()
    assertEquals("request-rate-limit", fallbackEvent.requestId)
    assertEquals(primaryRoute.id, fallbackEvent.route.routeId)
    assertEquals(FallbackTrigger.RATE_LIMIT_429, fallbackEvent.trigger)
    assertEquals(FallbackAction.TRY_NEXT_ROUTE, fallbackEvent.action)
    assertEquals(fallbackRoute.id, fallbackEvent.nextRoute?.routeId)

    val requestLogs = logger.requestSnapshot()
    assertEquals(2, requestLogs.size)
    assertEquals(listOf("traceId"), requestLogs[0].metadataKeys)
    assertEquals(listOf("traceId"), requestLogs[1].metadataKeys)

    val responseLogs = logger.responseSnapshot()
    assertEquals(2, responseLogs.size)
    assertEquals(LiteLlmAttemptOutcome.RATE_LIMITED, responseLogs[0].outcome)
    assertEquals(LiteLlmGatewayStatus.RATE_LIMITED.name, responseLogs[0].errorCode)
    assertEquals(listOf("retryAfterMs", "retryClass"), responseLogs[0].metadataKeys)
    assertEquals(LiteLlmAttemptOutcome.SUCCESS, responseLogs[1].outcome)
    assertEquals(listOf("providerRequestId"), responseLogs[1].metadataKeys)
    assertNoSensitiveKeys(requestLogs.flatMap { it.metadataKeys })
    assertNoSensitiveKeys(responseLogs.flatMap { it.metadataKeys })

    println(
      "TASK12 fallback active_profile=${providerClient.requests[0].selection.profileId} " +
        "primary_provider=${primaryAttempt.route.providerId} " +
        "primary_route=${primaryAttempt.route.routeId} " +
        "primary_model=${primaryAttempt.route.model} " +
        "fallback_trigger=${fallbackEvent.trigger} " +
        "fallback_route=${fallbackEvent.nextRoute?.routeId} " +
        "selected_provider=${result.selectedRoute?.providerId} " +
        "selected_route=${result.selectedRoute?.routeId} " +
        "selected_model=${result.selectedRoute?.model} " +
        "request_log_keys=${requestLogs[0].metadataKeys.joinToString(separator = ",")} " +
        "rate_limit_log_keys=${responseLogs[0].metadataKeys.joinToString(separator = ",")} " +
        "success_log_keys=${responseLogs[1].metadataKeys.joinToString(separator = ",")}",
    )
  }

  @Test
  fun runServer5xxPrimaryFailureFallsBackToHealthyRoute() {
    val primaryRoute = route(
      id = "route-primary-5xx",
      providerId = "openai",
      model = "gpt-4o-mini",
      baseUrl = "https://openai.example",
    )
    val fallbackRoute = route(
      id = "route-fallback-healthy",
      providerId = "anthropic",
      model = "claude-3-5-sonnet",
      baseUrl = "https://anthropic.example",
    )
    val routingStore = InMemoryLiteLlmRoutingSettingsStore(
      ProviderRouting(
        activeProfileId = "profile-resilient-5xx",
        profiles = listOf(
          profile(
            id = "profile-resilient-5xx",
            displayName = "Resilient 5xx Profile",
            primaryRoute = primaryRoute,
            fallbackRoutes = listOf(fallbackRoute),
          ),
        ),
      ),
    )
    val providerClient = RecordingProviderClient(
      resultsByRouteId = mapOf(
        primaryRoute.id to listOf(
          LiteLlmProviderResult.Failure(
            errorCode = "HTTP_503",
            errorMessage = "Provider returned HTTP 503.",
          ),
        ),
        fallbackRoute.id to listOf(
          LiteLlmProviderResult.Success(
            outputText = "fallback answer",
            finishReason = "stop",
          ),
        ),
      ),
    )
    val fallbackEventLog = InMemoryLiteLlmFallbackEventLog()
    val gateway = DefaultLiteLlmGateway(
      routingStore = routingStore,
      providerClient = providerClient,
      fallbackEventLog = fallbackEventLog,
      clock = IncrementingClock(start = 30_000L)::next,
    )

    val result = gateway.execute(
      LiteLlmGatewayRequest(
        requestId = "request-server-error",
        prompt = "Handle a server error from the primary provider.",
      ),
    )

    assertEquals(listOf(primaryRoute.id, fallbackRoute.id), providerClient.requests.map { it.route.id })
    assertEquals(FallbackTrigger.HTTP_5XX, providerClient.requests[1].selection.fallbackTrigger)
    assertEquals(1, providerClient.requests[1].selection.attemptIndex)
    assertTrue(providerClient.requests[1].selection.isFallbackAttempt)

    assertEquals(LiteLlmGatewayStatus.SUCCESS, result.status)
    assertEquals(LiteLlmCompletionMode.FALLBACK, result.completionMode)
    assertEquals("fallback answer", result.outputText)
    assertEquals(fallbackRoute.id, result.selectedRoute?.routeId)
    assertEquals(2, result.attempts.size)

    val primaryAttempt = result.attempts[0]
    assertEquals(primaryRoute.id, primaryAttempt.route.routeId)
    assertEquals(LiteLlmAttemptOutcome.FAILED, primaryAttempt.outcome)
    assertEquals(FallbackAction.TRY_NEXT_ROUTE, primaryAttempt.fallbackAction)
    assertEquals("HTTP_503", primaryAttempt.errorCode)

    val fallbackAttempt = result.attempts[1]
    assertEquals(LiteLlmAttemptOutcome.SUCCESS, fallbackAttempt.outcome)

    val fallbackEvent = fallbackEventLog.snapshot().single()
    assertEquals(FallbackTrigger.HTTP_5XX, fallbackEvent.trigger)
    assertEquals(FallbackAction.TRY_NEXT_ROUTE, fallbackEvent.action)
    assertEquals(fallbackRoute.id, fallbackEvent.nextRoute?.routeId)
  }

  @Test
  fun runServer5xxExhaustionKeepsProviderErrorCodeAndTerminalStatus() {
    val primaryRoute = route(
      id = "route-primary-exhausted",
      providerId = "openai",
      model = "gpt-4o-mini",
      baseUrl = "https://openai.example",
    )
    val fallbackRoute = route(
      id = "route-fallback-exhausted",
      providerId = "anthropic",
      model = "claude-3-5-sonnet",
      baseUrl = "https://anthropic.example",
    )
    val routingStore = InMemoryLiteLlmRoutingSettingsStore(
      ProviderRouting(
        activeProfileId = "profile-exhausted",
        profiles = listOf(
          profile(
            id = "profile-exhausted",
            displayName = "Exhausted Profile",
            primaryRoute = primaryRoute,
            fallbackRoutes = listOf(fallbackRoute),
          ),
        ),
      ),
    )
    val providerClient = RecordingProviderClient(
      resultsByRouteId = mapOf(
        primaryRoute.id to listOf(
          LiteLlmProviderResult.Failure(
            errorCode = "HTTP_500",
            errorMessage = "Primary returned HTTP 500.",
          ),
        ),
        fallbackRoute.id to listOf(
          LiteLlmProviderResult.Failure(
            errorCode = "HTTP_502",
            errorMessage = "Fallback returned HTTP 502.",
          ),
        ),
      ),
    )
    val fallbackEventLog = InMemoryLiteLlmFallbackEventLog()
    val gateway = DefaultLiteLlmGateway(
      routingStore = routingStore,
      providerClient = providerClient,
      fallbackEventLog = fallbackEventLog,
      clock = IncrementingClock(start = 40_000L)::next,
    )

    val result = gateway.execute(
      LiteLlmGatewayRequest(
        requestId = "request-server-exhaustion",
        prompt = "Every route is failing.",
      ),
    )

    assertEquals(2, providerClient.requests.size)
    assertEquals(LiteLlmGatewayStatus.FAILED, result.status)
    assertEquals(LiteLlmCompletionMode.TERMINAL, result.completionMode)
    assertEquals("HTTP_502", result.errorCode)
    assertEquals("Fallback returned HTTP 502.", result.errorMessage)
    assertEquals(2, result.attempts.size)
    assertEquals(FallbackAction.TRY_NEXT_ROUTE, result.attempts[0].fallbackAction)
    assertEquals(FallbackAction.TERMINAL_FAILURE, result.attempts[1].fallbackAction)

    val events = fallbackEventLog.snapshot()
    assertEquals(2, events.size)
    assertEquals(FallbackTrigger.HTTP_5XX, events[0].trigger)
    assertEquals(FallbackAction.TRY_NEXT_ROUTE, events[0].action)
    assertEquals(FallbackTrigger.HTTP_5XX, events[1].trigger)
    assertEquals(FallbackAction.TERMINAL_FAILURE, events[1].action)
  }

  @Test
  fun runDeterministic4xxFailureDoesNotSwitchRoutes() {
    val primaryRoute = route(
      id = "route-primary-4xx",
      providerId = "openai",
      model = "gpt-4o-mini",
      baseUrl = "https://openai.example",
    )
    val fallbackRoute = route(
      id = "route-fallback-4xx",
      providerId = "anthropic",
      model = "claude-3-5-sonnet",
      baseUrl = "https://anthropic.example",
    )
    val routingStore = InMemoryLiteLlmRoutingSettingsStore(
      ProviderRouting(
        activeProfileId = "profile-4xx",
        profiles = listOf(
          profile(
            id = "profile-4xx",
            displayName = "Deterministic Profile",
            primaryRoute = primaryRoute,
            fallbackRoutes = listOf(fallbackRoute),
          ),
        ),
      ),
    )
    val providerClient = RecordingProviderClient(
      resultsByRouteId = mapOf(
        primaryRoute.id to listOf(
          LiteLlmProviderResult.Failure(
            errorCode = "HTTP_400",
            errorMessage = "Provider returned HTTP 400.",
          ),
        ),
        fallbackRoute.id to listOf(
          LiteLlmProviderResult.Success(outputText = "should not be reached"),
        ),
      ),
    )
    val fallbackEventLog = InMemoryLiteLlmFallbackEventLog()
    val gateway = DefaultLiteLlmGateway(
      routingStore = routingStore,
      providerClient = providerClient,
      fallbackEventLog = fallbackEventLog,
      clock = IncrementingClock(start = 50_000L)::next,
    )

    val result = gateway.execute(
      LiteLlmGatewayRequest(
        requestId = "request-deterministic-failure",
        prompt = "The request itself is invalid.",
      ),
    )

    assertEquals(listOf(primaryRoute.id), providerClient.requests.map { it.route.id })
    assertEquals(LiteLlmGatewayStatus.FAILED, result.status)
    assertEquals(LiteLlmCompletionMode.TERMINAL, result.completionMode)
    assertEquals("HTTP_400", result.errorCode)
    assertEquals(1, result.attempts.size)
    assertNull(result.attempts.single().fallbackAction)
    assertTrue(fallbackEventLog.snapshot().isEmpty())
  }

  @Test
  fun runTransportErrorPrimaryFailureFallsBackToHealthyRoute() {
    val primaryRoute = route(
      id = "route-primary-transport",
      providerId = "openai",
      model = "gpt-4o-mini",
      baseUrl = "https://openai.example",
    )
    val fallbackRoute = route(
      id = "route-fallback-transport",
      providerId = "anthropic",
      model = "claude-3-5-sonnet",
      baseUrl = "https://anthropic.example",
    )
    val routingStore = InMemoryLiteLlmRoutingSettingsStore(
      ProviderRouting(
        activeProfileId = "profile-transport",
        profiles = listOf(
          profile(
            id = "profile-transport",
            displayName = "Transport Profile",
            primaryRoute = primaryRoute,
            fallbackRoutes = listOf(fallbackRoute),
          ),
        ),
      ),
    )
    val providerClient = RecordingProviderClient(
      resultsByRouteId = mapOf(
        primaryRoute.id to listOf(
          LiteLlmProviderResult.Failure(
            errorCode = "PROVIDER_TRANSPORT_ERROR",
            errorMessage = "Connection reset by peer.",
          ),
        ),
        fallbackRoute.id to listOf(
          LiteLlmProviderResult.Success(
            outputText = "transport fallback answer",
            finishReason = "stop",
          ),
        ),
      ),
    )
    val fallbackEventLog = InMemoryLiteLlmFallbackEventLog()
    val gateway = DefaultLiteLlmGateway(
      routingStore = routingStore,
      providerClient = providerClient,
      fallbackEventLog = fallbackEventLog,
      clock = IncrementingClock(start = 60_000L)::next,
    )

    val result = gateway.execute(
      LiteLlmGatewayRequest(
        requestId = "request-transport-failure",
        prompt = "The transport dropped.",
      ),
    )

    assertEquals(listOf(primaryRoute.id, fallbackRoute.id), providerClient.requests.map { it.route.id })
    assertEquals(FallbackTrigger.TRANSPORT_ERROR, providerClient.requests[1].selection.fallbackTrigger)
    assertEquals(LiteLlmGatewayStatus.SUCCESS, result.status)
    assertEquals(LiteLlmCompletionMode.FALLBACK, result.completionMode)
    assertEquals("transport fallback answer", result.outputText)

    val fallbackEvent = fallbackEventLog.snapshot().single()
    assertEquals(FallbackTrigger.TRANSPORT_ERROR, fallbackEvent.trigger)
    assertEquals(FallbackAction.TRY_NEXT_ROUTE, fallbackEvent.action)
    assertEquals(fallbackRoute.id, fallbackEvent.nextRoute?.routeId)
  }

  @Test
  fun runServer5xxHonorsTerminalFallbackPolicyWithoutSwitchingRoutes() {
    val primaryRoute = route(
      id = "route-primary-policy-terminal",
      providerId = "openai",
      model = "gpt-4o-mini",
      baseUrl = "https://openai.example",
    )
    val fallbackRoute = route(
      id = "route-fallback-policy-terminal",
      providerId = "anthropic",
      model = "claude-3-5-sonnet",
      baseUrl = "https://anthropic.example",
    )
    val routingStore = InMemoryLiteLlmRoutingSettingsStore(
      ProviderRouting(
        activeProfileId = "profile-policy-terminal",
        profiles = listOf(
          profile(
            id = "profile-policy-terminal",
            displayName = "Terminal Policy Profile",
            primaryRoute = primaryRoute,
            fallbackRoutes = listOf(fallbackRoute),
            fallbackPolicy = FallbackTriggerPolicy(
              onHttp5xx = FallbackAction.TERMINAL_FAILURE,
            ),
          ),
        ),
      ),
    )
    val providerClient = RecordingProviderClient(
      resultsByRouteId = mapOf(
        primaryRoute.id to listOf(
          LiteLlmProviderResult.Failure(
            errorCode = "HTTP_503",
            errorMessage = "Provider returned HTTP 503.",
          ),
        ),
        fallbackRoute.id to listOf(
          LiteLlmProviderResult.Success(outputText = "should not be reached"),
        ),
      ),
    )
    val fallbackEventLog = InMemoryLiteLlmFallbackEventLog()
    val gateway = DefaultLiteLlmGateway(
      routingStore = routingStore,
      providerClient = providerClient,
      fallbackEventLog = fallbackEventLog,
      clock = IncrementingClock(start = 70_000L)::next,
    )

    val result = gateway.execute(
      LiteLlmGatewayRequest(
        requestId = "request-policy-terminal",
        prompt = "Policy disallows falling back on 5xx.",
      ),
    )

    assertEquals(listOf(primaryRoute.id), providerClient.requests.map { it.route.id })
    assertEquals(LiteLlmGatewayStatus.FAILED, result.status)
    assertEquals(LiteLlmCompletionMode.TERMINAL, result.completionMode)
    assertEquals("HTTP_503", result.errorCode)
    assertEquals(1, result.attempts.size)
    assertEquals(FallbackAction.TERMINAL_FAILURE, result.attempts.single().fallbackAction)

    val fallbackEvent = fallbackEventLog.snapshot().single()
    assertEquals(FallbackAction.TERMINAL_FAILURE, fallbackEvent.action)
    assertNull(fallbackEvent.nextRoute)
  }

  private fun route(
    id: String,
    providerId: String,
    model: String,
    baseUrl: String,
  ): ProviderRoute = ProviderRoute(
    id = id,
    providerId = providerId,
    baseUrl = baseUrl,
    model = model,
  )

  private fun profile(
    id: String,
    displayName: String,
    primaryRoute: ProviderRoute,
    fallbackRoutes: List<ProviderRoute> = emptyList(),
    fallbackPolicy: FallbackTriggerPolicy = FallbackTriggerPolicy(),
  ): ModelProfile = ModelProfile(
    id = id,
    displayName = displayName,
    primaryRouteId = primaryRoute.id,
    fallbackRouteIds = fallbackRoutes.map { it.id },
    routes = listOf(primaryRoute) + fallbackRoutes,
    fallbackPolicy = fallbackPolicy,
  )

  private fun assertNoSensitiveKeys(keys: List<String>) {
    assertFalse(keys.any { key -> key.contains("auth", ignoreCase = true) })
    assertFalse(keys.any { key -> key.contains("token", ignoreCase = true) })
    assertFalse(keys.any { key -> key.contains("secret", ignoreCase = true) })
    assertFalse(keys.any { key -> key.contains("password", ignoreCase = true) })
    assertFalse(keys.any { key -> key.contains("api-key", ignoreCase = true) })
    assertFalse(keys.any { key -> key.contains("apikey", ignoreCase = true) })
    assertFalse(keys.any { key -> key.equals("key", ignoreCase = true) })
  }

  private class IncrementingClock(
    start: Long,
  ) {
    private var now: Long = start

    fun next(): Long = now++
  }

  private class RecordingProviderClient(
    resultsByRouteId: Map<String, List<LiteLlmProviderResult>>,
  ) : LiteLlmProviderClient {
    private val queuedResults: Map<String, ArrayDeque<LiteLlmProviderResult>> = resultsByRouteId
      .mapValues { (_, results) -> ArrayDeque(results) }

    val requests: MutableList<LiteLlmProviderRequest> = mutableListOf()

    override fun execute(request: LiteLlmProviderRequest): LiteLlmProviderResult {
      requests += request
      val results = queuedResults[request.route.id]
        ?: error("No fake provider result configured for route '${request.route.id}'.")
      return results.removeFirstOrNull()
        ?: error("No fake provider results remaining for route '${request.route.id}'.")
    }
  }
}

// Learnings: The gateway already exposes enough in-memory seams to verify route selection and fallback behavior without mocks or HTTP.
// Issues: This focused test file covers fallback-first 429 handling, while terminal-policy 429 remains a separate edge case.
// Learnings: Stable summary lines make the evidence files show routing and fallback choices without printing any secret-bearing values.
// Issues: Evidence fidelity still depends on Gradle surfacing unit-test stdout even when the Kotlin language server stays stale.
