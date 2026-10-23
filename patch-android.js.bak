// Parcha AndroidManifest.xml: permisos de ubicación/servicio y enlace del QR (intent-filter).
// Uso: node scripts/patch-android.js   (después de `npx cap add android`)
const fs = require('fs');
const cfg = JSON.parse(fs.readFileSync('capacitor.config.json', 'utf8'));
const mf = 'android/app/src/main/AndroidManifest.xml';
if (!fs.existsSync(mf)) { console.error('Falta android/. Corre primero: npx cap add android'); process.exit(1); }
const url = (cfg.server && cfg.server.url) || '';
if (!/^https:\/\//.test(url) || /CAMBIA-ESTO/.test(url)) { console.error('Edita capacitor.config.json: server.url con tu dominio real (https://...)'); process.exit(1); }
const host = new URL(url).host;
let x = fs.readFileSync(mf, 'utf8');
const perms = ['ACCESS_COARSE_LOCATION','ACCESS_FINE_LOCATION','FOREGROUND_SERVICE','FOREGROUND_SERVICE_LOCATION','POST_NOTIFICATIONS','INTERNET'];
perms.forEach(p => {
  if (!x.includes('android.permission.' + p)) x = x.replace('<application', `<uses-permission android:name="android.permission.${p}"/>\n    <application`);
});
if (!x.includes('android:host="' + host + '"')) {
  const filter = `
            <intent-filter>
                <action android:name="android.intent.action.VIEW"/>
                <category android:name="android.intent.category.DEFAULT"/>
                <category android:name="android.intent.category.BROWSABLE"/>
                <data android:scheme="https" android:host="${host}"/>
            </intent-filter>`;
  x = x.replace(/(<activity[\s\S]*?android:name="\.MainActivity"[\s\S]*?>)/, '$1' + filter);
}
fs.writeFileSync(mf, x);
console.log('AndroidManifest.xml listo (host: ' + host + ')');
