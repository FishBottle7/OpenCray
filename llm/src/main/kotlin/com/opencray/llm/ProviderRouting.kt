package com.opencray.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object ProviderRoutingSchemaVersion {
  const val CURRENT: Int = 1
}

private const val DEFAULT_ROUTE_TIMEOUT_MS: Long = 30_000L

@Serializable
enum class FallbackTrigger {
  @SerialName("timeout") TIMEOUT,
  @SerialName("rate_limit_429") RATE_LIMIT_429,
  @SerialName("http_5xx") HTTP_5XX,
  @SerialName("transport_error") TRANSPORT_ERROR,
}

@Serializable
enum class FallbackAction {
  @SerialName("try_next_route") TRY_NEXT_ROUTE,
  @SerialName("terminal_failure") TERMINAL_FAILURE,
}

@Serializable
data class FallbackTriggerPolicy(
  val onTimeout: FallbackAction = FallbackAction.TRY_NEXT_ROUTE,
  val onRateLimit429: FallbackAction = FallbackAction.TRY_NEXT_ROUTE,
  val onHttp5xx: FallbackAction = FallbackAction.TRY_NEXT_ROUTE,
  val onTransportError: FallbackAction = FallbackAction.TRY_NEXT_ROUTE,
) {
  fun actionFor(trigger: FallbackTrigger): FallbackAction = when (trigger) {
    FallbackTrigger.TIMEOUT -> onTimeout
    FallbackTrigger.RATE_LIMIT_429 -> onRateLimit429
    FallbackTrigger.HTTP_5XX -> onHttp5xx
    FallbackTrigger.TRANSPORT_ERROR -> onTransportError
  }

  fun shouldFallback(trigger: FallbackTrigger): Boolean = actionFor(trigger) == FallbackAction.TRY_NEXT_ROUTE
}

@Serializable
data class ProviderRoute(
  val id: String,
  val providerId: String,
  val baseUrl: String? = null,
  val model: String,
  val timeoutMs: Long = DEFAULT_ROUTE_TIMEOUT_MS,
  val metadata: Map<String, String> = emptyMap(),
  val schemaVersion: Int = ProviderRoutingSchemaVersion.CURRENT,
) {
  init {
    require(id.isNotBlank()) { "ProviderRoute id must not be blank." }
    require(providerId.isNotBlank()) { "ProviderRoute providerId must not be blank." }
    require(model.isNotBlank()) { "ProviderRoute model must not be blank." }
    require(timeoutMs > 0) { "ProviderRoute timeoutMs must be > 0." }
    require(baseUrl == null || baseUrl.isHttpOrHttps()) {
      "ProviderRoute baseUrl must start with http:// or https:// when provided."
    }
  }
}

@Serializable
data class RoutingLogMetadata(
  val profileId: String,
  val routeId: String,
  val providerId: String,
  val baseUrl: String? = null,
  val model: String,
  val attemptIndex: Int = 0,
  val fallbackTrigger: FallbackTrigger? = null,
  val isFallbackAttempt: Boolean = attemptIndex > 0,
) {
  init {
    require(profileId.isNotBlank()) { "RoutingLogMetadata profileId must not be blank." }
    require(routeId.isNotBlank()) { "RoutingLogMetadata routeId must not be blank." }
    require(providerId.isNotBlank()) { "RoutingLogMetadata providerId must not be blank." }
    require(model.isNotBlank()) { "RoutingLogMetadata model must not be blank." }
    require(attemptIndex >= 0) { "RoutingLogMetadata attemptIndex must be >= 0." }
    require(baseUrl == null || baseUrl.isHttpOrHttps()) {
      "RoutingLogMetadata baseUrl must start with http:// or https:// when provided."
    }
    require(!isFallbackAttempt || attemptIndex > 0) {
      "RoutingLogMetadata fallback attempts must use attemptIndex > 0."
    }
  }
}

