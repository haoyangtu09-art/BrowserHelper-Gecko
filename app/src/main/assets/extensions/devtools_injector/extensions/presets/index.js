// 内置插件描述符。每个插件 registerPlugin 到中枢（loader.js）。
// 当前为两张演示插件：onEnable/onDisable 只打调试日志，验证生命周期跑通，
// 暂不接真实功能。真插件（如 GPT 响应编辑）后续各自一文件，按同样接口注册。
registerPlugin({
  id: 'web-agent',
  name: 'HTTP Agent',
  version: '0.7',
  desc: '在浏览器上层显示一个可收展的原生悬浮窗 Agent（白色小球 → 点击展开聊天面板）。' +
    '开启后若未授予「悬浮窗 / 显示在其他应用上层」权限，会自动跳转到系统授权页，授权返回即出现小球。',
  detail: '在浏览器之上叠加一个原生悬浮窗 Agent：平时是一颗白色小球停靠在屏幕边缘，' +
    '点击展开为聊天面板，可拖动、缩放、收起。面板内含模型/权限选择、设置、个性化与记忆等入口，' +
    '后续将接入页面工具调用与二次确认审批。',
  usage: '1. 启用本插件；若系统提示，请授予「悬浮窗 / 显示在其他应用上层」权限。\n' +
    '2. 返回浏览器即可看到白色小球，拖动可贴边停靠。\n' +
    '3. 点击小球展开聊天面板，右下角可拖拽缩放，顶部蓝条可拖动，右上角减号收起。\n' +
    '4. 关闭本插件即移除悬浮窗。',
  onEnable: function (ctx) {
    ctx.port.postMessage({ action: 'agentOverlayEnable' });
    ctx.log('web-agent onEnable → agentOverlayEnable');
  },
  onDisable: function (ctx) {
    ctx.port.postMessage({ action: 'agentOverlayDisable' });
    ctx.log('web-agent onDisable → agentOverlayDisable');
  },
});

// 本地伪装规则：命中 ChatGPT 账号计划/订阅响应里的免费态字样，改写成 Plus/Pro；
// 并抹掉「升级」类 CTA 文案。这些规则会以 `gptplus-` 前缀注入共享 netReplaceRules，
// 走既有「响应体字符替换」通道下发原生（坑#4 安全，不新增缓冲）。前缀便于停用时精确移除。
// 命中字样以真机抓包为准，用户可在「字符替换」面板里直接增删/微调这些规则。
var GPT_PLUS_RULES = [
  // 计划类型 / 订阅态（覆盖有无空格两种 JSON 风格）。
  { from: '"plan_type":"free"', to: '"plan_type":"plus"' },
  { from: '"plan_type": "free"', to: '"plan_type": "plus"' },
  { from: '"has_active_subscription":false', to: '"has_active_subscription":true' },
  { from: '"has_active_subscription": false', to: '"has_active_subscription": true' },
  { from: '"is_free_tier":true', to: '"is_free_tier":false' },
  { from: '"is_free_tier": true', to: '"is_free_tier": false' },
  { from: '"has_paid_subscription":false', to: '"has_paid_subscription":true' },
  { from: '"has_paid_subscription": false', to: '"has_paid_subscription": true' },
  { from: 'chatgptfreeplan', to: 'chatgptplusplan' },
  // 升级 CTA 文案（英文 + 中文），置空以隐藏入口。
  { from: 'Upgrade to Plus', to: '' },
  { from: 'Upgrade to Go', to: '' },
  { from: 'Upgrade plan', to: '' },
  { from: '升级到 Plus', to: '' },
  { from: '升级方案', to: '' },
];

function gptPlusStripRules() {
  if (typeof netReplaceRules === 'undefined' || !Array.isArray(netReplaceRules)) return;
  netReplaceRules = netReplaceRules.filter(function (r) {
    return !(r && String(r.id || '').indexOf('gptplus-') === 0);
  });
}

function gptPlusPushRules(ctx) {
  if (typeof netReplaceRules === 'undefined' || !Array.isArray(netReplaceRules)) {
    ctx.log('local-gpt-plus: 替换模块未就绪，稍后重试');
    return;
  }
  gptPlusStripRules();
  GPT_PLUS_RULES.forEach(function (r, i) {
    netReplaceRules.push({ id: 'gptplus-' + i, from: r.from, to: r.to, enabled: true });
  });
  // 响应方向替换才能改订阅接口，因此强制启用替换并纳入 resp 方向。
  netReplaceEnabled = true;
  netReplaceScope = 'both';
  if (typeof saveReplaceRules === 'function') saveReplaceRules();
  if (typeof saveNetConfig === 'function') saveNetConfig();
  if (typeof updateReplaceBtn === 'function') updateReplaceBtn();
  // 下发即经 setReplaceRules→saveReplaceConfig 持久化到原生 SharedPreferences，
  // 冷启动 loadReplaceConfig 会在 socket 开启前载入 → 重载/重启后首个订阅响应也能命中。
  if (typeof pushReplaceRulesToNative === 'function') pushReplaceRulesToNative();
}

registerPlugin({
  id: 'local-gpt-plus',
  name: '本地 GPT Plus/Pro 伪装',
  version: '0.1',
  desc: '本地改写账号计划/订阅响应，把免费态伪装成 Plus/Pro，并隐藏页面「升级」入口。' +
    '纯本地字符替换，不触碰账号认证，不与服务器做任何真实校验绕过。',
  detail: '启用后向原生代理下发一组响应体字符替换规则：命中 ChatGPT 账号/订阅接口里的 ' +
    'plan_type=free、has_active_subscription=false 等字样并改写为 Plus/Pro，同时抹掉 ' +
    '"Upgrade to Plus" / "升级到 Plus" 等 CTA 文案。规则会同步显示在「字符替换」面板，可自行增删微调。',
  usage: '1. 先开启「代理探针」并安装抓包根证书（否则无法解密改写 HTTPS）。\n' +
    '2. 启用本插件；规则自动写入「字符替换」并持久化到原生。\n' +
    '3. 刷新 ChatGPT 页面；若仍显示免费版，在「网络」面板确认那条账号/订阅响应是否被代理捕获——\n' +
    '   若根本没出现该请求，说明被 Service Worker/缓存直接命中，需清缓存或断网重载；\n' +
    '   若出现了，核对其真实字段名/文案，在「字符替换」里补上对应规则。\n' +
    '4. 关闭本插件即移除这些以 gptplus- 前缀标记的规则。',
  onEnable: function (ctx) {
    gptPlusPushRules(ctx);
    ctx.log('local-gpt-plus enabled: pushed ' + GPT_PLUS_RULES.length + ' spoof rules');
  },
  onDisable: function (ctx) {
    gptPlusStripRules();
    if (typeof saveReplaceRules === 'function') saveReplaceRules();
    if (typeof updateReplaceBtn === 'function') updateReplaceBtn();
    if (typeof pushReplaceRulesToNative === 'function') pushReplaceRulesToNative();
    ctx.log('local-gpt-plus disabled: removed gptplus- rules');
  },
});
