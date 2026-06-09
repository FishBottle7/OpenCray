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

Current focus: Chat main surface polish.

1. Chat: message hierarchy, run trace status language, approval surface,
   composer combinations, session drawer, attachment and preview surfaces.
2. Skills: segmented control, Manage/Install horizontal switching, search result
   reveal, install/manage state changes.
3. Files: preview/create dialogs, editor/preview transitions, busy/selection
   states.
4. Settings: home-to-subpage navigation, option sheets, inline sections.

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

## Next Revision Plan: Chat Main Surface Polish

This plan focuses on Chat after the composer add-menu pass. The goal is not to
add more animation everywhere. The goal is to make the Chat surface easier to
scan while preserving the calm productivity tone: messages stay primary,
runtime/tool state stays explanatory, approvals feel distinct and serious, and
secondary surfaces return along the path they entered.

Local UI guidance used for this pass:

- Respect reduced motion for all spatial movement.
- Animate only the important state changes in a view.
- Keep motion in the 150-300ms range unless a longer active-work shimmer is
  tied to real ongoing work.
- Avoid generic bounce/pop treatments for routine productivity UI.

### Chat Header And Summary Hierarchy

Files:

- `flutter_app/lib/features/chat/chat_feature_screen.dart`

Current behavior:

- `_ChatScrollContent` renders the large title, summary card, then message list
  with fixed vertical gaps.
- `_SummaryCard` competes with the first messages when the thread is active,
  especially after the user has already entered a conversation.
- The top glass bar reacts to scroll, but the title/summary transition does not
  yet help the user understand whether they are at the start of a thread or deep
  in the transcript.

Target behavior:

- Empty/new thread: keep the title and summary more prominent.
- Active thread: make the summary quieter so the message list owns attention.
- When scrolling down, the header should visually settle into the top bar rather
  than feeling like a separate large block above the transcript.

Implementation details:

- Add a header state model derived from scroll offset and thread content:
  - empty thread
  - active thread at top
  - active thread scrolled
- In active threads, reduce the summary card emphasis:
  - smaller vertical gap before message list
  - lighter card surface or border-only treatment
  - optional collapse to one-line host/runtime status after scroll threshold
- Use existing `OpenCrayMotion.micro` or `OpenCrayMotion.expand` for header
  height/color transitions.
- Keep the large title readable at 360dp and do not introduce a sticky hero.

Acceptance:

- First screenful still feels calm on a new chat.
- In a populated chat, the first visible message is not pushed too far down by
  repeated chrome.
- Header changes do not cause scroll jumps.

### Message List Insertions And Group Rhythm

Files:

- `flutter_app/lib/features/chat/chat_feature_screen.dart`

Current behavior:

- `_MessageList` builds a static `Column`.
- New messages, runtime traces, timestamps, and timeline pills appear as list
  children without an explicit insertion motion.
- Bubbles use fixed max widths (`252` inbound, `236` outbound), which can feel
  narrow on larger phones while still being close to the spec on compact phones.

Target behavior:

- New assistant/user messages should appear as transcript insertions, not page
  pops.
- Runtime trace insertion should feel attached to the message or run that caused
  it.
- Message group spacing should stay consistent across plain text, attachments,
  timeline pills, and traces.

Implementation details:

- Introduce a lightweight message-list item wrapper for message, trace, timeline,
  and timestamp rows.
- Add keyed insertion animation only for newly added rows:
  - vertical reveal from the row's natural location
  - small opacity change
  - no horizontal slide for ordinary text bubbles
- For messages with attachments, keep the bubble body stable and animate only
  attachment preview loading/replacement.
- Review bubble max-width calculation against the layout spec:
  - compact width: preserve current readable width
  - standard/large phone: allow up to about 78% of content width
- Keep selection mode layout stable; selection controls should not resize the
  bubble content column.

Acceptance:

- Streaming and final assistant messages do not flash or jump.
- New run traces insert near their cause without stealing the whole viewport.
- Message text and attachment rows do not overlap during insertion.

### Run Trace Status Language

Files:

- `flutter_app/lib/features/chat/chat_feature_screen.dart`

Current behavior:

- `_RunTraceBubble` is visually lightweight, but it mixes multiple concerns:
  status line, inline interrupt/retry actions, sandbox session card, preview
  card, and fullscreen inspector entry.
- `_RunTraceStatusLine` uses a shimmer for active traces, but all active states
  can read similarly even when they mean different things.
- Inline trace cards can compete with agent messages when a run has many steps.

Target behavior:

- Runtime/tool state should read as a secondary process lane under the message
  flow.
- Active, waiting for approval, high-risk, retryable, terminal, and preview-ready
  states should have distinct but restrained visual treatment.
- Expanding to the fullscreen inspector should feel like drilling into a process,
  not opening an unrelated dialog.

Implementation details:

- Create a compact run-trace visual grammar:
  - active: subtle moving text or small progress line only while work is live
  - waiting approval: amber/blue waiting capsule, no shimmer
  - high risk: warm border/accent, no aggressive fill
  - terminal: muted dot/check with calmer text
  - retryable/error: restrained warning accent plus retry affordance
