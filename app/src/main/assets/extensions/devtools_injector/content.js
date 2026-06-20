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
    // 刷新后恢复 Eruda UI。根因候选已从「注入时机」收敛到 Eruda 的移动端 autoScale：
    // eruda.init 默认会读取 viewport initial-scale 并按 1/scale 重写自身 px 尺寸，
    // 在 GeckoView + displayDensityOverride/自动恢复路径上会触发 1/0.85 量级的错位。
    // core/utils.js 里已显式 autoScale:false + scale(1)，这里恢复自动注入来验证该根因。
    restoreUiSoon();
  });

  // ─ 阶段 3：刷新后重建 Eruda UI。
  function restoreUiSoon() {
    var ran = false;

    function run() {
      if (ran) return;
      ran = true;
      if (erudaActive) return;
      toggle();
    }

    if (document.readyState === 'complete') {
      setTimeout(run, 250);
    } else {
      window.addEventListener('load', function () { setTimeout(run, 250); }, { once: true });
    }
  }
}
}());
