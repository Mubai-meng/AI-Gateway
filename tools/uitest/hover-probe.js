// 概览页 hover 结构探针：同步版。
// ExecuteScriptAsync 不 await Promise，所以必须同步 return。
// 等待用「壳先 --eval 这个文件跑不结果、稍后再跑一次读结果」不划算；
// 改为：探测在 drawXxx 完成后才可能成立，而壳的 CaptureAsync 前 1800ms 延迟
// + 心跳节奏已足够图表画完，直接同步读一次即可；没就位就返回 pending，
// 由外层重跑判断。
(function () {
  var out = {};
  ['topoCanvas', 'capCanvas', 'trendCanvas'].forEach(function (id) {
    var c = document.getElementById(id);
    var wrap = c && c.parentElement;
    out[id] = c ? {
      capWrap: !!(wrap && wrap.classList.contains('cap-wrap')),
      tip: !!(wrap && wrap.querySelector('.cap-tip')),
      wired: !!(c.dataset.hoverWired || c.dataset.topoWired || c.dataset.trendWired),
    } : 'missing';
  });
  var box = document.getElementById('activityBox');
  out.activity = box ? {
    rows: box.querySelectorAll('.activity-row').length,
    tip: !!box.querySelector('.cap-tip'),
    capWrap: box.classList.contains('cap-wrap'),
  } : 'missing';
  var row = document.querySelector('.activity-row');
  out.rowHoverTransition = row ? getComputedStyle(row).transitionProperty : 'no-row';
  out.url = location.href;
  return JSON.stringify(out);
})();
