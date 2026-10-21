/* Runway Search plugin — highlights, on the page a search result opened, the words the search matched there.
   Served at /__plugin/search/static/highlight.js and loaded only by highlight-loader.js (which every page of a site
   with highlighting on carries), and only when the address carries the marker #rw-hl=<words> that a result link
   adds (search 2.1.0+).

   What it does:
   - reads the words from the marker (percent-encoded, separated by spaces) on load and on every hashchange;
   - highlights each whole-word occurrence, ignoring case, with the CSS Custom Highlight API: no element is added
     or changed, so a site script holding references to the page's nodes is unaffected;
   - looks inside the page's content slot (<main id="content">, else <main>, else <body> without its <header>,
     <nav>, <footer> and <aside>), skipping scripts, styles, form fields and the search box's own results; at
     most 200 occurrences;
   - scrolls to the first occurrence a visitor can see (a match inside a closed tab or <details> stays highlighted
     and shows when it opens);
   - removes the marker from the address with history.replaceState, so a reload or a bookmark gets the plain URL.

   The style is the ::highlight(rw-search) rule in highlight.css, added before the site's own stylesheets so a
   site rule for ::highlight(rw-search) wins. A browser without the API (Firefox before 140, Safari before 17.2)
   highlights nothing; the marker is still removed. */
(function () {
    'use strict';

    var KEY = 'rw-hl=';
    var NAME = 'rw-search';
    var MAX_RANGES = 200;
    var MAX_WORDS = 20;
    var SKIP = { SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, TEMPLATE: 1, TEXTAREA: 1, INPUT: 1, SELECT: 1, OPTION: 1, svg: 1 };

    function wordsFromHash() {
        var hash = location.hash;
        var at = hash.indexOf(KEY);
        if (at < 0) {
            return null;
        }
        var raw = hash.substring(at + KEY.length);
        var amp = raw.indexOf('&');
        if (amp >= 0) {
            raw = raw.substring(0, amp);
        }
        var text;
        try {
            text = decodeURIComponent(raw);
        } catch (e) {
            return [];
        }
        var seen = {};
        var words = [];
        text.split(/\s+/).forEach(function (w) {
            var word = w.trim().toLowerCase();
            if (word && word.length <= 64 && !seen[word] && words.length < MAX_WORDS) {
                seen[word] = 1;
                words.push(word);
            }
        });
        return words;
    }

    /** Drops the marker from the address, keeping any other fragment, without adding a history entry. */
    function clearMarker() {
        var hash = location.hash;
        var at = hash.indexOf(KEY);
        if (at < 0 || !history.replaceState) {
            return;
        }
        var rest = hash.substring(at + KEY.length);
        var amp = rest.indexOf('&');
        var before = hash.substring(1, at).replace(/&$/, '');
        var after = amp >= 0 ? rest.substring(amp + 1) : '';
        var kept = [before, after].filter(Boolean).join('&');
        try {
            history.replaceState(history.state, '', location.pathname + location.search + (kept ? '#' + kept : ''));
        } catch (e) {
            // A document that cannot rewrite its address (about:srcdoc, blob:) still gets the highlight.
        }
    }

    function escapeRegExp(s) {
        return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    }

    /**
     * One pattern for all words, longest first, matching only whole words (letters, digits and marks). The server
     * lowercases with Java's rules, which turn the Turkish capital I with a dot into "i" plus a combining dot; the
     * browser's case-insensitive match never folds that capital to anything, so every "i" also accepts it.
     */
    function pattern(words) {
        var alternatives = words.slice().sort(function (a, b) { return b.length - a.length; }).map(function (w) {
            return escapeRegExp(w.replace(/i\u0307/g, 'i')).replace(/i/g, '[i\u0130]');
        });
        return new RegExp('(?<![\\p{L}\\p{N}\\p{M}])(?:' + alternatives.join('|') + ')(?![\\p{L}\\p{N}\\p{M}])', 'giu');
    }

    function root() {
        return document.getElementById('content') || document.querySelector('main') || document.body;
    }

    /** Page chrome, skipped when the page has no content slot and the whole <body> is searched. */
    var CHROME = { HEADER: 1, NAV: 1, FOOTER: 1, ASIDE: 1 };

    function rejectNode(node, wholeBody) {
        for (var el = node.parentElement; el; el = el.parentElement) {
            if (SKIP[el.tagName] || SKIP[el.nodeName] || (wholeBody && CHROME[el.tagName]) || el.isContentEditable
                    || (el.classList && el.classList.contains('rw-search-hits'))) {
                return true;
            }
        }
        return false;
    }

    function ranges(words) {
        var re = pattern(words);
        var found = [];
        var start = root();
        var wholeBody = start === document.body;
        var walker = document.createTreeWalker(start, NodeFilter.SHOW_TEXT, {
            acceptNode: function (node) {
                return node.nodeValue.trim() && !rejectNode(node, wholeBody)
                        ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT;
            }
        });
        var node;
        while (found.length < MAX_RANGES && (node = walker.nextNode())) {
            re.lastIndex = 0;
            var m;
            while (found.length < MAX_RANGES && (m = re.exec(node.nodeValue))) {
                var range = document.createRange();
                range.setStart(node, m.index);
                range.setEnd(node, m.index + m[0].length);
                found.push(range);
            }
        }
        return found;
    }

    function visible(range) {
        var el = range.startContainer.parentElement;
        if (!el) {
            return false;
        }
        if (el.checkVisibility) {
            return el.checkVisibility({ visibilityProperty: true, opacityProperty: false });
        }
        return range.getClientRects().length > 0;
    }

    function addStyle() {
        if (document.getElementById('rw-search-highlight-css')) {
            return;
        }
        var link = document.createElement('link');
        link.id = 'rw-search-highlight-css';
        link.rel = 'stylesheet';
        link.href = '/__plugin/search/static/highlight.css?v=2.3.1';
        // First in <head>, so any ::highlight(rw-search) rule of the site comes later and wins.
        document.head.insertBefore(link, document.head.firstChild);
    }

    function run() {
        var words = wordsFromHash();
        if (words === null) {
            return;
        }
        clearMarker();
        if (!window.CSS || !CSS.highlights || typeof Highlight === 'undefined' || !words.length) {
            return;
        }
        addStyle();
        var found = ranges(words);
        CSS.highlights.delete(NAME);
        if (!found.length) {
            return;
        }
        CSS.highlights.set(NAME, new Highlight(...found));
        for (var i = 0; i < found.length; i++) {
            if (visible(found[i])) {
                found[i].startContainer.parentElement.scrollIntoView({ block: 'center' });
                break;
            }
        }
    }

    function start() {
        run();
        addEventListener('hashchange', run);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', start);
    } else {
        start();
    }
})();