- Split `_RunTraceBubble` into clearer internal sections:
  - status row
  - actions row
  - optional session/preview cards
- Animate run trace state changes with color/size only:
  - live -> terminal: shimmer stops, dot settles, text fades to terminal color
  - live -> approval: status row expands to expose approval-linked action
  - retryable: retry action reveals vertically from the status row
- Keep fullscreen inspector entry tied to the status row:
  - tap row opens inspector
  - dialog/sheet transition should originate from row context where practical
  - reduced motion uses fade only
- Ensure shimmer respects `OpenCrayMotion.reduce(context)`, not only
  `MediaQuery.disableAnimations`.

Acceptance:

- Users can distinguish "thinking", "waiting for approval", "failed/retry", and
  "done" without reading the full trace body.
- Long runtime histories do not visually overpower assistant messages.
- Reduced-motion mode has no active shimmer.

### Approval Surface

Files:

- `flutter_app/lib/features/chat/chat_feature_screen.dart`

Current behavior:

- `_PendingApprovalOverlaySurface` uses a glass surface with stacked queued
  approval previews.
- Approval action buttons are presented in one row, including reject,
  approve-for-session, and approve.
- High-risk state has different color, but approval severity and queue movement
  could be clearer.

Target behavior:

- Approval should feel like a focused blocking decision anchored above the
  composer.
- Queued approvals should be visible but secondary.
- High-risk approval should be visually serious without becoming loud.
- Resolving one approval should move the next queued approval forward with a
  clear stack-to-front motion.

Implementation details:

- Add approval state transitions:
  - new approval enters from composer/top edge of composer stack
  - active approval resolves by fading/sliding upward a small distance
  - queued approval moves forward from the preview stack to active position
- Tighten action hierarchy:
  - destructive/reject remains secondary
  - approve-for-session is outlined and clear
  - approve is the only filled action
- Keep button labels stable; if three buttons do not fit at 360dp, stack the
  secondary action row above the primary approve row.
- Add explicit busy state motion:
  - button content fades to progress label/spinner
  - card remains stable, no full surface pulse
- For high-risk:
  - warm border and small severity chip
  - avoid a full orange card fill

Acceptance:

- User can tell which approval is active and how many are queued.
- Approval card does not hide the composer state unexpectedly.
- Three-action approval layouts do not clip labels on compact phones.

### Composer Combination States

Files:

- `flutter_app/lib/features/chat/chat_feature_screen.dart`

Current behavior:

- The recent pass improved add-menu expansion, surface material transition, and
  plus-button active transition.
- Composer can still combine TODO, attachments, command options, add menu,
  interrupt confirm, focus outline, and disabled/busy send states.
- These combinations may create dense stacked surfaces near the screen bottom.

Target behavior:

- Composer should keep one clear primary row: text field, plus, send/interrupt.
- Secondary composer content should have an order that reflects causality:
  TODO/command context above, attachments next, input row, add tray below input
  row.
- The bottom edge remains anchored in all combinations.

Implementation details:

- Define a single composer stack order:
  1. TODO / active task context
  2. command options
  3. attachments
  4. input row
  5. add tray
- Add focused state tests for combinations:
  - add tray + attachment
  - add tray + command options
  - interrupt confirm replaces input row
  - TODO surface plus attachment
- Make attachment row insertion directional:
  - first attachment reveals from the input row's top edge
  - removal collapses the card without shifting the input row horizontally
- Use one material progress source per composer state; avoid multiple surfaces
  independently changing white/glass/background.

Acceptance:

- Composer never has two competing white cards stacked inside each other.
- Text field bottom position stays stable when secondary content changes.
- Add tray, attachments, and command options do not overlap on compact phones.

### Attachments And Preview Surfaces

Files:

- `flutter_app/lib/features/chat/chat_feature_screen.dart`

Current behavior:

- Chat supports inline image groups, file tiles, voice attachments, composer
  attachments, and text/image preview dialogs.
- Preview surfaces exist, but attachment-to-preview continuity is limited.

Target behavior:

- Attachment previews should feel connected to the item that opened them.
- Inline attachments should be visibly secondary to the message text but still
  easy to inspect.
- Loading thumbnails should avoid layout shifts.

Implementation details:

- Give image/file/voice attachment tiles stable aspect/height constraints before
  async preview data arrives.
- Animate attachment preview readiness with opacity/content replacement only.
- For text/image preview dialogs:
  - keep modal behavior, but use shared route/dialog motion tokens
  - reduce center scale where the preview is opened from a visible tile
  - consider a tile-origin fade/expand only if it can be done without heavy
    layout work
- Keep markdown/text selection affordances visible in text preview.

Acceptance:

- Image thumbnails do not resize the bubble after data arrives.
- Opening and closing previews does not feel like an unrelated center pop.
- Voice waveform interaction remains stable while playback progress changes.

### Session Drawer And Session List

Files:

- `flutter_app/lib/features/chat/chat_feature_screen.dart`

Current behavior:

- Session drawer now opens/closes from the left.
- Drawer uses a fixed white panel, a CTA, and a list of session tiles.
- Current, unread, running, failed, and long-press action states can be more
  legible.

