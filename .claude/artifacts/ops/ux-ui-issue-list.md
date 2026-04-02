# UX/UI Issue Discovery — Pass 1

**Scope**: Full repo (UI focus)
**Commit**: 5fd1db3
**Date**: 2026-04-02

---

## Issues

### UX-001: Drag panel has no touch support and no clamp at drag time
- **Severity**: Medium
- **File**: `/Users/leif/Code/noumenon/ui/src/noumenon/ui/core.cljs:282-298`
- **Description**: The ask-panel drag implementation listens to `mousedown`/`mousemove`/`mouseup` only. No `touchstart`/`touchmove`/`touchend` handlers exist. Additionally, panel position is written raw to state with no viewport clamping during the drag — only the initial card-chrome clamping at render time — so the panel can be dragged fully off-screen on any axis.
- **Evidence**: `(.addEventListener js/document "mousemove" (fn [e] (when-let ... (state/dispatch! [:action/ask-panel-move {:x (- (.-clientX e) offset-x) :y ...}]))))` — no clamp, no touch events.
- **Impact**: Users on touch-capable devices (tablets, touch-screen laptops) cannot drag the panel. Any user who aggressively drags off-screen loses the panel with no recovery short of page reload.
- **Recommendation**: Clamp `x`/`y` in `:action/ask-panel-move` against `(- js/window.innerWidth panel-width)` and `0`. Add a "reset panel" escape hatch (double-click handle or Escape when panel is off-screen). Add touch event handlers mirroring the mouse ones.

---

### UX-002: Suggestion ticker pills are not keyboard-accessible
- **Severity**: High
- **File**: `/Users/leif/Code/noumenon/ui/src/noumenon/ui/views/ask.cljs:343-372`
- **Description**: The suggestion ticker renders `<button>` elements but they scroll continuously off-screen. There is no way for a keyboard user to Tab through them: the buttons are clipped behind a `mask-image` and the ticker div has no `aria-label`. Screen readers will announce an undifferentiated list of buttons with no context.
- **Evidence**: The outer ticker div has no ARIA role or label. The inner buttons have no `aria-label`; their text content is the full suggestion string but is never visible at rest to a screen reader. No `tabIndex -1` is applied to off-screen duplicates (the doubled list has no distinction).
- **Impact**: Keyboard and screen-reader users cannot discover or use suggestions. The feature is entirely inaccessible to non-pointer users.
- **Recommendation**: Add `role="region"` and `aria-label="Suggested questions"` to the ticker div. For the second half of the doubled list (the loop copies), set `tabIndex={-1}` and `aria-hidden="true"`. Provide a static fallback list (visually hidden but SR-readable) with `role="list"`.

---

### UX-003: `ask-run-suggestion` only sets query text — does not submit
- **Severity**: High
- **File**: `/Users/leif/Code/noumenon/ui/src/noumenon/ui/state.cljs:220-221`
- **Description**: Clicking a suggestion pill dispatches `:action/ask-run-suggestion`, which only sets `ask/query`. The user must then press Enter or click the submit arrow. There is no visual affordance indicating this extra step is required. The label "run suggestion" implies immediate execution.
- **Evidence**: `(defmethod handle-event :action/ask-run-suggestion [state [_ question]] {:state (assoc state :ask/query question)})` — no `:fx` dispatch to submit.
- **Impact**: Users click a suggestion expecting an immediate answer and are confused when nothing happens. Abandonment likely.
- **Recommendation**: Either rename the action to `ask-fill-suggestion` and update the visual affordance (e.g., change button label to "Ask: ..."), or add `[:dispatch [:action/ask-submit]]` to the effects so the question submits immediately.

---

### UX-004: `"Connecting..."` status shown indefinitely on unknown daemon state
- **Severity**: Medium
- **File**: `/Users/leif/Code/noumenon/ui/src/noumenon/ui/views/shell.cljs:373-380`
- **Description**: The connection status indicator renders `"Connecting..."` for the `:unknown` state. The initial state is `:unknown` and it transitions to `:connected` only after the first successful `/api/databases` call. If the daemon is not running and no error comes back (e.g., a network timeout), the indicator stays at `"Connecting..."` indefinitely with no timeout fallback.
- **Evidence**: `(case status :connected "Connected" :error "Disconnected" "Connecting...")` — the fallback string covers `:unknown` with no timeout handling in `state.cljs`.
- **Impact**: Users see "Connecting..." forever with no guidance. They don't know the daemon is dead until they visit the Databases view.
- **Recommendation**: Add a timeout in the HTTP fetch effect (or a `dispatch-later` on init) to transition from `:unknown` to `:error` after ~10 seconds if no response is received.

---

### UX-005: File card loading state uses presence-of-keys heuristic that misfires
- **Severity**: Medium
- **File**: `/Users/leif/Code/noumenon/ui/src/noumenon/ui/views/shell.cljs:168`
- **Description**: `loading?` is derived as `(and (not (contains? card :summary)) (not (contains? card :imports)))`. Files with no summary (e.g. binary assets, config files never analyzed) and no imports will show "Loading details..." forever after all HTTP responses have resolved with empty data.
- **Evidence**: `loading? (and (not (contains? card :summary)) (not (contains? card :imports)))` — both conditions are false for files that genuinely have no summary or imports.
- **Impact**: Users selecting a binary file, empty file, or unanalyzed file see perpetual "Loading details..." with no useful information.
- **Recommendation**: Track loading state explicitly. Dispatch `:action/graph-file-card-loading true` on `graph-select-node` for file nodes and set it to false in each of the four card-data loaded handlers once all four have resolved (similar to the `seg-data-ready?` pattern).

---

### UX-006: Card cache may serve stale/incomplete file card on repeat selections
- **Severity**: High
- **File**: `/Users/leif/Code/noumenon/ui/src/noumenon/ui/state.cljs:831-837`
- **Description**: `maybe-cache-file-card` caches only when `summary` AND `imports` are present. But `importers`, `authors`, and `history` are loaded by separate handlers that do not call `maybe-cache-file-card`. If `summary` and `imports` resolve first, the card is cached without `importers`, `authors`, and `history`. On re-selection, the stale cached card is served and the other four fetches are skipped.
- **Evidence**: Only `graph-node-imports-loaded` and `graph-node-meta-loaded` call `maybe-cache-file-card`. `graph-node-importers-loaded`, `graph-node-authors-loaded`, `graph-node-history-loaded` do not. The cached check in `graph-select-node`: `(if cached {:state (assoc state :graph/selected id :graph/node-card cached ...)})` — no re-fetch.
- **Impact**: Second visit to a file node silently shows incomplete data (missing importers, authors, history) with no indication data is absent vs. genuinely empty.
- **Recommendation**: Cache only after all five data sources have resolved, or invalidate the cache if any field is still missing. The simplest fix: move the cache write to a separate handler that fires once all four `*-loaded` events have resolved (using the same `ready?` accumulation pattern as segments).

---

### UX-007: No empty state for the graph at `:files` or `:segments` depth
- **Severity**: Medium
- **File**: `/Users/leif/Code/noumenon/ui/src/noumenon/ui/views/graph.cljs:30-44`
- **Description**: The empty state `"No components found"` is only rendered when `(empty? nodes)` at the top level. When expanding into a component with zero files (e.g., a freshly-imported but unanalyzed component), or a file with zero segments, `(empty? nodes)` is true but depth is `:files` or `:segments`. The message still says "No components found" and advises `noum digest`, which is misleading.
- **Evidence**: `(when (and (not loading?) (empty? nodes)) [:div ... "No components found" ... "Run noum digest"])` — no depth check on the empty state message.
- **Impact**: User drills into a component, sees "No components found — Run noum digest" even though components are present and the issue is that the component has no analyzed files/segments.
- **Recommendation**: Make the empty state message depth-aware: `case depth :components "No components found..." :files "No files found in this component" :segments "No segments found in this file"`.

---

### UX-008: Breadcrumb navigation item for intermediate component level lacks `aria-label`
- **Severity**: Low
- **File**: `/Users/leif/Code/noumenon/ui/src/noumenon/ui/views/graph.cljs:73-91`
- **Description**: The "Components" breadcrumb link and intermediate component crumbs use `role="button"` and `tabindex 0` but have no `aria-label`. The label is just the path fragment (e.g. `"src/core"`), which a screen reader announces with no context that it's a navigation breadcrumb.
- **Evidence**: `[:span {:style ... :role "button" :tabindex 0} "Components"]` — no `aria-label` on the components root link or on the clickable intermediate crumbs.
- **Impact**: Screen reader announces "Components button" with no indication this collapses the graph to the top level.
- **Recommendation**: Add `aria-label="Collapse to Components view"` to the root item and `aria-label (str "Navigate up to " crumb)` to intermediate crumbs.

---

