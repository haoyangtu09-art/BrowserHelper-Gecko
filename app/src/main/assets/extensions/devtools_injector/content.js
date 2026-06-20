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
    // ⚠️ 修复「开 Eruda 后刷新 → 整页放大一圈 + 按钮错位 + 控制台点不开」的根因：
    //   不再在 load 期间提前 loadPageEruda（= fetch+求值 eruda bundle + 注入 @font-face 样式表）。
    //   该「提前求值」是页面世界拦截器时代的时序遗留——当年必须让 eruda 的 chobitsu 在我们的
    //   拦截器之前 patch 原生 XHR/fetch，所以在 load 途中先求值 bundle。如今页面世界拦截器已
    //   删除、抓包改由原生 MITM 代理负责，提前求值毫无意义，却恰是「刷新恢复」相对「手动开
    //   Eruda」唯一残留的行为差异：在 GeckoView 首屏 APZ 仍在解析 <meta viewport> / 计算
    //   resolution 的途中注入大块 @font-face 样式表并求值大 bundle，触发 reflow 与视口缩放
    //   抢算 → 合成器 resolution 跳到约 1/0.85 → 整页放大一圈、触摸坐标整体错位、Eruda 入口
    //   按钮点不中。这就是为什么先前删 displayDensityOverride / 关 useShadowDom / 推迟 init()
    //   都没修好——真正的触发点是 bundle 求值与样式注入的时机，而非 init() 本身。
    //   修法：等 load 后视口稳定，由 restoreUiSoon 一次性 loadPageEruda+initPageEruda，与
    //   「手动开 Eruda」走完全相同的路径（后者从无放大/错位问题）。
    restoreUiSoon();
  });

  // ─ 阶段 3：刷新后重建 Eruda UI（load 后注入）。
  //   根因(真机逐一证伪 + 历史分析 c8e0d19e):放大/错位纯发生在 GeckoView 原生 APZ 合成器层。
  //   已证伪的 JS 侧修法:viewport meta 重写、改注入时机、滚动、等首次用户交互——全无效,
  //   因为跳变在原生层、DOM 测不到(dpr/visualViewport.scale 均不变);密度 override 亦无罪。
  //   c8e0d19e 把触发源锁定在「Eruda 注入的那块占大半屏的 fixed SHADOW host」本身。故本轮
  //   换注入方式:eruda.init 改 useShadowDom:false(见 core/utils.js),用普通 light-DOM 渲染,
  //   不再创建 fixed shadow host → 避免 GeckoView APZ 把它当新内容触发分辨率重算。
  //   时机回归最简单的「load 后直接注入」,只动「注入方式」一个变量,便于真机定性。
  function restoreUiSoon() {
    var ran = false;
    function run() {
      if (ran) return;
      ran = true;
      if (erudaActive) return;
      toggle();
    }
    if (document.readyState === 'complete') {
      requestAnimationFrame(function () { requestAnimationFrame(run); });
    } else {
      window.addEventListener('load', function () {
        requestAnimationFrame(function () { requestAnimationFrame(run); });
      }, { once: true });
    }
  }
}
}());
