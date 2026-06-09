# Mobile UI Motion Polish Plan

Date: 2026-06-08

## Context

OpenCray's current Flutter shell already follows the mobile layout direction in
`docs/mobile-ui-layout-spec.md`: quiet surfaces, flat hierarchy, weak shadows,
and phone-first single-column layouts. This pass should keep that restraint while
making the app feel more coordinated, modern, and responsive through deliberate
motion.

The implementation branch must be created as an isolated Codex worktree from the
repository's mainline branch. In this repository the mainline branch is named
`master`.

## External Skill References

Skill search found these useful references:

- `mblode/agent-skills@ui-animation`
  https://skills.sh/mblode/agent-skills/ui-animation
- `dylantarre/animation-principles`
  https://skills.sh/dylantarre/animation-principles
- `motion-patterns`
  https://skills.pub/en/skills/affaan-m-everything-claude-code%3A%3Aeverything-claude-code%3A%3Askills-motion-patterns
- Skills design topic index
  https://www.skills.sh/topic/design

Most relevant guidance carried into this plan:

- `ui-animation`: keep motion purposeful, fast, interruptible, mostly on
  transform/opacity, and disable transform-heavy motion for reduced-motion users.
- `animation-principles / accessible-motion`: when users request reduced motion,
  preserve functional state clarity with opacity/color/instant changes instead
  of spatial movement.
- `animation-principles / motion-designer`: use anticipation and staging only
  where it clarifies intent; keep UI personality subtle for productivity
  surfaces.

Local `ui-ux-pro-max` search also highlighted the same baseline constraints:
respect reduced motion, animate only key changes, keep most UI transitions in
the 150-300ms range, use directional easing instead of linear motion, and avoid
continuous decorative animation except for true loading states.

## Motion Principles

1. Motion must explain cause and effect. Expansion expands from its source,
   drawers and pages return in the direction they came from, and controls should
   visually acknowledge the exact action the user performed.
2. Motion must be directional. Left-origin panels leave to the left, bottom
   sheets settle from the bottom, tab changes track the selected tab order, and
   inline details expand vertically rather than popping.
3. Motion must be interruptible and state-driven. Repeated taps, fast navigation,
   and runtime updates must not leave stale animation state or delayed visual
   effects.
4. Motion must be quiet. Avoid applying the same scale/pop effect everywhere.
   Use opacity, small translation, size, and color transitions in combinations
   that match the interaction's geometry.
5. Motion must preserve layout confidence. Avoid animating expensive layout
   properties globally, avoid scroll jumps, and do not create text overlap or
   unstable button/card dimensions.
6. Motion must respect accessibility. Honor Flutter's reduced-motion signals
   through `MediaQuery.disableAnimations`, keep feedback visible without large
   movement, and avoid decorative loops.

## Proposed Motion Language

### Timing Tokens

Add shared motion tokens beside the existing design tokens:

- instant feedback: `90ms` to `120ms`
- micro state change: `140ms` to `180ms`
- panel and inline expansion: `220ms` to `260ms`
- page or tab transition: `260ms` to `320ms`
- exit timing: slightly shorter than entry where practical

### Easing Tokens

Use a small named set rather than ad hoc curves:

- standard enter: ease-out cubic
- standard exit: ease-in cubic
- spatial move: emphasized ease-out cubic
- expand/collapse: ease-in-out cubic with bottom/top alignment as appropriate
- reduced motion: opacity/color only, no large translation or scale

### Visual Coordination

The UI polish should stay within the existing OpenCray tone:

- preserve light background and white surfaces
- add borders or subtle surface contrast before adding shadows
- reserve primary blue for selection, focus, or one primary action
- keep cards flat and calm, with slightly tighter alignment and consistent
  internal spacing

## Detailed Implementation Scope

### 1. Shared Motion Foundation

Files:

- `flutter_app/lib/core/design/opencray_motion.dart` (new)
- `flutter_app/lib/core/design/opencray_widgets.dart`
- `flutter_app/lib/app/opencray_app.dart`
- `flutter_app/lib/app/opencray_app_shell.dart`

Current behavior:

