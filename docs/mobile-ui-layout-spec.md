# OpenCray Mobile UI Layout Spec

Last updated: 2026-03-11

## Scope

This document defines a handoff-ready layout specification for OpenCray's Android app UI. The target visual direction is:

- Android platform conventions for navigation, back behavior, permissions, and system interaction
- Apple-inspired visual tone for spacing, hierarchy, density, and restraint
- Minimal and flat presentation with weak shadows, clear grouping, and high legibility

This spec is intended for both design and implementation. All values below use `dp` for layout and `sp` for text unless noted otherwise.

## Product structure

The current shell structure is:

- `Chat`
- `Skills`
- `Files`
- `Settings`

Settings subpages currently include:

- `Workspace Access`
- `LLM`
- `MCP`
- `Privacy & Telemetry`
- `Safety & Limits`
- `About & Version`
- `Personalization`

## Design goals

- Keep the first screenful quiet and easy to scan
- Use spacing, typography, and grouping as the main hierarchy tools
- Avoid Material-heavy chrome, large shadows, and loud color blocks
- Keep one primary action per screen or section
- Preserve Android interaction expectations even when the visual language feels closer to iOS

## Base device targets

Use `360dp` width as the primary compact layout reference.

Recommended layout buckets:

| Bucket | Width range | Default behavior |
| --- | --- | --- |
| Compact | `320dp` to `389dp` | Single-column layout, reduced horizontal padding where needed |
| Standard phone | `390dp` to `479dp` | Single-column layout with full spacing values |
| Large phone / small fold state | `480dp` to `599dp` | Single-column layout with slightly wider content regions |
| Tablet / expanded | `600dp+` | Optional split layout for dense work surfaces only |

Default implementation target for V1:

- Prioritize `360dp` to `430dp`
- Keep all critical actions reachable in single-column layouts
- Do not require dual-pane layouts for core flows

## Grid and spacing system

Use a `4dp` base grid. Most production spacing should land on the `8dp` rhythm.

### Spacing scale

| Token | Value | Use |
| --- | --- | --- |
| `space-1` | `4dp` | Hairline padding, icon separation |
| `space-2` | `8dp` | Tight internal spacing |
| `space-3` | `12dp` | Compact card padding, chip gaps |
| `space-4` | `16dp` | Standard horizontal page padding |
| `space-5` | `20dp` | Section internal spacing |
| `space-6` | `24dp` | Section gap, large card padding |
| `space-7` | `32dp` | Major vertical separation |
| `space-8` | `40dp` | Large-title breathing room |
| `space-9` | `48dp` | Hero spacing, oversized section separation |

### Default page padding

| Context | Horizontal padding | Top content padding | Bottom content padding |
| --- | --- | --- | --- |
| Compact phone | `16dp` | `12dp` after top bar | `24dp` above bottom nav safe area |
| Standard phone | `20dp` | `12dp` after top bar | `28dp` above bottom nav safe area |
| Large phone | `24dp` | `16dp` after top bar | `32dp` above bottom nav safe area |

### Section rhythm

Use these defaults unless a page explicitly overrides them:

- Gap between title block and first content block: `20dp`
- Gap between major sections: `24dp`
- Gap between cards in the same group: `12dp`
- Gap between label and field: `8dp`
- Gap between helper text and field: `6dp`

## Safe area and shell metrics

Respect system insets on every page. Do not fake iOS safe area values on Android.

### Top area

| Element | Height / spacing |
| --- | --- |
| Status bar inset | System-provided |
| Compact top app bar content height | `56dp` |
| Large title block top padding below app bar | `8dp` |
| Large title block bottom spacing | `20dp` |

### Bottom area

| Element | Height / spacing |
| --- | --- |
| Bottom nav visual height | `64dp` |
| Bottom nav top padding | `8dp` |
| Bottom nav bottom padding | `10dp` plus system inset |
| Gap from scroll content to nav container | `16dp` minimum |

### Shell rules

- Large-title pages should use a two-step top structure:
  - compact top bar for context and overflow actions
  - page title block for title and one-line summary
- Avoid stacking more than one persistent banner above page content
- Never place a page CTA under the bottom nav

## Corner radius system

