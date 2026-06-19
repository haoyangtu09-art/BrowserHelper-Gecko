// 拓展面板：与「网络」同级的独立 Eruda Tool（不再是网络面板的子页）。
// 结构镜像 panel/index.js 的网络 Tool：content world 构建 #bh-ext，
// page/isolated 两世界各自把它 append 进对应的 eruda 容器。
var extPanel = null;

var EXT_STYLE = [
  '#bh-ext{display:flex;flex-direction:column;height:100%;font-size:15px;',
  '  font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;',
  '  background:#fff;color:#111;pointer-events:auto;touch-action:auto;}',
  '#bh-ext-head{display:flex;align-items:center;gap:8px;padding:10px 12px;',
  '  border-bottom:1px solid #d0d7de;background:#f6f8fa;}',
  '#bh-ext-title{flex:1;font-size:15px;font-weight:700;}',
  '#bh-ext-body{flex:1;display:flex;align-items:center;justify-content:center;color:#888;font-size:14px;}',
].join('');

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
  body.textContent = '无拓展';
  wrap.appendChild(body);
  extPanel = wrap;
  return wrap;
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
  return BH_EXTENSION_PRESETS;
}