- Motion timings and curves are hardcoded in multiple feature files.
- Some interactions animate with generic opacity/scale while others switch
  instantly.
- Reduced-motion handling exists in a few local places but is not a shared rule.

Target behavior:

- Shared motion tokens define the small set of accepted durations and curves.
- Shared helpers provide directional tab/page transitions and reduced-motion
  fallbacks.
- Feature code reads like intent: page slide, bottom sheet rise, inline reveal,
  selected tab settle.

Implementation details:

- Add `OpenCrayMotion` with:
  - `instant` 90-120ms for press/color feedback
  - `micro` 140-180ms for chips, toggles, active rows
  - `expand` 220-260ms for inline height changes
  - `page` 260-320ms for tab/page movement
  - `pageExit` slightly shorter than entry
  - `enter`, `exit`, `spatial`, and `expandCurve`
- Add `OpenCrayMotion.reduce(context)` using `MediaQuery.disableAnimations` and
  `MediaQuery.accessibleNavigation`.
- Add `openCrayHorizontalPageRoute()` for settings/detail pushes.
- Add `OpenCrayDirectionalIndexedStack` for tab changes while preserving tab
  state.
- Add `OpenCrayDirectionalSwitcher` for settings subpage changes inside the same
  screen.

Acceptance:

- New motion code is centralized in the design layer.
- Existing feature files no longer need ad hoc page/tab transition logic.
- Reduced motion degrades to cross-fade or instant state changes.

### 2. App Shell Tab Navigation

Files:

- `flutter_app/lib/app/opencray_app_shell.dart`
- `flutter_app/lib/core/design/opencray_widgets.dart`

Current behavior:

- Shell body uses a static `IndexedStack`, so tabs change instantly.
- Bottom navigation icon/text color changes instantly, with no spatial feedback.

Target behavior:

- Tab movement follows tab order:
  - Chat -> Skills -> Files -> Settings slides subtly forward.
  - Settings -> Files -> Skills -> Chat slides subtly backward.
- Existing tab state is preserved.
- Bottom nav selection has a calm indicator and color transition, but item size
  never changes.

Implementation details:

- Replace shell `IndexedStack` with `OpenCrayDirectionalIndexedStack`.
- Keep each tab wrapped in its existing `KeyedSubtree`.
- Update `_BottomNavItem` to use:
  - `AnimatedContainer` for a small selected pill/indicator behind icon area
  - `AnimatedDefaultTextStyle` for label color/weight
  - `TweenAnimationBuilder` or `AnimatedScale` only for a tiny selected-icon
    settle if it does not affect layout
- Keep hit target and bottom nav height unchanged.

Acceptance:

- Fast repeated tab taps do not reset pages or leave stale layers.
- No vertical jump in the nav bar.
- Direction is obvious but restrained.

### 3. App-Level Routes

Files:

- `flutter_app/lib/app/opencray_app.dart`

Current behavior:

- `onGenerateRoute` returns default `MaterialPageRoute` for shell/settings
  entry points.

Target behavior:

- Deep-linked or host-started settings detail screens enter from the right and
  back out to the right.
- Shell entry remains visually calm and does not introduce a heavy animation
  when the app first opens.

Implementation details:

- Use `openCrayHorizontalPageRoute()` for settings detail routes.
- Use the same route helper for shell entry routes created from named routes if
  they are navigational, not initial app boot.
- Preserve route settings and current builders.

Acceptance:

- Android back gesture mirrors the route entry direction.
- No business logic or host bridge behavior changes.

### 4. Settings Home and Subpage Navigation

Files:

- `flutter_app/lib/features/settings/settings_feature.dart`
- `flutter_app/lib/features/settings/agent_settings_pages.dart`
- `flutter_app/lib/features/settings/settings_debug_pages.dart`

Current behavior:

- `SettingsFeatureScreen` uses a generic `AnimatedSwitcher` with no directional
  meaning.
- Non-standalone settings entries push default `MaterialPageRoute`.
- Debug and agent nested pages also use default routes.

Target behavior:

- Home -> subpage moves forward from the right.
- Nested parent -> nested child moves forward from the right.
- Back returns toward the right.
- Reduced motion uses a short fade.

