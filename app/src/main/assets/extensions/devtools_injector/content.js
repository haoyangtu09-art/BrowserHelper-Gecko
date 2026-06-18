// Entry script. Module files are loaded before this file by manifest.json.
(function () {
connect();
proxyFeedInit();  // 初始化原生代理数据源，接收 DevTools 面板数据

// 页面导航后自动恢复：原生 MITM 代理已在网络层处理所有拦截（从应用启动时就工作），
// 无需页内早期注入。但仍需加载 Eruda 并初始化配置。
if (wasActive()) {
  // 异步加载真实配置
  loadNetConfig(function () {
    loadReplaceRules();
    // 加载 Eruda（必须的，即使拦截由代理处理）
    loadPageEruda(function (err) {
      if (err) {
        postStatus('eruda page load error: ' + err);
        return;
      }
      initPageEruda(function (pageErr) {
        if (pageErr) {
          postStatus('eruda init error: ' + pageErr);
          return;
        }
        erudaActive = true;
        saveActiveState();
        postStatus('ok(page)');
        setTimeout(installI18n, 500);
      });
    });
  });

  // DOMContentLoaded 后尽快初始化 UI
  function restoreUiSoon() {
    function run() {
      if (erudaActive) return;
      toggle();
    }
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', function () {
        setTimeout(run, 50);
      }, { once: true });
    } else {
      setTimeout(run, 50);
    }
  }
  setTimeout(restoreUiSoon, 100);
}
}());
