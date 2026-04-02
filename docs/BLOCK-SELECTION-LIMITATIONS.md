# Block Selection Limitations in Roam Research

## Problem Statement

Roam Research has two distinct block interaction states:

1. **Editing** — textarea open, cursor blinking, ready for text input
2. **Selected** — block highlighted (blue background), navigable with arrow keys, not editing

There is a public API for entering state 1 (`setBlockFocusAndSelection`), but **no API for entering state 2**. The only documented "set" APIs for selection (`multiselect.getSelected`, `individualMultiselect.getSelectedUids`) are **read-only**.

This document records every approach attempted to programmatically select a block without entering edit mode, and why each failed.

---

## Approaches Attempted

### 1. `setBlockFocusAndSelection` → always enters edit mode

```javascript
window.roamAlphaAPI.ui.setBlockFocusAndSelection({
  location: { "block-uid": uid, "window-id": "main-window" }
});
```

**Result:** Always opens a `<textarea>` and places the cursor at the end of the block text. There is no parameter to enter "selected" state instead of "editing" state. This is documented behavior.

**Useful for:** Edit mode works perfectly. Also handles scrolling into view, virtual list rendering, and sidebar `window-id` routing — the only API that does all of this.

---

### 2. `setBlockFocusAndSelection` + `textarea.blur()`

Enter edit mode, then immediately blur the textarea to exit.

```javascript
await roamAlphaAPI.ui.setBlockFocusAndSelection({...});
// wait for textarea to appear
document.activeElement.blur();
```

**Result:** Creates a "zombie" state — the textarea element remains in the DOM but is unfocused. The block shows the editing text but cannot accept input, and is not highlighted. Roam's internal state still considers the block "editing" because `blur()` only removes DOM focus without triggering React's state cleanup.

---

### 3. `setBlockFocusAndSelection` + React `onBlur` handler

Instead of DOM blur, call the textarea's React `onBlur` handler directly to trigger Roam's internal state transition.

```javascript
const propsKey = Object.keys(textarea).find(k => k.startsWith("__reactProps$"));
const props = textarea[propsKey];
props.onBlur();
```

**Result:** The React handler dispatches a re-frame event (`:relemma.routes.app.events/update-block`) which saves the block text. However, `setBlockFocusAndSelection` holds internal state that overrides the blur — the block re-enters edit mode. Even with delays up to 500ms, the API's focus state persists and the textarea reappears or stays.

When called on a *different* block (not the one just focused by the API), `props.onBlur()` also had no visible effect — the textarea remained in edit mode.

---

### 4. Synthetic `KeyboardEvent` dispatch (Escape)

In normal usage, pressing Escape transitions a block from "editing" → "selected". Attempt to simulate this:

```javascript
textarea.dispatchEvent(new KeyboardEvent("keydown", {
  key: "Escape", code: "Escape", keyCode: 27,
  bubbles: true, cancelable: true
}));
```

**Result:** The event dispatches successfully (`dispatchEvent` returns `true`), but Roam's event handler ignores it. Confirmed by probing: the textarea remains focused after dispatch.

**Root cause:** Browsers mark programmatically dispatched events as `isTrusted: false`. Roam's compiled ClojureScript event handler (or React's synthetic event system) checks this property and ignores untrusted events. This is a browser security feature that cannot be circumvented.

Dispatch was attempted on:
- The textarea element directly
- `document` (for global listeners)
- Both `keydown` and `keyup` events

All had no effect.

---

### 5. Calling React `onKeyDown` handler directly

Bypass the DOM event system entirely by calling the React `onKeyDown` handler function from the textarea's `__reactProps$`:

```javascript
props.onKeyDown({
  key: "Escape", keyCode: 27,
  shiftKey: false, ctrlKey: false, altKey: false, metaKey: false,
  target: textarea,
  preventDefault: () => {},
  stopPropagation: () => {}
});
```

**Result:** The handler executes (confirmed via Proxy trapping — it accesses `target`, `keyCode`, `shiftKey`, `ctrlKey`, `altKey`, `metaKey`). Returns `null`. But the textarea remains focused — the Escape key logic does not fire.

