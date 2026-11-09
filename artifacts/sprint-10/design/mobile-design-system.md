# Aegis Mobile Design System & Visual Specification

**Task ID:** S10.1  
**Attempt:** S10.1-design-a1  
**Date:** 2026-11-09  
**Designer:** S101Designer  

---

## 1. Visual Identity & Design Principles

The redesigned Aegis visual language moves away from the flat, industrial, engineering-console aesthetic of the initial prototype. Drawing inspiration from modern fintech interfaces (VietinBank iPay, MoMo, Revolut), Aegis adopts a **clean, confident, and humane aesthetic** centered around clarity of financial truth, tactile hierarchy, and effortless one-handed thumb interaction.

### Key Pillars:
1. **Generous Touch Geometry:** Interactive elements conform to a minimum $48\times 48\text{dp}$ touch target standard.
2. **Tactile Soft Surfaces:** Card corners and sheet containers use friendly $16\text{dp}$–$20\text{dp}$ radii, with $12\text{dp}$ on buttons and $8\text{dp}$ on chips/badges.
3. **High-Contrast Editorial Typography:** Clear tabular numbers for VND figures, prominent weights ($600$–$700$) for amounts, and balanced line heights preventing vertical clipping.
4. **Non-Color Dependent Status Hierarchy:** Every status (Confirmed, Pending, Conflict, Cancelled) pairs its color with an unambiguous icon glyph and an explicit localized label.
5. **System Insets & Ergonomics:** Safe area insets (Status Bar, Navigation Bar, Dynamic Island, and Software Keyboard IME) are integrated into the root layout without jitter or content cut-off.

---

## 2. Design Tokens: Color System

The color system supports both **Light Mode** and **Dark Mode** with strict contrast ratios exceeding WCAG 2.1 AA standards ($>4.5:1$ for normal text, $>3:1$ for large headings and interactive components).

### 2.1 Light Theme Tokens
- `--bg-canvas`: `#F4F6F9` (Soft cool grey-blue backdrop)
- `--bg-surface`: `#FFFFFF` (Pure white card and modal containers)
- `--bg-surface-elevated`: `#FFFFFF` with soft box shadow `0 4px 16px rgba(15, 23, 42, 0.06)`
- `--bg-surface-subtle`: `#F1F4F8` (Pill backgrounds, secondary chips)
- `--fg-primary`: `#0F172A` (Slate 900, primary text and figures)
- `--fg-secondary`: `#475569` (Slate 600, secondary descriptions, metadata)
- `--fg-muted`: `#94A3B8` (Slate 400, placeholder text, disabled states)
- `--border-subtle`: `#E2E8F0` (Slate 200, card outlines and dividers)
- `--border-focus`: `#0284C7` (Sky 600, active input focus ring)

#### Brand & Functional Accent Colors:
- `--brand-primary`: `#0284C7` (Sky 600, primary actions, active tabs)
- `--brand-primary-container`: `#E0F2FE` (Sky 100, active icon background)
- `--brand-on-primary`: `#FFFFFF`
- `--brand-gradient`: `linear-gradient(135deg, #0284C7 0%, #0369A1 100%)` (Hero balance card)

#### Financial Semantic Roles:
- `--semantic-expense`: `#E11D48` (Rose 600, money spent, outgoing cashflow)
- `--semantic-expense-bg`: `#FFE4E6` (Rose 100)
- `--semantic-income`: `#059669` (Emerald 600, money received, incoming cashflow)
- `--semantic-income-bg`: `#D1FAE5` (Emerald 100)
- `--semantic-transfer`: `#6366F1` (Indigo 500, internal movement between accounts)
- `--semantic-transfer-bg`: `#EEF2FF` (Indigo 100)
- `--semantic-warning`: `#D97706` (Amber 600, conflicts, possible duplicates)
- `--semantic-warning-bg`: `#FEF3C7` (Amber 100)
- `--semantic-info`: `#0284C7` (Sky 600, proposed links, agent notes)
- `--semantic-info-bg`: `#E0F2FE` (Sky 100)

---

