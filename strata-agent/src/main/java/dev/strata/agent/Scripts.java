package dev.strata.agent;

/** Browser-side scripts. Redaction happens in the browser so unredacted data never reaches disk (CLAUDE.md §4.4). */
final class Scripts {
    private Scripts() {
    }

    /**
     * Async: resolves once a MutationObserver has seen no change for 500 ms (hard cap 5000 ms), then reports the
     * URL, title and container ids used for page-change detection (§4.2, §4.3). The observer stays installed on
     * the page, so on a document it already watches the quiet window counts from the last mutation it saw; on a
     * fresh document it counts from installation.
     */
    static final String SETTLE = """
            var done = arguments[arguments.length - 1];
            var QUIET = 500, CAP = 5000, start = Date.now();
            var state = window.__strataSettle;
            if (!state) {
              state = window.__strataSettle = { last: Date.now() };
              try {
                new MutationObserver(function () { state.last = Date.now(); })
                  .observe(document, { childList: true, subtree: true, attributes: true, characterData: true });
              } catch (e) { state.last = Date.now(); }
            }
            function containerIds() {
              var out = [];
              var els = document.querySelectorAll('form, nav, table, dialog, [role=dialog], section, main > *');
              for (var i = 0; i < els.length; i++) {
                var id = els[i].getAttribute('id');
                var testId = els[i].getAttribute('data-testid');
                if (id) out.push(id);
                if (testId) out.push(testId);
              }
              return out;
            }
            (function poll() {
              var now = Date.now();
              if (now - state.last >= QUIET || now - start >= CAP) {
                done({ settleMs: now - start, url: location.href, title: document.title, ids: containerIds() });
              } else {
                setTimeout(poll, 25);
              }
            })();
            """;

    /** Sync: returns {@code {html, skipped}} for a redacted clone of {@code document.documentElement}. */
    static final String REDACT = """
            var BLANK = 'tbody, [role=grid], [role=row], [role=gridcell], td, output';
            var KEEP = 'button, a, label, th, h1, h2, h3, h4, h5, h6, legend, option, [role=tab], [role=menuitem]';
            var MARK = '\\u00b7\\u00b7\\u00b7', MAX = 60;
            var skipped = [];
            var frames = document.querySelectorAll('iframe, frame');
            for (var f = 0; f < frames.length; f++) {
              skipped.push('iframe' + (frames[f].id ? '#' + frames[f].id : '[' + f + ']'));
            }
            var all = document.querySelectorAll('*');
            for (var a = 0; a < all.length; a++) {
              if (all[a].shadowRoot) {
                skipped.push('shadow:' + all[a].tagName.toLowerCase() + (all[a].id ? '#' + all[a].id : ''));
              }
            }
            var root = document.documentElement.cloneNode(true);
            var drop = root.querySelectorAll('script, style, noscript');
            for (var d = 0; d < drop.length; d++) drop[d].remove();
            var svgs = root.querySelectorAll('svg');
            for (var s = 0; s < svgs.length; s++) { while (svgs[s].firstChild) svgs[s].removeChild(svgs[s].firstChild); }
            var areas = root.querySelectorAll('textarea');
            for (var t = 0; t < areas.length; t++) areas[t].textContent = '';
            var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_COMMENT);
            var comments = [], texts = [];
            while (walker.nextNode()) {
              (walker.currentNode.nodeType === Node.COMMENT_NODE ? comments : texts).push(walker.currentNode);
            }
            for (var c = 0; c < comments.length; c++) comments[c].remove();
            for (var x = 0; x < texts.length; x++) {
              var node = texts[x], value = node.nodeValue, parent = node.parentElement;
              if (!parent || !value.trim()) continue;
              if (parent.closest(BLANK) || !parent.closest(KEEP)) {
                node.nodeValue = MARK;
              } else if (value.trim().length > MAX) {
                node.nodeValue = value.trim().substring(0, MAX);
              }
            }
            var elements = [root].concat(Array.prototype.slice.call(root.querySelectorAll('*')));
            for (var e = 0; e < elements.length; e++) {
              var names = elements[e].getAttributeNames();
              for (var n = 0; n < names.length; n++) {
                var name = names[n].toLowerCase();
                if (name === 'value' || name === 'style' || name.indexOf('on') === 0) elements[e].removeAttribute(names[n]);
              }
            }
            return { html: root.outerHTML, skipped: skipped };
            """;
}
