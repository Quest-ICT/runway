/* Externalized from admin/page-editor.html so the console page carries no inline <script> — that is what lets the CSP
   script-src drop 'unsafe-inline'. Loaded deferred; behaviour is identical to the former inline block. */
    // Editor / preview column split: swap the Bootstrap col-lg-* widths on the two columns and remember the
    // choice per browser. Dormant (buttons disabled, editor full width) while the preview is popped out, and
    // re-applied on reattach. Defined BEFORE the pop-out block so its on-load re-detach can call in.
    (function () {
        var group = document.getElementById('pe-ratio');
        var leftCol = document.getElementById('pe-col-left');
        var rightCol = document.getElementById('pe-col-right');
        if (!group || !leftCol || !rightCol) { return; }
        // key → [left cols, right cols]. ¾ (9/3) forces a narrow preview to preview a mobile-width layout.
        var RATIOS = { '13': [4, 8], '12': [6, 6], '23': [8, 4], '34': [9, 3] };
        var KEY = 'runway.pe.ratio';
        var buttons = Array.prototype.slice.call(group.querySelectorAll('.pe-ratio-btn'));

        function setCols(col, n) {
            col.className = col.className.replace(/\bcol-lg-\d+\b/g, '').replace(/\s+/g, ' ').trim();
            col.classList.add('col-lg-' + n);
        }
        function current() {
            var v = null;
            try { v = localStorage.getItem(KEY); } catch (e) { /* private mode */ }
            return RATIOS[v] ? v : '12';
        }
        // The chosen split is shown by .active AND announced by aria-pressed (state not by colour alone).
        function mark(key) {
            buttons.forEach(function (b) {
                var on = b.getAttribute('data-ratio') === key;
                b.classList.toggle('active', on);
                b.setAttribute('aria-pressed', on ? 'true' : 'false');
            });
        }
        // Detached = the editor owns the full width (col-lg-12); the pop-out sets this and the split is dormant.
        function detached() { return leftCol.classList.contains('col-lg-12'); }
        // Unconditionally write the two column widths for a ratio (strips any col-lg-*, including the detached 12).
        function place(key) {
            var r = RATIOS[key] || RATIOS['12'];
            setCols(leftCol, r[0]);
            setCols(rightCol, r[1]);
        }
        // Button-click path: mark the choice, but leave the widths to the pop-out while detached.
        function apply(key) {
            mark(key);
            if (!detached()) { place(key); }
        }

        buttons.forEach(function (b) {
            b.addEventListener('click', function () {
                var key = b.getAttribute('data-ratio');
                try { localStorage.setItem(KEY, key); } catch (e) { /* private mode */ }
                apply(key);
            });
        });

        window.__peRatio = {
            // Reattach: force-restore the saved split even though the editor is still at col-lg-12 right now.
            attach: function () { var k = current(); mark(k); place(k); },
            detach: function () { setCols(leftCol, 12); },  // hand the full width to the editor (popped out)
            setEnabled: function (on) { buttons.forEach(function (b) { b.disabled = !on; }); }
        };

        apply(current());   // initial split from the persisted choice (overrides the server-rendered ½ default)
    })();

    (function () {
        var btn = document.getElementById('pe-popout');
        // Looked up on every use, never cached: the live preview below replaces the #pe-preview element with a
        // freshly loaded one after each render, so a reference kept from page load would point at a removed frame.
        function frame() { return document.getElementById('pe-preview'); }
        if (!btn || !frame()) { return; }
        var label = document.getElementById('pe-popout-label');
        var note = document.getElementById('pe-preview-detached');
        var leftCol = document.getElementById('pe-col-left');
        var rightCol = document.getElementById('pe-col-right');
        // Scope the pop-out window name PER PAGE (from its unique preview-window URL) so two editor tabs open on
        // different pages each own their own detached window instead of fighting over one shared popup.
        var WIN = 'runwayPagePreview:' + (btn.getAttribute('data-popout-url') || '').replace(/[^a-zA-Z0-9]+/g, '_');
        var FLAG = 'runway.pe.popout';          // sessionStorage (per editor tab), survives the save reload
        var win = null;
        // Guard sessionStorage exactly like the ratio control guards localStorage: a browser/profile with site
        // storage blocked throws SecurityError on access. Degrade to "not popped out" so the state machine and
        // the close-recovery focus handler below still install instead of dying on an uncaught throw.
        function flagGet() { try { return sessionStorage.getItem(FLAG); } catch (e) { return null; } }
        function flagSet() { try { sessionStorage.setItem(FLAG, '1'); } catch (e) { /* blocked storage */ } }
        function flagClear() { try { sessionStorage.removeItem(FLAG); } catch (e) { /* blocked storage */ } }

        // Open the dedicated pop-out HOST page (a clearly-titled "Preview" window that frames the sandboxed
        // preview), not the raw preview — so the detached window is never mistaken for the live site.
        function popUrl() { return btn.getAttribute('data-popout-url') || ''; }   // always rendered by the template

        function reflect(detached) {
            var f = frame();
            if (f) { f.style.display = detached ? 'none' : ''; }
            if (note) { note.style.display = detached ? '' : 'none'; }
            if (label) { label.textContent = detached ? (btn.getAttribute('data-label-back') || 'Reattach')
                                                      : (btn.getAttribute('data-label-out') || 'Pop out'); }
            if (rightCol) { rightCol.classList.toggle('d-none', detached); }
            // Widths are owned by the ratio control: full width to the editor while detached, saved split on
            // reattach. Fall back to a ½ / full-width toggle if the ratio module didn't initialise.
            if (window.__peRatio) {
                window.__peRatio.setEnabled(!detached);
                if (detached) { window.__peRatio.detach(); } else { window.__peRatio.attach(); }
            } else if (leftCol) {
                leftCol.classList.toggle('col-lg-6', !detached);
                leftCol.classList.toggle('col-lg-12', detached);
            }
        }

        function popOut() {
            win = window.open(popUrl(), WIN, 'popup,width=1024,height=920');
            if (!win) { return; }                // popup blocked — leave the panel embedded
            try { win.focus(); } catch (e) { /* ignore */ }
            flagSet();
            reflect(true);
        }

        function reattach() {
            flagClear();
            if (win && !win.closed) { try { win.close(); } catch (e) { /* ignore */ } }
            win = null;
            reflect(false);
        }

        btn.addEventListener('click', function () {
            if (flagGet()) { reattach(); } else { popOut(); }
        });

        // The editor does a full reload on save. If it was popped out, re-point the still-open same-named window
        // at the fresh saved-draft preview (this navigates it — not a new popup — so no gesture is needed) and
        // keep the detached layout. If the window is gone, opening a NEW one without a gesture is blocked
        // (window.open returns null), so we cleanly fall back to the embedded panel.
        if (flagGet()) {
            win = window.open(popUrl(), WIN);
            if (win) { reflect(true); } else { reattach(); }
        }

        // If the author closes the pop-out window themselves, restore the embedded panel when the editor
        // regains focus (there is no cross-window close event we can rely on with a sandboxed opaque origin).
        window.addEventListener('focus', function () {
            if (flagGet() && (!win || win.closed)) { reattach(); }
        });
    })();

    // Expand/collapse toggles: cap each code editor's height (they scroll internally) with a click to grow it.
    (function () {
        document.querySelectorAll('.pe-cm-expand').forEach(function (b) {
            b.addEventListener('click', function () {
                var host = document.getElementById(b.getAttribute('data-target'));
                if (!host) { return; }
                var tall = host.classList.toggle('pe-cm-tall');
                var icon = b.querySelector('i');
                if (icon) { icon.classList.toggle('fa-expand', !tall); icon.classList.toggle('fa-compress', tall); }
                b.setAttribute('title', tall ? (b.getAttribute('data-title-collapse') || 'Collapse')
                                             : (b.getAttribute('data-title-expand') || 'Expand'));
                b.setAttribute('aria-pressed', tall ? 'true' : 'false');
            });
        });
    })();

    // Download / upload the editor content. The CodeMirror facade stores each instance on the textarea as
    // `__cmMounted` (getValue/setValue) and mirrors every change back into textarea.value, so download reads
    // the live value and upload writes through setValue (which re-syncs the textarea, so the normal Save then
    // submits the uploaded content). Everything is client-side — no round-trip; the author still clicks Save.
    (function () {
        function valueOf(textareaId) {
            var ta = document.getElementById(textareaId);
            if (!ta) { return ''; }
            return (ta.__cmMounted && ta.__cmMounted.getValue) ? ta.__cmMounted.getValue() : ta.value;
        }
        function setValueOf(textareaId, text) {
            var ta = document.getElementById(textareaId);
            if (!ta) { return; }
            if (ta.__cmMounted && ta.__cmMounted.setValue) { ta.__cmMounted.setValue(text); }
            else { ta.value = text; }
        }
        document.querySelectorAll('.pe-cm-download').forEach(function (b) {
            b.addEventListener('click', function () {
                var content = valueOf(b.getAttribute('data-target'));
                var blob = new Blob([content], { type: 'text/plain;charset=utf-8' });
                var url = URL.createObjectURL(blob);
                var a = document.createElement('a');
                a.href = url;
                a.download = b.getAttribute('data-filename') || 'page.txt';
                document.body.appendChild(a);
                a.click();
                document.body.removeChild(a);
                setTimeout(function () { URL.revokeObjectURL(url); }, 2000);
            });
        });
        document.querySelectorAll('.pe-cm-upload').forEach(function (b) {
            b.addEventListener('click', function () {
                var input = document.querySelector('.pe-cm-file[data-target="' + b.getAttribute('data-target') + '"]');
                if (input) { input.click(); }
            });
        });
        document.querySelectorAll('.pe-cm-file').forEach(function (input) {
            input.addEventListener('change', function () {
                var file = input.files && input.files[0];
                if (!file) { return; }
                var target = input.getAttribute('data-target');
                var existing = valueOf(target);
                // Only ask before clobbering real content the author might not have saved.
                if (existing && existing.trim() && !window.confirm(input.getAttribute('data-confirm') || 'Replace the editor content?')) {
                    input.value = '';
                    return;
                }
                var reader = new FileReader();
                reader.onload = function () {
                    setValueOf(target, String(reader.result));
                    if (window.__peLive) { window.__peLive.schedule(); }   // an upload is an edit: re-render
                };
                reader.onloadend = function () { input.value = ''; };   // allow re-picking the same file
                reader.readAsText(file);
            });
        });
    })();

    // Page-quality panel: per-finding "ignore" (persisted per page; ERROR findings render no ignore button and
    // are never ignorable) plus the Preview header status flag. The flag lights as ERROR if any error finding
    // exists (ignored or not), else as WARNING if any non-ignored finding remains, else it is hidden.
    (function () {
        var flag = document.getElementById('pe-quality-flag');
        var flagIcon = document.getElementById('pe-quality-flag-icon');
        if (!flag) { return; }
        var rows = Array.prototype.slice.call(document.querySelectorAll('.pe-f-row'));
        var m = location.pathname.match(/\/pages\/([^\/]+)\//);
        var KEY = 'runway.pe.qignore.' + (m ? m[1] : 'page');

        function load() {
            try { var v = JSON.parse(localStorage.getItem(KEY) || '[]'); return Array.isArray(v) ? v : []; }
            catch (e) { return []; }
        }
        var ignored = load();
        function persist() { try { localStorage.setItem(KEY, JSON.stringify(ignored)); } catch (e) { /* blocked */ } }

        function refreshFlag() {
            var anyError = false, anyActive = false;
            rows.forEach(function (row) {
                if (row.getAttribute('data-severity') === 'error') { anyError = true; }
                else if (!row.classList.contains('pe-f-ignored')) { anyActive = true; }
            });
            if (!anyError && !anyActive) { flag.style.display = 'none'; return; }
            flag.style.display = '';
            if (flagIcon) {
                flagIcon.classList.toggle('fa-circle-exclamation', anyError);
                flagIcon.classList.toggle('fa-triangle-exclamation', !anyError);
                flagIcon.classList.toggle('text-danger', anyError);
                flagIcon.classList.toggle('text-warning', !anyError);
            }
            flag.setAttribute('title', anyError ? (flag.getAttribute('data-title-error') || 'Page quality: errors')
                                                : (flag.getAttribute('data-title-warn') || 'Page quality: open items'));
        }

        function reflectBtn(btn, ig) {
            var icon = btn.querySelector('i');
            if (icon) { icon.classList.toggle('fa-bell-slash', !ig); icon.classList.toggle('fa-bell', ig); }
            btn.setAttribute('title', ig ? (btn.getAttribute('data-title-unignore') || 'Stop ignoring')
                                         : (btn.getAttribute('data-title-ignore') || 'Ignore'));
            btn.setAttribute('aria-pressed', ig ? 'true' : 'false');
        }

        rows.forEach(function (row) {
            if (row.getAttribute('data-severity') === 'error') { return; }  // errors: not ignorable, no button
            var code = row.getAttribute('data-code');
            var btn = row.querySelector('.pe-f-ignore');
            if (code && ignored.indexOf(code) >= 0) { row.classList.add('pe-f-ignored'); }
            if (!btn) { return; }
            reflectBtn(btn, row.classList.contains('pe-f-ignored'));
            btn.addEventListener('click', function () {
                var ig = !row.classList.contains('pe-f-ignored');
                row.classList.toggle('pe-f-ignored', ig);
                if (code) {
                    var i = ignored.indexOf(code);
                    if (ig && i < 0) { ignored.push(code); } else if (!ig && i >= 0) { ignored.splice(i, 1); }
                    persist();
                }
                reflectBtn(btn, ig);
                refreshFlag();
            });
        });

        refreshFlag();
    })();

    // Live preview of UNSAVED edits. ~600 ms after the author stops typing, the current contents of both code
    // editors are POSTed to the isolated preview endpoint (/__admin/pages/<slug>/preview) and the frame shows the
    // result; a badge says the preview is ahead of what is saved.
    //
    // How the response reaches the frame — and why not srcdoc or a blob: URL. The endpoint answers with its own
    // `Content-Security-Policy: sandbox allow-scripts` header, so the author's page renders in an opaque origin with
    // its scripts running, exactly like the saved-draft preview. A srcdoc or blob: document would instead INHERIT
    // this console page's policy: in enforce mode every author script without the console's nonce would be
    // blocked, and in the default report mode each one would be reported to /__csp-report — the very noise the
    // endpoint's own header exists to avoid. So the hidden #pe-live-form (CSRF token included) is submitted with
    // its target set to a frame, and the browser navigates that frame to the response.
    //
    // Double buffering. Each render goes into a NEW sandboxed frame, cloned from the visible one and kept hidden
    // over it until it has loaded; then it takes the #pe-preview id and the old frame is removed. The author never
    // sees a blank frame, and a frame's first navigation replaces its initial about:blank instead of adding a
    // history entry — so the browser's Back button is not filled with preview renders (re-POSTing them). (A browser
    // that fired a late load event for the new frame's initial about:blank would only swap a moment early.)
    //
    // Rate: one render in flight at a time; edits made meanwhile are rendered when it lands (the latest content
    // only — intermediate states are skipped). Content identical to what is shown is never re-sent, and content
    // back to the saved state shows the saved preview again. While the preview is popped out it is not rendered
    // here (the detached window keeps the saved draft); reattaching catches up.
    //
    // Fallback: if a render does not load within data-timeout ms, or the submission throws, the saved-draft
    // preview is shown again and live rendering stops for this page view — the editor keeps working as before.
    // A server-side failure to RENDER is not a failure here: the endpoint answers 200 with the error in the page.
    (function () {
        var form = document.getElementById('pe-live-form');
        var bodyTa = document.getElementById('pe-body');
        var logicTa = document.getElementById('pe-logic');
        var first = document.getElementById('pe-preview');
        if (!form || !bodyTa || !first) { return; }
        var htmlField = document.getElementById('pe-live-html');
        var logicField = document.getElementById('pe-live-thxml');
        var badge = document.getElementById('pe-live-badge');
        var savedSrc = form.getAttribute('data-saved-src') || first.getAttribute('src') || '';
        var DEBOUNCE = parseInt(form.getAttribute('data-debounce'), 10) || 600;
        var TIMEOUT = parseInt(form.getAttribute('data-timeout'), 10) || 15000;

        function valueOf(ta) {
            if (!ta) { return ''; }
            return (ta.__cmMounted && ta.__cmMounted.getValue) ? ta.__cmMounted.getValue() : ta.value;
        }
        // The textareas hold the saved sources until CodeMirror mounts over them (it starts from the same text).
        var savedHtml = valueOf(bodyTa), savedLogic = valueOf(logicTa);
        var shownHtml = savedHtml, shownLogic = savedLogic;   // what the visible frame renders
        var timer = null, inFlight = false, dirty = false, broken = false, seq = 0;

        function current() { return document.getElementById('pe-preview'); }
        function detached() { var f = current(); return !f || f.style.display === 'none'; }
        function setBadge(on) { if (badge) { badge.classList.toggle('d-none', !on); } }

        // Loads a new hidden frame next to the visible one; on load it replaces the visible one. ready(true) once
        // swapped, ready(false) on timeout or a throwing navigation (the staged frame is then discarded).
        function stage(navigate, ready) {
            var cur = current();
            var next = cur.cloneNode(false);
            next.removeAttribute('id');
            next.removeAttribute('src');
            next.setAttribute('name', 'pe-live-' + (++seq) + '-' + Date.now());
            next.setAttribute('aria-hidden', 'true');
            next.setAttribute('tabindex', '-1');
            next.classList.add('pe-live-staging');
            cur.parentNode.insertBefore(next, cur.nextSibling);
            var settled = false;
            function finish(ok) {
                if (settled) { return; }
                settled = true;
                clearTimeout(watchdog);
                if (!ok) {
                    if (next.parentNode) { next.parentNode.removeChild(next); }
                    ready(false);
                    return;
                }
                var old = current();
                next.classList.remove('pe-live-staging');
                next.removeAttribute('aria-hidden');
                next.removeAttribute('tabindex');
                if (old && old !== next && old.parentNode) {
                    next.style.display = old.style.display;   // popped out meanwhile: stay hidden
                    old.parentNode.removeChild(old);
                }
                next.id = 'pe-preview';
                ready(true);
            }
            var watchdog = setTimeout(function () { finish(false); }, TIMEOUT);
            next.addEventListener('load', function () { finish(true); });
            try { navigate(next); } catch (e) { finish(false); }
        }

        function fallBack() {
            broken = true;
            setBadge(false);
            shownHtml = savedHtml;
            shownLogic = savedLogic;
            stage(function (f) { f.setAttribute('src', savedSrc); }, function () { /* best effort */ });
        }

        function render() {
            timer = null;
            if (broken) { return; }
            if (detached()) { dirty = true; return; }
            var h = valueOf(bodyTa), l = valueOf(logicTa);
            if (h === shownHtml && l === shownLogic) { return; }
            if (inFlight) { dirty = true; return; }
            inFlight = true;
            dirty = false;
            var backToSaved = h === savedHtml && l === savedLogic;
            stage(function (f) {
                if (backToSaved) { f.setAttribute('src', savedSrc); return; }
                htmlField.value = h;
                logicField.value = l;
                form.setAttribute('target', f.getAttribute('name'));
                form.submit();
            }, function (ok) {
                inFlight = false;
                if (!ok) { fallBack(); return; }
                shownHtml = h;
                shownLogic = l;
                setBadge(!backToSaved);
                if (dirty) { render(); }
            });
        }

        function schedule() {
            if (broken) { return; }
            if (timer) { clearTimeout(timer); }
            timer = setTimeout(render, DEBOUNCE);
        }
        window.__peLive = { schedule: schedule };

        // CodeMirror mirrors every change into its textarea but fires no event there, so listen on the editor hosts
        // (input/keyup/paste/cut/drop bubble out of the editor) — render() compares the content, so a key that
        // changed nothing (an arrow, Shift) costs a comparison and no request.
        ['pe-body-host', 'pe-logic-host'].forEach(function (id) {
            var host = document.getElementById(id);
            if (!host) { return; }
            ['input', 'keyup', 'paste', 'cut', 'drop'].forEach(function (type) {
                host.addEventListener(type, schedule);
            });
        });
        // Reattaching a popped-out preview: catch up with what was typed while it was detached.
        var popout = document.getElementById('pe-popout');
        if (popout) { popout.addEventListener('click', function () { if (dirty) { schedule(); } }); }
        window.addEventListener('focus', function () { if (dirty) { schedule(); } });
    })();

