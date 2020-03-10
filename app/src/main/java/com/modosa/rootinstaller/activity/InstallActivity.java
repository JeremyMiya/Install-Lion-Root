package com.modosa.rootinstaller.activity;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.modosa.rootinstaller.R;
import com.modosa.rootinstaller.util.OpUtil;
import com.modosa.rootinstaller.util.ShellUtil;
import com.modosa.rootinstaller.util.Utils;
import com.modosa.rootinstaller.util.apksource.ApkSource;
import com.modosa.rootinstaller.util.installer.ApkSourceBuilder;
import com.modosa.rootinstaller.util.installer.SAIPackageInstaller;
import com.modosa.rootinstaller.util.installer.rooted.RootedSAIPackageInstaller;
import com.modosa.rootinstaller.util.shell.Shell;
import com.modosa.rootinstaller.util.shell.SuShell;

import java.io.File;
import java.util.ArrayList;
import java.util.List;


/**
 * @author dadaewq
 */
public class InstallActivity extends AbstractInstallActivity implements SAIPackageInstaller.InstallationStatusListener {

    private long mOngoingSessionId;
    private String pkgName;
    private String apkPath;

    @Override
    public void startInstall(String apkPath) {
        Log.d("Start install", apkPath + "");
        if (apkPath != null) {
            this.apkPath = apkPath;
            apkFile = new File(apkPath);

            ArrayList<File> files = new ArrayList<>();
            files.add(apkFile);
            if (OpUtil.checkRoot()) {
                new Thread(() -> {
                    showToast0(String.format(getString(R.string.start_install), apkinfo[0]));
                    try {
                        installPackages(files);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                ).start();
            } else {
                deleteCache();
                Toast.makeText(this, R.string.installer_error_root_no_root, Toast.LENGTH_SHORT).show();
            }

        } else {
            showToast0(getString(R.string.tip_failed_read));
            finish();
        }
    }

    @Override
    protected void startUninstall(String pkgName) {
        this.pkgName = pkgName;
        new UninstallApkTask().start();
    }


    private void installPackages(List<File> apkFiles) {
        Context mContext = getApplication();
        SAIPackageInstaller mInstaller = RootedSAIPackageInstaller.getInstance(mContext);
        mInstaller.addStatusListener(this);
        ApkSource apkSource = new ApkSourceBuilder()
                .fromApkFiles(apkFiles)
                .build();

        mOngoingSessionId = mInstaller.createInstallationSession(apkSource);
        mInstaller.startInstallationSession(mOngoingSessionId);
    }


    @Override
    public void onStatusChanged(long installationId, SAIPackageInstaller.InstallationStatus status, @Nullable String packageNameOrErrorDescription) {
        if (installationId != mOngoingSessionId) {
            deleteCache();
            return;
        }
        Log.d("status", status + "");

        switch (status) {
            case QUEUED:
            case INSTALLING:
                break;
            case INSTALLATION_SUCCEED:
                deleteCache();
                showToast0(String.format(getString(R.string.tip_success_install), apkinfo[0]));
                showNotification(true);
                finish();
                break;
            case INSTALLATION_FAILED:

                if (packageNameOrErrorDescription != null) {
                    if (packageNameOrErrorDescription.contains(getString(R.string.installer_error_root_no_root))) {
                        installByShellUtils();
                    } else {
                        showNotification(false);
                        deleteCache();
                        copyErr(packageNameOrErrorDescription);
                        String err = packageNameOrErrorDescription.substring(packageNameOrErrorDescription.indexOf("Err:") + 4);
                        showToast1(String.format(getString(R.string.tip_failed_install_witherror), apkinfo[0], err));
                    }
                } else {
                    showNotification(false);
                    deleteCache();
                    copyErr(getString(R.string.unknown));
                    showToast1(String.format(getString(R.string.tip_failed_install_witherror), apkinfo[0], ""));
                }
                finish();
                break;
            default:
                finish();
        }
    }

    private void showNotification(boolean success) {
        if (show_notification) {
            Log.e("packagename", apkinfo[1]);
            Intent intent = new Intent()
                    .setComponent(new ComponentName(this, NotifyActivity.class))
                    .putExtra("packageName", apkinfo[1])
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (success) {
                intent.putExtra("channelId", "1")
                        .putExtra("channelName", getString(R.string.tip_success_install))
                        .putExtra("contentTitle", String.format(getString(R.string.tip_success_install), apkinfo[0]));
            } else {
                intent.putExtra("channelId", "4")
                        .putExtra("channelName", getString(R.string.channalname_fail))
                        .putExtra("realPath", apkPath)
                        .putExtra("contentTitle", String.format(getString(R.string.tip_failed_install), apkinfo[0]));
            }
            startActivity(intent);
        }


    }

    private void installByShellUtils() {
        String installcommand = "pm install -r -d --user 0 -i " + getPackageName() + " \"" + apkPath + "\"";
        String[] resultSElinux = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            resultSElinux = ShellUtil.execWithRoot("setenforce 0");
        }
        Log.e("installcommand", installcommand);
        String[] result = ShellUtil.execWithRoot(installcommand);


        if ("0".equals(result[3])) {
            deleteCache();
            showNotification(true);
            showToast0(String.format(getString(R.string.tip_success_install), apkinfo[0]));
        } else {
            showNotification(false);
            deleteCache();
            String saiVersion = "???";
            try {
                saiVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (PackageManager.NameNotFoundException ignore) {
            }
            StringBuilder err = new StringBuilder(String.format("%s: %s %s | %s | Android %s | Install Lion-Root %s\n\n", getString(R.string.installer_device), Build.BRAND, Build.MODEL, Utils.isMiui() ? "MIUI" : "Not MIUI", Build.VERSION.RELEASE, saiVersion))
                    .append(String.format("Command: %s\nExit code: %s\nOut:\n%s\n=============\nErr:\n%s", result[2], result[3], result[0], result[1]));

            if (resultSElinux != null && !"0".equals(resultSElinux[3])) {
                copyErr(err.append("\n") + resultSElinux[1]);
                showToast1(String.format(getString(R.string.tip_failed_install_witherror), apkinfo[0], resultSElinux[1] + "\n" + result[1]));
            } else {
                copyErr(err.toString());
                showToast1(String.format(getString(R.string.tip_failed_install_witherror), apkinfo[0], result[1]));
            }

        }
    }


    private class UninstallApkTask extends Thread {
        @Override
        public void run() {
            super.run();
            Log.d("Start uninstall", pkgName);
            Looper.prepare();
            if (!SuShell.getInstance().isAvailable()) {
                copyErr(String.format("%s\n\n%s\n%s", getString(R.string.dialog_uninstall_title), alertDialogMessage, getString(R.string.installer_error_root_no_root)));
                showToast1(String.format(getString(R.string.tip_failed_uninstall_witherror), packageLable, getString(R.string.installer_error_root_no_root)));
            } else {
                Shell.Result uninstallationResult = SuShell.getInstance().exec(new Shell.Command("pm", "uninstall", pkgName));
                if (0 == uninstallationResult.exitCode) {
                    showToast0(String.format(getString(R.string.tip_success_uninstall), packageLable));
                } else {
                    String saiVersion = "???";
                    try {
                        saiVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                    } catch (PackageManager.NameNotFoundException ignore) {
                    }
                    String info = String.format("%s: %s %s | %s | Android %s | Install Lion-Root %s\n\n", getString(R.string.installer_device), Build.BRAND, Build.MODEL, Utils.isMiui() ? "MIUI" : "Not MIUI", Build.VERSION.RELEASE, saiVersion);
                    copyErr(info + uninstallationResult.toString());
                    showToast1(String.format(getString(R.string.tip_failed_uninstall_witherror), packageLable, uninstallationResult.err));
                }
            }
            Looper.loop();
        }
    }
}