### UX-009: SSE error from `/api/ask` appends a broken-markdown error to history
- **Severity**: Medium
- **File**: `/Users/leif/Code/noumenon/ui/src/noumenon/ui/state.cljs:405-411`
- **Description**: On SSE error, the answer is set to `(str "**Error:** " error)`. This is appended to `ask/history` and rendered by `history-item` via `render-markdown`. The bold markdown renders correctly, but the raw error string (e.g. `"HTTP 500: Internal server error"`) is presented in the same typography as a normal answer, mixed with a toast notification. The user receives duplicate error signals of inconsistent severity.
- **Evidence**: `(update :ask/history conj {:question query :answer (str "**Error:** " error)})` combined with `[:dispatch [:action/toast {:message (str "Ask failed: " error) :type :error}]]`.
- **Impact**: The error "answer" persists in history and can be copied to clipboard, suggesting it is a real response. Users are confused about what went wrong. The toast disappears in 5 seconds; the history entry persists forever in the session.
- **Recommendation**: Either do not add error entries to `ask/history` (keep them only as toasts), or use a distinct visual treatment (error card, red border, no copy button) to distinguish error entries from answers.

---

---

### UX-011: History table `<tr>` uses position index as React key after `reverse`
- **Severity**: Low
- **File**: `/Users/leif/Code/noumenon/ui/src/noumenon/ui/views/ask.cljs:493`
- **Description**: `(for [[i item] (map-indexed vector (reverse history))] [:div {:key i} ...])` — keys are positional indices on a reversed collection. Each time a new question is answered, `history` grows and the entire reversed collection shifts every index by one. Every existing history entry remounts, triggering full markdown re-renders for all previous answers.
- **Evidence**: `(for [[i item] (map-indexed vector (reverse history))] [:div {:key i} (history-item item)])` in `ask-view`.
- **Impact**: Noticeable render jank after several questions accumulate. Markdown re-renders are not free; each involves a `str/split-lines` pass and DOM reconciliation.
- **Recommendation**: Use a stable key derived from question content or timestamp: `{:key (hash (:question item))}` or add a `:id` to each history entry when it is appended.

---

### UX-013: Node card (`card-chrome`) is 280px wide but the panel is 640px — cards can obscure the panel
- **Severity**: Medium
- **File**: `/Users/leif/Code/noumenon/ui/src/noumenon/ui/views/shell.cljs:9-38`
- **Description**: Node cards are positioned at `fixed` coordinates derived from the click position. The ask panel is also `fixed` and 640px wide. When the user clicks a node on the left side of the viewport while the ask panel is centered, the card will appear at the click coordinates, potentially directly beneath the ask panel. There is no z-index coordination between the card (`z-index: 50`) and the ask panel (`z-index: 10`) — the card always renders above the panel, covering its content including the active question input.
- **Evidence**: `card-chrome`: `z-index 50`. `app-shell` ask panel: `:z-index 10`. The card uses click screen coordinates directly: `(min (- js/window.innerWidth 320) (max 16 (:x pos)))`.
- **Impact**: After clicking a node near the center of the screen, the card covers the Ask input, preventing the user from typing a follow-up question without first dismissing the card.
- **Recommendation**: Position cards to prefer the right or left of the viewport away from the ask panel's current position. Alternatively, give the ask panel a higher `z-index` (e.g. 60) than the card so the panel is always on top when the user interacts with it.

---

### UX-014: No virtualization for large query results tables
- **Severity**: Medium
- **File**: `/Users/leif/Code/noumenon/ui/src/noumenon/ui/views/schema.cljs:107-138`; `/Users/leif/Code/noumenon/ui/src/noumenon/ui/views/history.cljs:66-77`
- **Description**: `results-table` renders all rows with `for`. Named queries use `:limit 500` default but raw queries have no enforced limit. The history view loads 100 commits. Large result sets (500+ rows) are rendered into the DOM synchronously with no virtualization, pagination, or progressive loading.
- **Evidence**: `(for [[i row] (map-indexed vector results)] [:tr ...])` — no row limit applied in the view. Query event `(assoc state :query/results data)` stores the full response.
- **Impact**: Running a query returning 500 rows freezes the browser tab for hundreds of milliseconds while Replicant reconciles the DOM. On low-end hardware this can produce multi-second jank.
- **Recommendation**: Add a display cap (e.g. first 200 rows with a "Load more" / "Show all N results" toggle) or integrate virtual scrolling. At minimum, truncate in the view and show `"Showing first 200 of N rows"`.

---

### UX-015: Sidebar collapse button has no `aria-label` and uses directional arrow characters as content
- **Severity**: Low
- **File**: `/Users/leif/Code/noumenon/ui/src/noumenon/ui/components/sidebar.cljs:55-63`
- **Description**: The sidebar collapse/expand toggle button renders `"\u25B6"` (▶) or `"\u25C0"` (◀) as its sole content. Screen readers announce these as "BLACK RIGHT-POINTING TRIANGLE" or "BLACK LEFT-POINTING SMALL TRIANGLE" rather than "Collapse sidebar" / "Expand sidebar". No `aria-label` or `title` attribute is present.
- **Evidence**: `[:button {:on {:click [:action/sidebar-toggle]} :style {...}} (if collapsed? "\u25B6" "\u25C0")]` — no `aria-label`.
- **Impact**: Screen reader users cannot determine the button's function.
- **Recommendation**: Add `:aria-label (if collapsed? "Expand sidebar" "Collapse sidebar")` to the button.

---

### UX-016: Theme toggle button uses celestial symbols with no accessible label
- **Severity**: Low
- **File**: `/Users/leif/Code/noumenon/ui/src/noumenon/ui/components/sidebar.cljs:91-100`
- **Description**: The theme toggle renders `"\u263E"` (☾ crescent moon) or `"\u2600"` (☀ sun) as its content. A `title` attribute is present (`"Switch to dark"` / `"Switch to light"`), which provides a tooltip, but `title` is not reliably announced by all screen readers (especially on focusable elements in Firefox/NVDA).
- **Evidence**: `[:button {:on {:click [:action/theme-toggle]} :title (if (= theme :light) "Switch to dark" "Switch to light") ...} (if (= theme :light) "\u263E" "\u2600")]` — no `aria-label`.
- **Impact**: Screen reader users may not know the button purpose.
- **Recommendation**: Add `aria-label` matching the `title` value. `title` on interactive elements is a tooltip, not an accessible name in all AT combinations.

---

### UX-017: Backend switcher `<select>` in sidebar has no `<label>` or `aria-label`
- **Severity**: Low
- **File**: `/Users/leif/Code/noumenon/ui/src/noumenon/ui/components/sidebar.cljs:71-82`
- **Description**: The backend switcher `<select>` has only a `title="Active backend"` attribute. The `title` attribute is unreliable as an accessible name for `<select>` in NVDA+Firefox. No `<label>` element or `aria-label` attribute is present.
- **Evidence**: `[:select {:on {:change [:action/backend-switch-input]} :value ... :title "Active backend" ...} ...]` — no `aria-label`.
- **Impact**: Screen reader users cannot identify this control.
- **Recommendation**: Add `:aria-label "Active backend"` to the `<select>`.

---

### UX-018: `confirm()` dialog for database deletion is blocking and unstyled
- **Severity**: Medium
- **File**: `/Users/leif/Code/noumenon/ui/src/noumenon/ui/state.cljs:1170-1173`
- **Description**: `db-delete` uses the native `js/confirm` dialog. In Electron this is fine, but in browser mode the `confirm()` call blocks the main thread. More critically, the dialog is the OS-default modal with no application styling, giving no context about what data will be lost, no display of the database name in a styled confirmation, and no "Cancel" button styled consistently with the app.
- **Evidence**: `(when (js/confirm message) (dispatch! on-confirm))` where `message` is `"Delete database \"db-name\"? This cannot be undone."`.
- **Impact**: In browser dev mode, the browser may suppress `confirm()` after repeated calls (Chrome). The dialog provides no styling context and can confuse users who are not sure which window is Noumenon.
- **Recommendation**: Replace with an in-app modal or toast-with-confirm pattern. At minimum, this is a code smell — a custom confirmation dialog component keeps all destructive confirmations within the app's design system.

---

### UX-019: Graph canvas is not keyboard-focusable — graph interactions require a pointer
- **Severity**: High
- **File**: `/Users/leif/Code/noumenon/ui/src/noumenon/ui/core.cljs:85-148`
- **Description**: The `<canvas>` element receives click and mousemove events but has no `tabindex`, no `role`, and no keyboard event handlers for node navigation (Tab through nodes, Enter to select, arrow keys to pan). The entire graph layer is inaccessible to keyboard-only users.
- **Evidence**: `(.addEventListener fresh "click" ...)` and `(.addEventListener fresh "mousemove" ...)` — no keyboard event listeners on the canvas. In `graph.cljs`: `[:canvas {:style {...}}]` — no `tabindex` or `role` attribute.
- **Impact**: Users who cannot use a pointing device get a blank canvas with no way to explore the graph. The breadcrumb navigation is keyboard-accessible, but there is no way to select a node or expand a component without a mouse/touch.
- **Recommendation**: Add `tabindex="0"` and `role="application"` to the canvas. Implement keyboard navigation: Tab/arrow keys to cycle through visible nodes (highlight with the existing hover rendering), Enter to select/expand the focused node. This is a significant feature but is the minimum needed for the graph to be WCAG 2.1 AA compliant.

---

