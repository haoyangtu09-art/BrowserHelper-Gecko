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
        // Completion-value semantics: a bare trailing expression returns its value (devtools
        // console style), so `document.title` works without an explicit `return`. A top-level
        // `return` is a SyntaxError under direct eval, so fall back to a function-body wrapper.
        // That SyntaxError is parse-time (nothing ran), so the retry can't double-execute.
        var r;
        try {
            // eslint-disable-next-line no-eval
            r = eval(code);
        } catch (e) {
            if (!(e instanceof SyntaxError)) return null;
            // eslint-disable-next-line no-eval
            r = eval('(function(){' + code + '\n})()');
        }
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
            // Page-world eval for completion-value semantics (a bare trailing expression returns
            // its value, like the console), keeping an explicit top-level `return` working via the
            // SyntaxError fallback. eval and Function share the page's unsafe-eval CSP, so if the
            // Function constructor is allowed here, eval is too.
            var fn = new pw.Function('__bhSrc',
                'try{return eval(__bhSrc);}catch(__bhE){' +
                'if(__bhE instanceof SyntaxError){return (new Function(__bhSrc))();}throw __bhE;}');
            var r = fn.call(pw, code);
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

// ── lifecycle guardrail for page_exec ────────────────────────────────────────
// window.open / window.close (and self/top/parent/globalThis variants, dot OR
// bracket member access) leave a wedged about:blank tab / broken engine session
// that white-screens the whole browser (see CLAUDE.md §6). page_exec rejects them
// and steers to the dedicated tab_open / tab_close tools. Best-effort static check:
// it stops accidental misuse, not a determined obfuscator (fine — goal is safety,
// not sandboxing a hostile model). Custom objects' .open()/.close() are NOT matched.
var _bhLifecycleDotRe = /(?:^|[^.\w$])(?:window|self|top|parent|globalThis)\s*\.\s*(?:open|close)\s*\(/;
var _bhLifecycleIdxRe = /(?:^|[^.\w$])(?:window|self|top|parent|globalThis)\s*\[\s*(['"])(?:open|close)\1\s*\]\s*\(/;
function _bhBlockedLifecycleApi(code) {
    try {
        var s = String(code || '');
        return _bhLifecycleDotRe.test(s) || _bhLifecycleIdxRe.test(s);
    } catch (_) {
        return false;
    }
}

// localStorage/sessionStorage of the PAGE origin. Gecko content scripts share the
// page's DOM storage, so this reads/writes the real page store from the isolated
// world — CSP never governs storage access, so this is inherently CSP-immune.
function _bhStore(area) {
    return (String(area || 'local').toLowerCase() === 'session') ? window.sessionStorage : window.localStorage;
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

    // ── page_get (S1, read-only) ────────────────────────────────────────────
    // Read a single property/text/value/attribute off a matched element. No JS eval.
    if (cmd === 'getProp') {
        var gpSel = String(args.selector || '');
        if (!gpSel) return Promise.resolve({ error: 'selector required' });
        try {
            var gpEl = _bhQueryOne(gpSel);
            if (!gpEl) return Promise.resolve({ error: 'no elements matched selector', selector: gpSel });
            var gpWhat = String(args.prop || 'text').toLowerCase();
            var gpVal;
            if (gpWhat === 'text') gpVal = gpEl.textContent || '';
            else if (gpWhat === 'value') gpVal = ('value' in gpEl) ? gpEl.value : '';
            else if (gpWhat === 'html') gpVal = gpEl.innerHTML || '';
            else if (gpWhat === 'outerhtml') gpVal = gpEl.outerHTML || '';
            else if (gpWhat === 'href') gpVal = gpEl.href || gpEl.getAttribute('href') || '';
            else if (gpWhat === 'attr') {
                var gpAttr = String(args.attr || '');
                if (!gpAttr) return Promise.resolve({ error: 'attr required when prop="attr"' });
                gpVal = gpEl.getAttribute(gpAttr);
            } else if (gpWhat === 'checked') gpVal = !!gpEl.checked;
            else gpVal = gpEl.getAttribute(gpWhat);
            if (typeof gpVal === 'string') gpVal = gpVal.substring(0, 20000);
            return Promise.resolve({ ok: true, selector: gpSel, prop: gpWhat, value: gpVal, tag: gpEl.tagName });
        } catch (e) {
            return Promise.resolve({ error: String(e) });
        }
    }

    // ── page_info (S1, read-only) ───────────────────────────────────────────
    // Page-level metadata: url/title/referrer/charset/readyState/scroll/viewport.
    if (cmd === 'pageInfo') {
        try {
            return Promise.resolve({
                ok: true,
                url: location.href,
                origin: location.origin,
                title: document.title || '',
                referrer: document.referrer || '',
                charset: document.characterSet || '',
                readyState: document.readyState || '',
                scrollX: Math.round(window.scrollX || 0),
                scrollY: Math.round(window.scrollY || 0),
                scrollWidth: document.documentElement ? document.documentElement.scrollWidth : 0,
                scrollHeight: document.documentElement ? document.documentElement.scrollHeight : 0,
                innerWidth: window.innerWidth,
                innerHeight: window.innerHeight,
            });
        } catch (e) {
            return Promise.resolve({ error: String(e) });
        }
    }

    // ── browser_info (S1, read-only) ────────────────────────────────────────
    // navigator/screen environment info. No JS eval; just reads well-known props.
    if (cmd === 'browserInfo') {
        try {
            var nav = window.navigator || {};
            return Promise.resolve({
                ok: true,
                userAgent: nav.userAgent || '',
                platform: nav.platform || '',
                language: nav.language || '',
                languages: nav.languages ? Array.prototype.slice.call(nav.languages) : [],
                online: !!nav.onLine,
                cookieEnabled: !!nav.cookieEnabled,
                hardwareConcurrency: nav.hardwareConcurrency || 0,
                deviceMemory: nav.deviceMemory || 0,
                screenWidth: window.screen ? window.screen.width : 0,
                screenHeight: window.screen ? window.screen.height : 0,
                devicePixelRatio: window.devicePixelRatio || 1,
            });
        } catch (e) {
            return Promise.resolve({ error: String(e) });
        }
    }

    // ── storage_get (S1, read-only) ─────────────────────────────────────────
    // Read one key from local/session storage of the page origin (CSP-immune).
    if (cmd === 'storageGet') {
        var sgKey = String(args.key || '');
        if (!sgKey) return Promise.resolve({ error: 'key required' });
        try {
            return Promise.resolve({ ok: true, area: String(args.area || 'local'), key: sgKey, value: _bhStore(args.area).getItem(sgKey) });
        } catch (e) {
            return Promise.resolve({ error: String(e) });
        }
    }

    // ── storage_keys (S1, read-only) ────────────────────────────────────────
    // List all keys (and optionally values) in local/session storage.
    if (cmd === 'storageKeys') {
        try {
            var skStore = _bhStore(args.area);
            var skKeys = [];
            for (var ski = 0; ski < skStore.length; ski++) skKeys.push(skStore.key(ski));
            var skOut = { ok: true, area: String(args.area || 'local'), count: skKeys.length, keys: skKeys };
            if (args.withValues) {
                var skVals = {};
                skKeys.forEach(function (k) { try { skVals[k] = skStore.getItem(k); } catch (_) {} });
                skOut.values = skVals;
            }
            return Promise.resolve(skOut);
        } catch (e) {
            return Promise.resolve({ error: String(e) });
        }
    }

    // ── storage_set (S2) ────────────────────────────────────────────────────
    if (cmd === 'storageSet') {
        var ssKey = String(args.key || '');
        if (!ssKey) return Promise.resolve({ error: 'key required' });
        try {
            _bhStore(args.area).setItem(ssKey, String(args.value == null ? '' : args.value));
            return Promise.resolve({ ok: true, area: String(args.area || 'local'), key: ssKey });
        } catch (e) {
            return Promise.resolve({ error: String(e) });
        }
    }

    // ── storage_remove (S2) ─────────────────────────────────────────────────
    if (cmd === 'storageRemove') {
        var srKey = String(args.key || '');
        if (!srKey) return Promise.resolve({ error: 'key required' });
        try {
            _bhStore(args.area).removeItem(srKey);
            return Promise.resolve({ ok: true, area: String(args.area || 'local'), key: srKey });
        } catch (e) {
            return Promise.resolve({ error: String(e) });
        }
    }

    // ── storage_clear (S2) ──────────────────────────────────────────────────
    if (cmd === 'storageClear') {
        try {
            var scStore = _bhStore(args.area);
            var scCount = scStore.length;
            scStore.clear();
            return Promise.resolve({ ok: true, area: String(args.area || 'local'), cleared: scCount });
        } catch (e) {
            return Promise.resolve({ error: String(e) });
        }
    }

    // ── page_cookie_get (S1, read-only) ─────────────────────────────────────
    // Read document.cookie (non-HttpOnly cookies of the page origin). CSP-immune.
    if (cmd === 'cookieGet') {
        try {
            var ckRaw = document.cookie || '';
            var ckList = [];
            ckRaw.split(';').forEach(function (pair) {
                var idx = pair.indexOf('=');
                if (idx < 0) return;
                var name = pair.slice(0, idx).trim();
                if (!name) return;
                ckList.push({ name: name, value: pair.slice(idx + 1).trim() });
            });
            var ckName = String(args.name || '');
            if (ckName) {
                var hit = ckList.filter(function (c) { return c.name === ckName; })[0];
                return Promise.resolve({ ok: true, name: ckName, value: hit ? hit.value : null, found: !!hit });
            }
            return Promise.resolve({ ok: true, count: ckList.length, cookies: ckList });
        } catch (e) {
            return Promise.resolve({ error: String(e) });
        }
    }

    // ── page_cookie_set (S2) ────────────────────────────────────────────────
    // Set a cookie via document.cookie. Cannot set HttpOnly; honours path/maxAge/etc.
    if (cmd === 'cookieSet') {
        var csName = String(args.name || '');
        if (!csName) return Promise.resolve({ error: 'name required' });
        try {
            var csStr = encodeURIComponent(csName) + '=' + encodeURIComponent(String(args.value == null ? '' : args.value));
            csStr += '; path=' + (args.path ? String(args.path) : '/');
            if (args.domain) csStr += '; domain=' + String(args.domain);
            if (args.maxAge != null) csStr += '; max-age=' + Number(args.maxAge);
            if (args.expires) csStr += '; expires=' + String(args.expires);
            if (args.secure) csStr += '; secure';
            if (args.sameSite) csStr += '; samesite=' + String(args.sameSite);
            document.cookie = csStr;
            return Promise.resolve({ ok: true, name: csName });
        } catch (e) {
            return Promise.resolve({ error: String(e) });
        }
    }

    // ── page_select_option (S2) ─────────────────────────────────────────────
    // Select an <option> in a <select> by value/label/index, fire input+change.
    if (cmd === 'selectOption') {
        var soSel = String(args.selector || '');
        if (!soSel) return Promise.resolve({ error: 'selector required' });
        try {
            var soEl = _bhQueryOne(soSel);
            if (!soEl) return Promise.resolve({ error: 'no elements matched selector', selector: soSel });
            if ((soEl.tagName || '').toLowerCase() !== 'select') {
                return Promise.resolve({ error: 'element is not a <select>', tag: soEl.tagName });
            }
            var soOpts = Array.prototype.slice.call(soEl.options || []);
            var soIdx = -1;
            if (args.index != null) soIdx = Number(args.index);
            else if (args.value != null) {
                var sv = String(args.value);
                for (var i = 0; i < soOpts.length; i++) { if (soOpts[i].value === sv) { soIdx = i; break; } }
            } else if (args.label != null) {
                var sl = String(args.label);
                for (var j = 0; j < soOpts.length; j++) { if ((soOpts[j].textContent || '').trim() === sl) { soIdx = j; break; } }
            }
            if (soIdx < 0 || soIdx >= soOpts.length) {
                return Promise.resolve({ error: 'no matching option', selector: soSel });
            }
            soEl.selectedIndex = soIdx;
            try { soEl.dispatchEvent(new Event('input', { bubbles: true })); } catch (_) {}
            try { soEl.dispatchEvent(new Event('change', { bubbles: true })); } catch (_) {}
            return Promise.resolve({ ok: true, selector: soSel, index: soIdx, value: soOpts[soIdx].value, label: (soOpts[soIdx].textContent || '').trim() });
        } catch (e) {
            return Promise.resolve({ error: String(e) });
        }
    }

    // ── page_set_checked (S2) ───────────────────────────────────────────────
    // Set a checkbox/radio checked state, fire click+input+change.
    if (cmd === 'setChecked') {
        var scSel = String(args.selector || '');
        if (!scSel) return Promise.resolve({ error: 'selector required' });
        try {
            var scEl = _bhQueryOne(scSel);
            if (!scEl) return Promise.resolve({ error: 'no elements matched selector', selector: scSel });
            if (!('checked' in scEl)) return Promise.resolve({ error: 'element has no checked state', tag: scEl.tagName });
            var scWant = (args.checked == null) ? true : !!args.checked;
            if (scEl.checked !== scWant) {
                scEl.checked = scWant;
                try { scEl.dispatchEvent(new Event('input', { bubbles: true })); } catch (_) {}
                try { scEl.dispatchEvent(new Event('change', { bubbles: true })); } catch (_) {}
            }
            return Promise.resolve({ ok: true, selector: scSel, checked: scEl.checked });
        } catch (e) {
            return Promise.resolve({ error: String(e) });
        }
    }

    // ── page_submit (S2) ────────────────────────────────────────────────────
    // Submit a <form> (the form itself, or the form an element belongs to).
    if (cmd === 'submitForm') {
        var sfSel = String(args.selector || '');
        if (!sfSel) return Promise.resolve({ error: 'selector required' });
        try {
            var sfEl = _bhQueryOne(sfSel);
            if (!sfEl) return Promise.resolve({ error: 'no elements matched selector', selector: sfSel });
            var sfForm = ((sfEl.tagName || '').toLowerCase() === 'form') ? sfEl : sfEl.form || sfEl.closest('form');
            if (!sfForm) return Promise.resolve({ error: 'no form found for selector', selector: sfSel });
            var sfOk = sfForm.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
            if (sfOk) { try { sfForm.submit(); } catch (_) {} }
            return Promise.resolve({ ok: true, selector: sfSel, defaultPrevented: !sfOk });
        } catch (e) {
            return Promise.resolve({ error: String(e) });
        }
    }

    // ── page_focus (S2) ─────────────────────────────────────────────────────
    if (cmd === 'focusEl') {
        var fcSel = String(args.selector || '');
        if (!fcSel) return Promise.resolve({ error: 'selector required' });
        try {
            var fcEl = _bhQueryOne(fcSel);
            if (!fcEl) return Promise.resolve({ error: 'no elements matched selector', selector: fcSel });
            _bhScrollIntoView(fcEl);
            try { fcEl.focus(); } catch (_) {}
            return Promise.resolve({ ok: true, selector: fcSel, focused: document.activeElement === fcEl, tag: fcEl.tagName });
        } catch (e) {
            return Promise.resolve({ error: String(e) });
        }
    }

    // ── page_press_key (S2) ─────────────────────────────────────────────────
    // Dispatch keydown+keypress+keyup on a target (or activeElement). Bounded synthetic
    // keyboard event — not arbitrary JS.
    if (cmd === 'pressKey') {
        var pkKey = String(args.key || '');
        if (!pkKey) return Promise.resolve({ error: 'key required' });
        try {
            var pkEl = args.selector ? _bhQueryOne(String(args.selector)) : (document.activeElement || document.body);
            if (!pkEl) return Promise.resolve({ error: 'no target element', selector: args.selector || '' });
            var pkInit = {
                bubbles: true,
                cancelable: true,
                key: pkKey,
                code: String(args.code || pkKey),
                keyCode: Number(args.keyCode || 0),
                which: Number(args.keyCode || 0),
                ctrlKey: !!args.ctrl,
                shiftKey: !!args.shift,
                altKey: !!args.alt,
                metaKey: !!args.meta,
            };
            try { pkEl.dispatchEvent(new KeyboardEvent('keydown', pkInit)); } catch (_) {}
            try { pkEl.dispatchEvent(new KeyboardEvent('keypress', pkInit)); } catch (_) {}
            try { pkEl.dispatchEvent(new KeyboardEvent('keyup', pkInit)); } catch (_) {}
            return Promise.resolve({ ok: true, key: pkKey, tag: pkEl.tagName });
        } catch (e) {
            return Promise.resolve({ error: String(e) });
        }
    }

    // ── page_dispatch_event (S2) ────────────────────────────────────────────
    // Dispatch a generic DOM event by name on a matched element. Bounded — no JS eval.
    if (cmd === 'dispatchEvent') {
        var deSel = String(args.selector || '');
        var deType = String(args.type || '');
        if (!deSel) return Promise.resolve({ error: 'selector required' });
        if (!deType) return Promise.resolve({ error: 'type required' });
        try {
            var deEl = _bhQueryOne(deSel);
            if (!deEl) return Promise.resolve({ error: 'no elements matched selector', selector: deSel });
            var deEv;
            try { deEv = new Event(deType, { bubbles: args.bubbles !== false, cancelable: args.cancelable !== false }); }
            catch (_) { deEv = document.createEvent('Event'); deEv.initEvent(deType, args.bubbles !== false, args.cancelable !== false); }
            var deOk = deEl.dispatchEvent(deEv);
            return Promise.resolve({ ok: true, selector: deSel, type: deType, defaultPrevented: !deOk });
        } catch (e) {
            return Promise.resolve({ error: String(e) });
        }
    }

    // ── page_exec (S3/L3) ───────────────────────────────────────────────────
    // Execute arbitrary JS in PAGE world. Gate: native caller approval in APK.
    // wrappedJSObject gives full access to page APIs (fetch, document.cookie, etc.).
    if (cmd === 'evalJS') {
        var code = args.code || '';
        if (!code) return Promise.resolve({ error: 'code required' });
        if (_bhBlockedLifecycleApi(code)) {
            return Promise.resolve({
                error: 'window.open / window.close 已被禁用：它们会留下卡死的 about:blank 标签并导致整个浏览器白屏。请改用 tab_open / tab_close 工具来开关标签页。',
                blocked: 'window.open/close',
            });
        }
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