### 2.2 Dark Theme Tokens
- `--bg-canvas`: `#0B0F17` (Deep slate-black backdrop)
- `--bg-surface`: `#131923` (Elevated dark card container)
- `--bg-surface-elevated`: `#1B2332` (Modals, bottom sheets, dropdown menus)
- `--bg-surface-subtle`: `#18202E` (Secondary buttons, search fields)
- `--fg-primary`: `#F8FAFC` (Slate 50, crisp primary text)
- `--fg-secondary`: `#94A3B8` (Slate 400, secondary text)
- `--fg-muted`: `#64748B` (Slate 500, disabled text, hints)
- `--border-subtle`: `#1E293B` (Slate 800, subtle borders)
- `--border-focus`: `#38BDF8` (Sky 400)

#### Brand & Functional Accents (Dark Mode):
- `--brand-primary`: `#38BDF8` (Sky 400, high legibility on dark surfaces)
- `--brand-primary-container`: `#0C344E` (Deep navy container)
- `--brand-on-primary`: `#07131E`
- `--brand-gradient`: `linear-gradient(135deg, #0C4A6E 0%, #07273C 100%)`

#### Financial Semantic Roles (Dark Mode):
- `--semantic-expense`: `#FB7185` (Rose 400)
- `--semantic-expense-bg`: `#3B1219`
- `--semantic-income`: `#34D399` (Emerald 400)
- `--semantic-income-bg`: `#082F24`
- `--semantic-transfer`: `#818CF8` (Indigo 400)
- `--semantic-transfer-bg`: `#1E1B4B`
- `--semantic-warning`: `#FBBF24` (Amber 400)
- `--semantic-warning-bg`: `#362106`
- `--semantic-info`: `#38BDF8` (Sky 400)
- `--semantic-info-bg`: `#0C2B42`

---

## 3. Typography Scale (Tabular Numerals & Dynamic Type)

Financial applications require strict numeric alignment. The typography scale utilizes system sans-serif fonts (`-apple-system`, `BlinkMacSystemFont`, `Roboto`, `Segoe UI`, `Inter`) with `font-variant-numeric: tabular-nums` to ensure exact column alignment of monetary amounts.

| Style Role | Font Size | Line Height | Font Weight | Letter Spacing | Usage Context |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Display Hero** | $32\text{sp}$ / $32\text{px}$ | $40\text{px}$ | Bold ($700$) | $-0.5\text{px}$ | Hero Balance Card, Main Amount Input |
| **Title Large** | $22\text{sp}$ / $22\text{px}$ | $28\text{px}$ | SemiBold ($600$) | $-0.2\text{px}$ | Top App Bar Titles, Sheet Headings |
| **Title Medium** | $18\text{sp}$ / $18\text{px}$ | $24\text{px}$ | SemiBold ($600$) | $0\text{px}$ | Section Headers, Transaction Amounts |
| **Body Large** | $16\text{sp}$ / $16\text{px}$ | $22\text{px}$ | Regular ($400$) | $0\text{px}$ | Form Field Inputs, Note Body Text |
| **Body Medium** | $14\text{sp}$ / $14\text{px}$ | $20\text{px}$ | Regular ($400$) | $0\text{px}$ | Transaction Descriptions, Subtext |
| **Label Medium** | $13\text{sp}$ / $13\text{px}$ | $18\text{px}$ | Medium ($500$) | $+0.2\text{px}$ | Feature Icon Labels, Tab Bar Labels |
| **Label Small** | $11\text{sp}$ / $11\text{px}$ | $14\text{px}$ | SemiBold ($600$) | $+0.5\text{px}$ | Badges, Status Tags, Caps Captions |

### Dynamic Scaling Guidelines:
- The design supports accessibility text scaling from $1.0\times$ up to $1.5\times$ without text clipping.
- Touch target heights and padding flex with container content (`min-height: 48dp; height: auto`).
- Numbers truncate with standard ellipses (`... ₫`) only under extreme magnification ($>1.5\times$), preserving full amounts on primary focus cards.

---

## 4. Spacing Scale & Elevation

Aegis is based on a clean $4\text{dp}$ base grid ($8\text{dp}$ layout rhythm):

- `space-1`: $4\text{dp}$ (Tight gaps between badge icons and text)
- `space-2`: $8\text{dp}$ (Standard spacing between form elements and chip lists)
- `space-3`: $12\text{dp}$ (Internal card padding, button gap)
- `space-4`: $16\text{dp}$ (Standard horizontal screen margin, card padding)
- `space-5`: $20\text{dp}$ (Vertical separation between distinct card groups)
- `space-6`: $24\text{dp}$ (Header bottom margin, modal margins)
- `space-8`: $32\text{dp}$ (Hero section spacing, bottom clearance)

