// Custom dropdown (Angular Material style overlay) and a menu, both rendered only when opened.
document.addEventListener('click', function (e) {
  if (e.target.closest('#currency-toggle') && !document.querySelector('.cdk-overlay-container')) {
    var overlay = document.createElement('div');
    overlay.className = 'cdk-overlay-container';
    overlay.innerHTML =
      '<div class="cdk-overlay-pane">' +
      '<div role="listbox" id="mat-select-0-panel" aria-label="Currency">' +
      '<mat-option role="option" id="mat-option-0">USD</mat-option>' +
      '<mat-option role="option" id="mat-option-1">EUR</mat-option>' +
      '<mat-option role="option" id="mat-option-2">INR</mat-option>' +
      '</div></div>';
    document.body.appendChild(overlay);
    return;
  }
  var option = e.target.closest('mat-option');
  if (option) {
    document.getElementById('currency-toggle').textContent = 'Currency';
    document.querySelector('.cdk-overlay-container').remove();
    return;
  }
  var toggle = e.target.closest('#actions-toggle');
  if (toggle && !document.getElementById('actions-menu')) {
    var menu = document.createElement('ul');
    menu.id = 'actions-menu';
    menu.setAttribute('role', 'menu');
    menu.setAttribute('aria-label', 'Actions');
    menu.innerHTML = '<li role="menuitem">Export</li><li role="menuitem">Archive</li>';
    toggle.parentElement.appendChild(menu);
  }
});

// A cross-origin frame: same port, different host name.
window.addEventListener('DOMContentLoaded', function () {
  var ext = document.getElementById('ext-frame');
  var host = location.hostname === 'localhost' ? '127.0.0.1' : 'localhost';
  ext.src = location.protocol + '//' + host + ':' + location.port + '/frame.html';
});
