package com.miui.packageinstaller;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.miui.packageinstaller.utils.ApplicationLabelUtils;
import com.miui.packageinstaller.utils.FileSizeUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;


/**
 * @author dadaewq
 */
public abstract class AbstractInstallActivity extends Activity {
    private static final String ILLEGALPKGNAME = "Fy^&IllegalPN*@!128`+=：:,.[";
    private final String[] permissions = new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
    private final String nl = System.getProperty("line.separator");
    boolean istemp = false;
    String[] apkinfo;
    private String[] source;

    private Uri uri;
    private boolean needrequest;
    private SharedPreferences sourceSp;
    private SharedPreferences.Editor editor;
    private AlertDialog alertDialog;
    private String cachePath;


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String action = getIntent().getAction();
        if (Intent.ACTION_VIEW.equals(action) || Intent.ACTION_INSTALL_PACKAGE.equals(action)) {
            uri = getIntent().getData();

            assert uri != null;
            needrequest = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) && (ContentResolver.SCHEME_FILE.equals(uri.getScheme()));

            sourceSp = getSharedPreferences("allowsource", Context.MODE_PRIVATE);
            if (needrequest) {
                if (checkPermission()) {
                    needrequest = false;
                    initInstall();
                } else {
                    requestPermission();
                }
            } else {
                initInstall();
            }
        } else {
            showToast(getString(R.string.failed_prase) + " " + action);
            finish();
        }

    }

    private String[] getExistedVersion(String pkgname) {
        PackageManager pm = getPackageManager();
        ApplicationInfo applicationInfo = null;
        try {
            applicationInfo = pm.getApplicationInfo(pkgname, 0);
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (applicationInfo == null) {
            return null;
        } else {
            String apkPath = applicationInfo.sourceDir;
            PackageInfo pkgInfo = pm.getPackageArchiveInfo(apkPath, PackageManager.GET_ACTIVITIES);
            if (pkgInfo != null) {
                pkgInfo.applicationInfo.sourceDir = apkPath;
                pkgInfo.applicationInfo.publicSourceDir = apkPath;
                return new String[]{pkgInfo.versionName,
                        Build.VERSION.SDK_INT < 28 ? Integer.toString(pkgInfo.versionCode) : Long.toString(pkgInfo.getLongVersionCode())};
            } else {
                return null;
            }
        }

    }


    private void initInstall() {
        source = checkInstallSource();

        boolean allowsource = sourceSp.getBoolean(source[0], false);
        String apkPath = preInstall();
        cachePath = apkPath;
        if (apkPath == null) {
            showToast(getString(R.string.failed_prase));
            finish();
        } else if (allowsource) {
            startInstall(apkPath);
            finish();
        } else {
            String[] version = getExistedVersion(apkinfo[1]);

            StringBuilder alertDialogMessage = new StringBuilder();
            alertDialogMessage
                    .append(nl)
                    .append(
                            String.format(
                                    getString(R.string.message_name),
                                    apkinfo[0]
                            )
                    )
                    .append(nl)
                    .append(
                            String.format(
                                    getString(R.string.message_packagename),
                                    apkinfo[1]
                            )
                    )
                    .append(nl)
                    .append(
                            String.format(
                                    getString(R.string.message_version),
                                    apkinfo[2],
                                    apkinfo[3]
                            )
                    )
                    .append(nl);

            if (version != null) {
                alertDialogMessage.append(
                        String.format(
                                getString(R.string.message_version_existed),
                                version[0],
                                version[1]
                        )
                )
                        .append(nl);
            }

            alertDialogMessage
                    .append(
                            String.format(
                                    getString(R.string.message_size),
                                    apkinfo[4]
                            )
                    )
                    .append(nl);

            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle(getString(R.string.dialog_install_title));

            builder.setMessage(alertDialogMessage);
            View checkBoxView = View.inflate(this, R.layout.confirm_checkbox, null);
            builder.setView(checkBoxView);
            CheckBox checkBox = checkBoxView.findViewById(R.id.confirm_checkbox);
            checkBox.setText(String.format(getString(R.string.always_allow), source[1]));

            checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked) {
                    editor = sourceSp.edit();
                    editor.putBoolean(source[0], true);
                    editor.apply();
                } else {
                    editor = sourceSp.edit();
                    editor.putBoolean(source[0], false);
                    editor.apply();
                }
            });
            builder.setPositiveButton(android.R.string.yes, (dialog, which) -> {
                cachePath = null;
                startInstall(apkPath);
                finish();
            });
            builder.setNegativeButton(android.R.string.no, (dialog, which) -> finish());


            builder.setCancelable(false);
            alertDialog = builder.show();
            alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextSize(20);
            alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextSize(20);
        }

    }

    private String[] checkInstallSource() {

        final String fromPkgLabel;
        final String fromPkgName;
        Uri referrerUri = getReferrer();
        if (referrerUri == null || !"android-app".equals(referrerUri.getScheme())) {
            fromPkgLabel = ILLEGALPKGNAME;
            fromPkgName = ILLEGALPKGNAME;
        } else {
            fromPkgName = referrerUri.getEncodedSchemeSpecificPart().substring(2);
            String refererPackageLabel =
                    ApplicationLabelUtils.getApplicationLabel(this, null, null, fromPkgName);
            if ("Uninstalled".equals(refererPackageLabel)) {
                fromPkgLabel = ILLEGALPKGNAME;
            } else {
                fromPkgLabel = refererPackageLabel;
            }
        }

        return new String[]{fromPkgName, fromPkgLabel};
    }

    @Override
    public void onResume() {
        super.onResume();
        if (needrequest) {
            if (checkPermission()) {
                initInstall();
            } else {
                requestPermission();
            }
        }
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (istemp && (cachePath != null)) {
            deleteSingleFile(new File(cachePath));
        }
        if (alertDialog != null) {
            alertDialog.dismiss();
        }

    }


    private String preInstall() {
        String apkPath = null;
        if (uri != null) {
            Log.e("--getData--", uri + "");
            if (ContentResolver.SCHEME_FILE.equals(uri.getScheme())) {
                apkPath = uri.getPath();
            } else if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
                apkPath = createApkFromUri(this);
            } else {
                showToast(getString(R.string.failed_prase));
                finish();
            }

            apkinfo = getApkPkgInfo(apkPath);
            if (apkinfo != null) {
                return apkPath;
            } else {

                if (ContentResolver.SCHEME_FILE.equals(uri.getScheme()) && apkPath != null && getExistedVersion("moe.shizuku.redirectstorage") != null) {
                    return checkSR(apkPath);
                } else {
                    return null;
                }

            }
        } else {
            finish();
            return null;
        }
    }

    private String checkSR(@NonNull String apkPath) {
        String prefix = Environment.getExternalStorageDirectory().getAbsolutePath();
        if (apkPath.startsWith(prefix)) {
            StringBuilder stringBuilder = new StringBuilder(apkPath);
            @SuppressLint("SdCardPath") String toInsert = "/Android/data/" + source[0] + "/sdcard";
            stringBuilder.insert(prefix.length(), toInsert);
            apkPath = stringBuilder.toString();
            Log.e("SRnewpath", apkPath);
            apkinfo = getApkPkgInfo(apkPath);
            if (apkinfo != null) {
                return apkPath;
            } else {
                return null;
            }
        } else {
            return null;
        }
    }

    protected abstract void startInstall(String apkPath);


    private void requestPermission() {
        ActivityCompat.requestPermissions(this, permissions, 0x233);
    }

    private boolean checkPermission() {
        int permissionRead = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE);
        return (permissionRead == 0);
    }


    void showToast(final String text) {
        runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_SHORT).show());
    }

    private String createApkFromUri(Context context) {
        istemp = true;
        File tempFile = new File(context.getExternalCacheDir(), System.currentTimeMillis() + ".apk");
        try {
            InputStream is = context.getContentResolver().openInputStream(uri);
            if (is != null) {
                OutputStream fos = new FileOutputStream(tempFile);
                byte[] buf = new byte[4096 * 1024];
                int ret;
                while ((ret = is.read(buf)) != -1) {
                    fos.write(buf, 0, ret);
                    fos.flush();
                }
                fos.close();
                is.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return tempFile.getAbsolutePath();
    }


    void deleteSingleFile(File file) {
        if (file.exists() && file.isFile()) {
            if (file.delete()) {
                Log.e("-DELETE-", "==>" + file.getAbsolutePath() + " OK！");
            } else {
                finish();
            }
        } else {
            finish();
        }
    }

    private String[] getApkPkgInfo(String apkPath) {
        if (apkPath == null) {
            return null;
        } else {
            PackageManager pm = this.getPackageManager();
            PackageInfo pkgInfo = pm.getPackageArchiveInfo(apkPath, PackageManager.GET_ACTIVITIES);

            if (pkgInfo != null) {
                pkgInfo.applicationInfo.sourceDir = apkPath;
                pkgInfo.applicationInfo.publicSourceDir = apkPath;

                return new String[]{pm.getApplicationLabel(pkgInfo.applicationInfo).toString(), pkgInfo.packageName, pkgInfo.versionName,
                        Build.VERSION.SDK_INT < 28 ?
                                Integer.toString(pkgInfo.versionCode) : Long.toString(pkgInfo.getLongVersionCode()), FileSizeUtils.getAutoFolderOrFileSize(apkPath)};
            }
            return null;
        }
    }

}