Target behavior:

- Drawer should read as a session switcher with clear current/running/unread
  status.
- Selecting a session should show immediate local selection feedback before the
  host snapshot arrives.
- Closing the drawer should always return left, including after new-session or
  session-select actions.

Implementation details:

- Add selected-session treatment:
  - subtle background fill
  - active left rail or dot
  - stable text weight change
- Distinguish status markers:
  - unread dot/count
  - running small progress dot or pill
  - failed/retryable muted warning marker
- Add row-level press feedback with `OpenCrayMotion.micro`; avoid scale changes
  that shift list layout.
- If session selection triggers a host-backed load, keep the drawer row selected
  and close only after the local pending state is visible.

Acceptance:

- Current session is obvious in the drawer.
- Running/unread state is scannable without reading every preview.
- Fast session switching does not flash old thread seed content.

### Selection And Message Menu

Files:

- `flutter_app/lib/features/chat/chat_feature_screen.dart`
- `flutter_app/test/chat_message_menu_test.dart`

Current behavior:

- Long press opens a message menu overlay.
- Selection mode changes toolbar and adds selection controls.
- Text selection and message selection are both present, so state transitions
  must be especially clear.

Target behavior:

- Long-press menu should feel anchored to the pressed bubble.
- Entering selection mode should explain that the user is now selecting whole
  messages, not markdown text.
- Exiting selection mode should restore normal bubble spacing without a jump.

Implementation details:

- Anchor menu transition to the message bubble rect:
  - menu fades/slides from the bubble side
  - dismissed menu returns to the same origin
- Selection controls should reveal from the row side, not pop over text.
- Toolbar transition:
  - normal toolbar -> selection toolbar uses cross-fade plus slight vertical
    settle
  - no width changes in bottom nav or composer
- Keep text selection highlight unchanged when markdown text is selected; only
  whole-message selection shows row controls.
- Message delete motion uses the restrained ownership slide-out pattern:
  - keep the target message in local UI state for the exit motion instead of
    removing it immediately
  - inbound bubbles slide 18-24px toward the left; outbound bubbles slide 18-24px
    toward the right
  - opacity fades first, then row height collapses through a clipped height
    factor so surrounding messages settle without overlap
  - use one outer animation wrapper around the row; do not animate markdown,
    image, or attachment internals independently
  - commit the host/local deletion after the exit motion; failed host deletes
    restore from the latest host snapshot
  - reduced motion resolves the same path to immediate removal

Acceptance:

- It is obvious whether the user is selecting text or whole messages.
- Message menu does not obscure the selected text more than necessary.
- Selection mode works on compact phones without horizontal clipping.
- Deleting a single inbound or outbound bubble gives directional feedback before
  the row is removed.
- Delete animation does not re-layout markdown or attachment contents during the
  motion.

### Next Chat Revision Checklist

Planning:

- [x] Audit current Chat compact-phone risks for header, transcript, run trace,
  approval, drawer, composer, and attachments through implementation review and
  focused widget coverage.
- [x] Choose Chat main surface polish as the implementation slice after this
  plan.

Header and transcript:

- [x] Add active-thread header/summary visual states.
- [x] Add keyed message/trace insertion wrappers.
- [x] Add restrained directional delete exit motion for message bubbles.
- [x] Review bubble width behavior against 78% content-width target.

Run trace:

- [x] Define compact run-trace state visual grammar.
- [x] Split run trace rendering into status, actions, and optional preview
  sections.
- [x] Animate live -> terminal, live -> approval, and retryable transitions.
- [x] Ensure run trace shimmer uses shared reduced-motion helper.

Approval:

- [x] Add approval stack transition for queued approvals moving forward.
- [x] Tighten approval action layout for 360dp.
- [x] Add explicit busy transition for approval actions.
- [x] Refine high-risk approval surface treatment.

Composer:

- [x] Lock composer stack order across TODO, commands, attachments, input, and
  add tray.
- [x] Add tests for mixed composer states.
- [x] Animate attachment insertion/removal from the input row edge.

Attachments and preview:

- [x] Stabilize attachment preview dimensions before async data arrives.
- [x] Normalize text/image preview dialog motion with shared tokens.
- [x] Keep voice waveform progress from shifting tile layout.

Session drawer:

- [x] Add current/unread visual states to session rows. Running/failed remain
  pending model support because `ChatSessionListItemData` does not expose those
  states.
- [x] Add row-level press/selection feedback.
- [x] Verify session selection never flashes seed content.

Selection/menu:

- [x] Anchor message menu transition to the pressed bubble.
- [x] Clarify whole-message selection versus markdown text selection states.
- [x] Add focused widget tests for toolbar/menu transitions.

Verification:

- [x] Run `dart analyze flutter_app`.
- [x] Run focused Chat widget tests for the sub-area changed.
- [x] Run message menu tests if selection/menu is changed.
- [x] Run shell tab state test if message list persistence or scroll state is
  touched.
- [x] Build debug APK after implementation.

Current Chat polish verification notes:

- `dart analyze flutter_app` passes.
- Sandboxed `flutter test test/chat_message_menu_test.dart` timed out after
  180s, then the focused Flutter tests were rerun outside the sandbox per repo
  guidance.
