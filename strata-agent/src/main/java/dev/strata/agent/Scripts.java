package dev.strata.agent;

/**
 * Browser-side scripts. Redaction happens in the browser so unredacted data never reaches disk (CLAUDE.md §4.4).
 * Every script works on the top-level document when it is reachable, so a test that has switched into a
 * same-origin frame still captures the whole page.
 */
final class Scripts {
    private Scripts() {
    }

    /** Shared prelude: {@code D} is the top document (or the current one when the top is cross-origin). */
    private static final String PRELUDE = """
            var W = window, D = document, crossOrigin = false;
            try { if (window.top !== window && window.top.document) { W = window.top; D = W.document; } }
            catch (e) { crossOrigin = true; }
            function strataState() {
              var state = W.__strataSettle;
              if (!state) {
                state = W.__strataSettle = { last: Date.now(), fresh: true };
                try {
                  new W.MutationObserver(function () { state.last = Date.now(); })
                    .observe(D, { childList: true, subtree: true, attributes: true, characterData: true });
                } catch (e) { state.last = Date.now(); }
                // A navigation this document started but has not finished yet (the click returned before it).
                function leaving() { state.leavingAt = Date.now(); }
                W.addEventListener('beforeunload', leaving);
                W.addEventListener('pagehide', leaving);
                W.addEventListener('submit', function (e) { if (!e.defaultPrevented) leaving(); });
                W.addEventListener('click', function (e) {
                  var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
                  var href = a ? a.getAttribute('href') || '' : '';
                  if (a && !e.defaultPrevented && href.charAt(0) !== '#' && href.indexOf('javascript:') !== 0
                      && (!a.target || a.target === '_self')) leaving();
                });
              }
              return state;
            }
            function containerIds() {
              var out = [];
              var els = D.querySelectorAll('form, nav, table, dialog, [role=dialog], section, main > *');
              for (var i = 0; i < els.length; i++) {
                var id = els[i].getAttribute('id'), testId = els[i].getAttribute('data-testid');
                if (id) out.push(id);
                if (testId) out.push(testId);
              }
              var overlays = D.querySelectorAll(
                '[role=listbox], [role=menu], [role=dialog], dialog[open], .cdk-overlay-pane, .dropdown-menu.show');
              for (var o = 0; o < overlays.length; o++) {
                var el = overlays[o];
                out.push('overlay:' + el.tagName.toLowerCase() + ':' + (el.getAttribute('role') || '') + ':'
                  + (el.getAttribute('aria-label') || ''));
              }
              return out;
            }
            // Expires so an SPA router that cancels the navigation in its own listener cannot stall the wait.
            function isLeaving(state) { return !!state.leavingAt && Date.now() - state.leavingAt < 2000; }
            function probe(state, extra) {
              var now = Date.now();
              var result = { url: W.location.href, title: D.title, ids: containerIds(), ready: D.readyState,
                             quietFor: now - state.last, fresh: !!state.fresh, leaving: isLeaving(state),
                             crossOrigin: crossOrigin };
              state.fresh = false;
              for (var k in extra) result[k] = extra[k];
              return result;
            }
            """;

    /** Sync, no waiting: the cheapest look at the page, used to skip the settle wait when nothing changed. */
    static final String PROBE = PRELUDE + """
            return probe(strataState(), {});
            """;

    /**
     * Async: resolves once a MutationObserver has seen no change for {@code arguments[0]} ms (hard cap
     * {@code arguments[1]} ms), then reports the same probe (§4.2, §4.3). On a document the observer already watches,
     * the quiet window counts from the last mutation it saw; on a fresh document, from installation.
     */
    static final String SETTLE = PRELUDE + """
            var done = arguments[arguments.length - 1];
            var QUIET = arguments[0], CAP = arguments[1], start = Date.now();
            var state = strataState();
            (function poll() {
              var now = Date.now();
              if ((now - state.last >= QUIET && !isLeaving(state)) || now - start >= CAP) {
                done(probe(state, { settleMs: now - start }));
              } else {
                setTimeout(poll, 25);
              }
            })();
            """;

    /**
     * Sync: for each element argument, its snapshot path: element-child indexes from {@code <html>} down, skipping
     * the elements redaction removes, with SVG descendants mapped to their {@code <svg>}; plus the chain of frame
     * indexes from the top document (§3.1 {@code UsageRecord}).
     */
    static final String PATHS = """
            var DROP = { SCRIPT: 1, STYLE: 1, NOSCRIPT: 1 };
            function pathOf(el) {
              var svg = el.closest && el.closest('svg');
              while (svg && svg.parentElement && svg.parentElement.closest('svg')) svg = svg.parentElement.closest('svg');
              if (svg) el = svg;
              var path = [];
              while (el && el.parentElement) {
                var i = 0, sib = el.parentElement.firstElementChild;
                for (; sib && sib !== el; sib = sib.nextElementSibling) if (!DROP[sib.tagName.toUpperCase()]) i++;
                path.unshift(i);
                el = el.parentElement;
              }
              return path;
            }
            function framesOf(el) {
              var chain = [], win = el.ownerDocument.defaultView;
              try {
                while (win && win.frameElement) {
                  var frame = win.frameElement, parentDoc = frame.ownerDocument;
                  chain.unshift(Array.prototype.indexOf.call(parentDoc.querySelectorAll('iframe, frame'), frame));
                  win = parentDoc.defaultView;
                }
              } catch (e) { return null; }
              return chain;
            }
            var out = [];
            for (var a = 0; a < arguments.length; a++) {
              var el = arguments[a];
              if (!el || !el.tagName) { out.push(null); continue; }
              var frames = framesOf(el);
              var target = el.closest && el.closest('svg') ? el.closest('svg') : el;
              out.push(frames === null ? null : { framePath: frames, path: pathOf(el), tag: target.tagName.toLowerCase() });
            }
            return out;
            """;