@Serializable
data class ModelProfile(
  val id: String,
  val displayName: String,
  val primaryRouteId: String,
  val fallbackRouteIds: List<String> = emptyList(),
  val routes: List<ProviderRoute>,
  val fallbackPolicy: FallbackTriggerPolicy = FallbackTriggerPolicy(),
  val metadata: Map<String, String> = emptyMap(),
  val schemaVersion: Int = ProviderRoutingSchemaVersion.CURRENT,
) {
  init {
    require(id.isNotBlank()) { "ModelProfile id must not be blank." }
    require(displayName.isNotBlank()) { "ModelProfile displayName must not be blank." }
    require(primaryRouteId.isNotBlank()) { "ModelProfile primaryRouteId must not be blank." }
    require(routes.isNotEmpty()) { "ModelProfile routes must not be empty." }

    val routeIds = routes.map { it.id }
    require(routeIds.distinct().size == routeIds.size) {
      "ModelProfile route ids must be unique."
    }
    require(primaryRouteId in routeIds) {
      "ModelProfile primaryRouteId must reference a declared route."
    }
    require(fallbackRouteIds.distinct().size == fallbackRouteIds.size) {
      "ModelProfile fallbackRouteIds must be unique."
    }
    require(primaryRouteId !in fallbackRouteIds) {
      "ModelProfile fallbackRouteIds must not repeat the primary route."
    }
    require(fallbackRouteIds.all { it in routeIds }) {
      "ModelProfile fallbackRouteIds must reference declared routes only."
    }
  }

  val orderedRouteIds: List<String>
    get() = listOf(primaryRouteId) + fallbackRouteIds

  fun orderedRoutes(): List<ProviderRoute> = orderedRouteIds.map { routeId -> routeById(routeId) }

  fun nextRoute(afterRouteId: String, trigger: FallbackTrigger): ProviderRoute? {
    require(afterRouteId in orderedRouteIds) {
      "ModelProfile nextRoute requires a route id from this profile."
    }
    if (!fallbackPolicy.shouldFallback(trigger)) return null

    val nextIndex = orderedRouteIds.indexOf(afterRouteId) + 1
    return orderedRouteIds.getOrNull(nextIndex)?.let(::routeById)
  }

  fun loggingMetadata(
    routeId: String,
    attemptIndex: Int = 0,
    fallbackTrigger: FallbackTrigger? = null,
  ): RoutingLogMetadata {
    val route = routeById(routeId)
    return RoutingLogMetadata(
      profileId = id,
      routeId = route.id,
      providerId = route.providerId,
      baseUrl = route.baseUrl,
      model = route.model,
      attemptIndex = attemptIndex,
      fallbackTrigger = fallbackTrigger,
    )
  }

  private fun routeById(routeId: String): ProviderRoute = routes.first { it.id == routeId }
}

@Serializable
data class ProviderRouting(
  val activeProfileId: String,
  val profiles: List<ModelProfile>,
  val schemaVersion: Int = ProviderRoutingSchemaVersion.CURRENT,
) {
  init {
    require(activeProfileId.isNotBlank()) { "ProviderRouting activeProfileId must not be blank." }
    require(profiles.isNotEmpty()) { "ProviderRouting profiles must not be empty." }

    val profileIds = profiles.map { it.id }
    require(profileIds.distinct().size == profileIds.size) {
      "ProviderRouting profile ids must be unique."
    }
    require(activeProfileId in profileIds) {
      "ProviderRouting activeProfileId must reference a declared profile."
    }
  }

  fun activeProfile(): ModelProfile = profile(activeProfileId)

  fun switchProfile(profileId: String): ProviderRouting {
    require(profiles.any { it.id == profileId }) {
      "ProviderRouting cannot switch to undeclared profile '$profileId'."
    }
    return copy(activeProfileId = profileId)
  }

  fun profile(profileId: String): ModelProfile = profiles.first { it.id == profileId }
}

private fun String.isHttpOrHttps(): Boolean = startsWith("http://") || startsWith("https://")