### UX-020: Long file paths in cards overflow the 280px card width
- **Severity**: Low
- **File**: `/Users/leif/Code/noumenon/ui/src/noumenon/ui/views/shell.cljs:171-176`
- **Description**: File node card headers use `word-break: break-all` to wrap long paths, which can cause very long paths (e.g. `src/noumenon/analysis/pipeline/stages/enrichment/cross_file_refs.cljs`) to wrap into 4-5 lines, pushing all card content below the fold of the 70vh max-height card.
- **Evidence**: `[:div {:style {:font-family "'SF Mono', monospace" :font-size "12px" :color "#e6edf3" :word-break "break-all" ...}} selected]`. Card `max-height "70vh"` with `overflow-y "auto"`.
- **Impact**: Users with deep directory structures see the filename consuming half the card before any meaningful data appears.
- **Recommendation**: Apply `text-overflow: ellipsis` with `overflow: hidden` and `white-space: nowrap`, and use `title` attribute for the full path on hover. Alternatively, show only the last two path components in the header (as is done in the breadcrumb) and expose the full path in a secondary line.

---

### UX-021: No loading skeleton for the Ask panel's past sessions section
- **Severity**: Low
- **File**: `/Users/leif/Code/noumenon/ui/src/noumenon/ui/views/ask.cljs:515-545`
- **Description**: `past-questions-panel` conditionally renders when `(seq past-sessions)`. On initial load, `:ask/past-sessions` is `nil` (not `[]`), so `(seq nil)` is falsy and nothing renders until the `/api/ask/sessions` response arrives. There is no loading skeleton or "Loading history..." placeholder during the fetch.
- **Evidence**: `(when (seq past-sessions) ...)` — `past-sessions` starts as `nil` per `app-state` initial value. The fetch is triggered by `:action/ask-load-history` in `init!`.
- **Impact**: The history section flashes into existence after a network round-trip. On slow connections this can be a 1-2 second delay with an invisible loading state.
- **Recommendation**: Distinguish `nil` (not-yet-loaded) from `[]` (loaded, empty). Initialize `:ask/past-sessions` to `nil`, render a skeleton while `nil`, render the empty state when `[]`, and the list when `(seq past-sessions)`.

---

### UX-022: Benchmark table rows have no accessible `role` or `aria` selection state
- **Severity**: Low
- **File**: `/Users/leif/Code/noumenon/ui/src/noumenon/ui/views/benchmark.cljs:13-36`
- **Description**: `run-row` renders `<tr>` with `cursor: pointer` and background highlight for selected rows, but no `role="row"` override, no `aria-selected`, and no keyboard activation. The row's `on: {:click ...}` makes it interactive via mouse only.
- **Evidence**: `[:tr {:key id :style {:cursor "pointer" :background (when selected? ...)} :on {:click [:action/bench-toggle-select id]}} ...]` — no `aria-selected`, no `tabindex`, no `keydown` handler.
- **Impact**: Screen reader users cannot select benchmark runs to compare. The "Compare" button is announced without context of which runs are selected.
- **Recommendation**: Add `tabindex="0"` to interactive `<tr>` rows, add `aria-selected (boolean selected?)`, and handle `keydown` Enter/Space to toggle selection.

---

### UX-023: `ask/history` answer copy button has no `aria-label`
- **Severity**: Low
- **File**: `/Users/leif/Code/noumenon/ui/src/noumenon/ui/views/ask.cljs:190-202`
- **Description**: The "Copy" button in `history-item` has `title="Copy to clipboard"` but no `aria-label`. The same issue exists in `expanded-session-detail`. Title is not reliably exposed as an accessible name.
- **Evidence**: `[:button {:on {:click ...} :style {...} :title "Copy to clipboard"} "Copy"]` — the visible text "Copy" is present, which does provide an accessible name. However, the same button in `expanded-session-detail` at line 296 also uses only `title` and the text "Copy".
- **Impact**: The visible text "Copy" is adequate as an accessible name for the button itself, but `title` tooltips in general are unreliable. This is a minor issue given the visible label matches.
- **Recommendation**: Remove `title` (redundant with the button text) or replace with `aria-label="Copy answer to clipboard"` for more descriptive AT announcement.

---

### UX-024: Schema workbench `<textarea>` has no `aria-label` or associated `<label>`
- **Severity**: Low
- **File**: `/Users/leif/Code/noumenon/ui/src/noumenon/ui/views/schema.cljs:166-179`
- **Description**: The Datalog query editor `<textarea>` has a `placeholder` but no `<label>` element, no `for`/`id` pairing, and no `aria-label`. The placeholder disappears as soon as text is entered.
- **Evidence**: `[:textarea {:value ... :on ... :placeholder "[:find ?path\n :where ...]" ...}]` — no `aria-label` or associated label.
- **Impact**: Screen readers announce the textarea without identifying it as the "Query editor". AT users must infer purpose from surrounding content.
- **Recommendation**: Add `aria-label="Datalog query"` or wrap with `[:label {:for "query-editor"} "Query"] [:textarea {:id "query-editor" ...}]`.

---

### UX-025: "New question" button clears in-progress history but not the query field if partially typed
- **Severity**: Low
- **File**: `/Users/leif/Code/noumenon/ui/src/noumenon/ui/state.cljs:157-161`
- **Description**: `:action/ask-clear` sets `ask/history []` and `ask/steps []` but does not clear `ask/query`. After a user asks a question and the answer arrives, clicking "New question" clears history and steps but leaves the previous question text in the input field. The user sees a blank history area but the question input still contains the previous query.
- **Evidence**: `(defmethod handle-event :action/ask-clear [state _] {:state (assoc state :ask/history [] :ask/last-steps nil :ask/steps [] :ask/result nil :ask/show-post-reasoning? false :ask/reasoning-expanded? false :ask/expanded-session nil :ask/expanded-detail nil :graph/focused-ids nil)})` — no `(assoc ... :ask/query "")`.
- **Impact**: Users who click "New question" expecting a clean slate find the old question still in the input and may accidentally re-submit it.
- **Recommendation**: Add `:ask/query ""` to the `ask-clear` state map.

---

### UX-026: Ask view scrollable container clips the completions dropdown
- **Severity**: High
- **File**: `/Users/leif/Code/noumenon/ui/src/noumenon/ui/views/ask.cljs:487`
- **Description**: The scrollable content area `[:div {:style {:max-height "55vh" :overflow-y "auto" ...}}]` contains the reasoning trace and history. The completions dropdown is positioned `absolute` relative to the outer input container which is outside this scrollable div, so completions correctly appear above the scroll container. However, when `steps` or `history` is very long, the scrollable div extends to 55vh, and the input area above it (which holds the completions anchor) shifts upward — the completions dropdown may then overlap the scroll container's top edge without the scroll container clipping it (since the dropdown is outside the scroll container). This is fine positionally, but the outer ask-panel div has `max-height "75vh"` and `overflow: "visible"` — on small viewports where the panel is near the top (centered at `top: 12vh`), the dropdown can extend above the panel into `12vh` from the top, potentially off-screen.
- **Evidence**: Ask panel at `top: 12vh`, dropdown rendered at `top: 100%` relative to the input with `margin-top: 4px` — the dropdown renders downward (into the panel). The actual risk is on small viewports where 12vh + panel padding + input height pushes the dropdown's `top` close to the panel top and the `max-height: 240px` dropdown extends downward potentially past `75vh + 12vh = 87vh` viewport, causing it to be cut off by the viewport bottom.
- **Evidence code**: Completions dropdown: `{:position "absolute" :top "100%" :max-height "240px" :overflow-y "auto"}`. Ask panel: `{:max-height "75vh" :overflow "visible"}`.
- **Impact**: On viewport heights below ~600px, the completions dropdown is cut off at the bottom of the viewport.
- **Recommendation**: Detect available space below the input and if less than 240px, flip the dropdown to open upward (`bottom: 100%` instead of `top: 100%`).

---

---

### UX-028: `ask-feedback-text-input` dispatches `:action/ask-feedback-text-set` but the handler key is `:action/ask-feedback-text-set` vs actual `defmethod` key
- **Severity**: High
- **File**: `/Users/leif/Code/noumenon/ui/src/noumenon/ui/core.cljs:226-228` and `/Users/leif/Code/noumenon/ui/src/noumenon/ui/state.cljs:257-259`
- **Description**: `core.cljs` dispatches `:action/ask-feedback-text-set` in response to the textarea's `:input` event via the special case in `set-dispatch!`. The `defmethod` handler at `state.cljs:257` is `(defmethod handle-event :action/ask-feedback-text-set ...)`. However, the textarea in `ask.cljs` at line 245 specifies `:on {:input [:action/ask-feedback-text-input]}` — which is the Replicant raw event name that routes through `core.cljs`'s `set-dispatch!` handler for `:action/ask-feedback-text-input`, dispatching `:action/ask-feedback-text-set`. This chain is correct but the handler name `ask-feedback-text-set` vs the event `ask-feedback-text-input` is confusing and the indirection adds a silent failure point if refactored.
- **Evidence**: `ask.cljs:245`: `:on {:input [:action/ask-feedback-text-input]}`. `core.cljs:226-228`: `(state/dispatch! [:action/ask-feedback-text-set (extract-value dom-event)])`. `state.cljs:257`: `(defmethod handle-event :action/ask-feedback-text-set ...)`. This is not a bug in the current code, but the two-name indirection means removing the `core.cljs` special case silently breaks feedback text capture.
- **Impact**: Current code works. If `:action/ask-feedback-text-input` is ever handled by the default dispatch path, the raw DOM event is passed as the payload to `:ask-feedback-text-set`, replacing the text with the event object.
- **Recommendation**: Use `:action/ask-feedback-text-set` directly in the textarea's `:on` map (Replicant will call `extract-value` if the handler is registered in `set-dispatch!`), or document the indirection convention clearly to prevent future breakage.

