// 拓展面板：与「网络」同级的独立 Eruda Tool（不再是网络面板的子页）。
// 结构镜像 panel/index.js 的网络 Tool：content world 构建 #bh-ext，
// page/isolated 两世界各自把它 append 进对应的 eruda 容器。
var extPanel = null;

var EXT_STYLE = [
  '#bh-ext{position:relative;display:flex;flex-direction:column;height:100%;font-size:15px;',
  '  font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;',
  '  background:#fff;color:#111;pointer-events:auto;touch-action:auto;}',
  '#bh-ext-head{display:flex;align-items:center;gap:8px;padding:10px 12px;',
  '  border-bottom:1px solid #d0d7de;background:#f6f8fa;}',
  '#bh-ext-title{flex:1;font-size:15px;font-weight:700;}',
  '#bh-ext-body{flex:1;overflow:auto;padding:8px;display:grid;',
  '  grid-template-columns:repeat(auto-fill,minmax(64px,1fr));gap:7px;align-content:start;}',
  // Compact square cards sized so about five fit across a typical extension panel.
  '.bh-ext-card{display:flex;flex-direction:column;gap:4px;padding:6px;',
  '  border:1px solid #d0d7de;border-radius:8px;background:#fff;aspect-ratio:1/1;min-width:0;}',
  '.bh-ext-card-info{flex:1;min-width:0;overflow:hidden;display:flex;flex-direction:column;',
  '  justify-content:center;text-align:center;}',
  '.bh-ext-card-name{font-size:13px;line-height:1.15;font-weight:800;color:#111;',
  '  display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;}',
  '.bh-ext-card-ver{margin-top:3px;font-size:10px;line-height:1;font-weight:700;color:#2563eb;}',
  '.bh-ext-actions{display:flex;gap:4px;}',
  '.bh-ext-btn{flex:1;padding:4px 0;border:none;border-radius:6px;',
  '  font-size:10px;line-height:1.1;font-weight:800;text-align:center;white-space:nowrap;}',
  '.bh-ext-detail{background:#eef2f7;color:#333;}',
  '.bh-ext-toggle{color:#fff;}',
  '.bh-ext-toggle.on{background:#16a34a;}',
  '.bh-ext-toggle.off{background:#6b7280;}',
  // Detail dialog overlay (centered inside the panel).
  '.bh-ext-dlg-mask{position:absolute;inset:0;background:rgba(0,0,0,.4);display:flex;',
  '  align-items:center;justify-content:center;z-index:10;}',
  '.bh-ext-dlg{width:82%;max-width:320px;max-height:80%;overflow:auto;background:#fff;',
  '  border-radius:14px;padding:16px;}',
  '.bh-ext-dlg-title{font-size:16px;font-weight:700;color:#111;}',
  '.bh-ext-dlg-ver{margin-left:6px;font-size:11px;font-weight:600;color:#2563eb;',
  '  background:#eff6ff;border-radius:6px;padding:1px 6px;}',
  '.bh-ext-dlg-sec{margin-top:12px;font-size:13px;font-weight:700;color:#333;}',
  '.bh-ext-dlg-txt{margin-top:4px;font-size:13px;color:#555;line-height:1.5;white-space:pre-wrap;}',
  '.bh-ext-dlg-close{margin-top:16px;width:100%;padding:9px 0;border:none;border-radius:10px;',
  '  background:#111;color:#fff;font-size:14px;font-weight:600;}',
].join('');

// 单个拓展方块卡：名称 + 描述 + 底部「启用/禁用」开关按钮。
// 按钮态随 isPluginEnabled 切换，点击 togglePlugin 后刷新本卡。
function syncExtToggleBtn(btn, id) {
  var on = (typeof isPluginEnabled === 'function') && isPluginEnabled(id);
  btn.className = 'bh-ext-btn bh-ext-toggle ' + (on ? 'on' : 'off');
  btn.textContent = on ? '开' : '关';
}