Implementation details:

- Track previous settings page and compute direction:
  - home to any detail: forward
  - parent to nested child: forward
  - nested child to parent/home: backward
  - unrelated page replacement: compare `SettingsPage.values` order as fallback
- Replace the generic `AnimatedSwitcher` with `OpenCrayDirectionalSwitcher`.
- Replace settings `Navigator.push(MaterialPageRoute(...))` calls with
  `openCrayHorizontalPageRoute()`.
- For agent editor nested pages, create a small local helper like
  `_pushAgentSubpage<T>(WidgetBuilder builder)` so the same route behavior is
  used consistently.

Acceptance:

- Settings navigation never feels like a center pop.
- Back movement mirrors forward movement.
- Loading/detail page swaps do not flicker or lose content.

### 5. Chat Session Drawer

Files:

- `flutter_app/lib/features/chat/chat_feature_screen.dart`

Current behavior:

- `_SessionsDrawerOverlay` is inserted only when `_state.drawerOpen` is true and
  removed immediately when false, so close has no exit animation.

Target behavior:

- Drawer enters from the left and leaves to the left.
- Scrim fades independently.
- When closed, the overlay no longer intercepts taps.
- Drawer content keeps its current layout and copy.

Implementation details:

- Keep `_SessionsDrawerOverlay` mounted in the chat stack and pass `isOpen`.
- Convert `_SessionsDrawerOverlay` to a stateful or implicitly animated widget:
  - `AnimatedSlide` from `Offset(-1, 0)` to `Offset.zero`
  - `AnimatedOpacity` for scrim and panel
  - `IgnorePointer(ignoring: !isOpen)` to avoid tap capture while closed
  - `AnimatedModalBarrier` or gesture layer opacity tied to open state
- In reduced-motion mode, fade the scrim/panel without lateral motion.
- Keep `_closeDrawer()` state semantics unchanged.

Acceptance:

- The drawer clearly returns to the left on close.
- The toolbar and composer do not shift.
- Back press still closes drawer before app-level back handling.

### 6. Files Preview/Create Dialogs

Files:

- `flutter_app/lib/features/files/files_feature.dart`

Current behavior:

- `_showPreviewDialog()` uses transparent barrier, backdrop blur, fade, and a
  center scale from `0.96`.
- Keyboard padding animates with a local hardcoded duration/curve.

Target behavior:

- Preview/create modal keeps a restrained center modal feel, but uses shared
  timing and reduced-motion handling.
- Dialogs that are not edge-origin surfaces may use a tiny fade/scale, but they
  should not look like bottom sheets.

Implementation details:

- Replace hardcoded `180ms`, `easeOutCubic`, `easeInCubic` with
  `OpenCrayMotion.quick`, `enter`, and `exit`.
- In reduced motion, remove scale and keep opacity only.
- Keep blur and existing layout unchanged unless it causes jank.

Acceptance:

- Dialog behavior remains familiar.
- Motion is consistent with shared tokens.
- Keyboard inset motion remains smooth.

### 7. Bottom Sheets

Files:

- `flutter_app/lib/features/skills/skills_feature.dart`
- `flutter_app/lib/features/settings/settings_feature.dart`
- `flutter_app/lib/features/settings/agent_settings_pages.dart`
- `flutter_app/lib/features/settings/settings_notification_pages.dart`
- `flutter_app/lib/features/settings/safety_settings_pages.dart`

Current behavior:

- Multiple bottom sheets rely on Flutter defaults and local styling.
- Some sheets feel disconnected from the bottom edge.

Target behavior:

- Bottom sheets rise from the bottom and leave downward.
- Sheet content uses the same surface radius and timing.
- Reduced motion uses opacity without vertical travel.

Implementation details:

- Use `AnimationStyle` where `showModalBottomSheet` supports it:
  - `duration: OpenCrayMotion.panel`
  - `reverseDuration: OpenCrayMotion.pageExit`
  - `curve: OpenCrayMotion.enter`
  - `reverseCurve: OpenCrayMotion.exit`
- Add a shared local helper if signatures repeat heavily in settings.
- Keep existing safe-area padding and handle bars.

