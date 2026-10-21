/*
 * listing-tables.js — the house standard for data-listing tables.
 *
 * v2 behaviours (all opt-in, all persisted per table):
 *   1. Multi-column ACCUMULATED sort. Click a header to add it to the sort;
 *      the latest-clicked column has the highest priority and the biggest
 *      sort-order number. Re-clicking a column already in the sort flips its
 *      direction but keeps its priority.
 *   2. A per-column MENU (caret button + right-click + Shift+F10 / Menu key /
 *      Down arrow on a focused header — the ARIA menu-button keys):
 *      sort asc/desc, clear this column, clear all, change priority
 *      (higher / lower / make primary), reorder the column
 *      (left / right / start / end), WIDEN / NARROW the column (keyboard-operable
 *      resize), see the current sort-order number, and a toggle to show/hide the
 *      sort-order numbers beside the titles.
 *   3. Column REORDER by direct DRAG (mouse/pen: move past a small threshold;
 *      touch: long-press then drag) as well as via the menu; a moving column
 *      never crosses a pinned (Actions) anchor.
 *   4. User-RESIZABLE columns — pair-resize dividers (pointer events, so mouse,
 *      touch and pen all work) AND the menu's Widen/Narrow for keyboard users.
 *   5. Persisted per-table LAYOUT (column order, widths, sort, show-numbers)
 *      in localStorage, keyed by stable COLUMN IDENTITY (data-col-key, else a
 *      slug of the header text, else the original index) so the layout survives
 *      reordering and resizing.
 *
 * No framework dependency: the menu is built and toggled manually (there is no
 * `bootstrap` global in the Tabler bundle).
 *
 * Markup contract
 *   <table data-listing-table="<unique-key>">   <- opt in (sort + resize + reorder + persist)
 *   Legacy still works:  data-sortable  and/or  data-resizable-cols="<key>"
 *   Table opt-outs:  data-no-sort | data-no-resize | data-no-reorder | data-no-carets
 *   Header (<th>) opt-outs:  data-nosort (also pins the column - no reorder, no menu)
 *                            data-nomove (no reorder)  |  data-no-carets (hide this caret)
 *   Stable id (recommended):  data-col-key="<id>"
 *   Cell sort key when the display text missorts:  data-sort="<raw>"
 */
