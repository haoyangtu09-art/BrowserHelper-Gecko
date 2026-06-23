// agent.js — page channel for bhcodex MCP (BrowserHelper-Codex Phase 3).
//
// Loaded before content.js via manifest order; defines bhHandleAgentCmd() in the
// shared content-script global so the port.onMessage listener in connect() can
// dispatch {action:"agentCmd"} messages from BrowserBridge/PageChannel.
//
// Design: page source is stored in a local JS variable (_bhPageSource) and never
// transferred wholesale to the APK. The agent calls page_index to index the page,
// then page_search/page_query to explore it incrementally. Only small result
// snippets cross the port. This avoids overwhelming the model context with raw HTML.
//
// page_exec runs arbitrary JS in PAGE world using Gecko's wrappedJSObject.Function —
// synchronous, CSP-bypass capable, Gecko-only (fine, this is a GeckoView APK).
// It is gated by the native caller: S3 ApprovalSheet for the overlay Agent, or
// AgentConfirm for the external BrowserBridge compatibility path.

/* jshint esversion: 6 */
/* global browser, wrappedJSObject */

var _bhPageSource = null;           // last indexed outerHTML (capped, JS-only)
var _bhPageSourceUrl = '';
var _bhPageSourceTs = 0;
var _bhEvalSeq = 0;
var _bhPageSourceCap = 512 * 1024;  // 512 KB cap to keep JS memory sane

// ── page world execution ─────────────────────────────────────────────────────

function _bhRunInPage(code) {
    // Inject a blob <script> into the page DOM to run code in page world. This
    // survives strict inline-script CSP on sites such as ChatGPT; inline is last resort.
    try {
        var blob = new Blob([code], { type: 'application/javascript' });
        var url = URL.createObjectURL(blob);
        var s = document.createElement('script');
        s.src = url;
        s.onload = function () { s.remove(); URL.revokeObjectURL(url); };
        s.onerror = function () {
            s.remove(); URL.revokeObjectURL(url);
            try {
                var inl = document.createElement('script');
                inl.textContent = code;
                (document.head || document.documentElement).appendChild(inl);
                inl.remove();
            } catch (_) {}
        };
        (document.head || document.documentElement).appendChild(s);
    } catch (e) {
        try {
            var s2 = document.createElement('script');
            s2.textContent = code;
            (document.head || document.documentElement).appendChild(s2);
            s2.remove();
        } catch (_) {}
    }
}

// Run [code] (a function body, may use `return`) in the CONTENT-SCRIPT sandbox via
// eval. The page's CSP does NOT govern the extension's content-script world, so this
// executes even when the page forbids inline / blob / unsafe-eval. It runs in the
// isolated world with Xray access to the live DOM (document/window/location are Xray
// wrappers of the page), so DOM-oriented scripts work; only page-defined JS expandos
// are out of reach here. Returns the result envelope on success, or null if blocked
// (extension CSP without 'unsafe-eval', or a runtime throw — caller falls back).
function _bhSandboxEval(code) {
    try {
        // eslint-disable-next-line no-eval
        var r = eval('(function(){' + code + '\n})()');
        var s;
        try { s = JSON.stringify(r); } catch (_) { s = null; }
        if (s === undefined || s === null) s = '"' + String(r) + '"';
        return { ok: true, value: s, world: 'content' };
    } catch (_) {
        return null;
    }
}

function _bhEvalInPage(code, timeoutMs) {
    // Primary path: use Gecko's wrappedJSObject to call page-world Function()
    // constructor — synchronous, works through Xray, full page-world fidelity.
    try {
        var pw = window.wrappedJSObject;
        if (pw && pw.Function) {
            var fn = new pw.Function(code);
            var r = fn.call(pw);
            var s;
            try { s = pw.JSON ? pw.JSON.stringify(r) : JSON.stringify(r); } catch (_) { s = null; }
            if (s === undefined || s === null) s = '"' + String(r) + '"';
            return Promise.resolve({ ok: true, value: s });
        }
    } catch (_cspErr) {
        // wrappedJSObject.Function blocked by page CSP (no 'unsafe-eval', e.g. ChatGPT).
        // Fall through to blob URL injection — treated as external script so it bypasses
        // inline-script / unsafe-eval restrictions on most pages; sandbox eval is the
        // last-resort bypass for pages (like ChatGPT) that forbid blob+inline too.
    }

    // Fallback A: blob URL injection (external script, page world, bypasses inline-script CSP).
    // Fallback B: content-script sandbox eval (immune to page CSP, Xray DOM) — the robust
    //             bypass when the page blocks blob+inline script-src entirely.
    // Fallback C: inline <script> textContent (last resort, permissive pages only).
    return new Promise(function (resolve) {
        var evtName = '__bhEval' + (++_bhEvalSeq) + '_' + Date.now();
        var done = false;
        function finish(detail) {
            if (done) return;
            done = true;
            document.removeEventListener(evtName, handler);
            resolve(detail || { error: 'no detail' });
        }
        function handler(e) { finish(e.detail); }
        document.addEventListener(evtName, handler);

        var scriptCode =
            '(function(){try{var __r=(function(){' + code + '})();' +
            'var __s;try{__s=JSON.stringify(__r);}catch(_){__s=String(__r);}' +
            'document.dispatchEvent(new CustomEvent(' + JSON.stringify(evtName) +
            ',{detail:{ok:true,value:__s||"null"}}));' +
            '}catch(e){document.dispatchEvent(new CustomEvent(' + JSON.stringify(evtName) +
            ',{detail:{ok:false,error:String(e)}}));}})()'
        ;

        var injected = false;
        // Fallback A: blob URL (preferred — keeps page-world fidelity).
        try {
            var blob = new Blob([scriptCode], { type: 'application/javascript' });
            var blobUrl = URL.createObjectURL(blob);
            var blobScript = document.createElement('script');
            blobScript.src = blobUrl;
            blobScript.onerror = function () {
                URL.revokeObjectURL(blobUrl);
                blobScript.remove();
                if (done) return;
                // blob blocked → page-world injection is gone; the content-script sandbox
                // still runs regardless of page CSP, so prefer it over a doomed inline retry.
                var sv = _bhSandboxEval(code);
                if (sv) { finish(sv); return; }
                _bhRunInPage(scriptCode);
            };
            blobScript.onload = function () { URL.revokeObjectURL(blobUrl); blobScript.remove(); };
            (document.head || document.documentElement).appendChild(blobScript);
            injected = true;
        } catch (_) {}

        // Fallback C directly if blob injection failed to even start.
        if (!injected && !done) {
            var sv0 = _bhSandboxEval(code);
            if (sv0) { finish(sv0); return; }
            _bhRunInPage(scriptCode);
        }

        setTimeout(function () {
            if (done) return;
            // Last-resort sandbox eval before reporting a CSP timeout.
            finish(_bhSandboxEval(code) || { error: 'evalJS timeout — CSP may be blocking all script execution on this page' });
        }, timeoutMs || 10000);
    });
}

