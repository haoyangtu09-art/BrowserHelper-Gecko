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

  // ─ 阶段 3：刷新后重建 Eruda UI 的时机。
  //   必须等页面 load 完、视口/缩放(GeckoView APZ)稳定后再 init Eruda，否则在加载途中
  //   注入 Eruda 的大块 fixed-定位 shadow host 会与 GeckoView 的视口计算抢跑，导致页面
  //   视觉缩放一圈、触摸坐标整体错位(点 Eruda 悬浮窗/页面输入框都点不中)。首次手动开
  //   Eruda 没有这个问题，正是因为那时页面已 load 完、视口已稳定——这里对齐同样的条件。
  //   兜底：万一 load 长时间不触发(被流式请求拖住)，超时后仍恢复，避免永远不恢复。
  function restoreUiSoon() {
    var ran = false;
    function run() {
      if (ran) return;
      ran = true;
      if (erudaActive) return;
      toggle();
    }
    // load 完成后再等两帧 + 小延时，确保视觉视口已落定再注入。
    function afterStable() {
      requestAnimationFrame(function () {
        requestAnimationFrame(function () { setTimeout(run, 50); });
      });
    }
    if (document.readyState === 'complete') {
      afterStable();
    } else {
      window.addEventListener('load', afterStable, { once: true });
      // 页面世界拦截器已全部停用（earlyInjectInterceptor/injectInterceptor 均为空函数，
      // 早期请求由原生 MITM 代理抓取），我们的代码不再阻塞 load → 可放心纯等 load。
      // 之前 4s 兜底在重 SPA（load 常 >4s）会先于 load 提前注入 Eruda，与 GeckoView
      // 首屏 APZ 抢跑 → 页面放大一圈 + 触摸坐标错位（这正是回归根因）。这里只留一个
      // 远超正常加载时长的安全网，仅防 load 永不触发的极端卡死，正常加载绝不会先于 load。
      setTimeout(run, 20000);
    }
  }
}
}());