// 居中详情弹窗：标题 + 版本 + 介绍 + 使用说明 + 关闭。叠在 #bh-ext 之上。
function showExtDetailDialog(p) {
  if (!extPanel) return;
  var mask = document.createElement('div');
  mask.className = 'bh-ext-dlg-mask';
  var dlg = document.createElement('div');
  dlg.className = 'bh-ext-dlg';
  var title = document.createElement('div');
  title.className = 'bh-ext-dlg-title';
  title.textContent = p.name || '';
  if (p.version) {
    var ver = document.createElement('span');
    ver.className = 'bh-ext-dlg-ver';
    ver.textContent = 'v' + p.version;
    title.appendChild(ver);
  }
  dlg.appendChild(title);
  function section(label, text) {
    if (!text) return;
    var h = document.createElement('div');
    h.className = 'bh-ext-dlg-sec';
    h.textContent = label;
    dlg.appendChild(h);
    var t = document.createElement('div');
    t.className = 'bh-ext-dlg-txt';
    t.textContent = text;
    dlg.appendChild(t);
  }
  section('介绍', p.detail || p.desc || '');
  section('使用说明', p.usage || '');
  var close = document.createElement('button');
  close.className = 'bh-ext-dlg-close';
  close.textContent = '关闭';
  close.addEventListener('click', function () {
    if (mask.parentNode) mask.parentNode.removeChild(mask);
  });
  dlg.appendChild(close);
  // 点遮罩空白处也关闭（点弹窗本体不关）。
  mask.addEventListener('click', function (e) {
    if (e.target === mask && mask.parentNode) mask.parentNode.removeChild(mask);
  });
  mask.appendChild(dlg);
  extPanel.appendChild(mask);
}

function buildExtCard(p) {
  var card = document.createElement('div');
  card.className = 'bh-ext-card';
  var info = document.createElement('div');
  info.className = 'bh-ext-card-info';
  var name = document.createElement('div');
  name.className = 'bh-ext-card-name';
  name.textContent = p.name || '';
  info.appendChild(name);
  // 描述位置改放版本号。
  var ver = document.createElement('div');
  ver.className = 'bh-ext-card-ver';
  ver.textContent = p.version ? 'v' + p.version : '';
  info.appendChild(ver);
  card.appendChild(info);
  // 底部动作行：详情 + 安装/启用。
  var actions = document.createElement('div');
  actions.className = 'bh-ext-actions';
  var detail = document.createElement('button');
  detail.className = 'bh-ext-btn bh-ext-detail';
  detail.textContent = '详';
  detail.addEventListener('click', function () { showExtDetailDialog(p); });
  actions.appendChild(detail);
  var btn = document.createElement('button');
  btn.className = 'bh-ext-btn bh-ext-toggle off';
  syncExtToggleBtn(btn, p.id);
  btn.addEventListener('click', function () {
    if (typeof togglePlugin === 'function') togglePlugin(p.id);
    syncExtToggleBtn(btn, p.id);
  });
  actions.appendChild(btn);
  card.appendChild(actions);
  return card;
}

// 重新同步面板里所有卡片的开关态（恢复/外部变更后调用）。
function refreshExtCards() {
  if (!extPanel) return;
  var btns = extPanel.querySelectorAll('.bh-ext-toggle');
  var list = (typeof BH_PLUGINS !== 'undefined' && BH_PLUGINS) || [];
  for (var i = 0; i < btns.length && i < list.length; i++) {
    syncExtToggleBtn(btns[i], list[i].id);
  }
}

function buildExtPanel() {
  if (extPanel) return extPanel;
  var wrap = document.createElement('div');
  wrap.id = 'bh-ext';
  ['pointerdown', 'pointerup', 'touchstart', 'touchend', 'mousedown', 'mouseup', 'click', 'dblclick', 'contextmenu'].forEach(function (type) {
    wrap.addEventListener(type, function (e) { e.stopPropagation(); }, false);
  });
  var style = document.createElement('style');
  style.textContent = EXT_STYLE;
  wrap.appendChild(style);
  var head = document.createElement('div');
  head.id = 'bh-ext-head';
  head.innerHTML = '<div id="bh-ext-title">拓展</div>';
  wrap.appendChild(head);
  var body = document.createElement('div');
  body.id = 'bh-ext-body';
  var plugins = (typeof BH_PLUGINS !== 'undefined' && BH_PLUGINS) || [];
  plugins.forEach(function (p) { body.appendChild(buildExtCard(p)); });
  wrap.appendChild(body);
  extPanel = wrap;
  return wrap;
}

