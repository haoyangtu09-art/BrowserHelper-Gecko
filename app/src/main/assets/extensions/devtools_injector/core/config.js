// Network state and bhNetConfig persistence.
// ── 网络拦截层 ───────────────────────────────────────────────────────────────
var netRequests = [];   // 最近 200 条
var netEnabled = true;
var netPanelVisible = false;
var netBreakpoints = []; // [{pattern, id}]
var netMockRules = [];   // [{pattern, status, headers, body}]
var netThrottle = { enabled: false, latencyMs: 0, kbps: 0 };
var netPendingBreaks = {}; // reqId -> {resolve, reject, req}

var NET_CONFIG_KEY = 'bhNetConfig';

function saveNetConfig() {
  var st = storageLocal();
  if (!st || !st.set) return;
  try {
    st.set({ bhNetConfig: {
      interceptMaster: netInterceptMaster,
      scopeReq: netScopeReq,
      scopeResp: netScopeResp,
      scopeNoise: netScopeNoise,
      replaceScope: netReplaceScope,
      filterSuppressResp: netScopeFilterSuppressResp,
      plainProbe: netPlainProbeEnabled,
      hideTunnelNoise: netHideTunnelNoise,
      payloadOnly: netPayloadOnly,
      replaceEnabled: netReplaceEnabled,
    }}).catch(function () {});
  } catch (e) {}
  // 同步写入 sessionStorage 快照，供导航后 document_start 阶段即时恢复（不等 storage.local 异步）
  try {
    sessionStorage.setItem('__bhNetConfigCache', JSON.stringify({
      interceptMaster: netInterceptMaster,
      scopeReq: netScopeReq,
      scopeResp: netScopeResp,
      scopeNoise: netScopeNoise,
      filterSuppressResp: netScopeFilterSuppressResp,
      replaceScope: netReplaceScope,
    }));
  } catch (e) {}
  // 同步配置到原生代理
  if (typeof proxySendConfig === 'function') proxySendConfig();
}

function loadNetConfig(cb) {
  var st = storageLocal();
  if (!st || !st.get) { if (cb) cb(); return; }
  try {
    st.get(NET_CONFIG_KEY).then(function (res) {
      var cfg = res && res[NET_CONFIG_KEY];
      if (cfg) {
        if ('interceptMaster' in cfg || 'scopeReq' in cfg) {
          netInterceptMaster = !!cfg.interceptMaster;
          netScopeReq = 'scopeReq' in cfg ? !!cfg.scopeReq : true;
          netScopeResp = !!cfg.scopeResp;
          netScopeNoise = !!cfg.scopeNoise;
        } else {
          // 兼容旧配置：旧版无主开关，req/resp 各自即开即拦
          netInterceptMaster = !!cfg.globalIntercept || !!cfg.globalRespIntercept;
          netScopeReq = !!cfg.globalIntercept;
          netScopeResp = !!cfg.globalRespIntercept;
          netScopeNoise = !!cfg.globalInterceptNoise;
        }
        recomputeIntercept();
        if (cfg.replaceScope === 'req' || cfg.replaceScope === 'resp') netReplaceScope = cfg.replaceScope;
        else netReplaceScope = 'both';
        if ('filterSuppressResp' in cfg) netScopeFilterSuppressResp = !!cfg.filterSuppressResp;
        netPlainProbeEnabled = !!cfg.plainProbe;
        netHideTunnelNoise = !!cfg.hideTunnelNoise;
        netPayloadOnly = !!cfg.payloadOnly;
        netReplaceEnabled = !!cfg.replaceEnabled;
      }
      if (cb) cb();
    }).catch(function () { if (cb) cb(); });
  } catch (e) { if (cb) cb(); }
}

// Phase 1+：页内拦截已废弃，由原生 MITM 代理处理
function recomputeIntercept() {
  // 重新计算拦截标志，同步到代理
  // （旧逻辑是同步到 page world，现在改为同步到代理）
  if (typeof proxySendConfig === 'function') {
    proxySendConfig();
  }
}

function syncReplaceRules() {
  // Phase 1+：替换规则由代理处理，同步到代理
  if (typeof proxyOnConfigChanged === 'function') {
    proxyOnConfigChanged();
  }
}

function earlyInjectInterceptor() {
  // NOOP - 拦截由代理在网络层处理
}

// 从 sessionStorage 快照同步恢复拦截配置，用于 document_start 阶段即时注入（无需等 storage.local）
function loadNetConfigFromCache() {
  try {
    var cfg = JSON.parse(sessionStorage.getItem('__bhNetConfigCache') || 'null');
    if (!cfg) return;
    if ('interceptMaster' in cfg) {
      netInterceptMaster = !!cfg.interceptMaster;
      netScopeReq = 'scopeReq' in cfg ? !!cfg.scopeReq : true;
      netScopeResp = !!cfg.scopeResp;
      netScopeNoise = !!cfg.scopeNoise;
    }
    recomputeIntercept();
    if ('filterSuppressResp' in cfg) netScopeFilterSuppressResp = !!cfg.filterSuppressResp;
    if (cfg.replaceScope === 'req' || cfg.replaceScope === 'resp') netReplaceScope = cfg.replaceScope;
    else netReplaceScope = 'both';
  } catch (e) {}
}
