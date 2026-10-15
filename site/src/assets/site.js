// Little script of the site: puts the download that fits the visitor's device first. No tracker, no external call.
(function () {
  var ua = navigator.userAgent || '';
  var device = /Android/i.test(ua) ? 'android' : /iPhone|iPad|iPod/i.test(ua) ? 'ios' : /Windows/i.test(ua) ? 'windows' : 'other';
  document.documentElement.setAttribute('data-device', device);
  var primary = document.querySelectorAll('[data-for]');
  primary.forEach(function (el) {
    var wanted = el.getAttribute('data-for');
    if (device === 'android' && wanted === 'android') el.classList.add('recommended');
    if (device !== 'android' && wanted === 'windows') el.classList.add('recommended');
  });
  // the page of the downloads: the card that fits the visitor comes first on a phone
  var dl = document.querySelector('.dl');
  var android = document.querySelector('.dl [data-for="android"]');
  if (dl && android && device === 'android') dl.insertBefore(android, dl.firstChild);
  var note = document.getElementById('device-note');
  if (note) {
    if (device === 'ios') note.textContent = 'Cable Club n’existe pas pour iPhone et iPad : il fonctionne sur PC Windows et sur téléphone Android.';
    else if (device === 'other') note.textContent = 'Cable Club fonctionne sur PC Windows et sur téléphone Android.';
  }
})();
