# Bubble Dropdown + Unified Capture — Design

Date: 2026-07-03
Branch: `claude/overlay-phase1`
Status: Approved for planning

## Goal

Change the overlay bubble so that tapping it opens an **animated dropdown of
actions** instead of opening the chat directly. Chat becomes one action among
several. Add a unified **Capture** action that lets the user mark a region of
the screen — either by dragging a **rectangle** or by **freehand "magic pencil"**
— and then ask a question about that region, with the selection shown as a
preview thumbnail in the chat.

## Motivation

Currently the bubble tap opens the WhatsApp-style chat panel, and secondary
actions (Open Claude, Circle search, Voice) live behind the chat header's `⋯`
menu. The user wants the bubble itself to be the launcher: a quick, animated
menu of everything the overlay can do, with chat demoted to one item. This makes
capture-based asks (draw/circle over the screen) first-class rather than buried.

## Non-Goals

- No change to the brain layer, drivers, selectors, or the `brain.ask()` flow.
- No change to the answer-capture (poll + notification) hybrid.
- Voice remains a disabled "soon" placeholder.
- No new permissions.

---

## Section 1 — Bubble tap → animated dropdown

**Behavior change:** `OverlayService.onBubbleTap` no longer opens the chat. It
toggles a **dropdown** that animates down from the bubble.

- The dropdown is a vertical stack of action buttons (pill or circular) that
  animate in with a **staggered slide-down + fade** (~40 ms stagger per item)
  and reverse-animate out on dismiss.
- Dismiss triggers: tapping the bubble again, tapping an item, or tapping
  outside the dropdown.
- The dropdown anchors under the bubble and flips its horizontal alignment based
  on which screen edge the bubble is snapped to (reuse the bubble-snap logic).

**Items, in order:**
| Icon | Label | Action |
|------|-------|--------|
| 💬 | Ask | Opens the existing chat panel (`showPanel` + resume session). |
| ✏️ | Capture | Enters capture mode (Section 2). |
| 🅰️ | Open Claude | `ClaudeNotificationListener.openLatest(this)`. |
| 🎤 | Voice | Disabled placeholder ("soon"). |

**New component:** `BubbleDropdownView` (a classic `View`/`LinearLayout`
subclass owning the item buttons and the enter/exit animation).

**Controller:** add `showDropdown(items)` / `hideDropdown()` to
`OverlayWindowController`, mirroring the existing menu pattern:
- A transparent outside-dismiss layer (like the current `menuScrim`) that closes
  ONLY the dropdown on an outside `ACTION_DOWN`.
- The dropdown window anchored via the existing bubble-position math.
- `raiseBubble()` after adding so the bubble stays above the dropdown.

---

## Section 2 — Capture mode (rectangle + magic pencil)

Selecting **✏️ Capture**:
1. Dismisses the dropdown.
2. Delays ~120 ms so overlay windows clear, then `captureScreenshot()` (existing
   accessibility path).
3. Shows a full-screen selection surface over the frozen, dimmed screenshot.

**Mode toggle** (bottom of the surface), two buttons:
- **▭ Rectangle** — existing drag-a-box → tight-rect crop.
- **✏️ Magic pencil** — freehand path; on release the path auto-closes, pixels
  **inside** the closed path are kept and everything outside is erased to white,
  then the result is cropped to the stroke's bounding box (with small padding).

**Shared surface details:**
- Dimmed screenshot underneath; the selected region is redrawn un-dimmed.
- Live preview: rectangle outline while dragging, or the ink trail while drawing.
- Instruction pill reflecting the active mode.
- **Back cancels**; a too-small selection (either mode) cancels.
- Both modes finish by handing exactly one `Bitmap` to the same `onDone(crop)`
  callback, so all downstream code is unchanged.

**Implementation:**
- Evolve `RegionSelectView` into a capture surface with a `mode` state
  (`RECTANGLE` / `FREEHAND`). To keep each path focused, the freehand behavior
  may be split into a small helper; the rectangle behavior is the current logic.
