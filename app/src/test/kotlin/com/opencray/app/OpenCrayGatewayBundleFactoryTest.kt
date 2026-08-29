package com.opencray.app

import android.content.ContextWrapper
import android.content.Context
import com.opencray.runtime.OpenCrayFinalAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class OpenCrayGatewayBundleFactoryTest {
  @Test
  fun projectionGatewayHostLifecycleDescriptorReusesStableDescriptorWithinEnvironment() {
    val firstEnvironment = OpenCrayRuntimeServiceEnvironment(
      projectionHostLifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
    )
    val secondEnvironment = OpenCrayRuntimeServiceEnvironment(
      projectionHostLifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
    )
    val firstDescriptor = firstEnvironment.projectionHostLifecycleDescriptor
    val secondDescriptor = firstEnvironment.projectionHostLifecycleDescriptor
    val thirdDescriptor = secondEnvironment.projectionHostLifecycleDescriptor

    assertSame(firstDescriptor, secondDescriptor)
    assertEquals(firstDescriptor.hostInstanceId, secondDescriptor.hostInstanceId)
    assertEquals(firstDescriptor.runtimeOwnerId, secondDescriptor.runtimeOwnerId)
    assertEquals(firstDescriptor.runtimeControllerId, secondDescriptor.runtimeControllerId)
    assertNotSame(firstDescriptor, thirdDescriptor)
  }

  @Test
  fun runtimeServiceAccessGatewayDefaultsToEnvironmentScopedInstance() {
    val firstEnvironment = OpenCrayRuntimeServiceEnvironment(
      projectionHostLifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
    )
    val secondEnvironment = OpenCrayRuntimeServiceEnvironment(
      projectionHostLifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
    )

    assertSame(firstEnvironment.runtimeServiceAccessGateway, firstEnvironment.runtimeServiceAccessGateway)
    assertNotSame(firstEnvironment.runtimeServiceAccessGateway, secondEnvironment.runtimeServiceAccessGateway)
  }

  @Test
  fun executionControllerResolverDefaultsToEnvironmentScopedInstance() {
    val firstEnvironment = OpenCrayRuntimeServiceEnvironment(
      projectionHostLifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
    )
    val secondEnvironment = OpenCrayRuntimeServiceEnvironment(
      projectionHostLifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
    )

    assertSame(firstEnvironment.executionControllerResolver, firstEnvironment.executionControllerResolver)
    assertNotSame(firstEnvironment.executionControllerResolver, secondEnvironment.executionControllerResolver)
  }

  @Test
  fun localHostGatewayDefaultsToEnvironmentScopedInstance() {
    val firstEnvironment = OpenCrayRuntimeServiceEnvironment(
      projectionHostLifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
      localHostGatewayProvider = { NoOpLocalHostGateway() },
    )
    val secondEnvironment = OpenCrayRuntimeServiceEnvironment(
      projectionHostLifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
      localHostGatewayProvider = { NoOpLocalHostGateway() },
    )
    val firstContext = RuntimeEnvironmentContext(firstEnvironment)
    val secondContext = RuntimeEnvironmentContext(secondEnvironment)

    val firstResolved = firstEnvironment.localHostGateway(firstContext)
    val firstResolvedAgain = firstEnvironment.localHostGateway(firstContext)
    val secondResolved = secondEnvironment.localHostGateway(secondContext)

    assertSame(firstResolved, firstResolvedAgain)
    assertNotSame(firstResolved, secondResolved)
  }

  @Test
  fun ownerlessContextLookupFailsExplicitly() {
    val failure = runCatching {
      openCrayRuntimeServiceEnvironment(MinimalContext())
    }.exceptionOrNull()

    assertEquals(
      "OpenCray runtime environment requires an application context that implements " +
        "OpenCrayRuntimeServiceEnvironmentOwner or OpenCrayApplication.",
      failure?.message,
    )
  }

  @Test
  fun configurableServiceBackedGatewayBundleFactoryCreatesProjectionBundleLazilyAndCachesIt() {
    val context = MinimalContext()
    val serviceClient = object : OpenCrayRuntimeServiceClient {
      override fun loadSnapshot(): OpenCrayRuntimeServiceClientSnapshot =
        OpenCrayRuntimeServiceClientSnapshot(
          connectionState = RuntimeServiceConnectionState.inProcessFallback(
            serviceStartRequested = true,
            fallbackReason = "test",
          ),
        )

      override fun peekConnectionState(): RuntimeServiceConnectionState =
        RuntimeServiceConnectionState.inProcessFallback(
          serviceStartRequested = true,
          fallbackReason = "test",
        )
    }
    val expectedShellSnapshot = mapOf("surface" to "shell")
    val expectedChatSnapshot = mapOf("surface" to "chat")
    val expectedSkillsSnapshot = mapOf("surface" to "skills")
    val expectedSettingsSnapshot = mapOf("surface" to "settings")
    var capturedClientContext: android.content.Context? = null
    var capturedClientTarget: RuntimeServiceTarget? = null
    var capturedProjectionContext: android.content.Context? = null
    var capturedProjectionClient: OpenCrayRuntimeServiceClient? = null
    var projectionCreateCallCount = 0
    val factory = ConfigurableOpenCrayServiceBackedGatewayBundleFactory(
      runtimeServiceClientProvider = { resolvedContext, resolvedTarget ->
        capturedClientContext = resolvedContext
        capturedClientTarget = resolvedTarget
        serviceClient
      },
      projectionGatewayBundleFactory = OpenCrayProjectionGatewayBundleFactory {
          resolvedContext,
          resolvedServiceClient,
        ->
        projectionCreateCallCount += 1
        capturedProjectionContext = resolvedContext
        capturedProjectionClient = resolvedServiceClient
        OpenCrayProjectionGatewayBundle(
          shellGateway = object : NoOpShellGateway() {
            override fun loadShellSnapshot(): Map<String, Any?> = expectedShellSnapshot

            override fun observeShell(listener: (Map<String, Any?>) -> Unit): () -> Unit = { }
          },
          chatRuntimeGateway = object : NoOpChatRuntimeGateway() {
            override fun loadChatSnapshot(): Map<String, Any?> = expectedChatSnapshot
          },
          skillsGateway = object : NoOpSkillsGateway() {
            override fun loadSkillsSnapshot(
              query: String,
              suggestedLimit: Int,
            ): Map<String, Any?> = expectedSkillsSnapshot
          },
          settingsGateway = object : NoOpSettingsGateway() {
            override fun loadSettingsOverview(): Map<String, Any?> = expectedSettingsSnapshot
          },
        )
      },
    )

    val bundle = factory.create(
      context,
      target = RuntimeServiceTarget.INTERACTIVE,
    )

    assertSame(context, capturedClientContext)
    assertEquals(RuntimeServiceTarget.INTERACTIVE, capturedClientTarget)
    assertEquals(0, projectionCreateCallCount)
    assertNull(capturedProjectionContext)
    assertNull(capturedProjectionClient)

    assertEquals(expectedShellSnapshot, bundle.shellGateway.loadShellSnapshot())
    assertEquals(1, projectionCreateCallCount)
    assertSame(context, capturedProjectionContext)
    assertSame(serviceClient, capturedProjectionClient)
    assertEquals(expectedChatSnapshot, bundle.chatRuntimeGateway.loadChatSnapshot())
    assertEquals(expectedSkillsSnapshot, bundle.skillsGateway.loadSkillsSnapshot())
    assertEquals(expectedSettingsSnapshot, bundle.settingsGateway.loadSettingsOverview())
    assertEquals(1, projectionCreateCallCount)
  }

  @Test
  fun configurableServiceBackedGatewayBundleFactorySkipsProjectionBundleCreationWhenBinderGatewayHandlesRead() {
    val context = MinimalContext()
    val expectedShellSnapshot = mapOf("surface" to "binder-shell")
    val binderShellGateway = object : NoOpShellGateway() {
      override fun loadShellSnapshot(): Map<String, Any?> = expectedShellSnapshot

      override fun observeShell(listener: (Map<String, Any?>) -> Unit): () -> Unit = { }
    }
    val serviceClient = object : OpenCrayRuntimeServiceClient {
      override fun loadSnapshot(): OpenCrayRuntimeServiceClientSnapshot =
        OpenCrayRuntimeServiceClientSnapshot(
          connectionState = RuntimeServiceConnectionState.binderConnected(),
        )

      override fun peekConnectionState(): RuntimeServiceConnectionState =
        RuntimeServiceConnectionState.binderConnected()

      override fun loadShellGateway(): OpenCrayShellGateway = binderShellGateway

      override fun peekShellGateway(): OpenCrayShellGateway = binderShellGateway
    }
    var projectionCreateCallCount = 0
    val factory = ConfigurableOpenCrayServiceBackedGatewayBundleFactory(
      runtimeServiceClientProvider = { _, _ -> serviceClient },
      projectionGatewayBundleFactory = OpenCrayProjectionGatewayBundleFactory { _, _ ->
        projectionCreateCallCount += 1
        OpenCrayProjectionGatewayBundle(
          shellGateway = object : NoOpShellGateway() {
            override fun loadShellSnapshot(): Map<String, Any?> = mapOf("surface" to "fallback-shell")

            override fun observeShell(listener: (Map<String, Any?>) -> Unit): () -> Unit = { }
          },
          chatRuntimeGateway = object : NoOpChatRuntimeGateway() { },
          skillsGateway = object : NoOpSkillsGateway() { },
          settingsGateway = object : NoOpSettingsGateway() { },
        )
      },
    )

    val bundle = factory.create(
      context,
      target = RuntimeServiceTarget.INTERACTIVE,
    )

    assertEquals(expectedShellSnapshot, bundle.shellGateway.loadShellSnapshot())
    assertEquals(0, projectionCreateCallCount)
  }

  @Test
  fun configurableServiceBackedGatewayBundleFactorySkipsProjectionBundleCreationWhenBinderChatGatewayHandlesRead() {
    val context = MinimalContext()
    val expectedChatSnapshot = mapOf("surface" to "binder-chat")
    val binderChatGateway = object : NoOpChatRuntimeGateway() {
      override fun loadChatSnapshot(): Map<String, Any?> = expectedChatSnapshot
    }
    val serviceClient = object : OpenCrayRuntimeServiceClient {
      override fun loadSnapshot(): OpenCrayRuntimeServiceClientSnapshot =
        OpenCrayRuntimeServiceClientSnapshot(
          connectionState = RuntimeServiceConnectionState.binderConnected(),
        )

      override fun peekConnectionState(): RuntimeServiceConnectionState =
        RuntimeServiceConnectionState.binderConnected()

      override fun loadChatRuntimeGateway(): OpenCrayChatRuntimeGateway = binderChatGateway
    }
    var projectionCreateCallCount = 0
    val factory = ConfigurableOpenCrayServiceBackedGatewayBundleFactory(
      runtimeServiceClientProvider = { _, _ -> serviceClient },
      projectionGatewayBundleFactory = OpenCrayProjectionGatewayBundleFactory { _, _ ->
        projectionCreateCallCount += 1
        OpenCrayProjectionGatewayBundle(
          shellGateway = object : NoOpShellGateway() {
            override fun loadShellSnapshot(): Map<String, Any?> = mapOf("surface" to "fallback-shell")

            override fun observeShell(listener: (Map<String, Any?>) -> Unit): () -> Unit = { }
          },
          chatRuntimeGateway = object : NoOpChatRuntimeGateway() {
            override fun loadChatSnapshot(): Map<String, Any?> = mapOf("surface" to "fallback-chat")
          },
          skillsGateway = object : NoOpSkillsGateway() { },
          settingsGateway = object : NoOpSettingsGateway() { },
        )
      },
    )

    val bundle = factory.create(
      context,
      target = RuntimeServiceTarget.INTERACTIVE,
    )

    assertEquals(expectedChatSnapshot, bundle.chatRuntimeGateway.loadChatSnapshot())
    assertEquals(0, projectionCreateCallCount)
  }

  @Test
  fun configurableServiceBackedGatewayBundleFactorySkipsProjectionBundleCreationWhenBinderSkillsGatewayHandlesRead() {
    val context = MinimalContext()
    val expectedSkillsSnapshot = mapOf("surface" to "binder-skills")
    val binderSkillsGateway = object : NoOpSkillsGateway() {
      override fun loadSkillsSnapshot(
        query: String,
        suggestedLimit: Int,
      ): Map<String, Any?> = expectedSkillsSnapshot
    }
    val serviceClient = object : OpenCrayRuntimeServiceClient {
      override fun loadSnapshot(): OpenCrayRuntimeServiceClientSnapshot =
        OpenCrayRuntimeServiceClientSnapshot(
          connectionState = RuntimeServiceConnectionState.binderConnected(),
        )

      override fun peekConnectionState(): RuntimeServiceConnectionState =
        RuntimeServiceConnectionState.binderConnected()

      override fun loadSkillsGateway(): OpenCraySkillsGateway = binderSkillsGateway
    }
    var projectionCreateCallCount = 0
    val factory = ConfigurableOpenCrayServiceBackedGatewayBundleFactory(
      runtimeServiceClientProvider = { _, _ -> serviceClient },
      projectionGatewayBundleFactory = OpenCrayProjectionGatewayBundleFactory { _, _ ->
        projectionCreateCallCount += 1
        OpenCrayProjectionGatewayBundle(
          shellGateway = object : NoOpShellGateway() {
            override fun loadShellSnapshot(): Map<String, Any?> = mapOf("surface" to "fallback-shell")

            override fun observeShell(listener: (Map<String, Any?>) -> Unit): () -> Unit = { }
          },
          chatRuntimeGateway = object : NoOpChatRuntimeGateway() { },
          skillsGateway = object : NoOpSkillsGateway() {
            override fun loadSkillsSnapshot(
              query: String,
              suggestedLimit: Int,
            ): Map<String, Any?> = mapOf("surface" to "fallback-skills")
          },
          settingsGateway = object : NoOpSettingsGateway() { },
        )
      },
    )

    val bundle = factory.create(
      context,
      target = RuntimeServiceTarget.INTERACTIVE,
    )

    assertEquals(expectedSkillsSnapshot, bundle.skillsGateway.loadSkillsSnapshot())
    assertEquals(0, projectionCreateCallCount)
  }

  @Test
  fun configurableServiceBackedGatewayBundleFactorySkipsProjectionBundleCreationWhenBinderSettingsGatewayHandlesRead() {
    val context = MinimalContext()
    val expectedSettingsSnapshot = mapOf("surface" to "binder-settings")
    val binderSettingsGateway = object : NoOpSettingsGateway() {
      override fun loadSettingsOverview(): Map<String, Any?> = expectedSettingsSnapshot
    }
    val serviceClient = object : OpenCrayRuntimeServiceClient {
      override fun loadSnapshot(): OpenCrayRuntimeServiceClientSnapshot =
        OpenCrayRuntimeServiceClientSnapshot(
          connectionState = RuntimeServiceConnectionState.binderConnected(),
        )

      override fun peekConnectionState(): RuntimeServiceConnectionState =
        RuntimeServiceConnectionState.binderConnected()

      override fun loadSettingsGateway(): OpenCraySettingsGateway = binderSettingsGateway
    }
    var projectionCreateCallCount = 0
    val factory = ConfigurableOpenCrayServiceBackedGatewayBundleFactory(
      runtimeServiceClientProvider = { _, _ -> serviceClient },
      projectionGatewayBundleFactory = OpenCrayProjectionGatewayBundleFactory { _, _ ->
        projectionCreateCallCount += 1
        OpenCrayProjectionGatewayBundle(
          shellGateway = object : NoOpShellGateway() {
            override fun loadShellSnapshot(): Map<String, Any?> = mapOf("surface" to "fallback-shell")

            override fun observeShell(listener: (Map<String, Any?>) -> Unit): () -> Unit = { }
          },
          chatRuntimeGateway = object : NoOpChatRuntimeGateway() { },
          skillsGateway = object : NoOpSkillsGateway() { },
          settingsGateway = object : NoOpSettingsGateway() {
            override fun loadSettingsOverview(): Map<String, Any?> = mapOf("surface" to "fallback-settings")
          },
        )
      },
    )

    val bundle = factory.create(
      context,
      target = RuntimeServiceTarget.INTERACTIVE,
    )

    assertEquals(expectedSettingsSnapshot, bundle.settingsGateway.loadSettingsOverview())
    assertEquals(0, projectionCreateCallCount)
  }

  @Test
  fun configurableServiceBackedGatewayBundleFactoryDispatchesChatWritesThroughPrimaryClient() {
    val context = MinimalContext()
    val interactiveCommands = mutableListOf<OpenCrayChatWriteCommand>()
    val detachedCommands = mutableListOf<OpenCrayChatWriteCommand>()
    val clients = mapOf(
      RuntimeServiceTarget.INTERACTIVE to recordingServiceClient(interactiveCommands),
      RuntimeServiceTarget.DETACHED_BACKGROUND to recordingServiceClient(detachedCommands),
    )
    val factory = ConfigurableOpenCrayServiceBackedGatewayBundleFactory(
      runtimeServiceClientProvider = { _, target -> clients.getValue(target) },
      projectionGatewayBundleFactory = OpenCrayProjectionGatewayBundleFactory { _, _ ->
        OpenCrayProjectionGatewayBundle(
          shellGateway = object : NoOpShellGateway() {
            override fun loadShellSnapshot(): Map<String, Any?> = emptyMap()

            override fun observeShell(listener: (Map<String, Any?>) -> Unit): () -> Unit = { }
          },
          chatRuntimeGateway = object : NoOpChatRuntimeGateway() { },
          skillsGateway = object : NoOpSkillsGateway() { },
          settingsGateway = object : NoOpSettingsGateway() { },
        )
      },
    )

    val bundle = factory.create(
      context,
      target = RuntimeServiceTarget.INTERACTIVE,
    )

    bundle.chatRuntimeGateway.approveChatApproval("task-detached")

    assertEquals(
      listOf(OpenCrayChatWriteCommand.ApproveChatApproval("task-detached")),
      interactiveCommands,
    )
    assertEquals(emptyList<OpenCrayChatWriteCommand>(), detachedCommands)
  }

  @Test
  fun configurableClientGatewayBundleFactoryUsesInjectedFactories() {
    val context = MinimalContext()
    val localGateway = object : NoOpLocalHostGateway() { }
    val shellGateway = object : NoOpShellGateway() {
      override fun loadShellSnapshot(): Map<String, Any?> = mapOf("surface" to "shell")

      override fun observeShell(listener: (Map<String, Any?>) -> Unit): () -> Unit = { }
    }
    val chatRuntimeGateway = object : NoOpChatRuntimeGateway() { }
    val skillsGateway = object : NoOpSkillsGateway() { }
    val settingsGateway = object : NoOpSettingsGateway() { }
    var capturedLocalContext: android.content.Context? = null
    var capturedServiceBackedContext: android.content.Context? = null
    var capturedServiceBackedTarget: RuntimeServiceTarget? = null
    val factory = ConfigurableOpenCrayClientGatewayBundleFactory(
      localHostGatewayProvider = { resolvedContext ->
        capturedLocalContext = resolvedContext
        localGateway
      },
      serviceBackedGatewayBundleFactory =
        OpenCrayServiceBackedGatewayBundleFactory { resolvedContext, resolvedTarget ->
        capturedServiceBackedContext = resolvedContext
        capturedServiceBackedTarget = resolvedTarget
        OpenCrayServiceBackedGatewayBundle(
          shellGateway = shellGateway,
          chatRuntimeGateway = chatRuntimeGateway,
          skillsGateway = skillsGateway,
          settingsGateway = settingsGateway,
        )
        },
    )

    val bundle = factory.create(
      context,
      target = RuntimeServiceTarget.DETACHED_BACKGROUND,
    )

    assertSame(context, capturedLocalContext)
    assertSame(context, capturedServiceBackedContext)
    assertEquals(RuntimeServiceTarget.DETACHED_BACKGROUND, capturedServiceBackedTarget)
    assertSame(localGateway, bundle.localHostGateway)
    assertSame(shellGateway, bundle.shellGateway)
    assertSame(chatRuntimeGateway, bundle.chatRuntimeGateway)
    assertSame(skillsGateway, bundle.skillsGateway)
    assertSame(settingsGateway, bundle.settingsGateway)
  }

  @Test
  fun configurableClientGatewayBundleFactoryCachesBundlePerTarget() {
    val context = MinimalContext()
    var localGatewayCreateCallCount = 0
    val localGateway = object : NoOpLocalHostGateway() { }
    val createdTargets = mutableListOf<RuntimeServiceTarget>()
    val factory = ConfigurableOpenCrayClientGatewayBundleFactory(
      localHostGatewayProvider = {
        localGatewayCreateCallCount += 1
        localGateway
      },
      serviceBackedGatewayBundleFactory =
        OpenCrayServiceBackedGatewayBundleFactory { _, target ->
          createdTargets += target
          OpenCrayServiceBackedGatewayBundle(
            shellGateway = object : NoOpShellGateway() {
              override fun loadShellSnapshot(): Map<String, Any?> = mapOf("target" to target.wireValue)

              override fun observeShell(listener: (Map<String, Any?>) -> Unit): () -> Unit = { }
            },
            chatRuntimeGateway = object : NoOpChatRuntimeGateway() { },
            skillsGateway = object : NoOpSkillsGateway() { },
            settingsGateway = object : NoOpSettingsGateway() { },
          )
        },
    )

    val firstInteractive = factory.create(context, RuntimeServiceTarget.INTERACTIVE)
    val secondInteractive = factory.create(context, RuntimeServiceTarget.INTERACTIVE)
    val detached = factory.create(context, RuntimeServiceTarget.DETACHED_BACKGROUND)

    assertSame(firstInteractive, secondInteractive)
    assertSame(localGateway, firstInteractive.localHostGateway)
    assertSame(localGateway, detached.localHostGateway)
    assertEquals(1, localGatewayCreateCallCount)
    assertEquals(
      listOf(RuntimeServiceTarget.INTERACTIVE, RuntimeServiceTarget.DETACHED_BACKGROUND),
      createdTargets,
    )
  }

  @Test
  fun environmentClientGatewayBundleFactoryUsesEnvironmentLocalHostGatewayByDefault() {
    val localGateway = object : NoOpLocalHostGateway() { }
    val environment = OpenCrayRuntimeServiceEnvironment(
      projectionHostLifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
      localHostGatewayProvider = { localGateway },
      serviceBackedGatewayBundleFactoryProvider = {
        OpenCrayServiceBackedGatewayBundleFactory { _, _ ->
          OpenCrayServiceBackedGatewayBundle(
            shellGateway = object : NoOpShellGateway() {
              override fun loadShellSnapshot(): Map<String, Any?> = emptyMap()

              override fun observeShell(listener: (Map<String, Any?>) -> Unit): () -> Unit = { }
            },
            chatRuntimeGateway = object : NoOpChatRuntimeGateway() { },
            skillsGateway = object : NoOpSkillsGateway() { },
            settingsGateway = object : NoOpSettingsGateway() { },
          )
        }
      },
    )
    val context = RuntimeEnvironmentContext(environment)
    val factory = environment.clientGatewayBundleFactory

    val interactive = factory.create(context, RuntimeServiceTarget.INTERACTIVE)
    val detached = factory.create(context, RuntimeServiceTarget.DETACHED_BACKGROUND)

    assertSame(localGateway, interactive.localHostGateway)
    assertSame(localGateway, detached.localHostGateway)
  }

  @Test
  fun environmentServiceBackedGatewayBundleFactoryUsesEnvironmentRuntimeServiceAccessGatewayByDefault() {
    val serviceClient = object : OpenCrayRuntimeServiceClient {
      override fun loadSnapshot(): OpenCrayRuntimeServiceClientSnapshot =
        OpenCrayRuntimeServiceClientSnapshot(
          connectionState = RuntimeServiceConnectionState.inProcessFallback(
            serviceStartRequested = true,
            fallbackReason = "test",
          ),
        )

      override fun peekConnectionState(): RuntimeServiceConnectionState =
        RuntimeServiceConnectionState.inProcessFallback(
          serviceStartRequested = true,
          fallbackReason = "test",
        )
    }
    var capturedTarget: RuntimeServiceTarget? = null
    var capturedProjectionClient: OpenCrayRuntimeServiceClient? = null
    val environment = OpenCrayRuntimeServiceEnvironment(
      projectionHostLifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
      runtimeServiceAccessGateway = object : RuntimeServiceAccessGateway {
        override fun ensureClient(
          context: Context,
          target: RuntimeServiceTarget,
        ): OpenCrayRuntimeServiceClient {
          capturedTarget = target
          return serviceClient
        }

        override fun startScheduledTask(
          context: Context,
          command: ScheduledTaskWakeCommand,
          target: RuntimeServiceTarget,
        ): Boolean = error("Unexpected scheduled task wake.")

        override fun repairSchedules(
          context: Context,
          repairReason: String,
          target: RuntimeServiceTarget,
        ): Boolean = error("Unexpected schedule repair.")

        override fun resumeInterruptedRuns(
          context: Context,
          repairReason: String,
          target: RuntimeServiceTarget,
        ): Boolean = error("Unexpected interrupted-run repair.")

        override fun approvalActionPendingIntent(
          context: Context,
          action: String,
          sessionId: String,
          taskId: String,
          runId: String,
          executionId: String?,
          executionOrdinal: Int?,
          requestCode: Int,
          target: RuntimeServiceTarget,
        ) = error("Unexpected approval pending intent.")
      },
      projectionGatewayBundleFactoryProvider = {
        OpenCrayProjectionGatewayBundleFactory { _, resolvedClient ->
          capturedProjectionClient = resolvedClient
          OpenCrayProjectionGatewayBundle(
            shellGateway = object : NoOpShellGateway() {
              override fun loadShellSnapshot(): Map<String, Any?> = emptyMap()

              override fun observeShell(listener: (Map<String, Any?>) -> Unit): () -> Unit = { }
            },
            chatRuntimeGateway = object : NoOpChatRuntimeGateway() { },
            skillsGateway = object : NoOpSkillsGateway() { },
            settingsGateway = object : NoOpSettingsGateway() { },
          )
        }
      },
    )
    val context = RuntimeEnvironmentContext(environment)
    val factory = environment.serviceBackedGatewayBundleFactory

    val bundle = factory.create(context, RuntimeServiceTarget.DETACHED_BACKGROUND)
    bundle.shellGateway.loadShellSnapshot()

    assertSame(serviceClient, capturedProjectionClient)
    assertEquals(RuntimeServiceTarget.DETACHED_BACKGROUND, capturedTarget)
  }

  private class MinimalContext : ContextWrapper(null) {
    override fun getApplicationContext(): android.content.Context = this
  }

  private class RuntimeEnvironmentContext(
    override val openCrayRuntimeServiceEnvironment: OpenCrayRuntimeServiceEnvironment,
  ) : ContextWrapper(null), OpenCrayRuntimeServiceEnvironmentOwner {
    override fun getApplicationContext(): android.content.Context = this
  }

  private fun recordingServiceClient(
    commands: MutableList<OpenCrayChatWriteCommand>,
  ): OpenCrayRuntimeServiceClient = object : OpenCrayRuntimeServiceClient {
    override fun loadSnapshot(): OpenCrayRuntimeServiceClientSnapshot =
      OpenCrayRuntimeServiceClientSnapshot(
        connectionState = RuntimeServiceConnectionState.binderConnected(),
      )

    override fun peekConnectionState(): RuntimeServiceConnectionState =
      RuntimeServiceConnectionState.binderConnected()

    override fun dispatchChatWriteCommand(
      command: OpenCrayChatWriteCommand,
    ): OpenCrayChatWriteDispatchResult {
      commands += command
      return OpenCrayChatWriteDispatchResult.Completed
    }
  }

  private open class NoOpLocalHostGateway : OpenCrayLocalHostGateway {
    override fun loadFilesSnapshot(): Map<String, Any?> = emptyMap()

    override fun loadWorkspaceImagePreview(relativePath: String): Map<String, Any?> = emptyMap()

    override fun loadWorkspaceTextPreview(relativePath: String): Map<String, Any?> = emptyMap()

    override fun loadWorkspaceVoicePlaybackSource(relativePath: String): Map<String, Any?> =
      emptyMap()

    override fun loadWorkspaceTextDocument(relativePath: String): Map<String, Any?> = emptyMap()

    override fun openWorkspaceEntry(relativePath: String) = Unit

    override fun openExternalUri(uri: String) = Unit

    override fun copyRichTextToClipboard(plainText: String, htmlText: String?) = Unit

    override fun createWorkspaceFolder(parentRelativePath: String, name: String): Map<String, Any?> =
      emptyMap()

    override fun createWorkspaceTextFile(parentRelativePath: String, name: String): Map<String, Any?> =
      emptyMap()

    override fun renameWorkspaceEntry(
      targetRelativePath: String,
      newName: String,
    ): Map<String, Any?> = emptyMap()

    override fun deleteWorkspaceEntries(relativePaths: List<String>): Map<String, Any?> = emptyMap()

    override fun saveWorkspaceTextDocument(
      targetRelativePath: String,
      content: String,
    ): Map<String, Any?> = emptyMap()

    override fun pasteWorkspaceEntries(
      sourceRelativePaths: List<String>,
      destinationRelativePath: String,
      move: Boolean,
    ): Map<String, Any?> = emptyMap()

    override fun shareWorkspaceEntries(relativePaths: List<String>) = Unit

    override fun saveWorkspaceMediaAttachment(relativePath: String, kind: String): Map<String, Any?> =
      emptyMap()

    override fun showNativeToast(message: String) = Unit

    override fun importDraftChatAttachments(
      requestedKind: String,
      uriStrings: List<String>,
    ): List<Map<String, Any?>> = emptyList()

    override fun probeTwinImportSource(filePath: String): Map<String, Any?> = emptyMap()
  }

  private open class NoOpShellGateway : OpenCrayShellGateway {
    override fun loadShellSnapshot(): Map<String, Any?> = emptyMap()

    override fun observeShell(listener: (Map<String, Any?>) -> Unit): () -> Unit = { }

    override fun saveShellDestination(
      selectedTab: String,
      settingsSubpage: String?,
    ) = Unit
  }

  private open class NoOpChatRuntimeGateway : OpenCrayChatRuntimeGateway {
    override fun loadChatSnapshot(): Map<String, Any?> = emptyMap()

    override fun observeChat(listener: (Map<String, Any?>) -> Unit): () -> Unit = { }

    override fun loadChatRuntimeSnapshot(): Map<String, Any?> = emptyMap()

    override fun observeLiveAssistantDraftEvents(listener: (Map<String, Any?>) -> Unit): () -> Unit = { }

    override fun loadChatRunSnapshot(runId: String): Map<String, Any?>? = null

    override fun waitForChatRun(runId: String, timeoutMs: Long): Map<String, Any?>? = null

    override fun observeChatRuntime(listener: (Map<String, Any?>) -> Unit): () -> Unit = { }

    override fun refreshSandboxSessionInfo() = Unit

    override fun loadMemoryDebugSnapshot(): Map<String, Any?> = emptyMap()

    override fun loadMemoryDebugLinksSnapshot(): Map<String, Any?> = emptyMap()

    override fun loadSoulDebugSnapshot(): Map<String, Any?> = emptyMap()

    override fun searchMemoryDebug(query: String, maxResults: Int, minScore: Int): Map<String, Any?> =
      emptyMap()

    override fun getMemoryDebugSlice(path: String, fromLine: Int?, lines: Int): Map<String, Any?> =
      emptyMap()

    override fun applyMemoryDebugAction(recordId: String, actionId: String): Map<String, Any?> =
      emptyMap()

    override fun createChatSession() = Unit

    override fun copyChatSession(sessionId: String) = Unit

    override fun deleteChatSession(sessionId: String) = Unit

    override fun selectChatSession(sessionId: String) = Unit

    override fun branchChatSessionFromMessage(sessionId: String, messageId: String) = Unit

    override fun deleteChatMessage(sessionId: String, messageId: String) = Unit

    override fun recallChatMessage(sessionId: String, messageId: String) = Unit

    override fun submitChatMessage(
      text: String,
      attachments: List<OpenCrayFinalAttachment>,
    ): Map<String, Any?>? = null

    override fun approveChatApproval(taskIdOrRunId: String) = Unit

    override fun approveChatApprovalForSession(taskIdOrRunId: String) = Unit

    override fun approveChatApprovalAsBatch(taskIdOrRunId: String) = Unit

    override fun rejectChatApproval(taskIdOrRunId: String) = Unit

    override fun interruptChatRun(taskIdOrRunId: String) = Unit

    override fun retryChatRun(taskIdOrRunId: String) = Unit
  }

  private open class NoOpSkillsGateway : OpenCraySkillsGateway {
    override fun loadSkillsSnapshot(query: String, suggestedLimit: Int): Map<String, Any?> = emptyMap()

    override fun observeSkills(listener: (Map<String, Any?>) -> Unit): () -> Unit = { }

    override fun setSkillEnabled(skillId: String, enabled: Boolean) = Unit

    override fun installSuggestedSkill(skillId: String): String = ""

    override fun installSkillSource(sourceRef: String, selectedSkillName: String): String = ""

    override fun installSkillSourceBatch(sourceRef: String, selectedSkillNames: List<String>): String =
      ""

    override fun inspectSkillSource(sourceRef: String): Map<String, Any?> = emptyMap()

    override fun deleteInstalledSkill(skillId: String): String = ""

    override fun refreshSkills(): String = ""

    override fun checkInstalledSkillUpdates(skillId: String): String = ""

    override fun updateInstalledSkill(skillId: String): String = ""

    override fun loadSkillInstructions(skillId: String): Map<String, Any?> = emptyMap()

    override fun loadSuggestedSkillInstructions(
      sourceRef: String,
      selectedSkillName: String,
    ): Map<String, Any?> = emptyMap()

    override fun activateSkillsInstallSource(sourceId: String): String = ""
  }

  private open class NoOpSettingsGateway : OpenCraySettingsGateway {
    override fun loadSettingsOverview(): Map<String, Any?> = emptyMap()

    override fun observeSettingsOverview(listener: (Map<String, Any?>) -> Unit): () -> Unit = { }

    override fun loadSettingsDetail(routeIdRaw: String): Map<String, Any?> = emptyMap()

    override fun loadNotificationSettings(): Map<String, Any?> = emptyMap()

    override fun saveNotificationSettings(payload: Map<String, Any?>): Map<String, Any?> = emptyMap()

    override fun loadStrongBackgroundSnapshot(): Map<String, Any?> = emptyMap()

    override fun performStrongBackgroundAction(actionId: String): Map<String, Any?> = emptyMap()

    override fun loadNetworkSearchConfig(): Map<String, Any?> = emptyMap()

    override fun saveNetworkSearchConfig(slots: List<Map<String, Any?>>): Map<String, Any?> =
      emptyMap()

    override fun loadMediaSpeechConfig(): Map<String, Any?> = emptyMap()

    override fun saveMediaSpeechConfig(payload: Map<String, Any?>): Map<String, Any?> = emptyMap()

    override fun loadSandboxSettings(): Map<String, Any?> = emptyMap()

    override fun saveSandboxSettings(payload: Map<String, Any?>): Map<String, Any?> = emptyMap()

    override fun loadLlmConfig(): Map<String, Any?> = emptyMap()

    override fun saveLlmConfig(
      enabled: Boolean,
      streamingEnabled: Boolean?,
      providerMode: String,
      providerId: String,
      selectedProviderOptionId: String,
      protocol: String,
      providerName: String,
      providerNotes: String,
      baseUrl: String,
      apiKey: String,
      model: String,
      reasoningEffort: String,
      systemPrompt: String,
      openAiPromptCacheKeyStrategy: String?,
      openAiPromptCacheRetention: String?,
      anthropicPromptCachingEnabled: Boolean?,
      anthropicPromptCacheTtl: String?,
      contextBudgetPreset: String?,
      contextBudgetReservedOutputTokens: Int?,
      contextBudgetSafetyMarginTokens: Int?,
      contextBudgetEffectiveInputPercent: Double?,
      selectedOnDeviceModelId: String,
      onDeviceMaxContextWindow: Int,
      onDeviceMaxTokens: Int,
      onDeviceTopK: Int,
      onDeviceTopP: Double,
      onDeviceTemperature: Double,
      onDeviceAccelerator: String,
      onDeviceThinkingEnabled: Boolean,
      onDeviceLiteModeEnabled: Boolean,
      contextWindowTokensOverride: Int?,
    ): Map<String, Any?> = emptyMap()

    override fun saveCustomLlmProvider(
      selectedProviderOptionId: String,
      streamingEnabled: Boolean?,
      protocol: String,
      providerName: String,
      providerNotes: String,
      baseUrl: String,
      apiKey: String,
      model: String,
      reasoningEffort: String,
      systemPrompt: String,
      openAiPromptCacheKeyStrategy: String?,
      openAiPromptCacheRetention: String?,
      anthropicPromptCachingEnabled: Boolean?,
      anthropicPromptCacheTtl: String?,
      contextBudgetPreset: String?,
      contextBudgetReservedOutputTokens: Int?,
      contextBudgetSafetyMarginTokens: Int?,
      contextBudgetEffectiveInputPercent: Double?,
      contextWindowTokensOverride: Int?,
    ): Map<String, Any?> = emptyMap()

    override fun validateLlmConfig(
      providerId: String,
      protocol: String,
      baseUrl: String,
      apiKey: String,
      model: String,
      reasoningEffort: String,
      contextWindowTokensOverride: Int?,
    ): Map<String, Any?> = emptyMap()

    override fun downloadOnDeviceLlmModel(modelId: String): Map<String, Any?> = emptyMap()

    override fun cancelOnDeviceLlmModelDownload(modelId: String): Map<String, Any?> = emptyMap()

    override fun deleteOnDeviceLlmModel(modelId: String): Map<String, Any?> = emptyMap()

    override fun loadPersonalizationConfig(): Map<String, Any?> = emptyMap()

    override fun savePersonalizationConfig(
      presetId: String,
      customLabel: String,
      customGuidance: String,
    ): Map<String, Any?> = emptyMap()

    override fun setAppLanguage(languageId: String): Map<String, Any?> = emptyMap()

    override fun runPersonalizationReset(scopeId: String): Map<String, Any?> = emptyMap()

    override fun loadMcpSettings(): Map<String, Any?> = emptyMap()

    override fun setMcpMasterEnabled(enabled: Boolean): Map<String, Any?> = emptyMap()

    override fun setMcpServerEnabled(serverId: String, enabled: Boolean): Map<String, Any?> =
      emptyMap()

    override fun loadSafetySettings(): Map<String, Any?> = emptyMap()

    override fun saveSafetySettings(
      automationModeId: String,
      rollbackJournalEnabled: Boolean,
      maxFilesPerBatch: Int,
      maxAgentTurns: Int,
      maxToolCalls: Int,
      undoWindowHours: Int,
      fileChangesPolicyId: String,
      fileDeletesPolicyId: String,
      shellCommandsPolicyId: String,
      externalAccessModeId: String,
      photoLibraryEnabled: Boolean,
      downloadsEnabled: Boolean,
      documentsEnabled: Boolean,
      recordingsEnabled: Boolean,
      workspaceAccessProfileId: String,
      readOnlyOutsideWorkspace: Boolean,
      liveContextModeId: String,
      memoryToolsEnabled: Boolean,
      subAgentContextDefaultModeId: String?,
      subAgentContextProfileOverrides: Map<String, String>,
    ): Map<String, Any?> = emptyMap()
  }
}
