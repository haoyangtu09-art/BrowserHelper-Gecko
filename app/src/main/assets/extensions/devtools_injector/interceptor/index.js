// Interceptor entry points and page-world flag synchronization.
// 页面内 fetch/XHR 拦截器已删除（改用原生 MITM 代理只读抓包）。本文件仅保留
// 仍被工具栏/配置引用的标志同步函数；INTERCEPT_JS / 页面 hook 注入已不存在。

var PLAIN_PROBE_JS = (function () {
  return [
    '(function(){',
    '  if(window.__bhPlainProbeInstalled)return;',
    '  window.__bhPlainProbeInstalled=true;',
    '  window.__bhPlainProbeEnabled=!!window.__bhPlainProbeEnabled;',
    '  var MAX=65536;',
    '  function send(data){try{window.postMessage(Object.assign({__bhNet:true,type:"plain"},data),"*");}catch(e){}}',
    '  function textFrom(v){',
    '    if(v==null)return null;',
    '    if(typeof v==="string")return v;',
    '    try{',
    '      if(v instanceof ArrayBuffer){return new TextDecoder("utf-8",{fatal:false}).decode(new Uint8Array(v));}',
    '      if(ArrayBuffer.isView&&ArrayBuffer.isView(v)){return new TextDecoder("utf-8",{fatal:false}).decode(new Uint8Array(v.buffer,v.byteOffset,v.byteLength));}',
    '    }catch(e){}',
    '    return null;',
    '  }',
    '  function printableRatio(s){',
    '    if(!s)return 0;',
    '    var ok=0,n=Math.min(s.length,2048);',
    '    for(var i=0;i<n;i++){var c=s.charCodeAt(i);if(c===9||c===10||c===13||c>=32)ok++;}',
    '    return n?ok/n:0;',
    '  }',
    '  function meaningful(s,source){',
    '    if(!s||s.length<6)return false;',
    '    if(printableRatio(s)<0.75)return false;',
    '    if(source==="crypto.encrypt")return true;',
    '    return /[\\{\\[]|prompt|message|messages|content|conversation|query|variables|graphql|model|token|auth|session|chat/i.test(s);',
    '  }',
    '  function capture(source,value,meta){',
    '    if(!window.__bhPlainProbeEnabled)return;',
    '    var text=textFrom(value);',
    '    if(!meaningful(text,source))return;',
    '    send({source:source,plainText:text.slice(0,MAX),plainSize:text.length,meta:meta||{},ts:Date.now()});',
    '  }',
    '  try{',
    '    var _stringify=JSON.stringify;',
    '    JSON.stringify=function(){',
    '      var out=_stringify.apply(this,arguments);',
    '      capture("JSON.stringify",out,{});',
    '      return out;',
    '    };',
    '  }catch(e){}',
    '  try{',
    '    var _encode=TextEncoder&&TextEncoder.prototype&&TextEncoder.prototype.encode;',
    '    if(_encode){TextEncoder.prototype.encode=function(input){capture("TextEncoder.encode",input,{});return _encode.apply(this,arguments);};}',
    '  }catch(e){}',
    '  try{',
    '    var subtle=crypto&&crypto.subtle;',
    '    var _encrypt=subtle&&subtle.encrypt;',
    '    if(_encrypt){subtle.encrypt=function(alg,key,data){var name=(alg&&alg.name)||String(alg||"");capture("crypto.encrypt",data,{algorithm:name});return _encrypt.apply(this,arguments);};}',
    '  }catch(e){}',
    '  try{',
    '    var _wsSend=WebSocket&&WebSocket.prototype&&WebSocket.prototype.send;',
    '    if(_wsSend){WebSocket.prototype.send=function(data){capture("WebSocket.send",data,{});return _wsSend.apply(this,arguments);};}',
    '  }catch(e){}',
    '})();',
  ].join('\n');
}());

function injectInterceptor() {
  // 已停用页面内 fetch/XHR 拦截器：抓包数据改由原生 MITM 代理旁路 tee 到面板（只读）。
  // 保留此空函数仅为兼容现有调用点，不再注入任何 page-world hook。
}

function syncPlainProbeEnabled() {
  if (erudaMode !== 'page' && typeof window.wrappedJSObject !== 'undefined') {
    try { window.wrappedJSObject.__bhPlainProbeEnabled = !!netPlainProbeEnabled; return; } catch (e) {}
  }
  runInPage('(function(){window.__bhPlainProbeEnabled=' + (netPlainProbeEnabled ? 'true' : 'false') + ';})();');
}

	  function syncGlobalInterceptEnabled() {
	    if (erudaMode !== 'page' && typeof window.wrappedJSObject !== 'undefined') {
	      try { window.wrappedJSObject.__bhGlobalInterceptEnabled = !!netGlobalInterceptEnabled; return; } catch (e) {}
	    }
	    runInPage('(function(){window.__bhGlobalInterceptEnabled=' + (netGlobalInterceptEnabled ? 'true' : 'false') + ';})();');
	  }

	  function syncGlobalRespInterceptEnabled() {
	    if (erudaMode !== 'page' && typeof window.wrappedJSObject !== 'undefined') {
	      try { window.wrappedJSObject.__bhGlobalRespInterceptEnabled = !!netGlobalRespInterceptEnabled; return; } catch (e) {}
	    }
	    runInPage('(function(){window.__bhGlobalRespInterceptEnabled=' + (netGlobalRespInterceptEnabled ? 'true' : 'false') + ';})();');
	  }

	  function syncGlobalInterceptNoise() {
	    if (erudaMode !== 'page' && typeof window.wrappedJSObject !== 'undefined') {
	      try { window.wrappedJSObject.__bhGlobalInterceptNoise = !!netGlobalInterceptNoise; return; } catch (e) {}
	    }
	    runInPage('(function(){window.__bhGlobalInterceptNoise=' + (netGlobalInterceptNoise ? 'true' : 'false') + ';})();');
	  }

