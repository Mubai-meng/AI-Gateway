// 概览页：滚到「消耗趋势」卡片 + 强制出帧，供截图核对双纵轴
(function () {
  var pb = document.querySelector('.page-body');
  var c = document.getElementById('trendCanvas');
  var card = c && c.closest('.card');
  if (pb && card) {
    var r = card.getBoundingClientRect();
    var pr = pb.getBoundingClientRect();
    pb.scrollTop += (r.top - pr.top) - 40;
  }
  var bar = document.createElement('div');
  bar.style.cssText = 'position:fixed;top:0;left:0;width:100%;height:3px;background:#ff0033;z-index:99999';
  document.body.appendChild(bar);
  return 'scrolled=' + (pb ? pb.scrollTop : -1);
})();
