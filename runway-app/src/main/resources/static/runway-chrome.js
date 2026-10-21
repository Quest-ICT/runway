/*
 * runway-chrome.js — the operator console's own chrome behaviours. House rules, original code, kept
 * deliberately framework-light (the Tabler bundle wires the Bootstrap data API but exposes NO
 * window.bootstrap, so modals are opened declaratively with data-bs-toggle — Bootstrap then moves focus into
 * the dialog, traps it, closes it on Escape and returns focus to the trigger). Loaded by op-layout from
 * /__assets, cache-busted by ?v=.
 */
(function () {
  "use strict";

  /*
   * Password show/hide eye toggle, wired once for every <input type="password">. The toggle button stays
   * OUT of the tab order (tabindex=-1) on purpose: keyboard and password-manager users go field -> Submit
   * without an extra stop on every password field, and revealing a password is a convenience, not a step
   * the form needs. Its name (aria-label) and state (aria-pressed) are still exposed to assistive technology.
   * Tooltip text comes from the body's data-i18n-pw-toggle (localized server-side).
   */
  function wirePasswordToggles() {
    var label = document.body.getAttribute("data-i18n-pw-toggle") || "Show / hide password";
    var fields = document.querySelectorAll('input[type="password"]');
    for (var i = 0; i < fields.length; i++) {
      var input = fields[i];
      if (input.getAttribute("data-rw-pw-wired")) continue;
      input.setAttribute("data-rw-pw-wired", "1");

      var group = input.parentNode;
      if (!group || !group.classList || !group.classList.contains("input-group")) {
        group = document.createElement("div");
        group.className = "input-group";
        input.parentNode.insertBefore(group, input);
        group.appendChild(input);
      }
      var button = document.createElement("button");
      button.type = "button";
      button.className = "btn";
      button.tabIndex = -1;
      button.title = label;
      button.setAttribute("aria-label", label);
      button.setAttribute("aria-pressed", "false");
      button.innerHTML = '<i class="fa-solid fa-eye"></i>';
      (function (inp, btn) {
        btn.addEventListener("click", function () {
          var show = inp.type === "password";
          inp.type = show ? "text" : "password";
          btn.innerHTML = show ? '<i class="fa-solid fa-eye-slash"></i>' : '<i class="fa-solid fa-eye"></i>';
          btn.setAttribute("aria-pressed", show ? "true" : "false");
        });
      })(input, button);
      group.appendChild(button);
    }
  }

  /*
   * Confirm guard on disruptive actions: any element with data-confirm="message" asks before proceeding.
   * On a form's submit button it gates the submit; on a link it gates navigation.
   */
  function wireConfirmGuards() {
    document.addEventListener("click", function (event) {
      var target = event.target.closest("[data-confirm]");
      if (!target) return;
      var message = target.getAttribute("data-confirm");
      if (message && !window.confirm(message)) {
        event.preventDefault();
        event.stopPropagation();
      }
    }, true);
  }

  /*
   * Dependent settings: an element with data-show-when="<key>" (a settings row, or a whole "Advanced"
   * disclosure) is shown only while the checkbox named <key> in the same form is checked AND that checkbox's
   * own row is shown — a switch nested under a hidden switch is as unreachable as the switch. The server
   * renders the initial state the same way, so there is no flash on load; this follows later clicks. Hidden
   * inputs still submit — hiding is never clearing, so a value survives its switch going off and on again.
   */
  function wireShowWhen() {
    var dependents = [];
    Array.prototype.forEach.call(document.querySelectorAll("[data-show-when]"), function (el) {
      var form = el.closest("form");
      if (!form) return;
      var key = el.getAttribute("data-show-when");
      var gate = Array.prototype.find.call(form.querySelectorAll("input[type='checkbox']"), function (input) {
        return input.name === key;
      });
      if (gate) dependents.push({ el: el, gate: gate });
    });
    if (!dependents.length) return;
    var syncAll = function () {
      // Repeat until stable: a chain settles one level per pass, whatever the document order.
      for (var pass = 0; pass <= dependents.length; pass++) {
        var changed = false;
        dependents.forEach(function (d) {
          var gateHost = d.gate.closest("[data-show-when]");
          var hide = !d.gate.checked || !!(gateHost && gateHost.hidden);
          if (d.el.hidden !== hide) { d.el.hidden = hide; changed = true; }
        });
        if (!changed) return;
      }
    };
    dependents.forEach(function (d) { d.gate.addEventListener("change", syncAll); });
    syncAll();
  }

  /*
   * Accessible names for the CodeMirror code editors. The self-hosted bundle (static/vendor/codemirror.min.js)
   * hides each textarea[data-cm] and mounts an editor whose focusable element is view.contentDOM (role=textbox),
   * which the bundle leaves unnamed. The template names the TEXTAREA (aria-labelledby the card heading,
   * aria-describedby the visible "Esc then Tab" hint); this copies those onto the editor so a screen reader
   * announces the same thing. Done here rather than in frontend/codemirror/entry.mjs so the committed bundle
   * does not have to be rebuilt. mountAll is idempotent, so calling it first makes the order of the two
   * DOMContentLoaded listeners irrelevant. CodeMirror only rewrites attributes it set itself, so these stay.
   */
  function labelCodeEditors() {
    if (window.RunwayEditor && window.RunwayEditor.mountAll) window.RunwayEditor.mountAll(document);
    var areas = document.querySelectorAll("textarea[data-cm]");
    for (var i = 0; i < areas.length; i++) {
      var ta = areas[i];
      var editor = ta.__cmMounted;
      if (!editor || !editor.view || !editor.view.contentDOM) continue;
      ["aria-label", "aria-labelledby", "aria-describedby"].forEach(function (name) {
        var value = ta.getAttribute(name);
        if (value) editor.view.contentDOM.setAttribute(name, value);
      });
    }
  }

  /*
   * Copy buttons: an element with data-copy="<path or URL>" copies it to the clipboard, a site-relative path made
   * absolute on this origin, and shows data-copied (e.g. "Copied") on itself for a moment. Where the clipboard
   * is unavailable (an insecure origin), nothing is copied and the button is left as it was.
   */
  function wireCopyButtons() {
    document.addEventListener("click", function (event) {
      var button = event.target.closest("[data-copy]");
      if (!button || !navigator.clipboard) return;
      var value = button.getAttribute("data-copy");
      if (value.charAt(0) === "/") value = window.location.origin + value;
      navigator.clipboard.writeText(value).then(function () {
        var done = button.getAttribute("data-copied");
        if (!done) return;
        var before = button.textContent;
        button.textContent = done;
        setTimeout(function () { button.textContent = before; }, 1500);
      });
    });
  }

  document.addEventListener("DOMContentLoaded", function () {
    wirePasswordToggles();
    wireCopyButtons();
    labelCodeEditors();
    wireConfirmGuards();
    wireShowWhen();
  });
})();