function syncReplaceScope() {
  if (erudaMode !== 'page' && typeof window.wrappedJSObject !== 'undefined') {
    try { window.wrappedJSObject.__bhReplaceScope = netReplaceScope; return; } catch (e) {}
  }
  runInPage('(function(){window.__bhReplaceScope=' + JSON.stringify(netReplaceScope) + ';})();');
}

function syncFilterSuppressResp() {
  if (erudaMode !== 'page' && typeof window.wrappedJSObject !== 'undefined') {
    try { window.wrappedJSObject.__bhFilterSuppressResp = !!netScopeFilterSuppressResp; return; } catch (e) {}
  }
  runInPage('(function(){window.__bhFilterSuppressResp=' + (netScopeFilterSuppressResp ? 'true' : 'false') + ';})();');
}

	  // 由主开关 + 作用域重新计算生效标志，并同步到拦截器（page/isolated 两世界）
	  // 批量同步所有拦截相关旗标，避免多次 runInPage 造成拦截按钮卡顿
	  function recomputeIntercept() {
	    netGlobalInterceptEnabled = netInterceptMaster && netScopeReq;
	    netGlobalRespInterceptEnabled = netInterceptMaster && netScopeResp;
	    netGlobalInterceptNoise = netScopeNoise;
	    if (erudaMode !== 'page' && typeof window.wrappedJSObject !== 'undefined') {
	      try {
	        var pw = window.wrappedJSObject;
	        pw.__bhGlobalInterceptEnabled = !!netGlobalInterceptEnabled;
	        pw.__bhGlobalRespInterceptEnabled = !!netGlobalRespInterceptEnabled;
	        pw.__bhGlobalInterceptNoise = !!netGlobalInterceptNoise;
	        pw.__bhFilterSuppressResp = !!netScopeFilterSuppressResp;
	        return;
	      } catch (e) {}
	    }
	    runInPage('(function(){window.__bhGlobalInterceptEnabled=' + (netGlobalInterceptEnabled ? 'true' : 'false') +
	      ';window.__bhGlobalRespInterceptEnabled=' + (netGlobalRespInterceptEnabled ? 'true' : 'false') +
	      ';window.__bhGlobalInterceptNoise=' + (netGlobalInterceptNoise ? 'true' : 'false') +
	      ';window.__bhFilterSuppressResp=' + (netScopeFilterSuppressResp ? 'true' : 'false') + ';})();');
	  }

	  function syncReplaceRules() {
	    var active = netReplaceEnabled ? netReplaceRules.filter(function (r) { return r.enabled; }) : [];
	    if (erudaMode !== 'page' && typeof window.wrappedJSObject !== 'undefined' && typeof cloneInto !== 'undefined') {
	      try { window.wrappedJSObject.__bhReplaceRules = cloneInto(active, window); return; } catch (e) {}
	    }
	    runInPage('(function(){window.__bhReplaceRules=' + JSON.stringify(active) + ';})();');
	  }

	  function disableInterceptor() {
	    if (erudaMode !== 'page' && typeof window.wrappedJSObject !== 'undefined') {
	      try {
	        var pw = window.wrappedJSObject;
	        pw.__bhNetInstalled = false;
	        if (pw.__bhRestoreFetch) pw.fetch = pw.__bhRestoreFetch;
	        if (pw.__bhRestoreXHR) pw.XMLHttpRequest = pw.__bhRestoreXHR;
	        return;
	      } catch (e) {}
	    }
	    runInPage('(function(){window.__bhNetInstalled=false;if(window.__bhRestoreFetch){window.fetch=window.__bhRestoreFetch;}if(window.__bhRestoreXHR){window.XMLHttpRequest=window.__bhRestoreXHR;}})();');
	  }

	  function setNetworkCaptureEnabled(enabled) {
	    netEnabled = !!enabled;
	    if (netEnableBtn) netEnableBtn.textContent = netEnabled ? '● 监听中' : '○ 已停止';
	    if (!netEnabled) {
	      releaseAllIntercepts();
	      disableInterceptor();
	      return;
	    }
	    injectInterceptor();
	    syncGlobalInterceptEnabled();
	    syncGlobalRespInterceptEnabled();
	    syncGlobalInterceptNoise();
	    syncReplaceScope();
	    syncFilterSuppressResp();
	    syncInterceptRules();
	    injectBreakpoints();
	    injectMockRules();
	    applyThrottle(netThrottle);
	    injectPlainProbe();
	  }

	  function injectPlainProbe() {
  // 已停用页面内明文探针（JSON.stringify / crypto.encrypt 等 hook）：只读代理模式不注入。
  // 保留空函数仅为兼容现有调用点。
}
