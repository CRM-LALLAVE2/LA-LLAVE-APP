// Parcha AndroidManifest.xml: permisos de ubicación, SMS, servicio y enlace del QR (intent-filter).
// Luego inyecta el uploader GPS nativo (GpsUploader).
// Uso: node scripts/patch-android.js   (después de `npx cap add android` o `npx cap sync`)
const fs = require('fs');
const path = require('path');

const cfg = JSON.parse(fs.readFileSync('capacitor.config.json', 'utf8'));
const mf = 'android/app/src/main/AndroidManifest.xml';

if (!fs.existsSync(mf)) {
  console.error('Falta android/. Corre primero: npx cap add android');
  process.exit(1);
}

const url = (cfg.server && cfg.server.url) || '';
if (!/^https:\/\//.test(url) || /CAMBIA-ESTO/.test(url)) {
  console.error('Edita capacitor.config.json: server.url con tu dominio real (https://...)');
  process.exit(1);
}

const host = new URL(url).host;
let x = fs.readFileSync(mf, 'utf8');

// 1. Permisos requeridos (incluyendo SMS)
const perms = [
  'ACCESS_COARSE_LOCATION',
  'ACCESS_FINE_LOCATION',
  'ACCESS_BACKGROUND_LOCATION',
  'FOREGROUND_SERVICE',
  'FOREGROUND_SERVICE_LOCATION',
  'POST_NOTIFICATIONS',
  'INTERNET',
  'WAKE_LOCK',
  'RECEIVE_SMS',       // <-- CRÍTICO PARA DETECTAR EL TEXTO
  'SEND_SMS',          // <-- CRÍTICO PARA RESPONDER LA UBICACIÓN
  'READ_PHONE_STATE'
];

perms.forEach((p) => {
  if (!x.includes('android.permission.' + p)) {
    x = x.replace(
      '<application',
      `    <uses-permission android:name="android.permission.${p}"/>\n    <application`
    );
  }
});

// 2. Registro del BroadcastReceiver para SmsLocateReceiver
const receiverTag = `
        <!-- Receptor de SMS para ubicar el vehiculo -->
        <receiver
            android:name="mx.lallave.crm.plugins.SmsLocateReceiver"
            android:exported="true">
            <intent-filter android:priority="999">
                <action android:name="android.provider.Telephony.SMS_RECEIVED"/>
            </intent-filter>
        </receiver>`;

if (!x.includes('mx.lallave.crm.plugins.SmsLocateReceiver')) {
  x = x.replace('</application>', `${receiverTag}\n    </application>`);
}

// 3. Intent-filter del Deep Link en MainActivity
if (!x.includes('android:host="' + host + '"')) {
  const filter = `
            <intent-filter>
                <action android:name="android.intent.action.VIEW"/>
                <category android:name="android.intent.category.DEFAULT"/>
                <category android:name="android.intent.category.BROWSABLE"/>
                <data android:scheme="https" android:host="${host}"/>
            </intent-filter>`;
  x = x.replace(
    /(<activity[\s\S]*?android:name="\.MainActivity"[\s\S]*?>)/,
    '$1' + filter
  );
}

fs.writeFileSync(mf, x);
console.log('AndroidManifest.xml listo (host: ' + host + ' + permisos SMS inyectados)');

// 4. Inyectar servicio nativo de subida GPS
const inject = path.join(__dirname, 'inject-native-gps.js');
if (fs.existsSync(inject)) {
  require(inject);
} else {
  console.warn('No está scripts/inject-native-gps.js — omite uploader nativo');
}