    /** Sync: returns {@code {html, skipped, frames}} for redacted clones of the top document and its same-origin frames. */
    static final String REDACT = PRELUDE + """
            var BLANK = 'tbody, [role=grid], [role=row], [role=gridcell], td, output';
            var KEEP = 'button, a, label, th, h1, h2, h3, h4, h5, h6, legend, caption, [role=tab], [role=menuitem], '
              + 'mat-icon, .material-icons, .material-symbols-outlined, .material-symbols-rounded';
            var ALWAYS_KEEP = '[role=columnheader]';
            var ALWAYS_BLANK = 'option, [role=option], mat-option';
            var ACTION = 'button, a, [role=button], [role=link], [role=menuitem]';
            var MARK = '\\u00b7\\u00b7\\u00b7', MAX = 60;

            function norm(s) { return s.replace(/\\s+/g, ' ').trim(); }

            // Repeated-label exception: an action whose text repeats across >= 2 rows is UI, not data.
            function repeatedActions(root) {
              var keep = new Set();
              var bodies = root.querySelectorAll('tbody, [role=grid]');
              for (var b = 0; b < bodies.length; b++) {
                var rowsByText = new Map();
                var actions = bodies[b].querySelectorAll(ACTION);
                for (var a = 0; a < actions.length; a++) {
                  var row = actions[a].closest('tr, [role=row]');
                  var text = norm(actions[a].textContent);
                  if (!row || !text) continue;
                  if (!rowsByText.has(text)) rowsByText.set(text, { rows: new Set(), els: [] });
                  var entry = rowsByText.get(text);
                  entry.rows.add(row);
                  entry.els.push(actions[a]);
                }
                rowsByText.forEach(function (entry) {
                  if (entry.rows.size >= 2) entry.els.forEach(function (el) { keep.add(el); });
                });
              }
              return keep;
            }

            function redact(doc) {
              var root = doc.documentElement.cloneNode(true);
              var drop = root.querySelectorAll('script, style, noscript');
              for (var d = 0; d < drop.length; d++) drop[d].remove();
              var svgs = root.querySelectorAll('svg');
              for (var s = 0; s < svgs.length; s++) { while (svgs[s].firstChild) svgs[s].removeChild(svgs[s].firstChild); }
              var areas = root.querySelectorAll('textarea');
              for (var t = 0; t < areas.length; t++) areas[t].textContent = '';
              var repeated = repeatedActions(root);
              var walker = doc.createTreeWalker(root, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_COMMENT);
              var comments = [], texts = [];
              while (walker.nextNode()) {
                (walker.currentNode.nodeType === Node.COMMENT_NODE ? comments : texts).push(walker.currentNode);
              }
              for (var c = 0; c < comments.length; c++) comments[c].remove();
              for (var x = 0; x < texts.length; x++) {
                var node = texts[x], value = node.nodeValue, parent = node.parentElement;
                if (!parent || !value.trim()) continue;
                var action = parent.closest(ACTION);
                var keep;
                if (parent.closest(ALWAYS_BLANK)) keep = false;
                else if (parent.closest(ALWAYS_KEEP)) keep = true;
                else if (parent.closest(BLANK)) keep = !!(action && repeated.has(action));
                else keep = !!parent.closest(KEEP);
                if (!keep) node.nodeValue = MARK;
                else if (value.trim().length > MAX) node.nodeValue = value.trim().substring(0, MAX);
              }
              var elements = [root].concat(Array.prototype.slice.call(root.querySelectorAll('*')));
              for (var e = 0; e < elements.length; e++) {
                var names = elements[e].getAttributeNames();
                for (var n = 0; n < names.length; n++) {
                  var name = names[n].toLowerCase();
                  // row-id / row-business-key carry the application's own row keys (AG Grid getRowId): data.
                  if (name === 'value' || name === 'style' || name.indexOf('on') === 0 || name === 'row-id'
                      || name === 'row-business-key'
                      || (name === 'title' && elements[e].closest('td, [role=gridcell]'))) {
                    elements[e].removeAttribute(names[n]);
                  }
                }
              }
              return root.outerHTML;
            }

            if (crossOrigin) return { crossOrigin: true };
            var skipped = [], frames = [];
            var frameEls = D.querySelectorAll('iframe, frame');
            for (var f = 0; f < frameEls.length; f++) {
              var label = 'iframe' + (frameEls[f].id ? '#' + frameEls[f].id : '[' + f + ']');
              var inner = null;
              try { inner = frameEls[f].contentDocument; } catch (err) { inner = null; }
              var blank = false;
              try { blank = !!inner && frameEls[f].contentWindow.location.href === 'about:blank'; } catch (err) { blank = false; }
              if (blank) {
                skipped.push(label + ':blank');
              } else if (inner && inner.documentElement) {
                frames.push({ index: f, url: frameEls[f].contentWindow.location.href, html: redact(inner) });
                if (inner.querySelector('iframe, frame')) skipped.push(label + ':nested');
              } else {
                skipped.push(label);
              }
            }
            var all = D.querySelectorAll('*');
            for (var a = 0; a < all.length; a++) {
              if (all[a].shadowRoot) {
                skipped.push('shadow:' + all[a].tagName.toLowerCase() + (all[a].id ? '#' + all[a].id : ''));
              }
            }
            return { html: redact(D), skipped: skipped, frames: frames };
            """;
}
