/* Externalized from admin/messages.html so the console page carries no inline <script> — that is what lets the CSP
   script-src drop 'unsafe-inline'. Loaded deferred; behaviour is identical to the former inline block. */
    (function () {
        var PAGE_SIZE = 50;
        var table = document.getElementById('msg-table');
        if (!table) { return; }
        var rows = Array.prototype.slice.call(table.querySelectorAll('tbody tr.msg-data-row'));
        var search = document.getElementById('msg-search');
        var pager = document.getElementById('msg-pager');
        var pagination = document.getElementById('msg-pagination');
        var countEl = document.getElementById('msg-count');
        var pageInfo = document.getElementById('msg-pageinfo');
        var prev = document.getElementById('msg-prev');
        var next = document.getElementById('msg-next');
        var page = 0, query = '';

        function rowText(row) {
            var t = '', inputs = row.querySelectorAll('input[type="text"], input[type="search"]');
            for (var i = 0; i < inputs.length; i++) { t += ' ' + (inputs[i].value || '').toLowerCase(); }
            return t;
        }
        function fmt(tpl, a, b) { return (tpl || '').replace('{0}', a).replace('{1}', b); }

        function render() {
            var matched = query ? rows.filter(function (r) { return rowText(r).indexOf(query) >= 0; }) : rows;
            var pages = Math.max(1, Math.ceil(matched.length / PAGE_SIZE));
            if (page >= pages) { page = pages - 1; }
            if (page < 0) { page = 0; }
            var start = page * PAGE_SIZE;
            rows.forEach(function (r) { r.style.display = 'none'; });
            matched.slice(start, start + PAGE_SIZE).forEach(function (r) { r.style.display = ''; });
            if (countEl) {
                countEl.textContent = (matched.length === rows.length)
                    ? fmt(countEl.getAttribute('data-all'), rows.length)
                    : fmt(countEl.getAttribute('data-filtered'), matched.length, rows.length);
            }
            if (pageInfo) { pageInfo.textContent = (page + 1) + ' / ' + pages; }
            if (prev) { prev.disabled = page <= 0; }
            if (next) { next.disabled = page >= pages - 1; }
            if (pagination) { pagination.style.visibility = pages > 1 ? '' : 'hidden'; }
            // Show the footer once the list is long enough to warrant it, or while filtering.
            if (pager) { pager.style.display = (rows.length > PAGE_SIZE || query) ? '' : 'none'; }
        }

        if (search) {
            search.addEventListener('input', function () { query = search.value.trim().toLowerCase(); page = 0; render(); });
            // Enter in the filter box must NOT submit (and save) the whole form.
            search.addEventListener('keydown', function (e) { if (e.key === 'Enter') { e.preventDefault(); } });
        }
        if (prev) { prev.addEventListener('click', function () { page -= 1; render(); }); }
        if (next) { next.addEventListener('click', function () { page += 1; render(); }); }
        render();
    })();
    