function _bhPageFetch(args) {
    var target = String(args.url || '');
    if (!target) return Promise.resolve({ error: 'url required' });
    var required = String(args.requiredPageUrlContains || '');
    if (required && location.href.indexOf(required) < 0) {
        return Promise.resolve({ error: 'current page URL does not match requiredPageUrlContains', pageUrl: location.href });
    }
    var payload = {
        url: target,
        method: String(args.method || 'GET').toUpperCase(),
        headers: args.headers || {},
        body: Object.prototype.hasOwnProperty.call(args, 'body') ? String(args.body || '') : null,
        credentials: args.credentials || 'same-origin',
        mode: args.mode || 'cors',
        timeoutMs: Math.max(1000, Math.min(Number(args.timeoutMs || 20000), 60000)),
        bodyCap: 512 * 1024,
        pageUrl: location.href,
    };
    return new Promise(function (resolve) {
        var evtName = '__bhPageFetch' + (++_bhEvalSeq) + '_' + Date.now();
        var done = false;
        function finish(detail) {
            if (done) return;
            done = true;
            document.removeEventListener(evtName, handler);
            resolve(detail || { error: 'no detail' });
        }
        function handler(e) { finish(e.detail); }
        document.addEventListener(evtName, handler);

        var scriptCode =
            '(function(){var cfg=' + JSON.stringify(payload) + ';' +
            'var ctl=new AbortController();' +
            'var timer=setTimeout(function(){try{ctl.abort();}catch(_){}} ,cfg.timeoutMs);' +
            'function send(d){clearTimeout(timer);document.dispatchEvent(new CustomEvent(' + JSON.stringify(evtName) + ',{detail:d}));}' +
            'try{var opt={method:cfg.method,headers:cfg.headers||{},credentials:cfg.credentials,mode:cfg.mode,signal:ctl.signal};' +
            'if(cfg.body!==null&&cfg.method!=="GET"&&cfg.method!=="HEAD")opt.body=cfg.body;' +
            'fetch(cfg.url,opt).then(function(r){return r.text().then(function(text){' +
            'var h={};try{r.headers.forEach(function(v,k){h[k]=v;});}catch(_){}' +
            'send({ok:true,pageUrl:cfg.pageUrl,url:r.url,status:r.status,statusText:r.statusText,headers:h,body:text.substring(0,cfg.bodyCap),truncated:text.length>cfg.bodyCap});' +
            '});}).catch(function(e){send({ok:false,error:String(e),pageUrl:cfg.pageUrl});});' +
            '}catch(e){send({ok:false,error:String(e),pageUrl:cfg.pageUrl});}})()'
        ;

        var injected = false;
        try {
            var blob = new Blob([scriptCode], { type: 'application/javascript' });
            var blobUrl = URL.createObjectURL(blob);
            var blobScript = document.createElement('script');
            blobScript.src = blobUrl;
            blobScript.onerror = function () {
                URL.revokeObjectURL(blobUrl);
                blobScript.remove();
                if (!done) _bhRunInPage(scriptCode);
            };
            blobScript.onload = function () { URL.revokeObjectURL(blobUrl); blobScript.remove(); };
            (document.head || document.documentElement).appendChild(blobScript);
            injected = true;
        } catch (_) {}
        if (!injected && !done) _bhRunInPage(scriptCode);

        setTimeout(function () {
            finish({ error: 'pageFetch timeout', pageUrl: location.href });
        }, payload.timeoutMs + 1000);
    });
}

// ── DOM/CSS hardening for page tools ────────────────────────────────────────
// Page automation must work on pages that use shadow DOM, pointer-events traps,
// opacity/visibility transitions, sticky overlays, or framework-controlled inputs.
// These helpers keep tools constrained (no arbitrary JS for S2), but make the
// actual element interaction less dependent on the page's own CSS.
function _bhParentElement(el) {
    if (!el) return null;
    if (el.parentElement) return el.parentElement;
    var root = el.getRootNode && el.getRootNode();
    return root && root.host ? root.host : null;
}

function _bhQueryAll(selector, limit) {
    selector = String(selector || '');
    if (!selector) return [];
    var max = Math.max(1, Math.min(Number(limit || 100), 1000));
    var out = [];
    var seenRoots = [];
    function add(el) {
        if (!el || out.indexOf(el) >= 0) return;
        out.push(el);
    }
    function scan(root) {
        if (!root || seenRoots.indexOf(root) >= 0 || out.length >= max) return;
        seenRoots.push(root);
        var matches = [];
        try { matches = Array.prototype.slice.call(root.querySelectorAll(selector)); }
        catch (e) { throw e; }
        for (var i = 0; i < matches.length && out.length < max; i++) add(matches[i]);
        var all = [];
        try { all = Array.prototype.slice.call(root.querySelectorAll('*')); } catch (_) {}
        for (var j = 0; j < all.length && out.length < max; j++) {
            if (all[j].shadowRoot) scan(all[j].shadowRoot);
        }
    }
    scan(document);
    return out.slice(0, max);
}

function _bhQueryOne(selector) {
    return _bhQueryAll(selector, 1)[0] || null;
}

function _bhElementRect(el) {
    try {
        var r = el.getBoundingClientRect();
        return { x: Math.round(r.x), y: Math.round(r.y), width: Math.round(r.width), height: Math.round(r.height) };
    } catch (e) {
        return { x: 0, y: 0, width: 0, height: 0 };
    }
}