| Token | Value | Use |
| --- | --- | --- |
| `radius-sm` | `8dp` | chips, tiny badges |
| `radius-md` | `12dp` | text fields, compact surfaces |
| `radius-lg` | `16dp` | standard cards, panels |
| `radius-xl` | `20dp` | bottom sheets, large cards |
| `radius-pill` | `999dp` | pills, segmented controls |

Default radius choices:

- Cards: `16dp`
- Inputs: `12dp`
- Buttons: `14dp`
- Bottom sheet: `20dp`

## Divider and elevation rules

- Divider thickness: `1dp`
- Divider color should stay low-contrast
- Default cards should not rely on heavy shadow
- Use one of these depth approaches only:
  - background contrast only
  - background contrast plus `1dp` border
  - very light shadow for floating surfaces such as bottom sheets

Avoid:

- large blurred shadows
- neon glows
- nested card-in-card depth stacks for routine content

## Typography sizes

These sizes are optimized for a restrained, Apple-influenced hierarchy while staying legible on Android.

| Role | Size | Weight | Line height |
| --- | --- | --- | --- |
| Display page title | `28sp` | SemiBold | `34sp` |
| Section title | `20sp` | SemiBold | `26sp` |
| Card title | `17sp` | Medium | `22sp` |
| Body | `15sp` | Regular | `22sp` |
| Secondary body | `14sp` | Regular | `20sp` |
| Label / button text | `15sp` | Medium | `20sp` |
| Caption / metadata | `13sp` | Regular | `18sp` |

Text rules:

- Use no more than three visible text weights on one screen
- Prefer medium emphasis through spacing and placement before using heavier text
- Secondary text should remain readable, not faint

## Component sizing

### Buttons

| Type | Height | Horizontal padding | Radius |
| --- | --- | --- | --- |
| Primary | `52dp` | `20dp` | `14dp` |
| Secondary | `52dp` | `20dp` | `14dp` |
| Tertiary inline | `40dp` | `12dp` | `12dp` |
| Icon-only | `40dp` | square | `12dp` |

Rules:

- Minimum touch target: `48dp`
- Primary button width should either fill container or fit content with strong alignment
- Keep no more than one filled primary button in the same visual group

### Inputs

| Type | Height | Radius | Internal horizontal padding |
| --- | --- | --- | --- |
| Single-line text field | `50dp` | `12dp` | `14dp` |
| Search field | `44dp` | `12dp` | `12dp` |
| Multi-line field min height | `112dp` | `12dp` | `14dp` |

Rules:

- Field label to field gap: `8dp`
- Field to helper/error gap: `6dp`
- Field to next field gap: `16dp`

### Cards

| Card type | Padding | Radius | Internal gap |
| --- | --- | --- | --- |
| Status card | `16dp` | `16dp` | `10dp` |
| Standard content card | `16dp` | `16dp` | `12dp` |
| Dense utility card | `14dp` | `16dp` | `10dp` |
| Large hero card | `20dp` | `20dp` | `12dp` |

### List rows

| Row type | Min height | Top/bottom padding | Internal horizontal gap |
| --- | --- | --- | --- |
| Standard row | `60dp` | `12dp` | `12dp` |
| Dense metadata row | `52dp` | `10dp` | `10dp` |
| File row | `64dp` | `12dp` | `12dp` |
| Settings entry row | `68dp` | `14dp` | `12dp` |

Rules:

- Use vertical centering
- Do not stack more than two text lines in default rows
- Trailing accessories should be visually light

### Chips and segmented controls

| Element | Height | Horizontal padding | Radius |
| --- | --- | --- | --- |
| Filter chip | `32dp` | `12dp` | `999dp` |
| Segmented control | `36dp` | `4dp` outer, `12dp` inner | `999dp` |

### Dialogs and bottom sheets

| Element | Value |
| --- | --- |
| Bottom sheet top radius | `20dp` |
| Bottom sheet side padding | `20dp` |
| Bottom sheet top padding | `16dp` |
| Bottom sheet bottom padding | `24dp` plus system inset |
| Dialog max width on phone | `320dp` |

## Global page templates

### Large-title page template

Use for most top-level tabs and Settings subpages.