---

### UX-029: History view table has no responsive overflow on narrow viewports
- **Severity**: Low
- **File**: `/Users/leif/Code/noumenon/ui/src/noumenon/ui/views/history.cljs:66-77`
- **Description**: The history table with 6 columns (SHA, Type, Message, Author, Date, Changes) has a `max-width: 1000px` container but no `overflow-x: auto` wrapper. On viewports narrower than ~700px, the table will overflow its container and cause horizontal page scroll.
- **Evidence**: `[:div {:style {:max-width "1000px"}}` wrapping a `[:table {:style {:width "100%" ...}}]` with 6 columns — no overflow wrapper.
- **Impact**: On smaller windows or when the sidebar is expanded, the table overflows. This is particularly relevant given the app supports multiple window sizes (Electron custom sizing).
- **Recommendation**: Wrap the table in `[:div {:style {:overflow-x "auto"}}]`. Consider hiding the "Changes" column on narrow viewports via media query or dynamic width detection.

---

### UX-030: `toast-container` overlaps the connection status indicator and may overlap each other
- **Severity**: Low
- **File**: `/Users/leif/Code/noumenon/ui/src/noumenon/ui/components/toast.cljs:30-41` and `/Users/leif/Code/noumenon/ui/src/noumenon/ui/views/shell.cljs:365-380`
- **Description**: `toast-container` is positioned `fixed` at `bottom: 20px, right: 20px, max-width: 400px`. The connection status indicator is also `fixed` at `bottom: 8px, right: 12px`. When toasts are present, they stack upward from 20px but the status indicator at 8px is beneath the first toast. Multiple toasts can stack beyond the viewport top with no scrolling mechanism.
- **Evidence**: `toast-container`: `{:bottom "20px" :right "20px"}`. Status dot: `{:bottom "8px" :right "12px"}`. Toasts stack with `flex-direction "column"` (bottom-to-top) but grow upward — with 5+ toasts they extend past the viewport top.
- **Impact**: The connection status dot is obscured by the first toast. With many errors, toasts pile up out of view.
- **Recommendation**: Increase toast container `bottom` to at least 32px to clear the status dot. Cap visible toasts at 3-4 and either queue or merge duplicates. The `toast-dismiss` auto-fires after 5s, so long-lived queues indicate rapid error generation that should be addressed at the source.

---

*Total issues found: 30*

| Category | Count |
|---|---|
| Accessibility (keyboard, ARIA, screen reader) | 9 (UX-002, UX-008, UX-015, UX-016, UX-017, UX-019, UX-022, UX-023, UX-024) |
| Error/Loading States | 5 (UX-004, UX-005, UX-006, UX-009, UX-021) |
| Interaction / Data Display | 7 (UX-003, UX-007, UX-011, UX-013, UX-020, UX-025, UX-026) |
| Responsiveness / Layout | 3 (UX-014, UX-029, UX-030) |
| Performance | 2 (UX-011, UX-014) |
| State Consistency / Bugs | 2 (UX-001, UX-028) |

---

## Pass 2 Findings

**Scope**: CLI/TUI (launcher/), shell view, history view, benchmark view, error states, mobile/responsive, color theme/dark-mode consistency, form validation
**Commit**: 5fd1db3
**Date**: 2026-04-02

---

### UX-031: CLI `do-upgrade` never reports failure — always exits 0 regardless of download outcome
- **Severity**: Medium
- **File**: `/Users/leif/Code/noumenon/launcher/src/noum/main.clj:307-312`
- **Description**: `do-upgrade` calls `(jar/download!)` and prints "Noumenon updated." only if the return value is truthy, but it always exits 0. If `download!` returns `nil` (already up to date) the only output is the launcher self-update hint line. If `download!` throws, the exception propagates to `-main`'s top-level `System/exit 1` path but `do-upgrade` never calls `(tui/eprintln ...)` for the failure case. The return value 0 is unconditional, so scripted callers (`&&`-chained shell scripts) cannot detect failure.
- **Evidence**: `(defn- do-upgrade [_] (if (jar/download!) (do ...) (tui/eprintln "To update...")) 0)` — the `0` is outside the `if`, returned in both branches including the nil (no-update) path. `jar/download!` can also throw, which is not caught here.
- **Impact**: Users running `noum upgrade` in CI or scripts assume success if exit code is 0. Silent failures are masked.
- **Recommendation**: Wrap `jar/download!` in a `try/catch`. Return `1` on exception. Distinguish the "already up to date" case with a clear message.

---

### UX-032: `do-watch` silently resets `failures` counter on each success — 3 failures must be *consecutive* but the UI message says "3 consecutive failures" only at stop time, not at each failure
- **Severity**: Low
- **File**: `/Users/leif/Code/noumenon/launcher/src/noum/main.clj:335-348`
- **Description**: The watch loop emits a yellow "Warning: update failed" per failure and stops at 3 consecutive failures. However, the warning message gives no indication of how many failures have occurred or how many remain before the watch stops. A user watching the output sees warnings without knowing whether the next failure will abort.
- **Evidence**: `(tui/eprintln (str (style/yellow "  Warning: ") "update failed: " (:error resp)))` — no failure count context. Counter `n` is local and not included in the message.
- **Impact**: Users cannot determine if the watch is about to abort. They may leave the terminal unattended and miss that watching stopped after the third failure.
- **Recommendation**: Include the failure count in the warning: `(str "  Warning: update failed (" n "/3): " (:error resp))`. Alternatively emit a distinct "stopping after N more failures" message.

---

### UX-033: `do-delete` confirms with `confirm/ask` before connecting to the daemon, but `ensure-backend!` runs *after* — user can confirm deletion of a non-existent database before discovering the daemon isn't running
- **Severity**: Low
- **File**: `/Users/leif/Code/noumenon/launcher/src/noum/main.clj:350-367`
- **Description**: `do-delete` first calls `(confirm/ask ...)`, prompting the user to confirm deletion. Only after confirmation does it call `(ensure-backend! flags)`, which may start the JVM daemon (~5-30s). If the daemon fails to start, the user has already confirmed the operation and then receives a startup error. The destructive confirmation and the side-effect (starting the daemon) are in the wrong order.
- **Evidence**: `(if (and (not (:force flags)) (not (confirm/ask ...))) ... (let [conn (ensure-backend! flags) ...]))` — `ensure-backend!` is inside the confirmation branch.
- **Impact**: Minor confusion — user says "yes, delete" then waits for daemon startup only to encounter an error. It feels like the confirmation was wasted.
- **Recommendation**: Call `(ensure-backend! flags)` before the confirmation prompt, or at minimum verify the daemon is reachable before asking the user to confirm. This also matches the user mental model: "check that the thing I want to delete actually exists, then confirm."

---

---

### UX-036: History view `commit-row` uses positional destructuring of server tuple — if the API returns a different column order or adds fields, silent data misalignment occurs with no visual indicator
- **Severity**: Low
- **File**: `/Users/leif/Code/noumenon/ui/src/noumenon/ui/views/history.cljs:14-16`
- **Description**: `commit-row` destructures commits as `[sha message author kind date added deleted]`. This is a positional vector binding that is fragile to server-side changes. If a new field is inserted before `added`, the display silently shows wrong values (e.g. the `date` column shows `added` counts). There is no data validation or named key binding.
- **Evidence**: `(let [[sha message author kind date added deleted] commit] ...)` — pure positional, no map destructuring.
- **Impact**: Silent data display errors if API response shape changes. Low immediate risk but a maintenance hazard — the column labels in `[:thead]` are also positional and must be manually kept in sync.
- **Recommendation**: Have the API return maps (or ensure the server contract is documented and tested). If tuples are intentional for performance, at minimum add a comment documenting the expected index positions.

---

### UX-037: `past-questions-panel` in `shell.cljs` is overlaid at `bottom: 24px, left: 12px` with `z-index: 10` — same z-index as the ask panel, so it can be obscured by the ask panel when the panel is dragged to the left
- **Severity**: Low
- **File**: `/Users/leif/Code/noumenon/ui/src/noumenon/ui/views/shell.cljs:354-361`
- **Description**: The past-questions panel is positioned `fixed` at `{:bottom "24px" :left "12px" :z-index 10}`. The ask panel also uses `z-index 10`. When the user drags the ask panel to the lower-left corner, it lands on top of the past-questions panel (same z-index means DOM order wins — the ask panel div appears later in the DOM and thus renders on top). The past-questions panel becomes inaccessible without moving the ask panel first.
- **Evidence**: `ask-panel` div: `:z-index 10`. Past-questions div: `:z-index 10`. Both `position: absolute`/`fixed` in the same stacking context.
- **Impact**: Past sessions become unreachable when the ask panel overlaps the bottom-left corner.
- **Recommendation**: Give the past-questions panel a higher z-index (e.g. 15) so it is always accessible above the ask panel, or reposition it to a corner that is out of the typical drag range.

---

