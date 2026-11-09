# Mobile Financial & Productivity Apps Benchmark Research

**Task ID:** S10.1  
**Attempt:** S10.1-design-a1  
**Date of Research & Inspection:** 2026-11-09  
**Researcher / Designer Role:** S101Designer (Designer and Vision Specialist)  

---

## 1. Executive Summary & Objective

The owner of Aegis rejected the current frontend user experience as unattractive, rigid, and cognitively fatiguing. Specifically, vertically stacking functional cards on a single endless scrolling screen was identified as an unacceptable information architecture. The owner explicitly directed the adoption of **clearly labelled feature icons that open focused screens**, predictable navigation, and task-specific workflows, referencing market-leading Vietnamese fintech applications **MoMo** and **VietinBank iPay**, alongside modern international financial apps (e.g., **Revolut**, **Monzo**).

This research document records direct visual audits, architectural teardowns, and interaction pattern analyses of these reference applications. All primary visual assets were fetched directly from official application distribution platforms (Apple App Store, Google Play Store, VietinBank official portals, and MoMo developer portals) and visually inspected via multimodal image analysis.

---

## 2. Primary References & Visual Inspection

### 2.1 VietinBank iPay Mobile (v6.x)
- **Primary Source:** Apple App Store Vietnam (`id689963454`), VietinBank Digital Portal (`https://ipay.vietinbank.vn/`, `https://www.vietinbank.vn/ls/`).
- **Access Date:** 2026-11-09.
- **Visual Assets Inspected:** 6 high-definition device mockups (`vietinbank_shot_1.webp` through `vietinbank_shot_6.webp`), captured at $600\times 1300\text{px}$.

#### Observed Patterns & Architecture:
1. **Header Zone:**
   - User profile avatar, localized greeting, loyalty badge level ("Mới"), notification bell with unread count badge (`9+`), and settings shortcut gear icon.
   - Status bar respects system notch / dynamic island with clear separation.
2. **Hero Balance Card:**
   - Prominently displays masked account number (`**** **** 6690`) and total balance in VND (`*** *** *** VND`).
   - Integrated **Eye Toggle** icon for instantaneous privacy masking in public environments.
   - Quick action pill buttons anchored directly beneath the balance card: *"Tài chính cá nhân"* (Personal finance) and *"QR của tôi"* (My QR).
3. **Modular Feature Icon Grid:**
   - 3-column / 4-column grid of rounded, clean icon containers with dual-tone illustration and high-contrast, unambiguous textual labels.
   - Key features: *"Chuyển tiền trong nước"* (Domestic transfer), *"Thanh toán hoá đơn"* (Bill payment), *"Tiết kiệm"* (Savings), *"Dịch vụ vay & tín dụng"* (Loans), *"Đầu tư chứng khoán"* (Stocks).
   - Bottom pill button: *"Xem tất cả dịch vụ"* (View all services) which expands a structured directory of secondary services.
4. **Dedicated Directory Screens:**
   - When expanding services, the app opens a dedicated screen categorized by domain cards:
     * *Thanh toán hóa đơn* (Utilities: Electricity, Water, Internet, Tuition).
     * *Giải trí & Phong cách sống* (Lifestyle: Cinema, Store management, Fund certificates).
     * *Du lịch & Di chuyển* (Travel: Flights, Trains, Hotels, Taxis).
5. **Theme Support:**
   - Complete visual parity between Light Theme (fresh cyan/blue with crisp white surfaces) and Dark Theme (signature deep blue/slate "VietinBank PREMIUM" palette).

#### Applicability to Aegis:
- **Adopt:** The Hero Balance Card with privacy toggle, the 4-column labelled feature icon grid opening dedicated screens, the grouped secondary service directory, and the dual-tone icon aesthetics.
- **Omit / Inapplicable:** Banking-specific products (credit lines, billers, loans, gold/stocks, soft OTP, eKYC face scan) and proprietary branding assets.

---

### 2.2 MoMo (Vietnam Fintech Super-App)
- **Primary Source:** Apple App Store Vietnam (`id918751511`), M-N Associates Brand Architecture (`https://m-n.associates/works/momo/`), MoMo Partner Guidelines.
- **Access Date:** 2026-11-09.
- **Visual Assets Inspected:** 6 high-definition screenshots (`momo_shot_1.webp` through `momo_shot_6.webp`), captured at $600\times 1300\text{px}$.

#### Observed Patterns & Architecture:
1. **Top Header & Quick Actions Bar:**
   - Greeting ("Chào buổi sáng! [Tên]") with notification bell and chat support.
   - 4 primary utility squircle buttons placed in the prime thumb reach zone:
     * *Nạp/Rút* (Deposit/Withdraw)
     * *Nhận tiền* (Receive money)
     * *QR Thanh toán* (Scan QR)
     * *Ví tiện ích* (Utility wallet)
2. **Multi-Wallet Balance Strip:**
   - Eye privacy toggle concealing balance values (`********`).
   - Horizontal status breakdown: Main Wallet balance, High-yield micro-savings (*Túi Thần Tài*), and Credit line (*Ví Trả Sau*).
   - Direct link to *"Trung tâm Tài chính"* (Financial Center).
3. **Core Feature Grid:**
   - 4-column icon grid with concise labels: *Chuyển tiền*, *Thanh toán hóa đơn*, *Nạp tiền điện thoại*, *Data 4G/5G*, *Túi Thần Tài*, *Ví Trả Sau*, *Xem thêm dịch vụ*.
