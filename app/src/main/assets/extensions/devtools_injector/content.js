// Entry script. Module files are loaded before this file by manifest.json.
(function () {
connect();
proxyFeedInit();  // 接收原生 MITM 代理的解密流量，旁路显示到面板（不影响转发）

// 页面导航后自动恢复：
//   阶段 1（document_start，同步）：从 sessionStorage 快照即时恢复拦截标志并注入拦截器，
//     页面第一个 fetch/XHR 就能被拦截（解决登录跳转等早期请求漏抓问题）。
//   阶段 2（异步）：加载真实配置 → 卸载早期拦截器 → 求值 eruda bundle → 重装拦截器。
//     顺序约束：eruda bundle 求值时必须看到原生 XHR/fetch（不是我们的 exportFunction 替身），
//     否则 chobitsu 读 Xray 原型抛错 → window.eruda 不可用。故在求值前调 disableInterceptor()
//     还原原生，求值后重装。
//   阶段 3（load 事件）：toggle() 调 eruda.init() 构建 UI。
if (wasActive()) {
  // ─ 阶段 1：同步快速恢复（document_start 即时生效）
  loadNetConfigFromCache();
  syncGlobalInterceptEnabled();
  syncGlobalRespInterceptEnabled();
  syncGlobalInterceptNoise();
  syncFilterSuppressResp();
  earlyInjectInterceptor(); // ← 立即注入，页面首个 fetch/XHR 即被拦截

  // ─ 阶段 2：异步加载真实配置，再卸载→重装拦截器（绕开 eruda 时序约束）
  loadNetConfig(function () {
    loadReplaceRules();
    // 更新标志（真实配置可能与快照略有不同）
    syncGlobalInterceptEnabled();
    syncGlobalRespInterceptEnabled();
    syncGlobalInterceptNoise();
    syncFilterSuppressResp();
    pushReplaceRulesToNative();
    // 释放早期阶段拦截到的所有请求（UI 尚未就绪，无法手动放行；直接透传原始请求）。
    // releaseAllIntercepts 只清可见队列；releaseAllPendingIso 兜底清 isolated 世界里
    // 已挂起但消息尚未入队的 Promise，否则 disableInterceptor 后页面 fetch 永远 pending → reload 循环。
    releaseAllIntercepts();
    releaseAllRespIntercepts();
    releaseAllPendingIso();
    // 卸载早期拦截器，还原原生 XHR/fetch，使 eruda bundle 可安全 patch 原型
    disableInterceptor();
    loadPageEruda(function () {
      // eruda 已对原生 XHR/fetch 做 prototype patch；更新 __bhRestore* 指向 eruda-patched 版本，
      // 保证后续 disableInterceptor() 不会绕过 eruda 直接还原到原始原生 API。
      try {
        var _pw = window.wrappedJSObject;
        if (_pw) { _pw.__bhRestoreFetch = _pw.fetch; _pw.__bhRestoreXHR = _pw.XMLHttpRequest; }
      } catch (e) {}
      // 不在这里重装阻塞型拦截器：UI 尚未初始化，重新拦截会卡住触发 load 的请求，造成 reload 死锁。
      // 让 toggle()->installI18n() 在 UI 可用后统一 injectInterceptor()+sync*。
      restoreUiSoon();
    });
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