- Passing focused suites:
  - `test/chat_message_menu_test.dart`
  - Chat focused subset:

    ```powershell
    flutter test test/chat_feature_screen_test.dart --name "composer keeps mixed sections in a stable vertical order|plus menu expands inside animated composer surface|composer picks and submits attachments without requiring text|composer image attachments render a thumbnail preview card|session drawer shows unread dot and count badges|session drawer opens from the left edge|host message renders image, voice, and file attachments in one bubble|text file attachments open an internal preview on tap"
    ```

  - `test/opencray_app_shell_test.dart test/skills_feature_test.dart`
  - `test/files_feature_test.dart`
- Debug APK built from the isolated worktree:
  `.codex-worktrees/mobile-ui-motion-polish/build/apk/OpenCray-debug.apk`.

## Next Revision Plan: Lazy Rendering And Paging

This revision addresses high-content surfaces where visual polish alone is not
enough. Chat transcripts, session history, and file directories can all grow
large enough that building every row at once will make motion feel janky even
when the motion curves are correct.

Design distinction:

- Render virtualization is mandatory for long local lists. It keeps offscreen
  rows from building and reduces layout/paint work during scroll and transition
  frames.
- Data paging is a separate host/runtime contract. It keeps Flutter from
  receiving, decoding, diffing, and holding huge snapshots at once.
- Do not hide paging behind generic spinners. Loading older/newer content should
  appear where the content belongs: top of transcript for older chat history,
  bottom of session drawer for older sessions, and bottom of a large directory
  listing for additional entries.

### Files Directory Virtualization

Files:

- `flutter_app/lib/features/files/files_feature.dart`
- `flutter_app/test/files_feature_test.dart`

Current behavior:

- `FilesFeatureScreen` uses a `SingleChildScrollView` containing one `Column`.
- `_DirectoryCard` builds every visible entry with a `for` loop.
- Large directories therefore build all row widgets, all dividers, and all row
  text in one layout pass.

Target behavior:

- Keep the existing Files visual structure: title, search, location card, then a
  rounded directory card.
- Convert the screen body to a sliver scroll surface.
- Render directory rows with a builder-backed sliver so only visible rows are
  built.
- Preserve row keys such as `files-row-<relativePath>` and the
  `files-scroll-view` key for tests and automation.

Implementation details:

- Replace the outer `SingleChildScrollView + Column` with `CustomScrollView`.
- Render title/search/location as a short `SliverList` or
  `SliverToBoxAdapter` group.
- Replace `_DirectoryCard` with a sliver-capable directory section:
  - state/empty/error cards remain box adapters
  - populated directories use `SliverList` with a `SliverChildBuilderDelegate`
  - dividers are produced by the builder between rows
- Keep the selection toolbar overlay outside the scroll view, still pinned above
  the shell tab bar.
- Do not add per-row entrance animation for existing directory contents. Large
  directory performance matters more than decorative row motion here.

Acceptance:

- Scrolling a large directory does not require building every file row.
- Existing file row keys, tap, long-press, selection, preview, and breadcrumb
  tests keep working.
- Sticky location bar still appears after scrolling.
- Empty, error, and loading states preserve the current card layout.

### Chat Transcript Virtualization

Files:

- `flutter_app/lib/features/chat/chat_feature_screen.dart`
- `flutter_app/test/chat_feature_screen_test.dart`
- `flutter_app/test/chat_message_menu_test.dart`

Current behavior:

- The Chat page uses `SingleChildScrollView`.
- `_MessageList` precomputes every message, timestamp divider, and run trace
  into a `children` list, then returns a `Column`.
- Long sessions with markdown, attachments, live traces, and delete/selection
  wrappers will eventually make normal scroll and tab transition frames heavy.

Target behavior:

- Convert transcript rendering to a row model plus virtual list.
- Keep current message insertion, directional delete, selection, and run-trace
  behavior intact.
- Preserve bottom composer anchoring and "scroll to latest" behavior.

Implementation details:

- Introduce a transcript row model before rendering:
  - timestamp divider
  - timeline pill
  - inbound message
  - outbound message
  - detached run trace
  - leading/attached run trace groups
- Use a builder-backed sliver/list for transcript rows.
- Keep row keys stable so insertion and delete exit motion can remain local to
  the affected row.
- Keep recent-message auto-scroll behavior explicit:
  - new local/user message can scroll to bottom
  - older-page prepend must preserve scroll offset
  - reduced-motion mode still avoids spatial reveal
- Treat this as a separate implementation slice because it touches scroll
  physics, overlays, message menu anchoring, and delete motion.

Completed implementation:

- `OpenCrayChatFeature` now uses a keyed `CustomScrollView` for the
  transcript surface instead of an eager `SingleChildScrollView`.
- `_ChatScrollContent` now emits slivers, keeping the header and empty-state
  spacer as box adapters while delegating populated transcripts to `_MessageList`.
- `_MessageList` now builds stable transcript row descriptors first, then uses
  `SliverList` with `SliverChildBuilderDelegate` so message bubbles, timestamp
  dividers, and run-trace rows are built only when the sliver asks for them.
