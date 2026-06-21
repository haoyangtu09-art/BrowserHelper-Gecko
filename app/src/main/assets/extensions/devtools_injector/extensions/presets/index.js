// 内置插件描述符。每个插件 registerPlugin 到中枢（loader.js）。
// 当前为两张演示插件：onEnable/onDisable 只打调试日志，验证生命周期跑通，
// 暂不接真实功能。真插件（如 GPT 响应编辑）后续各自一文件，按同样接口注册。
registerPlugin({
  id: 'web-agent',
  name: 'HTTP Agent',
  version: '0.6',
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

registerPlugin({
  id: 'local-gpt-plus',
  name: '本地 gpt plus/pro 伪装',
  desc: '演示占位：启用/禁用只输出日志',
  onEnable: function (ctx) { ctx.log('local-gpt-plus onEnable'); },
  onDisable: function (ctx) { ctx.log('local-gpt-plus onDisable'); },
});
