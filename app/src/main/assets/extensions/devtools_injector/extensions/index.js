// Extension panel entry and placeholder UI.
function initExtensionsPanel(wrap, bar) {
  netExtViewEl = document.createElement('div');
  netExtViewEl.id = 'bh-ext-view';
  netExtViewEl.innerHTML =
    '<div id="bh-ext-head">' +
      '<div id="bh-ext-title">拓展</div>' +
      '<button id="bh-ext-close" type="button">×</button>' +
    '</div>' +
    '<div id="bh-ext-body">无拓展</div>';
  wrap.appendChild(netExtViewEl);
  netExtViewEl.querySelector('#bh-ext-close').addEventListener('click', function () {
    netExtViewEl.classList.remove('open');
  });
  bar.querySelector('#bh-ext-btn').addEventListener('click', function () {
    netExtViewEl.classList.toggle('open');
  });
  return BH_EXTENSION_PRESETS;
}
