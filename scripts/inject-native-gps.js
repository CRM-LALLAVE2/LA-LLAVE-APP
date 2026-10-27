/**
 * Inyecta el plugin nativo GpsUploader + servicio de subida a Supabase
 * en el proyecto Android generado por `npx cap add android`.
 *
 * Uso (después de cap add android):
 *   node scripts/inject-native-gps.js
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const ANDROID = path.join(ROOT, 'android');
const MANIFEST = path.join(ANDROID, 'app/src/main/AndroidManifest.xml');
const APP_BUILD = path.join(ANDROID, 'app/build.gradle');
const APP_BUILD_KTS = path.join(ANDROID, 'app/build.gradle.kts');
const JAVA_SRC = path.join(ANDROID, 'app/src/main/java');
const PLUGIN_SRC = path.join(__dirname, 'native-gps/java/mx/lallave/crm/plugins');

function die(msg) {
  console.error(msg);
  process.exit(1);
}

if (!fs.existsSync(MANIFEST)) {
  die('Falta android/. Ejecuta primero: npx cap add android');
}

// 1) Copiar clases Java del plugin
const destDir = path.join(JAVA_SRC, 'mx/lallave/crm/plugins');
fs.mkdirSync(destDir, { recursive: true });
for (const f of fs.readdirSync(PLUGIN_SRC)) {
  if (!f.endsWith('.java')) continue;
  fs.copyFileSync(path.join(PLUGIN_SRC, f), path.join(destDir, f));
  console.log('Copiado', f);
}

// 2) Dependencia Play Services Location
function ensurePlayServices(gradlePath) {
  if (!fs.existsSync(gradlePath)) return false;
  let g = fs.readFileSync(gradlePath, 'utf8');
  if (g.includes('play-services-location')) {
    console.log('play-services-location ya esta en', path.basename(gradlePath));
    return true;
  }
  if (g.includes('dependencies {')) {
    g = g.replace(
      /dependencies\s*\{/,
      'dependencies {\n    implementation "com.google.android.gms:play-services-location:21.3.0"'
    );
    fs.writeFileSync(gradlePath, g);
    console.log('Anadido play-services-location a', path.basename(gradlePath));
    return true;
  }
  return false;
}

if (!ensurePlayServices(APP_BUILD) && !ensurePlayServices(APP_BUILD_KTS)) {
  console.warn('No se pudo anadir play-services-location automaticamente');
}

// 3) Manifest: permisos + service
let mf = fs.readFileSync(MANIFEST, 'utf8');
const extraPerms = [
  'ACCESS_COARSE_LOCATION',
  'ACCESS_FINE_LOCATION',
  'ACCESS_BACKGROUND_LOCATION',
  'FOREGROUND_SERVICE',
  'FOREGROUND_SERVICE_LOCATION',
  'POST_NOTIFICATIONS',
  'WAKE_LOCK',
  'RECEIVE_SMS',
  'SEND_SMS',
  'INTERNET'
];

extraPerms.forEach(function (p) {
  if (!mf.includes('android.permission.' + p)) {
    mf = mf.replace(
      '<application',
      '<uses-permission android:name="android.permission.' + p + '"/>\n    <application'
    );
  }
});

if (!mf.includes('GpsUploadService')) {
  const svc =
    '\n        <service\n' +
    '            android:name="mx.lallave.crm.plugins.GpsUploadService"\n' +
    '            android:exported="false"\n' +
    '            android:foregroundServiceType="location" />';
  mf = mf.replace('</application>', svc + '\n    </application>');
  console.log('Registrado GpsUploadService en Manifest');
}

// Keep receiver registration consistent with patch-android.js and replace stale entries.
const smsReceiver =
  '\n        <receiver\n' +
  '            android:name="mx.lallave.crm.plugins.SmsLocateReceiver"\n' +
  '            android:exported="true">\n' +
  '            <intent-filter android:priority="999">\n' +
  '                <action android:name="android.provider.Telephony.SMS_RECEIVED" />\n' +
  '            </intent-filter>\n' +
  '        </receiver>';
const smsReceiverPattern = /<receiver\b(?=[^>]*android:name="mx\.lallave\.crm\.plugins\.SmsLocateReceiver")[\s\S]*?<\/receiver>/;
if (smsReceiverPattern.test(mf)) {
  mf = mf.replace(smsReceiverPattern, smsReceiver.trim());
} else {
  mf = mf.replace('</application>', smsReceiver + '\n    </application>');
}
console.log('SmsLocateReceiver verificado en Manifest');
if (!mf.includes('android.hardware.telephony')) {
  mf = mf.replace('<application', '<uses-feature android:name="android.hardware.telephony" android:required="false"/>\n    <application');
}

fs.writeFileSync(MANIFEST, mf);

// 4) Registrar plugin en MainActivity ANTES de super.onCreate
function patchMainActivity(file) {
  if (!fs.existsSync(file)) return false;
  let src = fs.readFileSync(file, 'utf8');

  if (src.includes('GpsUploaderPlugin')) {
    if (/super\.onCreate\([^;]*\);\s*\n\s*registerPlugin\(GpsUploaderPlugin/.test(src)) {
      src = src.replace(
        /super\.onCreate\(([^)]*)\);\s*\n\s*registerPlugin\(GpsUploaderPlugin\.class\);/,
        'registerPlugin(GpsUploaderPlugin.class);\n        super.onCreate($1);'
      );
      fs.writeFileSync(file, src);
      console.log('Reordenado GpsUploaderPlugin ANTES de super.onCreate');
      return true;
    }
    console.log('GpsUploaderPlugin ya registrado en', path.basename(file));
    return true;
  }

  if (file.endsWith('.java')) {
    if (!src.includes('import mx.lallave.crm.plugins.GpsUploaderPlugin')) {
      src = src.replace(
        /package [^;]+;/,
        function (m) {
          return m + '\n\nimport mx.lallave.crm.plugins.GpsUploaderPlugin;';
        }
      );
    }
    if (src.includes('super.onCreate')) {
      src = src.replace(
        /super\.onCreate\(([^)]*)\);/,
        'registerPlugin(GpsUploaderPlugin.class);\n        super.onCreate($1);'
      );
    } else if (/void\s+onCreate\s*\(/.test(src)) {
      src = src.replace(
        /(void\s+onCreate\s*\([^)]*\)\s*\{)/,
        '$1\n        registerPlugin(GpsUploaderPlugin.class);'
      );
    } else {
      src = src.replace(
        /public class MainActivity extends BridgeActivity\s*\{/,
        'public class MainActivity extends BridgeActivity {\n' +
          '    @Override\n' +
          '    public void onCreate(android.os.Bundle savedInstanceState) {\n' +
          '        registerPlugin(GpsUploaderPlugin.class);\n' +
          '        super.onCreate(savedInstanceState);\n' +
          '    }'
      );
    }
  } else if (file.endsWith('.kt')) {
    if (!src.includes('import mx.lallave.crm.plugins.GpsUploaderPlugin')) {
      src = src.replace(
        /package [^\n]+/,
        function (m) {
          return m + '\n\nimport mx.lallave.crm.plugins.GpsUploaderPlugin';
        }
      );
    }
    if (src.includes('super.onCreate')) {
      src = src.replace(
        /super\.onCreate\(([^)]*)\)/,
        'registerPlugin(GpsUploaderPlugin::class.java)\n        super.onCreate($1)'
      );
    } else {
      return false;
    }
  }

  fs.writeFileSync(file, src);
  console.log('Registrado plugin (antes de super.onCreate) en', file);
  return true;
}

function findMainActivity(dir) {
  if (!fs.existsSync(dir)) return null;
  const entries = fs.readdirSync(dir, { withFileTypes: true });
  for (var i = 0; i < entries.length; i++) {
    var e = entries[i];
    var full = path.join(dir, e.name);
    if (e.isDirectory()) {
      var f = findMainActivity(full);
      if (f) return f;
    } else if (e.name === 'MainActivity.java' || e.name === 'MainActivity.kt') {
      return full;
    }
  }
  return null;
}

var main = findMainActivity(JAVA_SRC);
if (main) {
  if (!patchMainActivity(main)) {
    console.warn('No se pudo parchear MainActivity automaticamente:', main);
  }
} else {
  console.warn('MainActivity no encontrado; registra GpsUploaderPlugin a mano.');
}

console.log('inject-native-gps.js listo');