**Probable cause:** The compiled CLJS `onKeyDown` handler (`function(Ea){c(Ea);return Ba(Ea)}`) delegates to two closure-captured functions. The Escape-specific logic may depend on additional internal state (e.g., whether autocomplete is open, or the block's editing context) that our synthetic call doesn't satisfy.

---

### 6. Clicking the bullet (`.rm-bullet__inner`)

```javascript
blockEl.querySelector(".rm-bullet__inner").click();
```

**Result:** Clicking the bullet in Roam **zooms into the block** (navigates to it as a focused page view). This is destructive — it changes the entire view, not what we want.

---

### 7. `textarea.blur()` + `container.focus()`

Blur the textarea, then focus the block container element to simulate Roam's "selected" state:

```javascript
textarea.blur();
container.focus();
```

**Result:** Same zombie state as approach 2. The textarea remains visible but unfocused. The container receives DOM focus but Roam's React/ClojureScript state doesn't recognize this as "block selected." No blue highlight, no arrow key navigation.

---

### 8. CLJS Internal State Manipulation (re-frame dispatch)

Deep spelunking into Roam's compiled ClojureScript runtime (`$APP` namespace) to find and call the re-frame dispatch function directly.

#### Discovery phase (via `eval` command probe):

Found key re-frame event keywords:
```
$APP.aF  → :relemma.routes.app.events/user-focus-block
$APP.EJ  → :relemma.routes.app.events/user-unfocus-block
$APP.$6a → :relemma.routes.app.events/user-refocus-block
$APP.p5a → :relemma.routes.app.events/set-selected
```

Found CLJS infrastructure:
```
$APP.Q   → cljs.core/PersistentVector constructor
$APP.R   → PersistentVector.EMPTY_NODE
$APP.aL  → IDeref protocol (deref method at .J)
```

#### Dispatch approach:

Intercepted `cljs.core/-deref` to capture the re-frame dispatch function from the `onBlur` handler's closure, then dispatched `:set-selected`:

```javascript
// Intercept deref to capture dispatch fn
let dispatchFn = null;
const origDeref = $APP.aL.J;
$APP.aL.J = function(atom) {
  const fn = origDeref.call($APP.aL, atom);
  dispatchFn = fn;
  $APP.aL.J = origDeref;
  return fn;
};
props.onBlur(); // triggers deref, we capture dispatch fn

// Dispatch set-selected
const vec = new $APP.Q(null, 2, 5, $APP.R, [$APP.p5a, uid], null);
dispatchFn.J(vec);
```

**Result (partial success):** The `onBlur` + dispatch DID remove the textarea (`textareaGone: true`). However, the block ended up in the "fully unfocused" state (`rm-not-focused` class), not "selected." The `:set-selected` event may require additional arguments or context, or it may need to be dispatched at a different point in the state machine.

Additionally, dispatching `:user-unfocus-block` also resulted in full deselection, not the intermediate "selected" state.

#### Fatal flaw — munged names:

Roam uses Google Closure Compiler with **advanced optimizations**. All CLJS symbol names (`$APP.p5a`, `$APP.aL`, `$APP.Q`, etc.) are renamed on every Roam release. After a database restore to a different Roam version:

- `$APP.p5a` became `$APP.q5a`
- `$APP.aL.J` (deref method) changed to a different structure entirely (`.je` property, now an object not a function)
- `PersistentVector` no longer had the `cljs$core$IVector$` protocol marker on its prototype

Dynamic resolution by string matching (e.g., finding the keyword whose `toString()` equals `:relemma.routes.app.events/set-selected`) works for keywords but fails for structural types like PersistentVector and the IDeref protocol, whose shapes change unpredictably.

**Conclusion:** CLJS interop is fundamentally fragile and breaks on every Roam update.

---

### 9. Probe-induced crash

During the CLJS exploration, intercepting `$APP.aL.J` (the global `cljs.core/-deref` implementation) to capture dispatch functions corrupted Roam's runtime state. This caused:

- Roam's Local API to return errors (`Cannot read properties of undefined`)
- The app to fail to reload ("Roam wasn't built in a day" error)
- Required a database restore from backup

**Lesson:** Mutating global CLJS protocol implementations is extremely dangerous. `-deref` is called throughout the entire application, and even brief interception can cascade into unrecoverable state corruption.

---

## What Works

### Clicking the page title to dismiss editing

```javascript
document.querySelector(".rm-title-display").click();
```

A real DOM click that Roam's event handlers process natively. Reliably exits any block's edit mode. This is the only reliable way found to programmatically exit edit mode.

### CSS highlight as selection substitute

The current implementation:
1. Clicks the page title to dismiss any active editing
2. Finds the target block(s) in the DOM via `data-block-uid`
3. Scrolls the first block into view
4. Applies a persistent CSS highlight class matching Roam's native selection color
5. Clears the highlight on next user click or keypress

```css
.agent-select-highlight {
  background: rgb(213, 230, 243) !important;
  border-radius: 4px;
}
```

**Limitations vs native selection:**
- No arrow key navigation between blocks
- No keyboard-driven indent/outdent/move
- No right-click context menu integration
- Purely visual — Roam's internal state doesn't know the block is "selected"

---

## API Gaps Summary

| Capability | API Exists? | Notes |
|---|---|---|
| Enter edit mode | ✅ `setBlockFocusAndSelection` | Works perfectly |
| Read focused block | ✅ `getFocusedBlock` | Returns uid + window-id |
| Read drag-selected blocks | ✅ `multiselect.getSelected` | Read-only |
| Read Cmd-M selected blocks | ✅ `individualMultiselect.getSelectedUids` | Read-only |
| **Set block-level selection** | ❌ | No API exists |
| **Exit edit mode programmatically** | ❌ | Only via DOM click workaround |
| **Transition editing → selected** | ❌ | Only via real Escape keypress |

---

## Recommendation

Roam Research should expose:

```javascript
// Set block-level selection (blue highlight, arrow-key navigable)
window.roamAlphaAPI.ui.setBlockSelection({
  location: { "block-uid": uid, "window-id": windowId }
});

// Clear block selection
window.roamAlphaAPI.ui.clearBlockSelection();

// Exit edit mode without entering selected state
window.roamAlphaAPI.ui.blurBlock();
```

Until then, the CSS highlight approach is the pragmatic ceiling for extension developers.
