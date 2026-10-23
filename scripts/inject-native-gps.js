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

// 2) Dependencia Play Services Location en app/build.gradle
function ensurePlayServices(gradlePath) {
  if (!fs.existsSync(gradlePath)) return false;
  let g = fs.readFileSync(gradlePath, 'utf8');
  if (g.includes('play-services-location')) {
    console.log('play-services-location ya está en', path.basename(gradlePath));
    return true;
  }
  if (g.includes('dependencies {')) {
    g = g.replace(
      /dependencies\s*\{/,
      'dependencies {\n    implementation "com.google.android.gms:play-services-location:21.3.0"'
    );
    fs.writeFileSync(gradlePath, g);
    console.log('Añadido play-services-location a', path.basename(gradlePath));
    return true;
  }
  return false;
}
if (!ensurePlayServices(APP_BUILD) && !ensurePlayServices(APP_BUILD_KTS)) {
  console.warn('No se pudo añadir play-services-location automáticamente; añádelo a app/build.gradle');
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
  'INTERNET'
];
extraPerms.forEach((p) => {
  if (!mf.includes('android.permission.' + p)) {
    mf = mf.replace(
      '<application',
      `<uses-permission android:name="android.permission.${p}"/>\n    <application`
    );
  }
});

if (!mf.includes('GpsUploadService')) {
  const svc = `
        <service
            android:name="mx.lallave.crm.plugins.GpsUploadService"
            android:exported="false"
            android:foregroundServiceType="location" />`;
  mf = mf.replace('</application>', svc + '\n    </application>');
  console.log('Registrado GpsUploadService en Manifest');
}
fs.writeFileSync(MANIFEST, mf);

// 4) Registrar plugin en MainActivity (Java o Kotlin)
function patchMainActivity(file) {
  if (!fs.existsSync(file)) return false;
  let src = fs.readFileSync(file, 'utf8');
  if (src.includes('GpsUploaderPlugin')) {
    console.log('GpsUploaderPlugin ya registrado en', path.basename(file));
    return true;
  }
  if (file.endsWith('.java')) {
    if (!src.includes('import mx.lallave.crm.plugins.GpsUploaderPlugin')) {
      src = src.replace(
        /package [^;]+;/,
        (m) => m + '\n\nimport mx.lallave.crm.plugins.GpsUploaderPlugin;'
      );
    }
    if (src.includes('registerPlugin')) {
      src = src.replace(
        /registerPlugin\([^)]+\);/,
        (m) => m + '\n        registerPlugin(GpsUploaderPlugin.class);'
      );
    } else if (src.includes('super.onCreate')) {
      src = src.replace(
        /super\.onCreate\([^)]*\);/,
        (m) => m + '\n        registerPlugin(GpsUploaderPlugin.class);'
      );
    } else {
      return false;
    }
  } else if (file.endsWith('.kt')) {
    if (!src.includes('import mx.lallave.crm.plugins.GpsUploaderPlugin')) {
      src = src.replace(
        /package [^\n]+/,
        (m) => m + '\n\nimport mx.lallave.crm.plugins.GpsUploaderPlugin'
      );
    }
    if (src.includes('registerPlugin')) {
      src = src.replace(
        /registerPlugin\([^)]+\)/,
        (m) => m + '\n        registerPlugin(GpsUploaderPlugin::class.java)'
      );
    } else if (src.includes('super.onCreate')) {
      src = src.replace(
        /super\.onCreate\([^)]*\)/,
        (m) => m + '\n        registerPlugin(GpsUploaderPlugin::class.java)'
      );
    } else {
      return false;
    }
  }
  fs.writeFileSync(file, src);
  console.log('Registrado plugin en', file);
  return true;
}

function findMainActivity(dir) {
  if (!fs.existsSync(dir)) return null;
  const entries = fs.readdirSync(dir, { withFileTypes: true });
  for (const e of entries) {
    const full = path.join(dir, e.name);
    if (e.isDirectory()) {
      const f = findMainActivity(full);
      if (f) return f;
    } else if (e.name === 'MainActivity.java' || e.name === 'MainActivity.kt') {
      return full;
    }
  }
  return null;
}

const main = findMainActivity(JAVA_SRC);
if (main) {
  if (!patchMainActivity(main)) {
    console.warn('No se pudo parchear MainActivity automáticamente:', main);
  }
} else {
  console.warn('MainActivity no encontrado; registra GpsUploaderPlugin a mano.');
}

console.log('inject-native-gps.js listo');