4. **Expense Management Sub-App ("Quản lý Chi tiêu"):**
   - Direct inspection of `momo_shot_4.webp`:
     * Navigation bar with Back arrow (`<-`), screen title *"Quản lý chi tiêu"*, and search/help icons.
     * Month selector control with chevrons: `< Tháng 10/2024 >`.
     * Two side-by-side metric cards:
       - **Chi tiêu (Spending):** `4.567.000đ` with period comparison indicator (`↑ 948.600đ cùng kỳ`).
       - **Thu nhập (Income):** `1.670.000đ` with period comparison indicator (`↑ 148.600đ cùng kỳ`).
       - Net cashflow summary pill: `Thu - Chi = -2.897.000đ`.
     * Donut chart visualizing category proportions with clear percentage tags (30%, 22%, 20%, 12%, 10%, 5%, 1%).
     * Sub-tabs for granular inspection: *"Danh mục con"* (Sub-categories) vs *"Danh mục cha"* (Parent categories).
     * Categorized list items with category icons, total VND amounts, and chevrons drilling into individual transactions.
5. **Persistent Bottom Navigation Bar:**
   - 5 anchored tabs: *Trang chủ (Home)*, *Ưu đãi (Deals)*, *Quét mã (Scan)*, *Lịch sử GD (History/Ledger)*, *Ví của tôi (Profile/Settings)*.

#### Applicability to Aegis:
- **Adopt:**
  - The side-by-side Spending vs. Income metric cards and net cashflow indicator.
  - The month selector control `< Tháng MM/YYYY >`.
  - Category breakdown visualization (proportional bar / donut) with drill-down into filtered transaction records.
  - The 5-tab bottom navigation shell for root destinations.
  - Privacy eye toggle for financial masking.
- **Omit / Inapplicable:** Super-app marketing carousels, gamification widgets (Heo Đất, Thần Tài wheel spins), partner vouchers, and BNPL credit products.

---

### 2.3 International Financial Apps (Revolut & Monzo)
- **Primary Sources:** Revolut UI Evolution teardown, Monzo Design Community, Making Monzo sneak peeks.
- **Access Date:** 2026-11-09.

#### Observed Patterns & Architecture:
1. **Transaction Detail Layout:**
   - Digital receipt styling: Large bold transaction amount at the top (`- 150.000 ₫` in primary text, or `+ 5.000.000 ₫` in accent green).
   - Counterparty / Merchant avatar with merchant name and timestamp.
   - Status tag: Confirmed, Pending, Duplicate, or Conflict.
   - Key-value detail rows: Source bank, account number, category badge with color swatch, note/memo, and linked attachments.
   - Action buttons at the bottom: Edit, Split, Re-categorize, and Delete.
2. **Review & Reconciliation Inbox:**
   - Distinct grouping between items needing immediate user attention (Conflicts, Missing Categories) and items ready for bulk/single confirmation.
   - Progressive disclosure: cards show core facts (Amount, Source, Time, Conflict reason); tapping expands full resolution context.

---

## 3. Synthesis: Design Principles for the Aegis Redesign

From the audited benchmarks and the owner's usability feedback, we establish six foundational principles for the Aegis redesign:

1. **Feature Icons Over Stacking Cards:**
   - The Home screen must not be an infinite stack of every functional widget in the system.
   - Routine capabilities are exposed as labelled, high-contrast, rounded feature icons arranged in a clean grid (e.g., $4\times 2$ or $4\times 3$). Tapping an icon opens a focused, dedicated screen.
2. **Dedicated, Task-Focused Screens:**
   - Each screen owns one specific workflow:
     * Viewing analytics & trends.
     * Browsing and filtering the ledger.
     * Capturing quick notes & conversational AI.
     * Reviewing incoming bank drafts.
     * Recording or editing transactions by hand.
     * Managing categories and rules.
     * Configuring notification listeners and hosted models.
   - Every secondary screen features a standard Top App Bar with a clear title and an unambiguous Back navigation action (`<-`).
3. **Clarity of Financial Truth (VND & Ledger Invariants):**
   - Amounts are formatted with standard Vietnamese thousands separators (`.` or `,`) and the authoritative `₫` symbol (e.g., `150.000 ₫`).
   - Strict separation between Spending (Expense), Inflow (Income), and Internal Transfers.
   - Unconfirmed drafts and unrecorded notes are never merged into confirmed ledger totals.
4. **Conflict-First, Evidence-Backed Review:**
   - Incoming drafts requiring decisions (possible duplicates, proposed links, missing categories) are visually highlighted and prioritized before ready-to-confirm drafts.
   - Reversible linking: links between notes and bank drafts explicitly show their state (`Attached`, `Proposed`, `Rejected`) with `Attach`, `Reject`, and `Unlink` controls.
5. **Privacy by Default:**
   - An eye toggle on the Home dashboard allows masking all financial values (`*** *** ₫`) with a single tap, preserving privacy in public spaces.
   - Provider API keys and secret endpoints are masked in settings.
6. **Ergonomic Mobile Touch Targets & Accessibility:**
   - All interactive touch targets strictly meet or exceed $48\times 48\text{dp}$.
   - System insets (status bar, navigation bar, keyboard IME) are handled cleanly with zero overlap or clipped content.
   - Visual status indicators do not rely on color alone (combining icons, badges, and unambiguous textual labels).
