// Entry script. Module files are loaded before this file by manifest.json.
(function () {
connect();
proxyFeedInit();  // 接收原生 MITM 代理的解密流量，旁路显示到面板（不影响转发）

// 页面导航后自动恢复：
//   阶段 1（document_start，同步）：从 sessionStorage 快照即时恢复拦截/过滤标志。
//     （页面世界拦截器已删除，抓包由原生 MITM 代理负责；earlyInjectInterceptor 为 no-op。）
//   阶段 2（异步）：加载真实配置、下发替换规则、释放早期挂起的拦截。
//   阶段 3（load 事件后视口稳定）：restoreUiSoon → toggle() 一次性 loadPageEruda+initPageEruda
//     构建 Eruda UI。⚠️ 关键：不在 load 期间提前求值 eruda bundle（详见阶段 2 内说明）。
if (wasActive()) {
  // ─ 阶段 1：同步快速恢复（document_start 即时生效）
  loadNetConfigFromCache();
  syncGlobalInterceptEnabled();
  syncGlobalRespInterceptEnabled();
  syncGlobalInterceptNoise();
  syncFilterSuppressResp();
  earlyInjectInterceptor(); // no-op（兼容保留；抓包已由原生 MITM 代理负责）

  // ─ 阶段 2：异步加载真实配置
  loadNetConfig(function () {
    loadReplaceRules();
    // 更新标志（真实配置可能与快照略有不同）
    syncGlobalInterceptEnabled();
    syncGlobalRespInterceptEnabled();
    syncGlobalInterceptNoise();
    syncFilterSuppressResp();
    pushReplaceRulesToNative();
    // 释放早期阶段挂起的拦截（UI 尚未就绪，直接透传原始请求），避免 disableInterceptor 后
    // 残留 Promise 永远 pending → reload 循环。
    releaseAllIntercepts();
    releaseAllRespIntercepts();
    releaseAllPendingIso();
    // 兼容性 no-op（页面世界拦截器已删除，无可还原的 wrapper）。
    disableInterceptor();
    // 刷新后恢复 Eruda UI。真机新观察：手动注入也会瞬时放大，但不到半秒会被拉回；
    // 自动恢复直接在 content script 里 toggle() 则不会拉回。差异不是 Eruda 参数，而是
    // 手动路径先经过 native sendContentMessage("toggle")。这里让自动恢复也走同一条
    // native -> content round-trip，避免绕过 GeckoView 原生调度/viewport 收敛点。
    restoreUiSoon();
  });

  // ─ 阶段 3：刷新后重建 Eruda UI。
  function restoreUiSoon() {
    var ran = false;

    function run() {
      if (ran) return;
      ran = true;
      if (erudaActive) return;
      var tries = 0;
      (function askNative() {
        if (erudaActive) return;
        if (requestRestoreToggleViaNative()) return;
        if (tries++ < 50) {
          setTimeout(askNative, 100);
          return;
        }
        // 极端情况下 native port 一直不可用，保留旧路径兜底，避免完全无法恢复。
        toggle();
      })();
    }

    if (document.readyState === 'complete') {
      setTimeout(run, 250);
    } else {
      window.addEventListener('load', function () { setTimeout(run, 250); }, { once: true });
    }
  }
}
}());