- The sliver delegate keeps Flutter's default automatic keep-alive behavior so
  visible rows can preserve local state while offscreen historical rows avoid
  initial widget construction.
- Existing bubble keys remain the test and automation surface; the scroll view
  exposes `chat-scroll-view`.
- Message-menu copy now ignores text selection produced by the same long-press
  gesture that opened the menu, while still allowing an existing or explicitly
  updated text selection to be copied.

Acceptance:

- Long transcripts do not build all historical rows during a normal frame.
- New message insertion still reads as an insertion, not a page flash.
- Deleting inbound/outbound messages still exits toward the owning side.
- Message menu anchoring still uses the visible bubble rect.
- No seed-content flash appears when switching sessions.

### Session Drawer Paging

Files:

- `flutter_app/lib/features/chat/chat_feature_screen.dart`
- host snapshot/gateway files if the data contract is extended

Current behavior:

- The session drawer already uses `ListView.separated`, so its rendering path is
  lazy enough for the visible drawer rows.
- The data contract still appears to provide the drawer's session list as one
  snapshot.

Target behavior:

- Keep the current `ListView.separated` rendering.
- Add data paging only when the host/runtime model can provide a cursor or
  bounded list.
- First page should prioritize recent/current sessions.

Implementation details:

- Add a drawer paging model only after the host contract exists:
  - `hasMore`
  - `nextCursor` or `oldestLoadedAt`
  - loading/error state for older sessions
- Trigger load-more near the bottom of the drawer list.
- Keep row selection feedback immediate and independent from page loads.
- Do not block opening the drawer on older-page loading.

Completed implementation:

- The drawer continues to use `ListView.separated`, which is the correct lazy
  rendering layer for visible session rows.
- The drawer list now has the stable key `chat-session-list`, and each row has a
  stable `chat-session-row-<sessionId>` key for large-list tests and automation.
- `ChatSessionsDrawerState` and `OpenCrayChatDrawerSnapshot` still expose a
  plain `sessions` list only. There is no `hasMore`, `nextCursor`, or
  `oldestLoadedAt` field yet, so data paging remains deferred until the
  host/runtime snapshot contract is extended.
- Added widget coverage proving a large drawer does not build the last session
  row until the drawer list scrolls to the bottom.

Acceptance:

- Opening the drawer remains fast even with a large session history.
- Current/recent sessions are available immediately.
- Loading older sessions does not reset scroll position or selected-row state.

### Lazy Rendering Checklist

- [x] Write lazy rendering and paging plan before implementation.
- [x] Convert Files body to a sliver scroll surface.
- [x] Convert Files directory rows to builder-backed lazy rendering.
- [x] Add or update Files widget coverage for large-directory virtualization.
- [x] Keep Session drawer rendering as `ListView.separated`; defer data paging
  until a host cursor contract exists.
- [x] Add stable Session drawer list and row keys.
- [x] Add Session drawer widget coverage for large-history lazy rendering.
- [x] Plan Chat transcript row-model migration as a separate high-risk slice.
- [x] Convert Chat transcript surface to `CustomScrollView` and slivers.
- [x] Convert Chat transcript rows to a builder-backed `SliverList`.
- [x] Preserve message insertion, delete motion, selection mode, and message menu
  anchoring under transcript virtualization.
- [x] Add Chat transcript widget coverage for large-history lazy rendering.
- [x] Run `dart analyze flutter_app`.
- [x] Run focused Files tests after the Files slice.
- [x] Run focused Chat transcript and Session drawer lazy-rendering tests.
- [x] Run message menu tests after transcript virtualization.

## Next Revision Plan: Files And Skills High-Frequency Polish

This revision shifts attention away from major Chat motion and toward the
surfaces users will repeatedly operate under pressure: Files first, Skills
second, and only small global feedback refinements after that. The guiding rule
is workbench clarity: state changes should be obvious without becoming louder
than the content.

Priority order:

1. Files selection mode and operation toolbar.
2. Files breadcrumb and search/filter state.
3. Skills search results and install lifecycle.
4. Small Chat run-trace density cleanup only if it stays local.
5. Bottom navigation selected-state feedback.

### Files Selection Mode And Operation Toolbar

Files:

- `flutter_app/lib/features/files/files_feature.dart`
- `flutter_app/test/files_feature_test.dart`

Current behavior:

- Long press enters selection mode and changes the page title to selected count.
- The selection toolbar is a flat bottom row with Share, Move, Copy/Paste,
  Rename, and Delete at equal visual weight.
- Delete uses the danger color, but it still occupies the same action group as
  reversible or lower-risk actions.
- Selection mode appears as a toolbar overlay rather than a temporary working
  mode attached to the list state.

Target behavior:

- Treat selection as a temporary file-operation mode:
  - entering selection mode lightly lifts the operation surface from the bottom
  - leaving selection mode lets it settle back down
  - the list retains spatial continuity and does not jump
- Group actions by risk and frequency:
  - primary/reversible group: Share, Move, Copy/Paste, Rename
  - destructive group: Delete, visually separated and lower-emphasis until
    enabled
