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

Current focus: Skills.

1. Skills: segmented control, Manage/Install horizontal switching, search result
   reveal, install/manage state changes.
2. Chat: composer focus, add-menu expansion, action rail, drawer,
   streaming/active work feedback.
3. Settings: home-to-subpage navigation, option sheets, inline sections.
4. Files: preview/create dialogs, editor/preview transitions, busy/selection
   states.

## Current Revision Plan: Skills And Chat Composer

This revision responds to the follow-up QA findings after the first motion pass:

- Right-to-left page movement can feel like it hesitates at the start.
- Skills Manage/Install switching can show overlapping elements during the
  transition.
- The Skills segmented tab changes state instead of feeling connected to the
  content movement.
- The Chat composer add menu appears to expand upward from below the composer,
  but the intended geometry is that the menu grows out from the input row and
  pushes the composer upward while the composer bottom stays anchored.
- Chat composer material changes, including transparent-to-white surface changes
  and plus-button active color changes, need gradual transitions.

### Skills Manage/Install Switching

Files:

- `flutter_app/lib/features/skills/skills_feature.dart`
- `flutter_app/lib/core/design/opencray_motion.dart`

Current behavior:

- Manage/Install content uses `OpenCrayDirectionalSwitcher`, which is based on
  `AnimatedSwitcher`.
- `AnimatedSwitcher` keeps outgoing and incoming children in the same transition
  stack. With tall lists and different page content, the transition can read as
  two pages overlapping instead of one viewport sliding.
- The outgoing child also uses an exit curve that can make one direction feel
  slower at the start than the opposite direction.

Target behavior:

- Manage and Install behave like two adjacent horizontal pages inside one clipped
  viewport.
- Switching to Install moves content left: Manage exits to the left, Install
  enters from the right.
- Switching back to Manage moves content right: Install exits to the right,
  Manage enters from the left.
- The old page is clipped by the viewport instead of fading over the new page.
- During transition, only the selected page accepts input.
- Different page heights should not create visible overlap, scroll flashes, or
  unexpected vertical jumps.

Implementation details:

- Replace the Skills content `OpenCrayDirectionalSwitcher` usage with a
  Skills-specific horizontal viewport, or add a shared helper only if the same
  geometry is useful outside Skills.
- Use `ClipRect` around the transition area.
- Drive both outgoing and incoming pages from one animation progress so their
  positions stay locked:
  - forward: outgoing `0 -> -1`, incoming `1 -> 0`
  - backward: outgoing `0 -> 1`, incoming `-1 -> 0`
- Prefer `FractionalTranslation` or `SlideTransition` over animating layout
  offsets directly.
- Avoid meaningful fade. If opacity is needed to soften subpixel edges, keep it
  near `1.0` so it never reads as a flash.
- Keep old/new pages in a `Stack` only as moving neighbors, not as centered
  overlapping pages.
- Update or add focused widget coverage to verify direction and that the
  transition helper is present for Manage/Install.

Acceptance:

- Mid-transition screenshots should show adjacent pages sliding, not two pages
  centered on top of each other.
- Manage -> Install and Install -> Manage have mirrored directions.
- Repeated fast tab taps do not leave stale outgoing content.
- The content does not flash or briefly show the wrong seed/loading page.

### Skills Segmented Tab

Files:

- `flutter_app/lib/features/skills/skills_feature.dart`

Current behavior:

- Each segment animates its own selected background.
- The selected state changes visually, but there is no physical relationship
  between the tab indicator and the page movement.

Target behavior:

- The segmented control uses one sliding selected capsule.
- The capsule moves horizontally with the same direction as the page switch.
- Text color and weight transition with the capsule movement.
- The tab control keeps stable height, hit targets, and text layout.

Implementation details:

- Refactor `_SegmentedControl` so the selected capsule is a single positioned
  indicator behind the two labels.
- Use a deterministic two-position animation:
  - Manage selected: indicator aligned to the left half.
  - Install selected: indicator aligned to the right half.
- Use shared motion timing:
  - tab indicator: `OpenCrayMotion.expand` or a nearby `220-240ms` duration
  - curve: spatial, with immediate movement and soft arrival
- Keep label widgets fixed in place; only colors/weight change.
- Avoid scaling the tab labels or changing segment dimensions.

Acceptance:

- The tab reads as one control with a moving selection indicator.
- Label text never shifts, clips, or changes hit target size.
- The tab and content agree on direction.

### Chat Composer Add Menu

Files:

- `flutter_app/lib/features/chat/chat_feature_screen.dart`
- `flutter_app/lib/core/design/opencray_motion.dart`

Current behavior:

- The composer surface uses `AnimatedSize` with bottom alignment.
- The add menu uses `AnimatedSwitcher` and `SizeTransition`.
- The resulting motion can read as the composer expanding from the bottom upward,
  instead of the menu growing from the input row downward while the composer is
  pushed upward.
- The composer surface/background and plus button active colors change too
  abruptly.

Target behavior:

- Tapping plus makes the add menu grow out from the input row.
- The add menu reveals below the input row.
- The composer bottom stays anchored to the screen bottom; as the add menu grows,
  the whole composer moves upward to make room.
- Closing the menu reverses the same path: the tray collapses back into the input
  row, and the composer settles back down.
- The input surface transitions from the non-expanded material to the expanded
  white card material gradually.
- The plus button background and icon color animate with the same state change.

Implementation details:

- Replace the add-menu `AnimatedSwitcher` with an explicit add tray whose height
  is driven by open progress.
- Put the tray directly below `_InputRow` in the composer column.
- Use `ClipRect` plus `Align(heightFactor: progress, alignment: topCenter)` so
  the tray reveals downward from the input row.
- Keep the outer composer `AnimatedSize` aligned to `Alignment.bottomCenter` so
  the bottom edge remains anchored while the new height pushes content upward.
- Drive surface state from the same `showAddMenu` progress:
  - card/background color blend
  - border color blend
  - optional subtle shadow or stroke change
  - plus-button background color blend
  - plus-button foreground color blend
- Keep focus outline and add-menu active material separate so tapping plus does
  not look like a text-field focus jump.
- Use open timing around `220-260ms` and close timing around `180-220ms`.
- Avoid bounce, generic pop, or center-scale effects.

Acceptance:

- The menu visibly originates at the input row and opens downward from that
  source.
- The composer bottom edge remains anchored during expansion/collapse.
- Surface and plus-button state changes are smooth, not instant.
- Reduced-motion mode preserves state clarity without large spatial movement.

### Page And Switch Curve Correction

Files:

- `flutter_app/lib/core/design/opencray_motion.dart`
- Any route/switch helper that applies page-level horizontal movement.

Current behavior:

- Page and switch code can reuse `OpenCrayMotion.exit` for spatial movement.
- `exit` is useful for opacity or closing affordances, but for horizontal page
  movement it can create a near-zero initial velocity in one sampled direction,
  making a right-to-left transition feel like it sticks before moving.

Target behavior:

- Page-level horizontal movement uses a spatial curve that starts moving
  immediately enough to feel responsive and lands softly.
- Forward and reverse directions should feel symmetric even though they move in
  opposite directions.
- Exit curves remain available for opacity, scrim, and non-spatial close states.

Implementation details:

- Add or tune a dedicated spatial curve token for page movement.
- Use that spatial curve for both incoming and outgoing horizontal page
  translation where a single transition progress controls both pages.
- Avoid double-curving the same animation in `AnimatedSwitcher` and inside the
  transition builder.
- Keep durations in the existing page range unless manual QA shows a clear need
  to adjust.

Acceptance:

- Right-to-left and left-to-right transitions start without a visible pause.
- Transitions do not feel linear or sticky.
- No new flash is introduced by opacity.

### Current Revision Checklist

Skills first:

- [x] Replace Skills Manage/Install `AnimatedSwitcher`-style transition with a
  clipped horizontal viewport.
- [x] Make Manage -> Install slide left and Install -> Manage slide right.
- [x] Prevent old/new page centered overlap during the transition.
- [x] Keep only the selected Skills page interactive during motion.
- [x] Refactor Skills segmented control to a single sliding capsule indicator.
- [x] Keep Skills tab label layout and hit targets stable.
- [x] Add or update focused Skills widget tests.

Chat composer next:

- [x] Replace Chat composer add-menu switcher with a tray that expands downward
  from the input row.
- [x] Keep composer bottom anchored while expanded height pushes the composer
  upward.
- [x] Animate composer material, border, and plus-button colors with the open
  progress.
- [x] Keep focus outline behavior distinct from add-menu active behavior.
- [x] Add or update focused Chat composer widget tests.

Shared motion:

- [x] Tune page spatial curve so right-to-left and left-to-right movement feel
  symmetric.
- [x] Use spatial movement curves for page translation and keep exit curves for
  opacity/close affordances.
- [x] Verify reduced-motion fallbacks for Skills and Chat composer.

Verification:

- [x] Run `dart analyze flutter_app`.
- [x] Run focused Skills tests.
- [x] Run focused Chat composer tests.
- [x] Rebuild debug APK after implementation.
- [x] Create a focused Conventional Commit in Chinese.

Current revision verification notes:

- `dart analyze flutter_app` passes.
- `flutter test test/skills_feature_test.dart` passes outside the sandbox.
- Focused tests passing outside the sandbox:
  - `test/chat_feature_screen_test.dart --plain-name "plus menu expands inside animated composer surface"`
  - `test/settings_feature_test.dart --plain-name "home settings opens API integrations entry"`
  - `test/opencray_app_shell_test.dart --plain-name "shell tab transition preserves chat widget state"`
- Debug APK rebuilt at `build/apk/OpenCray-debug.apk`.
- APK SHA256:
  `C9B6B1DC8440E2582220ACF1D3111F1290236155E6A07CA01AC369D8FE6C048E`.

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