### Elevation & Corner Radii:
- **Badge / Chip:** `radius-sm` ($8\text{dp}$)
- **Button / Input Field:** `radius-md` ($12\text{dp}$)
- **Card / Feed Container:** `radius-lg` ($16\text{dp}$)
- **Hero Card / Bottom Sheet:** `radius-xl` ($20\text{dp}$–$24\text{dp}$)
- **Avatar / Icon Squircle:** `radius-full` ($50\%$) or squircle ($14\text{dp}$ on $48\text{dp}$ box)

---

## 5. Feature Icon System & Home Grid Layout

A core requirement from the owner is replacing vertically stacked functional cards with **clearly labelled feature icons opening dedicated screens**.

### 5.1 Feature Icon Specifications:
- **Bounding Box:** $64\times 72\text{dp}$ total footprint (including label).
- **Icon Squircle Container:** $48\times 48\text{dp}$ minimum touch surface.
- **Corner Radius:** $14\text{dp}$ continuous curvature squircle.
- **Visual Style:** High-contrast, friendly dual-tone vector glyph with distinct thematic tint per functional domain.
- **Label:** $12\text{sp}$ / $13\text{sp}$ Medium weight, centered, max 2 lines, high contrast (`#0F172A` / `#F8FAFC`).

### 5.2 The 8 Primary Home Feature Launchers:
1. **Sổ giao dịch (Ledger):** Icon: Book / Receipt list. Tint: Blue (`#0284C7`). Target: `LedgerScreen`.
2. **Báo cáo chi tiêu (Analytics):** Icon: Donut / Bar chart. Tint: Purple (`#8B5CF6`). Target: `AnalyticsScreen`.
3. **Ghi chép nhanh (Quick Capture):** Icon: Pen / Note journal. Tint: Emerald (`#10B981`). Target: `CaptureChatScreen`.
4. **Duyệt tin biến động (Draft Review):** Icon: Bank notification bell / badge. Tint: Amber (`#F59E0B`). Target: `ReviewInboxScreen` (shows badge count).
5. **Thêm giao dịch (Manual Record):** Icon: Plus in circle / Calculator. Tint: Sky (`#0EA5E9`). Target: `ManualRecordScreen`.
6. **Danh mục & Quy tắc (Categories & Rules):** Icon: Tag & Sliders. Tint: Rose (`#F43F5E`). Target: `CategoryRulesScreen`.
7. **Kết nối ngân hàng (Bank Sync):** Icon: Bank building / Shield. Tint: Indigo (`#6366F1`). Target: `BankNotificationSettingsScreen`.
8. **Cài đặt mô hình AI (AI Settings):** Icon: Sparkles / Brain. Tint: Violet (`#7C3AED`). Target: `HostedProviderSettingsScreen`.

---

## 6. Bottom Navigation Shell & Ergonomics

The bottom navigation bar adheres to standard mobile thumb-reach guidelines:
- **Height:** $64\text{dp}$ + system navigation bar safe area inset (`WindowInsets.navigationBars`).
- **5 Tabs:**
  1. **Trang chủ (Home):** Home icon glyph.
  2. **Sổ quỹ (Ledger):** Receipt list glyph.
  3. **Ghi chép (Capture):** Sparkle note glyph.
  4. **Duyệt tin (Review):** Notification badge glyph with dynamic badge counter (`n`).
  5. **Cài đặt (Settings):** Gear cog glyph.
- **Active State:** Highlighted in primary brand accent (`#0284C7` / `#38BDF8`) with subtle indicator pill and SemiBold label.
- **Inactive State:** Slate muted (`#64748B` / `#94A3B8`).

---

## 7. Back & Up Navigation Hierarchy

- **Root Destinations:** Switching tabs resets to the root of that destination.
- **Secondary Screens:** Standard Top App Bar with back navigation arrow (`<-`). Tapping back or triggering system back pops one level in the destination stack.
- **Deep Sheets / Modals:** Downward pull handle or top-right Close button (`X`), popping back to the calling screen.
- **Form Protection:** If an editor screen has unsaved edits, navigating back presents a non-destructive confirmation dialog: *"Bỏ thay đổi hiện tại?"* (Discard changes?) with "Tiếp tục sửa" and "Bỏ thay đổi".