### UX-038: Shell view database `<select>` and the ask view `db-selector` `<select>` can both be visible simultaneously and show different states — no deduplication
- **Severity**: Low
- **File**: `/Users/leif/Code/noumenon/ui/src/noumenon/ui/views/shell.cljs:325-352` and `/Users/leif/Code/noumenon/ui/src/noumenon/ui/views/ask.cljs:5-17`
- **Description**: When more than one database exists, the shell renders a `<select>` in the top-right corner of the screen (fixed position). The ask view also renders a `db-selector` `<select>` inline within the ask panel for multiple databases. Both selects dispatch `:action/select-db` and are visible simultaneously when the ask panel is open. If a user changes the database in one, the other updates too (since they share state), but momentarily both exist as separate controls with no visual grouping.
- **Evidence**: `shell.cljs:327-341`: top-right fixed `<select>` for `(> (count list) 1)`. `ask.cljs:5-17`: inline `db-selector` also for `(> (count databases) 1)`.
- **Impact**: Two database selectors for the same state creates visual clutter and potentially confuses users about which control is authoritative.
- **Recommendation**: Remove the inline `db-selector` from `ask-view` since the shell already provides a top-right selector. The shell selector is always visible regardless of the active route.

---

### UX-039: Light theme: `shell.cljs` uses hardcoded dark RGBA values throughout card-chrome, file-card, segment-card, and component-card — these do not adapt to the light theme
- **Severity**: High
- **File**: `/Users/leif/Code/noumenon/ui/src/noumenon/ui/views/shell.cljs:9-38, 92-153, 157-269`
- **Description**: The node cards (`card-chrome`, `file-card`, `segment-card`, `component-card`) use hardcoded dark RGBA values: `"rgba(12,16,24,0.92)"` background, `"#e6edf3"` text, `"rgba(255,255,255,0.08)"` borders, `"rgba(255,255,255,0.3)"` secondary text etc. None of these values reference `styles/tokens`. When the user switches to light theme, the rest of the UI updates (sidebar, views, tables) but all node cards remain dark, creating an inconsistent mixed appearance.
- **Evidence**: `card-chrome` background: `"rgba(12,16,24,0.92)"` — hardcoded dark. `file-card` text: `"#e6edf3"` — hardcoded light-on-dark color. Compare to `ask.cljs` which consistently uses `(:bg-secondary styles/tokens)`, `(:text-primary styles/tokens)` etc.
- **Impact**: Switching to light mode leaves all graph node cards dark-themed, creating a jarring visual inconsistency that looks like a rendering bug.
- **Recommendation**: Replace all hardcoded RGBA/hex values in `shell.cljs` card components with `styles/tokens` equivalents. For the card's backdrop-blur glass effect, use `(:bg-secondary styles/tokens)` with `opacity` or a dedicated card-background token.

---

### UX-040: No `prefers-color-scheme` auto-detection — the app always starts in dark mode regardless of OS preference
- **Severity**: Low
- **File**: `/Users/leif/Code/noumenon/ui/src/noumenon/ui/state.cljs:10-42` (initial state) and `core.cljs` init
- **Description**: `app-state` initializes with no `:theme` key set. `theme-toggle` defaults `(if (= :dark (:theme state :dark)) ...)` — so the default is `:dark`. There is no `window.matchMedia("(prefers-color-scheme: dark)")` query at startup to respect the user's OS-level preference. Settings are loaded from storage via `:action/settings-load`, which could restore a saved theme, but a first-time user always starts in dark mode regardless of their OS preference.
- **Evidence**: `app-state` initial value: no `:theme` key. `handle-event :action/theme-toggle`: `(if (= :dark (:theme state :dark)) ...)` — explicit `:dark` default.
- **Impact**: Users with OS-level light mode preference see a dark UI on first load and must manually toggle.
- **Recommendation**: In `init!` or `core.cljs`'s `init!`, check `(.-matches (.matchMedia js/window "(prefers-color-scheme: dark)"))` before loading saved settings. Apply the OS preference as the default when no saved preference exists. Add a `change` listener to respond to live OS theme changes.

---

### UX-041: `noum ask` positional argument requires repo as first arg and question as all remaining args, but the help text format `noum ask <repo> "question"` does not indicate that multi-word questions work without quotes in a shell
- **Severity**: Low
- **File**: `/Users/leif/Code/noumenon/launcher/src/noum/cli.clj:27-30` and `main.clj:189-190`
- **Description**: The `ask` command's positional map uses `(str/join " " (rest pos))` to join all args after the repo path as the question. This means `noum ask myrepo What is the architecture?` works correctly. However, the help text shows `noum ask <repo> "question"` with quotes, implying quotes are required. The `min-args 2` check fires if the question is absent, with the message `"Usage: noum ask <repo> \"question\""` — the escaped quotes reinforce the misconception that quoting is mandatory.
- **Evidence**: `(fn [pos] {:repo_path ... :question (str/join " " (rest pos))})` — multi-word unquoted works fine. Help text: `"noum ask <repo> \"question\" [options]"`.
- **Impact**: Users may unnecessarily quote questions containing special shell characters (especially `?` and `*`) which can cause shell expansion issues if they use single quotes inconsistently.
- **Recommendation**: Update the usage text to `noum ask <repo> <question...>` or add a note "Quotes are optional for simple questions." Explicitly document that multi-word questions work without quotes.

---

---

### UX-043: `spinner/start` is called at module load time to set `frames` — if `tui/utf8?` is evaluated before the JVM locale is fully configured, frames may be wrong
- **Severity**: Low
- **File**: `/Users/leif/Code/noumenon/launcher/src/noum/tui/spinner.clj:8`
- **Description**: `(def ^:private frames (if (tui/utf8?) unicode-frames ascii-frames))` is a top-level `def` evaluated when the namespace is first loaded. `utf8?` checks `(System/getenv "LANG")` and `(System/getenv "LC_ALL")`. In some CI environments and Docker containers, these env vars are not set at namespace load time but may be set later. The frames selection is locked in at load time and cannot adapt.
- **Evidence**: `(def ^:private frames (if (tui/utf8?) unicode-frames ascii-frames))` — evaluated once at load.
- **Impact**: In environments where UTF-8 detection is unreliable at load time (Docker, CI), users may see ASCII spinners even in UTF-8 capable terminals, or see Unicode braille characters in non-UTF-8 terminals.
- **Recommendation**: Change `frames` to a `defn` or evaluate `utf8?` inside `start` rather than at definition time: `(let [frames (if (tui/utf8?) unicode-frames ascii-frames)] ...)`.

---

### UX-044: `do-version` silently swallows all exceptions from the health check — if the daemon is running but returns unexpected data, `version` exits 0 with partial output and no error indicator
- **Severity**: Low
- **File**: `/Users/leif/Code/noumenon/launcher/src/noum/main.clj:423-432`
- **Description**: `do-version` wraps the daemon health check in `(try ... (catch Exception _))` with an empty catch body. Any error (network failure, malformed JSON, missing version field) is silently ignored. The user sees `noum <launcher-version>` but no daemon version, with no indication of why.
- **Evidence**: `(catch Exception _)` — no logging, no user feedback. The function always returns 0.
- **Impact**: Users diagnosing version mismatch issues between launcher and daemon get incomplete output with no hint that the daemon version check failed.
- **Recommendation**: At minimum, print `"noumenon (daemon not running or unreachable)"` in the catch block, or use a more targeted exception handler that distinguishes "daemon not running" from "JSON parse error".

---

### UX-045: Shell app-shell renders the ask panel unconditionally — even on non-Ask routes (Databases, Schema, History, etc.) the ask panel floats over the content
- **Severity**: High
- **File**: `/Users/leif/Code/noumenon/ui/src/noumenon/ui/views/shell.cljs:273-381`
- **Description**: The `app-shell` function always renders the ask panel `<div id="ask-panel">` regardless of the current `:route`. On the Databases, Schema, History, Benchmark, and Introspect views (which are rendered inside a sidebar layout in `core.cljs`), the ask panel is not present — those views are rendered via a different root. However on the Graph/Ask view shell, the ask panel is always shown even when the user is at the graph breadcrumb level with no ask intent. There is no route-aware hide/show logic for the ask panel on the shell.
- **Evidence**: `(defn app-shell [state] ... [:div {:id "ask-panel" ...} ...])` — no `(when (= :ask (:route state)) ...)` guard.
- **Impact**: The floating ask panel overlaps graph navigation on the shell view even when the user has not initiated a question, reducing available space for the graph visualization.
- **Recommendation**: Add a toggle to minimize/hide the ask panel (beyond the existing drag), or narrow it to an icon-only collapsed state when the user has not started a query and is focused on the graph. A hide button (`X`) on the panel itself distinct from the drag handle would suffice.

---

*Pass 2 total new issues: 15 (UX-031 through UX-045)*

| Category | New Issues |
|---|---|
| CLI/TUI UX | UX-031, UX-032, UX-033, UX-041, UX-043, UX-044 |
| Error States | UX-031, UX-035, UX-042, UX-044 |
| Dark Mode / Theme Consistency | UX-039, UX-040 |
| Benchmark View | UX-034, UX-035 |
| Layout / Overlap | UX-037, UX-038, UX-045 |
| Data Display | UX-036 |
| Form Validation | UX-042 |

---

## Pass 3 Findings

