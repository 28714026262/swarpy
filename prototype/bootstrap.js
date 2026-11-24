(function () {
  'use strict';
  function load(file) { var script=document.createElement('script');script.src=file;document.body.appendChild(script); }
  fetch('/api/v1/session',{credentials:'same-origin'}).then(function(response){
    if(!response.ok){var error=new Error('本地服务暂不可用，请刷新重试');error.static=response.status===404;throw error;}return response.json();
  }).then(function(session){window.SwapySession=session;load('live.js');}).catch(function(error){if(error.static)load('app.js');else document.querySelector('.boot').textContent=error.message||'本地服务连接失败，请刷新重试';});
}());