(function () {
    'use strict';

    var STORE_PREFIX = 'lt.';   // localStorage["lt.<table-key>"] = full layout state
    var ASC = '▲', DESC = '▼', CARET = '⋮';   // up-triangle / down-triangle / kebab

    // =====================================================================
    // Persistence - one identity-keyed object per table.
    //   { v:1, order:[colKey...], widths:{colKey:fraction}, sort:[{key,dir}...],
    //     showOrder: true|false|undefined }
    // sort[] is ordered LOW->HIGH priority: index 0 = number 1 (lowest),
    // last index = biggest number (highest priority).
    // =====================================================================
    function tableKey(table) {
        return table.dataset.listingTable || table.dataset.resizableCols || null;
    }
    function loadState(key) {
        if (!key) return {};
        try {
            var raw = localStorage.getItem(STORE_PREFIX + key);
            var s = raw ? JSON.parse(raw) : {};
            // Reject arrays (typeof [] === 'object'): named props would be
            // dropped on the next JSON.stringify, silently losing persistence.
            return (s && typeof s === 'object' && !Array.isArray(s)) ? s : {};
        } catch (e) { return {}; }
    }
    function saveState(table) {
        var key = tableKey(table);
        if (!key) return;   // no key => works in-session, just not persisted
        try { localStorage.setItem(STORE_PREFIX + key, JSON.stringify(table._ltState)); }
        catch (e) { /* quota / private mode - silently skip */ }
    }

    // =====================================================================
    // Column model - stable identity per column.
    // =====================================================================
    function slug(s) {
        return String(s || '').trim().toLowerCase()
            .replace(/\s+/g, '-').replace(/[^a-z0-9_-]/g, '').slice(0, 40);
    }
    function featureOn(table, optOut) { return !table.hasAttribute(optOut); }
    function buildColumns(table) {
        var optedIn = table.hasAttribute('data-listing-table') || table.hasAttribute('data-sortable');
        var sortOn = optedIn && featureOn(table, 'data-no-sort');
        var reorderOn = optedIn && featureOn(table, 'data-no-reorder');
        var headRow = table.tHead && table.tHead.rows[0];
        var ths = headRow ? Array.prototype.slice.call(headRow.cells) : [];
        var seen = {};
        return ths.map(function (th, i) {
            var key = th.dataset.colKey || slug(th.textContent) || ('col' + i);
            if (seen[key]) key = key + '-' + i;        // guarantee uniqueness
            seen[key] = true;
            var pinned = th.hasAttribute('data-nosort');
            return {
                th: th,
                key: key,
                sortable: sortOn && !pinned,
                movable: reorderOn && !pinned && !th.hasAttribute('data-nomove')
            };
        });
    }
    function colByKey(table, key) {
        for (var i = 0; i < table._ltCols.length; i++) {
            if (table._ltCols[i].key === key) return table._ltCols[i];
        }
        return null;
    }
    function colAt(table, cellIndex) {
        for (var i = 0; i < table._ltCols.length; i++) {
            if (table._ltCols[i].th.cellIndex === cellIndex) return table._ltCols[i];
        }
        return null;
    }

    // =====================================================================
    // Sorting.
    // =====================================================================
    function cellKey(row, idx) {
        var cell = row.cells[idx];
        if (!cell) return '';
        return (cell.dataset.sort != null ? cell.dataset.sort : cell.textContent).trim();
    }
    function cmpKeys(a, b) {
        var na = parseFloat(a), nb = parseFloat(b);
        if (!isNaN(na) && !isNaN(nb) && /^-?[\d.]+$/.test(a) && /^-?[\d.]+$/.test(b)) return na - nb;
        return a.localeCompare(b, undefined, { sensitivity: 'base' });
    }
    function findSort(state, key) {
        var arr = state.sort || [];
        for (var i = 0; i < arr.length; i++) if (arr[i].key === key) return i;
        return -1;
    }
    function applySort(table) {
        var state = table._ltState, sort = state.sort || [];
        var tbody = table.tBodies[0];
        if (tbody) {
            var rows = Array.prototype.slice.call(tbody.rows);
            rows.sort(function (a, b) {
                // Highest priority (end of array) first; original order as the
                // final, stable tiebreaker (restores server order when empty).
                for (var i = sort.length - 1; i >= 0; i--) {
                    var col = colByKey(table, sort[i].key);
                    if (!col) continue;
                    var c = cmpKeys(cellKey(a, col.th.cellIndex), cellKey(b, col.th.cellIndex));
                    if (c) return sort[i].dir === 'asc' ? c : -c;
                }
                return (a._ltRow || 0) - (b._ltRow || 0);
            });
            rows.forEach(function (r) { tbody.appendChild(r); });
        }
        decorateHeaders(table);
    }
    function showOrderEffective(state) {
        if (state.showOrder === true || state.showOrder === false) return state.showOrder;
        return (state.sort || []).length >= 2;          // default: show when >=2 keys
    }
    function decorateHeaders(table) {
        var state = table._ltState, show = showOrderEffective(state), n = (state.sort || []).length;
        table._ltCols.forEach(function (col) {
            var tools = col.th.querySelector('.lt-th-tools');
            if (!tools) return;
            var old = tools.querySelector('.lt-ind'); if (old) old.remove();
            var oldSr = tools.querySelector('.lt-sr'); if (oldSr) oldSr.remove();
            col.th.removeAttribute('aria-sort');
            var pos = findSort(state, col.key);
            if (pos < 0) return;
            var dir = state.sort[pos].dir;
            col.th.setAttribute('aria-sort', dir === 'asc' ? 'ascending' : 'descending');
            var span = document.createElement('span');
            span.className = 'lt-ind';
            span.setAttribute('aria-hidden', 'true');     // visual only; AT gets aria-sort + the SR span below
            span.textContent = dir === 'asc' ? ASC : DESC;
            if (show) {
                var num = document.createElement('span');
                num.className = 'lt-order';
                num.textContent = pos + 1;                // 1 = lowest ... biggest = primary
                span.appendChild(num);
            }
            tools.insertBefore(span, tools.firstChild);
            // Convey multi-sort PRIORITY to assistive tech (aria-sort alone gives
            // only direction). Folded into the header's accessible name.
            if (n > 1) {
                var sr = document.createElement('span');
                sr.className = 'lt-sr';
                sr.textContent = ' (' + L('order') + ' ' + (pos + 1) + ' ' + L('of') + ' ' + n + ')';
                tools.appendChild(sr);
            }
        });
    }
    // Left-click: NEW column -> push at highest priority; existing -> flip dir.
    function clickHeader(table, col) {
        var state = table._ltState;
        if (!state.sort) state.sort = [];
        var pos = findSort(state, col.key);
        if (pos < 0) state.sort.push({ key: col.key, dir: 'asc' });
        else state.sort[pos].dir = state.sort[pos].dir === 'asc' ? 'desc' : 'asc';
        applySort(table); saveState(table);
    }

    // =====================================================================
    // Reorder (menu actions + direct drag). Non-movable columns are fixed
    // anchors that a moving column never crosses.
    // =====================================================================
    function currentOrder(table) {
        return table._ltCols.slice()
            .sort(function (a, b) { return a.th.cellIndex - b.th.cellIndex; })
            .map(function (c) { return c.key; });
    }
    function reorderDom(table, order) {
        // Snapshot each column's ORIGINAL cell index BEFORE mutating — appending
        // cells shifts cellIndex, so we must resolve positions up front.
        var origIndex = {};
        table._ltCols.forEach(function (c) { origIndex[c.key] = c.th.cellIndex; });
        var rows = [table.tHead.rows[0]];
        if (table.tBodies[0]) rows = rows.concat(Array.prototype.slice.call(table.tBodies[0].rows));
        rows.forEach(function (row) {
            // Resolve this row's cells in the NEW order first, then re-append.
            var cells = order.map(function (key) { return row.cells[origIndex[key]]; });
            cells.forEach(function (cell) { if (cell) row.appendChild(cell); });
        });
    }
    function canMove(table, col, dir) {              // dir: -1 left, +1 right
        if (!col.movable) return false;
        var j = col.th.cellIndex + dir;
        var neighbour = colAt(table, j);
        return !!(neighbour && neighbour.movable);    // never cross a pinned column
    }
    function swap(table, col, dir) {
        var order = currentOrder(table);
        var i = col.th.cellIndex, j = i + dir;
        var t = order[i]; order[i] = order[j]; order[j] = t;
        reorderDom(table, order);
        table._ltState.order = currentOrder(table);
        refreshDividers(table); applyWidths(table); decorateHeaders(table); saveState(table);
    }
    function moveColumn(table, col, where) {
        if (where === 'left') { if (canMove(table, col, -1)) swap(table, col, -1); }
        else if (where === 'right') { if (canMove(table, col, +1)) swap(table, col, +1); }
        else if (where === 'start') { while (canMove(table, col, -1)) swap(table, col, -1); }
        else if (where === 'end') { while (canMove(table, col, +1)) swap(table, col, +1); }
    }
    // Step a column toward a target cell index, one pin-respecting swap at a time
    // (reuses swap, so a moving column never crosses a fixed anchor). Used by drag-drop.
    function moveToward(table, col, targetIndex) {
        var guard = table._ltCols.length + 1;      // hard stop against any pathological loop
        while (col.th.cellIndex < targetIndex && canMove(table, col, +1) && guard-- > 0) swap(table, col, +1);
        while (col.th.cellIndex > targetIndex && canMove(table, col, -1) && guard-- > 0) swap(table, col, -1);
    }
    // Direct drag-to-reorder. Mouse/pen arm on a small horizontal move; touch arms
    // on a long-press (so a vertical swipe still scrolls). The drop lands the column
    // at whichever movable column the pointer is over, via moveToward (pin-safe).
    function enableHeaderDrag(table, col) {
        var th = col.th, MOVE = 6, HOLD = 400;
        th.addEventListener('pointerdown', function (ev) {
            if (ev.button != null && ev.button > 0) return;                 // primary button / touch / pen only
            if (ev.target.closest('.lt-divider') || ev.target.closest('.lt-caret')) return;
            var startX = ev.clientX, touch = ev.pointerType === 'touch';
            var pid = ev.pointerId, armed = false, aborted = false, holdTimer = null;

            function marks(clear) {
                table._ltCols.forEach(function (c) { c.th.classList.remove('lt-drop-target'); });
                if (!clear && armed) {
                    var t = targetAt(lastX);
                    if (t && t !== col) t.th.classList.add('lt-drop-target');
                }
            }
            function targetAt(x) {
                var hit = null;
                table._ltCols.forEach(function (c) {
                    if (!c.movable) return;
                    var r = c.th.getBoundingClientRect();
                    if (x >= r.left && x <= r.right) hit = c;
                });
                return hit;
            }
            function begin() {
                if (aborted) return;
                armed = true;
                col._suppressClick = true;                                  // a drag must not also sort
                th.classList.add('lt-drag-src');
                document.body.style.cursor = 'grabbing';
                if (th.setPointerCapture) { try { th.setPointerCapture(pid); } catch (e) {} }
            }
            var lastX = startX;
            function move(e) {
                lastX = e.clientX;
                if (!armed) {
                    if (touch) { if (Math.abs(e.clientX - startX) > MOVE) { aborted = true; clearTimeout(holdTimer); } return; }
                    if (Math.abs(e.clientX - startX) > MOVE) begin(); else return;
                }
                e.preventDefault();                                         // once dragging, own the gesture
                marks(false);
            }
            function up(e) {
                clearTimeout(holdTimer);
                document.removeEventListener('pointermove', move);
                document.removeEventListener('pointerup', up);
                document.removeEventListener('pointercancel', up);
                if (th.releasePointerCapture) { try { th.releasePointerCapture(pid); } catch (ex) {} }
                document.body.style.cursor = '';
                th.classList.remove('lt-drag-src');
                if (armed) {
                    var t = targetAt(e.clientX);
                    marks(true);
                    if (t && t !== col && t.movable) moveToward(table, col, t.th.cellIndex);
                    setTimeout(function () { col._suppressClick = false; }, 50);
                }
            }
            if (touch) holdTimer = setTimeout(begin, HOLD);
            document.addEventListener('pointermove', move);
            document.addEventListener('pointerup', up);
            document.addEventListener('pointercancel', up);
        });
    }

    // =====================================================================
    // Resize - pair-resize, identity-keyed widths, pointer events.
    // =====================================================================
    function applyWidths(table) {
        var w = table._ltState.widths;
        if (!w) return;
        var total = 0;
        table._ltCols.forEach(function (c) { var f = w[c.key]; if (typeof f === 'number' && f > 0) total += f; });
        if (total <= 0) return;
        table._ltCols.forEach(function (c) {
            var f = w[c.key]; if (typeof f === 'number' && f > 0) c.th.style.width = (f / total * 100) + '%';
        });
    }
    function seedWidths(table) {
        var state = table._ltState, cols = table._ltCols;
        if (state.widths && cols.every(function (c) { return typeof state.widths[c.key] === 'number'; })) return;
        var px = cols.map(function (c) { return c.th.getBoundingClientRect().width; });
        var total = px.reduce(function (a, b) { return a + b; }, 0) || cols.length;
        state.widths = {};
        cols.forEach(function (c, i) { state.widths[c.key] = px[i] / total; });
    }
    function refreshDividers(table) {
        if (!table._ltResize) return;
        table.querySelectorAll('.lt-divider').forEach(function (d) { d.remove(); });
        var ordered = table._ltCols.slice().sort(function (a, b) { return a.th.cellIndex - b.th.cellIndex; });
        ordered.forEach(function (c, i) {
            c.th.classList.add('lt-th-resizable');
            if (i === ordered.length - 1) return;     // no divider after the last column
            var div = document.createElement('span');
            div.className = 'lt-divider';
            div.setAttribute('aria-hidden', 'true');
            c.th.appendChild(div);
            div.addEventListener('pointerdown', function (ev) { startDrag(ev, table, c); });
        });
    }
    function startDrag(ev, table, leftCol) {
        ev.preventDefault(); ev.stopPropagation();
        var rightCol = colAt(table, leftCol.th.cellIndex + 1);
        if (!rightCol) return;
        var startX = ev.clientX;
        var totalW = table.getBoundingClientRect().width;
        var leftStart = leftCol.th.getBoundingClientRect().width;
        var rightStart = rightCol.th.getBoundingClientRect().width;
        var pairTotal = leftStart + rightStart, min = 24;
        var w = table._ltState.widths;
        var target = ev.target, pid = ev.pointerId;
        if (target.setPointerCapture) { try { target.setPointerCapture(pid); } catch (e) {} }
        function move(e) {
            var dx = e.clientX - startX;
            var leftPx = Math.max(min, Math.min(pairTotal - min, leftStart + dx));
            w[leftCol.key] = leftPx / totalW;
            w[rightCol.key] = (pairTotal - leftPx) / totalW;
            applyWidths(table);
        }
        // Finalize on pointerup AND pointercancel — touch gesture takeover
        // (scroll, multi-touch, app switch) fires cancel, not up; without this
        // the document listeners leak and the cursor stays stuck.
        function up() {
            document.removeEventListener('pointermove', move);
            document.removeEventListener('pointerup', up);
            document.removeEventListener('pointercancel', up);
            if (target.releasePointerCapture) { try { target.releasePointerCapture(pid); } catch (e) {} }
            document.body.style.cursor = '';
            saveState(table);
        }
        document.addEventListener('pointermove', move);
        document.addEventListener('pointerup', up);
        document.addEventListener('pointercancel', up);
        document.body.style.cursor = 'col-resize';
    }
    // Keyboard-operable resize (the menu's Widen/Narrow) — closes the "resize is
    // pointer-only" gap. Trades a fixed width step with the RIGHT neighbour (or the
    // LEFT one for the last column), preserving the pair-total invariant that
    // pointer pair-resize keeps, so the table width never changes.
    function resizeNeighbour(table, col) {
        return colAt(table, col.th.cellIndex + 1) || colAt(table, col.th.cellIndex - 1);
    }
    function resizeStep(table, col, dir) {           // dir: +1 widen, -1 narrow
        if (!table._ltResize) return;
        var partner = resizeNeighbour(table, col);
        if (!partner) return;
        seedWidths(table);
        var w = table._ltState.widths, step = 0.03, min = 0.04;
        var a = w[col.key], b = w[partner.key];
        if (typeof a !== 'number' || typeof b !== 'number') return;
        var na = a + dir * step, nb = b - dir * step;
        if (na < min || nb < min) return;            // clamp: neither side collapses
        w[col.key] = na; w[partner.key] = nb;
        applyWidths(table); saveState(table);
    }

    // =====================================================================
    // The per-column menu (singleton popup, manually toggled).
    // =====================================================================
    var menuEl = null;
    function menuItems() {
        return Array.prototype.slice.call(menuEl.querySelectorAll('.lt-menu-item:not([disabled])'));
    }
    function focusItem(delta, abs) {                 // roving focus within the menu
        var items = menuItems();
        if (!items.length) return;
        var i = abs === 'first' ? 0
              : abs === 'last' ? items.length - 1
              : (items.indexOf(document.activeElement) + delta + items.length) % items.length;
        items[i].focus();
    }
    function ensureMenu() {
        if (menuEl) return menuEl;
        menuEl = document.createElement('div');
        menuEl.className = 'lt-menu';
        menuEl.setAttribute('role', 'menu');
        menuEl.hidden = true;
        document.body.appendChild(menuEl);
        document.addEventListener('pointerdown', function (e) {
            if (menuEl.hidden) return;
            if (!menuEl.contains(e.target) && !(e.target.closest && e.target.closest('.lt-caret'))) closeMenu();
        });
        document.addEventListener('keydown', function (e) {
            if (menuEl.hidden) return;
            // Arrows/Home/End rove inside the open menu. Tab (and Shift+Tab) CLOSE it, as the ARIA menu pattern
            // says: focus returns to the trigger and the browser's own Tab action then moves on from there, so
            // the menu is never a keyboard trap.
            switch (e.key) {
                case 'Escape': closeMenu(true); break;
                case 'ArrowDown': e.preventDefault(); focusItem(1); break;
                case 'ArrowUp': e.preventDefault(); focusItem(-1); break;
                case 'Tab': closeMenu(true); break;
                case 'Home': e.preventDefault(); focusItem(0, 'first'); break;
                case 'End': e.preventDefault(); focusItem(0, 'last'); break;
            }
        });
        // A fixed-position menu floats away from its column on scroll — close it.
        window.addEventListener('scroll', function () { if (!menuEl.hidden) closeMenu(); }, true);
        return menuEl;
    }
    function closeMenu(restoreFocus) {
        if (!menuEl || menuEl.hidden) return;
        var trigger = menuEl._trigger;
        menuEl.hidden = true; menuEl.innerHTML = ''; menuEl._trigger = null;
        if (trigger) trigger.setAttribute('aria-expanded', 'false');
        if (restoreFocus && trigger && trigger.focus) trigger.focus();
    }
    function mkItem(label, opts) {
        opts = opts || {};
        var b = document.createElement('button');
        b.type = 'button';
        b.className = 'lt-menu-item' + (opts.active ? ' lt-active' : '');
        b.setAttribute('role', 'menuitem');
        b.setAttribute('tabindex', '-1');           // roving: focus moved programmatically
        if (opts.disabled) b.disabled = true;
        b.innerHTML = '<span class="lt-check">' + (opts.active ? '✓' : '') + '</span><span class="lt-label"></span>';
        b.querySelector('.lt-label').textContent = label;
        if (opts.onClick && !opts.disabled) b.addEventListener('click', function () {
            opts.onClick(); if (!opts.keepOpen) closeMenu(true);
        });
        menuEl.appendChild(b);
        return b;
    }
    function mkSep() { var d = document.createElement('div'); d.className = 'lt-menu-sep'; menuEl.appendChild(d); }
    function mkHead(text) { var h = document.createElement('div'); h.className = 'lt-menu-head'; h.textContent = text; menuEl.appendChild(h); }

    function openMenu(table, col, trigger, x, y) {
        ensureMenu(); menuEl.innerHTML = '';
        var state = table._ltState, pos = findSort(state, col.key);
        var n = (state.sort || []).length;
        var title = (col.th.querySelector('.lt-th-label') || col.th).textContent.trim();
        mkHead(pos < 0 ? title + ' — ' + L('notSorted')
                       : title + ' — ' + L('order') + ' ' + (pos + 1) + '/' + n);
        mkItem(L('asc'), { active: pos >= 0 && state.sort[pos].dir === 'asc',
            onClick: function () { setDir(table, col, 'asc'); } });
        mkItem(L('desc'), { active: pos >= 0 && state.sort[pos].dir === 'desc',
            onClick: function () { setDir(table, col, 'desc'); } });
        mkItem(L('clearThis'), { disabled: pos < 0, onClick: function () { clearOne(table, col); } });
        mkItem(L('clearAll'), { disabled: n === 0, onClick: function () { clearAll(table); } });
        mkSep();
        mkItem(L('raise'), { disabled: pos < 0 || pos === n - 1, onClick: function () { reprioritise(table, col, +1); } });
        mkItem(L('lower'), { disabled: pos <= 0, onClick: function () { reprioritise(table, col, -1); } });
        mkItem(L('primary'), { disabled: pos < 0 || pos === n - 1, onClick: function () { makePrimary(table, col); } });
        if (col.movable) {
            mkSep();
            mkItem(L('moveLeft'), { disabled: !canMove(table, col, -1), onClick: function () { moveColumn(table, col, 'left'); } });
            mkItem(L('moveRight'), { disabled: !canMove(table, col, +1), onClick: function () { moveColumn(table, col, 'right'); } });
            mkItem(L('moveStart'), { disabled: !canMove(table, col, -1), onClick: function () { moveColumn(table, col, 'start'); } });
            mkItem(L('moveEnd'), { disabled: !canMove(table, col, +1), onClick: function () { moveColumn(table, col, 'end'); } });
        }
        // Keyboard-operable width control — kept open so repeated presses widen/narrow progressively.
        if (table._ltResize && resizeNeighbour(table, col)) {
            mkSep();
            mkItem(L('widen'), { keepOpen: true, onClick: function () { resizeStep(table, col, +1); } });
            mkItem(L('narrow'), { keepOpen: true, onClick: function () { resizeStep(table, col, -1); } });
        }
        mkSep();
        mkItem(L('showNumbers'), { active: showOrderEffective(state), keepOpen: true,
            onClick: function () { toggleNumbers(table); } });

        // position within the viewport
        menuEl.hidden = false; menuEl._trigger = trigger;
        if (trigger) trigger.setAttribute('aria-expanded', 'true');
        var mw = menuEl.offsetWidth, mh = menuEl.offsetHeight;
        menuEl.style.left = Math.max(8, Math.min(x, window.innerWidth - mw - 8)) + 'px';
        menuEl.style.top = Math.max(8, Math.min(y, window.innerHeight - mh - 8)) + 'px';
        var first = menuEl.querySelector('.lt-menu-item:not([disabled])');
        if (first) first.focus();
    }
    // menu actions
    function setDir(table, col, dir) {
        var s = table._ltState; if (!s.sort) s.sort = [];
        var pos = findSort(s, col.key);
        if (pos < 0) s.sort.push({ key: col.key, dir: dir }); else s.sort[pos].dir = dir;
        applySort(table); saveState(table);
    }
    function clearOne(table, col) {
        var s = table._ltState, pos = findSort(s, col.key);
        if (pos >= 0) s.sort.splice(pos, 1);
        applySort(table); saveState(table);
    }
    function clearAll(table) { table._ltState.sort = []; applySort(table); saveState(table); }
    function reprioritise(table, col, dir) {       // +1 raise (toward end), -1 lower
        var s = table._ltState.sort, i = findSort(table._ltState, col.key), j = i + dir;
        if (i < 0 || j < 0 || j >= s.length) return;
        var t = s[i]; s[i] = s[j]; s[j] = t;
        applySort(table); saveState(table);
    }
    function makePrimary(table, col) {
        var s = table._ltState.sort, i = findSort(table._ltState, col.key);
        if (i < 0) return;
        s.push(s.splice(i, 1)[0]);
        applySort(table); saveState(table);
    }
    function toggleNumbers(table) {
        table._ltState.showOrder = !showOrderEffective(table._ltState);
        decorateHeaders(table); saveState(table);
        var item = menuEl && menuEl.querySelector('.lt-menu-item:last-child');
        if (item) {
            var on = showOrderEffective(table._ltState);
            item.classList.toggle('lt-active', on);
            item.querySelector('.lt-check').textContent = on ? '✓' : '';
        }
    }

    // i18n: body data-lt-* attributes override the English defaults.
    var STRINGS = {
        asc: 'Sort ascending', desc: 'Sort descending',
        clearThis: 'Clear sort on this column', clearAll: 'Clear all sorting',
        raise: 'Higher priority', lower: 'Lower priority', primary: 'Make primary',
        moveLeft: 'Move left', moveRight: 'Move right',
        moveStart: 'Move to start', moveEnd: 'Move to end',
        widen: 'Widen column', narrow: 'Narrow column',
        showNumbers: 'Show sort-order numbers',
        order: 'sort order', of: 'of', notSorted: 'not sorted', menu: 'Column options',
        keyHint: 'Enter sorts by this column. Down arrow or Shift+F10 opens the column options.'
    };
    function L(k) {
        var attr = 'lt' + k.charAt(0).toUpperCase() + k.slice(1);
        return (document.body && document.body.dataset[attr]) || STRINGS[k];
    }
    // One shared, visually-hidden description for every interactive header (created on first use).
    var keyHintEl = null;
    function keyHint() {
        if (keyHintEl) return keyHintEl;
        keyHintEl = document.createElement('span');
        keyHintEl.id = 'lt-key-hint';
        keyHintEl.className = 'visually-hidden';
        keyHintEl.textContent = L('keyHint');
        document.body.appendChild(keyHintEl);
        return keyHintEl;
    }

    // =====================================================================
    // Header wiring + init.
    // =====================================================================
    function wireHeader(table, col) {
        var th = col.th;
        // restructure to: <th class="lt-th"><span.lt-th-inner (flex)>
        //   <span.lt-th-label>…original…</span><span.lt-th-tools/></span></th>
        // The flex wrapper is an INNER element — never the <th> itself: a flex
        // <th> stops being a table cell and the whole table layout collapses.
        if (!th.querySelector('.lt-th-inner')) {
            var label = document.createElement('span'); label.className = 'lt-th-label';
            while (th.firstChild) label.appendChild(th.firstChild);
            var inner = document.createElement('span'); inner.className = 'lt-th-inner';
            inner.appendChild(label);
            var tools = document.createElement('span'); tools.className = 'lt-th-tools';
            inner.appendChild(tools);
            th.appendChild(inner);
            th.classList.add('lt-th');
        }
        if (!col.sortable && !col.movable) return;     // pinned column: no interaction
        var tools = th.querySelector('.lt-th-tools');

        // The header is a menu trigger whenever it has ANY menu action (sort or
        // reorder) — focusability + the keyboard menu key must NOT depend on
        // sortability, or movable-only / caret-hidden columns lose keyboard access.
        th.setAttribute('tabindex', '0');
        th.setAttribute('aria-haspopup', 'menu');
        th.setAttribute('aria-expanded', 'false');
        if (col.sortable) {
            th.classList.add('lt-sortable');
            th.addEventListener('click', function (ev) {
                if (col._suppressClick) return;        // touch long-press already opened the menu
                if (ev.target.closest('.lt-divider') || ev.target.closest('.lt-caret')) return;
                clickHeader(table, col);
            });
        }
        // The header is the keyboard route to the column menu (the caret stays out of the tab order so a table
        // is not two tab stops per column): Shift+F10 / the Menu key, or Down arrow (the ARIA menu-button key).
        // A visually-hidden hint, referenced by aria-describedby, tells screen-reader users so.
        th.setAttribute('aria-describedby', keyHint().id);
        th.addEventListener('keydown', function (ev) {
            if (ev.target !== th) return;
            if (col.sortable && (ev.key === 'Enter' || ev.key === ' ')) { ev.preventDefault(); clickHeader(table, col); }
            else if ((ev.key === 'F10' && ev.shiftKey) || ev.key === 'ContextMenu' || ev.key === 'ArrowDown') {
                // stopPropagation: the document-level menu handler must not ALSO treat this Down arrow as "next item".
                ev.preventDefault(); ev.stopPropagation();
                var r = th.getBoundingClientRect(); openMenu(table, col, th, r.left, r.bottom);
            }
        });
        // right-click menu (sortable AND movable headers). On touch a long-press
        // fires contextmenu but may also fire a trailing click — suppress that so
        // the column isn't sorted at the same instant the menu opens.
        th.addEventListener('contextmenu', function (ev) {
            ev.preventDefault();
            col._suppressClick = true;
            setTimeout(function () { col._suppressClick = false; }, 600);
            openMenu(table, col, th, ev.clientX, ev.clientY);
        });
        // Drag-to-reorder (mouse/pen: move past a small threshold; touch: long-press
        // then drag). Menu-reorder stays the keyboard/click path; this is the direct
        // one. It reuses moveToward → swap, so pinned columns are never crossed.
        if (col.movable) enableHeaderDrag(table, col);
        // caret button
        var hideCaret = table.hasAttribute('data-no-carets') || th.hasAttribute('data-no-carets');
        if (!hideCaret) {
            var caret = document.createElement('button');
            caret.type = 'button';
            caret.className = 'lt-caret';
            caret.setAttribute('aria-label', L('menu'));
            caret.setAttribute('title', L('menu') + ' (Shift+F10)');
            caret.setAttribute('aria-haspopup', 'menu');
            caret.setAttribute('aria-expanded', 'false');
            caret.setAttribute('tabindex', '-1');
            caret.innerHTML = '<span aria-hidden="true">' + CARET + '</span>';
            caret.addEventListener('click', function (ev) {
                ev.preventDefault(); ev.stopPropagation();
                if (menuEl && !menuEl.hidden && menuEl._trigger === caret) { closeMenu(); return; }
                var r = caret.getBoundingClientRect(); openMenu(table, col, caret, r.left, r.bottom);
            });
            tools.appendChild(caret);
        }
    }

    // Stamp the CURRENT tbody rows with their server order — the stable sort
    // tiebreaker and what "clear all sorting" restores. Re-run after htmx swaps.
    function stampRows(table) {
        if (table.tBodies[0]) Array.prototype.slice.call(table.tBodies[0].rows)
            .forEach(function (r, i) { r._ltRow = i; });
    }
    // Reconcile persisted state against the LIVE column set: drop sort/widths/
    // order entries for columns that no longer exist (schema change, edited
    // header). Without this, stale sort entries inflate the priority numbers
    // (a lone sorted column shows "2/2") and dead keys accumulate in storage.
    function reconcile(table) {
        var s = table._ltState, changed = false;
        var pruned = (s.sort || []).filter(function (e) { return colByKey(table, e.key); });
        if (pruned.length !== (s.sort || []).length) { s.sort = pruned; changed = true; }
        if (s.widths) {
            var w = {}, kept = 0;
            table._ltCols.forEach(function (c) {
                if (typeof s.widths[c.key] === 'number') { w[c.key] = s.widths[c.key]; kept++; }
            });
            if (kept !== Object.keys(s.widths).length) { s.widths = kept ? w : undefined; changed = true; }
        }
        if (s.order && !(Array.isArray(s.order) && s.order.length === table._ltCols.length
                && s.order.every(function (k) { return colByKey(table, k); }))) {
            delete s.order; changed = true;
        }
        if (changed) saveState(table);
    }
    var keyOwners = {};   // dev-time duplicate-key detection (per page)

    function initTable(table) {
        if (table.dataset.ltInit) return;
        table.dataset.ltInit = '1';
        table._ltCols = buildColumns(table);
        if (!table._ltCols.length) return;
        var k = tableKey(table);
        if (k) {
            if (keyOwners[k] && keyOwners[k] !== table && typeof console !== 'undefined' && console.warn) {
                console.warn('listing-tables: duplicate table key "' + k + '" — these tables will clobber each other in localStorage; keys must be unique per page.');
            }
            keyOwners[k] = table;
        }
        table._ltState = loadState(k);
        if (!table._ltState.sort) table._ltState.sort = [];
        reconcile(table);                 // self-heal stale persisted state

        stampRows(table);                 // original server order (tiebreaker)

        // resize? needs >=2 columns and not opted out
        table._ltResize = (table.hasAttribute('data-listing-table') || table.hasAttribute('data-resizable-cols'))
                && featureOn(table, 'data-no-resize') && table._ltCols.length >= 2;

        table._ltCols.forEach(function (c) { wireHeader(table, c); });

        // apply stored ORDER (only if it matches the current column set)
        var order = table._ltState.order;
        if (Array.isArray(order) && order.length === table._ltCols.length
                && order.every(function (k) { return colByKey(table, k); })) {
            reorderDom(table, order);
        }

        if (table._ltResize) {
            table.style.tableLayout = 'fixed';
            table.style.width = '100%';
            seedWidths(table);
            applyWidths(table);
            refreshDividers(table);
        }
        applySort(table);   // also decorates headers (indicators + numbers)
    }

    // Seed AFTER fonts are ready (FontAwesome action buttons have zero width
    // until then; measuring earlier under-sizes the actions column).
    function whenFontsReady(fn) {
        if (document.fonts && document.fonts.ready) document.fonts.ready.then(fn);
        else if (document.readyState === 'complete') fn();
        else window.addEventListener('load', fn);
    }
    var SEL = 'table[data-listing-table], table[data-sortable], table[data-resizable-cols]';
    function scan(root) {
        (root || document).querySelectorAll(SEL).forEach(initTable);
    }
    // An already-initialized table whose tbody was swapped (the common htmx
    // pattern) keeps its dataset.ltInit, so initTable would skip it and the
    // swapped-in rows would render unstamped + unsorted. Re-stamp + re-apply.
    function refreshBody(table) { stampRows(table); applySort(table); }

    whenFontsReady(function () { scan(document); });
    document.addEventListener('htmx:afterSwap', function (ev) {
        closeMenu();
        var host = ev.target;
        var tables = host.querySelectorAll ? Array.prototype.slice.call(host.querySelectorAll(SEL)) : [];
        // ev.target may itself be (inside) a table whose body was replaced
        var enclosing = host.closest ? host.closest(SEL) : null;
        if (enclosing && tables.indexOf(enclosing) < 0) tables.push(enclosing);
        tables.forEach(function (t) { if (t.dataset.ltInit) refreshBody(t); else initTable(t); });
    });
    window.addEventListener('resize', function () { closeMenu(); });

    window.ListingTables = { scan: scan };
})();