**Scope**: Regressions from fix/audit-remediation-v0.4.0 branch (commits main..8b9b477), plus graph interaction, ask panel edge cases, toast/notification system, and loading states
**Commit**: 8b9b477
**Date**: 2026-04-02

---

### UX-046: `:tab-index` attribute in suggestion ticker renders as `tab-index="..."` — an unknown HTML attribute that browsers ignore
- **Severity**: High
- **File**: `/Users/leif/Code/noumenon/ui/src/noumenon/ui/views/ask.cljs:367`
- **Description**: The fix for UX-002 (suggestion ticker accessibility) added `:tab-index (if duplicate? -1 0)` to each ticker button. Replicant converts keyword attribute names using `(name attr)`, so `:tab-index` becomes the HTML attribute `tab-index`. The valid HTML attribute for tab order is `tabindex` (all lowercase, no hyphen). The DOM property is `tabIndex` (camelCase). Browsers do not recognize `tab-index` as a valid attribute and silently ignore it. Compare to `graph.cljs` which correctly uses `:tabindex 0` throughout.
- **Evidence**: `ask.cljs:367`: `:tab-index (if duplicate? -1 0)`. `graph.cljs:75`: `:tabindex 0`. Replicant `core.cljc:434`: `(let [an (name attr)] ... (.setAttribute el an ...))` — `(name :tab-index)` = `"tab-index"`, not `"tabindex"`.
- **Impact**: Duplicate ticker buttons are not excluded from tab order as intended. Keyboard users Tab through all 12 buttons (including the 6 invisible duplicates). The fix for UX-002 is partially broken — the `aria-label` and `aria-hidden` additions are correct, but the keyboard tab exclusion does not work.
- **Recommendation**: Change `:tab-index` to `:tabindex` on line 367 of `ask.cljs`, consistent with the usage in `graph.cljs`.

---

### UX-047: `maybe-cache-file-card` still caches on `summary + imports` only — UX-006 is not fully fixed
- **Severity**: High
- **File**: `/Users/leif/Code/noumenon/ui/src/noumenon/ui/state.cljs:840-846`
- **Description**: The fix for UX-006 (stale card cache) added `maybe-cache-file-card` calls to the `importers-loaded`, `authors-loaded`, and `history-loaded` handlers. However, the predicate inside `maybe-cache-file-card` still reads `(and card (contains? card :summary) (contains? card :imports))`. If the `meta-loaded` (summary) and `imports-loaded` handlers resolve before `importers-loaded`, the cache write fires immediately with only `summary` + `imports` populated — without `importers`, `authors`, or `history`. On a second node selection, the cached card is served and no re-fetch occurs, leaving `importers`, `authors`, and `history` permanently absent from the card.
- **Evidence**: `maybe-cache-file-card` at `state.cljs:844`: condition `(and card (contains? card :summary) (contains? card :imports))` — unchanged from the pre-fix version. The five data handlers (`meta`, `imports`, `importers`, `authors`, `history`) are all independent HTTP requests that resolve in arbitrary order.
- **Impact**: The premature-cache bug persists: users revisiting a previously-selected file node see a card missing importers, authors, and/or history with no indication of absent data. The addition of `maybe-cache-file-card` calls in three more handlers means cache writes now happen more frequently (up to 4 times per file selection), but each individual write can be premature.
- **Recommendation**: Cache only after all five fields are present. Change the predicate to: `(and card (contains? card :summary) (contains? card :imports) (contains? card :importers) (contains? card :authors) (contains? card :history))`. This correctly defers caching until all five responses arrive.

---

### UX-048: Markdown list parser groups mixed ordered/unordered consecutive lines under the first item's type
- **Severity**: Medium
- **File**: `/Users/leif/Code/noumenon/ui/src/noumenon/ui/components/markdown.cljs:144-156`
- **Description**: The new list parser (fix for UX-010) collects all consecutive lines where `(list-item? line)` is truthy into one block, then wraps them in `[:ol]` or `[:ul]` based solely on whether the *first* line is ordered. A mixed block like `"- item\n1. next"` becomes `[:ul ...]` with both items; `"1. item\n- next"` becomes `[:ol ...]` with both items. In both cases the inner `parse-list-item` produces `[:li ...]` with no `list-style-type` override, so unordered items inside `[:ol]` display as decimal-numbered, and ordered items inside `[:ul]` display as bullets. Additionally, a transition from ordered to unordered items (or vice versa) within a single response breaks the semantic list structure.
- **Evidence**: `markdown.cljs:145-151`: `(let [ordered? (ordered-item? line) ... tag (if ordered? :ol :ul)] ...)` — `ordered?` is derived from the first line only. The inner loop at line 148 accumulates all `list-item?` lines without checking type consistency.
- **Impact**: LLM responses often contain mixed list formats (e.g., a numbered overview followed by bullet sub-points in the same paragraph). These render with incorrect semantic tags and visual bullet/numbering styles.
- **Recommendation**: In the accumulation loop, stop collecting when the item type changes (ordered vs. unordered): `(and (seq rest-lines) (list-item? (first rest-lines)) (= (ordered-item? (first rest-lines)) ordered?))`. This emits separate `[:ol]`/`[:ul]` blocks for each contiguous run of same-type items.

---

### UX-049: `ask-run-suggestion` clobbers the in-flight query text when a request is already loading
- **Severity**: Low
- **File**: `/Users/leif/Code/noumenon/ui/src/noumenon/ui/state.cljs:226-228`
- **Description**: The fix for UX-003 (suggestion auto-submit) dispatches `:action/ask-submit` after setting `:ask/query`. The `ask-submit` handler guards against `(:ask/loading? state)` and silently no-ops when a request is in flight. However, `ask-run-suggestion` does not guard against `loading?` — it always sets `:ask/query` to the clicked suggestion. If the user clicks a suggestion while a query is loading, the query input immediately shows the suggestion text. When the current request completes, `ask-result` clears `:ask/query` to `""`, not restoring the original question. During the loading window the user sees unexpected text in the input field with no explanation.
- **Evidence**: `ask-run-suggestion` at `state.cljs:226-228`: no `loading?` guard. `ask-submit` at `state.cljs:354`: `(if (and (seq query) db-name (not (:ask/loading? state))) ...)` — the submit is blocked but the query mutation is not.
- **Impact**: Minor confusion: a user who accidentally clicks a suggestion during a loading request sees their input text replaced unexpectedly. The next submit (once loading completes) would use `""` (cleared by `ask-result`) rather than the suggestion or original question.
- **Recommendation**: Add a `loading?` guard to `ask-run-suggestion`: `(if (:ask/loading? state) {:state state} {:state (assoc state :ask/query question) :fx [[:dispatch [:action/ask-submit]]]})`.

---

### ~~UX-050~~ (Fixed)
Connection timeout timer now stored in `connection-timer` atom and cancelled on `db-loaded`.

---

### UX-051: Suggestion ticker buttons lack `:key` stability — positional keys cause all buttons to remount on suggestion rotation
- **Severity**: Low
- **File**: `/Users/leif/Code/noumenon/ui/src/noumenon/ui/views/ask.cljs:365`
- **Description**: Each ticker button has `:key (str i "-" q)`. The key is a combination of index `i` and the question string `q`. When `:action/ask-rotate-one-suggestion` changes one slot in `visible-suggestions`, `items` (which is `(concat visible-set visible-set)`) changes. All keys that include the rotated slot's index get new values. Replicant sees new keys and remounts those button elements, restarting any CSS transitions and flickering the buttons mid-scroll.
- **Evidence**: `ask.cljs:345`: `(let [items (vec (concat visible-set visible-set))] ...)`. `ask.cljs:365`: `:key (str i "-" q)`. When slot 3 changes, keys `"3-<old>"` and `"9-<old>"` (position 3 in the second copy) are replaced by `"3-<new>"` and `"9-<new>"`, causing Replicant to destroy and recreate those buttons.
- **Impact**: Noticeable visual flicker when suggestions rotate. The ticker scroll animation is also disrupted because the container's children change, causing the browser to recalculate layout mid-animation.
- **Recommendation**: Use only the question string as the key: `:key q`. This is stable across rotations (unchanged suggestions keep their DOM nodes). The duplicate set should use a prefix to distinguish: `:key (str "dup-" q)`.

---

### UX-052: `reasoning-trace` uses hardcoded `"rgba(15,20,30,0.75)"` background — does not adapt to light theme
- **Severity**: Low
- **File**: `/Users/leif/Code/noumenon/ui/src/noumenon/ui/views/ask.cljs:142`
- **Description**: The reasoning trace panel uses `{:background "rgba(15,20,30,0.75)"}` as its background color. This is the same hardcoded dark value pattern noted for card-chrome in UX-039. When the user switches to light theme, this panel remains visually dark while surrounding content lightens, creating an inconsistent appearance. The same hardcoded value also appears in `history-item` at `ask.cljs:185`.
- **Evidence**: `ask.cljs:142`: `:background "rgba(15,20,30,0.75)"`. `ask.cljs:185`: `:background "rgba(15,20,30,0.75)"`. Compare to other panels in `ask.cljs` that correctly use `(:bg-secondary styles/tokens)`.
- **Impact**: Switching to light mode leaves reasoning trace and answer cards dark, in the same way as UX-039 for graph node cards.
- **Recommendation**: Replace `"rgba(15,20,30,0.75)"` with `(:bg-secondary styles/tokens)` in the reasoning trace div and history item answer div.

