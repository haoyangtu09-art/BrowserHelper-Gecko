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

  // ─ 阶段 3：刷新后重建 Eruda UI（根因重定位 + MVM 收敛修复 + 真机判定日志）。
  //   本轮工作假设(推翻「纯原生 APZ、DOM 测不到」旧结论):放大/错位不是捏合分辨率(那会改
  //   visualViewport.scale、且不会让点击命中错位),而是 Gecko MobileViewportManager(MVM)的
  //   「布局视口 / zoom-to-fit 重适配」。reload 后 MVM 仍处于「下一次 reflow 就重算分辨率」的
  //   窗口;此刻注入 Eruda(大块 @font-face light-DOM 样式表 + 占屏 fixed shadow host)触发
  //   reflow → MVM 锁错分辨率(约 1/0.85,正是 displayDensityOverride 几何被重算时算偏的幅度)。
  //   手动开 Eruda 不触发,是因为那时页面已 load 完、MVM 早已锁定,reflow 不再重适配。
  //   ⚠️ 旧判定「DOM 完全测不到」只测了 dpr 与 visualViewport.scale——这两者对「布局视口重适配」
  //   本就不变;真正会变的是 window.innerWidth / documentElement.clientWidth,之前从没量过。
  //   故本轮:
  //     ① geomReport 在注入前/后打印整套几何量(iw/cw/ow/vvw/vvs/dpr/sw),真机一眼判定是哪种放大;
  //     ② 把「最后一次 reflow」做成一次干净的强制重排(lastReflowSettle),逼 MVM 按真实 viewport
  //        收敛回正确分辨率——不再像 45d7af64 那样改完 meta 又 restore(restore 自身又触发一次
  //        重适配 → 失败);且只对已声明移动端 viewport 的页面补 initial-scale=1,绝不把桌面型
  //        (无 viewport)页面强改成 device-width 造成新回归。
  function restoreUiSoon() {
    var ran = false;

    function geomReport(tag) {
      try {
        var vv = window.visualViewport;
        var de = document.documentElement;
        var s = 'BHZOOM ' + tag +
          ' iw=' + window.innerWidth +
          ' cw=' + (de ? de.clientWidth : -1) +
          ' ow=' + window.outerWidth +
          ' vvw=' + (vv ? Math.round(vv.width) : -1) +
          ' vvs=' + (vv ? vv.scale.toFixed(3) : '-') +
          ' dpr=' + window.devicePixelRatio +
          ' sw=' + screen.width;
        try { console.log(s); } catch (e) {}
        postStatus(s);
      } catch (e) {}
    }

    // 把「最后一次 reflow」做干净:强制一次同步重排,让 MVM 按当前真实 viewport 收敛锁定。
    // 仅当页面已声明移动端 viewport(width=device-width / initial-scale)且 initial-scale≠1 时,
    // 才补成 initial-scale=1 作为收敛基准;桌面型(无 viewport)页面只做强制重排、不改 meta。
    function lastReflowSettle() {
      try {
        var m = document.querySelector('meta[name="viewport"]');
        var content = m ? (m.getAttribute('content') || '') : '';
        if (m && /device-width|initial-scale/i.test(content) &&
            !/initial-scale\s*=\s*1(\.0)?\b/i.test(content)) {
          var fixed = content.replace(/initial-scale\s*=\s*[\d.]+/i, 'initial-scale=1.0');
          if (fixed === content) fixed = content.replace(/\s*$/, '') + ', initial-scale=1.0';
          m.setAttribute('content', fixed);
        }
        // 强制同步 reflow:读取布局尺寸触发 flush,使 MVM 立即按当前 viewport 重算并锁定。
        void (document.documentElement && document.documentElement.offsetHeight);
      } catch (e) {}
    }

    function run() {
      if (ran) return;
      ran = true;
      if (erudaActive) return;
      geomReport('pre-inject');
      toggle();
      // toggle 异步(loadPageEruda→initPageEruda);轮询 erudaActive 置位后把最后一次 reflow
      // 做成干净收敛,并三处量测(注入后 / 收敛 2 帧后 / 1.2s 兜底)供真机判定是否生效。
      var tries = 0;
      (function afterInject() {
        if (erudaActive || tries++ > 80) {
          geomReport('post-inject');
          lastReflowSettle();
          requestAnimationFrame(function () {
            requestAnimationFrame(function () { geomReport('post-settle'); });
          });
          setTimeout(function () { lastReflowSettle(); geomReport('post-settle-1200'); }, 1200);
          return;
        }
        setTimeout(afterInject, 50);
      })();
    }

    if (document.readyState === 'complete') {
      run();
    } else {
      window.addEventListener('load', run, { once: true });
    }
  }
}
}());
