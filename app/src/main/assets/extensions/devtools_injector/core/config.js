// Network state and bhNetConfig persistence.
// ── 网络拦截层 ───────────────────────────────────────────────────────────────
var netRequests = [];   // 最近 200 条
var netEnabled = true;
var netPanelVisible = false;
var netBreakpoints = []; // [{pattern, id}]
var netMockRules = [];   // [{pattern, status, headers, body}]
var netMockRulesLoaded = false; // storage.local 加载完才向原生下发，避免空集合覆盖持久化
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
      scopeTelemetry: netScopeTelemetry,
      scopeNoise: netScopeNoise,
      scopeCookie: netScopeCookie,
      replaceScope: netReplaceScope,
      filterSuppressResp: netScopeFilterSuppressResp,
      hideTelemetry: netHideTelemetry,
      hideNoise: netHideNoise,
      hideCookie: netHideCookie,
      payloadOnly: netPayloadOnly,
      thisSiteOnly: netThisSiteOnly,
      replaceEnabled: netReplaceEnabled,
      mockRules: netMockRules,
      throttle: netThrottle,
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
          // 迁移：旧 scopeNoise（心跳/遥测合一）→ 遥测+噪音两个新开关。cookie 为新增，默认关。
          if ('scopeTelemetry' in cfg || 'scopeCookie' in cfg) {
            netScopeTelemetry = !!cfg.scopeTelemetry;
            netScopeNoise = !!cfg.scopeNoise;
            netScopeCookie = !!cfg.scopeCookie;
          } else {
            netScopeTelemetry = !!cfg.scopeNoise;
            netScopeNoise = !!cfg.scopeNoise;
            netScopeCookie = false;
          }
        } else {
          // 兼容旧配置：旧版无主开关，req/resp 各自即开即拦
          netInterceptMaster = !!cfg.globalIntercept || !!cfg.globalRespIntercept;
          netScopeReq = !!cfg.globalIntercept;
          netScopeResp = !!cfg.globalRespIntercept;
          netScopeTelemetry = !!cfg.globalInterceptNoise;
          netScopeNoise = !!cfg.globalInterceptNoise;
          netScopeCookie = false;
        }
        if (Array.isArray(cfg.mockRules)) netMockRules = cfg.mockRules;
        if (cfg.throttle && typeof cfg.throttle === 'object') netThrottle = cfg.throttle;
        recomputeIntercept();
        if (cfg.replaceScope === 'req' || cfg.replaceScope === 'resp') netReplaceScope = cfg.replaceScope;
        else netReplaceScope = 'both';
        if ('filterSuppressResp' in cfg) netScopeFilterSuppressResp = !!cfg.filterSuppressResp;
        // 迁移：旧 hideTunnelNoise 拆成遥测+噪音两个开关。
        if (cfg.hideTunnelNoise === true) {
          netHideTelemetry = true;
          netHideNoise = true;
        } else {
          netHideTelemetry = !!cfg.hideTelemetry;
          netHideNoise = !!cfg.hideNoise;
        }
        netHideCookie = !!cfg.hideCookie;
        netPayloadOnly = !!cfg.payloadOnly;
        netThisSiteOnly = !!cfg.thisSiteOnly;
        netReplaceEnabled = !!cfg.replaceEnabled;
      }
      // 配置加载完，向原生下发 Mock / 弱网（即便无 cfg 也标记已加载，允许后续编辑下发）。
      netMockRulesLoaded = true;
      if (typeof pushMockRulesToNative === 'function') pushMockRulesToNative();
      if (typeof pushThrottleToNative === 'function') pushThrottleToNative();
      if (cb) cb();
    }).catch(function () { if (cb) cb(); });
  } catch (e) { if (cb) cb(); }
}

// 已停用 document_start 早期拦截器注入：早期请求由原生 MITM 代理负责抓取（旁路 tee，只读）。
// 保留空函数仅为兼容现有调用点。
function earlyInjectInterceptor() {
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