---

### UX-053: Ask input hardcodes `"#e6edf3"` text color and `"rgba(15,20,30,0.8)"` background — not theme-aware
- **Severity**: Low
- **File**: `/Users/leif/Code/noumenon/ui/src/noumenon/ui/views/ask.cljs:408-414`
- **Description**: The main ask `<input>` element specifies `:color "#e6edf3"` (hardcoded light-on-dark) and `:background "rgba(15,20,30,0.8)"` (hardcoded dark). In light theme the input is unreadable — dark background with very light text on a light-themed page.
- **Evidence**: `ask.cljs:408`: `:color "#e6edf3"`. `ask.cljs:411`: `:background "rgba(15,20,30,0.8)"`. The tokens `(:text-primary styles/tokens)` and `(:bg-secondary styles/tokens)` should be used instead.
- **Impact**: Switching to light mode makes the primary query input visually inconsistent with the rest of the form (same class as UX-039/UX-052 but on the primary interactive element).
- **Recommendation**: Replace hardcoded values with `(:text-primary styles/tokens)` and `(:bg-secondary styles/tokens)`.

---

*Pass 3 total new issues: 8 (UX-046 through UX-053)*

| Category | New Issues |
|---|---|
| Regressions (Accessibility fix broken) | UX-046 |
| Regressions (Cache fix incomplete) | UX-047 |
| Regressions (Markdown list fix) | UX-048 |
| Regressions (Suggestion auto-submit) | UX-049 |
| Connection / Loading State | UX-050 |
| Performance / Rendering | UX-051 |
| Dark Mode / Theme Consistency | UX-052, UX-053 |

---

## Pass 4 Findings

**Scope**: Full repo at commit 3432391 (refactor series: core, render, graph, ask, markdown, shell)
**Commit**: 3432391
**Date**: 2026-04-02
**Prior fixes confirmed**: UX-033 (delete confirm order), UX-046 (tabindex spelling), UX-047 (cache predicate), UX-048 (mixed list types)

---

### Status Updates

- **UX-033**: RESOLVED — `ensure-backend!` now called before `confirm/ask` in `do-delete`.
- **UX-046**: RESOLVED — `:tabindex` is correctly lowercase in current `ask.cljs:367`.
- **UX-047**: RESOLVED — `maybe-cache-file-card` predicate now requires all five fields (`:summary`, `:imports`, `:importers`, `:authors`, `:history`).
- **UX-048**: RESOLVED — `collect-list-items` stops accumulation when ordered/unordered type changes.

---

### UX-054: Submit arrow button `→` in `ask-input-field` has no `aria-label`
- **Severity**: Low
- **File**: `/Users/leif/Code/noumenon/ui/src/noumenon/ui/views/ask.cljs:415-421`
- **Description**: The submit button renders only `"\u2192"` (→) as its content with no `aria-label`. Screen readers announce this as "right arrow button" or simply "button" depending on the AT — neither conveys the action "Submit question". The `Stop` button at line 407-413 correctly uses visible text "Stop" but also lacks an `aria-label` for the specific action context.
- **Evidence**: `[:button {:on {:click [:action/ask-submit]} :style {...}} "\u2192"]` — no `aria-label`. The `Stop` button: `[:button {:on {:click [:action/ask-cancel]} ...} "Stop"]` — visible text "Stop" serves as accessible name but lacks context that it cancels the current query.
- **Impact**: Screen reader users cannot discover the submit action. The icon-only button is invisible to AT without pointing devices.
- **Recommendation**: Add `:aria-label "Submit question"` to the arrow button. Optionally add `:aria-label "Stop current query"` to the Stop button for clarity.

---

### UX-055: `ask-hint` uses deprecated `navigator.platform` for Mac detection
- **Severity**: Minor
- **File**: `/Users/leif/Code/noumenon/ui/src/noumenon/ui/views/ask.cljs:464-466`
- **Description**: The keyboard shortcut hint uses `(re-find #"Mac" (.-platform js/navigator))` to detect macOS and show `⌘K` vs `Ctrl+K`. `navigator.platform` is deprecated as of Chrome 101 and removed in some browsers; it returns an empty string in sandboxed iframes and may return incorrect values. The modern replacement is `navigator.userAgentData.platform` with a `"macOS"` check, or `navigator.userAgent` as a fallback.
- **Evidence**: `ask.cljs:465`: `(re-find #"Mac" (.-platform js/navigator))` — deprecated API.
- **Impact**: On browsers where `navigator.platform` is empty or absent, `mac?` is falsy and Mac users see `Ctrl+K` instead of `⌘K`. This is confusing though not blocking — `⌘K` still works because the keyboard handler checks both `metaKey` and `ctrlKey`.
- **Recommendation**: Replace with `(some-> js/navigator .-userAgentData .-platform (str/includes? "macOS"))` with fallback to `(re-find #"Mac|iPhone|iPad" (.. js/navigator -userAgent))`.

---

### UX-056: `graph-empty-state` is a no-arg function after refactor — depth context is permanently lost
- **Severity**: Normal
- **File**: `/Users/leif/Code/noumenon/ui/src/noumenon/ui/views/graph.cljs:9-20` and `:77-78`
- **Description**: The refactor at commit 5095439 extracted `graph-empty-state` into a standalone function taking no arguments. The calling site `(graph-empty-state)` at line 78 does not pass `depth`, which is in scope in `graph-canvas`. As a result, the empty-state message is permanently hardcoded to "No components found — Run noum digest", regardless of whether the user is at `:files` or `:segments` depth. UX-007 (depth-aware empty state) identified this as a needed fix; the refactor made that fix structurally harder — now it requires changing the function signature as well.
- **Evidence**: `graph.cljs:9`: `(defn- graph-empty-state [])` — takes no args. `graph.cljs:78`: `(graph-empty-state)` — no depth passed. `depth` is available in the `let` at line 68 but is not threaded through.
- **Impact**: Users at `:files` depth (inside a component) with no analyzed files see "No components found — Run noum digest", which is incorrect advice. The regression was introduced by the refactor and makes the existing UX-007 fix harder to apply.
- **Recommendation**: Change the signature to `(defn- graph-empty-state [depth])` and pass `depth` at the call site: `(graph-empty-state depth)`. Then implement the depth-aware message from UX-007: `(case depth :components "No components found..." :files "No files found in this component" :segments "No segments found in this file")`.

---

*Pass 4 total: 3 net-new issues (UX-054 through UX-056), 4 prior issues confirmed resolved (UX-033, UX-046, UX-047, UX-048)*

| Category | New Issues |
|---|---|
| Accessibility | UX-054 |
| Content/Terminology | UX-055 |
| Regression from Refactor | UX-056 |

---

## Pass 5 Findings

**Scope**: Full repo at commit 3432391 — saturation sweep focused on CLI errors, long-running feedback, MCP tool descriptions, help text completeness, web UI state management, keyboard navigation
**Commit**: 3432391
**Date**: 2026-04-02

---

---

### UX-058: `noum open` silently fails if `npx` or Electron is not installed — no actionable error message
- **Severity**: Medium
- **File**: `/Users/leif/Code/noumenon/launcher/src/noum/main.clj:464-474`
- **Description**: `do-open` calls `npx electron ui/` via `proc/process` with `:inherit true`. If `npx` is not on PATH, or if Electron is not installed in the local `node_modules`, the process exits non-zero but `do-open` returns that exit code directly with no additional message. The user sees only the shell error from `npx` (e.g. `npx: command not found`) with no guidance on what to do.
- **Evidence**: `(let [result @(proc/process {:cmd ["npx" "electron" "ui/"] ...})] (:exit result))` — no error check, no catch, no guidance message.
- **Impact**: Users who run `noum open` without Node.js or without a development checkout get a confusing shell error with no instruction. The help text for `open` says only `"noum open"` with no prerequisites listed.
- **Recommendation**: Check the exit code after `proc/process`. If non-zero, print an actionable message: `"Could not open UI. Ensure Node.js and Electron are installed: npm install electron. If running from the installed jar, use --port to find the port and open http://localhost:<port> manually."` Update the `open` command's `:summary` to note the Node.js requirement.

---

### UX-059: `do-api-command` prints generic `"Command 'X' is not yet implemented."` for commands missing `api-path` — `watch`, `status`, `schema`, `delete`, `results`, `compare`, `history`, `demo`, `setup`, `start`, `stop`, `ping`, `upgrade`, `serve`, `open`, `version` all silently fall through
- **Severity**: Low
- **File**: `/Users/leif/Code/noumenon/launcher/src/noum/main.clj:271-289`
- **Description**: `do-api-command` is the default handler for commands not in the `dispatch` map. It checks `(not api-path)` and prints `"Command 'X' is not yet implemented."`. However, all commands have dedicated handlers in `dispatch` — the message should never appear. If a future command is added to `cli/commands` but not to `dispatch`, it prints a misleading "not yet implemented" rather than "internal error: no handler". More immediately, if a typo is made in `dispatch`'s key (e.g. `"upgade"` instead of `"upgrade"`), the fallback fires with a confusing message.
- **Evidence**: `(not api-path) (do (tui/eprintln (str "Command '" command "' is not yet implemented.")) 1)` — defensive but misleading.
- **Impact**: Low risk in practice. The message confuses users who may see it in certain edge cases and think the feature is coming soon rather than that there is a bug.
- **Recommendation**: Change the message to `"Internal error: no handler for command '"` + `command` + `"'. Please report this bug."` This accurately describes the situation and prompts users to file a bug rather than wait for a feature.

