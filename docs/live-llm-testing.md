# Live LLM Test Config

OpenCray's app-side LLM settings are normally stored in Android `SharedPreferences`, so there is no repo-tracked plaintext config file for JVM tests.

For local live-model verification, the test suite now supports an ignored local file at:

`D:\codes\MobileProjects\OpenCray\.opencray\live-llm-test-config.json`

`.opencray/` is already ignored by Git, so secrets written there will not be committed.

Example payload:

```json
{
  "protocol": "openai",
  "baseUrl": "https://api.openai.com/v1",
  "apiKey": "replace-with-your-local-key",
  "model": "gpt-4.1-mini",
  "reasoningEffort": "medium"
}
```

You can also override the path for a single test run with:

- JVM property: `-Dopencray.liveLlmConfig=D:\path\to\config.json`
- Environment variable: `OPENCRAY_LIVE_LLM_CONFIG=D:\path\to\config.json`

Targeted smoke test command:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.opencray.app.LiveLlmInterpreterSmokeTest"
```

End-to-end stewardship smoke test command:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.opencray.app.LiveMemoryStewardshipServiceSmokeTest"
```

Cross-session write + recall / soul-overlay smoke test command:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.opencray.app.LiveCrossSessionMemoryFlowSmokeTest"
```

Current live cross-session coverage now includes:

- durable workspace memory write + recall
- preferred-name carryover and explicit replacement across sessions
- explicit preferred-name invalidation across sessions, including clearing the projected soul snapshot / overlay
- durable adaptive preference projection into typed interaction-preference state and cross-session soul overlay

Current live coverage for memory stewardship focuses on the bounded actions that exist today:

- duplicate-refresh style maintenance via `refresh_record_with_candidate` semantics
- replacement-style maintenance via `supersede_record_with_candidate` semantics
- compatible-detail merge via `merge_record_with_candidate` semantics
- record-only invalidation / resolution
- mixed-turn maintenance where one old preference is invalidated while an unrelated durable fact stays accepted
- transient candidate dropping
- candidate-driven and record-only maintenance coexisting in one turn

There is still no free-form corpus-level `merge_records` editor in runtime. The current merge path is intentionally narrow: `merge_record_with_candidate` only applies to bounded stewardship review for compatible `project_fact` / `durable_instruction` pairs, and runtime still rejects it when the candidate appears to replace a scalar value instead of safely extending the old record.

The live smoke tests are opt-in:

- If the config file is absent, they skip.
- If the config file exists but is malformed, they fail fast.

Windows / Gradle notes from current verification:

- In this repo's Windows environment, repeated `:app:testDebugUnitTest` runs may intermittently fail because Gradle cannot delete `app/build/test-results/testDebugUnitTest/binary/output.bin` after a prior run.
- Another intermittent failure mode is a locked `app/build/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar`.
- Similar file-lock failures can also hit `runtime/build/test-results/testDebugUnitTest` or `app/build/intermediates/.../R.jar` during repeated no-daemon verification.
- The practical workaround is:
  - stop Gradle daemons with `.\gradlew.bat --stop`
  - wait a few seconds for Java/Kotlin worker processes to exit
  - delete the locked generated directory under `app/build` or `runtime/build/test-results/testDebugUnitTest`
  - if needed, also delete `app/build/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar`
  - rerun the target class with `--no-daemon`
- In practice, running the live smoke classes one-by-one is more reliable than a single Gradle invocation with multiple `--tests` selectors.