// eruda 把 settings 之外的所有 tool 都插在「设置」之前，所以「拓展」默认排在设置左边、
// 紧挨网络。注册后把它的 nav tab DOM 挪到父容器末尾，即排到「设置」右边（最右）。
function moveExtTabLast() {
  var tries = 0;
  (function go() {
    try {
      var host = document.querySelector('.eruda-container');
      var sr = host && host.shadowRoot;
      var item = sr && sr.querySelector('.eruda-item[data-id="\u62d3\u5c55"]');
      if (item && item.parentNode) { item.parentNode.appendChild(item); return; }
    } catch (e) {}
    if (tries++ < 30) setTimeout(go, 100);
  }());
}

// isolated world：直接 eruda.add 一个名为「拓展」的 Tool。
function registerExtTool(erudaObj) {
  if (!erudaObj || !erudaObj.add) return;
  var tool = {
    name: '拓展',
    _$el: null,
    init: function ($el) {
      this._$el = $el;
      var node = ($el && $el[0]) || $el;
      if (!extPanel) buildExtPanel();
      if (node && node.appendChild) node.appendChild(extPanel);
    },
    show: function () { if (this._$el && this._$el.show) this._$el.show(); return this; },
    hide: function () { if (this._$el && this._$el.hide) this._$el.hide(); return this; },
    destroy: function () {},
  };
  try { erudaObj.add(tool); } catch (e) {}
  moveExtTabLast();
}

// page world：content script 无法直接调用页面里的 eruda.add，注入脚本在页面世界注册。
// #bh-ext 由 content world 构建（先挂 body），page world 的 tool.init 再把它移进容器。
function injectExtToolPageWorld() {
  if (!extPanel) buildExtPanel();
  extPanel.style.display = 'none';
  document.body.appendChild(extPanel);
  runInPage([
    '(function(){',
    '  if(!window.eruda||!window.eruda.add)return;',
    '  if(window.__bhExtToolAdded)return;',
    '  window.__bhExtToolAdded=true;',
    '  window.eruda.add(function(devtools){',
    '    return {',
    '      name:"拓展",',
    '      init:function($el){',
    '        this._$el=$el;',
    '        var node=($el&&$el[0])||($el&&$el.get&&$el.get(0))||$el;',
    '        var panel=document.getElementById("bh-ext");',
    '        if(panel&&node&&node.appendChild){node.appendChild(panel);panel.style.display="flex";}',
    '      },',
    '      show:function(){',
    '        if(this._$el&&this._$el.show)this._$el.show();',
    '        var panel=document.getElementById("bh-ext");if(panel)panel.style.display="flex";',
    '        return this;',
    '      },',
    '      hide:function(){if(this._$el&&this._$el.hide)this._$el.hide();return this;},',
    '      destroy:function(){},',
    '    };',
    '  });',
    // 把「拓展」tab 挪到设置右边（eruda 默认把它插在设置左边）。
    '  var tries=0;(function go(){try{',
    '    var host=document.querySelector(".eruda-container");',
    '    var sr=host&&host.shadowRoot;',
    '    var item=sr&&sr.querySelector(".eruda-item[data-id=\\"\u62d3\u5c55\\"]");',
    '    if(item&&item.parentNode){item.parentNode.appendChild(item);return;}',
    '  }catch(e){}if(tries++<30)setTimeout(go,100);})();',
    '})();',
  ].join('\n'));
}

// 在网络 Tool 注册的同一时机调用，按当前 eruda 世界注册「拓展」Tool。
function installExtTool() {
  if (erudaMode === 'page') {
    injectExtToolPageWorld();
  } else {
    registerExtTool(self.eruda || null);
  }
  // 面板就绪后恢复已启用的插件（幂等），并把卡片态刷成最新。
  if (typeof restoreEnabledPlugins === 'function') restoreEnabledPlugins();
  return BH_PLUGINS;
}
