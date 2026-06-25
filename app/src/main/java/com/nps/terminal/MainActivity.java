package com.nps.terminal;

  import android.annotation.SuppressLint;
  import android.app.Activity;
  import android.content.ActivityNotFoundException;
  import android.content.ClipData;
  import android.content.ContentValues;
  import android.content.Intent;
  import android.net.Uri;
  import android.os.Build;
  import android.os.Bundle;
  import android.os.Environment;
  import android.provider.MediaStore;
  import android.util.Base64;
  import android.webkit.JavascriptInterface;
  import android.webkit.ValueCallback;
  import android.webkit.WebChromeClient;
  import android.webkit.WebSettings;
  import android.webkit.WebView;
  import android.webkit.WebViewClient;
  import android.Manifest;
  import android.content.pm.PackageManager;
  import android.widget.Toast;
  import java.io.OutputStream;
  import java.io.File;
  import java.io.FileOutputStream;

  public class MainActivity extends Activity {

      private static final int REQUEST_SELECT_FILE = 100;
      private static final int REQUEST_PERMISSIONS  = 101;

      private WebView webView;
      private ValueCallback<Uri[]> mFilePathCallback;

      @SuppressLint({"SetJavaScriptEnabled","AddJavascriptInterface"})
      @Override
      protected void onCreate(Bundle savedInstanceState) {
          super.onCreate(savedInstanceState);

          webView = new WebView(this);
          setContentView(webView);

          WebSettings s = webView.getSettings();
          s.setJavaScriptEnabled(true);
          s.setDomStorageEnabled(true);
          s.setDatabaseEnabled(true);
          s.setAllowFileAccess(true);
          s.setAllowContentAccess(true);
          s.setAllowFileAccessFromFileURLs(true);
          s.setAllowUniversalAccessFromFileURLs(true);
          s.setMediaPlaybackRequiresUserGesture(false);
          s.setSupportZoom(false);
          s.setBuiltInZoomControls(false);
          s.setLoadWithOverviewMode(true);
          s.setUseWideViewPort(true);
          s.setCacheMode(WebSettings.LOAD_DEFAULT);

          // Bridge Java ↔ JavaScript pour sauvegarder dans les Téléchargements
          webView.addJavascriptInterface(new VaultBridge(), "AndroidVault");
          webView.setWebViewClient(new WebViewClient());

          webView.setWebChromeClient(new WebChromeClient() {
              @Override
              public boolean onShowFileChooser(WebView view,
                                               ValueCallback<Uri[]> filePathCallback,
                                               FileChooserParams params) {
                  // Annuler le callback précédent si encore ouvert
                  if (mFilePathCallback != null) {
                      mFilePathCallback.onReceiveValue(null);
                      mFilePathCallback = null;
                  }
                  mFilePathCallback = filePathCallback;

                  // Demander les permissions si nécessaire
                  if (!hasStoragePerm()) {
                      requestStoragePerm();
                      mFilePathCallback.onReceiveValue(null);
                      mFilePathCallback = null;
                      Toast.makeText(MainActivity.this,
                          "Autorisation requise — réessayez après avoir accepté", Toast.LENGTH_LONG).show();
                      return false;
                  }

                  // Construire l'intent de sélection de fichier
                  Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                  intent.addCategory(Intent.CATEGORY_OPENABLE);
                  intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);

                  String[] acceptTypes = params.getAcceptTypes();
                  String mimeType = (acceptTypes != null && acceptTypes.length > 0 && !acceptTypes[0].isEmpty())
                      ? acceptTypes[0] : "*/*";
                  intent.setType(mimeType);

                  try {
                      startActivityForResult(
                          Intent.createChooser(intent, "Choisir un fichier"),
                          REQUEST_SELECT_FILE);
                  } catch (ActivityNotFoundException e) {
                      mFilePathCallback.onReceiveValue(null);
                      mFilePathCallback = null;
                      Toast.makeText(MainActivity.this,
                          "Aucun gestionnaire de fichiers installé", Toast.LENGTH_SHORT).show();
                      return false;
                  }
                  return true;
              }
          });

          webView.loadUrl("file:///android_asset/www/index.html");

          // Demander les permissions au démarrage
          if (!hasStoragePerm()) requestStoragePerm();
      }

      @Override
      protected void onActivityResult(int requestCode, int resultCode, Intent data) {
          super.onActivityResult(requestCode, resultCode, data);
          if (requestCode != REQUEST_SELECT_FILE) return;
          if (mFilePathCallback == null) return;

          Uri[] results = null;
          if (resultCode == RESULT_OK && data != null) {
              ClipData clip = data.getClipData();
              if (clip != null) {
                  results = new Uri[clip.getItemCount()];
                  for (int i = 0; i < clip.getItemCount(); i++)
                      results[i] = clip.getItemAt(i).getUri();
              } else if (data.getDataString() != null) {
                  results = new Uri[]{ Uri.parse(data.getDataString()) };
              }
          }
          mFilePathCallback.onReceiveValue(results);
          mFilePathCallback = null;
      }

      // ── Permissions ────────────────────────────────────────────────────
      private boolean hasStoragePerm() {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
              return checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)  == PackageManager.PERMISSION_GRANTED
                  || checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO)   == PackageManager.PERMISSION_GRANTED
                  || checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO)   == PackageManager.PERMISSION_GRANTED;
          }
          return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
      }

      private void requestStoragePerm() {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
              requestPermissions(new String[]{
                  Manifest.permission.READ_MEDIA_IMAGES,
                  Manifest.permission.READ_MEDIA_VIDEO,
                  Manifest.permission.READ_MEDIA_AUDIO
              }, REQUEST_PERMISSIONS);
          } else {
              requestPermissions(new String[]{
                  Manifest.permission.READ_EXTERNAL_STORAGE,
                  Manifest.permission.WRITE_EXTERNAL_STORAGE
              }, REQUEST_PERMISSIONS);
          }
      }

      @Override
      public void onRequestPermissionsResult(int code, String[] perms, int[] grants) {
          super.onRequestPermissionsResult(code, perms, grants);
          // Rechargement léger pour mettre à jour l'état des permissions dans le HTML
          if (code == REQUEST_PERMISSIONS) webView.reload();
      }

      @Override
      public void onBackPressed() {
          if (webView.canGoBack()) webView.goBack();
          else super.onBackPressed();
      }

      // ── Pont JavaScript → Android ──────────────────────────────────────
      class VaultBridge {

          /** Appelé depuis JS : AndroidVault.saveToDownloads(dataUrl, filename, mimeType) */
          @JavascriptInterface
          public void saveToDownloads(final String dataUrl, final String filename, final String mimeType) {
              runOnUiThread(() -> {
                  try {
                      // Décoder le dataURL base64
                      String b64 = dataUrl.substring(dataUrl.indexOf(',') + 1);
                      byte[] bytes = Base64.decode(b64, Base64.DEFAULT);

                      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                          // Android 10+ → MediaStore Downloads
                          ContentValues cv = new ContentValues();
                          cv.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                          cv.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                          cv.put(MediaStore.Downloads.IS_PENDING, 1);
                          Uri col = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                          Uri uri = getContentResolver().insert(col, cv);
                          if (uri != null) {
                              try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                                  if (os != null) os.write(bytes);
                              }
                              cv.clear();
                              cv.put(MediaStore.Downloads.IS_PENDING, 0);
                              getContentResolver().update(uri, cv, null, null);
                          }
                      } else {
                          // Android 9 et inférieur → dossier Downloads direct
                          File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                          if (!dir.exists()) dir.mkdirs();
                          File f = uniqueFile(dir, filename);
                          try (FileOutputStream fos = new FileOutputStream(f)) { fos.write(bytes); }
                      }

                      Toast.makeText(MainActivity.this,
                          "✅ " + filename + " enregistré dans Téléchargements", Toast.LENGTH_LONG).show();

                  } catch (Exception e) {
                      Toast.makeText(MainActivity.this,
                          "❌ Erreur : " + e.getMessage(), Toast.LENGTH_LONG).show();
                  }
              });
          }

          /** Permet au JS de savoir qu'il est dans l'app Android */
          @JavascriptInterface
          public boolean isAndroid() { return true; }
      }

      private File uniqueFile(File dir, String name) {
          File f = new File(dir, name);
          if (!f.exists()) return f;
          String base = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
          String ext  = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";
          int n = 1;
          while (f.exists()) f = new File(dir, base + "(" + n++ + ")" + ext);
          return f;
      }
  }
  