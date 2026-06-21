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
    // Inject a <script> into the page DOM to run code in page world.
    // Fallback used when wrappedJSObject is unavailable.
    try {
        var s = document.createElement('script');
        s.textContent = code;
        (document.head || document.documentElement).appendChild(s);
        s.remove();
    } catch (e) {}
}

function _bhEvalInPage(code, timeoutMs) {
    // Primary path: use Gecko's wrappedJSObject to call page-world Function()
    // constructor — synchronous, works through Xray, no CSP restriction.
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
        // wrappedJSObject.Function blocked by CSP (e.g. ChatGPT strict policy).
        // Fall through to blob URL injection — treated as external script so it
        // bypasses inline-script / unsafe-eval restrictions on most pages.
    }

    // Fallback A: blob URL injection (external script, bypasses inline-script CSP).
    // Fallback B: inline <script> textContent (last resort, works on permissive pages).
    return new Promise(function (resolve) {
        var evtName = '__bhEval' + (++_bhEvalSeq) + '_' + Date.now();
        var done = false;
        function handler(e) {
            if (done) return;
            done = true;
            resolve(e.detail || { error: 'no detail' });
        }
        document.addEventListener(evtName, handler, { once: true });

        var scriptCode =
            '(function(){try{var __r=(function(){' + code + '})();' +
            'var __s;try{__s=JSON.stringify(__r);}catch(_){__s=String(__r);}' +
            'document.dispatchEvent(new CustomEvent(' + JSON.stringify(evtName) +
            ',{detail:{ok:true,value:__s||"null"}}));' +
            '}catch(e){document.dispatchEvent(new CustomEvent(' + JSON.stringify(evtName) +
            ',{detail:{ok:false,error:String(e)}}));}})()'
        ;

        var injected = false;
        // Fallback A: blob URL
        try {
            var blob = new Blob([scriptCode], { type: 'application/javascript' });
            var blobUrl = URL.createObjectURL(blob);
            var blobScript = document.createElement('script');
            blobScript.src = blobUrl;
            blobScript.onerror = function () {
                URL.revokeObjectURL(blobUrl);
                blobScript.remove();
                // Fallback B: inline textContent
                if (!done) _bhRunInPage(scriptCode);
            };
            blobScript.onload = function () { URL.revokeObjectURL(blobUrl); blobScript.remove(); };
            (document.head || document.documentElement).appendChild(blobScript);
            injected = true;
        } catch (_) {}

        // Fallback B directly if blob injection failed to start
        if (!injected && !done) _bhRunInPage(scriptCode);

        setTimeout(function () {
            if (done) return;
            done = true;
            document.removeEventListener(evtName, handler);
            resolve({ error: 'evalJS timeout — CSP may be blocking all script execution on this page' });
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

    // ── page_search ─────────────────────────────────────────────────────────
    // Text search on the locally-stored source. Returns small snippets (±150 chars).
    if (cmd === 'searchSource') {
        var q = args.query || '';
        if (!q) return Promise.resolve({ error: 'query required' });
        if (!_bhPageSource) return Promise.resolve({
            error: 'no indexed source — call page_index first',
        });
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
            var els = Array.prototype.slice.call(document.querySelectorAll(sel), 0, args.limit || 20);
            var mapped = els.map(function (el) {
                var attrs = {};
                for (var i = 0; i < el.attributes.length; i++) {
                    attrs[el.attributes[i].name] = el.attributes[i].value.substring(0, 200);
                }
                return {
                    tag: el.tagName,
                    id: el.id || undefined,
                    className: el.className || undefined,
                    text: el.textContent ? el.textContent.trim().substring(0, 300) : '',
                    html: el.outerHTML ? el.outerHTML.substring(0, 600) : '',
                    attrs: attrs,
                };
            });
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
            var targets = Array.prototype.slice.call(document.querySelectorAll(writeSel), 0, args.all ? 50 : 1);
            if (!targets.length) return Promise.resolve({ error: 'no elements matched selector' });
            if (cmd === 'setText') {
                targets.forEach(function (el) { el.textContent = String(args.text || ''); });
            } else if (cmd === 'setHTML') {
                targets.forEach(function (el) { el.innerHTML = String(args.html || ''); });
            } else {
                var attrName = String(args.name || '').trim();
                if (!/^[A-Za-z_:][A-Za-z0-9_.:-]*$/.test(attrName)) {
                    return Promise.resolve({ error: 'invalid attribute name' });
                }
                targets.forEach(function (el) { el.setAttribute(attrName, String(args.value || '')); });
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

    // ── page-origin fetch (S3) ─────────────────────────────────────────────
    // Runs fetch in page world, so the request originates from the current page
    // instead of the native app process.
    if (cmd === 'pageFetch') {
        return _bhPageFetch(args);
    }

    // ── page_exec (S3/L3) ───────────────────────────────────────────────────
    // Execute arbitrary JS in PAGE world. Gate: native caller approval in APK.
    // wrappedJSObject gives full access to page APIs (fetch, document.cookie, etc.).
    if (cmd === 'evalJS') {
        var code = args.code || '';
        if (!code) return Promise.resolve({ error: 'code required' });
        return _bhEvalInPage(code, args.timeoutMs || 10000);
    }

    return Promise.resolve({ error: 'unknown agentCmd: ' + cmd });
}
