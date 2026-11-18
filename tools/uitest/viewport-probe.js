// 读窗口真实视口与缩放，诊断「高分屏下显示过大」
(function () {
  var sb = document.querySelector('.sidebar');
  var nav = document.querySelector('.nav-item span');
  var r = nav ? nav.getBoundingClientRect() : null;
  return JSON.stringify({
    innerW: window.innerWidth,
    innerH: window.innerHeight,
    dpr: window.devicePixelRatio,
    screenW: screen.width,
    screenH: screen.height,
    sidebarW: sb ? sb.getBoundingClientRect().width : -1,
    navFontPx: r ? getComputedStyle(nav).fontSize : '?',
    navTextH: r ? Math.round(r.height) : -1,
    bodyFontSize: getComputedStyle(document.body).fontSize,
  });
})();
