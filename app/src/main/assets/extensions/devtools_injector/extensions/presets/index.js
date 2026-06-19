// 内置插件描述符。每个插件 registerPlugin 到中枢（loader.js）。
// 当前为两张演示插件：onEnable/onDisable 只打调试日志，验证生命周期跑通，
// 暂不接真实功能。真插件（如 GPT 响应编辑）后续各自一文件，按同样接口注册。
registerPlugin({
  id: 'web-agent',
  name: '网页 agent',
  desc: '演示占位：启用/禁用只输出日志',
  onEnable: function (ctx) { ctx.log('web-agent onEnable'); },
  onDisable: function (ctx) { ctx.log('web-agent onDisable'); },
});

registerPlugin({
  id: 'local-gpt-plus',
  name: '本地 gpt plus/pro 伪装',
  desc: '演示占位：启用/禁用只输出日志',
  onEnable: function (ctx) { ctx.log('local-gpt-plus onEnable'); },
  onDisable: function (ctx) { ctx.log('local-gpt-plus onDisable'); },
});
