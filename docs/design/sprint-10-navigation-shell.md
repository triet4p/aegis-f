# Sprint 10 navigation shell and shared design system (S10.2)

**Task:** S10.2 — Implement the design system and navigation shell  
**Attempt:** S10.2-implementation-a1  
**Status:** implementation complete; independent evidence review pending (Bronze checkpoint after PASS)  
**Design baseline:** [specification](./sprint-10-mobile-experience-specification.md), [design tokens](../../artifacts/sprint-10/design/mobile-design-system.md), [file mapping §§2–4](../../artifacts/sprint-10/design/backend-and-file-mapping.md)

This note records what the S10.2 cutover actually built, where it lives, and which later task
owns each screen's visual replacement. Runtime evidence lives in
[the worker report](../../artifacts/sprint-10/workers/S10.2-implementation-a1.md) and its device
captures, not in this note.

## One shell, five roots, eight launchers

`AegisAppShell` (`ui/navigation/AegisNavShell.kt`) owns navigation only:

| Root tab | Root screen | Child routes |
| --- | --- | --- |
| Home | `HomeLauncherScreen` — exact spending glance with mask, eight labelled launchers | Analytics (reports), Manual record, Categories & rules, Settings, Bank notification settings, Hosted provider settings, scoped records list, record detail, record edit |
| Ledger | saved-transactions list (root mode: no Back) | record detail, record edit |
| Notes | local note journal (no hosted panel) | manual record, record from a note event |
| AI Chat | hosted-agent exchange (moved out of the note journal) | record from a note event |
| Review | bank-draft inbox (root mode: no Back) | draft detail |

The Home launchers open: Ledger, Analytics, Notes, AI Chat, Review, Manual record,
Categories & rules and Settings. Ledger/Analytics/…/Settings are real destinations; no launcher
opens a placeholder.

The bottom bar is shown only at a root. A child route (form, receipt, settings child) hides it, so
that screen has one task and one Back. System Back pops one child route, returns a non-Home root to
Home, and leaves the app from Home's root. Each destination keeps its own route stack and each route
keeps its saveable state (filters, pages, half-written form input); a process restart restores the
destination, its route and that state.

## Shared design system

| File | Provides |
| --- | --- |
| `ui/theme/Color.kt` | light/dark Material schemes, financial semantic roles (spending, income, transfer, warning, info) |
| `ui/theme/Type.kt` | the design type scale with tabular figures |
| `ui/theme/Shapes.kt` | chip/button/card/sheet radii |
| `ui/theme/AegisTheme.kt` | one theme entry point plus the language, in-app light/dark mode resolution |
| `ui/text/AegisStrings.kt` | English baseline and Vietnamese parity for the shell and shared primitives |
| `ui/components/AegisGlyph.kt` | the shared 24-unit vector vocabulary |
| `ui/components/AegisFeatureIcon.kt` | labelled feature launcher and its grid |
| `ui/components/AegisBottomBar.kt` | the five-destination bar (selected by weight + pill, not color alone) |
| `ui/components/AegisControls.kt` | exact-money text (`VndMoney` digits, `₫`, masking), status chip with glyph+label, setting row, bottom-sheet choice, unsaved-changes dialog |

Language and theme are the user's presentation choices, stored by `AegisUiPreferencesStore` in
`aegis_ui_preferences` (not ledger data, not credentials). English and follow-system-theme are the
defaults; the Settings hub switches both.

## Cutover decisions

- The former four-destination shell (`ui/AppShell.kt`), its `Manual` hub and its combined
  "Chat / Quick Capture" screen wiring are retired. The note journal and the conversation are now
  separate destinations; the hosted-agent panel moved unchanged into
  `ui/chat/AiChatScreen.kt` (visual streaming redesign is S10.5).
- The existing `DashboardScreen` (reports and drill-down) is reached through Home's Analytics
  launcher; it keeps its exact figures and scope. The S10.3 Home/Analytics/Ledger/Detail redesign
  replaces these screens and removes the temporary Home glance.
- Notes, AI Chat, Review, Categories, Settings and the record form keep their existing visuals and
  behaviour in this task; S10.4–S10.8 own their visual replacement.
- The record form gained one navigation protection: leaving with unsaved edits asks for an explicit
  discard. The shell itself never discards saveable state.
- Financial/backend contracts did not change: the same query/command services, exact `VndMoney`
  totals, draft-confirmation boundary, category provenance and masking/consent paths are used. The
  only formatter addition is an optional currency suffix on `VndMoney.formatAmount`.
