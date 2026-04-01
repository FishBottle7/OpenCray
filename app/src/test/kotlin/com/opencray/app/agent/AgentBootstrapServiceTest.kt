package com.opencray.app.agent

import com.opencray.persistence.model.ChatTranscriptRole
import com.opencray.persistence.store.file.JsonFileChatWorkspaceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentBootstrapServiceTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun createAgentBuildsIsolatedPrivateSoulConfigAndSeedSession() {
    val root = temporaryFolder.newFolder("bootstrap-agent-root").toPath()
    val pathResolver = AgentPathResolver(root)
    var now = 500L
    val registryStore = AgentRegistryStore(
      directory = pathResolver.registryDirectory().toFile(),
      nowEpochMs = { now++ },
    )
    val service = AgentBootstrapService(
      pathResolver = pathResolver,
      registryStore = registryStore,
      idFactory = AgentIdFactory(
        nowEpochMs = { 900L },
        randomToken = { "seedtoken" },
      ),
      nowEpochMs = { 700L },
    )

    val result = service.createAgent(
      AgentCreateRequest(
        displayName = "Aster",
        presetName = "WARM",
        plasticity = "high",
        callsYou = "Fish",
        addressStyle = "friendly",
        mode = "full",
        voiceSummary = "calm concrete",
        verbosity = "detailed",
        relationshipStyle = "supportive",
        riskTolerance = "assertive",
        toolUseBias = "execution_first",
        baseDescription = "Calm, concrete, and keeps momentum.",
        collaborationGuidance = "Keep reasoning concrete and implementation-first.",
        escalationRules = "Ask before risky workspace changes.",
        forbiddenBehaviors = "Do not fabricate local evidence.",
        llm = AgentLlmConfig(
          provider = "openai",
          protocol = "openai",
          baseUrl = "https://api.openai.com/v1",
          apiKey = "sk-test",
          model = "gpt-4o-mini",
          reasoningEffort = "medium",
        ),
        avatar = AgentAvatarConfig(
          source = "custom",
          settingsAssetId = "settings-portrait",
        ),
        imageReferences = listOf(
          AgentImageReferenceConfig(
            referenceId = "front-portrait",
            label = "Front portrait",
            settingsAssetId = "settings-portrait",
          ),
        ),
      ),
    )

    val configStore = AgentConfigStore(pathResolver)
    val soulStore = AgentSoulProfileStore(pathResolver)
    val chatWorkspace = JsonFileChatWorkspaceStore(result.storagePaths.chatLocalStateRoot.toFile()).load()
    val soulProfile = requireNotNull(soulStore.loadSoulProfile(result.descriptor.agentId))
    val config = requireNotNull(configStore.load(result.descriptor.agentId))

    assertEquals(result.descriptor.agentId, registryStore.activeAgentId())
    assertTrue(result.storagePaths.privateSoulFile.startsWith(result.storagePaths.privateRoot))
    assertFalse(result.storagePaths.workspaceRoot.resolve("SOUL.md").toFile().exists())
    assertTrue(result.storagePaths.privateSoulFile.toFile().exists())
    assertTrue(result.storagePaths.privateConfigFile.toFile().exists())
    assertEquals("Aster", result.descriptor.displayName)
    assertEquals("WARM", soulProfile.presetName)
    assertEquals("Aster", soulProfile.customLabel)
    assertEquals("Calm, concrete, and keeps momentum.", soulProfile.customGuidance)
    assertEquals("Fish", soulProfile.extensions["preferred_naming"])
    assertEquals("friendly", soulProfile.extensions["preferred_address_style"])
    assertEquals("calm concrete", soulProfile.extensions["voice"])
    assertEquals("detailed", soulProfile.extensions["verbosity"])
    assertEquals("high", soulProfile.extensions["plasticity"])
    assertEquals("supportive", soulProfile.extensions["user_relationship_style"])
    assertEquals("assertive", soulProfile.extensions["risk_tolerance"])
    assertEquals("execution_first", soulProfile.extensions["tool_use_bias"])
    assertEquals(
      "Keep reasoning concrete and implementation-first.",
      soulProfile.extensions["collaboration_preferences"],
    )
    assertEquals("Ask before risky workspace changes.", soulProfile.extensions["escalation_rules"])
    assertEquals("Do not fabricate local evidence.", soulProfile.extensions["forbidden_behaviors"])
    assertEquals("Aster", config.displayName)
    assertEquals("full", config.mode)
    assertEquals("openai", config.llm?.provider)
    assertEquals("settings-portrait", config.avatar?.settingsAssetId)
    assertEquals(1, config.imageReferences.size)
    assertNotNull(chatWorkspace)
    assertEquals(result.descriptor.activeSessionId, chatWorkspace?.activeSessionId)
    assertEquals(1, chatWorkspace?.sessions?.size)
    assertEquals(ChatTranscriptRole.SYSTEM, chatWorkspace?.sessions?.single()?.messages?.single()?.role)
  }

  @Test
  fun createAgentRespectsActivateOnCreateFlagWhenRegistryAlreadyHasActiveAgent() {
    val root = temporaryFolder.newFolder("bootstrap-active-selection").toPath()
    val pathResolver = AgentPathResolver(root)
    var token = 0
    val service = AgentBootstrapService(
      pathResolver = pathResolver,
      registryStore = AgentRegistryStore(pathResolver.registryDirectory().toFile()),
      idFactory = AgentIdFactory(
        nowEpochMs = { 1000L },
        randomToken = { "token${token++}" },
      ),
      nowEpochMs = { 2000L },
    )

    val first = service.createAgent(
      AgentCreateRequest(
        displayName = "First",
        presetName = "STEADY",
        plasticity = "low",
      ),
    )
    val second = service.createAgent(
      AgentCreateRequest(
        displayName = "Second",
        presetName = "WARM",
        plasticity = "high",
        activateOnCreate = false,
      ),
    )

    val registryStore = AgentRegistryStore(pathResolver.registryDirectory().toFile())
    assertEquals(first.descriptor.agentId, registryStore.activeAgentId())
    assertTrue(registryStore.loadAgent(second.descriptor.agentId) != null)
  }
}