function _bhElementVisible(el) {
    if (!el) return false;
    try {
        var r = el.getBoundingClientRect();
        if (r.width <= 0 || r.height <= 0) return false;
        var cs = window.getComputedStyle ? window.getComputedStyle(el) : null;
        if (cs && (cs.display === 'none' || cs.visibility === 'hidden' || cs.visibility === 'collapse' || Number(cs.opacity) === 0)) return false;
        return true;
    } catch (e) {
        return false;
    }
}

function _bhApplyCssBypass(el, opts) {
    if (!el || !el.style) return function () {};
    opts = opts || {};
    var saved = [];
    function set(prop, val) {
        try {
            saved.push([prop, el.style.getPropertyValue(prop), el.style.getPropertyPriority(prop)]);
            el.style.setProperty(prop, val, 'important');
        } catch (e) {}
    }
    set('pointer-events', 'auto');
    set('visibility', 'visible');
    set('opacity', '1');
    set('scroll-margin', '80px');
    set('scroll-margin-top', '80px');
    if (opts.zIndex) {
        set('position', 'relative');
        set('z-index', '2147483647');
    }
    if (opts.display) {
        try {
            var cs = window.getComputedStyle ? window.getComputedStyle(el) : null;
            if (cs && cs.display === 'none') set('display', 'block');
        } catch (e) {}
    }
    return function () {
        for (var i = saved.length - 1; i >= 0; i--) {
            try {
                if (saved[i][1]) el.style.setProperty(saved[i][0], saved[i][1], saved[i][2] || '');
                else el.style.removeProperty(saved[i][0]);
            } catch (e) {}
        }
    };
}

function _bhScrollIntoView(el, block, inline) {
    if (!el) return;
    var restore = _bhApplyCssBypass(el, { display: true });
    try { el.scrollIntoView({ block: block || 'center', inline: inline || 'center' }); } catch (e) {}
    setTimeout(restore, 120);
}

function _bhSetNativeValue(el, value) {
    var tag = (el.tagName || '').toLowerCase();
    var proto = tag === 'textarea' ? HTMLTextAreaElement.prototype :
        (tag === 'input' ? HTMLInputElement.prototype : null);
    var desc = proto ? Object.getOwnPropertyDescriptor(proto, 'value') : null;
    if (desc && desc.set) desc.set.call(el, value);
    else el.value = value;
}

function _bhDispatchInput(el, value) {
    try {
        el.dispatchEvent(new InputEvent('beforeinput', { bubbles: true, cancelable: true, inputType: 'insertText', data: value }));
    } catch (e) {}
    try {
        el.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: value }));
    } catch (e) {
        try { el.dispatchEvent(new Event('input', { bubbles: true })); } catch (_) {}
    }
    try { el.dispatchEvent(new Event('change', { bubbles: true })); } catch (e) {}
}

function _bhSummarizeElement(el) {
    var attrs = {};
    try {
        for (var i = 0; i < el.attributes.length; i++) {
            attrs[el.attributes[i].name] = el.attributes[i].value.substring(0, 200);
        }
    } catch (e) {}
    return {
        tag: el.tagName,
        id: el.id || undefined,
        className: el.className || undefined,
        text: el.textContent ? el.textContent.trim().substring(0, 300) : '',
        html: el.outerHTML ? el.outerHTML.substring(0, 600) : '',
        attrs: attrs,
        visible: _bhElementVisible(el),
        rect: _bhElementRect(el),
    };
}

// ── page-world console capture (best-effort) ─────────────────────────────────
// console.list reads page-world console output. The hook MUST live in page world
// (the content-script console is a separate object), so we inject a small idempotent
// patch that mirrors console.{log,info,warn,error,debug} + uncaught errors into a
// capped page-world ring buffer (window.__bhConsoleBuf). Logs emitted BEFORE the hook
// installs are lost — there is no API to retroactively capture them (CSP-sensitive).
var _bhConsoleHookCode =
    '(function(){' +
    'if(window.__bhConsoleHooked)return;' +
    'window.__bhConsoleHooked=true;' +
    'window.__bhConsoleBuf=window.__bhConsoleBuf||[];' +
    'var CAP=200;' +
    'function push(level,a){try{var parts=Array.prototype.slice.call(a).map(function(x){if(typeof x==="string")return x;try{return JSON.stringify(x);}catch(_){return String(x);}});window.__bhConsoleBuf.push({level:level,ts:Date.now(),text:parts.join(" ").substring(0,1000)});if(window.__bhConsoleBuf.length>CAP)window.__bhConsoleBuf.shift();}catch(_){}}' +
    '["log","info","warn","error","debug"].forEach(function(lvl){var orig=console[lvl];if(typeof orig!=="function")return;console[lvl]=function(){push(lvl,arguments);return orig.apply(console,arguments);};});' +
    'window.addEventListener("error",function(e){push("error",["Uncaught: "+((e&&e.message)||"")]);});' +
    'window.addEventListener("unhandledrejection",function(e){push("error",["UnhandledRejection: "+((e&&e.reason)||"")]);});' +
    '})()';
function _bhEnsureConsoleHook() {
    return _bhEvalInPage(_bhConsoleHookCode, 3000).then(function () { return true; }, function () { return true; });
}

// ── command dispatcher ────────────────────────────────────────────────────────