| Section | Spacing |
| --- | --- |
| Top bar content height | `56dp` |
| Top bar to title block | `8dp` |
| Title to summary | `6dp` |
| Title block to first section | `20dp` |
| Section to section | `24dp` |

### Utility page template

Use for dense editor or manager surfaces where the page title can stay compact.

| Section | Spacing |
| --- | --- |
| Top bar content height | `56dp` |
| Top bar to first section | `12dp` |
| Section to section | `20dp` |

## Page-by-page layout spec

## Chat

Chat is the most important product surface. It should feel calm, readable, and focused.

### Chat page shell

| Element | Spec |
| --- | --- |
| Top bar height | `56dp` |
| Title block top gap | `8dp` |
| Title block bottom gap | `20dp` |
| Page horizontal padding | `16dp` compact, `20dp` standard |
| Message list bottom gap above composer | `12dp` |

### Chat header

| Element | Spec |
| --- | --- |
| Product title | `28sp` |
| Summary line gap below title | `6dp` |
| Right-side action button | `40dp` square |

### Message list

| Element | Spec |
| --- | --- |
| Gap between message groups | `16dp` |
| Gap between consecutive bubbles from same speaker | `8dp` |
| Bubble max width | `78%` of content width |
| Bubble internal padding | `12dp` vertical, `14dp` horizontal |
| Bubble radius | `16dp` |
| Tool card top gap | `8dp` |
| Tool card padding | `12dp` |

Rules:

- User bubbles align right
- Agent bubbles align left
- Avoid full-width tinted backgrounds for agent messages
- Tool cards should read as secondary insertions, not primary content

### Composer

| Element | Spec |
| --- | --- |
| Composer min height | `56dp` |
| Composer radius | `16dp` |
| Internal horizontal padding | `12dp` |
| Internal vertical padding | `10dp` |
| Attachment button size | `36dp` |
| Send button size | `40dp` |
| Composer to page bottom gap | `12dp` plus inset |

### Approval banner

| Element | Spec |
| --- | --- |
| Banner height | `44dp` to `52dp` |
| Banner radius | `12dp` |
| Banner bottom gap before composer | `8dp` |

## Skills

Skills should behave like a lightweight library and editor workbench, not an admin dashboard.

### Skills page shell

| Element | Spec |
| --- | --- |
| Page horizontal padding | `16dp` compact, `20dp` standard |
| Title block to quick actions gap | `20dp` |
| Group-to-group vertical gap | `24dp` |

### Quick actions row

| Element | Spec |
| --- | --- |
| Button height | `40dp` or `52dp` depending on emphasis |
| Gap between buttons | `8dp` |
| Row top/bottom spacing | `0dp` / `20dp` |

Rule:

- In the first screenful, show at most one filled button

### Filter / segmented control

| Element | Spec |
| --- | --- |
| Control height | `36dp` |
| Gap below control before list | `16dp` |

### Skill cards

| Element | Spec |
| --- | --- |
| Card min height | `92dp` |
| Card padding | `16dp` |
| Title to metadata gap | `6dp` |
| Card-to-card gap | `12dp` |

### Skill detail or editor page

| Element | Spec |
| --- | --- |
| Horizontal padding | `20dp` |
| Group gap | `24dp` |
| Field stack gap | `16dp` |
| Footer action row top gap | `24dp` |

## Files

Files is a bounded workbench, not a general filesystem browser. The layout must reinforce that scope.

### Files page shell

| Element | Spec |
| --- | --- |
| Page horizontal padding | `16dp` compact, `20dp` standard |
| Title block to workspace status card | `20dp` |
| Major section gap | `24dp` |

### Workspace status card

| Element | Spec |
| --- | --- |
| Min height | `104dp` |
| Padding | `16dp` |
| CTA top gap | `12dp` |

### Path and utility controls

| Element | Spec |
| --- | --- |
| Current path label bottom gap | `12dp` |
| Search field height | `44dp` |
| Search field bottom gap | `12dp` |
| Utility button row gap | `8dp` |

### File list

| Element | Spec |
| --- | --- |
| Section title bottom gap | `12dp` |
| File row min height | `64dp` |
| Row padding | `12dp` vertical, `0dp` extra horizontal beyond card/container padding |
| Row-to-row divider inset | `12dp` |

### Preview and editor panel