- Keep existing action semantics and keys where tests already rely on them.
- Keep touch targets stable on 360dp screens.

Implementation details:

- Replace the full-width hard-edged toolbar treatment with a bottom workbench
  surface:
  - rounded top corners
  - subtle top divider/shadow only when needed for separation
  - safe-area padding preserved
  - no card nested inside a card
- Add an `AnimatedSlide`/`AnimatedOpacity` wrapper on toolbar presentation using
  shared `OpenCrayMotion` tokens.
- Split `_SelectionToolbar` into two internal groups:
  - `_SelectionActionGroup` for ordinary actions
  - `_SelectionDangerAction` for delete
- Add stable keys for the ordinary and danger groups.
- Preserve `files-selection-toolbar`, `files-toolbar-action-copy`,
  `files-toolbar-action-paste`, and existing action behavior.

Acceptance:

- Entering and exiting selection mode reads as a temporary work mode, not a
  generic pop-in.
- Delete is visibly separated from reversible file operations.
- Toolbar remains pinned above the shell tab bar and short-directory cases still
  work.
- Existing copy/move/paste/delete tests keep passing.

### Files Breadcrumb And Search/Filter State

Files:

- `flutter_app/lib/features/files/files_feature.dart`
- `flutter_app/test/files_feature_test.dart`

Current behavior:

- Search is a plain field above the location card.
- The directory card changes empty text for no matches, but the page does not
  make the filtered state explicit.
- Breadcrumb chips use similar weight for parent/current path segments.

Target behavior:

- Make the location area read more like a file workbench:
  - current directory is the strongest label
  - parent breadcrumb segments are lighter
  - current segment is clearly non-clickable/current
- Search focus and active query should create an explicit filtered state:
  - search field border/background subtly changes while focused or filtered
  - directory list header/empty state says the list is filtered
  - no-result state is compact and actionable, not a large error card
- Do not hide the current path while filtering.

Implementation details:

- Convert `_SearchBar` to a small stateful/focus-aware widget, or pass focus
  state from the parent only if tests need it.
- Add `isFiltered` to `_DirectoryCard` and surface a compact filter status row
  above entries when query is non-empty.
- Update `_BreadcrumbChip` styling so the current segment has stronger text and
  parents are lighter.
- Keep breadcrumb keys unchanged.

Acceptance:

- Typing a query visibly moves the file list into filtered mode.
- Empty search results explain the active query without looking like a load
  failure.
- Breadcrumb navigation remains available outside selection mode and disabled
  during selection mode.

### Skills Search Results And Install Lifecycle

Files:

- `flutter_app/lib/features/skills/skills_feature.dart`
- `flutter_app/test/skills_feature_test.dart`

Current behavior:

- Manage/Install page switching and segmented tab motion are already fixed.
- Search uses debounce and a top linear progress indicator.
- Suggested skill rows remain in place while installing, but the install button
  only changes to `...`.
- Success/failure is mostly communicated through reload/toast behavior.

Target behavior:

- Keep each result card in place through preview/install/install-complete states.
- Replace `...` with an explicit compact progress state in the install button.
- After a successful install, show an inline installed state on the original
  result row until the next snapshot removes or reclassifies the row.
- On failure, show a restrained inline failed state while keeping the retry
  action available.
- Search loading should feel connected to results, not like a global page load.

Implementation details:

- Track pending install source ref as today, plus a short-lived
  `_recentlyInstalledSourceRefs` and `_failedInstallSourceRefs` set.
- Extend `_SuggestedRow` with `installState` rather than a bool-only installing
  flag.
- Use stable keys for install button states:
  - installing
  - installed
  - failed/retry
- Keep direct install card behavior aligned with suggested result behavior where
  practical.

Acceptance:

- Installing a suggested skill keeps the row in place.
- Button state changes from Install -> Installing -> Installed or Retry without
  requiring the user to find the row again.
- Search result ordering and preview behavior remain unchanged.

### Chat Run Trace Minor Density Pass

Files:

- `flutter_app/lib/features/chat/chat_feature_screen.dart`
- `flutter_app/test/chat_feature_screen_test.dart`

Scope:

- No large Chat motion changes in this revision.
- Only adjust small visual hierarchy if it is local to run trace state language:
  running, waiting approval, failed, done.
- Avoid changing runtime projection or snapshot logic in this UI polish branch.

Potential follow-up:

- Normalize state dot size/color and action text weight.
- Keep run trace cards subordinate to message bubbles unless action is required.

### Bottom Navigation Selected-State Feedback

Files:

- `flutter_app/lib/core/design/opencray_widgets.dart`
- shell/navigation widget tests if present

Current behavior:

- Bottom navigation already has a selected capsule and text weight transition.
- The selected icon also applies a tiny scale animation.

Target behavior:

- Keep the selected feedback precise and non-bouncy:
  - retain color/capsule/text-weight transition
  - remove or reduce icon scaling so the nav feels calmer
  - preserve item dimensions and hit targets

Acceptance:

- Selecting a tab has clear feedback without a spring/bounce feel.
- No layout shift in the bottom bar.

### Additional Polish Plan For Later