---

### UX-060: `noumenon_list_queries` MCP tool has no `repo_path` parameter but requires a meta database — on first call with no database seeded, it returns `"No named queries found. Run noumenon_reseed or noumenon_import..."` which is confusing: `noumenon_reseed` also takes no `repo_path`
- **Severity**: Low
- **File**: `/Users/leif/Code/noumenon/src/noumenon/mcp.clj:364-378`
- **Description**: `handle-list-queries` uses `util/resolve-db-dir defaults` to find the meta database. If the meta database has not been initialized (e.g., fresh install), it returns `"No named queries found. Run noumenon_reseed or noumenon_import to initialize."` The message is correct but `noumenon_reseed` and `noumenon_import` both require `repo_path`. An LLM reading this message cannot know whether to call `noumenon_reseed` (no args) or `noumenon_import` (requires repo_path). The tool description for `noumenon_list_queries` itself says `"Use this to discover what structured questions you can ask"` but does not say "call noumenon_import or noumenon_update first if this is a fresh install."
- **Evidence**: `tool-result "No named queries found. Run noumenon_reseed or noumenon_import to initialize."` — ambiguous recovery instruction.
- **Impact**: An LLM agent that calls `noumenon_list_queries` on a fresh install may call `noumenon_reseed` (no args) or be confused and halt. The correct first step is `noumenon_update <repo_path>`.
- **Recommendation**: Change the empty-state message to: `"No named queries found. First call noumenon_update or noumenon_import with your repo_path to initialize the knowledge graph, then retry."` This gives LLMs a clear, actionable next step.

---

### UX-061: `noum watch` in the launcher prints `"  Warning: update failed: X"` but never includes the failed repo path — if the user is watching multiple repos in separate terminals, they cannot tell which watch session is failing
- **Severity**: Low
- **File**: `/Users/leif/Code/noumenon/launcher/src/noum/main.clj:373`
- **Description**: The watch failure warning is `(str (style/yellow "  Warning: ") "update failed: " (:error resp))`. The `repo-path` variable is in scope at this point but is not included in the message. When running multiple watch sessions in separate tmux panes or screen splits, users see `"Warning: update failed: ..."` without knowing which repository is affected.
- **Evidence**: `(tui/eprintln (str (style/yellow "  Warning: ") "update failed: " (:error resp)))` — `repo-path` is bound in the enclosing `let` but unused here.
- **Impact**: Low — mostly affects power users running multiple watch sessions. But it also makes correlating failures with repos impossible in log aggregators.
- **Recommendation**: Include the repo name: `(str "  Warning: [" (last (str/split repo-path #"/")) "] update failed: " (:error resp))`.

---

### UX-062: `introspect-view` empty state gives no guidance on what "Start an introspect session" requires — no mention of required prior steps (analyze + benchmark must exist)
- **Severity**: Low
- **File**: `/Users/leif/Code/noumenon/ui/src/noumenon/ui/views/introspect.cljs:70-73`
- **Description**: When no introspect history exists, the view renders `"No introspect history. Start an introspect session to begin."` with a "Start" button. However, introspect requires both analyzed files and an existing benchmark run to function. Clicking "Start" without those prerequisites results in an error from the backend. The empty state gives no indication of these prerequisites.
- **Evidence**: `[:p {:style {:color ...}} "No introspect history. Start an introspect session to begin."]` — no prerequisite guidance. The introspect start action eventually calls `do-introspect` in `main.clj`, which will fail if no benchmark data or analysis exists.
- **Impact**: Users new to the tool click "Start" and get a backend error with no explanation. They don't know they need to run `noum digest` or `noum analyze` + `noum bench` first.
- **Recommendation**: Update the empty state message to: `"No introspect history. Introspect requires an analyzed repository with at least one benchmark run. Run Digest or Analyze + Benchmark first, then start an introspect session."` Consider disabling the "Start" button and showing it as enabled only when benchmark data is present.

---

### UX-063: `benchmark-view` "Run Benchmark" button has no tooltip or description of what it does — pressing it launches an expensive LLM operation with no confirmation
- **Severity**: Medium
- **File**: `/Users/leif/Code/noumenon/ui/src/noumenon/ui/views/benchmark.cljs:100-102`
- **Description**: The "Run Benchmark" button dispatches `:action/bench-run` immediately on click with no confirmation dialog, no cost estimate, and no description of what the operation entails. The operation makes multiple LLM API calls (potentially 22-40+ questions) and can cost several dollars. There is a `WARNING: Expensive` note in the MCP tool description but no equivalent warning in the web UI.
- **Evidence**: `(button/primary {:on {:click [:action/bench-run]} :disabled? running?} (if running? "Running..." "Run Benchmark"))` — no confirmation, no cost estimate.
- **Impact**: Users who click "Run Benchmark" expecting a quick status check are surprised by the LLM costs. Users on metered API keys with low quotas may exhaust them unintentionally.
- **Recommendation**: Add a `title` tooltip to the button: `"Runs 22+ questions against your LLM provider — may take 5-15 minutes and incur API costs."` Alternatively, show a brief cost warning message below the button. For destructive/expensive operations, a single-click confirmation pattern (e.g. first click shows "Are you sure? Click again to confirm") is a common low-friction pattern.

---

### UX-064: MCP `noumenon_digest` description says `"WARNING: analyze and benchmark steps are expensive"` but the `skip_synthesize` flag is not documented in the description — LLMs may not know synthesize can be skipped
- **Severity**: Low
- **File**: `/Users/leif/Code/noumenon/src/noumenon/mcp.clj:207-220`
- **Description**: The `noumenon_digest` tool description says `"Use skip_analyze and skip_benchmark for a quick structural import."` This mentions two skip flags but the tool schema also has `skip_import`, `skip_enrich`, and `skip_synthesize`. The description's guidance only mentions two of the five skip flags. An LLM trying to run a cheap digest (import + enrich only, no analyze/synthesize/benchmark) would need to pass all three of `skip_analyze`, `skip_synthesize`, `skip_benchmark` but the description only mentions two.
- **Evidence**: Tool description: `"Use skip_analyze and skip_benchmark for a quick structural import."` Schema has: `skip_import`, `skip_enrich`, `skip_analyze`, `skip_synthesize`, `skip_benchmark`. The omission of `skip_synthesize` from the description means an LLM following the description will still run synthesize (an LLM call) even when trying to do a "cheap" structural import.
- **Impact**: LLMs using `noumenon_digest` to do quick structural imports will incur synthesize LLM costs they were trying to avoid. The missing `skip_synthesize` in guidance increases unexpected token spend.
- **Recommendation**: Update the description to: `"Use skip_analyze, skip_synthesize, and skip_benchmark for a quick structural import (no LLM calls)."` or provide an example: `"Quick structural import: pass skip_analyze=true, skip_synthesize=true, skip_benchmark=true."`

---

### UX-065: `noum schema <repo>` in the launcher calls `do-schema` which prints raw schema text and exits 0, but the JVM CLI's `show-schema` subcommand uses `log!` (stderr) for output while `noum schema` uses `println` (stdout) — inconsistent output channels
- **Severity**: Low
- **File**: `/Users/leif/Code/noumenon/launcher/src/noum/main.clj:303-307` and `/Users/leif/Code/noumenon/src/noumenon/main.clj:381-392`
- **Description**: `do-schema` in the launcher prints `(get-in resp [:data :schema])` to stdout via `println`. The JVM CLI's `do-show-schema` uses `(log! summary)` which writes to stderr (since `log!` wraps `binding.eprintln`). The two interfaces print schema data to different output channels. Users piping `noum schema <repo>` output will receive it on stdout, but users piping `noumenon show-schema <repo>` will receive it on stderr. This inconsistency breaks scripted use cases.
- **Evidence**: Launcher `do-schema`: `(do (println (get-in resp [:data :schema])) 0)`. JVM `do-show-schema`: `(log! summary)` where `log!` writes to stderr. The JVM CLI epilog says `"Stdout: EDN result map (for scripting)"` but schema output goes to stderr.
- **Impact**: Scripts that pipe `noumenon show-schema myrepo | grep ":file/"` get no output because the schema goes to stderr, not stdout. Users debugging this are confused by the platform inconsistency.
- **Recommendation**: Change `do-show-schema` in `main.clj` to write the human-readable schema to stdout: `(println summary)`. Alternatively, ensure the EDN result map (printed by the default result output path) includes the schema text in a queryable field and document this for scripting users.

---

*Pass 5 total new issues: 9 (UX-057 through UX-065)*

| Category | New Issues |
|---|---|
| Keyboard Navigation / Accessibility | UX-057 |
| CLI Error Messages | UX-058, UX-059, UX-061, UX-065 |
| MCP Tool Descriptions | UX-060, UX-064 |
| Long-Running Operation Feedback | UX-063 |
| Web UI State / Empty States | UX-062, UX-063 |
