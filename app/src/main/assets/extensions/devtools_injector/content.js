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

  // ─ 阶段 3：刷新后重建 Eruda UI。
  //   根因(真机逐一证伪 + 历史分析 c8e0d19e):放大/错位纯发生在 GeckoView 原生 APZ 合成器层
  //   ——reload 后页面尚未被用户交互时,自动注入 Eruda 那块占大半屏的 fixed shadow host
  //   触发 APZ 分辨率重算,合成器缩放跳变(放大一圈) + 触摸坐标整体锁错(按钮挤一块 / 控制台
  //   点不开 / 缩放失效)。该跳变 DOM 完全测不到(dpr / visualViewport.scale 均不变),所以
  //   viewport meta 重写、改注入时机、滚动都修不了(已分别真机证伪);密度 override 亦无罪
  //   (自然密度下照样跳)。
  //   关键观察:手动开 Eruda 从不触发——因为那时页面已 load 完且用户刚触屏,APZ 已被用户交互
  //   "激活"到稳定分辨率。修法:刷新后不再无条件自动注入,而是等用户对页面的第一次真实交互
  //   (touchstart / pointerdown / wheel / scroll / keydown)之后再 toggle(),复刻"手动开"
  //   的前置条件(页面静置 + 用户已触屏),从根上避开 APZ 重算跳变。
  function restoreUiSoon() {
    var ran = false;

    function run() {
      if (ran) return;
      ran = true;
      if (erudaActive) return;
      toggle();
    }

    // 绑定一次性「首次用户交互」监听:任一手势触发即解绑全部并注入。
    function armGesture() {
      var evts = ['touchstart', 'pointerdown', 'wheel', 'scroll', 'keydown'];
      function onGesture() {
        evts.forEach(function (t) { window.removeEventListener(t, onGesture, true); });
        // 等这一拍交互引发的 APZ 调整落定后再注入,避免与手势自身的滚动/缩放抢算。
        requestAnimationFrame(function () { requestAnimationFrame(run); });
      }
      evts.forEach(function (t) {
        window.addEventListener(t, onGesture, { capture: true, passive: true });
      });
    }

    if (document.readyState === 'complete') {
      armGesture();
    } else {
      window.addEventListener('load', armGesture, { once: true });
    }
  }
}
}());
