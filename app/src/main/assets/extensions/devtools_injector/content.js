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
  //   根因(真机逐一证伪):密度(displayDensityOverride)无罪——不开 Eruda 只刷新不放大；
  //   时序无罪——等多久(1s/数秒)注入仍放大、且不自恢复；shadowDOM 无罪。真凶是 Eruda 的
  //   DOM 注入本身:在「页面刚 reload、尚未稳定交互」时,注入触发 GeckoView 的 meta-viewport
  //   自动重适配(re-fit) → resolution 跳到约 1/0.85 → 整页放大 + 触摸坐标锁错。手动开
  //   Eruda(页面已静置)不触发 re-fit,故无此问题。
  //   修法:不再纠结注入时机(治不了),而是在注入后强制重写一次 <meta name="viewport"> 逼
  //   GeckoView 把 resolution 重算回 initial-scale,直接撤销这次 re-fit 放大。
  function restoreUiSoon() {
    var ran = false;

    // 重写 viewport meta 逼 GeckoView 重算 resolution=1.0，撤销注入触发的 re-fit 放大。
    // 内容必须发生变化才会触发重算：先 pin 到 initial-scale=1，两帧后恢复页面原始 viewport。
    function resetViewportZoom() {
      try {
        var m = document.querySelector('meta[name="viewport"]');
        if (m) {
          var orig = m.getAttribute('content') || '';
          m.setAttribute('content', 'width=device-width,initial-scale=1,maximum-scale=1,user-scalable=0');
          requestAnimationFrame(function () {
            requestAnimationFrame(function () {
              m.setAttribute('content', orig || 'width=device-width,initial-scale=1');
            });
          });
        } else {
          // 页面无 viewport meta：临时插一个标准的逼 GeckoView 重算，随后移除。
          var tmp = document.createElement('meta');
          tmp.setAttribute('name', 'viewport');
          tmp.setAttribute('content', 'width=device-width,initial-scale=1,maximum-scale=1,user-scalable=0');
          (document.head || document.documentElement).appendChild(tmp);
          requestAnimationFrame(function () {
            requestAnimationFrame(function () { if (tmp.parentNode) tmp.parentNode.removeChild(tmp); });
          });
        }
      } catch (e) {}
    }

    function run() {
      if (ran) return;
      ran = true;
      if (erudaActive) return;
      toggle();
      // 等 Eruda 真正注入(erudaActive 置位)后再重置 viewport，撤销 re-fit 放大。
      // toggle 是异步(loadPageEruda→initPageEruda)，故轮询 erudaActive；置位后留缓冲
      // 等 shadow 内容绘制完(re-fit 此时才发生)再重置，并补打一次兜底。
      var tries = 0;
      (function afterInject() {
        if (erudaActive || tries++ > 60) {
          setTimeout(resetViewportZoom, 120);
          setTimeout(resetViewportZoom, 700);
          return;
        }
        setTimeout(afterInject, 50);
      })();
    }

    if (document.readyState === 'complete') {
      requestAnimationFrame(function () { requestAnimationFrame(run); });
    } else {
      window.addEventListener('load', function () {
        requestAnimationFrame(function () { requestAnimationFrame(run); });
      }, { once: true });
      // 兜底：万一 load 永不触发(极端卡死)，超时后仍恢复。
      setTimeout(run, 20000);
    }
  }
}
}());
