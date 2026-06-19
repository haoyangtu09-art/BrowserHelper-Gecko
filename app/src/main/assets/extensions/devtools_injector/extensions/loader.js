// 拓展插件中枢：注册表 + 启用/禁用生命周期 + 持久化 + 重载自动恢复。
// 所有插件随包内置、已加载进同一 content-script 全局，只有「启用/禁用」两态。
// 启用调 onEnable、禁用调 onDisable；启用态写 storage.local，页面重载后自动恢复。
// 运行中的页面无法真正「删掉」已执行的代码，所以卸载=onDisable 把自己做的事全撤回。
var BH_PLUGINS = [];            // [{id, name, desc, onEnable, onDisable}]
var bhEnabledPlugins = {};      // id -> true（当前内存态）
var bhPluginsRestored = false;  // 防重复恢复
var BH_PLUGINS_KEY = 'bhEnabledPlugins';

// 插件自注册（在 presets/* 里调用）。重复 id 忽略。
function registerPlugin(desc) {
  if (!desc || !desc.id) return;
  if (BH_PLUGINS.some(function (p) { return p.id === desc.id; })) return;
  BH_PLUGINS.push(desc);
}

function bhPluginById(id) {
  return BH_PLUGINS.find(function (p) { return p.id === id; }) || null;
}

function isPluginEnabled(id) {
  return !!bhEnabledPlugins[id];
}

// 提供给插件的公共能力句柄，避免插件各自乱碰全局。
function bhPluginCtx() {
  return {
    port: (typeof port !== 'undefined') ? port : null,
    runInPage: (typeof runInPage === 'function') ? runInPage : function () {},
    log: function () {
      try {
        var a = ['[bh-plugin]'].concat([].slice.call(arguments));
        console.log.apply(console, a);
      } catch (e) {}
    },
  };
}

function enablePlugin(id) {
  if (bhEnabledPlugins[id]) return;
  var p = bhPluginById(id);
  if (!p) return;
  bhEnabledPlugins[id] = true;
  try { if (typeof p.onEnable === 'function') p.onEnable(bhPluginCtx()); } catch (e) {}
  saveEnabledPlugins();
}

function disablePlugin(id) {
  if (!bhEnabledPlugins[id]) return;
  var p = bhPluginById(id);
  delete bhEnabledPlugins[id];
  try { if (p && typeof p.onDisable === 'function') p.onDisable(bhPluginCtx()); } catch (e) {}
  saveEnabledPlugins();
}

function togglePlugin(id) {
  if (isPluginEnabled(id)) disablePlugin(id); else enablePlugin(id);
}

function saveEnabledPlugins() {
  var st = storageLocal();
  if (!st || !st.set) return;
  try {
    var o = {};
    o[BH_PLUGINS_KEY] = Object.keys(bhEnabledPlugins);
    st.set(o).catch(function () {});
  } catch (e) {}
}

// 读盘恢复：对每个曾启用的插件跑 onEnable（enablePlugin 幂等）。恢复后刷新卡片态。
function restoreEnabledPlugins() {
  if (bhPluginsRestored) return;
  bhPluginsRestored = true;
  var st = storageLocal();
  if (!st || !st.get) return;
  try {
    st.get(BH_PLUGINS_KEY).then(function (res) {
      var ids = (res && res[BH_PLUGINS_KEY]) || [];
      if (!Array.isArray(ids)) return;
      ids.forEach(function (id) { enablePlugin(id); });
      if (typeof refreshExtCards === 'function') refreshExtCards();
    }).catch(function () {});
  } catch (e) {}
}