These are the next five polish candidates to track after the current
Files/Skills pass. They are intentionally written as implementation-ready plans,
but they are outside the current completed goal so this branch does not keep
expanding without a new implementation decision.

#### 1. Approval Surface Risk-Decision Card

Files:

- `flutter_app/lib/features/chat/chat_feature_screen.dart`
- `flutter_app/lib/core/copy/opencray_ui_copy.dart`
- `flutter_app/test/chat_feature_screen_test.dart`

Current behavior:

- The approval surface already distinguishes high risk with color, a side accent
  bar, a severity chip, and filled/outline action hierarchy.
- The remaining problem is not color. The remaining problem is that the user
  still has to read a mixed body to understand what is requested, what will be
  affected, why it is needed, and what happens after the decision.

Target behavior:

- Treat the active approval as a compact risk-decision card, not as a generic
  alert.
- Keep the existing color system as an auxiliary signal:
  - normal approval keeps blue/accent treatment
  - high-risk approval keeps the warm side bar and chip
  - no full orange/red card fill, because it makes dense command/path text
    harder to read and makes all high-risk requests feel equally severe
- Add non-color risk cues so severity is not carried by color alone.

Implementation details:

- Split the active approval card into four readable zones:
  - decision headline: one strong line for what is being requested
  - impact scope: affected path, working directory, tool, or session scope as
    compact key-value rows
  - reason/evidence: why the agent needs it, secondary in weight
  - decision bar: Reject, Approve for session, Approve using the current
    hierarchy
- Add stable labels or icons for impact rows such as Path, Tool, Scope, and
  Reason.
- For destructive or broad-scope approvals, show one short "will affect" line
  before the action buttons.
- Make queued approvals quieter:
  - active card shows the full structure
  - queued cards show title, risk chip, and one impact line only
  - queue count stays visible without competing with the active decision
- Refine decision completion:
  - pressed button enters a compact pending state in place
  - approved/rejected state resolves on the same card for a brief beat
  - the card then collapses upward and the next queued approval advances from
    the stack

Acceptance:

- A high-risk approval is distinguishable by text/structure even without color.
- The user can identify request, impact scope, and reason before reaching the
  action buttons.
- Compact phone layouts do not clip the three-action approval layout.
- Reduced-motion mode avoids stack travel and keeps state changes visible with
  opacity/color only.

#### 2. Settings And Agent Configuration State

Files:

- `flutter_app/lib/features/settings/settings_feature.dart`
- `flutter_app/lib/features/settings/agent_settings_pages.dart`
- `flutter_app/lib/features/settings/safety_settings_pages.dart`
- focused settings widget tests

Current behavior:

- Settings already has section grouping and danger tone support, but dense
  configuration pages can still read like stacked forms.
- Save/update/error feedback is often page-level or message-level rather than
  attached to the field or section that changed.

Target behavior:

- Make Settings feel more like a quiet control console:
  - current values are easy to scan
  - edited sections show pending/saved/failed state locally
  - destructive or security-sensitive settings remain visually distinct without
    becoming louder than normal controls

Implementation details:

- Add a section-level state language:
  - unchanged: flat surface, regular title
  - edited/pending: subtle accent divider or compact "Unsaved" label
  - saving: inline spinner or progress text in the section header
  - saved: short-lived check/state label in the same header position
  - failed: compact error row under the affected control
- For Agent configuration:
  - make risk tolerance, escalation rules, and allowed tools read as summary
    rows before deeper editing
  - keep advanced controls behind existing navigation instead of expanding the
    main page into a long form
  - preserve current save semantics and bridge contracts
- For Safety settings:
  - use danger tone only for genuinely destructive/security-sensitive sections
  - add concise impact text for settings that broaden permissions

Acceptance:

- Users can see which setting changed and whether it saved without scanning for
  a toast.
- Field-level errors are visible near the field or section that caused them.
- Section height stays stable during short pending/saved transitions.

#### 3. Skills Manage Installed-Card Lifecycle

Files:

- `flutter_app/lib/features/skills/skills_feature.dart`
- `flutter_app/test/skills_feature_test.dart`

Current behavior:

- Install search results now keep rows in place and show
  Installing/Installed/Retry inline.
- Manage page actions such as update, disable/enable, and delete still rely more
  on action sheets, reloads, and messages.

Target behavior:

- Bring installed skill cards into the same lifecycle language as install
  results:
  - Update becomes Updating -> Updated or Retry in place
  - Enable/Disable becomes Changing -> Enabled/Disabled or Retry in place
  - Delete starts from the owning card, then exits with a restrained collapse
    only after success

Implementation details:

- Add per-skill pending action state keyed by skill id and action type.
- Keep the card in place while the operation is pending.
- Convert primary card action labels to stable-width animated labels using
  shared motion tokens.
- For delete:
  - confirmation remains explicit
  - card enters deleting state after confirmation
  - successful deletion collapses/removes the card from its list position
  - failure restores the card with a Retry affordance
- Keep action sheet behavior for secondary operations, but reflect the chosen
  action back on the originating card.

Acceptance:

- Users do not need to find the affected skill again after update/delete.
- Failed operations leave a visible retry path on the same card.
- Manage list ordering does not jump during pending operations.

#### 4. Files Operation Execution Feedback

Files:

- `flutter_app/lib/features/files/files_feature.dart`
- `flutter_app/test/files_feature_test.dart`
- host bridge files only if operation progress becomes available

Current behavior:

- Selection mode and the operation toolbar now communicate browsing vs
  temporary work mode.
- Copy, move, paste, and delete execution feedback is still mostly immediate
  state/message driven. The operation surface does not yet show a clear
  in-progress or completed operation lifecycle.

Target behavior:

- Show file operations as concrete work attached to the file list:
  - Copy/Move selection enters a pending transfer state
  - Paste/Delete shows a local operation state
  - Success/failure is visible near the operation source or bottom workbench
    instead of relying only on transient messages

Implementation details:

- If the host exposes progress later:
  - add a compact bottom task strip above the shell tab bar
  - show operation type, item count, destination/source, and progress
  - keep the strip dismissible only after terminal success/failure
- If only bounded async completion exists:
  - show local pending labels on the toolbar action that was tapped
  - keep selected rows subtly marked while the operation is pending
  - on success, collapse selection mode after a brief resolved state
  - on failure, keep selection and show a compact retry/error row
- For delete:
  - keep delete separated from reversible actions
  - after confirmation, selected rows can fade/collapse toward their list
    origin only after host success

Acceptance:

- Users can tell whether a file operation is pending, succeeded, or failed.
- Failed operations do not silently clear selection.
- No new long-running animation is introduced for operations without progress.

#### 5. Global Empty, Loading, And Error States

Files:

- `flutter_app/lib/core/design/opencray_widgets.dart`
- `flutter_app/lib/features/chat/chat_feature_screen.dart`
- `flutter_app/lib/features/files/files_feature.dart`
- `flutter_app/lib/features/skills/skills_feature.dart`
- `flutter_app/lib/features/settings/settings_feature.dart`
- focused widget tests for updated state components

Current behavior:

- Each feature has local empty/loading/error treatments.
- Some states are compact and useful, while others can still read as generic
  blank space, page failure, or unrelated message text.

Target behavior:

- Create a shared quiet state language for high-frequency pages:
  - empty: what is empty and one next action
  - filtered empty: the active filter/search term and a clear/reset action
  - loading: preserves surrounding layout and avoids full-page flashes
  - error: compact reason plus Retry or relevant recovery action
- Keep states dense and operational, not marketing-like.

Implementation details:

- Add or consolidate a small shared state widget only if it removes real
  duplication across pages.
- Keep feature-specific copy where context matters, but normalize:
  - icon size
  - title/body text scale
  - action button hierarchy
  - spacing from page chrome
- For Chat:
  - avoid showing seed/placeholder content during real host loading
  - keep empty state subordinate to the composer
- For Files:
  - keep current path visible in empty and filtered-empty states
  - keep clear-search available for filtered empty
- For Skills:
  - search loading should keep previous results stable where possible
  - no-results should offer query refinement or direct install when applicable
- For Settings:
  - failed section loads should show Retry inside the section, not a whole-page
    dead end when other sections remain usable

Acceptance:

- Empty/error/loading states across major tabs feel like one system.
- Search no-results states are actionable, not dead ends.
- Loading states do not flash unrelated placeholder/seed content.

#### Future Polish Checklist

- [ ] Plan and implement Approval risk-decision card structure.
- [ ] Add Approval focused tests for non-color risk cues, queue advancement, and
  compact action layout.
- [ ] Plan and implement Settings/Agent section-level pending/saved/failed
  states.
- [ ] Add Settings focused tests for local save/error feedback.
- [ ] Plan and implement Skills Manage installed-card action lifecycle.
- [ ] Add Skills Manage tests for update/delete retry and stable card position.
- [ ] Plan and implement Files operation execution feedback.
- [ ] Add Files tests for pending/success/failure operation states.
- [ ] Plan and normalize global empty/loading/error states.
- [ ] Add focused tests for shared or normalized state components.

### High-Frequency Polish Checklist

- [x] Write Files/Skills/global polish plan before implementation.
- [x] Convert Files selection toolbar into a grouped temporary workbench surface.
- [x] Separate destructive Files delete action from reversible actions.
- [x] Add/adjust focused Files tests for toolbar grouping and selection mode.
- [x] Add explicit Files filtered state in search/list UI.
- [x] Lighten parent breadcrumbs and emphasize current directory.
- [x] Add/adjust focused Files tests for filtered state and breadcrumb hierarchy.
- [x] Replace Skills install `...` with explicit installing/installed/failed row
  states.
- [x] Add focused Skills tests for inline install state.
- [x] Remove or reduce bottom-nav icon scaling while preserving selected feedback.
- [x] Keep Chat changes limited to run-trace density only if a local cleanup is
  needed.
- [x] Run `dart analyze flutter_app`.
- [x] Run focused Files tests.
- [x] Run focused Skills tests.
- [x] Run shell navigation tests if bottom navigation changes.
- [x] Create a focused Conventional Commit in Chinese.

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