Acceptance:

- Every sheet appears attached to the bottom edge.
- No sheet uses a generic center pop.

### 8. Inline Expansion and State Feedback

Files:

- `flutter_app/lib/features/chat/chat_feature_screen.dart`
- `flutter_app/lib/features/files/files_feature.dart`
- `flutter_app/lib/features/skills/skills_feature.dart`
- `flutter_app/lib/features/settings/settings_feature.dart`
- `flutter_app/lib/features/settings/safety_settings_pages.dart`

Current behavior:

- Many rows/cards already use `AnimatedContainer`, `AnimatedOpacity`, and
  `AnimatedSize`, but durations are scattered and some state changes are abrupt.

Target behavior:

- Expanding details grow vertically from the source edge.
- Active chips, selected cards, filters, and focus states use shared `micro`
  timing.
- Active work can have a subtle breathing signal only when it communicates real
  activity.

Implementation details:

- Normalize common `160ms`/`180ms` animated containers to `OpenCrayMotion.micro`
  or `OpenCrayMotion.quick`.
- Composer text field expansion should use `OpenCrayMotion.expand` and bottom
  alignment.
- Existing shimmer/loading animation remains allowed only for active work and
  must continue respecting `disableAnimations`.
- Avoid adding decorative infinite pulse to static cards or icons.

Acceptance:

- Expansion reads as expansion, not a generic pop.
- Busy/streaming indicators stop when work is terminal.
- No new always-on decorative animation.

### 9. Surface Coordination Polish

Files:

- `flutter_app/lib/core/design/opencray_widgets.dart`
- `flutter_app/lib/features/chat/chat_feature_screen.dart`
- `flutter_app/lib/features/files/files_feature.dart`
- `flutter_app/lib/features/skills/skills_feature.dart`
- `flutter_app/lib/features/settings/settings_feature.dart`

Current behavior:

- The app is visually restrained, but some surfaces differ in timing, row
  selection feedback, and border/indicator treatment.

Target behavior:

- The UI feels like one system: quiet, flat, lightly tactile.
- Selection/focus affordances are visible but not loud.
- Motion supports hierarchy instead of decorating every element.

Implementation details:

- Prefer border/background transitions over shadows.
- Keep card radii, page padding, and touch targets aligned with
  `docs/mobile-ui-layout-spec.md`.
- Do not introduce new copy, new navigation items, or new product behavior in
  this pass.

Acceptance:

- 360dp phone layout remains stable.
- Text does not overlap, clip, or force horizontal scroll.
- No nested card-in-card visual drift is introduced.

## Screens To Prioritize

1. Chat: composer focus, action rail, drawer, streaming/active work feedback.
2. Settings: home-to-subpage navigation, option sheets, inline sections.
3. Files: preview/create dialogs, editor/preview transitions, busy/selection
   states.
4. Skills: segmented control, search result reveal, install/manage state
   changes.

## Acceptance Criteria

- Navigation direction is consistent: things return along the path they used to
  enter.
- Expanding content expands instead of popping or globally scaling.
- Modal surfaces use geometry-appropriate motion: side drawer from side, sheet
  from bottom, modal dialog from its own center/trigger context.
- `MediaQuery.disableAnimations` is respected by shared motion helpers.
- Common durations and curves come from shared tokens.
- No new layout shifts, text overlap, or touch-target regressions on 360dp phone
  layouts.
- Flutter analysis passes for the affected module.
- Flutter tests are run where practical. If sandboxed Flutter commands hang,
  rerun outside the sandbox with approval per repository guidance.

## Initial Verification Plan

1. `dart analyze flutter_app`
2. `flutter test` for the Flutter module, outside the sandbox if the command
   hangs in the current environment.
3. Focused widget tests for any new transition helpers or navigation behavior
   that can be tested deterministically.
4. Manual emulator pass on compact phone width for Chat, Settings, Files, and
   Skills after code changes.

## Worktree Plan

Create an isolated worktree without switching the root checkout:

```powershell
git worktree add -b codex/mobile-ui-motion-polish .codex-worktrees/mobile-ui-motion-polish master
```

