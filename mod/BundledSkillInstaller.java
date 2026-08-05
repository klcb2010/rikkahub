//
// Decompiled by Jadx - 449ms
//
package me.rerere.rikkahub;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public final class BundledSkillInstaller {
    public static void copyDir(AssetManager assetManager, String str, File file) {
        String[] list = assetManager.list(str);
        if (list != null && list.length != 0) {
            if (!file.exists()) {
                file.mkdirs();
            }
            for (String str2 : list) {
                copyDir(assetManager, str + "/" + str2, new File(file, str2));
            }
            return;
        }
        if (file.exists()) {
            return;
        }
        File parentFile = file.getParentFile();
        if (parentFile != null) {
            parentFile.mkdirs();
        }
        InputStream open = assetManager.open(str);
        FileOutputStream fileOutputStream = new FileOutputStream(file);
        byte[] bArr = new byte[8192];
        while (true) {
            int read = open.read(bArr);
            if (read == -1) {
                fileOutputStream.flush();
                fileOutputStream.close();
                open.close();
                return;
            }
            fileOutputStream.write(bArr, 0, read);
        }
    }

    public static void install(Context context) {
        try {
            SharedPreferences sharedPreferences = context.getSharedPreferences("bundled_skills", 0);
            if (sharedPreferences.getInt("installed_version", 0) < 1) {
                copyDir(context.getAssets(), "skills", new File(context.getFilesDir(), "skills"));
                sharedPreferences.edit().putInt("installed_version", 1).apply();
            }
        } catch (Throwable th) {
            Log.e("BundledSkillInstaller", "install failed", th);
        }
    }
}