/* ---- next block ---- */

    (function () {
        var form = document.querySelector('form[action="/__admin/messages"]');
        if (!form) { return; }
        var saveBtn = document.getElementById('msg-save');
        if (!saveBtn) { return; }
        var statusEl = document.getElementById('msg-save-status');
        var csrfEl = document.getElementById('msg-csrf');

        var baseLabel = (saveBtn.textContent || 'Save').trim();
        var savedMsg = statusEl ? (statusEl.getAttribute('data-saved') || '') : '';
        var failedMsg = statusEl ? (statusEl.getAttribute('data-failed') || '') : '';
        var saving = false;
        var guardArmed = true;

        function valueInputs() { return form.querySelectorAll('input[data-key][data-locale]'); }
        function keepBoxes() { return form.querySelectorAll('input[name="keep"]'); }
        function newValInputs() { return form.querySelectorAll('input[name^="newValue_"]'); }

        // Assemble the delta: value edits on existing rows, the add-a-message row, and removed (un-kept) keys.
        function collect() {
            // Object.create(null): a prototype-less set so a message key named like an Object member
            // (toString, constructor, __proto__, …) can't read truthy from the prototype chain and be dropped.
            var removed = [], removedSet = Object.create(null), i, k, boxes = keepBoxes();
            for (i = 0; i < boxes.length; i++) {
                if (!boxes[i].checked) { k = boxes[i].value; if (k) { removed.push(k); removedSet[k] = true; } }
            }
            var cells = [], vals = valueInputs(), inp;
            for (i = 0; i < vals.length; i++) {
                inp = vals[i];
                if (inp.value !== inp.defaultValue) {          // changed vs the server-rendered original
                    k = inp.getAttribute('data-key');
                    if (removedSet[k]) { continue; }            // key is being deleted → its cell edits are moot
                    // keep the element so the success branch can re-baseline THIS cell to the value it sent.
                    cells.push({ key: k, locale: inp.getAttribute('data-locale'), value: inp.value, el: inp });
                }
            }
            var newKeyEl = document.getElementById('msg-newkey');
            var newKey = newKeyEl ? newKeyEl.value.trim() : '';
            var added = [];
            if (newKey) {
                var nv = newValInputs();
                for (i = 0; i < nv.length; i++) {
                    if (nv[i].value && nv[i].value.length) {
                        added.push({ key: newKey, locale: nv[i].getAttribute('data-locale'), value: nv[i].value });
                    }
                }
            }
            return { cells: cells, added: added, removed: removed };
        }

        function count(e) { return e.cells.length + e.added.length + e.removed.length; }
        function structural(e) { return e.added.length > 0 || e.removed.length > 0; }

        function setStatus(text, kind) {
            if (!statusEl) { return; }
            statusEl.textContent = text || '';
            statusEl.className = 'ms-3 ' + (kind === 'ok' ? 'text-success' : (kind === 'err' ? 'text-danger' : 'text-secondary'));
        }
        function refresh() {
            var n = count(collect());
            saveBtn.textContent = n > 0 ? baseLabel + ' (' + n + ')' : baseLabel;
            saveBtn.disabled = saving || n === 0;
        }
        function onEdit() { if (!saving) { setStatus('', ''); } refresh(); }

        form.addEventListener('input', onEdit);
        form.addEventListener('change', onEdit);

        form.addEventListener('submit', function (ev) {
            ev.preventDefault();                                // JS path: replace the full-grid POST with a delta
            if (saving) { return; }
            var e = collect();
            // JSON body: plain cells only (drop the DOM element we kept for re-baselining).
            var payloadCells = e.cells.concat(e.added).map(function (c) {
                return { key: c.key, locale: c.locale, value: c.value };
            });
            if (!payloadCells.length && !e.removed.length) { return; }   // nothing changed
            saving = true; refresh(); setStatus('', '');
            fetch('/__admin/messages/apply', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrfEl ? csrfEl.value : '' },
                body: JSON.stringify({ cells: payloadCells, removedKeys: e.removed })
            }).then(function (r) {
                if (!r.ok) { throw new Error('HTTP ' + r.status); }
                return r.json().catch(function () { return {}; });
            }).then(function () {
                if (structural(e)) {                            // add/remove changes the grid shape → re-render
                    guardArmed = false;                         // disarm the unsaved-changes guard before reloading
                    window.location.reload();
                    return;
                }
                // Value-only save: re-baseline ONLY the cells we sent, to what the server now stores (a blank
                // value was dropped ⇒ empty). A cell edited DURING the round-trip has el.value != its sent value,
                // so it is left dirty (never silently marked clean) and its later save wins.
                for (var i = 0; i < e.cells.length; i++) {
                    var c = e.cells[i], stored = c.value.trim() === '' ? '' : c.value;
                    if (c.el.value === c.value) { c.el.value = stored; }   // untouched since send → adopt stored form
                    c.el.defaultValue = stored;
                }
                saving = false; refresh(); setStatus(savedMsg, 'ok');
            }).catch(function () {
                saving = false; refresh(); setStatus(failedMsg, 'err');
            });
        });

        // Warn before leaving with unsaved edits (disarmed just before a programmatic reload).
        window.addEventListener('beforeunload', function (ev) {
            if (guardArmed && !saving && count(collect()) > 0) { ev.preventDefault(); ev.returnValue = ''; return ''; }
        });

        refresh();
    })();
    
