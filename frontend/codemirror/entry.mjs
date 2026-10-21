// Runway tenant page-editor code editor — a self-hosted CodeMirror 6 bundle.
//
// esbuild bundles this into an IIFE (static/vendor/codemirror.min.js) that exposes a small, stable
// window.RunwayEditor facade. CodeMirror 6 injects its own styles at runtime (style-mod), so the
// companion codemirror.min.css only frames the editor host element in the Tabler UI.
//
// Contract:
//   RunwayEditor.create(textarea, {mode, height, dark}) -> { view, getValue, setValue, destroy }
//     Mounts an editor over a <textarea>, hides the textarea, and keeps textarea.value in sync on
//     every edit AND on form submit — so a plain form POST carries the edited source (no JS wiring
//     at the call site). mode: "html" (default) | "xml" | "css".
//   RunwayEditor.mountAll(root?) -> auto-mounts every textarea[data-cm] under root (default document),
//     reading data-cm-mode / data-cm-height; runs once on DOMContentLoaded and is HTMX-rescan safe.
import { EditorView, keymap } from "@codemirror/view";
import { basicSetup } from "codemirror";
import { indentWithTab } from "@codemirror/commands";
import { html } from "@codemirror/lang-html";
import { css } from "@codemirror/lang-css";
import { xml } from "@codemirror/lang-xml";
import { oneDark } from "@codemirror/theme-one-dark";

function langFor(mode) {
  switch ((mode || "html").toLowerCase()) {
    case "css": return css();
    case "xml": return xml();
    default: return html();
  }
}

function create(textarea, opts) {
  opts = opts || {};
  const mode = opts.mode || textarea.getAttribute("data-cm-mode") || "html";
  const height = opts.height || textarea.getAttribute("data-cm-height") || "60vh";
  const dark = opts.dark !== undefined
    ? opts.dark
    : (document.body && document.body.getAttribute("data-cm-theme") === "dark");

  const extensions = [
    basicSetup,
    keymap.of([indentWithTab]),
    langFor(mode),
    // Sync back to the textarea on every edit so a normal form POST carries the source.
    EditorView.updateListener.of((u) => { if (u.docChanged) textarea.value = u.state.doc.toString(); }),
    EditorView.theme({
      "&": { height: height, border: "1px solid var(--tblr-border-color, #dee2e6)", borderRadius: "4px" },
      ".cm-scroller": { overflow: "auto", fontFamily: "var(--tblr-font-monospace, monospace)" },
    }),
  ];
  if (dark) extensions.push(oneDark);

  const view = new EditorView({ doc: textarea.value, extensions });
  textarea.parentNode.insertBefore(view.dom, textarea);
  textarea.style.display = "none";
  // Belt-and-suspenders: force a final sync at submit time.
  if (textarea.form) {
    textarea.form.addEventListener("submit", () => { textarea.value = view.state.doc.toString(); });
  }
  return {
    view,
    getValue: () => view.state.doc.toString(),
    setValue: (val) => view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: val } }),
    destroy: () => view.destroy(),
  };
}

function mountAll(root) {
  const scope = root || document;
  scope.querySelectorAll("textarea[data-cm]").forEach((ta) => {
    if (!ta.__cmMounted) { ta.__cmMounted = create(ta, {}); }
  });
}

window.RunwayEditor = { create, mountAll };
document.addEventListener("DOMContentLoaded", () => mountAll(document));
// HTMX-swap safe: re-mount any editors that arrived in swapped content (no-op without HTMX).
document.addEventListener("htmx:afterSwap", (e) => mountAll(e.target || document));