function bhHandleAgentCmd(msg) {
    var cmd = (msg && msg.cmd) || '';
    var args = (msg && msg.args) || {};

    // ── page_index ──────────────────────────────────────────────────────────
    // Fetch current page outerHTML; store locally; return only metadata so the
    // agent doesn't get the entire source blob in one shot.
    if (cmd === 'getSource') {
        try {
            var raw = document.documentElement.outerHTML;
            var trunc = raw.length > _bhPageSourceCap;
            _bhPageSource = trunc ? raw.substring(0, _bhPageSourceCap) : raw;
            _bhPageSourceUrl = location.href;
            _bhPageSourceTs = Date.now();
            var h1 = [], h2 = [], forms = [], links = [];
            try {
                document.querySelectorAll('h1').forEach(function (e, i) {
                    if (i < 5) h1.push(e.textContent.trim().substring(0, 100));
                });
                document.querySelectorAll('h2').forEach(function (e, i) {
                    if (i < 10) h2.push(e.textContent.trim().substring(0, 100));
                });
                document.querySelectorAll('form').forEach(function (e, i) {
                    if (i < 5) forms.push({
                        action: (e.action || '').substring(0, 120),
                        method: e.method,
                        inputs: Array.prototype.slice.call(e.querySelectorAll('input,textarea,select'), 0, 10)
                            .map(function (inp) { return { name: inp.name, type: inp.type || inp.tagName }; }),
                    });
                });
                document.querySelectorAll('a[href]').forEach(function (e, i) {
                    if (i < 10) links.push({ text: e.textContent.trim().substring(0, 60), href: (e.href || '').substring(0, 120) });
                });
            } catch (_) {}
            return Promise.resolve({
                url: location.href,
                title: document.title,
                totalLength: raw.length,
                storedLength: _bhPageSource.length,
                truncated: trunc,
                h1: h1, h2: h2, forms: forms, links: links,
            });
        } catch (e) {
            return Promise.resolve({ error: String(e) });
        }
    }

    // ── page_save_to_container (native writes the blob to a file) ─────────────
    // Returns the full current-page outerHTML to NATIVE only (never into the model
    // transcript). Native writes it to a container file and reports just metadata.
    // Capped at 4 MB so a pathological page can't stall the page channel.
    if (cmd === 'getSourceRaw') {
        try {
            var rawFull = document.documentElement.outerHTML || '';
            var rawCap = 4 * 1024 * 1024;
            var clipped = rawFull.length > rawCap;
            return Promise.resolve({
                ok: true,
                url: location.href,
                title: document.title,
                totalLength: rawFull.length,
                truncated: clipped,
                html: clipped ? rawFull.substring(0, rawCap) : rawFull,
            });
        } catch (e) {
            return Promise.resolve({ error: String(e) });
        }
    }

    // ── page_search ─────────────────────────────────────────────────────────
    // Text search on the locally-stored source. Returns small snippets (±150 chars).
    if (cmd === 'searchSource') {
        var q = args.query || '';
        if (!q) return Promise.resolve({ error: 'query required' });
        // Auto-index if the page hasn't been indexed yet (or was indexed on a
        // different URL), so page_search is self-sufficient and never fails with
        // "call page_index first". Pure content-script DOM read — not CSP-gated.
        if (!_bhPageSource || _bhPageSourceUrl !== location.href) {
            try {
                var rawIdx = document.documentElement.outerHTML;
                _bhPageSource = rawIdx.length > _bhPageSourceCap
                    ? rawIdx.substring(0, _bhPageSourceCap) : rawIdx;
                _bhPageSourceUrl = location.href;
                _bhPageSourceTs = Date.now();
            } catch (e) {
                return Promise.resolve({ error: 'index failed: ' + String(e) });
            }
        }
        var results = [];
        var src = _bhPageSource;
        var srcL = src.toLowerCase();
        var qL = q.toLowerCase();
        var pos = 0;
        var maxR = Math.min(args.limit || 10, 50);
        var CTX = 150;
        while (results.length < maxR) {
            var idx = srcL.indexOf(qL, pos);
            if (idx < 0) break;
            var s0 = Math.max(0, idx - CTX);
            var s1 = Math.min(src.length, idx + q.length + CTX);
            results.push({ pos: idx, snippet: src.substring(s0, s1) });
            pos = idx + Math.max(q.length, 1);
        }
        return Promise.resolve({
            query: q, sourceUrl: _bhPageSourceUrl,
            indexedAt: _bhPageSourceTs,
            count: results.length, results: results,
        });
    }

    // ── page_query ──────────────────────────────────────────────────────────
    // Live CSS selector query. Returns element summaries (tag, text, attrs, html).
    if (cmd === 'queryDOM') {
        var sel = args.selector || '';
        if (!sel) return Promise.resolve({ error: 'selector required' });
        try {
            var els = _bhQueryAll(sel, args.limit || 20);
            var mapped = els.map(_bhSummarizeElement);
            return Promise.resolve({ selector: sel, count: mapped.length, elements: mapped });
        } catch (e) {
            return Promise.resolve({ error: String(e) });
        }
    }

    // ── page write (S2, constrained DOM writes) ─────────────────────────────
    // These commands intentionally cover common page-source edits without exposing
    // arbitrary JS. Full page-world JS stays behind the S3-only evalJS command.
    if (cmd === 'setText' || cmd === 'setHTML' || cmd === 'setAttr') {
        var writeSel = args.selector || '';
        if (!writeSel) return Promise.resolve({ error: 'selector required' });
        try {
            var targets = _bhQueryAll(writeSel, args.all ? 50 : 1);
            if (!targets.length) return Promise.resolve({ error: 'no elements matched selector' });
            if (cmd === 'setText') {
                targets.forEach(function (el) {
                    var restore = _bhApplyCssBypass(el, { display: true });
                    el.textContent = String(args.text || '');
                    setTimeout(restore, 80);
                });
            } else if (cmd === 'setHTML') {
                targets.forEach(function (el) {
                    var restore = _bhApplyCssBypass(el, { display: true });
                    el.innerHTML = String(args.html || '');
                    setTimeout(restore, 80);
                });
            } else {
                var attrName = String(args.name || '').trim();
                if (!/^[A-Za-z_:][A-Za-z0-9_.:-]*$/.test(attrName)) {
                    return Promise.resolve({ error: 'invalid attribute name' });
                }
                targets.forEach(function (el) {
                    var restore = _bhApplyCssBypass(el, { display: true });
                    el.setAttribute(attrName, String(args.value || ''));
                    setTimeout(restore, 80);
                });
            }
            return Promise.resolve({
                ok: true,
                cmd: cmd,
                selector: writeSel,
                count: targets.length,
            });
        } catch (e) {
            return Promise.resolve({ error: String(e) });
        }
    }

    // ── page_scroll (S2, constrained UI action) ─────────────────────────────
    // Scrolls the page or one matched scroll container. This is a bounded UI action,
    // not arbitrary JS; full script execution remains S3-only evalJS.
    if (cmd === 'scrollPage') {
        try {
            var direction = String(args.direction || 'down').toLowerCase();
            var behavior = String(args.behavior || 'auto').toLowerCase();
            if (behavior !== 'smooth' && behavior !== 'instant') behavior = 'auto';
            var actualBehavior = behavior === 'smooth' ? 'smooth' : 'auto';
            var selector = String(args.selector || '');
            var toSelector = String(args.toSelector || args.intoViewSelector || '');
            var block = String(args.block || 'center');
            var inline = String(args.inline || 'nearest');
            var waitMs = Math.max(20, Math.min(Number(args.waitMs || (behavior === 'smooth' ? 240 : 60)), 1000));
            var pageFactor = Number(args.pages || 0);
            var baseAmount = pageFactor ? Math.round(window.innerHeight * pageFactor) : Number(args.amount || args.pixels || 600);
            if (!isFinite(baseAmount) || baseAmount <= 0) baseAmount = 600;
            var amount = Math.max(1, Math.min(baseAmount, 5000));
            function isScrollable(el) {
                if (!el || el === window || el === document || el === document.documentElement || el === document.body) return false;
                var style = window.getComputedStyle ? window.getComputedStyle(el) : null;
                var oy = style ? style.overflowY : '';
                var ox = style ? style.overflowX : '';
                return ((el.scrollHeight > el.clientHeight + 1) && /(auto|scroll|overlay)/.test(oy)) ||
                    ((el.scrollWidth > el.clientWidth + 1) && /(auto|scroll|overlay)/.test(ox));
            }
            function nearestScrollable(el) {
                var cur = el;
                while (cur && cur !== document.body && cur !== document.documentElement) {
                    if (isScrollable(cur)) return cur;
                    cur = _bhParentElement(cur);
                }
                return window;
            }
            var target = selector ? _bhQueryOne(selector) : window;
            if (!target) return Promise.resolve({ error: 'no elements matched selector', selector: selector });
            if (target !== window && !isScrollable(target) && args.nearestScrollable !== false) {
                target = nearestScrollable(target);
            }

            var isWindow = target === window;
            var beforeX = isWindow ? window.scrollX : target.scrollLeft;
            var beforeY = isWindow ? window.scrollY : target.scrollTop;
            var dx = 0;
            var dy = 0;
            if (direction === 'up') dy = -amount;
            else if (direction === 'left') dx = -amount;
            else if (direction === 'right') dx = amount;
            else if (direction === 'top') dy = -100000000;
            else if (direction === 'bottom') dy = 100000000;
            else dy = amount;

            var intoViewEl = toSelector ? _bhQueryOne(toSelector) : null;
            if (toSelector && !intoViewEl) return Promise.resolve({ error: 'no elements matched toSelector', toSelector: toSelector });
            if (intoViewEl) {
                var ivRestore = _bhApplyCssBypass(intoViewEl, { display: true });
                intoViewEl.scrollIntoView({ behavior: actualBehavior, block: block, inline: inline });
                setTimeout(ivRestore, waitMs + 120);
            } else if (isWindow) {
                window.scrollBy({ left: dx, top: dy, behavior: actualBehavior });
            } else {
                target.scrollBy({ left: dx, top: dy, behavior: actualBehavior });
            }

            return new Promise(function (resolve) {
                setTimeout(function () {
                    var afterX = isWindow ? window.scrollX : target.scrollLeft;
                    var afterY = isWindow ? window.scrollY : target.scrollTop;
                    var rect = null;
                    if (intoViewEl) {
                        var r = intoViewEl.getBoundingClientRect();
                        rect = { x: Math.round(r.x), y: Math.round(r.y), width: Math.round(r.width), height: Math.round(r.height) };
                    }
                    resolve({
                        ok: true,
                        selector: selector || null,
                        toSelector: toSelector || null,
                        direction: direction,
                        amount: amount,
                        pages: pageFactor || null,
                        target: isWindow ? 'window' : 'element',
                        beforeX: beforeX,
                        beforeY: beforeY,
                        afterX: afterX,
                        afterY: afterY,
                        movedX: afterX - beforeX,
                        movedY: afterY - beforeY,
                        moved: beforeX !== afterX || beforeY !== afterY,
                        elementRect: rect,
                        viewportHeight: window.innerHeight,
                        viewportWidth: window.innerWidth,
                        pageHeight: Math.max(document.documentElement.scrollHeight, document.body ? document.body.scrollHeight : 0),
                        pageWidth: Math.max(document.documentElement.scrollWidth, document.body ? document.body.scrollWidth : 0),
                    });
                }, behavior === 'smooth' ? 220 : 40);
            });
        } catch (e) {
            return Promise.resolve({ error: String(e) });
        }
    }

    // ── page_long_press (S2, constrained UI gesture) ────────────────────────
    // Holds a pointer/mouse/touch down on an element (or x/y point) for a duration,
    // then releases + fires contextmenu — enough to trigger timer-based long-press
    // handlers. Bounded gesture; arbitrary JS stays S3-only evalJS.
    if (cmd === 'longPress') {
        try {
            var lpSel = String(args.selector || '');
            var durationMs = Math.max(100, Math.min(Number(args.durationMs || args.duration || 600), 5000));
            var lpEl = lpSel ? _bhQueryOne(lpSel) : null;
            if (lpSel && !lpEl) return Promise.resolve({ error: 'no elements matched selector', selector: lpSel });
            var lpRestore = lpEl ? _bhApplyCssBypass(lpEl, { display: true, zIndex: true }) : function () {};
            var px;
            var py;
            if (lpEl) {
                _bhScrollIntoView(lpEl);
                var lr = lpEl.getBoundingClientRect();
                px = (typeof args.x === 'number') ? args.x : Math.round(lr.left + lr.width / 2);
                py = (typeof args.y === 'number') ? args.y : Math.round(lr.top + lr.height / 2);
            } else {
                px = Math.round(Number(args.x || 0));
                py = Math.round(Number(args.y || 0));
            }
            var lpTgt = lpEl || document.elementFromPoint(px, py) || document.body;
            var lpBase = { bubbles: true, cancelable: true, view: window, clientX: px, clientY: py, button: 0 };
            var lpPtr = { bubbles: true, cancelable: true, view: window, clientX: px, clientY: py, pointerId: 1, pointerType: 'touch', isPrimary: true };
            function lpFireMouse(type) { try { lpTgt.dispatchEvent(new MouseEvent(type, lpBase)); } catch (e) {} }
            function lpFirePtr(type) { try { if (window.PointerEvent) lpTgt.dispatchEvent(new PointerEvent(type, lpPtr)); } catch (e) {} }
            function lpFireTouch(type, active) {
                try {
                    var t = new Touch({ identifier: 1, target: lpTgt, clientX: px, clientY: py, pageX: px, pageY: py });
                    lpTgt.dispatchEvent(new TouchEvent(type, {
                        bubbles: true, cancelable: true, view: window,
                        touches: active ? [t] : [], targetTouches: active ? [t] : [], changedTouches: [t],
                    }));
                } catch (e) {}
            }
            lpFirePtr('pointerdown');
            lpFireTouch('touchstart', true);
            lpFireMouse('mousedown');
            return new Promise(function (resolve) {
                setTimeout(function () {
                    lpFireTouch('touchend', false);
                    lpFirePtr('pointerup');
                    lpFireMouse('mouseup');
                    try { lpTgt.dispatchEvent(new MouseEvent('contextmenu', lpBase)); } catch (e) {}
                    lpRestore();
                    resolve({ ok: true, selector: lpSel || null, x: px, y: py, durationMs: durationMs, tag: lpTgt.tagName });
                }, durationMs);
            });
        } catch (e) {
            return Promise.resolve({ error: String(e) });
        }
    }

    // ── page_swipe (S3) ─────────────────────────────────────────────────────
    // Continuous / long-distance swipe: repeats a directional scroll (and optionally
    // emits a pointer drag gesture) so long pages and carousels can be driven far in
    // one call. scrollBy is the reliable scroller; the gesture is a best-effort bonus
    // for widgets that listen to pointer drags.
    if (cmd === 'swipePage') {
        try {
            var sdir = String(args.direction || 'down').toLowerCase();
            var sSel = String(args.selector || '');
            var distance = Number(args.distance || args.amount || args.pixels || 800);
            if (!isFinite(distance) || distance <= 0) distance = 800;
            distance = Math.max(1, Math.min(distance, 100000));
            var repeat = Math.max(1, Math.min(Number(args.repeat || 1), 100));
            var stepDelayMs = Math.max(0, Math.min(Number(args.stepDelayMs || 120), 2000));
            var doGesture = args.gesture !== false;
            var swBehavior = (String(args.behavior || 'auto').toLowerCase() === 'smooth') ? 'smooth' : 'auto';
            var swTarget = sSel ? _bhQueryOne(sSel) : window;
            if (sSel && !swTarget) return Promise.resolve({ error: 'no elements matched selector', selector: sSel });
            var swIsWindow = swTarget === window;
            var swRestore = swIsWindow ? function () {} : _bhApplyCssBypass(swTarget, { display: true });
            var unitX = 0;
            var unitY = 0;
            if (sdir === 'up') unitY = -1;
            else if (sdir === 'left') unitX = -1;
            else if (sdir === 'right') unitX = 1;
            else unitY = 1;
            var swStartX = swIsWindow ? window.scrollX : swTarget.scrollLeft;
            var swStartY = swIsWindow ? window.scrollY : swTarget.scrollTop;
            function swGesture(stepDx, stepDy) {
                if (!doGesture || !window.PointerEvent) return;
                try {
                    var cx = Math.round(window.innerWidth / 2);
                    var cy = Math.round(window.innerHeight / 2);
                    var fdx = -(stepDx === 0 ? 0 : (stepDx > 0 ? 1 : -1)) * Math.min(Math.abs(stepDx), Math.round(window.innerWidth * 0.6));
                    var fdy = -(stepDy === 0 ? 0 : (stepDy > 0 ? 1 : -1)) * Math.min(Math.abs(stepDy), Math.round(window.innerHeight * 0.6));
                    var gt = document.elementFromPoint(cx, cy) || document.body;
                    function gp(extra) {
                        var o = { bubbles: true, cancelable: true, view: window, pointerId: 1, pointerType: 'touch', isPrimary: true };
                        for (var k in extra) o[k] = extra[k];
                        return o;
                    }
                    gt.dispatchEvent(new PointerEvent('pointerdown', gp({ clientX: cx, clientY: cy })));
                    for (var s = 1; s <= 4; s++) {
                        gt.dispatchEvent(new PointerEvent('pointermove', gp({ clientX: cx + fdx * s / 4, clientY: cy + fdy * s / 4 })));
                    }
                    gt.dispatchEvent(new PointerEvent('pointerup', gp({ clientX: cx + fdx, clientY: cy + fdy })));
                } catch (e) {}
            }
            return new Promise(function (resolve) {
                var done = 0;
                function swStep() {
                    if (done >= repeat) {
                        swRestore();
                        var endX = swIsWindow ? window.scrollX : swTarget.scrollLeft;
                        var endY = swIsWindow ? window.scrollY : swTarget.scrollTop;
                        resolve({
                            ok: true, selector: sSel || null, direction: sdir,
                            distance: distance, repeat: repeat, target: swIsWindow ? 'window' : 'element',
                            startX: swStartX, startY: swStartY, endX: endX, endY: endY,
                            movedX: endX - swStartX, movedY: endY - swStartY,
                            moved: (endX !== swStartX) || (endY !== swStartY),
                            pageHeight: Math.max(document.documentElement.scrollHeight, document.body ? document.body.scrollHeight : 0),
                            viewportHeight: window.innerHeight,
                        });
                        return;
                    }
                    var dx = unitX * distance;
                    var dy = unitY * distance;
                    swGesture(dx, dy);
                    if (swIsWindow) window.scrollBy({ left: dx, top: dy, behavior: swBehavior });
                    else swTarget.scrollBy({ left: dx, top: dy, behavior: swBehavior });
                    done++;
                    setTimeout(swStep, stepDelayMs);
                }
                swStep();
            });
        } catch (e) {
            return Promise.resolve({ error: String(e) });
        }
    }

    // ── page-origin fetch (S3) ─────────────────────────────────────────────
    // Runs fetch in page world, so the request originates from the current page
    // instead of the native app process.
    if (cmd === 'pageFetch') {
        return _bhPageFetch(args);
    }

    // ── page_click (S2, constrained UI action) ──────────────────────────────
    // Click a single matched element via realistic pointer/mouse down+up then the
    // element's native click(). Bounded action; arbitrary JS stays S3-only evalJS.
    if (cmd === 'clickEl') {
        var clkSel = String(args.selector || '');
        if (!clkSel) return Promise.resolve({ error: 'selector required' });
        try {
            var clkEl = _bhQueryOne(clkSel);
            if (!clkEl) return Promise.resolve({ error: 'no elements matched selector', selector: clkSel });
            var clkRestore = _bhApplyCssBypass(clkEl, { display: true, zIndex: true });
            _bhScrollIntoView(clkEl);
            var cr = clkEl.getBoundingClientRect();
            var ccx = Math.round(cr.left + cr.width / 2);
            var ccy = Math.round(cr.top + cr.height / 2);
            ccx = Math.max(1, Math.min(window.innerWidth - 1, ccx));
            ccy = Math.max(1, Math.min(window.innerHeight - 1, ccy));
            var hit = null;
            try { hit = document.elementFromPoint(ccx, ccy); } catch (e) {}
            var covered = !!(hit && hit !== clkEl && !clkEl.contains(hit));
            var cbase = { bubbles: true, cancelable: true, view: window, clientX: ccx, clientY: ccy, button: 0 };
            var cptr = { bubbles: true, cancelable: true, view: window, clientX: ccx, clientY: ccy, pointerId: 1, pointerType: 'mouse', isPrimary: true };
            try { if (window.PointerEvent) clkEl.dispatchEvent(new PointerEvent('pointerdown', cptr)); } catch (e) {}
            try { clkEl.dispatchEvent(new MouseEvent('mousedown', cbase)); } catch (e) {}
            try { if (window.PointerEvent) clkEl.dispatchEvent(new PointerEvent('pointerup', cptr)); } catch (e) {}
            try { clkEl.dispatchEvent(new MouseEvent('mouseup', cbase)); } catch (e) {}
            // Native click() fires the canonical 'click' event once (avoids double-fire).
            try { if (typeof clkEl.click === 'function') clkEl.click(); else clkEl.dispatchEvent(new MouseEvent('click', cbase)); } catch (e) {}
            setTimeout(clkRestore, 120);
            return Promise.resolve({
                ok: true,
                selector: clkSel,
                tag: clkEl.tagName,
                x: ccx,
                y: ccy,
                visible: _bhElementVisible(clkEl),
                covered: covered,
                hitTag: hit && hit.tagName || '',
            });
        } catch (e) {
            return Promise.resolve({ error: String(e) });
        }
    }

    // ── page_type (S2, constrained UI action) ───────────────────────────────
    // Set the value/text of an input, textarea, or contentEditable element and fire
    // input/change so frameworks observe it. Bounded; arbitrary JS stays S3 evalJS.
    if (cmd === 'typeText') {
        var tySel = String(args.selector || '');
        if (!tySel) return Promise.resolve({ error: 'selector required' });
        try {
            var tyEl = _bhQueryOne(tySel);
            if (!tyEl) return Promise.resolve({ error: 'no elements matched selector', selector: tySel });
            var tyVal = String(args.text || '');
            var tyAppend = !!args.append;
            var tyRestore = _bhApplyCssBypass(tyEl, { display: true, zIndex: true });
            _bhScrollIntoView(tyEl);
            try { tyEl.focus(); } catch (e) {}
            if ('value' in tyEl) {
                _bhSetNativeValue(tyEl, tyAppend ? (tyEl.value + tyVal) : tyVal);
            } else if (tyEl.isContentEditable) {
                tyEl.textContent = tyAppend ? ((tyEl.textContent || '') + tyVal) : tyVal;
            } else {
                tyRestore();
                return Promise.resolve({ error: 'element is not editable (no value and not contentEditable)' });
            }
            _bhDispatchInput(tyEl, tyVal);
            setTimeout(tyRestore, 120);
            return Promise.resolve({ ok: true, selector: tySel, tag: tyEl.tagName, length: tyVal.length, append: tyAppend, visible: _bhElementVisible(tyEl) });
        } catch (e) {
            return Promise.resolve({ error: String(e) });
        }
    }

    // ── page_wait_for_element (S1, read-only poll) ──────────────────────────
    // Poll the DOM until a selector matches (optionally requiring it be visible) or
    // a timeout elapses. Returns found:true/false; never throws on timeout.
    if (cmd === 'waitForElement') {
        var wSel = String(args.selector || '');
        if (!wSel) return Promise.resolve({ error: 'selector required' });
        var wTimeout = Math.max(100, Math.min(Number(args.timeoutMs || 5000), 30000));
        var wVisible = !!args.visible;
        var wStart = Date.now();
        function wMatch() {
            var el = null;
            try { el = _bhQueryOne(wSel); } catch (e) { return { __bhError: String(e) }; }
            if (!el) return null;
            if (wVisible && !_bhElementVisible(el)) return null;
            return el;
        }
        return new Promise(function (resolve) {
            (function tick() {
                var el = wMatch();
                if (el && el.__bhError) {
                    resolve({ ok: false, error: el.__bhError, selector: wSel, waitedMs: Date.now() - wStart });
                    return;
                }
                if (el) {
                    resolve({ ok: true, found: true, selector: wSel, waitedMs: Date.now() - wStart, tag: el.tagName, text: el.textContent ? el.textContent.trim().substring(0, 200) : '' });
                    return;
                }
                if (Date.now() - wStart >= wTimeout) {
                    resolve({ ok: true, found: false, selector: wSel, waitedMs: Date.now() - wStart, timeout: true });
                    return;
                }
                setTimeout(tick, 100);
            })();
        });
    }

    // ── dom_highlight_element (S2, visual aid) ──────────────────────────────
    // Draw a temporary coloured outline around matched elements and scroll the first
    // into view. Restores the previous inline outline after durationMs.
    if (cmd === 'highlight') {
        var hSel = String(args.selector || '');
        if (!hSel) return Promise.resolve({ error: 'selector required' });
        try {
            var hEls = _bhQueryAll(hSel, args.all ? 50 : 1);
            if (!hEls.length) return Promise.resolve({ error: 'no elements matched selector', selector: hSel });
            var hColor = String(args.color || '#ff3b30');
            var hDuration = Math.max(0, Math.min(Number(args.durationMs || 2000), 20000));
            hEls.forEach(function (el) {
                try {
                    var restoreCss = _bhApplyCssBypass(el, { display: true, zIndex: true });
                    var prevOutline = el.style.getPropertyValue('outline');
                    var prevOutlinePr = el.style.getPropertyPriority('outline');
                    var prevOffset = el.style.getPropertyValue('outline-offset');
                    var prevOffsetPr = el.style.getPropertyPriority('outline-offset');
                    el.style.setProperty('outline', '3px solid ' + hColor, 'important');
                    el.style.setProperty('outline-offset', '2px', 'important');
                    function restoreHighlight() {
                        try {
                            if (prevOutline) el.style.setProperty('outline', prevOutline, prevOutlinePr);
                            else el.style.removeProperty('outline');
                            if (prevOffset) el.style.setProperty('outline-offset', prevOffset, prevOffsetPr);
                            else el.style.removeProperty('outline-offset');
                            restoreCss();
                        } catch (e) {}
                    }
                    setTimeout(restoreHighlight, hDuration > 0 ? hDuration : 80);
                } catch (e) {}
            });
            _bhScrollIntoView(hEls[0]);
            return Promise.resolve({ ok: true, selector: hSel, count: hEls.length, durationMs: hDuration });
        } catch (e) {
            return Promise.resolve({ error: String(e) });
        }
    }

    // ── dom_inject_css (S2, page styling) ───────────────────────────────────
    // Append a <style> to the page. Content-script-inserted styles apply to the page
    // DOM (CSS is not world-isolated), so this restyles the live page.
    if (cmd === 'injectCss') {
        var css = String(args.css || '');
        if (!css) return Promise.resolve({ error: 'css required' });
        try {
            var styleEl = document.createElement('style');
            styleEl.setAttribute('data-bh-agent', '1');
            styleEl.textContent = css;
            (document.head || document.documentElement).appendChild(styleEl);
            return Promise.resolve({ ok: true, length: css.length });
        } catch (e) {
            return Promise.resolve({ error: String(e) });
        }
    }

    // ── console_list (S1, read-only) ────────────────────────────────────────
    // Return recent page-world console output captured by the injected hook. Best
    // effort: only logs after the hook installed are available; CSP can block capture.
    if (cmd === 'consoleList') {
        var clLimit = Math.max(1, Math.min(Number(args.limit || 50), 200));
        var clLevel = String(args.level || '').toLowerCase();
        return _bhEnsureConsoleHook().then(function () {
            return _bhEvalInPage('return (window.__bhConsoleBuf||[]).slice();', 3000).then(function (res) {
                if (!res || res.error) {
                    return { error: (res && res.error) || 'console read failed', hint: 'CSP may block page-world console capture' };
                }
                var arr;
                try { arr = JSON.parse(res.value); } catch (_) { arr = []; }
                if (!Array.isArray(arr)) arr = [];
                if (clLevel) arr = arr.filter(function (en) { return en && en.level === clLevel; });
                arr = arr.slice(-clLimit);
                return { ok: true, count: arr.length, entries: arr };
            });
        });
    }

    // ── page_exec (S3/L3) ───────────────────────────────────────────────────
    // Execute arbitrary JS in PAGE world. Gate: native caller approval in APK.
    // wrappedJSObject gives full access to page APIs (fetch, document.cookie, etc.).
    if (cmd === 'evalJS') {
        var code = args.code || '';
        if (!code) return Promise.resolve({ error: 'code required' });
        return _bhEvalInPage(code, args.timeoutMs || 10000);
    }

    // ── plugin_list (S1, read-only) ─────────────────────────────────────────
    // List all built-in plugins with id/name/desc and current enabled state.
    if (cmd === 'pluginList') {
        try {
            var pl = (typeof BH_PLUGINS !== 'undefined' ? BH_PLUGINS : []).map(function (p) {
                return {
                    id: p.id,
                    name: p.name || p.id,
                    desc: p.desc || '',
                    enabled: (typeof isPluginEnabled === 'function') ? isPluginEnabled(p.id) : false,
                };
            });
            return Promise.resolve({ ok: true, count: pl.length, plugins: pl });
        } catch (e) {
            return Promise.resolve({ error: String(e) });
        }
    }

    // ── plugin_get_permissions (S1, read-only) ──────────────────────────────
    // Return metadata for one plugin (id/name/desc/enabled). Plugins are built-in and
    // don't declare granular permissions, so this surfaces the plugin's descriptor.
    if (cmd === 'pluginInfo') {
        var piId = String(args.id || '');
        if (!piId) return Promise.resolve({ error: 'id required' });
        try {
            var pp = (typeof bhPluginById === 'function') ? bhPluginById(piId) : null;
            if (!pp) return Promise.resolve({ error: 'plugin not found: ' + piId });
            return Promise.resolve({
                ok: true,
                id: pp.id,
                name: pp.name || pp.id,
                desc: pp.desc || '',
                enabled: (typeof isPluginEnabled === 'function') ? isPluginEnabled(pp.id) : false,
            });
        } catch (e) {
            return Promise.resolve({ error: String(e) });
        }
    }

    // ── plugin_enable (S2) ──────────────────────────────────────────────────
    if (cmd === 'pluginEnable') {
        var peId = String(args.id || '');
        if (!peId) return Promise.resolve({ error: 'id required' });
        try {
            if (typeof bhPluginById !== 'function' || !bhPluginById(peId)) {
                return Promise.resolve({ error: 'plugin not found: ' + peId });
            }
            if (typeof enablePlugin === 'function') enablePlugin(peId);
            if (typeof refreshExtCards === 'function') { try { refreshExtCards(); } catch (_) {} }
            return Promise.resolve({ ok: true, id: peId, enabled: isPluginEnabled(peId) });
        } catch (e) {
            return Promise.resolve({ error: String(e) });
        }
    }

    // ── plugin_disable (S2) ─────────────────────────────────────────────────
    if (cmd === 'pluginDisable') {
        var pdId = String(args.id || '');
        if (!pdId) return Promise.resolve({ error: 'id required' });
        try {
            if (typeof bhPluginById !== 'function' || !bhPluginById(pdId)) {
                return Promise.resolve({ error: 'plugin not found: ' + pdId });
            }
            if (typeof disablePlugin === 'function') disablePlugin(pdId);
            if (typeof refreshExtCards === 'function') { try { refreshExtCards(); } catch (_) {} }
            return Promise.resolve({ ok: true, id: pdId, enabled: isPluginEnabled(pdId) });
        } catch (e) {
            return Promise.resolve({ error: String(e) });
        }
    }

    return Promise.resolve({ error: 'unknown agentCmd: ' + cmd });
}

// Install the page-world console hook early so console_list can capture from now on.
try { _bhEnsureConsoleHook(); } catch (e) {}
