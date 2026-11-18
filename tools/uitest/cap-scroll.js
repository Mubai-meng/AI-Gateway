// 概览页滚动到「最近错误 / 供应商成功率」卡 + 强制出帧，为截图做准备
// 用法：外壳 --page overview --eval 本文件 --screenshot <png> --quit
(function () {
  var pb = document.querySelector('.page-body');
  var target = document.getElementById('errorsBox') || document.getElementById('capCanvas');
  var card = target && target.closest('.card');
  if (pb && card) {
    var r = card.getBoundingClientRect();
    var pr = pb.getBoundingClientRect();
    pb.scrollTop += (r.top - pr.top) - 60;
  }
  var bar = document.createElement('div');
  bar.style.cssText = 'position:fixed;top:0;left:0;width:100%;height:3px;background:#ff0033;z-index:99999';
  document.body.appendChild(bar);
  return 'scrolled=' + (pb ? pb.scrollTop : -1);
})();