All code changes should happen inside `.codex-worktrees/mobile-ui-motion-polish`.
The root checkout has unrelated uncommitted work by other agents and should not
be switched, reset, or cleaned.

## Implementation Checklist

Planning:

- [x] Create a docs plan before implementation.
- [x] Search for external motion/design skill references.
- [x] Create isolated worktree from `master`.
- [x] Keep this checklist updated as implementation proceeds.

Shared foundation:

- [x] Add `OpenCrayMotion` tokens and reduced-motion helper.
- [x] Add horizontal page route helper.
- [x] Add directional tab stack helper.
- [x] Add directional settings switcher helper.

Navigation:

- [x] Replace shell body `IndexedStack` with directional state-preserving stack.
- [x] Animate bottom nav selection without layout shift.
- [x] Replace app-level settings routes with shared horizontal route.
- [x] Replace settings standalone pushes with shared horizontal route.
- [x] Replace agent/debug nested pushes with shared horizontal route.

Spatial surfaces:

- [x] Convert chat session drawer to left-enter/left-exit overlay.
- [x] Normalize files preview/create dialog timing and reduced-motion behavior.
- [x] Normalize skills bottom sheet animation.
- [x] Normalize settings bottom sheet animation.
- [x] Normalize notification/safety/agent bottom sheet animation.

Inline and state feedback:

- [x] Normalize existing `AnimatedContainer` timings in skills.
- [x] Normalize existing `AnimatedContainer` timings in settings/safety.
- [x] Normalize chat composer expansion and focus timing.
- [x] Audit active work breathing/shimmer for reduced-motion compliance.
- [x] Avoid adding decorative infinite animations.

Visual QA:

- [x] Check Chat drawer behavior through focused widget coverage.
- [x] Check Settings home -> subpage -> nested subpage -> back through focused
  widget coverage.
- [x] Check Files preview/create dialog behavior through focused widget coverage.
- [x] Check Skills manage/install behavior through focused widget coverage.
- [ ] Manual compact-phone emulator pass for text overlap, horizontal scroll,
  and touch-target regressions. Not executed yet: `adb devices` returned no
  connected devices and `emulator`/`avdmanager` were not on PATH in this
  environment.

Verification:

- [x] Run `dart analyze flutter_app`.
- [x] Run Flutter tests, escalating outside sandbox if the command hangs.
- [x] Use focused widget tests where behavior can be deterministic.
- [x] Create a focused Conventional Commit in Chinese after verification.

Verification notes:

- `dart analyze flutter_app` passes.
- `flutter test` hung in the sandbox, then ran outside the sandbox as required
  by the repository guidance.
- Focused tests passing outside the sandbox:
  - `test/chat_feature_screen_test.dart --plain-name "session drawer opens from the left edge"`
  - `test/chat_feature_screen_test.dart --plain-name "new session drawer action waits for host creation before closing the drawer"`
  - `test/chat_feature_screen_test.dart --plain-name "host-backed session selection updates drawer and clears old thread immediately"`
  - `test/chat_feature_screen_test.dart --plain-name "session drawer shows unread dot and count badges"`
  - `test/opencray_app_shell_test.dart test/opencray_app_test.dart`
  - `test/settings_feature_test.dart --plain-name "home settings opens API integrations entry"`
  - `test/settings_feature_test.dart --plain-name "api integrations page opens sandbox providers and the E2B detail page"`
  - `test/settings_feature_test.dart --plain-name "about version page opens debug tools and renders context trace details"`
  - `test/settings_feature_test.dart --plain-name "agent create flow exposes twin import page and updates summary"`
  - `test/files_feature_test.dart`
  - `test/skills_feature_test.dart`
- Full `flutter test` still reports existing failures outside this motion pass:
  runtime snapshot assertions in `chat_feature_screen_test.dart`, plus two
  standalone LLM focus tests whose direct failure is a tap on a TextField laid
  out below the 600px test viewport. No runtime snapshot logic was changed in
  this pass.
- Focused commit created on `codex/mobile-ui-motion-polish`:
  `feat: 打磨移动端 UI 动效体系`.