- Freehand masking: accumulate touch points into an `android.graphics.Path`; on
  `ACTION_UP` call `path.close()`. Compute the path bounds
  (`path.computeBounds`). Build the crop by:
  1. Create a bitmap the size of the (clamped, padded) path bounds.
  2. Translate the path to that bitmap's origin.
  3. Pre-fill the bitmap white, then `canvas.clipPath(translatedPath)` and draw
     the corresponding slice of the screenshot into it; pixels outside the path
     stay white (erased). (White, not transparent — chosen so Claude sees a
     clean image, not an alpha checkerboard.)
- Keep the view↔bitmap coordinate scaling exactly as it is today (screenshot
  drawn scaled-to-fill; `bitmapRect()` maps view space to bitmap pixels). The
  freehand path must be scaled to bitmap space with the same `sx`/`sy` factors.
- Extract the mask-build into a small pure-ish function
  (`maskToPath(screenshot, pathInViewSpace, viewW, viewH): Bitmap`) so the
  scaling/masking math can be reasoned about and spot-checked independently.

---

## Section 3 — Preview & ask flow

After capture (either mode):
- The crop becomes the one-shot `pendingAttachment` (already exists).
- The chat panel opens; the crop is attached to the next message via the
  existing `onSend` logic (`attach = pendingAttachment ?: …`).

**Refinement — attached-region preview:** add a small **attached-preview row**
above the chat input showing the pending crop as a thumbnail with a **clear (✕)**
control, so the user can see a region is attached and remove it before sending.
Today the crop only becomes visible after sending (as the message `thumb`).

- `AskPanelView` gains a way to show/clear a pending attachment thumbnail.
- Clearing the preview sets `pendingAttachment = null` in `OverlayService`.

---

## Section 4 — Files touched

**Removed / demoted:**
- `OverlayService.onBubbleTap` — opens dropdown instead of chat.
- `OverlayService.onMore`, the chat header `⋯` button, `BubbleMenuView`, and
  `OverlayWindowController.toggleMenu`/`hideMenu`/`menuScrim` — removed; those
  actions migrate into the bubble dropdown. (Verify no other caller depends on
  them before deleting.)
- Chat header keeps ✎ new / ✕ close only.

**Added / changed:**
- `overlay/BubbleDropdownView.kt` (new) — animated action dropdown.
- `OverlayWindowController` — `showDropdown` / `hideDropdown` (+ outside-dismiss
  layer, anchoring, raiseBubble); drop the menu methods.
- `overlay/RegionSelectView.kt` — add rectangle/freehand mode toggle + masking
  (or refactor into a `CaptureView`).
- `overlay/AskPanelView.kt` — add clearable attached-preview row above input.
- `OverlayService` — wire dropdown items; `onCapture()` replaces/absorbs
  `onCircleSearch()`; clear-attachment callback.

## Testing / verification

- Core unit tests are unaffected (selectors and brain untouched).
- The mask-build function is small and pure enough to sanity-check its output
  dimensions/behavior in isolation.
- On-device (user): bubble tap shows animated dropdown; Ask opens chat; Capture
  → rectangle crop and freehand masked crop each attach a correct preview and
  produce a region-referencing answer; Open Claude works; outside-tap and Back
  dismiss correctly; bubble stays above the dropdown; dropdown flips side when
  the bubble is snapped to either edge.

## Risks

| Risk | Mitigation |
|------|------------|
| Freehand coordinate scaling off (crop misaligned) | Same `sx`/`sy` mapping as the working rectangle path; isolate mask-build for spot-checking. |
| Removing the `⋯` menu breaks a hidden caller | Grep for `toggleMenu`/`BubbleMenuView`/`onMore` usages before deleting. |
| Dropdown window occludes the driven app (a11y list) | Dropdown is small and dismissed before any ask runs; capture freezes its own screenshot first, same as circle search today. |
| Animation jank on the overlay window | Keep it a simple translate+alpha on a short duration; no layout thrash. |
