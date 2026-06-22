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
      scopeHeartbeat: netScopeHeartbeat,
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
      scopeTelemetry: netScopeTelemetry,
      scopeHeartbeat: netScopeHeartbeat,
      scopeNoise: netScopeNoise,
      scopeCookie: netScopeCookie,
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
        // 原生（ProxyProbe）是拦截开关/作用域的唯一权威：只要本会话已收到过一次
        // interceptState（含 Agent 调 intercept_set 触发的推送），就绝不用 storage 里
        // 的旧拦截配置回写主开关/作用域，否则 storage 的「关」会把 Agent 刚打开的拦截
        // 在前端显示成关闭，造成「没开拦截却在拦响应」的前后端不同步。
        if (!netNativeInterceptStateReceived) {
          if ('interceptMaster' in cfg || 'scopeReq' in cfg) {
            netInterceptMaster = !!cfg.interceptMaster;
            netScopeReq = 'scopeReq' in cfg ? !!cfg.scopeReq : true;
            netScopeResp = !!cfg.scopeResp;
            // 迁移：旧 scopeNoise（心跳/遥测合一）→ 遥测+心跳+噪音三个新开关。cookie 为新增，默认关。
            if ('scopeTelemetry' in cfg || 'scopeHeartbeat' in cfg || 'scopeCookie' in cfg) {
              netScopeTelemetry = !!cfg.scopeTelemetry;
              netScopeHeartbeat = !!cfg.scopeHeartbeat;
              netScopeNoise = !!cfg.scopeNoise;
              netScopeCookie = !!cfg.scopeCookie;
            } else {
              netScopeTelemetry = !!cfg.scopeNoise;
              netScopeHeartbeat = !!cfg.scopeNoise;
              netScopeNoise = !!cfg.scopeNoise;
              netScopeCookie = false;
            }
          } else {
            // 兼容旧配置：旧版无主开关，req/resp 各自即开即拦
            netInterceptMaster = !!cfg.globalIntercept || !!cfg.globalRespIntercept;
            netScopeReq = !!cfg.globalIntercept;
            netScopeResp = !!cfg.globalRespIntercept;
            netScopeTelemetry = !!cfg.globalInterceptNoise;
            netScopeHeartbeat = !!cfg.globalInterceptNoise;
            netScopeNoise = !!cfg.globalInterceptNoise;
            netScopeCookie = false;
          }
        }
        if (Array.isArray(cfg.mockRules)) netMockRules = cfg.mockRules;
        if (cfg.throttle && typeof cfg.throttle === 'object') netThrottle = cfg.throttle;
        recomputeIntercept(true);
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
    // 同 loadNetConfig：原生已推送过拦截状态时不让 sessionStorage 旧快照回写主开关/作用域。
    if ('interceptMaster' in cfg && !netNativeInterceptStateReceived) {
      netInterceptMaster = !!cfg.interceptMaster;
      netScopeReq = 'scopeReq' in cfg ? !!cfg.scopeReq : true;
      netScopeResp = !!cfg.scopeResp;
      netScopeTelemetry = !!cfg.scopeTelemetry;
      netScopeHeartbeat = !!cfg.scopeHeartbeat;
      netScopeNoise = !!cfg.scopeNoise;
      netScopeCookie = !!cfg.scopeCookie;
    }
    recomputeIntercept(true);
    if ('filterSuppressResp' in cfg) netScopeFilterSuppressResp = !!cfg.filterSuppressResp;
    if (cfg.replaceScope === 'req' || cfg.replaceScope === 'resp') netReplaceScope = cfg.replaceScope;
    else netReplaceScope = 'both';
  } catch (e) {}
}
