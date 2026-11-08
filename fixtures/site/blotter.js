// Opens the export dialog on click (fixture for CONTAINER_CHANGE capture).
document.addEventListener('click', function (e) {
  var trigger = e.target.closest('[aria-label="Export"]');
  if (trigger && !document.getElementById('export-dialog')) {
    var dialog = document.createElement('div');
    dialog.id = 'export-dialog';
    dialog.setAttribute('role', 'dialog');
    dialog.setAttribute('aria-label', 'Export trades');
    dialog.innerHTML =
      '<h2>Export trades</h2>' +
      '<label for="export-format">Format</label>' +
      '<select id="export-format" name="format"><option>CSV</option><option>XLSX</option></select>' +
      '<label><input type="checkbox" name="includeCancelled"> Include cancelled</label>' +
      '<button type="button" data-testid="export-download">Download</button>' +
      '<button type="button">Cancel</button>';
    document.body.appendChild(dialog);
    location.hash = 'export';
  }
  if (e.target.closest('#export-dialog button') && e.target.textContent === 'Cancel') {
    document.getElementById('export-dialog').remove();
  }
});