| Element | Spec |
| --- | --- |
| Panel top gap from list | `24dp` |
| Panel padding | `16dp` |
| Preview title to body gap | `10dp` |
| Editor field min height | `220dp` |
| Footer actions top gap | `16dp` |

Rule:

- On phones, keep preview/editor below the list rather than forcing split view

## Settings Home

Settings Home should act as a routing page with live status summaries.

### Settings Home shell

| Element | Spec |
| --- | --- |
| Page horizontal padding | `16dp` compact, `20dp` standard |
| Title block to first card | `20dp` |
| Entry card gap | `12dp` |

### Settings entry card

| Element | Spec |
| --- | --- |
| Min height | `72dp` |
| Padding | `16dp` |
| Title to summary gap | `4dp` |
| Trailing chevron zone width | `24dp` |

Rule:

- Settings Home should not include long inline forms

## Settings subpages

Use a shared pattern across all subpages.

### Shared subpage shell

| Element | Spec |
| --- | --- |
| Top back row height | `44dp` within the `56dp` top bar |
| Title block to status card | `20dp` |
| Major section gap | `24dp` |
| Card stack gap | `12dp` |

### LLM settings

| Element | Spec |
| --- | --- |
| Field stack gap | `16dp` |
| Toggle card min height | `84dp` |
| Helper note top gap | `8dp` |

### MCP settings

| Element | Spec |
| --- | --- |
| Master switch card min height | `92dp` |
| Server card min height | `112dp` |
| Server card action gap | `12dp` |

### Privacy and telemetry

| Element | Spec |
| --- | --- |
| Toggle card min height | `92dp` |
| Disclosure block padding | `16dp` |
| Disclosure paragraph gap | `8dp` |

### Safety and limits

| Element | Spec |
| --- | --- |
| Risk card min height | `124dp` |
| Card padding | `16dp` |
| Card-to-card gap | `12dp` |

### Personalization

| Element | Spec |
| --- | --- |
| Preset card min height | `88dp` |
| Preset grid gap | `12dp` |
| Custom overlay field stack gap | `16dp` |
| Danger zone section top gap | `32dp` |
| Reset token field height | `50dp` |

### About and version

| Element | Spec |
| --- | --- |
| Info card min height | `88dp` |
| Bullet list item gap | `8dp` |
| Card stack gap | `12dp` |

## Responsive rules

### Narrow phones

When width is below `360dp`:

- Keep horizontal padding at `16dp`
- Reduce large title size from `28sp` to `26sp`
- Collapse multi-action rows into two lines when needed
- Keep button height unchanged

### Wider phones

When width reaches `390dp+`:

- Increase default page padding to `20dp`
- Allow slightly wider message bubbles and cards
- Keep single-column layout

### Expanded layouts

When width reaches `600dp+`:

- Optional split view is allowed for:
  - `Skills`
  - `Files`
  - `Settings`
- `Chat` should remain single focus unless a validated tablet design is produced

## Implementation notes for Android developers

- All values should live in a shared spacing and sizing token file rather than inline literals
- Insets must be applied from system bars, not hardcoded
- Bottom navigation height should include visual chrome only; gesture inset remains separate
- Use scroll containers with generous top and bottom content padding
- Keep CTA rows stable; avoid shifting controls when helper text appears

Suggested token groups:

- spacing
- radius
- component heights
- page paddings
- typography styles

## Figma handoff notes

Designers should prepare:

- one base phone frame at `360 x 800`
- one wider phone frame at `393 x 852`
- components built from the spacing tokens in this document
- reusable page templates for:
  - large-title shell page
  - settings subpage
  - list-with-detail section
  - chat transcript plus composer

Developers should receive:

- a spacing token sheet
- component specs with min height and padding
- per-page redline exports for `Chat`, `Skills`, `Files`, and `Settings Home`

## Recommended first implementation pass

The first visual refinement pass should cover:

1. `Chat`
2. `Settings Home`
3. `Files`

These three screens define the product's first impression, hierarchy model, and working density.

## Open questions for the next design pass

- Whether `Skills` should remain a single scroll page on phone or use a separate detail route by default
- Whether `Files` preview should become a bottom sheet for phone instead of an inline panel
- Whether `Personalization` should keep cards only or introduce a segmented preset selector
